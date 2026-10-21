package nintype.engine.trie

import nintype.engine.SoftFloat
import nintype.engine.math.FastCurveMapper

/**
 * Emkey::SuggestionsColl, as calculateChainPredScore reads it: +0 vector of 0x34-byte suggestions,
 * each with +4 vector<NCWordHandle> (the predicted next words, 8 bytes each) and +0x24 f32 score.
 * ChainEnv::gatherSuggestions fills it from the user's typed history (ChainEnv, not ported); here
 * a suggestion is its handles' words (NCWordHandle::word) and its score.
 */
class ChainSuggestion(val words: List<ByteArray>, val score: Float)

object ChainPred {
    /** The static FastCurveMapper calculateChainPredScore ends with:
     *  (0, 1) (1, 1.3) (2, 1.4) (6, 1.6) (12, 1.85) (22, 2.1) (30, 2.3) (100, 2.7). */
    val CURVE: FastCurveMapper by lazy {
        FastCurveMapper().push(0f, 1.0f).push(1.0f, Float.fromBits(0x3fa66666)).push(2.0f, Float.fromBits(0x3fb33333))
            .push(6.0f, Float.fromBits(0x3fcccccd)).push(12.0f, Float.fromBits(0x3feccccd)).push(22.0f, Float.fromBits(0x40066666))
            .push(30.0f, Float.fromBits(0x40133333)).push(100.0f, Float.fromBits(0x402ccccd)).finalize()
    }

    /**
     * SuggestionsColl::calculateChainPredScore(word, next) @013588bc: over the suggestions whose
     * first word is [word] (NCWordHandle::matches: an exact byte compare), sum score^2, plus
     * 2 * score^2 when [next] is not empty and the second word is [next]; then CURVE(sum).
     */
    fun calculateChainPredScore(coll: List<ChainSuggestion>, word: ByteArray, next: ByteArray): Float {
        var s = 0f
        for (sug in coll) {
            if (sug.words.isEmpty() || !sug.words[0].contentEquals(word)) continue
            val sq = SoftFloat.fmul(sug.score, sug.score)
            s = SoftFloat.fadd(s, sq)
            if (sug.words.size >= 2 && next.isNotEmpty() && sug.words[1].contentEquals(next))
                s = SoftFloat.fadd(s, SoftFloat.fadd(sq, sq))
        }
        return CURVE.get(s)
    }
}

/**
 * The archive policy ChainEnv::maybeArchiveOldNascents @00989194 applies to the nascent chains
 * (the text the user is writing; ChainEnv::getSortedNascents orders them by creation time, oldest
 * first). The oldest (rank 0) only gets maybeSuckTailOff; ranks 1 and 2 are archived into the main
 * chain (maybeArchiveNascent -> ChainEnv::addWordAtEnd per word) once created more than 80 s of
 * engine time ago; later ones at once. ChainEnv.maybeArchiveOldNascents (ChainNascent.kt) applies it.
 */
object NascentPolicy {
    /** NascentChain::shouldBeExpired(int rank) @00975af8: [touched] = NascentChain+8, [now] = the
     *  static engine-time double. */
    fun shouldBeExpired(rank: Int, now: Double, touched: Double): Boolean = when {
        rank > 2 -> true
        rank < 1 -> false
        else -> SoftFloat.fcmpgt((now - touched).toFloat(), 80.0f)
    }

    /** NascentChain::canBeArchived() @00975b70: at least 3 chainlements (12 bytes each, vector at
     *  +0x10), of which at least 2 have the +8 or +9 byte set. */
    fun canBeArchived(flags: List<Pair<Int, Int>>): Boolean {
        if (flags.size < 3) return false
        var n = 0
        for ((a, b) in flags) n += (a or b) and 0xff
        if (n < 2) return false
        val f = SoftFloat.i2f(n)
        return !SoftFloat.fcmplt(f, Float.fromBits(0x3ecccccd)) || f.isNaN()
    }
}
