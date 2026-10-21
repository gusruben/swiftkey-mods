package nintype.engine.merge

import nintype.engine.vectors.MemImage

/** Trivial `Emkey::PendingWord` getters (the learning side lives with the dictionary). */
object PendingWord {
    /** getScore() @008e462c: constant 0.5f (`mov r0, #0x3f000000`). */
    fun getScore(): Float = 0.5f

    /** hasHangul() @008e463c: u8 at +0xc. */
    fun hasHangul(pw: MemImage): Int = pw.u8(0xc)

    /** hasNonBasic() @008e4634: u8 at +0xd. */
    fun hasNonBasic(pw: MemImage): Int = pw.u8(0xd)

    /** getCapsBools(bool& a, bool& b, bool& c) @008e4644 over the Flag32 at +0x28: flag 2 ->
     * (false, false, true); else (flag 1, flag 0, flag 4). */
    fun getCapsBools(pw: MemImage): Triple<Boolean, Boolean, Boolean> {
        val f = pw.i32(0x28)
        if (Flag32.flagged(f, 2)) return Triple(false, false, true)
        return Triple(Flag32.flagged(f, 1), Flag32.flagged(f, 0), Flag32.flagged(f, 4))
    }
}
