package nintype.engine.geometry

import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage
import java.util.TreeMap

/**
 * The board-build derivations: what Nintype computes from a layout's key rects once the keys
 * exist (MooBoard::initionFinisher and the sync* helpers it runs). SwiftKey supplies the rects,
 * so [KeyboardModel.fromRects] runs these on them. Each function mirrors one binary function;
 * containers are Kotlin collections (vector -> ArrayList in order, std::map -> TreeMap), the
 * scalar fields stay in the keys' and rows' byte images at the binary's offsets.
 */
object BoardBuild {
    // --- Mookey ---------------------------------------------------------------------------

    /** Emkey::Mookey::Mookey() @009b8bc8 on [img]: the ctor's address-independent defaults
     * ([MookeyCtor], generated from the binary; e.g. 0x54 kind = -1, 0xa8 worth = 10, all rects
     * 0). Pointer fields (vtable aside) and the UI text components' heap pointers are left out. */
    fun construct(img: MemImage) {
        for ((off, hex) in MookeyCtor.RUNS) img.load(off, nintype.engine.vectors.Values.parseBytes(hex))
    }

    /** Mookey::syncKeyTypeFlags() @009beb9c: type "space" -> flag 0x400000; name "<" ->
     * 0x200000, "^" -> 0x1000000, "one"/"two"/"three" -> 0x800000. Only sets bits. */
    fun syncKeyTypeFlags(k: Mookey) {
        var f = k.img.i32(Mookey.FLAGS)
        if (k.typeName == "space") f = f or 0x400000
        if (k.name == "<") f = f or 0x200000
        if (k.name == "^") f = f or 0x1000000
        if (k.isRechooser()) f = f or 0x800000
        k.img.setI32(Mookey.FLAGS, f)
    }

    /**
     * Mookey::syncExpansions() @009bf948. Touch x range = visual range; a letter key whose
     * previous (next) key is missing reaches 60 units past its edge, one whose neighbour is not
     * a letter reaches to that neighbour's edge (prev.x1 / next.x0). Flag byte 0x52 bit
     * 0 doubles the key downwards (y1 += h, centre y += h / 2) and resyncs the backer; with the
     * Moopad Xnl at 0x1f79c == 1 the rechooser keys grow 100 up.
     */
    fun syncExpansions(k: Mookey, board: MemImage, mp: MemImage, roenWidth: Int, topbarPx: () -> Int) {
        val img = k.img
        img.setI32(Mookey.TX0, img.i32(Mookey.X0))
        img.setI32(Mookey.TX1, img.i32(Mookey.X1))
        if (Geometry.shingIswalpha(k.chara)) {
            val p = k.prev
            if (p == null) img.setI32(Mookey.TX0, img.i32(Mookey.TX0) - 60)
            else if (!Geometry.shingIswalpha(p.chara)) img.setI32(Mookey.TX0, p.x1)
        }
        if (Geometry.shingIswalpha(k.chara)) {
            val n = k.next
            if (n == null) img.setI32(Mookey.TX1, img.i32(Mookey.TX1) + 60)
            else if (!Geometry.shingIswalpha(n.chara)) img.setI32(Mookey.TX1, n.x0)
        }
        if (img.u8(0x52) and 1 != 0) {
            val d = img.i32(Mookey.Y1) - img.i32(Mookey.Y0)
            img.setI32(Mookey.Y1, d + img.i32(Mookey.Y1))
            img.setF32(Mookey.CENTRE + 4, fadd(img.f32(Mookey.CENTRE + 4), i2f(d / 2)))
            syncBackerDimensions(k, board, mp, roenWidth, topbarPx)
        }
        if (mp.u8(XNL_STOCK + 0xc) != 1) return
        if (!k.isRechooser()) return
        img.setI32(Mookey.Y0, img.i32(Mookey.Y0) - 100)
        syncBackerDimensions(k, board, mp, roenWidth, topbarPx)
    }

    /**
     * Mookey::syncBackerDimensions() @009bfaf4 (inventory "ui", but it fills decoder-read
     * fields: 0xcc4 stair limit and 0xcd0 label position). Backer rect = (y0, y1, x0, x1) at
     * 0xcc0..0xccc; label = (centre x, (y0' + y1) / 2). For a visible key (0x52 bit 3 clear):
     * on a topbar board (board+0x7c) row 0 starts topbarPx above y1; with Moopad 0xa0a8 set and
     * no one-handed mode, the backer is capped at 1.4 (1.5 on a roen width <= 320) key widths.
     */
    fun syncBackerDimensions(k: Mookey, board: MemImage, mp: MemImage, roenWidth: Int, topbarPx: () -> Int) {
        val img = k.img
        var a = img.i32(Mookey.Y0)
        val b = img.i32(Mookey.Y1)
        if (img.u8(0x52) and 8 == 0) {
            if (board.u8(0x7c) != 0 && img.i32(Mookey.ROW) == 0) a = img.i32(Mookey.Y1) - topbarPx()
            var f = F1_4
            if (roenWidth < 0x141) f = 1.5f
            if (img.u8(Mookey.OHM) == 0 && mp.u8(0xa0a8) != 0) {
                val h = i2f(b - a)
                val lim = fmul(f, mp.f32(MoopadGeometry.KEY_W))
                if (!fcmple(h, lim) && !fcmpun(h, lim)) a = b - f2iz(lim)
            }
        }
        img.setI32(0xcc0, a)
        img.setI32(0xcc4, b)
        img.setI32(0xcc8, img.i32(Mookey.X0))
        img.setI32(0xccc, img.i32(Mookey.X1))
        img.setI32(Mookey.LABEL_POS, img.i32(Mookey.CENTRE))
        img.setF32(Mookey.LABEL_POS + 4, fmul(i2f(a + b), 0.5f))
    }

    /** Mookey::finalizeNeighbors() @009bb400: 0x28 = 0x1c + self; 0x34 = the 0x1c neighbours one
     * row further down (row index + 1), in order. */
    fun finalizeNeighbors(k: Mookey) {
        k.neighborsAndSelf.clear()
        k.neighborsAndSelf.addAll(k.neighbors)
        k.neighborsAndSelf.add(k)
        k.neighborsBelow.clear()
        val r = k.rowIndex + 1
        for (n in k.neighbors) if (n.rowIndex == r) k.neighborsBelow.add(n)
    }

    // --- Moorow ---------------------------------------------------------------------------

    /** Moorow::gatherHorizontalNeighbors() @009cc9a8: each pair of adjacent live keys become
     * neighbours (the right one gets the left one first). */
    fun gatherHorizontalNeighbors(keys: List<Mookey>) {
        for (i in 1 until keys.size) {
            keys[i].neighbors.add(keys[i - 1])
            keys[i - 1].neighbors.add(keys[i])
        }
    }

    /** Moorow::syncVerticalNeighbors(Moorow& above) @009ccbbc: a merge walk over both rows'
     * live keys; keys whose x ranges overlap (inclusive) become neighbours (this row's key gets
     * the other first). Advance the other row while its key ends at or before this one's. */
    fun syncVerticalNeighbors(keys: List<Mookey>, above: List<Mookey>) {
        if (keys.isEmpty()) return
        var i = 0
        var j = 0
        while (j < above.size) {
            val a = keys[i]
            val b = above[j]
            if (a.x0 <= b.x1 && b.x0 <= a.x1) {
                a.neighbors.add(b)
                b.neighbors.add(a)
            }
            if (b.x1 <= a.x1) j++
            else if (++i >= keys.size) return
        }
    }

    /**
     * Moorow::syncOrigKeyXes() @009ce5d8 over the row's template keys (0x04): visible keys
     * (0x52 bit 3 clear) get index 0x48 and worth centre 0x4c; then, when the row's total worth
     * (pads 0x38 + 0x3c + key worths 0xa8) is nonzero, x0/x1 = integer worth positions scaled
     * to the key-area width, y from the row (minus/plus the key's 0xc8c/0xc90 extras), the half-
     * key extended range 0xcb8/0xcbc, centre 0xd4c, the per-row index 0xc94 / count 0xc98 and
     * row index 0xc9c. [splitAllowed] = MooBoard::splitIsAllowed() (host: false).
     */
    fun syncOrigKeyXes(row: MemImage, tpl: List<Mookey>, mp: MemImage, roenWidth: Int, splitAllowed: Boolean) {
        var sum = 0
        var idx = 0
        for (k in tpl) {
            val im = k.img
            if (im.u8(0x52) and 8 == 0) {
                im.setI32(0x48, idx++)
                val w = im.i32(WORTH)
                im.setI32(0x4c, sum + w / 2)
                sum += w
            }
        }
        val total = row.i32(0x38) + sum + row.i32(0x3c)
        if (total == 0) return
        val rowWorth = row.i32(0x40)
        var w = roenWidth
        if (row.u8(0x44) == 0) w = roenWidth - mp.i32(0x9c58)
        if (!splitAllowed) w = roenWidth
        if (tpl.isEmpty()) return
        val left = mp.i32(MoopadGeometry.KEYAREA_L)
        w = w - left - mp.i32(MoopadGeometry.KEYAREA_R)
        var acc = row.i32(0x38) + (rowWorth - total) / 2
        var c = 0
        for (k in tpl) {
            val im = k.img
            im.setI32(0xc98, tpl.size)
            if (im.u8(0x52) and 8 != 0) continue
            im.setI32(0xc94, c)
            im.setI32(Mookey.ROW, row.i32(0x2c))
            val x0 = acc * w / rowWorth + left
            acc += im.i32(WORTH)
            val x1 = acc * w / rowWorth + left
            im.setI32(Mookey.X0, x0)
            im.setI32(Mookey.X1, x1)
            im.setI32(Mookey.Y0, row.i32(Moorow.Y0) - im.i32(0xc8c))
            im.setI32(Mookey.Y1, im.i32(0xc90) + row.i32(Moorow.Y1))
            val h = (x1 - x0) / 2
            im.setI32(0xcb8, x0 - h)
            im.setI32(0xcbc, x1 + h)
            im.setF32(Mookey.CENTRE, fmul(i2f(x1 + x0), 0.5f))
            im.setF32(Mookey.CENTRE + 4, fmul(i2f(row.i32(Moorow.Y1) + row.i32(Moorow.Y0)), 0.5f))
            c++
        }
    }

    /**
     * Moorow::syncXesAndSplits() @009cd4ec, the no-split path (split width = Moopad 0x9c58,
     * zeroed when the row opts out (byte 0x44) or [splitAllowed] is false; a split keyboard is a
     * Nintype setting the SwiftKey build leaves off, so a nonzero split throws). syncOrigKeyXes,
     * row 0x34 = split, live keys (0x10) = copies of the template keys (0x04), then [syncLiveKeys].
     * [live] is updated in place (copy-assignment keeps its elements, as std::vector::operator=
     * does when the sizes match).
     */
    fun syncXesAndSplits(row: MemImage, tpl: List<Mookey>, live: MutableList<Mookey>, board: MemImage, mp: MemImage,
                         roenWidth: Int, splitAllowed: Boolean) {
        syncOrigKeyXes(row, tpl, mp, roenWidth, splitAllowed)
        var split = mp.i32(0x9c58)
        if (row.u8(0x44) != 0) split = 0
        if (!splitAllowed) split = 0
        val mid = f2iz(MoopadGeometry.keyareaXposByFactor(mp, roenWidth, 0.5f))
        row.setI32(0x34, split)
        if (split != 0) throw UnsupportedOperationException("Moorow::syncXesAndSplits: split keyboard (gated setting)")
        for (i in tpl.indices) {
            if (i < live.size) copyKey(tpl[i], live[i])
            else live.add(Mookey(MemImage("${tpl[i].img.name}'", Mookey.SIZE)).also { copyKey(tpl[i], it) })
        }
        while (live.size > tpl.size) live.removeAt(live.size - 1)
        syncLiveKeys(row, live, board, mid)
    }

    /** The tail of Moorow::syncXesAndSplits over the live keys: owner board (0xc); keys sharing
     * a visible index (0x48) with an earlier key and lacking flag 0x80000 settle which of the two
     * gets flag 0x1 by distance of centre x from [mid] (the new key when the earlier one is
     * strictly farther, else the earlier one); row x extent 0x50 (min x0) / 0x54 (max x1); prev / next links between visible keys
     * (0x52 bit 3 clear). The row's first key keeps its old prev and the last its old next. */
    fun syncLiveKeys(row: MemImage, live: List<Mookey>, board: MemImage, mid: Int) {
        val fmid = i2f(mid)
        val seen = TreeMap<Int, Mookey>()
        for (k in live) {
            k.img.refs[Mookey.OWNER] = board
            val idx = k.img.i32(0x48)
            val o = seen[idx]
            if (o == null) seen[idx] = k
            else if (k.flags and 0x80000 == 0) {
                val a = absf(nintype.engine.SoftFloat.fsub(k.img.f32(Mookey.CENTRE), fmid))
                val b = absf(nintype.engine.SoftFloat.fsub(o.img.f32(Mookey.CENTRE), fmid))
                if (!fcmple(b, a) && !fcmpun(b, a)) k.img.setI32(Mookey.FLAGS, k.flags or 1)
                else o.img.setI32(Mookey.FLAGS, o.flags or 1)
            }
            if (k.x0 < row.i32(0x50)) row.setI32(0x50, k.x0)
            if (row.i32(0x54) < k.x1) row.setI32(0x54, k.x1)
        }
        var prev: Mookey? = null
        for (k in live) {
            if (k.img.u8(0x52) and 8 != 0) continue
            if (prev != null) { prev.next = k; k.prev = prev }
            prev = k
        }
    }

    /** Mookey::operator= as far as the model goes: every byte the source image holds, its
     * names, links and neighbour lists. */
    fun copyKey(src: Mookey, dst: Mookey) {
        val s = src.img
        for (i in 0 until Mookey.SIZE) if (s.isKnown(i)) dst.img.load(i, byteArrayOf(s.byteAt(i)))
        for ((off, r) in s.refs) if (off != Mookey.OWNER) dst.img.refs[off] = r
        dst.name = src.name
        dst.typeName = src.typeName
        dst.prev = src.prev
        dst.next = src.next
        for ((a, b) in listOf(src.neighbors to dst.neighbors, src.neighborsAndSelf to dst.neighborsAndSelf,
                src.neighborsBelow to dst.neighborsBelow)) { b.clear(); b.addAll(a) }
    }

    private fun absf(f: Float) = java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(f) and 0x7fffffff)

    // --- MooBoard ---------------------------------------------------------------------------

    /** MooBoard::syncNeighbors() @009ed2e4 over the rows' live keys: clear 0x1c / 0x28, gather
     * each row's horizontal neighbours and its vertical ones with the row above, then
     * finalizeNeighbors every key. */
    fun syncNeighbors(rows: List<List<Mookey>>) {
        if (rows.isEmpty()) return
        for (r in rows) for (k in r) { k.neighbors.clear(); k.neighborsAndSelf.clear() }
        var above: List<Mookey>? = null
        for (r in rows) {
            gatherHorizontalNeighbors(r)
            if (above != null) syncVerticalNeighbors(r, above)
            above = r
        }
        for (r in rows) for (k in r) finalizeNeighbors(k)
    }

    /** MookeyMaplet: its TileIndex and the keys registered in the tile. */
    class Maplet(val tile: TileIndex, val keys: MutableList<Mookey> = ArrayList())

    /** MooBoard::maplet(TileIndex const&) @009ec52c: the tile's maplet, inserted on first use. */
    fun maplet(tiles: TreeMap<TileIndex, Maplet>, t: TileIndex): Maplet = tiles.getOrPut(t) { Maplet(t) }

    private fun tileOf(mp: MemImage, x: Float, y: Float) = TileIndex(
        f2iz(Geometry.floorf(fdiv(x, mp.f32(MoopadGeometry.KEY_W)))),
        f2iz(Geometry.floorf(fdiv(y, mp.f32(MoopadGeometry.KEY_H)))))

    /**
     * MooBoard::indexMookeyToMaplet(Mookey*) @009ec6d4: register the key in every tile of its
     * touch rect grown by one unit ([tx0 - 1, tx1 + 1] x [y0 - 1, y1 + 1]); in one-handed mode
     * (non-rect shape) the box spanned by the corners A - (1,1), D + (1,-1), C + (1,1),
     * B + (-1,1): x from A to C, y from D to B.
     */
    fun indexMookeyToMaplet(tiles: TreeMap<TileIndex, Maplet>, mp: MemImage, k: Mookey) {
        val im = k.img
        val x0: Int; val x1: Int; val y0: Int; val y1: Int
        if (im.u8(Mookey.OHM) == 0 || im.u8(0x52) and 0x40 != 0) {
            val lo = tileOf(mp, i2f(im.i32(Mookey.TX0) - 1), i2f(im.i32(Mookey.Y0) - 1))
            val hi = tileOf(mp, i2f(im.i32(Mookey.TX1) + 1), i2f(im.i32(Mookey.Y1) + 1))
            x0 = lo.x; y0 = lo.y; x1 = hi.x; y1 = hi.y
        } else {
            fun c(off: Int, dx: Float, dy: Float) = tileOf(mp, fadd(im.f32(off), dx), fadd(im.f32(off + 4), dy))
            x0 = c(Mookey.CORNER_A, -1f, -1f).x
            y0 = c(Mookey.CORNER_D, 1f, -1f).y
            x1 = c(Mookey.CORNER_C, 1f, 1f).x
            y1 = c(Mookey.CORNER_B, -1f, 1f).y
        }
        if (x0 > x1) return
        var x = x0
        while (true) {
            if (y0 <= y1) {
                var y = y0
                while (true) {
                    maplet(tiles, TileIndex(x, y)).keys.add(k)
                    if (y >= y1) break
                    y++
                }
            }
            if (x >= x1) break
            x++
        }
    }

    /**
     * MooBoard::syncTrieCharKeys() @009e7dd8. For every live key (row order) not typing a space:
     * cache its trie chara (0xa0, from non-basic chara, else chara, else '?') and index it under
     * it in [byTrie] (0x1010); index it in [byUni] (0x1028) under its chara, under
     * towupper(towupper(chara)) when that differs, and under its capitalised jamo (Hangul).
     * Every key with a non-basic chara other than 0 / ' ' is also indexed under it; keys with
     * flag 0x4 join [apostrophes] (0x1084). Containers are appended to, not cleared.
     */
    fun syncTrieCharKeys(rows: List<List<Mookey>>, byTrie: TreeMap<Int, MutableList<Mookey>>,
                         byUni: TreeMap<Int, MutableList<Mookey>>, apostrophes: MutableList<Mookey>) {
        for (r in rows) for (k in r) {
            val im = k.img
            val c = im.i32(Mookey.CHARA)
            if (c != 0x20) {
                var t = im.i32(Mookey.TRIE_CHARA)
                if (t == 0) {
                    var src = im.i32(Mookey.NONBASIC)
                    if (src == 0) { src = c; if (c == 0) src = 0x3f }
                    t = Geometry.getTrieChara(src)
                    im.setI32(Mookey.TRIE_CHARA, t)
                }
                byTrie.getOrPut(t) { ArrayList() }.add(k)
                byUni.getOrPut(c) { ArrayList() }.add(k)
                val u = nintype.engine.trie.Caps.shingTowupper(c)
                if (u != c) byUni.getOrPut(nintype.engine.trie.Caps.shingTowupper(u)) { ArrayList() }.add(k)
                if (nintype.engine.trie.Hangul.isHangul(c)) {
                    val d = getCapitalizedDamo(c)
                    if (d != c) byUni.getOrPut(d) { ArrayList() }.add(k)
                }
            }
            val nb = im.i32(Mookey.NONBASIC)
            if (nb or 0x20 != 0x20) byUni.getOrPut(nb) { ArrayList() }.add(k)
            if (im.u8(Mookey.FLAGS) and 4 != 0) apostrophes.add(k)
        }
    }

    /** Hangul::get_capitalized_damo(unsigned int) @0141fc68: the tense (doubled) form of a
     * jamo: ㄱ ㄷ ㅂ ㅅ ㅈ (U+1100/1103/1107/1109/110C) -> U+1101/1104/1108/110A/110D, ㅐ ㅔ
     * (U+1162/1166) -> U+1164/1168; anything else unchanged (signed compares, as compiled). */
    fun getCapitalizedDamo(c: Int): Int {
        if (c > 0x1161) {
            if (c == 0x1162) return 0x1164
            if (c == 0x1166) return 0x1168
            return c
        }
        if (c > 0x1102) {
            return when (c - 0x1103) {
                0 -> 0x1104
                4 -> 0x1108
                6 -> 0x110a
                9 -> 0x110d
                else -> c
            }
        }
        return if (c == 0x1100) 0x1101 else c
    }

    /** std::less<std::string>: memcmp over the shorter length, then the length. */
    val STRING_ORDER: Comparator<String> = Comparator { a, b ->
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return@Comparator d
        }
        x.size - y.size
    }

    /** The named-key slots syncNamedKeys fills, by board offset, and the type name looked up. */
    val NAMED_SLOTS = listOf(0xfa8 to "caps", 0xfac to "space", 0xfb0 to "langer", 0xfb8 to "ret", 0xfbc to "maindot",
        0xfc0 to "choicer", 0xfc4 to "backspace", 0xfd4 to "splitter", 0xfd8 to "peckmodekey", 0xfdc to "leftcursorer",
        0xfc8 to "one", 0xfcc to "two", 0xfd0 to "choicer")

    /**
     * MooBoard::syncNamedKeys() @009e882c: rebuild [byName] (0xf90, type name -> last key with
     * it), point 0xfb4 at the last key named "123" or "abc", fill the [NAMED_SLOTS] from
     * [byName] (0xfd0 is "choicer" again, not "three"), then the suggestion-key x range
     * 0xfe0..0xfe4 (one.x0 .. choicer.x1, else the key
     * area's 0.1 and 0.9 points) and byte 0x7b = has a "choicer" key.
     */
    fun syncNamedKeys(board: MemImage, rows: List<List<Mookey>>, mp: MemImage, roenWidth: Int,
                      byName: TreeMap<String, Mookey>, named: MutableMap<Int, Mookey?>) {
        byName.clear()
        named[0xfb4] = null
        for (r in rows) for (k in r) {
            if (k.typeName != "") byName[k.typeName] = k
            if (k.name == "123" || k.name == "abc") named[0xfb4] = k
        }
        for ((off, n) in NAMED_SLOTS) named[off] = byName[n]
        val one = named[0xfc8]
        val last = named[0xfd0]
        if (one == null || last == null) {
            board.setI32(0xfe0, f2iz(MoopadGeometry.keyareaXposByFactor(mp, roenWidth, F0_1)))
            board.setI32(0xfe4, f2iz(MoopadGeometry.keyareaXposByFactor(mp, roenWidth, F0_9)))
        } else {
            board.setI32(0xfe0, one.x0)
            board.setI32(0xfe4, last.x1)
        }
        board.setU8(0x7b, if (named[0xfc0] != null) 1 else 0)
    }

    /**
     * The tail of MooBoard::initionFinisher() @009e28e4 after its four sync* calls: keys with
     * flag byte 0x51 bit 0x20 join [flagged] (board 0x1000); every live key is indexed into
     * [tiles]; each row's coverage x clamp (0x48 / 0x4c) runs from its first to its last
     * slidable key's (x0 + x1) / 2 (Mookey byte 0xd49) and marks the row (byte 0x45); the board's
     * y clamp (0x54 / 0x58) runs from the first to the last marked row's (y0 + y1) / 2. Not
     * verified on its own (initionFinisher also runs syncDimensions); KeyboardModelTest checks
     * its clamps against Nintype's board.
     */
    fun initionFinisherTail(board: MemImage, rowImgs: List<MemImage>, rows: List<List<Mookey>>, mp: MemImage,
                            tiles: TreeMap<TileIndex, Maplet>, flagged: MutableList<Mookey>) {
        if (rows.isEmpty()) return
        for (r in rows) for (k in r) {
            if (k.img.u8(0x51) and 0x20 != 0) flagged.add(k)
            indexMookeyToMaplet(tiles, mp, k)
        }
        var any = false
        for ((ri, r) in rows.withIndex()) {
            val row = rowImgs[ri]
            var first = true
            for (k in r) {
                if (k.img.u8(SLIDABLE) == 0) continue
                val mx = (k.x1 + k.x0) / 2
                if (first) row.setI32(Moorow.CLAMP_X0, mx)
                row.setI32(Moorow.CLAMP_X1, mx)
                row.setU8(0x45, 1)
                first = false
            }
            if (row.u8(0x45) != 0) {
                val my = (row.i32(Moorow.Y1) + row.i32(Moorow.Y0)) / 2
                board.setI32(MooBoard.CLAMP_Y1, my)
                if (!any) board.setI32(MooBoard.CLAMP_Y0, my)
                any = true
            }
        }
    }

    /** Mookey byte 0xd49: slidable (Mookey::markSlidable); the coverage clamp spans these keys. */
    const val SLIDABLE = 0xd49
    /** Shing::is_ios_emoji(int) @00f35300 (Emkey::is_ios_emoji is a tail call to it): a compare
     * tree over code points, as the [IosEmoji] range table generated from the binary. */
    fun isIosEmoji(c: Int): Boolean {
        val r = IosEmoji.RANGES
        var lo = 0
        var hi = r.size / 2 - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (c < r[2 * m]) hi = m - 1 else if (c > r[2 * m + 1]) lo = m + 1 else return true
        }
        return false
    }

    /** Hangul::is_damo(unsigned int) @014229b0: the jamo bit sets 0x7daed over U+1100..U+1112 and
     * 0x163177 over U+1161..U+1175 (signed compare at U+1160, as compiled). */
    fun isDamo(c: Int): Boolean {
        if (c > 0x1160) {
            val i = c - 0x1161
            return Integer.compareUnsigned(i, 0x14) <= 0 && (0x163177 ushr i) and 1 != 0
        }
        val i = c - 0x1100
        return Integer.compareUnsigned(i, 0x12) <= 0 && (0x7daed ushr i) and 1 != 0
    }

    /** Mookey::assignChara(int) @009ca9cc, the decoder-visible part: chara 0x98, byte 0xcc set
     * (never cleared) for an iOS emoji, byte 0xac = is_damo. The two TextDisplayComponents
     * (0xd0, 0x2c4: label and capital label) are rendering and are not modelled. */
    fun assignChara(k: Mookey, c: Int) {
        k.img.setI32(Mookey.CHARA, c)
        if (isIosEmoji(c)) k.img.setU8(0xcc, 1)
        k.img.setU8(0xac, if (isDamo(c)) 1 else 0)
    }

    /** Moorow::addKey(int chara, int worth) @009ccf30: append a default Mookey to the row's
     * template keys (0x04), assignChara, worth 0xa8, def name 0x60 = the chara as UTF-8 (set
     * after syncKeyTypeFlags runs on the empty name). Returns the new key. */
    fun addKey(tpl: MutableList<Mookey>, name: String, c: Int, worth: Int): Mookey {
        val k = Mookey(MemImage(name, Mookey.SIZE))
        construct(k.img)
        tpl.add(k)
        assignChara(k, c)
        k.img.setI32(WORTH, worth)
        k.name = ""
        syncKeyTypeFlags(k)
        val o = java.io.ByteArrayOutputStream()
        nintype.engine.trie.Utf8.append(o, c)
        k.name = o.toString("UTF-8")
        return k
    }

    /** One-handed mode as Mookey::syncOneHandedMode / MooBoard::performOneHandedModeBottomRow-
     * SpliceArrangement test it: a board OHM byte (0x1c, 0x24..0x26) set and the left Xnl
     * (Moopad 0x9d6c) on or the right one (0x9da0) == 1. */
    fun ohmOn(board: MemImage, mp: MemImage): Boolean =
        (board.u8(0x25) != 0 || board.u8(0x24) != 0 || board.u8(0x1c) != 0 || board.u8(0x26) != 0) &&
            (mp.u8(0x9d6c + 0xc) != 0 || mp.u8(0x9da0 + 0xc) == 1)

    /** Mookey::syncOneHandedMode() @009bfc10 with one-handed mode off (inventory "ui"; the OHM
     * shapes are a Nintype setting the SwiftKey build leaves off, so OHM on throws):
     * syncBackerDimensions, then OHM centre 0xd2c = label position 0xcd0. */
    fun syncOneHandedMode(k: Mookey, board: MemImage, mp: MemImage, roenWidth: Int, topbarPx: () -> Int) {
        syncBackerDimensions(k, board, mp, roenWidth, topbarPx)
        k.img.setI32(Mookey.OHM_CENTRE, k.img.i32(Mookey.LABEL_POS))
        k.img.setI32(Mookey.OHM_CENTRE + 4, k.img.i32(Mookey.LABEL_POS + 4))
        if (ohmOn(board, mp)) throw UnsupportedOperationException("Mookey::syncOneHandedMode: one-handed mode (gated setting)")
    }

    /**
     * MooBoard::syncDimensions() @009ecb8c, one-handed mode off; render arrays (RVA) are not
     * modelled. Row y ranges split the keyboard height (Moopad 0x1f48c, grown by a fifth per
     * extra row count board 0x38 > 1) evenly over the rows (unsigned division), or with board
     * byte 0x53 (number row added) the first and last rows get a 1 / n share and the middle rows
     * share the rest; the last row loses Moopad 0x1f4b8 at the bottom and may start a third of a
     * key lower (settings); rows' 0x24 / 0x28 = range grown by half its height. Per row: type
     * flags and alpha index 0x44 of template keys, syncXesAndSplits. Then syncExpansions and
     * syncOneHandedMode of every live key, board height 0x44 = max row y1, 0x3c = key height,
     * live keys' board-wide index 0x58.
     */
    fun syncDimensions(board: MemImage, rowImgs: List<MemImage>, tpls: List<List<Mookey>>, lives: List<MutableList<Mookey>>,
                       mp: MemImage, roenWidth: Int, splitAllowed: Boolean, topbarPx: () -> Int) {
        board.setI32(MooBoard.HEIGHT, 0)
        var h = mp.i32(0x1f48c)
        var off = 0
        val fifth = h / 5
        val n38 = board.i32(0x38)
        if (n38 > 1 || n38 < 0) { h += (n38 - 1) * fifth; off = -((n38 - 1) * fifth) }
        val n = rowImgs.size
        var eff = n - (board.u8(0x53) and 1)
        if (eff < 1) eff = 1
        var acc = 0
        for ((idx, row) in rowImgs.withIndex()) {
            row.refs[0] = board
            for (k in lives[idx]) k.img.refs[Mookey.OWNER] = board
            val y1: Int
            if (board.u8(0x53) == 0) {
                row.setI32(Moorow.Y0, Integer.divideUnsigned(acc * h, n) + off)
                acc++
                y1 = Integer.divideUnsigned(acc * h, n) + off
            } else if (idx == 0 || idx == eff) {
                val t = if (idx != 0) idx - 1 else 0
                row.setI32(Moorow.Y0, t * h / eff + off)
                y1 = (t + 1) * h / eff + off
            } else {
                val d = h / eff
                val base = d + off
                val span = (off + h - d) - base
                row.setI32(Moorow.Y0, span * (idx - 1) / (n - 2) + base)
                y1 = span * idx / (n - 2) + base
            }
            row.setI32(Moorow.Y1, y1)
            if (idx + 1 == n) {
                row.setI32(Moorow.Y1, y1 - mp.i32(0x1f4b8))
                if ((mp.u8(0x1ee74 + 0xc) != 0 || MoopadGeometry.hasUSF1(mp, 0x146) == 0) && board.u8(0x27) != 0 &&
                    MoopadGeometry.hasUSF1(mp, 0xdc) == 0)
                    row.setI32(Moorow.Y0, f2iz(fadd(fmul(mp.f32(MoopadGeometry.KEY_H), F0_333), i2f(row.i32(Moorow.Y0)))))
            }
            val hh = (row.i32(Moorow.Y1) - row.i32(Moorow.Y0)) / 2
            row.setI32(0x24, row.i32(Moorow.Y0) - hh)
            row.setI32(0x28, row.i32(Moorow.Y1) + hh)
            val tpl = tpls[idx]
            if (tpl.isNotEmpty()) {
                for (k in tpl) syncKeyTypeFlags(k)
                var a = 0
                for (k in tpl) if (Geometry.shingIswalpha(k.chara)) k.img.setI32(0x44, a++)
            }
            syncXesAndSplits(row, tpl, lives[idx], board, mp, roenWidth, splitAllowed)
            for (k in tpl) k.img.refs[Mookey.OWNER_ROW] = row
            for (k in lives[idx]) k.img.refs[Mookey.OWNER_ROW] = row
        }
        if (ohmOn(board, mp)) throw UnsupportedOperationException("MooBoard::performOneHandedModeBottomRowSpliceArrangement (gated setting)")
        if (rowImgs.isNotEmpty()) {
            for ((idx, row) in rowImgs.withIndex()) {
                for (k in lives[idx]) syncExpansions(k, board, mp, roenWidth, topbarPx)
                for (k in lives[idx]) syncOneHandedMode(k, board, mp, roenWidth, topbarPx)
                if (board.i32(MooBoard.HEIGHT) < row.i32(Moorow.Y1)) board.setI32(MooBoard.HEIGHT, row.i32(Moorow.Y1))
            }
        }
        board.setF32(0x3c, mp.f32(MoopadGeometry.KEY_H))
        var gi = 0
        for (l in lives) for (k in l) k.img.setI32(0x58, gi++)
    }

    /**
     * MooBoard::initionFinisher() @009e28e4 (after initionPreparer emptied the containers):
     * board 0x38 += 1 when byte 0x50 is set, syncDimensions, syncNeighbors (live keys),
     * syncNamedKeys, syncTrieCharKeys, [initionFinisherTail], version 0x20 = Moopad 0xaf3c. The
     * trailing Moopad::maybeResyncMootouchKeysOnKeyboardReload is the host's (it rebuilds its
     * KeyboardModel instead).
     */
    fun initionFinisher(c: BuildContainers, board: MemImage, rowImgs: List<MemImage>, tpls: List<List<Mookey>>,
                        lives: List<MutableList<Mookey>>, mp: MemImage, roenWidth: Int, topbarPx: () -> Int) {
        if (board.u8(0x50) != 0) board.setI32(0x38, board.i32(0x38) + 1)
        syncDimensions(board, rowImgs, tpls, lives, mp, roenWidth, false, topbarPx)
        syncNeighbors(lives)
        syncNamedKeys(board, lives, mp, roenWidth, c.byName, c.named)
        syncTrieCharKeys(lives, c.byTrie, c.byUni, c.apostrophes)
        initionFinisherTail(board, rowImgs, lives, mp, c.tiles, c.flagged)
        board.setI32(0x20, mp.i32(0xaf3c))
    }

    /** The board's containers that the build fills (MooBoard 0xfe8 tiles, 0x1010 / 0x1028
     * indexes, 0x1084 apostrophe keys, 0x1000 flagged keys, 0xf90 name map, named key slots). */
    class BuildContainers {
        val tiles = TreeMap<TileIndex, Maplet>()
        val byTrie = TreeMap<Int, MutableList<Mookey>>()
        val byUni = TreeMap<Int, MutableList<Mookey>>()
        val apostrophes = ArrayList<Mookey>()
        val flagged = ArrayList<Mookey>()
        val byName = TreeMap<String, Mookey>(STRING_ORDER)
        val named = HashMap<Int, Mookey?>()
    }

    /** Mookey::glow(int d) @009becd0: an ASCII letter key (libc isalpha) joins the board's glow
     * set [glowing] (MooBoard 0x60, a std::set<Mookey*>) when its glow count 0xda0 is 0, then
     * 0xda0 += d, 0xda8 = 0xdac = 1.0, 0xda4 clamped below at 0 (NaN kept). Rendering state. */
    fun glow(k: Mookey, d: Int, glowing: MutableSet<Mookey>) {
        val c = k.chara
        if (!(c in 0x41..0x5a || c in 0x61..0x7a)) return
        if (k.img.i32(0xda0) == 0) glowing.add(k)
        k.img.setI32(0xda0, k.img.i32(0xda0) + d)
        k.img.setF32(0xda8, 1.0f)
        k.img.setF32(0xdac, 1.0f)
        val a = k.img.f32(0xda4)
        if (nintype.engine.SoftFloat.fcmplt(a, 0f)) k.img.setF32(0xda4, 0f)
    }

    /** MooBoard::newRow() @009e08d4: append a row (by value) with newRow's defaults: empty key
     * vectors, y / split / pads 0, worth 0x40 = board 0x34, clamp x (0, -10), x extent (9999,
     * -9999), row index 0x2c, owner board. Bytes 0x31..0x33 and 0x46..0x47 are left as the
     * binary leaves them (stack garbage), here 0. */
    fun newRow(board: MemImage, rows: MutableList<MemImage>): MemImage {
        val r = MemImage("row${rows.size}", Moorow.SIZE)
        for (o in 0x04 until 0x58 step 4) r.setI32(o, 0)
        r.setI32(0x40, board.i32(0x34))
        r.setI32(Moorow.CLAMP_X1, -10)
        r.setI32(0x50, 9999)
        r.setI32(0x54, -9999)
        rows.add(r)
        r.setI32(0x2c, rows.size - 1)
        r.refs[0] = board
        return r
    }

    /** MooBoard::reloadIfVersionIsOutdated() @009e0738: when the board's layout version 0x20
     * differs from Moopad 0xaf3c or the board was never loaded (byte 0x10), take the version and,
     * if it has a def file name (std::string 0xc), reload. Returns whether a reload is due (the
     * binary calls reloadFromDefFile; the host rebuilds its KeyboardModel instead). */
    fun reloadIfVersionIsOutdated(board: MemImage, mp: MemImage, defFile: String): Boolean {
        if (board.i32(0x20) == mp.i32(0xaf3c) && board.u8(0x10) != 0) return false
        board.setI32(0x20, mp.i32(0xaf3c))
        return defFile != ""
    }

    /** MooBoard::initionPreparer() @009e0c20: destroy the rows (and their keys), zero board 0x38
     * / 0x5c and bytes 0x50 / 0x52, and empty every container the build fills: the glow set
     * [glowing], [c] (name map 0xf90, 0x1000 list, tiles, trie-char / unicode indexes,
     * apostrophe keys), the three further int -> keys maps at 0x103c / 0x1054 / 0x106c
     * ([otherMaps]) and the BiDir table 0x1090 ([bidirReset]). Render arrays are not modelled. */
    fun initionPreparer(board: MemImage, rows: MutableList<*>, glowing: MutableSet<Mookey>, c: BuildContainers,
                        otherMaps: List<MutableMap<Int, MutableList<Mookey>>>, bidirReset: () -> Unit) {
        rows.clear()
        board.setI32(0x38, 0)
        board.setI32(0x5c, 0)
        board.setU8(0x52, 0)
        board.setU8(0x50, 0)
        glowing.clear()
        c.byName.clear()
        c.flagged.clear()
        c.tiles.clear()
        c.byTrie.clear()
        c.byUni.clear()
        for (m in otherMaps) m.clear()
        c.apostrophes.clear()
        bidirReset()
    }

    const val XNL_STOCK = 0x1f79c
    const val WORTH = 0xa8
    private val F1_4 = java.lang.Float.intBitsToFloat(0x3fb33333)
    private val F0_333 = java.lang.Float.intBitsToFloat(0x3eaa7efa)
    private val F0_1 = java.lang.Float.intBitsToFloat(0x3dcccccd)
    private val F0_9 = java.lang.Float.intBitsToFloat(0x3f666666)
}
