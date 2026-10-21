package nintype.engine.curve

import nintype.engine.SoftFloat

/**
 * Worked example #1: a tiny piecewise-linear curve evaluator.
 *
 * This is NOT a real Nintype function -- `re/` (the emulator harness) isn't
 * generating real golden vectors yet, so this hand-written example exists
 * purely to prove the vector-runner pipeline end to end (deliverable 4):
 * a struct-by-pointer argument, a scalar argument, a float return value,
 * and float arithmetic routed through [SoftFloat] the way a real port
 * would be. Replace/delete once real ported functions exist.
 *
 * Mirrors a typical fixed-size-array C struct:
 *
 * ```c
 * typedef struct { float x, y; } Point;
 * typedef struct { Point points[4]; int32_t count; } Curve;
 * float nt_curve_eval(const Curve* c, float x);
 * ```
 *
 * `nt_curve_eval` clamps to the first/last point outside the curve's
 * domain, and linearly interpolates between the two surrounding points
 * otherwise. Points are assumed sorted by `x` ascending, `count` in
 * `[0, 4]`.
 */
data class Point(val x: Float, val y: Float)

data class Curve(val points: List<Point>, val count: Int)

fun evalCurve(curve: Curve, x: Float): Float {
    val n = curve.count
    if (n <= 0) return 0f
    if (n == 1) return curve.points[0].y

    if (SoftFloat.fcmple(x, curve.points[0].x)) return curve.points[0].y
    if (SoftFloat.fcmpge(x, curve.points[n - 1].x)) return curve.points[n - 1].y

    for (i in 0 until n - 1) {
        val a = curve.points[i]
        val b = curve.points[i + 1]
        if (SoftFloat.fcmpge(x, a.x) && SoftFloat.fcmple(x, b.x)) {
            val dx = SoftFloat.fsub(b.x, a.x)
            if (SoftFloat.fcmpeq(dx, 0f)) return a.y
            val t = SoftFloat.fdiv(SoftFloat.fsub(x, a.x), dx)
            return SoftFloat.fadd(a.y, SoftFloat.fmul(t, SoftFloat.fsub(b.y, a.y)))
        }
    }
    // Unreachable given the clamps above and sorted points.
    return curve.points[n - 1].y
}
