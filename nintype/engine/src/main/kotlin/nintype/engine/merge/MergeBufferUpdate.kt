package nintype.engine.merge

import nintype.engine.Bits
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.vectors.MemImage

/**
 * `Emkey::MooMergeBuffer::update(int last, bool first, bool full, Emkey::Moopad*)` @0089f4cc.
 * The orchestration is ported here; every search / result / autocomplete step goes through
 * [Host], which the final engine implements with the PathSearch, PathResult and ACCollectage
 * ports (the vectors replay the recorded oracle calls instead; see
 * re/vectors/gen_merge_update.py, whose docstring has the spec this follows).
 */
object MergeBufferUpdate {
    /** The PathResult fields update itself reads: score = f4 * f8 (compared with 7.4),
     * flags3e bit 0 = early split, score68 = the final score (multimap key = -score68). */
    class PR(val f4: Float, val f8: Float, val flags3e: Int, val score68: Float)

    /** [R] is the caller's PathResult handle (the real object in the engine, [PR] in the vectors). */
    interface Host<R> {
        /** The fields of [r] update reads. */
        fun fields(r: R): PR
        /** PathResult() for a padding slot (its score +0x68 is 0). */
        fun emptyResult(): R
        /** Shing::Context::has: [which] 1 = the check after padding, 2 = the loop-top re-run check. */
        fun contextHas(which: Int): Boolean
        fun reset(start: Int, end: Int)
        fun syncBucketStepAllowance(f: Float)
        fun performSteps(n: Int)
        fun listResults(n: Int)
        fun topResult(): R
        /** ACCollectage::ACCollectage(Moopad*) for the result being appended. */
        fun newACCollectage()
        fun gatherAutocomplete()
        fun mootouchIndexOfNearSpaceTapper(): Int
        fun mootouchIndexOfApostropheEnder(): Int
        fun resetForPrePostcut(start: Int, end: Int, idx: Int, post: Boolean)
        fun resetForAutospaceSplitSearch(start: Int, end: Int, idx: Int, post: Boolean)
        /** top.mergeFromSpaceSplitters(pre, post, w, idx, moopad); returns top afterwards. */
        fun mergeFromSpaceSplitters(top: R, pre: R, post: R, w: Float, idx: Int): R
        /** AutospaceModule::syncAutospaceStatsPostSearch (it leaves its stats at Moopad+0x8370). */
        fun syncAutospaceStatsPostSearch()
        fun shouldSplit(): Boolean
        fun onDetectEarlySplit(gap: Float)
        /** The possessive branch's string check on the post-split result (FUN_01721cb8 == 0). */
        fun apostropheMergeAllowed(post: R): Boolean
    }

    /** The buffer's containers: the PathResults (+0x0 vector), the ACCollectage count (+0xc,
     * the caller creates them in [Host.newACCollectage]) and the multimap<float, uint> (+0x1c;
     * key, result index) in order. */
    class State<R>(val results: MutableList<R>, var acs: Int, val order: MutableList<Pair<Float, Int>>)

    private val D_TIME_EPS = Bits.bitsToDouble(0x3f847ae140000000L)
    private val F7_4 = Bits.bitsToFloat(0x40eccccd)
    private val F0_55 = Bits.bitsToFloat(0x3f0ccccd)

    /** multimap insert as the inlined tree walk: after every element whose key is not > k
     * (fcmpgt(existing, k)), i.e. upper_bound for ordered keys. */
    private fun insert(order: MutableList<Pair<Float, Int>>, k: Float, idx: Int) {
        var pos = order.size
        for (i in order.indices) if (fcmpgt(order[i].first, k)) { pos = i; break }
        order.add(pos, Pair(k, idx))
    }

    private fun steps(e: Float, hi: Float): Int = f2iz(fadd(fmul(e, hi), fmul(fsub(1f, e), 45f)))

    /** Runs update on [b] (the buffer's scalar fields) / [st]; returns the new value of the
     * static lastUpdateT. [now] = RoenGL::globaltimestamp. */
    fun <R> update(b: MemImage, st: State<R>, last: Int, first: Boolean, full: Boolean, mp: MemImage,
                   now: Double, lastUpdateT: Double, h: Host<R>): Double {
        var lut = lastUpdateT
        val d = dsub(now, lut)
        val ad = Bits.bitsToDouble(Bits.doubleToBits(d) and Long.MAX_VALUE)
        if (!(dcmple(ad, D_TIME_EPS) || dcmpun(ad, D_TIME_EPS))) lut = now
        if (b.u8(MooMergeBuffer.DEAD) != 0) return lut
        if (!first) {
            if (b.u8(MooMergeBuffer.FINISHED) != 0) return lut
            if (mp.u8(0x766) != 0) { b.setU8(MooMergeBuffer.DEAD, 1); return lut }
        }
        val target = if (full && !first) last else last - 1
        while (b.i32(MooMergeBuffer.LAST_TOUCH) < target) {
            b.setI32(MooMergeBuffer.LAST_TOUCH, b.i32(MooMergeBuffer.LAST_TOUCH) + 1)
            insert(st.order, 0f, st.results.size)
            st.results.add(h.emptyResult())
            h.newACCollectage(); st.acs++
            if (fcmplt(b.f32(0x30), 0f)) b.setF32(0x30, 0f)
        }
        var rerun = false
        if (b.i32(MooMergeBuffer.LAST_TOUCH) == last) rerun = h.contextHas(1)
        val e = mp.f32(0x12ac)
        while (true) {
            val lt = b.i32(MooMergeBuffer.LAST_TOUCH)
            if (last <= lt) {
                if (lt != last) break
                if (!h.contextHas(2)) break
            }
            val start = b.i32(MooMergeBuffer.START_TOUCH)
            h.reset(start, lt + 1)
            if (start == lt + 1 && mp.u8(0x2a110) == 0) b.setU8(MooMergeBuffer.FINISHED, 1)
            if (!first && b.u8(MooMergeBuffer.FINISHED) != 0) break
            b.setI32(MooMergeBuffer.LAST_TOUCH, lt + 1)
            h.syncBucketStepAllowance(e)
            h.performSteps(steps(e, 150f))
            h.listResults(7)
            var top = h.topResult()
            var tf = h.fields(top)
            h.newACCollectage(); st.acs++
            if (Xnl.val_(mp, 0x2b45c) == 1) h.gatherAutocomplete()
            val end = b.i32(MooMergeBuffer.LAST_TOUCH) + 1
            var split = false
            val low: Boolean
            if (Xnl.val_(mp, 0x800c) == 0 && Xnl.val_(mp, 0xb44) != 0) {
                val sc = fmul(tf.f4, tf.f8)
                low = fcmplt(sc, F7_4)
                if (mp.u8(0x2a11a) == 0 && !(fcmpge(sc, F7_4) || fcmpun(sc, F7_4)) && mp.i32(0x2affc) < 3) {
                    val i = h.mootouchIndexOfNearSpaceTapper()
                    if (i != -1) {
                        h.syncBucketStepAllowance(e)
                        val n = steps(e, 80f)
                        h.resetForPrePostcut(start, end, i, false); h.performSteps(n); val pre = h.topResult()
                        h.resetForPrePostcut(start, end, i, true); h.performSteps(n); val post = h.topResult()
                        top = h.mergeFromSpaceSplitters(top, pre, post, F0_55, i); tf = h.fields(top)
                        split = true
                    }
                }
            } else low = fcmplt(fmul(tf.f4, tf.f8), F7_4)
            if (Xnl.val_(mp, 0x7fd8) == 1) {
                var ok = 1
                if (mp.u8(0x2a11a) != 0) ok = if (MergeMoopad.hasUSF1(mp, 0x95) == 1) Xnl.val_(mp, 0x295dc) xor 1 else 0
                val u20 = Xnl.val_(mp, 0x29644)
                val i26 = Xnl.val_(mp, 0x295a8)
                ok = ok and (u20 xor 1)
                val go = if (i26 == 0) (ok and (if (mp.i32(0x2affc) == 0) 1 else 0)) != 0 else ok == 1
                if (go) {
                    h.syncAutospaceStatsPostSearch()
                    if (h.shouldSplit()) {
                        h.syncBucketStepAllowance(e)
                        val n = steps(e, 80f)
                        val i = mp.i32(0x8388)
                        h.resetForAutospaceSplitSearch(start, end, i, false); h.performSteps(n); val pre = h.topResult()
                        h.resetForAutospaceSplitSearch(start, end, i, true); h.performSteps(n); val post = h.topResult()
                        top = h.mergeFromSpaceSplitters(top, pre, post, mp.f32(0x83a0), i); tf = h.fields(top)
                        if (tf.flags3e and 1 != 0) h.onDetectEarlySplit(mp.f32(0x838c))
                        split = true
                    }
                }
            }
            var u27 = MergeMoopad.hasUSF1(mp, 0xb1)
            val b32 = low && !split
            if (b32) u27 = u27 xor 1
            if (b32 && u27 == 1 && mp.i32(0x2affc) < 3) {
                val i = h.mootouchIndexOfApostropheEnder()
                if (Integer.compareUnsigned(i + 1, 1) > 0) {
                    h.syncBucketStepAllowance(e)
                    val n = steps(e, 80f)
                    h.resetForAutospaceSplitSearch(start, end, i, false); h.performSteps(n); val pre = h.topResult()
                    h.resetForAutospaceSplitSearch(start, end, i, true); h.performSteps(n); val post = h.topResult()
                    if (h.apostropheMergeAllowed(post)) { top = h.mergeFromSpaceSplitters(top, pre, post, F0_55, i); tf = h.fields(top) }
                }
            }
            if (rerun) {
                st.results.removeAt(st.results.size - 1)
                b.setI32(MooMergeBuffer.LAST_TOUCH, b.i32(MooMergeBuffer.LAST_TOUCH) - 1)
            }
            insert(st.order, Bits.bitsToFloat(Bits.floatToBits(tf.score68) xor Int.MIN_VALUE), st.results.size)
            st.results.add(top)
            if (fcmplt(b.f32(0x30), tf.score68)) b.setF32(0x30, tf.score68)
            if (rerun) break
            rerun = false
            if (b.u8(MooMergeBuffer.DEAD) != 0) break
        }
        return lut
    }
}
