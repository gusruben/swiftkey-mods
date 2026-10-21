package nintype.engine.trie

/**
 * shingcachemap<K, V> (template; e.g. obtainEx @004cf2f4, maybeInduceMFU @004cf878, pruneLFU
 * @004cfd44 for <string, CoreccionMarkovChainData>): a std::map with two eviction structures.
 *
 * Layout (from the map object): +0 std::map<K, shingcachemap_elt> (elt: +0 V, +8 int usage,
 * +0xC K*, +0x10 LRU list node or the list sentinel, +0x14 MFU node or the MFU sentinel); +0x18
 * std::list LRU (front = oldest touch); +0x24 MFU multiset keyed by usage (nodes {+0x10 usage,
 * +0x14 elt}), +0x34 its count; +0x38 u8 pruning enabled; +0x3C MFU usage threshold; +0x40 MFU
 * capacity; +0x44 LRU capacity. CoreccionMarkov's chain map: (1, 1, 30, 60); LangEnv's root
 * cache (+0xA88): (1, 1, 4, 8).
 *
 * An entry stays while it is in the LRU list or the MFU set. Touching (obtainEx with n >= 1)
 * moves it to the LRU back, adds n to its usage and re-ranks it in the MFU set; a new entry past
 * the LRU capacity pops the LRU front (erased unless in the MFU set); the MFU set keeps the
 * [mfuCap] highest usages (an entry whose usage does not beat the minimum of a full set stays out;
 * the minimum, earliest inserted first, is dropped and erased unless it is in the LRU list).
 * [onErase] sees every entry the cache drops.
 */
class ShingCacheMap<K, V>(
    val pruning: Boolean,
    val mfuThreshold: Int,
    val mfuCap: Int,
    val lruCap: Int,
    private val newValue: (K) -> V,
    private val onErase: (K, V) -> Unit = { _, _ -> },
) {
    inner class Elt(val key: K, val value: V) {
        var usage = 0
        var inLru = false
        var inMfu = false
    }

    val map = LinkedHashMap<K, Elt>()
    /** LRU order, front = oldest. */
    val lru = ArrayList<Elt>()
    /** MFU multiset in tree order (ascending usage, equal usages in insertion order). */
    val mfu = ArrayList<Elt>()

    operator fun get(key: K): V? = map[key]?.value

    /** shingcachemap::obtainEx(key, n): get or create; n >= 1 counts as a use. */
    fun obtainEx(key: K, n: Int): V {
        val found = map[key]
        if (found != null) {
            if (n >= 1) {
                if (found.inLru) lru.remove(found)
                lru.add(found)
                found.inLru = true
                found.usage += n
                if (found.inMfu) {
                    mfu.remove(found)
                    found.inMfu = false
                }
                maybeInduceMfu(found)
            }
            return found.value
        }
        val e = Elt(key, newValue(key))
        map[key] = e
        lru.add(e)
        e.inLru = true
        if (n >= 1) {
            e.usage += n
            maybeInduceMfu(e)
        }
        if (pruning) {
            while (lru.size > lruCap) {
                val f = lru.removeAt(0)
                f.inLru = false
                if (!f.inMfu) erase(f)
            }
        }
        return e.value
    }

    private fun erase(e: Elt) {
        map.remove(e.key)
        onErase(e.key, e.value)
    }

    /** maybeInduceMFU(elt). */
    private fun maybeInduceMfu(e: Elt) {
        if (mfuCap < 1 || e.inMfu || e.usage < mfuThreshold) return
        if (mfu.size == mfuCap && pruning && e.usage <= mfu[0].usage) return
        var i = mfu.size
        while (i > 0 && e.usage < mfu[i - 1].usage) i--
        mfu.add(i, e)
        e.inMfu = true
        if (pruning) pruneLfu(mfuCap)
    }

    /** pruneLFU(cap): drop MFU minimums until the set has [cap] (>= 0) entries. */
    fun pruneLfu(cap0: Int): Int {
        val cap = maxOf(cap0, 0)
        var n = 0
        while (mfu.size > cap) {
            val e = mfu.removeAt(0)
            e.inMfu = false
            if (!e.inLru) erase(e)
            n++
        }
        return n
    }
}
