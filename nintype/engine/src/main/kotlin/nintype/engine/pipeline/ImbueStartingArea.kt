package nintype.engine.pipeline

import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.geometry.MooBoard
import nintype.engine.geometry.Mookey
import nintype.engine.geometry.MoopadGeometry
import nintype.engine.math.Point2D
import nintype.engine.touch.Mootouch
import nintype.engine.vectors.MemImage

/**
 * Moopad::imbueStartingArea(Mootouch&) @008f4fd4: classifies where a new touch started, as bits
 * of the Mootouch's 64-bit flag word at +0x268 (lo = +0x268, hi = +0x26c), and marks it active
 * (+0xb5) when it starts on the spacebar's band, the shift key's left band or key 0xfc4's
 * right band. Bits (from use):
 *
 *   lo 4 alpha key, 5 key flag 0x4, 6 key byte 0xd49, 7 emoji-board key, 8 below the mini bottom
 *   bar, 9/10 near the last tap of a recent touch, 11 emoji expander, 12 key type is not
 *   "emojibutt", 13 key 0xfbc band, 14 key 0xfb0 band, 15/16 key 0xfc0 (period) area, 17/19 key
 *   0xfc4 bands, 20/21/22 above topbar / row 1 / row 2.3 lines, 23/24/25 spacebar bands
 *   (1, 1.6, 2.2 key heights), 26 on the spacebar, 27 key 0xfb0, 28 spaceless-merge side,
 *   29/30 shift bands, 31 key 0xfb8; hi 0/1 left of shift, 2/3 right of key 0xfc4, 4/5 near
 *   shift / 0xfc4 (USF-gated).
 *
 * [keyType] gives a key's std::string at +0x5c (NintypeLayout.KEY_TYPES); [emojiExpander] the
 * EmojiEnv expander Xnl (Moopad+0x22c24 -> +0x1c0), with its x range.
 */
class ImbueStartingArea(
    private val pad: MemImage,
    private val board: MooBoard,
    private val alphaBoard: MooBoard,
    private val roenWidth: Int,
    private val keyType: (Mookey) -> String,
    private val emojiExpander: Int,
    private val pend: (String) -> Unit,
) {
    private var lo = 0
    private var hi = 0

    private fun bit(n: Int, v: Boolean) { lo = (lo and (1 shl n).inv()) or ((if (v) 1 else 0) shl n) }
    private fun hbit(n: Int, v: Boolean) { hi = (hi and (1 shl n).inv()) or ((if (v) 1 else 0) shl n) }
    private fun usf(n: Int) = MoopadGeometry.hasUSF1(pad, n)
    private fun xnl(off: Int) = pad.u8(off + 0xc)
    private fun named(off: Int) = board.named[off]

    fun run(mt: Mootouch) {
        val b = mt.body
        lo = b.i32(0x268); hi = b.i32(0x26c)
        val s = Point2D(b.f32(0x128), b.f32(0x12c))
        val kh = MoopadGeometry.curboardVertlen(pad)
        fun cwe(k: Mookey, l: Int, r: Int, t: Int, bt: Int) = k.containsWithExtra(s, l, r, t, bt)
        fun cweI(k: Mookey, l: Int, r: Int, t: Int, bt: Int) = k.containsWithExtraIgnoringOHM(s, l, r, t, bt)

        // 1. the key under the start
        val k = board.findKey(f2iz(s.x), f2iz(s.y))
        if (k != null) {
            if (keyType(k) == "emojibutt") lo = lo or 0x1000   // std::string::compare == 0
            if (k.isEmojiBoardEmojiKey()) lo = lo or 0x80
            lo = (lo and 0x40.inv()) or (k.img.u8(0xd49) shl 6)
            bit(4, k.isAlphaKey())
            lo = (lo and 0x20.inv()) or ((k.flags shl 3) and 0x20)
        }
        named(0xfb0)?.let { bit(27, cwe(it, 3, 3, 3, 3)) }
        // 2. the spacebar
        val sb = named(0xfac)
        if (sb != null) {
            if (xnl(0x1f680) == 1) {
                val ys = fadd(fadd(fmul(kh, 5f), i2f(pad.i32(0x9c48))), -25f)   // minibottomBarYStart
                bit(8, fcmpgt(s.y, ys))
            }
            val on = cwe(sb, 0, 0, 0, 9999)
            bit(26, on)
            if (on && emojiExpander == 1) pend("Emkey::EmojiEnv::getExpanderButtonXRange (emoji expander bit 11)")
            if (lo and 0x4000000 != 0 && usf(0x57) == 1) {
                val ratio = spacelessMergeAreaRatio()
                val x0 = i2f(sb.x0); val x1 = i2f(sb.x1)
                val side = if (usf(0x3e) == 1) {
                    val r6 = fsub(1f, ratio)
                    fcmpgt(s.x, fadd(fmul(fsub(1f, r6), x0), fmul(r6, x1)))
                } else {
                    fcmplt(s.x, fadd(fmul(fsub(1f, ratio), x0), fmul(ratio, x1)))
                }
                bit(28, side)
            }
            val nkh = f2iz(fsub(-0.0f, kh))
            bit(23, cweI(sb, 9999, 5555, f2iz(kh), nkh))
            bit(24, cweI(sb, 9999, 5555, f2iz(fmul(kh, F1_6)), nkh))
            bit(25, cweI(sb, 9999, 5555, f2iz(fmul(kh, F2_2)), nkh))
            if (lo and 0x4000000 != 0) b.setU8(0xb5, 1)
        }
        named(0xfb8)?.let { bit(31, cweI(it, 15, 15, 0, 9999)) }
        // 3. the shift key
        val sh = named(0xfa8)
        if (sh != null) {
            val left = cweI(sh, 1000, f2iz(fmul(i2f(sh.x0 - sh.x1), F0_43)), 0, 0)
            bit(30, left)
            if (left) b.setU8(0xb5, 1)
            bit(29, cweI(sh, 15, 15, 15, 0))
            hbit(0, cwe(sh, 999, 0, 0, 0))
            hbit(1, cweI(sh, 999, 0, f2iz(fmul(kh, -0.5f)), 0))
            if (usf(0x29) == 0 && usf(0x2e) == 0) {
                val h5 = if (hi and 0x20 != 0) true
                else cweI(sh, 999, f2iz(fmul(pad.f32(MoopadGeometry.KEY_W), 0.5f)), f2iz(fmul(kh, 0.5f)), 0)
                val old = hi
                hbit(5, h5)
                if (old and 0x10 != 0) hbit(4, true)
                else if (old and 1 != 0) hbit(4, true)
                else {
                    val c = cweI(sh, 999, f2iz(fmul(pad.f32(MoopadGeometry.KEY_W), 0.2f)), f2iz(fmul(kh, 0.2f)), 0)
                    hbit(4, c)
                    if (!c) hbit(4, cweI(sh, 999, sh.x0 - sh.x1, f2iz(fmul(kh, 4f)), 0))
                }
            }
        }
        // 4. key 0xfc4 (the right-hand neighbour of the letters)
        val kr = named(0xfc4)
        if (kr != null) {
            bit(17, cwe(kr, 0, 0, 20, 0))
            bit(19, cwe(kr, f2iz(fmul(i2f(kr.x0 - kr.x1), F0_43)), 1000, 0, 0))
            hbit(2, cwe(kr, 0, 9999, 0, 0))
            hbit(3, cwe(kr, 0, 9999, f2iz(fmul(kh, -0.5f)), 0))
            if (usf(0x2a) == 0 && usf(0x2f) == 0) {
                hbit(4, if (hi and 0x10 != 0) true else cwe(kr, 0, 9999, f2iz(fmul(kh, 0.1f)), 0))
            }
            if (lo and 0x80000 != 0) b.setU8(0xb5, 1)
        }
        // 5. key 0xfc0 and its neighbours (period / punctuation area)
        val kp = named(0xfc0)
        if (kp != null) {
            if (xnl(0x9d38) == 1) {
                for (off in intArrayOf(0xfc0, 0xfc8, 0xfcc)) {
                    val kk = named(off) ?: continue
                    if (kk.contains(f2iz(s.x), f2iz(s.y))) lo = lo or 0x8000
                }
            } else {
                bit(15, cwe(kp, 9999, 5555, 200, 0))
            }
            val d = dsub(b.f64(0x110), pad.f64(0x32e48))
            if (!(dcmpge(d, D0_3F) || dcmpun(d, D0_3F)) && lo and 0x8000 != 0) {
                val dist = Point2D(pad.f32(0x32e50), pad.f32(0x32e54)).lengthFrom(s)
                bit(9, fcmplt(dist, 13f))
                lo = (lo and 0x400.inv()) or (pad.u8(0x32e58) shl 10)
                if (usf(0xe0) == 1) lo = lo and 0x400.inv()
            }
            bit(16, cwe(kp, 9999, 5555, 200, 14))
            val w = MoopadGeometry.keyareaWidthOf(pad, roenWidth)
            val q = w / 10
            val l = pad.i32(MoopadGeometry.KEYAREA_L)
            val outside = fcmplt(s.x, i2f(l + q)) || run {
                val v = i2f(w - q + l)
                !(fcmple(s.x, v) || fcmpun(s.x, v))
            }
            if (outside) lo = lo and 0x18000.inv()
        }
        // 6. alpha board only
        if (board.img === alphaBoard.img) {
            val k4 = named(0xfbc)
            if (k4 != null && Integer.compareUnsigned(pad.i32(0x29440), 1) <= 0) {
                if (xnl(0xadc) == 1) {
                    if (lo and 0x4000000 == 0) bit(26, cwe(k4, 0, 0, 0, 90))
                } else {
                    var r2 = k4.x0 - k4.x1 + 60
                    if (r2 < 0) r2 = 0
                    if (sb != null) {
                        val r1 = sb.x1 - sb.x0 - 60
                        if (r2 > r1) r2 = r1
                        if (r2 < 0) r2 = 0
                    }
                    bit(13, cwe(k4, r2, 105, 0, 0))
                }
            }
            val k5 = named(0xfb0)
            if (k5 != null && Integer.compareUnsigned(pad.i32(0x29440), 1) <= 0) bit(14, cwe(k5, 0, 0, 0, 40))
            val top = idealTopbarYpos1(kh)
            lo = (lo and 0x700000.inv()) or ((if (fcmplt(s.y, top)) 1 else 0) shl 20) or
                ((if (fcmplt(s.y, fadd(kh, kh))) 1 else 0) shl 21) or ((if (fcmplt(s.y, fmul(kh, F3_3))) 1 else 0) shl 22)
            if (xnl(0x9d38) == 1 && k != null && k.rowIndex == 1) lo = lo or 0x600000
        }
        b.setI32(0x268, lo); b.setI32(0x26c, hi)
    }

    /** Moopad::spacelessMergeAreaRatio() @0069e340. */
    private fun spacelessMergeAreaRatio(): Float {
        if (xnl(0x2e4c) != 0) return 0.5f
        if (xnl(0x28d0) != 0) return F0_3
        return if (xnl(0x289c) != 0) 0.2f else 0.4f
    }

    /** Moopad::getIdealTopbarYpos(1.0) @00877458 = (1 - 1) * (kh - (int)(factor * kh)) + kh * 1,
     * i.e. exactly kh for any finite topbar height factor. */
    private fun idealTopbarYpos1(kh: Float): Float = kh

    companion object {
        private fun f(b: Int) = java.lang.Float.intBitsToFloat(b)
        val F1_6 = f(0x3fcccccd)
        val F2_2 = f(0x400ccccd)
        val F0_43 = f(0x3edc28f6)
        val F3_3 = f(0x40533333)
        val F0_3 = f(0x3e99999a)
        val D0_3F = java.lang.Double.longBitsToDouble(0x3fd3333340000000L)
    }
}
