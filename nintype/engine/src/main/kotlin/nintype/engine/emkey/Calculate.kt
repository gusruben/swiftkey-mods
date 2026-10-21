package nintype.engine.emkey

import nintype.engine.Bits
import nintype.engine.SoftFloat
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.Point2D

/**
 * Free `Emkey::calculate_*` helpers (libgl2jni.so). Each static curve below is the
 * function-local `static FastCurveMapper` the binary builds on first call; the control
 * points are the exact bit patterns read back from the emulator after it built them
 * (re/vectors/harness_ext.py capture_curves), not re-typed decimals.
 */
object Calculate {
    private fun b(i: Long) = i.toInt()

    /** {0->1, 0.02->0.8, 0.04->0.6, 0.1->0.4, 0.2->0.3, 0.5->0.1, 1->0.05, 2->0.01, 4->0.004} */
    val BACKTOUCHTRAVEL = FastCurveMapper.ofBits(
        b(0x00000000), b(0x3f800000), b(0x3ca3d70a), b(0x3f4ccccd), b(0x3d23d70a), b(0x3f19999a),
        b(0x3dcccccd), b(0x3ecccccd), b(0x3e4ccccd), b(0x3e99999a), b(0x3f000000), b(0x3dcccccd),
        b(0x3f800000), b(0x3d4ccccd), b(0x40000000), b(0x3c23d70a), b(0x40800000), b(0x3b83126f))

    /** {0->1, 0.02->0.98, 0.04->0.93, 0.1->0.5, 0.2->0.3, 0.5->0.1, 1->0.05, 2->0.01, 4->0.004} */
    val BACKTRAVEL = FastCurveMapper.ofBits(
        b(0x00000000), b(0x3f800000), b(0x3ca3d70a), b(0x3f7ae148), b(0x3d23d70a), b(0x3f6e147b),
        b(0x3dcccccd), b(0x3f000000), b(0x3e4ccccd), b(0x3e99999a), b(0x3f000000), b(0x3dcccccd),
        b(0x3f800000), b(0x3d4ccccd), b(0x40000000), b(0x3c23d70a), b(0x40800000), b(0x3b83126f))

    /** {0->1, 0.02->0.96, 0.04->0.93, 0.1->0.91, 0.2->0.89, 0.5->0.86, 1->0.83, 2->0.8, 4->0.75} */
    val STRICT_BACKTRAVEL = FastCurveMapper.ofBits(
        b(0x00000000), b(0x3f800000), b(0x3ca3d70a), b(0x3f75c28f), b(0x3d23d70a), b(0x3f6e147b),
        b(0x3dcccccd), b(0x3f68f5c3), b(0x3e4ccccd), b(0x3f63d70a), b(0x3f000000), b(0x3f5c28f6),
        b(0x3f800000), b(0x3f547ae1), b(0x40000000), b(0x3f4ccccd), b(0x40800000), b(0x3f400000))

    /** {0.25->1, 0.5->0.8, 0.75->0.4, 1->0.25, 1.5->0} */
    val COVERAGE = FastCurveMapper.ofBits(
        b(0x3e800000), b(0x3f800000), b(0x3f000000), b(0x3f4ccccd), b(0x3f400000), b(0x3ecccccd),
        b(0x3f800000), b(0x3e800000), b(0x3fc00000), b(0x00000000))

    private val MINUS_POINT_ONE = Bits.bitsToDouble(0xbfb999999999999aUL.toLong()) // -0.1 (double)

    /** calculate_backtouchtravel_mult(double t, double ref) @007e9af8: curve(d2f(ref - t)). */
    fun backtouchtravelMult(t: Double, ref: Double): Float = BACKTOUCHTRAVEL.get(SoftFloat.d2f(SoftFloat.dsub(ref, t)))

    /** calculate_backtravel_mult(double t, double ref, bool strict) @007e9ccc:
     * x = ref - t, plus -0.1 (in double) when !strict; curve(d2f(x)). */
    fun backtravelMult(t: Double, ref: Double, strict: Boolean): Float {
        var x = SoftFloat.dsub(ref, t)
        if (!strict) x = SoftFloat.dadd(x, MINUS_POINT_ONE)
        return BACKTRAVEL.get(SoftFloat.d2f(x))
    }

    /** calculate_strict_backtravel_mult(double t, double ref) @007e9ecc. */
    fun strictBacktravelMult(t: Double, ref: Double): Float = STRICT_BACKTRAVEL.get(SoftFloat.d2f(SoftFloat.dsub(ref, t)))

    /** calculate_coverage_score(p1, p2, key, moopad) @0095c6f8:
     * d1 = unitDist(key, p1), d2 = unitDist(key, p2); d = d2 < d1 ? d2 : d1; COVERAGE(d). */
    fun coverageScore(p1: Point2D, p2: Point2D, key: Point2D, unit: Float): Float {
        val d1 = Moopad.unitDist(key, p1, unit)
        val d2 = Moopad.unitDist(key, p2, unit)
        return COVERAGE.get(if (SoftFloat.fcmplt(d2, d1)) d2 else d1)
    }

    /** calculate_triecheck_lookbuffer(int) @007e9280: table[n-1] for n in 1..10, else 4. */
    private val LOOKBUFFER = intArrayOf(0, 1, 1, 1, 2, 2, 2, 3, 3, 3)

    fun triecheckLookbuffer(n: Int): Int = if (Integer.compareUnsigned(n - 1, 10) < 0) LOOKBUFFER[n - 1] else 4

    /** calculate_triecheck_indexes(int n, float f, int k, int& lo, int& hi) @007e92b0:
     * c = min(f2iz(i2f(n-1) * f), n-1) (as `c < n ? c : n-1`); lo = max(c - k, 0); hi = min(k + c + 1, n). */
    fun triecheckIndexes(n: Int, f: Float, k: Int): Pair<Int, Int> {
        val c0 = SoftFloat.f2iz(SoftFloat.fmul(SoftFloat.i2f(n - 1), f))
        val c = if (c0 < n) c0 else n - 1
        var hi = k + c + 1
        var lo = c - k
        if (lo < 0) lo = 0
        if (n < hi) hi = n
        return lo to hi
    }

    /** calculateImportantPointRevisitMult(int) @0081f6bc: jump table on (unsigned) n:
     * 0 -> 1, 1 -> 0.94, 2 -> 0.9, 3 -> 0.86, anything else (incl. negative) -> 0.84. */
    fun importantPointRevisitMult(n: Int): Float = when (n) {
        0 -> 1f
        1 -> Bits.bitsToFloat(0x3f70a3d7); 2 -> Bits.bitsToFloat(0x3f666666); 3 -> Bits.bitsToFloat(0x3f5c28f6)
        else -> Bits.bitsToFloat(0x3f570a3d)
    }

    /** calculatePointRevisitMult(int) @0081f720: 0 -> 1, 1 -> 0.98, 2 -> 0.96, 3 -> 0.92, else 0.89. */
    fun pointRevisitMult(n: Int): Float = when (n) {
        0 -> 1f
        1 -> Bits.bitsToFloat(0x3f7ae148); 2 -> Bits.bitsToFloat(0x3f75c28f); 3 -> Bits.bitsToFloat(0x3f6b851f)
        else -> Bits.bitsToFloat(0x3f63d70a)
    }
}
