package nintype.engine.pathscore

import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.LibM
import nintype.engine.vectors.MemImage

/**
 * PredPath::determineFinalScore() @008261f0: the finished path's score (+0x74).
 *
 *   score = gain + 10 - penalty, then syncSmoothVisitation (+0xac); when > 0:
 *   score *= 0x78 * |t50 - t0 + 0.6| / (tLast - t0 + 0.6) * mean(0x88/0x84) * mean(0x80/0x7c)
 *            * 0xb0 * 0xb4 * 0xb8 * 0xac * mean(0x90/0x8c) * 0xa0 * 0xd4 * PPSCK * unicode
 *            * 0xe0 * 0xec * doubled-letter penalty * cap-tap * variant
 *   and with skipped chars 0x144 = multForCharSkip(skips, pathsize), score *= 0x144.
 *   On a valued cursor with a finisher TrieWord: 0x100 = 1; the word prior (+0xfc) is the
 *   language-flowed score multiplier f (0.5 for a pending word), 0.01 when f < 0.001 or f < 0.04,
 *   1 when f > 1; then p = f (x0.75 with a variant, x0.5 more for flag 0x200; 0.4 for an apostrophe
 *   ending 0x140), prior = (1 - w) + p*w with w = PS+0x34c, the chain ranker's blend, ^ Moopad
 *   wordscore_weighting (+0x96e4); score *= prior. Always sets flag bit 2.
 *
 * `chain` = PathSearch+0x3a0 (word -> chain-ranker weight), `board` = the Moopad board chars.
 */
object FinalScore {
    private val T06 = Double.fromBits(0x3fe3333333333333L)
    private val F0_01 = Float.fromBits(0x3c23d70a)
    private val F0_001 = Float.fromBits(0x3a83126f)

    private fun mean(pp: MemImage, nOff: Int, sOff: Int): Float =
        if (pp.i32(nOff) != 0) fdiv(pp.f32(sOff), i2f(pp.i32(nOff))) else 1f

    private fun variant(pp: MemImage): Float = when (pp.i8(0x34)) {
        0 -> 1f; 2 -> Float.fromBits(0x3f4ccccd); 1 -> Float.fromBits(0x3f75c28f); else -> Float.fromBits(0x3f19999a)
    }

    fun determineFinalScore(pp: MemImage, board: Set<Int>, chain: Map<String, Float>) {
        val ps = pp.ref(PredPath.OFF_PS)!!
        // (no TWRet and no pending word: an assert, then the same path)
        pp.setF32(0x74, fsub(fadd(pp.f32(0x68), 10f), pp.f32(0x6c)))
        Visitation.syncSmoothVisitation(pp)
        val s0 = pp.f32(0x74)
        if (!(fcmple(s0, 0f) || fcmpun(s0, 0f))) {
            val t0 = ps.f64(0x168)
            val ta = dadd(dsub(pp.f64(0x50), t0), T06)
            val tb = dadd(dsub(ps.f64(0x178), t0), T06)
            val ratio = d2f(ddiv(Double.fromBits(ta.toRawBits() and Long.MAX_VALUE), tb))
            var x = fmul(pp.f32(0x78), ratio)
            x = fmul(x, mean(pp, 0x84, 0x88))
            x = fmul(x, mean(pp, 0x7c, 0x80))
            x = fmul(x, pp.f32(0xb0)); x = fmul(x, pp.f32(0xb4)); x = fmul(x, pp.f32(0xb8)); x = fmul(x, pp.f32(0xac))
            x = fmul(x, mean(pp, 0x8c, 0x90))
            x = fmul(x, pp.f32(0xa0)); x = fmul(x, pp.f32(0xd4))
            x = fmul(x, PredPath.determinePPSCKScoreMult(pp))
            x = fmul(x, Unicompat.determineUnicodeCompatibilityScore(pp, ps.ref(0x110)!!, board))
            x = fmul(x, pp.f32(0xe0)); x = fmul(x, pp.f32(0xec))
            x = fmul(x, PredPath.calculateLastCharSameLetterSamePointPenalty(pp))
            x = fmul(x, PredPathTrie.multForCapTap(pp))
            x = fmul(x, variant(pp))
            pp.setF32(0x74, fmul(x, pp.f32(0x74)))
            val skips = pp.i8(0x2c)
            if (skips != 0) {
                val m = PredPath.multForCharSkip(pp, skips, i2f(pp.i8(0x2a)))
                pp.setF32(0x144, m)
                pp.setF32(0x74, fmul(m, pp.f32(0x74)))
            }
        }
        if (Ctp.of(pp).isvalued() && pp.i32(0x24) != 0) {
            pp.setF32(0x100, 1f)
            val mp = ps.ref(0x110)!!
            var f = Ctp.finisherWord(pp).langflowedScoremult(mp.i32(0x21bc8), FloatArray(4) { mp.f32(0x21c64 + 4 * it) })
            check(pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
            val prior: Float
            if (fcmplt(f, F0_001)) prior = F0_01
            else {
                var r8 = 1f
                var fail = false
                if (!fcmpgt(f, 1f)) {
                    if (!(fcmpge(f, Float.fromBits(0x3d23d70a)) || fcmpun(f, Float.fromBits(0x3d23d70a)))) fail = true else r8 = f
                }
                if (fail) prior = F0_01
                else {
                    val fl = pp.u8(0x2f) or (pp.u8(0x30) shl 8)
                    var p = Float.fromBits(0x3ecccccd)
                    if (fl and 0x140 == 0) {
                        if (pp.u8(0x34) == 0) p = r8
                        else {
                            p = fmul(r8, 0.75f)
                            if (fl and 0x200 != 0) p = fmul(p, 0.5f)
                        }
                    }
                    val w = ps.f32(0x34c)
                    var pr = fadd(fsub(1f, w), fmul(p, w))
                    val word = String(Ctp.trie(pp).rawWord(pp.i32(0x20))!!, Charsets.ISO_8859_1)
                    pr = nintype.engine.search.PathSearch.modifyWordScoreDueToChainRanker(chain, word, pr)
                    pr = LibM.powf(pr, mp.f32(0x96e4))
                    prior = pr
                }
            }
            pp.setF32(0xfc, prior)
            pp.setF32(0x74, fmul(prior, pp.f32(0x74)))
        }
        pp.setU8(0x2f, pp.u8(0x2f) or 4)
        pp.setU8(0x30, pp.u8(0x30))
    }
}
