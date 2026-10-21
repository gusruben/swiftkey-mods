package nintype.engine.pathscore

import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.LibM
import nintype.engine.vectors.MemImage

/**
 * The PredPath visitation members (`Emkey::PredPath::markVisited`, `updateVisitation`,
 * `updateVisitationComplete`, `syncSmoothVisitation`). Kept apart from [PredPath] only for
 * file size. Visitation points are PathSearch+0x1bc (`std::vector<MooTouchPoint*>`, indexed by
 * the point's visitation index +0x308); the path's visited set is the Flag64 at +0xf0.
 */
object Visitation {
    private const val VIS = 0xf0
    private val F0_003 = Float.fromBits(0x3b449ba6)
    private val F0_01 = Float.fromBits(0x3c23d70a)
    private val F0_001 = Float.fromBits(0x3a83126f)

    private fun absf(x: Float) = Float.fromBits(x.toRawBits() and 0x7fffffff)
    /** |x - 1| > eps, as compiled: !(<= eps || unordered). */
    private fun off1(x: Float, eps: Float): Boolean { val d = absf(fadd(x, -1f)); return !(fcmple(d, eps) || fcmpun(d, eps)) }

    fun visCount(ps: MemImage): Int = Vecs.size(ps, 0x1bc)
    fun visPoint(ps: MemImage, i: Int): MemImage = Vecs.elems(ps, 0x1bc)[i].ref(0)!!

    private fun visited(pp: MemImage, i: Int) = i <= 63 && Flag64.flagged(pp, VIS, i)

    /** markVisited(MooTouchPoint* tp, bool force, PredHistory* hist) @0082d29c. */
    fun markVisited(pp: MemImage, tp: MemImage, force: Boolean, hist: MemImage) {
        val idx = tp.i32(if (tp.u8(0x34d) != 0) 0x308 else 0x304)
        if (idx > 63) return
        if (idx < pp.i8(PredPath.fing(pp, tp.i8(0x21c)) + 0x28)) return
        val v = Flag64.flagged(pp, VIS, idx)
        if (force || !v) hist.setU16(0x1e, hist.u16(0x1e) or 0x10)
        val sb = visPoint(pp.ref(PredPath.OFF_PS)!!, idx)   // (sb.0x308 != idx only asserts)
        for ((link, relOff) in arrayOf(0x18 to 0x36c, 0x14 to 0x368)) {
            val nb = sb.ref(link) ?: continue
            val rel = sb.f32(relOff)
            if (!off1(rel, F0_003)) continue
            if (tp.u8(0x34d) == 0) continue
            val j = nb.i32(0x308)
            if (j > 63 || !Flag64.flagged(pp, VIS, j)) continue
            pp.setI32(0x8c, pp.i32(0x8c) + 2)
            pp.setF32(0x90, fadd(fadd(rel, rel), pp.f32(0x90)))
            pp.setI32(0x94, pp.i32(0x94) + 1)
        }
        pp.setI32(0x8c, pp.i32(0x8c) + 1)
        pp.setF32(0x90, fadd(pp.f32(0x90), 1f))
        Flag64.flag(pp, VIS, idx)
    }

    /** updateVisitation(double t, int finger, MooTouchPoint*, PredHistory*) @0082dbdc (the
     * pointer arguments are unused). Walks visitation points from the lower of the two fingers'
     * cursors (0x1a0 / 0x1e8); each unvisited point the path has passed multiplies the novisit
     * accumulator (0x98) by (1 - relief) + relief * point.novisitPen. */
    fun updateVisitation(pp: MemImage, t: Double, finger: Int) {
        var k = minOf(pp.i8(0x1a0), pp.i8(0x1e8))
        val lb = PredPath.getBackwardsVisitationLookback(pp, d2f(t))
        val ps = pp.ref(PredPath.OFF_PS)!!
        if (Integer.compareUnsigned(k, visCount(ps)) >= 0) return
        val tcut = dsub(t, f2d(lb))
        while (true) {
            val pt = visPoint(ps, k)
            if (finger != 0 && dcmpgt(pt.f64(0x148), t)) return
            val fu = pt.u8(0x21c)
            val ge = when (fu) { 2 -> k >= pp.i8(0x1e8); 1 -> k >= pp.i8(0x1a0); else -> true }
            val go = when {
                !ge -> false
                finger != 0 && pt.i8(0x21c) != finger -> !dcmpgt(pt.f64(0x138), tcut)
                else -> true
            }
            if (go) {
                if (!visited(pp, k)) {
                    pp.setI32(0x140, pp.i32(0x140) + 1)
                    var rel = 1f
                    pt.ref(0x18)?.let { prev ->
                        if (visited(pp, prev.i32(0x308))) {
                            rel = pt.f32(0x364)
                            if (pt.u8(0x225) and 8 != 0) rel = fmul(rel, pt.f32(0x35c))
                        }
                    }
                    pt.ref(0x14)?.let { next -> if (visited(pp, next.i32(0x308))) rel = fmul(rel, pt.f32(0x360)) }
                    pp.setF32(0x98, fmul(pp.f32(0x98), fadd(fsub(1f, rel), fmul(rel, pt.f32(0x354)))))
                }
                when (fu) { 2 -> pp.setU8(0x1e8, (k + 1) and 0xff); 1 -> pp.setU8(0x1a0, (k + 1) and 0xff) }
            }
            k++
            if (Integer.compareUnsigned(k, visCount(ps)) >= 0) return
        }
    }

    /** updateVisitationComplete() @0082e368: updateVisitation(PS.0x178 + 3.0, 0). */
    fun updateVisitationComplete(pp: MemImage) =
        updateVisitation(pp, dadd(pp.ref(PredPath.OFF_PS)!!.f64(0x178), 3.0), 0)

    /** syncSmoothVisitation(std::vector<float>* out) @008269cc: per visitation point the best
     * visit strength (point+0x370) of the steps on it, spread to neighbours as v*(1-relief);
     * 0xac = (mean over penalisable points of v + pen*(1-v))^4.2. Returns the per-point vector
     * (what the C++ copies into *out; detached points read -1). */
    fun syncSmoothVisitation(pp: MemImage): FloatArray {
        val ps = pp.ref(PredPath.OFF_PS)!!
        val n = visCount(ps)
        val v = FloatArray(n)
        fun raise(j: Int, x: Float) { if (fcmplt(v[j], x)) v[j] = x }
        for (st in Vecs.elems(pp, PredPath.OFF_STEPS)) {
            val p = st.ref(0x18)!!
            val idx = p.i32(if (p.u8(0x34d) != 0) 0x308 else 0x304)   // idx >= n: assert + out-of-range write (not modelled)
            val s = p.f32(0x370)
            raise(idx, s)
            val sl = visPoint(ps, idx)
            sl.ref(0x18)?.let { prv ->
                val rel = sl.f32(0x364)
                if (off1(rel, F0_01)) { val j = prv.i32(0x308); if (j < n) raise(j, fmul(s, fsub(1f, rel))) }
            }
            sl.ref(0x14)?.let { nxt ->
                val j = nxt.i32(0x308)
                if (j < n) {
                    val rel = sl.f32(0x360)
                    if (off1(rel, F0_01)) raise(j, fmul(s, fsub(1f, rel)))
                    if (nxt.u8(0x225) and 8 != 0) raise(j, fmul(s, fsub(1f, nxt.f32(0x35c))))
                }
            }
        }
        var mean = 1f
        if (n != 0) {
            var sum = 0f
            var cnt = 0
            for (i in 0 until n) {
                val sl = visPoint(ps, i)
                if (sl.u8(0x225) and 4 != 0) v[i] = -1f
                val pen = sl.f32(0x354)
                if (off1(pen, F0_001) && sl.u8(0x225) and 4 == 0) {
                    val vi = v[i]
                    sum = fadd(sum, fadd(vi, fmul(pen, fsub(1f, vi))))
                    cnt++
                }
            }
            if (cnt != 0) mean = fdiv(sum, i2f(cnt))
        }
        pp.setF32(0xac, LibM.powf(mean, Float.fromBits(0x40866666)))
        return v
    }
}
