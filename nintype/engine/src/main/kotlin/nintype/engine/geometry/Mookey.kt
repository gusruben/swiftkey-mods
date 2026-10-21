package nintype.engine.geometry

import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.i2f
import nintype.engine.SoftFloat.f2iz
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

/**
 * `Emkey::Mookey` (sizeof 0xdb4), one key of a [MooBoard], as a view over a byte image so the
 * field offsets stay the binary's. Only the fields the decoder reads are named (measured by
 * tracing every load from key memory during real decodes, see GLUE.md "Fields the decoder
 * reads"). Names are from use; the offsets are what the vectors check.
 *
 * Board space: x grows right, y grows down, one key pitch = 54 units on Nintype's QWERTY.
 */
class Mookey(val img: MemImage) {
    val flags: Int get() = img.i32(FLAGS)
    /** Flag bit 0x400000 (byte 0x52 bit 0x40): the one-handed-mode shape is a full rect, no
     * corner polygon (findTest returns distance 999, contains walks the stair list). */
    val ohmRectShape: Boolean get() = img.u8(0x52) and 0x40 != 0
    val chara: Int get() = img.i32(CHARA)
    val rowIndex: Int get() = img.i32(ROW)
    val x0: Int get() = img.i32(X0)
    val x1: Int get() = img.i32(X1)
    val touchX0: Int get() = img.i32(TX0)
    val touchX1: Int get() = img.i32(TX1)
    val y0: Int get() = img.i32(Y0)
    val y1: Int get() = img.i32(Y1)
    val stairLimit: Int get() = img.i32(STAIR_LIMIT)
    val labelPos: Point2D get() = pt(LABEL_POS)
    /** One-handed mode active for this key (byte 0xce8). */
    val ohm: Boolean get() = img.u8(OHM) != 0
    val ohmCentre: Point2D get() = pt(OHM_CENTRE)
    val centre: Point2D get() = pt(CENTRE)
    val toucherCount: Int get() = img.i32(TOUCHERS)

    private fun pt(off: Int) = Point2D(img.f32(off), img.f32(off + 4))

    /** The (x, y) stair points at 0xd34 (gnustl vector<Point2D>), used by [contains] when
     * [ohm] && [ohmRectShape]. Set by the owner (vector decode or [KeyboardModel]). */
    var stairs: List<Point2D> = emptyList()

    /** Mookey::findTest(Point2D const&, float&) @009bc484. Returns hit; on a hit [dist] is
     * replaced (in [FindTest.dist]). NaN coordinates never hit (unordered compares fail). */
    fun findTest(p: Point2D, dist: Float): FindTest {
        val x = p.x
        if (ohm) {
            if (ltOrUn(x, i2f(x0)) || gtOrUn(x, i2f(x1))) return FindTest(false, dist)
            val y = p.y
            if (ltOrUn(y, i2f(y0)) || gtOrUn(y, i2f(y1))) return FindTest(false, dist)
            if (ohmRectShape) return FindTest(true, F999)
            if (!Geometry.withinRectangleWithTolerance(p, pt(CORNER_A), pt(CORNER_B), pt(CORNER_C), pt(CORNER_D), 1.0f))
                return FindTest(false, dist)
            return FindTest(true, ohmCentre.lengthFrom(p))
        }
        if (ltOrUn(x, i2f(touchX0)) || gtOrUn(x, i2f(touchX1))) return FindTest(false, dist)
        val y = p.y
        if (ltOrUn(y, i2f(y0)) || gtOrUn(y, i2f(y1))) return FindTest(false, dist)
        return FindTest(true, p.lengthFrom(centre))
    }

    data class FindTest(val hit: Boolean, val dist: Float)

    /** Mookey::contains(int, int) @009bf4c4: inclusive integer rect [x0,x1] x [y0,y1]; in one-
     * handed mode also the corner polygon (tolerance 1) or, for rect-shaped keys, the stairs. */
    fun contains(x: Int, y: Int): Boolean {
        val lx = x0
        if (!ohm) return !(lx > x || x1 < x || y0 > y || y1 < y)
        if (lx > x || x1 < x || y0 > y || y1 < y) return false
        if (!ohmRectShape) {
            return Geometry.withinRectangleWithTolerance(Point2D(i2f(x), i2f(y)), pt(CORNER_A), pt(CORNER_B), pt(CORNER_C), pt(CORNER_D), 1.0f)
        }
        val lim = i2f(stairLimit)
        val fx = i2f(x)
        for (s in stairs) {
            val sy = s.y
            val t = fadd(sy, 1.0f)
            if (fcmpge(t, lim) || fcmpun(t, lim)) continue
            val sx = s.x
            if (fcmpge(fx, sx) || fcmpun(fx, sx)) continue
            return fcmpgt(i2f(y), sy)
        }
        return false
    }

    /** Mookey::containsWithExtra(Point2D const&, int l, int r, int t, int b) @009bf3a0. */
    fun containsWithExtra(p: Point2D, l: Int, r: Int, t: Int, b: Int): Boolean {
        if (ohm) return contains(f2iz(p.x), f2iz(p.y))
        return containsWithExtraIgnoringOHM(p, l, r, t, b)
    }

    /** Mookey::containsWithExtraIgnoringOHM(Point2D const&, int, int, int, int) @009bf2a8:
     * the [x0 - l, x1 + r] x [y0 - t, y1 + b] float test (int sums wrap like the binary's). */
    fun containsWithExtraIgnoringOHM(p: Point2D, l: Int, r: Int, t: Int, b: Int): Boolean {
        val x = p.x
        if (ltOrUn(x, i2f(x0 - l)) || gtOrUn(x, i2f(x1 + r))) return false
        val y = p.y
        if (ltOrUn(y, i2f(y0 - t))) return false
        return fcmple(y, i2f(y1 + b))
    }

    /** Mookey::getTrieChara() @009bb1bc: the cached trie char at 0xa0; when 0, computed from
     * the non-basic chara, else the chara, else '?' via Emkey::get_trie_chara and cached. */
    fun getTrieChara(): Int {
        val c = img.i32(TRIE_CHARA)
        if (c != 0) return c
        val t = Geometry.getTrieChara(trieSource())
        img.setI32(TRIE_CHARA, t)
        return t
    }

    private fun trieSource(): Int {
        val nb = img.i32(NONBASIC)
        if (nb != 0) return nb
        val ch = img.i32(CHARA)
        return if (ch == 0) 0x3f else ch
    }

    /** Mookey::getAltTrieChara() @009bb158: cached at 0xa4; flag bit 0x4 makes it the
     * apostrophe's trie char, else the trie char (filling 0xa0 the same way when 0). */
    fun getAltTrieChara(): Int {
        val a = img.i32(ALT_TRIE_CHARA)
        if (a != 0) return a
        val v = if (img.u8(FLAGS) and 4 != 0) Geometry.getTrieChara(0x27) else {
            val t = img.i32(TRIE_CHARA)
            if (t != 0) t else Geometry.getTrieChara(trieSource()).also { img.setI32(TRIE_CHARA, it) }
        }
        img.setI32(ALT_TRIE_CHARA, v)
        return v
    }

    /** Mookey::getNonBasicChara() @009bb058: the non-basic chara if chara_is_nonbasic, else
     * (when 0x9c is 0) the chara if non-basic, else 0. */
    fun getNonBasicChara(): Int {
        val nb = img.i32(NONBASIC)
        if (nb != 0) return if (nintype.engine.trie.Keycode.isNonbasic(nb)) nb else 0
        val ch = img.i32(CHARA)
        if (ch == 0) return 0
        return if (nintype.engine.trie.Keycode.isNonbasic(ch)) ch else 0
    }

    /** Mookey::getAltTrieCharaPosShift() @009bb0b8 (sret): (keyWidth / 2, 0) when the alt trie
     * char is the apostrophe, else (10, 0). keyWidth comes from owner board (0xc) -> Moopad
     * (board+4) -> 0x1f4a4. */
    fun getAltTrieCharaPosShift(): Point2D {
        val a = getAltTrieChara()
        if (a != 0x27) return Point2D(F10, 0f)
        val board = img.ref(OWNER) ?: img.ptr(OWNER) ?: error("Mookey without owner board")
        val mp = board.ref(MooBoard.MOOPAD) ?: board.ptr(MooBoard.MOOPAD) ?: error("board without Moopad")
        return Point2D(nintype.engine.SoftFloat.fmul(mp.f32(MooBoard.MP_KEY_W), 0.5f), 0f)
    }

    /** The def-file name (std::string at 0x60), e.g. "q", "leftcursorer", "one". */
    var name: String = ""

    /** The key type name (std::string at 0x5c), e.g. "space", "caps", "backspace"; "" for
     * ordinary keys. MooBoard::syncNamedKeys indexes keys by it. */
    var typeName: String = ""

    /** Board-build links (BoardBuild.kt): 0x14 / 0x18 previous / next visible key of the row
     * (Moorow::syncXesAndSplits), 0x1c all neighbours, 0x28 neighbours + self, 0x34 neighbours
     * in the next row down (MooBoard::syncNeighbors). */
    var prev: Mookey? = null
    var next: Mookey? = null
    val neighbors = ArrayList<Mookey>()
    val neighborsAndSelf = ArrayList<Mookey>()
    val neighborsBelow = ArrayList<Mookey>()

    /** Mookey::isRechooser() @009bf1c4: the suggestion-bar keys "one", "two", "three". */
    fun isRechooser(): Boolean = name == "one" || name == "two" || name == "three"

    /** Mookey::isAlphaKey() @009b97e8: shing_iswalpha(chara). */
    fun isAlphaKey(): Boolean = Geometry.shingIswalpha(chara)

    /**
     * Mookey::alphaBoardForcePushKeyIndex() @009bb1f8 (sret TileIndex): the force-touch grid slot
     * of a letter key. (-1, -1) without an owner board (0xc), owner row (0x10, a Moorow*), board
     * byte 0x1c, or an alphabetic chara. r = row index (row+0x2c), minus 1 when Moopad USF1 0xd9 is
     * set. r == 1: (1, c) with c = 0xc94, or 9 - (0xc98 - 1 - c) when 0xc98 != 0 and c >= that;
     * else (r, 0x44) unless 0x44 is -1.
     */
    fun alphaBoardForcePushKeyIndex(): TileIndex {
        val none = TileIndex(-1, -1)
        val board = img.ref(OWNER) ?: img.ptr(OWNER) ?: return none
        val row = img.ref(OWNER_ROW) ?: img.ptr(OWNER_ROW) ?: return none
        if (board.u8(0x1c) == 0) return none
        if (!Geometry.shingIswalpha(chara)) return none
        var r = row.i32(0x2c)
        val mp = board.ref(MooBoard.MOOPAD) ?: board.ptr(MooBoard.MOOPAD) ?: error("board without Moopad")
        if (MoopadGeometry.hasUSF1(mp, 0xd9) != 0) r -= 1
        if (r == 1) {
            val n = img.i32(0xc98)
            var c = img.i32(0xc94)
            if (n != 0) {
                val lim = n - 1 - c
                if (c >= lim) c = 9 - lim
            }
            return TileIndex(1, c)
        }
        val col = img.i32(0x44)
        if (col == -1) return none
        return TileIndex(r, col)
    }

    /** Mookey::hasToucher() @009bf1a4: toucher count (0xd88) != 0. */
    fun hasToucher(): Boolean = img.i32(TOUCHERS) != 0

    /** Mookey::isEmojiBoardEmojiKey() @009b9f78: the emoji index at 0x54 is >= 0 (signed). */
    fun isEmojiBoardEmojiKey(): Boolean = img.i32(0x54) >= 0

    /** Mookey::shiftToucherCount(int) @009bf1b4. */
    fun shiftToucherCount(d: Int) = img.setI32(TOUCHERS, img.i32(TOUCHERS) + d)

    companion object {
        const val SIZE = 0xdb4
        const val OWNER = 0x0c        // MooBoard* owner
        const val OWNER_ROW = 0x10    // Moorow* owner row
        const val FLAGS = 0x50        // u32 key flags
        const val NAME = 0x60         // std::string def-file name ("q", "leftcursorer", ...)
        const val CHARA = 0x98        // unicode the key types (0 for function keys)
        const val NONBASIC = 0x9c
        const val TRIE_CHARA = 0xa0
        const val ALT_TRIE_CHARA = 0xa4
        const val ROW = 0xc9c         // row index in the board (findKeyEx's minimum-row filter)
        const val X0 = 0xca0          // visual rect, ints, inclusive
        const val X1 = 0xca4
        const val TX0 = 0xca8         // touch x range: edge keys reach past the board edge
        const val TX1 = 0xcac
        const val Y0 = 0xcb0
        const val Y1 = 0xcb4
        const val STAIR_LIMIT = 0xcc4
        const val LABEL_POS = 0xcd0   // Point2D, findKeyPosByUnicode's answer
        const val INNER_RECT = 0xcd8  // 4 ints (y0+2, y1-2, x0+1, x1-1), read by Mootouch::pushPointEx
        const val OHM = 0xce8         // u8
        const val CORNER_A = 0xcec    // one-handed-mode quad, passed to withinRectangleWithTolerance as
        const val CORNER_D = 0xcf4    //   (A, B, C, D) = (0xcec, 0xcfc, 0xd04, 0xcf4)
        const val CORNER_B = 0xcfc
        const val CORNER_C = 0xd04
        const val OHM_CENTRE = 0xd2c  // Point2D
        const val STAIRS = 0xd34      // vector<Point2D>
        const val CENTRE = 0xd4c      // Point2D: the key centre every distance is measured to
        const val TOUCHERS = 0xd88    // int

        val F999 = java.lang.Float.intBitsToFloat(0x4479c000)
        val F10 = java.lang.Float.intBitsToFloat(0x41200000)

        internal fun ltOrUn(a: Float, b: Float) = fcmplt(a, b) || fcmpun(a, b)
        internal fun gtOrUn(a: Float, b: Float) = fcmpgt(a, b) || fcmpun(a, b)
    }
}
