package nintype.engine.pathscore

import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.vectors.MemImage

/** std::string members of object images (the word texts), by image identity and offset. */
object Strs {
    private val store: MutableMap<MemImage, HashMap<Int, ByteArray>> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    fun get(img: MemImage, off: Int): ByteArray = store[img]?.get(off) ?: ByteArray(0)
    fun set(img: MemImage, off: Int, v: ByteArray) { store.getOrPut(img) { HashMap() }[off] = v }
}

/** `Emkey::WordAlternative` (0x50 bytes): +0/+4/+8/+0xc std::string (+4 = the word),
 * +0x10 u32, +0x14 / +0x18 TrieWord id / ender TrieWord id (-1 = none), +0x1c covered important
 * points, +0x20 score / norm, +0x24 norm, +0x28 f32, +0x2c score, +0x30 flags (bit 2 pending word,
 * 3 caps, 4 PathSearch+0x330, 6 wild guess, 0xc/0xd PathSearch +0x359/+0x358, 0xe +0x35b,
 * 0x16 the word is "'s"), +0x34/+0x38 Flag32, +0x3c i8 penalty reason, +0x3d i8 penalty
 * priority, +0x3e u8, +0x40 language-flowed mult, +0x44 word prior, +0x48 Refcounter<PredPath>
 * (debug copy), +0x4c std::string (debug path text). */
object WordAlternative {
    /** WordAlternative() @0083ff14 (field init; its one-time Flector registration is reflection). */
    fun init(wa: MemImage) {
        for (o in intArrayOf(0, 4, 8, 0xc, 0x4c)) Strs.set(wa, o, ByteArray(0))
        wa.setI32(0x34, 0); wa.setI32(0x38, 0)
        wa.refs[0x48] = null
        wa.setI32(0x14, -1); wa.setI32(0x18, -1)
        wa.setI32(0x10, 0); wa.setI32(0x20, 0)
        wa.setF32(0x24, 1f); wa.setF32(0x28, 1f)
        wa.setI32(0x1c, 0); wa.setI32(0x2c, 0)
        wa.setU8(0x3e, 0)
        wa.setF32(0x40, 1f); wa.setF32(0x44, 1f)
        wa.setI32(0x30, wa.i32(0x30) and 0xf8000000.toInt())
        wa.setU8(0x3d, 0xf6); wa.setU8(0x3c, 0x06)
    }

    /** setTrieWord(TWRet) @008462d4 / setEnderTrieWord(TWRet) @00846378: the id, -1 for a null TWRet. */
    fun setTrieWord(wa: MemImage, id: Int, ptr: Int) = wa.setI32(0x14, if (ptr == 0) -1 else id)
    fun setEnderTrieWord(wa: MemImage, id: Int, ptr: Int) = wa.setI32(0x18, if (ptr == 0) -1 else id)

    /** addPenaltyReason(int reason, int prio) @00845dd8: sets the reason when prio beats +0x3d
     * (the priority byte itself is never updated). */
    fun addPenaltyReason(wa: MemImage, reason: Int, prio: Int) { if (wa.i8(0x3d) < prio) wa.setU8(0x3c, reason and 0xff) }

    /** canWarnOnAltChoice() const @00845930: reason in {0, 1, 2, 5}. */
    fun canWarnOnAltChoice(wa: MemImage): Boolean = wa.u8(0x3c).let { it <= 5 && (0x27 ushr it) and 1 != 0 }
}

/** `Emkey::StepScoreStats` members ported here (the object model is search/StepScoreStats.kt). */
object StepStats {
    /** recSkipBranches(int n) @007f071c: +0x20 += n. */
    fun recSkipBranches(s: MemImage, n: Int) = s.setI32(0x20, s.i32(0x20) + n)
}

object PredPathWA {
    private fun lt(x: Float, t: Float) = !(fcmpge(x, t) || fcmpun(x, t))

    /** PredPath::imbueWAWithPenaltyReasons(WordAlternative&) @008221d0. */
    fun imbueWAWithPenaltyReasons(pp: MemImage, wa: MemImage) {
        if (pp.i8(0x34) >= 1) WordAlternative.addPenaltyReason(wa, 0, 3)
        if (pp.u8(0x2f) and 0x10 != 0) WordAlternative.addPenaltyReason(wa, 1, 5)
        if (pp.u8(0x2c) != 0) WordAlternative.addPenaltyReason(wa, 2, 6)
        if (pp.i32(0x140) >= 3) WordAlternative.addPenaltyReason(wa, 3, 5)
        if (Vecs.size(pp, PredPath.OFF_STEPS) <= 5 && lt(wa.f32(0x40), Float.fromBits(0x3f19999a))) WordAlternative.addPenaltyReason(wa, 4, 4)
        val bt = pp.f32(0xb0)
        if (lt(bt, Float.fromBits(0x3f59999a))) WordAlternative.addPenaltyReason(wa, 5, 4)
        if (lt(bt, Float.fromBits(0x3f59999a))) WordAlternative.addPenaltyReason(wa, 5, 4)   // (twice in the binary)
        val ps = pp.ref(PredPath.OFF_PS)!!
        wa.setI32(0x30, (wa.i32(0x30) and 0x10.inv()) or (ps.u8(0x330) shl 4))
    }

    /** PredPath::properlyCountCoveredImportantPoints() @00821f34: visited bits among the first
     * min(#visitation points, 64) indices. */
    fun properlyCountCoveredImportantPoints(pp: MemImage): Int {
        val n = Vecs.size(pp.ref(PredPath.OFF_PS)!!, 0x1bc)
        if (n < 1) return 0
        val last = if (n * 4 < 0x100) n - 1 else 63
        var c = 0
        for (i in 0..last) if (Flag64.flagged(pp, 0xf0, i)) c++
        return c
    }

    /** PredPath::obtainNeoVisihist(int) @0081f7fc: only reports an assert in this build. */
    fun obtainNeoVisihist(@Suppress("UNUSED_PARAMETER") pp: MemImage, @Suppress("UNUSED_PARAMETER") k: Int) {}
}
