package nintype.engine.trie

import nintype.dict.Pack
import nintype.engine.SoftFloat

/** The dictionary half of Emkey::LangEnv: the CompactTrie (+0x778), its TrieWord pool (+0x75C)
 *  and StringPool (+0x744), and the pack loader that fills them. */
object LangEnv {
    /** Emkey::score_to_triewordscore(float) @007bfa40: clamp((int)(f * 255f), 0, 255). */
    fun scoreToTriewordscore(f: Float): Int {
        val i = SoftFloat.f2iz(SoftFloat.fmul(f, 255.0f))
        return if (i < 0) 0 else if (i > 0xff) 0xff else i
    }

    /** Emkey::triewordscore_to_score(unsigned char) @007bfa74: b / 255f. */
    fun triewordscoreToScore(b: Int): Float = SoftFloat.fdiv(SoftFloat.ui2f(b and 0xff), 255.0f)

    /**
     * LangEnv::langflow_onWordUsage(TrieWord*) @004a164c: the language-flow bookkeeping a committed
     * trie word feeds. It returns at once unless Xnl LangEnv+0x5E0 (several languages enabled) is
     * on, and that Xnl is off with one language installed (the only configuration this port boots),
     * so the call is a no-op. The multi-language body (langflow_enforceIfLocked when +0x614 is on,
     * else the per-language flow vectors at +0x56C / +0x64C) is not ported; [multiLanguage] = true
     * refuses rather than guessing.
     */
    fun langflowOnWordUsage(multiLanguage: Boolean) {
        if (!multiLanguage) return
        throw UnsupportedOperationException("LangEnv::langflow_onWordUsage with several languages is not ported")
    }

    /** A fresh install's custom-dictionary state: Moopad::Moopad()'s default word mods
     *  ([DefaultWordMods], switchable by rule) run through the ported MooWord path. */
    fun applyMoopadDefaults(trie: CompactTrie, rules: Set<DefaultWordMods.Rule> = DefaultWordMods.ALL): WordModEnv {
        val env = WordModEnv(trie)
        DefaultWordMods.apply(env, rules)
        return env
    }

    /**
     * The word half of Emkey::LangEnv::loadNBIPackFlector(lang, NBIPack&, path, langIndex)
     * @004b5e58 (FORMAT.md 6.1), in file order:
     * full-form packs (English) lowercase ASCII (Morestring::toLowerInPlace); the cooked string is
     * always "" so CompactTrie::obtainRawResident_MaybeCooked takes the obtainRawResident path with
     * ORRCTX::maxifying(score byte); then on the TrieWord: flags |= spellingmode, score byte at
     * +8+langIndex, lang bit, flag(10) for English, flag(0x12) (isFromLangDict).
     * Full form order: |= spellingmode, score byte, lang bit, [flag 10], flag 0x12. Compact: score
     * byte, |= spellingmode, flag 0x12, lang bit. Both orders give the same bytes.
     * Returns the number of words that needed an unported path (Hangul / altchain) and were skipped.
     */
    fun loadPackWords(trie: CompactTrie, pack: Pack, langIndex: Int, english: Boolean): Int {
        require(langIndex in 0..3)
        var skipped = 0
        val compact = pack.header.compactwords
        for (w in pack.words) {
            val raw = w.runtimeWord(compact).toByteArray(Charsets.UTF_8)
            val score = scoreToTriewordscore(w.multscore)
            val tw = trie.obtainRawResident(raw, score)
            if (tw == null) { skipped++; continue }
            val d = trie.trieWords.data(tw)
            val o = trie.trieWords.off(tw)
            CompactTrie.setLe32(d, o, CompactTrie.le32(d, o) or w.spellingmode)
            d[o + 8 + langIndex] = score.toByte()
            CompactTrie.setLe32(d, o + 4, CompactTrie.le32(d, o + 4) or ((1 shl langIndex) and 0xf)) // flagLangIndex
            if (english) trie.twFlag(tw, 10)
            trie.twFlag(tw, 0x12)
        }
        return skipped
    }
}
