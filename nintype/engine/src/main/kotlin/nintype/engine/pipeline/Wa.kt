package nintype.engine.pipeline

import nintype.engine.pathscore.Strs
import nintype.engine.vectors.MemImage

/**
 * `Emkey::WordAlternative` (0x50 bytes) helpers for the pipeline, on pathscore's model: a
 * MemImage of the scalar bytes plus the five std::strings in [Strs] (+0 display text, +4 word,
 * +8, +0xc, +0x4c debug path text) and the Refcounter<PredPath> at +0x48 in refs.
 *
 * Fields the pipeline uses: +0x10 u32, +0x14 TrieWord id (-1 none), +0x18 ender TrieWord id,
 * +0x1c char count / covered points, +0x20 score, +0x24 norm, +0x28, +0x2c final score (the sort
 * key, descending), +0x30 flags (0x20 forced explicit, 0x40 wild guess, 0x100 literal, 0x200
 * literal or literal-equal, 0x4000 / 0x8000 caps hints, 0x10000 two-word, 0x20000 autocomplete,
 * 0x80000 placeholder, 0x100000 quote literal, 0x200000 glued literal, 0x800000 weird-caps
 * converted), +0x3c i8 penalty / literal reason, +0x3d i8 its priority, +0x40, +0x44.
 * WordAlternative() keeps bits 27..31 of +0x30 from whatever was in memory (stack garbage in
 * the binary), and so is the padding byte +0x3f; here they are 0, and comparisons with the
 * oracle mask them.
 */
object Wa {
    const val SIZE = 0x50
    private val STRS = intArrayOf(0, 4, 8, 0xc, 0x4c)

    /** WordAlternative() @0083ff14. */
    fun new(name: String = "wa"): MemImage = MemImage(name, SIZE).also {
        it.writeBytes(0, ByteArray(SIZE))
        nintype.engine.pathscore.WordAlternative.init(it)
    }

    /** WordAlternative(WordAlternative const&): bytes, strings, the PredPath ref. */
    fun copy(src: MemImage, name: String = src.name): MemImage = MemImage(name, SIZE).also { assign(it, src) }

    /** operator=: the same member-wise copy into an existing image. */
    fun assign(dst: MemImage, src: MemImage) {
        if (dst === src) return
        dst.writeBytes(0, src.readBytes(0, SIZE))
        dst.refs[0x48] = src.refs[0x48]
        for (o in STRS) Strs.set(dst, o, Strs.get(src, o).copyOf())
    }

    fun s(wa: MemImage, off: Int): ByteArray = Strs.get(wa, off)
    fun setS(wa: MemImage, off: Int, v: ByteArray) = Strs.set(wa, off, v.copyOf())
    fun word(wa: MemImage): ByteArray = Strs.get(wa, 4)
    fun flags(wa: MemImage): Int = wa.i32(0x30)
    fun orFlags(wa: MemImage, f: Int) = wa.setI32(0x30, wa.i32(0x30) or f)
    fun trieWord(wa: MemImage): Int? = wa.i32(0x14).takeIf { it != -1 }

    /** flagLiteralReason(WALiteralReason) @00843ae4: Shing::Flag32::flag(this+0x38, r, true), bit r of the u32 at +0x38. */
    fun flagLiteralReason(wa: MemImage, r: Int) = wa.setI32(0x38, wa.i32(0x38) or (1 shl r))

    /** operator< @00836484: a.+0x2c > b.+0x2c (descending final score). */
    fun less(a: MemImage, b: MemImage): Boolean = nintype.engine.SoftFloat.fcmpgt(a.f32(0x2c), b.f32(0x2c))

    /** std::sort with operator< (gnustl introsort, so ties land where the binary puts them). */
    fun sort(v: MutableList<MemImage>) = nintype.engine.touch.StdSort.sort(v, ::less)

    fun deepCopy(v: List<MemImage>): MutableList<MemImage> = v.mapTo(ArrayList()) { copy(it) }

    /** The decoder-meaningful content, for comparisons: the scalar bytes minus the string and
     * PredPath pointer words and the garbage bits of +0x30, then the five strings. */
    fun summary(wa: MemImage): String {
        val b = wa.readBytes(0, SIZE)
        for (o in STRS + 0x48) for (k in 0 until 4) b[o + k] = 0
        b[0x33] = (b[0x33].toInt() and 0x07).toByte()
        b[0x3f] = 0       // padding WordAlternative() leaves uninitialised
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) } + "|" +
            STRS.joinToString(",") { o -> Strs.get(wa, o).joinToString("") { "%02x".format(it.toInt() and 0xff) } }
    }
}
