package nintype.engine.merge

/**
 * gnustl (libstdc++ 4.9) `std::sort(first, last, comp)` over a list, mirrored step for step
 * (introsort, threshold 16, median-of-three into *first, unguarded partition, heapsort on depth
 * exhaustion, final insertion sort), so equivalent elements end up in the binary's order.
 * Same algorithm as `math.GnuSort` (FastCurveMapper), generic over the element and comparator.
 */
object GnuSortList {
    private const val THRESHOLD = 16

    fun <T> sort(a: MutableList<T>, less: (T, T) -> Boolean) {
        val n = a.size
        if (n == 0) return
        introsortLoop(a, 0, n, 2 * (31 - Integer.numberOfLeadingZeros(n)), less)
        finalInsertionSort(a, 0, n, less)
    }

    private fun <T> swap(a: MutableList<T>, i: Int, j: Int) { val t = a[i]; a[i] = a[j]; a[j] = t }

    private fun <T> introsortLoop(a: MutableList<T>, first: Int, lastIn: Int, depthIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        var depth = depthIn
        while (last - first > THRESHOLD) {
            if (depth == 0) {
                makeHeap(a, first, last, less)
                sortHeap(a, first, last, less)
                return
            }
            depth--
            val mid = first + (last - first) / 2
            moveMedianToFirst(a, first, first + 1, mid, last - 1, less)
            val cut = unguardedPartition(a, first + 1, last, first, less)
            introsortLoop(a, cut, last, depth, less)
            last = cut
        }
    }

    private fun <T> moveMedianToFirst(a: MutableList<T>, result: Int, x: Int, y: Int, z: Int, less: (T, T) -> Boolean) {
        if (less(a[x], a[y])) {
            if (less(a[y], a[z])) swap(a, result, y)
            else if (less(a[x], a[z])) swap(a, result, z)
            else swap(a, result, x)
        } else if (less(a[x], a[z])) swap(a, result, x)
        else if (less(a[y], a[z])) swap(a, result, z)
        else swap(a, result, y)
    }

    private fun <T> unguardedPartition(a: MutableList<T>, firstIn: Int, lastIn: Int, pivot: Int, less: (T, T) -> Boolean): Int {
        var first = firstIn
        var last = lastIn
        while (true) {
            while (less(a[first], a[pivot])) first++
            last--
            while (less(a[pivot], a[last])) last--
            if (first >= last) return first
            swap(a, first, last)
            first++
        }
    }

    private fun <T> finalInsertionSort(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (last - first > THRESHOLD) {
            insertionSort(a, first, first + THRESHOLD, less)
            for (i in first + THRESHOLD until last) unguardedLinearInsert(a, i, less)
        } else insertionSort(a, first, last, less)
    }

    private fun <T> insertionSort(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (first == last) return
        for (i in first + 1 until last) {
            val v = a[i]
            if (less(v, a[first])) {
                for (k in i downTo first + 1) a[k] = a[k - 1]
                a[first] = v
            } else unguardedLinearInsert(a, i, less)
        }
    }

    private fun <T> unguardedLinearInsert(a: MutableList<T>, lastIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        val v = a[last]
        var next = last - 1
        while (less(v, a[next])) {
            a[last] = a[next]
            last = next
            next--
        }
        a[last] = v
    }

    private fun <T> makeHeap(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        val len = last - first
        if (len < 2) return
        var parent = (len - 2) / 2
        while (true) {
            adjustHeap(a, first, parent, len, a[first + parent], less)
            if (parent == 0) return
            parent--
        }
    }

    private fun <T> sortHeap(a: MutableList<T>, first: Int, lastIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        while (last - first > 1) {
            last--
            val v = a[last]
            a[last] = a[first]
            adjustHeap(a, first, 0, last - first, v, less)
        }
    }

    private fun <T> adjustHeap(a: MutableList<T>, first: Int, holeIn: Int, len: Int, v: T, less: (T, T) -> Boolean) {
        var hole = holeIn
        val top = hole
        var second = hole
        while (second < (len - 1) / 2) {
            second = 2 * (second + 1)
            if (less(a[first + second], a[first + second - 1])) second--
            a[first + hole] = a[first + second]
            hole = second
        }
        if ((len and 1) == 0 && second == (len - 2) / 2) {
            second = 2 * (second + 1)
            a[first + hole] = a[first + second - 1]
            hole = second - 1
        }
        var parent = (hole - 1) / 2
        while (hole > top && less(a[first + parent], v)) {
            a[first + hole] = a[first + parent]
            hole = parent
            parent = (hole - 1) / 2
        }
        a[first + hole] = v
    }
}
