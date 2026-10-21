package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.merge.MergeBufferUpdate
import nintype.engine.pipeline.Candidate
import nintype.engine.pipeline.MergeHostFactory
import nintype.engine.pipeline.MergeSlot
import nintype.engine.pipeline.NintypeEngine
import nintype.engine.touch.PlanarCoverage
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Json
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import nintype.engine.vectors.Values

/**
 * The pipeline's sitting touches (touch's [nintype.engine.touch.Mootouch]: point links and the
 * owner as indices / ptrs, TravKeyData as structs) as the search world's "smt<k>" objects, and the
 * two fields resetEx writes back into them (the stroke side +0xec, the drift points' double-letter
 * factor +0x16c).
 */
object SittingTouches {
    fun toWorld(sw: SearchWorld, sitting: List<nintype.engine.touch.Mootouch>): List<MemImage> =
        sitting.mapIndexed { k, t ->
            val name = "smt$k"
            val m = MemImage(name, 0x3b8)
            m.writeBytes(0, t.body.readBytes(0, 0x3b8))
            for ((o, l) in t.body.seqs) m.seqs[o] = l.toMutableList()
            sw.add(m)
            for ((v, off) in intArrayOf(0x244, 0x250, 0x25c).withIndex()) {
                val src = t.vecs[v]
                val pts = src.mapIndexed { j, p ->
                    val q = MemImage("$name.$v.$j", 1000)
                    q.writeBytes(0, p.readBytes(0, 1000))
                    for ((o, l) in p.seqs) q.seqs[o] = l.toMutableList()
                    sw.add(q)
                }
                for ((j, p) in src.withIndex()) {
                    val q = pts[j]
                    q.refs[8] = m
                    for (o in nintype.engine.touch.MootouchCodec.TP_LINKS) {
                        val i = p.i32(o)
                        if (i >= 0 && i < pts.size) q.refs[o] = pts[i] else { q.refs.remove(o); q.setI32(o, 0) }
                    }
                    val travs = p.seq(0x328).mapIndexed { i, j2 ->
                        val t2 = MemImage("${q.name}.t$i", 0x20)
                        t2.writeBytes(0, MemImage.decode(j2).readBytes(0, 0x20))
                        sw.add(t2)
                    }
                    sw.setRefs(q, 0x328, travs)
                    for (o in intArrayOf(0xa0, 0xac)) require(p.seq(o).isEmpty()) { "${q.name}: planar coverages on a sitting touch" }
                }
                sw.setRefs(m, off, pts)
            }
            m
        }

    fun writeBack(sw: SearchWorld, smt: List<MemImage>, sitting: List<nintype.engine.touch.Mootouch>) {
        for ((k, t) in sitting.withIndex()) {
            val m = smt[k]
            t.body.setU8(0xec, m.u8(0xec))
            for ((v, off) in intArrayOf(0x244, 0x250, 0x25c).withIndex()) {
                val pts = sw.refs(m, off)
                for ((j, p) in t.vecs[v].withIndex()) p.setI32(0x16c, pts[j].i32(0x16c))
            }
        }
    }
}

/**
 * The search side of the pipeline's MooMergeBuffer::update: MergeBufferUpdate.Host over the
 * Kotlin PathSearch (resetEx -> performSteps -> listResults -> topResult). Found by the engine by
 * reflection (pipeline MergeHosts). The PathSearch lives in its own image, copied back into the
 * Moopad (+0x29dc0) after every call; its containers (step statistics, the embedded
 * PlanarCoverages) persist from search to search, starting from the booted engine's
 * (re/out/e2e_search_boot.json).
 */
private object MergeMoopadUSF {
    fun usf1(mp: MemImage, n: Int): Int = nintype.engine.geometry.MoopadGeometry.hasUSF1(mp, n)
}

object PipelineMergeHost : (NintypeEngine) -> MergeHostFactory {
    const val PS_OFF = 0x29dc0
    /** The booted engine's PathSearch containers (re/out/e2e_search_boot.json), parsed once per path. */
    private val boots = java.util.concurrent.ConcurrentHashMap<String, JsonValue>()
    /** The factory that last searched each trie. The trie's coverage map is per-search state
     * keyed by PathSearch stamps (+0x120); a fresh engine's stamps restart, so a trie shared
     * with another factory is cleared before this one's first search (equivalent to the fresh
     * trie the binary would have). */
    private val trieOwner = java.util.Collections.synchronizedMap(java.util.WeakHashMap<nintype.engine.trie.CompactTrie, java.lang.ref.WeakReference<Factory>>())

    fun bootDump(engine: NintypeEngine): JsonValue {
        val f = engine.boot.searchBoot ?: java.io.File("../re/out/e2e_search_boot.json")
        return boots.computeIfAbsent(f.absolutePath) { Json.parse(f.readText()) }
    }

    override fun invoke(engine: NintypeEngine): MergeHostFactory = Factory(engine)

    class Factory(val engine: NintypeEngine) : MergeHostFactory {
        val w = MemWorld(emptyMap())
        val sw = SearchWorld(w)
        val ps: MemImage
        var search: SearchEngine? = null
        var prevStats: List<MemImage> = emptyList()
        var prevEnders: List<PlanarCoverage>
        var prevFull: PlanarCoverage
        var candidates: List<Candidate> = emptyList()
        var gen = 1L
        private val BOOT = bootDump(engine)
        val roenB28: Int = (BOOT["meta"]!!["roenB28"] as JsonValue.JNumber).value.toInt()

        init {
            w.images["mp"] = engine.pad
            ps = MemImage("ps", 0xec8)
            ps.writeBytes(0, engine.pad.readBytes(PS_OFF, 0xec8))
            ps.refs[0x110] = engine.pad
            w.images["ps"] = ps
            val bp = BOOT["objects"]!!["ps"]!!
            prevStats = bp["@0xea8:stepStats"]!!["\$vec"]!!.asArray().mapIndexed { k, j -> StepScoreStats.fromJson(j, ps, "ps.stepStats[$k]").also { it.refs[0] = ps } }
            prevEnders = listOf("@0x438:enderCoverage1", "@0x460:enderCoverage2", "@0x488:enderCoverage3").map { SearchWorld.pcOf(bp[it]!!) }
            prevFull = SearchWorld.pcOf(bp["@0x410:fullCoverage"]!!)
            gen = java.lang.Long.parseUnsignedLong(BOOT["meta"]!!["gen"]!!.asString().removePrefix("0x"), 16)
        }

        /** The ranked result vector (+0x638) as candidates (word, +0x74 score, +0xfc prior). */
        private fun currentCandidates(): List<Candidate> = search!!.st.resultList.map { pp ->
            Candidate(String(nintype.engine.pathscore.PredPathTrie.getWord(pp), Charsets.UTF_8), pp.f32(0x74), pp.f32(0xfc))
        }

        fun gatherEnv() = GatherEnv(engine.model, engine.boot.roenScale)

        private fun sync() = engine.pad.writeBytes(PS_OFF, ps.readBytes(0, 0xec8))

        fun resetEx(a: Int, b: Int) {
            val sitting = engine.touches.sitting
            for (k in w.images.keys.filter { it != "mp" && it != "ps" }.toList()) w.images.remove(k)
            val smt = SittingTouches.toWorld(sw, sitting)
            val prev = JsonValue.JObject(linkedMapOf(
                "@0xea8:stepStats" to Values.vec(prevStats.map { StepScoreStats.toJson(it) }),
                "@0x438:enderCoverage1" to SearchWorld.pcJson(prevEnders[0]), "@0x460:enderCoverage2" to SearchWorld.pcJson(prevEnders[1]),
                "@0x488:enderCoverage3" to SearchWorld.pcJson(prevEnders[2]), "@0x410:fullCoverage" to SearchWorld.pcJson(prevFull)))
            // the chain ranker (Moopad Xnl +0x31f28) seeds from the recent-word handles at +0x31f1c,
            // which the pipeline does not keep yet
            val chainXnl = nintype.engine.merge.Xnl.val_(engine.pad, 0x31f28)
            if (chainXnl == 1) engine.pend("Emkey::PathSearch::resetEx chain ranker (Moopad +0x31f1c recent words)")
            val env = ResetEnv(gatherEnv(), Bits.doubleToBits(engine.clock.gts), roenB28, chainXnl)
            val trie = engine.trie
            if (trieOwner[trie]?.get() !== this) { trie.coverage.clear(); trieOwner[trie] = java.lang.ref.WeakReference(this) }
            val e = Reset.resetEx(w, prev, smt, a, b, env, trie, engine.pendingRoot, gen, emptySet(), emptyMap())
            SittingTouches.writeBack(sw, smt, sitting)
            search = e
            prevStats = e.st.stepStats
            prevEnders = e.enders
            prevFull = e.full
            sync()
        }

        override fun host(slot: MergeSlot): MergeBufferUpdate.Host<Any?> = object : MergeBufferUpdate.Host<Any?> {
            override fun fields(r: Any?): MergeBufferUpdate.PR {
                val (pr, wa) = r as Pair<*, *>
                pr as MemImage; wa as MemImage
                return MergeBufferUpdate.PR(pr.f32(4), pr.f32(8), wa.u8(0x32), pr.f32(0x68))
            }
            override fun emptyResult(): Any? {
                val pr = MemImage("pr", 0xa0); val wa = MemImage("wa", 0x50)
                nintype.engine.pathscore.PredPathTrie.initPathResult(pr, wa)
                return pr to wa
            }
            override fun contextHas(which: Int): Boolean = false
            override fun reset(start: Int, end: Int) {
                ps.setI32(0x114, 0); ps.setI32(0x118, 0); ps.setU8(0x11c, 0)
                resetEx(start, end)
            }
            /** MooMergeBuffer::update stores 3 into the PathSearch's per-word path limit (+0x4fc,
             * Moopad +0x2a2bc) next to every syncBucketStepAllowance: after reset() and before it
             * on the main search, after it and before the split resets (whose
             * resetAndEmptyVariables reloads +0x4fc from Moopad +0x333f0). */
            override fun syncBucketStepAllowance(f: Float) { ps.setI32(0x4fc, 3); PathSearch.syncBucketStepAllowance(ps, f); sync() }
            override fun performSteps(n: Int) { search!!.performSteps(); gen = search!!.gen.v; sync() }
            override fun listResults(n: Int) {
                search!!.listResults(n)
                candidates = currentCandidates()
                sync()
            }
            /** topResult() @007eace8: PredPath::result() of the first ranked result (listing
             * min(+0x634, 9) first when fewer are ranked), the other ranked paths' getAsWA appended
             * to the alternatives (+0x5c); +0x74/+0x78 the best path's (+0x610) pathsize and proper
             * word; +0x7c/+0x80 the search range (+0x194/+0x198); +0x88 the latest touch end
             * (+0x178, f64). */
            override fun topResult(): Any? {
                val st = search!!.st
                val n = minOf(st.results.size, 9)
                // a real PathSearch::listResults call: the oracle driver counts it as a listing, so
                // lastCandidates() changes (a new list object) when it runs
                if (st.resultList.size < n) { PathSearch.listResults(st, n); candidates = currentCandidates() }
                val pr = MemImage("pr", 0xa0); val wa = MemImage("wa", 0x50)
                val top = st.resultList.firstOrNull()
                if (top == null) nintype.engine.pathscore.PredPathTrie.initPathResult(pr, wa)
                else {
                    nintype.engine.pathscore.PredPathTrie.result(top, pr, wa, sw.refs(ps, 0x1a0).size)
                    val alts = nintype.engine.pathscore.Vecs.elems(pr, 0x5c)
                    for ((k, pp) in st.resultList.withIndex()) if (k != 0) {
                        val a = MemImage("pr.alternatives[${alts.size}]", 0x50)
                        nintype.engine.pathscore.PredPathTrie.getAsWA(pp, a)
                        alts.add(a)
                    }
                }
                st.bestPath?.let { bp ->
                    pr.setI32(0x74, bp.i8(0x2a))
                    nintype.engine.pathscore.Strs.set(pr, 0x78, nintype.engine.pathscore.PredPathTrie.getProperWord(bp))
                }
                pr.setI32(0x7c, ps.i32(0x194)); pr.setI32(0x80, ps.i32(0x198))
                pr.setF64(0x88, ps.f64(0x178))
                sync()
                return pr to wa
            }
            override fun newACCollectage() { slot.acs.add(null) }
            /** gatherAutocomplete(ACCollectage&, PathResult&) @007e27b8: only its ranking side is
             * modelled: with +0x368 clear it lists min(+0x634, 9) results when fewer are ranked
             * (a listing the oracle driver sees). The ACCollectage itself is parked (see
             * engine/wip/search/AutocompleteHost.kt.txt). */
            override fun gatherAutocomplete() {
                val st = search!!.st
                if (ps.u8(0x368) == 0) {
                    val n = minOf(st.results.size, 9)
                    if (st.resultList.size < n) { PathSearch.listResults(st, n); candidates = currentCandidates(); sync() }
                }
                engine.pend("Emkey::PathSearch::gatherAutocomplete (ACCollectage contents)")
            }
            override fun mootouchIndexOfNearSpaceTapper(): Int = Splits.mootouchIndexOfNearSpaceTapper(sw, gatherEnv())
            override fun mootouchIndexOfApostropheEnder(): Int = Splits.mootouchIndexOfApostropheEnder(sw)
            /** resetForPrePostcut @007d75d4 / resetForAutospaceSplitSearch @007d75b0: +0x118 = idx,
             * +0x114 = post ? 2 : 1, +0x11c = 0 / 1, then resetEx. */
            override fun resetForPrePostcut(start: Int, end: Int, idx: Int, post: Boolean) {
                ps.setI32(0x118, idx); ps.setI32(0x114, if (post) 2 else 1); ps.setU8(0x11c, 0); resetEx(start, end)
            }
            override fun resetForAutospaceSplitSearch(start: Int, end: Int, idx: Int, post: Boolean) {
                ps.setI32(0x118, idx); ps.setI32(0x114, if (post) 2 else 1); ps.setU8(0x11c, 1); resetEx(start, end)
            }
            override fun mergeFromSpaceSplitters(top: Any?, pre: Any?, post: Any?, w: Float, idx: Int): Any? =
                this@Factory.mergeFromSpaceSplitters(top, pre, post, w, idx)
            override fun syncAutospaceStatsPostSearch() = throw NotImplementedError("AutospaceModule::syncAutospaceStatsPostSearch")
            override fun shouldSplit(): Boolean = throw NotImplementedError("AutospaceSplitoffStats::shouldSplit")
            override fun onDetectEarlySplit(gap: Float) = throw NotImplementedError("AutospaceModule::onDetectEarlySplit")
            /** MooMergeBuffer::update's possessive check: post's word (PathResult +0x10, its
             * WordAlternative's +4) compares equal to "'s" (std::string::compare(char const*) == 0). */
            override fun apostropheMergeAllowed(post: Any?): Boolean {
                val wa = (post as Pair<*, *>).second as MemImage
                return nintype.engine.pathscore.Strs.get(wa, 4).contentEquals("'s".toByteArray(Charsets.ISO_8859_1))
            }
        }

        override fun lastCandidates(): List<Candidate> = candidates




        /** PathResult +0x94 (vector<PathResult>) per handle. */
        private val subs = java.util.IdentityHashMap<MemImage, MutableList<Any?>>()

        private fun copyResult(r: Any?): Pair<MemImage, MemImage> {
            val (pr, wa) = r as Pair<*, *>
            pr as MemImage; wa as MemImage
            val p2 = MemImage(pr.name, 0xa0).also { it.writeBytes(0, pr.readBytes(0, 0xa0)) }
            for (o in intArrayOf(0, 0x78)) nintype.engine.pathscore.Strs.set(p2, o, nintype.engine.pathscore.Strs.get(pr, o).copyOf())
            nintype.engine.pathscore.Vecs.put(p2, 0x5c, nintype.engine.pipeline.Wa.deepCopy(nintype.engine.pathscore.Vecs.elems(pr, 0x5c)))
            subs[pr]?.let { subs[p2] = it.toMutableList() }
            return p2 to nintype.engine.pipeline.Wa.copy(wa)
        }

        /**
         * PathResult::mergeFromSpaceSplitters(pre, post, w, idx, Moopad*) @0081e440 on [top]:
         * appends copies of pre and post to +0x94; when both have alternatives, for each of post's
         * first three (the loop counter starts at -1, runs while < 2): a WordAlternative "<pre alt 0> <post alt>" (no space when Moopad USF1 0xb0
         * is off and the post alternative has flag 0x400000), trie word -1, ender trie word (+0x18)
         * = the post alternative's trie word (+0x14), +0x1c the summed covered counts, +0x20 =
         * +0x2c = mean score x w, +0x24 mean norm, flags pre's 0x4000/0x8000 kept, 0x10000 set
         * (0x18120 cleared), +0x3e = idx; then std::sort of +0x5c and alternative 0 becomes the
         * result's own WordAlternative (+0xc), word (+0), score (+4), norm (+8), +0x68.
         */
        fun mergeFromSpaceSplitters(top: Any?, pre: Any?, post: Any?, w: Float, idx: Int): Any? {
            val (tpr, twa) = top as Pair<*, *>
            tpr as MemImage; twa as MemImage
            val S = nintype.engine.pathscore.Strs
            val V = nintype.engine.pathscore.Vecs
            val sub = subs.getOrPut(tpr) { ArrayList() }
            sub.add(copyResult(pre)); sub.add(copyResult(post))
            val preAlts = V.elems((pre as Pair<*, *>).first as MemImage, 0x5c)
            val postAlts = V.elems((post as Pair<*, *>).first as MemImage, 0x5c)
            val alts = V.elems(tpr, 0x5c)
            if (preAlts.isNotEmpty() && postAlts.isNotEmpty()) {
                val a = preAlts[0]
                for (b in postAlts.take(3)) {
                    val m = nintype.engine.pipeline.Wa.new("merged")
                    val glue = if (MergeMoopadUSF.usf1(engine.pad, 0xb0) == 0 && b.u8(0x32) and 0x40 != 0) ByteArray(0) else byteArrayOf(0x20)
                    S.set(m, 4, S.get(a, 4) + glue + S.get(b, 4))
                    nintype.engine.pathscore.WordAlternative.setTrieWord(m, -1, 0)
                    m.setI32(0x18, b.i32(0x14))
                    m.setI32(0x1c, b.i32(0x1c) + a.i32(0x1c))
                    val sc = nintype.engine.SoftFloat.fmul(nintype.engine.SoftFloat.fmul(nintype.engine.SoftFloat.fadd(a.f32(0x20), b.f32(0x20)), 0.5f), w)
                    m.setF32(0x20, sc)
                    m.setF32(0x24, nintype.engine.SoftFloat.fmul(nintype.engine.SoftFloat.fadd(a.f32(0x24), b.f32(0x24)), 0.5f))
                    m.setF32(0x2c, sc)
                    var f = m.i32(0x30) and 0x120.inv()
                    f = ((f and 0xffffbedf.toInt()) or (a.i32(0x30) and 0x4000))
                    f = (f and 0xfffe7edf.toInt()) or (a.i32(0x30) and 0x8000) or 0x10000
                    m.setI32(0x30, f)
                    m.setU8(0x3e, idx and 0xff)
                    alts.add(m)
                }
            }
            if (alts.isNotEmpty()) {
                nintype.engine.pipeline.Wa.sort(alts)
                nintype.engine.pipeline.Wa.assign(twa, alts[0])
                S.set(tpr, 0, S.get(twa, 4).copyOf())
                tpr.setF32(4, twa.f32(0x20)); tpr.setF32(8, twa.f32(0x24))
                tpr.setF32(0x68, nintype.engine.SoftFloat.fmul(twa.f32(0x20), nintype.engine.SoftFloat.ui2f(S.get(tpr, 0).size)))
            }
            return top
        }

        override fun pathResult(r: Any?): nintype.engine.pipeline.PathResultView {
            val (pr, wa) = r as Pair<*, *>
            pr as MemImage; wa as MemImage
            // the PathResult's own WordAlternative lives at +0xc (score +0x2c = its +0x20)
            val img = MemImage(pr.name, 0xa0).also { it.writeBytes(0, pr.readBytes(0, 0xa0)); it.writeBytes(0xc, wa.readBytes(0, 0x50)) }
            return nintype.engine.pipeline.PathResultView(img, wa, nintype.engine.pathscore.Vecs.elems(pr, 0x5c),
                pr.f64(0x88), subs[pr]?.toList() ?: emptyList())
        }
    }
}
