package nintype.engine.math

import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpeq
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.Bits

/**
 * `Point2D {float x, y}` (libgl2jni.so) and the helpers the touch layer calls. Ghidra
 * addresses in each doc comment; operation order and operand order follow the disassembly
 * (operand order matters only for NaN payloads, which the vector runner tolerates, but is
 * kept anyway).
 */
data class Point2D(val x: Float, val y: Float) {

    /** operator-(Point2D const&) @00c1f240: (x - o.x, y - o.y). */
    operator fun minus(o: Point2D) = Point2D(fsub(x, o.x), fsub(y, o.y))

    /** operator+(Point2D const&) @00c1f348. */
    operator fun plus(o: Point2D) = Point2D(fadd(x, o.x), fadd(y, o.y))

    /** operator*(float) @00c1f314. */
    operator fun times(f: Float) = Point2D(fmul(x, f), fmul(y, f))

    /** length() @00c1dbc4: sqrtf(x*x + y*y). */
    fun length(): Float = LibM.sqrtf(fadd(fmul(x, x), fmul(y, y)))

    /** lengthsquared() @00c1db90 (assumed same shape as length() minus the sqrt). */
    fun lengthSquared(): Float = fadd(fmul(x, x), fmul(y, y))

    /** distance(o) @00c1d794 and lengthFrom(o) @00c1d7f0 (identical bodies):
     * dx = o.x - x, dy = o.y - y, sqrtf(dx*dx + dy*dy). */
    fun distance(o: Point2D): Float {
        val dx = fsub(o.x, x)
        val dy = fsub(o.y, y)
        return LibM.sqrtf(fadd(fmul(dx, dx), fmul(dy, dy)))
    }

    fun lengthFrom(o: Point2D): Float = distance(o)

    /** distanceSquared(o) @00c1d73c. */
    fun distanceSquared(o: Point2D): Float {
        val dx = fsub(o.x, x)
        val dy = fsub(o.y, y)
        return fadd(fmul(dx, dx), fmul(dy, dy))
    }

    /** manhattandistance(o) @00c1d680: |o.x - x| + |o.y - y| (sign bit cleared, not fabs()). */
    fun manhattanDistance(o: Point2D): Float = fadd(abs(fsub(o.x, x)), abs(fsub(o.y, y)))

    /** xorydistance(o) @00c1d6bc: max(|dx|, |dy|) as `ax < ay ? ay : ax`. */
    fun xorYDistance(o: Point2D): Float {
        val ax = abs(fsub(o.x, x))
        val ay = abs(fsub(o.y, y))
        return if (fcmplt(ax, ay)) ay else ax
    }

    /** normalise() @00c1f184: len = sqrtf(x*x + y*y); len < 0.0001f -> (0, 0), else (x/len, y/len). */
    fun normalise(): Point2D {
        val len = LibM.sqrtf(fadd(fmul(x, x), fmul(y, y)))
        if (fcmplt(len, EPS)) return Point2D(0f, 0f)
        return Point2D(fdiv(x, len), fdiv(y, len))
    }

    /** interpolate(o, t) @00c1dbfc: ((1-t)*x + o.x*t, (1-t)*y + o.y*t). */
    fun interpolate(o: Point2D, t: Float): Point2D {
        val a = fsub(1f, t)
        return Point2D(fadd(fmul(a, x), fmul(o.x, t)), fadd(fmul(a, y), fmul(o.y, t)))
    }

    /** conjugate() @00c1df78: (x, -0.0f - y). */
    fun conjugate(): Point2D = Point2D(x, fsub(-0.0f, y))

    /** arg() @00c1df9c: atan2-like angle in radians from atanf, see [argOf]. */
    fun arg(): Float = argOf(x, y)

    /** argDeg() @00c1f038: [arg] in degrees: d2f(f2d(arg * 180f) / pi). */
    fun argDeg(): Float = toDegrees(argOf(x, y))

    /** degreesVs(o) @00c1e0c8: signed angle from this to o, in degrees:
     * argOf(o.x*x + y*o.y, o.x*y - x*o.y). */
    fun degreesVs(o: Point2D): Float {
        val cross = fsub(fmul(o.x, y), fmul(x, o.y))
        val dotp = fadd(fmul(o.x, x), fmul(y, o.y))
        return toDegrees(argOf(dotp, cross))
    }

    /** argDifference(o) @00c1dd90: like [degreesVs] but in radians. */
    fun argDifference(o: Point2D): Float {
        val cross = fsub(fmul(o.x, y), fmul(x, o.y))
        val dotp = fadd(fmul(o.x, x), fmul(y, o.y))
        return argOf(dotp, cross)
    }

    companion object {
        private val EPS = Bits.bitsToFloat(0x38d1b717) // 0.0001f
        private val HALF_PI = Bits.bitsToFloat(0x3fc90fdb)
        private val NEG_HALF_PI = Bits.bitsToFloat(0xbfc90fdb.toInt())
        private val PI_D = Bits.bitsToDouble(0x400921fb54442d18L)
        private val NEG_PI_D = Bits.bitsToDouble(0xc00921fb54442d18uL.toLong())

        private fun abs(f: Float): Float = Bits.bitsToFloat(Bits.floatToBits(f) and 0x7fffffff)

        /** dot(Point2D const&, Point2D const&) @00c1d628: a.x*b.x + a.y*b.y. */
        fun dot(a: Point2D, b: Point2D): Float = fadd(fmul(a.x, b.x), fmul(a.y, b.y))

        /** The atan2 replacement inlined in arg()/argDeg()/degreesVs()/argDifference():
         *  x == 0: y > 0 -> pi/2f, y < 0 -> -pi/2f, else +0;
         *  x > 0: atanf(y / x);
         *  x <= 0 or NaN: y < 0 or NaN -> d2f(f2d(atanf(y / x)) + -pi), else d2f(pi - f2d(atanf(y / -x))). */
        fun argOf(x: Float, y: Float): Float {
            if (fcmpeq(x, 0f)) {
                if (fcmpgt(y, 0f)) return HALF_PI
                return if (fcmplt(y, 0f)) NEG_HALF_PI else 0f
            }
            if (!(fcmple(x, 0f) || fcmpun(x, 0f))) return LibM.atanf(fdiv(y, x))
            if (fcmplt(y, 0f) || fcmpun(y, 0f)) return d2f(dadd(f2d(LibM.atanf(fdiv(y, x))), NEG_PI_D))
            val negX = Bits.bitsToFloat(Bits.floatToBits(x) xor Int.MIN_VALUE)
            return d2f(dsub(PI_D, f2d(LibM.atanf(fdiv(y, negX)))))
        }

        fun toDegrees(r: Float): Float = d2f(ddiv(f2d(fmul(r, 180f)), PI_D))
    }
}
