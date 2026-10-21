package nintype.engine.pipeline

import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.i2d

/**
 * The engine's clocks, as the binary computes them (verified against every step and touch of
 * the e2e corpus, see PipelineE2ETest):
 *
 *  - RoenGL::globaltimestamp (once per NINLib.step, in RoenGL::updateState via
 *    MacStuff::getUpTime = SDate().doubleSecDifference(appStart)):
 *    `gts = i2d(sec - s0) + i2d(usec - u0) / 1e6` from gettimeofday (sec, usec).
 *  - RoenGL::globallastdtime / globallastdtimefactor: `dt = gts - prev`, `f = dt / (1/60)`;
 *    when the previous frame time (RoenGL+0xbc8) is negative, dt = 1/60 and f = 1.
 *  - Touch event time (MacStuff::getUpTimeAtUnixMilliseconds on the jlong's low word, 32-bit
 *    wrapping like the binary): `t = i2d(msLow - (s0 * 1000 + u0 / 1000)) / 1000`.
 *  - Emkey::__recordtime / __recorddtf / __recorddts, the clock Mootouch::record reads: set to
 *    (t, f, dt) by handleMooTouch / performMooTouchRelease for a touch, to (gts, f, dt) by
 *    Moopad::update each frame, and interpolated during MOVE subdivision.
 */
class EngineClock(
    val startSec: Int,
    val startUsec: Int,
    var gts: Double,
    var prevGts: Double,
    var lastDt: Double,
    var lastDtFactor: Double,
    var recordTime: Double,
    var recordDtf: Double,
    var recordDts: Double,
) {
    /** One NINLib.step at the gettimeofday clock [clockUs]. */
    /** The last step's gettimeofday (µs). */
    var lastClockUs: Long = startSec * 1_000_000L + startUsec

    fun step(clockUs: Long) {
        lastClockUs = clockUs
        val sec = (clockUs / 1_000_000L).toInt()
        val usec = (clockUs % 1_000_000L).toInt()
        val prev = prevGts
        val now = dadd(i2d(sec - startSec), ddiv(i2d(usec - startUsec), MICRO))
        gts = now
        prevGts = now
        if (dcmplt(prev, 0.0)) {
            lastDt = FRAME
            lastDtFactor = 1.0
        } else {
            lastDt = dsub(now, prev)
            lastDtFactor = ddiv(lastDt, FRAME)
        }
    }

    /** The TouchInfo time of an event stamped [ms] (System.currentTimeMillis). */
    fun touchTime(ms: Long): Double {
        val startMs = startSec * 1000 + startUsec / 1000
        return ddiv(i2d(ms.toInt() - startMs), 1000.0)
    }

    /** handleMooTouch / performMooTouchRelease: the record clock for an event at [t]. */
    fun setRecordForTouch(t: Double) {
        recordTime = t; recordDtf = lastDtFactor; recordDts = lastDt
    }

    /** Moopad::update: the record clock for the per-frame re-record. */
    fun setRecordForFrame() {
        recordTime = gts; recordDtf = lastDtFactor; recordDts = lastDt
    }

    companion object {
        /** 1/60 as the binary's literal (0x3f91111111111111). */
        val FRAME: Double = java.lang.Double.longBitsToDouble(0x3f91111111111111L)
        val MICRO: Double = 1_000_000.0
    }
}
