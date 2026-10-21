package nintype.engine.geometry

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * The geometry/glue part of `Emkey::Moopad` the decoder calls (decisions per function in
 * GLUE.md). The Moopad is a ~0x2a000-byte image ([mp]); these read only the fields below.
 * `roenWidth` is RoenGL's surface width in board units (RoenGL+0xb28; 540 on the reference
 * 1080-px surface), a global in the binary and a parameter here.
 *
 *  - 0x09c4c / 0x09c50 int  left / right inset of the key area (0 on the reference layout)
 *  - 0x02b74 Xnl            spacebar-mid override off; val byte at 0x2b80
 *  - 0x1f208 MooBoard*      current board (0x1f1ec is the alpha board, the same one at boot)
 *  - 0x1f498 int            topbar height (54)
 *  - 0x1f4a0 f32            key height = curboardVertlen (54)
 *  - 0x1f4a4 f32            key width (54)
 *  - 0x1f4b8 int            extra origin offset (0)
 *  - 0x1f4c4 f32            topbar shrink factor (1.18519)
 */
object MoopadGeometry {
    const val KEYAREA_L = 0x9c4c
    const val KEYAREA_R = 0x9c50
    const val SPACEMID_XNL = 0x2b74
    const val CUR_BOARD = 0x1f208
    const val ALPHA_BOARD = 0x1f1ec
    const val TOPBAR_H = 0x1f498
    const val KEY_H = 0x1f4a0
    const val KEY_W = 0x1f4a4
    const val ORIGIN_EXTRA = 0x1f4b8
    const val TOPBAR_SHRINK = 0x1f4c4

    private val F4 = java.lang.Float.intBitsToFloat(0x40800000)

    /** curboardVertlen() @0062957c. */
    fun curboardVertlen(mp: MemImage): Float = mp.f32(KEY_H)

    /** curboardTopbarBottomYpos() @00629c20 (the same field). */
    fun curboardTopbarBottomYpos(mp: MemImage): Float = mp.f32(KEY_H)

    /** curboardSpacebarTopYpos() @00629c30: keyHeight * 4. */
    fun curboardSpacebarTopYpos(mp: MemImage): Float = fmul(mp.f32(KEY_H), F4)

    /** curboardVertposWithoutTopbar(float f) @0062988c: (f * 4 + 1) * keyHeight. */
    fun curboardVertposWithoutTopbar(mp: MemImage, f: Float): Float = fmul(fadd(fmul(f, F4), 1.0f), mp.f32(KEY_H))

    /** hiddenAreaBelowOriginDueToShrinkage() @00629534: keyHeight - shrink * topbarHeight. */
    fun hiddenAreaBelowOriginDueToShrinkage(mp: MemImage): Float =
        fsub(mp.f32(KEY_H), fmul(mp.f32(TOPBAR_SHRINK), i2f(mp.i32(TOPBAR_H))))

    private fun keyareaWidth(mp: MemImage, roenWidth: Int): Int = roenWidth - mp.i32(KEYAREA_L) - mp.i32(KEYAREA_R)

    /** Moopad::getTrueWidth() @00626e80: the key area width, roenWidth - left - right inset. */
    fun keyareaWidthOf(mp: MemImage, roenWidth: Int): Int = keyareaWidth(mp, roenWidth)

    /** keyarea_xposByFactor(float f) @006299a8: left + width * f. */
    fun keyareaXposByFactor(mp: MemImage, roenWidth: Int, f: Float): Float =
        fadd(i2f(mp.i32(KEYAREA_L)), fmul(i2f(keyareaWidth(mp, roenWidth)), f))

    /** keyarea_xfactorByPos(float x) @0062a018: (x - left) / width. */
    fun keyareaXfactorByPos(mp: MemImage, roenWidth: Int, x: Float): Float =
        fdiv(fsub(x, i2f(mp.i32(KEYAREA_L))), i2f(keyareaWidth(mp, roenWidth)))

    /** letterarea_yposByFactor(float f) @0062a084: (1 - f) * kh + (kh * 4) * f. */
    fun letterareaYposByFactor(mp: MemImage, f: Float): Float {
        val kh = mp.f32(KEY_H)
        val k4 = fmul(kh, F4)
        return fadd(fmul(fsub(1.0f, f), kh), fmul(k4, f))
    }

    /** letterarea_yfactorByPos(float y) @0062a0e4: (y - kh) / (kh * 4 - kh). */
    fun letterareaYfactorByPos(mp: MemImage, y: Float): Float {
        val kh = mp.f32(KEY_H)
        val k4 = fmul(kh, F4)
        return fdiv(fsub(y, kh), fsub(k4, kh))
    }

    /** getSpacebarStuffXMid() @006fcdc4: the key area's mid x, or (when the Xnl at 0x2b74 is
     * off and the current board has a spacebar) the spacebar's label x. */
    fun getSpacebarStuffXMid(mp: MemImage, roenWidth: Int): Float {
        var mid = fadd(i2f(mp.i32(KEYAREA_L)), fmul(i2f(keyareaWidth(mp, roenWidth)), 0.5f))
        if (mp.u8(SPACEMID_XNL + 0xc) == 0) {
            val board = mp.ref(CUR_BOARD) ?: mp.ptr(CUR_BOARD)
            if (board != null) {
                val sb = board.ref(MooBoard.SPACEBAR) ?: board.ptr(MooBoard.SPACEBAR)
                if (sb != null) mid = sb.f32(Mookey.LABEL_POS)
            }
        }
        return mid
    }

    // --- mode / setting flags (Xnl::val = the raw byte at Xnl + 0xc) ---------------------

    const val USF_BASE = 0x137c
    const val USF_STRIDE = 0x34
    const val SLIDEMODE = 0x29aa4
    const val PECKMODE = 0x29a08
    const val STOCKMODE = 0x29a70
    const val TEMP_AUTOSPACE_GATE = 0x297c8
    const val TEMP_AUTOSPACE = 0x29794
    const val FORCE_OFF = 0x41cc
    const val FORCE_EXP = 0x12b0
    const val TWERK_OFF = 0x520c
    const val WRITEUSER = 0x2afac

    private fun xnl(mp: MemImage, off: Int) = mp.u8(off + 0xc)

    /** slidemodeActive() @00623d54, peckmodeActive() @00623d60, stockmodeActive() @00623da4. */
    fun slidemodeActive(mp: MemImage): Int = xnl(mp, SLIDEMODE)
    fun peckmodeActive(mp: MemImage): Int = xnl(mp, PECKMODE)
    fun stockmodeActive(mp: MemImage): Int = xnl(mp, STOCKMODE)

    /** hasUSF1(int n) @0061fd48: the n-th user-setting flag Xnl (stride 0x34 from 0x137c). The
     * binary asserts n < 0x200 (unsigned) and reads anyway. */
    fun hasUSF1(mp: MemImage, n: Int): Int = xnl(mp, USF_BASE + n * USF_STRIDE)

    /** temporaryAutospaceIsOn() @006a2148: 0 unless the gate Xnl is exactly 1, else the flag. */
    fun temporaryAutospaceIsOn(mp: MemImage): Int = if (xnl(mp, TEMP_AUTOSPACE_GATE) != 1) 0 else xnl(mp, TEMP_AUTOSPACE)

    /** writeuser() @0063040c: the pointer at 0x2afac. */
    fun writeuser(mp: MemImage): MemImage? = mp.ref(WRITEUSER) ?: mp.ptr(WRITEUSER)

    private val F6_67 = java.lang.Float.intBitsToFloat(0x40d570a4)

    /** translateForceval(float f) @00624e8c: powf(off ? 0 : f / 6.67, mp[0x12b0]) * 6.67. */
    fun translateForceval(mp: MemImage, f: Float): Float {
        var t = fdiv(f, F6_67)
        if (xnl(mp, FORCE_OFF) != 0) t = 0f
        return fmul(nintype.engine.math.LibM.powf(t, mp.f32(FORCE_EXP)), F6_67)
    }

    /** translateTwerkForce(float f) @00624e64: 0 when the twerk-off Xnl is set, else f. */
    fun translateTwerkForce(mp: MemImage, f: Float): Float = if (xnl(mp, TWERK_OFF) != 0) 0f else f

    const val LIVE_TOUCHES = 0x2942c     // std::map<?, Mootouch> (header 0x29430, Mootouch at node+0x18)
    const val SITTING_TOUCHES = 0x294c4  // std::list<Mootouch> (Mootouch at node+8)
    const val MOOTOUCH_UNIQUE_ID = 0xc0

    /** Moopad::findTouchByUniqueID(int) @006847a8: the first Mootouch, in map order then list
     * order, whose int at +0xc0 is [id]; null if none. Images are Mootouch-relative. */
    fun findTouchByUniqueID(live: List<MemImage>, sitting: List<MemImage>, id: Int): MemImage? {
        for (t in live) if (t.i32(MOOTOUCH_UNIQUE_ID) == id) return t
        for (t in sitting) if (t.i32(MOOTOUCH_UNIQUE_ID) == id) return t
        return null
    }

    /** Moopad::findKey(int, int) @006987b4: the current board's findKey, null without one. */
    fun findKey(currentBoard: MooBoard?, x: Int, y: Int): Mookey? = currentBoard?.findKey(x, y)

    /**
     * screenKeyboardOrigin() @00684bf4 (UI, replaced): (rect.left, rect.top - topbarHeight +
     * originExtra) as floats, rect = Cns::getRectOnScreen() in roen units. Board space is
     * touch / roenScale - origin; on the reference setup origin = (0, 152) from a measured
     * rect top of 206 and topbar 54. A host (SwiftKey) supplies the origin directly through
     * [KeyboardModel.origin].
     */
    fun screenKeyboardOrigin(rectLeft: Int, rectTop: Int, mp: MemImage): Point2D =
        Point2D(i2f(rectLeft), i2f(rectTop - mp.i32(TOPBAR_H) + mp.i32(ORIGIN_EXTRA)))
}
