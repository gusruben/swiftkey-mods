package nintype.engine.search

import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dmul
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpeq
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.i2d
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.i2f
import nintype.engine.SoftFloat.ui2f
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.Bits
import nintype.engine.emkey.Moopad
import nintype.engine.math.CurveMapper
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.LibM
import nintype.engine.math.Point2D
import nintype.engine.touch.PlanarCoverage
import nintype.engine.trie.Keycode
import nintype.engine.vectors.MemImage

/**
 * `Emkey::PathSearch` (the best-first search driver), ported over a byte image of the object
 * ([MemImage], sizeof >= 0xec4) so field offsets stay exactly the binary's. Containers the
 * methods walk are passed in as Kotlin lists of the element images (the adapters in
 * `ports/SearchPorts.kt` build them from the vectors). Ghidra addresses in each doc comment.
 *
 * Known fields (names are guesses from use; offsets are what the vectors check):
 *
 *   0x110 ptr   Moopad*
 *   0x128 map   std::map<int, TrieStepCoverageDistro> (header; root at 0x130)
 *   0x1a0 vec   std::vector<Mootouch> (by value, sizeof 0x3b8)
 *   0x1b0 vec   std::vector<MooTouchPoint*> important points (any finger, time order)
 *   0x1bc vec   std::vector<MooTouchPoint*> (generic look-ahead allowances at +0x318)
 *   0x1c8 vec   finger-1 points    0x1d4 vec finger-0 points (look-ahead allowance at +0x314)
 *   0x3a0 map   std::map<std::string, float> chain-ranker scores
 *   0x540 QueueBucket[4], stride 0x34 (ctor args (0,1,7) (2,2,24) (3,3,35) (4,999,100)):
 *               +0 id, +4 maxLen?, +8 step allowance, +0xc queue (heap vector of PredPath refs),
 *               +0x1c second vector, +0x2c steps taken, +0x30 ?
 *   0x620 map   std::map<std::string, Refcounter<PredPath>> results (header 0x624; 0x634 = its size)
 *   0x638 vec   ranked result list (listResults)
 *   0xea8 vec   std::vector<StepScoreStats> (sizeof 0x90)
 *
 * MooTouchPoint fields used here (see emkey/MooTouchPoint.kt for the rest):
 *   0x008 owner Mootouch*  0x024/0x028 prev/next important same-finger  0x030 next any-finger
 *   0x034/0x038 prev/next same-finger  0x0dc pos  0x128 f64 tStart  0x16c f32 soft-index width
 *   0x21c u8 finger  0x225 u8 flags high byte (bit 4 = flag 0x400)  0x314 i32 finger look-ahead
 *   0x318 i32 generic look-ahead  0x320/0x324 f32 soft index generic / same-finger
 *   0x34d u8 important-for-finger  0x360 f32 look-ahead weight  0x364 f32 soft-index gap
 * Mootouch: 0xf8 f64 tStart, 0x110 f64 tEnd.
 */
object PathSearch {
    const val SIZE = 0xec8
    const val BUCKETS = 0x540
    const val BUCKET_STRIDE = 0x34

    private fun f(b: Int) = Bits.bitsToFloat(b)

    /** syncBucketStepAllowance(float) @007d584c: bucket allowances from a 0..1 aggressiveness. */
    fun syncBucketStepAllowance(ps: MemImage, a: Float) {
        ps.setI32(0x548, 7)
        val inv = fmul(fsub(1f, a), 5f)
        ps.setI32(0x57c, f2iz(fadd(fmul(a, 24f), inv)))
        ps.setI32(0x5b0, f2iz(fadd(fmul(a, 35f), inv)))
        ps.setI32(0x5e4, f2iz(fadd(fmul(a, 100f), fmul(fsub(1f, a), 19f))))
    }

    /** onlyHasASingleTouch() @007e1988. */
    fun onlyHasASingleTouch(mootouches: List<MemImage>): Boolean = mootouches.size == 1

    /** hasResult() @007ea8f4: the results map (+0x620) is not empty (its node count at +0x634). */
    fun hasResult(topPathSet: Boolean): Boolean = topPathSet

    /** finished() @007f0210: every bucket has used its allowance or emptied its queue.
     * [queueSizes] = sizes of the four bucket queues (+0xc vectors). */
    fun finished(ps: MemImage, queueSizes: IntArray): Boolean {
        for (b in 0 until 4) {
            val base = BUCKETS + BUCKET_STRIDE * b
            if (ps.i32(base + 0x2c) < ps.i32(base + 8) && queueSizes[b] != 0) return false
        }
        return true
    }

    private fun lookAhead(pts: List<MemImage>, idx: Int, field: Int): Int {
        var i = if (idx < 0) 0 else idx
        if (pts.size <= i) {
            if (pts.isEmpty()) return 2
            i = pts.size - 1
        }
        return pts[i].i32(field)
    }

    /** getGenericLookAheadAllowance(int) @007e1a30: points = the +0x1bc vector. */
    fun getGenericLookAheadAllowance(pts: List<MemImage>, idx: Int): Int = lookAhead(pts, idx, 0x318)

    /** getFingerLookAheadAllowance(int, int) @007e1a6c: [pts] = the finger's vector (+0x1c8
     * for finger 1, else +0x1d4). */
    fun getFingerLookAheadAllowance(pts: List<MemImage>, idx: Int): Int = lookAhead(pts, idx, 0x314)

    /** isACForceGlancingTouch(a, b) @007e19a4. [unit] = Moopad unit (+0x1f4a8 of +0x110). */
    fun isACForceGlancingTouch(a: MemImage, b: MemImage, unit: Float): Boolean {
        if (a === b) return false
        if (a.ref(8) !== b.ref(8)) return false
        val d = Moopad.unitDist(Point2D(a.f32(0xdc), a.f32(0xe0)), Point2D(b.f32(0xdc), b.f32(0xe0)), unit)
        if (a.ref(0x28) !== b) return false
        return fcmplt(d, f(0x3f19999a)) // 0.6
    }

    /** touchPointIsNotMultiTouching(tp, float margin) @007e0ed8. Despite the name: true when
     * tp starts strictly inside (start - margin, end + margin) of some Mootouch other than its
     * owner. [mootouches] = the +0x1a0 vector. */
    fun touchPointIsNotMultiTouching(mootouches: List<MemImage>, tp: MemImage, margin: Float): Boolean {
        if (mootouches.isEmpty()) return false
        val owner = tp.ref(8)
        val m = f2d(margin)
        for (mt in mootouches) {
            if (owner === mt) continue
            val hi = dadd(m, mt.f64(0x110))
            val t = tp.f64(0x128)
            if (dcmpge(t, hi) || dcmpun(t, hi)) continue
            if (dcmpgt(t, dsub(mt.f64(0xf8), m))) return true
        }
        return false
    }

    /** syncSamefingLattice(vector&) @007dd754: prev (+0x34) / next (+0x38) links between
     * neighbours. Links are kept in the images' [MemImage.refs]. */
    fun syncSamefingLattice(pts: List<MemImage>) = link(pts, 0x34, 0x38)

    /** syncSamefingImportantLattice(vector&) @007dd788: same with +0x24 / +0x28. */
    fun syncSamefingImportantLattice(pts: List<MemImage>) = link(pts, 0x24, 0x28)

    private fun link(pts: List<MemImage>, prevOff: Int, nextOff: Int) {
        for (k in 1 until pts.size) {
            setRef(pts[k - 1], nextOff, pts[k])
            setRef(pts[k], prevOff, pts[k - 1])
        }
    }

    fun setRef(img: MemImage, off: Int, target: MemImage?) {
        img.refs[off] = target
        img.refNames[off] = target?.name
    }

    /** syncFingerSpecifForelookAllowances(vector&) @007de5bc. */
    fun syncFingerSpecifForelookAllowances(pts: List<MemImage>) {
        var si = 0f
        val lim = f(0x3ffd70a4) // 1.98
        for (i in pts.indices) {
            val p = pts[i]
            var n = 0
            if (i + 1 < pts.size) {
                var sum = 0f
                var j = i + 1
                while (true) {
                    sum = fadd(sum, pts[j].f32(0x360))
                    n = j - i
                    if (fcmpge(sum, lim)) break
                    j++
                    if (j >= pts.size) break
                }
            }
            p.setI32(0x314, n)
            val half = fmul(p.f32(0x16c), 0.5f)
            val mid = fadd(si, half)
            p.setF32(0x324, mid)
            var gap = p.f32(0x364)
            if (p.u8(0x225) and 4 != 0) gap = fmul(gap, 0.5f)
            si = fadd(gap, fadd(mid, half))
        }
    }

    /** syncOtherFingIndexes(int finger) @007de6d4. [important] = the +0x1b0 vector; the walk
     * follows each point's any-finger next link (+0x30, a ref). Shing::guardInifiniteLoop only
     * reports (never alters state), so it is not modelled. */
    fun syncOtherFingIndexes(important: List<MemImage>, finger: Int) {
        if (important.isEmpty()) return
        val fing = finger // ldrb vs the full int argument
        fun other(p: MemImage) = p.u8(0x21c) != fing
        var anchor: MemImage? = null
        var cur: MemImage? = important[0]
        for (p in important) {
            var newAnchor = anchor
            if (p.u8(0x34d) != 0 && p.u8(0x21c) == fing) {
                newAnchor = cur
                if (cur !== p) {
                    val a = anchor
                    if (a == null) {
                        do {
                            val c = cur!!
                            if (other(c)) c.setI32(0x320, p.i32(0x324))
                            cur = c.ref(0x30)
                        } while (cur !== p)
                    } else {
                        do {
                            val c = cur!!
                            if (other(c)) {
                                val at = a.f64(0x128)
                                val span = dsub(p.f64(0x128), at)
                                var v = f2d(a.f32(0x324))
                                if (!dcmple(span, 0.0)) {
                                    val dsi = dsub(f2d(p.f32(0x324)), v)
                                    v = dadd(v, dmul(dsi, ddiv(dsub(c.f64(0x128), at), span)))
                                }
                                c.setF32(0x320, d2f(v))
                            }
                            cur = c.ref(0x30)
                        } while (cur !== p)
                    }
                    newAnchor = cur
                }
            }
            anchor = newAnchor
        }
        val a = anchor
        if (a != null) {
            while (cur != null) {
                val c = cur
                if (other(c)) c.setI32(0x320, a.i32(0x324))
                cur = c.ref(0x30)
            }
        }
    }

    /** modifyWordScoreDueToChainRanker(std::string const&, float) @007dace8: blends the score
     * toward 1.1 by the word's chain-ranker weight, when the word is in the +0x3a0 map. */
    fun modifyWordScoreDueToChainRanker(chainScores: Map<String, Float>, word: String, score: Float): Float {
        val c = chainScores[word] ?: return score
        val rest = fmul(fsub(1f, c), score)
        return fadd(fmul(c, f(0x3f8ccccd)), rest) // 1.1
    }

    // --- StepScoreStats (+0xea8) -----------------------------------------------------------

    /** getStepStats(int) @007db064: grows [stats] (the +0xea8 vector) with default elements
     * until it holds index [i] and returns that element. Each new element gets owner = [ps],
     * index = its slot, and per-slot tweaks (slot 1: +0x6c = 0.1; slot >= 4: allowance 50).
     * i > 0x3f only asserts (logs); a negative i never terminates in the binary. */
    fun getStepStats(ps: MemImage, stats: MutableList<MemImage>, i: Int): MemImage {
        require(i >= 0) { "getStepStats($i): the binary loops forever" }
        while (stats.size <= i) {
            val s = StepScoreStats.construct("${ps.name}.stepStats[${stats.size}]")
            stats.add(s)
            val count = stats.size
            s.setI32(4, count - 1)
            if (count - 3 !in 0..1) {
                if (count == 2) s.setI32(0x6c, 0x3dcccccd) // 0.1f
                else if (count - 1 >= 4) s.setI32(0x68, 50)
            }
            setRef(s, 0, ps)
        }
        return stats[i]
    }

    /** {3->1, 4->0.5, 5->0.15, 6->0}: syncStepAllowances' static curve. */
    private val STEP_ALLOWANCE_CURVE = FastCurveMapper.ofBits(
        0x40400000, 0x3f800000, 0x40800000, 0x3f000000, 0x40a00000, 0x3e19999a, 0x40c00000, 0x00000000)

    /** syncStepAllowances() @007da48c: bucket step allowances from the predicted word length
     * (+0x144), mirrored (min 36) into the step stats of lengths 1..3. */
    fun syncStepAllowances(ps: MemImage, stats: MutableList<MemImage>) {
        val c = STEP_ALLOWANCE_CURVE.get(ps.f32(0x144))
        val inv = fsub(1f, c)
        var a = f2iz(fadd(fmul(c, 25f), fmul(inv, 7f)))
        ps.setI32(0x548, a)
        getStepStats(ps, stats, 1).setI32(0x68, if (a <= 0x24) 0x24 else a)
        a = f2iz(fadd(fmul(c, 45f), fmul(inv, 24f)))
        ps.setI32(0x57c, a)
        getStepStats(ps, stats, 2).setI32(0x68, if (a <= 0x24) 0x24 else a)
        a = f2iz(fadd(fmul(c, 65f), fmul(inv, 35f)))
        ps.setI32(0x5b0, a)
        getStepStats(ps, stats, 3).setI32(0x68, if (a > 0x24) a else 0x24)
        ps.setI32(0x5e4, f2iz(fadd(fmul(c, 50f), fmul(inv, 100f))))
    }

    /** preallocateSteps(int bucket, int n) @007e1ab4: bucket 1..3 -> its allowance and the
     * step stats' (min 36); >= 4 -> the last bucket's allowance only; < 1 asserts (no-op). */
    fun preallocateSteps(ps: MemImage, stats: MutableList<MemImage>, bucket: Int, n: Int) {
        val slot = when (bucket) {
            1 -> 0x548; 2 -> 0x57c; 3 -> 0x5b0
            else -> { if (bucket >= 4) ps.setI32(0x5e4, n); return }
        }
        ps.setI32(slot, n)
        getStepStats(ps, stats, bucket).setI32(0x68, if (n < 0x25) 0x24 else n)
    }

    /** branchCutoffScore(int len) @007e6e7c: 0.1 when the Moopad flag at +0x333f8 is set, else
     * the cutoff (+0x74) of the step stats for len + 1. */
    fun branchCutoffScore(ps: MemImage, stats: MutableList<MemImage>, moopadFlag: Int, len: Int): Float {
        if (moopadFlag != 0) return f(0x3dcccccd)
        return getStepStats(ps, stats, len + 1).f32(0x74)
    }

    // --- curve-based helpers ---------------------------------------------------------------

    /** {0->1, 1->0.7, 2->0.5, 3->0.3, 4->0.1}: checkTrieNodeEnderLengthMult's static curve. */
    private val ENDER_LENGTH_CURVE = FastCurveMapper.ofBits(
        0x00000000, 0x3f800000, 0x3f800000, 0x3f333333, 0x40000000, 0x3f000000, 0x40400000, 0x3e99999a,
        0x40800000, 0x3dcccccd)

    /** checkTrieNodeEnderLengthMult(int len) @007eeba8: curve(predictedLen (+0x144) - len). */
    fun checkTrieNodeEnderLengthMult(ps: MemImage, len: Int): Float =
        ENDER_LENGTH_CURVE.get(fsub(ps.f32(0x144), i2f(len)))

    /** checkTrieNodeLengthMultSatisfaction(int, float) @007eed18. */
    fun checkTrieNodeLengthMultSatisfaction(ps: MemImage, len: Int, thr: Float): Boolean =
        fcmpgt(checkTrieNodeEnderLengthMult(ps, len), thr)

    /** {1->1, 2->1, 3->0.96, 4->0.9, 5->0.85, 6->0.8, 7->0.7, 10->0.6, 15->0.4}: syncWordMultness' curve. */
    private val WORD_MULT_CURVE = FastCurveMapper.ofBits(
        0x3f800000, 0x3f800000, 0x40000000, 0x3f800000, 0x40400000, 0x3f75c28f, 0x40800000, 0x3f666666,
        0x40a00000, 0x3f59999a, 0x40c00000, 0x3f4ccccd, 0x40e00000, 0x3f333333, 0x41200000, 0x3f19999a,
        0x41700000, 0x3ecccccd)

    /** syncWordMultness() @007e0b8c: writes +0x354 (1 - n328/n324) and +0x34c (word multness).
     * Reads ints +0x19c, +0x324, +0x328, +0x32c, +0x348 (meaning unknown yet) and whether there
     * is a single Mootouch ([singleTouch]). */
    fun syncWordMultness(ps: MemImage, singleTouch: Boolean) {
        var r = 1f
        val n324 = ps.i32(0x324)
        if (n324 != 0) r = fsub(1f, fdiv(i2f(ps.i32(0x328)), i2f(n324)))
        ps.setF32(0x354, r)
        var base = 0f
        val n348 = ps.i32(0x348)
        if (n348 != 0) base = fmul(i2f((ps.i32(0x19c) - 1) / n348), 0.5f)
        var v = fadd(base, fmul(ps.f32(0x354), 0.5f))
        val c = WORD_MULT_CURVE.get(i2f(n348))
        v = fadd(fsub(1f, c), fmul(c, v))
        val x = i2f(ps.i32(0x32c))
        val w: Float
        if (singleTouch) {
            val t = fdiv(x, 3f)
            val cl = if (fcmplt(t, 0f)) 0f else if (fcmpgt(t, 1f)) 1f else t
            w = LibM.powf(cl, f(0x3ecccccd)) // 0.4
        } else {
            val t = fdiv(x, 5f)
            w = if (fcmplt(t, 0f)) 0f else if (fcmpgt(t, 1f)) 1f else t
        }
        ps.setF32(0x34c, fsub(1f, fadd(w, fmul(fsub(1f, w), v))))
    }

    /** getNthCharPlanarCoverageTimes(int n, float& lo, float& mid, float& hi) @007e24a8: the
     * soft-index window [lo, hi] around n for the n-th char, widened by the time spread the
     * +0x27c (soft index -> time) and +0x294 (time -> soft index) curves give for n +- 0.25 s.
     * (The decompile drops the powf exponents 0.9 / 0.65 and shows the double 1.3 as 1.9125.) */
    fun getNthCharPlanarCoverageTimes(siToTime: CurveMapper, timeToSi: CurveMapper, n: Int): Triple<Float, Float, Float> {
        val x = i2f(n)
        val mid = x
        var a = LibM.powf(fdiv(x, 1.5f), f(0x3f666666)) // 0.9
        val b = d2f(dadd(f2d(LibM.powf(fmul(x, f(0x3e99999a)), f(0x3f266666))), Bits.bitsToDouble(0x3ff4cccccccccccdL)))
        if (fcmplt(b, a)) a = b
        var lo = fsub(x, a)
        val w = LibM.powf(fmul(x, f(0x3ecccccd)), f(0x3f266666)) // (0.4 x)^0.65
        var hi = fadd(mid, a)
        val w2 = fadd(w, 1.5f)
        val t = siToTime.get(mid)
        var tl = timeToSi.getInterpolated(fadd(t, f(0xbe800000.toInt())))
        var th = timeToSi.getInterpolated(fadd(t, 0.25f))
        val d = fsub(mid, w2)
        if (fcmplt(tl, d)) tl = d
        if (!fcmplt(tl, lo)) tl = lo
        lo = tl
        val u = fadd(w2, mid)
        if (fcmplt(u, th)) th = u
        if (!fcmplt(hi, th)) th = hi
        hi = th
        val l1 = fadd(mid, f(0xbdcccccd.toInt()))
        if (fcmplt(l1, lo)) lo = l1
        val h1 = fadd(mid, f(0x3dcccccd))
        if (fcmplt(hi, h1)) hi = h1
        return Triple(lo, mid, hi)
    }

    /** syncSoftIndexToTimeCurvemap() @007e0238: records each point's (soft index, time since
     * +0x168) into the curve pairs at +0x27c/+0x294 (all important points, generic index
     * +0x31c; +0x2ac gets +0x154 -> time when there is a single Mootouch), +0x2c4/+0x2dc
     * (finger 1, +0x324) and +0x2f4/+0x30c (finger 0, +0x324). [curves] = the seven
     * CurveMappers by offset. */
    fun syncSoftIndexToTimeCurvemap(
        ps: MemImage, important: List<MemImage>, finger1: List<MemImage>, finger0: List<MemImage>,
        singleTouch: Boolean, curves: Map<Int, CurveMapper>,
    ) {
        val base = ps.f64(0x168)
        fun t(p: MemImage) = d2f(dsub(p.f64(0x120), base))
        for (p in important) {
            val si = p.f32(0x31c)
            curves[0x27c]!!.put(si, t(p))
            curves[0x294]!!.put(t(p), si)
            if (singleTouch) curves[0x2ac]!!.put(p.f32(0x154), t(p))
        }
        for (p in finger1) {
            val si = p.f32(0x324)
            curves[0x2c4]!!.put(si, t(p))
            curves[0x2dc]!!.put(t(p), si)
        }
        for (p in finger0) {
            val si = p.f32(0x324)
            curves[0x2f4]!!.put(si, t(p))
            curves[0x30c]!!.put(t(p), si)
        }
    }

    // --- planar coverage checks ------------------------------------------------------------
    //
    // PlanarCoverage slots (0x28 apart): +0x410 full-word coverage, +0x438/+0x460/+0x488
    // ender coverages 1..3; +0x3d0 = std::vector of 0x28-byte nth-char coverage slots.

    /** checkFullCoverage(int key) @007ee5e4: 1 for the wildcard key, else the full-word
     * coverage of [key] floored at 0. */
    fun checkFullCoverage(full: PlanarCoverage, key: Int): Float {
        if (key == Keycode.WILDCARD) return 1f
        val c = full.getCoverage(key)
        return if (fcmplt(c, 0f)) 0f else c
    }

    /** checkCoverage(int a, int b, int c, int key) @007ee62c: the best coverage of [key] over
     * every other nth-char slot between the ones char a falls on for word lengths c and b,
     * each damped by 1 - 0.3 sqrt(|predictedLen (+0x144) - interpolated length|) (only when
     * that difference is > 0 or < -5). 1 for the wildcard key. */
    fun checkCoverage(ps: MemImage, nth: List<PlanarCoverage>, a: Int, b: Int, c: Int, key: Int): Float {
        if (key == Keycode.WILDCARD) return 1f
        var fb = 0f
        if (b >= 2) fb = fdiv(i2f(a - 1), i2f(b - 1))
        var fc = 1f
        if (c >= 2) fc = fdiv(i2f(a - 1), i2f(c - 1))
        var best = 0f
        val last = nth.size - 1
        val nf = i2f(last)
        var start = f2iz(fmul(fc, nf))
        var end = f2iz(fmul(fb, nf))
        if (start < 0) start = 0
        if (start > last) start = last
        val dStart = i2d(start)
        if (end < 0) end = 0
        if (end > last) end = last
        val span = dsub(i2d(end), dStart)
        val dc = i2d(c)
        val dd = dsub(i2d(b), dc)
        val negInf = f(0xff800000.toInt())
        var i = start
        while (true) {
            val idx = if (i > last) last else i
            val v = if (dcmple(span, 0.0)) dc else dadd(dc, dmul(dd, ddiv(dsub(i2d(idx), dStart), span)))
            val diff = fsub(ps.f32(0x144), d2f(v))
            var damp: Float
            if (!(fcmple(diff, 0f) || fcmpun(diff, 0f))) {
                damp = damped(diff, negInf)
            } else if (!(fcmpge(diff, -5f) || fcmpun(diff, -5f))) {
                damp = damped(fsub(-5f, diff), negInf)
            } else {
                damp = 1f
            }
            val m = fmul(damp, nth[idx].getCoverage(key))
            if (fcmplt(best, m)) best = m
            i = idx + 2
            if (idx >= end) break
        }
        return best
    }

    private fun damped(x: Float, negInf: Float): Float {
        val isNegInf = fcmpeq(x, negInf)
        val s = f(Bits.floatToBits(LibM.sqrtf(x)) and 0x7fffffff)
        val r = fadd(fmul(s, f(0xbe99999a.toInt())), 1f) // -0.3
        return if (isNegInf) negInf else r
    }

    /** checkTrieNodeEnderMult(int len, int key) @007eed3c: min(length mult, sqrt of ender
     * coverage 1 (+0x438) of [key]); a coverage of -inf counts as +inf, a non-positive (or NaN)
     * root as 0; negative or wildcard keys only take the length mult. */
    fun checkTrieNodeEnderMult(ps: MemImage, ender1: PlanarCoverage, len: Int, key: Int): Float {
        var lm = checkTrieNodeEnderLengthMult(ps, len)
        var r = 1f
        if (key >= 0 && key != Keycode.WILDCARD) {
            val cov = ender1.getCoverage(key)
            val s = LibM.sqrtf(cov)
            r = if (fcmpeq(cov, f(0xff800000.toInt()))) f(0x7f800000) else f(Bits.floatToBits(s) and 0x7fffffff)
            if (!fcmpgt(r, 0f)) r = 0f
        }
        if (fcmplt(r, lm)) lm = r
        return lm
    }

    /** checkTrieNodeMultiPossibleEndersCoverageSatisfaction(int which, Flag64&, float)
     * @007ee9e8: ender coverage [which] (1..3 -> +0x438/+0x460/+0x488) satisfies [thr] on one
     * of the flagged keys. Other values only assert in the binary (not ported). */
    fun checkTrieNodeMultiPossibleEndersCoverageSatisfaction(enders: List<PlanarCoverage>, which: Int, flags: Long, thr: Float): Boolean {
        require(which in 1..3) { "checkTrieNodeMultiPossibleEndersCoverageSatisfaction($which) asserts" }
        return enders[which - 1].checkCoverageSatisfaction(flags, thr)
    }

    // --- syncGenericForelookAllowances -----------------------------------------------------

    /** `Emkey::SoftIndexSortContainer` (0x20 bytes): +0 tp (null for a synthetic lead-in
     * entry), +8 f64 time, +0x10 finger (1, 0, or 2 for a synthetic finger-0 entry), +0x14 f32
     * finger-1 soft-index axis, +0x18 f32 finger-0 axis, +0x1c next (linked after sorting,
     * never read). */
    class SoftIndexEntry(val tp: MemImage?, val t: Double, val finger: Int, var s14: Float, var s18: Float)

    /** {0->0.5, 0.05->0.2, 0.1->0.1, 0.2->0.05, 0.3->0}: syncGenericForelookAllowances' curve. */
    private val LEAD_IN_CURVE = FastCurveMapper.ofBits(
        0x00000000, 0x3f000000, 0x3d4ccccd, 0x3e4ccccd, 0x3dcccccd, 0x3dcccccd, 0x3e4ccccd, 0x3d4ccccd,
        0x3e99999a, 0x00000000)

    /** syncGenericForelookAllowances() @007de9c8: merges both fingers' soft indexes into one
     * generic soft index (+0x31c) per important point, then each point's generic look-ahead
     * allowance (+0x318: how many following points it takes to advance >= 2.98 generic soft
     * index units). Writes +0x148 (total generic length) and +0x14c (the part on flag-0x1
     * points, or the total when the +0x368 byte is clear).
     *
     * Steps: one entry per important point (time +0x128, finger +0x21c, its finger-1 / finger-0
     * axis = own +0x324 / other-finger +0x320); with both fingers present, 20 + 20 synthetic
     * lead-in entries before each finger's first point (10 ms apart, axis values going
     * negative, split by the fingers' head start); gnustl std::sort by time; each synthetic
     * entry's missing axis interpolated by time between the neighbouring entries of the other
     * kind; negative tails clamped to running minimums (backwards); generic index = running sum
     * of both axes' increments, rescaled so its minimum maps to 0 when it went negative. */
    fun syncGenericForelookAllowances(ps: MemImage, important: List<MemImage>, finger1: List<MemImage>, finger0: List<MemImage>) {
        ps.setI32(0x148, 0)
        ps.setI32(0x14c, 0)
        val v = ArrayList<SoftIndexEntry>()
        for (p in important) {
            val fing = p.i8(0x21c)
            val (a, b) = if (fing == 1) p.f32(0x324) to p.f32(0x320) else p.f32(0x320) to p.f32(0x324)
            v.add(SoftIndexEntry(p, p.f64(0x128), fing, a, b))
        }
        var prev1 = 0f
        var prev2 = 0f
        if (finger1.isNotEmpty() && finger0.isNotEmpty()) {
            val base = ps.f64(0x168)
            val a = d2f(dsub(finger1[0].f64(0x128), base))
            val b = d2f(dsub(finger0[0].f64(0x128), base))
            val c = LEAD_IN_CURVE.get(f(Bits.floatToBits(fsub(a, b)) and 0x7fffffff))
            val ratio = fdiv(fadd(a, c), fadd(fadd(a, b), fadd(c, c)))
            val inv = fsub(1f, ratio)
            for (k in 0 until 20) {
                val x = fdiv(i2f(k), 20f)
                val dx = f2d(fmul(x, f(0x3e4ccccd))) // 0.2
                val nx = fsub(-0f, x)
                prev1 = fmul(ratio, nx)
                v.add(SoftIndexEntry(null, dsub(finger1[0].f64(0x128), dx), 1, prev1, 0f))
                prev2 = fmul(inv, nx)
                v.add(SoftIndexEntry(null, dsub(finger0[0].f64(0x128), dx), 2, 0f, prev2))
            }
        }
        if (v.isNotEmpty()) nintype.engine.merge.GnuSortList.sort(v) { x, y -> dcmplt(x.t, y.t) }
        interpolateLeadIns(v)
        var m1 = 999f
        var m2 = 999f
        for (k in v.indices.reversed()) {
            val e = v[k]
            if (fcmplt(e.s14, 0f) || !(fcmpge(m1, 0f) || fcmpun(m1, 0f))) { if (!fcmplt(e.s14, m1)) e.s14 = m1 else m1 = e.s14; e.s14 = m1 }
            if (fcmplt(e.s18, 0f) || !(fcmpge(m2, 0f) || fcmpun(m2, 0f))) { if (!fcmplt(e.s18, m2)) e.s18 = m2 else m2 = e.s18; e.s18 = m2 }
        }
        var acc = 0f
        var flagged = 0f
        var minSi = f(0x461c3c00) // 9999
        for (e in v) {
            val d = fadd(fsub(e.s14, prev1), fsub(e.s18, prev2))
            acc = fadd(acc, d)
            val tp = e.tp
            if (tp != null) {
                if (tp.u8(0x224) and 1 != 0) flagged = fadd(flagged, d)
                tp.setF32(0x31c, acc)
                if (fcmplt(acc, minSi)) minSi = acc
            }
            prev1 = e.s14
            prev2 = e.s18
        }
        ps.setF32(0x148, acc)
        ps.setF32(0x14c, if (ps.u8(0x368) == 0) acc else flagged)
        val t = fadd(minSi, f(0x3c23d70a)) // 0.01
        if (!(fcmpge(t, acc) || fcmpun(t, acc))) {
            val scale = fdiv(acc, fsub(acc, minSi))
            for (e in v) {
                val tp = e.tp ?: continue
                val total = ps.f32(0x148)
                tp.setF32(0x31c, fadd(total, fmul(scale, fsub(tp.f32(0x31c), total))))
            }
        }
        val lim = f(0x403eb852) // 2.98
        for (i in important.indices) {
            val p = important[i]
            var n = 0
            if (i + 1 < important.size) {
                var sum = 0f
                var prev = p.f32(0x31c)
                var j = i + 1
                while (true) {
                    val cur = important[j].f32(0x31c)
                    sum = fadd(sum, fsub(cur, prev))
                    n = j - i
                    if (fcmpge(sum, lim)) break
                    prev = cur
                    j++
                    if (j >= important.size) break
                }
            }
            p.setI32(0x318, n)
        }
    }

    /** The walk at 0x7df544: a synthetic finger-1 entry gets its finger-0 axis (+0x18) from the
     * finger-0/2 entries around it, a synthetic finger-2 entry its finger-1 axis (+0x14) from
     * the finger-1 entries around it (linear in time; the earlier neighbour's value when the
     * span is <= 0; the only neighbour's value at either end). */
    private fun interpolateLeadIns(v: List<SoftIndexEntry>) {
        var last1: SoftIndexEntry? = null
        var last2: SoftIndexEntry? = null
        var p1: SoftIndexEntry? = null
        var p2: SoftIndexEntry? = null
        fun interp(lastT: Double, lastV: Float, eT: Double, eV: Float, at: Double): Float {
            val dt = dsub(eT, lastT)
            var r = f2d(lastV)
            if (!dcmple(dt, 0.0)) r = dadd(r, dmul(dsub(f2d(eV), r), ddiv(dsub(at, lastT), dt)))
            return d2f(r)
        }
        for (e in v) {
            if (e.tp == null) { if (e.finger == 1) p1 = e else p2 = e }
            val sl = p2
            if (e.finger == 1) {
                p2 = null
                if (sl != null) {
                    val l = last1
                    sl.s14 = if (l == null) e.s14 else interp(l.t, l.s14, e.t, e.s14, sl.t)
                }
                last1 = e
            } else {
                val q = p1
                if (q != null) {
                    val l = last2
                    q.s18 = if (l == null) e.s18 else interp(l.t, l.s18, e.t, e.s18, q.t)
                }
                p2 = sl
                p1 = null
                last2 = e
            }
        }
        p1?.let { q -> q.s18 = requireNotNull(last2) { "unresolved finger-1 lead-in (the binary asserts, then crashes)" }.s18 }
        p2?.let { q -> q.s14 = requireNotNull(last1) { "unresolved finger-2 lead-in (the binary asserts, then crashes)" }.s14 }
    }

    // --- ender planar coverages ------------------------------------------------------------

    /** gatherEnderPlanarCoverage's static curves (by `which` 1..3) and walk limits. */
    private val ENDER_CURVES = arrayOf(
        FastCurveMapper.ofBits(0xbf000000.toInt(), 0x00000000, 0x00000000, 0x3f800000, 0x3f800000, 0x3e99999a, 0x3fc00000, 0x00000000),
        FastCurveMapper.ofBits(0xbe4ccccd.toInt(), 0x00000000, 0x00000000, 0x3f666666, 0x3e4ccccd, 0x3f666666, 0x3f800000, 0x3f800000,
            0x40000000, 0x3f000000, 0x40533333, 0x00000000),
        FastCurveMapper.ofBits(0x3e4ccccd, 0x3f333333, 0x3f19999a, 0x3f800000, 0x3f800000, 0x3f800000, 0x40000000, 0x3f800000,
            0x40400000, 0x3f4ccccd, 0x40800000, 0x3f000000, 0x409ccccd, 0x00000000),
    )
    private val ENDER_LIMITS = intArrayOf(0x3fc00000, 0x40133333, 0x4089999a) // 1.5, 2.3, 4.3

    /** Emkey::getalphaindex(int) @004f920c: a tail call to triechara_to_keycode. */
    fun getalphaindex(c: Int): Int = Keycode.trieCharaToKeycode(c)

    /** gatherEnderPlanarCoverage(PlanarCoverage&, MooTouchPoint* start, int which) @007e6474:
     * walks back from [start] along the any-finger prev links (+0x2c) while either the generic
     * (+0x31c) or the (blended) non-generic soft-index distance stays within the limit, and
     * records each traversed coverable key with curve(distance) * signed (score / 6)^e
     * (e = 0.3 for detached points, 0.9 otherwise) at the point's time. `which` 1..3 = ender
     * window 1, 2, 3 (other values assert and then crash in the binary). The trav vector
     * (+0x328) elements are read as (chara +8, score +0x10) pairs via [travs]. */
    fun gatherEnderPlanarCoverage(pc: PlanarCoverage, start: MemImage, which: Int, travs: (MemImage) -> List<Pair<Int, Float>>) {
        require(which in 1..3) { "gatherEnderPlanarCoverage($which) asserts" }
        val curve = ENDER_CURVES[which - 1]
        val lim = f(ENDER_LIMITS[which - 1])
        var cur: MemImage? = start
        while (cur != null) {
            val c = cur
            var d = f(Bits.floatToBits(MooTouchPointNG.distance(c, start)) and 0x7fffffff)
            val g = f(Bits.floatToBits(fsub(c.f32(0x31c), start.f32(0x31c))) and 0x7fffffff)
            if (which != 1 && !(fcmple(d, 1.5f) || fcmpun(d, 1.5f))) {
                val t = fmul(fadd(d, -2.5f), 0.5f)
                val w = if (fcmplt(t, 0f)) 0f else if (fcmpgt(t, 1f)) 1f else t
                d = fadd(fmul(g, w), fmul(d, fsub(1f, w)))
            }
            if (!(fcmple(g, lim) || fcmpun(g, lim)) && fcmpgt(d, lim)) break
            val cv = curve.get(d)
            if (!(fcmple(cv, f(0x3c23d70a)) || fcmpun(cv, f(0x3c23d70a)))) { // 0.01
                for ((chara, score) in travs(c)) {
                    val k = getalphaindex(chara)
                    if (!Keycode.isCoverable(k)) continue
                    val sc = fdiv(score, 6f)
                    val e = if (c.u8(0x225) and 0x20 != 0) f(0x3e99999a) else f(0x3f666666)
                    val p = if (!(fcmpge(sc, 0f) || fcmpun(sc, 0f))) -LibM.powf(-sc, e) else LibM.powf(sc, e)
                    pc.record(k, fmul(cv, p), c.f64(0x128))
                }
                // CovererRecorder::recordCoverer(pc, c, cv) is a no-op in this build
            }
            cur = c.ref(0x2c)
        }
    }

    /** gatherEnderPlanarCoverage(int which, PlanarCoverage&) @007e6cd0: resets [out], then
     * gathers from the last important point (+0x1b0) when there is one. */
    fun gatherEnderPlanarCoverage(important: List<MemImage>, which: Int, out: PlanarCoverage, travs: (MemImage) -> List<Pair<Int, Float>>) {
        resetPlanarCoverage(out)
        if (important.isNotEmpty()) gatherEnderPlanarCoverage(out, important.last(), which, travs)
    }

    /** gatherEnderPlanarCoverages() @007d9a70: ender windows 1..3 into +0x438/+0x460/+0x488. */
    fun gatherEnderPlanarCoverages(important: List<MemImage>, enders: List<PlanarCoverage>, travs: (MemImage) -> List<Pair<Int, Float>>) {
        for (w in 1..3) gatherEnderPlanarCoverage(important, w, enders[w - 1], travs)
    }

    /** Move-assigning a fresh PlanarCoverage() (all fields zero, no areas). */
    fun resetPlanarCoverage(pc: PlanarCoverage) {
        pc.tMin = 0.0; pc.tMax = 0.0; pc.hasTime = 0; pc.areas.clear(); pc.maxCoverage = 0f
    }
    /** {1->0.8, 2->0.6, 3->0.5, 4->0.4} and {0->1, 1->0.6, 2->0.1, 3->0}: the rough estimate's curves. */
    private val ROUGH_TOLERANCE_CURVE = FastCurveMapper.ofBits(
        0x3f800000, 0x3f4ccccd, 0x40000000, 0x3f19999a, 0x40400000, 0x3f000000, 0x40800000, 0x3ecccccd)
    private val ROUGH_FALLOFF_CURVE = FastCurveMapper.ofBits(
        0x00000000, 0x3f800000, 0x3f800000, 0x3f19999a, 0x40000000, 0x3dcccccd, 0x40400000, 0x00000000)

    /** Signed (score / 6)^e of a trav, e = 0.3 for detached points (+0x225 bit 0x20), else 0.9. */
    private fun travWeight(p: MemImage, score: Float): Float {
        val s = fdiv(score, 6f)
        val e = if (p.u8(0x225) and 0x20 != 0) f(0x3e99999a) else f(0x3f666666)
        return if (!(fcmpge(s, 0f) || fcmpun(s, 0f))) -LibM.powf(-s, e) else LibM.powf(s, e)
    }

    /** gatherRoughEstimatedPlanarCoverages() @007d9c9c: rebuilds the full-word coverage
     * (+0x410) and the 21 nth-char coverage slots (+0x3d0). Walking every point from
     * important[0] along +0x30, each coverable traversed key goes into the full coverage with
     * its signed (score / 6)^e weight, and into slot j (0..20) scaled by
     * falloff(|si - j/20 * total| * tolerance(j * total / 20)), si = the point's generic soft
     * index (+0x31c), total = +0x148. [nth] is replaced by 21 fresh slots. */
    fun gatherRoughEstimatedPlanarCoverages(ps: MemImage, first: MemImage?, full: PlanarCoverage,
                                            nth: MutableList<PlanarCoverage>, travs: (MemImage) -> List<Pair<Int, Float>>) {
        resetPlanarCoverage(full)
        nth.clear()
        repeat(21) { nth.add(PlanarCoverage()) }
        var p = first
        while (p != null) {
            val pt = p
            for (j in nth.indices) {
                val total = ps.f32(0x148)
                val fj = i2f(j)
                val r = fsub(pt.f32(0x31c), fmul(fdiv(fj, ui2f(nth.size - 1)), total))
                val pos = fdiv(fmul(fj, total), 20f)
                val d = f(Bits.floatToBits(r) and 0x7fffffff)
                val c = ROUGH_FALLOFF_CURVE.get(fmul(d, ROUGH_TOLERANCE_CURVE.get(pos)))
                if (fcmple(c, f(0x3c23d70a)) || fcmpun(c, f(0x3c23d70a))) continue
                for ((chara, score) in travs(pt)) {
                    val k = getalphaindex(chara)
                    if (!Keycode.isCoverable(k)) continue
                    nth[j].record(k, fmul(c, travWeight(pt, score)), pt.f64(0x128))
                }
            }
            for ((chara, score) in travs(pt)) {
                val k = getalphaindex(chara)
                if (!Keycode.isCoverable(k)) continue
                full.record(k, travWeight(pt, score), pt.f64(0x128))
            }
            p = pt.ref(0x30)
        }
    }
    /** gatherSlowSlideCoverage(PlanarCoverage&, int n) @007e1fb0 (single-Mootouch words only):
     * a tent window over slide position (+0x154, chars * 1/0.63) around char n, as wide as
     * getNthCharPlanarCoverageTimes' spread (at least +-0.1, plus 0.5 position units of
     * falloff), weighting each traversed key of the points in it (walking +0x30 from
     * important[0] until the position passes the window). */
    fun gatherSlowSlideCoverage(pc: PlanarCoverage, singleTouch: Boolean, first: MemImage?, n: Int,
                                travs: (MemImage) -> List<Pair<Int, Float>>) {
        if (!singleTouch) return
        val x = i2f(n)
        var a = LibM.powf(fdiv(x, f(0x3fa66666)), f(0x3f666666)) // (x/1.3)^0.9
        val b = d2f(dadd(f2d(LibM.powf(fmul(x, f(0x3e99999a)), f(0x3f266666))), Bits.bitsToDouble(0x3ff4cccccccccccdL)))
        if (fcmplt(b, a)) a = b
        var hi = fadd(x, a)
        val h1 = fadd(x, f(0x3dcccccd))
        if (fcmplt(hi, h1)) hi = h1
        var lo = fsub(x, a)
        val l1 = fadd(x, f(0xbdcccccd.toInt()))
        if (fcmplt(l1, lo)) lo = l1
        val k = f(0x3fcb2cb3) // 1.5873 (1 / 0.63)
        val xk = fmul(x, k)
        val hk = fmul(hi, k)
        val hiK = fadd(hk, 0.5f)
        val lk = fmul(lo, k)
        val loK = fadd(lk, -0.5f)
        val cm = CurveMapper().put(loK, 0f).put(lk, 0.5f).put(xk, 1f).put(hk, 0.5f).put(hiK, 0f)
        var p = first
        while (p != null) {
            val pt = p
            val v = pt.f32(0x154)
            if (!(fcmplt(v, loK) || fcmpun(v, loK))) {
                val c = cm.get(v)
                if (!(fcmple(c, f(0x3c23d70a)) || fcmpun(c, f(0x3c23d70a)))) {
                    for ((chara, score) in travs(pt)) {
                        val key = getalphaindex(chara)
                        if (!Keycode.isCoverable(key)) continue
                        pc.record(key, fmul(c, travWeight(pt, score)), pt.f64(0x128))
                    }
                }
            }
            if (fcmpge(v, hiK)) break
            p = pt.ref(0x30)
        }
    }

    /** getSlowSlideCoverage(int n) @007e1d9c: the +0x3e8 cache, grown with fresh coverages
     * gathered by gatherSlowSlideCoverage(slot) until it holds n; returns slot n. */
    fun getSlowSlideCoverage(cache: MutableList<PlanarCoverage>, singleTouch: Boolean, first: MemImage?, n: Int,
                             travs: (MemImage) -> List<Pair<Int, Float>>): PlanarCoverage {
        require(n >= 0) { "getSlowSlideCoverage($n): the binary loops forever" }
        while (cache.size <= n) {
            val pc = PlanarCoverage()
            cache.add(pc)
            gatherSlowSlideCoverage(pc, singleTouch, first, cache.size - 1, travs)
        }
        return cache[n]
    }
    /** gatherAutoCompleteEnderPlanarCoverage(PlanarCoverage&, int m) @007e5f30: resets [pc],
     * then walks back from the last important point (+0x2c links) like
     * gatherEnderPlanarCoverage (blending generic and non-generic distance when m != 0,
     * stopping once both pass hi = 1.2 m + 1.5). Points whose flags (+0x224, or those of the
     * +0xc point when unset) carry bit 0x2 add their keys weighted by a falling edge
     * 1 - (d - m) / (hi - m) for d in [m - 0.01, hi). (The rising edge below m is coded as
     * d < lo && d < m -> (d - lo) / (m - lo), which is negative and so never records.) */
    fun gatherAutoCompleteEnderPlanarCoverage(pc: PlanarCoverage, important: List<MemImage>, m: Int,
                                              travs: (MemImage) -> List<Pair<Int, Float>>) {
        resetPlanarCoverage(pc)
        if (important.isEmpty()) return
        val start = important.last()
        val x = i2f(m)
        val lo = fadd(fmul(x, f(0x3f333333)), f(0xbe99999a.toInt())) // 0.7 m - 0.3
        val hi = fadd(fmul(x, f(0x3f99999a)), 1.5f) // 1.2 m + 1.5
        val spanLo = fsub(x, lo)
        val xm = fadd(x, f(0xbc23d70a.toInt())) // m - 0.01
        val spanHi = fsub(hi, x)
        var cur: MemImage? = start
        while (cur != null) {
            val c = cur
            var d = f(Bits.floatToBits(MooTouchPointNG.distance(c, start)) and 0x7fffffff)
            val g = f(Bits.floatToBits(fsub(c.f32(0x31c), start.f32(0x31c))) and 0x7fffffff)
            if (m != 0 && !(fcmple(d, 1.5f) || fcmpun(d, 1.5f))) {
                val t = fmul(fadd(d, -2.5f), 0.5f)
                val w = if (fcmplt(t, 0f)) 0f else if (fcmpgt(t, 1f)) 1f else t
                d = fadd(fmul(g, w), fmul(d, fsub(1f, w)))
            }
            if (!(fcmple(g, hi) || fcmpun(g, hi)) && fcmpgt(d, hi)) return
            var bits = c.u16(0x224) ushr 1
            if (bits and 1 == 0) { val link = c.ref(0xc); if (link != null) bits = link.u16(0x224) ushr 1 }
            if (bits and 1 != 0) {
                val w: Float? = if (!(fcmpge(d, lo) || fcmpun(d, lo)) && !(fcmpge(d, x) || fcmpun(d, x))) {
                    fdiv(fsub(d, lo), spanLo)
                } else if (!(fcmplt(d, xm) || fcmpun(d, xm)) && !(fcmpge(d, hi) || fcmpun(d, hi))) {
                    fsub(1f, fdiv(fsub(d, x), spanHi))
                } else null
                if (w != null && !(fcmple(w, f(0x3c23d70a)) || fcmpun(w, f(0x3c23d70a)))) {
                    for ((chara, score) in travs(c)) {
                        val k = getalphaindex(chara)
                        if (!Keycode.isCoverable(k)) continue
                        pc.record(k, fmul(w, travWeight(c, score)), c.f64(0x128))
                    }
                }
            }
            cur = c.ref(0x2c)
        }
    }

    /** getAutocompleteEnderCoverage(int m) @007e3334: the +0x400 cache, grown like
     * getSlowSlideCoverage with gatherAutoCompleteEnderPlanarCoverage(slot). */
    fun getAutocompleteEnderCoverage(cache: MutableList<PlanarCoverage>, important: List<MemImage>, m: Int,
                                     travs: (MemImage) -> List<Pair<Int, Float>>): PlanarCoverage {
        require(m >= 0) { "getAutocompleteEnderCoverage($m): the binary loops forever" }
        while (cache.size <= m) {
            val pc = PlanarCoverage()
            cache.add(pc)
            gatherAutoCompleteEnderPlanarCoverage(pc, important, cache.size - 1, travs)
        }
        return cache[m]
    }
    /** The "acforce" NeoContextHolder<bool> bytes [0] and [1] that gatherNthCharPlanarCoverage
     * reads (Shing::shing_obtainSplechtref<bool>, cached in a function-local static). A booted
     * engine (re/out/snapshots/after_init.v2.snap) holds 0, 0: no forced-autocomplete mode. */
    const val ACFORCE_B0 = 0
    const val ACFORCE_B1 = 0

    /** The tent gatherNthCharPlanarCoverage and gatherNthCharDirectionalCoverage share: a
     * CurveMapper over generic soft-index distance (0 at loK, 0.5 at lo, 1 at n, 0.5 at hi, 0 at
     * hiK), with the near/far feet loK and hiK. */
    fun nthCharTent(siToTime: CurveMapper, timeToSi: CurveMapper, n: Int): Triple<CurveMapper, Float, Float> {
        val x = i2f(n)
        var a = LibM.powf(fdiv(x, 1.5f), f(0x3f666666))
        val b = LibM.powf(fmul(x, f(0x3e99999a)), f(0x3f266666))
        val w = LibM.powf(fmul(x, f(0x3ecccccd)), f(0x3f266666))
        val t = siToTime.get(x)
        var tl = timeToSi.getInterpolated(fadd(t, f(0xbe800000.toInt())))
        var th = timeToSi.getInterpolated(fadd(t, 0.25f))
        val b2 = d2f(dadd(f2d(b), Bits.bitsToDouble(0x3ff4cccccccccccdL)))
        val w2 = fadd(w, 1.5f)
        val up = fadd(x, w2)
        if (fcmplt(b2, a)) a = b2
        val xa = fadd(x, a)
        if (fcmplt(up, th)) th = up
        var hi = if (fcmplt(xa, th)) th else xa
        val h1 = fadd(x, f(0x3dcccccd))
        if (fcmplt(hi, h1)) hi = h1
        val hiK = fadd(hi, 0.5f)
        val dn = fsub(x, w2)
        val xs = fsub(x, a)
        if (fcmplt(tl, dn)) tl = dn
        var lo = if (fcmplt(tl, xs)) tl else xs
        val l1 = fadd(x, f(0xbdcccccd.toInt()))
        if (fcmplt(l1, lo)) lo = l1
        val loK = fadd(lo, -0.5f)
        return Triple(CurveMapper().put(loK, 0f).put(lo, 0.5f).put(x, 1f).put(hi, 0.5f).put(hiK, 0f), loK, hiK)
    }

    /** gatherNthCharPlanarCoverage(PlanarCoverage&, int n) @007e534c: a tent over generic
     * soft-index distance from important[0] around char n (half-height at the
     * getNthCharPlanarCoverageTimes-style bounds, 0 half a unit beyond), adding each traversed
     * key of the points under it (walking +0x30 until the distance passes the far foot).
     * With both acforce bytes set only points with flag 0x1 count. The two extra
     * CurveMapper::get calls on +0x2dc / +0x30c have unused results (no side effects). */
    fun gatherNthCharPlanarCoverage(pc: PlanarCoverage, important: List<MemImage>, siToTime: CurveMapper,
                                    timeToSi: CurveMapper, n: Int, travs: (MemImage) -> List<Pair<Int, Float>>,
                                    acforce0: Int = ACFORCE_B0, acforce1: Int = ACFORCE_B1) {
        if (important.isEmpty()) return
        val (cm, loK, hiK) = nthCharTent(siToTime, timeToSi, n)
        val both = acforce0 != 0 && acforce1 != 0
        val first = important[0]
        var p: MemImage? = first
        while (p != null) {
            val pt = p
            val d = f(Bits.floatToBits(fsub(pt.f32(0x31c), first.f32(0x31c))) and 0x7fffffff)
            val inWindow = !(fcmplt(d, loK) || fcmpun(d, loK)) && (!both || pt.u16(0x224) and 1 != 0)
            if (inWindow) {
                val c = cm.get(d)
                if (!(fcmple(c, f(0x3c23d70a)) || fcmpun(c, f(0x3c23d70a)))) {
                    for ((chara, score) in travs(pt)) {
                        val k = getalphaindex(chara)
                        if (!Keycode.isCoverable(k)) continue
                        pc.record(k, fmul(c, travWeight(pt, score)), pt.f64(0x128))
                    }
                }
            }
            if (fcmpge(d, hiK)) break
            p = pt.ref(0x30)
        }
    }

    /** getNthCharPlanarCoverage(int n) @007e5138: the +0x3dc cache, grown like
     * getSlowSlideCoverage with gatherNthCharPlanarCoverage(slot). */
    fun getNthCharPlanarCoverage(cache: MutableList<PlanarCoverage>, important: List<MemImage>, siToTime: CurveMapper,
                                 timeToSi: CurveMapper, n: Int, travs: (MemImage) -> List<Pair<Int, Float>>): PlanarCoverage {
        require(n >= 0) { "getNthCharPlanarCoverage($n): the binary loops forever" }
        while (cache.size <= n) {
            val pc = PlanarCoverage()
            cache.add(pc)
            gatherNthCharPlanarCoverage(pc, important, siToTime, timeToSi, cache.size - 1, travs)
        }
        return cache[n]
    }
    /** getAutocompleteStarterCoverage(int n) @007e5af4: with PathSearch +0x368 clear, the
     * nth-char coverage (getNthCharPlanarCoverage); else the +0x3f4 cache, grown the same way
     * but gathering with the "acforce" holder forced to (1, 1) (only points with flag 0x1). */
    fun getAutocompleteStarterCoverage(acMode: Int, nthCache: MutableList<PlanarCoverage>, acCache: MutableList<PlanarCoverage>,
                                       important: List<MemImage>, siToTime: CurveMapper, timeToSi: CurveMapper, n: Int,
                                       travs: (MemImage) -> List<Pair<Int, Float>>): PlanarCoverage {
        if (acMode == 0) return getNthCharPlanarCoverage(nthCache, important, siToTime, timeToSi, n, travs)
        require(n >= 0) { "getAutocompleteStarterCoverage($n): the binary loops forever" }
        while (acCache.size <= n) {
            val pc = PlanarCoverage()
            acCache.add(pc)
            gatherNthCharPlanarCoverage(pc, important, siToTime, timeToSi, acCache.size - 1, travs, 1, 1)
        }
        return acCache[n]
    }

    // --- Mootouch-level setup -------------------------------------------------------------

    /** syncTouchIslandness() @007dcf98: groups the strokes into islands of transitively
     * time-overlapping ones (Mootouch +0xdc = island id, assigned in list order) and stores
     * the island count at +0x19c. */
    fun syncTouchIslandness(ps: MemImage, mts: List<MemImage>) {
        for (m in mts) m.setI32(0xdc, -1)
        var island = 0
        outer@ for (m in mts) {
            if (m.i32(0xdc) != -1) continue
            for (o in mts) {
                if (o !== m && MootouchOps.timeOverlapsWith(m, o) && o.i32(0xdc) != -1) { m.setI32(0xdc, o.i32(0xdc)); continue@outer }
            }
            m.setI32(0xdc, island)
            for (o in mts) if (o !== m && MootouchOps.timeOverlapsWith(m, o)) o.setI32(0xdc, island)
            island++
        }
        ps.setI32(0x19c, island)
    }

    /** predictWordLength() @007d9958: +0x140 = summed Mootouch::predictCharCount; +0x150 /
     * +0x154 = the largest same-finger soft index (+0x324) of finger-1 / other important
     * points (+0x1bc), from -1; +0x158/+0x15c = those + 1; +0x144 = both + 2; +0x160 = 0. */
    fun predictWordLength(ps: MemImage, mtPoints: List<List<MemImage>>, important: List<MemImage>) {
        ps.setI32(0x140, 0)
        for (pts in mtPoints) ps.setF32(0x140, fadd(MootouchOps.predictCharCount(pts), ps.f32(0x140)))
        ps.setI32(0x160, 0)
        var m1 = -1f
        var m0 = -1f
        ps.setF32(0x150, m1); ps.setF32(0x154, m0)
        for (p in important) {
            val si = p.f32(0x324)
            if (p.u8(0x21c) == 1) { if (fcmplt(m1, si)) m1 = si; ps.setF32(0x150, m1) }
            else { if (fcmplt(m0, si)) m0 = si; ps.setF32(0x154, m0) }
        }
        ps.setF32(0x158, fadd(m1, 1f))
        ps.setF32(0x15c, fadd(m0, 1f))
        ps.setF32(0x144, fadd(fadd(m1, m0), 2f))
    }

    /** checkIsGoodOverlapper(Mootouch& m) @007dbca8: m may overlap the other strokes: each
     * earlier stroke (list order, until m's id +0xc0) raises a running lowest y; m must sit
     * lower than that + 0.23 key heights; later strokes must start >= 1.4 key widths to the
     * right with m lower than their lowest point, or else m lower than their lowest + 0.23
     * key heights; and no time-overlapping stroke may enclose m or be enclosed by it.
     * [mtPoints] = each stroke's +0x244 points; [keyHeight]/[keyWidth] = Moopad 0x1f4a0/0x1f4a4
     * (curboardVertlen and the key width). */
    fun checkIsGoodOverlapper(mts: List<MemImage>, mtPoints: List<List<MemImage>>, m: MemImage, mPoints: List<MemImage>,
                              keyHeight: Float, keyWidth: Float): Boolean {
        val low = MootouchOps.lowestYPoint(mPoints)
        val start = MootouchOps.getStartpos(m)
        val thr = fmul(keyHeight, f(0x3e6b851f)) // 0.23
        var seenSelf = false
        var maxBefore = 0f
        for ((k, o) in mts.withIndex()) {
            if (o.i32(0xc0) == m.i32(0xc0)) {
                val t = fadd(thr, maxBefore)
                seenSelf = true
                if (fcmple(low, t) || fcmpun(low, t)) return false
                continue
            }
            val ol = MootouchOps.lowestYPoint(mtPoints[k])
            if (seenSelf) {
                val dx = fdiv(fsub(MootouchOps.getStartpos(o).x, start.x), keyWidth)
                val far = fcmpge(dx, f(0x3fb33333)) || fcmpun(dx, f(0x3fb33333)) // 1.4
                if (!far) { if (!fcmpgt(low, fadd(thr, ol))) return false }
                else if (fcmple(low, ol) || fcmpun(low, ol)) return false
            } else if (fcmplt(maxBefore, ol)) maxBefore = ol
            if (MootouchOps.timeOverlapsWith(o, m)) {
                if (MootouchOps.timeEncloses(o, m)) return false
                if (MootouchOps.timeEncloses(m, o)) return false
            }
        }
        return true
    }
    /** syncTouchLRNess() @007dd09c: which thumb each stroke is (Mootouch +0xec: 1 left,
     * 2 right). In start-time order (gnustl std::sort), an unassigned stroke takes its side from
     * its mean x vs the board middle (Moopad i32 +0x1f490 / 2); every unassigned stroke that
     * time-overlaps it (Mootouch::timeOverlaps, margin 0.01) gets the other side. Finally each
     * stroke's side is copied to the Moopad's live touch with the same id (+0xc0), via
     * [findTouchByUniqueID] (Moopad::findTouchByUniqueID: its touch map, then its list). */
    fun syncTouchLRNess(mts: List<MemImage>, mtPoints: List<List<MemImage>>, boardWidth: Int,
                        findTouchByUniqueID: (Int) -> MemImage?) {
        for (m in mts) m.setU8(0xec, 0)
        val order = mts.indices.toMutableList()
        nintype.engine.merge.GnuSortList.sort(order) { a, b -> dcmplt(mts[a].f64(0xf8), mts[b].f64(0xf8)) }
        val mid = i2f(boardWidth / 2)
        val margin = f(0x3c23d70a) // 0.01
        for (k in order) {
            val m = mts[k]
            if (m.u8(0xec) == 0) {
                val x = MootouchOps.getAveragePos(m, mtPoints[k]).x
                m.setU8(0xec, if (fcmpge(x, mid) || fcmpun(x, mid)) 2 else 1)
            }
            for (o in mts) {
                if (o.u8(0xec) == 0 && MootouchOps.timeOverlaps(o, m, margin)) o.setU8(0xec, if (m.u8(0xec) == 1) 2 else 1)
            }
        }
        for (m in mts) findTouchByUniqueID(m.i32(0xc0))?.setU8(0xec, m.u8(0xec))
    }
    /** {0.05->0, 0.15->1}: syncDoubleLetterAllowances' curve. */
    private val DOUBLE_LETTER_CURVE = FastCurveMapper.ofBits(0x3d4ccccd, 0x00000000, 0x3e19999a, 0x3f800000)

    /** syncDoubleLetterAllowances() @007dddf8: the double-letter factor (+0x16c) of each
     * important point. With Moopad Xnl +0x2b578 set (Xnl::val == 1, the u8 at Xnl+0xc) it is
     * first raised to +0x1a8. With Xnl +0x2b544 set: points of strokes without the +0x102
     * byte restart from 0, and those pausing (+0x158) longer than 1.6x the mean pause, with no
     * other stroke active within 0.04 s, get curve(pause). Each stroke's point factors are then
     * copied to its live Moopad touch (Moopad::findTouchByUniqueID + takeDoubleLetterFactorings).
     * [owners] maps a point to its owner Mootouch (+8). */
    fun syncDoubleLetterAllowances(important: List<MemImage>, mts: List<MemImage>, mtPoints: List<List<MemImage>>,
                                   xnlRaise: Int, xnlPause: Int, findTouchByUniqueID: (Int) -> Pair<MemImage, List<MemImage>>?) {
        if (important.isEmpty()) return
        if (xnlRaise == 1) for (p in important) { var v = p.f32(0x16c); if (fcmplt(v, p.f32(0x1a8))) v = p.f32(0x1a8); p.setF32(0x16c, v) }
        if (xnlPause != 1) return
        var sum = 0f
        for (p in important) {
            if (p.ref(8)!!.u8(0x102) != 0) continue
            p.setI32(0x16c, 0)
            sum = fadd(sum, p.f32(0x158))
        }
        val avg = fmul(fdiv(sum, ui2f(important.size)), f(0x3fcccccd)) // 1.6
        val gap = Bits.bitsToDouble(0x3fa47ae140000000L) // the double literal 0.04000000059604645 (0.04f widened)
        val nGap = -gap
        pts@ for (p in important) {
            val v = p.f32(0x158)
            if (fcmple(v, avg) || fcmpun(v, avg)) continue
            val owner = p.ref(8)!!
            if (owner.u8(0x102) != 0) continue
            val t = p.f64(0x128)
            for (m in mts) {
                if (owner === m) continue
                val hi = dadd(m.f64(0x110), gap)
                if (dcmpge(t, hi) || dcmpun(t, hi)) continue
                if (dcmpgt(t, dadd(m.f64(0xf8), nGap))) continue@pts
            }
            p.setF32(0x16c, DOUBLE_LETTER_CURVE.get(v))
        }
        for ((k, m) in mts.withIndex()) {
            val (_, livePts) = findTouchByUniqueID(m.i32(0xc0)) ?: continue
            MootouchOps.takeDoubleLetterFactorings(livePts, mtPoints[k])
        }
    }
    /** Linear time interpolation of a soft index as the binary computes it:
     * v = f2d(v0); if !(t1 - t0 <= 0) v += ratio * (f2d(v1) - v) (or (f2d(v1) - v) * ratio). */
    private fun interpSi(t0: Double, v0: Float, t1: Double, v1: Float, t: Double, ratioFirst: Boolean): Float {
        val span = dsub(t1, t0)
        var v = f2d(v0)
        if (!dcmple(span, 0.0)) {
            val ratio = ddiv(dsub(t, t0), span)
            val diff = dsub(f2d(v1), v)
            v = dadd(v, if (ratioFirst) dmul(ratio, diff) else dmul(diff, ratio))
        }
        return d2f(v)
    }

    /** MooTouchPoint::syncSameFingSoftIndexToParentForCKPoint() @0095d69c (detached points
     * only, +0x225 bit 0x20): the same-finger soft index (+0x324) interpolated by time between
     * the parent (+0xc) and its previous (+0x24) or next (+0x28) important same-finger point,
     * on whichever side the point lies; the parent's own value without that neighbour. */
    fun syncSameFingSoftIndexToParentForCKPoint(tp: MemImage) {
        if (tp.u8(0x225) and 0x20 == 0) return
        val par = requireNotNull(tp.ref(0xc)) { "detached point without parent (the binary asserts, then crashes)" }
        val t = tp.f64(0x128)
        val pt = par.f64(0x128)
        if (!(dcmpge(t, pt) || dcmpun(t, pt))) {
            val prev = par.ref(0x24)
            if (prev == null) tp.setI32(0x324, par.i32(0x324))
            else tp.setF32(0x324, interpSi(prev.f64(0x128), prev.f32(0x324), pt, par.f32(0x324), t, true))
        } else {
            val nxt = par.ref(0x28)
            if (nxt == null) tp.setI32(0x324, par.i32(0x324))
            else tp.setF32(0x324, interpSi(pt, par.f32(0x324), nxt.f64(0x128), nxt.f32(0x324), t, true))
        }
    }

    /** syncCKPointSoftIndexes() @007dfe74: syncs every important point's (+0x1b0) same-finger
     * index to its parent, then gives the detached points between consecutive
     * important-for-finger points (+0x34d) a generic soft index (+0x31c) interpolated by time
     * between them, and those after the last one its value. */
    fun syncCKPointSoftIndexes(important: List<MemImage>) {
        var anchor: MemImage? = null
        for (p in important) {
            syncSameFingSoftIndexToParentForCKPoint(p)
            if (p.u8(0x34d) == 0) continue
            val a = anchor
            if (a != null) {
                var cur = a.ref(0x30)
                while (cur !== p) {
                    val c = requireNotNull(cur) { "chain does not reach the next anchor (the binary asserts, then crashes)" }
                    if (c.u8(0x225) and 0x20 != 0)
                        c.setF32(0x31c, interpSi(a.f64(0x128), a.f32(0x31c), p.f64(0x128), p.f32(0x31c), c.f64(0x128), false))
                    cur = c.ref(0x30)
                }
            }
            anchor = p
        }
        val a = anchor ?: return
        var cur = a.ref(0x30)
        while (cur != null) {
            if (cur.u8(0x225) and 0x20 != 0) cur.setI32(0x31c, a.i32(0x31c))
            cur = cur.ref(0x30)
        }
    }
    // --- queue --------------------------------------------------------------------------

    /** getSlotForBester(int len) @007dadf8: the +0x614 slot for word length [len] (index
     * len - 1), growing the vector with empty slots. len < 1 asserts in the binary. */
    fun getSlotForBester(st: SearchState, len: Int): Int {
        require(len >= 1) { "getSlotForBester($len) asserts" }
        while (st.bestByLength.size < len) st.bestByLength.add(null)
        return len - 1
    }

    /** pushQueue(Refcounter<PredPath> const&) @007da70c: queues a path. Debug trace: when the
     * Moopad trace word is set, equals the path's word and +0x9cc is clear, the path is kept in
     * +0x9d0. A path of pathsize (+0x2a) >= 2 that beats the best path (+0x610) replaces it;
     * one of pathsize >= 1 likewise for its word length's best slot. Then the step stats of
     * that length record it (StepScoreStats::recordQueue), it joins the queue and best-of heaps
     * of the bucket for its length (2 -> bucket 1, 3 -> 2, >= 4 -> 3, else 0), the best-of heap
     * is cut back to 5 (15 when the bucket's allowance > 50) by popping its top, the bucket's
     * push counter (+0x30) is bumped, and the best score seen (+0x664) raised; the first push only
     * sets the +0x660 flag, it does not write +0x664. */
    fun pushQueue(st: SearchState, pp: MemImage) {
        val ps = st.ps
        if (st.traceWord.isNotEmpty()) {
            if (st.word(pp).contentEquals(st.traceWord) && ps.u8(0x9cc) == 0) st.traceHits.add(pp)
        }
        val len = st.wordLength(pp)
        if (pp.i8(0x2a) >= 2) {
            val b = st.bestPath
            if (b == null || SearchState.beats(pp, b)) { if (b !== pp) st.bestPath = pp }
        }
        if (pp.i8(0x2a) >= 1) {
            val k = getSlotForBester(st, len)
            val b = st.bestByLength[k]
            if (b == null || SearchState.beats(pp, b)) { if (b !== pp) st.bestByLength[k] = pp }
        }
        val s = getStepStats(ps, st.stepStats, len)
        StepScoreStats.recordQueue(s, st.heapOf(s), st.countsOf(s), ps.i32(0x4fc), pp, st.word)
        val bi = when { len >= 4 -> 3; len == 2 -> 1; len == 3 -> 2; else -> 0 }
        val bk = st.buckets[bi]
        GnuHeap.push(bk.queue, pp, SearchState.BY_SCORE)
        GnuHeap.push(bk.best, pp, SearchState.BY_SCORE)
        val base = BUCKETS + BUCKET_STRIDE * bi
        val cap = if (ps.i32(base + 8) > 0x32) 15 else 5
        if (bk.best.size > cap) GnuHeap.pop(bk.best, SearchState.BY_SCORE)
        ps.setI32(base + 0x30, ps.i32(base + 0x30) + 1)
        if (ps.u8(0x660) != 0 && !fcmplt(pp.f32(0x74), ps.f32(0x664))) ps.setF32(0x664, pp.f32(0x74))
        ps.setU8(0x660, 1)
    }
    /** topStep(QueueBucket*&) @007ecf4c: pops the best path of the first bucket that is under
     * its step allowance (+0x2c < +8) and not empty; returns it and that bucket's index, or
     * null and bucket 3 when none qualifies. */
    fun topStep(st: SearchState): Pair<MemImage?, Int> {
        val ps = st.ps
        for (b in 0 until 4) {
            val base = BUCKETS + BUCKET_STRIDE * b
            if (ps.i32(base + 0x2c) < ps.i32(base + 8) && st.buckets[b].queue.isNotEmpty())
                return GnuHeap.pop(st.buckets[b].queue, SearchState.BY_SCORE) to b
        }
        return null to 3
    }
    /** regResult(PredPath&) @007ea0c4: registers a finished path. A NaN score is a debug trap:
     * the path becomes (or joins, when +0x9cc is already set) the trace list +0x9d0, +0x9cc is
     * set, and PredPath::print logs it. Otherwise: result tracing (Moopad +0x333e0) keeps paths
     * whose word is the trace word; the best result score (+0x668) is raised, the result
     * counter (+0x65c) bumped, and the word's entry in the results map (+0x620) takes the path
     * unless it already holds one with a score >= it. */
    fun regResult(st: SearchState, pp: MemImage) {
        val ps = st.ps
        val sc = pp.f32(0x74)
        if (fcmpun(sc, sc)) {
            if (ps.u8(0x9cc) == 0) st.traceHits.clear()
            st.traceHits.add(pp)
            ps.setU8(0x9cc, 1)
            return
        }
        if (st.resultTraceOn && st.word(pp).contentEquals(st.resultTraceWord) && ps.u8(0x9cc) == 0) st.traceHits.add(pp)
        if (fcmplt(ps.f32(0x668), sc)) ps.setF32(0x668, sc)
        ps.setI32(0x65c, ps.i32(0x65c) + 1)
        val w = StrKey(st.word(pp))
        val cur = st.results[w]
        if (cur == null) { st.results[w] = pp; return }
        if (!(fcmpge(cur.f32(0x74), sc) || fcmpun(cur.f32(0x74), sc)) && cur !== pp) st.results[w] = pp
    }
    /** listResults(int n) @007eb0b4: rebuilds the ranked list +0x638 with the best [n] results
     * (by score, a pathpointer_comparer heap fed in word order). A result that is an apostrophe
     * variant (+0x34) whose un-auto-apostrophe word is also a result first gets flag 0x200
     * (byte +0x30 bit 1) and its final score recomputed (PredPath::determineFinalScore). */
    fun listResults(st: SearchState, n: Int): List<MemImage> {
        val list = ArrayList<MemImage>()
        val heap = ArrayList<MemImage>()
        for ((_, pp) in st.results) {
            if (pp.u8(0x34) != 0) {
                if (st.results.containsKey(StrKey(st.unAutoAposWord(pp)))) {
                    pp.setU8(0x30, pp.u8(0x30) or 2)
                    st.determineFinalScore(pp)
                }
            }
            GnuHeap.push(heap, pp, SearchState.BY_SCORE)
        }
        val k = minOf(n, heap.size)
        var i = 1
        if (k > 0) while (heap.isNotEmpty()) {
            list.add(heap[0])
            GnuHeap.pop(heap, SearchState.BY_SCORE)
            if (i >= k) break
            i++
        }
        st.resultList.clear()
        st.resultList.addAll(list)
        return list
    }
    /** requireTopResult() @007eab08: listResults(min(result count, 9)) unless the ranked list
     * already holds that many (unsigned compare). */
    fun requireTopResult(st: SearchState) {
        val n = minOf(st.results.size, 9)
        if (Integer.compareUnsigned(st.resultList.size, n) >= 0) return
        listResults(st, n)
    }

    /** getTopResultantPath() @007e2fa8: requireTopResult, then the first ranked result or null. */
    fun getTopResultantPath(st: SearchState): MemImage? {
        requireTopResult(st)
        return st.resultList.firstOrNull()
    }

    /** topScore() @007ea904: the first ranked result's score, 0 without results. */
    fun topScore(st: SearchState): Float {
        requireTopResult(st)
        return st.resultList.firstOrNull()?.f32(0x74) ?: 0f
    }
    /** maybeBranch(PredPath&, Mootouch&, MooTouchPoint&, TravKeyData&, double, double,
     * BranchmentData&) @007e6eb8: tries each branching mode the BranchmentData allows: plain
     * (+0x23 -> mode 0), +0x24 (mode 1), +0x25 (mode 2) and one skip branch (mode 3) per skip
     * target char (vector<char> +0x28), each via [inner] = maybeBranchInner(..., mode, char).
     * Every try bumps a FunnelStat pair in the PathSearch (attempts +0x74c/+0x760/+0x774/+0x788,
     * and +0x750/+0x764/+0x778/+0x78c clamped to >= 0 then incremented). Returns the summed
     * branch counts. */
    fun maybeBranch(ps: MemImage, plain: Int, alt: Int, apos: Int, skips: List<Int>, inner: (mode: Int, c: Int) -> Int): Int {
        fun bump(tries: Int) = ps.setI32(tries, ps.i32(tries) + 1)
        fun after(done: Int) {
            var v = ps.i32(done)
            if (v < 0) { v = 0; ps.setI32(done, 0) }
            ps.setI32(done, v + 1)
        }
        var r = 0
        if (plain != 0) { bump(0x74c); r = inner(0, 0); after(0x750) }
        if (alt != 0) { bump(0x760); r += inner(1, 0); after(0x764) }
        if (apos != 0) { bump(0x774); r += inner(2, 0); after(0x778) }
        for (c in skips) { bump(0x788); r += inner(3, c); after(0x78c) }
        return r
    }
    /** step()'s calls out of the search driver. */
    interface StepCallees {
        fun maximumPathLength(pp: MemImage): Int
        fun seekNextStep(pp: MemImage, mt: MemImage, second: Boolean): Int
        fun seekPreordainedNextSteps(pp: MemImage)
    }

    /** step() @007ed1ec: one search step. Pops the best path of the first bucket with
     * allowance left (topStep; none -> nothing to do). A path of pathsize >= 2 whose word is
     * done in the word dedup (+0x4b0) is dropped and the next one popped; otherwise both its
     * word minus the last char and its word are registered there, the word's count is bumped
     * and the word is done once the count reaches +0x4fc. Then: the bucket's step counter
     * (+0x2c) and the global one (+0xe8c) are bumped; a non-empty word logs an Arrival {step,
     * score, +0x138, +0x130, +0x134} in +0xe90 (first time: name and PredPath::maximumPathLength),
     * and the path's +0x130 = max(+0x130, arrivals), +0x134 += arrivals - 1, +0x114 = arrivals;
     * the length's step stats list it in +0x78 (count +0x84; path +0x118 = list size) and track
     * best (+0x24) / worst (+0x28) / count (+0x38) / f64 sum (+0x30) of scores; +0x684 is bumped.
     * Finally a pathsize-0 path seeks into every stroke still live at its +0x58 time (both
     * passes, +0x6c0 per stroke), any other path seekPreordainedNextSteps. Debug printing is not
     * ported. */
    fun step(st: SearchState, mts: List<MemImage>, cb: StepCallees): Boolean {
        val ps = st.ps
        while (true) {
            val (pp, bi) = topStep(st)
            if (pp == null) return false
            if (pp.i8(0x2a) >= 2) {
                val w = st.word(pp)
                st.accessage.getOrPut(StrKey(w.copyOf(w.size - 1))) { IntArray(2) }
                val a = st.accessage.getOrPut(StrKey(w)) { IntArray(2) }
                if (a[0] != 0) continue
                a[1] = (a[1] + 1).toShort().toInt()
                if (a[1] >= ps.i32(0x4fc)) a[0] = 1
            }
            val base = BUCKETS + BUCKET_STRIDE * bi
            ps.setI32(base + 0x2c, ps.i32(base + 0x2c) + 1)
            ps.setI32(0xe8c, ps.i32(0xe8c) + 1)
            val w2 = st.word(pp)
            if (w2.isNotEmpty()) {
                val ap = st.arrivals.getOrPut(StrKey(w2)) { SearchState.ArrivalPair() }
                if (ap.maxLen == 0) { ap.name = w2; ap.maxLen = cb.maximumPathLength(pp) }
                ap.list.add(intArrayOf(ps.i32(0xe8c), pp.i32(0x74), pp.i32(0x138), pp.i32(0x130), pp.i32(0x134)))
                val n = ap.list.size
                pp.setI32(0x130, maxOf(pp.i32(0x130), n))
                pp.setI32(0x134, n + pp.i32(0x134) - 1)
                pp.setI32(0x114, n)
            }
            val s = getStepStats(ps, st.stepStats, pp.i8(0x2a))
            st.statsPending(s).add(pp)
            s.setI32(0x84, s.i32(0x84) + 1)
            pp.setI32(0x118, st.statsPending(s).size)
            val sc = pp.f32(0x74)
            val cnt = s.i32(0x38)
            if (cnt == 0) { s.setF32(0x24, sc); s.setF32(0x28, sc) }
            else {
                if (fcmplt(s.f32(0x24), sc)) s.setF32(0x24, sc)
                if (fcmpgt(s.f32(0x28), sc)) s.setF32(0x28, sc)
            }
            s.setI32(0x38, cnt + 1)
            s.setF64(0x30, dadd(f2d(sc), s.f64(0x30)))
            ps.setI32(0x684, ps.i32(0x684) + 1)
            if (pp.u8(0x2a) == 0) {
                for (mt in mts) {
                    if (!dcmplt(mt.f64(0x110), pp.f64(0x58))) {
                        cb.seekNextStep(pp, mt, true)
                        cb.seekNextStep(pp, mt, false)
                    }
                    ps.setI32(0x6c0, ps.i32(0x6c0) + 1)
                }
            } else cb.seekPreordainedNextSteps(pp)
            return true
        }
    }

    /** performSteps(int) @007ede98: runs step() until it finds nothing to do (at most 9999
     * steps; the argument is only printed). Skipped entirely when the RoenGL flag Xnl
     * "emkey_nopathsearch" is set or the global Shing::Context has "dontsearch" (both clear
     * in a booted engine). Before stepping: LangEnv::maybeFinishCacheLoading and the Moopad's
     * WordLearningModule::prepareForSearch. Statistics printing ("printstats", +0x190) is not
     * ported. Returns the number of steps taken (the binary only prints it). */
    fun performSteps(noPathSearch: Boolean, dontSearch: Boolean, finishCacheLoading: () -> Unit,
                     prepareForSearch: () -> Unit, step: () -> Boolean): Int {
        if (noPathSearch || dontSearch) return 0
        finishCacheLoading()
        prepareForSearch()
        var n = 0
        var i = 1
        while (true) {
            if (!step()) break
            n = i
            if (i >= 9999) break
            i++
        }
        return n
    }
}

/** MooTouchPoint::nonGenericSoftIndexDistance, reached through the verified emkey port. */
private object MooTouchPointNG {
    fun distance(a: MemImage, b: MemImage): Float = nintype.engine.emkey.MooTouchPoint.nonGenericSoftIndexDistance(a, b)
}
