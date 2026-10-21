package nintype.engine.pathscore

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.LibM
import nintype.engine.vectors.MemImage

/**
 * PredPath::determineUnicodeCompatibilityScore() @00824c4c: how well a word's non-basic
 * characters (accents etc.) agree with the keys the path actually took.
 *
 * Inputs beyond the path: the finisher TrieWord (TWRet +0x20/+0x24; flags hangul 0x8000,
 * nonbasic 0x10000, raw word), path flag +0x2f bit 0 ("typed with non-basic keys"), PathSearch
 * +0x35d (board has non-basic keys), the Moopad's board-char std::set<int> (+0x1f1f0, `board`)
 * and Moopad::hasUSF1(0x7f). Each step's char is step+0x10 (0 = none).
 *
 *   hangul word                      -> 1
 *   basic word, bit 0 clear          -> 1
 *   basic word, bit 0 set            -> 0.001 if (+0x35d and a step char is on the board),
 *                                       else 0.15 with USF 0x7f, else 0.4
 *   non-basic word, bit 0 clear      -> 0.001 if (+0x35d and a word char is on the board), else 1
 *   non-basic word, bit 0 set        -> per-char matching below
 *
 * Matching: every non-basic word char c at index i is an entry (i, matched=false) under c. Step
 * k with char c marks entries of c at index k (strength 1) or k+1 (0.5); the best strength is
 * summed when > 0.01 and (>= 0.99 or the step and char counts differ). Any other step with a char
 * is unmatched (and returns 0.001 when +0x35d and the char is on the board). Afterwards (+0x35d)
 * any board char with an unmatched entry gives 0.001. Result: (1 + 0.5 sum) *
 * {0->1, 1->.7, 2->.4, 3->.2}(unmatched)^(USF 0x7f ? 2 : 1).
 */
object Unicompat {
    private val F001 = Float.fromBits(0x3a83126f)       // 0.001
    private val CURVE = PredPath.curve(0, 0x3f800000, 0x3f800000, 0x3f333333, 0x40000000, 0x3ecccccd, 0x40400000, 0x3e4ccccd)

    private class Entry(val idx: Int) { var matched = false }

    fun determineUnicodeCompatibilityScore(pp: MemImage, mp: MemImage, board: Set<Int>): Float {
        check(pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
        val tw = if (pp.i32(0x24) != 0) Ctp.finisherWord(pp) else null   // neither: the binary asserts, then goes on as basic
        if (tw != null && tw.hasHangul()) return 1f
        val nonbasic = tw?.hasNonBasic() ?: false
        val b0 = pp.u8(0x2f) and 1 == 1
        if (!nonbasic && !b0) return 1f
        val ps = pp.ref(PredPath.OFF_PS)!!
        val unicodeBoard = ps.u8(0x35d) != 0
        val usf by lazy { nintype.engine.geometry.MoopadGeometry.hasUSF1(mp, 0x7f) != 0 }
        val steps by lazy { Vecs.elems(pp, PredPath.OFF_STEPS) }
        if (!nonbasic) {
            if (unicodeBoard) for (st in steps) { val k = st.i32(0x10); if (k != 0 && k in board) return F001 }
            return if (usf) Float.fromBits(0x3e19999a) else Float.fromBits(0x3ecccccd)
        }
        val word = Ctp.trie(pp).rawWord(pp.i32(0x20))!!
        val chars = nintype.engine.trie.Utf8.split(word)
        if (!b0) {
            if (!unicodeBoard) return 1f
            for (c in chars) if (c in board) return F001
            return 1f
        }
        val mm = java.util.TreeMap<Int, MutableList<Entry>>()
        for ((i, c) in chars.withIndex()) if (nintype.engine.trie.Keycode.isNonbasic(c)) mm.getOrPut(c) { ArrayList() }.add(Entry(i))
        var sum = 0f
        var unmatched = 0
        val countsDiffer = steps.size != chars.size
        for ((si, st) in steps.withIndex()) {
            val key = st.i32(0x10)
            if (key == 0) continue
            val v = mm[key]
            if (v != null) {
                var best = 0f
                for (m in v) {
                    if (m.idx == si) { m.matched = true; if (fcmplt(best, 1f)) best = 1f }
                    else if (m.idx - si == 1) { m.matched = true; if (fcmplt(best, 0.5f)) best = 0.5f }
                }
                val hi = fcmpge(best, Float.fromBits(0x3f7d70a4)) || fcmpun(best, Float.fromBits(0x3f7d70a4))
                if (!fcmple(best, Float.fromBits(0x3c23d70a)) && (countsDiffer || hi)) { sum = fadd(sum, best); continue }
            }
            if (unicodeBoard && key in board) return F001
            unmatched++
        }
        if (unicodeBoard) for ((c, v) in mm) if (c in board && v.any { !it.matched }) return F001
        val a = fadd(fmul(sum, 0.5f), 1f)
        val b = CURVE.get(i2f(unmatched))
        return fmul(a, LibM.powf(b, if (usf) 2f else 1f))
    }
}
