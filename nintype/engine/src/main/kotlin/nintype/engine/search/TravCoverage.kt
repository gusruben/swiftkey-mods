package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.LibM
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage

/**
 * MooTouchPoint's per-keycode trav coverage cache (+0x340, std::vector<float>: index =
 * getalphaindex(trav chara), value = trav score / 6), kept in the image's seqs[0x340] as f32
 * leaves, and PathSearch::checkTouchCoverageByLetters on top of it (split agreed with touch).
 */
object TravCoverage {
    fun cache(tp: MemImage): MutableList<Int> = tp.seq(0x340).map { Leaf.fromJson(it).asInt() }.toMutableList()
    fun store(tp: MemImage, c: List<Int>) {
        tp.seqs[0x340] = c.map { Leaf("f32", it.toLong() and 0xFFFFFFFFL).toJson() }.toMutableList()
    }

    /** obtainTravCaverageCacheSlot(int) @0095c290 grows with 0 to k + 1 (unsigned compare). */
    private fun grow(c: MutableList<Int>, k: Int) {
        require(k >= 0) { "negative keycode grows the vector until allocation fails" }
        while (c.size <= k) c.add(0)
    }

    /** populateTravCoverageCache() @0095c35c (also inlined in checkCoverageByLetters): only
     * when the cache is empty, cache[getalphaindex(chara)] = score / 6 for every trav. */
    fun populate(tp: MemImage, travs: List<Pair<Int, Float>>) {
        val c = cache(tp)
        if (c.isNotEmpty()) return
        for ((chara, score) in travs) {
            val v = fdiv(score, 6f)
            val k = PathSearch.getalphaindex(chara)
            grow(c, k)
            c[k] = Bits.floatToBits(v)
        }
        store(tp, c)
    }

    /** getTravCoverageCache(int) @0095c33c: cache[k], 0 past the end. */
    fun get(tp: MemImage, k: Int): Float {
        val c = tp.seq(0x340)
        return if (Integer.compareUnsigned(c.size, k) > 0) Leaf.fromJson(c[k]).asFloat() else 0f
    }

    /** MooTouchPoint::checkCoverageByLetters(std::string const&, int from, int to) @0095c4b4:
     * the best cached coverage of the word's chars [from, to) (0 if none). */
    fun checkCoverageByLetters(tp: MemImage, travs: List<Pair<Int, Float>>, word: ByteArray, from: Int, to: Int): Float {
        populate(tp, travs)
        var best = 0f
        for (i in from until to) {
            val v = get(tp, PathSearch.getalphaindex(word[i].toInt() and 0xff))
            if (fcmplt(best, v)) best = v
        }
        return best
    }

    private val LOOK = intArrayOf(0, 1, 1, 1, 2, 2, 2, 3, 3, 3) // look-around by word length 1..10 (else 4)

    /** PathSearch::checkTouchCoverageByLetters(std::string const&) @007e9320: how well the
     * important points (+0x1bc) cover the word's letters. Point j (at generic soft index
     * position si/total of the word, unless +0x178 < 0.01 where it sits at letter 0) takes its
     * best letter coverage within +-look letters; a point with gap weight w = +0x364 < 0.99 and a
     * pair (+0x18, slot +0x308) lends (1 - w) of its coverage to the pair and back; the result is
     * (sum x (1 + 9 (1.02 - x)^0.6) / sum (1 + 9 (1.02 - x)^0.6))^0.9 over the points' best x.
     * An empty word gives 1; no important points only asserts in the binary. */
    fun checkTouchCoverageByLetters(ps: MemImage, important: List<MemImage>, travs: (MemImage) -> List<Pair<Int, Float>>, word: ByteArray): Float {
        if (word.isEmpty()) return 1f
        require(important.isNotEmpty()) { "checkTouchCoverageByLetters without points asserts" }
        val look = if (word.size - 1 in 0..9) LOOK[word.size - 1] else 4
        val total = important.last().f32(0x31c)
        val cov = FloatArray(important.size)
        val best = FloatArray(important.size)
        for ((j, p) in important.withIndex()) {
            var frac = 0f
            if (!dcmplt(ps.f64(0x178), Bits.bitsToDouble(0x3f847ae140000000L))) frac = fdiv(p.f32(0x31c), total)
            val len = word.size
            var pos = len - 1
            val c = f2iz(fmul(frac, i2f(len - 1)))
            if (c < len) pos = c
            var hi = look + 1 + pos
            if (hi > len) hi = len
            var lo = pos - look
            if (lo < 0) lo = 0
            val v = checkCoverageByLetters(p, travs(p), word, lo, hi)
            cov[j] = v; best[j] = v
        }
        var acc = 0f
        var wsum = 0f
        for ((j, p) in important.withIndex()) {
            val w = p.f32(0x364)
            val q = p.ref(0x18)
            if (!(fcmpge(w, f(0x3f7d70a4)) || fcmpun(w, f(0x3f7d70a4))) && q != null) {
                val inv = fsub(1f, w)
                val qi = q.i32(0x308)
                require(qi in important.indices) { "pair slot outside this call's slots (reads stale static memory)" }
                val a = fmul(inv, cov[qi])
                if (fcmplt(best[j], a)) best[j] = a
                val b = fmul(inv, cov[j])
                if (fcmplt(best[qi], b)) best[qi] = b
            }
            val x = best[j]
            val wgt = fadd(fmul(LibM.powf(fsub(f(0x3f828f5c), x), f(0x3f19999a)), 9f), 1f)
            acc = fadd(acc, fmul(x, wgt))
            wsum = fadd(wsum, wgt)
        }
        return LibM.powf(fdiv(acc, wsum), f(0x3f666666))
    }

    private fun f(b: Int) = Bits.bitsToFloat(b)
}
