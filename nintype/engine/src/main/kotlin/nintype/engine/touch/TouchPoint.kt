package nintype.engine.touch

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.emkey.MooTouchPoint
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * More `Emkey::MooTouchPoint` methods (wave 2, touch area), over the same byte image as
 * [MooTouchPoint]. Fields this file adds to the map there:
 *
 *   0x0ec Point2D neighbourA, 0x0f4 Point2D neighbourB (the points syncLeadPoints averages)
 *   0x0fc Point2D leadA = pos + (pos - mid) * 0.25, 0x104 Point2D leadB = pos + (pos - mid) * -0.25
 *   0x10c Point2D leadDir = normalise(pos - mid) (left as is when |pos - mid| <= 1)
 *   0x140 f64 tLast (the time the previous point's roughness is measured against)
 *   0x210 f32 roughness (px per ms), 0x214 u8 roughnessShared
 *   0x328 vector<TravKeyData> travs
 *   0x34d u8 important (score > 0.7), 0x350 f32 importance, 0x354 f32 novisitMultpen,
 *   0x358 f32 revisitMultpen
 */
object TouchPoint {
    private fun f(b: Int) = Bits.bitsToFloat(b)

    /** syncScores() @0095d02c: importance@0x350 (getImportanceScore(false), raised to 0.73 for a
     * slowdown point, flag 0x8), important@0x34d = importance > 0.7, novisitMultpen@0x354,
     * revisitMultpen@0x358. */
    fun syncScores(tp: MemImage) {
        var imp = MooTouchPoint.getImportanceScore(tp, false)
        if (tp.u16(0x224) and 0x8 != 0 && fcmplt(imp, f(0x3f3ae148))) imp = f(0x3f3ae148)
        tp.setF32(0x350, imp)
        tp.setU8(0x34d, if (fcmpgt(imp, f(0x3f333333))) 1 else 0)
        tp.setF32(0x354, MooTouchPoint.novisitMultpen(tp))
        tp.setF32(0x358, MooTouchPoint.revisitMultpen(tp))
    }

    /** markDetached() @0095d018: flags |= 0x400, then syncScores. */
    fun markDetached(tp: MemImage) {
        tp.setU16(0x224, tp.u16(0x224) or 0x400)
        syncScores(tp)
    }

    /** normalizeTravkeyScores(float to) @0095d384: scale every score so the max (from 0,
     * `m < s ? s : m`) becomes `to`, unless the max is <= 0.001 (or NaN). */
    fun normalizeTravkeyScores(tp: MemImage, to: Float) {
        val travs = TravKeyData.list(tp)
        if (travs.isEmpty()) return
        var m = 0f
        for (t in travs) if (fcmplt(m, t.score)) m = t.score
        if (fcmple(m, f(0x3a83126f)) || fcmpun(m, f(0x3a83126f))) return
        val k = fdiv(to, m)
        for (t in travs) t.score = fmul(k, t.score)
        TravKeyData.store(tp, travs)
    }

    /** syncRoughness(prev) @0095c630: roughness@0x210 = |pos - prev.pos| / (tStart - prev.tLast)
     * / 1000 when that time is > 0.03 s (copied to prev when prev.0x214), else prev's value (0
     * when prev is a slowdown point, flags 0x800 at byte 0x225 bit 3); a slowdown prev then
     * takes this point's value. prev == null: no-op. */
    fun syncRoughness(tp: MemImage, prev: MemImage?) {
        if (prev == null) return
        val d = Point2D(prev.f32(0xdc), prev.f32(0xe0)).lengthFrom(Point2D(tp.f32(0xdc), tp.f32(0xe0)))
        val dt = d2f(dsub(tp.f64(0x128), prev.f64(0x140)))
        if (!(fcmple(dt, f(0x3cf5c28f)) || fcmpun(dt, f(0x3cf5c28f)))) {
            val r = fdiv(fdiv(d, dt), 1000f)
            tp.setF32(0x210, r)
            if (prev.u8(0x214) != 0) prev.setF32(0x210, r)
        } else {
            tp.setI32(0x210, if (prev.u8(0x225) and 8 == 0) prev.i32(0x210) else 0)
        }
        if (prev.u8(0x225) and 8 != 0) prev.setI32(0x210, tp.i32(0x210))
    }

    /** syncLeadPoints() @0095d4a4: see the field map. Detached (byte 0x225 bit 0x20): both lead
     * points = pos. */
    fun syncLeadPoints(tp: MemImage) {
        val pos = Point2D(tp.f32(0xdc), tp.f32(0xe0))
        if (tp.u8(0x225) and 0x20 != 0) {
            tp.setI32(0xfc, tp.i32(0xdc)); tp.setI32(0x100, tp.i32(0xe0))
            tp.setI32(0x104, tp.i32(0xdc)); tp.setI32(0x108, tp.i32(0xe0))
            return
        }
        val mid = (Point2D(tp.f32(0xec), tp.f32(0xf0)) + Point2D(tp.f32(0xf4), tp.f32(0xf8))) * 0.5f
        val d = pos - mid
        val a = pos + d * 0.25f
        tp.setF32(0xfc, a.x); tp.setF32(0x100, a.y)
        val b = pos + d * -0.25f
        tp.setF32(0x104, b.x); tp.setF32(0x108, b.y)
        val len = d.length()
        if (fcmple(len, 1f) || fcmpun(len, 1f)) return
        val n = d.normalise()
        tp.setF32(0x10c, n.x); tp.setF32(0x110, n.y)
    }

    /** pushTrav(char c, int n, float score) @00961e94: appends {0, 0, c, n, score, 1.0, 0, 0};
     * returns its index. [c] is the char register as passed (unsigned char on ARM). */
    fun pushTrav(tp: MemImage, c: Int, n: Int, score: Float): Int {
        val travs = TravKeyData.list(tp)
        val t = TravKeyData()
        t.chara = c; t.n = n; t.score = score; t.coverage = 1f
        travs.add(t)
        TravKeyData.store(tp, travs)
        return travs.size - 1
    }

    /** bestKeyScore() @00961f94: last trav's score (the vector is sorted ascending), 0 if none. */
    fun bestKeyScore(tp: MemImage): Float = TravKeyData.list(tp).lastOrNull()?.score ?: 0f

    /** bestKey() @00961fac: last trav's chara & 0xff, 0 if none. */
    fun bestKey(tp: MemImage): Int = (TravKeyData.list(tp).lastOrNull()?.chara ?: 0) and 0xff

    /** probablyContains(char c, float minScore) @00961fc8: some trav with chara == c (full word
     * compare against the char register) and score > minScore. */
    fun probablyContains(tp: MemImage, c: Int, minScore: Float): Boolean =
        TravKeyData.list(tp).any { it.chara == c && fcmpgt(it.score, minScore) }

    /** sortTravs() @00962024: std::sort ascending by score (`a.score < b.score`). */
    fun sortTravs(tp: MemImage) {
        val travs = TravKeyData.list(tp)
        StdSort.sort(travs) { a, b -> fcmplt(a.score, b.score) }
        TravKeyData.store(tp, travs)
    }

    /** markOnFocus(bool f) @00960a4c: focused@0x170 = f; on a false->true change tFocus@0x128 = now. */
    fun markOnFocus(tp: MemImage, f: Boolean, now: Double) {
        val v = if (f) 1 else 0
        val old = tp.u8(0x170)
        if (v != old) {
            tp.setU8(0x170, v)
            if (f) tp.setF64(0x128, now)
        }
    }

    /** finaliseFocusTime() @00960a88: tFocus@0x128 < 0 (never focused) -> tEnd@0x148. */
    fun finaliseFocusTime(tp: MemImage) {
        val t = tp.f64(0x128)
        if (!(nintype.engine.SoftFloat.dcmpge(t, 0.0) || nintype.engine.SoftFloat.dcmpun(t, 0.0))) tp.setF64(0x128, tp.f64(0x148))
    }

    /** twerkConsume(MooTouchPoint& o) @0095ae10: this twerked (0x1a8 > 0.4), o within 32 of this
     * and not starting after this twerk's stop (o.0x128 > this.0x198 is false) -> o.0x1ad = 1, true. */
    fun twerkConsume(tp: MemImage, o: MemImage): Boolean {
        val a = tp.f32(0x1a8)
        if (fcmple(a, 0.4f) || fcmpun(a, 0.4f)) return false
        val d = Point2D(o.f32(0xdc), o.f32(0xe0)).lengthFrom(Point2D(tp.f32(0xdc), tp.f32(0xe0)))
        if (fcmpgt(d, 32f)) return false
        if (nintype.engine.SoftFloat.dcmpgt(o.f64(0x128), tp.f64(0x198))) return false
        o.setU8(0x1ad, 1)
        return true
    }

    /** getAssocCKPosElseDriftpos(char c) @0096114c: position of the first associated CK
     * (vector 0x334, chara@+8 == c), else this point's pos. */
    fun getAssocCKPosElseDriftpos(tp: MemImage, c: Int): Point2D {
        for (t in TravKeyData.list(tp, 0x334)) if (t.chara == c) return Point2D(t.x, t.y)
        return Point2D(tp.f32(0xdc), tp.f32(0xe0))
    }

    /** associateCK(TravKeyData t) @00961198: replace the first entry of vector 0x334 with the
     * same chara when its score is lower (`!(old >= new)`; unordered keeps the old one); append t if none. */
    fun associateCK(tp: MemImage, t: TravKeyData) {
        val l = TravKeyData.list(tp, 0x334)
        for ((i, o) in l.withIndex()) {
            if (o.chara == t.chara) {
                if (!(nintype.engine.SoftFloat.fcmpge(o.score, t.score) || fcmpun(o.score, t.score))) {
                    l[i] = t.copy(); TravKeyData.store(tp, l, 0x334)
                }
                return
            }
        }
        l.add(t.copy())
        TravKeyData.store(tp, l, 0x334)
    }

    /** syncStatesOnRelease(MooTouchPoint* prev, Moopad*) @0095ccf4: when the touch is lifted.
     * dir@0x114 = normalise(pos - prev.pos) (also into a slowdown prev). End point (0x1000) that
     * is not a start: flag 0x200 when it lasted < 0.025 s (0x148 - 0x138) and is < 0.8 key units
     * from prev. Otherwise: displacement@0x234 < 0.025 -> flag 0x400 + syncScores (a start point
     * gets 0x200); a start point that lasted < 0.025 s (0x140 - 0x138) -> 0x200. */
    fun syncStatesOnRelease(tp: MemImage, prev: MemImage?, unit: Float) {
        val pos = Point2D(tp.f32(0xdc), tp.f32(0xe0))
        if (prev != null) {
            val d = (pos - Point2D(prev.f32(0xdc), prev.f32(0xe0))).normalise()
            tp.setF32(0x114, d.x); tp.setF32(0x118, d.y)
            if (prev.u8(0x225) and 8 != 0) { prev.setF32(0x114, d.x); prev.setF32(0x118, d.y) }
        }
        val fl = tp.u16(0x224)
        val d0025f = Bits.bitsToDouble(0x3f999999a0000000L)
        val d0025 = Bits.bitsToDouble(0x3f9999999999999aL)
        if (fl and 0x1000 != 0) {
            if (fl and 0x800 != 0) return
            requireNotNull(prev) { "syncStatesOnRelease: end point without prev (the binary asserts, then reads it)" }
            val t = dsub(tp.f64(0x148), tp.f64(0x138))
            if (nintype.engine.SoftFloat.dcmpge(t, d0025f) || nintype.engine.SoftFloat.dcmpun(t, d0025f)) return
            val u = nintype.engine.emkey.Moopad.unitDist(Point2D(prev.f32(0xdc), prev.f32(0xe0)), pos, unit)
            if (!(nintype.engine.SoftFloat.fcmpge(u, 0.8f) || fcmpun(u, 0.8f))) tp.setU16(0x224, fl or 0x200)
            return
        }
        if (nintype.engine.SoftFloat.dcmplt(nintype.engine.SoftFloat.f2d(tp.f32(0x234)), d0025)) {
            if (fl and 0x800 == 0) { tp.setU16(0x224, fl or 0x400); syncScores(tp); return }
            tp.setU16(0x224, fl or 0x200)
            return
        }
        if (fl and 0x800 == 0) return
        if (nintype.engine.SoftFloat.dcmplt(dsub(tp.f64(0x140), tp.f64(0x138)), d0025)) tp.setU16(0x224, fl or 0x200)
    }

    /** syncCoverage(p1, p2, moopad) @0095cb88, both branches. Not detached: [MooTouchPoint.syncCoverage]
     * (coverage@0x370 = 1, per-trav coverage). Detached (flag 0x2000): coverage@0x370 =
     * calculate_coverage_score(p1, p1, board.clampCoveragePos(pos), mp) with the Moopad's
     * current board (Moopad+0x1f1ec); the travs are left alone. */
    fun syncCoverage(tp: MemImage, p1: Point2D, p2: Point2D, unit: Float, board: nintype.engine.geometry.MooBoard) {
        if (tp.u16(0x224) and 0x2000 == 0) return MooTouchPoint.syncCoverage(tp, p1, p2, unit)
        val c = board.clampCoveragePos(Point2D(tp.f32(0xdc), tp.f32(0xe0)))
        tp.setF32(0x370, nintype.engine.emkey.Calculate.coverageScore(p1, p1, c, unit))
    }

    /** takeForceTouchUpdate(TouchInfo const&, Moopad*) @0095b77c: with a force reading,
     * 0x23c = 1, 0x240 = translateForceval(force), 0x244 = translateForceval(force2). */
    fun takeForceTouchUpdate(tp: MemImage, ti: ForceInfo, mp: MemImage) {
        if (ti.hasForce == 0) return
        tp.setU8(0x23c, 1)
        tp.setF32(0x240, nintype.engine.geometry.MoopadGeometry.translateForceval(mp, ti.force))
        tp.setF32(0x244, nintype.engine.geometry.MoopadGeometry.translateForceval(mp, ti.force2))
    }

    /** considerSlideVSHist's static curves: dt -> weight, hybrid distance -> weight. */
    val SLIDEVS_DT = nintype.engine.math.FastCurveMapper()
        .push(1.5f, 0f).push(1f, Bits.bitsToFloat(0x3ecccccd)).push(0.5f, Bits.bitsToFloat(0x3f333333))
        .push(Bits.bitsToFloat(0x3e99999a), Bits.bitsToFloat(0x3f666666)).push(Bits.bitsToFloat(0x3e4ccccd), 1f).finalize()
    val SLIDEVS_DIST = nintype.engine.math.FastCurveMapper()
        .push(5f, 0.5f).push(3f, Bits.bitsToFloat(0x3f333333)).push(2f, 1f).finalize()

    /** considerSlideVSHist(bool sameFinger, MooTouchPoint& o, Moopad*) @009612fc: when both points
     * are important (0x34d) and o came 0..1 s before this one, appends a SlideVSTarget {o,
     * SLIDEVS_DT(dt) * SLIDEVS_DIST(hybridDist(pos, o.pos)) [* 0.5 when !sameFinger]} to the
     * vector at 0x3c. [oRef] is how the element names o (the JSON of its pointer field). */
    fun considerSlideVSHist(tp: MemImage, same: Boolean, o: MemImage, oRef: nintype.engine.vectors.JsonValue, hybridUnit: Float) {
        if (o.u8(0x34d) == 0 || tp.u8(0x34d) == 0) return
        val dt = d2f(dsub(tp.f64(0x128), o.f64(0x128)))
        if (fcmplt(dt, 0f) || fcmpgt(dt, 1f)) return
        val hd = nintype.engine.emkey.Moopad.hybridDist(Point2D(tp.f32(0xdc), tp.f32(0xe0)), Point2D(o.f32(0xdc), o.f32(0xe0)), hybridUnit)
        var v = fmul(SLIDEVS_DT.get(dt), SLIDEVS_DIST.get(hd))
        if (!same) v = fmul(v, 0.5f)
        tp.seq(0x3c).add(nintype.engine.vectors.Values.struct("@0x0:target" to oRef,
            "@0x4:mult" to nintype.engine.vectors.Leaf.f32(v).toJson()))
    }

    /** getPlanarCoverage(int i) @0095d658 / getOtherPlanarCoverage(int i) @0095d614: element i - 1
     * of the PlanarCoverage vector at 0xa0 / 0xac (40-byte elements: sizeof 0x24 padded to 8), as an index (-1 = null when
     * i < 1 or past the end). */
    fun planarCoverageIndex(tp: MemImage, other: Boolean, i: Int): Int {
        if (i < 1) return -1
        val n = tp.seq(if (other) 0xac else 0xa0).size
        return if (Integer.compareUnsigned(i - 1, n) < 0) i - 1 else -1
    }

    /** receiveNTT(NextTouchTarget& t) @0095c884: a NextTouchTarget (12 bytes; +4 its MooTouchPoint
     * [p]) is filed under same-finger (vector 0x48, earliest time 0x58) or other-finger (0x60,
     * earliest 0x70) by p's finger 0x21c; the first one sets the time, later ones keep the
     * minimum (`old < t ? old : t`, so NaN takes t). [t] is appended as given (its JSON). */
    fun receiveNTT(tp: MemImage, t: nintype.engine.vectors.JsonValue, p: MemImage) {
        val same = p.u8(0x21c) == tp.u8(0x21c)
        val vo = if (same) 0x48 else 0x60
        val to = if (same) 0x58 else 0x70
        val v = tp.seq(vo)
        val pt = p.f64(0x128)
        if (v.isEmpty()) tp.setF64(to, pt)
        else { val o = tp.f64(to); if (!nintype.engine.SoftFloat.dcmplt(o, pt)) tp.setF64(to, pt) }
        v.add(t)
    }
}
