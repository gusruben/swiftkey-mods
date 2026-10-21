package nintype.engine.touch

import nintype.engine.Bits
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmplt

/**
 * `Emkey::PlanarCoverage` (0x24 bytes):
 *
 *   +0x00 f64 tMin, +0x08 f64 tMax   time span of the recorded samples
 *   +0x10 u8  hasTime                tMin/tMax valid (any nonzero byte; record writes 1)
 *   +0x14 vector<float> areas        per-area coverage, -1 = never recorded
 *   +0x20 f32 maxCoverage            max over every record() call
 *
 * Floats are kept as raw bit patterns.
 */
class PlanarCoverage(
    var tMin: Double = 0.0, var tMax: Double = 0.0, var hasTime: Int = 0,
    val areas: MutableList<Int> = mutableListOf(), var maxCoverage: Float = 0f,
) {
    /** getCoverage(int) @0095a628: unsigned i < size ? areas[i] : -1. */
    fun getCoverage(i: Int): Float =
        if (Integer.compareUnsigned(i, areas.size) < 0) Bits.bitsToFloat(areas[i]) else -1f

    /** record(int i, float c, double t) @0095a64c: grows areas with -1 until i fits (unsigned
     * compare), areas[i] = max(areas[i], c) and maxCoverage likewise (`a < c ? c : a`), then
     * widens [tMin, tMax] to t (first call sets both; `tMin < t ? tMin : t`, so NaN takes t). */
    fun record(i: Int, c: Float, t: Double) {
        require(i >= 0) { "negative index grows the vector until allocation fails" }
        while (areas.size <= i) areas.add(Bits.floatToBits(-1f))
        if (fcmplt(Bits.bitsToFloat(areas[i]), c)) areas[i] = Bits.floatToBits(c)
        if (fcmplt(maxCoverage, c)) maxCoverage = c
        if (hasTime == 0) {
            tMin = t; tMax = t; hasTime = 1
        } else {
            if (!dcmplt(tMin, t)) tMin = t
            if (!dcmpgt(tMax, t)) tMax = t
        }
    }

    /** checkCoverageSatisfaction(Shing::Flag64&, float thr) @0095a5b8: some area i with bit i
     * of [flags] set (Flag64::flagged: a plain 64-bit test, false for i >= 64) and
     * areas[i] >= thr. */
    fun checkCoverageSatisfaction(flags: Long, thr: Float): Boolean {
        for (i in areas.indices) {
            if (i < 64 && (flags ushr i) and 1L != 0L && fcmpge(Bits.bitsToFloat(areas[i]), thr)) return true
        }
        return false
    }
}
