package nintype.engine.math

import nintype.engine.Bits
import nintype.engine.SoftFloat

/**
 * `FastCurveMapper`: a piecewise-linear curve over a `std::vector<std::pair<float,float>>`
 * (libgl2jni.so, gnustl). Control points are stored as raw float32 bit patterns so NaN
 * payloads survive moves exactly like the C++ memcpy-style element moves.
 *
 * Ghidra addresses (file vaddr + 0x10000): operator()(float,float) @0138fa54,
 * finalize() @0138faec, get(float) @0138fd18.
 */
class FastCurveMapper(capacity: Int = 8) {
    var xs = IntArray(capacity); private set
    var ys = IntArray(capacity); private set
    var size = 0; private set

    fun x(i: Int): Float = Bits.bitsToFloat(xs[i])
    fun y(i: Int): Float = Bits.bitsToFloat(ys[i])

    /** operator()(float x, float y) @0138fa54: push_back({x, y}); returns this. */
    fun push(x: Float, y: Float): FastCurveMapper = pushBits(Bits.floatToBits(x), Bits.floatToBits(y))

    fun pushBits(xb: Int, yb: Int): FastCurveMapper {
        if (size == xs.size) {
            val n = maxOf(8, xs.size * 2)
            xs = xs.copyOf(n); ys = ys.copyOf(n)
        }
        xs[size] = xb; ys[size] = yb; size++
        return this
    }

    /** finalize() @0138faec: std::sort with pair<float,float>::operator<, then a
     * Shing::reportAssert (logging only, no state change) whenever two consecutive x's are
     * not more than 0.0001f apart. The assert is not modelled. */
    fun finalize(): FastCurveMapper {
        GnuSort.sort(this)
        return this
    }

    /** get(float) @0138fd18. Scans down from the last point for the first x[i] < v (an
     * unordered compare counts as "not less", so a NaN v ends at y[0]). */
    fun get(v: Float): Float {
        val n = size
        if (n < 1) return 0f
        var i = n - 1
        while (i >= 0) {
            val xi = x(i)
            // __aeabi_fcmpge(x, v) | __aeabi_fcmpun(x, v): keep scanning
            if (!(SoftFloat.fcmpge(xi, v) || SoftFloat.fcmpun(xi, v))) break
            i--
        }
        if (i < 0) return y(0)
        val yi = y(i)
        if (i == n - 1) return yi
        val t = SoftFloat.fdiv(SoftFloat.fsub(v, x(i)), SoftFloat.fsub(x(i + 1), x(i)))
        return SoftFloat.fadd(SoftFloat.fmul(SoftFloat.fsub(1f, t), yi), SoftFloat.fmul(t, y(i + 1)))
    }

    // --- element access for the sort -----------------------------------------------------

    internal fun less(a: Int, b: Int): Boolean = pairLess(xs[a], ys[a], xs[b], ys[b])

    internal fun swap(a: Int, b: Int) {
        val tx = xs[a]; xs[a] = xs[b]; xs[b] = tx
        val ty = ys[a]; ys[a] = ys[b]; ys[b] = ty
    }

    internal fun set(i: Int, xb: Int, yb: Int) { xs[i] = xb; ys[i] = yb }

    companion object {
        /** std::pair<float,float>::operator<: a.first < b.first || (!(b.first < a.first) && a.second < b.second). */
        fun pairLess(ax: Int, ay: Int, bx: Int, by: Int): Boolean {
            val fax = Bits.bitsToFloat(ax); val fbx = Bits.bitsToFloat(bx)
            if (SoftFloat.fcmplt(fax, fbx)) return true
            if (SoftFloat.fcmplt(fbx, fax)) return false
            return SoftFloat.fcmplt(Bits.bitsToFloat(ay), Bits.bitsToFloat(by))
        }

        /** A finalized curve from (x, y) float32 bit patterns, as the binary's static curves are
         * built: one operator() per point in source order, then finalize(). */
        fun ofBits(vararg xy: Int): FastCurveMapper {
            val c = FastCurveMapper(xy.size / 2)
            for (k in 0 until xy.size / 2) c.pushBits(xy[2 * k], xy[2 * k + 1])
            return c.finalize()
        }
    }
}

/**
 * gnustl (libstdc++ 4.9) `std::sort` on a [FastCurveMapper]'s points: introsort with
 * threshold 16, median-of-three into *first, unguarded partition, heapsort on depth
 * exhaustion, then the final insertion sort. Mirrored step for step (not just "a stable
 * sort"), so the order of equivalent elements (0.0 vs -0.0 keys, NaN y) matches.
 * The binary inlines it as std::__introsort_loop + std::__final_insertion_sort calls from
 * FastCurveMapper::finalize.
 */
internal object GnuSort {
    private const val THRESHOLD = 16

    fun sort(c: FastCurveMapper) {
        val n = c.size
        if (n == 0) return
        introsortLoop(c, 0, n, 2 * (31 - Integer.numberOfLeadingZeros(n)))
        finalInsertionSort(c, 0, n)
    }

    private fun introsortLoop(c: FastCurveMapper, first: Int, lastIn: Int, depthIn: Int) {
        var last = lastIn
        var depth = depthIn
        while (last - first > THRESHOLD) {
            if (depth == 0) {
                makeHeap(c, first, last)
                sortHeap(c, first, last)
                return
            }
            depth--
            val cut = unguardedPartitionPivot(c, first, last)
            introsortLoop(c, cut, last, depth)
            last = cut
        }
    }

    private fun unguardedPartitionPivot(c: FastCurveMapper, first: Int, last: Int): Int {
        val mid = first + (last - first) / 2
        moveMedianToFirst(c, first, first + 1, mid, last - 1)
        return unguardedPartition(c, first + 1, last, first)
    }

    private fun moveMedianToFirst(c: FastCurveMapper, result: Int, a: Int, b: Int, cc: Int) {
        if (c.less(a, b)) {
            if (c.less(b, cc)) c.swap(result, b)
            else if (c.less(a, cc)) c.swap(result, cc)
            else c.swap(result, a)
        } else if (c.less(a, cc)) c.swap(result, a)
        else if (c.less(b, cc)) c.swap(result, cc)
        else c.swap(result, b)
    }

    private fun unguardedPartition(c: FastCurveMapper, firstIn: Int, lastIn: Int, pivot: Int): Int {
        var first = firstIn
        var last = lastIn
        while (true) {
            while (c.less(first, pivot)) first++
            last--
            while (c.less(pivot, last)) last--
            if (first >= last) return first
            c.swap(first, last)
            first++
        }
    }

    private fun finalInsertionSort(c: FastCurveMapper, first: Int, last: Int) {
        if (last - first > THRESHOLD) {
            insertionSort(c, first, first + THRESHOLD)
            for (i in first + THRESHOLD until last) unguardedLinearInsert(c, i)
        } else insertionSort(c, first, last)
    }

    private fun insertionSort(c: FastCurveMapper, first: Int, last: Int) {
        if (first == last) return
        for (i in first + 1 until last) {
            if (c.less(i, first)) {
                val vx = c.xs[i]; val vy = c.ys[i]
                for (k in i downTo first + 1) c.set(k, c.xs[k - 1], c.ys[k - 1])
                c.set(first, vx, vy)
            } else unguardedLinearInsert(c, i)
        }
    }

    private fun unguardedLinearInsert(c: FastCurveMapper, lastIn: Int) {
        var last = lastIn
        val vx = c.xs[last]; val vy = c.ys[last]
        var next = last - 1
        while (FastCurveMapper.pairLess(vx, vy, c.xs[next], c.ys[next])) {
            c.set(last, c.xs[next], c.ys[next])
            last = next
            next--
        }
        c.set(last, vx, vy)
    }

    // --- heap fallback (std::__partial_sort(first, last, last)) --------------------------

    private fun makeHeap(c: FastCurveMapper, first: Int, last: Int) {
        val len = last - first
        if (len < 2) return
        var parent = (len - 2) / 2
        while (true) {
            adjustHeap(c, first, parent, len, c.xs[first + parent], c.ys[first + parent])
            if (parent == 0) return
            parent--
        }
    }

    private fun sortHeap(c: FastCurveMapper, first: Int, lastIn: Int) {
        var last = lastIn
        while (last - first > 1) {
            last--
            val vx = c.xs[last]; val vy = c.ys[last]
            c.set(last, c.xs[first], c.ys[first])
            adjustHeap(c, first, 0, last - first, vx, vy)
        }
    }

    private fun adjustHeap(c: FastCurveMapper, first: Int, holeIn: Int, len: Int, vx: Int, vy: Int) {
        var hole = holeIn
        val top = hole
        var second = hole
        while (second < (len - 1) / 2) {
            second = 2 * (second + 1)
            if (c.less(first + second, first + second - 1)) second--
            c.set(first + hole, c.xs[first + second], c.ys[first + second])
            hole = second
        }
        if ((len and 1) == 0 && second == (len - 2) / 2) {
            second = 2 * (second + 1)
            c.set(first + hole, c.xs[first + second - 1], c.ys[first + second - 1])
            hole = second - 1
        }
        var parent = (hole - 1) / 2
        while (hole > top && FastCurveMapper.pairLess(c.xs[first + parent], c.ys[first + parent], vx, vy)) {
            c.set(first + hole, c.xs[first + parent], c.ys[first + parent])
            hole = parent
            parent = (hole - 1) / 2
        }
        c.set(first + hole, vx, vy)
    }
}
