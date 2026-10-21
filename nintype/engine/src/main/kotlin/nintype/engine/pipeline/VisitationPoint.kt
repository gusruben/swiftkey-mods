package nintype.engine.pipeline

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.emkey.Moopad
import nintype.engine.emkey.MooTouchPoint
import nintype.engine.geometry.KeyboardModel
import nintype.engine.math.Point2D
import nintype.engine.touch.Mootouch
import nintype.engine.touch.TouchPoint
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.Values

/**
 * `Emkey::Mootouch::syncVisitationPoint()` @0097434c, run by registerSittingTouch on the
 * touch it just appended to the sitting list.
 *
 * For each corner-kink point c (vector +0x250, [Mootouch.ck]):
 *  1. its visitation point (MooTouchPoint +0xc): over the drift points (+0x244, [Mootouch.pts])
 *     that are visitation points (+0x34d or +0x34c), the first p with c.t < p.t + 0.01 (t = f64
 *     +0x120). With an earlier visitation point `prev` the two compete: prev wins when
 *     0.6 * d(c, prev) / (d(c, prev) + d(c, p)) + 0.4 * dt(c, prev) / (dt(c, prev) + dt(c, p)) < 0.5
 *     (d over +0xdc, dt = |difference of f64 +0x128| as float). Without such a p, the last
 *     visitation point (null without any);
 *  2. +0x10 = Mootouch::findLatestImportantPointBefore(c);
 *  3. syncCoverage(clampCoveragePos(v.+0xdc), clampCoveragePos(v.+0xd4)) for v = that visitation
 *     point (TouchPoint.syncCoverage, inlined in the binary);
 *  4. when v is not a +0x34c point and c has TravKeyData (+0x328): c's first one goes into v's
 *     +0x334 list, replacing the entry with the same char (+8) when that entry's score (+0x10) is
 *     lower, or appended when there is none.
 * Then every drift point p gets syncCoverage(clamp(p.+0xdc), clamp(p.+0xd4)).
 *
 * Representation: +0xc and +0x10 are MooTouchPoint* into the drift points in the binary (raw
 * words in touch_ext.py's layout); here they hold the drift-point index, -1 for null, the
 * convention of the 0x2c..0x38 links.
 */
object VisitationPoint {
    private val D_001 = Bits.bitsToDouble(0x3f847ae140000000L)
    private val F04 = Bits.bitsToFloat(0x3ecccccd)
    private val F06 = Bits.bitsToFloat(0x3f19999a)

    private fun pt(p: MemImage, o: Int) = Point2D(p.f32(o), p.f32(o + 4))

    fun sync(mt: Mootouch, model: KeyboardModel) {
        val board = model.board
        val unit = model.mp.f32(Moopad.OFF_UNIT)
        val pts = mt.pts
        for (c in mt.ck) {
            val ct = c.f64(0x120)
            var best = -1
            var chosen = -1
            for ((i, p) in pts.withIndex()) {
                if (!MooTouchPoint.isVisitationPoint(p)) continue
                val t = dadd(p.f64(0x120), D_001)
                if (best < 0) {
                    if (dcmplt(ct, t)) { chosen = i; break }
                    best = i
                    continue
                }
                if (dcmpge(ct, t) || dcmpun(ct, t)) { best = i; continue }
                val prev = pts[best]
                val cp = pt(c, 0xdc)
                val l1 = cp.lengthFrom(pt(prev, 0xdc))
                val l2 = cp.lengthFrom(pt(p, 0xdc))
                val t1 = d2f(Math.abs(dsub(c.f64(0x128), prev.f64(0x128))))
                val t2 = d2f(Math.abs(dsub(c.f64(0x128), p.f64(0x128))))
                val tt = fmul(fdiv(t1, fadd(t1, t2)), F04)
                val lt = fmul(fdiv(l1, fadd(l1, l2)), F06)
                chosen = if (fcmplt(fadd(lt, tt), 0.5f)) best else i
                break
            }
            if (chosen < 0) chosen = best
            c.setI32(0xc, chosen)
            c.setI32(0x10, mt.findLatestImportantPointBefore(c.f64(0x128)))
            val v = pts[chosen]   // the binary dereferences it unconditionally
            TouchPoint.syncCoverage(c, board.clampCoveragePos(pt(v, 0xdc)), board.clampCoveragePos(pt(v, 0xd4)), unit, board)
            val travs = c.seq(0x328)
            if (v.u8(0x34c) == 0 && travs.isNotEmpty()) {
                val e = MemImage.decode(travs[0]).readBytes(0, 0x20)
                val eImg = MemImage("trav", 0x20).also { it.writeBytes(0, e) }
                val list = v.seq(0x334)
                val elem = Values.struct("@0x0" to Values.bytesLeaf(e))
                val at = list.indexOfFirst { MemImage.decode(it).i32(8) == eImg.i32(8) }
                if (at < 0) list.add(elem)
                else {
                    val s = MemImage.decode(list[at]).f32(0x10)
                    val es = eImg.f32(0x10)
                    if (!(fcmpge(s, es) || fcmpun(s, es))) list[at] = elem
                }
            }
        }
        for (p in pts) TouchPoint.syncCoverage(p, board.clampCoveragePos(pt(p, 0xdc)), board.clampCoveragePos(pt(p, 0xd4)), unit, board)
    }
}
