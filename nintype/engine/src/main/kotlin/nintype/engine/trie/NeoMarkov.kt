package nintype.engine.trie

import nintype.engine.SoftFloat
import nintype.engine.math.FastCurveMapper
import nintype.engine.math.LibM

/**
 * One Emkey::WordAlternative as Moopad::performNeoMarkov sees it (0x50 bytes): +4 word, +0x20 score,
 * +0x24 markov factor (out), +0x28 chain factor (out), +0x2c final score (out), +0x30 flags
 * (0x10000 = a two-word alternative "prev word"); WordAlternative() sets +0x24 / +0x28 to 1 and
 * +0x2c to 0, which is what a skipped call leaves. [tag] carries the caller's own object through the
 * reordering.
 */
class NeoAlternative(
    var word: ByteArray,
    var score: Float,
    var flags: Int,
    var markov: Float = 1f,
    var chain: Float = 1f,
    var final: Float = 0f,
    var tag: Any? = null,
)

/**
 * The Moopad state performNeoMarkov reads. Xnl values are Xnl::val() (the byte at Xnl+0xC).
 *  - [disabled]: int at Moopad+0x2AFFC (non-zero = skip everything);
 *  - [timeNow] / [timeRef]: doubles at Moopad+0x29F48 / +0x29D58; skipped when (float)(now - ref) > 4;
 *  - [xnl29644]: skip the reranking;
 *  - [xnl296e4]: halves the exponent scale; [xnl296ac] + [int296e0]: scale *= CURVE_SCALE(int296e0);
 *  - [xnl3196c] / [xnl317e0]: the chain predictor is enabled (settings_use_chain_predictor_xnl and
 *    a second switch); [chainWeight] (+0x96E8) / [markovWeight] (+0x96EC): the exponents' weights,
 *    each has to exceed 0.01 for its factor to be computed;
 *  - [chainGathered] (Xnl +0x31E9C, in / out): ChainEnv::gatherSuggestions already ran for this word;
 *  - [xnl37d8]: full weights (else markov x 0.6, chain x 0.34);
 *  - [history]: the words of the MoopadReplaceableHistory deque (+0x32CA8), oldest first
 *    (element+0x64 = the word).
 */
data class NeoMarkovState(
    val disabled: Int = 0,
    val timeNow: Double = 0.0,
    val timeRef: Double = 0.0,
    val xnl29644: Boolean = false,
    val xnl296e4: Boolean = false,
    val xnl296ac: Boolean = false,
    val int296e0: Int = 0,
    val xnl3196c: Boolean = false,
    val xnl317e0: Boolean = false,
    val chainWeight: Float = 0f,
    val markovWeight: Float = 0f,
    var chainGathered: Boolean = false,
    val xnl37d8: Boolean = false,
    val history: List<ByteArray> = emptyList(),
)

/** What performNeoMarkov calls out to: LangEnv::calculateMarkovScoring (MarkovArg +0x14 = [exponent]),
 *  ChainEnv::gatherSuggestions and SuggestionsColl::calculateChainPredScore. */
interface NeoMarkovHooks {
    fun markov(prev2: ByteArray, prev: ByteArray, word: ByteArray, score: Float, exponent: Float): Float
    fun gatherSuggestions()
    fun chainPred(word: ByteArray, next: ByteArray): Float
}

object NeoMarkov {
    /** The static FastCurveMapper the exponent scale goes through: (1, 0.9) (2, 0.6) (3, 0.3) (4, 0.1). */
    val CURVE_SCALE: FastCurveMapper by lazy {
        FastCurveMapper().push(1.0f, 0.9f).push(2.0f, 0.6f).push(3.0f, 0.3f).push(4.0f, 0.1f).finalize()
    }

    /** The static FastCurveMapper the time gap goes through for MarkovArg A's exponent (+0x14):
     *  (0.2, 2.5) (0.5, 1.5) (0.7, 1) (1.5, 0.6) (2.5, 0.3) (4, 0.1). */
    val CURVE_GAP: FastCurveMapper by lazy {
        FastCurveMapper().push(0.2f, 2.5f).push(0.5f, 1.5f).push(0.7f, 1.0f).push(1.5f, 0.6f)
            .push(2.5f, 0.3f).push(4.0f, 0.1f).finalize()
    }

    private val NEG1 = Float.fromBits(0xbf800000.toInt())
    private val F0_01 = Float.fromBits(0x3c23d70a)
    private val F0_6 = Float.fromBits(0x3f19999a)
    private val F0_34 = Float.fromBits(0x3eae147b)

    /**
     * Morestring::splitOn(std::string const&, char, vector&) @0079e7b8: byte split that keeps empty
     * parts; the last part is always pushed ("" -> [""]).
     */
    fun splitOn(s: ByteArray, c: Byte): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in s.indices) if (s[i] == c) { out.add(s.copyOfRange(start, i)); start = i + 1 }
        out.add(s.copyOfRange(start, s.size))
        return out
    }

    /**
     * Emkey::Moopad::performNeoMarkov(vector<WordAlternative>&) @006d4f20. Multiplies every
     * alternative's score by markov^(m * scale) and chain^(c * scale) into [NeoAlternative.final]
     * and swaps the best one (strictly greater, from -1) to the front.
     *
     * Normal alternatives: MarkovArg A (prev2 = toWLower(history[-2]), prev = toWLower(history[-1]),
     * word = toWLower(word), score, exponent = CURVE_GAP(time gap)); with no history (prev "") both factors stay 1. The chain factor
     * is chainPred(toWLower(word), ""). Two-word alternatives (flag 0x10000, exactly two parts when
     * split on ' '): MarkovArg B (prev2 = toWLower(history[-1]), prev = part 0, word = part 1, score
     * not set, exponent 1) and chainPred(part 0, part 1). The MarkovArgs persist across the loop.
     */
    fun perform(alts: MutableList<NeoAlternative>, st: NeoMarkovState, hooks: NeoMarkovHooks) {
        if (alts.isEmpty() || st.disabled != 0) return
        val gap = (st.timeNow - st.timeRef).toFloat()
        if (SoftFloat.fcmpgt(gap, 4.0f) || st.xnl29644) return
        var scale = if (st.xnl296e4) 0.5f else 1.0f
        if (st.xnl296ac) scale = SoftFloat.fmul(scale, CURVE_SCALE.get(SoftFloat.i2f(st.int296e0)))

        // MarkovArg A (fp-0x50) / B (fp-0x6c): +0 prev2, +4 prev, +0xc word, +0x10 score.
        val a = arrayOf(ByteArray(0), ByteArray(0), ByteArray(0))
        var aScore = 0f
        val b = arrayOf(ByteArray(0), ByteArray(0), ByteArray(0))
        val aExp = CURVE_GAP.get(gap)
        val h = st.history
        if (h.size >= 2) a[0] = Caps.toWLower(h[h.size - 2])
        if (h.isNotEmpty()) {
            a[1] = Caps.toWLower(h[h.size - 1])
            b[0] = a[1]
        }

        val markovOn = SoftFloat.fcmpgt(st.markovWeight, F0_01)
        var chainOn = false
        if (st.xnl3196c && st.xnl317e0) {
            val w = st.chainWeight
            if (!(SoftFloat.fcmple(w, F0_01) || w.isNaN())) {
                chainOn = true
                if (!st.chainGathered) {
                    hooks.gatherSuggestions()
                    st.chainGathered = true
                }
            }
        }

        var best = NEG1
        var bestIdx = 0
        for ((idx, alt) in alts.withIndex()) {
            val lowered = Caps.toWLower(alt.word)
            var mk = 1.0f
            var ch = 1.0f
            if (alt.flags and 0x10000 != 0) {
                val parts = splitOn(alt.word, 0x20)
                if (parts.size == 2) {
                    b[1] = parts[0]
                    b[2] = parts[1]
                    if (markovOn) mk = hooks.markov(b[0], b[1], b[2], 0f, 1.0f)
                    if (chainOn && st.chainGathered) ch = hooks.chainPred(parts[0], parts[1])
                }
            } else if (a[1].isNotEmpty()) {
                a[2] = lowered
                aScore = alt.score
                if (markovOn) mk = hooks.markov(a[0], a[1], a[2], aScore, aExp)
                if (chainOn && st.chainGathered) ch = hooks.chainPred(lowered, ByteArray(0))
            }
            var mw = st.markovWeight
            if (!st.xnl37d8) mw = SoftFloat.fmul(mw, F0_6)
            val m = LibM.powf(mk, SoftFloat.fmul(scale, mw))
            var cw = st.chainWeight
            if (!st.xnl37d8) cw = SoftFloat.fmul(cw, F0_34)
            val c = LibM.powf(ch, SoftFloat.fmul(scale, cw))
            alt.markov = m
            alt.chain = c
            val fin = SoftFloat.fmul(c, SoftFloat.fmul(alt.score, m))
            alt.final = fin
            if (SoftFloat.fcmpgt(fin, best)) {
                best = fin
                bestIdx = idx
            }
        }
        if (bestIdx != 0) {
            val t = alts[0]
            alts[0] = alts[bestIdx]
            alts[bestIdx] = t
        }
    }
}
