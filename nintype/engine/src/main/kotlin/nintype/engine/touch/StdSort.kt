package nintype.engine.touch

/**
 * gnustl (libstdc++ 4.9) `std::sort` over a MutableList, mirrored step for step: introsort
 * with threshold 16, median-of-three into *first, unguarded partition, heapsort on depth
 * exhaustion (depth limit 2 * floor(log2 n)), then the final insertion sort. Same algorithm
 * as the FastCurveMapper-specialised one in math/, generic over the element type so the
 * order of equivalent elements matches the binary for any comparator.
 */
object StdSort {
    private const val THRESHOLD = 16

    fun <T> sort(c: MutableList<T>, less: (T, T) -> Boolean) {
        val n = c.size
        if (n == 0) return
        introsortLoop(c, 0, n, 2 * (31 - Integer.numberOfLeadingZeros(n)), less)
        finalInsertionSort(c, 0, n, less)
    }

    private fun <T> swap(c: MutableList<T>, a: Int, b: Int) { val t = c[a]; c[a] = c[b]; c[b] = t }

    private fun <T> introsortLoop(c: MutableList<T>, first: Int, lastIn: Int, depthIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        var depth = depthIn
        while (last - first > THRESHOLD) {
            if (depth == 0) {
                makeHeap(c, first, last, less)
                sortHeap(c, first, last, less)
                return
            }
            depth--
            val mid = first + (last - first) / 2
            moveMedianToFirst(c, first, first + 1, mid, last - 1, less)
            val cut = unguardedPartition(c, first + 1, last, first, less)
            introsortLoop(c, cut, last, depth, less)
            last = cut
        }
    }

    private fun <T> moveMedianToFirst(c: MutableList<T>, result: Int, a: Int, b: Int, cc: Int, less: (T, T) -> Boolean) {
        if (less(c[a], c[b])) {
            if (less(c[b], c[cc])) swap(c, result, b)
            else if (less(c[a], c[cc])) swap(c, result, cc)
            else swap(c, result, a)
        } else if (less(c[a], c[cc])) swap(c, result, a)
        else if (less(c[b], c[cc])) swap(c, result, cc)
        else swap(c, result, b)
    }

    private fun <T> unguardedPartition(c: MutableList<T>, firstIn: Int, lastIn: Int, pivot: Int, less: (T, T) -> Boolean): Int {
        var first = firstIn
        var last = lastIn
        while (true) {
            while (less(c[first], c[pivot])) first++
            last--
            while (less(c[pivot], c[last])) last--
            if (first >= last) return first
            swap(c, first, last)
            first++
        }
    }

    private fun <T> finalInsertionSort(c: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (last - first > THRESHOLD) {
            insertionSort(c, first, first + THRESHOLD, less)
            for (i in first + THRESHOLD until last) unguardedLinearInsert(c, i, less)
        } else insertionSort(c, first, last, less)
    }

    private fun <T> insertionSort(c: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (first == last) return
        for (i in first + 1 until last) {
            if (less(c[i], c[first])) {
                val v = c[i]
                for (k in i downTo first + 1) c[k] = c[k - 1]
                c[first] = v
            } else unguardedLinearInsert(c, i, less)
        }
    }

    private fun <T> unguardedLinearInsert(c: MutableList<T>, lastIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        val v = c[last]
        var next = last - 1
        while (less(v, c[next])) {
            c[last] = c[next]
            last = next
            next--
        }
        c[last] = v
    }

    private fun <T> makeHeap(c: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        val len = last - first
        if (len < 2) return
        var parent = (len - 2) / 2
        while (true) {
            adjustHeap(c, first, parent, len, c[first + parent], less)
            if (parent == 0) return
            parent--
        }
    }

    private fun <T> sortHeap(c: MutableList<T>, first: Int, lastIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        while (last - first > 1) {
            last--
            val v = c[last]
            c[last] = c[first]
            adjustHeap(c, first, 0, last - first, v, less)
        }
    }

    private fun <T> adjustHeap(c: MutableList<T>, first: Int, holeIn: Int, len: Int, v: T, less: (T, T) -> Boolean) {
        var hole = holeIn
        val top = hole
        var second = hole
        while (second < (len - 1) / 2) {
            second = 2 * (second + 1)
            if (less(c[first + second], c[first + second - 1])) second--
            c[first + hole] = c[first + second]
            hole = second
        }
        if ((len and 1) == 0 && second == (len - 2) / 2) {
            second = 2 * (second + 1)
            c[first + hole] = c[first + second - 1]
            hole = second - 1
        }
        var parent = (hole - 1) / 2
        while (hole > top && less(c[first + parent], v)) {
            c[first + hole] = c[first + parent]
            hole = parent
            parent = (hole - 1) / 2
        }
        c[first + hole] = v
    }
}
