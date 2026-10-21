package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.math.FastCurveMapper
import nintype.engine.vectors.MemImage

/**
 * The preordained next-step lists of the drift points (MooTouchPoint +0x48 same finger, +0x60
 * other finger; seekPreordainedNextSteps walks them): PathSearch::syncNextStepPoints,
 * considerAddingNextStep and MooTouchPoint::receiveNTT.
 */
object NextSteps {
    private fun b(i: Long) = i.toInt()
    private fun f(i: Long) = Bits.bitsToFloat(i.toInt())
    private fun d(i: Long) = Bits.bitsToDouble(i)

    /** Same-finger weight by soft-index lead (+0x324): {0 -> 0.5, 0.3 -> 0.85, 1 -> 1, 2 -> 0.6, 3 -> 0.3, 4 -> 0.1}. */
    val SAME = FastCurveMapper.ofBits(0, b(0x3f000000), b(0x3e99999a), b(0x3f59999a), b(0x3f800000), b(0x3f800000),
        b(0x40000000), b(0x3f19999a), b(0x40400000), b(0x3e99999a), b(0x40800000), b(0x3dcccccd))
    /** Other-finger weight by generic soft-index lead (+0x31c): {-3 -> 0.2, -2 -> 0.6, -1 -> 0.85, 0 -> 0.9,
     *  1 -> 1, 2 -> 0.85, 3 -> 0.7, 4 -> 0.4, 5 -> 0.1}. */
    val OTHER = FastCurveMapper.ofBits(b(0xc0400000), b(0x3e4ccccd), b(0xc0000000), b(0x3f19999a), b(0xbf800000), b(0x3f59999a),
        0, b(0x3f666666), b(0x3f800000), b(0x3f800000), b(0x40000000), b(0x3f59999a), b(0x40400000), b(0x3f333333),
        b(0x40800000), b(0x3ecccccd), b(0x40a00000), b(0x3dcccccd))

    private val D012 = d(0x3fc1eb851eb851ecL)
    private val D034 = d(0x3fd5c28f5c28f5c3L)
    private val DM016 = d(0xbfc47ae147ae147bUL.toLong())
    private val DM024 = d(0xbfceb851eb851eb8UL.toLong())
    private val DM015 = d(0xbfc3333333333333UL.toLong())

    /** MooTouchPoint::receiveNTT(NextTouchTarget&) @0094c884: appends {key, point, trav} to the
     * same-finger list (+0x48, earliest target time at +0x58) or the other-finger one (+0x60,
     * earliest at +0x70). */
    fun receiveNTT(p: MemImage, e: NextStep, lists: (MemImage, Int) -> MutableList<NextStep>) {
        val t = e.p.f64(0x128)
        val (lo, to) = if (e.p.u8(0x21c) == p.u8(0x21c)) 0x48 to 0x58 else 0x60 to 0x70
        val l = lists(p, lo)
        if (l.isEmpty() || !dcmplt(p.f64(to), t)) p.setF64(to, t)
        l.add(e)
    }

    /** considerAddingNextStep(MooTouchPoint& p, MooTouchPoint& q) @007d0fc4: offers every
     * traversed key of q to p's next-step lists with key = weight * trav score. */
    fun considerAddingNextStep(p: MemImage, q: MemImage, travs: (MemImage) -> List<MemImage>,
                               lists: (MemImage, Int) -> MutableList<NextStep>) {
        val qf = q.u16(0x224)
        val ok0 = qf and 0x6000 != 0x6000
        var w: Float
        if (p.u8(0x21c) == q.u8(0x21c)) {
            val dt = d2f(dsub(q.f64(0x128), p.f64(0x128)))
            val far = dcmpgt(f2d(dt), D012)
            val q310 = q.i32(0x310); val p310 = p.i32(0x310)
            var ok = ok0
            if (q310 < p310 || (far && q310 > p.i32(0x314) + p310)) ok = false
            else if (!(fcmpge(dt, f(0xbd8f5c29)) || fcmpun(dt, f(0xbd8f5c29)))) ok = false
            val m = when {
                fcmpgt(dt, f(0x3cf5c28f)) -> 1f
                fcmpgt(dt, 0f) -> f(0x3f428f5c)
                fcmpgt(dt, f(0xbcf5c28f)) -> f(0x3ecccccd)
                else -> f(0x3dcccccd)
            }
            w = fmul(m, SAME.get(fsub(q.f32(0x324), p.f32(0x324))))
            if (!ok) return
        } else {
            if (!ok0) return
            w = 1f
        }
        val dt2 = d2f(dsub(q.f64(0x128), p.f64(0x128)))
        val dd = f2d(dt2)
        if (!(dcmple(dd, D034) || dcmpun(dd, D034)) && q.i32(0x308) > p.i32(0x308) + p.i32(0x318)) return
        val pt = p.f64(0x128)
        val lt = dcmplt(q.f64(0x148), dadd(pt, DM016))
        if (q.i32(0x308) < p.i32(0x308) - 3 && lt) return
        if (dcmplt(q.f64(0x148), dadd(pt, DM024))) return
        if (p.u8(0x21c) != q.u8(0x21c)) {
            val m = when {
                fcmpgt(dt2, f(0xbd4ccccd)) -> 1f
                dcmpgt(dd, DM015) -> f(0x3f4ccccd)
                fcmpgt(dt2, f(0xbe99999a)) -> f(0x3f19999a)
                else -> f(0x3dcccccd)
            }
            w = fmul(m, OTHER.get(fsub(q.f32(0x31c), p.f32(0x31c))))
        }
        for (t in travs(q)) receiveNTT(p, NextStep(Bits.floatToBits(fmul(w, t.f32(0x10))), q, t), lists)
    }

    /** syncNextStepPoints() @007e0404: for each drift point p (+0x244 of every Mootouch), every
     * point q of every Mootouch (+0x244 then +0x250) is considered; then p's same-finger list is
     * std::sort-ed by key, descending. */
    fun syncNextStepPoints(mts: List<MemImage>, points: (MemImage, Int) -> List<MemImage>, travs: (MemImage) -> List<MemImage>,
                           lists: (MemImage, Int) -> MutableList<NextStep>) {
        for (m in mts) for (p in points(m, 0x244)) {
            for (m2 in mts) {
                for (q in points(m2, 0x244)) considerAddingNextStep(p, q, travs, lists)
                for (q in points(m2, 0x250)) considerAddingNextStep(p, q, travs, lists)
            }
            val l = lists(p, 0x48)
            if (l.isNotEmpty()) nintype.engine.pathscore.StlSort.sort(l) { a, b2 ->
                fcmpgt(Bits.bitsToFloat(a.key), Bits.bitsToFloat(b2.key))
            }
        }
    }
}

/** `rangemap<float, bool>` (std::map<float, pair<bool, bool>> + a default value at +0x18): breakpoints
 *  key -> (in range, value) holding for [key, next key). Keys ordered by std::less<float>. */
class RangeMapF(val default: Boolean = false) {
    class Bp(val key: Float, var inRange: Boolean, var value: Boolean)
    val bps = ArrayList<Bp>()
    private fun lt(a: Float, b: Float) = nintype.engine.SoftFloat.fcmplt(a, b)
    private fun put(k: Float, inRange: Boolean, value: Boolean) {
        val i = bps.indexOfFirst { !lt(it.key, k) }
        if (i >= 0 && !lt(k, bps[i].key)) { bps[i].inRange = inRange; bps[i].value = value; return }
        bps.add(if (i < 0) bps.size else i, Bp(k, inRange, value))
    }
    /** set(float lo, float hi, bool const& v) @007d16c4. */
    fun set(lo: Float, hi: Float, v: Boolean) {
        if (nintype.engine.SoftFloat.fcmpge(lo, hi) || nintype.engine.SoftFloat.fcmpun(lo, hi)) return
        val ub = bps.indexOfFirst { !(nintype.engine.SoftFloat.fcmple(it.key, hi) || nintype.engine.SoftFloat.fcmpun(it.key, hi)) }.let { if (it < 0) bps.size else it }
        if (ub != 0) { val p = bps[ub - 1]; put(hi, p.inRange, p.value) } else put(hi, false, default)
        val r5 = bps.indexOfFirst { !lt(it.key, hi) && !lt(hi, it.key) }
        val r4 = bps.indexOfFirst { !lt(it.key, lo) }.let { if (it < 0) bps.size else it }
        if (r4 < r5) for (k in r4 until r5) bps.removeAt(r4)
        put(lo, true, v)
    }
    /** The syncOverlaps query: the breakpoint before upper_bound(t) is in range. */
    fun covers(t: Float): Boolean {
        val ub = bps.indexOfFirst { !(nintype.engine.SoftFloat.fcmple(it.key, t) || nintype.engine.SoftFloat.fcmpun(it.key, t)) }.let { if (it < 0) bps.size else it }
        return ub != 0 && bps[ub - 1].inRange
    }
}

object Overlaps {
    /** syncOverlaps() @007e0840: each stroke's time span (+0xf8..+0x110 relative to PathSearch
     *  +0x168, widened by 0.03 s) goes into a range map per finger (+0xec == 1 / other); a
     *  corner-kink point (+0x250) of a finger-2 stroke inside a finger-1 span, or of a finger-1
     *  stroke inside another finger's span, is an overlap: point +0xd0 = 1, PathSearch +0x330 = 1,
     *  +0x340 counts them. */
    fun syncOverlaps(ps: MemImage, mts: List<MemImage>, points: (MemImage, Int) -> List<MemImage>) {
        ps.setU8(0x330, 0); ps.setI32(0x338, 0); ps.setI32(0x33c, 0); ps.setI32(0x340, 0)
        val m1 = RangeMapF(); val m2 = RangeMapF()
        val t0 = ps.f64(0x168)
        for (m in mts) {
            val lo = nintype.engine.SoftFloat.fadd(nintype.engine.SoftFloat.d2f(nintype.engine.SoftFloat.dsub(m.f64(0xf8), t0)), Bits.bitsToFloat(0xbcf5c28f.toInt()))
            val hi = nintype.engine.SoftFloat.fadd(nintype.engine.SoftFloat.d2f(nintype.engine.SoftFloat.dsub(m.f64(0x110), t0)), Bits.bitsToFloat(0x3cf5c28f))
            if (m.u8(0xec) == 1) m1.set(lo, hi, true) else m2.set(lo, hi, true)
        }
        for (m in mts) for (q in points(m, 0x250)) {
            val t = nintype.engine.SoftFloat.d2f(nintype.engine.SoftFloat.dsub(q.f64(0x128), t0))
            val map = when (m.u8(0xec)) { 2 -> m1; 1 -> m2; else -> null } ?: continue
            if (map.covers(t)) { q.setU8(0xd0, 1); ps.setU8(0x330, 1); ps.setI32(0x340, ps.i32(0x340) + 1) }
        }
    }
}

object ForceAC {
    /** syncForceACPoints() @007de230: with the Moopad Xnl +0x28ac8 on and a generic point (+0x1bc)
     * flagged 0x4 (the last one, ac): points after it are autocomplete-forced (flag 0x2,
     * PathSearch +0x369 = 1), except ac's same-stroke successor (+0x28) within 0.6 units; points
     * up to it are flagged 0x1, ac itself only when no earlier same-stroke point has ac as its
     * successor within 0.6 units. PathSearch +0x368 = 1 (0 when no point is flagged 0x4). */
    fun syncForceACPoints(ps: MemImage, generic: List<MemImage>, xnl: Int, unit: Float) {
        if (xnl != 1) return
        var ac: MemImage? = null
        var ai = -1
        for ((i, p) in generic.withIndex()) if (p.u16(0x224) and 4 != 0) { ac = p; ai = i }
        if (ai == -1) { ps.setU8(0x368, 0); return }
        val a = ac!!
        fun pos(p: MemImage) = nintype.engine.math.Point2D(p.f32(0xdc), p.f32(0xe0))
        fun mark(p: MemImage, f: Int) = p.setU16(0x224, p.u16(0x224) or f)
        var acc = false
        for ((i, p) in generic.withIndex()) {
            if (i > ai) {
                if (p === a || p.ref(8) !== a.ref(8)) { mark(p, 2); ps.setU8(0x369, 1); continue }
                val u = nintype.engine.emkey.Moopad.unitDist(pos(a), pos(p), unit)
                if (!(nintype.engine.SoftFloat.fcmpge(u, 0.6f.let { Bits.bitsToFloat(0x3f19999a) }) || nintype.engine.SoftFloat.fcmpun(u, Bits.bitsToFloat(0x3f19999a))) && a.ref(0x28) === p) continue
                mark(p, 2); ps.setU8(0x369, 1)
            } else {
                var r = false
                if (p !== a && a.ref(8) === p.ref(8)) {
                    val u = nintype.engine.emkey.Moopad.unitDist(pos(p), pos(a), unit)
                    if (p.ref(0x28) === a) r = nintype.engine.SoftFloat.fcmplt(u, Bits.bitsToFloat(0x3f19999a))
                }
                acc = acc || r
                if (p !== a || !acc) mark(p, 1)
            }
        }
        ps.setU8(0x368, 1)
    }
}

object PointerMesh {
    /** Mootouch::syncPointerMesh() @009739fc: each point vector (+0x244, +0x250): owner (+8) =
     * the Mootouch, and consecutive points linked (+0x18 next, +0x14 previous). */
    fun syncPointerMesh(mt: MemImage, points: (MemImage, Int) -> List<MemImage>) {
        for (off in intArrayOf(0x244, 0x250)) {
            var prev: MemImage? = null
            for (p in points(mt, off)) {
                p.refs[8] = mt
                if (prev != null) { prev.refs[0x18] = p; p.refs[0x14] = prev }
                prev = p
            }
        }
    }
}

object BaseTimes {
    /** syncBaseTimes() @007dd7bc: the search's time base from its strokes: +0x168 earliest start
     * (+0xf8), +0x170 earliest first-focus time, +0x178 latest end (+0x110), +0x180 = end - start;
     * the touch group range +0x194 / +0x198 (min / max of +0xd8); point counts (+0x324 all drift
     * points, +0x328 of strokes with more than one, +0x350 set then, +0x32c points pausing (+0x158)
     * more than 0.04 s); per stroke +0x394 = 0 and +0xed = started within 0.04 s of the base.
     * +0x359: exactly one stroke flagged +0xcc; +0x358: some +0xcc stroke had points (or a +0xcd
     * stroke exists, which also clears +0x359). Then +0x35b / +0x35c from the text before the
     * cursor ([lastChar]: its last character, null without an input connection): a letter / a
     * period; both cleared when the global Context has the override key ([ctxOverride]) or
     * temporary autospace is on. */
    fun syncBaseTimes(ps: MemImage, mts: List<MemImage>, points: (MemImage, Int) -> List<MemImage>,
                      lastChar: Int?, ctxOverride: Boolean, tempAutospace: Boolean) {
        ps.setI32(0x324, 0); ps.setI32(0x328, 0); ps.setI32(0x32c, 0)
        for (o in 0x168 until 0x180 step 4) ps.setI32(o, 0)
        if (mts.isEmpty()) {
            ps.setI32(0x180, 0); ps.setI32(0x184, 0); ps.setU8(0x359, 0); ps.setU8(0x358, 0)
        } else {
            var first = true
            var seenCC = false
            var onlyOneCC = false
            var ccPoints = 0
            var anyCD = false
            for (m in mts) {
                m.setU8(0x394, 0)
                val pts = points(m, 0x244)
                val ff = MootouchOps.firstFocusTime(m, pts)
                if (first) {
                    ps.setF64(0x168, m.f64(0xf8)); ps.setF64(0x178, m.f64(0x110)); ps.setF64(0x170, ff)
                    ps.setI32(0x194, m.i32(0xd8)); ps.setI32(0x198, m.i32(0xd8))
                } else {
                    if (nintype.engine.SoftFloat.dcmplt(m.f64(0xf8), ps.f64(0x168))) ps.setF64(0x168, m.f64(0xf8))
                    if (nintype.engine.SoftFloat.dcmplt(ff, ps.f64(0x170))) ps.setF64(0x170, ff)
                    if (nintype.engine.SoftFloat.dcmplt(ps.f64(0x178), m.f64(0x110))) ps.setF64(0x178, m.f64(0x110))
                    if (!(ps.i32(0x194) < m.i32(0xd8))) ps.setI32(0x194, m.i32(0xd8))
                    if (!(m.i32(0xd8) < ps.i32(0x198))) ps.setI32(0x198, m.i32(0xd8))
                }
                val n = pts.size
                if (n > 1) { ps.setU8(0x350, 1); ps.setI32(0x328, ps.i32(0x328) + n) }
                for (p in pts) {
                    val v = p.f32(0x158)
                    val lim = Bits.bitsToFloat(0x3d23d70a)
                    if (!(nintype.engine.SoftFloat.fcmple(v, lim) || nintype.engine.SoftFloat.fcmpun(v, lim))) ps.setI32(0x32c, ps.i32(0x32c) + 1)
                }
                ps.setI32(0x324, ps.i32(0x324) + n)
                if (m.u8(0xcc) != 0) { ccPoints += n; onlyOneCC = !seenCC; seenCC = true }
                if (m.u8(0xcd) != 0) anyCD = true
                first = false
            }
            ps.setF64(0x180, nintype.engine.SoftFloat.dsub(ps.f64(0x178), ps.f64(0x168)))
            val d004 = Bits.bitsToDouble(0x3fa47ae147ae147bL)
            for (m in mts) m.setU8(0xed, if (nintype.engine.SoftFloat.dcmplt(nintype.engine.SoftFloat.dsub(m.f64(0xf8), ps.f64(0x168)), d004)) 1 else 0)
            if (onlyOneCC) { ps.setU8(0x359, 1); ps.setU8(0x358, 0) } else { ps.setU8(0x359, 0); ps.setU8(0x358, if (ccPoints != 0) 1 else 0) }
            if (anyCD) { ps.setU8(0x359, 0); ps.setU8(0x358, 1) }
        }
        ps.setU8(0x35c, 0); ps.setU8(0x35b, 0)
        if (lastChar != null) {
            if (nintype.engine.geometry.Geometry.shingIswalpha(lastChar)) ps.setU8(0x35b, 1)
            if (lastChar == 0x2e) ps.setU8(0x35c, 1)
            if (ctxOverride || tempAutospace) { ps.setU8(0x35c, 0); ps.setU8(0x35b, 0) }
        }
    }
}

object SlideHist {
    /** Mootouch::syncSlideVSHist(vector<Mootouch>& all) @00973ad8: rebuilds each drift point's
     * slide-VS targets (+0x3c, SlideVSTarget {point, mult}) from the earlier drift points of
     * every stroke that started before this one ended (MooTouchPoint::considerSlideVSHist,
     * same-finger when it is this stroke), sorts them by mult descending (ties: lower point
     * address first; [order] gives a point's allocation rank) and keeps the best 3. */
    fun syncSlideVSHist(mt: MemImage, mts: List<MemImage>, points: (MemImage, Int) -> List<MemImage>, hybridUnit: Float,
                        order: (MemImage) -> Int, resolve: (String) -> MemImage) {
        val mine = points(mt, 0x244)
        for (p in mine) p.seq(0x3c).clear()
        for (m2 in mts) {
            if (nintype.engine.SoftFloat.dcmpge(m2.f64(0xf8), mt.f64(0x110)) || nintype.engine.SoftFloat.dcmpun(m2.f64(0xf8), mt.f64(0x110))) continue
            for (p in mine) for (q in points(m2, 0x244)) {
                if (p === q) continue
                if (nintype.engine.SoftFloat.dcmpge(q.f64(0x128), p.f64(0x128)) || nintype.engine.SoftFloat.dcmpun(q.f64(0x128), p.f64(0x128))) continue
                nintype.engine.touch.TouchPoint.considerSlideVSHist(p, m2 === mt, q, nintype.engine.vectors.Values.ref(q.name), hybridUnit)
            }
        }
        for (p in mine) {
            val l = p.seq(0x3c)
            if (l.isEmpty()) continue
            fun target(j: nintype.engine.vectors.JsonValue) = resolve(j["@0x0:target"]!!["\$ref"]!!.asString())
            fun mult(j: nintype.engine.vectors.JsonValue) = nintype.engine.vectors.Leaf.fromJson(j["@0x4:mult"]!!).asFloat()
            nintype.engine.pathscore.StlSort.sort(l) { a, b ->
                val ma = mult(a); val mb = mult(b)
                nintype.engine.SoftFloat.fcmpgt(ma, mb) || (!nintype.engine.SoftFloat.fcmpgt(mb, ma) && order(target(a)) < order(target(b)))
            }
            while (l.size > 3) l.removeAt(l.size - 1)
        }
    }
}

object FuturePlanar {
    private fun f(i: Long) = Bits.bitsToFloat(i.toInt())
    private val CURVES = arrayOf(
        FastCurveMapper().push(1f, 1f).push(1.5f, f(0x3f19999a)).push(f(0x40133333), 0f).finalize(),
        FastCurveMapper().push(2f, 1f).push(f(0x402ccccd), f(0x3f19999a)).push(3.5f, 0f).finalize(),
        FastCurveMapper().push(3f, 1f).push(f(0x406ccccd), f(0x3f19999a)).push(4.5f, 0f).finalize())
    private val LIM = floatArrayOf(f(0x40133333), 3.5f, f(0x408ccccd))
    private val OFF = floatArrayOf(f(0xbfa66666), f(0xbf8ccccd), f(0xbf4ccccd))

    private fun recordTravs(q: MemImage, c: Float, pc: nintype.engine.touch.PlanarCoverage, travs: (MemImage) -> List<MemImage>) {
        val e = if (q.u8(0x225) and 0x20 != 0) f(0x3e99999a) else f(0x3f666666)
        for (t in travs(q)) {
            val k = nintype.engine.trie.Keycode.trieCharaToKeycode(t.i32(8))
            if (!nintype.engine.trie.Keycode.isCoverable(k)) continue
            val v = nintype.engine.SoftFloat.fdiv(t.f32(0x10), 6f)
            val w = if (fcmpge(v, 0f) || fcmpun(v, 0f)) nintype.engine.math.LibM.powf(v, e)
                    else -nintype.engine.math.LibM.powf(-v, e)
            pc.record(k, fmul(c, w), q.f64(0x128))
        }
    }

    /** MooTouchPoint::syncFuturePlanarCoverage(int n) @0094dddc: the planar coverage the next
     * n chars may reach from this point. Same finger: the points from here along +0x38 while
     * the generic (+0x31c) or same-finger (+0x324) soft-index lead is within LIM[n], weighted
     * by curve n of the larger lead weight, into [own] slot n - 1. Other finger: back along +0x2c
     * to the first point within OFF[n] generic, then forward along +0x30 over other-finger
     * points (lead = +0x31c lead, raised to n or by 1 below n; other lead = +0x324 - own +0x320),
     * into [other] slot n - 1. Each traversed key's score / 6 is ^0.3 (+0x225 bit 5) or ^0.9. */
    fun syncFuturePlanarCoverage(p: MemImage, n: Int, own: List<nintype.engine.touch.PlanarCoverage>,
                                 other: List<nintype.engine.touch.PlanarCoverage>, travs: (MemImage) -> List<MemImage>) {
        val curve = CURVES[n - 1]; val lim = LIM[n - 1]; val a2 = n.toFloat()
        var q: MemImage? = p
        while (q != null) {
            val dg = fsub(q.f32(0x31c), p.f32(0x31c))
            val ds = fsub(q.f32(0x324), p.f32(0x324))
            if (!(nintype.engine.SoftFloat.fcmple(dg, lim) || fcmpun(dg, lim)) && fcmpgt(ds, lim)) break
            var c = curve.get(dg); val c2 = curve.get(ds)
            if (nintype.engine.SoftFloat.fcmplt(c, c2)) c = c2
            if (!(nintype.engine.SoftFloat.fcmple(c, f(0x3c23d70a)) || fcmpun(c, f(0x3c23d70a)))) recordTravs(q, c, own[n - 1], travs)
            q = q.ref(0x38)
        }
        val bound = nintype.engine.SoftFloat.fadd(OFF[n - 1], p.f32(0x31c))
        var r: MemImage = p
        while (true) {
            if (nintype.engine.SoftFloat.fcmplt(r.f32(0x31c), bound)) break
            r = r.ref(0x2c) ?: break
        }
        var s: MemImage? = r
        while (s != null) {
            if (s.u8(0x21c) != p.u8(0x21c)) {
                val dg = fsub(s.f32(0x31c), p.f32(0x31c))
                val x = if (fcmpge(dg, a2) || fcmpun(dg, a2)) dg
                        else if (nintype.engine.SoftFloat.fcmplt(f(Bits.floatToBits(fsub(a2, dg)).toLong() and 0x7fffffff), 1f)) a2
                        else nintype.engine.SoftFloat.fadd(dg, 1f)
                val ds = fsub(s.f32(0x324), p.f32(0x320))
                if (!(nintype.engine.SoftFloat.fcmple(ds, lim) || fcmpun(ds, lim)) && fcmpgt(x, lim)) break
                var c = curve.get(x); val c2 = curve.get(ds)
                if (nintype.engine.SoftFloat.fcmplt(c, c2)) c = c2
                if (!(nintype.engine.SoftFloat.fcmple(c, f(0x3c23d70a)) || fcmpun(c, f(0x3c23d70a)))) recordTravs(s, c, other[n - 1], travs)
            }
            s = s.ref(0x30)
        }
    }

    /** syncFuturePlanarCoverage() @0094efc4: both vectors (+0xa0 own, +0xac other) resized to 3,
     * n = 1, 2, 3, then +0xb8 = other[0].tMin (own time +0x128 when it has none) and
     * +0xc0 = own[0].tMin. */
    fun syncFuturePlanarCoverage(p: MemImage, own: MutableList<nintype.engine.touch.PlanarCoverage>,
                                 other: MutableList<nintype.engine.touch.PlanarCoverage>, travs: (MemImage) -> List<MemImage>) {
        for (l in listOf(own, other)) {
            while (l.size < 3) l.add(nintype.engine.touch.PlanarCoverage())
            while (l.size > 3) l.removeAt(l.size - 1)
        }
        for (n in 1..3) syncFuturePlanarCoverage(p, n, own, other, travs)
        p.setF64(0xb8, if (other[0].hasTime == 0) p.f64(0x128) else other[0].tMin)
        p.setF64(0xc0, own[0].tMin)
    }
}
