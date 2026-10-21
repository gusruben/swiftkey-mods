package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.math.CurveMapper
import nintype.engine.pathscore.Ctp
import nintype.engine.pathscore.FinalScore
import nintype.engine.pathscore.PredPath
import nintype.engine.pathscore.PredPathTrie
import nintype.engine.pathscore.Vecs
import nintype.engine.touch.PlanarCoverage
import nintype.engine.trie.CombinedTriePath
import nintype.engine.trie.CompactTrie
import nintype.engine.trie.CompactTriePath
import nintype.engine.trie.PendingTrieNode
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import nintype.engine.vectors.Values

/**
 * The search loop over real ports only: PathSearch::performSteps (step -> seekNextStep /
 * seekPreordainedNextSteps -> maybeBranch -> canAdvanceEx + maybeBranchInner, with pathscore's
 * PredPath members and the trie cursor) and listResults, from a PathSearch state as resetEx
 * leaves it.
 *
 * The state is an object world in the gen_search_e2e.py layout: images "ps" (PathSearch),
 * "mp" (Moopad), the Mootouches of ps+0x1a0 with their MooTouchPoints (raw bytes plus `$ref`s
 * at pointer fields), each point's TravKeyData as its own image (the +0x328 vector holds refs),
 * the queued root PredPath, and the PathSearch containers in the search vectors' formats.
 * [pendRoot] is the pending-word trie root (empty in a fresh engine).
 */
class SearchEngine(val w: MemWorld, val trie: CompactTrie, val pendRoot: PendingTrieNode = PendingTrieNode(),
                   gen0: Long = 1, val board: Set<Int> = emptySet(), val strs: Map<Int, ByteArray> = emptyMap()) {
    val ps: MemImage = w["ps"]
    val mp: MemImage = w["mp"]
    private val pj = ps
    private fun refs(img: MemImage, off: Int): List<MemImage> = img.seq(off).map { w[it["\$ref"]!!.asString()] }
    private fun refsN(img: MemImage, off: Int): List<MemImage?> = img.seq(off).map { it["\$ref"]!!.asStringOrNull()?.let { n -> w[n] } }

    val mts: List<MemImage> = refs(ps, 0x1a0)
    val important = refs(ps, 0x1b0)
    val generic = refs(ps, 0x1bc)
    val finger1 = refs(ps, 0x1c8)
    val finger0 = refs(ps, 0x1d4)
    private val ptsCache = HashMap<Pair<MemImage, Int>, List<MemImage>>()
    fun points(mt: MemImage, off: Int): List<MemImage> = ptsCache.getOrPut(mt to off) { refs(mt, off) }
    private val travCache = HashMap<MemImage, List<MemImage>>()
    fun travImgs(p: MemImage): List<MemImage> = travCache.getOrPut(p) { refs(p, 0x328) }
    fun travPairs(p: MemImage): List<Pair<Int, Float>> = travImgs(p).map { it.i32(8) to it.f32(0x10) }
    private val elemCache = HashMap<Pair<MemImage, Int>, List<MemImage>>()
    /** A raw-element vector of [img] at [off] as images, element refs linked. */
    fun elems(img: MemImage, off: Int): List<MemImage> = elemCache.getOrPut(img to off) {
        img.seq(off).mapIndexed { k, j ->
            val e = MemImage.decode(j, "${img.name}@${off.toString(16)}[$k]")
            for ((o, n) in e.refNames) e.refs[o] = n?.let { w[it] }
            e
        }
    }

    private fun cmOf(j: JsonValue): CurveMapper {
        val c = CurveMapper()
        for (p in j["@0x4:map"]!!["\$map"]!!.asArray()) c.put(Leaf.fromJson(p["first"]!!).asFloat(), Leaf.fromJson(p["second"]!!).asFloat())
        return c
    }
    private fun pcFull(j: JsonValue): PlanarCoverage {
        val pc = PlanarCoverage(areas = j["@0x14:areas"]!!["\$vec"]!!.asArray().map { Leaf.fromJson(it).asInt() }.toMutableList())
        pc.tMin = Leaf.fromJson(j["@0x0:tMin"]!!).asDouble()
        pc.tMax = Leaf.fromJson(j["@0x8:tMax"]!!).asDouble()
        pc.hasTime = Leaf.fromJson(j["@0x10:hasTime"]!!).asInt()
        pc.maxCoverage = Leaf.fromJson(j["@0x20:maxCoverage"]!!).asFloat()
        return pc
    }

    lateinit var siToTime: CurveMapper
    lateinit var timeToSi: CurveMapper
    lateinit var nth: List<PlanarCoverage>
    lateinit var ender1: PlanarCoverage
    val chain = LinkedHashMap<String, Float>()
    val nthCache = ArrayList<PlanarCoverage>()
    val slowCache = ArrayList<PlanarCoverage>()
    val gen = GenCounter(gen0)
    val skipCaches = SkipCheckerCaches()
    lateinit var st: SearchState
    /** The PathSearch-embedded PlanarCoverages resetEx left (+0x438.. enders, +0x410 full), kept
     * for the next resetEx. */
    var enders: List<PlanarCoverage> = emptyList()
    var full: PlanarCoverage = PlanarCoverage()

    /** Loads the containers from the PathSearch object's JSON [psj] (the dump's "ps"). */
    fun load(psj: JsonValue, clearCoverage: Boolean = true) {
        // the dictionary's coverage map (CompactTrie+0xe8) is per search: resetEx empties it
        // (when the previous search had touches; see Reset.resetEx)
        if (clearCoverage) trie.coverage.clear()
        siToTime = cmOf(psj["@0x27c:siToTime"]!!)
        timeToSi = cmOf(psj["@0x294:timeToSi"]!!)
        nth = psj["@0x3d0:nthCharCoverages"]!!["\$vec"]!!.asArray().map(::pcFull)
        ender1 = pcFull(psj["@0x438:enderCoverage1"]!!)
        for (e in psj["@0x3a0:chain"]!!["\$map"]!!.asArray())
            chain[String(Values.parseBytes(e["first"]!!["v"]!!.asString()), Charsets.ISO_8859_1)] = Leaf.fromJson(e["second"]!!).asFloat()
        // pathscore reads the generic points through Vecs (element image ref at 0)
        Vecs.put(ps, 0x1bc, generic.mapIndexed { k, p -> MemImage("ps.generic[$k]", 4).also { it.refs[0] = p } }.toMutableList())
        st = SearchState(ps, { PredPathTrie.getWord(it) }, { PredPathTrie.getWordLength(it) }, strs[0x333ec] ?: ByteArray(0),
            mp.u8(0x333e0) != 0, strs[0x333e8] ?: ByteArray(0),
            { pp -> SearchState.unAutoAposWordOf(Vecs.elems(pp, PredPath.OFF_STEPS).map { it.i32(0xc) }) },
            { pp -> FinalScore.determineFinalScore(pp, board, chain) })
        for ((k, j) in psj["@0xea8:stepStats"]!!["\$vec"]!!.asArray().withIndex()) {
            val s = StepScoreStats.fromJson(j, ps, "ps.stepStats[$k]")
            s.refs[0] = ps
            check(s.seq(0x3c).isEmpty() && s.seq(0x78).isEmpty()) { "stepStats[$k] heap/pending not empty after resetEx" }
            st.stepStats.add(s)
        }
        for (b in 0 until 4) {
            val base = PathSearch.BUCKETS + PathSearch.BUCKET_STRIDE * b
            st.buckets[b].queue.addAll(refsN(ps, base + 0xc).map { it!! })
            st.buckets[b].best.addAll(refsN(ps, base + 0x1c).map { it!! })
        }
        st.bestPath = ps.refs[0x610]
        // root paths: the trie cursor at the dictionary root and the pending root
        for (b in st.buckets) for (pp in b.queue + b.best) if (!Ctp.has(pp)) {
            val main = CompactTriePath(trie)
            main.curid = pp.i32(0x15c)
            Ctp.attach(pp, CombinedTriePath(main, pendRoot, 0, false, false))
            Vecs.put(pp, PredPath.OFF_STEPS, ArrayList())
        }
    }

    // --- callees -------------------------------------------------------------------------

    /** A FingerPathData of [pp] at [off] as its own image (Seek reads +0x15, +0x29, +0x30, +0x40, +0x44). */
    private fun fingView(pp: MemImage, off: Int): MemImage {
        val v = MemImage("${pp.name}.fing@${off.toString(16)}", 0x48)
        v.writeBytes(0, pp.readBytes(off, 0x48))
        for (o in intArrayOf(0x40, 0x44)) pp.refs[off + o]?.let { v.refs[o] = it }
        return v
    }

    val compat by lazy {
        SearchCompat(ps, important, PathSearch.onlyHasASingleTouch(mts), siToTime, timeToSi, nthCache, slowCache, nth, ender1, ::travPairs, generic)
    }
    val trace = ArrayList<List<Any>>()
    val routeInfos = ArrayList<String>()
    val live by lazy { LiveBranchCallees(st, mp, compat, ps, { pp -> FinalScore.determineFinalScore(pp, board, chain) }, trace, { routeInfos.add(it) }) }
    val env by lazy {
        BranchEnv(mp, strs[0x333e4] ?: ByteArray(0), { i -> PathSearch.getStepStats(ps, st.stepStats, i) },
            { p -> elems(p, 0x3c) })
    }

    /** Each BranchmentData's skip-target chars (its vector<char> +0x28, filled by canAdvanceEx).
     * They stay with the bd for every maybeBranch made with it: the revisit second branch of
     * seekNextStep / seekPreordainedNextSteps gets the same skip branches as the first. Cleared
     * at each seek call (a bd never outlives the seek that made it). */
    private val skipsOf = java.util.IdentityHashMap<MemImage, MutableList<Int>>()

    /** canAdvanceEx calls as [word, bd char, result, bd +0x23, +0x24, +0x25, skips] (the
     * gen_search_e2e.py "advs" diagnostic, SEARCH_E2E_ADV=1); null = not recorded. */
    var advs: ArrayList<List<Any>>? = null

    private fun canAdvanceEx(pp: MemImage, bd: MemImage): Boolean {
        val skips = ArrayList<Int>()
        skipsOf[bd] = skips
        val r = PredPathTrie.canAdvanceEx(pp, bd, skips, skipCaches, st.stepStats)
        advs?.add(listOf(String(PredPathTrie.getWord(pp), Charsets.ISO_8859_1), (bd.i32(8) and 0xff).toLong(), if (r) 1L else 0L,
            bd.u8(0x23).toLong(), bd.u8(0x24).toLong(), bd.u8(0x25).toLong(), skips.map { (it and 0xff).toLong() }))
        return r
    }

    private fun branch(pp: MemImage, mt: MemImage, p: MemImage, trav: MemImage, d1: Double, d2: Double, bd: MemImage): Int =
        PathSearch.maybeBranch(ps, bd.u8(0x23), bd.u8(0x24), bd.u8(0x25), skipsOf[bd] ?: emptyList()) { mode, c ->
            val r = Branch.maybeBranchInner(ps, pp, mt, p, trav, d1, d2, mode, c, env, live)
            trace.add(listOf("mbi", p.name, trav.i32(8).toLong() and 0xff, mode, c and 0xff))
            if (r) 1 else 0
        }

    private fun stats84(pp: MemImage) = PathSearch.getStepStats(ps, st.stepStats, pp.i8(0x2a)).i32(0x84)

    private val seekCb = object : SeekCallees {
        var mt: MemImage? = null
        var pts: List<MemImage> = emptyList()
        override fun fing(pp: MemImage, side: Int): MemImage = fingView(pp, PredPath.fing(pp, side))
        override fun canAdvanceEx(pp: MemImage, p: MemImage, trav: Int, bd: MemImage): Boolean = this@SearchEngine.canAdvanceEx(pp, bd)
        override fun justVisited(pp: MemImage, p: MemImage): Boolean = PredPath.justVisited(pp, p)
        override fun hasVisited(pp: MemImage, p: MemImage): Boolean = PredPath.hasVisited(pp, p)
        override fun maybeBranch(pp: MemImage, mt: MemImage, p: MemImage, trav: Int, d1: Double, d2: Double, bd: MemImage): Int =
            branch(pp, mt, p, travImgs(p)[trav], d1, d2, bd)
    }

    private val preCb = object : PreordainedCallees {
        override fun lastfing(pp: MemImage): MemImage = fingView(pp, PredPath.lastfing(pp, 1))
        override fun lastotherfing(pp: MemImage): MemImage = fingView(pp, PredPath.lastotherfing(pp, 1))
        override fun fing(pp: MemImage, side: Int): MemImage = fingView(pp, PredPath.fing(pp, side))
        override fun canAdvanceEx(pp: MemImage, bd: MemImage): Boolean = this@SearchEngine.canAdvanceEx(pp, bd)
        override fun justVisited(pp: MemImage, p: MemImage): Boolean = PredPath.justVisited(pp, p)
        override fun maybeBranch(pp: MemImage, mt: MemImage, p: MemImage, n: MemImage, d1: Double, d2: Double, bd: MemImage): Int =
            branch(pp, mt, p, n, d1, d2, bd)
    }

    private fun nexts(p: MemImage, off: Int): List<NextStep> = elems(p, off).map { NextStep(it.i32(0), it.ref(4)!!, it.ref(8)!!) }

    private val stepCb = object : PathSearch.StepCallees {
        override fun maximumPathLength(pp: MemImage): Int = PredPathTrie.maximumPathLength(pp)
        override fun seekNextStep(pp: MemImage, mt: MemImage, second: Boolean): Int {
            trace.add(listOf("seek", String(PredPathTrie.getWord(pp), Charsets.ISO_8859_1), pp.i32(0x74).toLong() and 0xffffffffL, if (second) 1 else 0))
            skipsOf.clear()
            return Seek.seekNextStep(ps, pp, mt, second, points(mt, if (second) 0x250 else 0x244),
                { p -> travImgs(p).map { t -> IntArray(8) { t.i32(4 * it) } } }, generic, finger1, finger0, stats84(pp), seekCb)
        }
        override fun seekPreordainedNextSteps(pp: MemImage) {
            trace.add(listOf("pre", String(PredPathTrie.getWord(pp), Charsets.ISO_8859_1), pp.i32(0x74).toLong() and 0xffffffffL, 0))
            skipsOf.clear()
            val s84 = stats84(pp)
            Seek.seekPreordainedNextSteps(ps, pp, s84, gen, { mp.u8(0xc48 + 0xc) }, { mp.u8(0xc14 + 0xc) }, ::nexts, preCb)
        }
    }

    /** performSteps(int) @007ede98 with both "no search" flags clear. */
    fun performSteps(): Int = PathSearch.performSteps(false, false, {}, {}) { PathSearch.step(st, mts, stepCb) }

    /** listResults(n) as (word, final score bits, word prior bits). */
    fun listResults(n: Int): List<Triple<ByteArray, Int, Int>> =
        PathSearch.listResults(st, n).map { Triple(PredPathTrie.getWord(it), Bits.floatToBits(it.f32(0x74)), Bits.floatToBits(it.f32(0xfc))) }

    companion object {
        /** An engine from a gen_search_e2e.py dump document (its "objects" and "meta"). */
        fun fromDump(doc: JsonValue, trie: CompactTrie): SearchEngine {
            val objs = doc["objects"]!!.asObject()
            val meta = doc["meta"]!!
            val w = MemWorld(objs)
            val strs = meta["strs"]!!.asObject().mapKeys { java.lang.Long.decode(it.key).toInt() }
                .mapValues { Values.parseBytes(it.value.asString()) }
            val board = meta["board"]!!.asArray().map { (it as JsonValue.JNumber).value.toInt() }.toSet()
            val e = SearchEngine(w, trie, PendingTrieNode(), java.lang.Long.parseUnsignedLong(meta["gen"]!!.asString().removePrefix("0x"), 16),
                board, strs)
            e.load(objs["ps"]!!)
            return e
        }
    }
}
