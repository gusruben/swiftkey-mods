package nintype.engine.trie

import nintype.engine.SoftFloat
import nintype.engine.math.LibM

/**
 * The PathSearch queries CompactTrieNode::syncCompat makes (search area). The search port
 * implements this over its PathSearch; the vector adapter replays the oracle's recorded answers.
 */
interface CompatSearch {
    /** PathSearch::getNthCharPlanarCoverage(n) -> PlanarCoverage::getCoverage(key). */
    fun nthCharCoverage(n: Int, key: Int): Float
    /** PathSearch::onlyHasASingleTouch(). */
    fun onlyHasASingleTouch(): Boolean
    /** PathSearch::getSlowSlideCoverage(n) -> PlanarCoverage::getCoverage(key). */
    fun slowSlideCoverage(n: Int, key: Int): Float
    /** PathSearch::checkTrieNodeLengthMultSatisfaction(int, float). */
    fun checkTrieNodeLengthMultSatisfaction(len: Int, thr: Float): Boolean
    /** PathSearch::checkCoverage(int, int, int, int). */
    fun checkCoverage(a: Int, b: Int, c: Int, key: Int): Float
    /** PathSearch::checkTouchCoverageByLetters(std::string const&). */
    fun checkTouchCoverageByLetters(word: ByteArray): Float
    /** PathSearch::checkTrieNodeEnderMult(int, int). */
    fun checkTrieNodeEnderMult(len: Int, key: Int): Float
    /** PathSearch+0x124: the best subtree bound seen so far (max-accumulated by syncCompat). */
    var bestBound: Float
}

/** Emkey::CompactCoverageData, the value of the coverage map at CompactTrie+0xE8
 *  (std::map<CompactTrieNode*, CompactCoverageData>, rb-node value at +0x14):
 *  +0 (rb +0x14) 0xC4 from the value constructor (not written by syncCompat; meaning unknown),
 *  +4 bound (subtree score bound; coverageValue() returns it), +8 sum(cov * w), +0xC sum(w). */
class CompactCoverageData(var stamp: Int = 0xc4, var bound: Float = 0f, var sumCovW: Float = 0f, var sumW: Float = 0f)

object Compat {
    internal val F0_6 = Float.fromBits(0x3f19999a)
    internal val F0_62 = Float.fromBits(0x3f1eb852)
    internal val F0_56 = Float.fromBits(0x3f0f5c29)
    internal val F0_27027 = Float.fromBits(0x3e8a60dd)

    /** Per-char weight w = powf(1 - cov, 0.6) * 5 + 1. */
    internal fun weight(cov: Float) = SoftFloat.fadd(SoftFloat.fmul(LibM.powf(SoftFloat.fsub(1.0f, cov), F0_6), 5.0f), 1.0f)

    /**
     * CompactTrieNode::syncCompat(PathSearch*, CTNOCTX&) @005b6a78: the cached coverage bound of
     * node [n] reached by [path] (the nodes above it, root first).
     *
     * - Cached: return the existing entry.
     * - New entry: bound 1, sums inherited from the parent's entry (0 if none); then for each
     *   label char with a real key (not the wildcard), cov = nth-char planar coverage at
     *   (prefix + i) (max with the slow-slide coverage when there is a single touch), w as above,
     *   sumCovW += cov * w, sumW += w.
     * - (sumCovW + 2) / (sumW + 2) < 0.62 (not NaN): bound 0, done (pruned).
     * - prefix == 0 (root and its children): done (bound 1, no recursion).
     * - Terminal node with a non-empty path and checkTrieNodeLengthMultSatisfaction(len, 0.56):
     *   r = sum(cov' w') / sum(w') over the whole word with cov' = checkCoverage(j + 1,
     *   minDist + prefix, minDist + prefix, key); b = min(r, bound); bound = (b == -inf ? +inf :
     *   |sqrtf(b)|) * powf(score byte / 255, 0.27027); bound = min(bound, checkTouchCoverageByLetters).
     *   Otherwise bound = 0.
     * - bound = max(min(bound, checkTrieNodeEnderMult(len + prefix, key of the last label char)),
     *   max over children of their syncCompat bound); PathSearch+0x124 = max(itself, bound).
     */
    fun syncCompat(t: CompactTrie, n: Int, path: List<Int>, ps: CompatSearch,
                   map: MutableMap<Int, CompactCoverageData>): CompactCoverageData {
        map[n]?.let { return it }
        val d = CompactCoverageData()
        map[n] = d
        d.bound = 1.0f
        var prefix = 0
        if (path.isNotEmpty()) {
            for (p in path) prefix += t.labelLen(p)
            val parent = map[path.last()]
            if (parent != null) { d.sumCovW = parent.sumCovW; d.sumW = parent.sumW }
        }
        val len = t.labelLen(n)
        for (i in 0 until len) {
            val key = Keycode.trieCharaToKeycode(t.u8(n, 4 + i))
            if (key == Keycode.WILDCARD) continue
            var cov = ps.nthCharCoverage(prefix + i, key)
            if (ps.onlyHasASingleTouch()) {
                val s = ps.slowSlideCoverage(prefix + i, key)
                if (SoftFloat.fcmplt(cov, s)) cov = s
            }
            val w = weight(cov)
            d.sumCovW = SoftFloat.fadd(d.sumCovW, SoftFloat.fmul(cov, w))
            d.sumW = SoftFloat.fadd(d.sumW, w)
        }
        val ratio = SoftFloat.fdiv(SoftFloat.fadd(d.sumCovW, 2.0f), SoftFloat.fadd(d.sumW, 2.0f))
        if (!(SoftFloat.fcmpge(ratio, F0_62) || SoftFloat.fcmpun(ratio, F0_62))) {
            d.bound = 0f
            return d
        }
        if (prefix == 0) return d

        val word = pathsofar(t, n, path)
        var own = 0f
        if (word.isNotEmpty() && t.isTerminal(n) && ps.checkTrieNodeLengthMultSatisfaction(word.size, F0_56)) {
            var sw = 0f
            var scw = 0f
            val at = t.minDist(n) + prefix
            for (j in word.indices) {
                val key = Keycode.trieCharaToKeycode(word[j].toInt() and 0xff)
                if (key == Keycode.WILDCARD) continue
                val cov = ps.checkCoverage(j + 1, at, at, key)
                val w = weight(cov)
                sw = SoftFloat.fadd(sw, w)
                scw = SoftFloat.fadd(scw, SoftFloat.fmul(cov, w))
            }
            val r = SoftFloat.fdiv(scw, sw)
            var b = d.bound
            if (SoftFloat.fcmplt(r, b)) b = r
            val sq = LibM.sqrtf(b)
            d.bound = if (SoftFloat.fcmpeq(b, Float.NEGATIVE_INFINITY)) Float.POSITIVE_INFINITY
            else Float.fromBits(sq.toRawBits() and 0x7fffffff)
            d.bound = SoftFloat.fmul(LibM.powf(LangEnv.triewordscoreToScore(t.maxScore(n)), F0_27027), d.bound)
            val tc = ps.checkTouchCoverageByLetters(word)
            own = d.bound
            if (SoftFloat.fcmplt(tc, own)) own = tc
        }
        d.bound = own
        val lastKey = if (len != 0) Keycode.trieCharaToKeycode(t.u8(n, 4 + len - 1)) else -1
        val ender = ps.checkTrieNodeEnderMult(len + prefix, lastKey)
        var childMax = 0f
        val sub = ArrayList(path)
        sub.add(n)
        for (c in t.children(n)) {
            val cb = syncCompat(t, c, sub, ps, map).bound
            if (SoftFloat.fcmplt(childMax, cb)) childMax = cb
        }
        var b = d.bound
        if (SoftFloat.fcmplt(ender, b)) b = ender
        if (SoftFloat.fcmplt(b, childMax)) b = childMax
        d.bound = b
        val best = ps.bestBound
        ps.bestBound = if (SoftFloat.fcmplt(best, b)) b else best
        return d
    }

    /** CompactTrieNode::pathsofar(CTNOCTX&) @005b2ac0: the path's labels then this node's full label. */
    fun pathsofar(t: CompactTrie, n: Int, path: List<Int>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in path) out.write(t.label(p))
        out.write(t.label(n))
        return out.toByteArray()
    }

    /** CompactTriePath::syncCompat(PathSearch*) @005b75a4: sync the current node unless cached. */
    fun syncCompat(p: CompactTriePath, ps: CompatSearch, map: MutableMap<Int, CompactCoverageData>) {
        if (map.containsKey(p.node)) return
        syncCompat(p.trie, p.node, p.path, ps, map)
    }

    /** CompactTriePath::coverageValue(PathSearch*) @005b7b6c: the cached bound, 0 if none. */
    fun coverageValue(p: CompactTriePath, map: Map<Int, CompactCoverageData>): Float = map[p.node]?.bound ?: 0f
}
