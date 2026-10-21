package nintype.engine.touch

import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.Values

/**
 * The whole-Mootouch JSON layout of re/vectors/touch_ext.py (see its docstring), both ways.
 * Constants mirror the Python ones exactly; the span lists are recomputed the same way.
 */
object MootouchCodec {
    const val MT_SIZE = 0x3b8
    const val TP_SIZE = 1000
    val MT_HOLDERS = intArrayOf(0x4, 0x10, 0x1c, 0x28, 0x34)
    val MT_PTVECS = intArrayOf(0x244, 0x250, 0x25c)
    const val MT_TWERK = 0x88
    const val MT_PIX = 0x1ec

    val TP_VECS = intArrayOf(0x3c, 0x48, 0x60, 0x8c, 0xa0, 0xac, 0x1c4, 0x1e4, 0x204, 0x328, 0x334, 0x340, 0x3c8)
    val TP_LINKS = intArrayOf(0x2c, 0x30, 0x34, 0x38)
    val TP_KEYS = intArrayOf(0x2f0, 0x39c)
    private val TP_SKIP = listOf(0x248 to 0x2c8, 0x2ec to 0x2f0, 0x2f4 to 0x2f8, 0x1e0 to 0x1e4, 0x15c to 0x16c)
    private val TP_PADDING = listOf(0x54 to 0x58, 0x6c to 0x70, 0x89 to 0x8c, 0x9c to 0xa0, 0xc8 to 0xd0, 0xd1 to 0xd4,
        0x11c to 0x120, 0x171 to 0x174, 0x18c to 0x190, 0x1ae to 0x1b0, 0x1c1 to 0x1c4, 0x1e1 to 0x1e4, 0x201 to 0x204,
        0x216 to 0x218, 0x21e to 0x220, 0x227 to 0x228, 0x23d to 0x240, 0x261 to 0x264, 0x28b to 0x28c, 0x2e9 to 0x2ec,
        0x34e to 0x350, 0x3d9 to 0x3dc, 0x3e1 to 0x3e4)

    private fun spans(size: Int, special: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val cov = BooleanArray(size)
        for ((a, b) in special) for (i in a until minOf(b, size)) cov[i] = true
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < size) {
            if (cov[i]) { i++; continue }
            var j = i
            while (j < size && !cov[j]) j++
            out.add(i to j); i = j
        }
        return out
    }

    val TP_DATA = spans(TP_SIZE, TP_SKIP + (TP_LINKS + TP_KEYS).map { it to it + 4 } + TP_VECS.map { it to it + 12 } +
        listOf(0x8 to 0xc) + TP_PADDING)
    const val MT_RECS = 0x274
    val MT_DATA = spans(MT_SIZE, listOf(0x0 to 0x4, 0x274 to 0x280, 0x294 to 0x394) +
        MT_HOLDERS.flatMap { listOf(it to it + 12) } + MT_PTVECS.map { it to it + 12 } + listOf(MT_TWERK to MT_TWERK + 12, MT_PIX to 0x214))

    /** x86's default NaN (0xffc00000, what the JVM produces on the build host) as ARM's
     * (0x7fc00000): the one NaN-payload difference the f32-leaf comparison would tolerate,
     * applied to every 4-byte-aligned word of the raw data chunks (touch_ext.py does the same). */
    private fun canonNaN(base: Int, b: ByteArray): ByteArray {
        var i = (4 - base % 4) % 4
        while (i + 4 <= b.size) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 0xc0.toByte() && b[i + 3] == 0xff.toByte()) b[i + 3] = 0x7f
            i += 4
        }
        return b
    }

    private fun k(off: Int, name: String? = null) = "@0x" + off.toString(16) + (name?.let { ":$it" } ?: "")
    private fun i32(v: Int) = Leaf.i32(v).toJson()

    /** One MooTouchPoint: its byte image (links/keys as i32 indices at their offsets, vectors as
     * seqs, owner as a $ptr child). */
    fun pointToJson(p: MemImage): JsonValue {
        val m = LinkedHashMap<String, JsonValue>()
        for ((a, b) in TP_DATA) m[k(a)] = Values.bytesLeaf(canonNaN(a, p.readBytes(a, b - a)))
        val own = p.ptr(0x8)
        m[k(0x8, "owner")] = JsonValue.JObject(mapOf("\$ptr" to (own?.let { Values.struct(k(0xbc) to i32(it.i32(0xbc))) } ?: JsonValue.JNull)))
        for (o in TP_LINKS) m[k(o, "link")] = i32(p.i32(o))
        for (o in TP_KEYS) m[k(o, "key")] = i32(p.i32(o))
        for (o in TP_VECS) m[k(o, if (o == 0x328) "travs" else "vec")] = Values.vec(p.seq(o).toList())
        return JsonValue.JObject(m)
    }

    fun pointFromJson(j: JsonValue): MemImage = MemImage.decode(j)

    /** MooTouchPoint::MooTouchPoint() @0095a814 as serialized: the constructor's constant bytes
     * (dumped from the emulator, padding left 0), every pointer null, every vector empty. */
    fun newPoint(): MemImage {
        val p = MemImage("", TP_SIZE)
        p.load(0, CTOR_BYTES)
        p.ptrs[0x8] = null
        for (o in TP_LINKS + TP_KEYS) p.load(o, ByteArray(4) { 0xff.toByte() })
        for (o in TP_VECS) p.seqs[o] = mutableListOf()
        return p
    }

    /** MooTouchPoint(MooTouchPoint const&) @0073c008: a deep copy (vectors copied, the key holder
     * re-held, point links kept as they are: they still name the source's neighbours). */
    fun copyPoint(src: MemImage): MemImage = pointFromJson(pointToJson(src))

    /** MooTouchPoint(MooTouchPoint&&) @009828d0: like the copy; the source's vectors are left empty. */
    fun movePoint(src: MemImage): MemImage {
        val d = copyPoint(src)
        for (o in TP_VECS) src.seqs[o] = mutableListOf()
        return d
    }

    /** operator=(MooTouchPoint const&) @008162d0 on [dst]: every serialized field of [src]. */
    fun assignPoint(dst: MemImage, src: MemImage) {
        val c = copyPoint(src)
        for ((a, b) in TP_DATA) dst.writeBytes(a, c.readBytes(a, b - a))
        dst.ptrs[0x8] = c.ptrs[0x8]
        for (o in TP_LINKS + TP_KEYS) dst.setI32(o, c.i32(o))
        for (o in TP_VECS) dst.seqs[o] = c.seq(o)
    }

    /** MooTouchPoint() @0095a814 over existing storage [p]: the constructor's constants, with the
     * two fields it leaves uninitialised kept (flag bit 0x8000 of 0x224, the 0x39c pointer). */
    fun constructPoint(p: MemImage) {
        val keep225 = p.u8(0x225) and 0x80
        val keep39c = p.i32(0x39c)
        val n = newPoint()
        for ((a, b) in TP_DATA) p.writeBytes(a, n.readBytes(a, b - a))
        p.setU8(0x225, p.u8(0x225) or keep225)
        p.ptrs[0x8] = null
        for (o in TP_LINKS + TP_KEYS) p.setI32(o, -1)
        p.setI32(0x39c, keep39c)
        for (o in TP_VECS) p.seqs[o] = mutableListOf()
    }

    fun mootouchToJson(t: Mootouch): JsonValue {
        val b = t.body
        val m = LinkedHashMap<String, JsonValue>()
        for ((a, e) in MT_DATA) m[k(a)] = Values.bytesLeaf(canonNaN(a, b.readBytes(a, e - a)))
        for (h in MT_HOLDERS) m[k(h + 4, "key")] = i32(b.i32(h + 4))
        m[k(MT_TWERK, "twerk")] = Values.vec(b.seq(MT_TWERK).toList())
        m[k(MT_PIX, "pix")] = Values.vec(t.pix.map { (dt, v) ->
            Values.struct(k(0, "dt") to Leaf("f32", dt.toLong() and 0xFFFFFFFFL).toJson(), k(4, "pxPerSec") to i32(v))
        })
        m[k(MT_RECS, "recs")] = Values.vec(b.seq(MT_RECS).toList())
        for ((i, o) in MT_PTVECS.withIndex()) m[k(o, "points")] = Values.vec(t.vecs[i].map(::pointToJson))
        return JsonValue.JObject(m)
    }

    fun mootouchFromJson(j: JsonValue): Mootouch {
        val b = MemImage.decode(j, "this")
        val vecs = MT_PTVECS.map { o -> b.seq(o).map(::pointFromJson).toMutableList() }
        val pix = ArrayDeque<Pair<Int, Int>>()
        for (e in b.seq(MT_PIX)) {
            val img = MemImage.decode(e)
            pix.addLast(img.i32(0) to img.i32(4))
        }
        return Mootouch(b, vecs, pix)
    }

    private val CTOR_BYTES: ByteArray by lazy { Values.parseBytes(CTOR_HEX) }

    /** MooTouchPoint() on zero-filled storage (re: ctor_probe), 1000 bytes. */
    private const val CTOR_HEX = "0x0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000f0bf000000000000f0bf000000000000f0bf000000000000f0bf00000000000000000000000000000000000000000000000000000000000000000000803f0000000000000000ffffffffffffffffffffffff0000000000000000ffffffff00000000000000000000f0bf000000000000f0bf000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000803f804fc347003c1c46000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000ffffffffffffffffffffffffffffffff00000000ffffffff00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000b04ab541000000000000000000000000000000000000000000000000ffffffffffffffffffffffff020000000400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000803f0000803f0000803f0000803f0000803f0000803f0000803f0000803f0000000000000000000000000000803f0000000000000000000000000000803f000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000803f00000000000000000000000000000000"
}
