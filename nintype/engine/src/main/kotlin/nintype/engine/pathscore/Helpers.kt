package nintype.engine.pathscore

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.vectors.MemImage

/**
 * Small free helpers used by the pathscore code, each over an embedded field of some object
 * image (`img` + byte offset `o` of the helper object inside it).
 */

/** `WeightedFloats` (8 bytes): +0 f32 sumW, +4 f32 sumWV. Ghidra 01413798.. */
object WeightedFloats {
    private val MIN_W = Float.fromBits(0x3a83126f) // 0.001f

    /** WeightedFloats() @01413798. */
    fun init(img: MemImage, o: Int) { img.setI32(o, 0); img.setI32(o + 4, 0) }

    /** add(float v, float w) @014137a8: sumW += w; sumWV = v*w + sumWV. */
    fun add(img: MemImage, o: Int, v: Float, w: Float) {
        img.setF32(o, fadd(img.f32(o), w))
        img.setF32(o + 4, fadd(fmul(v, w), img.f32(o + 4)))
    }

    /** mean() @014137e8: sumW < 0.001 -> 1, else sumWV / sumW. */
    fun mean(img: MemImage, o: Int): Float {
        val w = img.f32(o)
        return if (fcmplt(w, MIN_W)) 1f else fdiv(img.f32(o + 4), w)
    }

    /** mean(float a, float b) @01413828: (a*b + sumWV) / (sumW + a), or 1 when that
     * weight < 0.001. Note the argument order differs from add(): here the weight comes first. */
    fun mean(img: MemImage, o: Int, a: Float, b: Float): Float {
        val sw = fadd(img.f32(o), a)
        return if (fcmplt(sw, MIN_W)) 1f else fdiv(fadd(fmul(a, b), img.f32(o + 4)), sw)
    }
}

/** `Shing::Flag64` (8 bytes, little-endian u64 bitset). Ghidra 010e45b8.. */
object Flag64 {
    /** ARM register shift: the amount is the low byte of the register; >= 32 gives 0. */
    private fun lsl(v: Int, n: Int): Int { val s = n and 0xff; return if (s >= 32) 0 else v shl s }
    private fun lsr(v: Int, n: Int): Int { val s = n and 0xff; return if (s >= 32) 0 else v ushr s }

    /** (low word mask, high word mask) the compiled code builds for bit `i`. */
    private fun masks(i: Int): Pair<Int, Int> {
        var hi = lsr(1, 32 - i)
        if (i - 32 >= 0) hi = lsl(1, i - 32)
        return lsl(1, i) to hi
    }

    fun init(img: MemImage, o: Int) { img.setI32(o, 0); img.setI32(o + 4, 0) }

    /** flagged(int) const @010e45dc (and isFlagged @010e4790, operator>> which jumps to it).
     * i >= 64 only asserts (log); the bit test runs anyway. */
    fun flagged(img: MemImage, o: Int, i: Int): Boolean {
        val (lo, hi) = masks(i)
        return (img.i32(o) and lo) or (img.i32(o + 4) and hi) != 0
    }

    /** flag(int) @010e4974: set bit i. */
    fun flag(img: MemImage, o: Int, i: Int) {
        val (lo, hi) = masks(i)
        val a = img.i32(o); val b = img.i32(o + 4)
        img.setI32(o, a or lo); img.setI32(o + 4, b or hi)
    }

    /** flag(int, bool) @010e49a4: set or clear bit i. */
    fun flag(img: MemImage, o: Int, i: Int, on: Boolean) {
        val (lo, hi) = masks(i)
        val a = img.i32(o); val b = img.i32(o + 4)
        if (on) { img.setI32(o, a or lo); img.setI32(o + 4, b or hi) }
        else { img.setI32(o, a and lo.inv()); img.setI32(o + 4, b and hi.inv()) }
    }
}

/** `Emkey::FunnelStat` (12 bytes): +0 i32 total, +4 i32 succ, +8 i32 fail1. */
object FunnelStat {
    /** operator++(int) @007e70f4 / operator++() @007f04f0: total += 1. */
    fun inc(img: MemImage, o: Int) = img.setI32(o, img.i32(o) + 1)

    /** succ() @007e923c / fail1() @007ea09c: clamp the counter at +4 / +8 to >= 0 and
     * return its offset (the C++ returns int&). */
    fun succ(img: MemImage, o: Int): Int = clampRef(img, o + 4)
    fun fail1(img: MemImage, o: Int): Int = clampRef(img, o + 8)

    private fun clampRef(img: MemImage, off: Int): Int {
        if (img.i32(off) < 0) img.setI32(off, 0)
        return off
    }
}
