package nintype.engine.trie

/** Morestring::UTF8Splitter, for well-formed UTF-8 (every pack word is valid UTF-8, FORMAT.md 4).
 *  Malformed input handling is not ported. */
object Utf8 {
    fun split(b: ByteArray): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            val (n, init) = when {
                c < 0x80 -> 1 to c
                c < 0xe0 -> 2 to (c and 0x1f)
                c < 0xf0 -> 3 to (c and 0x0f)
                else -> 4 to (c and 0x07)
            }
            var cp = init
            for (k in 1 until n) cp = (cp shl 6) or ((b.getOrElse(i + k) { 0 }.toInt()) and 0x3f)
            out.add(cp)
            i += n
        }
        return out.toIntArray()
    }

    /**
     * Decoding with utf8::internal::validate_next (utf8-cpp), as Morestring::toWLower / toWUpper
     * loop: the code points before the first malformed sequence (bad lead byte 0x80..0xBF or
     * >= 0xF8, truncated or bad continuation, overlong, surrogate, > 0x10FFFF); the rest is dropped.
     */
    fun splitValid(b: ByteArray): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            val n = when {
                c < 0x80 -> 1
                c shr 5 == 0x6 -> 2
                c shr 4 == 0xe -> 3
                c shr 3 == 0x1e -> 4
                else -> break
            }
            if (i + n > b.size) break
            var cp = if (n == 1) c else c and (0xff shr (n + 1))
            var ok = true
            for (k in 1 until n) {
                val t = b[i + k].toInt() and 0xff
                if (t shr 6 != 0x2) { ok = false; break }
                cp = (cp shl 6) or (t and 0x3f)
            }
            if (!ok) break
            if ((cp < 0x80 && n != 1) || (cp < 0x800 && n > 2) || (cp < 0x10000 && n > 3)) break
            if (cp > 0x10ffff || cp in 0xd800..0xdfff) break
            out.add(cp)
            i += n
        }
        return out.toIntArray()
    }

    /** Morestring::appendUtf8Char(std::string&, unsigned int) (merge's verified port: 0 appends nothing). */
    fun append(out: java.io.ByteArrayOutputStream, cp: Int) = nintype.engine.merge.ShingText.appendUtf8Char(out, cp)
}
