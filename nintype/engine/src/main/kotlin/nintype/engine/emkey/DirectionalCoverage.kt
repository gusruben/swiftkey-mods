package nintype.engine.emkey

import nintype.engine.Bits
import nintype.engine.SoftFloat
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.math.LibM
import nintype.engine.math.Point2D

/**
 * `Emkey::DirectionalCoverage`: 32 direction bins of 11.25 degrees in a
 * `std::vector<float>` at +0x14 (empty until first use, then 32 zeros). Raw float32 bit
 * patterns are kept so NaN payloads survive. Fields before +0x14 are not touched by these
 * methods.
 */
class DirectionalCoverage(var bins: IntArray = IntArray(0)) {

    private fun ensure() { if (bins.isEmpty()) bins = IntArray(32) }

    fun bin(i: Int): Float = Bits.bitsToFloat(bins[i])

    /** getCoverage(Point2D const& dir) @00959a88: bins[getIndex(dir)], allocating the 32 zeros first. */
    fun getCoverage(dir: Point2D): Float {
        val i = getIndex(dir)
        ensure()
        return bin(i)
    }

    /** getCoverage(int) @00959af4: 0 when empty, else bins[i]. */
    fun getCoverage(i: Int): Float = if (bins.isEmpty()) 0f else bin(i)

    /** record(DirectionalCoverage const& o, float f) @009595a8: if o is non-empty,
     * bins[i] = max(bins[i], o[i] * f) for the 32 bins (`b < v ? v : b`). */
    fun record(o: DirectionalCoverage, f: Float) {
        if (o.bins.isEmpty()) return
        ensure()
        for (i in 0 until 32) {
            val v = fmul(o.bin(i), f)
            if (fcmplt(bin(i), v)) bins[i] = Bits.floatToBits(v)
        }
    }

    /** record(Point2D const& dir, float width, float weight) @00959668: for each bin centre
     * c_i = i * 11.25, t = c_i - deg(dir) wrapped into [-180, 180) (truncating integer
     * reduction by 360, then +-360 loops), v = max(1 - |t| / width, 0)^1.2 * weight;
     * bins[i] = max(bins[i], v). */
    fun record(dir: Point2D, width: Float, weight: Float) {
        ensure()
        val deg = Point2D.toDegrees(dir.arg())
        for (i in 0 until 32) {
            var t = fsub(fmul(SoftFloat.i2f(i * 360), 0.03125f), deg)
            val q = SoftFloat.f2iz(t) / 360 // smull magic = C truncating division
            t = fsub(t, SoftFloat.i2f(q * 360))
            if (fcmpge(t, 180f)) {
                do { t = fadd(t, -360f) } while (!(fcmplt(t, 180f) || fcmpun(t, 180f)))
            }
            while (fcmplt(t, -180f)) t = fadd(t, 360f)
            var v = fsub(1f, fdiv(abs(t), width))
            if (fcmplt(v, 0f)) v = 0f
            val p = fmul(LibM.powf(v, Bits.bitsToFloat(0x3f99999a)), weight)
            if (fcmplt(bin(i), p)) bins[i] = Bits.floatToBits(p)
        }
    }

    companion object {
        private fun abs(f: Float) = Bits.bitsToFloat(Bits.floatToBits(f) and 0x7fffffff)

        /** floatround(float) @00c1a044: f2iz(x + 0.5), or f2iz(x - 0.5) when x <= 0 or NaN. */
        fun floatround(x: Float): Int =
            SoftFloat.f2iz(fadd(x, if (SoftFloat.fcmple(x, 0f) || fcmpun(x, 0f)) -0.5f else 0.5f))

        /** getIndex(Point2D const& dir) @00959878: round(deg(dir) (+360 if negative) / 11.25)
         * wrapped into 0..31 (one +-32 step; the binary asserts, logging only, if still out). */
        fun getIndex(dir: Point2D): Int {
            var deg = Point2D.toDegrees(dir.arg())
            if (!(fcmpge(deg, 0f) || fcmpun(deg, 0f))) deg = fadd(deg, 360f)
            var i = floatround(fdiv(deg, 11.25f))
            if (i < 0) i += 32
            if (i > 31) i -= 32
            return i
        }
    }
}
