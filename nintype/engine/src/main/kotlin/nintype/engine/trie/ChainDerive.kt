package nintype.engine.trie

import nintype.engine.geometry.Geometry

/** Emkey::NascentChainlement (12 bytes): +0 id (0 when derived from text), +4 the standardised
 *  word, +8/+9/+0xA flags (0 when derived). */
class NascentChainlement(val word: ByteArray) {
    var id = 0
    var f8 = 0
    var f9 = 0
    var fa = 0
}

/**
 * Emkey::NasVec: deque<NascentChainlement> plus +0x28 the text before was short (< 50 bytes, and
 * nothing was pruned from the front), +0x29 the same for the text after, +0x2C / +0x30 the first
 * element at the cursor and the first one after it, +0x34 / +0x38 the cursor word's first / last
 * element (-1 = none), +0x3C the cursor word is the last element before it (no word at the cursor),
 * +0x40 how many elements follow the last sentence ender (9999 = unknown: no ender and the text
 * before was cut).
 */
class NasVec {
    val els = ArrayList<NascentChainlement>()
    var preShort = false
    var postShort = false
    var f2c = 0
    var f30 = 0
    var f34 = -1
    var f38 = -1
    var f3c = false
    var f40 = 0

    /** NasVec::pruneEnders(int max) @00978b5c: at most [max] elements before the cursor element and
     *  after the post-cursor index; pruning clears the matching "short" flag. */
    fun pruneEnders(max: Int) {
        if (max < f2c) {
            val d = f2c - max
            preShort = false
            repeat(d) { els.removeAt(0) }
            f2c -= d
            f30 -= d
            if (f34 != -1) f34 -= d
            if (f38 != -1) f38 -= d
        }
        val d = els.size - f30
        if (max < d) {
            postShort = false
            repeat(d - max) { els.removeAt(els.size - 1) }
        }
    }
}

/** Emkey::EasyParser over UTF-8 (UTF8EasyParser): code points with their byte offsets. */
private class TextParser(text: ByteArray) {
    val cps = Utf8.split(text)
    val offs = IntArray(cps.size + 1)
    var i = 0

    init {
        var o = 0
        for ((k, c) in cps.withIndex()) {
            offs[k] = o
            o += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c < 0x10000 -> 3
                else -> 4
            }
        }
        offs[cps.size] = o
    }

    fun has() = i < cps.size
    fun peek(k: Int = 0) = if (i + k < cps.size) cps[i + k] else 0
    fun get() { i++ }
    fun point() = offs[i]
}

object ChainDerive {
    private fun isDigit(c: Int) = c - 0x30 in 0..9
    private fun isAlpha(c: Int) = Geometry.shingIswalpha(c)
    /** bionic iswspace. */
    private fun isSpace(c: Int) = c == 0x20 || c in 0x09..0x0d || c == 0x85 || c == 0xa0 || c == 0x1680 ||
        c in 0x2000..0x200a || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000

    /** EasyParser::parseAlphaNumericWord(): digits, letters, U+2018..U+201B, ' and _. */
    private fun parseWord(p: TextParser, out: java.io.ByteArrayOutputStream) {
        while (p.has()) {
            val c = p.peek()
            if (isDigit(c) || isAlpha(c) || c - 0x2018 in 0..3 || c == 0x27 || c == 0x5f) { Utf8.append(out, c); p.get() } else break
        }
    }

    /** EasyParser::parseAlphaNumericEnglishElement(): words joined by '-' or '.' when a letter or
     *  digit follows. */
    private fun parseAnee(p: TextParser): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (p.has() && (isAlpha(p.peek()) || isDigit(p.peek()))) {
            parseWord(p, out)
            val c = p.peek()
            if ((c == 0x2d || c == 0x2e) && (isAlpha(p.peek(1)) || isDigit(p.peek(1)))) { out.write(c); p.get(); continue }
            break
        }
        return out.toByteArray()
    }

    /** EasyParser::parseSentenceElement(): skips spaces (not '\n'), then a word or one character. */
    private fun parseElement(p: TextParser): ByteArray {
        while (p.has() && isSpace(p.peek()) && p.peek() != 0x0a) p.get()
        if (!p.has()) return ByteArray(0)
        val c = p.peek()
        if (isAlpha(c) || isDigit(c)) return parseAnee(p)
        p.get()
        val out = java.io.ByteArrayOutputStream()
        Utf8.append(out, c)
        return out.toByteArray()
    }

    /** Emkey::chain_standardise_word(std::string const&): shing_towlower per code point, with U+2018 /
     *  U+2019 as '. */
    fun chainStandardiseWord(w: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in Utf8.split(w)) Utf8.append(out, if (c == 0x2018 || c == 0x2019) 0x27 else Keycode.shingTowlower(c))
        return out.toByteArray()
    }

    private fun isEnder(w: ByteArray) = w.size == 1 && (w[0] == '.'.code.toByte() || w[0] == '?'.code.toByte() || w[0] == '!'.code.toByte())

    /**
     * Emkey::ChainEnv::deriveChainlements(pre, cur, post) @00986f28: the text around the cursor as
     * chainlements. [pre] is cut after its last newline and [post] before its first; a pre longer
     * than 60 bytes (with no newline) loses its first element and a post that long its last (both
     * may be partial words). Elements are skipped whitespace apart; the cursor indices follow from
     * each element's byte range against the pre and cur ends; pruneEnders(6) then keeps at most 6
     * elements on either side.
     */
    fun deriveChainlements(pre0: ByteArray, cur: ByteArray, post0: ByteArray): NasVec {
        var post = post0
        val postShort = post0.size < 0x32
        var postLong = post0.size > 0x3c
        val nl = post.indexOf(0x0a)
        if (nl >= 0) { post = post.copyOfRange(0, nl); postLong = false }
        var pre = pre0
        val preShort = pre0.size < 0x32
        var preLong = pre0.size > 0x3c
        val pnl = pre.lastIndexOf(0x0a)
        if (pnl >= 0) { pre = pre.copyOfRange(pnl + 1, pre.size); preLong = false }
        val l = pre.size
        val e = l + cur.size
        val p = TextParser(pre + cur + post)
        val v = NasVec()
        var notFirst = false
        var seenAtOrPastE = false
        var sawPast = false
        var set30 = false
        while (true) {
            while (p.has() && isSpace(p.peek())) p.get()  // EasyParser::operator--(int)
            if (!p.has()) break
            val p0 = p.point()
            val tok = chainStandardiseWord(parseElement(p))
            val p1 = p.point()
            sawPast = sawPast || p1 > l
            if (notFirst || !preLong) {
                if (p1 > l && p0 < e) {
                    if (v.f34 == -1) { v.f34 = v.els.size; v.f38 = v.els.size } else v.f38 = v.els.size
                }
                val c = p0 < e && !seenAtOrPastE
                if (!(set30 || c)) { v.f30 = v.els.size; set30 = true }
                v.els.add(NascentChainlement(tok))
                if (!sawPast) v.f2c = v.els.size
            }
            notFirst = true
            seenAtOrPastE = seenAtOrPastE || p0 >= e
        }
        if (postLong && v.els.size != v.f30) v.els.removeAt(v.els.size - 1)
        if (!set30) v.f30 = v.els.size
        v.preShort = preShort
        v.postShort = postShort
        if (v.f34 != -1) {
            v.f2c = v.f34
            v.f30 = v.f38 + 1
        } else if (v.f2c >= 1) {
            v.f2c -= 1
            v.f34 = v.f2c
            v.f38 = v.f2c
            v.f3c = true
        }
        v.pruneEnders(6)
        var n = 0
        var found = false
        for (k in v.els.indices.reversed()) {
            if (isEnder(v.els[k].word)) { found = true; break }
            n++
        }
        v.f40 = if (found || v.preShort) n else 9999
        return v
    }
}
