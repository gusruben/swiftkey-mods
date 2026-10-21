package nintype.engine.geometry

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.math.LibM
import nintype.engine.math.Point2D

/** Free geometry helpers the board code calls. */
object Geometry {
    /**
     * Point2D::withinRectangleWithTolerance(a, b, c, d, tol) const @00c1fd00. For each edge
     * (a->b, b->c, c->d, d->a) the signed distance of `p` from the edge line,
     * (e.x * (p - from).y - e.y * (p - from).x) / |e|, must be <= tol (NaN fails). So the
     * quad is wound with its inside on the negative side.
     */
    fun withinRectangleWithTolerance(p: Point2D, a: Point2D, b: Point2D, c: Point2D, d: Point2D, tol: Float): Boolean {
        if (bad(edge(p, a, b), tol)) return false
        if (bad(edge(p, b, c), tol)) return false
        if (bad(edge(p, c, d), tol)) return false
        return !bad(edge(p, d, a), tol)
    }

    private fun bad(v: Float, tol: Float) = fcmpgt(v, tol) || fcmpun(v, tol)

    private fun edge(p: Point2D, from: Point2D, to: Point2D): Float {
        val ex = fsub(to.x, from.x)
        val ey = fsub(to.y, from.y)
        val px = fsub(p.x, from.x)
        val py = fsub(p.y, from.y)
        val cross = fsub(fmul(ex, py), fmul(ey, px))
        return fdiv(cross, LibM.sqrtf(fadd(fmul(ex, ex), fmul(ey, ey))))
    }

    /** Emkey::get_trie_chara(int) @0116ef80: keycode_to_chara(basic(towlower(c)) ?: latin(towlower(c))),
     * i.e. the trie's own keycode functions (ported in nintype.engine.trie.Keycode). */
    fun getTrieChara(c: Int): Int = nintype.engine.trie.Keycode.toChara(nintype.engine.trie.Keycode.noAltchain(c))

    /** shing_iswalpha(int) @00f30718: a jump table over U+0041..U+0481 (true on the ranges
     * below, read from the table), else Hangul::is_hangul, Shing::is_hebrew (U+05D0..05EA),
     * is_cyrillic (U+0400..045F, U+048A..04F9), is_greek_coptic (U+0370..03FF). */
    fun shingIswalpha(c: Int): Boolean {
        if (Integer.compareUnsigned(c - 0x41, 0x440) <= 0) {
            var i = 0
            while (i < ISWALPHA_TABLE.size) {
                if (c >= ISWALPHA_TABLE[i] && c <= ISWALPHA_TABLE[i + 1]) return true
                i += 2
            }
        }
        if (nintype.engine.trie.Hangul.isHangul(c)) return true
        if (Integer.compareUnsigned(c - 0x5d0, 0x1b) < 0) return true
        if (Integer.compareUnsigned(c - 0x400, 0x60) < 0) return true
        if (Integer.compareUnsigned(c - 0x48a, 0x70) < 0) return true
        return Integer.compareUnsigned(c - 0x370, 0x90) < 0
    }

    private val ISWALPHA_TABLE = intArrayOf(
        0x41, 0x5a, 0x61, 0x7a, 0xaa, 0xaa, 0xb5, 0xb5, 0xba, 0xba, 0xc0, 0xd6, 0xd8, 0xf6, 0xf8, 0x257, 0x410, 0x481)

    /** msun floorf. The floor of a float is always a representable float, so the JVM's
     * double floor is exact (signed zeros and NaN pass through as msun's do). */
    fun floorf(x: Float): Float = Math.floor(x.toDouble()).toFloat()
}
