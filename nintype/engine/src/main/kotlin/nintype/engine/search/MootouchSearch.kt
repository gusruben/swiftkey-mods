package nintype.engine.search

import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.i2f
import nintype.engine.Bits
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * `Emkey::Mootouch` (sizeof 0x3b8, one finger's stroke) methods the search driver calls,
 * ported over a byte image (split agreed with the touch area, which owns Mootouch::record
 * and its callees). The point vector at +0x244 holds MooTouchPoints by value (1000 bytes
 * each) and is passed in as a list of their images. Fields used here:
 *
 *   0x0f8 f64 tStart    0x100 u8 ended (the end time is final)    0x110 f64 tEnd
 *   0x128 Point2D start position    0x130 Point2D position used when there are no points
 *   0x244 vector<MooTouchPoint> points (also 0x250, 0x25c: other point vectors)
 *
 * MooTouchPoint: 0x0d8 f32 (taken with y as a "lowest point" candidate), 0x0dc/0x0e0 pos,
 * 0x128 f64 tStart, 0x16c f32 double-letter factor, 0x225 u8 flags high byte, 0x238 dwell,
 * 0x300 corner angle.
 */
object MootouchOps {
    const val SIZE = 0x3b8

    private fun f(b: Int) = Bits.bitsToFloat(b)

    /** getStartpos() @009644b8 (sret). */
    fun getStartpos(mt: MemImage): Point2D = Point2D(mt.f32(0x128), mt.f32(0x12c))

    /** firstFocusTime() @009664a0: the first point's tStart, else the stroke's. */
    fun firstFocusTime(mt: MemImage, points: List<MemImage>): Double =
        if (points.isEmpty()) mt.f64(0xf8) else points[0].f64(0x128)

    /** getAveragePos() @009644cc (sret): mean point position (sum * (1 / n)), or +0x130. */
    fun getAveragePos(mt: MemImage, points: List<MemImage>): Point2D {
        if (points.isEmpty()) return Point2D(mt.f32(0x130), mt.f32(0x134))
        var x = 0f
        var y = 0f
        for (p in points) { x = fadd(p.f32(0xdc), x); y = fadd(p.f32(0xe0), y) }
        return Point2D(x, y) * fdiv(1f, i2f(points.size))
    }

    /** lowestYPoint() @009654c0: max over points of +0xd8 and y (screen y grows downwards); 0 if none. */
    fun lowestYPoint(points: List<MemImage>): Float {
        var best = 0f
        for (p in points) {
            var v = p.f32(0xd8)
            if (fcmplt(v, best)) v = best
            val y = p.f32(0xe0)
            if (!fcmplt(y, v)) v = y
            best = v
        }
        return best
    }

    /** timeOverlaps(Mootouch& o, float margin) @0096cd34: the time spans overlap, where a
     * stroke that has not ended (+0x100 == 0) extends forever. */
    fun timeOverlaps(a: MemImage, o: MemImage, margin: Float): Boolean {
        val aEnded = a.u8(0x100) != 0
        val oEnded = o.u8(0x100) != 0
        val m = f2d(margin)
        if (!oEnded) {
            if (!aEnded) return true
            return dcmplt(o.f64(0xf8), dadd(m, a.f64(0x110)))
        }
        if (!aEnded) return dcmpgt(dadd(m, o.f64(0x110)), a.f64(0xf8))
        val e1 = dadd(m, a.f64(0x110))
        if (dcmple(e1, o.f64(0xf8)) || dcmpun(e1, o.f64(0xf8))) return false
        return dcmplt(a.f64(0xf8), dadd(m, o.f64(0x110)))
    }

    /** timeOverlapsWith(Mootouch const& o) const @0096fc70: start < o.end && o.start < end. */
    fun timeOverlapsWith(a: MemImage, o: MemImage): Boolean {
        val s = a.f64(0xf8)
        if (dcmpge(s, o.f64(0x110)) || dcmpun(s, o.f64(0x110))) return false
        return dcmplt(o.f64(0xf8), a.f64(0x110))
    }

    /** timeEncloses(Mootouch const& o) @0096fbf4: start < o.start && o.end < end. */
    fun timeEncloses(a: MemImage, o: MemImage): Boolean {
        val s = a.f64(0xf8)
        if (dcmpge(s, o.f64(0xf8)) || dcmpun(s, o.f64(0xf8))) return false
        return dcmplt(o.f64(0x110), a.f64(0x110))
    }

    /** takeDoubleLetterFactorings(Mootouch& o) @00966838: copies every point's +0x16c from o
     * when both strokes have the same (non-zero) point count. */
    fun takeDoubleLetterFactorings(points: List<MemImage>, oPoints: List<MemImage>) {
        if (points.size != oPoints.size || points.isEmpty()) return
        for (i in points.indices) points[i].setI32(0x16c, oPoints[i].i32(0x16c))
    }

    /** predictCharCount() @009664bc: sum over points of a dwell-based char estimate (2 dwell:
     * > 0.4 -> 2; (0.2, 0.4] -> 1 + (d - 0.2) / 0.2; (0.1, 0.2] -> 0.6 + 0.4 (d - 0.1) / 0.1;
     * else 0.6 d / 0.1), raised to 1 for flag 0x1800 points or corners > 90 deg, to 0.6 for
     * corners > 45, to 0.34 for corners > 30. */
    fun predictCharCount(points: List<MemImage>): Float {
        var sum = 0f
        val f02 = f(0x3e4ccccd)
        val f01 = f(0x3dcccccd)
        val f06 = f(0x3f19999a)
        for (p in points) {
            val dw = fadd(p.f32(0x238), p.f32(0x238))
            var c: Float = if (fcmpgt(dw, f(0x3ecccccd))) 2f
            else if (!(fcmple(dw, f02) || fcmpun(dw, f02))) fadd(fdiv(fadd(dw, f(0xbe4ccccd.toInt())), f02), 1f)
            else if (!(fcmple(dw, f01) || fcmpun(dw, f01))) fadd(fdiv(fmul(fadd(dw, f(0xbdcccccd.toInt())), f(0x3ecccccd)), f01), f06)
            else fmul(fdiv(dw, f01), f06)
            if (p.u8(0x225) and 0x18 != 0 && !fcmpgt(c, 1f)) c = 1f
            val a = p.f32(0x300)
            if (!(fcmple(a, 90f) || fcmpun(a, 90f))) {
                if (!fcmpgt(c, 1f)) c = 1f
            } else {
                val floor = if (!(fcmple(a, 45f) || fcmpun(a, 45f))) f06
                else if (!(fcmple(a, 30f) || fcmpun(a, 30f))) f(0x3eae147b) else null
                if (floor != null && !fcmpgt(c, floor)) c = floor
            }
            sum = fadd(sum, c)
        }
        return sum
    }
}
