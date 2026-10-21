// Reader for Nintype / Keyboard 69 language packs (assets/<lang>.nbipack).
// Plain Kotlin/JVM, no Android dependencies. Byte-level spec: ../FORMAT.md.
//
//   file --boris_cipher(decrypt, "hazmatlamlay")--> LZF --lzf_decompress--> Flector stream
//        --> NBIPack header, wordcount words (WordData or compact strings), chaincount NBIChainData
//
// This mirrors the *file contents* (what NBIPackFile::load @00ccec10 sees). The runtime loader
// (LangEnv::loadNBIPackFlector @004b5e58) uses a subset: see Word.runtimeWord / FORMAT.md section 6.

package nintype.dict

class NinDictFormatException(msg: String) : RuntimeException(msg)

data class PackHeader(
    val version: Int,
    val flags: Int,
    val language: String,
    val specialchars: List<Int>,
    val chainImdbMinimum: Long,
    val chainAssumedAverageScore: Float,
    val chaincount: Int,
    val wordcount: Int,
    val compactwords: Boolean,
    val typoMinfreq: Long,
    val creditlines: List<String>,
    val corpusWordcount: Long,
    val corpusCharcount: Long,
)

data class Word(
    val word: String,
    /** WordData.cookedword; "" for compact packs (NBIPackFile::load drops it). */
    val cooked: String,
    /** Flag64 at WordData+0x08 (0 for compact). */
    val flags: Long,
    /** Flag32 at WordData+0x10. Compact packs: bits 5/6/7 from the flag byte. */
    val spellingmode: Int,
    /** WordData+0x24. Compact: scorebyte / 255f. */
    val multscore: Float,
    val extrascore: Float = 0f,
    val ranking: Long = 0,
    val freqnum: Long = 0,
    /** Compact only: raw score byte, raw flag byte, and the optional second string. */
    val scorebyte: Int = -1,
    val flagbyte: Int = -1,
    val fileCooked: String? = null,
) {
    /** Word as handed to CompactTrie by the runtime loader: full packs lowercase it
     *  (Morestring::toLowerInPlace, ASCII-only per FORMAT.md), compact packs keep it as is. */
    fun runtimeWord(compact: Boolean): String = if (compact) word else asciiLower(word)

    /** Compact flag byte bit 0x08 (spellingmode bit 14 in the writer). The runtime loader uses it
     *  only to decide whether to read the second string (FORMAT.md section 4.2). */
    val flag14: Boolean get() = flagbyte >= 0 && (flagbyte and 8) != 0
}

data class Chain(val words: String, val correctionratio: Float)

data class Pack(
    val header: PackHeader,
    val words: List<Word>,
    val chains: List<Chain>,
)

internal fun asciiLower(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
    return sb.toString()
}

object NinDict {
    val PACK_KEY: ByteArray = "hazmatlamlay".toByteArray(Charsets.US_ASCII)

    // ------------------------------------------------------------ layer 1: boris_cipher

    /** Shing::make_int_from_indexex: big-endian int32 of key[idx until idx+4], '?'-padded. */
    internal fun keyInt(key: ByteArray, idx: Int): Int {
        var v = 0
        for (i in 0 until 4) {
            val b = if (idx + i < key.size) key[idx + i].toInt() and 0xFF else '?'.code
            v = (v shl 8) or b
        }
        return v
    }

    /** Shing::pancho_cipher_base(char*, int, bool, int, int) @00e1922c, in place. */
    fun panchoCipherBase(buf: ByteArray, encrypt: Boolean, key: Int, round: Int) {
        val n = buf.size
        val k = (if (encrypt) key else -key) and 0xFF
        var prev = 0x17
        var a = 1
        var b = 1
        val backward = round % 2 == 0
        var u = if (backward) n - 1 else 0
        for (step in 0 until n) {
            val c = buf[u].toInt() and 0xFF
            val d: Int
            if (u and 1 == 0) {
                d = -(k * ((prev + u / 2 + 0x55) and 0xFF))
            } else {
                val nf = (a + b) % 103
                d = k * ((prev + nf + 0x67) and 0xFF)
                a = b
                b = nf
            }
            val out = (d + c) and 0xFF
            buf[u] = out.toByte()
            prev = if (encrypt) out else c
            if (backward) u-- else u++
        }
    }

    /** Shing::boris_cipher(Membuf&, false, key, 0, 8, 8) @00e1a2dc. */
    fun borisDecrypt(data: ByteArray, key: ByteArray = PACK_KEY): ByteArray {
        val buf = data.copyOf()
        val rounds = (key.size + 3) / 4
        for (r in rounds - 1 downTo 0) panchoCipherBase(buf, false, keyInt(key, r * 4), r)
        if (buf.size < 5) throw NinDictFormatException("boris: buffer too short")
        val s = buf.size
        val ln = ((buf[s - 4].toInt() and 0xFF) shl 24) or ((buf[s - 3].toInt() and 0xFF) shl 16) or
            ((buf[s - 2].toInt() and 0xFF) shl 8) or (buf[s - 1].toInt() and 0xFF)
        return if (ln in 0..s) buf.copyOf(ln) else buf
    }

    // ------------------------------------------------------------ layer 2: LZF

    /** liblzf lzf_decompress @013e14b0 (raw stream, no header). */
    fun lzfDecompress(data: ByteArray): ByteArray {
        var out = ByteArray(maxOf(64, data.size * 3))
        var op = 0
        var ip = 0
        val n = data.size
        fun ensure(extra: Int) {
            if (op + extra > out.size) out = out.copyOf(maxOf(out.size * 2, op + extra))
        }
        while (ip < n) {
            val c = data[ip++].toInt() and 0xFF
            if (c < 32) {
                val ln = c + 1
                if (ip + ln > n) throw NinDictFormatException("lzf: literal overrun at $ip")
                ensure(ln)
                System.arraycopy(data, ip, out, op, ln)
                ip += ln; op += ln
            } else {
                var ln = c shr 5
                if (ln == 7) ln += data[ip++].toInt() and 0xFF
                var ref = op - ((c and 0x1F) shl 8) - (data[ip++].toInt() and 0xFF) - 1
                if (ref < 0) throw NinDictFormatException("lzf: back reference before start")
                ensure(ln + 2)
                repeat(ln + 2) { out[op++] = out[ref++] }
            }
        }
        return out.copyOf(op)
    }

    // ------------------------------------------------------------ layer 3: Flector stream

    private class Reader(val d: ByteArray) {
        var p = 0
        fun need(k: Int) {
            if (p + k > d.size) throw NinDictFormatException("need $k bytes at $p, have ${d.size - p}")
        }
        fun u8(): Int { need(1); return d[p++].toInt() and 0xFF }
        fun rawI32(): Int {
            need(4)
            val v = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or
                ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        fun rawI64(): Long {
            val lo = rawI32().toLong() and 0xFFFFFFFFL
            val hi = rawI32().toLong() and 0xFFFFFFFFL
            return lo or (hi shl 32)
        }
        fun f32(): Float = Float.fromBits(rawI32())
        fun bool(): Boolean = u8() != 0
        /** IntFieldHandler::load @00c7625c: size byte (1/2/4/8) + LE value, sign-extended. */
        fun sizedInt(): Long {
            val sz = u8()
            if (sz != 1 && sz != 2 && sz != 4 && sz != 8) throw NinDictFormatException("bad int size $sz at ${p - 1}")
            need(sz)
            var v = 0L
            for (i in 0 until sz) v = v or ((d[p + i].toLong() and 0xFF) shl (8 * i))
            p += sz
            val shift = 64 - 8 * sz
            return (v shl shift) shr shift
        }
        fun bytes(): ByteArray {
            val n = rawI32()
            if (n < 0) throw NinDictFormatException("negative string length at ${p - 4}")
            need(n)
            val b = d.copyOfRange(p, p + n)
            p += n
            return b
        }
        fun string(): String = String(bytes(), Charsets.UTF_8)
        fun vecI32(): List<Int> { val n = rawI32(); return List(maxOf(n, 0)) { rawI32() } }
        fun vecStr(): List<String> { val n = rawI32(); return List(maxOf(n, 0)) { string() } }
        /** Flector::load @00c7cadc: raw int32, low 16 bits = version, high = flags. */
        fun header(): Pair<Int, Int> { val h = rawI32(); return Pair(h and 0xFFFF, h ushr 16) }
    }

    private fun readHeader(r: Reader): PackHeader {
        val (ver, flags) = r.header()
        val language = r.string()
        val special = if (ver >= 5) r.vecI32() else emptyList()
        val imdb = r.sizedInt()
        val avg = r.f32()
        val chaincount = r.sizedInt().toInt()
        val wordcount = r.sizedInt().toInt()
        val compact = r.bool()
        val typo = r.sizedInt()
        val credits = if (ver >= 3) r.vecStr() else emptyList()
        val cwc = if (ver >= 4) r.sizedInt() else 0L
        val ccc = if (ver >= 4) r.sizedInt() else 0L
        return PackHeader(ver, flags, language, special, imdb, avg, chaincount, wordcount, compact,
            typo, credits, cwc, ccc)
    }

    private fun readWordData(r: Reader): Word {
        val (ver, _) = r.header()
        val word = r.string()
        val cooked = if (ver >= 4) r.string() else ""
        val flags = r.rawI64()
        val spelling = r.rawI32()
        val mult = if (ver >= 3) r.f32() else 0f
        val extra = if (ver >= 3) r.f32() else 0f
        val ranking = r.sizedInt()
        val freq = r.sizedInt()
        return Word(word, cooked, flags, spelling, mult, extra, ranking, freq)
    }

    /** Emkey::decipher_compact_word @00832308 plus the optional second string. */
    private fun readCompactWord(r: Reader): Word {
        val s = r.bytes()
        if (s.size < 3) return Word("", "", 0, 0, 0f, scorebyte = 0, flagbyte = 0)
        val sb = s[s.size - 2].toInt() and 0xFF
        val fb = s[s.size - 1].toInt() and 0xFF
        var spelling = 0
        if (fb and 1 != 0) spelling = spelling or (1 shl 5)
        if (fb and 2 != 0) spelling = spelling or (1 shl 6)
        if (fb and 4 != 0) spelling = spelling or (1 shl 7)
        val word = String(s, 0, s.size - 2, Charsets.UTF_8)
        val fileCooked = if (fb and 0x10 != 0) r.string() else null
        return Word(word, "", 0, spelling, sb.toFloat() / 255f, scorebyte = sb, flagbyte = fb, fileCooked = fileCooked)
    }

    private fun readChain(r: Reader): Chain {
        r.header()
        return Chain(r.string(), r.f32())
    }

    fun parse(plain: ByteArray): Pack {
        val r = Reader(plain)
        val h = readHeader(r)
        if (h.language.isEmpty()) return Pack(h, emptyList(), emptyList())
        val words = ArrayList<Word>(h.wordcount)
        repeat(h.wordcount) { words.add(if (h.compactwords) readCompactWord(r) else readWordData(r)) }
        val chains = ArrayList<Chain>(h.chaincount)
        repeat(h.chaincount) { chains.add(readChain(r)) }
        if (r.p != plain.size) throw NinDictFormatException("${plain.size - r.p} trailing bytes")
        return Pack(h, words, chains)
    }

    /** Full pipeline from the raw .nbipack bytes. */
    fun load(nbipack: ByteArray): Pack = parse(lzfDecompress(borisDecrypt(nbipack)))
}
