package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.d2iz
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.emkey.Calculate
import nintype.engine.emkey.MooTouchPoint
import nintype.engine.math.LibM
import nintype.engine.math.Point2D
import nintype.engine.pathscore.FingerPathData
import nintype.engine.pathscore.PredPath
import nintype.engine.pathscore.SlideVSArg
import nintype.engine.pathscore.Vecs
import nintype.engine.vectors.MemImage

/**
 * The PredPath members (and the two PathSearch queue/result calls) maybeBranchInner delegates
 * to. Pure leaf helpers it uses (fing/lastfing, SlideVSArg setup, calcSlideDiff, the
 * calculate_* curves, timePenalty, SummableFloat, Point2D, powf) are called directly.
 * [newChild] is MemPool::use + Poolable::new + PredPath::PredPath(PredPath*).
 * [hard] arguments are the binary's float& (one-element arrays, read and written in place).
 */
interface BranchCallees {
    fun newChild(src: MemImage): MemImage
    fun advance(np: MemImage, c: Int)
    fun advanceApostrophied(np: MemImage, c: Int)
    fun advanceAposPossessive(np: MemImage, c: Int)
    fun advanceSkipping(np: MemImage, skip: Int, c: Int)
    fun syncSkipScoreMultForHeuristics(np: MemImage)
    fun syncApostate(np: MemImage)
    fun syncRouteCoverageHeuristics(np: MemImage)
    fun syncNotLongEnoughMultpen(np: MemImage)
    fun syncFutureCoverageHeuristics(np: MemImage)
    fun syncPointRevisitOn(np: MemImage, p: MemImage)
    fun syncPointRevisitMult(np: MemImage)
    fun syncBalancedNovisitMult(np: MemImage)
    fun syncClassicSlideVSCache(np: MemImage)
    fun syncNeoSlideVSCache(np: MemImage)
    fun updateHeuristicsSubscore(np: MemImage, old: Float, new: Float)
    /** maybePrune(cutoff, FunnelStat&) with the FunnelStat at PathSearch + [fsOff]. */
    fun maybePrune(np: MemImage, cutoff: Float, fsOff: Int): Boolean
    fun getWord(np: MemImage): ByteArray
    fun markVisited(np: MemImage, p: MemImage, force: Boolean, h: MemImage)
    fun updateVisitation(np: MemImage, t: Double, finger: Int, p: MemImage, h: MemImage)
    fun updateVisitationComplete(r: MemImage)
    fun maybeRecordSlideVSPenalty(np: MemImage, arg: MemImage, hard: FloatArray)
    fun maybeRecordHistoricNeoSlideVSFrom(np: MemImage, target: MemImage, index: Int, h: MemImage, pt: Point2D, rsi: MemImage, d1: Double, hard: FloatArray)
    fun canProgress(np: MemImage): Boolean
    fun canFinish(np: MemImage): Boolean
    fun determineScoreForHeuristics(np: MemImage)
    /** TWRet pairs {TrieWord id, TrieWord*}. */
    fun gatherFinisherTrieWords(np: MemImage): List<IntArray>
    fun gatherFinisherPendingWords(np: MemImage): List<Any>
    fun markFinisher(r: MemImage, tw: IntArray)
    fun markPendingFinisher(r: MemImage, pw: Any)
    fun determineFinalScore(r: MemImage)
    fun pushQueue(np: MemImage)
    fun regResult(r: MemImage)
}

/** Inputs of maybeBranchInner that live outside the five objects it is called with. */
class BranchEnv(
    /** Moopad (+0x333f8 no-cutoff flag, +0x333e0 trace-word flag, +0x7fa4 Xnl, 0x1f4a0 key height, 0x1f4a4 key width). */
    val mp: MemImage,
    /** The Moopad's trace word (std::string at +0x333e4). */
    val traceWord: ByteArray,
    /** StepScoreStats for path length i (getStepStats; may grow the list). */
    val stats: (Int) -> MemImage,
    /** The SlideVSTarget vector (+0x3c, 8-byte elements) of a point. */
    val slideTargets: (MemImage) -> List<MemImage>,
)

object Branch {
    private fun f(b: Int) = Bits.bitsToFloat(b)
    private val F25 = 25f
    private val D015F = Bits.bitsToDouble(0x3fc3333340000000L) // (double)0.15f
    private val F015 = f(0x3e19999a)
    private val F06 = f(0x3f19999a)
    private val F01 = f(0x3dcccccd)
    private val F02 = f(0x3e4ccccd)
    private val F067 = f(0x3f2b851f)
    private val F001 = f(0x3c23d70a)
    private val F235 = f(0x40166666)
    private val F036 = f(0x3eb851ec)
    private val F085 = f(0x3f59999a)
    private val F065 = f(0x3f266666)
    private val F19 = f(0x3ff33333)
    private val FM035 = f(0xbeb33333.toInt())
    private val F115 = f(0x3f933333)
    private val F07 = f(0x3f333333)
    private val F04 = f(0x3ecccccd)
    private val D001 = Bits.bitsToDouble(0x3f847ae147ae147bL)  // 0.01
    private val D001F = Bits.bitsToDouble(0x3f847ae140000000L) // (double)0.01f

    private fun funnel(ps: MemImage, off: Int) {
        var v = ps.i32(off)
        if (v < 0) { v = 0; ps.setI32(off, 0) }
        ps.setI32(off, v + 1)
    }
    private fun inc(img: MemImage, off: Int) = img.setI32(off, img.i32(off) + 1)
    private fun MemImage.pt(o: Int) = Point2D(f32(o), f32(o + 4))
    private fun flags16(img: MemImage) = img.u8(0x2f) or (img.u8(0x30) shl 8)
    private fun setFlags16(img: MemImage, v: Int) { img.setU8(0x2f, v and 0xff); img.setU8(0x30, (v ushr 8) and 0xff) }

    /** SummableFloat::add(float v, int n) @0081895c: count += n; sum = n*v + sum. */
    fun summableAdd(img: MemImage, o: Int, v: Float, n: Int) {
        img.setI32(o, img.i32(o) + n)
        img.setF32(o + 4, fadd(fmul(i2f(n), v), img.f32(o + 4)))
    }

    /** SummableFloat::mean() @00818998: 1 when empty, else sum / count. */
    fun summableMean(img: MemImage, o: Int): Float {
        val n = img.i32(o)
        return if (n == 0) 1f else fdiv(img.f32(o + 4), i2f(n))
    }

    /** Morestring::beginsWith(s, prefix) @00796b38. */
    private fun beginsWith(s: ByteArray, prefix: ByteArray): Boolean {
        if (s.size < prefix.size) return false
        for (i in prefix.indices) if (s[i] != prefix[i]) return false
        return true
    }

    /**
     * maybeBranchInner(PredPath& pp, Mootouch& mt, MooTouchPoint& p, TravKeyData& trav,
     * double d1, double d2, BranchmentData&, BranchingMode mode, char skip) @007e7104.
     * Makes the child path np of [pp] that steps onto [p] typing trav's char (+8) in the time
     * window [d1, d2], scores it heuristic by heuristic (each sync + updateHeuristicsSubscore
     * + maybePrune; a prune abandons the child), and queues it (pushQueue) when it can still
     * progress, or registers finisher copies (regResult) when it can finish. The cutoff is
     * the next length's StepScoreStats +0x74 (0.1 with Moopad +0x333f8 set). Returns whether
     * the child was queued. The BranchmentData is not read.
     */
    fun maybeBranchInner(ps: MemImage, pp: MemImage, mt: MemImage, p: MemImage, trav: MemImage, d1: Double, d2: Double,
                         mode: Int, skip: Int, env: BranchEnv, cb: BranchCallees): Boolean {
        val mp = env.mp
        val noCut = mp.u8(0x333f8) != 0
        val cutoff = if (!noCut) env.stats(pp.i8(0x2a) + 1).f32(0x74) else F01
        inc(ps, 0x670); inc(ps, 0x674)
        val np = cb.newChild(pp)
        inc(pp, 0x120)
        np.setI32(0x70, np.i32(0x74))
        np.setU8(0x11c, 0)
        np.setI32(0x120, 0); np.setI32(0x124, 0); np.setI32(0x128, 0)
        np.setI32(0x10, ps.i32(0xe8c))
        np.setI32(0x12c, ps.i32(0x674))
        np.refs[0xf8] = p
        val anchor: MemImage = if (p.u8(0x34d) != 0) p else p.ref(0xc) ?: error("maybeBranchInner: point without parent (assert)")
        val size0 = np.u8(0x2a)
        val timeOff: Float = when {
            size0 != 0 -> 0f
            p.u8(0x34d) == 0 -> F25
            p.i32(0x308) == 0 -> 0f
            p.u8(0x225) and 8 == 0 -> F25
            else -> {
                val dt = Bits.bitsToDouble(Bits.doubleToBits(dsub(p.f64(0x128), ps.f64(0x170))) and Long.MAX_VALUE)
                if (dcmpge(dt, D015F) || dcmpun(dt, D015F)) F25
                else { val v = fdiv(d2f(dt), F015); fmul(fmul(v, v), F06) }
            }
        }
        inc(ps, 0x7b0)
        val s = fsub(pp.f32(0x74), timeOff)
        if (!(fcmpge(s, cutoff) || fcmpun(s, cutoff))) { funnel(ps, 0x7b8); return false }
        funnel(ps, 0x7b4)
        val side = mt.i8(0xec)
        val lf = PredPath.lastfing(pp, side)
        val pf = PredPath.fing(pp, side)
        val nf = PredPath.fing(np, side)
        np.refs[nf + 0x44] = np.refs[nf + 0x40]
        np.refs[nf + 0x40] = p
        np.setU8(0x174, mt.u8(0xec))
        val keyIdx = p.i32(0x308)
        val mtc8 = mt.i32(0xc8)
        val pf13 = pp.i8(pf + 0x13)
        val travCh = trav.i32(8)
        val pf15 = pp.u8(pf + 0x15)
        val lf14 = pp.i8(lf + 0x14)
        fun prune(fs: Int) = cb.maybePrune(np, cutoff, fs)
        if (travCh == 0x20) {
            np.setU8(0x2f, np.u8(0x2f) or 2)
        } else {
            val c = travCh and 0xff
            when (mode) {
                0 -> cb.advance(np, c)
                1 -> cb.advanceApostrophied(np, c)
                2 -> cb.advanceAposPossessive(np, c)
                3 -> {
                    cb.advanceSkipping(np, skip, c)
                    cb.syncSkipScoreMultForHeuristics(np)
                    cb.updateHeuristicsSubscore(np, pp.f32(0x144), np.f32(0x144))
                    if (prune(0x7c4)) return false
                }
            }
        }
        np.setU8(nf + 0x14, p.i32(0x308) and 0xff)
        np.setU8(nf + 0x15, trav.i32(8) and 0xff)
        np.setU8(nf + 0x12, mt.i32(0xd8) and 0xff)
        np.setU8(nf + 0x13, mt.i32(0xc8) and 0xff)
        np.setU8(0x2a, (np.u8(0x2a) + 1) and 0xff)
        np.setU8(nf + 0x16, (np.u8(nf + 0x16) + 1) and 0xff)
        np.setU8(nf + 0x29, p.i32(0x310) and 0xff)
        np.setU16(0x36, p.i32(0x308) and 0xffff)
        np.setU8(0x33, p.i32(0x30c) and 0xff)
        val sameTouch = pf13 == mtc8
        val sameChar = pf15 == travCh
        val both = sameChar && sameTouch
        var fl = (flags16(np) and 8.inv()) or (if (both) 8 else 0)
        if (both) fl = fl or 0x10
        setFlags16(np, fl)
        cb.syncApostate(np)
        cb.syncRouteCoverageHeuristics(np)
        cb.updateHeuristicsSubscore(np, pp.f32(0xd0), np.f32(0xd0))
        if (prune(0x7d8)) return false
        inc(ps, 0x7ec)
        if (mp.u8(0x333e0) != 0 && !beginsWith(env.traceWord, cb.getWord(np))) return false
        funnel(ps, 0x7f0)
        np.setF32(0x6c, fadd(np.f32(0x6c), 0f))
        inc(ps, 0x800)
        np.setF64(nf + 0x38, d2)
        np.setF64(0x40, d1)
        np.setF64(nf + 0x30, d1)
        if (dcmplt(np.f64(0x48), d1)) np.setF64(0x48, d1)
        if (fcmplt(np.f32(0x38), p.f32(0x31c))) np.setF32(0x38, p.f32(0x31c))
        val q = p.ref(0xc) ?: p
        if (dcmplt(np.f64(0x50), q.f64(0x148))) np.setF64(0x50, q.f64(0x148))
        cb.syncNotLongEnoughMultpen(np)
        cb.updateHeuristicsSubscore(np, pp.f32(0xbc), np.f32(0xbc))
        if (prune(0x814)) return false
        cb.syncFutureCoverageHeuristics(np)
        cb.updateHeuristicsSubscore(np, pp.f32(0xcc), np.f32(0xcc))
        if (prune(0x828)) return false
        funnel(ps, 0x804)

        // backtravel multipliers
        val pf38 = pp.f64(pf + 0x38)
        var m1 = 1f
        if (!(dcmple(pf38, d2) || dcmpun(pf38, d2))) {
            m1 = Calculate.backtouchtravelMult(d2, pf38)
            if (pp.u8(pf + 0x13) == np.u8(nf + 0x13)) m1 = fmul(m1, F02)
        }
        np.setF32(0xb4, fmul(m1, np.f32(0xb4)))
        var a = 1f
        if (!(dcmple(np.f64(0x40), d1) || dcmpun(np.f64(0x40), d1))) a = Calculate.backtravelMult(d1, pp.f64(0x40), false)
        if (!(dcmple(np.f64(0x48), d1) || dcmpun(np.f64(0x48), d1))) a = fmul(a, Calculate.backtravelMult(d1, pp.f64(0x40), false))
        var r8 = 1f
        if (!(dcmple(np.f64(nf + 0x30), d1) || dcmpun(np.f64(nf + 0x30), d1))) r8 = Calculate.backtravelMult(d1, pp.f64(pf + 0x30), true)
        var r4 = 1f
        if (!(dcmple(np.f64(0x48), d1) || dcmpun(np.f64(0x48), d1))) r4 = Calculate.strictBacktravelMult(d1, pp.f64(0x48))
        val ex = ps.f32(0x360)
        val pa = LibM.powf(a, ex)
        val pb = LibM.powf(r4, ex)
        np.setF32(0xb0, fmul(fmul(r8, pa), np.f32(0xb0)))
        cb.updateHeuristicsSubscore(np, fmul(pp.f32(0xb4), pp.f32(0xb0)), fmul(np.f32(0xb0), np.f32(0xb4)))
        if (prune(0x83c)) return false
        np.setF32(0xb8, fmul(pb, np.f32(0xb8)))
        cb.updateHeuristicsSubscore(np, pp.f32(0xb8), np.f32(0xb8))
        if (prune(0x850)) return false
        cb.updateHeuristicsSubscore(np, pp.f32(0xb0), np.f32(0xb0))
        if (prune(0x878)) return false
        cb.syncPointRevisitOn(np, p)
        cb.syncPointRevisitMult(np)
        cb.updateHeuristicsSubscore(np, pp.f32(0xd4), np.f32(0xd4))
        if (prune(0x88c)) return false
        if (trav.i32(0xc) != 0) np.setU8(0x2f, np.u8(0x2f) or 1)

        // the step record (PredHistory, 0x28 bytes) appended to np's steps
        val steps = Vecs.elems(np, PredPath.OFF_STEPS)
        val h = MemImage("${np.name}.steps[${steps.size}]", 0x28)
        h.writeBytes(0, ByteArray(0x28))
        steps.add(h)
        h.setU8(8, skip and 0xff)
        h.setI32(0x10, trav.i32(0xc))
        h.setF32(0, d2f(dsub(d1, ps.f64(0x168))))
        h.setF32(4, d2f(dsub(d2, ps.f64(0x168))))
        h.setU16(0x1c, d2iz(ddiv(dsub(p.f64(0x128), mt.f64(0xf8)), D001)) and 0xffff)
        h.setI32(0xc, trav.i32(8))
        h.refs[0x18] = p
        h.setI32(0x20, trav.i32(0)); h.setI32(0x24, trav.i32(4))
        h.setU8(0x14, mt.i32(0xc8) and 0xff)
        h.setU8(0x15, mt.u8(0xec))
        h.setU8(0x16, 0)
        val p224 = p.u16(0x224)
        var hf = (h.u16(0x1e) and 1.inv()) or (if (sameChar) 1 else 0)
        hf = (hf and 2.inv()) or ((p224 ushr 10) and 2)
        hf = (hf and 4.inv()) or ((p224 ushr 10) and 4)
        hf = ((hf and 0xe027) or (p.u8(0x34d) shl 3)) and 0xffff
        h.setU16(0x1e, hf)
        np.setI32(nf, trav.i32(0)); np.setI32(nf + 4, trav.i32(4))
        val b2 = (np.u8(nf + 0x10) and 4.inv()) or (p.u8(0x34d) shl 2)
        np.setU8(nf + 0x10, b2 and 0xff)
        np.setI32(nf + 8, p.i32(0xdc)); np.setI32(nf + 0xc, p.i32(0xe0))
        np.setU8(nf + 0x10, ((b2 and 0xfc) or ((p224 ushr 12) and 2) or 1) and 0xff)
        np.setI32(nf + 0x20, p.i32(0xdc)); np.setI32(nf + 0x24, p.i32(0xe0))
        np.setI32(nf + 0x18, p.i32(0xdc)); np.setI32(nf + 0x1c, p.i32(0xe0))
        if (size0 == 0 && mt.u8(0xed) == 0) {
            np.setF32(0x78, fmul(np.f32(0x78), F067))
            h.setU16(0x1e, h.u16(0x1e) or 0x1000)
        }
        cb.markVisited(np, p, sameChar, h)
        cb.updateVisitation(np, dadd(d1, D001F), mt.i8(0xec), p, h)
        cb.syncBalancedNovisitMult(np)
        cb.updateHeuristicsSubscore(np, fmul(pp.f32(0x9c), pp.f32(0xa0)), fmul(np.f32(0x9c), np.f32(0xa0)))
        if (prune(0x8a0)) return false

        // double-letter / same-key multiplier into the SummableFloat at +0x7c
        var mult = 1f
        var weight = 1
        if (sameChar) {
            if (pp.u8(pf + 0x13) == np.u8(nf + 0x13)) {
                val x = nintype.engine.merge.Xnl.val_(mp, 0x7fa4)
                val dl = anchor.f32(0x16c)
                if (x != 0) {
                    if (!(fcmple(dl, F001) || fcmpun(dl, F001))) {
                        h.setU16(0x1e, h.u16(0x1e) or 0x800)
                        weight = f2iz(fadd(fadd(dl, dl), 1f))
                        mult = F235
                    }
                } else {
                    val k = if (ps.u8(0x35a) != 0) F036 else 1f
                    if (p224 and 0x1800 == 0x1000) {
                        h.setU16(0x1e, h.u16(0x1e) or 0x80)
                        val t = fmul(k, F085)
                        val u = fsub(1f, dl)
                        mult = fadd(dl, fmul(u, t))
                        weight = f2iz(fadd(dl, fadd(u, u)))
                    }
                    if (mtc8 == pf13 && pp.u8(lf + 0x11) == mt.u8(0xec)) {
                        h.setU16(0x1e, h.u16(0x1e) or 0x100)
                        val t = fmul(k, F065)
                        val u = fsub(1f, dl)
                        mult = fmul(fadd(dl, fmul(u, t)), mult)
                        weight = f2iz(fadd(dl, fadd(u, u)))
                    }
                    val g = anchor.f32(0x16c)
                    if (!(fcmple(g, F001) || fcmpun(g, F001))) {
                        h.setU16(0x1e, h.u16(0x1e) or 0x800)
                        mult = fmul(mult, F19)
                        weight = f2iz(fadd(fadd(g, g), 1f))
                    }
                    if (keyIdx == lf14 + 1 && mtc8 == pf13) {
                        h.setU16(0x1e, h.u16(0x1e) or 0x400)
                        mult = fmul(mult, 0.25f)
                        weight = 2
                    }
                }
            } else {
                val v = pp.pt(pf + 0x20) - np.pt(nf + 0x20)
                val u = Point2D(fdiv(v.x, mp.f32(0x1f4a4)), fdiv(v.y, mp.f32(0x1f4a0)))
                val len = u.length()
                h.setU16(0x1e, h.u16(0x1e) or 0x40)
                mult = fadd(fmul(len, FM035), F115)
                weight = 2
            }
        }
        summableAdd(np, 0x7c, mult, weight)
        cb.updateHeuristicsSubscore(np, summableMean(pp, 0x7c), summableMean(np, 0x7c))
        if (prune(0x8c8)) return false

        // slide-direction (slideVS) checks
        val hard = floatArrayOf(0f)
        var sameFinger = false
        val tpos = trav.pt(0)
        val ppos = p.pt(0xdc)
        if (!sameChar && sameTouch && pp.u8(pf + 0x10) and 1 != 0) {
            val v = tpos - ppos
            val len = v.length()
            inc(ps, 0x8dc)
            if (!(fcmple(len, 8f) || fcmpun(len, 8f))) {
                var w = pp.pt(pf) - ppos
                var wl = w.length()
                if (!(fcmpge(wl, 10f) || fcmpun(wl, 10f))) { w = p.pt(0) - ppos; wl = 10f }
                val d = Point2D.dot(v, w)
                val t = fmul(wl, F085)
                val short = fcmple(len, t) || fcmpun(len, t)
                val cos = fdiv(d, fmul(len, wl))
                if (!short && !(fcmple(cos, F07) || fcmpun(cos, F07))) { funnel(ps, 0x8e4); return false }
            }
            if (p.u8(0x34d) != 0) {
                val n = (ppos - pp.pt(pf + 8)).normalise()
                val dd = Point2D.dot(n, p.pt(0x114))
                if (!(fcmpge(dd, F001) || fcmpun(dd, F001))) { funnel(ps, 0x8e8); return false }
            }
            funnel(ps, 0x8e0)
            val arg = MemImage("slideA", SlideVSArg.SIZE)
            SlideVSArg.init(arg)
            arg.setU8(0x44, 1); arg.setU8(0x78, 1)
            arg.setU8(0x79, if (mt.i32(0xc8) == pp.i8(pf + 0x13)) 1 else 0)
            arg.setF64(0x48, d1)
            if (pp.u8(lf + 0x11) == pp.u8(pf + 0x11)) { arg.setF32(0x54, fadd(arg.f32(0x54), arg.f32(0x54))); sameFinger = true }
            SlideVSArg.markKeyDiff(arg, pp.u8(pf + 0x15), trav.u8(8), pp.pt(pf), tpos)
            FingerPathData.calcSlideDiff(pp, pf, np, nf, p.pt(0xfc), arg)
            cb.maybeRecordSlideVSPenalty(np, arg, hard)
            inc(ps, 0x8f0)
            if (!noCut && !(fcmple(hard[0], 1f) || fcmpun(hard[0], 1f))) { funnel(ps, 0x8f8); return false }
            funnel(ps, 0x8f4)
        }
        if (pp.u8(lf + 0x10) and 1 != 0 && !(sameFinger || pp.u8(lf + 0x15) == travCh)) {
            val arg = MemImage("slideB", SlideVSArg.SIZE)
            SlideVSArg.init(arg)
            arg.setU8(0x44, 1)
            arg.setF64(0x48, d1)
            arg.setU8(0x78, if (pp.u8(lf + 0x11) == pp.u8(pf + 0x11)) 1 else 0)
            arg.setU8(0x79, if (mt.i32(0xc8) == pp.i8(lf + 0x13)) 1 else 0)
            SlideVSArg.markKeyDiff(arg, pp.u8(lf + 0x15), trav.u8(8), pp.pt(lf), tpos)
            FingerPathData.calcSlideDiff(pp, lf, np, nf, p.pt(0xfc), arg)
            cb.maybeRecordSlideVSPenalty(np, arg, hard)
            inc(ps, 0x904)
            if (!noCut && !(fcmple(hard[0], 1f) || fcmpun(hard[0], 1f))) { funnel(ps, 0x90c); return false }
            funnel(ps, 0x908)
        }
        cb.syncClassicSlideVSCache(np)
        cb.updateHeuristicsSubscore(np, pp.f32(0xe0), np.f32(0xe0))
        if (prune(0x918)) return false
        inc(pp, 0x124)
        val rsi = MemImage("rsi", 0x28)
        rsi.writeBytes(0, ByteArray(0x28))
        val ptFc = p.pt(0xfc)
        for ((k, t) in env.slideTargets(anchor).withIndex()) cb.maybeRecordHistoricNeoSlideVSFrom(np, t, k, h, ptFc, rsi, d1, hard)
        cb.syncNeoSlideVSCache(np)
        cb.updateHeuristicsSubscore(np, pp.f32(0xec), np.f32(0xec))
        if (prune(0x92c)) return false
        inc(ps, 0x954)
        val slide = hard[0]
        val s2 = fsub(s, slide)
        if (fcmplt(s2, cutoff)) return false
        funnel(ps, 0x958)
        val hf0 = h.u16(0x1e)
        val hf1 = (hf0 and 0xffdf) or (if (fcmpgt(slide, F01)) 0x20 else 0)
        h.setU16(0x1e, hf1)
        if (hf0 and 8 != 0) np.setU8(0x31, (np.u8(0x31) + 1) and 0xff)
        if (hf1 and 0x10 != 0) np.setU8(0x32, (np.u8(0x32) + 1) and 0xff)
        if (!dcmplt(mt.f64(0xf8), np.f64(0x58))) np.setF64(0x58, mt.f64(0xf8))
        val tp = MooTouchPoint.timePenalty(p, d1)
        inc(ps, 0x968)
        val s3 = fsub(s2, tp)
        if (fcmplt(s3, cutoff)) return false
        funnel(ps, 0x96c)
        np.setF32(0x13c, timeOff)
        val wq = trav.f32(0x10)
        val pw = LibM.powf(fdiv(wq, 6f), F04)
        np.setF32(0x68, fadd(np.f32(0x68), fsub(fsub(-tp, slide), timeOff)))
        inc(ps, 0x97c)
        val w6 = fmul(pw, 6f)
        if (fcmplt(wq, 0f)) return false
        funnel(ps, 0x980)
        np.setF32(0x60, fadd(wq, np.f32(0x60)))
        np.setF32(0x64, fadd(w6, np.f32(0x64)))
        summableAdd(np, 0x84, fdiv(w6, 6f), 1)
        cb.updateHeuristicsSubscore(np, summableMean(pp, 0x84), summableMean(np, 0x84))
        if (prune(0x940)) return false
        inc(pp, 0x128)

        var queued = false
        if (travCh != 0x20 && cb.canProgress(np)) {
            cb.determineScoreForHeuristics(np)
            inc(ps, 0x990)
            if (fcmplt(np.f32(0x74), cutoff)) return false
            funnel(ps, 0x994)
            cb.pushQueue(np)
            inc(ps, 0x66c)
            queued = true
        }
        if (!cb.canFinish(np)) return queued
        val tws = cb.gatherFinisherTrieWords(np)
        val pws = cb.gatherFinisherPendingWords(np)
        val n = tws.size + pws.size
        val results = ArrayList<MemImage>()
        for (i in 0 until n) {
            val r = if (results.isEmpty()) cb.newChild(np).also { cb.updateVisitationComplete(it) } else cb.newChild(results[0])
            if (i < tws.size) cb.markFinisher(r, tws[i]) else cb.markPendingFinisher(r, pws[i - tws.size])
            results.add(r)
        }
        for (r in results) {
            cb.determineFinalScore(r)
            if (fcmplt(np.f32(0x138), r.f32(0x74))) np.setF32(0x138, r.f32(0x74))
            inc(ps, 0x9a4)
            cb.regResult(r)
            funnel(ps, 0x9a8)
        }
        return queued
    }
}
