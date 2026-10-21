package nintype.engine.merge

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.math.LibM
import nintype.engine.vectors.MemImage

/**
 * `ChasingFloat::chaseByFractionWithSpeedLimit(float frac, float dt, float speed)` @00c1a738,
 * over a ChasingFloat (+0 cur, +4 mid, +8 target; touch/ChasingFloat.kt has the plain chase)
 * embedded in an image at [off]:
 *   k = 1 - powf(1 - frac, dt); lim = dt * speed
 *   d = k * (mid - cur), clamped to [-lim, lim] (d > lim -> lim, else d < -lim -> -lim)
 *   cur = d + cur
 *   d = k * (target - mid0), same clamp; mid = d + mid0   (mid0 = mid before the call)
 */
object ChasingFloatSpeedLimit {
    private fun clamp(d: Float, lim: Float): Float {
        if (fcmpgt(d, lim)) return lim
        val nl = Float.fromBits(lim.toRawBits() xor Int.MIN_VALUE)
        return if (fcmplt(d, nl)) nl else d
    }

    fun chase(img: MemImage, off: Int, frac: Float, dt: Float, speed: Float) {
        val k = fsub(1f, LibM.powf(fsub(1f, frac), dt))
        val lim = fmul(dt, speed)
        val mid0 = img.f32(off + 4)
        val cur0 = img.f32(off)
        img.setF32(off, fadd(clamp(fmul(k, fsub(mid0, cur0)), lim), cur0))
        img.setF32(off + 4, fadd(clamp(fmul(k, fsub(img.f32(off + 8), mid0)), lim), mid0))
    }
}
