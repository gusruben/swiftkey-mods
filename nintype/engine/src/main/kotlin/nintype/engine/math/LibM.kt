package nintype.engine.math

import nintype.engine.Bits

/**
 * float32 libm routines the decoder imports, ported line for line from FreeBSD msun
 * (bionic's libm; vendored in re/msun/src, which is what re/emu.py serves the imports
 * from). Every expression keeps msun's evaluation order and float32 width (JVM float ops
 * are IEEE binary32 round-to-nearest, like msun built with FLT_EVAL_METHOD 0).
 *
 * Caveat inherited from the harness (re/msun/SOURCES.txt): Android 10+ swapped a few float
 * functions (powf among them) for ARM optimized-routines; this models classic msun.
 */
object LibM {
    private fun word(f: Float): Int = Bits.floatToBits(f)
    private fun fromWord(i: Int): Float = Bits.bitsToFloat(i)

    /** e_sqrtf.c: correctly rounded. sqrt in double then one rounding to float is exact
     * for binary32 (53 >= 2*24 + 2), including -0, negatives (NaN) and subnormals. */
    fun sqrtf(x: Float): Float = Math.sqrt(x.toDouble()).toFloat()

    fun fabsf(x: Float): Float = fromWord(word(x) and 0x7fffffff)

    /** s_scalbnf.c: x * 2^n with a single rounding (Math.scalb does the same for float). */
    fun scalbnf(x: Float, n: Int): Float = Math.scalb(x, n)

    // --- e_acosf.c ---------------------------------------------------------------------

    private val PI_F = fromWord(0x40490fda)
    private val PIO2_HI = fromWord(0x3fc90fda)
    private val PIO2_LO = fromWord(0x33a22168)
    private val pS0 = fromWord(0x3e2aaaab)
    private val pS1 = fromWord(0xbdf4c1d1.toInt())
    private val pS2 = fromWord(0x3bb33de9)
    private val qS1 = fromWord(0xbf956240.toInt())
    private val qS2 = fromWord(0x3e9489f9)

    fun acosf(x: Float): Float {
        val hx = word(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x3f800000) {
            if (ix == 0x3f800000) {
                return if (hx > 0) 0.0f else PI_F + 2.0f * PIO2_LO
            }
            return (x - x) / (x - x)
        }
        if (ix < 0x3f000000) {
            if (ix <= 0x32800000) return PIO2_HI + PIO2_LO
            val z = x * x
            val p = z * (pS0 + z * (pS1 + z * pS2))
            val q = 1.0f + z * (qS1 + z * qS2)
            val r = p / q
            return PIO2_HI - (x - (PIO2_LO - x * r))
        } else if (hx < 0) {
            val z = (1.0f + x) * 0.5f
            val p = z * (pS0 + z * (pS1 + z * pS2))
            val q = 1.0f + z * (qS1 + z * qS2)
            val s = sqrtf(z)
            val r = p / q
            val w = r * s - PIO2_LO
            return PI_F - 2.0f * (s + w)
        } else {
            val z = (1.0f - x) * 0.5f
            val s = sqrtf(z)
            val df = fromWord(word(s) and 0xfffff000.toInt())
            val c = (z - df * df) / (s + df)
            val p = z * (pS0 + z * (pS1 + z * pS2))
            val q = 1.0f + z * (qS1 + z * qS2)
            val r = p / q
            val w = r * s + c
            return 2.0f * (df + w)
        }
    }

    // --- s_atanf.c ---------------------------------------------------------------------

    private val atanhi = floatArrayOf(fromWord(0x3eed6338), fromWord(0x3f490fda), fromWord(0x3f7b985e), fromWord(0x3fc90fda))
    private val atanlo = floatArrayOf(fromWord(0x31ac3769), fromWord(0x33222168), fromWord(0x33140fb4), fromWord(0x33a22168))
    // msun writes these as double literals assigned to float: decimal -> double -> float, mirrored here
    private val aT = floatArrayOf(3.3333328366e-01.toFloat(), (-1.9999158382e-01).toFloat(), 1.4253635705e-01.toFloat(), (-1.0648017377e-01).toFloat(), 6.1687607318e-02.toFloat())

    fun atanf(xIn: Float): Float {
        var x = xIn
        val hx = word(x)
        val ix = hx and 0x7fffffff
        val id: Int
        if (ix >= 0x4c800000) {
            if (ix > 0x7f800000) return x + x
            return if (hx > 0) atanhi[3] + atanlo[3] else -atanhi[3] - atanlo[3]
        }
        if (ix < 0x3ee00000) {
            if (ix < 0x39800000) {
                if (1.0e30.toFloat() + x > 1.0f) return x
            }
            id = -1
        } else {
            x = fabsf(x)
            if (ix < 0x3f980000) {
                if (ix < 0x3f300000) {
                    id = 0; x = (2.0f * x - 1.0f) / (2.0f + x)
                } else {
                    id = 1; x = (x - 1.0f) / (x + 1.0f)
                }
            } else {
                if (ix < 0x401c0000) {
                    id = 2; x = (x - 1.5f) / (1.0f + 1.5f * x)
                } else {
                    id = 3; x = -1.0f / x
                }
            }
        }
        var z = x * x
        val w = z * z
        val s1 = z * (aT[0] + w * (aT[2] + w * aT[4]))
        val s2 = w * (aT[1] + w * aT[3])
        if (id < 0) return x - x * (s1 + s2)
        z = atanhi[id] - ((x * (s1 + s2) - atanlo[id]) - x)
        return if (hx < 0) -z else z
    }

    // --- e_powf.c ----------------------------------------------------------------------

    private val bp = floatArrayOf(1.0f, 1.5f)
    private val dp_h = floatArrayOf(0.0f, fromWord(0x3f15c000))
    private val dp_l = floatArrayOf(0.0f, fromWord(0x35d1cfdc))
    private const val two24 = 16777216.0f
    private val huge = 1.0e30.toFloat()
    private val tiny = 1.0e-30.toFloat()
    private val thrd = fromWord(0x3eaaaaab)
    private val L1 = fromWord(0x3f19999a)
    private val L2 = fromWord(0x3edb6db7)
    private val L3 = fromWord(0x3eaaaaab)
    private val L4 = fromWord(0x3e8ba305)
    private val L5 = fromWord(0x3e6c3255)
    private val L6 = fromWord(0x3e53f142)
    private val P1 = fromWord(0x3e2aaaab)
    private val P2 = fromWord(0xbb360b61.toInt())
    private val P3 = fromWord(0x388ab355)
    private val P4 = fromWord(0xb5ddea0e.toInt())
    private val P5 = fromWord(0x3331bb4c)
    private val lg2 = fromWord(0x3f317218)
    private val lg2_h = fromWord(0x3f317200)
    private val lg2_l = fromWord(0x35bfbe8c)
    private val ovt = 4.2995665694e-08.toFloat()
    private val cp = fromWord(0x3f76384f)
    private val cp_h = fromWord(0x3f764000)
    private val cp_l = fromWord(0xb8f623c6.toInt())
    private val ivln2 = fromWord(0x3fb8aa3b)
    private val ivln2_h = fromWord(0x3fb8aa00)
    private val ivln2_l = fromWord(0x36eca570)

    private fun mask(f: Float, m: Int): Float = fromWord(word(f) and m)

    fun powf(x: Float, y: Float): Float {
        val hx = word(x)
        val hy = word(y)
        var ix = hx and 0x7fffffff
        val iy = hy and 0x7fffffff
        if (iy == 0) return 1.0f
        if (hx == 0x3f800000) return 1.0f
        if (ix > 0x7f800000 || iy > 0x7f800000) return x + y // nan_mix
        var yisint = 0
        if (hx < 0) {
            if (iy >= 0x4b800000) yisint = 2
            else if (iy >= 0x3f800000) {
                val k = (iy shr 23) - 0x7f
                val j = iy shr (23 - k)
                if ((j shl (23 - k)) == iy) yisint = 2 - (j and 1)
            }
        }
        if (iy == 0x7f800000) {
            return if (ix == 0x3f800000) 1.0f
            else if (ix > 0x3f800000) (if (hy >= 0) y else 0.0f)
            else (if (hy < 0) -y else 0.0f)
        }
        if (iy == 0x3f800000) return if (hy < 0) 1.0f / x else x
        if (hy == 0x40000000) return x * x
        if (hy == 0x3f000000) {
            if (hx >= 0) return sqrtf(x)
        }
        var ax = fabsf(x)
        if (ix == 0x7f800000 || ix == 0 || ix == 0x3f800000) {
            var z = ax
            if (hy < 0) z = 1.0f / z
            if (hx < 0) {
                if (((ix - 0x3f800000) or yisint) == 0) z = (z - z) / (z - z)
                else if (yisint == 1) z = -z
            }
            return z
        }
        var n = (hx ushr 31) - 1
        if ((n or yisint) == 0) return (x - x) / (x - x)
        var sn = 1.0f
        if ((n or (yisint - 1)) == 0) sn = -1.0f
        val t1: Float
        val t2: Float
        if (iy > 0x4d000000) {
            if (ix < 0x3f7ffff6) return if (hy < 0) sn * huge * huge else sn * tiny * tiny
            if (ix > 0x3f800007) return if (hy > 0) sn * huge * huge else sn * tiny * tiny
            val t = ax - 1
            val w = (t * t) * (0.5f - t * (thrd - t * 0.25f))
            val u = ivln2_h * t
            val v = t * ivln2_l - w * ivln2
            t1 = mask(u + v, 0xfffff000.toInt())
            t2 = v - (t1 - u)
        } else {
            n = 0
            if (ix < 0x00800000) { ax *= two24; n -= 24; ix = word(ax) }
            n += (ix shr 23) - 0x7f
            val j = ix and 0x007fffff
            ix = j or 0x3f800000
            val k: Int
            if (j <= 0x1cc471) k = 0
            else if (j < 0x5db3d7) k = 1
            else { k = 0; n += 1; ix -= 0x00800000 }
            ax = fromWord(ix)
            var u = ax - bp[k]
            var v = 1.0f / (ax + bp[k])
            val s = u * v
            val s_h = mask(s, 0xfffff000.toInt())
            var iss = ((ix shr 1) and 0xfffff000.toInt()) or 0x20000000
            var t_h = fromWord(iss + 0x00400000 + (k shl 21))
            var t_l = ax - (t_h - bp[k])
            val s_l = v * ((u - s_h * t_h) - s_h * t_l)
            var s2 = s * s
            var r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))))
            r += s_l * (s_h + s)
            s2 = s_h * s_h
            t_h = mask(3.0f + s2 + r, 0xfffff000.toInt())
            t_l = r - ((t_h - 3.0f) - s2)
            u = s_h * t_h
            v = s_l * t_h + t_l * s
            val p_h = mask(u + v, 0xfffff000.toInt())
            val p_l = v - (p_h - u)
            val z_h = cp_h * p_h
            val z_l = cp_l * p_h + p_l * cp + dp_l[k]
            val t = n.toFloat()
            t1 = mask((((z_h + z_l) + dp_h[k]) + t), 0xfffff000.toInt())
            t2 = z_l - (((t1 - t) - dp_h[k]) - z_h)
        }
        val y1 = mask(y, 0xfffff000.toInt())
        val p_l = (y - y1) * t1 + y * t2
        var p_h = y1 * t1
        var z = p_l + p_h
        var j = word(z)
        if (j > 0x43000000) return sn * huge * huge
        else if (j == 0x43000000) {
            if (p_l + ovt > z - p_h) return sn * huge * huge
        } else if ((j and 0x7fffffff) > 0x43160000) return sn * tiny * tiny
        else if (j == 0xc3160000.toInt()) {
            if (p_l <= z - p_h) return sn * tiny * tiny
        }
        val i = j and 0x7fffffff
        var k = (i shr 23) - 0x7f
        n = 0
        if (i > 0x3f000000) {
            n = j + (0x00800000 shr (k + 1))
            k = ((n and 0x7fffffff) shr 23) - 0x7f
            val t = fromWord(n and (0x007fffff shr k).inv())
            n = ((n and 0x007fffff) or 0x00800000) shr (23 - k)
            if (j < 0) n = -n
            p_h -= t
        }
        val t = mask(p_l + p_h, 0xffff8000.toInt())
        val u = t * lg2_h
        val v = (p_l - (t - p_h)) * lg2 + t * lg2_l
        z = u + v
        val w = v - (z - u)
        val tt = z * z
        val tt1 = z - tt * (P1 + tt * (P2 + tt * (P3 + tt * (P4 + tt * P5))))
        val r = (z * tt1) / (tt1 - 2.0f) - (w + z * w)
        z = 1.0f - (r - z)
        j = word(z)
        j += (n shl 23)
        z = if ((j shr 23) <= 0) scalbnf(z, n) else fromWord(j)
        return sn * z
    }
}
