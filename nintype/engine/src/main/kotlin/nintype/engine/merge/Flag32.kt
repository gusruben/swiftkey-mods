package nintype.engine.merge

/** `Shing::Flag32` (a u32 bit set). flagged(int n) @010e4014: asserts n < 32 (unsigned), then
 * tests bits & (1 << n) with ARM register-shift semantics (the bottom byte of n is the shift;
 * 32 or more gives 0). */
object Flag32 {
    fun flagged(bits: Int, n: Int): Boolean {
        val sh = n and 0xff
        return sh < 32 && (bits and (1 shl sh)) != 0
    }
}
