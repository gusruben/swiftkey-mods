package nintype.engine.merge

import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.math.FastCurveMapper
import nintype.engine.touch.Mootouch
import nintype.engine.touch.TouchEnv
import nintype.engine.vectors.MemImage

/**
 * `Emkey::mootouchlist_getExplicitResult(std::vector<Mootouch*>, Moopad*, float& a, float& b,
 * bool& forced, std::vector<unsigned int>& codes)` @00958764: the literal reading of the
 * buffered touches (the key each touch landed on, ordered by end time), plus a confidence
 * floor [a] and the summed touch weight [b]. Spec in re/vectors/gen_merge_explicit.py.
 * Mootouches are the touch area's model (touch/Mootouch.kt; key holders hold key indices).
 */
object ExplicitResult {
    class Out(var a: Float, var b: Float, var forced: Boolean, val codes: MutableList<Int>, var text: ByteArray = ByteArray(0))

    /** {-0.1->0, 0->0.15, 0.1->0.2, 0.3->0.7, 0.5->1}: the two-touch floor by inter-touch gap (s). */
    val TWO_TOUCH = FastCurveMapper.ofBits(
        0xbdcccccd.toInt(), 0x00000000, 0x00000000, 0x3e19999a, 0x3dcccccd, 0x3e4ccccd,
        0x3e99999a, 0x3f333333, 0x3f000000, 0x3f800000)

    private val F23_5 = java.lang.Float.intBitsToFloat(0x41bc0000)
    private val F0_85 = java.lang.Float.intBitsToFloat(0x3f59999a)
    private val F0_8 = java.lang.Float.intBitsToFloat(0x3f4ccccd)

    /** [litamos2syl] = Hangul::litamos2syl (Korean jamo composition; not ported yet). */
    fun mootouchlistGetExplicitResult(touches: List<Mootouch>, env: TouchEnv, mp: MemImage, o: Out,
                                      litamos2syl: (List<Int>) -> ByteArray = { error("Hangul::litamos2syl is not ported") }) {
        o.forced = false
        var bad = false
        var litamo = false
        val literal = ArrayList<Int>()
        val byEnd = ArrayList<Pair<Double, Int>>()
        val fingers = java.util.TreeSet<Int>()
        val n = touches.size
        for ((k0, mt) in touches.withIndex()) {
            val k = k0 + 1
            val body = mt.body
            val keyIdx = body.i32(0x38)
            if (keyIdx == -1) bad = true
            else {
                val key = env.key(keyIdx) ?: error("touch key index $keyIdx")
                var chara: Int
                val frc: Boolean
                if (body.u8(0x102) != 0) { chara = body.i32(0x104); frc = true }
                else {
                    chara = key.img.i32(0x9c)
                    if (chara == 0) chara = key.img.i32(0x98)
                    frc = false
                }
                if (Xnl.val_(mp, 0x1f424) == 1) { if (k == n) o.codes.add(chara) } else o.codes.add(chara)
                literal.add(chara)
                val lit = ShingText.isLitamo(chara)
                if (frc) {
                    if (fcmplt(o.a, F23_5)) o.a = F23_5
                    o.forced = true
                }
                if (body.u8(0xcc) != 0) chara = nintype.engine.trie.Caps.shingTowupper(chara)
                val t = body.f64(0x110)
                var pos = byEnd.size
                for (i in byEnd.indices) if (dcmplt(t, byEnd[i].first)) { pos = i; break }
                byEnd.add(pos, Pair(t, chara))
                litamo = litamo || lit
            }
            if (!mt.isSimpleTap(env)) {
                val ok = if (body.u8(0xb5) == 0) true else {
                    val np = mt.pts.size
                    when {
                        np == 0 -> false
                        np > 2 -> false
                        np == 1 -> true
                        else -> { val f = mt.pts[0].f32(0x36c); fcmple(f, F0_85) || fcmpun(f, F0_85) }
                    }
                }
                bad = bad || !ok
            }
            fingers.add(body.i32(0xd0))
            o.b = fadd(body.f32(0x120), o.b)
        }
        fun floor(v: Float) { if (fcmplt(o.a, v)) o.a = v }
        when {
            n >= 4 -> floor(F0_8)
            n == 3 -> when (fingers.size) { 2 -> floor(0.25f); 3 -> floor(F0_8); else -> {} }
            n == 2 -> floor(TWO_TOUCH.get(d2f(dsub(touches[1].body.f64(0xf8), touches[0].body.f64(0x110)))))
            else -> {}
        }
        o.text = when {
            bad -> ByteArray(0)
            litamo -> litamos2syl(literal)
            else -> java.io.ByteArrayOutputStream().also { s -> for ((_, c) in byEnd) ShingText.appendUtf8Char(s, c) }.toByteArray()
        }
    }
}
