package nintype.engine.ports

import nintype.engine.math.CurveMapper
import nintype.engine.search.MootouchOps
import nintype.engine.search.PathSearch
import nintype.engine.search.SearchState
import nintype.engine.touch.PlanarCoverage
import nintype.engine.search.StepScoreStats
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.VectorCase
import nintype.engine.vectors.Values

/** Vector adapters for re/vectors/gen_search.py (Emkey::PathSearch). */

private const val PS = "Emkey::PathSearch::"

private fun reg(name: String, body: (VectorCase) -> CaseResult) = PortRegistry.register("$PS$name", body)
private fun regB(name: String, body: (VectorCase) -> Boolean) = reg(name) { CaseResult(ret = Leaf.bool(body(it))) }

/** A `$vec` of `$ref`s at [off] of [img], resolved against the case's objects. */
private fun MemWorld.refList(img: MemImage, off: Int): List<MemImage> =
    img.seq(off).map { this[it["\$ref"]!!.asString()] }

/** The image a top-level `{"$ref": name}` input names. */
private fun MemWorld.named(case: VectorCase, input: String): MemImage = this[case.inputs[input]!!["\$ref"]!!.asString()]

private fun JsonValue.bytesOf(): ByteArray = nintype.engine.vectors.Values.parseBytes(this["v"]!!.asString())

private fun ptsOf(img: JsonValue?): List<MemImage> =
    img?.get("@0x244:points")?.get("\$vec")?.asArray()?.mapIndexed { k, j -> MemImage.decode(j, "pt$k") } ?: emptyList()

private fun registerMootouchPorts() {
    val mo = "Emkey::Mootouch::"
    fun r(n: String, body: (VectorCase) -> CaseResult) = PortRegistry.register("$mo$n", body)
    fun p2(p: nintype.engine.math.Point2D) = Values.struct("@0x0:x" to Leaf.f32(p.x).toJson(), "@0x4:y" to Leaf.f32(p.y).toJson())
    r("getStartpos()") { CaseResult(retValue = p2(MootouchOps.getStartpos(it.img("this")))) }
    r("getAveragePos()") { CaseResult(retValue = p2(MootouchOps.getAveragePos(it.img("this"), ptsOf(it.inputs["this"])))) }
    r("firstFocusTime()") { CaseResult(ret = Leaf.f64(MootouchOps.firstFocusTime(it.img("this"), ptsOf(it.inputs["this"])))) }
    r("lowestYPoint()") { CaseResult(ret = Leaf.f32(MootouchOps.lowestYPoint(ptsOf(it.inputs["this"])))) }
    r("predictCharCount()") { CaseResult(ret = Leaf.f32(MootouchOps.predictCharCount(ptsOf(it.inputs["this"])))) }
    r("timeOverlaps(Emkey::Mootouch&, float)") { CaseResult(ret = Leaf.bool(MootouchOps.timeOverlaps(it.img("this"), it.img("o"), it.args[0].asFloat()))) }
    r("timeOverlapsWith(Emkey::Mootouch const&) const") { CaseResult(ret = Leaf.bool(MootouchOps.timeOverlapsWith(it.img("this"), it.img("o")))) }
    r("timeEncloses(Emkey::Mootouch const&)") { CaseResult(ret = Leaf.bool(MootouchOps.timeEncloses(it.img("this"), it.img("o")))) }
    r("takeDoubleLetterFactorings(Emkey::Mootouch&)") {
        val pts = ptsOf(it.inputs["this"])
        MootouchOps.takeDoubleLetterFactorings(pts, ptsOf(it.inputs["o"]))
        CaseResult(out = mapOf("this" to Values.struct("@0x244:points" to Values.vec(pts.map {
            Values.struct("@0x16c:dlFactor" to Leaf("f32", it.readLE(0x16c, 4)).toJson()) }))))
    }
}

private fun registerMtSearchPorts() {
    PortRegistry.register("Emkey::MooTouchPoint::syncSameFingSoftIndexToParentForCKPoint()") {
        val w = it.world()
        PathSearch.syncSameFingSoftIndexToParentForCKPoint(w.named(it, "this"))
        CaseResult(mem = w.images)
    }
    reg("syncCKPointSoftIndexes()") {
        val w = it.world()
        PathSearch.syncCKPointSoftIndexes(w.refList(w["this"], 0x1b0))
        CaseResult(mem = w.images)
    }
    reg("syncDoubleLetterAllowances()") {
        val w = it.world()
        val ps = w["this"]
        val names = ps.seq(0x1a0).map { r -> r["\$ref"]!!.asString() }
        val liveNames = it.inputs["liveTouches"]!!["@0x0:byId"]!!["\$vec"]!!.asArray().map { r -> r["\$ref"]!!.asString() }
        val livePts = liveNames.associateWith { n -> ptsOf(it.inputs[n]) }
        val mp = ps.ptr(0x110)!!
        PathSearch.syncDoubleLetterAllowances(w.refList(ps, 0x1bc), names.map { n -> w[n] }, names.map { n -> ptsOf(it.inputs[n]) },
            mp.u8(0x2b578 + 0xc), mp.u8(0x2b544 + 0xc)) { id ->
            liveNames.firstOrNull { n -> w[n].i32(0xc0) == id }?.let { n -> w[n] to livePts[n]!! }
        }
        val out = LinkedHashMap<String, JsonValue>()
        for (n in liveNames) out[n] = Values.struct("@0x244:points" to Values.vec(livePts[n]!!.map { p ->
            Values.struct("@0x16c:dlFactor" to Leaf("f32", p.readLE(0x16c, 4)).toJson()) }))
        CaseResult(out = out, mem = w.images)
    }
    reg("syncTouchLRNess()") {
        val w = it.world()
        val ps = w["this"]
        val names = ps.seq(0x1a0).map { r -> r["\$ref"]!!.asString() }
        val live = it.inputs["liveTouches"]!!["@0x0:byId"]!!["\$vec"]!!.asArray().map { r -> w[r["\$ref"]!!.asString()] }
        PathSearch.syncTouchLRNess(names.map { n -> w[n] }, names.map { n -> ptsOf(it.inputs[n]) }, ps.ptr(0x110)!!.i32(0x1f490)) { id ->
            live.firstOrNull { t -> t.i32(0xc0) == id }
        }
        CaseResult(mem = w.images)
    }
    reg("syncTouchIslandness()") {
        val w = it.world()
        PathSearch.syncTouchIslandness(w["this"], w.refList(w["this"], 0x1a0))
        CaseResult(mem = w.images)
    }
    reg("predictWordLength()") {
        val w = it.world()
        val ps = w["this"]
        val names = ps.seq(0x1a0).map { r -> r["\$ref"]!!.asString() }
        PathSearch.predictWordLength(ps, names.map { n -> ptsOf(it.inputs[n]) }, w.refList(ps, 0x1bc))
        CaseResult(mem = mapOf("this" to ps))
    }
    regB("checkIsGoodOverlapper(Emkey::Mootouch&)") {
        val w = it.world()
        val ps = w["this"]
        val names = ps.seq(0x1a0).map { r -> r["\$ref"]!!.asString() }
        val mp = ps.ptr(0x110)!!
        val argName = it.inputs["arg"]!!["\$ref"]!!.asString()
        PathSearch.checkIsGoodOverlapper(names.map { n -> w[n] }, names.map { n -> ptsOf(it.inputs[n]) }, w[argName], ptsOf(it.inputs[argName]),
            mp.f32(0x1f4a0) /* Moopad::curboardVertlen: a plain read of 0x1f4a0 (geometry, verified) */, mp.f32(0x1f4a4))
    }
}

/** A PredPath's getWord()/getWordLength() answers, carried by the vectors as "$word"/"$wordLength". */
private fun VectorCase.wordOf(pp: MemImage): ByteArray = Values.parseBytes(inputs[pp.name]!!["\$word"]!!["v"]!!.asString())
private fun VectorCase.wordLengthOf(pp: MemImage): Int = Leaf.fromJson(inputs[pp.name]!!["\$wordLength"]!!).asInt()

private fun wordCountsOf(seq: List<JsonValue>): java.util.TreeMap<nintype.engine.search.StrKey, Int> {
    val m = java.util.TreeMap<nintype.engine.search.StrKey, Int>()
    for (e in seq) m[nintype.engine.search.StrKey(Values.parseBytes(e["first"]!!["v"]!!.asString()))] = Leaf.fromJson(e["second"]!!).asInt()
    return m
}

private fun wordCountsJson(m: java.util.TreeMap<nintype.engine.search.StrKey, Int>): MutableList<JsonValue> =
    m.entries.map { (k, v) -> Values.struct("first" to Values.bytesLeaf(k.b), "second" to Leaf.i32(v).toJson()) }.toMutableList()

private fun MemWorld.refsN(seq: List<JsonValue>): List<MemImage?> = seq.map { r -> r["\$ref"]!!.asStringOrNull()?.let { this[it] } }
private fun refsJson(l: List<MemImage?>): MutableList<JsonValue> = l.map { Values.ref(it?.name) }.toMutableList()

/** Builds a SearchState from a pushQueue-style vector's `this` and writes it back after. */
private class StateIO(val c: VectorCase) {
    val w = c.world()
    val ps = w["this"]
    val th = c.inputs["this"]!!
    private val mp = th["@0x110:moopad"]?.get("\$ptr")
    private fun mpStr(k: String) = mp?.get(k)?.get("\$str")?.let { Values.parseBytes(it["v"]!!.asString()) } ?: ByteArray(0)
    val st = SearchState(ps, { c.wordOf(it) }, { c.wordLengthOf(it) }, mpStr("@0x333ec:traceWord"),
        mp?.get("@0x333e0:traceOn")?.let { Leaf.fromJson(it).asInt() != 0 } ?: false, mpStr("@0x333e8:traceWord"),
        { pp -> SearchState.unAutoAposWordOf(pp.seq(0x108).map { s -> Leaf.fromJson(s["@0xc:chara"]!!).asInt() }) },
        { pp -> pp.setF32(0x74, Leaf.fromJson(c.inputs[pp.name]!!["\$finalScore"]!!).asFloat()) })
    init {
        for (b in 0 until 4) {
            val base = PathSearch.BUCKETS + PathSearch.BUCKET_STRIDE * b
            st.buckets[b].queue.addAll(w.refsN(ps.seq(base + 0xc)).map { it!! })
            st.buckets[b].best.addAll(w.refsN(ps.seq(base + 0x1c)).map { it!! })
        }
        st.bestPath = ps.refNames[0x610]?.let { w[it] }
        st.bestByLength.addAll(w.refsN(ps.seq(0x614)))
        st.traceHits.addAll(w.refsN(ps.seq(0x9d0)).map { it!! })
        st.resultList.addAll(w.refsN(ps.seq(0x638)).map { it!! })
        for (e in ps.seq(0x620)) st.results[nintype.engine.search.StrKey(Values.parseBytes(e["first"]!!["v"]!!.asString()))] = w[e["second"]!!["\$ref"]!!.asString()]
        ps.seq(0xea8).forEachIndexed { k, j ->
            val s = StepScoreStats.fromJson(j, ps, "this.stepStats[$k]")
            st.stepStats.add(s)
            st.heapOf(s).addAll(w.refsN(s.seq(0x3c)).map { it!! })
            st.countsOf(s).putAll(wordCountsOf(s.seq(0x50)))
            st.statsPending(s).addAll(w.refsN(s.seq(0x78)).map { it!! })
        }
    }

    fun write(): CaseResult {
        for (b in 0 until 4) {
            val base = PathSearch.BUCKETS + PathSearch.BUCKET_STRIDE * b
            ps.seqs[base + 0xc] = refsJson(st.buckets[b].queue)
            ps.seqs[base + 0x1c] = refsJson(st.buckets[b].best)
        }
        ps.refNames[0x610] = st.bestPath?.name
        ps.refs.remove(0x610)
        ps.seqs[0x614] = refsJson(st.bestByLength)
        ps.seqs[0x9d0] = refsJson(st.traceHits)
        ps.seqs[0x638] = refsJson(st.resultList)
        ps.seqs[0x620] = st.results.entries.map { (k, v) -> Values.struct("first" to Values.bytesLeaf(k.b), "second" to Values.ref(v.name)) }.toMutableList()
        ps.seqs[0xea8] = st.stepStats.map { s ->
            s.seqs[0x3c] = refsJson(st.heapOf(s))
            s.seqs[0x50] = wordCountsJson(st.countsOf(s))
            s.seqs[0x78] = refsJson(st.statsPending(s))
            StepScoreStats.toJson(s)
        }.toMutableList()
        return CaseResult(mem = mapOf("this" to ps))
    }
}

private val englishTrie: nintype.engine.trie.CompactTrie by lazy {
    nintype.engine.trie.TrieDump.buildEnglish(java.io.File("../re/out/trie/english"))
}

/** A CombinedTriePath from the trie area's JSON {main{path,curid,node,pos}, pnode, ppos, pdead, mdead}. */
private fun ctpOf(j: JsonValue, pend: nintype.engine.trie.PendingTrieNode): nintype.engine.trie.CombinedTriePath {
    val o = j.asObject()
    val m = o["main"]!!.asObject()
    val pb = Values.parseBytes(m["path"]!!["v"]!!.asString())
    val ids = ArrayList((0 until pb.size / 4).map { nintype.engine.trie.CompactTrie.le32(pb, 4 * it) })
    val main = nintype.engine.trie.CompactTriePath(englishTrie, ids, Leaf.fromJson(m["curid"]!!).asInt(), Leaf.fromJson(m["node"]!!).asInt(), Leaf.fromJson(m["pos"]!!).asInt())
    return nintype.engine.trie.CombinedTriePath(main, if (Leaf.fromJson(o["pnode"]!!).asInt() != 0) pend else null, Leaf.fromJson(o["ppos"]!!).asInt(),
        Leaf.fromJson(o["pdead"]!!).asInt() and 0xff != 0, Leaf.fromJson(o["mdead"]!!).asInt() and 0xff != 0)
}

private fun pendRootOf(c: VectorCase): nintype.engine.trie.PendingTrieNode {
    val o = c.inputs["pend"]!!.asObject()
    check(Leaf.fromJson(o["len"]!!).asInt() == 0)
    return nintype.engine.trie.PendingTrieNode().also {
        it.valued = Leaf.fromJson(o["valued"]!!).asInt() != 0
        it.value.shortest = Leaf.fromJson(o["minDist"]!!).asInt().toByte().toInt()
    }
}

private fun cacheJson(c: nintype.engine.search.CanSkipToCache): JsonValue {
    val b = java.io.ByteArrayOutputStream()
    for (v in c.others) { b.write(v); b.write(v ushr 8); b.write(v ushr 16); b.write(v ushr 24) }
    return Values.struct("@0x0:flags" to Leaf("u64", c.flags.readLE(0, 8)).toJson(), "@0xc:others" to Values.bytesLeaf(b.toByteArray()))
}

private fun cacheOf(j: JsonValue): nintype.engine.search.CanSkipToCache {
    val c = nintype.engine.search.CanSkipToCache()
    c.flags.writeLE(0, 8, Leaf.fromJson(j["@0x0:flags"]!!).bits)
    val b = Values.parseBytes(j["@0xc:others"]!!["v"]!!.asString())
    for (k in 0 until b.size / 4) c.others.add(nintype.engine.trie.CompactTrie.le32(b, 4 * k))
    return c
}

private fun registerSkipPorts() {
    reg("getSkipCheckerCache(Emkey::CombinedTriePath&)") { c ->
        val pend = pendRootOf(c)
        val caches = nintype.engine.search.SkipCheckerCaches()
        val outs = c.inputs["seq"]!!["\$vec"]!!.asArray().map { j -> cacheJson(caches.get(ctpOf(j, pend))) }
        CaseResult(out = mapOf("caches" to Values.vec(outs), "size" to Values.struct("@0x0" to Leaf.i32(caches.map.size).toJson())))
    }
    PortRegistry.register("Emkey::CanSkipToCache::markPossible(int)") { c ->
        val k = cacheOf(c.inputs["this"]!!)
        k.markPossible(c.args[0].asInt())
        CaseResult(out = mapOf("this" to cacheJson(k)))
    }
    PortRegistry.register("Emkey::CanSkipToCache::isPossible(int)") { c ->
        CaseResult(ret = Leaf.bool(cacheOf(c.inputs["this"]!!).isPossible(c.args[0].asInt())))
    }
}

private fun registerLetterPorts() {
    PortRegistry.register("Emkey::MooTouchPoint::checkCoverageByLetters(std::string const&, int, int)") { c ->
        val w = c.world()
        val p = w.named(c, "this")
        val word = Values.parseBytes(c.inputs["word"]!!["@0x0:s"]!!["v"]!!.asString())
        val r = nintype.engine.search.TravCoverage.checkCoverageByLetters(p, travsOf(p), word, c.args[0].asInt(), c.args[1].asInt())
        CaseResult(ret = Leaf.f32(r), mem = w.images)
    }
    reg("checkTouchCoverageByLetters(std::string const&)") { c ->
        val w = c.world()
        val word = Values.parseBytes(c.inputs["word"]!!["@0x0:s"]!!["v"]!!.asString())
        val r = nintype.engine.search.TravCoverage.checkTouchCoverageByLetters(w["this"], w.refList(w["this"], 0x1bc), ::travsOf, word)
        CaseResult(ret = Leaf.f32(r), mem = w.images)
    }
}

/** Replays a seekNextStep vector's recorded callee answers and logs the port's calls in the
 *  generator's JSON shape (see gen_search.py SeekStubs.resolve). */
private class SeekReplay(val c: VectorCase, val fd: MemImage, val where: Map<MemImage, Pair<Int, Int>>) : nintype.engine.search.SeekCallees {
    val answers = c.inputs["\$answers"]!!.asArray().map { (it as JsonValue.JNumber).value.toInt() }
    var k = 0
    val log = ArrayList<String>()
    private fun next() = answers.getOrElse(k++) { error("more callee calls than the binary made") }
    private fun at(p: MemImage) = where[p]!!.let { "${it.first},${it.second}" }
    override fun fing(pp: MemImage, side: Int): MemImage { log.add("[\"fing\",$side]"); return fd }
    override fun canAdvanceEx(pp: MemImage, p: MemImage, trav: Int, bd: MemImage): Boolean { log.add("[\"can\",${at(p)},$trav]"); return next() != 0 }
    override fun justVisited(pp: MemImage, p: MemImage): Boolean { log.add("[\"jv\",${at(p)}]"); return next() != 0 }
    override fun hasVisited(pp: MemImage, p: MemImage): Boolean { log.add("[\"hv\",${at(p)}]"); return next() != 0 }
    override fun maybeBranch(pp: MemImage, mt: MemImage, p: MemImage, trav: Int, d1: Double, d2: Double, bd: MemImage): Int {
        fun h(x: Double) = java.lang.Long.toHexString(nintype.engine.Bits.doubleToBits(x)).padStart(16, '0')
        fun u(o: Int) = bd.i32(o).toLong() and 0xFFFFFFFFL
        log.add("[\"mb\",${at(p)},$trav,\"${h(d1)}\",\"${h(d2)}\",${u(0x34)},${u(0x38)},${u(0x3c)}]")
        return next()
    }
}

private fun registerSeekPorts() {
    fun run(c: VectorCase, pass: Boolean?): CaseResult {
        val w = c.world()
        val ps = w["this"]
        val pp = c.img("pp")
        val mtj = c.inputs["mt"]!!
        val mt = MemImage.decode(mtj, "mt")
        val vecs = listOf("@0x244:points", "@0x250:points2").map { k -> mtj[k]!!["\$vec"]!!.asArray().mapIndexed { j, pj -> MemImage.decode(pj, "v$k.$j") } }
        val where = HashMap<MemImage, Pair<Int, Int>>()
        vecs.forEachIndexed { v, l -> l.forEachIndexed { j, p -> where[p] = v to j } }
        val travCount = HashMap<MemImage, Int>()
        vecs.forEach { l -> l.forEachIndexed { j, p -> } }
        fun travs(p: MemImage): List<IntArray> {
            val v = where[p]!!
            val pj = mtj[listOf("@0x244:points", "@0x250:points2")[v.first]]!!["\$vec"]!!.asArray()[v.second]
            return List(Leaf.fromJson(pj["\$travs"]!!).asInt()) { IntArray(8) }
        }
        val stats = ps.seq(0xea8).mapIndexed { k, j -> StepScoreStats.fromJson(j, ps, "this.stepStats[$k]") }.toMutableList()
        val stats84 = PathSearch.getStepStats(ps, stats, pp.i8(0x2a)).i32(0x84)
        val cb = SeekReplay(c, c.img("fd"), where)
        val gen = w.refList(ps, 0x1bc); val f1 = w.refList(ps, 0x1c8); val f0 = w.refList(ps, 0x1d4)
        fun one(second: Boolean) = nintype.engine.search.Seek.seekNextStep(ps, pp, mt, second, vecs[if (second) 1 else 0], ::travs, gen, f1, f0, stats84, cb)
        val r = if (pass != null) one(pass) else nintype.engine.search.Seek.seekNextStep(pp, mt) { one(it) }
        return CaseResult(ret = Leaf.i32(r), mem = mapOf("this" to ps),
            out = mapOf("log" to Values.vec(cb.log.map { Values.bytesLeaf(it.toByteArray()) })))
    }
    reg("seekNextStep(Emkey::PredPath&, Emkey::Mootouch&, bool)") { c -> run(c, c.args[0].asBool()) }
    reg("seekNextStep(Emkey::PredPath&, Emkey::Mootouch&)") { c -> run(c, null) }
}

private fun registerPreordainedPort() {
    reg("seekPreordainedNextSteps(Emkey::PredPath&)") { c ->
        val w = c.world()
        val ps = w["this"]
        val pp = w["pp"]
        val answers = c.inputs["\$answers"]!!.asArray().map { (it as JsonValue.JNumber).value.toInt() }
        var k = 0
        fun next() = answers.getOrElse(k++) { error("more callee calls than the binary made") }
        val names = w.images.entries.associate { (n, i) -> i to n }
        val log = ArrayList<String>()
        val stats = ps.seq(0xea8).mapIndexed { j, sj -> StepScoreStats.fromJson(sj, ps, "this.stepStats[$j]") }.toMutableList()
        val stats84 = PathSearch.getStepStats(ps, stats, pp.i8(0x2a)).i32(0x84)
        val genImg = w["gen"]
        val gen = nintype.engine.search.GenCounter(genImg.readLE(0, 8))
        val mp = ps.ptr(0x110)!!
        fun nexts(p: MemImage, off: Int) = p.seq(off).map { e ->
            nintype.engine.search.NextStep(Leaf.fromJson(e["@0x0:key"]!!).asInt(), w[e["@0x4:p"]!!["\$ref"]!!.asString()], w[e["@0x8:n"]!!["\$ref"]!!.asString()])
        }
        val nts = w.images.filterKeys { it.matches(Regex("n\\d+")) }.values
        nintype.engine.search.Seek.seekPreordainedNextSteps(ps, pp, stats84, gen, { mp.u8(0xc54) }, { mp.u8(0xc20) }, ::nexts,
            object : nintype.engine.search.PreordainedCallees {
                override fun lastfing(pp: MemImage): MemImage { log.add("[\"lastfing\",1]"); return w["lf"] }
                override fun lastotherfing(pp: MemImage): MemImage { log.add("[\"lastotherfing\",1]"); return w["lo"] }
                override fun fing(pp: MemImage, side: Int): MemImage { log.add("[\"fing\",$side]"); return w["fd${if (side in 0..2) side else 0}"] }
                override fun canAdvanceEx(pp: MemImage, bd: MemImage): Boolean {
                    val t = bd.readBytes(0, 0x20)
                    val n = nts.first { it.readBytes(0, 0x20).contentEquals(t) }
                    log.add("[\"can\",\"${names[n]}\",${bd.u8(0x20)},${bd.u8(0x21)},${bd.u8(0x22)}]")
                    return next() == 1
                }
                override fun justVisited(pp: MemImage, p: MemImage): Boolean { log.add("[\"jv\",\"${names[p]}\"]"); return next() != 0 }
                override fun maybeBranch(pp: MemImage, mt: MemImage, p: MemImage, n: MemImage, d1: Double, d2: Double, bd: MemImage): Int {
                    fun h(x: Double) = java.lang.Long.toHexString(nintype.engine.Bits.doubleToBits(x)).padStart(16, '0')
                    fun u(o: Int) = bd.i32(o).toLong() and 0xFFFFFFFFL
                    log.add("[\"mb\",\"${names[mt]}\",\"${names[p]}\",\"${names[n]}\",\"${h(d1)}\",\"${h(d2)}\",${u(0x34)},${u(0x38)},${u(0x3c)}]")
                    return next()
                }
            })
        genImg.writeLE(0, 8, gen.v)
        CaseResult(mem = w.images, out = mapOf("log" to Values.vec(log.map { Values.bytesLeaf(it.toByteArray()) })))
    }
}

/** Replays a maybeBranchInner vector: stubbed PredPath members log their calls in the
 *  generator's JSON shape (gen_search.py gen_branchinner) and answer from in.$answers. */
private class BranchReplay(val c: VectorCase) : nintype.engine.search.BranchCallees {
    val answers = c.inputs["\$answers"]!!.asArray()
    var k = 0
    val log = ArrayList<String>()
    var copies = 0
    var np: MemImage? = null
    private fun next(): JsonValue = answers.getOrElse(k++) { error("more callee calls than the binary made") }
    private fun nextInt() = (next() as JsonValue.JNumber).value.toLong().toInt()
    private fun h8(x: Float) = java.lang.Integer.toHexString(nintype.engine.Bits.floatToBits(x)).padStart(8, '0')
    private fun h16(x: Double) = java.lang.Long.toHexString(nintype.engine.Bits.doubleToBits(x)).padStart(16, '0')
    private fun q(s: String) = "\"" + s + "\""
    private fun rec(vararg xs: Any) { log.add("[" + xs.joinToString(",") { if (it is String) q(it) else it.toString() } + "]") }
    private fun write(np: MemImage, off: Int) = np.setI32(off, nextInt())

    override fun newChild(src: MemImage): MemImage {
        val n = if (copies == 0) "np" else "f${copies - 1}"
        copies++
        rec("copy", src.name)
        val d = MemImage(n, 0x208)
        if (copies == 1) np = d
        d.writeBytes(0, src.readBytes(0, 0x208))
        d.refs.putAll(src.refs); d.refNames.putAll(src.refNames)
        nintype.engine.pathscore.Vecs.put(d, 0x108, nintype.engine.pathscore.Vecs.elems(src, 0x108).toMutableList())
        return d
    }
    override fun advance(np: MemImage, c: Int) = rec("adv", 0, c)
    override fun advanceApostrophied(np: MemImage, c: Int) = rec("adv", 1, c)
    override fun advanceAposPossessive(np: MemImage, c: Int) = rec("adv", 2, c)
    override fun advanceSkipping(np: MemImage, skip: Int, c: Int) = rec("adv", 3, skip and 0xff, c)
    override fun syncSkipScoreMultForHeuristics(np: MemImage) { rec("syncSkip"); write(np, 0x144) }
    override fun syncApostate(np: MemImage) = rec("syncApostate")
    override fun syncRouteCoverageHeuristics(np: MemImage) { rec("syncRoute"); write(np, 0xd0) }
    override fun syncNotLongEnoughMultpen(np: MemImage) { rec("syncNotLongEnough"); write(np, 0xbc) }
    override fun syncFutureCoverageHeuristics(np: MemImage) { rec("syncFuture"); write(np, 0xcc) }
    override fun syncPointRevisitOn(np: MemImage, p: MemImage) = rec("syncRevisitOn", p.name)
    override fun syncPointRevisitMult(np: MemImage) { rec("syncRevisitMult"); write(np, 0xd4) }
    override fun syncBalancedNovisitMult(np: MemImage) { rec("syncNovisit"); write(np, 0x9c) }
    override fun syncClassicSlideVSCache(np: MemImage) { rec("syncClassic"); write(np, 0xe0) }
    override fun syncNeoSlideVSCache(np: MemImage) { rec("syncNeo"); write(np, 0xec) }
    override fun updateHeuristicsSubscore(np: MemImage, old: Float, new: Float) = rec("uhs", h8(old), h8(new))
    override fun maybePrune(np: MemImage, cutoff: Float, fsOff: Int): Boolean { rec("prune", h8(cutoff), fsOff); return nextInt() != 0 }
    override fun getWord(np: MemImage): ByteArray { rec("getWord"); return Values.parseBytes((next() as JsonValue.JString).value) }
    override fun markVisited(np: MemImage, p: MemImage, force: Boolean, h: MemImage) =
        rec("markVisited", p.name, if (force) 1 else 0, nintype.engine.pathscore.Vecs.elems(np, 0x108).indexOf(h))
    override fun updateVisitation(np: MemImage, t: Double, finger: Int, p: MemImage, h: MemImage) = rec("updVis", h16(t), finger, p.name)
    override fun updateVisitationComplete(r: MemImage) = rec("updVisComplete", r.name)
    override fun maybeRecordSlideVSPenalty(np: MemImage, arg: MemImage, hard: FloatArray) {
        val b = arg.readBytes(0, 0x7c)
        for (i in intArrayOf(2, 3, 0x45, 0x46, 0x47, 0x51, 0x52, 0x53, 0x7a, 0x7b)) b[i] = 0
        rec("slideVS", "0x" + b.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        hard[0] = nintype.engine.Bits.bitsToFloat(nextInt())
    }
    override fun maybeRecordHistoricNeoSlideVSFrom(np: MemImage, target: MemImage, index: Int, h: MemImage, pt: nintype.engine.math.Point2D,
                                                   rsi: MemImage, d1: Double, hard: FloatArray) {
        rec("neo", index, h16(d1)); hard[0] = nintype.engine.Bits.bitsToFloat(nextInt())
    }
    override fun canProgress(np: MemImage): Boolean { rec("canProgress"); return nextInt() == 1 }
    override fun canFinish(np: MemImage): Boolean { rec("canFinish"); return nextInt() == 1 }
    override fun determineScoreForHeuristics(np: MemImage) { rec("dsh"); write(np, 0x74) }
    override fun gatherFinisherTrieWords(np: MemImage): List<IntArray> { rec("gatherTW"); return List(nextInt()) { intArrayOf(0x100 + it, 0x5000 + 8 * it) } }
    override fun gatherFinisherPendingWords(np: MemImage): List<Any> { rec("gatherPW"); return List(nextInt()) { 0x7000 + 4 * it } }
    override fun markFinisher(r: MemImage, tw: IntArray) = rec("markFinisher", r.name, tw[0], tw[1])
    override fun markPendingFinisher(r: MemImage, pw: Any) = rec("markPending", r.name, pw)
    override fun determineFinalScore(r: MemImage) { rec("dfs", r.name); write(r, 0x74) }
    override fun pushQueue(np: MemImage) = rec("push", np.name)
    override fun regResult(r: MemImage) = rec("reg", r.name)
}

private fun registerBranchInnerPort() {
    reg("maybeBranchInner(Emkey::PredPath&, Emkey::Mootouch&, Emkey::MooTouchPoint&, Emkey::TravKeyData&, double, double, Emkey::BranchmentData&, Emkey::BranchingMode, char)") { c ->
        val w = c.world()
        val ps = w["this"]
        val pp = w["pp"]
        nintype.engine.pathscore.Vecs.link(w, pp, 0x108)
        val stats = ps.seq(0xea8).mapIndexed { j, sj -> StepScoreStats.fromJson(sj, ps, "this.stepStats[$j]") }.toMutableList()
        val mpj = c.inputs["this"]!!["@0x110:moopad"]!!["\$ptr"]!!
        val env = nintype.engine.search.BranchEnv(ps.ptr(0x110)!!, Values.parseBytes(mpj["\$traceWord"]!!["v"]!!.asString()),
            { i -> PathSearch.getStepStats(ps, stats, i) },
            { q -> q.seq(0x3c).mapIndexed { j, tj -> MemImage.decode(tj, "${q.name}.targets[$j]") } })
        val d = c.inputs["\$d"]!!.asArray()
        fun dbl(j: JsonValue) = nintype.engine.Bits.bitsToDouble(java.lang.Long.parseUnsignedLong((j as JsonValue.JString).value, 16))
        val cb = BranchReplay(c)
        val r = nintype.engine.search.Branch.maybeBranchInner(ps, pp, w["mt"], w["p0"], w["trav"], dbl(d[0]), dbl(d[1]),
            (d[2] as JsonValue.JNumber).value.toInt(), (d[3] as JsonValue.JNumber).value.toInt(), env, cb)
        val np = cb.np ?: error("no child path")
        np.seqs[0x108] = nintype.engine.pathscore.Vecs.elems(np, 0x108).map { h ->
            Values.struct("@0x0:a" to Values.bytesLeaf(h.readBytes(0, 0x18)), "@0x18:p" to Values.ref(h.refs[0x18]?.name ?: h.refNames[0x18]),
                "@0x1c:b" to Values.bytesLeaf(h.readBytes(0x1c, 0xc)))
        }.toMutableList()
        CaseResult(ret = Leaf("u8", if (r) 1L else 0L), mem = mapOf("this" to ps, "pp" to pp),
            out = mapOf("np" to np.encodeLike(c.outputs["np"]!!), "log" to Values.vec(cb.log.map { Values.bytesLeaf(it.toByteArray()) })))
    }
}

private fun registerAcNodePort() {
    fun atp(j: JsonValue) = nintype.engine.trie.AutocompleteTriePoint(j["@0x4:path"]!!["\$vec"]!!.asArray().map { Leaf.fromJson(it).asInt() },
        Leaf.fromJson(j["@0x10:curid"]!!).asInt(), Leaf.fromJson(j["@0x14:node"]!!).asInt(), Leaf.fromJson(j["@0x18:pos"]!!).asInt(),
        Leaf.fromJson(j["@0x1c:score"]!!).asFloat())
    fun json(p: nintype.engine.trie.AutocompleteTriePoint) = Values.struct("@0x0:trie" to Values.ref("trie"),
        "@0x4:path" to Values.vec(p.path.map { Leaf.i32(it).toJson() }), "@0x10:curid" to Leaf.i32(p.curid).toJson(),
        "@0x14:node" to Leaf.i32(p.node).toJson(), "@0x18:pos" to Leaf.i32(p.pos).toJson(), "@0x1c:score" to Leaf.f32(p.score).toJson())
    reg("registerAutocompleteNode(Emkey::AutocompleteTriePoint const&)") { c ->
        val w = c.world()
        val ps = w["this"]
        val nodes = c.inputs["this"]!!["@0x500:acNodes"]!!["\$vec"]!!.asArray().map(::atp).toMutableList()
        nintype.engine.search.AcSearch(ps, nodes, { error("unused") }, { _, _, _ -> error("unused") }).registerAutocompleteNode(atp(c.inputs["p"]!!))
        CaseResult(mem = mapOf("this" to ps), out = mapOf("this" to Values.struct("@0x500:acNodes" to Values.vec(nodes.map(::json)))))
    }
}

private fun registerDirCovPorts() {
    val offs = intArrayOf(0x1b0, 0x1d0, 0x1f0)
    fun bins(j: JsonValue) = j["@0x14:bins"]!!["\$vec"]!!.asArray().map { Leaf.fromJson(it).asInt() }.toIntArray()
    fun dcJson(d: nintype.engine.emkey.DirectionalCoverage) = Values.struct("@0x14:bins" to Values.vec(d.bins.map { Leaf("f32", it.toLong() and 0xFFFFFFFFL).toJson() }))
    fun dirCov(j: JsonValue) = nintype.engine.search.DirCov(nintype.engine.emkey.DirectionalCoverage(bins(j)), Leaf.fromJson(j["@0x0:tMin"]!!).asDouble(),
        Leaf.fromJson(j["@0x8:tMax"]!!).asDouble(), Leaf.fromJson(j["@0x10:hasTime"]!!).asInt())
    fun dirCovJson(d: nintype.engine.search.DirCov) = Values.struct("@0x14:bins" to Values.vec(d.dc.bins.map { Leaf("f32", it.toLong() and 0xFFFFFFFFL).toJson() }),
        "@0x0:tMin" to Leaf.f64(d.tMin).toJson(), "@0x8:tMax" to Leaf.f64(d.tMax).toJson(), "@0x10:hasTime" to Leaf("u8", d.hasTime.toLong()).toJson())
    class Ctx(val c: VectorCase) {
        val w = c.world()
        val ps = w["this"]
        val th = c.inputs["this"]!!
        val important = w.refList(ps, 0x1b0)
        val points = w.refList(ps, 0x1bc)
        val names = w.images.keys.filter { it.matches(Regex("p\\d+")) }
        val cov = HashMap<Pair<MemImage, Int>, nintype.engine.emkey.DirectionalCoverage>()
        init {
            for (n in names) for (o in offs) cov[w[n] to o] = nintype.engine.emkey.DirectionalCoverage(bins(c.inputs[n]!!["@0x${o.toString(16)}:dc${o.toString(16)}"]!!))
        }
        val h = c.inputs["start"]!!.let { s -> c.inputs["ender"]!!.let { e -> nintype.engine.search.AcHolders(Leaf.fromJson(s["@0x0:b0"]!!).asInt(),
            Leaf.fromJson(s["@0x1:b1"]!!).asInt(), Leaf.fromJson(e["@0x0:b0"]!!).asInt(), Leaf.fromJson(e["@0x1:b1"]!!).asInt()) } }
        val unit = ps.ptr(0x110)!!.f32(0x1f4a8)
        val covf: (MemImage, Int) -> nintype.engine.emkey.DirectionalCoverage = { p, o -> cov[p to o]!! }
        fun gather(slot: nintype.engine.search.DirCov, n: Int) = nintype.engine.search.DirCoverage.gatherNthChar(slot, ps, important, points,
            cmOf(th["@0x27c:siToTime"]!!), cmOf(th["@0x294:timeToSi"]!!), n, unit, h, covf)
        fun out(extraThis: List<Pair<String, JsonValue>> = emptyList()): Map<String, JsonValue> =
            mapOf("this" to Values.struct("@0x3b4:dirSynced" to Leaf("u8", ps.u8(0x3b4).toLong()).toJson(), *extraThis.toTypedArray())) +
                names.associateWith { n -> Values.struct(*offs.map { o -> "@0x${o.toString(16)}:dc${o.toString(16)}" to dcJson(cov[w[n] to o]!!) }.toTypedArray()) }
    }
    reg("gatherDirectionalCoverageForTouchPoint(Emkey::MooTouchPoint*)") { c ->
        val x = Ctx(c)
        nintype.engine.search.DirCoverage.gatherForTouchPoint(x.points, x.w["p${c.args[0].asInt()}"], x.unit, x.h, x.covf)
        CaseResult(mem = mapOf("this" to x.ps), out = x.out())
    }
    reg("syncImportantTouchDirectionalCoverages()") { c ->
        val x = Ctx(c)
        nintype.engine.search.DirCoverage.syncImportantTouchDirectionalCoverages(x.ps, x.points, x.unit, x.h, x.covf)
        CaseResult(mem = mapOf("this" to x.ps), out = x.out())
    }
    reg("gatherNthCharDirectionalCoverage(Emkey::DirectionalCoverage&, int)") { c ->
        val x = Ctx(c)
        val slot = dirCov(c.inputs["slot"]!!)
        x.gather(slot, c.args[0].asInt())
        CaseResult(mem = mapOf("this" to x.ps), out = x.out() + mapOf("slot" to dirCovJson(slot)))
    }
    fun getter(c: VectorCase, ac: Boolean): CaseResult {
        val x = Ctx(c)
        val nth = x.th["@0x3b8:nthDirCoverages"]!!["\$vec"]!!.asArray().map(::dirCov).toMutableList()
        val acc = x.th["@0x3c4:acStarterDirCoverages"]!!["\$vec"]!!.asArray().map(::dirCov).toMutableList()
        val r = if (ac) nintype.engine.search.DirCoverage.getAcStarter(x.ps.u8(0x368), nth, acc, c.args[0].asInt(), x.h, x::gather)
                else nintype.engine.search.DirCoverage.getNthChar(nth, c.args[0].asInt(), x::gather)
        val which = if (nth.any { it === r }) 0 else 1
        return CaseResult(ret = Leaf.i32((if (which == 0) nth else acc).indexOfFirst { it === r }), mem = mapOf("this" to x.ps),
            out = x.out(listOf("@0x3b8:nthDirCoverages" to Values.vec(nth.map(::dirCovJson)), "@0x3c4:acStarterDirCoverages" to Values.vec(acc.map(::dirCovJson)))) +
                mapOf("which" to Values.struct("@0x0:cache" to Leaf("u8", which.toLong()).toJson())))
    }
    reg("getNthCharDirectionalCoverage(int)") { c -> getter(c, false) }
    reg("getAutocompleteStarterDirectionalCoverage(int)") { c -> getter(c, true) }
}

private fun registerBdiPort() {
    PortRegistry.register("Emkey::BoardDirectionalIndexes::getCoverageValue(Emkey::DirectionalCoverage&)") { c ->
        val dirs = Values.parseBytes(c.inputs["this"]!!["@0x0:dirs"]!!["v"]!!.asString())
        val bins = c.inputs["dc"]!!["@0x14:bins"]!!["\$vec"]!!.asArray().map { Leaf.fromJson(it).asInt() }.toIntArray()
        CaseResult(ret = Leaf.f32(nintype.engine.search.DirCoverage.boardCoverageValue(dirs, nintype.engine.emkey.DirectionalCoverage(bins))))
    }
}

private fun registerStepPort() {
    reg("step()") { c ->
        val io = StateIO(c)
        val st = io.st
        val acc = c.inputs["accessage"]!!
        for (row in acc["\$seeded"]!!.asArray()) {
            val r = row.asArray()
            st.accessage[nintype.engine.search.StrKey(Values.parseBytes(r[0]["v"]!!.asString()))] =
                intArrayOf((r[1] as JsonValue.JNumber).value.toInt(), (r[2] as JsonValue.JNumber).value.toInt())
        }
        for (e in io.ps.seq(0xe90)) {
            val v = e["second"]!!
            st.arrivals[nintype.engine.search.StrKey(Values.parseBytes(e["first"]!!["v"]!!.asString()))] = SearchState.ArrivalPair(
                Values.parseBytes(v["@0x0:name"]!!["v"]!!.asString()), Leaf.fromJson(v["@0x4:maxLen"]!!).asInt(),
                v["@0x8:arrivals"]!!["\$vec"]!!.asArray().map { a -> IntArray(5) { k -> Leaf.fromJson(a[listOf("@0x0:step", "@0x4:score", "@0x8:f138", "@0xc:f130", "@0x10:f134")[k]]!!).asInt() } }.toMutableList())
        }
        val mts = io.w.refList(io.ps, 0x1a0)
        val log = ArrayList<String>()
        val stepped = PathSearch.step(st, mts, object : PathSearch.StepCallees {
            override fun maximumPathLength(pp: MemImage) = Leaf.fromJson(c.inputs[pp.name]!!["\$maxLen"]!!).asInt()
            override fun seekNextStep(pp: MemImage, mt: MemImage, second: Boolean): Int {
                log.add("[\"seek\",${mts.indexOf(mt)},${if (second) 1 else 0}]"); return 0
            }
            override fun seekPreordainedNextSteps(pp: MemImage) { log.add("[\"pre\",\"${pp.name}\"]") }
        })
        io.ps.seqs[0xe90] = st.arrivals.entries.map { (k, ap) ->
            Values.struct("first" to Values.bytesLeaf(k.b), "second" to Values.struct("@0x0:name" to Values.bytesLeaf(ap.name),
                "@0x4:maxLen" to Leaf.i32(ap.maxLen).toJson(), "@0x8:arrivals" to Values.vec(ap.list.map { a ->
                    Values.struct("@0x0:step" to Leaf.i32(a[0]).toJson(), "@0x4:score" to Leaf("f32", a[1].toLong() and 0xFFFFFFFFL).toJson(),
                        "@0x8:f138" to Leaf("u32", a[2].toLong() and 0xFFFFFFFFL).toJson(), "@0xc:f130" to Leaf.i32(a[3]).toJson(),
                        "@0x10:f134" to Leaf.i32(a[4]).toJson()) })))
        }.toMutableList()
        val q = acc["\$query"]!!.asArray().map { j ->
            val v = st.accessage[nintype.engine.search.StrKey(Values.parseBytes(j["v"]!!.asString()))] ?: IntArray(2)
            Values.struct("done" to Leaf("u8", v[0].toLong() and 0xff).toJson(), "count" to Leaf("i16", v[1].toLong() and 0xffff).toJson())
        }
        io.write().copy(mem = io.w.images, ret = Leaf.bool(stepped), out = mapOf("accessage" to Values.vec(q), "log" to Values.vec(log.map { Values.bytesLeaf(it.toByteArray()) })))
    }
}

private fun registerPerformPort() {
    reg("performSteps(int)") { c ->
        val f = c.inputs["flags"]!!
        fun b(k: String) = Leaf.fromJson(f[k]!!).asInt() != 0
        val k = Leaf.fromJson(f["stepsAvailable"]!!).asInt()
        val log = ArrayList<String>()
        var calls = 0
        PathSearch.performSteps(b("nopathsearch"), b("dontsearch"), { log.add("[\"cache\"]") }, { log.add("[\"learn\"]") }) {
            calls++; calls <= k
        }
        if (calls > 0) log.add("[\"step\",$calls]")
        CaseResult(out = mapOf("log" to Values.vec(log.map { Values.bytesLeaf(it.toByteArray()) })))
    }
}

private fun registerQueuePorts() {
    registerPerformPort()
    registerStepPort()
    registerPreordainedPort()
    registerBranchInnerPort()
    registerAcNodePort()
    registerDirCovPorts()
    registerBdiPort()
    registerSeekPorts()
    registerLetterPorts()
    reg("maybeBranch(Emkey::PredPath&, Emkey::Mootouch&, Emkey::MooTouchPoint&, Emkey::TravKeyData&, double, double, Emkey::BranchmentData&)") { c ->
        val ps = c.img("this")
        val bd = c.img("bd")
        val skips = Values.parseBytes(c.inputs["bd"]!!["@0x28:skips"]!!["v"]!!.asString()).map { it.toInt() and 0xff }
        val answers = c.inputs["inner"]!!["\$answers"]!!.asArray().map { (it as JsonValue.JNumber).value.toInt() }
        val calls = ArrayList<JsonValue>()
        var k = 0
        val r = PathSearch.maybeBranch(ps, bd.u8(0x23), bd.u8(0x24), bd.u8(0x25), skips) { mode, ch ->
            calls.add(Values.struct("mode" to Leaf.i32(mode).toJson(), "char" to Leaf("u8", ch.toLong() and 0xff).toJson()))
            answers.getOrElse(k++) { error("more maybeBranchInner calls than the binary made") }
        }
        CaseResult(ret = Leaf.i32(r), mem = mapOf("this" to ps), out = mapOf("calls" to Values.vec(calls)))
    }
    reg("getTopResultantPath()") { c ->
        val io = StateIO(c)
        val r = PathSearch.getTopResultantPath(io.st)
        io.write().copy(mem = io.w.images, retValue = Values.ref(r?.name))
    }
    reg("requireTopResult()") { c -> val io = StateIO(c); PathSearch.requireTopResult(io.st); io.write().copy(mem = io.w.images) }
    reg("topScore()") { c ->
        val io = StateIO(c)
        val r = PathSearch.topScore(io.st)
        io.write().copy(mem = io.w.images, ret = Leaf.f32(r))
    }
    reg("listResults(int)") { c ->
        val io = StateIO(c)
        PathSearch.listResults(io.st, c.args[0].asInt())
        io.write().copy(mem = io.w.images)
    }
    reg("regResult(Emkey::PredPath&)") { c ->
        val io = StateIO(c)
        PathSearch.regResult(io.st, io.w.named(c, "arg"))
        io.write()
    }
    registerSkipPorts()
    reg("topStep(Emkey::QueueBucket*&)") { c ->
        val io = StateIO(c)
        val (top, b) = PathSearch.topStep(io.st)
        val r = io.write()
        CaseResult(retValue = Values.ref(top?.name), mem = r.mem, out = mapOf("bucket" to Values.struct("@0x0" to Leaf.i32(b).toJson())))
    }
    reg("pushQueue(Refcounter<Emkey::PredPath, true> const&)") { c ->
        val io = StateIO(c)
        PathSearch.pushQueue(io.st, io.w.named(c, "arg"))
        io.write()
    }
    PortRegistry.register("Emkey::StepScoreStats::recordQueue(Refcounter<Emkey::PredPath, true> const&)") { c ->
        val w = c.world()
        val s = w["this"]
        val heap = w.refList(s, 0x3c).toMutableList()
        val counts = wordCountsOf(s.seq(0x50))
        StepScoreStats.recordQueue(s, heap, counts, w["ps"].i32(0x4fc), w.named(c, "arg")) { c.wordOf(it) }
        s.seqs[0x3c] = heap.map { Values.ref(it.name) }.toMutableList()
        s.seqs[0x50] = wordCountsJson(counts)
        CaseResult(mem = mapOf("this" to s))
    }
}

fun registerSearchPorts() {
    registerQueuePorts()
    registerMtSearchPorts()
    registerMootouchPorts()
    registerStepStatsPorts()
    registerCurvePortsSearch()
    registerCoveragePorts()
    registerEnderPorts()
    reg("syncBucketStepAllowance(float)") {
        val ps = MemImage("this", PathSearch.SIZE)
        PathSearch.syncBucketStepAllowance(ps, it.args[0].asFloat())
        CaseResult(mem = mapOf("this" to ps))
    }
    regB("onlyHasASingleTouch()") { val w = it.world(); PathSearch.onlyHasASingleTouch(w.refList(w["this"], 0x1a0)) }
    regB("hasResult()") { PathSearch.hasResult(it.inputs["this"]!!["@0x634:topPath"]!!["\$ptr"]!!.isNull().not()) }
    regB("finished()") {
        val ps = it.img("this")
        PathSearch.finished(ps, IntArray(4) { b -> ps.seq(PathSearch.BUCKETS + PathSearch.BUCKET_STRIDE * b + 0xc).size })
    }
    reg("getGenericLookAheadAllowance(int)") {
        val w = it.world()
        CaseResult(ret = Leaf.i32(PathSearch.getGenericLookAheadAllowance(w.refList(w["this"], 0x1bc), it.args[0].asInt())))
    }
    reg("getFingerLookAheadAllowance(int, int)") {
        val w = it.world()
        val off = if (it.args[0].asInt() == 1) 0x1c8 else 0x1d4
        CaseResult(ret = Leaf.i32(PathSearch.getFingerLookAheadAllowance(w.refList(w["this"], off), it.args[1].asInt())))
    }
    regB("isACForceGlancingTouch(Emkey::MooTouchPoint*, Emkey::MooTouchPoint*)") {
        val w = it.world()
        val unit = w["this"].ptr(0x110)!!.f32(0x1f4a8)
        PathSearch.isACForceGlancingTouch(w.named(it, "a"), w.named(it, "b"), unit)
    }
    regB("touchPointIsNotMultiTouching(Emkey::MooTouchPoint*, float)") {
        val w = it.world()
        PathSearch.touchPointIsNotMultiTouching(w.refList(w["this"], 0x1a0), w["tp"], it.args[0].asFloat())
    }
    val vecArg = "(std::vector<Emkey::MooTouchPoint*, std::allocator<Emkey::MooTouchPoint*>>&)"
    for ((n, fn) in listOf<Pair<String, (List<MemImage>) -> Unit>>(
        "syncSamefingLattice" to PathSearch::syncSamefingLattice,
        "syncSamefingImportantLattice" to PathSearch::syncSamefingImportantLattice,
        "syncFingerSpecifForelookAllowances" to PathSearch::syncFingerSpecifForelookAllowances,
    )) reg("$n$vecArg") {
        val w = it.world()
        fn(w.refList(w["v"], 0))
        CaseResult(mem = w.images)
    }
    reg("syncGenericForelookAllowances()") {
        val w = it.world()
        val ps = w["this"]
        PathSearch.syncGenericForelookAllowances(ps, w.refList(ps, 0x1bc), w.refList(ps, 0x1c8), w.refList(ps, 0x1d4))
        CaseResult(mem = w.images)
    }
    reg("syncOtherFingIndexes(int)") {
        val w = it.world()
        PathSearch.syncOtherFingIndexes(w.refList(w["this"], 0x1b0), it.args[0].asInt())
        CaseResult(mem = w.images)
    }
    reg("modifyWordScoreDueToChainRanker(std::string const&, float)") {
        val m = LinkedHashMap<String, Float>()
        for (e in it.img("this").seq(0x3a0)) m[String(e["first"]!!.bytesOf(), Charsets.ISO_8859_1)] = Leaf.fromJson(e["second"]!!).asFloat()
        val word = String(it.inputs["word"]!!["@0x0:s"]!!.bytesOf(), Charsets.ISO_8859_1)
        CaseResult(ret = Leaf.f32(PathSearch.modifyWordScoreDueToChainRanker(m, word, it.args[0].asFloat())))
    }
}

/** Runs [body] with this@0xea8 decoded into a StepScoreStats list, then writes it back. */
private fun withStats(case: VectorCase, body: (MemImage, MutableList<MemImage>) -> Leaf?): CaseResult {
    val ps = case.img("this")
    val stats = ps.seq(0xea8).mapIndexed { k, j -> StepScoreStats.fromJson(j, ps, "this.stepStats[$k]") }.toMutableList()
    val r = body(ps, stats)
    ps.seqs[0xea8] = stats.map { StepScoreStats.toJson(it) }.toMutableList()
    return CaseResult(ret = r, mem = mapOf("this" to ps))
}

private fun registerStepStatsPorts() {
    reg("getStepStats(int)") { c -> withStats(c) { ps, st -> val s = PathSearch.getStepStats(ps, st, c.args[0].asInt()); Leaf.i32(st.indexOf(s)) } }
    reg("syncStepAllowances()") { c -> withStats(c) { ps, st -> PathSearch.syncStepAllowances(ps, st); null } }
    reg("preallocateSteps(int, int)") { c -> withStats(c) { ps, st -> PathSearch.preallocateSteps(ps, st, c.args[0].asInt(), c.args[1].asInt()); null } }
    reg("branchCutoffScore(int)") { c ->
        withStats(c) { ps, st -> Leaf.f32(PathSearch.branchCutoffScore(ps, st, ps.ptr(0x110)!!.u8(0x333f8), c.args[0].asInt())) }
    }
}

private fun cmOf(j: JsonValue): CurveMapper {
    val c = CurveMapper()
    for (p in j["@0x4:map"]!!["\$map"]!!.asArray()) c.put(Leaf.fromJson(p["first"]!!).asFloat(), Leaf.fromJson(p["second"]!!).asFloat())
    return c
}

private fun cmJson(c: CurveMapper): JsonValue = Values.struct("@0x4:map" to Values.map((0 until c.size).map {
    Values.struct("first" to Leaf("f32", c.keyBits(it).toLong() and 0xFFFFFFFFL).toJson(),
        "second" to Leaf("f32", c.valueBits(it).toLong() and 0xFFFFFFFFL).toJson())
}))

private fun registerCurvePortsSearch() {
    reg("checkTrieNodeEnderLengthMult(int)") { CaseResult(ret = Leaf.f32(PathSearch.checkTrieNodeEnderLengthMult(it.img("this"), it.args[0].asInt()))) }
    regB("checkTrieNodeLengthMultSatisfaction(int, float)") {
        PathSearch.checkTrieNodeLengthMultSatisfaction(it.img("this"), it.args[0].asInt(), it.args[1].asFloat())
    }
    reg("syncWordMultness()") {
        val w = it.world()
        val ps = w["this"]
        PathSearch.syncWordMultness(ps, w.refList(ps, 0x1a0).size == 1)
        CaseResult(mem = mapOf("this" to ps))
    }
    reg("getNthCharPlanarCoverageTimes(int, float&, float&, float&)") {
        val th = it.inputs["this"]!!
        val (lo, mid, hi) = PathSearch.getNthCharPlanarCoverageTimes(cmOf(th["@0x27c:siToTime"]!!), cmOf(th["@0x294:timeToSi"]!!), it.args[0].asInt())
        fun o(v: Float) = Values.struct("@0x0" to Leaf.f32(v).toJson())
        CaseResult(out = mapOf("lo" to o(lo), "mid" to o(mid), "hi" to o(hi)))
    }
    reg("syncSoftIndexToTimeCurvemap()") {
        val w = it.world()
        val ps = w["this"]
        val th = it.inputs["this"]!!.asObject()
        val keys = th.keys.filter { k -> k.startsWith("@0x") && (MemKeysOff(k) in 0x27c..0x30c) }
        val curves = keys.associate { k -> MemKeysOff(k) to cmOf(th[k]!!) }
        PathSearch.syncSoftIndexToTimeCurvemap(ps, w.refList(ps, 0x1bc), w.refList(ps, 0x1c8), w.refList(ps, 0x1d4),
            w.refList(ps, 0x1a0).size == 1, curves)
        CaseResult(out = mapOf("this" to JsonValue.JObject(LinkedHashMap(keys.associateWith { k -> cmJson(curves[MemKeysOff(k)]!!) }))))
    }
}

private fun pcOf(j: JsonValue): PlanarCoverage =
    PlanarCoverage(areas = j["@0x14:areas"]!!["\$vec"]!!.asArray().map { Leaf.fromJson(it).asInt() }.toMutableList())

private fun registerCoveragePorts() {
    reg("checkFullCoverage(int)") {
        CaseResult(ret = Leaf.f32(PathSearch.checkFullCoverage(pcOf(it.inputs["this"]!!["@0x410:fullCoverage"]!!), it.args[0].asInt())))
    }
    reg("checkTrieNodeEnderMult(int, int)") {
        val th = it.inputs["this"]!!
        CaseResult(ret = Leaf.f32(PathSearch.checkTrieNodeEnderMult(it.img("this"), pcOf(th["@0x438:enderCoverage1"]!!), it.args[0].asInt(), it.args[1].asInt())))
    }
    regB("checkTrieNodeMultiPossibleEndersCoverageSatisfaction(int, Shing::Flag64&, float)") {
        val th = it.inputs["this"]!!
        val enders = (1..3).map { w -> pcOf(th.asObject().entries.first { e -> e.key.endsWith(":enderCoverage$w") }.value) }
        val flags = Leaf.fromJson(it.inputs["flags"]!!["@0x0:bits"]!!).bits
        PathSearch.checkTrieNodeMultiPossibleEndersCoverageSatisfaction(enders, it.args[0].asInt(), flags, it.args[1].asFloat())
    }
    reg("checkCoverage(int, int, int, int)") {
        val th = it.inputs["this"]!!
        val nth = th["@0x3d0:nthCharCoverages"]!!["\$vec"]!!.asArray().map(::pcOf)
        CaseResult(ret = Leaf.f32(PathSearch.checkCoverage(it.img("this"), nth, it.args[0].asInt(), it.args[1].asInt(), it.args[2].asInt(), it.args[3].asInt())))
    }
}

private fun pcFull(j: JsonValue): PlanarCoverage {
    val pc = pcOf(j)
    pc.tMin = Leaf.fromJson(j["@0x0:tMin"]!!).asDouble()
    pc.tMax = Leaf.fromJson(j["@0x8:tMax"]!!).asDouble()
    pc.hasTime = Leaf.fromJson(j["@0x10:hasTime"]!!).asInt()
    pc.maxCoverage = Leaf.fromJson(j["@0x20:maxCoverage"]!!).asFloat()
    return pc
}

private fun pcFullJson(pc: PlanarCoverage): JsonValue = Values.struct(
    "@0x0:tMin" to Leaf.f64(pc.tMin).toJson(), "@0x8:tMax" to Leaf.f64(pc.tMax).toJson(),
    "@0x10:hasTime" to Leaf("u8", pc.hasTime.toLong() and 0xFF).toJson(),
    "@0x20:maxCoverage" to Leaf.f32(pc.maxCoverage).toJson(),
    "@0x14:areas" to Values.vec(pc.areas.map { Leaf("f32", it.toLong() and 0xFFFFFFFFL).toJson() }))

private fun travsOf(p: MemImage): List<Pair<Int, Float>> = p.seq(0x328).map {
    Leaf.fromJson(it["@0x8:chara"]!!).asInt() to Leaf.fromJson(it["@0x10:score"]!!).asFloat()
}

private fun firstRef(w: MemWorld, ps: MemImage, off: Int): MemImage? =
    ps.seq(off).firstOrNull()?.let { r -> r["\$ref"]!!.asStringOrNull()?.let { n -> w[n] } }

private fun registerEnderPorts() {
    reg("gatherNthCharPlanarCoverage(Emkey::PlanarCoverage&, int)") {
        val w = it.world()
        val th = it.inputs["this"]!!
        val pc = pcFull(it.inputs["pc"]!!)
        val af = it.inputs["acforce"]!!
        PathSearch.gatherNthCharPlanarCoverage(pc, w.refList(w["this"], 0x1b0), cmOf(th["@0x27c:siToTime"]!!), cmOf(th["@0x294:timeToSi"]!!),
            it.args[0].asInt(), ::travsOf, Leaf.fromJson(af["@0x0:b0"]!!).asInt(), Leaf.fromJson(af["@0x1:b1"]!!).asInt())
        CaseResult(out = mapOf("pc" to pcFullJson(pc)))
    }
    reg("getNthCharPlanarCoverage(int)") {
        val w = it.world()
        val ps = w["this"]
        val th = it.inputs["this"]!!
        val cache = th["@0x3dc:nthCharPlanarCoverages"]!!["\$vec"]!!.asArray().map(::pcFull).toMutableList()
        val r = PathSearch.getNthCharPlanarCoverage(cache, w.refList(ps, 0x1b0), cmOf(th["@0x27c:siToTime"]!!), cmOf(th["@0x294:timeToSi"]!!),
            it.args[0].asInt(), ::travsOf)
        CaseResult(ret = Leaf.i32(cache.indexOf(r)),
            out = mapOf("this" to Values.struct("@0x3dc:nthCharPlanarCoverages" to Values.vec(cache.map(::pcFullJson)))), mem = mapOf("this" to ps))
    }
    reg("getAutocompleteStarterCoverage(int)") {
        val w = it.world()
        val ps = w["this"]
        val th = it.inputs["this"]!!
        val nth = th["@0x3dc:nthCharPlanarCoverages"]!!["\$vec"]!!.asArray().map(::pcFull).toMutableList()
        val ac = th["@0x3f4:acStarterCoverages"]!!["\$vec"]!!.asArray().map(::pcFull).toMutableList()
        val r = PathSearch.getAutocompleteStarterCoverage(ps.u8(0x368), nth, ac, w.refList(ps, 0x1b0), cmOf(th["@0x27c:siToTime"]!!),
            cmOf(th["@0x294:timeToSi"]!!), it.args[0].asInt(), ::travsOf)
        val which = if (nth.any { c -> c === r }) 0 else 1
        CaseResult(ret = Leaf.i32((if (which == 0) nth else ac).indexOfFirst { c -> c === r }),
            out = mapOf("this" to Values.struct("@0x3dc:nthCharPlanarCoverages" to Values.vec(nth.map(::pcFullJson)),
                "@0x3f4:acStarterCoverages" to Values.vec(ac.map(::pcFullJson))),
                "which" to Values.struct("@0x0:cache" to Leaf("u8", which.toLong()).toJson())), mem = mapOf("this" to ps))
    }
    reg("gatherAutoCompleteEnderPlanarCoverage(Emkey::PlanarCoverage&, int)") {
        val w = it.world()
        val pc = pcFull(it.inputs["pc"]!!)
        PathSearch.gatherAutoCompleteEnderPlanarCoverage(pc, w.refList(w["this"], 0x1b0), it.args[0].asInt(), ::travsOf)
        CaseResult(out = mapOf("pc" to pcFullJson(pc)))
    }
    reg("getAutocompleteEnderCoverage(int)") {
        val w = it.world()
        val ps = w["this"]
        val cache = it.inputs["this"]!!["@0x400:acEnderCoverages"]!!["\$vec"]!!.asArray().map(::pcFull).toMutableList()
        val r = PathSearch.getAutocompleteEnderCoverage(cache, w.refList(ps, 0x1b0), it.args[0].asInt(), ::travsOf)
        CaseResult(ret = Leaf.i32(cache.indexOf(r)),
            out = mapOf("this" to Values.struct("@0x400:acEnderCoverages" to Values.vec(cache.map(::pcFullJson)))), mem = mapOf("this" to ps))
    }
    reg("gatherEnderPlanarCoverage(int, Emkey::PlanarCoverage&)") {
        val w = it.world()
        val pc = pcFull(it.inputs["pc"]!!)
        PathSearch.gatherEnderPlanarCoverage(w.refList(w["this"], 0x1b0), it.args[0].asInt(), pc, ::travsOf)
        CaseResult(out = mapOf("pc" to pcFullJson(pc)))
    }
    reg("gatherSlowSlideCoverage(Emkey::PlanarCoverage&, int)") {
        val w = it.world()
        val ps = w["this"]
        val pc = pcFull(it.inputs["pc"]!!)
        PathSearch.gatherSlowSlideCoverage(pc, w.refList(ps, 0x1a0).size == 1, firstRef(w, ps, 0x1b0), it.args[0].asInt(), ::travsOf)
        CaseResult(out = mapOf("pc" to pcFullJson(pc)))
    }
    reg("getSlowSlideCoverage(int)") {
        val w = it.world()
        val ps = w["this"]
        val cache = it.inputs["this"]!!["@0x3e8:slowSlideCoverages"]!!["\$vec"]!!.asArray().map(::pcFull).toMutableList()
        val r = PathSearch.getSlowSlideCoverage(cache, w.refList(ps, 0x1a0).size == 1, firstRef(w, ps, 0x1b0), it.args[0].asInt(), ::travsOf)
        CaseResult(ret = Leaf.i32(cache.indexOf(r)),
            out = mapOf("this" to Values.struct("@0x3e8:slowSlideCoverages" to Values.vec(cache.map(::pcFullJson)))), mem = mapOf("this" to ps))
    }
    reg("gatherRoughEstimatedPlanarCoverages()") {
        val w = it.world()
        val ps = w["this"]
        val th = it.inputs["this"]!!
        val full = pcFull(th["@0x410:fullCoverage"]!!)
        val nth = th["@0x3d0:nthCharCoverages"]!!["\$vec"]!!.asArray().map(::pcFull).toMutableList()
        val first = ps.seq(0x1b0).firstOrNull()?.let { r -> r["\$ref"]!!.asStringOrNull()?.let { n -> w[n] } }
        PathSearch.gatherRoughEstimatedPlanarCoverages(ps, first, full, nth, ::travsOf)
        CaseResult(out = mapOf("this" to Values.struct("@0x410:fullCoverage" to pcFullJson(full),
            "@0x3d0:nthCharCoverages" to Values.vec(nth.map(::pcFullJson)))), mem = mapOf("this" to ps))
    }
    reg("gatherEnderPlanarCoverage(Emkey::PlanarCoverage&, Emkey::MooTouchPoint*, int)") {
        val w = it.world()
        val j = it.inputs["pc"]!!
        val pc = pcFull(j)
        PathSearch.gatherEnderPlanarCoverage(pc, w.named(it, "start"), it.args[0].asInt(), ::travsOf)
        CaseResult(out = mapOf("pc" to pcFullJson(pc)))
    }
    reg("gatherEnderPlanarCoverages()") {
        val w = it.world()
        val ps = w["this"]
        val th = it.inputs["this"]!!
        val offs = listOf(0x438, 0x460, 0x488)
        val enders = offs.map { o -> pcFull(th["@0x${o.toString(16)}"]!!) }
        PathSearch.gatherEnderPlanarCoverages(w.refList(ps, 0x1b0), enders, ::travsOf)
        CaseResult(out = mapOf("this" to Values.struct(*offs.mapIndexed { k, o -> "@0x${o.toString(16)}" to pcFullJson(enders[k]) }.toTypedArray())),
            mem = mapOf("this" to ps))
    }
}

private fun MemKeysOff(k: String): Int = nintype.engine.vectors.MemKeys.offsetOf(k)!!

private fun VectorCase.img(name: String) = MemImage.decode(inputs[name]!!, name)
