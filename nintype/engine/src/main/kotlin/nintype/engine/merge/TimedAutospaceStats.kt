package nintype.engine.merge

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.emkey.Moopad
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage
import java.util.TreeMap

/**
 * The value model of an [AutospaceSplitoffStats] (offsets relative to the struct):
 *  0x00 std::map<int, float> gaps: Mootouch index (+0xc8) -> gap (s) before that touch
 *  0x18 splitTouch (-1)   0x1c maxGap (-1.0)   0x20 dist: unitDist from the end of the covered
 *  span to the split touch's start   0x24 npts: MooTouchPoints in the touches from the split on
 *  0x28 nonCompetitors (counts down from 0)   0x2c competitors   0x30 score
 */
class SplitoffStats {
    val gaps = TreeMap<Int, Float>()
    var splitTouch = -1
    var maxGap = Bits.bitsToFloat(0xbf800000.toInt())
    var dist = 0f
    var npts = 0
    var nonCompetitors = 0
    var competitors = 0
    var score = 0f
}

/**
 * `PathSearch::getStatsForTimedAutospace()` @007dbf04 and
 * `AutospaceModule::syncAutospaceStatsPostSearch()` @00ee835c: where (if anywhere) a word
 * break falls inside the buffered touches, from the holes in their time coverage.
 *
 * PathSearch fields read: 0x110 Moopad*, 0x1a0..0x1a4 std::vector<Mootouch> (0x3b8-byte
 * elements, in PathSearch order). Mootouch fields: 0xc8 i32 index, 0xdc i32 id, 0xf8 f64 start
 * time, 0x110 f64 end time, 0x128 Point2D start pos (getStartpos), 0x130 Point2D end pos,
 * 0x244 std::vector<MooTouchPoint> (1000-byte elements; only the count is read).
 */
object TimedAutospaceStats {
    private val D0_01 = Bits.bitsToDouble(0x3f847ae147ae147bL)
    private val F0_01 = Bits.bitsToFloat(0x3c23d70a)
    private val F0_04 = Bits.bitsToFloat(0x3d23d70a)
    private val F0_7 = Bits.bitsToFloat(0x3f333333)

    /** One buffered touch as getStatsForTimedAutospace reads it. */
    class Touch(val img: MemImage, val nPoints: Int) {
        val index get() = img.i32(0xc8)
        val id get() = img.i32(0xdc)
        val startT get() = img.f64(0xf8)
        val endT get() = img.f64(0x110)
        val startPos get() = Point2D(img.f32(0x128), img.f32(0x12c))
        val endPos get() = Point2D(img.f32(0x130), img.f32(0x134))
    }

    /** getStatsForTimedAutospace() @007dbf04. [touches] in PathSearch order; [mp] supplies
     * settings_autospace_timed_millsecs (via staticAutospaceTimedMergeTime of the Moopad's
     * AutospaceModule) and the key unit (Moopad::unitDist).
     *
     * Touches are sorted by start time (gnustl std::sort, comparator start < start). Walking
     * them, `covered` is the end of the union of touch intervals so far:
     *  - a touch starting more than 0.01 s after `covered` (or unordered) opens a gap
     *    g = d2f(start - covered). If g > base, every gap recorded so far becomes 0.01. Then
     *    gaps[touch.index] = g, and if g > maxGap or g > base, the touch becomes the split
     *    candidate: id, dist = unitDist(touch.startPos, endPos of the touch that set `covered`),
     *    npts = points in sorted touches k..n-1, maxGap = g. `covered` = its end, endPos = its end pos.
     *  - otherwise the touch overlaps: if it ends later (ordered >), endPos = its end pos;
     *    covered = max(covered, end) by `covered < end`.
     * splitTouch = index of the first touch (PathSearch order) whose id equals the candidate's. */
    fun getStatsForTimedAutospace(touches: List<Touch>, mp: MemImage): SplitoffStats {
        val base = fdiv(i2f(MergeMoopad.autospaceMs(mp)), 1000f)
        val st = SplitoffStats()
        var splitId = -1
        var maxGap = Bits.bitsToFloat(0xbf800000.toInt())
        var dist = 0f
        var npts = 0
        if (touches.isNotEmpty()) {
            val sorted = touches.toMutableList()
            GnuSortList.sort(sorted) { a, b -> dcmplt(a.startT, b.startT) }
            var covered = 0.0
            var endPos = Point2D(0f, 0f)
            for ((k, mt) in sorted.withIndex()) {
                if (k == 0) {
                    endPos = mt.endPos; covered = mt.endT
                    continue
                }
                val thr = dadd(covered, D0_01)
                val s = mt.startT
                if (dcmpgt(s, thr) || dcmpun(s, thr)) {
                    val g = d2f(dsub(s, covered))
                    val big = !(fcmple(g, base) || fcmpun(g, base))
                    if (big) for (key in st.gaps.keys) st.gaps[key] = F0_01
                    st.gaps[mt.index] = g
                    if (fcmpgt(g, maxGap) || big) {
                        splitId = mt.id
                        dist = Moopad.unitDist(mt.startPos, endPos, mp.f32(Moopad.OFF_UNIT))
                        var n = 0
                        for (j in k until sorted.size) n += sorted[j].nPoints
                        npts = n
                        maxGap = g
                    }
                    covered = mt.endT; endPos = mt.endPos
                } else {
                    val e = mt.endT
                    if (!(dcmple(e, covered) || dcmpun(e, covered))) endPos = mt.endPos
                    if (dcmplt(covered, e)) covered = e
                }
            }
            for (mt in touches) if (mt.id == splitId) { st.splitTouch = mt.index; break }
        }
        st.maxGap = maxGap; st.dist = dist; st.npts = npts
        return st
    }

    /** {0.5->0.1, 0.6->0.3, 0.7->0.7, 0.8->0.9, 0.9->1, 1->1.25}: split score, no competitor. */
    val SCORE0 = FastCurveMapper.ofBits(
        0x3f000000, 0x3dcccccd, 0x3f19999a, 0x3e99999a, 0x3f333333, 0x3f333333,
        0x3f4ccccd, 0x3f666666, 0x3f666666, 0x3f800000, 0x3f800000, 0x3fa00000)

    /** {0.5->0.1, 0.6->0.2, 0.7->0.5, 0.8->0.78, 0.9->0.94, 1->1.1}: one competitor. */
    val SCORE1 = FastCurveMapper.ofBits(
        0x3f000000, 0x3dcccccd, 0x3f19999a, 0x3e4ccccd, 0x3f333333, 0x3f000000,
        0x3f4ccccd, 0x3f47ae14, 0x3f666666, 0x3f70a3d7, 0x3f800000, 0x3f8ccccd)

    /** syncAutospaceStatsPostSearch() @00ee835c. [am] is the AutospaceModule image (its Moopad
     * at ptr(0)); [touches] / the sitting list come from the Moopad's PathSearch (Moopad+0x29dc0)
     * and Moopad+0x294c4. Returns the new stats, which the caller stores at AM+0x2fc.
     * [sittingFlags]: Mootouch+0x26e of each sitting touch (any & 8 -> score 0). */
    fun syncAutospaceStatsPostSearch(am: MemImage, touches: List<Touch>, sittingFlags: List<Int>): SplitoffStats {
        val mp = am.ptr(0) ?: error("no Moopad")
        val st = getStatsForTimedAutospace(touches, mp)
        st.nonCompetitors = 0; st.competitors = 0
        var nc = 0; var c = 0
        for ((key, v) in st.gaps) {
            if (key == st.splitTouch) continue
            val r = fdiv(v, st.maxGap)
            if (!(fcmple(r, F0_7) || fcmpun(r, F0_7))) { c++; st.competitors = c } else { nc--; st.nonCompetitors = nc }
        }
        val maxGap = st.maxGap
        val small = fcmple(maxGap, F0_04) || fcmpun(maxGap, F0_04)
        val dyn = fmul(fdiv(i2f(MergeMoopad.autospaceMs(mp)), 1000f), am.f32(AutospaceModule.DYN_MULT))
        var score = 0f
        if (!small) {
            val v = fsub(maxGap, AutospaceModule.calculateTimingReductionBetweenTouches(am, st.dist, st.npts))
            score = when (st.competitors) {
                1 -> SCORE1.get(fdiv(v, dyn))
                0 -> SCORE0.get(fdiv(v, dyn))
                else -> 0f
            }
        }
        st.score = score
        if (MergeMoopad.hasUSF1(mp, 0x7d) == 1) st.score = 0f
        if (sittingFlags.any { it and 8 != 0 }) st.score = 0f
        return st
    }

    /** Writes [st]'s scalar fields at [base] of [img] (the map is serialized by the adapter). */
    fun writeScalars(img: MemImage, base: Int, st: SplitoffStats) {
        img.setI32(base + 0x14, st.gaps.size)
        img.setI32(base + 0x18, st.splitTouch)
        img.setF32(base + 0x1c, st.maxGap)
        img.setF32(base + 0x20, st.dist)
        img.setI32(base + 0x24, st.npts)
        img.setI32(base + 0x28, st.nonCompetitors)
        img.setI32(base + 0x2c, st.competitors)
        img.setF32(base + 0x30, st.score)
    }
}
