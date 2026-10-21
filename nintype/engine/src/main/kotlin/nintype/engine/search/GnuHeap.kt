package nintype.engine.search

/**
 * gnustl (libstdc++ 4.9) heap algorithms, mirrored step for step so that equal-score elements
 * end up in the binary's order: std::__push_heap, std::__adjust_heap, std::pop_heap and the
 * std::priority_queue push/pop built on them. `comp(a, b)` is the heap's less-than: the top is
 * the element no other compares greater than (pathpointer_comparer: `a.score < b.score`, a
 * max-heap; reverse_pathpointer_comparer: `a.score > b.score`, a min-heap).
 */
object GnuHeap {
    /** std::__push_heap(first, holeIndex, topIndex, value, comp). */
    fun <T> pushHeap(a: MutableList<T>, holeIn: Int, top: Int, v: T, comp: (T, T) -> Boolean) {
        var hole = holeIn
        var parent = (hole - 1) / 2
        while (hole > top && comp(a[parent], v)) {
            a[hole] = a[parent]
            hole = parent
            parent = (hole - 1) / 2
        }
        a[hole] = v
    }

    /** std::__adjust_heap(first, holeIndex, len, value, comp). */
    fun <T> adjustHeap(a: MutableList<T>, holeIn: Int, len: Int, v: T, comp: (T, T) -> Boolean) {
        val top = holeIn
        var hole = holeIn
        var second = holeIn
        while (second < (len - 1) / 2) {
            second = 2 * (second + 1)
            if (comp(a[second], a[second - 1])) second--
            a[hole] = a[second]
            hole = second
        }
        if (len and 1 == 0 && second == (len - 2) / 2) {
            second = 2 * (second + 1)
            a[hole] = a[second - 1]
            hole = second - 1
        }
        pushHeap(a, hole, top, v, comp)
    }

    /** priority_queue::push: push_back + std::push_heap. */
    fun <T> push(a: MutableList<T>, v: T, comp: (T, T) -> Boolean) {
        a.add(v)
        pushHeap(a, a.size - 1, 0, v, comp)
    }

    /** priority_queue::pop: std::pop_heap (when size > 1) + pop_back; returns the old top. */
    fun <T> pop(a: MutableList<T>, comp: (T, T) -> Boolean): T {
        val top = a[0]
        if (a.size > 1) {
            val last = a.size - 1
            val v = a[last]
            a[last] = a[0]
            adjustHeap(a, 0, last, v, comp)
        }
        a.removeAt(a.size - 1)
        return top
    }
}

/** A std::string key ordered like std::less<std::string> (memcmp on unsigned bytes, then length). */
class StrKey(val b: ByteArray) : Comparable<StrKey> {
    override fun compareTo(other: StrKey): Int {
        val n = minOf(b.size, other.b.size)
        for (i in 0 until n) {
            val d = (b[i].toInt() and 0xff) - (other.b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return b.size - other.b.size
    }
    override fun equals(other: Any?) = other is StrKey && b.contentEquals(other.b)
    override fun hashCode() = b.contentHashCode()
}
