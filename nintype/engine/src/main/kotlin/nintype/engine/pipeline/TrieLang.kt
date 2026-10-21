package nintype.engine.pipeline

import nintype.engine.trie.CompactTrie
import nintype.engine.trie.TrieWord
import nintype.engine.trie.getWeirdCapsedVersion
import nintype.engine.vectors.MemImage

/**
 * The engine's language-model state (LangEnv at Moopad+0x21608 and the learning objects next to
 * it), built once per engine from the englishTrie: the trie agent's ports, held here.
 */
class LangState(dir: java.io.File, private val pad: MemImage, learnedDir: java.io.File? = null) {
    /** The English pack (english.nbipack), loaded the way TrieDump.buildEnglish does. */
    val pack = packOf(dir)
    private val packTrie: CompactTrie = CompactTrie().also {
        val skipped = nintype.engine.trie.LangEnv.loadPackWords(it, pack, 0, english = true)
        check(skipped == 0) { "$skipped words took an unported path" }
    }
    /** The learned-state files (trie agent's LearnedStore) when [learnedDir] is given: loaded here,
     *  written by [saveLearned]. */
    val learned: nintype.engine.trie.LearnedStore? = learnedDir?.let { nintype.engine.trie.LearnedStore(it) }
    private val reloaded = learned?.loadWordMods(packTrie)
    val trie: CompactTrie = reloaded?.first ?: packTrie
    /** LangEnv's word-mod side (LangEnv.applyMoopadDefaults, applied once, or the saved word sets):
     * maybeGetTrieWord, lowering, learning. */
    val env: nintype.engine.trie.WordModEnv = reloaded?.second ?: nintype.engine.trie.LangEnv.applyMoopadDefaults(trie)
    /** LangEnv's built-in chain Markov (the pack's chains). */
    val markov = nintype.engine.trie.BuiltInMarkov.fromPack(pack)
    /** MoopadReplaceableHistory deque (Moopad+0x32ca0): the committed words' histories, oldest
     * first, at most 30 ([history] holds their words, element+0x64, for NeoMarkov). */
    val replaceable = ArrayDeque<ReplaceableHistory>()
    val history = ArrayList<ByteArray>()
    /** The destroyed-word histories (Moopad+0x32cfc and the deque at +0x32d08) the retype
     * reinforcement reads; filled by the backspace paths (not ported), so empty. */
    val destroyed = ArrayList<ReplaceableHistory>()
    val lower: (ByteArray) -> ByteArray = { env.standardiseToLower(it) }
    /** WeirdCaseManager (Moopad+0x22c28); usf121 = Moopad::hasUSF1(0x121). */
    val weirdCase = nintype.engine.trie.WeirdCaseManager(lower, nintype.engine.geometry.MoopadGeometry.hasUSF1(pad, 0x121) != 0)
    /** BadExplicitManager (Moopad+0x22df0). */
    val badExplicit = nintype.engine.trie.BadExplicitManager(lower)
    /** WordLearningModule (Moopad+0x226d8). */
    val wlm = nintype.engine.trie.WordLearningModule(lower = lower, learnNewWord = { env.learnNewWord(it) },
        markMinimumScore = { w, f, l -> env.markMinimumScoreForWord(w, f, l) }).also { m -> env.onInduce = { m.onInduce(it) } }
    val autoComp = nintype.engine.trie.AutoCompLearn()
    val coreccion = nintype.engine.trie.CoreccionStore()

    init { learned?.loadRest(wlm, autoComp, weirdCase, badExplicit) }

    /** Writes the learned state (pending words, learned / custom words, autocomplete history,
     *  learned casings) to the learned directory; false when there is none or a write failed (logged).
     *  Never throws. Call it after commits or when the keyboard goes away. */
    fun saveLearned(): Boolean = learned?.save(env, wlm, autoComp, weirdCase, badExplicit) ?: false

    fun tw(id: Int) = TrieWord(trie.trieWords.data(id), trie.trieWords.off(id))

    companion object {
        private val packs = java.util.concurrent.ConcurrentHashMap<String, nintype.dict.Pack>()
        /** The parsed pack is immutable, so it is shared between engines. */
        fun packOf(dir: java.io.File) = packs.getOrPut(dir.canonicalPath) {
            nintype.dict.NinDict.load(java.io.File(dir, "english.nbipack").readBytes())
        }
    }
    /** LangEnv+0x5c0 (Moopad+0x21bc8): the active language indexes. */
    fun langFlags() = pad.i32(0x21bc8)
    /** LangEnv+0x65c.. (Moopad+0x21c64): the four language weights. */
    fun weights() = FloatArray(4) { pad.f32(0x21c64 + 4 * it) }
}

/** `Emkey::MoopadReplaceableHistory` (0x5b0 bytes) as the pipeline uses it: the committed
 * WordAlternative (+0x10, copied to +0x60), the u8 +0xc "chosen" flag and the alternatives
 * vector (+0xd8). */
class ReplaceableHistory {
    val wa: MemImage = Wa.new("hist")
    var chosen = false
    val was = ArrayList<MemImage>()
    /** +0x188: RoenGL::globaltimestamp at construction (the destruction time of a backspace history). */
    var time = 0.0
    /** +0xc8 / +0xcc: the word before it in the field, when there was one (backspace histories). */
    var prevWord: ByteArray? = null
}

/** [PredLang] over the trie agent's ports. */
class TrieLang(private val e: NintypeEngine) : PredLang {
    private val ls get() = e.langState
    private val pad get() = e.pad

    override fun trieWord(word: ByteArray): Int? = ls.env.maybeGetTrieWord(word).takeIf { it >= 0 }
    override fun isDisabledFCAL(tw: Int) = ls.tw(tw).isDisabledFCAL(ls.langFlags())
    override fun isUnfavored(tw: Int) = ls.tw(tw).isUnfavored(ls.langFlags(), ls.weights())
    override fun isSuperExiled(tw: Int) = ls.tw(tw).isSuperExiled()
    override fun rawWord(tw: Int): ByteArray = ls.trie.getRawWord(tw)

    override fun spliceCaps(tw: Int, s: ByteArray, b1: Boolean, b4000: Boolean, b8000: Boolean): ByteArray {
        val st = nintype.engine.trie.CapsSettings(pad.u8(0x21ca0 + 0xc) != 0, pad.u8(0x21cd4 + 0xc) != 0, pad.u8(0x29c5c + 0xc) != 0,
            usf = { nintype.engine.geometry.MoopadGeometry.hasUSF1(pad, it) != 0 })
        return nintype.engine.trie.Caps.spliceCaps(ls.tw(tw).i32(0), s, b1, b4000, b8000, st)
    }

    override fun weirdCase(raw: ByteArray, word: ByteArray): ByteArray = ls.weirdCase.convertWeirdCase(raw, word)

    override fun pendingWeirdCapsed(word: ByteArray): ByteArray =
        ls.wlm.obtainWord(word).getWeirdCapsedVersion(word, ls.weirdCase)

    override fun badExplicitCount(lower: ByteArray): Int? = ls.badExplicit.maybeGet(lower)?.count

    /** Moopad::performNeoMarkov(vector<WordAlternative>&) @006e4f20 (trie's NeoMarkov.perform) on the
     * WordAlternatives: +0x24 markov factor, +0x28 chain factor, +0x2c final score, best first.
     * Hooks: the built-in chain Markov with the Coreccion store; ChainEnv suggestions are not
     * ported (chainPred = 1.0, a fresh install's value). */
    override fun neoMarkov(alts: MutableList<MemImage>) {
        fun x(o: Int) = pad.u8(o + 0xc) != 0
        val st = nintype.engine.trie.NeoMarkovState(
            disabled = pad.i32(0x2affc), timeNow = pad.f64(0x29f48), timeRef = pad.f64(0x29d58),
            xnl29644 = x(0x29644), xnl296e4 = x(0x296e4), xnl296ac = x(0x296ac), int296e0 = pad.i32(0x296e0),
            xnl3196c = x(0x3196c), xnl317e0 = x(0x317e0), chainWeight = pad.f32(0x96e8), markovWeight = pad.f32(0x96ec),
            chainGathered = x(0x31e9c), xnl37d8 = x(0x37d8), history = ls.history.toList())
        val na = alts.mapTo(ArrayList()) { nintype.engine.trie.NeoAlternative(Wa.word(it), it.f32(0x20), Wa.flags(it),
            it.f32(0x24), it.f32(0x28), it.f32(0x2c), it) }
        val hooks = object : nintype.engine.trie.NeoMarkovHooks {
            override fun markov(prev2: ByteArray, prev: ByteArray, word: ByteArray, score: Float, exponent: Float) =
                nintype.engine.trie.MarkovScoring.score(listOf(ls.markov), prev2, prev, word, exponent, ls.coreccion)
            override fun gatherSuggestions() {}
            override fun chainPred(word: ByteArray, next: ByteArray) =
                nintype.engine.trie.ChainPred.calculateChainPredScore(emptyList(), word, next)
        }
        nintype.engine.trie.NeoMarkov.perform(na, st, hooks)
        pad.setU8(0x31e9c + 0xc, if (st.chainGathered) 1 else 0)
        alts.clear()
        for (a in na) {
            val w = a.tag as MemImage
            w.setF32(0x24, a.markov); w.setF32(0x28, a.chain); w.setF32(0x2c, a.final)
            alts.add(w)
        }
    }
}
