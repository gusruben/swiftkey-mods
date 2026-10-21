package nintype.engine.trie

import nintype.dict.Pack
import nintype.engine.SoftFloat
import nintype.engine.math.FastCurveMapper

/**
 * Emkey::BuiltInMarkov: a language pack's word chains as a word-pair prior. The loader
 * (LangEnv::loadNBIPackFlector) adds every pack chain with BuiltInMarkov::add(chainedwords,
 * 1 + 0.4 * clamp(correctionratio, -1, 1)) (std::map<string, hexified float>, a later duplicate
 * replaces an earlier one), and finishSetup stores the map to a sorted on-disc DiscMap. Lookups are
 * by exact key "prev word" (prepareRootCache + DiscMap::findBetween), so a hash map holds the same
 * data. The float survives the hexify / floatToByteString round trip bit for bit.
 */
class BuiltInMarkov {
    val chains = HashMap<String, Float>()

    /** BuiltInMarkov::add(std::string const&, float) @004896dc. */
    fun add(chainedWords: String, v: Float) { chains[chainedWords] = v }

    /** BuiltInMarkov::check(prev, word, int& a, int& b) @00489e0c: for a chain "prev word" with value f,
     *  v = clamp(f * 0.5, 0, 1); a += (int)(v * N), b += N - (int)(v * N). [n] = the static scale (40). */
    fun check(prev: ByteArray, word: ByteArray, a: IntArray, b: IntArray, n: Int = N) {
        val key = String(prev, Charsets.UTF_8) + " " + String(word, Charsets.UTF_8)
        val f = chains[key] ?: return
        val h = SoftFloat.fmul(f, 0.5f)
        var v = 0f
        if (!SoftFloat.fcmplt(h, 0f)) v = if (SoftFloat.fcmpgt(h, 1.0f)) 1.0f else h
        val add = SoftFloat.f2iz(SoftFloat.fmul(v, SoftFloat.i2f(n)))
        a[0] += add
        b[0] = b[0] + n - add
    }

    companion object {
        /** The static int BuiltInMarkov::check scales by (40 in the booted engine). */
        const val N = 40

        /** The chain loop of LangEnv::loadNBIPackFlector. */
        fun fromPack(pack: Pack): BuiltInMarkov {
            val m = BuiltInMarkov()
            for (c in pack.chains) {
                var r = c.correctionratio
                r = if (SoftFloat.fcmplt(r, -1.0f)) -1.0f else if (SoftFloat.fcmpgt(r, 1.0f)) 1.0f else r
                m.add(c.words, SoftFloat.fadd(SoftFloat.fmul(r, Float.fromBits(0x3ecccccd)), 1.0f))
            }
            return m
        }

        /** The static curve LangEnv::getBuiltinChainValue weights by: (4 -> 1, 10 -> 0). */
        val CURVE: FastCurveMapper by lazy { FastCurveMapper().push(4.0f, 1.0f).push(10.0f, 0.0f).finalize() }

        /**
         * LangEnv::getBuiltinChainValue(prev, word, int& a, int& b) @004b8880: sums check() over the
         * active languages' BuiltInMarkovs, then m = CURVE(a + b) (the evidence already in a / b) and
         * a += (int)(m * sumA), b += (int)(m * sumB).
         */
        fun getBuiltinChainValue(markovs: List<BuiltInMarkov>, prev: ByteArray, word: ByteArray, a: IntArray, b: IntArray) {
            val la = intArrayOf(0)
            val lb = intArrayOf(0)
            for (m in markovs) m.check(prev, word, la, lb)
            val fa = if (markovs.isEmpty()) 0f else SoftFloat.i2f(la[0])
            val fb = if (markovs.isEmpty()) 0f else SoftFloat.i2f(lb[0])
            val w = CURVE.get(SoftFloat.i2f(a[0] + b[0]))
            a[0] += SoftFloat.f2iz(SoftFloat.fmul(w, fa))
            b[0] += SoftFloat.f2iz(SoftFloat.fmul(w, fb))
        }
    }

}

/**
 * Emkey::LangEnv::calculateMarkovScoringInner(MarkovArg&) @004b8cb8 and calculateMarkovScoring
 * @004b8c98: the word-context multiplier Moopad::performNeoMarkov applies to the decoded
 * alternatives. MarkovArg: +0 prev2, +4 prev, +0xC word, +0x14 f32 exponent, +0x18 f32 result.
 * Evidence (a, b) for "prev word" (and for prev2 + "prev word" when prev2 is not empty) comes from
 * the user's CoreccionMarkov (learned corrections, [CoreccionStore]: the root of prev checked for
 * word, and the root of prev2's entry for "prev word"; none on a fresh install) plus getBuiltinChainValue. Each pair with a + b > 0 gives
 * w = clamp((a + b) / 3, 0, 1) * 0.25 and the term 1 + w * 2 * (a / (a + b) - 0.5); the result is
 * the mean of the terms (1 when there is none).
 */
object MarkovScoring {
    fun inner(markovs: List<BuiltInMarkov>, prev2: ByteArray, prev: ByteArray, word: ByteArray,
              coreccionCheck: (ByteArray, ByteArray, IntArray, IntArray) -> Unit = { _, _, _, _ -> },
              coreccionEntry: (ByteArray, ByteArray) -> Pair<Int, Int>? = { _, _ -> null }): Float {
        fun term(a: Int, b: Int): Float {
            val sum = SoftFloat.i2f(a + b)
            val t = SoftFloat.fdiv(sum, 3.0f)
            var w = 0f
            if (!SoftFloat.fcmplt(t, 0f)) w = if (SoftFloat.fcmpgt(t, 1.0f)) 0.25f else SoftFloat.fmul(t, 0.25f)
            val x = SoftFloat.fadd(SoftFloat.fdiv(SoftFloat.i2f(a), sum), -0.5f)
            return SoftFloat.fadd(SoftFloat.fmul(w, SoftFloat.fadd(x, x)), 1.0f)
        }
        val a = intArrayOf(0)
        val b = intArrayOf(0)
        coreccionCheck(prev, word, a, b)
        BuiltInMarkov.getBuiltinChainValue(markovs, prev, word, a, b)
        var n = 0
        var score = 0f
        if (a[0] + b[0] >= 1) {
            score = SoftFloat.fadd(term(a[0], b[0]), 0f)
            n = 1
        }
        if (prev2.isNotEmpty()) {
            val key = prev + " ".toByteArray() + word
            val e = coreccionEntry(prev2, key)
            val a2 = intArrayOf(e?.first ?: 0)
            val b2 = intArrayOf(e?.second ?: 0)
            BuiltInMarkov.getBuiltinChainValue(markovs, prev2, key, a2, b2)
            if (a2[0] + b2[0] >= 1) {
                score = SoftFloat.fadd(score, term(a2[0], b2[0]))
                n++
            }
        }
        return if (n != 0) SoftFloat.fdiv(score, SoftFloat.i2f(n)) else 1.0f
    }

    /** calculateMarkovScoring: powf(inner, exponent). [coreccion] = the user's learned roots (none on a fresh install). */
    fun score(markovs: List<BuiltInMarkov>, prev2: ByteArray, prev: ByteArray, word: ByteArray, exponent: Float,
              coreccion: CoreccionStore? = null): Float =
        nintype.engine.math.LibM.powf(inner(markovs, prev2, prev, word, coreccion), exponent)

    /** [inner] with the hooks bound to a [CoreccionStore]. */
    fun inner(markovs: List<BuiltInMarkov>, prev2: ByteArray, prev: ByteArray, word: ByteArray, coreccion: CoreccionStore?): Float =
        if (coreccion == null) inner(markovs, prev2, prev, word)
        else inner(markovs, prev2, prev, word, coreccion::check, coreccion::entry)
}
