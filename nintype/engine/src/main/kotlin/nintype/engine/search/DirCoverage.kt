package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.emkey.DirectionalCoverage
import nintype.engine.emkey.Moopad
import nintype.engine.math.CurveMapper
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * The two NeoContextHolder<bool> settings the directional-coverage code reads, as their two
 * bytes: "acforce_start" and "acforce_ender" (both 0, 0 at runtime). A setting is on when both
 * bytes are non-zero. Callers force one to (1, 1) around a call and restore it after.
 */
class AcHolders(var start0: Int = 0, var start1: Int = 0, var ender0: Int = 0, var ender1: Int = 0) {
    val startOn get() = start0 != 0 && start1 != 0
    val enderOn get() = ender0 != 0 && ender1 != 0
}

/** A DirectionalCoverage together with its CovererRecorder base: tMin (+0), tMax (+8),
 * hasTime (+0x10); the bins vector is at +0x14. */
class DirCov(val dc: DirectionalCoverage = DirectionalCoverage(), var tMin: Double = 0.0, var tMax: Double = 0.0, var hasTime: Int = 0) {
    /** CovererRecorder::recordTime(double t) @0095a4dc: the first time sets both ends. */
    fun recordTime(t: Double) {
        if (hasTime == 0) { tMin = t; tMax = t; hasTime = 1; return }
        if (!dcmplt(tMin, t)) tMin = t
        if (!dcmpgt(tMax, t)) tMax = t
    }
}

/**
 * PathSearch's directional coverage: per important point (gatherDirectionalCoverageForTouchPoint,
 * into the point's DirectionalCoverage at +0x1b0, +0x1d0 when acforce_start is on, +0x1f0 when
 * only acforce_ender is), and per nth char (a tent over generic soft index, like
 * gatherNthCharPlanarCoverage) with the +0x3b8 cache and the autocomplete +0x3c4 cache.
 * [cov] maps (point, offset) to that point's DirectionalCoverage.
 */
object DirCoverage {
    private fun b(i: Long) = i.toInt()

    /** Arc width by unit distance: {1 -> 90, 2 -> 60, 3 -> 45, 4 -> 35}. */
    val WIDTH = FastCurveMapper.ofBits(b(0x3f800000), b(0x42b40000), b(0x40000000), b(0x42700000), b(0x40400000), b(0x42340000),
        b(0x40800000), b(0x420c0000))
    /** Same-finger weight by soft-index lead: {2.5 -> 0, 2 -> 0.2, 1 -> 1, 0 -> 0.4, -0.1 -> 0}. */
    val SAME = FastCurveMapper.ofBits(b(0x40200000), 0, b(0x40000000), b(0x3e4ccccd), b(0x3f800000), b(0x3f800000), 0, b(0x3ecccccd),
        b(0xbdcccccd), 0)
    /** Other-finger weight by generic soft-index lead: {2.5 -> 0, 2 -> 0.2, 1 -> 1, 0 -> 0.6, -1 -> 0}. */
    val OTHER = FastCurveMapper.ofBits(b(0x40200000), 0, b(0x40000000), b(0x3e4ccccd), b(0x3f800000), b(0x3f800000), 0, b(0x3f19999a),
        b(0xbf800000), 0)
    /** Other-finger time factor: {0 -> 1, -0.2 -> 0.4, -0.4 -> 0}. */
    val OTHER_T = FastCurveMapper.ofBits(0, b(0x3f800000), b(0xbe4ccccd), b(0x3ecccccd), b(0xbecccccd), 0)
    private val F001 = Bits.bitsToFloat(0x3c23d70a)
    private val FM01 = Bits.bitsToFloat(0xbdcccccd.toInt())

    private fun MemImage.pt(o: Int) = Point2D(f32(o), f32(o + 4))

    /** gatherDirectionalCoverageForTouchPoint(MooTouchPoint* tp) @007e3548: for every generic
     * point q (+0x1bc) other than tp (when a force setting is on, only those sharing flag 0x1
     * or 0x2 with tp), records the direction tp - q (key centres +0xdc) with an arc width by
     * unit distance: same finger (+0x21c) when 0 < tp.si - q.si (+0x324) < 2.5 with weight
     * SAME(lead), else OTHER(generic lead +0x31c, -1..2.5) * OTHER_T(tp.t - q.t). */
    fun gatherForTouchPoint(points: List<MemImage>, tp: MemImage, unit: Float, h: AcHolders, cov: (MemImage, Int) -> DirectionalCoverage) {
        val sOn = h.startOn
        val eOn = h.enderOn
        val target = cov(tp, if (sOn) 0x1d0 else if (eOn) 0x1f0 else 0x1b0)
        val forced = sOn || eOn
        val tf = tp.u8(0x224)
        for (q in points) {
            if (forced && q !== tp) {
                val qf = q.u16(0x224)
                if (!(qf and 1 != 0 && tf and 1 != 0) && !(qf and 2 != 0 && tf and 2 != 0)) continue
            } else if (q === tp) continue
            val w: Float
            if (q.u8(0x21c) == tp.u8(0x21c)) {
                val ds = fsub(tp.f32(0x324), q.f32(0x324))
                if (fcmpge(ds, 2.5f) || fcmpun(ds, 2.5f)) continue
                if (fcmple(ds, FM01) || fcmpun(ds, FM01)) continue
                w = SAME.get(ds)
            } else {
                val ds = fsub(tp.f32(0x31c), q.f32(0x31c))
                if (fcmpge(ds, 2.5f) || fcmpun(ds, 2.5f)) continue
                if (fcmple(ds, -1f) || fcmpun(ds, -1f)) continue
                val a = OTHER.get(ds)
                val dt = d2f(dsub(tp.f64(0x128), q.f64(0x128)))
                w = fmul(a, OTHER_T.get(dt))
            }
            if (fcmple(w, F001) || fcmpun(w, F001)) continue
            val v = tp.pt(0xdc) - q.pt(0xdc)
            target.record(v, WIDTH.get(Moopad.unitDist(v, unit)), w)
        }
    }

    /** syncImportantTouchDirectionalCoverages() @007e40ec: once (PathSearch +0x3b4), every
     * generic point's coverage with the current settings, then with PathSearch +0x368 set again
     * with acforce_start forced on and then with acforce_ender forced on. */
    fun syncImportantTouchDirectionalCoverages(ps: MemImage, points: List<MemImage>, unit: Float, h: AcHolders,
                                               cov: (MemImage, Int) -> DirectionalCoverage) {
        if (ps.u8(0x3b4) != 0) return
        for (q in points) gatherForTouchPoint(points, q, unit, h, cov)
        if (ps.u8(0x368) != 0) {
            val s0 = h.start0; val s1 = h.start1
            h.start0 = 1; h.start1 = 1
            for (q in points) gatherForTouchPoint(points, q, unit, h, cov)
            h.start0 = s0; h.start1 = s1
            val e0 = h.ender0; val e1 = h.ender1
            h.ender0 = 1; h.ender1 = 1
            for (q in points) gatherForTouchPoint(points, q, unit, h, cov)
            h.ender0 = e0; h.ender1 = e1
        }
        ps.setU8(0x3b4, 1)
    }

    /** gatherNthCharDirectionalCoverage(DirectionalCoverage& dc, int n) @007e4af4: with
     * important points, syncs the per-point coverages, then over the generic points (+0x1bc,
     * until the generic soft-index distance from important[0] passes the far foot) records
     * each point's coverage (+0x1b0, or +0x1d0 and only flag-0x1 points with acforce_start
     * on, read before the sync) scaled by the nthCharTent value, and its time. */
    fun gatherNthChar(slot: DirCov, ps: MemImage, important: List<MemImage>, points: List<MemImage>, siToTime: CurveMapper,
                      timeToSi: CurveMapper, n: Int, unit: Float, h: AcHolders, cov: (MemImage, Int) -> DirectionalCoverage) {
        if (important.isEmpty()) return
        val both = h.startOn
        syncImportantTouchDirectionalCoverages(ps, points, unit, h, cov)
        val (cm, loK, hiK) = PathSearch.nthCharTent(siToTime, timeToSi, n)
        val first = important[0]
        for (q in points) {
            val d = Bits.bitsToFloat(Bits.floatToBits(fsub(q.f32(0x31c), first.f32(0x31c))) and 0x7fffffff)
            val inWindow = !(fcmplt(d, loK) || fcmpun(d, loK)) && (!both || q.u16(0x224) and 1 != 0)
            if (inWindow) {
                val c = cm.get(d)
                if (!(fcmple(c, F001) || fcmpun(c, F001))) {
                    slot.dc.record(cov(q, if (both) 0x1d0 else 0x1b0), c)
                    slot.recordTime(q.f64(0x128))
                }
            }
            if (fcmpge(d, hiK)) break
        }
    }

    private fun grow(cache: MutableList<DirCov>, n: Int, gather: (DirCov, Int) -> Unit): DirCov {
        require(n >= 0) { "directional coverage cache($n): the binary loops forever" }
        while (cache.size <= n) {
            val dc = DirCov()
            cache.add(dc)
            gather(dc, cache.size - 1)
        }
        return cache[n]
    }

    /** getNthCharDirectionalCoverage(int n) @007e490c: the +0x3b8 cache, grown one
     * gatherNthChar slot at a time. */
    fun getNthChar(cache: MutableList<DirCov>, n: Int, gather: (DirCov, Int) -> Unit): DirCov = grow(cache, n, gather)

    /** getAutocompleteStarterDirectionalCoverage(int n) @007e4518: with PathSearch +0x368 clear
     * the nth-char coverage; else the +0x3c4 cache, gathered with acforce_start forced on. */
    fun getAcStarter(acMode: Int, nthCache: MutableList<DirCov>, acCache: MutableList<DirCov>, n: Int, h: AcHolders,
                     gather: (DirCov, Int) -> Unit): DirCov {
        if (acMode == 0) return getNthChar(nthCache, n, gather)
        return grow(acCache, n) { dc, k ->
            val s0 = h.start0; val s1 = h.start1
            h.start0 = 1; h.start1 = 1
            gather(dc, k)
            h.start0 = s0; h.start1 = s1
        }
    }

    /** BoardDirectionalIndexes::getCoverageValue(DirectionalCoverage& dc) @009dea8c: over the
     * up-to-4 direction bytes [dirs] (signed; -1 = none), the largest dc.getCoverage(i), floored
     * at 0 by the first; 1 when the first is none. */
    fun boardCoverageValue(dirs: ByteArray, dc: DirectionalCoverage): Float {
        if (dirs[0].toInt() == -1) return 1f
        val f0 = dc.getCoverage(dirs[0].toInt())
        var a = if (nintype.engine.SoftFloat.fcmpgt(f0, 0f)) f0 else 0f
        for (k in 1..3) {
            if (dirs[k].toInt() == -1) continue
            val f = dc.getCoverage(dirs[k].toInt())
            if (fcmplt(a, f)) a = f
        }
        return a
    }

    /** The AutocompleteSearch directional callback: getCoverageValue(MooBoard::getBiDirIndex(prev,
     * c), getAutocompleteStarterDirectionalCoverage(pos)). */
    fun starterDirectionalValue(board: nintype.engine.geometry.MooBoard, prev: Int, c: Int, starter: DirCov): Float =
        boardCoverageValue(board.getBiDirIndex(prev, c).bytes(), starter.dc)
}
