package nintype.engine

/**
 * Raw bit-cast helpers. The JVM's `Float`/`Double` boxed-bits intrinsics
 * (`Float.floatToRawIntBits` / `Float.intBitsToFloat`, same for Double) are
 * available on Android API 1 and do exactly what we need: a reinterpret
 * cast with no canonicalization of NaN bit patterns (unlike
 * `floatToIntBits`, which *does* canonicalize NaNs -- never use that one
 * here, since the whole point of this port is to compare raw bit patterns
 * against the original binary's).
 */
object Bits {
    fun floatToBits(v: Float): Int = java.lang.Float.floatToRawIntBits(v)

    fun bitsToFloat(bits: Int): Float = java.lang.Float.intBitsToFloat(bits)

    fun doubleToBits(v: Double): Long = java.lang.Double.doubleToRawLongBits(v)

    fun bitsToDouble(bits: Long): Double = java.lang.Double.longBitsToDouble(bits)

    /** uint32 bit pattern as an unsigned hex string, e.g. "0x3f800000". */
    fun hex32(bits: Int): String = "0x" + (bits.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    fun hex64(bits: Long): String = "0x" + bits.toULong().toString(16).padStart(16, '0')

    // 1L shl 64 is 1L on the JVM (shift counts wrap), so the 8-byte case can't use the mask.
    fun hex(bits: Long, byteWidth: Int): String =
        if (byteWidth >= 8) hex64(bits)
        else "0x" + (bits and ((1L shl (byteWidth * 8)) - 1)).toString(16).padStart(byteWidth * 2, '0')

    /** Parse a "0x..." (or bare hex) string into its raw bit pattern, width-masked. */
    fun parseHex(s: String, byteWidth: Int): Long {
        val clean = s.removePrefix("0x").removePrefix("0X")
        val v = java.lang.Long.parseUnsignedLong(clean, 16)
        return if (byteWidth >= 8) v else v and ((1L shl (byteWidth * 8)) - 1)
    }

    fun parseHex32(s: String): Int = parseHex(s, 4).toInt()

    fun parseHex64(s: String): Long = parseHex(s, 8)
}
