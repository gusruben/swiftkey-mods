package nintype.engine.emkey

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.math.Point2D

/**
 * Distance helpers of `Emkey::Moopad` (libgl2jni.so). They only read layout scalars of the
 * (huge, ~0x1f4b0-byte) Moopad, passed in explicitly here:
 *  - 0x1f49c `xUnitForgiven` (xForgivenLength's x divisor)
 *  - 0x1f4a0 `keyHeight`     (balanced y divisor; xForgivenLength's y slack = 0.3 * it)
 *  - 0x1f4a4 `keyWidth`      (balanced x divisor)
 *  - 0x1f4a8 `unit`          (the key unit used by unitDist/unitDiff, `U` in the research doc)
 *  - 0x1f4ac `hybridUnit`
 * (Names are guesses from use; offsets are what the vectors check.)
 */
object Moopad {
    const val OFF_XFORGIVEN = 0x1f49c
    const val OFF_KEY_H = 0x1f4a0
    const val OFF_KEY_W = 0x1f4a4
    const val OFF_UNIT = 0x1f4a8
    const val OFF_HYBRID = 0x1f4ac

    /** unitDist(Point2D const& v) @006297b8: |(v.x / unit, v.y / unit)|. */
    fun unitDist(v: Point2D, unit: Float): Float = Point2D(fdiv(v.x, unit), fdiv(v.y, unit)).length()

    /** unitDist(a, b) @00629e18: d = b - a; |(d.x / unit, d.y / unit)|. */
    fun unitDist(a: Point2D, b: Point2D, unit: Float): Float {
        val d = b - a
        return Point2D(fdiv(d.x, unit), fdiv(d.y, unit)).length()
    }

    /** unitDiff(a, b) @00629770 (sret): (b - a) / unit, per component (operator- is called on b). */
    fun unitDiff(a: Point2D, b: Point2D, unit: Float): Point2D {
        val d = b - a
        return Point2D(fdiv(d.x, unit), fdiv(d.y, unit))
    }

    /** hybridDiff(a, b) @00629684: (a - b) / hybridUnit. */
    fun hybridDiff(a: Point2D, b: Point2D, hybrid: Float): Point2D = unitDiff(a, b, hybrid)

    /** hybridDist(a, b) @006296cc. */
    fun hybridDist(a: Point2D, b: Point2D, hybrid: Float): Float = unitDist(a, b, hybrid)

    /** balancedDiff(a, b) @00629590: ((b - a).x / keyWidth, (b - a).y / keyHeight). */
    fun balancedDiff(a: Point2D, b: Point2D, keyW: Float, keyH: Float): Point2D {
        val d = b - a
        return Point2D(fdiv(d.x, keyW), fdiv(d.y, keyH))
    }

    /** balancedDist(a, b) @006295dc. */
    fun balancedDist(a: Point2D, b: Point2D, keyW: Float, keyH: Float): Float = balancedDiff(a, b, keyW, keyH).length()

    /**
     * xForgivenLength(a, b, bool slide, Point2D const& dir, float f) @00629c54. Length of
     * d = a - b after two forgiveness steps:
     *  - slide: if |f - 1| > 0.01 (or unordered), remove (1 - f) of d's component along dir:
     *    d += dir * -((1 - f) * dot(dir, d)); then d.x /= xUnitForgiven (only when slide);
     *  - y slack s = keyHeight * 0.3: d.y > 0 -> d.y - min(d.y, s); else (incl. NaN)
     *    d.y + min(-0.0 - d.y, s) (as `s > n ? n : s`).
     */
    fun xForgivenLength(a: Point2D, b: Point2D, slide: Boolean, dir: Point2D, f: Float,
                        xForgiven: Float, keyH: Float): Float {
        var d = a - b
        if (slide) {
            val e = abs(fadd(f, -1.0f))
            if (!(fcmple(e, POINT_01) || fcmpun(e, POINT_01))) {
                val k = Point2D.dot(dir, d)
                val s = fmul(fsub(1.0f, f), k)
                val m = dir * neg(s)
                d = Point2D(fadd(d.x, m.x), fadd(d.y, m.y))
            }
            d = Point2D(fdiv(d.x, xForgiven), d.y)
        }
        val dy = d.y
        var lim = fmul(keyH, POINT_3)
        val ny: Float
        if (!(fcmple(dy, 0f) || fcmpun(dy, 0f))) {
            if (fcmplt(dy, lim)) lim = dy
            ny = fsub(dy, lim)
        } else {
            val n = fsub(-0.0f, dy)
            if (fcmpgt(lim, n)) lim = n
            ny = fadd(dy, lim)
        }
        return Point2D(d.x, ny).length()
    }

    private val POINT_01 = java.lang.Float.intBitsToFloat(0x3c23d70a)
    private val POINT_3 = java.lang.Float.intBitsToFloat(0x3e99999a)
    private fun abs(f: Float) = java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(f) and 0x7fffffff)
    private fun neg(f: Float) = java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(f) xor Int.MIN_VALUE)
}
