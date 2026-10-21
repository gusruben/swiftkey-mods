package nintype.engine.touch

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dcmpge
import nintype.engine.SoftFloat.dcmpgt
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.emkey.Moopad
import nintype.engine.emkey.MooTouchPoint
import nintype.engine.geometry.Geometry
import nintype.engine.geometry.KeyboardModel
import nintype.engine.geometry.Mookey
import nintype.engine.geometry.Traversal
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.LibM
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * What a Mootouch reads outside itself: the keyboard (keys are referenced by index into
 * [model].keys), the engine clock, RoenGL's length unit and a few Moopad scalars.
 *  - [now]: the engine clock in seconds (the .bss double record reads, VA 0x1b92f38)
 *  - [roenScale]: RoenGL+0x13c, the length unit of the detachment/drag thresholds
 *  - [mp]: Moopad scalars at their offsets (0x1f4a0 keyHeight = curboardVertlen, 0x1f4a4 keyWidth,
 *    0xcf0 the row-debug Xnl); unitDist/genTraversal use [model].mp.
 */
/** The TouchInfo fields takeForceTouchUpdate reads: +0x48 u8 hasForce (raw byte), +0x4c f32
 * force (pressure), +0x50 f32 force2 (size). */
class ForceInfo(val hasForce: Int, val force: Float, val force2: Float)

class TouchEnv(val model: KeyboardModel, val mp: MemImage, var now: Double, val roenScale: Float,
               /** frame step (VA 0x1b92f40), update()'s second clock (0x1b9e4f8), per-frame increment
                * (0x1b92f48) and decay exponent (0x1b8f2d8): the doubles update() reads. */
               val frameDt: Double = 0.0, val clock2: Double = 0.0, val g3: Double = 0.0, val g4: Double = 0.0) {
    fun key(i: Int): Mookey? = if (i < 0) null else model.keys[i]
}

/**
 * `Emkey::Mootouch` (0x3b8 bytes), one finger's touch: a byte image of its scalar fields at
 * the binary's offsets plus Kotlin containers for the parts that are pointers there.
 *
 * Fields (named from use):
 *   0x008 i32 curKey (holder 0x4: the key under the finger, findKey)     0x014 i32 key (holder 0x10, same after update)
 *   0x020 i32 firstKey (holder 0x1c)  0x02c i32 sameBoardKey (0x28)       0x038 i32 boardFlagKey (0x34)
 *   0x040 Point2D lastKeyUpdatePos    0x048 u8 onSpace   0x049 u8 spaceToucher   0x04b u8 twerkInit
 *   0x04c..0x7c 4 Coord3D twerk target (z -100)   0x084 f32 twerk speed (px/s over the last 0.05 s)
 *   0x0a8 f32 (travel accumulates while < 0.4)    0x0b0 f32 manhattan travel
 *   0x0b5 u8 active (a real typing touch)  0x0b6 u8 stock mode
 *   0x110 f64 tLastRecord   0x118 f64 tLastMove (not the update() re-record)   0x128 Point2D start   0x130 Point2D cur
 *   0x138 i32 row   0x13c..0x178 8 Point2D history slots   0x17c..0x1ac 4 Coord3D (z = keyWidth * -5)
 *   0x1b8 f32 accuracy input   0x1bc u8   0x1c8 f64 tLastRecord2   0x1d0 u8
 *   0x1d4 Point2D lastPos   0x1e0 f64 tLastPos   0x1e8 u8 hasLastPos
 *   0x1ec deque<{f32 dt, i32 pxPerSec}> pixel movement frames (at most 50, newest last): [pix]
 *   0x214 ChasingFloat speedChase {cur, mid, target = speed}   0x220 f64 tStop
 *   0x228 ChasingFloat accelChase   0x234 u8 stopped   0x235 u8 accelArmed   0x236 u8 slowing
 *   0x238 f64 tSlow   0x240 u8 slowCount
 *   0x244 vector<MooTouchPoint> drift points, 0x250 CK (current-key) points, 0x25c: [vecs]
 */
class Mootouch(val body: MemImage, val vecs: List<MutableList<MemImage>>, val pix: ArrayDeque<Pair<Int, Int>>) {
    val pts: MutableList<MemImage> get() = vecs[0]
    val ck: MutableList<MemImage> get() = vecs[1]

    private fun pt(off: Int) = Point2D(body.f32(off), body.f32(off + 4))
    private fun setPt(off: Int, p: Point2D) { body.setF32(off, p.x); body.setF32(off + 4, p.y) }

    companion object {
        private fun f(b: Int) = Bits.bitsToFloat(b)
        private fun d(b: Long) = Bits.bitsToDouble(b)
        private val D_005 = d(0x3fa99999a0000000L)   // (double) 0.05f
        private val D_0001 = d(0x3f50624de0000000L)  // (double) 0.001f
        private val D_009 = d(0x3fb70a3d80000000L)   // (double) 0.09f
        private val D_M0085 = d(0xbfb5c28f60000000UL.toLong())
        private val D_M005 = d(0xbf999999a0000000UL.toLong())
        private val D_004 = d(0x3fa47ae147ae147bL)    // 0.04
        private val PI_D = d(0x400921fb54442d18L)

        fun ptOf(img: MemImage, off: Int) = Point2D(img.f32(off), img.f32(off + 4))
        fun setPtOf(img: MemImage, off: Int, p: Point2D) { img.setF32(off, p.x); img.setF32(off + 4, p.y) }
        fun absf(x: Float) = Bits.bitsToFloat(Bits.floatToBits(x) and 0x7fffffff)

        /** isSimpleTap's static curve {0.07->1, 0.1->0.5, 0.15->0.3, 0.2->0.1} (dwell -> tap tolerance). */
        /** finalizeSlownessDetection's static curves (points as pushed; finalize sorts). */
        val SLOW_DOT: FastCurveMapper = FastCurveMapper()
            .push(f(0x3f428f5c), 1f).push(f(0x3f19999a), f(0x3f19999a)).push(f(0x3ecccccd), f(0x3e4ccccd)).push(f(0x3e4ccccd), -4f).finalize()
        val SLOW_DIST: FastCurveMapper = FastCurveMapper()
            .push(f(0x3f19999a), 2f).push(1f, 1f).push(f(0x3f99999a), f(0x3e99999a)).push(f(0x3fcccccd), f(0x3e4ccccd)).finalize()
        val SLOW_TIME: FastCurveMapper = FastCurveMapper()
            .push(f(0x3cf5c28f), 2f).push(f(0x3d4ccccd), 1f).push(f(0x3dcccccd), 0.5f).push(f(0x3e19999a), f(0x3e99999a))
            .push(f(0x3e4ccccd), f(0x3e19999a)).push(f(0x3eb33333), -0.5f).finalize()

        private val D_M004 = d(0xbfa47ae147ae147bUL.toLong()) // -0.04
        private fun curve(vararg xy: Int) = FastCurveMapper.ofBits(*xy)
        /** syncDampeningState's static curves, as pushed (bit patterns from the literal pools). */
        val DAMP_A = curve(0x3ecccccd, 0, 0x3f800000, 0x3e99999a, 0x3f99999a, 0x3f4ccccd, 0x3fab851f, 0x3f800000)
        val DAMP_B = curve(0x3c23d70a, 0, 0x3d4ccccd, 0x3e4ccccd, 0x3da3d70a, 0x3f4ccccd, 0x3dcccccd, 0x3f800000)
        val DAMP_C = curve(0x3ca3d70a, 0x3d4ccccd, 0x3d23d70a, 0x3e4ccccd, 0x3da3d70a, 0x3f000000, 0x3df5c28f, 0x3f400000,
            0x3e19999a, 0x3f800000)
        val DAMP_D = curve(0x3f800000, 0x40c00000, 0x3f28f5c3, 0x3fc00000, 0x3ea8f5c3, 0x3f8ccccd, 0, 0x3f800000,
            0xbf000000.toInt(), 0x3f333333, 0xbf800000.toInt(), 0x3f000000)
        val DAMP_E = curve(0, 0x3f800000, 0x3ca3d70a, 0x3f7ae148, 0x3d23d70a, 0x3f75c28f, 0x3d8f5c29, 0x3f2b851f,
            0x3dcccccd, 0x3ecccccd, 0x3e4ccccd, 0x3dcccccd, 0x3e99999a, 0)
        val DAMP_F = curve(0, 0x3f800000, 0x3f000000, 0x3f733333, 0x3f800000, 0x3f59999a, 0x3f933333, 0x3f000000,
            0x3fc00000, 0x3e19999a, 0x3fe00000, 0)
        val DAMP_G = curve(0, 0x3ecccccd, 0x3f000000, 0x3f400000, 0x3f800000, 0x3f63d70a, 0x3fa66666, 0x3f75c28f)
        val DAMP_H = curve(0x3ca3d70a, 0x3ff33333, 0x3d23d70a, 0x3fa66666, 0x3d6147ae, 0x3f800000, 0x3d8f5c29, 0x3f4ccccd,
            0x3dcccccd, 0x3f000000, 0x3e051eb8, 0x3e800000, 0x3e19999a, 0x3c23d70a)
        val DAMP_I = curve(0x3d75c28f, 0x40e00000, 0x3da3d70a, 0x40400000, 0x3dcccccd, 0x3f800000, 0x3e99999a, 0x3f000000)

        /** isProbablyProtractedSpaceTap's curve {0.1 -> 0, 0.4 -> 0.1} (hold time -> key heights). */
        val PROTRACT: FastCurveMapper = FastCurveMapper().push(f(0x3dcccccd), 0f).push(f(0x3ecccccd), f(0x3dcccccd)).finalize()

        /** Point2D::towards(Point2D const& target, float d) @00c1dc7c: this + (target - this) *
         * min(d / |target - this|, 1); the target itself when closer than 0.003. */
        fun towards(a: Point2D, t: Point2D, dist: Float): Point2D {
            val dx = fsub(t.x, a.x); val dy = fsub(t.y, a.y)
            val l = LibM.sqrtf(fadd(fmul(dx, dx), fmul(dy, dy)))
            if (!(fcmpge(l, f(0x3b449ba6)) || fcmpun(l, f(0x3b449ba6)))) return t
            var k = fdiv(dist, l)
            if (fcmpgt(k, 1f)) k = 1f
            return Point2D(fadd(a.x, fmul(dx, k)), fadd(a.y, fmul(dy, k)))
        }

        /** Point2D::withinLineWithTolerance(a, b, across, along) const @00c1f8d0: |cross| / |ab|
         * <= across (unordered passes the first test only if neither compare holds) and the
         * projection on ab within [-0 - along, |ab| + along] (unordered passes). */
        fun withinLineWithTolerance(p: Point2D, a: Point2D, b: Point2D, across: Float, along: Float): Boolean {
            val abx = fsub(b.x, a.x); val aby = fsub(b.y, a.y)
            val apx = fsub(p.x, a.x); val apy = fsub(p.y, a.y)
            val dotv = fadd(fmul(abx, apx), fmul(apy, aby))
            val l = LibM.sqrtf(fadd(fmul(abx, abx), fmul(aby, aby)))
            val inv = fdiv(1f, l)
            val proj = fmul(inv, dotv)
            val perp = fmul(inv, fsub(fmul(abx, apy), fmul(apx, aby)))
            if (fcmplt(perp, Bits.bitsToFloat(Bits.floatToBits(across) xor Int.MIN_VALUE))) return false
            if (fcmpgt(perp, across)) return false
            val lo = fsub(-0f, along)
            val ok1 = fcmpge(proj, lo) || fcmpun(proj, lo)
            val hi = fadd(l, along)
            val ok2 = fcmple(proj, hi) || fcmpun(proj, hi)
            return ok1 && ok2
        }

        val TAP_CURVE: FastCurveMapper = FastCurveMapper()
            .push(f(0x3d8f5c29), 1f).push(f(0x3dcccccd), 0.5f).push(f(0x3e19999a), f(0x3e99999a)).push(f(0x3e4ccccd), f(0x3dcccccd))
            .finalize()
    }

    // --- small callees ------------------------------------------------------------------

    /** pushPixMovementFrame(float dt, int pxPerSec) @00975a5c: push_back, then pop_front when
     * more than 50 frames. */
    fun pushPixMovementFrame(dt: Float, v: Int) {
        pix.addLast(Bits.floatToBits(dt) to v)
        if (pix.size > 50) pix.removeFirst()
    }

    /** The newest frames whose dt sum passes [window] (inclusive of the crossing frame):
     * (sum dt, sum dt * pxPerSec) as doubles, walking newest to oldest. */
    private fun recentFrames(window: Double): Pair<Double, Double> {
        var t = 0.0; var dsum = 0.0
        for (i in pix.indices.reversed()) {
            val (dtb, v) = pix[i]
            val dt = f(dtb)
            t = dadd(t, f2d(dt))
            val over = dcmpgt(t, window)
            dsum = dadd(dsum, f2d(fmul(dt, i2f(v))))
            if (over) break
        }
        return t to dsum
    }

    /** recordTwerkTarget(Point2D const&) @00976778. */
    fun recordTwerkTarget(p: Point2D) {
        if (body.u8(0x4b) == 0) {
            for (o in intArrayOf(0x4c, 0x58, 0x64, 0x70)) { setPt(o, p); body.setF32(o + 8, -100f) }
            body.setU8(0x4b, 1)
        }
        body.setI32(0x78, 0)
        setPt(0x70, p)
        if (pix.isEmpty()) return
        val (t, dsum) = recentFrames(D_005)
        if (dcmplt(t, D_0001)) return
        val lt = dcmplt(t, D_005)
        val w = if (lt) f(0x3d4ccccd) else d2f(t)
        if (fcmple(w, f(0x3ca3d70a)) || fcmpun(w, f(0x3ca3d70a))) return
        body.setF32(0x84, d2f(ddiv(dsum, if (dcmplt(t, D_005)) D_005 else t)))
    }

    /** findPointClosestToTime_WhileRecording(double t) @00975f70: newest to oldest drift point,
     * the one whose time (0x148, or this+0x110 for the newest) is closest to t (strictly closer
     * replaces, so the newest wins ties; a NaN distance never replaces). Points with both neighbours get their angle resynced
     * (MooTouchPoint::syncAngle) and the walk stops once the summed angles pass 20 degrees.
     * Returns the point's index, -1 if none. */
    fun findPointClosestToTime(t: Double): Int {
        val n = pts.size
        if (n == 0) return -1
        var best = f(0x461c3c00)
        var bestI = -1
        var sum = 0f
        var i = n - 1
        while (i >= 0) {
            val p = pts[i]
            val ref = if (i == n - 1) body.f64(0x110) else p.f64(0x148)
            val dd = dsub(t, ref)
            val dist = d2f(Bits.bitsToDouble(Bits.doubleToBits(dd) and Long.MAX_VALUE))
            if (bestI < 0 || !(fcmpge(dist, best) || fcmpun(dist, best))) { best = dist; bestI = i }
            if (i + 1 < n && i - 1 >= 0) {
                MooTouchPoint.syncAngle(p, pts[i - 1], pts[i + 1])
                sum = fadd(sum, p.f32(0x300))
                if (fcmpgt(sum, 20f)) break
            }
            i--
        }
        return bestI
    }

    /** markRow(int) @0097575c: row@0x138 (the Xnl'd debug freeze it can trigger is not modelled). */
    fun markRow(row: Int) {
        if (body.i32(0x138) != row) body.setI32(0x138, row)
    }

    /** isSimpleTap() @00959130. */
    fun isSimpleTap(env: TouchEnv): Boolean {
        if (body.u8(0xb5) == 0) {
            val dd = pt(0x128).lengthFrom(pt(0x130))
            return fcmple(dd, 10f) || fcmpun(dd, 10f)
        }
        val dd = if (pts.isEmpty()) 0f else Moopad.unitDist(pt(0x128), pt(0x130), env.model.mp.f32(Moopad.OFF_UNIT))
        if (fcmpgt(dd, fmul(env.roenScale, f(0x3f99999a)))) return false
        if (pts.isEmpty()) return true
        var key0 = 0
        for ((k, p) in pts.withIndex()) {
            if (k == 0) {
                key0 = p.i32(0x2f0)
                if (fcmpgt(p.f32(0x16c), f(0x3dcccccd))) return false
            } else if (p.i32(0x2f0) != key0) return false
        }
        val dt = d2f(dsub(body.f64(0x110), body.f64(0xf8)))
        val c = TAP_CURVE.get(dt)
        val len = ptOf(pts[0], 0xd4).lengthFrom(ptOf(pts.last(), 0xdc))
        return fcmplt(len, fmul(c, fmul(env.roenScale, 40f)))
    }

    /** updateCurrentKey(Point2D const&) @00963350: re-resolves the key under the finger
     * (unless still inside the current key of an active board) and the derived holders.
     * Returns whether the key changed. Mookey glow / toucher counts (UI state) are not modelled. */
    fun updateCurrentKey(env: TouchEnv, p: Point2D): Boolean {
        val x = f2iz(p.x)
        val y = f2iz(p.y)
        val cur = env.key(body.i32(0x14))
        if (cur == null) {
            setKey(env, env.model.board.findKey(x, y))
            setPt(0x40, p)
            return body.i32(0x14) >= 0
        }
        if (cur.contains(x, y) && env.model.board.isActive() != 0) return false
        val old14 = body.i32(0x14)
        val old8 = body.i32(0x8)
        setKey(env, env.model.board.findKey(x, y))
        setPt(0x40, p)
        return if (old8 == body.i32(0x8)) old14 != body.i32(0x14) else true
    }

    private fun setKey(env: TouchEnv, k: Mookey?) {
        val ki = env.model.indexOf(k)
        if (body.i32(0x8) != ki) {
            body.setI32(0x8, ki)
            body.setU8(0x48, if (k != null && k.chara == 0x20) 1 else 0)
        }
        body.setI32(0x14, ki)
        if (k == null) return
        if (env.model.board.img.u8(0x1c) != 0) body.setI32(0x38, ki)
        if (body.i32(0x20) < 0) body.setI32(0x20, ki)
        if (body.i32(0x20) >= 0) body.setI32(0x2c, ki) // one board: key.board == firstKey.board
    }

    /** The accuracy refresh shared by pushPointEx(false), record and updateLastAccuracy()
     * @0096f1d0 on the newest drift point. */
    fun updateLastAccuracy() {
        if (pts.isEmpty()) return
        val last = pts.last()
        if (pts.size == 1) { last.setF32(0x230, 67f); return }
        if (last.u8(0x225) and 1 != 0) return
        val x = fmul(body.f32(0x1b8), 0.03125f)
        val v = if (!(fcmpge(x, 0f) || fcmpun(x, 0f))) -LibM.powf(-x, f(0x3f19999a)) else LibM.powf(x, f(0x3f19999a))
        val s = fmul(v, 32f)
        val old = last.f32(0x230)
        val m = if (fcmplt(s, old)) s else old
        last.setF32(0x230, if (fcmpge(m, 44f) || fcmpun(m, 44f)) m else 44f)
    }

    /** pushPointEx(bool ck) @009749a0: appends a new MooTouchPoint at the current position to
     * the drift (false) or CK (true) points, finalises the previous one (angle, scores, lead
     * points; CK: Moopad::genTraversal). Returns the new point's index. Trail vertices, key
     * boxes and the trail colour (point 0x15c) are rendering and not modelled. */
    fun pushPointEx(env: TouchEnv, isCk: Boolean): Int {
        val v = if (isCk) ck else pts
        val cur = pt(0x130)
        val prevPos: Point2D
        if (v.isEmpty()) prevPos = cur
        else {
            val last = v.last()
            prevPos = ptOf(last, 0xdc)
            setPtOf(last, 0xf4, pt(0x13c))
            last.setF64(0x148, env.now)
            last.setU16(0x224, last.u16(0x224) and 0xefff)
        }
        val np = MootouchCodec.newPoint()
        v.add(np)
        np.setF32(0x22c, body.f32(0x21c))
        setPtOf(np, 0xd4, cur); setPtOf(np, 0xdc, cur); setPtOf(np, 0xe4, cur)
        setPtOf(np, 0xec, pt(0x13c)); setPtOf(np, 0xf4, pt(0x13c))
        setPtOf(np, 0x0, prevPos)
        if (v.size == 1) { setPtOf(np, 0x0, cur); setPtOf(np, 0xf4, cur); setPtOf(np, 0xec, cur) }
        for (o in intArrayOf(0x138, 0x140, 0x128, 0x120)) np.setF64(o, env.now)
        np.setU16(0x224, np.u16(0x224) or 0x1000)
        np.setI32(0x2f0, body.i32(0x14))
        np.setI32(0x2f8, body.i32(0x40)); np.setI32(0x2fc, body.i32(0x44))
        np.setU16(0x224, (np.u16(0x224) and 0xdfff) or (if (isCk) 0x2000 else 0))
        if (!isCk) updateLastAccuracy()
        val n = v.size
        if (n > 1) {
            val q = v[n - 2]
            if (n > 2) MooTouchPoint.syncAngle(q, v[n - 3], np)
            if (!(dcmpge(q.f64(0x128), 0.0) || dcmpun(q.f64(0x128), 0.0))) q.setF64(0x128, q.f64(0x148))
            TouchPoint.syncScores(q)
            TouchPoint.syncLeadPoints(q)
            if (isCk) Traversal.genTraversal(env.model.mp, q, env.model.board, env.key(q.i32(0x2f0)))
        }
        return n - 1
    }

    private fun fillHistory(p: Point2D, keyW: Float) {
        for (o in 0x13c until 0x17c step 8) setPt(o, p)
        val z = fmul(keyW, -5f)
        for (o in intArrayOf(0x17c, 0x188, 0x194, 0x1a0)) { setPt(o, p); body.setF32(o + 8, z) }
    }

    // --- small queries --------------------------------------------------------------------

    /** isSimplishTap() @00959488: inactive touch, or 1 drift point, or 2 whose first has
     * 0x36c <= 0.85 (or NaN). */
    fun isSimplishTap(): Boolean {
        if (body.u8(0xb5) == 0) return true
        val n = pts.size
        if (n == 0 || n >= 3) return false
        if (n == 1) return true
        val a = pts[0].f32(0x36c)
        return fcmple(a, f(0x3f59999a)) || fcmpun(a, f(0x3f59999a))
    }

    /** hasNotMoved() @00965578: fewer than two drift points. */
    fun hasNotMoved(): Boolean = pts.size < 2

    /** hardlyMovedAtAll() @009655f0: fewer than two drift points, or all within 16 of the current position. */
    fun hardlyMovedAtAll(): Boolean {
        if (pts.size < 2) return true
        for (p in pts) {
            val d = ptOf(p, 0xdc).lengthFrom(pt(0x130))
            if (!(fcmple(d, 16f) || fcmpun(d, 16f))) return false
        }
        return true
    }

    /** getDurationSoFar() @00965680: d2f(0x110 - 0xf8). */
    fun getDurationSoFar(): Float = d2f(dsub(body.f64(0x110), body.f64(0xf8)))

    /** didStationary(float t) @009656ac: 0x270 > t. */
    fun didStationary(t: Float): Boolean = fcmpgt(body.f32(0x270), t)

    /** isStationaryHolding(float t) @009656c8: fewer than two drift points, within 0.3 key widths
     * of the start, and held longer than t: (0x110 - t) > 0xf8. */
    fun isStationaryHolding(env: TouchEnv, t: Float): Boolean {
        if (pts.size >= 2) return false
        if (fcmpgt(pt(0x130).lengthFrom(pt(0x128)), fmul(env.mp.f32(0x1f4a4), f(0x3e99999a)))) return false
        return dcmpgt(dsub(body.f64(0x110), f2d(t)), body.f64(0xf8))
    }

    /** isRecentlyStationaryHolding(float t) @009657d4: the newest drift point unchanged for more
     * than t: (0x110 - last.0x120) > t. */
    fun isRecentlyStationaryHolding(t: Float): Boolean =
        pts.isNotEmpty() && dcmpgt(dsub(body.f64(0x110), pts.last().f64(0x120)), f2d(t))

    /** getSimpleDisplacementPixels() @00964d30: |start - cur|. */
    fun getSimpleDisplacementPixels(): Float = pt(0x128).lengthFrom(pt(0x130))

    /** getUnitDisplacement() @00964d9c: unitDist(start, cur), 0 without drift points. */
    fun getUnitDisplacement(env: TouchEnv): Float =
        if (pts.isEmpty()) 0f else Moopad.unitDist(pt(0x128), pt(0x130), env.model.mp.f32(Moopad.OFF_UNIT))

    /** getSwipeDir() @00965d18: cur - start. */
    fun getSwipeDir(): Point2D = pt(0x130) - pt(0x128)

    /** isSimpleSwipe(float minLen) @00965bf4: no interior drift point turning more than 30
     * degrees (only checked with 3+ points) and |cur - start| >= minLen (NaN passes). */
    fun isSimpleSwipe(minLen: Float): Boolean {
        val v = pt(0x130) - pt(0x128)
        val n = pts.size
        if (n > 2) for (i in 1 until n - 1) if (fcmpgt(pts[i].f32(0x300), 30f)) return false
        val l = v.length()
        return fcmpge(l, minLen) || fcmpun(l, minLen)
    }

    /** isTowards(Point2D const& dir, float minDot, float minLen) @00965d84. */
    fun isTowards(dir: Point2D, minDot: Float, minLen: Float): Boolean {
        val v = pt(0x130) - pt(0x128)
        if (fcmplt(Point2D.dot(dir.normalise(), v.normalise()), minDot)) return false
        val l = v.length()
        return fcmpge(l, minLen) || fcmpun(l, minLen)
    }

    /** isSimpleSwipeTowards(Point2D const& dir, float minDot, float minLen) @00965e78:
     * isSimpleSwipe's interior-angle check, then isTowards. */
    fun isSimpleSwipeTowards(dir: Point2D, minDot: Float, minLen: Float): Boolean {
        val n = pts.size
        if (n >= 3) for (i in 1 until n - 1) if (fcmpgt(pts[i].f32(0x300), 30f)) return false
        return isTowards(dir, minDot, minLen)
    }

    /** getOldPixMovementsAfterFor(float skip, float window, float& speed, float& time) @00975b88
     * (skip = 0: getPixMovementFor(float, float&, float&) @00975dac): newest to oldest pixel
     * frames, the first ones until their dt sum passes [skip] are skipped (a frame is skipped
     * while the sum so far is <= skip), then dt and dt * pxPerSec are summed (as doubles) until
     * the dt sum passes [window] (crossing frame included). Under 0.001 s: (0, 0); else time =
     * max(sum, window), speed = d2f(dsum / time). */
    fun pixMovement(skip: Float?, window: Float): Pair<Float, Float> {
        if (pix.isEmpty()) return 0f to 0f
        val w = f2d(window)
        var skipped = 0f
        var t = 0.0; var dsum = 0.0
        for (i in pix.indices.reversed()) {
            val dt = f(pix[i].first)
            if (skip != null && (fcmple(skipped, skip) || fcmpun(skipped, skip))) { skipped = fadd(skipped, dt); continue }
            t = dadd(t, f2d(dt))
            val over = dcmpgt(t, w)
            dsum = dadd(dsum, f2d(fmul(dt, i2f(pix[i].second))))
            if (over) break
        }
        if (!(dcmpge(t, D_0001) || dcmpun(t, D_0001))) return 0f to 0f
        val den = if (dcmplt(t, w)) w else t
        return d2f(ddiv(dsum, den)) to d2f(den)
    }

    /** findLatestImportantPointBefore(MooTouchPoint* q) @00974144: index of the last drift point
     * of the leading run whose time 0x128 is not after q's (`!(p.0x128 > q.0x128)`); the first
     * point when even that one is later; -1 without points (the binary asserts there). */
    fun findLatestImportantPointBefore(qTime: Double): Int {
        if (pts.isEmpty()) return -1
        var found = -1
        for ((i, p) in pts.withIndex()) {
            if (dcmpgt(p.f64(0x128), qTime)) break
            found = i
        }
        return if (found >= 0) found else 0
    }

    /** getStartingKey() @009644a0: the first drift point's key (index; -1 null or no points). */
    fun getStartingKey(): Int = if (pts.isEmpty()) -1 else pts[0].i32(0x2f0)

    /** charaForLiteralWord(bool& literal) @009590f8: (literal chara 0x104, true) when 0x102 is set,
     * else (holder 0x34's key nonbasic 0x9c, or chara 0x98 when that is 0; 0 without a key, false).
     * [Pair.second] = whether the bool out-param was written true (it is left alone otherwise). */
    fun charaForLiteralWord(env: TouchEnv): Pair<Int, Boolean> {
        if (body.u8(0x102) != 0) return body.i32(0x104) to true
        val k = env.key(body.i32(0x38)) ?: return 0 to false
        val nb = k.img.i32(0x9c)
        return (if (nb == 0) k.chara else nb) to false
    }

    /** markActualCurrentKey(Mookey*) @009755d8: holder 0x4 = k (0x8), onSpace 0x48 = k is the
     * space key; nothing when unchanged. Key glow / toucher counts are UI (not modelled). */
    fun markActualCurrentKey(env: TouchEnv, ki: Int) {
        if (body.i32(0x8) == ki) return
        body.setI32(0x8, ki)
        val k = env.key(ki)
        body.setU8(0x48, if (k != null && k.chara == 0x20) 1 else 0)
    }

    /** isDirectionalishSwipe(float k) @00965ad8: a straight swipe: |cur - start| >= 30 and every
     * drift point within min(len * k, unit) of the start-cur segment (withinLineWithTolerance,
     * same tolerance across and along). */
    fun isDirectionalishSwipe(env: TouchEnv, k: Float): Boolean {
        val st = pt(0x128); val cur = pt(0x130)
        val len = st.lengthFrom(cur)
        if (fcmplt(len, 30f)) return false
        var tol = fmul(len, k)
        val unit = env.model.mp.f32(Moopad.OFF_UNIT)
        if (fcmpgt(tol, unit)) tol = unit
        for (p in pts) if (!withinLineWithTolerance(ptOf(p, 0xdc), st, cur, tol, tol)) return false
        return true
    }

    /** getRecentDirection(float len) @00965844: mean direction of the newest [len] units of path
     * (walking the drift points back from the newest, the last segment cut with
     * Point2D::towards): sum(prev - cur) / covered, (0, 0) when covered <= 0.01; without points
     * normalise(start - cur). */
    fun getRecentDirection(len: Float): Point2D {
        if (pts.isEmpty()) return (pt(0x128) - pt(0x130)).normalise()
        var acc = 0f
        var sum = Point2D(0f, 0f)
        var prev = ptOf(pts.last(), 0xdc)
        for (i in pts.size - 2 downTo 0) {
            val rem = fsub(len, acc)
            if (fcmplt(rem, 0f)) break
            val p = ptOf(pts[i], 0xdc)
            var seg = prev.lengthFrom(p)
            val cut = fcmple(rem, seg) || fcmpun(rem, seg)
            val c = if (cut) { seg = rem; towards(prev, p, rem) } else p
            sum = Point2D(fadd(sum.x, fsub(prev.x, c.x)), fadd(sum.y, fsub(prev.y, c.y)))
            prev = p
            acc = fadd(acc, seg)
            if (cut) break
        }
        if (fcmple(acc, f(0x3c23d70a)) || fcmpun(acc, f(0x3c23d70a))) return Point2D(0f, 0f)
        return sum * fdiv(1f, acc)
    }

    /** isSimplishSlideOrTapTowards(char c) @00964ad0: a (simplish) tap or one-segment slide whose
     * first point's key, or second point's when there are exactly two, types [c]. */
    fun isSimplishSlideOrTapTowards(env: TouchEnv, c: Int): Boolean {
        val n = pts.size
        if (body.u8(0xb5) != 0) {
            if (n == 0 || n > 2) return false
            if (n == 2 && fcmpgt(pts[0].f32(0x36c), f(0x3f59999a))) return false
        }
        if (n == 0) return false
        val k0 = env.key(pts[0].i32(0x2f0))
        if (k0 != null && k0.chara == c) return true
        if (n == 2) {
            val k1 = env.key(pts[1].i32(0x2f0)) ?: return false
            return k1.chara == c
        }
        return false
    }

    /** isSimpleUnslideableTap() @00965ff0: inactive touch; or all drift points on one key that is
     * not a rechooser and is a plain key (name not "<" with chara 0, and byte 0xd49 clear); with
     * no points or no key, the first point barely moved (|0xdc - 0xd4| < roenScale * 40). */
    fun isSimpleUnslideableTap(env: TouchEnv): Boolean {
        if (body.u8(0xb5) == 0) return true
        var key0 = -1
        if (pts.isNotEmpty()) {
            for ((i, p) in pts.withIndex()) {
                if (i == 0) key0 = p.i32(0x2f0) else if (p.i32(0x2f0) != key0) return false
            }
            val k = env.key(key0)
            if (k != null) {
                if (k.isRechooser()) return false
                if (k.name == "<" && k.chara == 0) return false
                return k.img.u8(0xd49) == 0
            }
        }
        val p0 = pts.firstOrNull() ?: throw UnsupportedOperationException("isSimpleUnslideableTap with no points reads before the vector")
        return fcmplt(ptOf(p0, 0xdc).lengthFrom(ptOf(p0, 0xd4)), fmul(env.roenScale, 40f))
    }

    /** isProbablyProtractedSpaceTap() @00964e2c: a short (<= 0.4 s), unbumped (0xbc <= 0) touch
     * whose CK points include the space key, with at most one turning drift point (|angle| > 45,
     * no slide flags) and that one inside the spacebar, a vertical spread of the drift points
     * (0xd4.y and 0xdc.y) within 1.4 units, and its lowest y below spacebar.y0 + keyHeight *
     * PROTRACT(held). */
    fun isProbablyProtractedSpaceTap(env: TouchEnv): Boolean {
        val held = dsub(body.f64(0x110), body.f64(0xf8))
        if (dcmpgt(held, d(0x3fd99999a0000000L))) return false
        if (body.i32(0xbc) > 0) return false
        if (ck.none { env.key(it.i32(0x2f0))?.chara == 0x20 }) return false
        val space = env.model.board.named[0xfac]!!
        if (pts.isNotEmpty()) {
            var cnt = 0
            for (p in pts) {
                if (p.u8(0x225) and 0x18 != 0) continue
                val a = absf(p.f32(0x300))
                if (fcmple(a, 45f) || fcmpun(a, 45f)) continue
                cnt += if (space.containsWithExtra(ptOf(p, 0xdc), 0, 0, 0, 0)) 1 else 2
            }
            if (cnt > 1) return false
        }
        var lo = 999f; var hi = 0f
        for (p in pts) {
            val y1 = p.f32(0xd8); val y2 = p.f32(0xe0)
            if (!fcmplt(lo, y1)) lo = y1
            if (!fcmplt(lo, y2)) lo = y2
            var m = if (fcmplt(y1, hi)) hi else y1
            m = if (fcmplt(y2, m)) m else y2
            hi = m
        }
        val y0 = space.img.i32(0xcb0)
        val kh = env.mp.f32(0x1f4a0)
        val c = PROTRACT.get(d2f(held))
        if (fcmpgt(fsub(hi, lo), fmul(env.model.mp.f32(Moopad.OFF_UNIT), f(0x3fb33333)))) return false
        return fcmpgt(hi, fadd(i2f(y0), fmul(kh, c)))
    }

    /** syncBottomSpacingness(bool off) @00964a14: spaceToucher@0x49 = !off && (on the space key:
     * a simple tap, or below the spacebar top + 7). The Moopad toucher count is UI. */
    fun syncBottomSpacingness(env: TouchEnv, off: Boolean) {
        var v = false
        val k = env.key(body.i32(0x14))
        if (body.u8(0xb5) != 0 && pts.isNotEmpty() && k != null && k.chara == 0x20) {
            v = isSimpleTap(env) || fcmplt(i2f(k.img.i32(0xcb0) + 7), body.f32(0x134))
        }
        val nv = if (v && !off) 1 else 0
        if (body.u8(0x49) != nv) body.setU8(0x49, nv)
    }

    /** finishedDecaying() @00969c84: released (0x100) and more than 0.44 s (0.13 s with the ultra
     * freeze debug flag, not modelled) since the last record, against the frame clock. */
    fun finishedDecaying(env: TouchEnv): Boolean =
        body.u8(0x100) != 0 && dcmpgt(dsub(env.clock2, body.f64(0x110)), d(0x3fdc28f5c0000000L))

    // --- two-thumb collisions --------------------------------------------------------------

    /** checkFingerCollisionDetachmentPointsFromAnotherTouch(Mootouch& o) @0096f5c0: this touch's
     * start point (a detached 0x200 point with a successor) gets flag 0x400 + syncScores when the
     * other thumb was near it (< 3 units) 0.02..0.15 s earlier and the start direction points
     * toward it (dot > 0.64), or when the other touch's last point lies along the start direction
     * (dot > 0.965) and ended < 0.1 s before this start. Returns whether it marked. */
    fun checkFingerCollisionDetachmentPointsFromAnotherTouch(env: TouchEnv, o: Mootouch): Boolean {
        if (o === this || pts.size < 2) return false
        val p0 = pts[0]
        if (p0.u8(0x225) and 2 == 0) return false
        val pos0 = ptOf(p0, 0xdc)
        val dirA = (pos0 - ptOf(pts[1], 0xdc)).normalise()
        val near = fmul(env.model.mp.f32(Moopad.OFF_UNIT), 3f)
        for (q in o.pts) {
            val v = ptOf(q, 0xdc) - pos0
            val l = v.length()
            if (fcmpge(l, near) || fcmpun(l, near)) continue
            val dt = dsub(p0.f64(0x128), q.f64(0x128))
            if (dcmpge(dt, d(0x3fc3333333333333L)) || dcmpun(dt, d(0x3fc3333333333333L))) continue
            if (dcmple(dt, d(0x3f947ae147ae147bL)) || dcmpun(dt, d(0x3f947ae147ae147bL))) continue
            val dd = f2d(Point2D.dot(dirA, v.normalise()))
            if (dcmple(dd, d(0x3fe47ae147ae147bL)) || dcmpun(dd, d(0x3fe47ae147ae147bL))) continue
            p0.setU16(0x224, p0.u16(0x224) or 0x400); TouchPoint.syncScores(p0)
            return true
        }
        val ql = o.pts.lastOrNull() ?: throw UnsupportedOperationException("other touch without points: the binary reads before its vector")
        val w = (ptOf(ql, 0xdc) - pos0).normalise()
        val dd = f2d(Point2D.dot(w, dirA))
        if (dcmple(dd, d(0x3feee147ae147ae1L)) || dcmpun(dd, d(0x3feee147ae147ae1L))) return false
        val dt = d2f(dsub(p0.f64(0x128), ql.f64(0x138)))
        if (fcmplt(dt, 0f) || fcmpun(dt, 0f)) return false
        if (dcmpge(f2d(dt), d(0x3fb999999999999aL)) || dcmpun(f2d(dt), d(0x3fb999999999999aL))) return false
        p0.setU16(0x224, p0.u16(0x224) or 0x400); TouchPoint.syncScores(p0)
        return true
    }

    /** checkLastTouchFingerCollisionAgainst(Point2D const& p, double t) @0096fa78: this touch's
     * newest point, if a detached (0x200, not 0x400) end, gets 0x400 + syncScores when p (a new
     * touch's first position) continues its last segment within 45 degrees and t (the new
     * touch's down time) is 0..0.24 s after this touch's last record. */
    fun checkLastTouchFingerCollisionAgainst(p: Point2D, t: Double): Boolean {
        val n = pts.size
        if (n < 2) return false
        val last = pts[n - 1]
        if (last.u16(0x224) and 0x600 != 0x200) return false
        val a = p - ptOf(last, 0xdc)
        val b = ptOf(last, 0xdc) - ptOf(pts[n - 2], 0xdc)
        if (fcmpgt(absf(b.degreesVs(a)), 45f)) return false
        val dt = dsub(t, body.f64(0x110))
        if (dcmplt(dt, 0.0)) return false
        if (dcmpgt(dt, d(0x3fceb851eb851eb8L))) return false
        last.setU16(0x224, last.u16(0x224) or 0x400); TouchPoint.syncScores(last)
        return true
    }

    /** checkLastTouchFingerCollisionDetachmentPointsToANewTouch(Mootouch& n) @0096f9a4:
     * checkLastTouchFingerCollisionAgainst(n's first point's 0xd4 position, n's down time 0xf8). */
    fun checkLastTouchFingerCollisionDetachmentPointsToANewTouch(o: Mootouch): Boolean {
        if (o === this || pts.size < 2 || o.pts.isEmpty()) return false
        if (pts.last().u16(0x224) and 0x600 != 0x200) return false
        return checkLastTouchFingerCollisionAgainst(ptOf(o.pts[0], 0xd4), o.body.f64(0xf8))
    }

    // --- update -------------------------------------------------------------------------

    private fun c3(off: Int) = floatArrayOf(body.f32(off), body.f32(off + 4), body.f32(off + 8))

    /** Coord3D::lengthFrom(Coord3D const&) @00d74e8c: sqrtf(dx*dx + dy*dy + dz*dz), a - b. */
    private fun len3(a: FloatArray, b: FloatArray): Float {
        var s = fmul(fsub(a[0], b[0]), fsub(a[0], b[0]))
        s = fadd(s, fmul(fsub(a[1], b[1]), fsub(a[1], b[1])))
        s = fadd(s, fmul(fsub(a[2], b[2]), fsub(a[2], b[2])))
        return LibM.sqrtf(s)
    }

    /** Chasing<Coord3D>::chaseByFraction(float frac, float dt) @0096f0b0 on {cur, prev, mid,
     * target} at [off]: prev = cur; w = 1 - powf(1 - frac, dt); cur += (mid - cur) * w;
     * mid += (target - mid) * w. */
    private fun chase3(off: Int, frac: Float, dt: Float) {
        val k = LibM.powf(fsub(1f, frac), dt)
        for (i in 0 until 3) body.setI32(off + 0xc + 4 * i, body.i32(off + 4 * i))
        val w = fsub(1f, k)
        for (i in 0 until 3) {
            val c = body.f32(off + 4 * i); val m = body.f32(off + 0x18 + 4 * i)
            body.setF32(off + 4 * i, fadd(c, fmul(fsub(m, c), w)))
        }
        for (i in 0 until 3) {
            val m = body.f32(off + 0x18 + 4 * i); val t = body.f32(off + 0x24 + 4 * i)
            body.setF32(off + 0x18 + 4 * i, fadd(m, fmul(fsub(t, m), w)))
        }
    }

    /** Two-stage point smoothing of update(): a' = a + (b - a) * w, b' = b + (c - b) * w. */
    private fun smooth(a: Int, b: Int, c: Int, w: Float) {
        val pa = pt(a)
        setPt(a, pa + (pt(b) - pa) * w)
        val pb = pt(b)
        setPt(b, pb + (pt(c) - pb) * w)
    }

    /** updateTwerkFocused() @0096f2c8: the twerk-point list at 0x88 (TWERK elements). */
    fun updateTwerkFocused(env: TouchEnv) {
        val tw = body.seq(0x88)
        val pos = pt(0x70)
        if (tw.isNotEmpty()) {
            val last = MemImage.decode(tw.last())
            if (last.u8(0x20) == 0) {
                if (fcmple(last.f32(8), 1f) || fcmpun(last.f32(8), 1f)) return
                val d = Point2D(last.f32(0), last.f32(4)).lengthFrom(pos)
                if (fcmple(d, 15f) || fcmpun(d, 15f)) return
                last.setU8(0x20, 1)
                last.setF64(0x18, env.now)
                tw[tw.size - 1] = twerkJson(last)
            }
        }
        val e = MemImage("", 0x28)
        e.load(0, ByteArray(0x28))
        e.setF32(0, pos.x); e.setF32(4, pos.y)
        e.setF64(0x10, env.now)
        val a = body.f32(0x9c)
        body.setF32(0xa0, fadd(a, body.f32(0xa0)))
        e.setF32(8, fmul(a, 0.5f))
        body.setI32(0x9c, 0)
        tw.add(twerkJson(e))
    }

    private fun twerkJson(e: MemImage): nintype.engine.vectors.JsonValue {
        fun l(t: String, off: Int, w: Int) = nintype.engine.vectors.Leaf(t, e.readLE(off, w)).toJson()
        return nintype.engine.vectors.Values.struct("@0x0:x" to l("f32", 0, 4), "@0x4:y" to l("f32", 4, 4),
            "@0x8:strength" to l("f32", 8, 4), "@0x10:tStart" to l("f64", 0x10, 8), "@0x18:tEnd" to l("f64", 0x18, 8),
            "@0x20:ended" to l("u8", 0x20, 1), "@0x21" to l("u8", 0x21, 1))
    }

    /** update() @0096cf34: the per-frame step of a held touch: re-records the current position,
     * smooths the history points, ages the newest drift point (displacement, pause, slowness),
     * decays the twerk energy and runs the twerk focus. */
    fun update(env: TouchEnv) {
        val dt = d2f(env.frameDt)
        val hi = body.i32(0x26c)
        if (hi and 0x100000 == 0) {
            body.setF32(0x270, d2f(dsub(env.clock2, body.f64(0xf8))))
            var mark = true
            if (pts.size < 2) {
                val d = pt(0x130).lengthFrom(pt(0x128))
                val t = fmul(env.mp.f32(0x1f4a4), f(0x3e99999a))
                if (fcmple(d, t) || fcmpun(d, t)) mark = false
            }
            if (mark) body.setI32(0x26c, hi or 0x100000)
        }
        record(env, pt(0x130), true)
        val s15c = pt(0x15c)
        val s17c = c3(0x17c)
        setPt(0x174, pt(0x130))
        setPt(0x1a0, pt(0x130)); body.setI32(0x1a8, 0)
        setPt(0x154, pt(0x130))
        val w1 = fsub(1f, LibM.powf(f(0x3ea8f5c2), dt))
        setPt(0x164, pt(0x15c))
        smooth(0x15c, 0x16c, 0x174, w1)
        chase3(0x17c, f(0x3f2b851f), dt)
        val w2 = fsub(1f, LibM.powf(f(0x3f4ccccd), dt))
        setPt(0x144, pt(0x13c))
        smooth(0x13c, 0x14c, 0x154, w2)
        body.setF32(0x1ac, s15c.lengthFrom(pt(0x15c)))
        val l3 = len3(s17c, c3(0x17c))
        if (body.u8(0x94) != 0) {
            val h = body.i32(0x26c)
            if (h and 0x200 != 0) twerkEnergy()
            else if (h and 0x800 != 0) {
                val a4 = body.f32(0xa4)
                if (!(fcmple(a4, 2.5f) || fcmpun(a4, 2.5f))) {
                    body.setI32(0x26c, h or 0x1000)
                    if (pts.isNotEmpty()) pts.last().setU16(0x224, pts.last().u16(0x224) or 4)
                }
            }
        }
        val old1b0 = body.f32(0x1b0)
        body.setF32(0x1b8, pt(0x13c).lengthFrom(pt(0x130)))
        chase(0x1b0, f(0x3f4ccccd), dt)
        body.setU8(0x1bc, if (fcmplt(body.f32(0x1b0), old1b0)) 1 else 0)
        if (pts.isNotEmpty()) {
            val last = pts.last()
            last.setF32(0x234, d2f(dadd(env.g3, f2d(last.f32(0x234)))))
            val t = fmul(env.mp.f32(0x1f4a4), f(0x3c449ba6))
            if (!(fcmpge(l3, t) || fcmpun(l3, t))) last.setF32(0x158, d2f(dadd(env.g3, f2d(last.f32(0x158)))))
            var r = fdiv(fsub(2.5f, body.f32(0x1ac)), 2.5f)
            r = if (fcmplt(r, 0f)) 0f else if (fcmpgt(r, 1f)) 1f else r
            val gt = fcmpgt(r, 0.25f)
            if ((if (gt) 1 else 0) != last.u8(0x170)) {
                last.setU8(0x170, if (gt) 1 else 0)
                if (!(fcmple(r, 0.25f) || fcmpun(r, 0.25f))) last.setF64(0x128, env.now)
            }
            last.setF32(0x238, d2f(dadd(nintype.engine.SoftFloat.dmul(f2d(r), env.g3), f2d(last.f32(0x238)))))
            updateLastAccuracy()
        }
        val e4 = d2f(env.g4)
        body.setF32(0x9c, fmul(body.f32(0x9c), LibM.powf(f(0x3f666666), e4)))
        body.setF32(0xa0, fmul(body.f32(0xa0), LibM.powf(f(0x3f59999a), d2f(env.g4))))
        if (env.mp.u8(0x2b584) != 1) return
        chase(0x7c, f(0x3eb33333), dt)
        chase3(0x4c, f(0x3e4ccccd), dt)
        val l = len3(c3(0x70), c3(0x4c))
        if (!(fcmpge(l, 16f) || fcmpun(l, 16f)) && !(fcmpge(body.f32(0x7c), 220f) || fcmpun(body.f32(0x7c), 220f)) &&
            !(fcmple(body.f32(0x9c), f(0x3e99999a)) || fcmpun(body.f32(0x9c), f(0x3e99999a)))) {
            updateTwerkFocused(env)
            return
        }
        val tw = body.seq(0x88)
        if (tw.isNotEmpty()) {
            val last = MemImage.decode(tw.last())
            if (last.u8(0x20) == 0) {
                last.setF64(0x18, env.now); last.setU8(0x20, 1)
                tw[tw.size - 1] = twerkJson(last)
            }
        }
    }

    /** determineTwerkerPoint(MooTouchTwerkPoint&) @009709ac on twerk element [ti] of 0x88: the
     * drift point closest in time to the twerk's start (0 inside [0x120, 0x148], else the gap;
     * strictly closer wins, and only points within 60 of the twerk position count) takes the
     * twerk when that gap is < 0.3 s: strength clamped to [0, 1] at 0x1a8, start/stop at
     * 0x190/0x198, 0x1ac = 1; the element's 0x21 = 1. */
    fun determineTwerkerPoint(ti: Int) {
        if (pts.isEmpty()) return
        val tw = body.seq(0x88)
        val t = MemImage.decode(tw[ti])
        val ts = t.f64(0x10)
        val tpos = Point2D(t.f32(0), t.f32(4))
        var best = f(0x461c3c00)
        var bestP: MemImage? = null
        for (p in pts) {
            val d: Float = if (!(dcmpge(ts, p.f64(0x120)) || dcmpun(ts, p.f64(0x120)))) absGap(ts, p.f64(0x120))
            else if (!(dcmple(ts, p.f64(0x148)) || dcmpun(ts, p.f64(0x148)))) absGap(ts, p.f64(0x148))
            else 0f
            if (!(fcmpge(d, best) || fcmpun(d, best))) {
                val l = ptOf(p, 0xdc).lengthFrom(tpos)
                if (!(fcmpge(l, 60f) || fcmpun(l, 60f))) { best = d; bestP = p }
            }
        }
        val b = f2d(best)
        val lim = d(0x3fd3333333333333L)
        val bp = bestP ?: return
        if (dcmpge(b, lim) || dcmpun(b, lim)) return
        t.setU8(0x21, 1)
        val s = t.f32(8)
        bp.setF32(0x1a8, if (fcmplt(s, 0f)) 0f else if (fcmpgt(s, 1f)) 1f else s)
        bp.setF64(0x190, t.f64(0x10))
        bp.setF64(0x198, t.f64(0x18))
        bp.setU8(0x1ac, 1)
        tw[ti] = twerkJson(t)
    }

    private fun absGap(a: Double, b: Double): Float = d2f(Bits.bitsToDouble(Bits.doubleToBits(dsub(a, b)) and Long.MAX_VALUE))

    /** insertArtificialVisitationPointsAndGetRidOfUnimportantPoints() @009720cc: splits the
     * drift points (in order) into the important ones (0x34d set and not twerk-consumed 0x1ad),
     * kept at 0x244, and the rest, moved to 0x25c (cleared first); the newest kept point
     * becomes an end point (flag 0x1000).
     *
     * Point links (0x2c..0x38) are copied as raw pointers by the binary: they still address
     * slots of the 0x244 buffer, which is refilled in place. As serialized (touch_ext.py) a
     * link index k therefore stays k in the kept points while k is below the new count, and
     * becomes -2 (an address outside its vector) otherwise and in the moved points. */
    fun insertArtificialVisitationPointsAndGetRidOfUnimportantPoints() {
        val copy = pts.toList()
        pts.clear()
        val third = vecs[2]
        third.clear()
        for (p in copy) {
            if (p.u8(0x34d) == 0 || p.u8(0x1ad) != 0) third.add(p) else pts.add(p)
        }
        val n = pts.size
        for (p in pts) for (o in MootouchCodec.TP_LINKS) { val k = p.i32(o); if (k >= n) p.setI32(o, -2) }
        for (p in third) for (o in MootouchCodec.TP_LINKS) { if (p.i32(o) >= 0) p.setI32(o, -2) }
        if (n > 0) pts.last().setU16(0x224, pts.last().u16(0x224) or 0x1000)
    }

    /** finalizeSlownessDetection() @0096fce8, on release. Point flags: 0x40 slowdown (one per
     * slowdown episode 0x226: the one with the lowest speed ratio 0x228 survives, later wins
     * ties), 0x10 acceleration, 0x20 turn-ish slowdown, 0x8 = any of them.
     *  - 0x10 is dropped when the point is within 0.7 units of the previous one and either
     *    came < 0.05 s after the last slowdown, or is within 0.6 units of a previous point that
     *    paused > 0.11 s (and is no slide point) while itself pausing < 20% of that.
     *  - 0x20 (within 1.6 units of path after the last slowdown) is dropped when < 0.05 s or
     *    < 0.6 units after it, or when SLOW_DOT(dir . slowDir) + SLOW_DIST(path / unit) +
     *    SLOW_TIME(t) > 1.9. */
    fun finalizeSlownessDetection(env: TouchEnv) {
        val keep = HashMap<Int, MemImage>()
        for (p in pts) {
            if (p.u16(0x224) and 0x40 == 0) continue
            val k = p.i8(0x226)
            val o = keep[k]
            if (o != null) {
                if (!(fcmpge(o.f32(0x228), p.f32(0x228)) || fcmpun(o.f32(0x228), p.f32(0x228)))) {
                    p.setU16(0x224, p.u16(0x224) and 0xffbf); continue
                }
                o.setU16(0x224, o.u16(0x224) and 0xffbf)
            }
            keep[k] = p
        }
        if (pts.isEmpty()) return
        val unit = env.model.mp.f32(Moopad.OFF_UNIT)
        val u16 = fmul(unit, f(0x3fcccccd)); val u06 = fmul(unit, f(0x3f19999a)); val u07 = fmul(unit, f(0x3f333333))
        var len = 0f
        var slowT = f(0xc479c000.toInt()); var slowLen = f(0xc479c000.toInt())
        var slowDir = Point2D(0f, 0f)
        var prev: MemImage? = null
        val t0 = body.f64(0xf8)
        for (p in pts) {
            var dir = Point2D(0f, 0f)
            var t = 0f
            var dPrev = 0f
            if (prev != null) {
                len = fadd(len, ptOf(p, 0xdc).lengthFrom(ptOf(prev, 0xdc)))
                dir = (ptOf(p, 0xdc) - ptOf(prev, 0xdc)).normalise()
                t = d2f(dsub(dsub(p.f64(0x148), t0), f2d(slowT)))
            }
            if (p.u8(0x224) and 0x40 != 0) {
                slowDir = dir
                slowT = d2f(dsub(p.f64(0x148), t0))
                slowLen = len
            }
            val since = fsub(len, slowLen)
            if (prev != null) dPrev = ptOf(prev, 0xdc).lengthFrom(ptOf(p, 0xdc))
            var fl = p.u16(0x224)
            if (fl and 0x10 != 0 && prev != null && !(fcmpge(dPrev, u07) || fcmpun(dPrev, u07))) {
                var clear = fcmplt(t, f(0x3d4ccccd))
                if (!clear && !(fcmpge(dPrev, u06) || fcmpun(dPrev, u06)) && prev.u8(0x225) and 0x18 == 0) {
                    val pp = prev.f32(0x158)
                    if (!(fcmple(pp, f(0x3de147ae)) || fcmpun(pp, f(0x3de147ae)))) {
                        val th = fmul(pp, f(0x3e4ccccd))
                        if (!(fcmpge(p.f32(0x158), th) || fcmpun(p.f32(0x158), th))) clear = true
                    }
                }
                if (clear) { fl = fl and 0xffef; p.setU16(0x224, fl) }
            }
            if (!(fcmpge(since, u16) || fcmpun(since, u16)) && fl and 0x20 != 0) {
                val clear = if (fcmplt(t, f(0x3d4ccccd)) || fcmplt(since, u06)) true else {
                    val a = SLOW_DOT.get(Point2D.dot(dir, slowDir))
                    val b = SLOW_DIST.get(fdiv(since, unit))
                    val c = SLOW_TIME.get(t)
                    val sum = fadd(fadd(a, b), c)
                    !(fcmple(sum, f(0x3ff33333)) || fcmpun(sum, f(0x3ff33333)))
                }
                if (clear) p.setU16(0x224, p.u16(0x224) and 0xffdf)
            }
            prev = p
        }
        for (p in pts) {
            val fl = p.u16(0x224)
            val b = if (fl and 0x30 == 0) (fl and 0x40) shr 6 else 1
            p.setU16(0x224, (fl and 0xfff7) or (b shl 3))
        }
    }

    /** syncDampeningState() @009723f0, on release: per consecutive drift-point pair (prev, cur)
     *  - the first pair: prev.0x35c = DAMP_A(unitDist / roenScale) * DAMP_B(cur.0x128 - prev.0x128);
     *  - pairs of non-visitation points (0x34c clear on both): the dampening factors
     *    prev.0x364 = cur.0x360 = powf(1 - DAMP_E(2 dt) * DAMP_F(dist / unit * k [* 0.5 detached]
     *    / roenScale), DAMP_D(dirs) or 1 at a start point) (halved toward 1 for an accel point) and
     *    prev.0x36c = cur.0x368 = powf(DAMP_G(k |pos delta in key units| / roenScale),
     *    DAMP_H(dt) [* DAMP_I(release - down)]); the bracketed terms and k = DAMP_C(release -
     *    down) only for a slowdown prev followed by an accel cur, which also caps dt at
     *    (release - down) - 0.04. dt = |cur.0x138 - prev.0x140|. cur.0x3d4/0x3dc/0x3e4 keep k
     *    and the two curve values, 0x3d8/0x3e0 = 1. */
    fun syncDampeningState(env: TouchEnv) {
        val unit = env.model.mp.f32(Moopad.OFF_UNIT)
        var first = false
        var prev: MemImage? = null
        for (cur in pts) {
            val p = prev
            if (p != null && !first) {
                val ud = Moopad.unitDist(ptOf(p, 0xdc), ptOf(cur, 0xdc), unit)
                val dt = d2f(dsub(cur.f64(0x128), p.f64(0x128)))
                p.setF32(0x35c, fmul(DAMP_A.get(fdiv(ud, env.roenScale)), DAMP_B.get(dt)))
                first = true
            }
            if (p != null && p.u8(0x34c) == 0 && cur.u8(0x34c) == 0) dampPair(env, p, cur, unit)
            prev = cur
        }
    }

    private fun dampPair(env: TouchEnv, p: MemImage, cur: MemImage, unit: Float) {
        val dist = ptOf(cur, 0xdc).lengthFrom(ptOf(p, 0xdc))
        val dirA = (ptOf(p, 0xfc) - ptOf(p, 0xdc)).normalise()
        val dirB = (ptOf(cur, 0xfc) - ptOf(cur, 0xdc)).normalise()
        val ratio = fdiv(dist, unit)
        var dt = Bits.bitsToDouble(Bits.doubleToBits(dsub(cur.f64(0x138), p.f64(0x140))) and Long.MAX_VALUE)
        val dotv = Point2D.dot(dirA, dirB)
        var k = 1f
        val held = dsub(body.f64(0x110), body.f64(0xf8))
        val pair = p.u8(0x225) and 8 != 0 && cur.u8(0x225) and 0x10 != 0
        if (pair) {
            val x = dadd(held, D_M004)
            if (dcmplt(x, dt)) dt = x
            k = DAMP_C.get(d2f(held))
            cur.setF32(0x3d4, k)
        }
        var e = DAMP_D.get(dotv)
        if (p.u16(0x224) and 0x800 != 0) e = 1f
        val ev = DAMP_E.get(d2f(dadd(dt, dt)))
        val y = fmul(fmul(ratio, k), if (p.u16(0x224) and 0x400 != 0) 0.5f else 1f)
        val fv = DAMP_F.get(fdiv(y, env.roenScale))
        cur.setU8(0x3d8, 1); cur.setF32(0x3dc, ev); cur.setU8(0x3e0, 1); cur.setF32(0x3e4, fv)
        var v = LibM.powf(fsub(1f, fmul(ev, fv)), e)
        if (cur.u8(0x224) and 0x10 != 0) v = fadd(fmul(v, 0.5f), 0.5f)
        p.setF32(0x364, v); cur.setF32(0x360, v)
        val d = ptOf(cur, 0xdc) - ptOf(p, 0xdc)
        val dx = d2f(nintype.engine.SoftFloat.dmul(ddiv(1.0, f2d(env.model.mp.f32(0x1f4a4))), f2d(d.x)))
        val dy = d2f(nintype.engine.SoftFloat.dmul(ddiv(1.0, f2d(env.model.mp.f32(0x1f4a0))), f2d(d.y)))
        val g = DAMP_G.get(fdiv(fmul(k, Point2D(dx, dy).length()), env.roenScale))
        var h = DAMP_H.get(d2f(dt))
        if (pair) h = fmul(h, DAMP_I.get(d2f(held)))
        val w = LibM.powf(g, h)
        p.setF32(0x36c, w); cur.setF32(0x368, w)
    }

    /** SaveableTouch recs at 0x274 (40-byte SaveableTouchPointRec: f64 t @0, Point2D pos @8,
     * u8 @0x14 skip flag), read-only here. */
    private fun recs(): List<MemImage> = body.seq(0x274).map { MemImage.decode(it) }

    /** calcDepartureVelocity() @00971e40: weighted (2, 4, 6, ...) mean velocity over the last six
     * recorded samples, using only steps longer than 1e-4 s that started < 0.1 s before the
     * release (0x110): 0x1c0 = sum(w * dpos / dt) / (sum(w) * 50). */
    fun calcDepartureVelocity() {
        val r = recs()
        val n = r.size
        var v = Point2D(0f, 0f)
        var ws = 0
        var w = 2
        for (i in maxOf(n - 6, 0) until n) {
            if (i != 0) {
                val a = r[i - 1]; val b = r[i]
                val dt = dsub(b.f64(0x0), a.f64(0x0))
                val lim = d(0x3f1a36e2e0000000L) // (double) 1e-4f
                if (!(dcmple(dt, lim) || dcmpun(dt, lim))) {
                    val since = dsub(body.f64(0x110), a.f64(0x0))
                    val l2 = d(0x3fb99999a0000000L)
                    if (!(dcmpge(since, l2) || dcmpun(since, l2))) {
                        val dp = ptOf(b, 8) - ptOf(a, 8)
                        v = v + dp * d2f(ddiv(i2d(w), dt))
                        ws += w
                    }
                }
            }
            w += 2
        }
        if (ws == 0) return
        val k = d2f(ddiv(1.0, nintype.engine.SoftFloat.dmul(f2d(i2f(ws)), 50.0)))
        setPt(0x1c0, v * k)
    }

    private fun i2d(i: Int): Double = i.toDouble()

    /** The release-time finish of a point vector's newest point (drift or CK). */
    private fun finishLast(env: TouchEnv, v: MutableList<MemImage>) {
        if (v.isEmpty()) return
        val last = v.last()
        setPtOf(last, 0xe4, pt(0x130)); setPtOf(last, 0xdc, pt(0x130))
        setPtOf(last, 0xf4, pt(0x13c))
        last.setF64(0x148, env.now)
        if (!(dcmpge(last.f64(0x128), 0.0) || dcmpun(last.f64(0x128), 0.0))) last.setF64(0x128, last.f64(0x148))
        TouchPoint.syncScores(last)
        TouchPoint.syncLeadPoints(last)
        if (last.u8(0x225) and 0x20 != 0) Traversal.genTraversal(env.model.mp, last, env.model.board, env.key(last.i32(0x2f0)))
    }

    /** markReleased() @00970bd0: the finger lifted. Finishes the newest points, runs the
     * slowness detection, matches important points to the raw recorded samples (exact touch
     * times 0x128/0x138/0x140, sample indexes 0x174/0x178/0x17c/0x184), assigns twerks,
     * drops unimportant points (insertArtificialVisitation...), then the per-point release
     * states, dampening, roughness, traversals (scores normalised to 6) and the CK points'
     * time shift. Mookey/Moopad UI state is not touched here. */
    fun markReleased(env: TouchEnv) {
        val now = env.now
        val tw = body.seq(0x88)
        if (tw.isNotEmpty()) {
            val l = MemImage.decode(tw.last())
            if (l.u8(0x20) == 0) { l.setF64(0x18, now); l.setU8(0x20, 1); tw[tw.size - 1] = twerkJson(l) }
        }
        body.setF64(0x110, now); body.setF64(0x118, now)
        body.setU8(0x100, 1)
        finalizeSlownessDetection(env)
        finishLast(env, pts)
        for (p in pts) TouchPoint.syncScores(p)
        finishLast(env, ck)
        for (p in ck) TouchPoint.syncScores(p)
        matchSamples()
        for (i in tw.indices) determineTwerkerPoint(i)
        consumeTwerks()
        refineTimes()
        calcDepartureVelocity()
        insertArtificialVisitationPointsAndGetRidOfUnimportantPoints()
        for (p in pts) { p.setI32(0x1a8, 0); p.setU8(0x1ac, 0) }
        for (i in body.seq(0x88).indices) determineTwerkerPoint(i)
        val unit = env.model.mp.f32(Moopad.OFF_UNIT)
        var len = 0f; var ud = 0f
        var prev: MemImage? = null
        for (p in pts) {
            if (prev != null) {
                len = fadd(len, ptOf(p, 0xdc).lengthFrom(ptOf(prev, 0xdc)))
                val u = Moopad.unitDist(ptOf(p, 0xdc), ptOf(prev, 0xdc), unit)
                p.setF32(0x150, len)
                ud = fadd(ud, u)
                p.setF32(0x154, ud)
            }
            TouchPoint.syncStatesOnRelease(p, prev, unit)
            prev = p
        }
        body.setF32(0x120, ud)
        syncDampeningState(env)
        prev = null
        for (p in pts) { TouchPoint.syncRoughness(p, prev); prev = p }
        for (p in pts) {
            Traversal.genTraversal(env.model.mp, p, env.model.board, env.key(p.i32(0x2f0)))
            TouchPoint.normalizeTravkeyScores(p, 6f)
        }
        len = 0f; ud = 0f; prev = null
        for (p in ck) {
            p.setF64(0x130, p.f64(0x128))
            p.setF64(0x128, p.f64(0x120))
            if (prev != null) {
                len = fadd(len, ptOf(p, 0xdc).lengthFrom(ptOf(prev, 0xdc)))
                val u = Moopad.unitDist(ptOf(p, 0xdc), ptOf(prev, 0xdc), unit)
                p.setF32(0x150, len)
                ud = fadd(ud, u)
                p.setF32(0x154, ud)
            }
            prev = p
        }
        body.setF32(0x124, ud)
        val h = body.i32(0x26c)
        val bit = if (h and 0x400 != 0 && fcmpgt(body.f32(0xa8), 1.5f)) 0x2000 else 0
        body.setI32(0x26c, (h and 0x2000.inv()) or bit)
    }

    /** markReleased's match of important drift points to recorded samples (one shared cursor). */
    private fun matchSamples() {
        val r = recs()
        val n = r.size
        var idx = 0
        var have = false
        var prevPos = Point2D(0f, 0f)
        for (p in pts) {
            if (p.u8(0x34d) == 0) continue
            var limit = 28f
            if (have) {
                val l = fmul(ptOf(p, 0xdc).lengthFrom(prevPos), 0.5f)
                limit = if (fcmplt(l, 28f)) l else 28f
            }
            if (idx < n) {
                var inRun = false
                var last = 0f
                while (idx < n) {
                    var dCur = last
                    val rec = r[idx]
                    if (rec.u8(0x14) == 0) {
                        dCur = ptOf(rec, 8).lengthFrom(ptOf(p, 0xdc))
                        if (inRun) {
                            p.setI32(0x17c, idx)
                            if (fcmpgt(dCur, last)) break
                            if (fcmplt(dCur, 5f)) break
                        } else if (!(fcmpge(dCur, limit) || fcmpun(dCur, limit)) &&
                            run {
                                if (p.i32(0x174) == -1) p.setI32(0x174, idx)
                                !(have && fcmplt(prevPos.lengthFrom(ptOf(rec, 8)), dCur))
                            }) {
                            p.setF64(0x130, p.f64(0x128))
                            p.setF64(0x128, rec.f64(0))
                            p.setI32(0x178, idx); p.setI32(0x17c, idx)
                            inRun = true
                        } else inRun = false
                    }
                    idx++
                    last = dCur
                }
            }
            prevPos = ptOf(p, 0xdc)
            have = true
        }
    }

    /** markReleased's inline twerkConsume over following points. */
    private fun consumeTwerks() {
        for ((i, p) in pts.withIndex()) {
            if (p.u8(0x1ad) != 0) continue
            var j = i + 1
            while (j < pts.size) {
                val a = p.f32(0x1a8)
                if (fcmple(a, 0.4f) || fcmpun(a, 0.4f)) break
                val q = pts[j]
                if (fcmpgt(ptOf(q, 0xdc).lengthFrom(ptOf(p, 0xdc)), 32f)) break
                if (dcmpgt(q.f64(0x128), p.f64(0x198))) break
                q.setU8(0x1ad, 1)
                j++
            }
        }
    }

    /** markReleased's refinement of important points' times from the matched sample range. */
    private fun refineTimes() {
        val r = recs()
        val n = r.size
        for (p in pts) {
            if (p.u8(0x34d) == 0) continue
            val e = p.i32(0x17c)
            val s = p.i32(0x174)
            val hi = if (n - 1 < e + 15) n - 1 else e + 15
            val lo = if (s < 0) 0 else s
            if (lo <= hi) {
                var k = lo - 1
                var ri = lo
                var best = f(0x47c34f80)
                do {
                    val rec = r[ri]
                    val dd = ptOf(rec, 8).lengthFrom(ptOf(p, 0xdc))
                    k++
                    if (k <= e && !(fcmpge(dd, best) || fcmpun(dd, best))) {
                        p.setI32(0x184, k)
                        p.setF64(0x140, rec.f64(0)); p.setF64(0x138, rec.f64(0))
                        best = dd
                    }
                    val th = fadd(best, 2.5f)
                    if (!(fcmpge(dd, th) || fcmpun(dd, th))) p.setF64(0x140, rec.f64(0))
                    ri++
                } while (k < hi)
            }
            val x = dadd(p.f64(0x138), D_M004)
            if (dcmplt(p.f64(0x128), x)) p.setF64(0x128, x)
        }
    }

    /** takeForceTouchUpdate(TouchInfo const&) @00962ef8. Twerk energy: 0x98 = translateTwerkForce
     * (force), a rise adds to 0x9c and to the open twerk element's strength. With a force
     * reading: 0x94 = 1, 0xa4 = translateForceval(force) and 0xa8 = its max (all three zeroed
     * while 0x26c bit 0x10000 holds, which a reading < 0.4 clears); the newest drift and CK
     * points get MooTouchPoint::takeForceTouchUpdate. Moopad scalars from env.mp. */
    fun takeForceTouchUpdate(env: TouchEnv, ti: ForceInfo) {
        val mp = env.mp
        val old = body.f32(0x98)
        val v = nintype.engine.geometry.MoopadGeometry.translateTwerkForce(mp, ti.force)
        body.setF32(0x98, v)
        val dlt = fsub(v, old)
        if (!(fcmple(dlt, 0f) || fcmpun(dlt, 0f))) {
            body.setF32(0x9c, fadd(dlt, body.f32(0x9c)))
            val tw = body.seq(0x88)
            if (tw.isNotEmpty()) {
                val l = MemImage.decode(tw.last())
                if (l.u8(0x20) == 0) { l.setF32(8, fadd(dlt, l.f32(8))); tw[tw.size - 1] = twerkJson(l) }
            }
        }
        if (ti.hasForce == 0) return
        body.setU8(0x94, 1)
        val fv = nintype.engine.geometry.MoopadGeometry.translateForceval(mp, ti.force)
        var hi = body.i32(0x26c)
        if (!(fcmpge(fv, 0.4f) || fcmpun(fv, 0.4f)) && hi and 0x10000 != 0) { hi = hi and 0x10000.inv(); body.setI32(0x26c, hi) }
        if (hi and 0x10000 != 0) { body.setI32(0xa4, 0); body.setI32(0x98, 0); body.setI32(0xa8, 0) }
        else {
            body.setF32(0xa4, fv)
            if (fcmplt(body.f32(0xa8), fv)) body.setF32(0xa8, fv)
        }
        for (vv in listOf(pts, ck)) if (vv.isNotEmpty()) TouchPoint.takeForceTouchUpdate(vv.last(), ti, mp)
    }

    /** syncForceKeyEligibility() @00962cc0: 0x26c bits from the Moopad's force settings: 0x200
     * when 0x268 has neither 0x10 nor 0x20 or Xnl mp+0x28a60 is on; else 0x800 if Xnl
     * mp+0x28ac8 is on; 0x400 if Xnl mp+0x28a94 is on (Xnl value byte at +0xc). */
    fun syncForceKeyEligibility(env: TouchEnv) {
        val mp = env.mp
        if (body.i32(0x268) and 0x30 == 0 || mp.u8(0x28a6c) == 1) body.setI32(0x26c, body.i32(0x26c) or 0x200)
        if (body.i32(0x26c) and 0x200 == 0 && mp.u8(0x28ad4) == 1) body.setI32(0x26c, body.i32(0x26c) or 0x800)
        if (mp.u8(0x28aa0) == 1) body.setI32(0x26c, body.i32(0x26c) or 0x400)
    }

    /** The 0x200-mode energy integrator of update() (0xac, 0xb4). */
    private fun twerkEnergy() {
        val a1 = body.f32(0xac)
        var fv = fadd(body.f32(0xa4), -0.5f)
        if (fcmplt(fv, 0f)) fv = 0f
        if (!(fcmple(a1, 30f) || fcmpun(a1, 30f)) && fcmplt(fv, 0.5f)) fv = 0.5f
        val g = fdiv(fsub(13f, body.f32(0x1b0)), 13f)
        val sl: Float
        var toLow: Boolean // true: go to the `fv < 0.5 -> 0.5` check, false: the `fv <= 0.01` check
        if (fcmplt(g, -1f)) { sl = -1f; toLow = true }
        else if (fcmpgt(g, 1f)) { sl = 1f; toLow = false }
        else if (fcmpge(g, 0f) || fcmpun(g, 0f)) { sl = g; toLow = false }
        else { sl = g; toLow = true }
        var r6: Float
        if (toLow && fcmplt(fv, 0.5f)) r6 = 0.5f
        else {
            if (fcmple(fv, f(0x3c23d70a)) || fcmpun(fv, f(0x3c23d70a))) return
            r6 = fv
        }
        val v = fadd(a1, fmul(fmul(r6, sl), 4f))
        body.setF32(0xac, v)
        if (!(fcmple(v, 100f) || fcmpun(v, 100f))) body.setU8(0xb4, 1)
    }

    // --- record -------------------------------------------------------------------------

    /** record(Point2D const&, bool) @0096d9f0: one position sample (board space) of this touch.
     * [rerecord] is update()'s per-frame re-record of the current position. */
    fun record(env: TouchEnv, p: Point2D, rerecord: Boolean) {
        recordTwerkTarget(p)
        var accel = false
        if (body.u8(0x1e8) == 0) {
            setPt(0x1d4, p); body.setF64(0x1e0, env.now); body.setU8(0x1e8, 1)
        } else {
            val md = p.manhattanDistance(pt(0x1d4))
            if (!(fcmpge(body.f32(0xa8), f(0x3ecccccd)) || fcmpun(body.f32(0xa8), f(0x3ecccccd)))) body.setF32(0xb0, fadd(md, body.f32(0xb0)))
            val el = d2f(dsub(env.now, body.f64(0x1e0)))
            if (!(fcmple(el, f(0x3a83126f)) || fcmpun(el, f(0x3a83126f)))) {
                accel = motion(env, p, md, el)
                setPt(0x1d4, p); body.setF64(0x1e0, env.now)
            }
        }
        val changed = updateCurrentKey(env, p)
        body.setF64(0x110, env.now)
        if (!rerecord) body.setF64(0x118, env.now)
        body.setF64(0x1c8, env.now)
        body.setU8(0x1d0, 1)
        setPt(0x130, p)
        val row = f2iz(Geometry.floorf(fdiv(i2f(f2iz(p.y)), env.mp.f32(0x1f4a0))))
        markRow(row)
        val keyW = env.mp.f32(0x1f4a4)
        if (body.u8(0xb5) == 0 || body.u8(0xb6) != 0) {
            fillHistory(p, keyW)
            if (body.u8(0xb6) != 0) throw UnsupportedOperationException("Mootouch::recordOnStockmode (stock keyboard mode) not ported")
            return
        }
        if (ck.isNotEmpty()) {
            val lk = ck.last()
            val k = env.key(lk.i32(0x2f0))
            if (k != null) {
                val kc = Point2D(k.img.f32(0xd4c), k.img.f32(0xd50))
                val d1 = pt(0x130).lengthFrom(kc)
                val d2 = ptOf(lk, 0xdc).lengthFrom(kc)
                if (!(fcmpge(d1, d2) || fcmpun(d1, d2))) {
                    setPtOf(lk, 0xdc, pt(0x130))
                    for (o in intArrayOf(0x138, 0x140, 0x128)) lk.setF64(o, env.now)
                }
            }
        }
        if (changed) pushPointEx(env, true)
        if (pts.isEmpty()) {
            val ni = pushPointEx(env, false)
            fillHistory(p, keyW)
            body.setI32(0x19c, 0); body.setI32(0x1a8, 0)
            val np = pts[ni]
            np.setU16(0x224, np.u16(0x224) or 0x1800)
            return
        }
        if (changed || body.u8(0x48) != 0) {
            var sp = 0
            val k = env.key(body.i32(0x14))
            if (body.u8(0xb5) != 0 && k != null && k.chara == 0x20) {
                sp = 1
                if (!isSimpleTap(env)) sp = if (fcmplt(i2f(k.img.i32(0xcb0) + 7), body.f32(0x134))) 1 else 0
            }
            if (body.u8(0x49) != sp) body.setU8(0x49, sp) // Moopad::shiftSpaceToucherCount: UI, not modelled
        }
        val scale = env.roenScale
        if (pts.size == 1) {
            if (fcmplt(ptOf(pts[0], 0xd4).lengthFrom(p), fmul(scale, 14f))) return
            val ni = pushPointEx(env, false)
            MooTouchPoint.determineDirection(pts[ni], pts[0])
            return
        }
        tail(env, p, accel, scale)
    }

    /** The speed analysis of record (pixel frames, chasers, acceleration/slowdown/stop marks).
     * Returns the "acceleration on the newest point" flag. */
    private fun motion(env: TouchEnv, p: Point2D, md: Float, el: Float): Boolean {
        pushPixMovementFrame(el, f2iz(fdiv(md, el)))
        // speed over the newest 0.05 s
        var speed = 0f
        if (pix.isNotEmpty()) {
            val (t, dsum) = recentFrames(D_005)
            if (!dcmplt(t, D_0001)) speed = d2f(ddiv(dsum, if (dcmplt(t, D_005)) D_005 else t))
        }
        if (ck.isNotEmpty()) {
            val c = ck.last()
            if (fcmplt(speed, c.f32(0x22c))) c.setF32(0x22c, speed) // `speed < v ? speed : v`
        }
        // speed over the 0.09 s before that
        var avg2 = 0f
        var time2 = 0f
        if (pix.isNotEmpty()) {
            var skip = 0f; var t = 0.0; var dsum = 0.0
            for (i in pix.indices.reversed()) {
                val dt = f(pix[i].first)
                if (fcmple(skip, f(0x3d4ccccd)) || fcmpun(skip, f(0x3d4ccccd))) { skip = fadd(skip, dt); continue }
                t = dadd(t, f2d(dt))
                val over = dcmpgt(t, D_009)
                dsum = dadd(dsum, f2d(fmul(dt, i2f(pix[i].second))))
                if (over) break
            }
            if (!dcmplt(t, D_0001)) {
                val lt = dcmplt(t, D_009)
                avg2 = d2f(ddiv(dsum, if (lt) D_009 else t))
                time2 = if (lt) f(0x3db851ec) else d2f(t)
            }
        }
        body.setF32(0x21c, speed)
        val dtc = fdiv(el, f(0x3ca3d70a))
        val c214 = body.f32(0x214)
        chase(0x214, if (fcmpge(c214, speed) || fcmpun(c214, speed)) f(0x3f333333) else f(0x3ecccccd), dtc)
        chase(0x228, f(0x3e99999a), dtc)
        var accel = false
        val t400 = fadd(avg2, 400f)
        val a18 = fmul(avg2, f(0x3fe66666))
        val accelCase = !(fcmpge(avg2, 900f) || fcmpun(avg2, 900f)) &&
            !(fcmple(speed, t400) || fcmpun(speed, t400)) &&
            !(fcmple(time2, f(0x3c23d70a)) || fcmpun(time2, f(0x3c23d70a))) &&
            !(fcmple(speed, a18) || fcmpun(speed, a18))
        if (accelCase) {
            if (body.u8(0x235) == 0) {
                val c = body.f32(0x228)
                if (!(fcmpge(c, f(0x3e19999a)) || fcmpun(c, f(0x3e19999a)))) {
                    val i = findPointClosestToTime(dadd(env.now, D_M0085))
                    if (i >= 0) {
                        val q = pts[i]
                        q.setU16(0x224, q.u16(0x224) or 0x28)
                        if (i == pts.size - 1) accel = true
                    }
                    body.setU8(0x235, 1)
                }
            }
        } else if (!(fcmpge(speed, avg2) || fcmpun(speed, avg2))) body.setU8(0x235, 0)
        if (!(fcmple(speed, avg2) || fcmpun(speed, avg2))) { body.setI32(0x228, 0); body.setI32(0x22c, 0); body.setI32(0x230, 0) }
        val a52 = fmul(avg2, f(0x3f051eb8))
        val slowing = body.u8(0x236)
        if (!(fcmple(avg2, 1000f) || fcmpun(avg2, 1000f)) && !(fcmpge(speed, a52) || fcmpun(speed, a52))) {
            if (slowing == 0) {
                body.setU8(0x236, 1)
                body.setF64(0x238, env.now)
                body.setU8(0x240, (body.u8(0x240) + 1) and 0xff)
            }
            val i = findPointClosestToTime(dadd(env.now, D_M005))
            if (i >= 0) {
                val q = pts[i]
                q.setU16(0x224, q.u16(0x224) or 0x48)
                q.setU8(0x226, body.u8(0x240))
                val r = fdiv(speed, avg2)
                if (fcmplt(r, q.f32(0x228))) q.setF32(0x228, r)
                body.setF32(0x22c, 1f); body.setF32(0x228, 1f)
                body.setU8(0x236, 1)
            }
        } else if (slowing != 0) {
            val dd = dsub(env.now, body.f64(0x238))
            if (!(dcmple(dd, D_004) || dcmpun(dd, D_004))) body.setU8(0x236, 0)
        }
        if (!(fcmpge(speed, 140f) || fcmpun(speed, 140f))) {
            val q = fmul(body.f32(0x214), 0.25f)
            if (!(fcmpge(speed, q) || fcmpun(speed, q))) {
                accel = true
                body.setF64(0x220, env.now)
                body.setU8(0x234, 1)
                body.setF32(0x22c, 1f); body.setF32(0x228, 1f)
            }
        }
        return accel
    }

    private fun chase(off: Int, frac: Float, dt: Float) {
        val c = ChasingFloat(body.f32(off), body.f32(off + 4), body.f32(off + 8))
        c.chaseByFraction(frac, dt)
        body.setF32(off, c.cur); body.setF32(off + 4, c.mid)
    }

    /** record with two or more drift points: drags the newest one or starts a new one. */
    private fun tail(env: TouchEnv, p: Point2D, accel: Boolean, scale: Float) {
        val n = pts.size
        val last = pts[n - 1]
        val prev = pts[n - 2]
        val pp = ptOf(prev, 0xdc)
        val a0 = ptOf(last, 0xd4) - pp
        val l0 = a0.length()
        val a8 = ptOf(last, 0xdc) - pp
        val b0 = ptOf(last, 0xe4) - pp
        val b8 = p - pp
        val l1 = b8.length()
        val n60 = a0.normalise()
        val conj = n60.conjugate()
        val dy = absf(fadd(fmul(b8.x, conj.y), fmul(b8.y, conj.x)))
        var fct = fadd(fdiv(last.f32(0x234), f(0xbe8f5c29.toInt())), f(0x3fd9999a))
        fct = if (fcmplt(fct, f(0x3ee66666))) f(0x3ee66666) else if (fcmpgt(fct, f(0x3fd9999a))) f(0x3fd9999a) else fct
        val thrA = fmul(scale, fmul(fct, f(0x40866667)))
        val thrB = fmul(scale, fmul(fct, f(0x411ccccd)))
        val lf = pt(0x130).lengthFrom(pt(0x13c))
        val near = body.u8(0x1bc) != 0 && fcmplt(lf, fmul(scale, f(0x41b33333)))
        val lm3 = fadd(l0, -3f)
        if (!(fcmpge(l1, lm3) || fcmpun(l1, lm3))) last.setU16(0x224, last.u16(0x224) or 0x100)
        if (accel) {
            val f0 = last.u16(0x224)
            last.setU16(0x224, f0 or 0x18)
            if (!(fcmple(l0, f(0x40b33333)) || fcmpun(l0, f(0x40b33333)))) last.setU16(0x224, f0 or 0x98)
        }
        if (near) last.setU16(0x224, last.u16(0x224) or 0x80)
        if (!(fcmpge(dy, thrB) || fcmpun(dy, thrB))) {
            val lb = b0.length()
            if (!(fcmple(l1, lb) || fcmpun(l1, lb))) setPtOf(last, 0xe4, p)
        }
        if (!(fcmpge(dy, thrA) || fcmpun(dy, thrA))) {
            val la = a8.length()
            if (!(fcmple(l1, la) || fcmpun(l1, la)) && last.u8(0x224) and 0x80 == 0) {
                // drag the newest point along
                setPtOf(last, 0xdc, p)
                last.setF64(0x120, env.now)
                updateLastAccuracy()
                MooTouchPoint.determineDirection(last, prev)
                return
            }
        }
        if (last.u8(0x225) and 1 == 0) {
            if (fcmplt(ptOf(last, 0xdc).lengthFrom(p), fmul(scale, fmul(fct, 14f)))) return
        }
        val d58 = ptOf(last, 0xe4) - ptOf(last, 0xdc)
        val ln = d58.length()
        val t01 = fmul(scale, f(0x3dcccccd))
        if (!(fcmple(ln, t01) || fcmpun(ln, t01))) {
            val dt = Point2D.dot(d58.normalise(), (p - ptOf(last, 0xdc)).normalise())
            if (!(fcmpge(dt, 0f) || fcmpun(dt, 0f))) setPtOf(last, 0xdc, ptOf(last, 0xe4))
        }
        val ni = pushPointEx(env, false)
        MooTouchPoint.determineDirection(pts[ni], pts[ni - 1])
    }
}
