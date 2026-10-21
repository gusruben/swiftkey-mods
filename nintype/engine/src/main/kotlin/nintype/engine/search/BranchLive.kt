package nintype.engine.search

import nintype.engine.math.Point2D
import nintype.engine.pathscore.PredPath
import nintype.engine.pathscore.PredPathCtor
import nintype.engine.pathscore.PredPathTrie
import nintype.engine.pathscore.SlideHistory
import nintype.engine.pathscore.Visitation
import nintype.engine.vectors.MemImage

/**
 * maybeBranchInner's callees bound to the ported PredPath members (pathscore area) and this
 * area's queue / result functions. Compiles against the ports; not yet exercised end to end
 * (the maybeBranchInner vectors replay recorded callee answers).
 *
 * [mp] is the Moopad, [compat] the trie area's CompatSearch for route coverage,
 * [funnels] the PathSearch image holding the maybePrune FunnelStats, [finalScore] is
 * FinalScore.determineFinalScore with the board / chain-ranker inputs bound.
 * Pending-word finishers are not modelled (gatherFinisherPendingWords returns none).
 */
class LiveBranchCallees(
    val st: SearchState,
    val mp: MemImage,
    val compat: nintype.engine.trie.CompatSearch,
    val funnels: MemImage,
    val finalScore: (MemImage) -> Unit,
    /** When set, every pushQueue / regResult as (kind, word, score bits, pathsize). */
    val trace: MutableList<List<Any>>? = null,
    val routeInfo: ((String) -> Unit)? = null,
) : BranchCallees {
    /** When set, the gen_search_e2e.py "neos" diagnostic: syncNeoSlideVSCache returns
     * ["sync", hex of np +0xe4..+0xf0] and maybeRecordHistoricNeoSlideVSFrom returns ["mrh", target point, ran, hex]. */
    var neos: MutableList<List<Any>>? = null
    private fun neoHex(np: MemImage) = np.readBytes(0xe4, 12).joinToString("") { "%02x".format(it) }
    private var serial = 0
    override fun newChild(src: MemImage): MemImage {
        val np = MemImage("pp#${serial++}", PredPath.SIZE)
        PredPathCtor.copyConstruct(np, src)
        return np
    }
    override fun advance(np: MemImage, c: Int) = PredPathTrie.advance(np, c)
    override fun advanceApostrophied(np: MemImage, c: Int) = PredPathTrie.advanceApostrophied(np, c)
    override fun advanceAposPossessive(np: MemImage, c: Int) = PredPathTrie.advanceAposPossessive(np, c)
    override fun advanceSkipping(np: MemImage, skip: Int, c: Int) = PredPathTrie.advanceSkipping(np, skip, c)
    override fun syncSkipScoreMultForHeuristics(np: MemImage) = PredPathTrie.syncSkipScoreMultForHeuristics(np)
    override fun syncApostate(np: MemImage) = PredPath.syncApostate(np)
    override fun syncRouteCoverageHeuristics(np: MemImage) {
        PredPathTrie.syncRouteCoverageHeuristics(np) { PredPathTrie.routeCoverage(np, compat) }
        trace?.add(listOf("route", np.i32(0xd0).toLong() and 0xffffffffL, np.ref(PredPath.OFF_PS)!!.i32(0x124).toLong() and 0xffffffffL))
        val c = nintype.engine.pathscore.Ctp.of(np)
        routeInfo?.invoke("dict=${Integer.toHexString(java.lang.Float.floatToRawIntBits(if (!c.mainDead) nintype.engine.trie.Compat.coverageValue(c.main, c.main.trie.coverage) else 0f))} pdead=${c.pendDead} mdead=${c.mainDead} pstamp=${c.pnode?.value?.stamp} pval=${c.pnode?.value?.bound} node=${c.main.node} pos=${c.main.pos} path=${c.main.path} word=${String(PredPathTrie.getWord(np), Charsets.ISO_8859_1)}")
    }
    override fun syncNotLongEnoughMultpen(np: MemImage) { PredPathTrie.syncNotLongEnoughMultpen(np) }
    override fun syncFutureCoverageHeuristics(np: MemImage) = PredPath.syncFutureCoverageHeuristics(np)
    override fun syncPointRevisitOn(np: MemImage, p: MemImage) = PredPath.syncPointRevisitOn(np, p)
    override fun syncPointRevisitMult(np: MemImage) = PredPath.syncPointRevisitMult(np)
    override fun syncBalancedNovisitMult(np: MemImage) = PredPath.syncBalancedNovisitMult(np)
    override fun syncClassicSlideVSCache(np: MemImage) = PredPath.syncClassicSlideVSCache(np)
    override fun syncNeoSlideVSCache(np: MemImage) { PredPath.syncNeoSlideVSCache(np); neos?.add(listOf("sync", neoHex(np))) }
    override fun updateHeuristicsSubscore(np: MemImage, old: Float, new: Float) = PredPath.updateHeuristicsSubscore(np, old, new)
    override fun maybePrune(np: MemImage, cutoff: Float, fsOff: Int): Boolean {
        val r = PredPath.maybePrune(np, cutoff, funnels, fsOff)
        trace?.add(listOf("prune", fsOff.toLong(), np.i32(0x74).toLong() and 0xffffffffL, if (r) 1 else 0, java.lang.Float.floatToRawIntBits(cutoff).toLong() and 0xffffffffL))
        return r
    }
    override fun getWord(np: MemImage): ByteArray = PredPathTrie.getWord(np)
    override fun markVisited(np: MemImage, p: MemImage, force: Boolean, h: MemImage) = Visitation.markVisited(np, p, force, h)
    override fun updateVisitation(np: MemImage, t: Double, finger: Int, p: MemImage, h: MemImage) = Visitation.updateVisitation(np, t, finger)
    override fun updateVisitationComplete(r: MemImage) { Visitation.updateVisitationComplete(r) }
    private fun withHard(hard: FloatArray, f: (MemImage) -> Unit) {
        val img = MemImage("hard", 4)
        img.setF32(0, hard[0])
        f(img)
        hard[0] = img.f32(0)
    }
    override fun maybeRecordSlideVSPenalty(np: MemImage, arg: MemImage, hard: FloatArray) =
        withHard(hard) { PredPath.maybeRecordSlideVSPenalty(np, mp, arg, it, 0) }
    override fun maybeRecordHistoricNeoSlideVSFrom(np: MemImage, target: MemImage, index: Int, h: MemImage, pt: Point2D, rsi: MemImage,
                                                   d1: Double, hard: FloatArray) =
        withHard(hard) { val r = SlideHistory.maybeRecordHistoricNeoSlideVSFrom(np, target, h, pt, mp, it, 0); neos?.add(listOf("mrh", target.ref(0)?.name ?: "?", if (r) 1L else 0L, neoHex(np))) }
    override fun canProgress(np: MemImage): Boolean = PredPathTrie.canProgress(np)
    override fun canFinish(np: MemImage): Boolean = PredPathTrie.canFinish(np)
    override fun determineScoreForHeuristics(np: MemImage) = PredPathTrie.determineScoreForHeuristics(np)
    override fun gatherFinisherTrieWords(np: MemImage): List<IntArray> =
        ArrayList<Int>().also { PredPathTrie.gatherFinisherTrieWords(np, it) }.map { intArrayOf(it, 1) }
    override fun gatherFinisherPendingWords(np: MemImage): List<Any> = emptyList()
    override fun markFinisher(r: MemImage, tw: IntArray) = PredPath.markFinisher(r, tw[0], tw[1])
    override fun markPendingFinisher(r: MemImage, pw: Any) = error("pending-word finishers are not modelled")
    override fun determineFinalScore(r: MemImage) = finalScore(r)
    override fun pushQueue(np: MemImage) {
        trace?.add(listOf("push", String(PredPathTrie.getWord(np), Charsets.ISO_8859_1), np.i32(0x74).toLong() and 0xffffffffL, np.u8(0x2a)))
        PathSearch.pushQueue(st, np)
    }
    override fun regResult(r: MemImage) {
        trace?.add(listOf("reg", String(PredPathTrie.getWord(r), Charsets.ISO_8859_1), r.i32(0x74).toLong() and 0xffffffffL, r.u8(0x2a)))
        PathSearch.regResult(st, r)
    }
}
