package nintype.engine.trie

/** A save file that does not parse: truncated, a wrong header, or a field past the end. */
class FlecFormatException(msg: String) : Exception(msg)

/**
 * Shing::Flector's binary form (Flector::binarize / save, read back by Flector::loadBinary /
 * load): an object is a header, u16 class version, u16 2, u32 payload size, then its recorded
 * fields in order, with no names. Fields, little-endian: std::string = u32 length + bytes;
 * plain scalars carry their size first (int = u8 4 + u32, signed char = u8 1 + the byte); Flag32
 * = u32; bool = u8; f32 = its bits; SDate = u32 seconds + u32 microseconds; std::vector /
 * std::deque of objects = u32 count + one object (header included) per element; std::vector<Flag32> = u32 count + u32s; std::map<std::string, T> = u32 count +
 * (key string, object) pairs in key order.
 */
class FlecWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun str(s: ByteArray) = apply { u32(s.size); bytes(s) }
    fun schar(v: Int) = apply { u8(1); u8(v) }
    fun int(v: Int) = apply { u8(4); u32(v) }
    fun date(d: SDate) = apply { u32(d.sec); u32(d.usec) }

    /** One object: [version] header around what [body] writes. */
    fun obj(version: Int, body: FlecWriter.() -> Unit) = apply {
        val inner = FlecWriter().apply(body).toByteArray()
        u16(version); u16(2); u32(inner.size); bytes(inner)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun file(version: Int, body: FlecWriter.() -> Unit): ByteArray = FlecWriter().obj(version, body).toByteArray()
    }
}

class FlecReader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    private fun need(n: Int) { if (n < 0 || pos + n > end) throw FlecFormatException("truncated at $pos (+$n of $end)") }
    fun u8(): Int { need(1); return b[pos++].toInt() and 0xff }
    fun u16(): Int = u8() or (u8() shl 8)
    fun u32(): Int = u16() or (u16() shl 16)
    fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
    fun str(): ByteArray = bytes(u32())
    fun schar(): Int { if (u8() != 1) throw FlecFormatException("signed char size at ${pos - 1}"); return u8().toByte().toInt() }
    fun int(): Int { if (u8() != 4) throw FlecFormatException("int size at ${pos - 1}"); return u32() }
    fun bool(): Boolean = u8() != 0
    fun date(): SDate = SDate(u32(), u32())
    fun count(): Int { val n = u32(); if (n < 0 || n > end - pos) throw FlecFormatException("count $n at $pos"); return n }
    val atEnd get() = pos >= end

    /** One object: checks the header and hands [body] a reader limited to the payload. */
    fun <T> obj(body: FlecReader.(version: Int) -> T): T {
        val version = u16()
        val magic = u16()
        if (magic != 2) throw FlecFormatException("bad object header $magic at ${pos - 2}")
        val size = u32()
        need(size)
        val r = FlecReader(b, pos, pos + size)
        pos += size
        return r.body(version)
    }

    companion object {
        fun <T> file(b: ByteArray, body: FlecReader.(version: Int) -> T): T = FlecReader(b).obj(body)
    }
}

/** Morestring::mexify @007a0b50 / demexify: file names for keys. A key of ASCII letters and
 *  digits only (isalnum) becomes "m" + key, anything else "h" + lowercase hex of its bytes. */
object Mexify {
    private fun alnum(c: Int) = c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a

    fun mexify(key: ByteArray): String =
        if (key.all { alnum(it.toInt() and 0xff) }) "m" + String(key, Charsets.ISO_8859_1)
        else "h" + key.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The key a file name stands for, or null when it is neither form. */
    fun demexify(name: String): ByteArray? = when {
        name.startsWith("m") -> name.substring(1).toByteArray(Charsets.ISO_8859_1)
        name.startsWith("h") && name.length % 2 == 1 -> {
            val h = name.substring(1)
            if (h.any { Character.digit(it, 16) < 0 }) null
            else ByteArray(h.length / 2) { ((Character.digit(h[2 * it], 16) shl 4) or Character.digit(h[2 * it + 1], 16)).toByte() }
        }
        else -> null
    }
}
