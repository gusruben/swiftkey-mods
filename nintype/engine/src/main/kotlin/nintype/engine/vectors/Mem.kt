package nintype.engine.vectors

import nintype.engine.Bits

/**
 * Byte-level model of one C++ object for vector tests (see engine/VECTORS.md,
 * "C++ objects"). Built from an offset-keyed Struct (`"@0x238:dwell": Leaf`),
 * read and written by a port through typed accessors at raw offsets, then
 * re-serialized by the runner in the shape of the vector's expected `out`.
 *
 * Tracks, per byte: whether the input snapshot supplied it (`known`), its
 * input value (`initial`) and whether the port wrote it (`written`). That is
 * what the runner's `unchanged` check uses: a byte inside an unchanged range
 * that the port wrote with a different value (or wrote at all where the
 * input did not say what it was) is a mismatch.
 *
 * Pointer fields are children, never addresses:
 *  - `$ptr` (owned pointee, serialized inline) -> [ptrs], a nested MemImage
 *    or null for nullptr;
 *  - `$ref` (pointer to another named top-level object of the case) ->
 *    [refs], resolved against the case's other images by [MemWorld];
 *  - `$vec`/`$map` (gnustl std::vector begin/end/cap, or an in-order
 *    std::map walk) -> [seqs], the element Values as JSON. Ports that work
 *    on containers usually decode these into their own Kotlin model.
 */
class MemImage(val name: String = "", sizeHint: Int = 0) {
    private var data = ByteArray(maxOf(sizeHint, 16))
    private var initial = ByteArray(data.size)
    private var known = BooleanArray(data.size)
    private var written = BooleanArray(data.size)

    val ptrs = LinkedHashMap<Int, MemImage?>()
    val refs = LinkedHashMap<Int, MemImage?>()
    val refNames = LinkedHashMap<Int, String?>()
    val seqs = LinkedHashMap<Int, MutableList<JsonValue>>()

    private fun ensure(end: Int) {
        if (end <= data.size) return
        var n = data.size
        while (n < end) n *= 2
        data = data.copyOf(n); initial = initial.copyOf(n)
        known = known.copyOf(n); written = written.copyOf(n)
    }

    /** Loads input bytes (marks them known; does not count as a write). */
    fun load(off: Int, bytes: ByteArray) {
        ensure(off + bytes.size)
        for (i in bytes.indices) {
            data[off + i] = bytes[i]; initial[off + i] = bytes[i]; known[off + i] = true
        }
    }

    fun readBytes(off: Int, n: Int): ByteArray {
        ensure(off + n)
        return data.copyOfRange(off, off + n)
    }

    fun writeBytes(off: Int, bytes: ByteArray) {
        ensure(off + bytes.size)
        for (i in bytes.indices) { data[off + i] = bytes[i]; written[off + i] = true }
    }

    fun readLE(off: Int, width: Int): Long {
        ensure(off + width)
        var v = 0L
        for (i in width - 1 downTo 0) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
        return v
    }

    fun writeLE(off: Int, width: Int, v: Long) {
        ensure(off + width)
        for (i in 0 until width) { data[off + i] = (v ushr (8 * i)).toByte(); written[off + i] = true }
    }

    fun f32(off: Int): Float = Bits.bitsToFloat(readLE(off, 4).toInt())
    fun f64(off: Int): Double = Bits.bitsToDouble(readLE(off, 8))
    fun i32(off: Int): Int = readLE(off, 4).toInt()
    fun u16(off: Int): Int = readLE(off, 2).toInt()
    fun i16(off: Int): Int = readLE(off, 2).toShort().toInt()
    fun u8(off: Int): Int = readLE(off, 1).toInt()
    fun i8(off: Int): Int = readLE(off, 1).toByte().toInt()

    fun setF32(off: Int, v: Float) = writeLE(off, 4, Bits.floatToBits(v).toLong())
    fun setF64(off: Int, v: Double) = writeLE(off, 8, Bits.doubleToBits(v))
    fun setI32(off: Int, v: Int) = writeLE(off, 4, v.toLong())
    fun setU16(off: Int, v: Int) = writeLE(off, 2, v.toLong())
    fun setU8(off: Int, v: Int) = writeLE(off, 1, v.toLong())

    fun ptr(off: Int): MemImage? = ptrs[off]
    fun ref(off: Int): MemImage? = refs[off]
    fun seq(off: Int): MutableList<JsonValue> = seqs.getOrPut(off) { mutableListOf() }

    fun isWritten(off: Int): Boolean = off < written.size && written[off]
    fun isKnown(off: Int): Boolean = off < known.size && known[off]
    fun initialByte(off: Int): Byte = if (off < initial.size) initial[off] else 0
    fun byteAt(off: Int): Byte = if (off < data.size) data[off] else 0

    /** Serializes this image in the shape of [template] (an offset-keyed
     * Struct from the vector's expected `out`): same keys, same leaf types,
     * values read from this image. Only the template's keys and type tags
     * are used, never its values. */
    fun encodeLike(template: JsonValue): JsonValue {
        val out = LinkedHashMap<String, JsonValue>()
        for ((key, tv) in template.asObject()) {
            if (key.startsWith("$")) continue
            val off = MemKeys.offsetOf(key) ?: continue
            out[key] = encodeField(off, tv)
        }
        return JsonValue.JObject(out)
    }

    private fun encodeField(off: Int, tv: JsonValue): JsonValue {
        val o = tv.asObjectOrNull()
        if (o != null && o.containsKey("t") && o.containsKey("v")) {
            val t = o["t"]!!.asString()
            if (t == "bytes") {
                val n = (o["v"]!!.asString().removePrefix("0x").length) / 2
                return Values.bytesLeaf(readBytes(off, n))
            }
            return Leaf(t, readLE(off, Leaf.byteWidth(t))).toJson()
        }
        if (o != null && o.containsKey("\$ptr")) {
            val child = ptrs[off]
            val inner = o["\$ptr"]!!
            return JsonValue.JObject(mapOf("\$ptr" to (if (child == null || inner.isNull()) JsonValue.JNull else child.encodeLike(inner))))
        }
        if (o != null && o.containsKey("\$ref")) {
            val r = if (refs.containsKey(off)) refs[off]?.name else refNames[off]
            return JsonValue.JObject(mapOf("\$ref" to (r?.let { JsonValue.JString(it) } ?: JsonValue.JNull)))
        }
        if (o != null && (o.containsKey("\$vec") || o.containsKey("\$map"))) {
            val k = if (o.containsKey("\$vec")) "\$vec" else "\$map"
            return JsonValue.JObject(mapOf(k to JsonValue.JArray(seqs[off]?.toList() ?: emptyList())))
        }
        if (o != null) {
            // nested by-value sub-struct at this offset: its keys are offsets relative to `off`
            val out = LinkedHashMap<String, JsonValue>()
            for ((key, sub) in o) {
                if (key.startsWith("$")) continue
                val so = MemKeys.offsetOf(key) ?: continue
                out[key] = encodeField(off + so, sub)
            }
            return JsonValue.JObject(out)
        }
        throw IllegalArgumentException("$name@0x${off.toString(16)}: cannot encode template $tv")
    }

    companion object {
        /** Decodes an offset-keyed Struct into an image. `$ref` targets stay
         * unresolved (names only) until [MemWorld] links them. */
        fun decode(json: JsonValue, name: String = ""): MemImage {
            val o = json.asObject()
            val size = (o["\$size"] as? JsonValue.JNumber)?.value?.toInt() ?: 0
            val img = MemImage(name, size)
            img.decodeInto(0, o)
            return img
        }
    }

    private fun decodeInto(base: Int, o: Map<String, JsonValue>) {
        for ((key, v) in o) {
            if (key.startsWith("$")) continue
            val off = base + (MemKeys.offsetOf(key) ?: continue)
            val f = v.asObjectOrNull() ?: throw IllegalArgumentException("$name.$key: expected an object")
            when {
                f.containsKey("t") && f.containsKey("v") -> {
                    val t = f["t"]!!.asString()
                    if (t == "bytes") load(off, Values.parseBytes(f["v"]!!.asString()))
                    else {
                        val w = Leaf.byteWidth(t)
                        val bits = Bits.parseHex(f["v"]!!.asString(), w)
                        load(off, ByteArray(w) { (bits ushr (8 * it)).toByte() })
                    }
                }
                f.containsKey("\$ptr") -> ptrs[off] = f["\$ptr"]!!.let { if (it.isNull()) null else decode(it, "$name.$key") }
                f.containsKey("\$ref") -> refNames[off] = f["\$ref"]!!.asStringOrNull()
                f.containsKey("\$vec") -> seqs[off] = f["\$vec"]!!.asArray().toMutableList()
                f.containsKey("\$map") -> seqs[off] = f["\$map"]!!.asArray().toMutableList()
                else -> decodeInto(off, f)
            }
        }
    }
}

/** All named object images of one case, with `$ref` pointers linked. */
class MemWorld(inputs: Map<String, JsonValue>) {
    val images = LinkedHashMap<String, MemImage>()

    init {
        for ((k, v) in inputs) {
            val o = v.asObjectOrNull() ?: continue
            if (o.keys.any { MemKeys.offsetOf(it) != null }) images[k] = MemImage.decode(v, k)
        }
        fun link(img: MemImage) {
            for ((off, n) in img.refNames) img.refs[off] = n?.let { images[it] ?: throw IllegalArgumentException("unknown \$ref '$it'") }
            img.ptrs.values.forEach { it?.let(::link) }
        }
        images.values.forEach(::link)
    }

    operator fun get(name: String): MemImage = images[name] ?: throw IllegalArgumentException("no object '$name' in case inputs")
}

object MemKeys {
    /** "@0x238" or "@0x238:dwell" -> 0x238; any other key -> null. */
    fun offsetOf(key: String): Int? {
        if (!key.startsWith("@")) return null
        val hex = key.substring(1).substringBefore(':')
        return java.lang.Long.decode(hex).toInt()
    }
}

/** Constructors for the JSON value grammar (see engine/VECTORS.md). */
object Values {
    fun bytesLeaf(b: ByteArray): JsonValue = JsonValue.JObject(
        linkedMapOf("t" to JsonValue.JString("bytes"), "v" to JsonValue.JString("0x" + b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }))
    )

    fun parseBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x")
        return ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    fun ref(name: String?): JsonValue = JsonValue.JObject(mapOf("\$ref" to (name?.let { JsonValue.JString(it) } ?: JsonValue.JNull)))
    fun vec(items: List<JsonValue>): JsonValue = JsonValue.JObject(mapOf("\$vec" to JsonValue.JArray(items)))
    fun map(items: List<JsonValue>): JsonValue = JsonValue.JObject(mapOf("\$map" to JsonValue.JArray(items)))
    fun struct(vararg kv: Pair<String, JsonValue>): JsonValue = JsonValue.JObject(linkedMapOf(*kv))
}
