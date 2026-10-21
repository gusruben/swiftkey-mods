package nintype.engine.trie

import nintype.engine.SoftFloat

/** The PathSearch / MooBoard queries CompactTrieNode::gatherAutoCompletes makes (search area). */
interface AutocompleteSearch {
    /** PathSearch+0x50C, incremented once per visited node. */
    var visitCount: Int
    /** PathSearch+0x14C: the autocomplete start (chars typed so far, as a float). */
    val acStart: Float
    /** PathSearch::getAutocompleteStarterCoverage(pos) -> PlanarCoverage::getCoverage(key). */
    fun starterCoverage(pos: Int, key: Int): Float
    /** BoardDirectionalIndexes::getCoverageValue(MooBoard::getBiDirIndex(prev, c),
     *  PathSearch::getAutocompleteStarterDirectionalCoverage(pos)); prev and c are raw chars. */
    fun starterDirectionalCoverage(prev: Int, c: Int, pos: Int): Float
    /** PathSearch::registerAutocompleteNode(AutocompleteTriePoint const&). */
    fun registerAutocompleteNode(p: AutocompleteTriePoint)
}

/** Emkey::AutocompleteTriePoint: a CompactTriePath-shaped cursor {trie, path, curid, node, pos}
 *  (+0..+0x18) plus +0x1C the score. */
class AutocompleteTriePoint(val path: List<Int>, val curid: Int, val node: Int, val pos: Int, val score: Float)

object AutoComplete {
    private val F0_01 = Float.fromBits(0x3c23d70a)
    private val F0_2 = Float.fromBits(0x3e4ccccd)
    private val F0_99 = Float.fromBits(0x3f7d70a4)
    private val F0_67 = Float.fromBits(0x3f2b851f)
    private val F0_4 = Float.fromBits(0x3ecccccd)
    private val F0_66 = Float.fromBits(0x3f28f5c3)
    private val F1_02 = Float.fromBits(0x3f828f5c)

    /** CompactTrie::gatherAutoCompletes(PathSearch*) @005a5aa0: from the root, empty sums. */
    fun gather(t: CompactTrie, ps: AutocompleteSearch) = gather(t, t.root, emptyList(), t.root, 0f, 0f, ps)

    /**
     * CompactTrieNode::gatherAutoCompletes(PathSearch*, CTNOCTX&, ACCTX&) @005a5b74. ACCTX = {num,
     * den} (by value: the callee only reads it). For each label char i at word position
     * pos = prefix + i, with rem = (acStart + 1) - pos and w = min(rem, 1):
     * stop if w < 0.01; cov = starter coverage of the key, times the directional coverage from
     * the previous char when there is one (stop if that is < 0.2); while rem > 0.99 a first char
     * needs cov >= 0.67, and any char cov >= 0.4 (>= 0.5 at pos 1); num += cov, den += w; stop if
     * num / den < 0.66; register the point (pos i + 1, score num / trunc(den)) when rem < 1.5 and
     * trunc(den) > 0.01; stop if rem < 1.02. Then recurse into every child with the sums so far.
     */
    fun gather(t: CompactTrie, n: Int, path: List<Int>, curid: Int, num0: Float, den0: Float, ps: AutocompleteSearch) {
        ps.visitCount = ps.visitCount + 1
        var num = num0
        var den = den0
        var prefix = 0
        for (p in path) prefix += t.labelLen(p)
        val len = t.labelLen(n)
        if (len != 0) {
            val base = SoftFloat.fadd(ps.acStart, 1.0f)
            var i = 0
            while (true) {
                val c = t.u8(n, 4 + i)
                val key = Keycode.trieCharaToKeycode(c)
                var prev: Int
                if (i >= 1) prev = t.u8(n, 4 + i - 1)
                else {
                    prev = 0
                    if (path.isNotEmpty()) {
                        val last = path.last()
                        val ll = t.labelLen(last)
                        if (ll != 0) prev = t.u8(last, 4 + ll - 1)
                    }
                    if (prev == 0) prev = -1
                }
                val pos = i + prefix
                val rem = SoftFloat.fsub(base, SoftFloat.i2f(pos))
                val w = if (SoftFloat.fcmpgt(rem, 1.0f)) 1.0f else rem
                if (SoftFloat.fcmplt(w, F0_01)) return
                var cov = ps.starterCoverage(pos, key)
                if (prev != -1) {
                    val dv = ps.starterDirectionalCoverage(prev, c, pos)
                    if (SoftFloat.fcmplt(dv, F0_2)) return
                    cov = SoftFloat.fmul(cov, dv)
                }
                if (!(SoftFloat.fcmple(rem, F0_99) || SoftFloat.fcmpun(rem, F0_99))) {
                    if (pos == 0 && SoftFloat.fcmplt(cov, F0_67)) return
                    if (SoftFloat.fcmplt(cov, F0_4)) return
                    if (pos == 1 && SoftFloat.fcmplt(cov, 0.5f)) return
                }
                den = SoftFloat.fadd(den, w)
                num = SoftFloat.fadd(num, cov)
                if (SoftFloat.fcmplt(SoftFloat.fdiv(num, den), F0_66)) return
                val bigRem = !(SoftFloat.fcmple(rem, 1.0f) || SoftFloat.fcmpun(rem, 1.0f))
                val noRegister = bigRem && (SoftFloat.fcmpge(rem, 1.5f) || SoftFloat.fcmpun(rem, 1.5f))
                if (!noRegister) {
                    val td = SoftFloat.i2f(SoftFloat.f2iz(den))
                    if (!(SoftFloat.fcmple(td, F0_01) || SoftFloat.fcmpun(td, F0_01)))
                        ps.registerAutocompleteNode(AutocompleteTriePoint(ArrayList(path), curid, n, i + 1, SoftFloat.fdiv(num, td)))
                }
                if (SoftFloat.fcmplt(rem, F1_02)) return
                i++
                if (i >= len) break
            }
        }
        val sub = ArrayList(path)
        sub.add(n)
        for (c in t.children(n)) gather(t, c, sub, c, num, den, ps)
    }
}
