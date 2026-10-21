package nintype.engine.trie

/**
 * Per-code-point key mapping used to build CompactTrie keys (FORMAT.md section 6.2).
 *
 * The code-point functions (shing_towlower, chara_to_keycode_basic, latin_chara_to_keycode,
 * chara_to_keycode_no_altchain, chara_has_altchain) are switch/table code in the binary. They are
 * ported as run-length tables generated from an exhaustive emulation of every code point
 * 0..0x10FFFF ([KeycodeTables], re/vectors/gen_trie_keycode.py). Every int outside that range
 * behaves the same in the binary (checked on 6000 random ints): towlower is the identity,
 * latin/no_altchain give the wildcard 31, has_altchain and basic are false.
 *
 * Keycodes: 0..25 = a..z, 26..33 = extra letters ('!'..'('), 34..59 = 'A'..'Z' (Hangul jamo),
 * 31 = wildcard ('&'), 60+ = k-12.
 */
object Keycode {
    const val WILDCARD = 0x1f
    private const val MAXCP = 0x110000

    private fun lookup(t: IntArray, c: Int): Int {
        // binary search over runs of 4 ints (start, end, kind, value)
        var lo = 0
        var hi = t.size / 4 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val a = t[mid * 4]
            val b = t[mid * 4 + 1]
            if (c < a) hi = mid - 1 else if (c > b) lo = mid + 1 else {
                val v = t[mid * 4 + 3]
                return if (t[mid * 4 + 2] == 0) v else c + v
            }
        }
        throw IllegalStateException("code point $c not covered")
    }

    /** shing_towlower(int) @00f23ec8. */
    fun shingTowlower(c: Int): Int = if (c in 0 until MAXCP) lookup(KeycodeTables.TOWLOWER, c) else c

    /** Emkey::chara_to_keycode_basic(int, int&) @0116f1d0: a-z, Hebrew U+05D0..05EA (raw offset),
     *  Cyrillic, Hangul jamo (63 - damo index). Returns null when not basic (the out-param is then
     *  left untouched). */
    fun basic(c: Int): Int? {
        if (c !in 0 until MAXCP) return null
        if (lookup(KeycodeTables.BASIC_OK, c) == 0) return null
        return lookup(KeycodeTables.BASIC_OUT, c)
    }

    /** Emkey::latin_chara_to_keycode(int) @0116f3e8: accented Latin / Vietnamese lowercase, else 31. */
    fun latin(c: Int): Int = if (c in 0 until MAXCP) lookup(KeycodeTables.LATIN, c) else WILDCARD

    /** Emkey::chara_to_keycode_no_altchain(int) @0116f060 = basic(towlower(c)) ?: latin(towlower(c)).
     *  Uses the emulated table directly (it equals the composition, checked by the vectors of all three). */
    fun noAltchain(c: Int): Int = if (c in 0 until MAXCP) lookup(KeycodeTables.NO_ALTCHAIN, c) else WILDCARD

    /** Emkey::chara_has_altchain(int) @0116f168: Æ æ ß Ĳ ĳ Œ œ. */
    fun hasAltchain(c: Int): Boolean = c == 0xc6 || c == 0xdf || c == 0xe6 || c == 0x132 || c == 0x133 || c == 0x152 || c == 0x153

    /** Emkey::chara_to_keycode(int, std::string&) @0116fdf4: basic(c) on the UN-lowered code point,
     *  else lc = towlower(c); for ß æ œ ĳ the 2-letter expansion is assigned to [expansion];
     *  returns latin(lc). The expansion is null when the string is left untouched. */
    fun charaToKeycode(c: Int): Pair<Int, String?> {
        basic(c)?.let { return it to null }
        val lc = shingTowlower(c)
        val exp = when (lc) {
            0xdf -> "ss"
            0xe6 -> "ae"
            0x153 -> "oe"
            0x133 -> "ij"
            else -> null
        }
        return latin(lc) to exp
    }

    /** Emkey::keycode_to_chara(int) @0116f034 (signed compares). */
    fun toChara(k: Int): Int = when {
        k <= 0x19 -> k + 0x61
        k <= 0x21 -> k + 7
        k - 0x22 <= 0x19 -> k + 0x1f
        else -> k - 12
    }

    /** Emkey::triechara_to_keycode(int) @0116f128 (unsigned range checks). */
    fun trieCharaToKeycode(c: Int): Int = when {
        Integer.compareUnsigned(c - 0x61, 0x1a) < 0 -> c - 0x61
        Integer.compareUnsigned(c - 0x21, 7) <= 0 -> c - 7
        Integer.compareUnsigned(c - 0x41, 0x19) <= 0 -> c - 0x1f
        else -> c + 0xc
    }

    /** Emkey::keycode_is_valid(int) @0116f0f4: unsigned k < 0x40. */
    fun isValid(k: Int): Boolean = Integer.compareUnsigned(k, 0x40) < 0

    /** Emkey::keycode_is_coverable(int) @0116f108: k != 31 && unsigned k < 0x40. */
    fun isCoverable(k: Int): Boolean = k != WILDCARD && Integer.compareUnsigned(k, 0x40) < 0

    /** Emkey::chara_is_nonbasic(int) @0116ef6c: signed c > 0x80. */
    fun isNonbasic(c: Int): Boolean = c > 0x80

    /** The key character a raw code point contributes on the plain path:
     *  keycode_to_chara(chara_to_keycode_no_altchain(cp)), as a byte. */
    fun keyChar(cp: Int): Int = toChara(noAltchain(cp)) and 0xff
}
