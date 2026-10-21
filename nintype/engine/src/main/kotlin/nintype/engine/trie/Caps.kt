package nintype.engine.trie

/**
 * The Moopad settings the capitalisation path reads. Xnl::val is the byte at Xnl+0xC.
 *  - [x21ca0] / [x21cd4] / [x29c5c]: the Xnl at Moopad+0x21CA0, +0x21CD4 (passed on as the Turkish
 *    switch), +0x29C5C (caps disabled: spliceCaps clears the word's caps flags, wordObject_spliceCaps
 *    keeps the word as typed);
 *  - [usf]: Moopad::hasUSF1(n) (user setting flags; 0x123 and 0x40 are read here);
 *  - [debugCapsBypass]: the static splechtref<bool> debug toggle TrieWord::spliceCaps consults (both
 *    of its bytes set = skip the x29C5C override); off on a normal install;
 *  - [focusedLangName]: LangEnv's name of the focused language ("english").
 */
data class CapsSettings(
    val x21ca0: Boolean = false,
    val x21cd4: Boolean = false,
    val x29c5c: Boolean = false,
    val usf: (Int) -> Boolean = { false },
    val debugCapsBypass: Boolean = false,
    val focusedLangName: String = "english",
)

object Caps {
    private const val MAXCP = 0x110000

    /** shing_towupper(int) @00f32a78: generated table ([CaseTables]); out-of-range ints unchanged. */
    fun shingTowupper(c: Int): Int {
        if (c !in 0 until MAXCP) return c
        val t = CaseTables.TOWUPPER
        var lo = 0
        var hi = t.size / 4 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (c < t[mid * 4]) hi = mid - 1 else if (c > t[mid * 4 + 1]) lo = mid + 1
            else return if (t[mid * 4 + 2] == 0) t[mid * 4 + 3] else c + t[mid * 4 + 3]
        }
        error("code point $c not covered")
    }

    private fun inRanges(t: IntArray, c: Int): Boolean {
        var lo = 0
        var hi = t.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (c < t[mid * 2]) hi = mid - 1 else if (c > t[mid * 2 + 1]) lo = mid + 1 else return true
        }
        return false
    }

    /** shing_iswupper(int) @00f22208: generated table ([IswTables], exhaustive); other ints false. */
    fun shingIswupper(c: Int): Boolean = inRanges(IswTables.UPPER, c)

    /** shing_iswlower(int) @00f2188c: generated table ([IswTables], exhaustive); other ints false. */
    fun shingIswlower(c: Int): Boolean = inRanges(IswTables.LOWER, c)

    /** Emkey::turkish_towlower(int) @01171084: İ -> i, I -> ı, else shing_towlower. */
    fun turkishTowlower(c: Int): Int = when (c) { 0x130 -> 0x69; 0x49 -> 0x131; else -> Keycode.shingTowlower(c) }

    /** Emkey::turkish_towupper(int) @011710ac: i -> İ, ı -> I, else shing_towupper. */
    fun turkishTowupper(c: Int): Int = when (c) { 0x69 -> 0x130; 0x131 -> 0x49; else -> shingTowupper(c) }

    private fun mapChars(s: ByteArray, f: (Int, Int) -> Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((i, cp) in Utf8.split(s).withIndex()) Utf8.append(out, f(i, cp))
        return out.toByteArray()
    }

    /** Morestring::toWLower(std::string) @0079fad8: shing_towlower on every code point, stopping at the
     *  first malformed UTF-8 sequence (utf8::internal::validate_next). */
    fun toWLower(s: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (cp in Utf8.splitValid(s)) Utf8.append(out, Keycode.shingTowlower(cp))
        return out.toByteArray()
    }

    /** Morestring::toWUpper(std::string) @007afca4: shing_towupper on every code point. */
    fun toWUpper(s: ByteArray) = mapChars(s) { _, c -> shingTowupper(c) }

    /** Morestring::toWUpperFirstChar(std::string) @007afe70: shing_towupper on the first code point. */
    fun toWUpperFirstChar(s: ByteArray) = mapChars(s) { i, c -> if (i == 0) shingTowupper(c) else c }

    /**
     * Emkey::reflectWordCapsness(std::string&, bool first, bool all, bool lower, bool turkish)
     * @0122ee1c: rebuild the word code point by code point. Unless [first], lower |= !all. Then each
     * char: lower -> towlower; else all (and not first) -> towupper; else first -> towupper for the
     * first char and towlower after it. [turkish] selects the Turkish case maps.
     */
    fun reflectWordCapsness(s: ByteArray, first: Boolean, all: Boolean, lower0: Boolean, turkish: Boolean): ByteArray {
        val lower = if (!first) (!all || lower0) else lower0
        val lo: (Int) -> Int = if (turkish) ::turkishTowlower else Keycode::shingTowlower
        val up: (Int) -> Int = if (turkish) ::turkishTowupper else ::shingTowupper
        val out = java.io.ByteArrayOutputStream()
        for ((i, cp) in Utf8.split(s).withIndex()) {
            val c = when {
                lower -> lo(cp)
                !first && all -> up(cp)
                first -> if (i == 0) up(cp) else lo(cp)
                else -> return out.toByteArray()
            }
            Utf8.append(out, c)
        }
        return out.toByteArray()
    }

    /** Emkey::reflectWordCapsnessFromWordFlags(std::string&, bool a, bool b, bool c, bool d, bool turkish)
     *  @0122ed8c: (!a, b, !c) -> all caps; (a, !c) -> first cap; otherwise first cap when d; else as is. */
    fun reflectWordCapsnessFromWordFlags(s: ByteArray, a: Boolean, b: Boolean, c: Boolean, d: Boolean, turkish: Boolean): ByteArray {
        if (!a && b && !c) return reflectWordCapsness(s, false, true, false, turkish)
        if (a && !c) return reflectWordCapsness(s, true, false, false, turkish)
        if (d) return reflectWordCapsness(s, true, false, false, turkish)
        return s
    }

    /** Emkey::LangEnv::shouldSimplyCapitalize(std::string const&) @0048a388: the English "i" rule
     *  (!hasUSF1(0x123) && hasUSF1(0x40)). */
    fun shouldSimplyCapitalize(word: ByteArray, st: CapsSettings): Boolean =
        !st.usf(0x123) && st.usf(0x40) && word.contentEquals(byteArrayOf(0x69)) && st.focusedLangName == "english"

    /** Emkey::wordObject_spliceCaps(std::string&, bool b1, bool b2, bool b3, bool caps7, bool caps6, bool caps5,
     *  Moopad*) @0122ec6c. */
    fun wordObjectSpliceCaps(s: ByteArray, b1: Boolean, b2: Boolean, b3: Boolean, caps7: Boolean, caps6In: Boolean,
                             caps5In: Boolean, st: CapsSettings): ByteArray {
        var caps6 = caps6In
        var caps5 = caps5In
        if (b1) {
            caps5 = caps5 && caps7 && !caps6
            caps6 = !caps7 || caps6
        }
        val usf = st.usf(0x123)
        var a = false
        var b: Boolean
        var c = true
        if (st.x29c5c) {
            b = false
        } else {
            val five = caps5 || usf
            val ssc = shouldSimplyCapitalize(s, st)
            c = five && !ssc
            if (b2) b = false
            else {
                b = caps7 && !st.x21ca0 && !usf && !ssc && !b3
                a = ((caps6 && !st.x21ca0 && !usf) || ssc) && !b3
            }
        }
        return reflectWordCapsnessFromWordFlags(s, a, b, c, b1, st.x21cd4)
    }

    /** Emkey::TrieWord::spliceCaps(std::string&, bool, bool, bool, Moopad*) @007c0c00: the TrieWord's
     *  caps bits 7 / 6 / 5 into wordObject_spliceCaps; with x29C5C set (and the debug toggle off) the
     *  caps bits and the first flag are dropped and bit 5 forced. */
    fun spliceCaps(flags: Int, s: ByteArray, b1In: Boolean, b2: Boolean, b3: Boolean, st: CapsSettings): ByteArray {
        var caps7 = flags and (1 shl 7) != 0
        var caps6 = flags and (1 shl 6) != 0
        var caps5 = flags and (1 shl 5) != 0
        var b1 = b1In
        if (!st.debugCapsBypass) {
            val x = st.x29c5c
            caps5 = caps5 || x
            b1 = b1 && !x
            caps6 = caps6 && !x
            caps7 = caps7 && !x
        }
        return wordObjectSpliceCaps(s, b1, b2, b3, caps7, caps6, caps5, st)
    }
}
