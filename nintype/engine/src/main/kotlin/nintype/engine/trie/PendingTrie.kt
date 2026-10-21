package nintype.engine.trie

import nintype.engine.SoftFloat
import nintype.engine.math.LibM

/**
 * Emkey::PendingWordNode, the value of a pending-trie node (embedded at ptrie +0x18, 0x34 bytes):
 *   +0x00 vector<PendingWord*> words (the PendingWord objects belong to the word-learning module;
 *         here they are opaque references)
 *   +0x0C compat stamp (PathSearch+0x120 of the last syncPendingWordCompat; init 0xFF9CEBC5)
 *   +0x10 parent PendingWordNode* (set by WordLearningModule::initPendingWordNode)
 *   +0x14 prefix length (chars above this node), +0x18 label length, +0x1C u8 last label char
 *   +0x1D s8 shortest distance to a valued node (init 0x78)
 *   +0x20 f32 coverage bound, +0x24 f32 sum(cov * w), +0x28 f32 sum(w), +0x2C, +0x30 u8 (copied, unused here)
 */
class PendingWordNode {
    val words: MutableList<Any> = ArrayList()
    var stamp: Int = 0xff9cebc5.toInt()
    var parent: PendingTrieNode? = null
    var prefix = 0
    var labelLen = 0
    var lastChar = 0
    var shortest = 0x78
    var bound = 0f
    var sumCovW = 0f
    var sumW = 0f
    var f2c = 0
    var b30 = 0

    fun copyFrom(o: PendingWordNode) {
        words.clear(); words.addAll(o.words)
        stamp = o.stamp; parent = o.parent; prefix = o.prefix; labelLen = o.labelLen; lastChar = o.lastChar
        shortest = o.shortest; bound = o.bound; sumCovW = o.sumCovW; sumW = o.sumW; f2c = o.f2c; b30 = o.b30
    }
}

/**
 * ptrie<Emkey::PendingWordNode> (0x7C bytes): the trie of pending (being-learned) words, keyed by
 * trie strings (generate_triestrings). +0..+0x13 label (at most 20 chars), +0x15 u8 label length,
 * +0x16 u8 "valued" flag, +0x18 the [PendingWordNode] value, +0x4C parent, +0x50 vector<ptrie*>
 * children indexed by ptrie_alphalow_indexer (letters in frequency order, '\''; sparse, null-filled), +0x5C
 * std::map<char, ptrie*> for every other char, +0x74 s16 longest potential (syncLongestPotential).
 */
class PendingTrieNode(var parent: PendingTrieNode? = null) {
    var label: ByteArray = ByteArray(0)
    var valued = false
    val value = PendingWordNode()
    val vec: MutableList<PendingTrieNode?> = ArrayList()
    val map = java.util.TreeMap<Int, PendingTrieNode>()
    var longest = 0

    val len get() = label.size
    fun lab(i: Int) = label[i].toInt() and 0xff

    /** Children in the order every walker uses: the vector (skipping nulls), then the map. */
    fun children(): List<PendingTrieNode> = vec.filterNotNull() + map.values

    fun child(c: Int): PendingTrieNode? {
        val i = alphalow(c)
        if (i != -1) return if (i < vec.size) vec[i] else null
        return map[c and 0xff]
    }

    private fun setChild(c: Int, n: PendingTrieNode) {
        val i = alphalow(c)
        if (i != -1) {
            while (vec.size < i + 1) vec.add(null)
            vec[i] = n
        } else map[c and 0xff] = n
    }

    companion object {
        /** English letter-frequency order, the slot order of the child vector. */
        private const val ORDER = "etaoinshrdlcumwfgypbvkjxqz"

        /** ptrie_alphalow_indexer(char) @013efe38 (a jump table): a lowercase letter -> its rank
         *  in "etaoinshrdlcumwfgypbvkjxqz" (e 0, t 1, a 2, ...), '\'' -> 26, else -1. */
        fun alphalow(c: Int): Int = when (val ch = c and 0xff) {
            in 0x61..0x7a -> ORDER.indexOf(ch.toChar())
            0x27 -> 26
            else -> -1
        }

        private fun cstr(s: ByteArray, from: Int): ByteArray {
            var e = from
            while (e < s.size && s[e].toInt() != 0) e++
            return s.copyOfRange(from, e)
        }

        /** ptrie(ptrie*, char const*, PTrieCreationMode) @008e65ec: valued = (mode == 0); a key
         *  longer than 20 chars continues in a child (atend), and the node is then unvalued. */
        fun create(parent: PendingTrieNode?, s: ByteArray, mode: Int): PendingTrieNode {
            val n = PendingTrieNode(parent)
            n.label = s.copyOf(minOf(s.size, 20))
            n.valued = mode == 0
            if (s.size >= 21) {
                n.atend(s, 20, null)
                n.valued = false
            }
            n.longest = 0
            return n
        }

        /** ptrie(ptrie*, char const*, PendingWordNode const&, bool valued, bool takeParentsChildren)
         *  @008ebab8. With takeParentsChildren the parent's children move here and the parent
         *  becomes unvalued (CompactTrie-style cut). */
        fun create(parent: PendingTrieNode, s: ByteArray, v: PendingWordNode, valuedArg: Boolean, take: Boolean): PendingTrieNode {
            val n = PendingTrieNode(parent)
            n.label = s.copyOf(minOf(s.size, 20))
            if (take) {
                parent.valued = false
                if (parent !== n) {
                    n.map.clear(); n.map.putAll(parent.map)
                    n.vec.clear(); n.vec.addAll(parent.vec)
                    parent.map.clear()
                    parent.vec.clear()
                    for (c in n.vec) c?.parent = n
                    for (c in n.map.values) c.parent = n
                }
            }
            if (s.size >= 21) {
                n.valued = false
                n.atend(s, 20, v)
            } else {
                n.value.copyFrom(v)
                n.valued = valuedArg
            }
            n.longest = 0
            return n
        }
    }

    /** ptrie::insert(char const*, PendingWordNode const*) @008eafb8: returns the value of the node
     *  the key ends at. */
    fun insert(s: ByteArray, from: Int, v: PendingWordNode?): PendingWordNode {
        fun key(i: Int) = if (from + i < s.size) s[from + i].toInt() and 0xff else 0
        fun labAt(i: Int) = if (i < len) lab(i) else 0
        var i = 0
        var exhausted = labAt(0) == 0
        if (labAt(0) == key(0)) {
            while (true) {
                if (exhausted) return atend(s, s.size, v)
                i++
                exhausted = labAt(i) == 0
                if (labAt(i) != key(i)) break
            }
        }
        if (!exhausted) cut(i)
        return atend(s, from + i, v)
    }

    /** ptrie::atend(char const*, PendingWordNode const*) @008eb1f4. */
    fun atend(s: ByteArray, from: Int, v: PendingWordNode?): PendingWordNode {
        if (from >= s.size || s[from].toInt() == 0) {
            valued = true
            if (v != null) value.copyFrom(v)
            return value
        }
        val c = s[from].toInt() and 0xff
        var ch = child(c)
        if (ch == null) {
            val rest = cstr(s, from)
            ch = if (v == null) create(this, rest, 0) else create(this, rest, v, true, false)
            setChild(c, ch)
        }
        return ch.insert(s, from, v)
    }

    /** ptrie::cut(int, char const*) @008eb5d0: the tail of the label (with the value and all
     *  children) moves to a new child; this node keeps label[:i] and a fresh value. */
    fun cut(i: Int) {
        val tail = label.copyOfRange(i, len)
        val m = create(this, tail, value, valued, true)
        valued = false
        val fresh = PendingWordNode()
        value.copyFrom(fresh)
        label = label.copyOf(minOf(minOf(i, len), 20))
        setChild(tail[0].toInt() and 0xff, m)
    }

    /** ptrie::clear() @008e70b0. */
    fun clear() {
        vec.clear(); map.clear()
        valued = false
        label = ByteArray(0)
        value.copyFrom(PendingWordNode())
    }

    /** WordLearningModule::initPendingWordNode(ptrie*, PendingWordNode* parent) @008e7268. */
    fun initPendingWordNode(parentValue: PendingTrieNode?) {
        if (!valued) valued = true
        value.parent = parentValue
        value.labelLen = len
        if (len != 0) value.lastChar = lab(len - 1)
        if (parentValue != null) value.prefix = parentValue.value.labelLen + parentValue.value.prefix
        for (c in vec) c?.initPendingWordNode(this)
        for (c in map.values) c.initPendingWordNode(this)
        if (value.words.isNotEmpty()) {
            value.shortest = value.labelLen.toByte().toInt()
            var p = value.parent
            var d = value.shortest
            while (p != null) {
                d += p.value.labelLen
                if (p.value.shortest <= d) return
                p.value.shortest = d.toByte().toInt()
                p = p.value.parent
            }
        }
    }

    /** ptrie::syncLongestPotential() @008e7378: label length + the longest child potential (s16). */
    fun syncLongestPotential() {
        longest = len
        var best = 0
        for (c in children()) {
            c.syncLongestPotential()
            val l = c.longest.toShort().toInt()
            if (best.toShort() < l) best = l
        }
        longest = (longest + best) and 0xffff
    }

    /** ptrie::pathsofar() @008e5c50: every label from the root down to and including this one. */
    fun pathsofar(): ByteArray = (parent?.pathsofar() ?: ByteArray(0)) + label

    /** ptrie::lengthsofar() @012a92c4. */
    fun lengthsofar(): Int = (parent?.lengthsofar() ?: 0) + len

    /** ptrie::canprogress(int, char) @012a9048. */
    fun canprogress(pos: Int, c: Int): Boolean {
        if (c and 0xff == 0) return true
        if (len == pos) {
            val ch = child(c) ?: return false
            return ch.canprogress(0, c)
        }
        return lab(pos) == (c and 0xff)
    }

    /** ptrie::canprogress(int, char const*) @012a92f0. */
    fun canprogress(pos0: Int, s: ByteArray, from: Int = 0): Boolean {
        var n = this
        var pos = pos0
        var k = from
        while (true) {
            if (k >= s.size || s[k].toInt() == 0) return true
            val c = s[k].toInt() and 0xff
            if (n.len == pos) {
                n = n.child(c) ?: return false
                pos = 0
            } else {
                if (n.lab(pos) != c) return false
                pos++; k++
            }
        }
    }

    /** ptriepath::gatherskipadvance(char, std::vector<char>&) @012a821c from (this, pos): the next
     *  chars x such that x then c continues; returns whether any was added. */
    fun gatherskipadvance(pos: Int, c: Int, out: MutableList<Int>): Boolean {
        val ch = c and 0xff
        var added = false
        if (pos == len) {
            for (k in children()) if (k.canprogress(1, ch)) { out.add(k.lab(0)); added = true }
            return added
        }
        val x = lab(pos)
        val ok = if (pos + 1 < len) lab(pos + 1) == ch else child(ch) != null
        if (ok) { out.add(x); added = true }
        return added
    }

    /** ptrie::gatherPossibilitiesAt(int, std::set<char>&) @012a8894. */
    fun gatherPossibilitiesAt(at: Int, out: java.util.TreeSet<Int>) {
        if (len > at) { out.add(lab(at)); return }
        for (c in children()) c.gatherPossibilitiesAt(at - len, out)
    }

    /**
     * PendingWordNode::syncPendingWordCompat(ptrie*, PathSearch*) @008e4b98: the pending-word
     * twin of CompactTrieNode::syncCompat (see Compat.kt), cached per search by the stamp
     * (PathSearch+0x120 = [stamp]) instead of a map, using the value's prefix / label length /
     * last char / shortest fields and a fixed word score of 0.5 (powf(0.5, 0.27027)). The
     * PathSearch-side debug trace (Moopad+0x33404 word) only logs and is not modelled.
     */
    fun syncPendingWordCompat(ps: CompatSearch, stampNow: Int) {
        val v = value
        if (v.stamp == stampNow) return
        v.stamp = stampNow
        v.bound = 1.0f
        v.sumCovW = 0f
        v.sumW = 0f
        v.parent?.let { v.sumCovW = it.value.sumCovW; v.sumW = it.value.sumW }
        for (i in 0 until len) {
            val n = i + v.prefix
            val key = Keycode.trieCharaToKeycode(lab(i))
            if (key == Keycode.WILDCARD) continue
            var cov = ps.nthCharCoverage(n, key)
            if (ps.onlyHasASingleTouch()) {
                val s = ps.slowSlideCoverage(n, key)
                if (SoftFloat.fcmplt(cov, s)) cov = s
            }
            val w = Compat.weight(cov)
            v.sumCovW = SoftFloat.fadd(v.sumCovW, SoftFloat.fmul(w, cov))
            v.sumW = SoftFloat.fadd(w, v.sumW)
        }
        val ratio = SoftFloat.fdiv(SoftFloat.fadd(v.sumCovW, 2.0f), SoftFloat.fadd(v.sumW, 2.0f))
        if (!(SoftFloat.fcmpge(ratio, Compat.F0_62) || SoftFloat.fcmpun(ratio, Compat.F0_62))) {
            v.bound = 0f
            return
        }
        if (v.prefix == 0) return
        val word = pathsofar()
        if (word.isNotEmpty() && v.words.isNotEmpty() && ps.checkTrieNodeLengthMultSatisfaction(word.size, Compat.F0_56)) {
            var sw = 0f
            var scw = 0f
            val at = v.shortest + v.prefix
            for (j in word.indices) {
                val key = Keycode.trieCharaToKeycode(word[j].toInt() and 0xff)
                if (key == Keycode.WILDCARD) continue
                val cov = ps.checkCoverage(j + 1, at, at, key)
                val w = Compat.weight(cov)
                sw = SoftFloat.fadd(sw, w)
                scw = SoftFloat.fadd(scw, SoftFloat.fmul(cov, w))
            }
            val r = SoftFloat.fdiv(scw, sw)
            var b = v.bound
            if (SoftFloat.fcmplt(r, b)) b = r
            val sq = LibM.sqrtf(b)
            val root = if (SoftFloat.fcmpeq(b, Float.NEGATIVE_INFINITY)) Float.POSITIVE_INFINITY
            else Float.fromBits(sq.toRawBits() and 0x7fffffff)
            v.bound = SoftFloat.fmul(LibM.powf(0.5f, Compat.F0_27027), root)
            val tc = ps.checkTouchCoverageByLetters(word)
            if (SoftFloat.fcmplt(tc, v.bound)) v.bound = tc
        } else {
            v.bound = 0f
        }
        val lastKey = if (v.labelLen != 0) Keycode.trieCharaToKeycode(v.lastChar) else -1
        val ender = ps.checkTrieNodeEnderMult(v.labelLen + v.prefix, lastKey)
        var childMax = 0f
        for (c in children()) {
            c.syncPendingWordCompat(ps, stampNow)
            if (SoftFloat.fcmplt(childMax, c.value.bound)) childMax = c.value.bound
        }
        var b = v.bound
        if (SoftFloat.fcmplt(ender, b)) b = ender
        if (SoftFloat.fcmplt(b, childMax)) b = childMax
        v.bound = b
        val best = ps.bestBound
        ps.bestBound = if (SoftFloat.fcmplt(best, b)) b else best
    }
}

/** The trie-building half of WordLearningModule::resyncPendingWordsTrie() @008e6d8c. */
object PendingTrie {
    /** Emkey::generate_triestrings(std::string const&, std::vector<std::string>&) @0117xxxx:
     *  one key char per code point (keycode_to_chara(chara_to_keycode(cp)), so uppercase and
     *  other unmapped chars become '&'); each altchain letter (ß æ œ ĳ) doubles the list with its
     *  2-letter expansion, earlier letters varying fastest. Hangul words are not ported. */
    /** Emkey::get_a_triestring(std::string const&) @0116062c: the first of [generateTriestrings]
     *  (base key char per code point, no altchain expansion). Hangul words are not ported. */
    fun getATriestring(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (cp in Utf8.split(raw)) {
            check(!Hangul.isHangul(cp)) { "Hangul words are not ported" }
            out.write(Keycode.toChara(Keycode.charaToKeycode(cp).first))
        }
        return out.toByteArray()
    }

    fun generateTriestrings(raw: ByteArray): List<ByteArray> {
        var out = listOf(ByteArray(0))
        for (cp in Utf8.split(raw)) {
            check(!Hangul.isHangul(cp)) { "Hangul pending words are not ported" }
            val (k, exp) = Keycode.charaToKeycode(cp)
            val base = byteArrayOf(Keycode.toChara(k).toByte())
            out = if (exp == null) out.map { it + base }
            else out.map { it + base } + out.map { it + exp.toByteArray() }
        }
        return out
    }

    /**
     * Rebuild the pending trie from the module's pending words, in their map order (std::map
     * keyed by the word string): clear, insert every trie string of every word with no value
     * and append the PendingWord to the node's list, then initPendingWordNode(root, null) and
     * syncLongestPotential. (The per-PendingWord Hangul / nonbasic flags resync also sets belong
     * to the PendingWord object, outside the trie.)
     */
    fun resync(root: PendingTrieNode, words: List<Pair<ByteArray, Any>>) {
        root.clear()
        for ((key, pw) in words) for (ts in generateTriestrings(key)) root.insert(ts, 0, null).words.add(pw)
        root.initPendingWordNode(null)
        root.syncLongestPotential()
    }
}
