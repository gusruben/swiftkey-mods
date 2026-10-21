package nintype.engine.pathscore

/**
 * gnustl (libstdc++ 4.9) std::sort pieces, generic over the element and comparator and each
 * exposed on its own, because the binary's std::sort instantiations are separate unnamed
 * functions (inventory FUN_xxxxxxxx; the callers inline the rest of std::sort):
 * __introsort_loop (threshold 16, median-of-three into *first, unguarded partition, heapsort
 * when the depth budget runs out), __insertion_sort and __unguarded_linear_insert. See
 * inventory/GLOBAL_TRIAGE.md for which instantiation sorts what.
 */
object StlSort {
    private const val THRESHOLD = 16

    private fun <T> swap(a: MutableList<T>, i: Int, j: Int) { val t = a[i]; a[i] = a[j]; a[j] = t }

    /** __introsort_loop(first, last, depth). */
    fun <T> introsortLoop(a: MutableList<T>, first: Int, lastIn: Int, depthIn: Int, less: (T, T) -> Boolean) {
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

    private fun <T> moveMedianToFirst(a: MutableList<T>, r: Int, x: Int, y: Int, z: Int, less: (T, T) -> Boolean) {
        if (less(a[x], a[y])) {
            if (less(a[y], a[z])) swap(a, r, y) else if (less(a[x], a[z])) swap(a, r, z) else swap(a, r, x)
        } else if (less(a[x], a[z])) swap(a, r, x)
        else if (less(a[y], a[z])) swap(a, r, z)
        else swap(a, r, y)
    }

    private fun <T> unguardedPartition(a: MutableList<T>, firstIn: Int, lastIn: Int, pivot: Int, less: (T, T) -> Boolean): Int {
        var f = firstIn
        var l = lastIn
        while (true) {
            while (less(a[f], a[pivot])) f++
            l--
            while (less(a[pivot], a[l])) l--
            if (f >= l) return f
            swap(a, f, l)
            f++
        }
    }

    /** __insertion_sort(first, last). */
    fun <T> insertionSort(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (first == last) return
        for (i in first + 1 until last) {
            val v = a[i]
            if (less(v, a[first])) {
                for (k in i downTo first + 1) a[k] = a[k - 1]
                a[first] = v
            } else unguardedLinearInsert(a, i, less)
        }
    }

    /** __unguarded_linear_insert(last): moves *last down past every greater element. */
    fun <T> unguardedLinearInsert(a: MutableList<T>, lastIn: Int, less: (T, T) -> Boolean) {
        val v = a[lastIn]
        var j = lastIn - 1
        while (less(v, a[j])) { a[j + 1] = a[j]; j-- }
        a[j + 1] = v
    }

    /** std::sort's tail as the callers inline it (__final_insertion_sort). */
    fun <T> finalInsertionSort(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (last - first > THRESHOLD) {
            insertionSort(a, first, first + THRESHOLD, less)
            for (i in first + THRESHOLD until last) unguardedLinearInsert(a, i, less)
        } else insertionSort(a, first, last, less)
    }

    /** Whole std::sort(first, last, comp). */
    fun <T> sort(a: MutableList<T>, less: (T, T) -> Boolean) {
        val n = a.size
        if (n == 0) return
        introsortLoop(a, 0, n, 2 * (31 - Integer.numberOfLeadingZeros(n)), less)
        finalInsertionSort(a, 0, n, less)
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

    /** __adjust_heap(first, hole, len, value) (with __push_heap). */
    fun <T> adjustHeap(a: MutableList<T>, first: Int, holeIn: Int, len: Int, v: T, less: (T, T) -> Boolean) {
        var hole = holeIn
        val top = hole
        var ch = hole
        while (ch < (len - 1) / 2) {
            ch = 2 * (ch + 1)
            if (less(a[first + ch], a[first + ch - 1])) ch--
            a[first + hole] = a[first + ch]
            hole = ch
        }
        if (len and 1 == 0 && ch == (len - 2) / 2) {
            ch = 2 * (ch + 1)
            a[first + hole] = a[first + ch - 1]
            hole = ch - 1
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
