package nintype.engine.merge

/**
 * Text helpers the merge area needs:
 *  (`shing_towupper(int)` lives in nintype.engine.trie.Caps.shingTowupper, the single port.)
 *  - `Morestring::appendUtf8Char(std::string&, unsigned int)` @007a9430.
 *  - `Hangul::is_litamo(int)` @01422a64.
 */
object ShingText {
    /** appendUtf8Char(s, c): encodes c into a zeroed 4-byte buffer (1 byte if c <= 0x7f, 2 if
     * c < 0x800, 3 if c < 0x10000, else 4, each byte truncated to 8 bits) and appends it up to
     * the first NUL (strlen), so c == 0 appends nothing. */
    fun appendUtf8Char(out: java.io.ByteArrayOutputStream, c: Int) {
        val b = ByteArray(4)
        val u = c.toLong() and 0xffffffffL
        when {
            u <= 0x7f -> b[0] = u.toByte()
            u ushr 11 == 0L -> { b[0] = (0xc0L or (u ushr 6)).toByte(); b[1] = (0x80L or (u and 0x3f)).toByte() }
            u ushr 16 == 0L -> {
                b[0] = (0xe0L or (u ushr 12)).toByte(); b[1] = (0x80L or ((u ushr 6) and 0x3f)).toByte()
                b[2] = (0x80L or (u and 0x3f)).toByte()
            }
            else -> {
                b[0] = (0xf0L or (u ushr 18)).toByte(); b[1] = (0x80L or ((u ushr 12) and 0x3f)).toByte()
                b[2] = (0x80L or ((u ushr 6) and 0x3f)).toByte(); b[3] = (0x80L or (u and 0x3f)).toByte()
            }
        }
        for (x in b) { if (x.toInt() == 0) break; out.write(x.toInt()) }
    }

    /** is_litamo(int c): c - 0x1100 < 0x13 or c - 0x1161 < 0x15 (unsigned): a Hangul jamo. */
    fun isLitamo(c: Int): Boolean =
        Integer.compareUnsigned(c - 0x1100, 0x13) < 0 || Integer.compareUnsigned(c - 0x1161, 0x15) < 0
}
