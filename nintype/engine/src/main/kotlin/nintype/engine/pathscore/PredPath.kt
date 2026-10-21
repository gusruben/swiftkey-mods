package nintype.engine.pathscore

import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dmul
import nintype.engine.emkey.Moopad
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.Point2D
import nintype.engine.math.LibM
import nintype.engine.vectors.MemImage

/**
 * `Emkey::PredPath` (sizeof 0x208): one candidate path of the best-first search. Ported over a
 * byte image of the object; field offsets and meanings are in LAYOUT.md (same directory).
 * Pointers are image refs (`pp.ref(0x148)` = the PathSearch); the steps vector at 0x108 is
 * read through [Vecs].
 */
object PredPath {
    const val SIZE = 0x208
    const val OFF_PS = 0x148
    const val OFF_STEPS = 0x108
    const val STEP_SIZE = 0x28
    const val OFF_FING1 = 0x178
    const val OFF_FING2 = 0x1c0

    private fun f(b: Int) = Float.fromBits(b)

    /** justVisited(MooTouchPoint*) @0081fa4c. */
    fun justVisited(pp: MemImage, tp: MemImage?): Boolean = pp.ref(0xf8) === tp

    /** isChildOf(PredPath*) @00821684: o.parentId == this.id. */
    fun isChildOf(pp: MemImage, o: MemImage): Boolean = o.i32(0x18) == pp.i32(0x14)

    /** operator<(PredPath const&) const @0082e3b4: by score (0x74). */
    fun lessThan(pp: MemImage, o: MemImage): Boolean = fcmplt(pp.f32(0x74), o.f32(0x74))

    /** pathsize() @008270bc. */
    fun pathsize(pp: MemImage): Int = pp.i8(0x2a)

    /** syncApostate() @008239d0: clears bit 5 of the u16 at 0x2f (written back bytewise). */
    fun syncApostate(pp: MemImage) {
        val v = (pp.u8(0x2f) or (pp.u8(0x30) shl 8)) and 0xffdf
        pp.setU8(0x2f, v and 0xff)
        pp.setU8(0x30, v ushr 8)
    }

    /** syncFutureCoverageHeuristics() @00820af8. */
    fun syncFutureCoverageHeuristics(pp: MemImage) = pp.setF32(0xcc, 1f)

    /** determineLanguageCompatibilityScore() @00824c44 (stubbed in this build). */
    fun determineLanguageCompatibilityScore(@Suppress("UNUSED_PARAMETER") pp: MemImage): Float = 1f

    /** syncCharMarkovMult() @008244ec and traceVisiLog() @00827c1c: empty in this build. */
    fun syncCharMarkovMult(@Suppress("UNUSED_PARAMETER") pp: MemImage) {}
    fun traceVisiLog(@Suppress("UNUSED_PARAMETER") pp: MemImage) {}

    /** handleGraphAreaTapRelease(TouchInfo const&, Point2D const&) @0081f788. */
    fun handleGraphAreaTapRelease(): Boolean = false

    /** markFinisher(TWRet) @00823968: the TWRet's two words go to 0x20/0x24. */
    fun markFinisher(pp: MemImage, w0: Int, w1: Int) { pp.setI32(0x20, w0); pp.setI32(0x24, w1) }

    private fun countStepFlag(pp: MemImage, bit: Int): Int =
        Vecs.elems(pp, OFF_STEPS).sumOf { (it.u16(0x1e) ushr bit) and 1 }

    /** importantPointCount() @00827270 / pseudoImportantPointCount() @008272a4: steps with
     * step flag bit 3 / bit 4 set. */
    fun importantPointCount(pp: MemImage): Int = countStepFlag(pp, 3)
    fun pseudoImportantPointCount(pp: MemImage): Int = countStepFlag(pp, 4)

    /** fing(int) @00822358: finger 2 -> 0x1c0, 1 (and anything else, after an assert) -> 0x178.
     * Returns the FingerPathData's offset in this. */
    fun fing(@Suppress("UNUSED_PARAMETER") pp: MemImage, finger: Int): Int = if (finger == 2) OFF_FING2 else OFF_FING1

    /** otherfing(int) @008224f8: finger 2 -> 0x178, otherwise 0x1c0. */
    fun otherfing(@Suppress("UNUSED_PARAMETER") pp: MemImage, finger: Int): Int = if (finger == 2) OFF_FING1 else OFF_FING2

    /** lastfing(int) @00820cd0 / lastotherfing(int) @00820cec: by lastFinger (0x174) when set. */
    fun lastfing(pp: MemImage, finger: Int): Int = fing(pp, pp.i8(0x174).let { if (it != 0) it else finger })
    fun lastotherfing(pp: MemImage, finger: Int): Int = otherfing(pp, pp.i8(0x174).let { if (it != 0) it else finger })

    /** updateHeuristicsSubscore(float old, float new) @008244f0: score *= new/old, and the
     * running minimum (0x70) takes the lower of the old and new scores. */
    fun updateHeuristicsSubscore(pp: MemImage, old: Float, new: Float) {
        val q = fdiv(new, old)
        val s = pp.f32(0x74)
        var m = pp.f32(0x70)
        val ns = fmul(q, s)
        pp.setF32(0x74, ns)
        if (fcmplt(s, m)) m = s
        if (fcmplt(ns, m)) m = ns
        pp.setF32(0x70, m)
    }

    /** maybePrune(float cutoff, FunnelStat&) @00824550: true (pruned) when score <
     * max(cutoff, 2.0); counts into the FunnelStat at `fsOff` of `fs`. */
    fun maybePrune(pp: MemImage, cutoff: Float, fs: MemImage, fsOff: Int = 0): Boolean {
        FunnelStat.inc(fs, fsOff)
        val thr = if (fcmpgt(cutoff, 2f)) cutoff else 2f
        val s = pp.f32(0x74)
        if (fcmpge(s, thr) || fcmpun(s, thr)) {
            val c = FunnelStat.succ(fs, fsOff); fs.setI32(c, fs.i32(c) + 1)
            return false
        }
        val c = FunnelStat.fail1(fs, fsOff); fs.setI32(c, fs.i32(c) + 1)
        return true
    }

    /** syncPointRevisitOn(MooTouchPoint*) @0081f790: over the last four steps, counts steps on
     * the same point (0x2d) and on the same key (0x2e, point+0x308). Byte counters wrap. */
    fun syncPointRevisitOn(pp: MemImage, tp: MemImage) {
        val steps = Vecs.elems(pp, OFF_STEPS)
        var k = steps.size - 1
        var n = 0
        while (k >= 0) {
            val sp = steps[k].ref(0x18)!!
            if (sp === tp) pp.setU8(0x2d, (pp.u8(0x2d) + 1) and 0xff)
            if (sp.i32(0x308) == tp.i32(0x308)) pp.setU8(0x2e, (pp.u8(0x2e) + 1) and 0xff)
            if (++n > 3) break
            k--
        }
    }

    private fun revisitTier(n: Int, t1: Int, t2: Int, t3: Int, rest: Int): Float = when (n) {
        0 -> 1f; 1 -> f(t1); 2 -> f(t2); 3 -> f(t3); else -> f(rest)   // unsigned compare: negatives -> rest
    }

    /** syncPointRevisitMult() @00820bc0 -> 0xd4. */
    fun syncPointRevisitMult(pp: MemImage) {
        val ps = pp.ref(OFF_PS)!!
        val same = pp.i8(0x2d)
        var r = revisitTier(same, 0x3f7ae148, 0x3f75c28f, 0x3f6b851f, 0x3f63d70a)        // .98 .96 .92 / .89
        if (ps.u8(0x330) == 0) r = fmul(r, revisitTier(pp.i8(0x2e), 0x3f70a3d7, 0x3f666666, 0x3f5c28f6, 0x3f570a3d)) // .94 .9 .86 / .84
        pp.setF32(0xd4, r)
        val touches = Vecs.size(ps, 0x1bc)
        if (touches.toLong() and 0xffffffffL > 2) return
        if (same < 1) return
        pp.setF32(0xd4, fmul(r, f(0x3f47ae14)))  // .78
    }

    /** hasVisited(MooTouchPoint*) @0082d25c: visited-set bit (0xf0) of the point's visitation
     * index (0x308 when point+0x34d is set, else 0x304). */
    fun hasVisited(pp: MemImage, tp: MemImage): Boolean =
        Flag64.flagged(pp, 0xf0, tp.i32(if (tp.u8(0x34d) != 0) 0x308 else 0x304))

    /** syncClassicSlideVSCache() @00821344: 0xe0 = mean(0xd8)^3.8. */
    fun syncClassicSlideVSCache(pp: MemImage) = pp.setF32(0xe0, LibM.powf(WeightedFloats.mean(pp, 0xd8), f(0x40733333)))

    /** syncNeoSlideVSCache() @0082136c: 0xec = mean(0xe4)^3.6. */
    fun syncNeoSlideVSCache(pp: MemImage) = pp.setF32(0xec, LibM.powf(WeightedFloats.mean(pp, 0xe4), f(0x40666666)))

    /** registerSlideVsMult(SlideVSArg&, bool, bool, float, float) @0082cae8: when b1 && !b2,
     * adds (v2, arg.weight) to the neo (arg.neo) or classic slideVS accumulator. v1 is unused. */
    fun registerSlideVsMult(pp: MemImage, arg: MemImage, b1: Boolean, b2: Boolean, @Suppress("UNUSED_PARAMETER") v1: Float, v2: Float) {
        if (!b1 || b2) return
        WeightedFloats.add(pp, if (arg.u8(0x50) != 0) 0xe4 else 0xd8, v2, arg.f32(0x54))
    }

    /** A function-local static FastCurveMapper: points pushed in the binary's order, then
     * finalize() (sorts). Args are (x bits, y bits) pairs. */
    internal fun curve(vararg b: Int): FastCurveMapper {
        val c = FastCurveMapper()
        for (i in b.indices step 2) c.pushBits(b[i], b[i + 1])
        return c.finalize()
    }

    /** multForCharSkip: {0->1, .1->.85, 1/7->.75, 1/6->.6, .2->.4, .33->.2, .5->.05, .6->0}. */
    private val CHAR_SKIP = curve(0x3f19999a, 0, 0x3f000000, 0x3d4ccccd, 0x3ea8f5c3, 0x3e4ccccd, 0x3e4ccccd, 0x3ecccccd,
        0x3e2aaaab, 0x3f19999a, 0x3e124925, 0x3f400000, 0x3dcccccd, 0x3f59999a, 0, 0x3f800000)

    /** multForCharSkip(int skips, float len) @00823f64: curve(skips/len), x0.35 in literal-tap
     * mode (PathSearch+0x35a) when skips != 0. */
    fun multForCharSkip(pp: MemImage, skips: Int, len: Float): Float {
        val r = CHAR_SKIP.get(fdiv(i2f(skips), len))
        if (skips != 0 && pp.ref(OFF_PS)!!.u8(0x35a) != 0) return fmul(r, f(0x3eb33333))
        return r
    }

    private val NOVISIT_WPM = curve(0x42480000, 0x3f800000, 0x42f00000, 0x3f19999a)                         // {50->1, 120->.6}
    private val NOVISIT_KPS = curve(0x40a00000, 0x3f800000, 0x41000000, 0x3f000000, 0x41400000, 0x3e4ccccd) // {5->1, 8->.5, 12->.2}
    private val NOVISIT_LEN = curve(0x40000000, 0, 0x40800000, 0x3f000000, 0x40c00000, 0x3f800000)         // {2->0, 4->.5, 6->1}

    /** syncBalancedNovisitMult() @0082465c: 0x9c = acc(0x98) ^ (PS.0x364 * wpm(PS.0x344) *
     * ((1-c) + kps(PS.0x144)*c)), c = len(pathsize). */
    fun syncBalancedNovisitMult(pp: MemImage) {
        val ps = pp.ref(OFF_PS)!!
        val a = NOVISIT_WPM.get(ps.f32(0x344))
        val b = NOVISIT_KPS.get(ps.f32(0x144))
        val c = NOVISIT_LEN.get(i2f(pp.i8(0x2a)))
        val e = fmul(ps.f32(0x364), fmul(a, fadd(fsub(1f, c), fmul(b, c))))
        pp.setF32(0x9c, LibM.powf(pp.f32(0x98), e))
    }

    private val LOOKBACK_WPM = curve(0x428c0000, 0x3e051eb8, 0x42f00000, 0x3e8f5c29)  // {70->.13, 120->.28}
    private val LOOKBACK_W = curve(0x40000000, 0, 0x40a00000, 0x3f800000)            // {2->0, 5->1}

    /** getBackwardsVisitationLookback(float t) @0082d950 (seconds): w = {2->0,5->1}(PS soft
     * index at t - PS.t0), result = wpmCurve(PS.0x344)*w + (1-w)*0.13. */
    fun getBackwardsVisitationLookback(pp: MemImage, t: Float): Float {
        val ps = pp.ref(OFF_PS)!!
        val a = LOOKBACK_WPM.get(ps.f32(0x344))
        val si = Curves.curveMapperAt(ps, 0x294).get(d2f(dsub(f2d(t), ps.f64(0x168))))
        val w = LOOKBACK_W.get(si)
        val r6 = fmul(fsub(1f, w), f(0x3e051eb8))
        return fadd(fmul(a, w), r6)
    }

    private val PPSCK = curve(0x44160000, 0x3f800000, 0x447a0000, 0x3f000000, 0x44af0000, 0x3dcccccd, 0x44ed8000, 0) // {600->1, 1000->.5, 1400->.1, 1900->0}

    /** determinePPSCKScoreMult() @00825ec0: mean over steps of (detached point, point+0xd0 == 0 ?
     * curve(point+0x22c) : 1), ^0.4; 1 with no steps. */
    fun determinePPSCKScoreMult(pp: MemImage): Float {
        val steps = Vecs.elems(pp, OFF_STEPS)
        if (steps.isEmpty()) return 1f
        var sum = 0f
        for (st in steps) {
            val tp = st.ref(0x18)!!
            val v = if (tp.u8(0x225) and 0x20 != 0 && tp.u8(0xd0) == 0) PPSCK.get(tp.f32(0x22c)) else 1f
            sum = fadd(sum, v)
        }
        return LibM.powf(fdiv(sum, nintype.engine.SoftFloat.ui2f(steps.size)), f(0x3ecccccd))
    }

    private val SVS_NEO_W = curve(0x3ecccccd, 0x3dcccccd, 0x3f800000, 0x3f333333, 0x3fc00000, 0x3f800000)   // {.4->.1, 1->.7, 1.5->1}
    private val SVS_TOL = curve(0x3e800000, 0x420c0000, 0x3f000000, 0x41e00000, 0x3f800000, 0x41800000,
        0x40000000, 0x40e00000, 0x40400000, 0x40800000)                                                        // {.25->35, .5->28, 1->16, 2->7, 3->4} deg
    private val SVS_EXP = curve(0x3e800000, 0x3dcccccd, 0x3f000000, 0x3e4ccccd, 0x3f800000, 0x3f000000,
        0x40000000, 0x3f4ccccd, 0x40400000, 0x3f800000)                                                        // {.25->.1, .5->.2, 1->.5, 2->.8, 3->1}
    private val PI_D = Double.fromBits(0x400921fb54442d18L)

    private fun MemImage.pt(o: Int) = Point2D(f32(o), f32(o + 4))
    private fun absf(x: Float) = Float.fromBits(x.toRawBits() and 0x7fffffff)
    private fun negf(x: Float) = Float.fromBits(x.toRawBits() xor Int.MIN_VALUE)

    /** maybeRecordSlideVSPenalty(Moopad*, SlideVSArg&, float& hard) @0082c074: the slide
     * direction check (research doc "SlideVS"). Adds (score, arg.weight) to the classic (0xd8)
     * or neo (0xe4) accumulator, or for a reversed classic slide adds 10*weight to `hard`
     * (image `hardImg` at `hardOff`). Returns early (after an assert log) when |keyDiff| < 4. */
    fun maybeRecordSlideVSPenalty(pp: MemImage, mp: MemImage, arg: MemImage, hardImg: MemImage, hardOff: Int) {
        val a = arg.pt(0x1c); val b = arg.pt(0x24); val k = arg.pt(0x3c)
        val lA = a.length(); val lB = b.length(); val lK = k.length()
        if (!(fcmpge(lK, 4f) || fcmpun(lK, 4f))) return
        val neo = arg.u8(0x50) != 0
        if (neo) {
            val u = Moopad.unitDist(a, mp.f32(Moopad.OFF_UNIT))
            arg.setF32(0x54, fmul(SVS_NEO_W.get(u), arg.f32(0x54)))
        }
        val unit = mp.f32(Moopad.OFF_UNIT)
        val m = fmul(unit, 1.5f)
        val a2 = if (fcmplt(lA, m)) m else lA
        val k2 = if (fcmplt(lK, m)) m else lK
        val dA = fsub(a2, k2)
        val b2 = if (fcmplt(lB, m)) m else lB
        val dB = fsub(b2, k2)
        val pick = if (fcmpgt(absf(dA), absf(dB))) b2 else a2
        var r = fdiv(pick, k2)
        val lenScore = if (!(fcmpge(r, 1f) || fcmpun(r, 1f))) LibM.powf(r, f(0x3dcccccd)) else {
            r = fsub(2f, r)
            val r6 = if (fcmplt(r, 0f)) 0f else if (fcmpgt(r, 1f)) 1f else r
            LibM.powf(r6, f(0x3dcccccd))
        }
        arg.setF32(0x6c, lenScore)
        val acc = if (neo) 0xe4 else 0xd8
        if (!(fcmpge(lA, 4f) || fcmpun(lA, 4f))) {
            WeightedFloats.add(pp, acc, fmul(lenScore, f(0x3ecccccd)), arg.f32(0x54))
            return
        }
        val cA = fdiv(Point2D.dot(a, k), fmul(lA, lK))
        val cB = fdiv(Point2D.dot(b, k), fmul(lB, lK))
        val c = if (fcmpgt(cB, cA)) cB else cA
        arg.setF32(0x5c, c)
        val dU = fdiv(lA, unit)
        val cl = if (fcmplt(c, -1f)) -1f else if (fcmpgt(c, 1f)) 1f else c
        val theta = d2f(ddiv(f2d(fmul(LibM.acosf(cl), 180f)), PI_D))
        val tol = SVS_TOL.get(dU)
        arg.setF32(0x64, tol); arg.setF32(0x58, dU); arg.setF32(0x60, theta)
        val d = fsub(theta, tol)
        val over = if (fcmplt(d, 0f)) 0f else fmul(d, f(0x3f400000))
        val eff = fadd(fmul(theta, f(0x3e800000)), over)
        arg.setF32(0x68, eff)
        val cs = LibMTrig.cosf(d2f(ddiv(dmul(f2d(eff), PI_D), 180.0)))
        if (!(fcmpge(c, 0f) || fcmpun(c, 0f))) {
            val w = arg.f32(0x54)
            if (neo) WeightedFloats.add(pp, 0xe4, fmul(lenScore, f(0x3e4ccccd)), w)
            else hardImg.setF32(hardOff, fadd(hardImg.f32(hardOff), fmul(w, 10f)))
            return
        }
        arg.setF32(0x70, LibM.powf(c, f(0x40133333)))
        val sp = if (!(fcmpge(cs, 0f) || fcmpun(cs, 0f))) negf(LibM.powf(negf(cs), f(0x40133333))) else LibM.powf(cs, f(0x40133333))
        arg.setF32(0x74, sp)
        val v = fmul(lenScore, LibM.powf(sp, SVS_EXP.get(dU)))
        WeightedFloats.add(pp, acc, v, arg.f32(0x54))
    }

    private val NOT_LONG = curve(0x3f800000, 0x3f800000, 0x40000000, 0x3f6e147b, 0x40400000, 0x3f59999a,
        0x40600000, 0x3f000000, 0x40a00000, 0x3e99999a, 0x41200000, 0x3dcccccd)   // {1->1, 2->.93, 3->.85, 3.5->.5, 5->.3, 10->.1}

    /** calculateNotLongEnoughMultpen(int len, bool debug) @00824144: how many keypoints the word
     * (of potential length `len`) leaves unexplained, d = (1 - (0x38+1)/PS.0x144)*PS.0x144 -
     * (len - 0x2b) - PS.0x160, clamped at 0, through a falloff curve. `debug` only logs. */
    fun calculateNotLongEnoughMultpen(pp: MemImage, len: Int, @Suppress("UNUSED_PARAMETER") debug: Boolean): Float {
        val ps = pp.ref(OFF_PS)!!
        val kp = ps.f32(0x144)
        val frac = fdiv(fadd(pp.f32(0x38), 1f), kp)
        val left = fmul(fsub(1f, frac), kp)
        var d = fsub(fsub(left, i2f(len - pp.i8(0x2b))), i2f(ps.i32(0x160)))
        if (!(fcmpge(d, 0f) || fcmpun(d, 0f))) d = 0f
        return NOT_LONG.get(d)
    }

    /** calculateLastCharSameLetterSamePointPenalty() @00825c38: for a doubled letter on one
     * point (flag 0x2f bit 3; skipped when the Moopad double-letter Xnl, +0x7fa4, is on):
     * m = 0.56 for a vowel, else 0.86; result r + m(1-r), r = visit-representative point's +0x16c. */
    fun calculateLastCharSameLetterSamePointPenalty(pp: MemImage): Float {
        val mp = pp.ref(OFF_PS)!!.ref(0x110)!!
        if (nintype.engine.merge.Xnl.val_(mp, 0x7fa4) != 0) return 1f
        if (pp.u8(0x2f) and 8 == 0) return 1f
        val fo = pp.i8(0x174).let { if (it != 0) fing(pp, it) else OFF_FING1 }
        val ch = pp.u8(fo + 0x15).let { if (it in 97..122) it - 32 else it } - 0x41   // toupper
        val m = if (ch in 0..0x14 && (0x104111 ushr ch) and 1 != 0) f(0x3f0f5c29) else f(0x3f5c28f6)
        val pt = pp.ref(fo + 0x40) ?: return m
        val v = if (pt.u8(0x34d) != 0) pt else (pt.ref(0xc) ?: return m)   // (null: assert)
        val r = v.f32(0x16c)
        return fadd(r, fmul(m, fsub(1f, r)))
    }

    /** getFuturePlanarCoveragePoints(MooTouchPoint*& a, MooTouchPoint*& b) @00820480: the last
     * points of the current (lastFinger, else finger 1) and other finger's FingerPathData (+0x40).
     * Null current -> (null, null), false. Else a = current; b = the other one when its +0xc0
     * time is later than current's +0xb8, otherwise current. Returns true. */
    fun getFuturePlanarCoveragePoints(pp: MemImage): Triple<Boolean, MemImage?, MemImage?> {
        val lf = pp.i8(0x174)
        val a = if (lf != 0) fing(pp, lf) else OFF_FING1
        val b = if (lf != 0) otherfing(pp, lf) else OFF_FING2
        val p = pp.ref(a + 0x40) ?: return Triple(false, null, null)
        val q = pp.ref(b + 0x40)
        return if (q != null && nintype.engine.SoftFloat.dcmpgt(q.f64(0xc0), p.f64(0xb8))) Triple(true, p, q) else Triple(true, p, p)
    }
}
