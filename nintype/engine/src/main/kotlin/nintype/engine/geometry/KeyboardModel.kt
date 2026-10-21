package nintype.engine.geometry

import nintype.engine.Bits
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.Values
import java.util.TreeMap

/**
 * The keyboard the decoder measures touches against: a [MooBoard] (rows, keys, tile grid,
 * unicode index), the Moopad layout scalars it needs ([mp]) and the touch-to-board transform.
 *
 * Two ways to build one:
 *  - [nintype]: Nintype's own QWERTY exactly as the booted emulator holds it (raw bytes in
 *    [NintypeLayout], dumped by re/vectors/gen_geometry_board.py), for bit-exact comparison.
 *  - [fromRects]: a host's key labels and rects (SwiftKey), converted into the same fields
 *    Nintype derives from its layout files. See GLUE.md, "KeyboardModel from host rects", for
 *    which fields the decoder reads and how each is filled.
 *
 * Board space is what [Mookey] rects, centres and every decoder distance use. Touches arrive
 * in host pixels; [toBoard] maps them: board = (touch - [touchOrigin]) / [scale] - [origin]
 * (per axis).
 * On the reference setup scale = 2 (1080-px surface, RoenGL width 540), touchOrigin = (0, 0)
 * and origin = (0, 152) (Moopad::screenKeyboardOrigin), as measured through Mootouch::record
 * (touch (300, 500) records (150, 98)). The exact float ops of that path belong to the touch
 * handler port (Moopad::handleTouchEvent); this is the model's statement of it.
 */
class KeyboardModel(
    val board: MooBoard,
    val keys: List<Mookey>,
    val names: List<String>,
    /** RoenGL surface width in board units (getSpacebarStuffXMid, findKeyPosByUnicode). */
    val roenWidth: Int,
    /** Host px per board unit, per axis. */
    val scale: Point2D,
    val origin: Point2D,
    val touchOrigin: Point2D = Point2D(0f, 0f),
) {
    val mp: MemImage get() = board.mp

    fun toBoard(touchX: Float, touchY: Float): Point2D =
        Point2D(fdiv(fsub(touchX, touchOrigin.x), scale.x), fdiv(fsub(touchY, touchOrigin.y), scale.y)) - origin

    /** Inverse of [toBoard] (for hosts that draw decoder output, e.g. key centres). */
    fun toHost(p: Point2D): Point2D =
        Point2D(nintype.engine.SoftFloat.fadd(fmul(nintype.engine.SoftFloat.fadd(p.x, origin.x), scale.x), touchOrigin.x),
            nintype.engine.SoftFloat.fadd(fmul(nintype.engine.SoftFloat.fadd(p.y, origin.y), scale.y), touchOrigin.y))

    fun keyFor(chara: Int): Mookey? = board.findKeyByUnicode(chara, 0, roenWidth)

    fun indexOf(k: Mookey?): Int = if (k == null) -1 else keys.indexOf(k)

    companion object {
        /** Nintype's own board as booted in the emulator (1080 x 432 surface, 420 ppi). */
        fun nintype(): KeyboardModel {
            val L = NintypeLayout
            val keys = L.KEYS.mapIndexed { i, spans ->
                val img = MemImage("k$i", Mookey.SIZE)
                for ((j, s) in spans.withIndex()) img.load(L.KEY_SPANS[j][0], Values.parseBytes(s))
                Mookey(img).also { k ->
                    val st = L.KEY_STAIRS[i]
                    k.stairs = (st.indices step 2).map { Point2D(Bits.bitsToFloat(st[it]), Bits.bitsToFloat(st[it + 1])) }
                }
            }
            for ((i, k) in keys.withIndex()) k.name = L.KEY_NAMES[i]
            val rowImgs = L.ROWS.mapIndexed { r, hex ->
                val img = MemImage("row$r", Moorow.SIZE)
                img.load(0, Values.parseBytes(hex))
                val rk = keys.filterIndexed { i, _ -> L.KEY_ROW[i] == r }
                for (k in rk) k.img.refs[Mookey.OWNER_ROW] = img
                Moorow(img, rk)
            }
            val boardImg = MemImage("board", 0x10c0)
            for ((off, hex) in L.BOARD_SPANS) boardImg.load(off, Values.parseBytes(hex))
            val mp = MemImage("mp", 0x1f4d0)
            for ((off, hex) in L.MOOPAD_FIELDS) mp.load(off, Values.parseBytes(hex))
            val named = HashMap<Int, Mookey?>()
            for ((off, ki) in L.NAMED) {
                named[off] = keys[ki]
                boardImg.refs[off] = keys[ki].img
            }
            mp.refs[MoopadGeometry.CUR_BOARD] = boardImg
            mp.refs[MoopadGeometry.ALPHA_BOARD] = boardImg
            boardImg.refs[MooBoard.MOOPAD] = mp
            for (k in keys) k.img.refs[Mookey.OWNER] = boardImg
            val tiles = TreeMap<TileIndex, List<Mookey>>()
            for (t in L.TILES) tiles[TileIndex(t[0], t[1])] = (2 until t.size).map { keys[t[it]] }
            val uni = TreeMap<Int, List<Mookey>>()
            for (u in L.BY_UNICODE) uni[u[0]] = (1 until u.size).map { keys[u[it]] }
            val tri = TreeMap<Int, MutableList<Mookey>>()
            for (u in L.BY_TRIECHARA) tri[u[0]] = (1 until u.size).map { keys[u[it]] }.toMutableList()
            val board = MooBoard(boardImg, mp, rowImgs, tiles, uni, L.APOSTROPHE_KEYS.map { keys[it] }, named, tri)
            return KeyboardModel(board, keys, L.KEY_NAMES.toList(), L.ROEN_WIDTH, Point2D(2f, 2f), Point2D(0f, 152f))
        }

        /**
         * A board from host key rects (pixels, any origin) for a host keyboard such as SwiftKey.
         * One host key pitch maps to [PITCH] (54) board units on each axis (x by [pitchXPx], y by
         * [pitchYPx]), so every key is the 54 x 54 square Nintype's unit-based thresholds were
         * tuned on. Board (0, 0) is the top-left of the host keys' bounding box. Keys are
         * grouped into rows by their top edge (within half a pitch), left to right.
         *
         * The rects (rounded to Nintype's integer rects) take the place of Moorow::syncOrigKeyXes'
         * worth layout; everything else runs Nintype's own build path ([BoardBuild], each step
         * vector-verified against the binary): key type flags, the live-key pass of
         * Moorow::syncXesAndSplits (prev / next links, duplicate-index flags, row x extent),
         * Mookey::syncExpansions + syncBackerDimensions (touch ranges, label position, stair
         * limit), MooBoard::syncNeighbors, syncNamedKeys, syncTrieCharKeys and the
         * initionFinisher tail (tile grid, coverage clamps). Centre = ((x0 + x1) / 2,
         * (y0 + y1) / 2) as syncOrigKeyXes computes it. See GLUE.md "The mapping SwiftKey must apply".
         */
        fun fromRects(hostKeys: List<HostKey>, pitchXPx: Float, pitchYPx: Float = pitchXPx): KeyboardModel {
            require(hostKeys.isNotEmpty())
            val ux = PITCH / pitchXPx
            val uy = PITCH / pitchYPx
            val left0 = hostKeys.minOf { it.left }
            val top0 = hostKeys.minOf { it.top }
            fun bx(v: Float) = Math.round((v - left0) * ux)
            fun by(v: Float) = Math.round((v - top0) * uy)
            val rowsByTop = hostKeys.groupBy { Math.round((it.top - top0) * uy / PITCH) }.toSortedMap()
            val boardW = hostKeys.maxOf { bx(it.right) }
            val boardImg = MemImage("board", 0x10c0)
            val mp = MemImage("mp", 0x1f800)
            mp.setF32(MoopadGeometry.KEY_W, PITCH); mp.setF32(MoopadGeometry.KEY_H, PITCH)
            mp.setF32(0x1f4a8, PITCH); mp.setF32(0x1f4ac, PITCH)       // unit, hybridUnit
            mp.setF32(0x1f49c, java.lang.Float.intBitsToFloat(0x3fa66666)) // xUnitForgiven 1.3
            mp.setI32(MoopadGeometry.TOPBAR_H, 0)
            mp.setI32(MoopadGeometry.KEYAREA_L, 0); mp.setI32(MoopadGeometry.KEYAREA_R, 0)
            mp.setI32(0x9c58, 0)                                         // no split keyboard
            mp.setU8(BoardBuild.XNL_STOCK + 0xc, 0); mp.setU8(0xa0a8, 0)
            mp.refs[MoopadGeometry.CUR_BOARD] = boardImg
            mp.refs[MoopadGeometry.ALPHA_BOARD] = boardImg
            boardImg.refs[MooBoard.MOOPAD] = mp
            boardImg.setU8(MooBoard.ACTIVE_XNL + 0xc, 1)
            boardImg.setU8(0x7a, 1)                                         // traversals on
            boardImg.setU8(0x7c, 0)                                         // no topbar row
            // the alphabet board's byte 0x1c, as on Nintype's own board (BOARD_SPANS): Mootouch's
            // setKey records the touch's key in Mootouch+0x38 only on a board with it, and the
            // literal alternative (getExplicitResult) needs that key on every touch
            boardImg.setU8(0x1c, 1)
            val keys = ArrayList<Mookey>()
            val names = ArrayList<String>()
            val rowImgs = ArrayList<MemImage>()
            val rowKeyLists = ArrayList<List<Mookey>>()
            val mid = f2iz(MoopadGeometry.keyareaXposByFactor(mp, boardW, 0.5f))
            for ((ri, entry) in rowsByTop.entries.withIndex()) {
                val rk = entry.value.sortedBy { it.left }
                val rowKeys = ArrayList<Mookey>()
                for ((j, hk) in rk.withIndex()) {
                    val img = MemImage("k${keys.size}", Mookey.SIZE)
                    BoardBuild.construct(img)                                  // Mookey::Mookey() defaults
                    val x0 = bx(hk.left); val x1 = bx(hk.right)
                    val y0 = by(hk.top); val y1 = by(hk.bottom)
                    img.setI32(Mookey.FLAGS, 0)
                    img.setI32(Mookey.CHARA, hk.chara)
                    img.setI32(Mookey.NONBASIC, hk.nonbasic)
                    img.setI32(Mookey.TRIE_CHARA, 0); img.setI32(Mookey.ALT_TRIE_CHARA, 0)
                    img.setI32(0x48, j); img.setI32(0xc94, j); img.setI32(0xc98, rk.size)
                    img.setI32(Mookey.ROW, ri)
                    img.setI32(Mookey.X0, x0); img.setI32(Mookey.X1, x1)
                    img.setI32(Mookey.Y0, y0); img.setI32(Mookey.Y1, y1)
                    val h = (x1 - x0) / 2
                    img.setI32(0xcb8, x0 - h); img.setI32(0xcbc, x1 + h)
                    img.setF32(Mookey.CENTRE, fmul(i2f(x1 + x0), 0.5f))
                    img.setF32(Mookey.CENTRE + 4, fmul(i2f(y1 + y0), 0.5f))
                    img.setU8(Mookey.OHM, 0)
                    img.setU8(BoardBuild.SLIDABLE, if (hk.slidable) 1 else 0)
                    val k = Mookey(img)
                    k.name = hk.label
                    k.typeName = hk.typeName
                    BoardBuild.syncKeyTypeFlags(k)
                    keys += k; names += hk.label; rowKeys += k
                }
                val rimg = MemImage("row$ri", Moorow.SIZE)
                rimg.setI32(Moorow.Y0, rowKeys.minOf { it.y0 })
                rimg.setI32(Moorow.Y1, rowKeys.maxOf { it.y1 })
                rimg.setI32(0x2c, ri)
                // MooBoard::newRow defaults
                rimg.setI32(Moorow.CLAMP_X0, 0); rimg.setI32(Moorow.CLAMP_X1, -10)
                rimg.setI32(0x50, 9999); rimg.setI32(0x54, -9999); rimg.setU8(0x45, 0)
                BoardBuild.syncLiveKeys(rimg, rowKeys, boardImg, mid)
                for (k in rowKeys) k.img.refs[Mookey.OWNER_ROW] = rimg
                rowImgs += rimg
                rowKeyLists += rowKeys
            }
            for (k in keys) {
                BoardBuild.syncExpansions(k, boardImg, mp, boardW) { 0 }
                BoardBuild.syncBackerDimensions(k, boardImg, mp, boardW) { 0 }
            }
            boardImg.setI32(MooBoard.HEIGHT, rowImgs.maxOf { it.i32(Moorow.Y1) })
            BoardBuild.syncNeighbors(rowKeyLists)
            val byName = TreeMap<String, Mookey>(BoardBuild.STRING_ORDER)
            val named = HashMap<Int, Mookey?>()
            BoardBuild.syncNamedKeys(boardImg, rowKeyLists, mp, boardW, byName, named)
            for ((off, k) in named) boardImg.refs[off] = k?.img
            val tri = TreeMap<Int, MutableList<Mookey>>()
            val uni = TreeMap<Int, MutableList<Mookey>>()
            val aps = ArrayList<Mookey>()
            BoardBuild.syncTrieCharKeys(rowKeyLists, tri, uni, aps)
            val tiles = TreeMap<TileIndex, BoardBuild.Maplet>()
            BoardBuild.initionFinisherTail(boardImg, rowImgs, rowKeyLists, mp, tiles, ArrayList())
            val rowList = rowImgs.indices.map { Moorow(rowImgs[it], rowKeyLists[it]) }
            val board = MooBoard(boardImg, mp, rowList, TreeMap(tiles.mapValues { it.value.keys.toList() }),
                TreeMap(uni.mapValues { it.value.toList() }), aps, named, tri)
            return KeyboardModel(board, keys, names, boardW, Point2D(1f / ux, 1f / uy), Point2D(0f, 0f), Point2D(left0, top0))
        }

        const val PITCH = 54f
        /** How far past the board edge the outermost keys still catch touches (Nintype: q's
         * touch range starts at -60, p's ends at 600, i.e. a key and then some). */
        const val EDGE_REACH = 60
    }
}

/** One host key: label, the unicode it types (0 for function keys) and its rect in host px. */
data class HostKey(val label: String, val chara: Int, val left: Float, val top: Float, val right: Float, val bottom: Float,
                   /** Nintype's non-basic chara (0x9c): its shift key carries 'z' here, so slides over it
                    * count as z. A host with a plain shift leaves 0. */
                   val nonbasic: Int = 0,
                   /** Nintype's key type name (Mookey 0x5c), which MooBoard::syncNamedKeys indexes:
                    * "space", "caps" (shift), "backspace", "ret", "123", ... */
                   val typeName: String = if (chara == 0x20) "space" else "",
                   /** Mookey 0xd49: a key a swipe can pass over (Nintype: letters, apostrophe and its
                    * z-carrying shift); the coverage clamp spans these keys. */
                   val slidable: Boolean = (chara != 0 || nonbasic != 0) && chara != 0x20)
