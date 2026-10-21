package nintype.engine.pipeline

import nintype.engine.math.Point2D
import nintype.engine.touch.Mootouch
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.Values

/**
 * `Emkey::SaveableTouch` at Mootouch+0x274: a vector of 40-byte SaveableTouchPointRec. The
 * touch port reads it (markReleased's sample matching, calcDepartureVelocity); the pipeline
 * is its only writer. Each element is `{"@0x0": raw 40 bytes}` in the Mootouch's seq(0x274),
 * the shape MootouchCodec and touch_ext.py use.
 *
 * Record layout: 0x00 f64 t, 0x08 Point2D pos, 0x10 f32 twerk force, 0x14 u8 frame-update
 * flag, 0x18 f64 frame dt factor, 0x20 f64 frame dt (both 0 for an event record). Bytes
 * 0x15..0x17 are struct padding the binary copies from an uninitialised stack temporary; they
 * are 0 here and no reader looks at them.
 *
 * Writers (all checked in the emulator on kinetica-believe):
 *  - handleMooTouch DOWN, after Mootouch::record, when +0xb5: record(pos, translateTwerkForce(ti force), __recordtime)
 *  - handleMooTouch MOVE, unsubdivided, when +0xb5: the same
 *  - handleMooTouch MOVE subdivision step i: record(p_i, lerp(+0x98 before, +0x98 after
 *    takeForceTouchUpdate, f), the step's interpolated __recordtime)
 *  - performMooTouchRelease, after markReleased, when +0xb5: record(pos, +0xa4, __recordtime)
 *  - Moopad::update, after each live Mootouch::update (SaveableTouch::recordUpdate inlined):
 *    the last record's pos (0,0 if none), +0x98, flag 1, and the frame clock (gts, factor, dt)
 */
object SaveableTouch {
    const val OFF = 0x274
    const val SIZE = 40

    private fun push(mt: Mootouch, r: MemImage) {
        mt.body.seq(OFF).add(Values.struct("@0x0" to Values.bytesLeaf(r.readBytes(0, SIZE))))
    }

    private fun blank(): MemImage = MemImage("SaveableTouchPointRec", SIZE).also { it.writeBytes(0, ByteArray(SIZE)) }

    /** SaveableTouch::record(Point2D const&, float, double) @005ca554. */
    fun record(mt: Mootouch, pos: Point2D, force: Float, t: Double) {
        val r = blank()
        r.setF64(0x0, t)
        r.setF32(0x8, pos.x); r.setF32(0xc, pos.y)
        r.setF32(0x10, force)
        push(mt, r)
    }

    /** The recordUpdate step Moopad::update inlines (@006be32c..006be454). */
    fun recordFrame(mt: Mootouch, t: Double, factor: Double, dt: Double) {
        val v = mt.body.seq(OFF)
        val r = blank()
        if (v.isNotEmpty()) {
            val last = MemImage.decode(v.last())
            r.setF32(0x8, last.f32(0x8)); r.setF32(0xc, last.f32(0xc))
        }
        r.setF64(0x0, t)
        r.setF32(0x10, mt.body.f32(0x98))
        r.setU8(0x14, 1)
        r.setF64(0x18, factor)
        r.setF64(0x20, dt)
        push(mt, r)
    }
}
