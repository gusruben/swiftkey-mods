package nintype.engine.vectors

/**
 * A tiny, dependency-free JSON parser.
 *
 * Deliberately hand-rolled rather than pulling in Gson/Jackson/org.json:
 * this engine will eventually be embedded in an Android (ReVanced) build,
 * where `org.json` is a platform-provided stub that conflicts with the
 * bundled jar of the same name, and reflection-heavy parsers drag in a lot
 * of surface area for a format this simple. Good enough for vector files
 * (no streaming, no comments, no trailing commas) -- not a general-purpose
 * JSON library.
 */
sealed class JsonValue {
    data class JObject(val fields: Map<String, JsonValue>) : JsonValue()
    data class JArray(val items: List<JsonValue>) : JsonValue()
    data class JString(val value: String) : JsonValue()
    data class JNumber(val value: Double) : JsonValue()
    data class JBool(val value: Boolean) : JsonValue()
    object JNull : JsonValue()

    operator fun get(key: String): JsonValue? = (this as? JObject)?.fields?.get(key)
    operator fun get(index: Int): JsonValue? = (this as? JArray)?.items?.getOrNull(index)

    fun asObjectOrNull(): Map<String, JsonValue>? = (this as? JObject)?.fields
    fun asObject(): Map<String, JsonValue> = (this as JObject).fields
    fun asArray(): List<JsonValue> = (this as JArray).items
    fun asStringOrNull(): String? = (this as? JString)?.value
    fun asString(): String = (this as JString).value
    fun asDouble(): Double = (this as JNumber).value
    fun asInt(): Int = (this as JNumber).value.toInt()
    fun isNull(): Boolean = this is JNull
}

object Json {
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): JsonValue {
            skipWs()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JString(parseStringRaw())
                't' -> { expect("true"); JsonValue.JBool(true) }
                'f' -> { expect("false"); JsonValue.JBool(false) }
                'n' -> { expect("null"); JsonValue.JNull }
                else -> parseNumber()
            }
        }

        fun expect(lit: String) {
            require(s.regionMatches(i, lit, 0, lit.length)) { "expected '$lit' at $i in: ${context()}" }
            i += lit.length
        }

        fun context(): String = s.substring(maxOf(0, i - 20), minOf(s.length, i + 20))

        fun parseObject(): JsonValue.JObject {
            i++ // '{'
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return JsonValue.JObject(map)
            }
            while (true) {
                skipWs()
                val key = parseStringRaw()
                skipWs()
                require(s[i] == ':') { "expected ':' at $i in: ${context()}" }
                i++
                val value = parseValue()
                map[key] = value
                skipWs()
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $i in: ${context()}")
                }
            }
            return JsonValue.JObject(map)
        }

        fun parseArray(): JsonValue.JArray {
            i++ // '['
            val list = ArrayList<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return JsonValue.JArray(list)
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; break }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $i in: ${context()}")
                }
            }
            return JsonValue.JArray(list)
        }

        fun parseStringRaw(): String {
            require(s[i] == '"') { "expected '\"' at $i in: ${context()}" }
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape at $i")
                    }
                    i++
                } else {
                    sb.append(s[i])
                    i++
                }
            }
            i++ // closing quote
            return sb.toString()
        }

        fun parseNumber(): JsonValue.JNumber {
            val start = i
            if (s[i] == '-' || s[i] == '+') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return JsonValue.JNumber(s.substring(start, i).toDouble())
        }
    }
}
