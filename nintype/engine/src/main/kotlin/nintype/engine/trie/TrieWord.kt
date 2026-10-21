package nintype.engine.trie

import nintype.engine.SoftFloat

/**
 * Emkey::TrieWord, 16 bytes, read and written through a byte image (pool slot or vector image).
 *
 *   +0   Flag32 flags. Bits: 5/6/7 caps (getCapsBools), 10 English, 11 learned, 0xF Hangul,
 *        0x10 nonbasic, 0x11 super-exiled, 0x12 from the language dictionary, 0x13 wild guess,
 *        0x14 scoring locked.
 *   +4   bits 0-3 language mask (flagLangIndex), bits 4-24 MooWord index (signed 21-bit,
 *        all ones = none; hasMooWord tests bit 24 == 0), bits 25-31 unused.
 *        ENGINE QUIRK: StickySolidPool<TrieWord>::allocNewStuff @004cebec builds the TrieWord in an
 *        uninitialised stack temporary and the ctor only ORs 0x1fffff0 into +4, so the mask bits
 *        other than the ones flagLangIndex sets, and bits 25-31, are stack garbage (in the booted
 *        oracle: mask 0xf for 77026 English words, 0x5 for 21; bits 25-31 = 45 or 63). The port
 *        defines them as 0.
 *   +8..+0xB score byte per language index 0..3
 *   +0xC StringPool id of the raw word (-1 = none)
 */
class TrieWord(val d: ByteArray, val o: Int = 0) {
    fun i32(f: Int) = CompactTrie.le32(d, o + f)
    fun setI32(f: Int, v: Int) = CompactTrie.setLe32(d, o + f, v)
    fun u8(f: Int) = d[o + f].toInt() and 0xff

    /** Shing::Flag32::flagged(int) @010e4014: bit test (asserts on n >= 32). */
    fun flagged(bit: Int): Boolean = i32(0) and (1 shl bit) != 0
    /** Shing::Flag32::flag(int) @010e3f94. */
    fun flag(bit: Int) = setI32(0, i32(0) or (1 shl bit))

    fun hasWildGuess() = flagged(0x13)
    fun isFromLangDict() = flagged(0x12)
    fun scoringIsLocked() = flagged(0x14)
    fun isSuperExiled() = flagged(0x11)
    fun hasNonBasic() = flagged(0x10)
    fun markHasNonBasic() = flag(0x10)
    fun hasHangul() = flagged(0xf)
    fun markHangul() = flag(0xf)

    /** getCapsBools(bool&, bool&, bool&) @007c0bb8: (bit 7, bit 6, bit 5). */
    fun capsBools(): Triple<Boolean, Boolean, Boolean> = Triple(flagged(7), flagged(6), flagged(5))

    /** flagLangIndex(int) @007c17f0: +4 |= (1 << i) & 0xf (ARM register shift: i >= 32 gives 0). */
    fun flagLangIndex(i: Int) {
        val s = i and 0xff
        val bit = if (s >= 32) 0 else (1 shl s)
        setI32(4, i32(4) or (bit and 0xf))
    }

    /** emptyLangIndex() @007c180c. */
    fun emptyLangIndex() = u8(4) and 0xf == 0

    /** langIndexIntersectsWith(Flag32 const&) @007c1820. */
    fun langIndexIntersectsWith(mask: Int) = (mask and i32(4) and 0xf) != 0

    /** hasMooWord() @007c19c0: bit 24 of +4 clear. */
    fun hasMooWord() = u8(7) and 1 == 0

    /** MooWord index: bits 4-24 of +4, sign-extended ((x << 7) >> 11). */
    fun mooWordIndex() = (i32(4) shl 7) shr 11

    /** getBestScoremult8bit() @007c0e74: max of the 4 score bytes. */
    fun bestScoremult8bit(): Int = maxOf(maxOf(maxOf(u8(8), u8(9)), u8(10)), u8(11))

    /** getBestScoremult() @007c0ea4: max as floats (ui2f each, fcmplt chain), / 255f. */
    fun bestScoremult(): Float {
        var best = SoftFloat.ui2f(u8(8))
        for (k in 9..11) {
            val v = SoftFloat.ui2f(u8(k))
            if (SoftFloat.fcmplt(best, v)) best = v
        }
        return SoftFloat.fdiv(best, 255.0f)
    }

    /** getScoremultAtIndex(LangOrder) @007c0a28: 0 for unsigned index > 3, else byte / 255f. */
    fun scoremultAtIndex(i: Int): Float =
        if (Integer.compareUnsigned(i, 3) > 0) 0f else SoftFloat.fdiv(SoftFloat.i2f(u8(8 + i)), 255.0f)

    /** isDisabledAtLangIndex(LangOrder) @007c12fc: true for unsigned index > 3, else byte/255f < 0.02f. */
    fun isDisabledAtLangIndex(i: Int): Boolean =
        if (Integer.compareUnsigned(i, 3) > 0) true
        else SoftFloat.fcmplt(SoftFloat.fdiv(SoftFloat.i2f(u8(8 + i)), 255.0f), Float.fromBits(0x3ca3d70a))

    /** getLangflowedScoremult(Moopad*) @007c1018: 0 when super-exiled; else the max over the active
     *  languages i (bit i of [langFlags] = LangEnv+0x5C0, Moopad+0x21BC8) of weight[i] * byte[i]
     *  ([weights] = LangEnv+0x65C.., Moopad+0x21C64..; the first starts from max(that, 0)), / 255. */
    /** TrieWord::isDisabledFCAL(Moopad*) @007b1444: the largest score byte (+8..+0xB) over the active
     *  language indexes ([langFlags] = Moopad+0x21BC8, bits 0-3), / 255, below 0.02. */
    fun isDisabledFCAL(langFlags: Int): Boolean {
        var m = 0
        for (i in 0..3) if (langFlags and (1 shl i) != 0 && u8(8 + i) > m) m = u8(8 + i)
        return SoftFloat.fcmplt(SoftFloat.fdiv(SoftFloat.i2f(m), 255.0f), Float.fromBits(0x3ca3d70a))
    }

    /** TrieWord::isUnfavored(Moopad*) @007b1200: getLangflowedScoremult below 0.5. */
    fun isUnfavored(langFlags: Int, weights: FloatArray): Boolean = SoftFloat.fcmplt(langflowedScoremult(langFlags, weights), 0.5f)

    fun langflowedScoremult(langFlags: Int, weights: FloatArray): Float {
        if (flagged(0x11)) return 0f
        var best = 0f
        for (i in 0..3) {
            if (langFlags and (1 shl i) == 0) continue
            val v = SoftFloat.fmul(weights[i], SoftFloat.i2f(u8(8 + i)))
            if (i == 0) { if (SoftFloat.fcmpgt(v, 0f)) best = v }
            else if (SoftFloat.fcmplt(best, v)) best = v
        }
        return SoftFloat.fdiv(best, 255.0f)
    }
}
