package nintype.engine.merge

import nintype.engine.vectors.MemImage

/**
 * `Xnl` (a signal-backed bool, 0x34 bytes): only its value is decoder-relevant.
 * `Xnl::val() const` @01258270 is `ldrb r0, [r0, #0xc]`: the u8 at +0xc, returned as is
 * (callers compare it with 0 or with 1, so values other than 0/1 matter).
 */
object Xnl {
    const val VAL = 0xc

    /** Xnl::val() of the Xnl embedded at [off] inside [img]. */
    fun val_(img: MemImage, off: Int): Int = img.u8(off + VAL)
}

/**
 * Moopad fields the merge area reads, plus thin forwards to the geometry area's ports of the
 * Moopad / MooBoard glue (geometry/MoopadGeometry.kt, geometry/MooBoard.kt), so every caller
 * here goes through one place. All return the raw Xnl byte (callers compare with 0 or 1).
 */
object MergeMoopad {
    const val AUTOSPACE_MS = 0x7f9c       // i32 settings_autospace_timed_millsecs
    const val BOARD = 0x1f1ec             // MooBoard* (alpha board)
    const val LAST_UP_T = 0x2b530         // f64, time (s) of the last lift
    const val AUTOSPACE_MODULE = 0x8074   // embedded AutospaceModule
    const val SPLITOFF_STATS = 0x8370     // = AutospaceModule + 0x2fc

    /** Moopad::hasUSF1(int) @0061fd48 (geometry port). */
    fun hasUSF1(mp: MemImage, n: Int): Int = nintype.engine.geometry.MoopadGeometry.hasUSF1(mp, n)
    /** Moopad::slidemodeActive() @00623d54 (geometry port). */
    fun slidemodeActive(mp: MemImage): Int = nintype.engine.geometry.MoopadGeometry.slidemodeActive(mp)
    /** MooBoard::isActive() @009e08b4 (geometry port; raw byte, timeUntilAutospaceMerge tests == 1). */
    fun boardIsActive(board: MemImage, mp: MemImage): Int =
        nintype.engine.geometry.MooBoard(board, mp, emptyList(), java.util.TreeMap()).isActive()
    fun autospaceMs(mp: MemImage): Int = mp.i32(AUTOSPACE_MS)
}
