package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.CurveMapper
import nintype.engine.math.FastCurveMapper
import nintype.engine.touch.PlanarCoverage
import nintype.engine.trie.CompactTrie
import nintype.engine.trie.PendingTrieNode
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import nintype.engine.vectors.Values

/** What resetEx reads besides the PathSearch and the touches. */
class ResetEnv(
    val gather: GatherEnv,
    /** RoenGL::globaltimestamp, as f64 bits (PathSearch +0x188). */
    val gtsBits: Long,
    /** The RoenGL surface's int at +0xb28 (debug-draw origin, PathSearch +0xe50 = it - 20). */
    val roenB28: Int,
    /** Moopad Xnl +0x31f28 (chain-ranker seeding) and the recent words (+0x31f1c handles): word, weight. */
    val chainXnl: Int = 0,
    val chainWords: List<Pair<ByteArray, Float>> = emptyList(),
)

object Reset {
    private fun b(i: Long) = i.toInt()
    /** resetEx's chain-ranker curve: weight {0 -> 0, 1.4 -> 0.4, 4 -> 0.6, 10 -> 0.8, 15 -> 1}. */
    val CHAIN = FastCurveMapper.ofBits(0, 0, b(0x3fb33333), b(0x3ecccccd), b(0x40800000), b(0x3f19999a), b(0x41200000), b(0x3f4ccccd),
        b(0x41700000), b(0x3f800000))
    /** FunnelStats resetEx initialises: count (+0) = 0, then four -1 words. */
    val FUNNELS = intArrayOf(0x684, 0x698, 0x6ac, 0x6c0, 0x6d4, 0x6e8, 0x6fc, 0x710, 0x724, 0x738, 0x74c, 0x760, 0x774, 0x788, 0x79c,
        0x7b0, 0x7c4, 0x7d8, 0x7ec, 0x800, 0x814, 0x828, 0x83c, 0x850, 0x864, 0x878, 0x88c, 0x8a0, 0x8b4, 0x8c8, 0x8dc, 0x8f0, 0x904,
        0x918, 0x92c, 0x940, 0x954, 0x968, 0x97c, 0x990, 0x9a4)

    /** resetAndEmptyVariables() @007d7778: the scalar half (the containers are rebuilt by the
     * caller: the search state, caches and curves start empty). */
    fun resetAndEmptyVariables(ps: MemImage, mp: MemImage) {
        ps.setU8(0x35a, 0); ps.setI32(0x124, 0); ps.setI32(0x13c, 0); ps.setU8(0x3b4, 0); ps.setI32(0x50c, 0)
        for (o in Gather.CURVE_OFFS) { ps.seqs[o + 4] = mutableListOf(); ps.setI32(o + 0x14, 0) }
        for (o in intArrayOf(0x3b8, 0x3c4, 0x3d0, 0x3dc, 0x3e8, 0x3f4, 0x400, 0x500, 0x378, 0x384, 0x390, 0x644, 0x650, 0x9b8, 0x9d0,
            0x614, 0x638, 0xe68, 0x1a0, 0x1b0, 0x1bc, 0x1e0, 0x1ec, 0x1c8, 0x1d4, 0x228)) ps.seqs[o] = mutableListOf()
        ps.setI32(0xd68, 0); ps.refs.remove(0xd68)
        ps.setU8(0xe58, 1); ps.setI32(0xe60, 0); ps.setI32(0xe64, 0); ps.setI32(0x65c, 0)
        ps.setI32(0x9c4, -1); ps.setI32(0x9c8, 0); ps.refs.remove(0x9c8)
        ps.setU8(0x368, 0); ps.setU8(0x369, 0); ps.setI32(0xd60, -1); ps.setI32(0xd64, -1)
        ps.setU8(0x330, 0); ps.setI32(0x33c, 0); ps.setI32(0x338, 0); ps.setI32(0x340, 0)
        ps.setU8(0x9cc, 0); ps.setI32(0x9dc, -1); ps.setI32(0x53c, 0)
        ps.setI32(0x4fc, mp.i32(0x333f0))
        ps.setU8(0x35d, if (mp.i32(0x1f204) != 0) 1 else 0)
        ps.setI32(0x610, 0); ps.refs.remove(0x610)
        ps.setI32(0xea4, 0)
        for (bk in 0 until 4) {
            val base = PathSearch.BUCKETS + PathSearch.BUCKET_STRIDE * bk
            ps.seqs[base + 0xc] = mutableListOf(); ps.seqs[base + 0x1c] = mutableListOf()
            ps.setI32(base + 0x2c, 0); ps.setI32(base + 0x30, 0)
        }
        ps.setI32(0x634, 0); ps.setI32(0xe8c, 0); ps.setU8(0x660, 0)
        ps.setF32(0x664, -999f); ps.setF32(0x668, -999f)
        ps.setU8(0x350, 0); ps.setI32(0x3b0, 0)
        ps.setI32(0x66c, 1); for (o in intArrayOf(0x670, 0x674, 0x678, 0x67c, 0x680)) ps.setI32(o, 0)
        ps.setI32(0xe88, 0)
        for (o in intArrayOf(0x20c, 0x224, 0x248, 0x260, 0x278)) ps.setI32(o, 0)
    }

    /**
     * PathSearch::resetEx(int a, int b) @007d69e4 (reset(a, b) first clears +0x114, +0x118 and
     * +0x11c): rebuilds the search from the Moopad's sitting touches ([sitting], list order) in
     * world [w] and returns a [SearchEngine] holding the root path. [prev] = the PathSearch's
     * previous containers as the gen_search_e2e.py "ps" JSON (step statistics, the embedded
     * PlanarCoverages); the dictionary coverage map is emptied when the previous search had
     * touches (LangEnv::remapTrieAndWords).
     */
    fun resetEx(w: MemWorld, prev: JsonValue, sitting: List<MemImage>, a: Int, bArg: Int, env: ResetEnv, trie: CompactTrie,
                pendRoot: PendingTrieNode, gen0: Long, board: Set<Int>, strs: Map<Int, ByteArray>): SearchEngine {
        val sw = SearchWorld(w)
        val ps = sw.ps
        val mp = sw.mp
        ps.writeLE(0x188, 8, env.gtsBits)
        if (ps.seq(0x1a0).isNotEmpty()) trie.coverage.clear()
        resetAndEmptyVariables(ps, mp)
        ps.setI32(0x194, a); ps.setI32(0x198, bArg)
        val n120 = ps.i32(0x120)
        ps.setI32(0x120, if (n120 < -1) 0 else n120 + 1)
        for (f in FUNNELS) { ps.setI32(f, 0); for (k in 1..4) ps.setI32(f + 4 * k, -1) }
        ps.setU8(0x190, 0)
        val stats = prev["@0xea8:stepStats"]!!["\$vec"]!!.asArray().mapIndexed { k, j ->
            StepScoreStats.fromJson(j, ps, "ps.stepStats[$k]").also { it.refs[0] = ps; StepScoreStats.reset(it) }
        }.toMutableList()
        ps.setU8(0xeb4, 0)
        val curves = HashMap<Int, CurveMapper>()
        Gather.gatherTouches(sw, sitting, env.gather, curves)
        val mts = sw.refs(ps, 0x1a0)
        val important = sw.refs(ps, 0x1b0)
        PathSearch.predictWordLength(ps, mts.map { sw.refs(it, 0x244) }, sw.refs(ps, 0x1bc))
        val travs = { p: MemImage -> sw.refs(p, 0x328).map { it.i32(8) to it.f32(0x10) } }
        val enderKeys = listOf("@0x438:enderCoverage1", "@0x460:enderCoverage2", "@0x488:enderCoverage3")
        val enders = enderKeys.map { SearchWorld.pcOf(prev[it]!!) }
        PathSearch.gatherEnderPlanarCoverages(important, enders, travs)
        val full = SearchWorld.pcOf(prev["@0x410:fullCoverage"]!!)
        val nth = ArrayList<PlanarCoverage>()
        PathSearch.gatherRoughEstimatedPlanarCoverages(ps, important.firstOrNull(), full, nth, travs)
        fun store(o: Int, pc: PlanarCoverage) { ps.setF64(o, pc.tMin); ps.setF64(o + 8, pc.tMax); ps.setU8(o + 0x10, pc.hasTime); ps.setF32(o + 0x20, pc.maxCoverage) }
        store(0x410, full); for ((k, pc) in enders.withIndex()) store(0x438 + 0x28 * k, pc)
        PathSearch.syncStepAllowances(ps, stats)
        val chain = ArrayList<Pair<ByteArray, Float>>()
        if (env.chainXnl == 1) for ((word, wgt) in env.chainWords.take(8)) {
            if (!(fcmple(wgt, Bits.bitsToFloat(0x3c23d70a)) || fcmpun(wgt, Bits.bitsToFloat(0x3c23d70a)))) chain.add(word to CHAIN.get(wgt))
        }
        ps.setF32(0xe4c, 20f)
        ps.setF32(0xe50, i2f(env.roenB28 - 20))
        var maxSide = 0
        for (m in mts) if (maxSide <= m.i8(0xec)) maxSide = m.i8(0xec)
        ps.setF32(0xe54, i2f(-0x3c - (if (mts.isEmpty()) 0 else maxSide * 0x32)))
        // the engine over this state
        val chainMap = java.util.TreeMap<StrKey, Float>()
        for ((k, v) in chain) chainMap[StrKey(k)] = v
        val psj = JsonValue.JObject(linkedMapOf(
            "@0x27c:siToTime" to SearchWorld.cmJson(curves[0x27c]!!), "@0x294:timeToSi" to SearchWorld.cmJson(curves[0x294]!!),
            "@0x3d0:nthCharCoverages" to Values.vec(nth.map(SearchWorld::pcJson)),
            "@0x438:enderCoverage1" to SearchWorld.pcJson(enders[0]),
            "@0x3a0:chain" to Values.map(chainMap.map { (k, v) -> Values.struct("first" to Values.bytesLeaf(k.b), "second" to nintype.engine.vectors.Leaf.f32(v).toJson()) }),
            "@0xea8:stepStats" to Values.vec(stats.map { StepScoreStats.toJson(it) })))
        ps.setI32(0x3b0, chainMap.size)
        val e = SearchEngine(w, trie, pendRoot, gen0, board, strs)
        e.load(psj, clearCoverage = false)
        e.enders = enders; e.full = full
        val root = sw.add(MemImage("root0", 0x208))
        nintype.engine.pathscore.PredPathCtor.construct(root, ps, trie, pendRoot, 0)
        root.setF32(0x74, Bits.bitsToFloat(0x497423f0))  // 999999f
        PathSearch.pushQueue(e.st, root)
        return e
    }
}
