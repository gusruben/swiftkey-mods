package nintype.engine.trie

/**
 * Shing::SSPC<T> (sticky solid pool chain): a list of fixed-size StickySolidPool blocks. Ids are
 * `poolIndex * blocksize + slot`, so the allocation order decides every node / vector / TrieWord id.
 *
 * - obtainNewStuff (e.g. SSPC<CompactTrieNode> @005ab490): ask each pool in order; the first that
 *   can allocate wins; if none can, append a new pool.
 * - StickySolidPool::allocNewStuff: pop the free list (LIFO, from the back) if non-empty, else take
 *   slot `count++` while count < capacity. The element is (re)initialised by [init].
 * - erase (SSPC::erase -> StickySolidPoolBase::erase @007d024c): push the slot onto its pool's
 *   free list. The bytes are left as they are.
 *
 * Elements live in one little-endian ByteArray per pool, laid out exactly like the native pool, so
 * a whole pool can be compared byte for byte with the engine's memory (gen_trie_dump.py).
 */
class Sspc(val blocksize: Int, val elemSize: Int, private val init: (ByteArray, Int) -> Unit) {
    class Pool(cap: Int, elemSize: Int) {
        var count = 0
        val data = ByteArray(cap * elemSize)
        val free = ArrayList<Int>()
    }

    val pools = ArrayList<Pool>()

    fun obtainNewStuff(): Int {
        for ((pi, p) in pools.withIndex()) {
            val slot = alloc(p) ?: continue
            return pi * blocksize + slot
        }
        val p = Pool(blocksize, elemSize)
        pools.add(p)
        return (pools.size - 1) * blocksize + alloc(p)!!
    }

    private fun alloc(p: Pool): Int? {
        val slot = if (p.free.isNotEmpty()) p.free.removeAt(p.free.size - 1)
        else if (p.count < blocksize) p.count++ else return null
        init(p.data, slot * elemSize)
        return slot
    }

    /** Deep copy (pools, counts, free lists). */
    fun copyFrom(o: Sspc) {
        pools.clear()
        for (p in o.pools) {
            val q = Pool(blocksize, elemSize)
            q.count = p.count
            System.arraycopy(p.data, 0, q.data, 0, p.data.size)
            q.free.addAll(p.free)
            pools.add(q)
        }
    }

    fun erase(id: Int) {
        pools[id / blocksize].free.add(id % blocksize)
    }

    fun data(id: Int): ByteArray = pools[id / blocksize].data
    fun off(id: Int): Int = (id % blocksize) * elemSize

    val size: Int get() = if (pools.isEmpty()) 0 else (pools.size - 1) * blocksize + pools.last().count

    /** All pools' live bytes (slots 0 until count), concatenated: the dump layout. */
    fun dumpBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in pools) out.write(p.data, 0, p.count * elemSize)
        return out.toByteArray()
    }

    companion object {
        fun zeroFill(d: ByteArray, o: Int, n: Int) = java.util.Arrays.fill(d, o, o + n, 0)
    }
}

/**
 * Shing::StringPool (LangEnv+0x744): blocks of `blocksize` bytes. Block layout: bytes 0..3 = used
 * byte count (little endian, at least 4); each entry = 0xFF, u16 big-endian length, the bytes (no
 * NUL). store() tries every block in order (StringPoolBlock::store fails when used + 3 + len >
 * capacity) and appends a new block when none fits. No deduplication. The id is
 * `block * blocksize + offset of the 0xFF`.
 */
class StringPool(val blocksize: Int = 0x40000) {
    val blocks = ArrayList<ByteArray>()
    internal val used = ArrayList<Int>()

    fun store(s: ByteArray): Int {
        for (i in blocks.indices) {
            val off = tryStore(i, s)
            if (off >= 0) return i * blocksize + off
        }
        blocks.add(ByteArray(blocksize))
        used.add(4)
        setUsed(blocks.size - 1, 4)
        val off = tryStore(blocks.size - 1, s)
        check(off >= 0) { "string too long for a pool block" }
        return (blocks.size - 1) * blocksize + off
    }

    private fun tryStore(i: Int, s: ByteArray): Int {
        val u = maxOf(used[i], 4)
        val end = u + 3 + s.size
        if (end > blocksize) return -1
        val b = blocks[i]
        b[u] = 0xff.toByte()
        b[u + 1] = (s.size ushr 8).toByte()
        b[u + 2] = s.size.toByte()
        System.arraycopy(s, 0, b, u + 3, s.size)
        setUsed(i, end)
        return u
    }

    private fun setUsed(i: Int, v: Int) {
        used[i] = v
        val b = blocks[i]
        b[0] = v.toByte(); b[1] = (v ushr 8).toByte(); b[2] = (v ushr 16).toByte(); b[3] = (v ushr 24).toByte()
    }

    fun get(id: Int): ByteArray? {
        if (id < 0) return null
        val bi = id / blocksize
        if (bi >= blocks.size) return null
        val off = id % blocksize
        val b = blocks[bi]
        if (off < 4 || off + 3 > used[bi] || (b[off].toInt() and 0xff) != 0xff) return null
        val n = ((b[off + 1].toInt() and 0xff) shl 8) or (b[off + 2].toInt() and 0xff)
        return b.copyOfRange(off + 3, off + 3 + n)
    }

    fun usedBytes(i: Int): Int = used[i]

    fun copyFrom(o: StringPool) {
        blocks.clear(); used.clear()
        for (b in o.blocks) blocks.add(b.copyOf())
        used.addAll(o.used)
    }
}
