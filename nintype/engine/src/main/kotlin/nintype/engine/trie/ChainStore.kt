package nintype.engine.trie

/**
 * Emkey::NCWordMeta (0x78 bytes, SSPC<NCWordMeta> at ChainEnv+0x52C, 2000 per pool): +0 u8 head and
 * +1 u8 count of a 20-slot ring of the word's last chain positions (+4..+0x54), +0x54 the word when
 * it has at most 19 bytes (+0x69 length; longer words go to ChainEnv's string pool, id at +0x6C),
 * +0x70 how many chain slots hold the word, +0x74 preferred-chain reference count.
 */
class NCWordMeta(val word: ByteArray) {
    var head = 0
    var n = 0
    val pos = IntArray(20)
    var count = 0
    var pref = 0

    /** NCWordMeta::recordPositionOnChain(int) @01231df4: slot (head + n) % 20 takes [p]; a full
     *  ring advances its head instead of its count. */
    fun recordPositionOnChain(p: Int) {
        pos[(head + n) % 20] = p
        if (n > 0x13) {
            head = if (head + 1 > 0x13) 0 else head + 1
            return
        }
        n++
    }

    /** NCWordMeta::getWordPositions() @01231f90: the ring's non-negative positions, oldest first. */
    fun getWordPositions(): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until n) {
            var k = i + head
            if (k > 0x13) k -= 0x14
            if (pos[k] >= 0) out.add(pos[k])
        }
        return out
    }

    /** NCWordMeta::recordChainErasure() @012322c4. */
    fun recordChainErasure() { if (count != 0) count-- }
}

/**
 * The chain predictor's word store in Emkey::ChainEnv: the word map (NCBucket hash buckets keyed by
 * murmur3 of the standardised word, modelled as one map) from word to NCWordID, the NCWordMeta pool
 * (ids handed out like SSPC::obtainNewStuff: a pool's free list first, else the next unused slot)
 * and the main chain, a 200000-slot ring of word ids (+0x9B8, stored pancho-ciphered; the cipher
 * only protects the file, so ids are kept plain) with +0x984 the earliest historical position,
 * +0x988 start and +0x98C end slot.
 *
 * Construction mirrors ChainEnv::syncEnderNCWords: the sentence enders ".", "?" and "!" get the
 * first three ids (ChainEnv+0x560/+0x564/+0x568).
 *
 * Not ported yet: the ring overwriting its oldest slot once 200000 words are stored (the evicted
 * word's NCWordMeta::recordChainErasure + ChainEnv::maybeExpireMeta / eraseWordMeta); [addWordAtEnd]
 * refuses to wrap instead of guessing.
 */
class ChainStore(var enabled: Boolean = true) {
    val metas = HashMap<Int, NCWordMeta>()
    private val ids = HashMap<BytesKey, Int>()
    private var nextId = 0
    private val freeIds = ArrayList<Int>()
    val ring = IntArray(RING)
    var earliest = 0
    var start = 0
    var end = 0
    var saveCounter = 0
    /** Observer of every addWordAtEnd(std::string) call (on entry, as the oracle watch sees it). */
    var onAdd: ((ByteArray) -> Unit)? = null
    val enders: IntArray

    init {
        enders = intArrayOf(obtainWordMeta(".".toByteArray()), obtainWordMeta("?".toByteArray()), obtainWordMeta("!".toByteArray()))
    }

    companion object {
        const val RING = 200000

        /** ChainEnv::standardiseWord / Emkey::chain_standardise_word: shing_towlower on every code point. */
        fun standardise(word: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (c in Utf8.split(word)) Utf8.append(out, Keycode.shingTowlower(c))
            return out.toByteArray()
        }
    }

    /** NCWordHandle::word(): the stored (standardised) word of an id. */
    fun word(id: Int): ByteArray = metas[id]?.word ?: ByteArray(0)

    /** NCWordHandle::isSentenceEnder(): ".", "?" or "!". */
    fun isSentenceEnder(id: Int) = id == enders[0] || id == enders[1] || id == enders[2]

    /** NCWordHandle::isInclusiveEnder(): "?" or "!", the enders a FutureChain keeps. */
    fun isInclusiveEnder(id: Int) = id == enders[1] || id == enders[2]

    /** ChainEnv::maybeGetWord(word): the id of the standardised word, or -1. */
    fun maybeGetWord(word: ByteArray): Int = ids[BytesKey(standardise(word))] ?: -1

    /** ChainEnv::obtainWordMeta(word, NCWordID&) @0097d414: the standardised word's id, created
     *  (NCWordMeta::reset, word stored) when missing. */
    fun obtainWordMeta(word: ByteArray): Int {
        val std = standardise(word)
        ids[BytesKey(std)]?.let { if (metas[it] != null) return it }
        val id = if (freeIds.isNotEmpty()) freeIds.removeAt(freeIds.size - 1) else nextId++
        metas[id] = NCWordMeta(std)
        ids[BytesKey(std.copyOf())] = id
        return id
    }

    fun used(): Int = Math.floorMod(end - start, RING)

    /** ChainEnv::currentHistoricalPos(). */
    fun currentHistoricalPos(): Int = used() + earliest

    /** ChainEnv::wordAtHistpos(int): the id at a historical position, or -1 outside the chain. */
    fun wordAtHistpos(hp: Int): Int {
        if (hp < earliest || hp >= used() + earliest) return -1
        var k = start + (hp - earliest)
        if (k > RING - 1) k -= RING
        return ring[k]
    }

    /**
     * ChainEnv::addWordAtEnd(std::string const&) @00988160: words of at most 40 bytes, when the
     * chain is enabled (Xnl ChainEnv+0x844), get chain_standardise_word, obtainWordMeta and
     * addWordAtEnd(NCWordHandle) @0098830c: the id goes in the end slot, its NCWordMeta records the
     * historical position and counts one more slot, and the end advances. A save is requested every
     * 101st word (+0x9BC counter).
     */
    fun addWordAtEnd(word: ByteArray) {
        onAdd?.invoke(word)
        if (word.size > 0x28 || !enabled || word.isEmpty()) return
        val id = obtainWordMeta(standardise(word))
        val m = metas[id]!!
        ring[end] = id
        m.recordPositionOnChain(currentHistoricalPos())
        m.count++
        end = if (end > RING - 2) end - (RING - 1) else end + 1
        val gap = Math.floorMod(start - end, RING)
        if (gap < 5) throw UnsupportedOperationException("chain ring wrap (200000 words) is not ported")
        val old = saveCounter
        saveCounter = old + 1
        if (old > 99) saveCounter = 0
    }
}
