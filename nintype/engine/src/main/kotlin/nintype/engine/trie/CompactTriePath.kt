package nintype.engine.trie

/**
 * Read-only CompactTrieNode queries (they take a CTNOCTX only for its trie) and
 * Emkey::CompactTriePath, the cursor PathSearch moves through the trie.
 *
 * CompactTriePath (0x1C bytes, a CTNOCTX prefix): +0 CompactTrie*, +4 vector<CompactTrieNode*> path
 * (the nodes above the current one), +0x10 current node id, +0x14 current node pointer,
 * +0x18 pos (chars of the current node's label consumed). Here node pointers are node ids; the
 * oracle's node pools are mapped in id order, so pointer order == id order (operator<).
 */
object TrieQuery {
    /** CompactTrieNode::canprogress(int, char, CTNOCTX&) @005b6578. c == 0 -> true. pos == len:
     *  a child with head c (scanning slots until the first empty one, head checked first).
     *  Otherwise label[pos] == c (pos > len asserts in the binary and then reads past the label;
     *  not a reachable state). */
    fun canprogress(t: CompactTrie, n: Int, pos: Int, c: Int): Boolean {
        val ch = c and 0xff
        if (ch == 0) return true
        if (t.labelLen(n) == pos) {
            val r = t.childRecs(n)
            for (i in 0 until r.n) {
                if (r.head(i) == ch) return canprogress(t, r.id(i) - 1, 0, ch)
                if (r.id(i) == 0) return false
            }
            return false
        }
        return t.u8(n, 4 + pos) == ch
    }

    /** CompactTrieNode::canprogress(int, char const*, CTNOCTX&) @005b67a0: the whole string
     *  continues from (n, pos). */
    fun canprogress(t: CompactTrie, n0: Int, pos0: Int, s: ByteArray, from: Int = 0): Boolean {
        var n = n0
        var pos = pos0
        var k = from
        while (true) {
            if (k >= s.size || s[k].toInt() == 0) return true
            val ch = s[k].toInt() and 0xff
            if (t.labelLen(n) == pos) {
                val r = t.childRecs(n)
                var next = -1
                for (i in 0 until r.n) {
                    if (r.head(i) == ch) { next = r.id(i) - 1; break }
                    if (r.id(i) == 0) break
                }
                if (next < 0) return false
                n = next
                pos = 0
            } else {
                if (t.u8(n, 4 + pos) != ch) return false
                pos++
                k++
            }
        }
    }

    /** CompactTrieNode::gatherPossibilitiesAt(int, std::set<char>&, CTNOCTX&) @005b63bc: the
     *  chars at offset [at] from the start of n's label, through every child when at >= len. */
    fun gatherPossibilitiesAt(t: CompactTrie, n: Int, at: Int, out: java.util.TreeSet<Int>) {
        val len = t.labelLen(n)
        if (len <= at) {
            for (c in t.children(n)) gatherPossibilitiesAt(t, c, at - len, out)
            return
        }
        out.add(t.u8(n, 4 + at))
    }

    /** CompactTrieNode::getTrieWords(vector<TWRet>&, CTNOCTX&, Moopad*) @005b2658: the node's
     *  TrieWord ids in slot order. */
    fun getTrieWords(t: CompactTrie, n: Int): List<Int> = t.trieWordIds(n)
}

class CompactTriePath(val trie: CompactTrie, val path: ArrayList<Int> = ArrayList(), var curid: Int = trie.root,
                      var node: Int = trie.root, var pos: Int = 0) {
    fun copy() = CompactTriePath(trie, ArrayList(path), curid, node, pos)

    /** CompactTriePath::reset(CompactTrie*) @005b758c: node = root, pos = 0 (path and curid kept). */
    fun reset() { node = trie.root; pos = 0 }

    /** advance(char) @005ac39c: within the label, step if the next label char is c; at the label
     *  end, move to the child whose label starts with c (pushing the node, pos = 1). Returns
     *  whether it moved (the decompile shows void; the binary returns the flag in r0). */
    fun advance(c: Int): Boolean {
        val ch = c and 0xff
        val len = trie.labelLen(node)
        if (pos == len) {
            for (cid in trie.children(node)) {
                if (trie.u8(cid, 4) == ch) {
                    path.add(node)
                    curid = cid
                    node = cid
                    pos = 1
                    return true
                }
            }
            return false
        }
        if (trie.u8(node, 4 + pos) == ch) { pos++; return true }
        return false
    }

    /** canadvance(char) @005ac388. */
    fun canadvance(c: Int) = TrieQuery.canprogress(trie, node, pos, c)

    /** canadvance() @005b76d0: inside the label, or the node has children. */
    fun canadvance(): Boolean = pos != trie.labelLen(node) || trie.childTier(node) != 0

    /** canadvance(char, char) @005b770c: the 2-char C string {a, b, 0}. */
    fun canadvance(a: Int, b: Int) = TrieQuery.canprogress(trie, node, pos, byteArrayOf(a.toByte(), b.toByte()))

    /** canadvance(char const*) @005b76f8. */
    fun canadvance(s: ByteArray) = TrieQuery.canprogress(trie, node, pos, s)

    /** isvalued() @005ac700: at the label end of a terminal node. */
    fun isvalued() = pos == trie.labelLen(node) && trie.isTerminal(node)

    /** shortestUntilEnder() @005b76b0: minDist - pos. */
    fun shortestUntilEnder() = trie.minDist(node) - pos

    /** longestPotentialLeft() @005b76c4. */
    fun longestPotentialLeft() = 9999

    /** lengthsofar() const @005b7bec: path label lengths + pos. */
    fun lengthsofar(): Int {
        var s = 0
        for (p in path) s += trie.labelLen(p)
        return s + pos
    }

    /** pathsofar() const @005b7c20: the path nodes' labels, then min(len, pos) chars of this one. */
    fun pathsofar(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in path) out.write(trie.label(p))
        val len = trie.labelLen(node)
        out.write(trie.label(node), 0, minOf(len, pos))
        return out.toByteArray()
    }

    /** gatherSeconds(std::set<char>&) @005b7640 = gatherPossibilitiesAt(node, pos + 1). */
    fun gatherSeconds(out: java.util.TreeSet<Int>) = TrieQuery.gatherPossibilitiesAt(trie, node, pos + 1, out)

    /** gatherskipadvance(char, std::vector<char>&) @005b7784: the next chars x (in child order)
     *  such that x then c continues the path. */
    fun gatherskipadvance(c: Int, out: MutableList<Int>): Boolean {
        val len = trie.labelLen(node)
        var added = false
        if (pos == len) {
            for (cid in trie.children(node)) if (TrieQuery.canprogress(trie, cid, 1, c)) { out.add(trie.u8(cid, 4)); added = true }
        } else {
            val x = trie.u8(node, 4 + pos)
            if (TrieQuery.canprogress(trie, node, pos + 1, c)) { out.add(x); added = true }
        }
        return added
    }

    /** getTrieWords(vector<TWRet>&, Moopad*) @005b7dec: the node's words when isvalued(). */
    fun getTrieWords(): List<Int> = if (isvalued()) TrieQuery.getTrieWords(trie, node) else emptyList()

    /** operator<(CompactTriePath const&) const @005b7658: by node (pointer), then pos. */
    fun lessThan(o: CompactTriePath): Boolean = if (node != o.node) node < o.node else pos < o.pos
}
