package nintype.engine.pathscore

import nintype.engine.Bits

/**
 * msun `cosf` (s_cosf.c with k_cosf.c, k_sinf.c and e_rem_pio2f.c inlined), line for line,
 * as served to the binary's `cosf` import by re/msun. Plain double arithmetic: msun runs on
 * the FPU, not libgcc. The huge-argument reduction (|x| >= 2^28 * pi/2, __kernel_rem_pio2)
 * is not ported: the decoder only takes cos of angles in [0, pi] (and NaN).
 */
object LibMTrig {
    // exact bit patterns of the msun constants (hex float literals in the C source)
    private val kC0 = Double.fromBits(0xbfdffffffd0c5e81uL.toLong())
    private val kC1 = Double.fromBits(0x3fa55553e1053a42L)
    private val kC2 = Double.fromBits(0xbf56c087e80f1e27uL.toLong())
    private val kC3 = Double.fromBits(0x3ef99342e0ee5069L)
    private val kS1 = Double.fromBits(0xbfc5555554cbac77uL.toLong())
    private val kS2 = Double.fromBits(0x3f811110896efbb2L)
    private val kS3 = Double.fromBits(0xbf2a00f9e2cae774uL.toLong())
    private val kS4 = Double.fromBits(0x3ec6cd878c3b46a7L)

    private val c1pio2 = Double.fromBits(0x3FF921FB54442D18L)
    private val c2pio2 = Double.fromBits(0x400921FB54442D18L)
    private val c3pio2 = Double.fromBits(0x4012D97C7F3321D2L)
    private val c4pio2 = Double.fromBits(0x401921FB54442D18L)
    private val invpio2 = Double.fromBits(0x3FE45F306DC9C883L)
    private val pio2_1 = Double.fromBits(0x3FF921FB50000000L)
    private val pio2_1t = Double.fromBits(0x3E5110b4611A6263L)
    private val RNINT = Double.fromBits(0x4338000000000000L) // 0x1.8p52

    private fun kcos(x: Double): Float {
        val z = x * x
        val w = z * z
        val r = kC2 + z * kC3
        return (((1.0 + z * kC0) + w * kC1) + (w * z) * r).toFloat()
    }

    private fun ksin(x: Double): Float {
        val z = x * x
        val w = z * z
        val r = kS3 + z * kS4
        val s = z * x
        return ((x + s * (kS1 + z * kS2)) + s * w * r).toFloat()
    }

    fun cosf(x: Float): Float {
        val hx = Bits.floatToBits(x)
        val ix = hx and 0x7fffffff
        if (ix <= 0x3f490fda) {
            if (ix < 0x39800000) if (x.toInt() == 0) return 1f
            return kcos(x.toDouble())
        }
        if (ix <= 0x407b53d1) {
            if (ix > 0x4016cbe3) return -kcos(x.toDouble() + (if (hx > 0) -c2pio2 else c2pio2))
            return if (hx > 0) ksin(c1pio2 - x.toDouble()) else ksin(x.toDouble() + c1pio2)
        }
        if (ix <= 0x40e231d5) {
            if (ix > 0x40afeddf) return kcos(x.toDouble() + (if (hx > 0) -c4pio2 else c4pio2))
            return if (hx > 0) ksin(x.toDouble() - c3pio2) else ksin(-c3pio2 - x.toDouble())
        }
        if (ix >= 0x7f800000) return x - x
        if (ix >= 0x4dc90fdb) throw UnsupportedOperationException("cosf: huge argument reduction not ported ($x)")
        val fn = (x.toDouble() * invpio2 + RNINT) - RNINT
        val n = fn.toInt()
        val y = (x.toDouble() - fn * pio2_1) - fn * pio2_1t
        return when (n and 3) {
            0 -> kcos(y)
            1 -> ksin(-y)
            2 -> -kcos(y)
            else -> ksin(y)
        }
    }

}
