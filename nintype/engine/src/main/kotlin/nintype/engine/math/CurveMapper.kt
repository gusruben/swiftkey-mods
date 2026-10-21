package nintype.engine.math

import nintype.engine.Bits
import nintype.engine.SoftFloat

/**
 * `CurveMapper`: a piecewise-linear curve over a `std::map<float, float>` (libgl2jni.so).
 * Modelled as a key-sorted list, which is exactly the map's in-order sequence for non-NaN
 * keys (std::less<float> is no strict weak order with NaN keys; the binary never inserts
 * one and the vectors do not either). Equivalent keys (0.0f / -0.0f) share one entry
 * whose key keeps the first-inserted bit pattern, as in the map.
 *
 * Ghidra addresses: operator()(float,float) @0138fe20, operator[](float) @01390040,
 * get(float) @013901d8, get(float,float) @013900a0, getInterpolated(float) @01390304,
 * clear() @013906d0.
 */
class CurveMapper {
    private val keys = ArrayList<Int>()
    private val values = ArrayList<Int>()

    val size: Int get() = keys.size
    fun keyBits(i: Int): Int = keys[i]
    fun valueBits(i: Int): Int = values[i]
    private fun k(i: Int) = Bits.bitsToFloat(keys[i])
    private fun v(i: Int) = Bits.bitsToFloat(values[i])

    /** std::map::lower_bound + insert-if-absent: index of the entry for [key]. */
    private fun slot(key: Float): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) { // first i with !(k(i) < key)
            val mid = (lo + hi) ushr 1
            if (SoftFloat.fcmplt(k(mid), key)) lo = mid + 1 else hi = mid
        }
        if (lo == keys.size || SoftFloat.fcmplt(key, k(lo))) {
            keys.add(lo, Bits.floatToBits(key)); values.add(lo, 0)
        }
        return lo
    }

    /** operator()(float x, float y) @0138fe20: map[x] = y. */
    fun put(x: Float, y: Float): CurveMapper {
        values[slot(x)] = Bits.floatToBits(y)
        return this
    }

    /** operator[](float) @01390040: map[x] (inserting 0.0f); returns the value. */
    fun index(x: Float): Float = v(slot(x))

    /** clear() @013906d0. */
    fun clear() { keys.clear(); values.clear() }

    /** The map's upper_bound(v) as walked in get(): first key > v; NaN v -> end. */
    private fun upperBound(x: Float): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            // node goes right when key <= v or unordered
            if (SoftFloat.fcmple(k(mid), x) || SoftFloat.fcmpun(k(mid), x)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun lerp(x: Float, a: Int, b: Int): Float {
        val t = SoftFloat.fdiv(SoftFloat.fsub(x, k(a)), SoftFloat.fsub(k(b), k(a)))
        return SoftFloat.fadd(SoftFloat.fmul(SoftFloat.fsub(1f, t), v(a)), SoftFloat.fmul(t, v(b)))
    }

    /** get(float) @013901d8: clamped to the end values. */
    fun get(x: Float): Float {
        val it = upperBound(x)
        if (it == 0) return if (keys.isEmpty()) 0f else v(0)
        if (it == keys.size) return v(it - 1)
        return lerp(x, it - 1, it)
    }

    /** get(float v, float dflt) @013900a0: dflt when empty, else get(v). */
    fun get(x: Float, dflt: Float): Float = if (keys.isEmpty()) dflt else get(x)

    /** getInterpolated(float) @01390304: like get(), but extrapolates linearly through the
     * first / last segment outside the key range. 0 if empty; the value if one entry. */
    fun getInterpolated(x: Float): Float {
        if (keys.isEmpty()) return 0f
        if (keys.size == 1) return v(0)
        var it = upperBound(x)
        if (it == keys.size) it--
        return if (it == 0) lerp(x, 0, 1) else lerp(x, it - 1, it)
    }
}
