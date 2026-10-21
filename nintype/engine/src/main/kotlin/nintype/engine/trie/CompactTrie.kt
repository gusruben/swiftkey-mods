package nintype.engine.trie

/**
 * Emkey::CompactTrie (LangEnv+0x778) and Emkey::CompactTrieNode, built exactly like the native
 * loader so node ids, vector ids, child order and every defined byte match the engine
 * (FORMAT.md section 6; verified against the booted engine's memory by TrieDumpTest).
 *
 * CompactTrieNode, 24 bytes:
 *   +0  u8  minDist: chars from the start of this node's label to the nearest terminal below (init 0xC4)
 *   +1  u8  head char of the inline child (stale once the node has a child vector)
 *   +2  u8  bit 0 terminal; bits 1-3 child tier (0 none, 1 inline, 2..5 = 3/12/20/64-slot vector);
 *           bits 4-6 nodeval tier (0 none, 1 inline, 2..4 = 2/4/20-slot vector)
 *   +3  u8  max TrieWord score byte of the words at this node (ORRCTX maxifying)
 *   +4..+0xA label (<= 7 bytes); +0xB NUL slot; bytes past the label length are not defined
 *   +0xC u8 label length
 *   +0x10 u32 child: tier 1 = node id + 1, else child-vector id
 *   +0x14 u32 nodeval: tier 1 = TrieWord id + 1, else nodeval-vector id
 * Child vector = headchars[cap] (Short: padded to 4) then u32 ids[cap] (id + 1, 0 = empty).
 * Nodeval vector = u32 ids[cap] (TrieWord id + 1).
 */
class CompactTrie {
    // SSPC block sizes from CompactTrie::CompactTrie @005a2b24
    val nodes = Sspc(0x8000, NODE) { d, o ->
        // StickySolidPool<CompactTrieNode>::allocNewStuff @005bb2cc: writes a default-constructed
        // node; bytes 4..0xA come from an uninitialised stack temporary (not modelled: left as is).
        d[o] = 0xc4.toByte(); d[o + 1] = 0; d[o + 2] = 0; d[o + 3] = 0
        d[o + 0xc] = 0
        for (i in 0x10 until 0x18) d[o + i] = 0
    }
    val child3 = Sspc(0x1000, 0x10, zero(0x10))
    val child12 = Sspc(0x800, 0x3c, zero(0x3c))
    val child20 = Sspc(0x200, 0x64, zero(0x64))
    val child64 = Sspc(0x18, 0x140, zero(0x140))
    val nodeval2 = Sspc(0x200, 8, zero(8))
    val nodeval4 = Sspc(0x80, 0x10, zero(0x10))
    val nodeval20 = Sspc(0x10, 0x50, zero(0x50))

    /** Root node id (CompactTrie::reset @005a3ce0 allocates it first). */
    val root: Int = nodes.obtainNewStuff()

    // ---------------------------------------------------------------- node byte access
    private fun nd(id: Int) = nodes.data(id)
    private fun no(id: Int) = nodes.off(id)
    fun u8(id: Int, f: Int): Int = nd(id)[no(id) + f].toInt() and 0xff
    fun setU8(id: Int, f: Int, v: Int) { nd(id)[no(id) + f] = v.toByte() }
    fun u32(id: Int, f: Int): Int = le32(nd(id), no(id) + f)
    fun setU32(id: Int, f: Int, v: Int) = setLe32(nd(id), no(id) + f, v)

    fun minDist(id: Int) = u8(id, 0)
    fun flags(id: Int) = u8(id, 2)
    fun isTerminal(id: Int) = flags(id) and 1 != 0
    fun childTier(id: Int) = (flags(id) ushr 1) and 7
    fun nodevalTier(id: Int) = (flags(id) ushr 4) and 7
    fun maxScore(id: Int) = u8(id, 3)
    fun labelLen(id: Int) = u8(id, 0xc)
    fun label(id: Int): ByteArray = nd(id).copyOfRange(no(id) + 4, no(id) + 4 + labelLen(id))

    /** CTNOCTX: the trie, the path of nodes above the current one, the current node id. */
    class Ctx(val path: ArrayList<Int> = ArrayList(), var cur: Int = 0) {
        fun copy() = Ctx(ArrayList(path), cur)
    }

    // ---------------------------------------------------------------- child / nodeval records

    /** getCurrentChildRecsWithHeadchars @005b45c8: (data, headchar offset, ids offset, count). */
    class Recs(val d: ByteArray, val heads: Int, val ids: Int, val n: Int) {
        fun id(i: Int) = le32(d, ids + 4 * i)
        fun head(i: Int) = d[heads + i].toInt() and 0xff
        fun setId(i: Int, v: Int) = setLe32(d, ids + 4 * i, v)
        fun setHead(i: Int, v: Int) { d[heads + i] = v.toByte() }
    }

    fun childRecs(n: Int): Recs {
        val c = u32(n, 0x10)
        return when (childTier(n)) {
            0 -> Recs(nd(n), 0, 0, 0)
            1 -> Recs(nd(n), no(n) + 1, no(n) + 0x10, 1)
            2 -> Recs(child3.data(c), child3.off(c), child3.off(c) + 4, 3)
            3 -> Recs(child12.data(c), child12.off(c), child12.off(c) + 0xc, 12)
            4 -> Recs(child20.data(c), child20.off(c), child20.off(c) + 0x14, 20)
            5 -> Recs(child64.data(c), child64.off(c), child64.off(c) + 0x40, 64)
            else -> error("bad child tier")
        }
    }

    /** getCurrentNodevalRecs @005b034c: (data, ids offset, count). */
    fun nodevalRecs(n: Int): Recs {
        val v = u32(n, 0x14)
        return when (nodevalTier(n)) {
            0 -> Recs(nd(n), 0, 0, 0)
            1 -> Recs(nd(n), 0, no(n) + 0x14, 1)
            2 -> Recs(nodeval2.data(v), 0, nodeval2.off(v), 2)
            3 -> Recs(nodeval4.data(v), 0, nodeval4.off(v), 4)
            4 -> Recs(nodeval20.data(v), 0, nodeval20.off(v), 20)
            else -> error("bad nodeval tier")
        }
    }

    /** Child node ids in slot order (stops at the first empty slot, as every native walker does). */
    fun children(n: Int): List<Int> {
        val r = childRecs(n)
        val out = ArrayList<Int>()
        for (i in 0 until r.n) { val id = r.id(i); if (id == 0) break; out.add(id - 1) }
        return out
    }

    fun trieWordIds(n: Int): List<Int> {
        val r = nodevalRecs(n)
        val out = ArrayList<Int>()
        for (i in 0 until r.n) { val id = r.id(i); if (id == 0) break; out.add(id - 1) }
        return out
    }

    // ---------------------------------------------------------------- insertion

    /** CompactTrieNode::addNewChild(uchar, uint, CTNOCTX&, CompactTrieNode*) @005b3648. */
    fun addNewChild(n: Int, head: Int, cid: Int) {
        val r = childRecs(n)
        if (r.n == 1) {
            setU8(n, 2, (flags(n) and 0xf1) or 4)
            val oldId = u32(n, 0x10)
            val oldHead = u8(n, 1)
            val v = child3.obtainNewStuff()
            setU32(n, 0x10, v)
            val d = child3.data(v); val o = child3.off(v)
            setLe32(d, o + 4, oldId)
            setLe32(d, o + 8, cid + 1)
            d[o] = oldHead.toByte()
            d[o + 1] = head.toByte()
            return
        }
        if (r.n == 0) {
            setU8(n, 2, (flags(n) and 0xf1) or 2)
            setU32(n, 0x10, cid + 1)
            setU8(n, 1, head)
            return
        }
        if (r.id(r.n - 1) == 0) {
            for (i in 0 until r.n) if (r.id(i) == 0) { r.setId(i, cid + 1); r.setHead(i, head); return }
            error("unreachable")
        }
        val ids = IntArray(r.n) { r.id(it) }
        val heads = IntArray(r.n) { r.head(it) }
        val tier = childTier(n)
        when (tier) {
            4 -> child20.erase(u32(n, 0x10))
            3 -> child12.erase(u32(n, 0x10))
            2 -> child3.erase(u32(n, 0x10))
            else -> error("addNewChild: full 64-slot child vector")
        }
        val nt = (tier + 1) and 7
        setU8(n, 2, ((nt shl 1) and 0xe) or (flags(n) and 0xf1))
        val pool = when (nt) { 3 -> child12; 4 -> child20; 5 -> child64; else -> error("tier") }
        val v = pool.obtainNewStuff()
        setU32(n, 0x10, v)
        val nr = childRecs(n)
        for (i in ids.indices) { nr.setId(i, ids[i]); nr.setHead(i, heads[i]) }
        for (i in 0 until nr.n) if (nr.id(i) == 0) { nr.setId(i, cid + 1); nr.setHead(i, head); return }
    }

    /** CompactTrieNode::addNewNodeval(uint, CTNOCTX&, TWRet) @005b0950. */
    fun addNewNodeval(n: Int, tw: Int) {
        val r = nodevalRecs(n)
        if (r.n == 1) {
            setU8(n, 2, (flags(n) and 0x8f) or 0x20)
            val old = u32(n, 0x14)
            val v = nodeval2.obtainNewStuff()
            setU32(n, 0x14, v)
            val d = nodeval2.data(v); val o = nodeval2.off(v)
            setLe32(d, o, old)
            setLe32(d, o + 4, tw + 1)
            return
        }
        if (r.n == 0) {
            setU8(n, 2, (flags(n) and 0x8f) or 0x10)
            setU32(n, 0x14, tw + 1)
            return
        }
        if (r.id(r.n - 1) == 0) {
            for (i in 0 until r.n) if (r.id(i) == 0) { r.setId(i, tw + 1); return }
            error("unreachable")
        }
        val ids = IntArray(r.n) { r.id(it) }
        val tier = nodevalTier(n)
        when (tier) {
            3 -> nodeval4.erase(u32(n, 0x14))
            2 -> nodeval2.erase(u32(n, 0x14))
            else -> return // 20 words on one key: the native code warns once and drops the word
        }
        val nt = (tier + 1) and 7
        setU8(n, 2, ((nt shl 4) and 0x70) or (flags(n) and 0x8f))
        val pool = if (nt == 4) nodeval20 else nodeval4
        val v = pool.obtainNewStuff()
        setU32(n, 0x14, v)
        val nr = nodevalRecs(n)
        for (i in ids.indices) nr.setId(i, ids[i])
        for (i in 0 until nr.n) if (nr.id(i) == 0) { nr.setId(i, tw + 1); return }
    }

    private fun propagateUp(path: List<Int>, from: Int) {
        var d = minDist(from)
        for (k in path.indices.reversed()) {
            val p = path[k]
            d += labelLen(p)
            if (minDist(p) <= d) break
            setU8(p, 0, d)
        }
    }

    /** CompactTrieNode::insertLeaf(char const*, CTNOCTX&, int&) @005ab878. [s] is a NUL-free key,
     *  read from [pos]. Returns the node the key ends at. */
    fun insertLeaf(n: Int, s: ByteArray, pos: Int, ctx: Ctx): Int {
        val len = labelLen(n)
        if (len < 8) nd(n)[no(n) + 4 + len] = 0 // native: this[4 + len] = 0 (len <= 7)
        fun lab(i: Int) = nd(n)[no(n) + 4 + i].toInt() and 0xff
        fun key(i: Int) = if (pos + i < s.size) s[pos + i].toInt() and 0xff else 0
        var i = 0
        var exhausted = lab(0) == 0
        if (lab(0) == key(0)) {
            while (true) {
                if (exhausted) return atend(n, s, s.size, ctx) // both ended: atend(n, "")
                i++
                exhausted = lab(i) == 0
                if (lab(i) != key(i)) break
            }
        }
        if (!exhausted) cut(n, i, ctx)
        return atend(n, s, pos + i, ctx)
    }

    /** CompactTrieNode::atend(char const*, CTNOCTX&, int&) @005b1ae0. */
    fun atend(n: Int, s: ByteArray, pos: Int, ctx: Ctx): Int {
        if (pos >= s.size) {
            if (!isTerminal(n)) {
                setU8(n, 2, flags(n) or 1)
                setU8(n, 0, labelLen(n))
                propagateUp(ctx.path, n)
            }
            return n
        }
        val c = s[pos].toInt() and 0xff
        val r = childRecs(n)
        var child = -1
        for (i in 0 until r.n) {
            if (r.head(i) == c) { child = r.id(i) - 1; break }
            if (r.id(i) == 0) break
        }
        if (child < 0) {
            val m = nodes.obtainNewStuff()
            addNewChild(n, c, m)
            ctx.path.add(n)
            ctx.cur = m
            return initializeAndAtend(m, n, false, 0, s, pos, true, ctx)
        }
        ctx.path.add(n)
        ctx.cur = child
        return insertLeaf(child, s, pos, ctx)
    }

    /** CompactTrieNode::initializeAndAtend(CompactTrieNode*, bool, int, char const*, bool, CTNOCTX&, int&)
     *  @005b5ecc. [s] from [pos] is the label source (NUL-free). */
    fun initializeAndAtend(m: Int, parent: Int, isCut: Boolean, cutAt: Int, s: ByteArray, pos: Int, terminal: Boolean, ctx: Ctx): Int {
        val entry = ctx.path.size
        val sl = s.size - pos
        val l = minOf(sl, 7)
        setU8(m, 0xc, l)
        System.arraycopy(s, pos, nd(m), no(m) + 4, l)
        if (isCut) {
            val p = parent
            setU8(m, 2, (flags(m) and 0xfe) or (flags(p) and 1))
            setU8(p, 2, flags(p) and 0xfe)
            setU32(m, 0x10, u32(p, 0x10))
            val cv1 = flags(p)
            val cv2 = flags(m)
            setU8(m, 2, (cv2 and 0x81) or (cv1 and 0xe) or (flags(p) and 0x70))
            setU32(m, 0x14, u32(p, 0x14))
            setU8(m, 1, u8(p, 1))
            setU8(m, 3, u8(p, 3))
            setU32(p, 0x10, 0)
            setU8(p, 1, 0)
            setU8(p, 3, 0)
            setU32(p, 0x14, 0)
            setU8(p, 2, flags(p) and 0x81)
            setU8(m, 0, minDist(p) - cutAt)
        } else {
            setU8(m, 0, sl)
        }
        val ret: Int
        if (sl < 8) {
            setU8(m, 2, (flags(m) and 0xfe) or (if (terminal) 1 else 0))
            ret = m
        } else {
            setU8(m, 2, flags(m) and 0xfe)
            ret = atend(m, s, pos + 7, ctx)
        }
        while (ctx.path.size > entry) ctx.path.removeAt(ctx.path.size - 1)
        propagateUp(ctx.path, m)
        return ret
    }

    /** CompactTrieNode::cut(int, char const*, CTNOCTX&) @005b1fc0: split n's label at i. */
    fun cut(n: Int, i: Int, ctx: Ctx) {
        val m = nodes.obtainNewStuff()
        val c2 = ctx.copy()
        c2.path.add(n)
        c2.cur = m
        val len = labelLen(n)
        val tail = nd(n).copyOfRange(no(n) + 4 + i, no(n) + 4 + len)
        initializeAndAtend(m, n, true, i, tail, 0, isTerminal(n), c2)
        setU8(n, 2, flags(n) and 0xf0)
        addNewChild(n, u8(n, 4 + i), m)
        val nl = if (len == 0) 0 else minOf(minOf(len, i), 7)
        setU8(n, 0xc, nl) // strncpy(label, label, nl) is a no-op on the bytes
    }

    // ---------------------------------------------------------------- TrieWords and residents

    /** Emkey::TrieWord pool (SSPC<TrieWord> at LangEnv+0x75C, 20000 per block, 16 bytes). */
    val trieWords = Sspc(20000, 16) { d, o ->
        // TrieWord::TrieWord @007c0598 on a zeroed slot (see TrieWord.kt)
        java.util.Arrays.fill(d, o, o + 16, 0)
        setLe32(d, o + 4, 0x1fffff0)
        setLe32(d, o + 0xc, -1)
    }

    /** A deep copy of every pool (the coverage map starts empty). */
    fun copy(): CompactTrie {
        val t = CompactTrie()
        t.nodes.copyFrom(nodes); t.child3.copyFrom(child3); t.child12.copyFrom(child12); t.child20.copyFrom(child20)
        t.child64.copyFrom(child64); t.nodeval2.copyFrom(nodeval2); t.nodeval4.copyFrom(nodeval4); t.nodeval20.copyFrom(nodeval20)
        t.trieWords.copyFrom(trieWords); t.strings.copyFrom(strings)
        return t
    }

    /** The coverage map at CompactTrie+0xE8 (std::map<CompactTrieNode*, CompactCoverageData>),
     *  keyed by node id; filled by Compat.syncCompat during a search. */
    val coverage = HashMap<Int, CompactCoverageData>()

    /** CompactTrie::resetCoverage() @005ab2e4. */
    fun resetCoverage() = coverage.clear()

    /** The trie StringPool (LangEnv+0x744). */
    val strings = StringPool()

    fun rawWord(tw: Int): ByteArray? = strings.get(le32(trieWords.data(tw), trieWords.off(tw) + 0xc))

    /** TrieWord::getRawWord(Moopad*) @007afc24: the word in the active string pool (+0xC id), "" for
     *  id -1 or a missing entry (both assert in the engine). */
    fun getRawWord(tw: Int): ByteArray {
        val id = le32(trieWords.data(tw), trieWords.off(tw) + 0xc)
        if (id == -1) return ByteArray(0)
        return strings.get(id) ?: ByteArray(0)
    }

    /** CompactTrieNode::getRawResident @005aca70: the TrieWord at n whose raw word equals [raw]. */
    fun getRawResident(n: Int, raw: ByteArray): Int {
        for (tw in trieWordIds(n)) if (rawWord(tw)?.contentEquals(raw) == true) return tw
        return -1
    }

    /** CompactTrieNode::getOrAddNewRawResident @005ae280. */
    fun getOrAddNewRawResident(n: Int, raw: ByteArray): Int {
        val f = getRawResident(n, raw)
        if (f >= 0) return f
        val tw = trieWords.obtainNewStuff()
        setLe32(trieWords.data(tw), trieWords.off(tw) + 0xc, strings.store(raw)) // markRawWord
        addNewNodeval(n, tw)
        return tw
    }

    /** CompactTrie::obtainRawResident(std::string const&, LangEnv*, ORRCTX&) @005acd4c, plain path
     *  (no Hangul, no altchain). Returns the TrieWord id, or null when the word needs the Hangul
     *  or multi-key path (not ported yet). [maxScore] is ORRCTX::maxifying's score byte. */
    fun obtainRawResident(raw: ByteArray, maxScore: Int): Int? {
        val key = cookKey(raw) ?: return null
        val ctx = Ctx(cur = root)
        val node = insertLeaf(root, key, 0, ctx)
        val tw = getOrAddNewRawResident(node, raw)
        if (key.size != raw.size) twFlag(tw, 0x10) // markHasNonBasic
        if (u8(node, 3) < maxScore) setU8(node, 3, maxScore)
        return tw
    }

    /** The node a key ends at (label by label, child by head char), or -1. Read-only. */
    fun findNode(key: ByteArray): Int {
        var n = root
        var p = 0
        while (true) {
            val len = labelLen(n)
            if (p + len > key.size) return -1
            for (i in 0 until len) if (nd(n)[no(n) + 4 + i] != key[p + i]) return -1
            p += len
            if (p == key.size) return n
            val c = key[p].toInt() and 0xff
            val r = childRecs(n)
            var next = -1
            for (i in 0 until r.n) {
                if (r.id(i) == 0) break
                if (r.head(i) == c) { next = r.id(i) - 1; break }
            }
            if (next < 0) return -1
            n = next
        }
    }

    /** CompactTrieNode::syncWordScoreBytes(Moopad*, CTNOCTX&) @005aed3c: byte 3 = max best score
     *  byte of the TrieWords at the node. */
    fun syncWordScoreBytes(n: Int) {
        setU8(n, 3, 0)
        for (tw in trieWordIds(n)) {
            val b = TrieWord(trieWords.data(tw), trieWords.off(tw)).bestScoremult8bit()
            if (u8(n, 3) < b) setU8(n, 3, b)
        }
    }

    fun twFlag(tw: Int, bit: Int) {
        val d = trieWords.data(tw); val o = trieWords.off(tw)
        setLe32(d, o, le32(d, o) or (1 shl bit))
    }

    companion object {
        const val NODE = 0x18

        private fun zero(n: Int): (ByteArray, Int) -> Unit = { d, o -> java.util.Arrays.fill(d, o, o + n, 0) }

        fun le32(d: ByteArray, o: Int): Int =
            (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8) or
                ((d[o + 2].toInt() and 0xff) shl 16) or ((d[o + 3].toInt() and 0xff) shl 24)

        fun setLe32(d: ByteArray, o: Int, v: Int) {
            d[o] = v.toByte(); d[o + 1] = (v ushr 8).toByte(); d[o + 2] = (v ushr 16).toByte(); d[o + 3] = (v ushr 24).toByte()
        }

        /** The plain-path key of a raw UTF-8 word: one key char per code point
         *  (keycode_to_chara(chara_to_keycode_no_altchain(cp))). null = Hangul or altchain word. */
        fun cookKey(raw: ByteArray): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            for (cp in Utf8.split(raw)) {
                if (Hangul.isHangul(cp)) return null
                out.write(Keycode.keyChar(cp))
                if (Keycode.hasAltchain(cp)) return null
            }
            return out.toByteArray()
        }
    }
}
