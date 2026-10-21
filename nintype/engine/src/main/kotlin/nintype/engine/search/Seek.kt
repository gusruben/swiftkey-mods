package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.vectors.MemImage

/**
 * The PredPath / branching operations seekNextStep delegates to: PredPath::fing (finger data:
 * +0x29 i8 that finger's point index, +0x30 f64 its time), canAdvanceEx (pathscore, fills the
 * BranchmentData), justVisited / hasVisited (pathscore), and PathSearch::maybeBranch.
 */
interface SeekCallees {
    fun fing(pp: MemImage, side: Int): MemImage
    fun canAdvanceEx(pp: MemImage, p: MemImage, trav: Int, bd: MemImage): Boolean
    fun justVisited(pp: MemImage, p: MemImage): Boolean
    fun hasVisited(pp: MemImage, p: MemImage): Boolean
    fun maybeBranch(pp: MemImage, mt: MemImage, p: MemImage, trav: Int, d1: Double, d2: Double, bd: MemImage): Int
}

/** One entry of a MooTouchPoint's preordained next-step list (+0x48 / +0x60 vectors, 12 bytes):
 * f32 key (bits), the target point, and its NTT (whose first 0x20 bytes are a TravKeyData, +8
 * the key char, +0x18 a u64 visit stamp). */
class NextStep(val key: Int, val p: MemImage, val n: MemImage)

/** The callees of seekPreordainedNextSteps (all PredPath members except maybeBranch). */
interface PreordainedCallees {
    fun lastfing(pp: MemImage): MemImage
    fun lastotherfing(pp: MemImage): MemImage
    fun fing(pp: MemImage, side: Int): MemImage
    fun canAdvanceEx(pp: MemImage, bd: MemImage): Boolean
    fun justVisited(pp: MemImage, p: MemImage): Boolean
    fun maybeBranch(pp: MemImage, mt: MemImage, p: MemImage, n: MemImage, d1: Double, d2: Double, bd: MemImage): Int
}

/** The binary's global u64 step-generation counter (static at vaddr 0x1b8f160). */
class GenCounter(var v: Long)

object Seek {
    private fun d(bits: Long) = Bits.bitsToDouble(bits)
    private val D014 = d(0x3fc1eb851eb851ecL)    // 0.14
    private val DM016 = -d(0x3fc47ae147ae147bL)  // -0.16
    private val DM007 = -d(0x3fb1eb851eb851ecL)  // -0.07
    private val D034 = d(0x3fd5c28f5c28f5c3L)    // 0.34
    private val DM044 = -d(0x3fdc28f5c28f5c29L)  // -0.44
    private val D001 = d(0x3f847ae147ae147bL)    // 0.01
    private val D004 = d(0x3fa47ae147ae147bL)    // 0.04
    private val D008 = d(0x3fb47ae147ae147bL)    // 0.08

    /** A FunnelStat-style counter: clamped to >= 0, then incremented. */
    private fun funnel(ps: MemImage, off: Int) {
        var v = ps.i32(off)
        if (v < 0) { v = 0; ps.setI32(off, 0) }
        ps.setI32(off, v + 1)
    }
    private fun inc(ps: MemImage, off: Int) = ps.setI32(off, ps.i32(off) + 1)

    /** BranchmentData() @0082269c (after TravKeyData()) with the trav's 8 words copied in. */
    fun branchmentData(trav: IntArray): MemImage {
        val bd = MemImage("bd", 0x40)
        for (k in 0 until 8) bd.setI32(4 * k, trav[k])
        bd.setU8(0x20, 1); bd.setU8(0x21, 0); bd.setU8(0x23, 0); bd.setU8(0x24, 0); bd.setU8(0x25, 0)
        bd.setI32(0x28, 0); bd.setI32(0x2c, 0); bd.setI32(0x30, 0)
        bd.setI32(0x34, -1); bd.setI32(0x38, -1); bd.setI32(0x3c, -1)
        return bd
    }

    /** seekNextStep(PredPath&, Mootouch&, bool second) @007ec448: from path [pp], tries every
     * reachable point of stroke [mt] ([points] = its +0x250 vector when [second], else +0x244):
     * a detached point (flag 0x2000) without 0x4000, or an important-for-finger one (+0x34d).
     * A point is out of reach when its finger index (+0x310) is past the path's finger index
     * (fing +0x29) + the finger look-ahead and it starts > 0.14 s after the finger time, or
     * > 2 behind and ended before time - 0.16, or behind at all, or ended before time - 0.07;
     * or by generic index (+0x308 vs path +0x36 + generic look-ahead, 0.34 s after path +0x48;
     * > 3 behind and ended before +0x48 - 0.44). Each traversed key of a reachable point that
     * canAdvanceEx accepts is branched (maybeBranch) with a time window from the point's times
     * and dwell; a revisited point with a long enough gap and displacement > 0.08 gets a second
     * branch. FunnelStat counters at +0x6d4..+0x73c. Returns the number of branch calls that
     * branched. [stats84] = the path length's StepScoreStats +0x84. */
    fun seekNextStep(ps: MemImage, pp: MemImage, mt: MemImage, second: Boolean, points: List<MemImage>,
                     travs: (MemImage) -> List<IntArray>, generic: List<MemImage>, finger1: List<MemImage>,
                     finger0: List<MemImage>, stats84: Int, cb: SeekCallees): Int {
        val side = mt.i8(0xec)
        val fd = cb.fing(pp, side)
        val fla = PathSearch.getFingerLookAheadAllowance(if (mt.u8(0xec) == 1) finger1 else finger0, fd.i8(0x29))
        val gla = PathSearch.getGenericLookAheadAllowance(generic, pp.i16(0x36))
        val fdt = fd.f64(0x30)
        var branched = 0
        var tried = 0
        for (p in points) {
            val fl = p.u16(0x224)
            if (fl and 0x2000 != 0) { if (fl and 0x4000 != 0) continue } else if (p.u8(0x34d) == 0) continue
            inc(ps, 0x6d4)
            val fi = fd.i8(0x29)
            val k = p.i32(0x310)
            if (k > fi + fla) {
                val dt = f2d(d2f(dsub(p.f64(0x128), fdt)))
                if (!(dcmple(dt, D014) || dcmpun(dt, D014))) { funnel(ps, 0x6dc); continue }
            }
            if (k < fi - 2) {
                val lim = dadd(fdt, DM016)
                if (!(dcmpge(p.f64(0x148), lim) || dcmpun(p.f64(0x148), lim))) { funnel(ps, 0x6e0); continue }
            }
            if (k < fi) { funnel(ps, 0x6e4); continue }
            val lim2 = dadd(fdt, DM007)
            if (!(dcmpge(p.f64(0x148), lim2) || dcmpun(p.f64(0x148), lim2))) { funnel(ps, 0x6e4); continue }
            funnel(ps, 0x6d8)
            inc(ps, 0x6e8)
            val g = pp.i16(0x36)
            val gi = p.i32(0x308)
            if (gi > g + gla) {
                val dt = f2d(d2f(dsub(p.f64(0x128), pp.f64(0x48))))
                if (!(dcmple(dt, D034) || dcmpun(dt, D034))) { funnel(ps, 0x6f0); continue }
            }
            if (gi < g - 3) {
                val lim = dadd(pp.f64(0x48), DM044)
                if (!(dcmpge(p.f64(0x148), lim) || dcmpun(p.f64(0x148), lim))) { funnel(ps, 0x6f4); continue }
            }
            funnel(ps, 0x6ec)
            inc(ps, 0x6fc)
            for ((ti, trav) in travs(p).withIndex()) {
                val bd = branchmentData(trav)
                inc(ps, 0x710)
                if (!cb.canAdvanceEx(pp, p, ti, bd)) continue
                bd.setI32(0x34, tried)
                bd.setI32(0x3c, ps.i32(0xe8c) - 1)
                bd.setI32(0x38, stats84)
                funnel(ps, 0x714)
                val psz = pp.u8(0x2a)
                val off = if (psz == 0) 0.0 else D001
                val pt = p.f64(0x128)
                val d1: Double
                val d2: Double
                val dw = p.f32(0x238)
                if (!(fcmpge(dw, Bits.bitsToFloat(0x3df5c28f)) || fcmpun(dw, Bits.bitsToFloat(0x3df5c28f)))) { // dwell < 0.12
                    val tl = dadd(off, fdt)
                    d2 = pt
                    d1 = if (psz == 0) pt else if (dcmplt(pt, tl)) tl else pt
                } else {
                    val a = dadd(off, fdt)
                    val b = dadd(off, pt)
                    val e = dadd(p.f64(0x148), -D001)
                    val v1 = if (dcmplt(a, b)) b else if (dcmpgt(a, e)) e else a
                    val c = dadd(off, pt)
                    d2 = v1
                    d1 = if (psz == 0) v1 else if (dcmplt(c, a)) a else c
                }
                inc(ps, 0x724)
                if (cb.maybeBranch(pp, mt, p, ti, d1, d2, bd) != 0) { branched++; funnel(ps, 0x728) }
                tried++
                if (psz == 0 || second) continue
                if (cb.justVisited(pp, p)) continue
                if (!cb.hasVisited(pp, p)) continue
                val pe = p.f64(0x148)
                val x = if (dcmplt(pe, fdt)) fdt else pe
                val dl = dadd(d1, D004)
                if (dcmple(x, dl) || dcmpun(x, dl)) continue
                val disp = f2d(p.f32(0x234))
                if (dcmple(disp, D008) || dcmpun(disp, D008)) continue
                inc(ps, 0x724); inc(ps, 0x738)
                val y = dadd(off, pt)
                val d2b = if (dcmplt(pe, y)) y else pe
                if (cb.maybeBranch(pp, mt, p, ti, x, d2b, bd) != 0) { branched++; funnel(ps, 0x728); funnel(ps, 0x73c) }
            }
        }
        return branched
    }

    /** seekNextStep(PredPath&, Mootouch&) @007ecee4: nothing for a stroke that ended before
     * the path's +0x58 time; else the +0x250 then the +0x244 pass. */
    fun seekNextStep(pp: MemImage, mt: MemImage, pass: (second: Boolean) -> Int): Int {
        if (dcmplt(mt.f64(0x110), pp.f64(0x58))) return 0
        val a = pass(true)
        return pass(false) + a
    }

    /** seekPreordainedNextSteps(PredPath&) @007eb490. Marks the path preordained (+0x11c = 1,
     * +0x28 i16 = step - 1) and walks the precomputed next-step lists of the last finger's point
     * P (lastfing(1) +0x40, or its parent when it has one; the parent link must be detached,
     * 0x225 & 0x20). When the last other finger (+0x10 & 1) has a point Q (same resolution) with
     * Q +0x58 > P +0x70, the lists are P +0x48 and Q +0x48; else P +0x48 and P +0x60. The two
     * key-descending lists are merged (list 1 wins ties and NaNs). Each NTT is taken once per
     * call via a fresh generation stamp (+0x18). An entry whose key char differs from its finger's
     * last char is skipped if its point is that finger's last (+0x40) or second-last (+0x44)
     * point. Others are branched like seekNextStep (window from +0x138 for short dwells), with a
     * second branch for a non-detached, not-just-visited point that is the finger's last point
     * and has a long enough gap and displacement > 0.08. [Xnl] a = moopad +0xc48, b = +0xc14 (only
     * read when a == 1). FunnelStats +0x698..+0x6b8 and +0x710..+0x73c. */
    fun seekPreordainedNextSteps(ps: MemImage, pp: MemImage, stats84: Int, gen: GenCounter, xnlA: () -> Int, xnlB: () -> Int,
                                 nexts: (MemImage, Int) -> List<NextStep>, cb: PreordainedCallees) {
        pp.setU8(0x11c, 1)
        pp.setU16(0x28, (ps.i32(0xe8c) + 0xffff) and 0xffff)
        val lf = cb.lastfing(pp)
        val lo = cb.lastotherfing(pp)
        val g1 = gen.v + 1
        gen.v = if (g1 == 0L) gen.v + 2 else g1
        fun resolve(p: MemImage?): MemImage {
            val q = p ?: error("seekPreordainedNextSteps: finger without point (assert)")
            val par = q.ref(0xc) ?: return q
            if (q.u8(0x225) and 0x20 == 0) error("seekPreordainedNextSteps: attached parent (assert)")
            return par
        }
        val pP = resolve(lf.ref(0x40))
        val l1 = nexts(pP, 0x48)
        val l2 = if (lo.u8(0x10) and 1 != 0) {
            resolve(lf.ref(0x40))
            val q = resolve(lo.ref(0x40))
            val a = q.f64(0x58); val b = pP.f64(0x70)
            if (dcmple(a, b) || dcmpun(a, b)) nexts(pP, 0x60) else nexts(q, 0x48)
        } else nexts(pP, 0x60)
        val a = xnlA()
        val b = if (a == 1) xnlB() else 0
        var c = 0
        var i1 = 0
        var i2 = 0
        while (true) {
            val e: NextStep
            if (i2 == l2.size) {
                if (i1 == l1.size) return
                e = l1[i1++]
            } else if (i1 == l1.size) {
                e = l2[i2++]
            } else {
                val k2 = Bits.bitsToFloat(l2[i2].key); val k1 = Bits.bitsToFloat(l1[i1].key)
                e = if (fcmple(k2, k1) || fcmpun(k2, k1)) l1[i1++] else l2[i2++]
            }
            inc(ps, 0x698)
            val n = e.n
            if (n.readLE(0x18, 8) == gen.v) { funnel(ps, 0x6a0); continue }
            n.writeLE(0x18, 8, gen.v)
            funnel(ps, 0x69c)
            val p = e.p
            val mt = p.ref(8)!!
            val fd = cb.fing(pp, mt.i8(0xec))
            inc(ps, 0x6ac)
            if (n.i32(8) != fd.u8(0x15)) {
                if (p === fd.ref(0x40)) { funnel(ps, 0x6b4); continue }
                if (p === fd.ref(0x44)) { funnel(ps, 0x6b8); continue }
            }
            funnel(ps, 0x6b0)
            val bd = branchmentData(IntArray(8))
            bd.setU8(0x20, a and 0xff); bd.setU8(0x21, b and 0xff)
            val fl = p.u16(0x224)
            bd.setU8(0x22, if (fl and 0x800 != 0) (fl and 0x1000) shr 12 else 0)
            for (k in 0 until 8) bd.setI32(4 * k, n.i32(4 * k))
            inc(ps, 0x710)
            if (!cb.canAdvanceEx(pp, bd)) continue
            bd.setI32(0x34, c)
            bd.setI32(0x3c, ps.i32(0xe8c) - 1)
            bd.setI32(0x38, stats84)
            funnel(ps, 0x714)
            val psz = pp.u8(0x2a)
            val off = if (psz == 0) 0.0 else D001
            val fdt = fd.f64(0x30)
            val dw = p.f32(0x238)
            val d1: Double
            val d2: Double
            if (!(fcmpge(dw, Bits.bitsToFloat(0x3df5c28f)) || fcmpun(dw, Bits.bitsToFloat(0x3df5c28f)))) { // dwell < 0.12
                val t = p.f64(0x138)
                val tl = dadd(off, fdt)
                d2 = t
                d1 = if (psz == 0) t else if (dcmplt(t, tl)) tl else t
            } else {
                val x = dadd(off, fdt)
                val y = dadd(off, p.f64(0x128))
                val ee = dadd(p.f64(0x148), -D001)
                val v = if (dcmplt(x, y)) y else if (dcmpgt(x, ee)) ee else x
                d2 = v
                d1 = if (psz == 0) v else if (dcmplt(y, x)) x else y
            }
            inc(ps, 0x724)
            if (cb.maybeBranch(pp, mt, p, n, d1, d2, bd) != 0) funnel(ps, 0x728)
            c++
            if (psz == 0) continue
            if (fl and 0x2000 != 0) continue
            if (cb.justVisited(pp, p)) continue
            if (fd.ref(0x40) !== p) continue
            val pe = p.f64(0x148)
            val x = if (dcmplt(pe, fdt)) fdt else pe
            val dl = dadd(d1, D004)
            if (dcmple(x, dl) || dcmpun(x, dl)) continue
            val disp = f2d(p.f32(0x234))
            if (dcmple(disp, D008) || dcmpun(disp, D008)) continue
            inc(ps, 0x724); inc(ps, 0x738)
            val y = dadd(off, p.f64(0x128))
            val d2b = if (dcmplt(pe, y)) y else pe
            if (cb.maybeBranch(pp, mt, p, n, x, d2b, bd) != 0) { funnel(ps, 0x728); funnel(ps, 0x73c) }
        }
    }
}
