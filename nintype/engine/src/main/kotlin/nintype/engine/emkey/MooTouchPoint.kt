package nintype.engine.emkey

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dmul
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.LibM
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * Leaf methods of `Emkey::MooTouchPoint` (sizeof 1000), ported over a byte image of the
 * object ([MemImage]) so field offsets stay exactly the binary's. Known fields (names are
 * guesses from use; the offsets are what the vectors check):
 *
 *   0x008 ptr   owner (something with an int at +0xbc)     0x034 ptr prevSameFing
 *   0x038 ptr   nextSameFing                               0x0dc Point2D pos
 *   0x128 f64   tStart                                     0x148 f64 tEnd
 *   0x158 f32   pauseDur                                   0x190/0x198 f64 twerk start/stop
 *   0x1ac u8    twerking                                   0x21c u8 finger
 *   0x21d u8    dirChar ('1'..'9' numpad digit)            0x220 f32 dirDeg
 *   0x224 u16   flags (0x8 slowdown, 0x400, 0x800 start, 0x1000 end, 0x2000 detached)
 *   0x234 f32   displacement                               0x238 f32 dwell (s)
 *   0x300 f32   angle (deg)                                0x320/0x324 f32 soft index generic / same-finger
 *   0x34c/0x34d u8 visitation flags
 */
object MooTouchPoint {
    const val SIZE = 1000

    private fun f(b: Int) = Bits.bitsToFloat(b)
    private val F0_07 = f(0x3d8f5c29)
    private val F4_6 = f(0x40933333)
    private val F3_34 = f(0x4055c28f)

    private fun flags(tp: MemImage) = tp.u16(0x224)

    /** Tier by corner angle shared by several methods: a > 90 -> t90, > 45 -> t45, > 30 -> t30,
     * else null (an unordered compare falls through to the next tier, so NaN -> null). */
    private fun angleTier(a: Float, t90: Float, t45: Float, t30: Float): Float? = when {
        !(fcmple(a, 90f) || fcmpun(a, 90f)) -> t90
        !(fcmple(a, 45f) || fcmpun(a, 45f)) -> t45
        !(fcmple(a, 30f) || fcmpun(a, 30f)) -> t30
        else -> null
    }

    /** clamp as compiled: x < lo -> lo; else x > hi -> hi; else x (NaN passes through). */
    private fun clamp(x: Float, lo: Float, hi: Float): Float = if (fcmplt(x, lo)) lo else if (fcmpgt(x, hi)) hi else x

    /** getImportanceScore(bool) @00960fc0. */
    fun getImportanceScore(tp: MemImage, arg: Boolean): Float {
        val fl = flags(tp)
        if (fl and 0x2000 != 0) return if (arg) 1.5f else 0f
        var imp = clamp(fdiv(tp.f32(0x238), F0_07), 0f, 5f)
        if (fl and 0x800 != 0 && fcmplt(imp, 7f)) imp = 7f
        if (fl and 0x1000 != 0 && fcmplt(imp, 4.5f)) imp = 4.5f
        val tier = angleTier(tp.f32(0x300), 6f, F4_6, F3_34) ?: return imp
        return if (fcmpgt(imp, tier)) imp else tier
    }

    /** revisitPenalty() @00960ae0 (and the first half of revisitMultpen). */
    fun revisitPenalty(tp: MemImage): Float {
        val c = clamp(fdiv(tp.f32(0x238), f(0x3e99999a)), 0f, 1f)
        var v = fmul(fsub(1f, c), 5f)
        val tier = angleTier(tp.f32(0x300), 2f, f(0x40533333), 4.5f)
        if (tier != null && !fcmplt(v, tier)) v = tier
        if (tp.u8(0x225) and 0x18 == 0) return v
        return if (fcmplt(v, 1.25f)) v else 1.25f
    }

    /** revisitMultpen() @00960e34: powf((revisitPenalty / -5) * 0.5 + 1, 3). */
    fun revisitMultpen(tp: MemImage): Float =
        LibM.powf(fadd(fmul(fdiv(revisitPenalty(tp), -5f), 0.5f), 1f), 3f)

    /** {0->1, 15->0.99, 30->0.95, 45->0.9, 90->0.6, 130->0.3}: novisitMultpen's static curve. */
    val NOVISIT_ANGLE = FastCurveMapper.ofBits(
        0x00000000, 0x3f800000, 0x41700000, 0x3f7d70a4, 0x41f00000, 0x3f733333,
        0x42340000, 0x3f666666, 0x42b40000, 0x3f19999a, 0x43020000, 0x3e99999a)

    /** novisitMultpen() @00960c44: flag 0x400 -> 1; flag 0x1800 -> 0.4; pauseDur > 0.11 -> 0.1;
     * flag 0x8 -> owner == null ? 0.3 : owner.0xbc != 0 ? 0.3 : 0.1; else NOVISIT_ANGLE(angle). */
    fun novisitMultpen(tp: MemImage): Float {
        val fl = flags(tp)
        if (fl and 0x400 != 0) return 1f
        if (fl and 0x1800 != 0) return f(0x3ecccccd)
        if (fcmpgt(tp.f32(0x158), f(0x3de147ae))) return f(0x3dcccccd)
        if (fl and 0x8 != 0) {
            val owner = tp.ptr(0x8) ?: return f(0x3e99999a)
            return if (owner.i32(0xbc) != 0) f(0x3e99999a) else f(0x3dcccccd)
        }
        return NOVISIT_ANGLE.get(tp.f32(0x300))
    }

    /** timePenalty(double t) @009618ac: 0 inside [tStart, tEnd] (unordered counts as inside);
     * else d2f(|t - nearest end| * 45.0). */
    fun timePenalty(tp: MemImage, t: Double): Float {
        val start = tp.f64(0x128)
        val ref: Double
        if (!(dcmple(start, t) || dcmpun(start, t))) ref = start
        else {
            val end = tp.f64(0x148)
            if (dcmpge(end, t) || dcmpun(end, t)) return 0f
            ref = end
        }
        val d = dsub(t, ref)
        return d2f(dmul(Bits.bitsToDouble(Bits.doubleToBits(d) and Long.MAX_VALUE), 45.0))
    }

    private fun pos(tp: MemImage) = Point2D(tp.f32(0xdc), tp.f32(0xe0))

    /** syncAngle(prev, next) @00962568: angle@0x300 = deg(acosf(dot(norm(pos - prev.pos),
     * norm(next.pos - pos)))) with the degree conversion d2f(f2d(r * 180f) / pi). */
    fun syncAngle(tp: MemImage, prev: MemImage, next: MemImage) {
        val a = (pos(tp) - pos(prev)).normalise()
        val b = (pos(next) - pos(tp)).normalise()
        val r = LibM.acosf(Point2D.dot(a, b))
        tp.setF32(0x300, d2f(ddiv(f2d(fmul(r, 180f)), Bits.bitsToDouble(0x400921fb54442d18L))))
    }

    /** displacementInsensitivityFactor() @0096264c: clamp(disp / -0.28 + 1.7, 0.45, 1.7). */
    fun displacementInsensitivityFactor(tp: MemImage): Float =
        clamp(fadd(fdiv(tp.f32(0x234), f(0xbe8f5c29.toInt())), f(0x3fd9999a)), f(0x3ee66666), f(0x3fd9999a))

    /** determineDirFromArg(float deg) @00961978: numpad direction of an angle in degrees
     * (negative angles + 360 first). Writes dirChar@0x21d and the bin's centre angle dirDeg@0x220. */
    fun determineDirFromArg(tp: MemImage, argIn: Float) {
        val (ch, deg) = dirBin(argIn)
        tp.setU8(0x21d, ch.code)
        tp.setF32(0x220, deg)
    }

    /** The 8-way numpad binning shared by determineDirFromArg/determineDirection. */
    fun dirBin(argIn: Float): Pair<Char, Float> {
        var a = argIn
        if (!(fcmpge(a, 0f) || fcmpun(a, 0f))) a = fadd(a, 360f)
        fun below(t: Float) = !(fcmpge(a, t) || fcmpun(a, t))
        return when {
            below(22.5f) -> '6' to 0f
            below(67.5f) -> '3' to 45f
            below(112.5f) -> '2' to 90f
            below(157.5f) -> '1' to 135f
            below(202.5f) -> '4' to 180f
            below(247.5f) -> '7' to 225f
            below(292.5f) -> '8' to 270f
            below(337.5f) -> '9' to 315f
            else -> '6' to 0f
        }
    }

    /** estimateCharCount() @009616bc. */
    fun estimateCharCount(tp: MemImage): Float {
        val d = fadd(tp.f32(0x238), tp.f32(0x238))
        var n: Float
        if (fcmpgt(d, f(0x3ecccccd))) n = 2f
        else if (!(fcmple(d, f(0x3e4ccccd)) || fcmpun(d, f(0x3e4ccccd)))) n = fadd(fdiv(fadd(d, f(0xbe4ccccd.toInt())), f(0x3e4ccccd)), 1f)
        else if (!(fcmple(d, f(0x3dcccccd)) || fcmpun(d, f(0x3dcccccd)))) n = fadd(fdiv(fmul(fadd(d, f(0xbdcccccd.toInt())), f(0x3ecccccd)), f(0x3dcccccd)), f(0x3f19999a))
        else n = fmul(fdiv(d, f(0x3dcccccd)), f(0x3f19999a))
        if (tp.u8(0x225) and 0x18 != 0) n = if (fcmpgt(n, 1f)) n else 1f
        val tier = angleTier(tp.f32(0x300), 1f, f(0x3f19999a), f(0x3eae147b)) ?: return n
        return if (fcmpgt(n, tier)) n else tier
    }

    /** isMidStraightSlowdown() @0095b7c8: !(flags & 0x1800) && angle < 30 && (flags & 8). */
    fun isMidStraightSlowdown(tp: MemImage): Boolean {
        val fl = flags(tp)
        if (fl and 0x1800 != 0) return false
        return fcmplt(tp.f32(0x300), 30f) && (fl shr 3) and 1 != 0
    }

    /** isSlidePauserPoint() @0095b804: !(flags & 0x1800) && pauseDur > 0.11. */
    fun isSlidePauserPoint(tp: MemImage): Boolean =
        tp.u8(0x225) and 0x18 == 0 && fcmpgt(tp.f32(0x158), f(0x3de147ae))

    /** isVisitationPoint() @0095d480. */
    fun isVisitationPoint(tp: MemImage): Boolean = tp.u8(0x34d) != 0 || tp.u8(0x34c) != 0

    /** twerkStopDuration() @0095add4: twerking ? d2f(stop@0x198 - start@0x190) : 0. */
    fun twerkStopDuration(tp: MemImage): Float =
        if (tp.u8(0x1ac) != 0) d2f(dsub(tp.f64(0x198), tp.f64(0x190))) else 0f

    /** nonGenericSoftIndexDistance(other) @0095dcfc: same finger -> other.si - si (0x324);
     * else the smaller of the two cross differences, chosen by which point starts first. */
    fun nonGenericSoftIndexDistance(tp: MemImage, o: MemImage): Float {
        if (o.u8(0x21c) == tp.u8(0x21c)) return fsub(o.f32(0x324), tp.f32(0x324))
        val oLe = dcmple(o.f64(0x128), tp.f64(0x128))
        val un = dcmpun(o.f64(0x128), tp.f64(0x128))
        val r1 = fsub(o.f32(0x320), tp.f32(0x324))
        val r6 = fsub(o.f32(0x324), tp.f32(0x320))
        var a = r6
        var b = r1
        if (!un && !oLe) { a = r1; b = r6 }
        return if (fcmplt(a, b)) r6 else r1
    }

    /** Shared walk of the find* family: lim = si + delta (or si - d for the Earlier ones);
     * from this, return the first point whose index passes `hit`, following the link at
     * [link]; the last point of the chain if none passes. */
    private inline fun walk(tp: MemImage, siOff: Int, link: Int, lim: Float, hit: (Float, Float) -> Boolean): MemImage {
        var p: MemImage = tp
        while (true) {
            if (hit(p.f32(siOff), lim)) return p
            p = p.ref(link) ?: return p
        }
    }

    // Same-finger chain: si@0x324, prev@0x34, next@0x38. Any-finger chain: si@0x31c, prev@0x2c, next@0x30.

    /** findEarlierSameFing(float d) @0095dca4: first p (walking prev@0x34) with si < si - d. */
    fun findEarlierSameFing(tp: MemImage, d: Float): MemImage = walk(tp, 0x324, 0x34, fsub(tp.f32(0x324), d), ::fcmplt)

    /** findLaterSameFing(float d) @0095dc4c: first p (walking next@0x38) with si > si + d. */
    fun findLaterSameFing(tp: MemImage, d: Float): MemImage = walk(tp, 0x324, 0x38, fadd(tp.f32(0x324), d), ::fcmpgt)

    /** findEarlierAnyFing(float d) @0095db34: prev@0x2c, generic si@0x31c. */
    fun findEarlierAnyFing(tp: MemImage, d: Float): MemImage = walk(tp, 0x31c, 0x2c, fsub(tp.f32(0x31c), d), ::fcmplt)

    /** findLaterAnyFing(float d) @0095dadc: next@0x30, si@0x31c. */
    fun findLaterAnyFing(tp: MemImage, d: Float): MemImage = walk(tp, 0x31c, 0x30, fadd(tp.f32(0x31c), d), ::fcmpgt)

    /** findCloserSameFingShiftedBy(float d) @0095db8c: d > 0 walks next@0x38 for si > si+d,
     * else (incl. NaN) prev@0x34 for si < si+d. */
    fun findCloserSameFingShiftedBy(tp: MemImage, d: Float): MemImage = shifted(tp, d, 0x324, 0x38, 0x34)

    /** findCloserAnyFingShiftedBy(float d) @0095da1c: same over si@0x31c, next@0x30, prev@0x2c. */
    fun findCloserAnyFingShiftedBy(tp: MemImage, d: Float): MemImage = shifted(tp, d, 0x31c, 0x30, 0x2c)

    private fun shifted(tp: MemImage, d: Float, siOff: Int, next: Int, prev: Int): MemImage {
        val lim = fadd(tp.f32(siOff), d)
        return if (!(fcmple(d, 0f) || fcmpun(d, 0f))) walk(tp, siOff, next, lim, ::fcmpgt)
        else walk(tp, siOff, prev, lim, ::fcmplt)
    }

    /** determineDirection(other) @00961bbc: bins deg(arg(pos - other.pos)) like
     * [determineDirFromArg] and writes dirChar@0x21d and @0x215, dirDeg@0x220 and @0x218. */
    fun determineDirection(tp: MemImage, other: MemImage) {
        val deg = Point2D.toDegrees((pos(tp) - pos(other)).arg())
        val (ch, d) = dirBin(deg)
        tp.setU8(0x21d, ch.code)
        tp.setF32(0x220, d)
        tp.setF32(0x218, d)
        tp.setU8(0x215, ch.code)
    }

    /** syncCoverage(p1, p2, moopad) @0095cb88, non-detached branch: coverage@0x370 = 1, then for
     * every TravKeyData (32 bytes, key pos at +0) in the vector at 0x328:
     * +0x14 = powf(calculate_coverage_score(p1, p2, key, mp), flags & 0x800 ? 0.25 : 0.6).
     * Detached points (flag 0x2000): see touch.TouchPoint.syncCoverage. */
    fun syncCoverage(tp: MemImage, p1: Point2D, p2: Point2D, unit: Float) {
        val fl = flags(tp)
        if (fl and 0x2000 != 0) throw UnsupportedOperationException("syncCoverage detached branch needs MooBoard::clampCoveragePos (wave 2)")
        tp.setF32(0x370, 1f)
        val e = if (fl and 0x800 != 0) 0.25f else f(0x3f19999a)
        val travs = tp.seq(0x328)
        for (k in travs.indices) {
            val el = MemImage.decode(travs[k])
            el.setF32(0x14, LibM.powf(Calculate.coverageScore(p1, p2, Point2D(el.f32(0), el.f32(4)), unit), e))
            travs[k] = el.encodeLike(travs[k])
        }
    }

    /** coverageForChar(int c) @0095cc88: flags_hi & 0x20 (detached) -> coverage@0x370; else the
     * max (`a < b ? b : a` from 0) of +0x14 over TravKeyData with char@+8 == c. */
    fun coverageForChar(tp: MemImage, c: Int): Float {
        if (tp.u8(0x225) and 0x20 != 0) return tp.f32(0x370)
        var a = 0f
        for (t in tp.seq(0x328)) {
            val el = MemImage.decode(t)
            if (el.i32(8) == c && fcmplt(a, el.f32(0x14))) a = el.f32(0x14)
        }
        return a
    }
}
