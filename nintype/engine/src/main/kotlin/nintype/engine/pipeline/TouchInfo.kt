package nintype.engine.pipeline

import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.Point2D

/**
 * `TouchInfo` (0x80 bytes) as it reaches Moopad::handleMooTouch, and the RoenGL bookkeeping
 * that fills it (neojni_handleTouchEvent, RoenGL::handleTouchEvent; the fields below were
 * checked against all 2,192 events of the e2e corpus):
 *
 *   0x00 pid   0x04 type (0 down, 1 move, 2 up)   0x08 / 0x0c x, y: `f2iz(px) / 2` (C int
 *   division: the surface-px to roen-unit scale of the 1080-px reference surface)
 *   0x14 / 0x18 dx, dy since the pointer's previous event (0 on DOWN and UP)
 *   0x20 f64 time (EngineClock.touchTime)
 *   0x28 / 0x2c down position; 0x30 / 0x34 / 0x38 accumulated |dx|, |dy|, |dx| + |dy|
 *   0x48 u8 hasForce, 0x4c f32 force, 0x50 f32 force2: all 0 on the JNI path (no event of the
 *   corpus sets them; NINLib.onTouchEvent's pressure goes to 0x58), so force() is constant
 *   0x54 f32 6.67, 0x58 pressure, 0x5c size, 0x70 bit 0 flag (0)
 *
 * pos() and dpos() are these ints as floats; handleMooTouch subtracts screenKeyboardOrigin.
 */
class TouchInfo(
    val pid: Int, val type: Int, val x: Int, val y: Int, val dx: Int, val dy: Int, val t: Double,
    val x0: Int, val y0: Int, val accX: Int, val accY: Int, val accXY: Int,
    val pressure: Float, val size: Float,
) {
    fun pos(): Point2D = Point2D(i2f(x), i2f(y))
    fun dpos(): Point2D = Point2D(i2f(dx), i2f(dy))
    fun force(): nintype.engine.touch.ForceInfo = NO_FORCE
    val isDown get() = type == 0
    val isMove get() = type == 1
    val isUp get() = type == 2

    companion object { val NO_FORCE = nintype.engine.touch.ForceInfo(0, 0f, 0f) }
}

/** RoenGL's per-pointer record (down position, last position, travel accumulators). */
class RoenTouchTracker(private val scaleDiv: Int = 2) {
    private class Rec(val x0: Int, val y0: Int, var x: Int, var y: Int, var ax: Int = 0, var ay: Int = 0, var axy: Int = 0)

    private val recs = HashMap<Int, Rec>()

    fun build(pid: Int, action: Int, px: Float, py: Float, pressure: Float, size: Float, t: Double): TouchInfo {
        val x = f2iz(px) / scaleDiv
        val y = f2iz(py) / scaleDiv
        return when (action) {
            0 -> {
                val r = Rec(x, y, x, y)
                recs[pid] = r
                TouchInfo(pid, 0, x, y, 0, 0, t, x, y, 0, 0, 0, pressure, size)
            }
            1 -> {
                val r = recs.getOrPut(pid) { Rec(x, y, x, y) }
                val dx = x - r.x
                val dy = y - r.y
                r.x = x; r.y = y
                r.ax += Math.abs(dx); r.ay += Math.abs(dy); r.axy += Math.abs(dx) + Math.abs(dy)
                TouchInfo(pid, 1, x, y, dx, dy, t, r.x0, r.y0, r.ax, r.ay, r.axy, pressure, size)
            }
            else -> {
                val r = recs.remove(pid) ?: Rec(x, y, x, y)
                TouchInfo(pid, 2, x, y, 0, 0, t, r.x0, r.y0, r.ax, r.ay, r.axy, pressure, size)
            }
        }
    }
}
