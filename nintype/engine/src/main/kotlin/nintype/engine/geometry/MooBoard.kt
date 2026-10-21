package nintype.engine.geometry

import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fcmpgt
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage
import java.util.TreeMap

/** TileIndex (8 bytes: x, y ints). operator< @00dea880 is lexicographic (x, then y). */
data class TileIndex(val x: Int, val y: Int) : Comparable<TileIndex> {
    override fun compareTo(other: TileIndex): Int =
        if (x != other.x) x.compareTo(other.x) else y.compareTo(other.y)
}

/**
 * `Emkey::Moorow` (by value in MooBoard's row vector at +0x28, stride 0x58). Decoder-read
 * fields: 0x1c/0x20 y range [y0, y1) (ints), 0x48/0x4c the x clamp range of coverage positions
 * (first/last key centre), 0x10 vector<Mookey> = the row's live keys (stride 0xdb4).
 * Also seen: 0x04 vector<Mookey> template copies, 0x2c row index, 0x50 x offset, 0x54 width.
 */
class Moorow(val img: MemImage, val keys: List<Mookey> = emptyList()) {
    val y0: Int get() = img.i32(Y0)
    val y1: Int get() = img.i32(Y1)
    val clampX0: Int get() = img.i32(CLAMP_X0)
    val clampX1: Int get() = img.i32(CLAMP_X1)

    companion object {
        const val SIZE = 0x58
        const val KEYS = 0x10
        const val Y0 = 0x1c
        const val Y1 = 0x20
        const val CLAMP_X0 = 0x48
        const val CLAMP_X1 = 0x4c
    }
}

/**
 * `Emkey::MooBoard`: the active key layout. The scalar fields stay in [img] at the binary's
 * offsets; containers are Kotlin structures built by the vector adapter or [KeyboardModel]:
 *
 *  - 0x004 Moopad* (its 0x1f4a4 keyWidth and 0x1f4a0 keyHeight size the tile grid): [mp]
 *  - 0x028 vector<Moorow>: [rows]
 *  - 0x044 int height (findKeyEx clamps y to height - 1)
 *  - 0x054/0x058 int y clamp range of coverage positions (first/last letter-row centre)
 *  - 0x0fac Mookey* spacebar (getSpacebarStuffXMid); 0xfa8..0xfdc other named keys
 *  - 0x0fec map<TileIndex, MookeyMaplet{TileIndex; vector<Mookey*> at +8}>: [tiles]
 *  - 0x1028 map<int unicode, vector<Mookey*>>: [byUnicode]
 *  - 0x1084 vector<Mookey*> apostrophe fallback keys: [apostropheKeys]
 */
class MooBoard(
    val img: MemImage,
    val mp: MemImage,
    val rows: List<Moorow>,
    val tiles: TreeMap<TileIndex, List<Mookey>>,
    val byUnicode: TreeMap<Int, List<Mookey>> = TreeMap(),
    val apostropheKeys: List<Mookey> = emptyList(),
    val named: Map<Int, Mookey?> = emptyMap(),
    /** 0x1010 map<int trie char, vector<Mookey*>> (Mookey::getTrieChara of each key). */
    val byTrieChara: TreeMap<Int, MutableList<Mookey>> = TreeMap(),
) {
    /** 0x1090 vector<BoardDirectionalIndexes>: 64 x 64 entries of 4 direction bytes (0xff =
     * empty), allocated on first use (null = the empty vector the board boots with). */
    var bidir: ByteArray? = null

    /** A BoardDirectionalIndexes the binary returns a pointer to: entry [index] of [bidir], or
     * (index -1) the function-static empty one (all 0xff). */
    class BiDirIndexes(private val table: ByteArray?, val index: Int) {
        val isStaticEmpty: Boolean get() = index < 0
        /** Direction slot i (0..3) as an unsigned byte; 0xff = none. */
        operator fun get(i: Int): Int = if (table == null || index < 0) 0xff else table[4 * index + i].toInt() and 0xff
        fun bytes(): ByteArray = ByteArray(4) { get(it).toByte() }
    }

    /**
     * MooBoard::getBiDirIndex(int c1, int c2) @009e1128: the directions (DirectionalCoverage
     * indexes of key2.centre - key1.centre, up to 4, in key order) from the keys of trie char c1
     * to those of c2, cached in [bidir] at [keycode(c2) + 64 * keycode(c1)]. Both lookups use
     * operator[] (a missing char gets an empty entry). Either char with no keys or more than 2
     * keys returns the static empty entry. A cached entry (slot 0 set) is returned as is.
     */
    fun getBiDirIndex(c1: Int, c2: Int): BiDirIndexes {
        val l1 = byTrieChara.getOrPut(c1) { ArrayList() }
        val l2 = byTrieChara.getOrPut(c2) { ArrayList() }
        if (l1.isEmpty() || l1.size > 2) return BiDirIndexes(null, -1)
        if (l2.isEmpty() || l2.size > 2) return BiDirIndexes(null, -1)
        val k1 = nintype.engine.trie.Keycode.trieCharaToKeycode(c1)
        val k2 = nintype.engine.trie.Keycode.trieCharaToKeycode(c2)
        // the binary asserts k1, k2 < 64 and indexes anyway; real trie chars always pass
        val idx = k2 + (k1 shl 6)
        val t = bidir ?: ByteArray(0x4000) { 0xff.toByte() }.also { bidir = it }
        val e = 4 * idx
        if (t[e].toInt() and 0xff != 0xff) return BiDirIndexes(t, idx)
        for (a in l1) for (b in l2) {
            val d = nintype.engine.emkey.DirectionalCoverage.getIndex(b.centre - a.centre).toByte()
            when {
                t[e].toInt() and 0xff == 0xff -> t[e] = d
                t[e + 1].toInt() and 0xff == 0xff -> t[e + 1] = d
                t[e + 2].toInt() and 0xff == 0xff -> t[e + 2] = d
                t[e + 3].toInt() and 0xff == 0xff -> t[e + 3] = d
            }
        }
        return BiDirIndexes(t, idx)
    }

    val height: Int get() = img.i32(HEIGHT)

    /** MooBoard::clampCoveragePos(Point2D const&) @009e0268 (sret). Clamp y to the letter-row
     * centre range [0x54, 0x58]; then, in the row whose [y0, y1) holds (int)y, clamp x to the
     * row's centre range. An inverted y range (0x58 < 0x54) returns p unchanged. */
    fun clampCoveragePos(p: Point2D): Point2D {
        val lo = img.i32(CLAMP_Y0)
        val hi = img.i32(CLAMP_Y1)
        if (hi < lo) return p
        val y = clamp(p.y, i2f(lo), i2f(hi))
        val iy = f2iz(y)
        for (r in rows) {
            if (iy >= r.y0 && iy < r.y1) return Point2D(clamp(p.x, i2f(r.clampX0), i2f(r.clampX1)), y)
        }
        return Point2D(p.x, y)
    }

    /** MooBoard::findKey(int, int) @009ee8b4 = findKeyEx(x, y, -99, false). */
    fun findKey(x: Int, y: Int): Mookey? = findKeyEx(x, y, -99, false)

    /**
     * MooBoard::findKeyEx(int x, int y, int minRow, bool alt) @009ee8d8: among the keys
     * registered in the tile holding (x, y) (tile = floor(x / keyWidth), floor(y / keyHeight),
     * y first clamped to height - 1), skip keys with row < minRow and keys flagged 0x100000
     * (alt: 0x2800000); return the findTest hit nearest to its centre, with distance < 4999
     * (ties keep the earlier key).
     */
    fun findKeyEx(x: Int, y0: Int, minRow: Int, alt: Boolean): Mookey? {
        var y = y0
        val h = height
        if (h <= y) y = h - 1
        val fx = i2f(x)
        val fy = i2f(y)
        val tx = f2iz(Geometry.floorf(fdiv(fx, mp.f32(MP_KEY_W))))
        val ty = f2iz(Geometry.floorf(fdiv(fy, mp.f32(MP_KEY_H))))
        val list = tiles[TileIndex(tx, ty)] ?: return null
        var best = F4999
        var found: Mookey? = null
        val p = Point2D(fx, fy)
        val mask = if (alt) 0x2800000 else 0x100000
        for (k in list) {
            if (k.rowIndex < minRow) continue
            if (k.flags and mask != 0) continue
            val r = k.findTest(p, F99990)
            if (!r.hit) continue
            if (!(nintype.engine.SoftFloat.fcmpge(r.dist, best) || nintype.engine.SoftFloat.fcmpun(r.dist, best))) {
                best = r.dist
                found = k
            }
        }
        return found
    }

    /**
     * MooBoard::findKeyByUnicode(unsigned int, int mode) @009e54b0. Of the keys mapped to `u`
     * with a nonzero chara: mode 1 = leftmost x0, mode 2 = rightmost x0 (first wins ties);
     * mode 0 = whichever of the two has its label x farther from the spacebar mid (left on a
     * tie); other modes, or no key, null.
     */
    fun findKeyByUnicode(u: Int, mode: Int, roenWidth: Int): Mookey? {
        val list = byUnicode[u] ?: return null
        var left: Mookey? = null
        var right: Mookey? = null
        for (k in list) {
            if (k.chara == 0) continue
            if (left == null || k.x0 < left.x0) left = k
            if (right == null || k.x0 > right.x0) right = k
        }
        return when (mode) {
            1 -> left
            2 -> right
            0 -> {
                if (left == null && right == null) return null
                val mid = MoopadGeometry.getSpacebarStuffXMid(mp, roenWidth)
                val dl = abs(fsub(left!!.img.f32(Mookey.LABEL_POS), mid))
                val dr = abs(fsub(right!!.img.f32(Mookey.LABEL_POS), mid))
                if (fcmplt(dl, dr)) right else left
            }
            else -> null
        }
    }

    /**
     * MooBoard::findKeyPosByUnicode(unsigned int, int) @009e5680 (sret): the label position
     * of findKeyByUnicode(u); for an unmapped '\'' the first apostrophe key's label + (keyWidth/2, 0);
     * else retried with [trieChara] (Emkey::get_trie_chara);
     * else (roenWidth / 2, spacebarTop + keyHeight / 2).
     */
    fun findKeyPosByUnicode(u: Int, mode: Int, roenWidth: Int, trieChara: (Int) -> Int = Geometry::getTrieChara): Point2D {
        findKeyByUnicode(u, mode, roenWidth)?.let { return it.labelPos }
        if (u == 0x27 && apostropheKeys.isNotEmpty()) {
            val k = apostropheKeys[0]
            return k.labelPos + Point2D(fmul(mp.f32(MP_KEY_W), 0.5f), 0f)
        }
        findKeyByUnicode(trieChara(u), mode, roenWidth)?.let { return it.labelPos }
        val x = fmul(i2f(roenWidth), 0.5f)
        val top = MoopadGeometry.curboardSpacebarTopYpos(mp)
        val half = fmul(MoopadGeometry.curboardVertlen(mp), 0.5f)
        return Point2D(x, nintype.engine.SoftFloat.fadd(top, half))
    }

    companion object {
        const val MOOPAD = 0x4
        const val ROWS = 0x28
        const val HEIGHT = 0x44
        const val CLAMP_Y0 = 0x54
        const val CLAMP_Y1 = 0x58
        const val ACTIVE_XNL = 0xf5c   // Xnl; isActive() = its val byte at +0xc (board+0xf68)
        const val SPACEBAR = 0xfac
        const val TILES = 0xfec
        const val BY_UNICODE = 0x1028
        const val APOSTROPHE_KEYS = 0x1084
        const val BY_TRIECHARA = 0x1010
        const val BIDIR = 0x1090
        const val MP_KEY_H = 0x1f4a0
        const val MP_KEY_W = 0x1f4a4

        val F4999 = java.lang.Float.intBitsToFloat(0x459c3800)
        val F1_33 = java.lang.Float.intBitsToFloat(0x3faa3d71)
        val F2_3 = java.lang.Float.intBitsToFloat(0x40133333)
        val F0_33 = java.lang.Float.intBitsToFloat(0x3ea8f5c3)
        const val MP_OHM_LEFT = 0x9d6c
        const val MP_OHM_RIGHT = 0x9da0
        val F99990 = java.lang.Float.intBitsToFloat(0x47c34b00)

        /** x < lo -> lo; else x > hi -> hi; else x (NaN passes through), as compiled. */
        internal fun clamp(x: Float, lo: Float, hi: Float): Float = if (fcmplt(x, lo)) lo else if (fcmpgt(x, hi)) hi else x

        private fun abs(f: Float) = java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(f) and 0x7fffffff)
    }

    // --- one-handed mode (OHM) -------------------------------------------------------------

    private fun ohmBoardFlag(): Boolean =
        img.u8(0x25) != 0 || img.u8(0x24) != 0 || img.u8(0x1c) != 0 || img.u8(0x26) != 0

    /** MooBoard::oneHandedModeState() @009c10c8: 0 when the board allows no OHM (bytes 0x1c,
     * 0x24..0x26 all 0); -1 when the left-hand Xnl (Moopad 0x9d6c) is on; else the right-hand
     * Xnl's raw byte (Moopad 0x9da0). */
    fun oneHandedModeState(): Int {
        if (!ohmBoardFlag()) return 0
        if (mp.u8(MP_OHM_LEFT + 0xc) != 0) return -1
        return mp.u8(MP_OHM_RIGHT + 0xc)
    }

    /** MooBoard::getOhmBottomCorner() @009c113c (sret): key-area factors (0.75, 1.33) left-handed,
     * (0.25, 1.33) right-handed (right Xnl == 1), else (0.5, 1.0), through keyarea_xposByFactor /
     * letterarea_yposByFactor. */
    fun getOhmBottomCorner(roenWidth: Int): Point2D {
        var fx = 0.5f
        var fy = 1.0f
        if (ohmBoardFlag()) {
            if (mp.u8(MP_OHM_LEFT + 0xc) != 0) { fx = 0.75f; fy = F1_33 }
            else if (mp.u8(MP_OHM_RIGHT + 0xc) == 1) { fx = 0.25f; fy = F1_33 }
        }
        return Point2D(MoopadGeometry.keyareaXposByFactor(mp, roenWidth, fx), MoopadGeometry.letterareaYposByFactor(mp, fy))
    }

    /**
     * MooBoard::ohmShift(Point2D const& p, bool noClamp) @009c1204 (sret): the one-handed-mode
     * warp. In key-area factors (fx, fy): s = (right ? 1 - fx : fx), clamped to [0, 1] unless
     * noClamp; dy = (fy * 0.33 + (1 - fy) * 0.33) * s^2.3; dx = max(fy, 0) * s * (right ? 0.25 :
     * -0.25); returns the positions of (fx + dx, fy + dy). Unchanged without OHM. "right" is the
     * right-hand Xnl byte == 1; any other nonzero state (incl. left = -1) takes the left branch.
     */
    fun ohmShift(p: Point2D, noClamp: Boolean, roenWidth: Int): Point2D {
        if (!ohmBoardFlag()) return p
        val state = if (mp.u8(MP_OHM_LEFT + 0xc) != 0) -1 else mp.u8(MP_OHM_RIGHT + 0xc)
        if (state == 0) return p
        val fx = MoopadGeometry.keyareaXfactorByPos(mp, roenWidth, p.x)
        val fy = MoopadGeometry.letterareaYfactorByPos(mp, p.y)
        val right = state == 1
        var sv = if (right) fsub(1.0f, fx) else fx
        if (!noClamp) sv = if (fcmplt(sv, 0f)) 0f else if (fcmpgt(sv, 1.0f)) 1.0f else sv
        val pw = nintype.engine.math.LibM.powf(sv, F2_3)
        val a = fmul(fsub(1.0f, fy), F0_33)
        val dy = fmul(nintype.engine.SoftFloat.fadd(fmul(fy, F0_33), a), pw)
        val m = if (fcmpgt(fy, 0f)) fy else 0f
        val dx = fmul(fmul(m, sv), if (right) 0.25f else -0.25f)
        val x = MoopadGeometry.keyareaXposByFactor(mp, roenWidth, nintype.engine.SoftFloat.fadd(fx, dx))
        val y = MoopadGeometry.letterareaYposByFactor(mp, nintype.engine.SoftFloat.fadd(fy, dy))
        return Point2D(x, y)
    }

    /** MooBoard::findKeyByTriechar(int) @009e53f4: the first key of [byTrieChara] entry [tc]
     * (map find, no insertion) with a nonzero chara and flag bit 0x1 clear; else null. */
    fun findKeyByTriechar(tc: Int): Mookey? {
        val l = byTrieChara[tc] ?: return null
        for (k in l) if (k.chara != 0 && k.img.u8(Mookey.FLAGS) and 1 == 0) return k
        return null
    }

    /** MooBoard::findKeyPosByTriechar(unsigned int) @009e57dc (sret): the label position of the first
     * [byTrieChara] key with a nonzero chara and flag bit 0x1 clear; else, for trie char 0x26 (the
     * apostrophe) with apostrophe keys, the first one's label + (keyWidth / 2, 0); else
     * (roenWidth / 2, spacebarTop + keyHeight / 2). */
    fun findKeyPosByTriechar(tc: Int, roenWidth: Int): Point2D {
        byTrieChara[tc]?.let { l -> for (k in l) if (k.chara != 0 && k.img.u8(Mookey.FLAGS) and 1 == 0) return k.labelPos }
        if (tc == 0x26 && apostropheKeys.isNotEmpty())
            return apostropheKeys[0].labelPos + Point2D(fmul(mp.f32(MP_KEY_W), 0.5f), 0f)
        val x = fmul(i2f(roenWidth), 0.5f)
        return Point2D(x, nintype.engine.SoftFloat.fadd(MoopadGeometry.curboardSpacebarTopYpos(mp), fmul(MoopadGeometry.curboardVertlen(mp), 0.5f)))
    }

    /** MooBoard::getBiDirIndex(std::string const&) @009e0f80: getBiDirIndex(s[0], s[1]) of the
     * string's first two bytes, unsigned (it asserts length 2 and reads them anyway). */
    fun getBiDirIndex(s: ByteArray): BiDirIndexes = getBiDirIndex(s[0].toInt() and 0xff, s[1].toInt() and 0xff)

    /** MooBoard::isActive() @009e08b4: Xnl::val() of the Xnl at 0xf5c, i.e. the raw byte 0xf68
     * (callers may compare it with 1, so it is not normalised). */
    fun isActive(): Int = img.u8(ACTIVE_XNL + 0xc)
}
