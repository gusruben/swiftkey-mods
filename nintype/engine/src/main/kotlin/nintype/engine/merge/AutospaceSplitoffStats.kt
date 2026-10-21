package nintype.engine.merge

import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.vectors.MemImage

/**
 * `Emkey::AutospaceSplitoffStats` (0x34 bytes; embedded at AutospaceModule+0x2fc =
 * Moopad+0x8370). Filled by `PathSearch::getStatsForTimedAutospace` and
 * `AutospaceModule::syncAutospaceStatsPostSearch`, read by `MooMergeBuffer::update`:
 *
 *   0x00 (not touched by the ctor)
 *   0x04 std::map header (color 0x4, root 0x8, leftmost 0xc, rightmost 0x10, count 0x14)
 *   0x18 i32 splitTouch: mootouch index the split goes before (-1 = none); update passes it
 *        to resetForAutospaceSplitSearch (read as Moopad+0x8388)
 *   0x1c f32 (-1.0 at construction)
 *   0x20..0x2c zero at construction
 *   0x30 f32 score: split confidence; shouldSplit() is score > 0.36, and update passes it as
 *        the merge weight to PathResult::mergeFromSpaceSplitters (Moopad+0x83a0)
 */
object AutospaceSplitoffStats {
    const val SIZE = 0x34
    const val SPLIT_TOUCH = 0x18
    const val SCORE = 0x30
    private val F0_36 = java.lang.Float.intBitsToFloat(0x3eb851ec)

    /** AutospaceSplitoffStats() @00ee5388. The map header's two self-pointers (+0xc, +0x10)
     * are addresses; in the image they are left untouched (the vectors skip them). */
    fun construct(s: MemImage) {
        s.setI32(0x8, 0); s.setI32(0x14, 0); s.setI32(0x4, 0)
        s.setI32(SPLIT_TOUCH, -1)
        s.setI32(0x1c, 0xbf800000.toInt())
        for (o in 0x20..0x30 step 4) s.setI32(o, 0)
    }

    /** shouldSplit() @00ee53d8: score > 0.36f. */
    fun shouldSplit(s: MemImage): Boolean = fcmpgt(s.f32(SCORE), F0_36)
}
