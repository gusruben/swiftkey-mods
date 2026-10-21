package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2iz
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.dmul
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.i2d
import nintype.engine.SoftFloat.i2f
import nintype.engine.geometry.MoopadGeometry
import nintype.engine.vectors.MemImage

/** The split-point finders MooMergeBuffer::update asks the PathSearch for (search world). */
object Splits {
    /**
     * mootouchIndexOfNearSpaceTapper() @007dc9a8: the first touch (list +0x1a0, neither the
     * first nor the last) that is a simple tap starting below the space bar's top (space key
     * +0xcb0) minus 0.74 key heights (truncated to an int) and is a good overlapper; -1 if none.
     */
    fun mootouchIndexOfNearSpaceTapper(sw: SearchWorld, env: GatherEnv): Int {
        val ps = sw.ps; val mp = sw.mp
        val space = env.model.board.named[0xfac]!!
        val kh = MoopadGeometry.curboardVertlen(mp)
        val thr = i2f(d2iz(dadd(i2d(space.img.i32(0xcb0)), dmul(f2d(kh), Bits.bitsToDouble(0xbfe7ae147ae147aeUL.toLong())))))
        val mts = sw.refs(ps, 0x1a0)
        val te = nintype.engine.touch.TouchEnv(env.model, mp, 0.0, env.roenScale)
        for ((i, m) in mts.withIndex()) {
            if (i == 0 || i + 1 >= mts.size) continue
            val t = nintype.engine.touch.Mootouch(m, listOf(sw.refs(m, 0x244).toMutableList(), sw.refs(m, 0x250).toMutableList(),
                sw.refs(m, 0x25c).toMutableList()), ArrayDeque())
            if (!t.isSimpleTap(te)) continue
            val y = m.f32(0x12c)
            if (fcmple(y, thr) || fcmpun(y, thr)) continue
            if (PathSearch.checkIsGoodOverlapper(mts, mts.map { sw.refs(it, 0x244) }, m, sw.refs(m, 0x244), mp.f32(0x1f4a0), mp.f32(0x1f4a4))) return i
        }
        return -1
    }

    private fun probablyContains(sw: SearchWorld, p: MemImage, c: Int, min: Float) =
        sw.refs(p, 0x328).any { it.i32(8) == c && fcmpgt(it.f32(0x10), min) }

    /**
     * mootouchIndexOfApostropheEnder() @007e6dc4: with at least 4 important points (+0x1bc), when
     * the last probably contains 's' and the one before it '&' (score > 3) and that one has
     * +0x225 bit 3, the index (+0x1a0) of the touch owning it; -1 otherwise.
     */
    fun mootouchIndexOfApostropheEnder(sw: SearchWorld): Int {
        val ps = sw.ps
        val pts = sw.refs(ps, 0x1bc)
        if (pts.size < 4) return -1
        val last = pts[pts.size - 1]; val prev = pts[pts.size - 2]
        if (!probablyContains(sw, last, 0x73, 3f)) return -1
        if (!probablyContains(sw, prev, 0x26, 3f)) return -1
        if (prev.u8(0x225) and 8 == 0) return -1
        val owner = prev.refs[8]
        return sw.refs(ps, 0x1a0).indexOfFirst { it === owner }
    }
}
