package nintype.engine.trie

/**
 * Emkey::CombinedTriePath (0x28 bytes): a CompactTriePath (+0..+0x1B) over the dictionary plus a
 * ptriepath<PendingWordNode> {ptrie* node +0x1C, int pos +0x20} over the pending words, and two
 * dead flags: +0x24 pending side dead, +0x25 dictionary side dead. Every query asks the live
 * sides, dictionary first.
 */
class CombinedTriePath(
    val main: CompactTriePath,
    var pnode: PendingTrieNode?,
    var ppos: Int = 0,
    var pendDead: Boolean = false,
    var mainDead: Boolean = false,
) {
    // ---- ptriepath<PendingWordNode> ops
    /** ptriepath::advance(char) @012a7548. */
    private fun pAdvance(c: Int): Boolean {
        val n = pnode!!
        if (ppos == n.len) {
            val ch = n.child(c) ?: return false
            ppos = 1
            pnode = ch
            return true
        }
        if (n.lab(ppos) == (c and 0xff)) { ppos++; return true }
        return false
    }

    /** ptriepath::canadvance(char) @012a7268: at the label end a child slot for c exists. */
    private fun pCanadvance(c: Int): Boolean {
        val n = pnode!!
        if (ppos == n.len) return n.child(c) != null
        return n.lab(ppos) == (c and 0xff)
    }

    /** CombinedTriePath::advance(char) @012a74d0: advance both live sides; a side that cannot
     *  advance dies. True when either side moved. */
    fun advance(c: Int): Boolean {
        var moved = false
        if (!mainDead) {
            moved = main.advance(c)
            if (!moved) mainDead = true
        }
        if (!pendDead) {
            if (pAdvance(c)) return true
            pendDead = true
        }
        return moved
    }

    /** canadvance() @012a7044. */
    fun canadvance(): Boolean {
        if (!mainDead && main.canadvance()) return true
        if (pendDead) return false
        val n = pnode!!
        return !(ppos == n.len && n.map.isEmpty() && n.vec.isEmpty())
    }

    /** canadvance(char) @012a71fc. */
    fun canadvance(c: Int): Boolean {
        if (!mainDead && main.canadvance(c)) return true
        return !pendDead && pCanadvance(c)
    }

    /** canadvance(char const*) @012a70bc. */
    fun canadvance(s: ByteArray): Boolean {
        if (!mainDead && main.canadvance(s)) return true
        return !pendDead && pnode!!.canprogress(ppos, s)
    }

    /** canadvance(char, char) @012a712c. */
    fun canadvance(a: Int, b: Int): Boolean {
        if (!mainDead && main.canadvance(a, b)) return true
        return !pendDead && pnode!!.canprogress(ppos, byteArrayOf(a.toByte(), b.toByte()))
    }

    /** isvalued() @012a76bc: ... or at the end of a valued pending node with words (getval). */
    fun isvalued(): Boolean {
        if (!mainDead && main.isvalued()) return true
        if (pendDead) return false
        val n = pnode!!
        return ppos == n.len && n.valued && n.value.words.isNotEmpty()
    }

    /** getPendWords(vector<PendingWord*>&) @012a87f8: the pending node's words at its label end. */
    fun getPendWords(): List<Any> {
        if (pendDead) return emptyList()
        val n = pnode!!
        if (ppos != n.len || !n.valued) return emptyList()
        return ArrayList(n.value.words)
    }

    /** lengthsofar() @012a7ae0. */
    fun lengthsofar(): Int {
        if (mainDead && !pendDead) return ppos + (pnode!!.parent?.lengthsofar() ?: 0)
        return main.lengthsofar()
    }

    /** pathsofar() @012a7b3c: the live dictionary path, else the pending path, else "". */
    fun pathsofar(): ByteArray {
        if (!mainDead) return main.pathsofar()
        if (!pendDead) {
            // ptriepath::pathsofar() @012a7c24: the parent chain's labels, then min(len, pos) chars
            val n = pnode!!
            val head = n.parent?.pathsofar() ?: ByteArray(0)
            return if (n.len == 0) head else head + n.label.copyOf(minOf(n.len, ppos))
        }
        return ByteArray(0)
    }

    /** hasCurnode() @012a7ea0 (node pointers are never null in the port). */
    fun hasCurnode(): Boolean = if (!mainDead) true else !pendDead && pnode != null

    /** hasMainNode() @012a7ee8 / mainNode() @012a7ef8. */
    fun hasMainNode() = true
    fun mainNode() = main.node

    /** shortestUntilEnder() @012a7f00. */
    fun shortestUntilEnder(): Int {
        if (pendDead) return main.shortestUntilEnder()
        val v = pnode!!.value.shortest - ppos
        if (!mainDead) return minOf(v, main.shortestUntilEnder())
        return v
    }

    fun longestPotentialLeft() = 9999

    /** operator<(CombinedTriePath const&) const @012a7fd8: dictionary path, then pending node
     *  (pointer: identity order, the oracle has one pending node), pending pos, then the flags. */
    fun lessThan(o: CombinedTriePath, pendOrder: (PendingTrieNode?, PendingTrieNode?) -> Int): Boolean {
        if (main.lessThan(o.main)) return true
        if (o.main.lessThan(main)) return false
        val c = pendOrder(pnode, o.pnode)
        if (c != 0) return c < 0
        if (ppos != o.ppos) return ppos < o.ppos
        val t25 = if (mainDead) 1 else 0
        val o25 = if (o.mainDead) 1 else 0
        if (t25 != o25) return t25 < o25
        return (if (pendDead) 1 else 0) < (if (o.pendDead) 1 else 0)
    }

    /** gatherSeconds(std::set<char>&) @012a8848. */
    fun gatherSeconds(out: java.util.TreeSet<Int>) {
        if (!mainDead) main.gatherSeconds(out)
        if (pendDead) return
        pnode!!.gatherPossibilitiesAt(ppos + 1, out)
    }

    /** gatherskipadvance(char, std::vector<char>&) @012a8080: returns whether a side added a char
     *  (one live side: that side's result; both: r1 | r2, even when the merged list is empty).
     *  With both sides live the chars are collected, sorted and de-duplicated (consecutive,
     *  starting from 0, so a 0 char is dropped). */
    fun gatherskipadvance(c: Int, out: MutableList<Int>): Boolean {
        if (pendDead) return main.gatherskipadvance(c, out)
        val n = pnode!!
        if (mainDead) return n.gatherskipadvance(ppos, c, out)
        val tmp = ArrayList<Int>()
        val r1 = main.gatherskipadvance(c, tmp)
        val r2 = n.gatherskipadvance(ppos, c, tmp)
        if (!(r1 || r2)) return false
        if (tmp.isNotEmpty()) {
            tmp.sort()
            var last = 0
            for (x in tmp) if (x != last) { out.add(x); last = x }
        }
        return true
    }

    /** syncCompat(PathSearch*) @012a7f88: the dictionary side through Compat.syncCompat; the
     *  pending side through PendingWordNode::syncPendingWordCompat ([psStamp] = PathSearch+0x120). */
    fun syncCompat(ps: CompatSearch, psStamp: Int) {
        if (!mainDead) Compat.syncCompat(main, ps, main.trie.coverage)
        if (!pendDead) pnode!!.syncPendingWordCompat(ps, psStamp)
    }

    /** coverageValue(PathSearch*) @012a7a6c: max of the dictionary bound and, when the pending
     *  node's compat stamp equals PathSearch+0x120 ([psStamp]), its coverage value. */
    fun coverageValue(psStamp: Int): Float {
        val a1 = if (!mainDead) Compat.coverageValue(main, main.trie.coverage) else 0f
        var a2 = 0f
        if (!pendDead && pnode!!.value.stamp == psStamp) a2 = pnode!!.value.bound
        return if (nintype.engine.SoftFloat.fcmplt(a1, a2)) a2 else a1
    }

    /** getTrieWords(vector<TWRet>&, Moopad*) @012a87e8. */
    fun getTrieWords(): List<Int> = if (mainDead) emptyList() else main.getTrieWords()
}
