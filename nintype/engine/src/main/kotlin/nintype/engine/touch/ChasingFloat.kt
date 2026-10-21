package nintype.engine.touch

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.math.LibM

/**
 * `ChasingFloat` (12 bytes): a value chasing a target through one intermediate stage.
 * +0 cur, +4 mid, +8 target.
 */
class ChasingFloat(var cur: Float = 0f, var mid: Float = 0f, var target: Float = 0f) {
    /** chaseByFraction(float frac, float dt) @00c1a4c8: k = 1 - powf(1 - frac, dt);
     * cur += k * (mid - cur); then mid += k * (target - mid). */
    fun chaseByFraction(frac: Float, dt: Float) {
        val k = fsub(1f, LibM.powf(fsub(1f, frac), dt))
        val m = mid
        cur = fadd(cur, fmul(k, fsub(m, cur)))
        mid = fadd(m, fmul(k, fsub(target, m)))
    }
}
