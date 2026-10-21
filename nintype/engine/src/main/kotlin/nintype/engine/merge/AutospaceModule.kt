package nintype.engine.merge

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.i2f
import nintype.engine.emkey.Moopad
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * `Emkey::AutospaceModule` (0x3a8 bytes, embedded at Moopad+0x8074): word-boundary timing.
 * Ported over a byte image ([MemImage]); `am.ptr(0)` is the owning Moopad image.
 *
 *   0x000 Moopad*                          0x004 Roen::DrawTriggers (UI)
 *   0x2fc AutospaceSplitoffStats (0x34)    0x330 f32 dynMult (ChasingFloat value, 1.0 at rest;
 *                                                lowered by onDetectEarlySplit)
 *   0x334 f32 dynMultFloor (1.0)           0x338 f32 (1.0)
 *   0x33c Xnl, 0x370 Xnl (UI hint state)   0x3a4 u8 peckLocked (peckLock sets it; backspace /
 *                                                upload clear it; timeUntilAutospaceMerge -> 999)
 *
 * Moopad fields: see [MergeMoopad]. `ms` below is settings_autospace_timed_millsecs
 * (Moopad+0x7f9c, default 250) and `base = i2f(ms) / 1000`.
 */
object AutospaceModule {
    const val SIZE = 0x3a8
    const val DYN_MULT = 0x330
    const val DYN_MULT_FLOOR = 0x334
    const val PECK_LOCKED = 0x3a4
    const val SPLITOFF = 0x2fc

    private fun f(b: Int) = Bits.bitsToFloat(b)
    private val F0_67 = f(0x3f2b851f)
    private val F0_85 = f(0x3f59999a)
    private val F0_45 = f(0x3ee66666)
    private val F0_05 = f(0x3d4ccccd)
    /** -0.14f widened to double (0xbfc1eb85_20000000). */
    private val D_M0_14 = Bits.bitsToDouble(0xbfc1eb8520000000uL.toLong())

    private fun mp(am: MemImage) = am.ptr(0) ?: am.ref(0) ?: error("AutospaceModule: no Moopad")

    private fun base(mp: MemImage): Float = fdiv(i2f(MergeMoopad.autospaceMs(mp)), 1000f)

    /** {0.3->0.05, 0.7->0.023, 1.2->0.01, 1.5->0}: calculateTimingReductionBetweenTouches's static curve. */
    val TIMING_REDUCTION = FastCurveMapper.ofBits(
        0x3e99999a, 0x3d4ccccd, 0x3f333333, 0x3cbc6a7f, 0x3f99999a, 0x3c23d70a, 0x3fc00000, 0x00000000)

    /** staticAutospaceTimedMergeTime() @00ee5c64: base. */
    fun staticAutospaceTimedMergeTime(am: MemImage): Float = base(mp(am))

    /** dynamicAutospaceTimedMergeTime() @00ee5d40: base * dynMult. */
    fun dynamicAutospaceTimedMergeTime(am: MemImage): Float = fmul(base(mp(am)), am.f32(DYN_MULT))

    /** The "peck typing sympathy" window shared by absoluteAutospaceTimeLimit, touchStayTime and
     * automergeAllowanceCheck: unless USF 0x89, max(dyn, min(dyn + 0.25, 0.67)) with the
     * compiled compare order (a > 0.67 -> 0.67, then a < dyn -> dyn; NaN passes through). */
    fun sympathyLimit(mp: MemImage, dyn: Float): Float {
        if (MergeMoopad.hasUSF1(mp, 0x89) != 0) return dyn
        var a = fadd(dyn, 0.25f)
        if (fcmpgt(a, F0_67)) a = F0_67
        if (fcmplt(a, dyn)) a = dyn
        return a
    }

    /** absoluteAutospaceTimeLimit() @00ee5d78. */
    fun absoluteAutospaceTimeLimit(am: MemImage): Float {
        val mp = mp(am)
        return sympathyLimit(mp, fmul(base(mp), am.f32(DYN_MULT)))
    }

    /** touchStayTime() @00ee5e10: absoluteAutospaceTimeLimit, + 7 s when USF 0x8f == 1
     * (USF 0x8f is read first, before the window). */
    fun touchStayTime(am: MemImage): Float {
        val mp = mp(am)
        val stay = MergeMoopad.hasUSF1(mp, 0x8f)
        val v = sympathyLimit(mp, fmul(base(mp), am.f32(DYN_MULT)))
        return if (stay == 1) fadd(v, 7f) else v
    }

    /** calculateTimingReductionBetweenTouches(float x, int n) @00ee5ed4: the distance credit.
     * 0 under USF 0xd8; else (curve(x) + (x < 0.45 && n == 1 ? 0.05 : 0)) * (base * 4). */
    fun calculateTimingReductionBetweenTouches(am: MemImage, x: Float, n: Int): Float {
        val mp = mp(am)
        if (MergeMoopad.hasUSF1(mp, 0xd8) != 0) return 0f
        val far = fcmpge(x, F0_45) || fcmpun(x, F0_45)
        var v = TIMING_REDUCTION.get(x)
        if (!far && n == 1) v = fadd(v, F0_05)
        return fmul(v, fmul(base(mp), 4f))
    }

    /** onDetectEarlySplit(float gap) @00ee5c90: unless USF 0x7c, m = gap / base * 0.85;
     * dynMult = min(dynMult, m) and dynMultFloor = min(dynMultFloor, m) (m < old -> m). */
    fun onDetectEarlySplit(am: MemImage, gap: Float) {
        val mp = mp(am)
        if (MergeMoopad.hasUSF1(mp, 0x7c) != 0) return
        val m = fmul(fdiv(gap, base(mp)), F0_85)
        var a = am.f32(DYN_MULT)
        if (fcmplt(m, a)) a = m
        am.setF32(DYN_MULT, a)
        var b = am.f32(DYN_MULT_FLOOR)
        if (fcmplt(m, b)) b = m
        am.setF32(DYN_MULT_FLOOR, b)
    }

    /** peckLock(std::string const&) @00ee66b4: peckLocked = 1 if it was 0 (the tag is unused). */
    fun peckLock(am: MemImage) {
        if (am.u8(PECK_LOCKED) == 0) am.setU8(PECK_LOCKED, 1)
    }

    /** onNonBufferBackspace() @00ee5d28 / onJustAfterUpload() @00ee5d34: peckLocked = 0. */
    fun clearPeckLock(am: MemImage) = am.setU8(PECK_LOCKED, 0)

    /** timeUntilAutospaceMerge() @00ee7614 (seconds left before the timed autospace commits;
     * drives the countdown UI). 0 unless slide mode and the board are active; 999 while
     * peck-locked; else d2f(f2d(base) - max(0, now - lastUpT - 0.14)) with now =
     * RoenGL::globaltimestamp (a NaN difference is kept). */
    fun timeUntilAutospaceMerge(am: MemImage, now: Double): Float {
        val mp = mp(am)
        if (MergeMoopad.slidemodeActive(mp) != 1) return 0f
        val board = mp.ptr(MergeMoopad.BOARD) ?: error("no MooBoard")
        if (MergeMoopad.boardIsActive(board, mp) != 1) return 0f
        if (am.u8(PECK_LOCKED) != 0) return 999f
        var d = dadd(dsub(now, mp.f64(MergeMoopad.LAST_UP_T)), D_M0_14)
        if (dcmplt(d, 0.0)) d = 0.0
        return d2f(dsub(f2d(base(mp)), d))
    }

    // --- word-boundary decisions ------------------------------------------------------------

    /** One touch of the sitting std::list<Mootouch> (Moopad+0x294c4; list node + 8 = Mootouch):
     * [flags] = Mootouch+0x26e (bit 3 = detached), [notPlainTap] = Mootouch+0xbc (nonzero = not a
     * plain tap), [isSimpleTap] = Mootouch::isSimpleTap (touch area). */
    class Sitting(val flags: Int, val notPlainTap: Int, val isSimpleTap: () -> Int) {
        val detached get() = flags and 8 != 0
        /** The loop test shared by every sitting-list walk: isSimpleTap() == 1 && +0xbc == 0. */
        fun plainTap(): Boolean = isSimpleTap() == 1 && notPlainTap == 0

        companion object {
            /** From the touch area's Mootouch model. */
            fun of(mt: nintype.engine.touch.Mootouch, env: nintype.engine.touch.TouchEnv) =
                Sitting(mt.body.u8(0x26e), mt.body.i32(0xbc)) { if (mt.isSimpleTap(env)) 1 else 0 }
        }
    }

    /** automergeAllowanceCheck(double t, Point2D const& pos, int n, bool tap, int x) @00ee60c8.
     * Returns 1 when the new touch must start a new word (the caller force-merges the open
     * buffers), else 0.
     *  - 0 unless Xnl 0x7fd8 == 1, slidemode == 1, Xnl 0x2b0f8 == 0, Xnl 0x2b12c == 0, not
     *    peck-locked, the sitting list is non-empty and has a non-detached touch;
     *  - dyn = base * dynMult; lim1 = sympathyLimit(dyn); lim2 = lim1 (+7 s when USF 0x8f == 1);
     *  - gap = d2f(t - lastUpT) - calculateTimingReductionBetweenTouches(unitDist(lastUpPos, pos), n);
     *  - gap > lim2 or gap > lim1 -> 1; gap < dyn -> 0; else 1 if any sitting touch is not a
     *    plain tap, else (x != 0) | !tap. */
    fun automergeAllowanceCheck(am: MemImage, t: Double, pos: Point2D, n: Int, tap: Int, x: Int, sitting: List<Sitting>): Int {
        val mp = mp(am)
        if (Xnl.val_(mp, 0x7fd8) != 1) return 0
        if (MergeMoopad.slidemodeActive(mp) != 1) return 0
        if (Xnl.val_(mp, 0x2b0f8) != 0) return 0
        if (Xnl.val_(mp, 0x2b12c) != 0 || am.u8(PECK_LOCKED) != 0) return 0
        if (sitting.isEmpty()) return 0
        if (sitting.none { !it.detached }) return 0
        val dyn = fmul(base(mp), am.f32(DYN_MULT))
        val lim1 = sympathyLimit(mp, dyn)
        val stay = MergeMoopad.hasUSF1(mp, 0x8f)
        var lim2 = sympathyLimit(mp, fmul(base(mp), am.f32(DYN_MULT)))
        if (stay == 1) lim2 = fadd(lim2, 7f)
        val raw = d2f(dsub(t, mp.f64(MergeMoopad.LAST_UP_T)))
        val lastPos = Point2D(mp.f32(LAST_UP_POS), mp.f32(LAST_UP_POS + 4))
        val d = Moopad.unitDist(lastPos, pos, mp.f32(Moopad.OFF_UNIT))
        val gap = nintype.engine.SoftFloat.fsub(raw, calculateTimingReductionBetweenTouches(am, d, n))
        if (fcmpgt(gap, lim2) || fcmpgt(gap, lim1)) return 1
        if (fcmplt(gap, dyn)) return 0
        for (s in sitting) if (!s.plainTap()) return 1
        return (if (x != 0) 1 else 0) or (tap xor 1)
    }

    const val LAST_UP_POS = 0x2b538

    /** newTouchShouldAutomerge(Mootouch& mt, double t, Point2D const& pos, int n) @00ee6588:
     * automergeAllowanceCheck(t, pos, n, mt.isSimpleTap(), mt+0xbc). [mtSimpleTap] is evaluated first. */
    fun newTouchShouldAutomerge(am: MemImage, mtNotPlainTap: Int, mtSimpleTap: () -> Int, t: Double, pos: Point2D, n: Int,
                                sitting: List<Sitting>): Int {
        val tap = mtSimpleTap()
        return automergeAllowanceCheck(am, t, pos, n, tap, mtNotPlainTap, sitting)
    }

    /** checkSittingTapness() @00ee65e0: every sitting touch is a plain tap (true when empty). */
    fun checkSittingTapness(sitting: List<Sitting>): Boolean = sitting.all { it.plainTap() }

    /** newTapIsProbablyForSlowPecking(bool tap, int x) @00ee640c: all sitting plain taps && tap && x == 0. */
    fun newTapIsProbablyForSlowPecking(sitting: List<Sitting>, tap: Boolean, x: Int): Boolean {
        for (s in sitting) if (!s.plainTap()) return false
        return x == 0 && tap
    }

    /** newTouchIsProbablyForSlowPecking(Mootouch& mt) @00ee663c: mt.isSimpleTap() is called first;
     * all sitting plain taps -> isSimpleTap(mt) & (mt+0xbc == 0). */
    fun newTouchIsProbablyForSlowPecking(sitting: List<Sitting>, mtNotPlainTap: Int, mtSimpleTap: () -> Int): Int {
        val tap = mtSimpleTap()
        for (s in sitting) if (!s.plainTap()) return 0
        return tap and (if (mtNotPlainTap == 0) 1 else 0)
    }

    // --- the per-frame timer ----------------------------------------------------------------

    /** What updateAutospaceTimedMerge does outside the AutospaceModule: Moopad::maybeFinishWord
     * and Moopad::turnOnTempAutospace (Moopad ui-glue, not ported here; the reason strings are
     * "autospace_timed"-style tags the callees only log). */
    interface TimedMergeHost {
        fun maybeFinishWord()
        fun turnOnTempAutospace()
    }

    const val HINT_XNL_A = 0x33c   // Xnl: elapsed < dyn (UI countdown state)
    const val HINT_XNL_B = 0x370   // Xnl: elapsed < sympathy limit
    private val F0_01 = f(0x3c23d70a)
    private val F0_004 = f(0x3b83126f)

    /** Xnl::operator=(bool) on an Xnl inside [img]: the value byte. The signal propagation it
     * also does (UI redraw triggers) has no decoder effect and is not modelled. */
    private fun setXnl(img: MemImage, off: Int, v: Boolean) = img.setU8(off + Xnl.VAL, if (v) 1 else 0)

    /** updateAutospaceTimedMerge() @00ee66c8, run once per frame (Moopad::update fires the
     * AutospaceModule's frame trigger). [now] = RoenGL::globaltimestamp, [dtFactor] =
     * RoenGL::globallastdtimefactor. Spec:
     *  - dynMult ChasingFloat (0x330) chases its target with frac 0.01, dt = d2f(dtFactor), speed 0.004;
     *  - return unless Moopad+0x29440 == 0, the sitting list is non-empty, Xnl 0x2b0f8, 0x2b12c,
     *    0x239f0 are 0, Suspensions 0x1fdd4 / 0x1fe34 are off (Xnl at +0x2c) and slidemode == 1;
     *  - peck-locked: unlock only when Xnl 0x295a8 == 1 and USF 0x96 == 0, else return;
     *  - el = max(0, now - lastUpT - 0.14); dyn = base * dynMult; lim = sympathyLimit(dyn);
     *    lim2 = sympathyLimit (+7 s when USF 0x8f == 1); Xnl 0x33c := el < dyn, Xnl 0x370 := el < lim;
     *  - peck rules (each vetoes the finish): unless Xnl 0x295a8, sitting count + 2 * splitGate
     *    (Moopad+0x2affc, zeroed under USF 0x92 with 0x295a8) >= settings_autospace_pause_minpecks
     *    (Moopad+0x7fa0, default 4) vetoes, and peck-locks when the flag (1, or Xnl 0x29610 under
     *    USF 0x92/0x95 with Xnl 0x295dc off; ANDed with USF 0x96 when 0x295a8) is 1; USF 0x90 with
     *    a nonzero gate, Xnl 0x29540 and Xnl 0x29644 peck-lock and veto; Xnl 0x29610 with
     *    Xnl 0x2b0c4 == 0 peck-locks and returns;
     *  - el > lim2 (as double) and no veto -> maybeFinishWord, turnOnTempAutospace. */
    fun updateAutospaceTimedMerge(am: MemImage, now: Double, dtFactor: Double, sittingCount: Int, host: TimedMergeHost) {
        ChasingFloatSpeedLimit.chase(am, DYN_MULT, F0_01, d2f(dtFactor), F0_004)
        val mp = mp(am)
        if (mp.i32(0x29440) != 0) return
        if (sittingCount == 0) return
        if (Xnl.val_(mp, 0x2b0f8) != 0 || Xnl.val_(mp, 0x2b12c) != 0 || Xnl.val_(mp, 0x239f0) != 0) return
        if (Xnl.val_(mp, 0x1fdd4 + 0x2c) != 0 || Xnl.val_(mp, 0x1fe34 + 0x2c) != 0) return
        if (MergeMoopad.slidemodeActive(mp) != 1) return
        if (am.u8(PECK_LOCKED) != 0) {
            if (Xnl.val_(mp, 0x295a8) != 1) return
            if (MergeMoopad.hasUSF1(mp, 0x96) != 0) return
            am.setU8(PECK_LOCKED, 0)
        }
        var el = dadd(dsub(now, mp.f64(MergeMoopad.LAST_UP_T)), D_M0_14)
        if (dcmplt(el, 0.0)) el = 0.0
        val dyn = fmul(base(mp), am.f32(DYN_MULT))
        val lim = sympathyLimit(mp, dyn)
        val stay = MergeMoopad.hasUSF1(mp, 0x8f)
        var lim2 = sympathyLimit(mp, fmul(base(mp), am.f32(DYN_MULT)))
        if (stay == 1) lim2 = fadd(lim2, 7f)
        setXnl(am, HINT_XNL_A, dcmplt(el, f2d(dyn)))
        setXnl(am, HINT_XNL_B, dcmplt(el, f2d(lim)))
        val a5 = Xnl.val_(mp, 0x295a8)
        var gate = mp.i32(0x2affc)
        if (MergeMoopad.hasUSF1(mp, 0x92) == 1 && a5 != 0) gate = 0
        var flag = 1
        if (a5 == 0 && MergeMoopad.hasUSF1(mp, 0x92) == 1 &&
            MergeMoopad.hasUSF1(mp, 0x95) == 1 && Xnl.val_(mp, 0x295dc) == 0) flag = Xnl.val_(mp, 0x29610)
        if (Xnl.val_(mp, 0x295a8) == 1) flag = flag and MergeMoopad.hasUSF1(mp, 0x96)
        var allow = true
        if (a5 == 0) {
            if (sittingCount + gate * 2 < mp.i32(0x7fa0)) allow = true
            else {
                allow = false
                if (flag == 1) peckLock(am)
            }
        }
        if (gate != 0 && MergeMoopad.hasUSF1(mp, 0x90) == 1) { peckLock(am); allow = false }
        if (Xnl.val_(mp, 0x29540) == 1) { peckLock(am); allow = false }
        if (Xnl.val_(mp, 0x29644) == 1) { peckLock(am); allow = false }
        if (Xnl.val_(mp, 0x29610) == 1 && Xnl.val_(mp, 0x2b0c4) == 0) {
            peckLock(am); am.setU8(PECK_LOCKED, 1); return
        }
        if (nintype.engine.SoftFloat.dcmpgt(el, f2d(lim2)) && allow) {
            host.maybeFinishWord()
            host.turnOnTempAutospace()
        }
    }

    /** newPressShouldAutomerge(double t, Point2D const& pos, int n, int x) @00ee6470, on a new
     * press. [board] = the current board (Moopad+0x1f208), [keyFc4] / [spacebar] = its Mookey*
     * at +0xfc4 / +0xfac (null allowed). A press inside keyFc4 (3 px slack) or the spacebar
     * (1 px) never starts a new word; neither does one on a key whose row (+0xc9c) < 1.
     * Otherwise automergeAllowanceCheck(t, pos, n, tap = 1, x). */
    fun newPressShouldAutomerge(am: MemImage, t: Double, pos: Point2D, n: Int, x: Int, board: nintype.engine.geometry.MooBoard,
                                keyFc4: nintype.engine.geometry.Mookey?, spacebar: nintype.engine.geometry.Mookey?,
                                sitting: List<Sitting>): Int {
        if (keyFc4 != null && keyFc4.containsWithExtraIgnoringOHM(pos, 3, 3, 3, 3)) return 0
        if (spacebar != null && spacebar.containsWithExtraIgnoringOHM(pos, 1, 1, 1, 1)) return 0
        val k = board.findKey(nintype.engine.SoftFloat.f2iz(pos.x), nintype.engine.SoftFloat.f2iz(pos.y))
        if (k != null && k.rowIndex < 1) return 0
        return automergeAllowanceCheck(am, t, pos, n, 1, x, sitting)
    }
}
