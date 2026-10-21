package nintype.engine.pathscore

import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * PredPath members working on the step history for slideVS (`syncLastTwoForSlideVS`,
 * `maybeRecordHistoricNeoSlideVSFrom`). A step (PredHistory, 0x28 bytes) is one letter of the
 * path: +0xc char, +0x14 touch index, +0x15 finger, +0x18 MooTouchPoint*, +0x1e flags, +0x20
 * key centre. RecentSlideInfo: +0 hasTwo, +4 lastDiff, +0xc last, +0x14 prevDiff, +0x1c prev,
 * +0x24 cos(prevDiff, lastDiff).
 */
object SlideHistory {
    private fun MemImage.pt(o: Int) = Point2D(f32(o), f32(o + 4))
    private fun MemImage.setPt(o: Int, p: Point2D) { setF32(o, p.x); setF32(o + 4, p.y) }
    private fun le5(x: Float) = fcmple(x, 5f) || fcmpun(x, 5f)

    /** syncLastTwoForSlideVS(RecentSlideInfo&, PredHistory& h) @0082cb20: finds the last two
     * earlier steps on h's finger (the first one must not have flag bit 0) and records the two
     * travel vectors ending at h's point and their cosine. `h` may itself be one of the steps
     * (it is skipped, by identity). */
    fun syncLastTwoForSlideVS(pp: MemImage, rsi: MemImage, h: MemImage) {
        val st = Vecs.elems(pp, PredPath.OFF_STEPS)
        val fin = h.u8(0x15)
        var i = st.size - 1
        var e1 = -1
        while (i >= 0) {
            val e = st[i]
            if (e !== h && e.u8(0x15) == fin && e.u8(0x1e) and 1 == 0) {
                rsi.setPt(0xc, e.ref(0x18)!!.pt(0xdc))
                rsi.setPt(4, h.ref(0x18)!!.pt(0xdc) - rsi.pt(0xc))
                e1 = i
                break
            }
            i--
        }
        if (e1 <= 0) return
        for (j in e1 - 1 downTo 0) {
            val e = st[j]
            if (e === h || e.u8(0x15) != fin) continue
            rsi.setU8(0, 1)
            rsi.setPt(0x1c, e.ref(0x18)!!.pt(0xdc))
            rsi.setPt(0x14, rsi.pt(0xc) - rsi.pt(0x1c))
            val lB = rsi.pt(0x14).length()
            val lA = rsi.pt(4).length()
            if (le5(lB) || le5(lA)) return
            rsi.setF32(0x24, fdiv(Point2D.dot(rsi.pt(0x14), rsi.pt(4)), fmul(lB, lA)))
            return
        }
    }

    /** maybeRecordHistoricNeoSlideVSFrom(SlideVSTarget const& tg, PredHistory& h, Point2D const&
     * p2, RecentSlideInfo&, Moopad*, double, float& hard) @0082cd74: when the target point was
     * visited, finds the latest step on it and runs a neo slideVS check from that step to h
     * (a local SlideVSArg, weight = target+4). Returns whether it ran. */
    fun maybeRecordHistoricNeoSlideVSFrom(pp: MemImage, tg: MemImage, h: MemImage, p2: Point2D, mp: MemImage,
                                          hardImg: MemImage, hardOff: Int): Boolean {
        val tp = tg.ref(0)!!                       // tp.0x34d == 0 only asserts
        val idx = tp.i32(0x308)
        if (!Flag64.flagged(pp, 0xf0, idx)) return false
        val st = Vecs.elems(pp, PredPath.OFF_STEPS)
        var e: MemImage? = null
        for (k in st.indices.reversed()) {
            val p = st[k].ref(0x18)!!
            if (p.i32(if (p.u8(0x34d) != 0) 0x308 else 0x304) == idx) { e = st[k]; break }
        }
        if (e == null) return false               // (assert)
        if (e.i32(0xc) == h.i32(0xc)) return false
        val a = MemImage("slideVSArg", SlideVSArg.SIZE)
        val ep = e.ref(0x18)!!
        val hp = h.ref(0x18)!!
        a.setPt(0x3c, h.pt(0x20) - e.pt(0x20))
        a.setPt(4, ep.pt(0xdc))
        val (da, db) = determineSlideDiffs(e.u8(0x14) != h.u8(0x14), ep.u16(0x224) and 0x2000 != 0, ep.pt(0xdc),
            hp.u16(0x224) and 0x2000 != 0, hp.pt(0xdc), p2)
        a.setPt(0x1c, da); a.setPt(0x24, db)
        a.setU8(0x50, 1)
        a.setF32(0x54, tg.f32(4))
        PredPath.maybeRecordSlideVSPenalty(pp, mp, a, hardImg, hardOff)
        return true
    }
}
