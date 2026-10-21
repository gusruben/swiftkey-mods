package nintype.engine.trie

import nintype.engine.SoftFloat

/**
 * Emkey::CoreccionMarkov: the user's learned evidence for what follows one word (the "root").
 * The engine object (0x13c bytes: +0 refcount, +0xC shingcachemap<string, CoreccionMarkovChainData>
 * whose std::map nodes hold the key at +0x10 and the data {+0x14 a (for), +0x18 b (against)},
 * +0x54 dirty Trigger) is saved per root word to a file named by Morestring::mexify(word).
 * [cache] keys are "next" or "next next2 ..." (the rest of the reinforced phrase joined by ' ').
 */
class CoreccionMarkov {
    /** The chain-data cache (+0xC): shingcachemap(pruning, MFU threshold 1, MFU 30, LRU 60), so a root
     *  keeps its 60 most recently reinforced followers plus up to 30 most reinforced ones. */
    val cache = ShingCacheMap<BytesKey, IntArray>(true, 1, 30, 60, { IntArray(2) })

    /** CoreccionMarkov::check(key, int& a, int& b) @0046b758: a += entry.a, b += entry.b. */
    fun check(key: ByteArray, a: IntArray, b: IntArray): Boolean {
        val e = cache[BytesKey(key)] ?: return false
        a[0] += e[0]
        b[0] += e[1]
        return true
    }

    /**
     * CoreccionMarkov::reinforce(key, n, LangEnv*) @0046b860 (= CoreccionMarkovChainData::reinforce
     * @0046ab0c on the obtained entry): n >= 1 adds to a, else b -= n; once a + b >= 17 both are
     * scaled by 0.666 (truncated), so the evidence saturates around 17.
     */
    fun reinforce(key: ByteArray, n: Int) {
        val e = cache.obtainEx(BytesKey(key), 1)
        if (n >= 1) e[0] += n else e[1] -= n
        if (e[0] + e[1] >= 0x11) {
            e[0] = SoftFloat.f2iz(SoftFloat.fmul(SoftFloat.i2f(e[0]), F0_666))
            e[1] = SoftFloat.f2iz(SoftFloat.fmul(SoftFloat.i2f(e[1]), F0_666))
        }
    }

    companion object {
        private val F0_666 = Float.fromBits(0x3f2a7efa)
    }
}

/** Byte-string map key (std::string compares bytes). */
class BytesKey(val b: ByteArray) {
    override fun equals(other: Any?) = other is BytesKey && other.b.contentEquals(b)
    override fun hashCode() = b.contentHashCode()
    override fun toString() = String(b, Charsets.UTF_8)
}

/**
 * The LangEnv side of the correction markov: the roots and the delayed-reinforcement queue
 * (LangEnv+0xAD0, std::list of PendingMarkovAdditions {+8 vector<string> words, +0x14 int n,
 * +0x18 double time}).
 *
 * Roots live in LangEnv+0xA88, shingcachemap<string, Refcounter<CoreccionMarkov>> (pruning,
 * threshold 1, MFU 4, LRU 8), backed by one file per root ([disk]). A root the cache drops is
 * gone unless it was saved first ([save]; the engine saves through the root's dirty Trigger
 * +0x54, which the headless oracle never fires, so there evicted roots are lost). A saved root is
 * reloaded on its next use: CoreccionMarkov(word) -> load re-inserts the saved entries in file
 * (std::map) order with obtainEx(key, a + b), so its own LRU / MFU state is rebuilt from scratch.
 */
class CoreccionStore {
    /** Saved root files: the (key, a, b) entries in std::map order, as CoreccionMarkov::save writes. */
    val disk = HashMap<BytesKey, List<Triple<ByteArray, Int, Int>>>()
    val roots = ShingCacheMap<BytesKey, CoreccionMarkov>(true, 1, 4, 8, { k -> load(k) })

    private fun load(k: BytesKey): CoreccionMarkov {
        val cm = CoreccionMarkov()
        for ((key, a, b) in disk[k].orEmpty()) {
            val e = cm.cache.obtainEx(BytesKey(key), a + b)
            e[0] = a
            e[1] = b
        }
        return cm
    }

    /** CoreccionMarkov::save(path) @0046bbdc for the root of [word] (when cached). */
    fun save(word: ByteArray) {
        val cm = roots[BytesKey(word)] ?: return
        disk[BytesKey(word.copyOf())] = cm.cache.map.values.sortedWith { x, y -> WordLearningModule.unsignedCompare(x.key.b, y.key.b) }
            .map { Triple(it.key.b, it.value[0], it.value[1]) }
    }

    class Pending(val words: List<ByteArray>, var n: Int, var time: Double)
    val pending = ArrayList<Pending>()

    /** LangEnv::getCoreccionRootIfExistent(word) @0049b0f0: the cached root (no usage counted), else
     *  one with a save file through obtainCorrectionRoot, else null. */
    fun rootIfExistent(word: ByteArray): CoreccionMarkov? =
        roots[BytesKey(word)] ?: if (disk.containsKey(BytesKey(word))) obtainRoot(word) else null

    /** LangEnv::obtainCorrectionRoot(word) @0049b044: obtainEx(word, 1), creating (and loading) it. */
    fun obtainRoot(word: ByteArray): CoreccionMarkov = roots.obtainEx(BytesKey(word.copyOf()), 1)

    /**
     * LangEnv::registerChainReinforcement(words, n) @0049a9ec: root = words[0], key = words[1..]
     * joined by ' ' (empty for a one-word list), reinforce(key, n). An empty list is an engine
     * assert (it reads words[0] anyway); not supported.
     */
    fun registerChainReinforcement(words: List<ByteArray>, n: Int) {
        require(words.isNotEmpty())
        val key = java.io.ByteArrayOutputStream()
        for (i in 1 until words.size) {
            if (i >= 2) key.write(0x20)
            key.write(words[i])
        }
        obtainRoot(words[0]).reinforce(key.toByteArray(), n)
    }

    /**
     * LangEnv::addReinforcementWithDelay(words, n) @0049a2cc: an entry with the same words gets
     * n added (removed when that makes 0) and its time set to [now]; else a new entry is appended.
     */
    fun addReinforcementWithDelay(words: List<ByteArray>, n: Int, now: Double) {
        val i = pending.indexOfFirst { p -> p.words.size == words.size && p.words.indices.all { p.words[it].contentEquals(words[it]) } }
        if (i >= 0) {
            val p = pending[i]
            p.n += n
            if (p.n == 0) pending.removeAt(i) else p.time = now
            return
        }
        pending.add(Pending(words.map { it.copyOf() }, n, now))
    }

    /** LangEnv::updateDelayedReinforcements() @0049a880: every entry with time + [limit] < [now]
     *  is registered (registerChainReinforcement) and removed, in list order. */
    fun updateDelayedReinforcements(now: Double, limit: Double) {
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.time + limit < now) {
                registerChainReinforcement(p.words, p.n)
                it.remove()
            }
        }
    }

    /** The calculateMarkovScoringInner hook for "prev word": the root of prev, check(word). */
    fun check(prev: ByteArray, word: ByteArray, a: IntArray, b: IntArray) {
        rootIfExistent(prev)?.check(word, a, b)
    }

    /** The calculateMarkovScoringInner hook for prev2 + "prev word": the entry's (a, b), if any. */
    fun entry(root: ByteArray, key: ByteArray): Pair<Int, Int>? {
        val e = rootIfExistent(root)?.cache?.get(BytesKey(key)) ?: return null
        return e[0] to e[1]
    }
}
