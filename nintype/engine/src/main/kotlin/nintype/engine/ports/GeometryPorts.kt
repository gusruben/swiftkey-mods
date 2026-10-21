package nintype.engine.ports

import nintype.engine.geometry.Geometry
import nintype.engine.geometry.KeyboardModel
import nintype.engine.geometry.MooBoard
import nintype.engine.geometry.Mookey
import nintype.engine.geometry.MoopadGeometry
import nintype.engine.geometry.Moorow
import nintype.engine.geometry.TileIndex
import nintype.engine.math.Point2D
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.Values
import nintype.engine.vectors.VectorCase
import java.util.TreeMap

/** Vector adapters for re/vectors/gen_geometry.py (+ gen_geometry_board.py). */

private fun pt(img: MemImage, off: Int = 0) = Point2D(img.f32(off), img.f32(off + 4))
private fun ptJson(p: Point2D): JsonValue = Values.struct("@0x0:x" to Leaf.f32(p.x).toJson(), "@0x4:y" to Leaf.f32(p.y).toJson())
private fun VectorCase.p(name: String) = pt(MemImage.decode(inputs[name]!!))
private fun VectorCase.img(name: String) = MemImage.decode(inputs[name]!!, name)
private fun VectorCase.roen(): Int = MemImage.decode(inputs["roen"]!!).i32(0xb28)

/** Mookeys of a vector world, one wrapper per image (identity matters for `$ref` returns). */
class GeoWorld(val w: MemWorld) {
    private val cache = HashMap<MemImage, Mookey>()

    fun key(img: MemImage): Mookey = cache.getOrPut(img) {
        Mookey(img).also { k ->
            k.stairs = img.seqs[Mookey.STAIRS]?.map { pt(MemImage.decode(it)) } ?: emptyList()
            img.ptr(Mookey.NAME)?.let { s ->
                val b = java.io.ByteArrayOutputStream()
                var i = 0
                while (s.isKnown(i) && s.byteAt(i) != 0.toByte()) b.write(s.byteAt(i++).toInt())
                k.name = b.toString("UTF-8")
            }
        }
    }

    fun key(name: String): Mookey = key(w[name])

    private fun refName(j: JsonValue): String? = j.asObject()["\$ref"]!!.asStringOrNull()

    private fun keyList(items: List<JsonValue>) = items.map { key(refName(it)!!) }

    /** The board image with its containers decoded. */
    fun board(name: String = "board"): MooBoard {
        val bi = w[name]
        val mp = bi.ref(MooBoard.MOOPAD) ?: w.images["mp"] ?: MemImage("mp")
        val rows = bi.seqs[MooBoard.ROWS]?.map { j ->
            val ri = MemImage.decode(j)
            Moorow(ri, ri.seqs[Moorow.KEYS]?.let { keyList(it) } ?: emptyList())
        } ?: emptyList()
        val tiles = TreeMap<TileIndex, List<Mookey>>()
        for (e in bi.seqs[MooBoard.TILES] ?: mutableListOf()) {
            val o = e.asObject()
            val t = MemImage.decode(o["first"]!!)
            val maplet = o["second"]!!.asObject()
            val keys = maplet.entries.first { it.key.startsWith("@0x8") }.value.asObject()["\$vec"]!!.asArray()
            tiles[TileIndex(t.i32(0), t.i32(4))] = keyList(keys)
        }
        val uni = TreeMap<Int, List<Mookey>>()
        for (e in bi.seqs[MooBoard.BY_UNICODE] ?: mutableListOf()) {
            val o = e.asObject()
            uni[Leaf.fromJson(o["first"]!!).asInt()] = keyList(o["second"]!!.asObject()["\$vec"]!!.asArray())
        }
        val aps = bi.seqs[MooBoard.APOSTROPHE_KEYS]?.let { keyList(it) } ?: emptyList()
        val tri = TreeMap<Int, MutableList<Mookey>>()
        for (e in bi.seqs[MooBoard.BY_TRIECHARA] ?: mutableListOf()) {
            val o = e.asObject()
            tri[Leaf.fromJson(o["first"]!!).asInt()] = keyList(o["second"]!!.asObject()["\$vec"]!!.asArray()).toMutableList()
        }
        val board = MooBoard(bi, mp, rows, tiles, uni, aps, byTrieChara = tri)
        if (bi.isKnown(MooBoard.BIDIR) && bi.i32(MooBoard.BIDIR) > 0) {
            val t = ByteArray(4 * bi.i32(MooBoard.BIDIR)) { 0xff.toByte() }
            w.images["bidir"]?.let { d -> for (i in t.indices) if (d.isKnown(i)) t[i] = d.byteAt(i) }
            board.bidir = t
        }
        return board
    }

    fun nameOf(k: Mookey?): String? = k?.img?.name
}

private fun reg(name: String, body: (VectorCase) -> CaseResult) = PortRegistry.register(name, body)
private fun regF(name: String, body: (VectorCase) -> Float) = reg(name) { CaseResult(ret = Leaf.f32(body(it))) }
private fun regB(name: String, body: (VectorCase) -> Boolean) = reg(name) { CaseResult(ret = Leaf.bool(body(it))) }

fun registerGeometryPorts() {
    registerGeometryBuildPorts()
    regB("Point2D::withinRectangleWithTolerance(Point2D const&, Point2D const&, Point2D const&, Point2D const&, float) const") {
        Geometry.withinRectangleWithTolerance(it.p("this"), it.p("a"), it.p("b"), it.p("c"), it.p("d"), it.args[0].asFloat())
    }

    val mk = "Emkey::Mookey::"
    reg("${mk}findTest(Point2D const&, float&)") {
        val k = GeoWorld(it.world()).key("this")
        val r = k.findTest(it.p("p"), it.img("dist").f32(0))
        CaseResult(ret = Leaf.bool(r.hit), out = mapOf("dist" to Values.struct("@0x0" to Leaf.f32(r.dist).toJson())),
            mem = mapOf("this" to k.img))
    }
    regB("${mk}contains(int, int)") { GeoWorld(it.world()).key("this").contains(it.args[0].asInt(), it.args[1].asInt()) }
    regB("${mk}containsWithExtra(Point2D const&, int, int, int, int)") {
        GeoWorld(it.world()).key("this").containsWithExtra(it.p("p"), it.args[0].asInt(), it.args[1].asInt(), it.args[2].asInt(), it.args[3].asInt())
    }
    regB("${mk}containsWithExtraIgnoringOHM(Point2D const&, int, int, int, int)") {
        GeoWorld(it.world()).key("this").containsWithExtraIgnoringOHM(it.p("p"), it.args[0].asInt(), it.args[1].asInt(), it.args[2].asInt(), it.args[3].asInt())
    }
    reg("${mk}shiftToucherCount(int)") {
        val k = GeoWorld(it.world()).key("this")
        k.shiftToucherCount(it.args[0].asInt())
        CaseResult(mem = mapOf("this" to k.img))
    }

    val mb = "Emkey::MooBoard::"
    reg("${mb}clampCoveragePos(Point2D const&)") {
        val g = GeoWorld(it.world())
        CaseResult(retValue = ptJson(g.board("this").clampCoveragePos(it.p("p"))))
    }
    reg("${mb}isActive()") { CaseResult(ret = Leaf("bool", GeoWorld(it.world()).board("this").isActive().toLong())) }
    reg("${mb}findKeyEx(int, int, int, bool)") {
        val g = GeoWorld(it.world())
        val k = g.board().findKeyEx(it.args[0].asInt(), it.args[1].asInt(), it.args[2].asInt(), it.args[3].asBool())
        CaseResult(retValue = Values.ref(g.nameOf(k)))
    }
    reg("${mb}findKey(int, int)") {
        val g = GeoWorld(it.world())
        CaseResult(retValue = Values.ref(g.nameOf(g.board().findKey(it.args[0].asInt(), it.args[1].asInt()))))
    }
    reg("${mb}findKeyByUnicode(unsigned int, int)") {
        val g = GeoWorld(it.world())
        val k = g.board().findKeyByUnicode(it.args[0].asInt(), it.args[1].asInt(), it.roen())
        CaseResult(retValue = Values.ref(g.nameOf(k)))
    }

    val mpn = "Emkey::Moopad::"
    reg("${mpn}findKey(int, int)") {
        val g = GeoWorld(it.world())
        val mp = g.w["mp"]
        val board = if (mp.ref(MoopadGeometry.CUR_BOARD) != null) g.board() else null
        CaseResult(retValue = Values.ref(g.nameOf(MoopadGeometry.findKey(board, it.args[0].asInt(), it.args[1].asInt()))))
    }
    regF("${mpn}curboardVertlen()") { MoopadGeometry.curboardVertlen(it.img("mp")) }
    regF("${mpn}curboardTopbarBottomYpos()") { MoopadGeometry.curboardTopbarBottomYpos(it.img("mp")) }
    regF("${mpn}curboardSpacebarTopYpos()") { MoopadGeometry.curboardSpacebarTopYpos(it.img("mp")) }
    regF("${mpn}curboardVertposWithoutTopbar(float)") { MoopadGeometry.curboardVertposWithoutTopbar(it.img("mp"), it.args[0].asFloat()) }
    regF("${mpn}hiddenAreaBelowOriginDueToShrinkage()") { MoopadGeometry.hiddenAreaBelowOriginDueToShrinkage(it.img("mp")) }
    regF("${mpn}keyarea_xposByFactor(float)") { MoopadGeometry.keyareaXposByFactor(it.img("mp"), it.roen(), it.args[0].asFloat()) }
    regF("${mpn}keyarea_xfactorByPos(float)") { MoopadGeometry.keyareaXfactorByPos(it.img("mp"), it.roen(), it.args[0].asFloat()) }
    regF("${mpn}letterarea_yposByFactor(float)") { MoopadGeometry.letterareaYposByFactor(it.img("mp"), it.args[0].asFloat()) }
    regF("${mpn}letterarea_yfactorByPos(float)") { MoopadGeometry.letterareaYfactorByPos(it.img("mp"), it.args[0].asFloat()) }
    regF("${mpn}getSpacebarStuffXMid()") { MoopadGeometry.getSpacebarStuffXMid(it.world()["mp"], it.roen()) }

    reg("Emkey::get_trie_chara(int)") { CaseResult(ret = Leaf.i32(Geometry.getTrieChara(it.args[0].asInt()))) }
    for (n in listOf("getTrieChara", "getAltTrieChara", "getNonBasicChara")) reg("${mk}$n()") {
        val k = GeoWorld(it.world()).key("this")
        val r = when (n) {
            "getTrieChara" -> k.getTrieChara()
            "getAltTrieChara" -> k.getAltTrieChara()
            else -> k.getNonBasicChara()
        }
        CaseResult(ret = Leaf.i32(r), mem = mapOf("this" to k.img))
    }
    reg("${mk}getAltTrieCharaPosShift()") {
        val k = GeoWorld(it.world()).key("this")
        CaseResult(retValue = ptJson(k.getAltTrieCharaPosShift()), mem = mapOf("this" to k.img))
    }
    reg("${mpn}addTraversal(Emkey::MooTouchPoint&, Emkey::Mookey&)") {
        val g = GeoWorld(it.world())
        val tp = g.w["tp"]
        val k = g.key("key")
        nintype.engine.geometry.Traversal.addTraversal(g.w["mp"], tp, k)
        CaseResult(mem = mapOf("tp" to tp, "key" to k.img))
    }
    reg("${mpn}genTraversal(Emkey::MooTouchPoint&)") {
        val g = GeoWorld(it.world())
        val mp = g.w["mp"]
        val tp = g.w["tp"]
        val board = if (mp.ref(MoopadGeometry.CUR_BOARD) != null) g.board() else null
        val det = tp.ref(0x2f0)?.let { g.key(it) }
        nintype.engine.geometry.Traversal.genTraversal(mp, tp, board, det)
        CaseResult(mem = g.w.images)
    }

    regB("${mk}isRechooser()") { GeoWorld(it.world()).key("this").isRechooser() }
    regB("${mk}isAlphaKey()") { GeoWorld(it.world()).key("this").isAlphaKey() }
    regB("shing_iswalpha(int)") { Geometry.shingIswalpha(it.args[0].asInt()) }
    fun regU8(name: String, body: (VectorCase) -> Int) = reg(name) { CaseResult(ret = Leaf("bool", body(it).toLong())) }
    regU8("${mpn}slidemodeActive()") { MoopadGeometry.slidemodeActive(it.img("mp")) }
    regU8("${mpn}peckmodeActive()") { MoopadGeometry.peckmodeActive(it.img("mp")) }
    regU8("${mpn}stockmodeActive()") { MoopadGeometry.stockmodeActive(it.img("mp")) }
    regU8("${mpn}hasUSF1(int)") { MoopadGeometry.hasUSF1(it.img("mp"), it.args[0].asInt()) }
    regU8("${mpn}temporaryAutospaceIsOn()") { MoopadGeometry.temporaryAutospaceIsOn(it.img("mp")) }
    regF("${mpn}translateForceval(float)") { MoopadGeometry.translateForceval(it.img("mp"), it.args[0].asFloat()) }
    regF("${mpn}translateTwerkForce(float)") { MoopadGeometry.translateTwerkForce(it.img("mp"), it.args[0].asFloat()) }
    reg("${mb}findKeyPosByUnicode(unsigned int, int)") {
        val g = GeoWorld(it.world())
        CaseResult(retValue = ptJson(g.board().findKeyPosByUnicode(it.args[0].asInt(), it.args[1].asInt(), it.roen())))
    }

    for (strArg in listOf(false, true)) reg(if (strArg) "${mb}getBiDirIndex(std::string const&)" else "${mb}getBiDirIndex(int, int)") {
        val g = GeoWorld(it.world())
        val b = g.board()
        val sb = if (strArg) it.world()["s"].ptr(0)!!.readBytes(0, 2) else null
        val c1 = sb?.let { x -> x[0].toInt() and 0xff } ?: it.args[0].asInt()
        val c2 = sb?.let { x -> x[1].toInt() and 0xff } ?: it.args[1].asInt()
        val r = if (sb != null) b.getBiDirIndex(sb) else b.getBiDirIndex(c1, c2)
        val ret = Values.struct("kind" to Leaf("u8", if (r.isStaticEmpty) 0L else 1L).toJson(),
            "index" to Leaf.i32(r.index).toJson(), "bytes" to Values.bytesLeaf(r.bytes()))
        val tri = b.byTrieChara.entries.filter { e -> e.key == c1 || e.key == c2 }.map { (u, l) ->
            JsonValue.JObject(linkedMapOf("first" to Leaf.i32(u).toJson(), "second" to Values.vec(l.map { k -> Values.ref(g.nameOf(k)) })))
        }
        val t = b.bidir
        val board = Values.struct("@0x1010:byTrieChara" to Values.map(tri), "@0x1090:bidir.n" to Leaf.i32(if (t == null) 0 else t.size / 4).toJson())
        val bid = LinkedHashMap<String, JsonValue>()
        if (t != null) for (i in 0 until t.size / 4) {
            val v = t.copyOfRange(4 * i, 4 * i + 4)
            if (v.any { x -> x != 0xff.toByte() }) bid["@0x${(4 * i).toString(16)}"] = Values.bytesLeaf(v)
        }
        CaseResult(retValue = ret, out = mapOf("board" to board, "bidir" to JsonValue.JObject(bid)))
    }

    reg("${mb}findKeyPosByTriechar(unsigned int)") {
        val g = GeoWorld(it.world())
        CaseResult(retValue = ptJson(g.board().findKeyPosByTriechar(it.args[0].asInt(), it.roen())))
    }

    reg("${mb}oneHandedModeState()") { CaseResult(ret = Leaf.i32(GeoWorld(it.world()).board().oneHandedModeState())) }
    reg("${mb}getOhmBottomCorner()") { CaseResult(retValue = ptJson(GeoWorld(it.world()).board().getOhmBottomCorner(it.roen()))) }
    reg("${mb}ohmShift(Point2D const&, bool)") {
        CaseResult(retValue = ptJson(GeoWorld(it.world()).board().ohmShift(it.p("p"), it.args[0].asBool(), it.roen())))
    }
    reg("${mk}alphaBoardForcePushKeyIndex()") {
        val t = GeoWorld(it.world()).key("this").alphaBoardForcePushKeyIndex()
        CaseResult(retValue = Values.struct("@0x0:x" to Leaf.i32(t.x).toJson(), "@0x4:y" to Leaf.i32(t.y).toJson()))
    }

    reg("${mpn}findTouchByUniqueID(int)") {
        val w = it.world()
        val mp = w["mp"]
        fun list(off: Int) = mp.seq(off).map { j -> w[j.asObject()["\$ref"]!!.asString()] }
        val r = MoopadGeometry.findTouchByUniqueID(list(MoopadGeometry.LIVE_TOUCHES), list(MoopadGeometry.SITTING_TOUCHES), it.args[0].asInt())
        CaseResult(retValue = Values.ref(r?.name))
    }

    reg("${mb}findKeyByTriechar(int)") {
        val g = GeoWorld(it.world())
        CaseResult(retValue = Values.ref(g.nameOf(g.board().findKeyByTriechar(it.args[0].asInt()))))
    }
    regB("${mk}hasToucher()") { GeoWorld(it.world()).key("this").hasToucher() }
    regB("${mk}isEmojiBoardEmojiKey()") { GeoWorld(it.world()).key("this").isEmojiBoardEmojiKey() }

    // The reference layout (KeyboardModel.nintype()) against the unmodified booted board.
    val ref by lazy { KeyboardModel.nintype() }
    reg("geometry::NintypeLayout.findKey(int, int)") {
        CaseResult(ret = Leaf.i32(ref.indexOf(ref.board.findKey(it.args[0].asInt(), it.args[1].asInt()))))
    }
    reg("geometry::NintypeLayout.clampCoveragePos(Point2D const&)") { CaseResult(retValue = ptJson(ref.board.clampCoveragePos(it.p("p")))) }
    reg("geometry::NintypeLayout.findKeyByUnicode(unsigned int, int)") {
        CaseResult(ret = Leaf.i32(ref.indexOf(ref.board.findKeyByUnicode(it.args[0].asInt(), it.args[1].asInt(), ref.roenWidth))))
    }
}


// --- board build (re/vectors/gen_geometry_build.py) -----------------------------------------

private fun cString(s: MemImage?): String {
    if (s == null) return ""
    val b = java.io.ByteArrayOutputStream()
    var i = 0
    while (s.isKnown(i) && s.byteAt(i) != 0.toByte()) b.write(s.byteAt(i++).toInt())
    return b.toString("UTF-8")
}

private fun nameJson(n: String): JsonValue = Values.struct("\$ptr" to Values.struct("@0x0" to Values.bytesLeaf((n + "\u0000").toByteArray(Charsets.UTF_8))))

/** Every image of a build case wrapped as a Mookey (names, links and neighbour lists decoded). */
private class BuildWorld(val c: VectorCase) {
    val w = c.world()
    val keys = LinkedHashMap<MemImage, Mookey>()
    init {
        for (img in w.images.values) keys[img] = Mookey(img).also { k ->
            k.name = cString(img.ptr(Mookey.NAME))
            k.typeName = cString(img.ptr(0x5c))
        }
        for ((img, k) in keys) {
            k.prev = img.ref(0x14)?.let { keys[it] }
            k.next = img.ref(0x18)?.let { keys[it] }
            for ((off, list) in listOf(0x1c to k.neighbors, 0x28 to k.neighborsAndSelf, 0x34 to k.neighborsBelow))
                img.seqs[off]?.let { s -> if (s.all { e -> e.asObjectOrNull()?.containsKey("\$ref") == true }) list.addAll(s.map { key(it) }) }
        }
    }
    fun key(ref: JsonValue): Mookey = keys[w[ref.asObject()["\$ref"]!!.asString()]]!!
    fun key(name: String): Mookey {
        val v = c.inputs[name]!!.asObject()
        return if (v.containsKey("\$ref")) key(c.inputs[name]!!) else keys[w[name]]!!
    }
    fun refs(l: List<Mookey>): JsonValue = Values.vec(l.map { Values.ref(it.img.name) })
    /** Rows of a board-like image's vector at [off], each row's key vector at [keysOff]. */
    fun rows(img: MemImage, off: Int = MooBoard.ROWS, keysOff: Int = Moorow.KEYS): List<List<Mookey>> =
        (img.seqs[off] ?: mutableListOf()).map { j -> (MemImage.decode(j).seqs[keysOff] ?: mutableListOf()).map { key(it) } }
    fun storeLinks(keysToStore: Collection<Mookey> = keys.values) {
        for (k in keysToStore) {
            k.img.seqs[0x1c] = refs(k.neighbors).asObject()["\$vec"]!!.asArray().toMutableList()
            k.img.seqs[0x28] = refs(k.neighborsAndSelf).asObject()["\$vec"]!!.asArray().toMutableList()
            k.img.seqs[0x34] = refs(k.neighborsBelow).asObject()["\$vec"]!!.asArray().toMutableList()
        }
    }
    fun mem(): Map<String, MemImage> = w.images.toMap()
}

private fun tileKey(t: TileIndex) = Values.struct("@0x0:x" to Leaf.i32(t.x).toJson(), "@0x4:y" to Leaf.i32(t.y).toJson())

private fun decodeTiles(bw: BuildWorld, img: MemImage): TreeMap<TileIndex, nintype.engine.geometry.BoardBuild.Maplet> {
    val m = TreeMap<TileIndex, nintype.engine.geometry.BoardBuild.Maplet>()
    for (e in img.seqs[MooBoard.TILES] ?: mutableListOf()) {
        val o = e.asObject()
        val f = MemImage.decode(o["first"]!!)
        val sec = o["second"]!!.asObject()
        val t2 = MemImage.decode(Values.struct(*sec.entries.filter { !it.key.startsWith("@0x8") }.map { it.key to it.value }.toTypedArray()))
        val ks = sec.entries.first { it.key.startsWith("@0x8") }.value.asObject()["\$vec"]!!.asArray().map { bw.key(it) }
        m[TileIndex(f.i32(0), f.i32(4))] = nintype.engine.geometry.BoardBuild.Maplet(TileIndex(t2.i32(0), t2.i32(4)), ks.toMutableList())
    }
    return m
}

private fun encodeTiles(bw: BuildWorld, m: TreeMap<TileIndex, nintype.engine.geometry.BoardBuild.Maplet>): MutableList<JsonValue> =
    m.map { (t, ml) ->
        Values.struct("first" to tileKey(t), "second" to Values.struct("@0x0:tile.x" to Leaf.i32(ml.tile.x).toJson(),
            "@0x4:tile.y" to Leaf.i32(ml.tile.y).toJson(), "@0x8:keys" to bw.refs(ml.keys)))
    }.toMutableList()

private fun decodeIntMap(bw: BuildWorld, s: List<JsonValue>?): TreeMap<Int, MutableList<Mookey>> {
    val m = TreeMap<Int, MutableList<Mookey>>()
    for (e in s ?: emptyList()) {
        val o = e.asObject()
        m[Leaf.fromJson(o["first"]!!).asInt()] = o["second"]!!.asObject()["\$vec"]!!.asArray().map { bw.key(it) }.toMutableList()
    }
    return m
}

private fun encodeIntMap(bw: BuildWorld, m: TreeMap<Int, MutableList<Mookey>>): MutableList<JsonValue> =
    m.map { (k, v) -> Values.struct("first" to Leaf.i32(k).toJson(), "second" to bw.refs(v)) }.toMutableList()

fun registerGeometryBuildPorts() {
    val BB = nintype.engine.geometry.BoardBuild
    val mk = "Emkey::Mookey::"
    reg("${mk}Mookey()") {
        val img = MemImage.decode(it.inputs["this"]!!, "this")
        BB.construct(img)
        CaseResult(mem = mapOf("this" to img))
    }
    for (ctor in listOf("${mk}Mookey(Emkey::Mookey const&)", "${mk}Mookey(Emkey::Mookey&&)")) reg(ctor) {
        val bw = BuildWorld(it)
        BB.copyKey(bw.key("other"), bw.key("this"))
        CaseResult(mem = bw.mem())
    }
    reg("Hangul::get_capitalized_damo(unsigned int)") { CaseResult(ret = Leaf.u32(BB.getCapitalizedDamo(it.args[0].asInt()))) }
    reg("${mk}syncKeyTypeFlags()") {
        val bw = BuildWorld(it)
        BB.syncKeyTypeFlags(bw.key("this"))
        CaseResult(mem = bw.mem())
    }
    reg("${mk}syncExpansions()") {
        val bw = BuildWorld(it)
        val k = bw.key("this")
        val board = bw.w["board"]
        BB.syncExpansions(k, board, bw.w["mp"], bw.w["roen"].i32(0xb28)) { bw.w["host"].i32(0) }
        CaseResult(mem = bw.mem())
    }
    reg("${mk}finalizeNeighbors()") {
        val bw = BuildWorld(it)
        BB.finalizeNeighbors(bw.key("this"))
        bw.storeLinks()
        CaseResult(mem = bw.mem())
    }
    val mr = "Emkey::Moorow::"
    reg("${mr}gatherHorizontalNeighbors()") {
        val bw = BuildWorld(it)
        BB.gatherHorizontalNeighbors(bw.w["this"].seq(Moorow.KEYS).map { j -> bw.key(j) })
        bw.storeLinks()
        CaseResult(mem = bw.mem())
    }
    reg("${mr}syncVerticalNeighbors(Emkey::Moorow&)") {
        val bw = BuildWorld(it)
        BB.syncVerticalNeighbors(bw.w["this"].seq(Moorow.KEYS).map { j -> bw.key(j) }, bw.w["other"].seq(Moorow.KEYS).map { j -> bw.key(j) })
        bw.storeLinks()
        CaseResult(mem = bw.mem())
    }
    reg("${mr}syncOrigKeyXes()") {
        val bw = BuildWorld(it)
        val row = bw.w["this"]
        BB.syncOrigKeyXes(row, row.seq(0x04).map { j -> bw.key(j) }, bw.w["mp"], bw.w["roen"].i32(0xb28), bw.w["host"].i32(4) != 0)
        CaseResult(mem = bw.mem())
    }
    reg("${mr}syncXesAndSplits()") {
        val bw = BuildWorld(it)
        val row = bw.w["this"]
        val live = row.seq(Moorow.KEYS).map { j -> bw.key(j) }.toMutableList()
        BB.syncXesAndSplits(row, row.seq(0x04).map { j -> bw.key(j) }, live, bw.w["board"], bw.w["mp"], bw.w["roen"].i32(0xb28),
            bw.w["host"].i32(4) != 0)
        for (k in live) { k.img.refs[0x14] = k.prev?.img; k.img.refs[0x18] = k.next?.img }
        CaseResult(mem = bw.mem())
    }
    val mb = "Emkey::MooBoard::"
    reg("${mb}syncNeighbors()") {
        val bw = BuildWorld(it)
        BB.syncNeighbors(bw.rows(bw.w["this"]))
        bw.storeLinks()
        CaseResult(mem = bw.mem())
    }
    reg("${mb}maplet(TileIndex const&)") {
        val bw = BuildWorld(it)
        val b = bw.w["this"]
        val tiles = decodeTiles(bw, b)
        val t = bw.w["tile"]
        val ml = BB.maplet(tiles, TileIndex(t.i32(0), t.i32(4)))
        b.seqs[MooBoard.TILES] = encodeTiles(bw, tiles)
        CaseResult(mem = bw.mem(), retValue = Values.struct("@0x0:tile.x" to Leaf.i32(ml.tile.x).toJson(), "@0x4:tile.y" to Leaf.i32(ml.tile.y).toJson()))
    }
    reg("${mb}indexMookeyToMaplet(Emkey::Mookey*)") {
        val bw = BuildWorld(it)
        val b = bw.w["this"]
        val tiles = decodeTiles(bw, b)
        BB.indexMookeyToMaplet(tiles, bw.w["mp"], bw.key("key"))
        b.seqs[MooBoard.TILES] = encodeTiles(bw, tiles)
        CaseResult(mem = bw.mem())
    }
    reg("${mb}syncTrieCharKeys()") {
        val bw = BuildWorld(it)
        val b = bw.w["this"]
        val tri = decodeIntMap(bw, b.seqs[MooBoard.BY_TRIECHARA])
        val uni = decodeIntMap(bw, b.seqs[MooBoard.BY_UNICODE])
        val aps = (b.seqs[MooBoard.APOSTROPHE_KEYS] ?: mutableListOf()).map { j -> bw.key(j) }.toMutableList()
        BB.syncTrieCharKeys(bw.rows(b), tri, uni, aps)
        b.seqs[MooBoard.BY_TRIECHARA] = encodeIntMap(bw, tri)
        b.seqs[MooBoard.BY_UNICODE] = encodeIntMap(bw, uni)
        b.seqs[MooBoard.APOSTROPHE_KEYS] = aps.map { k -> Values.ref(k.img.name) }.toMutableList()
        CaseResult(mem = bw.mem())
    }
    reg("Shing::is_ios_emoji(int)") { CaseResult(ret = Leaf.bool(BB.isIosEmoji(it.args[0].asInt()))) }
    reg("Hangul::is_damo(unsigned int)") { CaseResult(ret = Leaf.bool(BB.isDamo(it.args[0].asInt()))) }
    reg("${mk}assignChara(int)") {
        val bw = BuildWorld(it)
        BB.assignChara(bw.key("this"), it.args[0].asInt())
        CaseResult(mem = bw.mem())
    }
    reg("${mr}addKey(int, int)") {
        val bw = BuildWorld(it)
        val row = bw.w["this"]
        val tpl = row.seq(0x04).map { j -> bw.key(j) }.toMutableList()
        val k = BB.addKey(tpl, "new", it.args[0].asInt(), it.args[1].asInt())
        k.img.seqs[0x60] = mutableListOf()
        val m = bw.mem().toMutableMap()
        m["new"] = k.img
        val nameImg = MemImage("new.name")
        nameImg.load(0, (k.name + "\u0000").toByteArray(Charsets.UTF_8))
        k.img.ptrs[0x60] = nameImg
        CaseResult(mem = m, out = mapOf("count" to Values.struct("@0x0" to Leaf.i32(tpl.size).toJson())))
    }
    for (fin in listOf(false, true)) reg(if (fin) "${mb}initionFinisher()" else "${mb}syncDimensions()") {
        val bw = BuildWorld(it)
        val b = bw.w["this"]
        val mp = bw.w["mp"]
        val rowImgs = (b.seqs[MooBoard.ROWS] ?: mutableListOf()).map { j -> MemImage.decode(j) }
        val tpls = rowImgs.map { r -> r.seq(0x04).map { j -> bw.key(j) } }
        val lives = rowImgs.map { r -> r.seq(Moorow.KEYS).map { j -> bw.key(j) }.toMutableList() }
        val roenW = bw.w["roen"].i32(0xb28)
        val tb = { bw.w["host"].i32(0) }
        val c = nintype.engine.geometry.BoardBuild.BuildContainers()
        if (fin) BB.initionFinisher(c, b, rowImgs, tpls, lives, mp, roenW, tb)
        else BB.syncDimensions(b, rowImgs, tpls, lives, mp, roenW, false, tb)
        for (l in lives) for (k in l) { k.img.refs[0x14] = k.prev?.img; k.img.refs[0x18] = k.next?.img }
        val rowFields = listOf(0x1c, 0x20, 0x24, 0x28, 0x2c, 0x34, 0x38, 0x3c, 0x40, 0x48, 0x4c, 0x50, 0x54)
        b.seqs[MooBoard.ROWS] = rowImgs.map { r ->
            Values.struct(*(rowFields.map { o -> "@0x${o.toString(16)}" to Leaf.i32(r.i32(o)).toJson() } +
                listOf("@0x44" to Leaf("u8", r.u8(0x44).toLong()).toJson(), "@0x45" to Leaf("u8", r.u8(0x45).toLong()).toJson())).toTypedArray())
        }.toMutableList()
        if (fin) {
            bw.storeLinks(lives.flatten())
            b.seqs[MooBoard.TILES] = encodeTiles(bw, c.tiles)
            b.seqs[MooBoard.BY_TRIECHARA] = encodeIntMap(bw, c.byTrie)
            b.seqs[MooBoard.BY_UNICODE] = encodeIntMap(bw, c.byUni)
            b.seqs[MooBoard.APOSTROPHE_KEYS] = c.apostrophes.map { k -> Values.ref(k.img.name) }.toMutableList()
            b.seqs[0x1000] = c.flagged.map { k -> Values.ref(k.img.name) }.toMutableList()
            b.seqs[0xf94] = c.byName.map { (n, k) -> Values.struct("first" to nameJson(n), "second" to Values.ref(k.img.name)) }.toMutableList()
            for ((off, k) in c.named) b.refs[off] = k?.img
        }
        CaseResult(mem = bw.mem())
    }
    reg("${mk}glow(int)") {
        val bw = BuildWorld(it)
        val b = bw.w["board"]
        val set = LinkedHashSet<Mookey>((b.seqs[0x64] ?: mutableListOf()).map { j -> bw.key(j) })
        BB.glow(bw.key("this"), it.args[0].asInt(), set)
        b.seqs[0x64] = set.map { k -> k.img.name }.sorted().map { n -> Values.ref(n) }.toMutableList()
        b.setI32(0x74, set.size)
        CaseResult(mem = bw.mem())
    }
    reg("${mb}newRow()") {
        val bw = BuildWorld(it)
        val b = bw.w["this"]
        val rows = (b.seqs[MooBoard.ROWS] ?: mutableListOf()).map { j -> MemImage.decode(j) }.toMutableList()
        BB.newRow(b, rows)
        b.seqs[MooBoard.ROWS] = rows.mapIndexed { i, r ->
            if (i < rows.size - 1) Values.struct(*listOf(0x2c, 0x40, 0x50).map { o -> "@0x${o.toString(16)}" to Leaf.i32(r.i32(o)).toJson() }.toTypedArray())
            else Values.struct("@0x4" to Values.bytesLeaf(r.readBytes(4, 0x28)), "@0x2c" to Leaf.i32(r.i32(0x2c)).toJson(),
                "@0x30" to Leaf("u8", r.u8(0x30).toLong()).toJson(),
                *listOf(0x34, 0x38, 0x3c, 0x40).map { o -> "@0x${o.toString(16)}" to Leaf.i32(r.i32(o)).toJson() }.toTypedArray(),
                "@0x44" to Leaf("u16", r.u16(0x44).toLong()).toJson(),
                *listOf(0x48, 0x4c, 0x50, 0x54).map { o -> "@0x${o.toString(16)}" to Leaf.i32(r.i32(o)).toJson() }.toTypedArray(),
                "@0x0:board" to Values.ref("this"))
        }.toMutableList()
        CaseResult(mem = bw.mem())
    }
    reg("${mb}reloadIfVersionIsOutdated()") {
        val bw = BuildWorld(it)
        val b = bw.w["this"]
        val r = BB.reloadIfVersionIsOutdated(b, bw.w["mp"], cString(b.ptr(0xc)))
        CaseResult(mem = bw.mem(), ret = Leaf.bool(r))
    }
    reg("${mb}initionPreparer()") {
        val b = MemImage.decode(it.inputs["this"]!!, "this")
        // the vector carries container sizes only: model each container with that many entries
        val dummy = Mookey(MemImage("k"))
        val c = nintype.engine.geometry.BoardBuild.BuildContainers()
        fun n(o: Int) = b.i32(o)
        repeat(n(0xfa4)) { i -> c.byName["k$i"] = dummy }
        repeat(n(0x1001)) { c.flagged.add(dummy) }
        repeat(n(0xffc)) { i -> c.tiles[TileIndex(i, 0)] = nintype.engine.geometry.BoardBuild.Maplet(TileIndex(i, 0)) }
        repeat(n(0x1020)) { i -> c.byTrie[i] = ArrayList() }
        repeat(n(0x1038)) { i -> c.byUni[i] = ArrayList() }
        repeat(n(0x1085)) { c.apostrophes.add(dummy) }
        val others = listOf(0x1050, 0x1068, 0x1080).map { o -> HashMap<Int, MutableList<Mookey>>().also { m -> repeat(n(o)) { i -> m[i] = ArrayList() } } }
        val glowing = HashSet<Mookey>().also { s -> repeat(n(0x74)) { i -> s.add(Mookey(MemImage("g$i"))) } }
        val rows = ArrayList<Any>().also { r -> repeat(n(0x29)) { r.add(Unit) } }
        var bidirN = n(0x1091)
        BB.initionPreparer(b, rows, glowing, c, others) { bidirN = 0 }
        b.setI32(0x74, glowing.size); b.setI32(0xfa4, c.byName.size); b.setI32(0xffc, c.tiles.size)
        b.setI32(0x1020, c.byTrie.size); b.setI32(0x1038, c.byUni.size)
        for ((i, o) in listOf(0x1050, 0x1068, 0x1080).withIndex()) b.setI32(o, others[i].size)
        b.setI32(0x29, rows.size); b.setI32(0x1001, c.flagged.size); b.setI32(0x1085, c.apostrophes.size); b.setI32(0x1091, bidirN)
        CaseResult(mem = mapOf("this" to b))
    }
    reg("${mb}syncNamedKeys()") {
        val bw = BuildWorld(it)
        val b = bw.w["this"]
        val byName = TreeMap<String, Mookey>(BB.STRING_ORDER)
        val named = HashMap<Int, Mookey?>()
        BB.syncNamedKeys(b, bw.rows(b), bw.w["mp"], bw.w["roen"].i32(0xb28), byName, named)
        b.seqs[0xf94] = byName.map { (n, k) -> Values.struct("first" to nameJson(n), "second" to Values.ref(k.img.name)) }.toMutableList()
        for ((off, k) in named) b.refs[off] = k?.img
        CaseResult(mem = bw.mem())
    }
}
