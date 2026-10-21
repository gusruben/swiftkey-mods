package nintype.engine

/**
 * Bit-exact mirrors of the ARM soft-float libgcc/EABI routines
 * (`__aeabi_fadd`/`fsub`/`fmul`/`fdiv`, float<->double/int conversions,
 * comparisons) the original binary links against for every float op --
 * see re/emu.py's IMPORTS table for the real symbol list once `re/` is
 * generating vectors. Mirrors quickhook/remake/src/sim/f32.ts's approach
 * and NaN-handling notes (that port's one TS-specific caveat --
 * `Math.fround` canonicalizing NaN payloads -- doesn't apply to Kotlin,
 * see the NaN section below).
 *
 * ## Why direct JVM float ops are already bit-exact here
 *
 * The JVM's `Float`/`Double` arithmetic (`+ - * /`) is IEEE-754
 * round-to-nearest-even, the same rounding libgcc's soft-float routines
 * implement (soft-float exists because the target CPU lacks an FPU, not
 * because it uses different math -- it's a software implementation of the
 * *same* IEEE-754 binary32 rules hardware FPUs use). So `Float` `+`/`-`/`*`/`/`
 * on the JVM already does what `__aeabi_fadd`/etc. do, bit for bit, for
 * every normal, zero, infinity and NaN input -- no extra rounding step
 * needed, unlike the TS port (which routes every op through
 * `Math.fround` because JS numbers are always double-width).
 *
 * **Exception (2026-10-20):** this binary's `__aeabi_fadd`/`fsub`/`dadd`/`dsub` are an old
 * libgcc with a subnormal-normalisation bug, mirrored by [libgccTinyAdd]. Multiplication,
 * division and conversions were checked equal to IEEE (subnormal-heavy differential test).
 *
 * ## Edge cases handled explicitly below
 *
 * - **Denormals (subnormals):** JVM float/double arithmetic supports
 *   subnormals per IEEE-754 by default (no `-ffast-math`-style flush-to-zero
 *   on the JVM), matching libgcc. No special handling needed; this comment
 *   exists so a future reader doesn't go looking for a missing case.
 * - **NaN payload:** unlike JS (which canonicalizes every NaN produced by
 *   its float ops to one bit pattern), the JVM's `Float`/`Double` scalar
 *   arithmetic operators are specified (JLS 15.18.2 et al.) to produce a
 *   single canonical NaN (`0x7fc00000` for float) for any NaN-producing op,
 *   same as ARM soft-float's convention for new NaNs -- but a NaN *passed
 *   through* unchanged (e.g. returned as-is, or propagated by `min`/`max`-
 *   style selection rather than arithmetic) keeps its original payload bits
 *   on both platforms. Because payloads can still legitimately differ bit-
 *   for-bit between two different-but-both-valid NaN-producing code paths,
 *   every comparison in this codebase treats NaN-vs-NaN as equal regardless
 *   of payload (see [f32BitsEqual]/[f64BitsEqual]) -- the same stance
 *   quickhook's f32.ts takes, and the only sound definition of "bit-exact"
 *   achievable without emulating payload propagation instruction-by-instruction.
 * - **-0.0:** preserved correctly by JVM arithmetic and by [Bits], and by
 *   the sign-aware comparisons below (`-0.0 == 0.0` per IEEE-754, which is
 *   also what `fcmpeq` must report).
 * - **f2iz / f2uiz out-of-range and NaN:** these are *not* plain C-style
 *   casts (which are undefined behavior out of range) -- EABI defines them
 *   as truncate-toward-zero, then saturate into the destination range, with
 *   NaN mapping to 0. See [f2iz]/[f2uiz].
 * - **Double rounding:** when the original C++ computes an expression in
 *   `double` and only truncates to `float` at the end (or vice versa: a
 *   `float` expression promoted to `double` for one op then narrowed back),
 *   the *order and width* of each intermediate matters -- rounding twice
 *   (double you then round to float) is not always equal to rounding once
 *   directly to float. **Every port function must replicate the original's
 *   exact intermediate widths**, not just its final result type: use
 *   [Float] all the way through for an all-float expression, and
 *   deliberately widen to [Double] with [f2d] only where the disassembly
 *   shows the original doing so (a `vcvt.f64.f32` / double-precision
 *   soft-float call), narrowing back with [d2f] only where the original
 *   does. Never "simplify" by computing in Double throughout for
 *   convenience -- that silently changes rounding and will fail the
 *   golden-vector diff.
 */
object SoftFloat {

    // --- __aeabi_f{add,sub,mul,div} -----------------------------------

    /** __aeabi_fadd as linked into libgl2jni.so (libgcc ieee754-sf.S, pre-fix version). IEEE
     * except one quirk, see [libgccTinyAdd]. */
    fun fadd(a: Float, b: Float): Float {
        val ab = Bits.floatToBits(a)
        val bb = Bits.floatToBits(b)
        if ((ab and 0x7f000000) == 0 && (bb and 0x7f000000) == 0) { // both exponent fields <= 1
            return Bits.bitsToFloat(libgccTinyAdd(ab.toLong(), bb.toLong(), Bits.floatToBits(a + b).toLong(), 32).toInt())
        }
        return a + b
    }

    /** __aeabi_fsub = __aeabi_fadd(a, b with its sign bit flipped) in this binary. */
    fun fsub(a: Float, b: Float): Float = fadd(a, Bits.bitsToFloat(Bits.floatToBits(b) xor Int.MIN_VALUE))
    fun fmul(a: Float, b: Float): Float = a * b
    fun fdiv(a: Float, b: Float): Float = a / b

    // --- __aeabi_d{add,sub,mul,div} (same reasoning, double-width) ----

    fun dadd(a: Double, b: Double): Double {
        val ab = Bits.doubleToBits(a)
        val bb = Bits.doubleToBits(b)
        if ((ab and 0x7fe0000000000000L) == 0L && (bb and 0x7fe0000000000000L) == 0L) {
            return Bits.bitsToDouble(libgccTinyAdd(ab, bb, Bits.doubleToBits(a + b), 64))
        }
        return a + b
    }

    fun dsub(a: Double, b: Double): Double = dadd(a, Bits.bitsToDouble(Bits.doubleToBits(b) xor Long.MIN_VALUE))

    /**
     * The one place this binary's soft-float add is not IEEE (found 2026-10-20 by differential
     * testing the emulated routines against numpy; re/vectors/gen_libm.py keeps vectors for it).
     * libgcc's old `__addsf3`/`__adddf3` normalise a cancelled result with
     * `movs r1, r1, lsl #1; adc r0, r0, r0; tst r0, #0x00800000; sub r2, r2, #1; bne Lad_e`,
     * which packs with exponent -1 when both operands have exponent field <= 1 (subnormal or
     * the smallest normal binade). Net effect, exactly: if neither operand is zero, |a| != |b|,
     * and the exact sum's magnitude m (in raw bits) lies in [2^(p-1), 2^p) (p = 23 or 52, i.e.
     * the top subnormal binade), the routine returns sign | (2m - 2^p) instead of m. Every
     * other case equals the IEEE result [ieee]. Newer libgcc (`subs r2,r2,#1; cmphs ...`) fixed
     * this; the device runs this binary's statically linked copy, so the quirk is real there.
     */
    fun libgccTinyAdd(a: Long, b: Long, ieee: Long, width: Int): Long {
        val p = if (width == 32) 23 else 52
        val sign = 1L shl (width - 1)
        val mag = sign - 1
        val am = a and mag
        val bm = b and mag
        if (am == 0L || bm == 0L || am == bm) return ieee
        val m = ieee and mag
        if (m >= (1L shl (p - 1)) && m < (1L shl p)) return (ieee and sign) or (2 * m - (1L shl p))
        return ieee
    }
    fun dmul(a: Double, b: Double): Double = a * b
    fun ddiv(a: Double, b: Double): Double = a / b

    // --- __aeabi_i2f / __aeabi_ui2f / __aeabi_l2f / __aeabi_ul2f ------

    /** __aeabi_i2f: int32 -> float32, round-to-nearest-even (the only
     * rounding IEEE-754 defines for this conversion). */
    fun i2f(i: Int): Float = i.toFloat()

    /** __aeabi_ui2f: uint32 (passed as Int bit pattern) -> float32. */
    fun ui2f(u: Int): Float = (u.toLong() and 0xFFFFFFFFL).toFloat()

    fun i2d(i: Int): Double = i.toDouble()
    fun ui2d(u: Int): Double = (u.toLong() and 0xFFFFFFFFL).toDouble()

    // --- __aeabi_f2iz / __aeabi_f2uiz / __aeabi_d2iz / __aeabi_d2uiz --
    //
    // EABI saturating conversions: truncate toward zero, then clamp into
    // the destination range; NaN -> 0. NOT a raw `(int)x` C cast (which is
    // UB out of range) and NOT Kotlin's `Float.toInt()` by accident of
    // naming -- Kotlin's `toInt()` on Float/Double already saturates and
    // maps NaN to 0 per Kotlin's spec, which happens to match EABI here,
    // but we spell it out explicitly below rather than lean on that
    // coincidence, so the semantics are documented at the call site.

    fun f2iz(v: Float): Int = when {
        v.isNaN() -> 0
        v >= Int.MAX_VALUE.toFloat() -> Int.MAX_VALUE
        v <= Int.MIN_VALUE.toFloat() -> Int.MIN_VALUE
        else -> v.toInt() // truncates toward zero for finite in-range values
    }

    fun f2uiz(v: Float): Int = when {
        v.isNaN() -> 0
        v <= 0f -> 0
        v >= 4294967295f -> -1 // 0xFFFFFFFF as a bit pattern
        else -> {
            val l = v.toLong() // truncates toward zero
            l.toInt()
        }
    }

    fun d2iz(v: Double): Int = when {
        v.isNaN() -> 0
        v >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        v <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
        else -> v.toInt()
    }

    fun d2uiz(v: Double): Int = when {
        v.isNaN() -> 0
        v <= 0.0 -> 0
        v >= 4294967295.0 -> -1
        else -> v.toLong().toInt()
    }

    // --- __aeabi_f2d / __aeabi_d2f (widen is always exact; narrow rounds) --

    /** float32 -> float64: exact, every float32 value is representable in float64. */
    fun f2d(v: Float): Double = v.toDouble()

    /** float64 -> float32: rounds to nearest-even, same as the hardware/libgcc op. */
    fun d2f(v: Double): Float = v.toFloat()

    // --- __aeabi_fcmp* / __aeabi_dcmp* ----------------------------------
    //
    // IEEE-754 comparisons: any ordered comparison involving a NaN is
    // false; `fcmpun`/`dcmpun` ("unordered") is the only one that's true
    // exactly when a NaN is involved. Kotlin's `Float`/`Double` `< <= > >=`
    // operators already implement IEEE-754 ordering (false whenever either
    // operand is NaN) and `==` already treats -0.0 == 0.0, so these are
    // thin, intentionally-explicit wrappers -- keep using them at call
    // sites instead of raw operators so a port function reads like the
    // disassembly's __aeabi_fcmp* calls.

    fun fcmpeq(a: Float, b: Float): Boolean = a == b
    fun fcmplt(a: Float, b: Float): Boolean = a < b
    fun fcmple(a: Float, b: Float): Boolean = a <= b
    fun fcmpgt(a: Float, b: Float): Boolean = a > b
    fun fcmpge(a: Float, b: Float): Boolean = a >= b
    fun fcmpun(a: Float, b: Float): Boolean = a.isNaN() || b.isNaN()

    fun dcmpeq(a: Double, b: Double): Boolean = a == b
    fun dcmplt(a: Double, b: Double): Boolean = a < b
    fun dcmple(a: Double, b: Double): Boolean = a <= b
    fun dcmpgt(a: Double, b: Double): Boolean = a > b
    fun dcmpge(a: Double, b: Double): Boolean = a >= b
    fun dcmpun(a: Double, b: Double): Boolean = a.isNaN() || b.isNaN()

    // --- diff-testing helpers: NaN-payload-tolerant bit equality --------

    /** Bit-exact equality for two float32 bit patterns, treating any NaN
     * as equal to any other NaN. See the class doc's NaN-payload note. */
    fun f32BitsEqual(aBits: Int, bBits: Int): Boolean {
        if (aBits == bBits) return true
        return Bits.bitsToFloat(aBits).isNaN() && Bits.bitsToFloat(bBits).isNaN()
    }

    /** Same as [f32BitsEqual], for float64. */
    fun f64BitsEqual(aBits: Long, bBits: Long): Boolean {
        if (aBits == bBits) return true
        return Bits.bitsToDouble(aBits).isNaN() && Bits.bitsToDouble(bBits).isNaN()
    }
}
