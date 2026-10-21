package nintype.engine.geometry

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.emkey.Moopad
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.LibM
import nintype.engine.math.Point2D
import nintype.engine.touch.TouchPoint
import nintype.engine.touch.TravKeyData
import nintype.engine.vectors.MemImage

/**
 * Moopad::genTraversal / addTraversal: which keys a touch point "traverses" (its candidate
 * keys, MooTouchPoint+0x328 trav list), scored by forgiving distance from the key centre.
 *
 * MooTouchPoint fields read (tp, sizeof 1000):
 *   0x000 Point2D prev   point before this one      0x0d4 Point2D alt position (slide)
 *   0x0dc Point2D pos                              0x0fc Point2D corner-cut position
 *   0x10c Point2D dir    unit direction            0x158 f32 pauseDur (s)
 *   0x210 f32 speed-ish  (C1 input, slides)        0x224 u16 flags (0x1800 both = 0.77 mult,
 *   0x230 f32 radius     (score scale)                  0x2000 detached)
 *   0x2f0 Mookey* key    (detached points)          0x34d u8 sliding
 *   0x350 f32 score multiplier                      0x328 vector<TravKeyData> travs
 */
object Traversal {
    private fun f(b: Int) = java.lang.Float.intBitsToFloat(b)

    /** addTraversal's first static curve (slides: forgiveness factor by tp+0x210). */
    val C1: FastCurveMapper = FastCurveMapper()
        .pushBits(0x3f000000, 0x3f800000).pushBits(0x3f333333, 0x3f733333).pushBits(0x3f800000, 0x3f666666)
        .pushBits(0x3fc00000, 0x3f4ccccd).pushBits(0x40000000, 0x3f333333).pushBits(0x40400000, 0x3f266666)
        .finalize()

    /** addTraversal's second static curve (taps: exponent by pause - 0.06 s). */
    val C2: FastCurveMapper = FastCurveMapper()
        .pushBits(0x00000000, 0x3f800000).pushBits(0x3dcccccd, 0x3f933333).pushBits(0x3e4ccccd, 0x3fa66666)
        .pushBits(0x3ea8f5c3, 0x3ff33333).pushBits(0x3f000000, 0x40400000)
        .finalize()

    private fun pt(img: MemImage, off: Int) = Point2D(img.f32(off), img.f32(off + 4))

    private fun xfl(mp: MemImage, a: Point2D, b: Point2D, slide: Boolean, dir: Point2D, k: Float): Float =
        Moopad.xForgivenLength(a, b, slide, dir, k, mp.f32(Moopad.OFF_XFORGIVEN), mp.f32(Moopad.OFF_KEY_H))

    private fun geOrUn(a: Float, b: Float) = fcmpge(a, b) || fcmpun(a, b)
    private fun leOrUn(a: Float, b: Float) = fcmple(a, b) || fcmpun(a, b)

    /** Moopad::addTraversal(MooTouchPoint&, Mookey&) @00683d38. */
    fun addTraversal(mp: MemImage, tp: MemImage, key: Mookey) {
        val kimg = key.img
        val chara = kimg.i32(Mookey.CHARA)
        if (chara == 0x20) return
        if (chara == 0 && kimg.i32(Mookey.NONBASIC) == 0) return
        val tc = key.getTrieChara()
        val atc = key.getAltTrieChara()
        val nb = key.getNonBasicChara()
        val pos = pt(tp, 0xdc)
        val p0 = pt(tp, 0x0)
        val d = pos - p0
        val a = p0 + d * f(0x3f4ccccd)
        val b = p0 + d * f(0x3f933333)
        val dir = pt(tp, 0x10c)
        val sliding = tp.u8(0x34d) != 0
        val k = if (sliding) C1.get(tp.f32(0x210)) else 1.0f
        val centre = key.centre
        val l0 = xfl(mp, centre, pos, sliding, dir, k)
        val l1 = xfl(mp, centre, a, sliding, dir, k)
        val l2 = xfl(mp, centre, b, sliding, dir, k)
        var s = l0
        val f03 = f(0x3e99999a)
        val f07 = f(0x3f333333)
        if (!geOrUn(l1, l0)) s = fadd(fmul(l0, f03), fmul(l1, f07))
        if (!geOrUn(l2, l0)) s = fadd(fmul(l0, f03), fmul(l2, f07))
        var mult = 1.0f
        if (sliding) {
            val l3 = xfl(mp, centre, pt(tp, 0xd4), true, dir, k)
            if (!geOrUn(l3, s)) s = fadd(fmul(s, 0.5f), fmul(l3, 0.5f))
            mult = if (tp.u16(0x224) and 0x1800 == 0x1800) f(0x3f451eb8) else 1.0f
        }
        var l4 = xfl(mp, centre, pt(tp, 0xfc), sliding, dir, k)
        if (fcmplt(s, l4)) l4 = s
        val dist = fmul(mult, l4)
        if (geOrUn(dist, 160f)) return
        val score: Float
        if (tp.u16(0x224) and 0x2000 != 0) {
            val v = centre - pos
            val u = Point2D(fdiv(v.x, mp.f32(MooBoard.MP_KEY_W)), fdiv(v.y, mp.f32(MooBoard.MP_KEY_H)))
            val r = fsub(1.0f, u.length())
            score = when {
                fcmplt(r, 0f) -> 1.5f
                fcmpgt(r, 1.0f) -> 4.5f
                else -> {
                    val sc = fmul(r, 4.5f)
                    if (fcmplt(sc, 1.5f)) 1.5f else if (leOrUn(sc, f(0x3dcccccd))) return else sc
                }
            }
        } else {
            val rad = tp.f32(0x230)
            val q = fdiv(fsub(rad, dist), rad)
            var t = if (fcmplt(q, 0f)) 0f else if (fcmpgt(q, 1.0f)) 1.0f else q
            val pause = tp.f32(0x158)
            if (!leOrUn(pause, f(0x3d75c28f))) {
                val g = C2.get(fadd(pause, f(0xbd75c28f.toInt())))
                t = LibM.powf(t, g)
            }
            val sc = fmul(t, tp.f32(0x350))
            if (leOrUn(sc, f(0x3dcccccd))) return
            score = sc
        }
        val tcb = tc and 0xff
        val i = TouchPoint.pushTrav(tp, tcb, nb, score)
        setTravPos(tp, i, if (tcb == 0x20) Point2D(pos.x, centre.y) else centre)
        val atcb = atc and 0xff
        if (tcb == atcb) return
        val j = TouchPoint.pushTrav(tp, atcb, nb, fmul(score, f07))
        setTravPos(tp, j, centre + key.getAltTrieCharaPosShift())
    }

    private fun setTravPos(tp: MemImage, i: Int, p: Point2D) {
        val l = TravKeyData.list(tp)
        l[i].w[0] = java.lang.Float.floatToRawIntBits(p.x)
        l[i].w[1] = java.lang.Float.floatToRawIntBits(p.y)
        TravKeyData.store(tp, l)
    }

    /**
     * Moopad::genTraversal(MooTouchPoint&) @00684638: clear the trav list; with a current board
     * whose byte 0x7a is set: a detached point traverses its own key (tp+0x2f0, if any), a
     * sliding point every key of every row; then sortTravs. Without such a board the list is
     * just cleared (no sort).
     */
    fun genTraversal(mp: MemImage, tp: MemImage, board: MooBoard?, detachedKey: Mookey?) {
        TravKeyData.store(tp, emptyList())
        if (board == null || board.img.u8(0x7a) == 0) return
        if (tp.u16(0x224) and 0x2000 != 0) {
            if (detachedKey != null) addTraversal(mp, tp, detachedKey)
        } else if (tp.u8(0x34d) != 0) {
            for (r in board.rows) for (k in r.keys) addTraversal(mp, tp, k)
        }
        TouchPoint.sortTravs(tp)
    }
}
