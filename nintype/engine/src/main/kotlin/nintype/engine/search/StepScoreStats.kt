package nintype.engine.search

import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.Values

/**
 * `Emkey::StepScoreStats` (sizeof 0x90): per-word-length search statistics, one per entry of
 * PathSearch's +0xea8 vector. Byte image with the containers in [MemImage.seqs]:
 *
 *   0x00 ptr  owner PathSearch* (a ref)     0x04 i32 index (= its slot in the vector)
 *   0x08 0x24 bytes of counters, zeroed by reset()
 *   0x2c ?    never written by ctor/reset (a fresh element holds stack garbage)
 *   0x30/0x34/0x38 i32, zeroed by reset()
 *   0x3c vec  heap of PredPath refs (reverse_pathpointer_comparer)
 *   0x4c map  std::map<std::string, int> (header at 0x50; the vectors key it @0x50)
 *   0x64 i32  0 after reset()                0x68 i32 step allowance (ctor 36)
 *   0x6c f32  (ctor 0.25; 0.1 for index 1)   0x70 i32  0x74 f32 branch cutoff score
 *   0x78 vec  PredPath refs                  0x84/0x88 i32, zeroed by reset()
 *   0x8c ?    never written
 */
object StepScoreStats {
    const val SIZE = 0x90

    /** StepScoreStats() @007f0510 (calls reset()). */
    fun construct(name: String = ""): MemImage {
        val s = MemImage(name, SIZE)
        s.seq(0x3c).clear()
        s.seq(0x50).clear()
        s.seq(0x78).clear()
        reset(s)
        PathSearch.setRef(s, 0, null)
        s.setI32(4, 0)
        s.setI32(0x68, 0x24)
        s.setI32(0x6c, 0x3e800000) // 0.25f
        return s
    }

    /** reset() @007d803c. Releasing the PredPath refs is the refcounting's business (not modelled). */
    fun reset(s: MemImage) {
        s.setI32(0x84, 0); s.setI32(0x88, 0)
        s.setI32(0x38, 0); s.setI32(0x34, 0); s.setI32(0x30, 0)
        s.writeBytes(8, ByteArray(0x24))
        s.seq(0x78).clear()
        s.seq(0x3c).clear()
        s.setI32(0x70, 0); s.setI32(0x74, 0)
        s.seq(0x50).clear()
        s.setI32(0x64, 0)
    }

    private val SCALARS = listOf(Triple(0x4, "i32", "index"), Triple(0x30, "i32", null), Triple(0x34, "i32", null),
        Triple(0x38, "i32", null), Triple(0x64, "i32", null), Triple(0x68, "i32", "allowance"), Triple(0x6c, "f32", null),
        Triple(0x70, "i32", null), Triple(0x74, "f32", "cutoff"), Triple(0x84, "i32", null), Triple(0x88, "i32", null))

    private fun k(off: Int, name: String?) = "@0x${off.toString(16)}" + (name?.let { ":$it" } ?: "")

    /** Vector serialization (re/vectors/gen_search.py SSS_FIELDS): keys are offsets only. */
    fun toJson(s: MemImage): JsonValue {
        val m = LinkedHashMap<String, JsonValue>()
        m[k(0, "owner")] = Values.ref(if (s.refs.containsKey(0)) s.refs[0]?.name else s.refNames[0])
        m[k(4, "index")] = Leaf("i32", s.readLE(4, 4)).toJson()
        m[k(8, "counters")] = Values.bytesLeaf(s.readBytes(8, 0x24))
        for ((off, t, n) in SCALARS.drop(1)) m[k(off, n)] = Leaf(t, s.readLE(off, 4)).toJson()
        m[k(0x3c, "heap")] = Values.vec(s.seq(0x3c).toList())
        m[k(0x78, "pending")] = Values.vec(s.seq(0x78).toList())
        m[k(0x50, "wordCounts")] = Values.map(s.seq(0x50).toList())
        return JsonValue.JObject(m)
    }

    /** Decodes one serialized element; its owner ref is resolved to [owner] when it names it. */
    fun fromJson(j: JsonValue, owner: MemImage, name: String): MemImage {
        val s = MemImage.decode(j, name)
        val on = s.refNames[0]
        if (on != null && on == owner.name) s.refs[0] = owner
        return s
    }

    /** reverse_pathpointer_comparer (the stats heap's order): `a.score > b.score` (PredPath +0x74). */
    val REVERSE = { a: MemImage, b: MemImage -> nintype.engine.SoftFloat.fcmpgt(a.f32(0x74), b.f32(0x74)) }

    /** recordQueue(Refcounter<PredPath> const&) @007db590: per-length queue statistics for a
     * path entering the queue: best (+0xc) / worst (+0x10) score, count (+0x14), f64 score sum
     * (+0x18). A path whose running minimum (+0x70) is above the floor (+0x70) also joins
     * the min-heap of the length's best paths ([heap], +0x3c) and the per-word counts
     * ([wordCounts], +0x4c); the heap then sheds its worst paths while it holds more than
     * allowance (+0x68) + duplicates (+0x64) entries or its worst path's word is over the
     * owner's per-word limit ([maxPerWord], PathSearch +0x4fc), and the floor becomes the worst
     * remaining path's +0x70. Finally cutoff (+0x74) = max(0.75 floor, best * +0x6c).
     * Refcounting is not modelled (paths are GC'd references). */
    fun recordQueue(s: MemImage, heap: MutableList<MemImage>, wordCounts: java.util.TreeMap<StrKey, Int>, maxPerWord: Int,
                    pp: MemImage, word: (MemImage) -> ByteArray) {
        val sc = pp.f32(0x74)
        val n = s.i32(0x14)
        if (n == 0) { s.setF32(0xc, sc); s.setF32(0x10, sc) }
        else {
            if (nintype.engine.SoftFloat.fcmplt(s.f32(0xc), sc)) s.setF32(0xc, sc)
            if (nintype.engine.SoftFloat.fcmplt(sc, s.f32(0x10))) s.setF32(0x10, sc)
        }
        s.setI32(0x14, n + 1)
        s.setF64(0x18, nintype.engine.SoftFloat.dadd(nintype.engine.SoftFloat.f2d(sc), s.f64(0x18)))
        val low = pp.f32(0x70)
        val floor = s.f32(0x70)
        if (nintype.engine.SoftFloat.fcmple(low, floor) || nintype.engine.SoftFloat.fcmpun(low, floor)) return finish(s)
        val w = StrKey(word(pp))
        GnuHeap.push(heap, pp, REVERSE)
        val old = wordCounts[w] ?: 0
        wordCounts[w] = old + 1
        if (old >= maxPerWord) s.setI32(0x64, s.i32(0x64) + 1)
        var popped = false
        if (Integer.compareUnsigned(heap.size, s.i32(0x64) + s.i32(0x68)) < 0) return finish(s)
        while (true) {
            val top = heap[0]
            val tw = StrKey(word(top))
            val c = wordCounts[tw] ?: 0.also { wordCounts[tw] = 0 } // operator[] inserts; the binary asserts c > 0
            if (Integer.compareUnsigned(heap.size, s.i32(0x64) + s.i32(0x68)) > 0 || c > maxPerWord) {
                if (c > maxPerWord) s.setI32(0x64, s.i32(0x64) - 1)
                wordCounts[tw] = c - 1
                GnuHeap.pop(heap, REVERSE)
                popped = true
                if (Integer.compareUnsigned(heap.size, s.i32(0x64) + s.i32(0x68)) >= 0) continue
                break
            }
            s.setF32(0x70, (if (popped) top else heap[0]).f32(0x70))
            break
        }
        finish(s)
    }

    private fun finish(s: MemImage) {
        val a = nintype.engine.SoftFloat.fmul(s.f32(0x70), 0.75f)
        val b = nintype.engine.SoftFloat.fmul(s.f32(0xc), s.f32(0x6c))
        s.setF32(0x74, if (nintype.engine.SoftFloat.fcmplt(a, b)) b else a)
    }
}
