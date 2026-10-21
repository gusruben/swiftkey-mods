package nintype.engine.trie

import nintype.engine.SoftFloat
import nintype.engine.merge.GnuSortList

/** Emkey::ChainNextWordStat (0x10 bytes inside a ranker map node at +0x18): +0 f32 confidence, +4
 *  f32 a secondary weight, +8 the NCWordHandle (here the word id). */
class ChainNextWordStat(val id: Int) {
    var conf = 0f
    var weight = 0f

    /** ChainNextWordStat::addConfidenceWithPrechain(int) @01358bd8: by the matched prechain length
     *  0..6 and more, confidence += 0, 1, 2, 4, 7, 10, 16, 22 and weight += 0, 0, 0.5, 1, 2, 3, 5, 8. */
    fun addConfidenceWithPrechain(n: Int) {
        if (n == 0) return
        val (w, c) = when (n) {
            1 -> Pair(Float.NaN, 1.0f)
            2 -> Pair(0.5f, 2.0f)
            3 -> Pair(1.0f, 4.0f)
            4 -> Pair(2.0f, 7.0f)
            5 -> Pair(3.0f, 10.0f)
            6 -> Pair(5.0f, 16.0f)
            else -> Pair(8.0f, 22.0f)
        }
        if (!w.isNaN()) weight = SoftFloat.fadd(weight, w)
        conf = SoftFloat.fadd(conf, c)
    }
}

/**
 * Emkey::ChainNextWordRanker (Moopad+0x31F04): +0 map<NCWordHandle, ChainNextWordStat>, +0x18
 * vector of stat pointers in first-seen order (Moopad+0x31F1C), which sortit() orders by confidence,
 * highest first (gnustl std::sort, so ties keep the binary's order).
 */
class ChainNextWordRanker {
    private val map = HashMap<Int, ChainNextWordStat>()
    val ranked = ArrayList<ChainNextWordStat>()

    /** ChainNextWordRanker::clear() @01358d14. */
    fun clear() { map.clear(); ranked.clear() }

    /** ChainNextWordRanker::obtainStat(NCWordHandle) @01358d4c: a new stat is appended to [ranked]. */
    fun obtainStat(id: Int): ChainNextWordStat = map.getOrPut(id) { ChainNextWordStat(id).also { ranked.add(it) } }

    /** ChainNextWordRanker::sortit() @01358fe0. */
    fun sortit() = GnuSortList.sort(ranked) { a, b -> a.conf > b.conf }
}

/**
 * Emkey::FutureChain (0x34 bytes): +0 ChainEnv, +4 vector<NCWordHandle> (the up to six words that
 * followed the match in the chain; a trailing "?" or "!" is kept, "." is not), +0x10 PFC (0 here),
 * +0x14 super-faved, +0x18 the chain position matched against the context's last word, +0x1C how
 * many context words matched, +0x20 the word count without a trailing ender, +0x24 score, +0x28
 * the sentence starts line up, +0x29 the TryMatchingArg flag, +0x2C sort key.
 */
class FutureChain(val pos: Int, val matched: Int) {
    val ids = ArrayList<Int>()
    var n20 = 0
    var score = 0f
    var b28 = false
    var b29 = false
    var b14 = false
    var key = 0f

    /** FutureChain::determineScore(int now) @01355ed8: matched + (pos - now)
     *  / 200000, +0.5 when [b28], +1 when super-faved, +60 when [b29]; key = score + (n20 + words)
     *  * 100000. */
    fun determineScore(now: Int, store: ChainStore) {
        n20 = ids.size
        if (ids.isNotEmpty() && store.isSentenceEnder(ids[ids.size - 1])) n20--
        var s = SoftFloat.fadd(SoftFloat.i2f(matched), SoftFloat.fdiv(SoftFloat.i2f(pos - now), 200000f))
        if (b28) s = SoftFloat.fadd(s, 0.5f)
        if (b14) s = SoftFloat.fadd(s, 1.0f)
        if (b29) s = SoftFloat.fadd(s, 60.0f)
        score = s
        key = SoftFloat.fadd(s, SoftFloat.ui2f((n20 + ids.size) * 100000))
    }
}

/** Emkey::SuggestionsColl as gatherSuggestions fills it: +0 vector<FutureChain>, +0xC set<int> of
 *  tried chain positions, +0x24 / +0x28 / +0x2C counters (positions tried, words compared, chains
 *  made). The SynthedChainTrees sortAndSync builds from the chains are not modelled. */
class SuggestionsColl {
    val chains = ArrayList<FutureChain>()
    val visited = HashSet<Int>()
    var tried = 0
    var compared = 0
    var made = 0

    fun clear() { chains.clear(); visited.clear(); tried = 0; compared = 0; made = 0 }

    /** The order and fields calculateChainPredScore reads. */
    fun suggestions(store: ChainStore): List<ChainSuggestion> = chains.map { fc -> ChainSuggestion(fc.ids.map { store.word(it) }, fc.score) }

    /** SuggestionsColl::sortAndSync() @01357d2c, the part that touches the chains: std::sort by score,
     *  highest first. */
    fun sortChains() = GnuSortList.sort(chains) { a, b -> a.score > b.score }
}

object ChainGather {
    /**
     * ChainEnv::tryMatchForFutureChain(TryMatchingArg&) @009858c8 for a main-chain position (no PFC,
     * no debug context). Each position is tried once per gather. The context [nas] is matched from
     * its last element backwards against the chain ending at [pos]; with at least one match, the
     * sentence start is located (an ender inside the match, else scanning further back while the
     * context lasts; 999 when none) and compared with the context's NasVec+0x40, the words after
     * [pos] (up to six, at most 30 characters, stopping at an ender) become a FutureChain, and the
     * first of them gets ranker confidence by the match length. A chain that ends up empty, or holds
     * only an ender, is dropped.
     */
    fun tryMatchForFutureChain(store: ChainStore, nas: NasVec, pos: Int, flag: Boolean, coll: SuggestionsColl, ranker: ChainNextWordRanker) {
        val hp = store.currentHistoricalPos()
        if (pos == hp || pos + 1 == hp) return
        if (!coll.visited.add(pos)) return
        coll.tried++
        val els = nas.els
        if (els.isEmpty()) return
        var matched = 0
        var enderSeen = false
        var sentenceStart = 999
        var k = els.size - 1
        var r4 = 0
        while (true) {
            val hpos = pos + r4
            if (hpos < store.earliest) break
            val h = store.wordAtHistpos(hpos)
            if (h < 0) break
            if (store.isSentenceEnder(h)) {
                if (!enderSeen) sentenceStart = matched
                enderSeen = true
            }
            coll.compared++
            if (!els[k].word.contentEquals(store.word(h))) break
            matched++
            r4--
            k--
            if (k < 0) break
        }
        if (matched == 0) return
        var flagOk = true
        if (!enderSeen) {
            var kk = matched
            flagOk = false
            while (true) {
                val h = store.wordAtHistpos(pos - kk)
                if (h < 0) break
                if (store.isSentenceEnder(h)) { sentenceStart = kk; flagOk = true; break }
                kk++
                if (kk >= els.size) break
            }
        }
        val fc = FutureChain(pos, matched)
        coll.chains.add(fc)
        coll.made++
        if (flagOk && nas.f40 == sentenceStart) fc.b28 = true
        if (flag) fc.b29 = true
        var chars = 0
        for (i in 1 until 7) {
            val h = store.wordAtHistpos(pos + i)
            if (h < 0) break
            if (store.isSentenceEnder(h)) {
                if (store.isInclusiveEnder(h)) fc.ids.add(h)
                break
            }
            if (i == 1) ranker.obtainStat(h).addConfidenceWithPrechain(matched)
            chars += Utf8.split(store.word(h)).size
            if (chars > 30) break
            fc.ids.add(h)
        }
        fc.determineScore(hp, store)
        if (fc.ids.isEmpty() || (fc.ids.size == 1 && store.isSentenceEnder(fc.ids[0]))) {
            coll.chains.removeAt(coll.chains.size - 1)
        }
    }

    /**
     * ChainEnv::gatherSuggestions(SuggestionsColl&, ChainNextWordRanker&) @00986298 without a PFC
     * or word-preferred chains (ChainEnv+0x584 / +0x670, empty on a fresh install): [before] is the
     * client's text before the cursor (latestNChars(100)); deriveChainlements(before, "", "") gives
     * the context; for each context word from the last (d = 0, 1, ...) that the chain knows, each of
     * its recorded positions, newest first, is tried at position + d. Stops at the first unknown
     * word. Then the chains are sorted by score and the ranker by confidence.
     */
    fun gatherSuggestions(store: ChainStore, before: ByteArray, coll: SuggestionsColl, ranker: ChainNextWordRanker) {
        coll.clear()
        ranker.clear()
        val nas = ChainDerive.deriveChainlements(before, ByteArray(0), ByteArray(0))
        if (nas.els.isEmpty()) return
        for (d in nas.els.indices) {
            val el = nas.els[nas.els.size - 1 - d]
            val id = store.maybeGetWord(el.word)
            if (id < 0) break
            val meta = store.metas[id] ?: break
            val ps = meta.getWordPositions()
            for (j in ps.indices.reversed()) tryMatchForFutureChain(store, nas, ps[j] + d, false, coll, ranker)
        }
        coll.sortChains()
        ranker.sortit()
    }
}
