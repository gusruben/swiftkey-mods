package nintype.engine.vectors

import nintype.engine.Bits
import java.nio.file.Files
import java.nio.file.Path

/** One scalar leaf value: a type tag plus its raw bit pattern, as hex.
 * See engine/VECTORS.md for the full grammar this is part of. */
data class Leaf(val type: String, val bits: Long) {
    fun asFloat(): Float = Bits.bitsToFloat(bits.toInt())
    fun asDouble(): Double = Bits.bitsToDouble(bits)
    fun asInt(): Int = bits.toInt()
    fun asLong(): Long = bits
    fun asBool(): Boolean = bits != 0L

    fun toJson(): JsonValue = JsonValue.JObject(
        linkedMapOf(
            "t" to JsonValue.JString(type),
            "v" to JsonValue.JString(Bits.hex(bits, byteWidth(type))),
        )
    )

    companion object {
        fun byteWidth(type: String): Int = when (type) {
            "i8", "u8", "bool" -> 1
            "i16", "u16" -> 2
            "i32", "u32", "f32" -> 4
            "i64", "u64", "f64" -> 8
            else -> throw IllegalArgumentException("unknown leaf type '$type'")
        }

        fun f32(v: Float): Leaf = Leaf("f32", Bits.floatToBits(v).toLong() and 0xFFFFFFFFL)
        fun f64(v: Double): Leaf = Leaf("f64", Bits.doubleToBits(v))
        fun i32(v: Int): Leaf = Leaf("i32", v.toLong() and 0xFFFFFFFFL)
        fun u32(v: Int): Leaf = Leaf("u32", v.toLong() and 0xFFFFFFFFL)
        fun bool(v: Boolean): Leaf = Leaf("bool", if (v) 1L else 0L)

        fun fromJson(j: JsonValue): Leaf {
            val obj = j.asObject()
            val type = obj["t"]!!.asString()
            val v = obj["v"]!!.asString()
            return Leaf(type, Bits.parseHex(v, byteWidth(type)))
        }
    }
}

/** One test case from a vector file: scalar call args, named struct
 * snapshots before (`inputs`) and after (`outputs`) the call, and the
 * expected return value (null for void). */
data class VectorCase(
    val args: List<Leaf>,
    val inputs: Map<String, JsonValue>,
    val outputs: Map<String, JsonValue>,
    val ret: Leaf?,
    /** The raw `ret` Value when it is not a scalar Leaf (a `$ref`, `$ptr`
     * or Struct); null when absent. Scalar returns also set [ret]. */
    val retJson: JsonValue? = null,
    /** `unchanged`: object name (or `name/@0xOFF` path through `$ptr`
     * children) -> half-open byte ranges that must not change. */
    val unchanged: Map<String, List<IntRange>> = emptyMap(),
    /** Free-form per-case label from the generator (`note`), for reports. */
    val note: String = "",
) {
    /** All `in` objects that are offset-keyed structs, decoded and linked. */
    fun world(): MemWorld = MemWorld(inputs)

    companion object {
        fun isLeaf(j: JsonValue): Boolean {
            val o = j.asObjectOrNull() ?: return false
            return o.containsKey("t") && o.containsKey("v") && o["t"]!!.asStringOrNull() != "bytes"
        }

        fun fromJson(j: JsonValue): VectorCase {
            val obj = j.asObject()
            val args = (obj["args"] as? JsonValue.JArray)?.items?.map { Leaf.fromJson(it) } ?: emptyList()
            val inputs = obj["in"]?.asObjectOrNull() ?: emptyMap()
            val outputs = obj["out"]?.asObjectOrNull() ?: emptyMap()
            val retRaw = obj["ret"]?.let { if (it.isNull()) null else it }
            val ret = retRaw?.let { if (isLeaf(it)) Leaf.fromJson(it) else null }
            val unchanged = obj["unchanged"]?.asObjectOrNull()?.mapValues { (_, ranges) ->
                ranges.asArray().map { r ->
                    val a = r.asArray()
                    java.lang.Long.decode(a[0].asString()).toInt() until java.lang.Long.decode(a[1].asString()).toInt()
                }
            } ?: emptyMap()
            val note = obj["note"]?.asStringOrNull() ?: ""
            return VectorCase(args, inputs, outputs, ret, retRaw, unchanged, note)
        }
    }
}

/** A whole vector file: header + cases. See engine/VECTORS.md. */
data class VectorFile(
    val function: String,
    val address: String,
    val generator: String,
    val signature: String,
    val argTypes: List<String>,
    val retType: String?,
    val cases: List<VectorCase>,
) {
    companion object {
        /** Reads `*.json`, or `*.json.gz` (gzip; used for the big whole-object trace files). */
        fun load(path: Path): VectorFile =
            if (path.toString().endsWith(".gz"))
                parse(java.util.zip.GZIPInputStream(Files.newInputStream(path), 1 shl 16).use { String(it.readBytes(), Charsets.UTF_8) })
            else parse(Files.readString(path))

        fun parse(text: String): VectorFile {
            val root = Json.parse(text).asObject()
            val argTypes = (root["argTypes"] as? JsonValue.JArray)?.items?.map { it.asString() } ?: emptyList()
            val cases = (root["cases"] as JsonValue.JArray).items.map { VectorCase.fromJson(it) }
            return VectorFile(
                function = root["function"]!!.asString(),
                address = root["address"]?.asStringOrNull() ?: "",
                generator = root["generator"]?.asStringOrNull() ?: "",
                signature = root["signature"]?.asStringOrNull() ?: "",
                argTypes = argTypes,
                retType = root["retType"]?.asStringOrNull(),
                cases = cases,
            )
        }
    }
}

/** What a registered port adapter hands back for one case: the return
 * value (if the function isn't void) and any mutated named objects, in the
 * same shape as the vector case's `out` (see engine/VECTORS.md). An object
 * the port didn't touch can simply be left out of `out` -- the runner only
 * compares keys the *vector file's* `out` actually lists. */
data class CaseResult(
    val ret: Leaf? = null,
    val out: Map<String, JsonValue> = emptyMap(),
    /** Non-scalar return (e.g. `Values.ref("p3")` for a returned pointer). */
    val retValue: JsonValue? = null,
    /** Mutated object images by `in`/`out` name. The runner serializes an
     * image in the shape of the expected `out` when [out] lacks that key,
     * and uses it for the case's `unchanged` checks. */
    val mem: Map<String, MemImage> = emptyMap(),
)

typealias PortAdapter = (VectorCase) -> CaseResult
