package nintype.engine.trie

/** Hangul:: helpers used when cooking trie keys. */
object Hangul {
    /** Hangul::is_hangul(int) @0141fd50 (unsigned range checks): syllables U+AC00..U+D6EF (the
     *  binary's bound is 0x2AF0, short of U+D7A3), jamo U+1100..1112, U+1161..1175, U+11A8..11C2,
     *  compatibility jamo U+3131..318E. */
    fun isHangul(c: Int): Boolean =
        Integer.compareUnsigned(c - 0xac00, 0x2af0) < 0 ||
            Integer.compareUnsigned(c - 0x11a8, 0x1b) < 0 ||
            Integer.compareUnsigned(c - 0x1100, 0x13) < 0 ||
            Integer.compareUnsigned(c - 0x1161, 0x15) < 0 ||
            Integer.compareUnsigned(c - 0x3131, 0x5e) < 0
}
