package nintype.engine.touch

import nintype.engine.Bits
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.Values

/**
 * `Emkey::TravKeyData` (32 bytes), one key a MooTouchPoint traversed, held in the gnustl
 * vector at MooTouchPoint+0x328. Raw 32-bit words so float bit patterns survive:
 *
 *   +0x00 f32 x, +0x04 f32 y   key position (set by Moopad::addTraversal)
 *   +0x08 i32 chara           the key's character (pushTrav stores the char register)
 *   +0x0c i32 n               pushTrav's int argument
 *   +0x10 f32 score           pushTrav's float argument; sortTravs orders ascending by it
 *   +0x14 f32 coverage        1.0 from pushTrav, rewritten by syncCoverage
 *   +0x18, +0x1c              zero from pushTrav
 */
class TravKeyData(val w: IntArray = IntArray(8)) {
    var x: Float get() = f(0); set(v) = sf(0, v)
    var y: Float get() = f(1); set(v) = sf(1, v)
    var chara: Int get() = w[2]; set(v) { w[2] = v }
    var n: Int get() = w[3]; set(v) { w[3] = v }
    var score: Float get() = f(4); set(v) = sf(4, v)
    var coverage: Float get() = f(5); set(v) = sf(5, v)

    private fun f(i: Int) = Bits.bitsToFloat(w[i])
    private fun sf(i: Int, v: Float) { w[i] = Bits.floatToBits(v) }

    fun copy() = TravKeyData(w.copyOf())

    /** Offset-keyed JSON in the generator's full layout (re/vectors/gen_touch.py TRAV). */
    fun toJson(): JsonValue = Values.struct(
        "@0x0:x" to leaf("f32", 0), "@0x4:y" to leaf("f32", 1), "@0x8:chara" to leaf("i32", 2),
        "@0xc:n" to leaf("i32", 3), "@0x10:score" to leaf("f32", 4), "@0x14:coverage" to leaf("f32", 5),
        "@0x18" to leaf("u32", 6), "@0x1c" to leaf("u32", 7),
    )

    private fun leaf(t: String, i: Int) = Leaf(t, w[i].toLong() and 0xFFFFFFFFL).toJson()

    companion object {
        /** Decodes one element; words the JSON does not carry stay 0. */
        fun fromJson(j: JsonValue): TravKeyData {
            val img = MemImage.decode(j)
            return TravKeyData(IntArray(8) { img.i32(4 * it) })
        }

        /** The vector at [off] of [img] as a mutable model list. */
        fun list(img: MemImage, off: Int = 0x328): MutableList<TravKeyData> = img.seq(off).map(::fromJson).toMutableList()

        fun store(img: MemImage, l: List<TravKeyData>, off: Int = 0x328) {
            img.seqs[off] = l.map { it.toJson() }.toMutableList()
        }
    }
}
