package nintype.engine.merge

import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fmul
import nintype.engine.vectors.MemImage

/**
 * `Emkey::MooMergeBuffer` (0x44 bytes; one per merge-buffer slot, Moopad::obtainMergeBuffer):
 * the per-word history of re-decodes. Each MooMergeBuffer::update appends one PathResult
 * (the top result of a full-buffer search) per newly sat touch.
 *
 *   0x00 std::vector<PathResult> results (0xa0-byte elements; +0x2c f32 top score,
 *        +0x88 f64, +0x90 u8 sweeped flag, +0x5c vector<WordAlternative>, +0x94 vector<PathResult>)
 *   0x0c std::vector<ACCollectage> autocompletes (0xe4-byte elements), parallel to results
 *   0x1c std::multimap<float, ...> header (count 0x2c): nodes {+0x10 f32 key = -score,
 *        +0x14 u32 result index}; update inserts one node per appended result
 *   0x30 f32 max score seen (max of PathResult +0x70 / +0xa0... see update)
 *   0x34 i32 startTouch (init(n)), 0x38 i32 lastTouch (init: n - 1; index of the last
 *        touch decoded into this buffer)
 *   0x3c u8 dead (markDeadAfter; update bails out while set)
 *   0x3d u8 finished (set when the whole buffer has been searched without new touches)
 *   0x3e u8 sweeped (markSweeped), 0x40 i32 sweepMaster (-1 = none)
 */
object MooMergeBuffer {
    const val SIZE = 0x44
    const val RESULTS = 0x0
    const val START_TOUCH = 0x34
    const val LAST_TOUCH = 0x38
    const val DEAD = 0x3c
    const val FINISHED = 0x3d
    const val SWEEPED = 0x3e
    const val SWEEP_MASTER = 0x40
    const val PATH_RESULT_SIZE = 0xa0
    const val PR_SWEEPED = 0x90

    /** MooMergeBuffer() @0089e430: empty containers (multimap header at +0x1c with its
     * leftmost/rightmost self-pointers, not modelled), maxScore 0, startTouch 0,
     * lastTouch -1, flags 0, sweepMaster -1. +0x18 is left as is. */
    fun construct(b: MemImage) {
        for (o in intArrayOf(0, 4, 8, 0xc, 0x10, 0x14, 0x1c, 0x20, 0x2c, 0x30, 0x34)) b.setI32(o, 0)
        b.setI32(LAST_TOUCH, -1)
        b.setU8(DEAD, 0); b.setU8(FINISHED, 0); b.setU8(SWEEPED, 0)
        b.setI32(SWEEP_MASTER, -1)
    }

    /** init(int n) @0089e4c4. */
    fun init(b: MemImage, n: Int) {
        b.setI32(START_TOUCH, n)
        b.setI32(LAST_TOUCH, n - 1)
    }

    /** markSweepMaster(int i) @0089e508. */
    fun markSweepMaster(b: MemImage, i: Int) = b.setI32(SWEEP_MASTER, i)

    /** markSweeped(int& count) @0089f0a8: first call sets the flag and bumps the counter. */
    fun markSweeped(b: MemImage, count: IntArray) {
        if (b.u8(SWEEPED) != 0) return
        b.setU8(SWEEPED, 1)
        count[0] = count[0] + 1
    }

    /** clearSweepFlags() @0089e4d4: sweeped = 0, sweepMaster = -1, every result's +0x90 = 0.
     * [results] are the PathResult element images (begin..end). */
    fun clearSweepFlags(b: MemImage, results: List<MemImage>) {
        b.setU8(SWEEPED, 0)
        b.setI32(SWEEP_MASTER, -1)
        for (r in results) r.setU8(PR_SWEEPED, 0)
    }

    /** The containers of a buffer as values: [results] are PathResult images (fields used
     * here: +0x2c sweep score, +0x88 f64), [acs] the parallel ACCollectages (opaque), [order]
     * the multimap<float, uint> at +0x1c in order (key = -score, value = result index). */
    class Containers<R, A>(val results: MutableList<R>, val acs: MutableList<A>, val order: MutableList<Pair<Float, Int>>)

    private val F0_2 = java.lang.Float.intBitsToFloat(0x3e4ccccd)

    /** getSweepChoice(int&, ...) @0089eda4, the index part: i = sweepMaster; none (null) when
     * i == -1, i >= count (unsigned) or i < 0. Otherwise walk back from i while
     * results[i].0x2c <= 0.5 (or unordered), or is not > 0.2 * results[i-1].0x2c; stop at 0.
     * The caller then runs returnSweepChoice(i, ...) and the function returns true. */
    fun sweepChoiceIndex(b: MemImage, results: List<MemImage>): Int? {
        var i = b.i32(SWEEP_MASTER)
        if (i == -1) return null
        if (Integer.compareUnsigned(i, results.size) >= 0) return null
        if (i < 0) return null
        while (i >= 1) {
            val a = results[i].f32(0x2c)
            if (!(fcmple(a, 0.5f) || fcmpun(a, 0.5f)) && fcmpgt(a, fmul(results[i - 1].f32(0x2c), F0_2))) break
            i--
        }
        return i
    }

    /** returnSweepChoice(int i, double* t, ..., bool* isLast) @0089e7d0, scalar outputs:
     * t = results[i].0x88; isLast = (i + 1 == count). (The WordAlternative, vector and
     * ACCollectage copies are other areas' types.) Returns 1. */
    fun returnSweepT(results: List<MemImage>, i: Int): Double = results[i].f64(0x88)
    fun returnSweepIsLast(results: List<MemImage>, i: Int): Boolean = i + 1 == results.size

    /** markDeadAfter(int n) @008a0490: dead = 1; while n + 1 < count (unsigned): pop the last
     * result and ACCollectage, lastTouch--. The multimap is left alone. */
    fun <R, A> markDeadAfter(b: MemImage, c: Containers<R, A>, n: Int) {
        b.setU8(DEAD, 1)
        while (Integer.compareUnsigned(n + 1, c.results.size) < 0) {
            c.results.removeAt(c.results.size - 1)
            c.acs.removeAt(c.acs.size - 1)
            b.setI32(LAST_TOUCH, b.i32(LAST_TOUCH) - 1)
        }
    }

    /** onTouchBacktrack(int n) @0089f368: with results present, while lastTouch >= n:
     * lastTouch--; pop a result + ACCollectage (if results remain); drop multimap entries whose
     * index >= count (unsigned). Then dead = 0. With no results nothing changes. */
    fun <R, A> onTouchBacktrack(b: MemImage, c: Containers<R, A>, n: Int) {
        if (c.results.isEmpty()) return
        if (b.i32(LAST_TOUCH) >= n) {
            do {
                b.setI32(LAST_TOUCH, b.i32(LAST_TOUCH) - 1)
                if (c.results.isNotEmpty()) {
                    c.results.removeAt(c.results.size - 1)
                    c.acs.removeAt(c.acs.size - 1)
                }
                c.order.removeAll { Integer.compareUnsigned(it.second, c.results.size) >= 0 }
            } while (b.i32(LAST_TOUCH) >= n)
        }
        b.setU8(DEAD, 0)
    }

    /** bestResultIslandCount() @0089f0cc: index of the best result (leftmost multimap entry)
     * + 1. It asserts on an empty buffer or multimap and reads on anyway: with an empty
     * multimap the leftmost "node" is the header, whose +0x14 is this+0x30. */
    fun bestResultIslandCount(b: MemImage, order: List<Pair<Float, Int>>): Int =
        (if (order.isEmpty()) b.i32(0x30) else order[0].second) + 1

    /** getMostRecentAutoCompleteBuffer(ACCollectage* ac) @0089ed74: *ac = the last
     * ACCollectage (operator=), or ac->reset() when there is none (a null ac is a no-op). */
    fun getMostRecentAutoCompleteBuffer(acs: List<ACModel>, ac: ACModel?) {
        if (ac == null) return
        if (acs.isNotEmpty()) ac.assign(acs.last()) else ac.reset()
    }

    /** getExplicitResult(Moopad*, float&, float&, bool&, vector<uint>&) @0089e510: "" when no
     * sweep master is set (the outputs untouched); else mootouchlist_getExplicitResult over the
     * sitting touches (Moopad+0x294c4, list order). */
    fun getExplicitResult(b: MemImage, sitting: List<nintype.engine.touch.Mootouch>, env: nintype.engine.touch.TouchEnv,
                          mp: MemImage, o: ExplicitResult.Out) {
        if (b.i32(SWEEP_MASTER) == -1) { o.text = ByteArray(0); return }
        ExplicitResult.mootouchlistGetExplicitResult(sitting, env, mp, o)
    }
}
