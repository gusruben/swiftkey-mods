package nintype.engine.vectors

import nintype.engine.Bits
import nintype.engine.SoftFloat

/** Result of running one vector file against a registered port adapter. */
data class SuiteResult(
    val function: String,
    val totalCases: Int,
    val fieldsChecked: Int,
    val mismatches: List<String>,
    val maxReported: Int,
) {
    val passed: Boolean get() = mismatches.isEmpty()

    /** Human-readable report: pass/fail summary plus (if any) the first
     * [maxReported] field-level diffs, one per line. */
    fun report(): String {
        val head = if (passed) {
            "$function: PASS ($totalCases cases, $fieldsChecked fields checked)"
        } else {
            "$function: FAIL ($totalCases cases, ${mismatches.size}${if (mismatches.size >= maxReported) "+" else ""} mismatches)"
        }
        return if (mismatches.isEmpty()) head else head + "\n" + mismatches.joinToString("\n") { "  $it" }
    }
}

/** Loads vector files and dispatches each case to a registered port
 * adapter, comparing bit-exactly and collecting the first N field-level
 * mismatches. See engine/VECTORS.md for the vector format this reads. */
object VectorRunner {

    const val DEFAULT_MAX_REPORTED = 20

    fun runSuite(file: VectorFile, adapter: PortAdapter, maxReported: Int = DEFAULT_MAX_REPORTED): SuiteResult {
        val mismatches = mutableListOf<String>()
        var checked = 0

        file.cases.forEachIndexed { i, case ->
            val result = try {
                adapter(case)
            } catch (e: Exception) {
                addMismatch(mismatches, maxReported, "case $i: port threw ${e::class.simpleName}: ${e.message}")
                checked++
                return@forEachIndexed
            }
            val tag = if (case.note.isEmpty()) "case $i" else "case $i (${case.note})"

            if (case.ret != null) {
                checked++
                val actual = result.ret ?: result.retValue?.let { if (VectorCase.isLeaf(it)) Leaf.fromJson(it) else null }
                if (actual == null) {
                    addMismatch(mismatches, maxReported, "$tag ret: expected ${hexOf(case.ret)}, got <none returned by port>")
                } else if (!leafEquals(case.ret, actual)) {
                    addMismatch(mismatches, maxReported, "$tag ret: expected ${hexOf(case.ret)}, got ${hexOf(actual)}")
                }
            } else if (case.retJson != null) {
                val actual = result.retValue
                if (actual == null) {
                    addMismatch(mismatches, maxReported, "$tag ret: expected ${case.retJson}, got <none returned by port>")
                    checked++
                } else {
                    checked += compareJson(case.retJson, actual, "$tag ret", mismatches, maxReported)
                }
            }

            for ((key, expectedObj) in case.outputs) {
                val actualObj = result.out[key] ?: result.mem[key]?.encodeLike(expectedObj)
                if (actualObj == null) {
                    addMismatch(mismatches, maxReported, "$tag $key: expected a mutated snapshot, port returned none")
                    continue
                }
                checked += compareJson(expectedObj, actualObj, "$tag $key", mismatches, maxReported)
            }

            for ((path, ranges) in case.unchanged) {
                val img = resolveImage(result.mem, path)
                if (img == null) {
                    addMismatch(mismatches, maxReported, "$tag $path: has unchanged ranges but the port returned no memory image")
                    checked++
                    continue
                }
                for (r in ranges) {
                    checked++
                    for (off in r) {
                        if (!img.isWritten(off)) continue
                        if (!img.isKnown(off) || img.byteAt(off) != img.initialByte(off)) {
                            addMismatch(mismatches, maxReported, "$tag $path@0x${off.toString(16)}: inside unchanged range [0x${r.first.toString(16)},0x${(r.last + 1).toString(16)}) but the port changed it")
                            break
                        }
                    }
                }
            }
        }

        return SuiteResult(file.function, file.cases.size, checked, mismatches, maxReported)
    }

    private fun addMismatch(into: MutableList<String>, max: Int, msg: String) {
        if (into.size < max) into.add(msg)
    }

    private fun hexOf(leaf: Leaf): String = Bits.hex(leaf.bits, Leaf.byteWidth(leaf.type))

    private fun leafEquals(expected: Leaf, actual: Leaf): Boolean {
        if (expected.type != actual.type) return false
        return when (expected.type) {
            "f32" -> SoftFloat.f32BitsEqual(expected.bits.toInt(), actual.bits.toInt())
            "f64" -> SoftFloat.f64BitsEqual(expected.bits, actual.bits)
            else -> expected.bits == actual.bits
        }
    }

    /** Recursively compares a Leaf/Struct/Array JSON tree, returning the
     * number of leaves checked and appending any mismatches found (up to
     * `max`, after which it still counts but stops recording messages). */
    private fun resolveImage(mem: Map<String, MemImage>, path: String): MemImage? {
        val parts = path.split('/')
        var img = mem[parts[0]] ?: return null
        for (p in parts.drop(1)) img = img.ptr(MemKeys.offsetOf(p) ?: return null) ?: return null
        return img
    }

    private fun compareJson(expected: JsonValue, actual: JsonValue, path: String, mismatches: MutableList<String>, max: Int): Int {
        val expectedObj = expected.asObjectOrNull()

        // Raw bytes leaf: exact hex comparison.
        if (expectedObj != null && expectedObj["t"]?.asStringOrNull() == "bytes") {
            val e = expectedObj["v"]!!.asString().lowercase()
            val a = actual["v"]?.asStringOrNull()?.lowercase()
            if (e != a) addMismatch(mismatches, max, "$path: expected bytes $e, got $a")
            return 1
        }

        // Pointer to an owned pointee, a named object, or a container's contents.
        if (expectedObj != null && expectedObj.containsKey("\$ptr")) {
            val e = expectedObj["\$ptr"]!!
            val a = actual["\$ptr"]
            if (a == null) { addMismatch(mismatches, max, "$path: expected a \$ptr, got $actual"); return 1 }
            if (e.isNull() || a.isNull()) {
                if (e.isNull() != a.isNull()) addMismatch(mismatches, max, "$path: expected ${if (e.isNull()) "nullptr" else "non-null"}, got ${if (a.isNull()) "nullptr" else "non-null"}")
                return 1
            }
            return compareJson(e, a, "$path->", mismatches, max)
        }
        if (expectedObj != null && expectedObj.containsKey("\$ref")) {
            val e = expectedObj["\$ref"]!!.asStringOrNull()
            val a = actual["\$ref"]?.asStringOrNull()
            if (e != a || actual["\$ref"] == null) addMismatch(mismatches, max, "$path: expected ref ${e ?: "nullptr"}, got ${actual["\$ref"]?.let { a ?: "nullptr" } ?: actual}")
            return 1
        }
        for (k in listOf("\$vec", "\$map")) {
            if (expectedObj != null && expectedObj.containsKey(k)) {
                val e = expectedObj[k]!!.asArray()
                val a = actual[k]?.asArray()
                if (a == null) { addMismatch(mismatches, max, "$path: expected $k, got $actual"); return 1 }
                if (a.size != e.size) { addMismatch(mismatches, max, "$path: expected $k of ${e.size} elements, got ${a.size}"); return 1 }
                var n = 1
                e.forEachIndexed { idx, el -> n += compareJson(el, a[idx], "$path[$idx]", mismatches, max) }
                return n
            }
        }

        // A Leaf is a JObject with exactly {"t", "v"}.
        if (expectedObj != null && expectedObj.containsKey("t") && expectedObj.containsKey("v")) {
            val expLeaf = Leaf.fromJson(expected)
            val actLeaf = try {
                Leaf.fromJson(actual)
            } catch (e: Exception) {
                addMismatch(mismatches, max, "$path: expected ${hexOf(expLeaf)}, got unparseable value ($actual)")
                return 1
            }
            if (!leafEquals(expLeaf, actLeaf)) {
                addMismatch(mismatches, max, "$path: expected ${hexOf(expLeaf)}, got ${hexOf(actLeaf)}")
            }
            return 1
        }

        if (expected is JsonValue.JArray) {
            val actArr = (actual as? JsonValue.JArray)?.items
            var n = 0
            expected.items.forEachIndexed { idx, el ->
                val actEl = actArr?.getOrNull(idx)
                if (actEl == null) {
                    addMismatch(mismatches, max, "$path[$idx]: expected a value, got none")
                    n++
                } else {
                    n += compareJson(el, actEl, "$path[$idx]", mismatches, max)
                }
            }
            return n
        }

        if (expectedObj != null) {
            val actObj = actual.asObjectOrNull()
            var n = 0
            for ((field, expVal) in expectedObj) {
                if (field.startsWith("$")) continue // metadata ($size, ...)
                val actVal = actObj?.get(field)
                if (actVal == null) {
                    addMismatch(mismatches, max, "$path.$field: expected a value, got none")
                    n++
                } else {
                    n += compareJson(expVal, actVal, "$path.$field", mismatches, max)
                }
            }
            return n
        }

        // Fallback (shouldn't normally happen -- every scalar should be a Leaf).
        if (expected != actual) addMismatch(mismatches, max, "$path: expected $expected, got $actual")
        return 1
    }
}

/** Where ported Kotlin functions register themselves, keyed by the
 * vector file's `function` string. One registry shared by tests and the
 * scorecard tool so "is this function ported" has one source of truth. */
object PortRegistry {
    private val functions = LinkedHashMap<String, PortAdapter>()

    /** Names registered more than once. Several areas port in parallel: that means two ports of one function. */
    val duplicates = sortedSetOf<String>()

    fun register(name: String, adapter: PortAdapter) {
        if (name in functions) duplicates.add(name)
        functions[name] = adapter
    }

    fun get(name: String): PortAdapter? = functions[name]

    fun allNames(): Set<String> = functions.keys
}
