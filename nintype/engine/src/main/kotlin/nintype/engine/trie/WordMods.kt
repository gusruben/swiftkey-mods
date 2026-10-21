package nintype.engine.trie

import nintype.engine.SoftFloat

/**
 * Emkey::MooWord, a custom-dictionary word modification (0x44 bytes, stored after its word string
 * in a MooWordSave of a MooWordSaveSet):
 *   +0x00 Flag32 flags (copied onto the TrieWord: caps bits 5/6/7, super-exiled 0x11, ...)
 *   +0x04 f32 default score, +0x08 bool use the default score for languages without an entry
 *         (true from the ctor, cleared by MooWordSaveSet::newWord)
 *   +0x0C std::map<LangCode, u8> per-language score bytes
 *   +0x24 std::set<LangCode> explicitly exiled languages (root +0x2C)
 *   +0x3C save-set index (written into the TrieWord's +4 bits 4-24)
 */
class MooWord(var setIndex: Int = -1) {
    var flags = 0
    var defaultScore = 0f
    var useDefault = true
    val scores = java.util.TreeMap<Int, Int>()
    val exiled = java.util.TreeSet<Int>()

    fun flagged(bit: Int) = flags and (1 shl bit) != 0
    fun flag(bit: Int) { flags = flags or (1 shl bit) }
    fun unflag(bit: Int) { flags = flags and (1 shl bit).inv() }

    /** getScoremultForLang(LangCode) @007c4064: the language's byte / 255, else the default. */
    fun getScoremultForLang(lang: Int): Float = scores[lang]?.let { SoftFloat.fdiv(SoftFloat.i2f(it), 255.0f) } ?: defaultScore

    /** setScoremultForLang(float, LangCode) @007c42f0: byte = (int)(v * 255) clamped (above 255 -> 255). */
    fun setScoremultForLang(v: Float, lang: Int) {
        var b = SoftFloat.f2iz(SoftFloat.fmul(v, 255.0f))
        if (b < 0) b = 0
        if (b > 0xff) b = 0xff
        scores[lang] = b
    }

    /** hasScoremult(LangCode) @007c4614. */
    fun hasScoremult(lang: Int) = scores.containsKey(lang)

    /** markSuperExiled(bool) @007c411c: flag 0x11. */
    fun markSuperExiled(on: Boolean) = if (on) flag(0x11) else unflag(0x11)

    /** markExiledness(bool, LangCode) @007c4128. */
    fun markExiledness(on: Boolean, lang: Int) { if (on) exiled.add(lang) else exiled.remove(lang) }

    /** isDisabledForLang(LangCode) @007c47e0: score < 0.02. */
    fun isDisabledForLang(lang: Int) = SoftFloat.fcmplt(getScoremultForLang(lang), Float.fromBits(0x3ca3d70a))
}

/**
 * The custom-dictionary half of Emkey::LangEnv: MooWordSaveSets (LangEnv+0x87C, 100 words each)
 * and the word-mod entry points that rewrite TrieWords (modifyWordCaps, modifyWordLC,
 * markMinimumScoreForWord, through modifyWordInner / obtainWordMod / saveAndReflect /
 * reflectCustomWord / TrieWord::takeCustomData).
 *
 * Persistence (Saver::needSave, the save files) is not modelled. The two callbacks into other
 * modules are hooks: [onInduce] = Moopad::onCustomDictionaryWordModification ->
 * WordLearningModule::onInduce(word) (word learning), [onDisableWeirdCase] =
 * WeirdCaseManager::disableWeirdCase(word) after modifyWordCaps. The spacebar message
 * modifyWordCaps shows for words that shouldSimplyCapitalize is UI only.
 */
class WordModEnv(
    val trie: CompactTrie,
    /** LangEnv+0x584: the loaded languages' LangCodes by language index (English only: [1]). */
    val loadedLangs: List<Int> = listOf(1),
    /** LangEnv::focusedLangCode() (English: 1). */
    val focusedLang: Int = 1,
    var onInduce: (ByteArray) -> Unit = {},
    var onDisableWeirdCase: (ByteArray) -> Unit = {},
    /** The Flag32 at LangEnv+0x5C0 (Moopad+0x21BC8): active language indexes (English: bit 0). */
    val activeLangIndexMask: Int = 1,
    /** The std::set<LangCode> at LangEnv+0x5C4 (Moopad+0x21BCC): active languages (English: {1}). */
    val activeLangs: java.util.SortedSet<Int> = java.util.TreeSet(listOf(1)),
) {
    /** MooWordSaveSet word lists, by set index: (word, MooWord) in insertion order. */
    val saveSets = ArrayList<ArrayList<Pair<ByteArray, MooWord>>>()

    /**
     * MooWordSaveSet i's file appdata/emkey/wordsaveset<i> (each set is a Shing::Saver, rewritten
     * the frame after a change): Flector<MooWordSaveSet> version 2, +0x100 vector<MooWordSave>
     * (version 2: the word, then the MooWord, version 10: f32 +4 default score, bool +8 use default,
     * Flag32 +0 flags, map<LangCode, u8> +0xC scores, set<LangCode> +0x24 exiled; the five other
     * recorded fields are read-only legacy temporaries and are not written).
     */
    fun binarizeSet(i: Int): ByteArray = FlecWriter.file(2) {
        val set = saveSets[i]
        u32(set.size)
        for ((w, mw) in set) obj(2) {
            str(w)
            obj(10) {
                u32(mw.defaultScore.toRawBits()); u8(if (mw.useDefault) 1 else 0); u32(mw.flags)
                u32(mw.scores.size); for ((k, v) in mw.scores) { u32(k); u8(v) }
                u32(mw.exiled.size); for (x in mw.exiled) u32(x)
            }
        }
    }

    /** Every save set's file, by name. */
    fun saveFiles(): Map<String, ByteArray> = saveSets.indices.associate { "wordsaveset$it" to binarizeSet(it) }

    /**
     * LangEnv::reloadCustomWords @0049bec8 on a trie fresh from the pack (performNBIPackLoading):
     * obtainWordSaveSet(i) @0049c0a0 for each set: the set loads from wordsaveset<i> (missing or
     * unreadable: empty), and each word without a space, NUL or uppercase letter is
     * reflectCustomWord'ed into the trie. Must run before any other word mod.
     *
     * [reflectAll]: reflect those words too. The original only rebuilds its trie (and runs this)
     * when the mmapped trie files are gone; across an ordinary restart the mmapped trie keeps every
     * word saveAndReflect put in, including those. A trie rebuilt at every start needs reflectAll
     * to come back the way the original's does.
     */
    fun reloadCustomWords(files: Map<String, ByteArray>, log: (String) -> Unit = {}, reflectAll: Boolean = false) {
        check(saveSets.isEmpty()) { "reloadCustomWords needs an env without save sets" }
        var n = 0
        while (files.containsKey("wordsaveset$n")) n++
        for (i in 0 until n) {
            val set = try {
                FlecReader.file(files["wordsaveset$i"]!!) { _ ->
                    (0 until count()).map { obj { _ ->
                        val w = str()
                        val mw = obj { _ ->
                            MooWord(i).also { m ->
                                m.defaultScore = Float.fromBits(u32()); m.useDefault = bool(); m.flags = u32()
                                repeat(count()) { val k = u32(); m.scores[k] = u8() }
                                repeat(count()) { m.exiled.add(u32()) }
                            }
                        }
                        w to mw
                    } }
                }
            } catch (x: FlecFormatException) {
                log("wordsaveset$i unreadable (${x.message}), loaded empty"); emptyList()
            }
            saveSets.add(ArrayList(set))
        }
        for (set in saveSets) for ((w, mw) in set) {
            var reflect = true
            if (!reflectAll) for (cp in Utf8.split(w)) if (cp and 0x20.inv() == 0 || Shing.iswupper(cp)) { reflect = false; break }
            if (reflect) {
                try { reflectCustomWord(mw, w) } catch (x: UnsupportedOperationException) { log("wordsaveset: ${x.message}") }
            }
        }
    }

    /** MooWordSaveSet::get(std::string const&) @007c68d0. */
    private fun getInSet(i: Int, word: ByteArray): MooWord? = saveSets.getOrNull(i)?.firstOrNull { it.first.contentEquals(word) }?.second

    /** MooWordSaveSet::isFull() @007c68a4: more than 99 words. */
    private fun isFull(i: Int) = saveSets[i].size > 99

    /** CompactTrie::maybeGetRawWord(std::string const&, LangEnv*) @005ac860: the resident TrieWord
     *  of a raw word, without inserting. The path is Emkey::get_a_triestring(raw) @0116062c, which
     *  maps each code point through chara_to_keycode / keycode_to_chara WITHOUT lowering (an
     *  uppercase ASCII letter keeps its uppercase keycode), so a raw word with capitals, stored
     *  under its lowered key by obtainRawResident, is never found here. Hangul words are not ported. */
    fun maybeGetRawWord(raw: ByteArray): Int {
        val key = PendingTrie.getATriestring(raw)
        val n = trie.findNode(key)
        return if (n < 0) -1 else trie.getRawResident(n, raw)
    }

    /** LangEnv::maybeGetTrieWord(std::string const&) @004a93dc: CompactTrie::maybeGetRawWord on the raw
     *  word as given (TWRet {+0 id, +4 TrieWord*}; -1 = none). */
    fun maybeGetTrieWord(word: ByteArray): Int = maybeGetRawWord(word)

    private fun tw(id: Int) = TrieWord(trie.trieWords.data(id), trie.trieWords.off(id))

    /** LangEnv::obtainWordMod(std::string const&) @004baa54. */
    fun obtainWordMod(word: ByteArray): MooWord {
        val t = maybeGetRawWord(word)
        if (t >= 0) {
            val w4 = tw(t).i32(4)
            if (w4 and 0x1fffff0 != 0x1fffff0) {
                getInSet((w4 shl 7) shr 11, word)?.let { return it }
            }
        }
        var set = saveSets.indices.firstOrNull { !isFull(it) }
        if (set == null) { saveSets.add(ArrayList()); set = saveSets.size - 1 }
        val mw = MooWord(set)          // MooWordSaveSet::newWord @007c6e20
        mw.useDefault = false
        saveSets[set].add(word.copyOf() to mw)
        var reflect = true
        for (cp in Utf8.split(word)) if (cp and 0x20.inv() == 0 || Shing.iswupper(cp)) { reflect = false; break }
        if (reflect) reflectCustomWord(mw, word)
        return mw
    }

    /** LangEnv::reflectCustomWord(MooWord&, std::string const&) @004b9524: obtainRawResident (inserting
     *  the word if it is new) with a collecting ORRCTX, TrieWord::takeCustomData, then
     *  ORRCTX::resyncNodeScores (syncWordScoreBytes of the collected node). */
    fun reflectCustomWord(mw: MooWord, word: ByteArray) {
        if (word.isEmpty()) return
        val key = CompactTrie.cookKey(word) ?: throw UnsupportedOperationException("Hangul / altchain words are not ported")
        val ctx = CompactTrie.Ctx(cur = trie.root)
        val node = trie.insertLeaf(trie.root, key, 0, ctx)
        val id = trie.getOrAddNewRawResident(node, word)
        if (key.size != word.size) trie.twFlag(id, 0x10)
        takeCustomData(tw(id), mw)
        trie.syncWordScoreBytes(node)
    }

    /** TrieWord::takeCustomData(MooWord const&, LangEnv&) @007c19d4. */
    fun takeCustomData(t: TrieWord, mw: MooWord) {
        t.setI32(4, (t.i32(4) and 0xfe00000f.toInt()) or (0x1fffff0 and (mw.setIndex shl 4)))
        var s = SoftFloat.f2iz(SoftFloat.fmul(mw.defaultScore, 255.0f))
        if (s < 0) s = 0
        if (s > 0xff) s = -1
        val n = loadedLangs.size
        if (n >= 1) {
            val last = if (n < 4) n - 1 else 3 // iVar9 = (count >> 2 ... ) - 1 capped at 3
            for (i in 0..last) {
                val v = mw.scores[loadedLangs[i]]
                if (v != null) t.d[t.o + 8 + i] = v.toByte()
                else if (mw.useDefault) t.d[t.o + 8 + i] = s.toByte()
            }
        }
        val hangul = t.flagged(0xf)
        val nonbasic = t.flagged(0x10)
        val fromDict = t.flagged(0x12)
        t.setI32(0, mw.flags)
        if (hangul) t.flag(0xf)
        if (nonbasic) t.flag(0x10)
        if (fromDict) t.flag(0x12)
    }

    /** LangEnv::saveAndReflect(MooWord&, std::string const&) @004bbf0c. */
    fun saveAndReflect(mw: MooWord, word: ByteArray) {
        if (word.isEmpty()) return
        onInduce(word)
        reflectCustomWord(mw, word)
    }

    /** LangEnv::modifyWordInner(std::string const&, std::function<bool (MooWord&, std::string const&)>)
     *  @004bd3cc: on the lowered word (the chomped copy is only tested for emptiness, so
     *  surrounding spaces stay in the custom word). */
    fun modifyWordInner(word: ByteArray, fn: (MooWord, ByteArray) -> Boolean) {
        if (Shing.chompWhitespaceOnEnds(word).isEmpty()) return
        val lowered = standardiseToLower(word)
        val mw = obtainWordMod(lowered)
        if (fn(mw, lowered)) saveAndReflect(mw, lowered)
    }

    /** The lambda modifyWordLC and markMinimumScoreForWord pass to modifyWordInner (@004cbd64):
     *  clamp the score to [0, 1], set it for the language, set super-exiled / exiled. */
    private fun scoreLambda(score: Float, exiled: Boolean, lang: Int, superExiled: Boolean): (MooWord, ByteArray) -> Boolean = { mw, _ ->
        val l = if (lang != -1) lang else focusedLang
        if (l < 0) false else {
            var v = 0f
            if (!SoftFloat.fcmplt(score, 0f)) v = if (SoftFloat.fcmpgt(score, 1.0f)) 1.0f else score
            mw.markSuperExiled(superExiled)
            mw.setScoremultForLang(v, l)
            mw.markExiledness(exiled, l)
            true
        }
    }

    /** LangEnv::modifyWordLC(std::string const&, float) @004bc68c: set the focused language's score. */
    fun modifyWordLC(word: ByteArray, score: Float) = modifyWordInner(word, scoreLambda(score, false, -1, false))

    /** LangEnv::markMinimumScoreForWord(std::string const&, float, LangCode) @004bcbbc: raise the
     *  word's score for [lang] to [min] unless the dictionary already has at least that. */
    fun markMinimumScoreForWord(word: ByteArray, min: Float, lang: Int) {
        val lowered = standardiseToLower(word)
        val t = maybeGetRawWord(lowered)
        if (t >= 0) {
            val s = trieWordScoremultForLang(tw(t), lang)
            if (SoftFloat.fcmpge(s, min) || SoftFloat.fcmpun(s, min)) return
        }
        modifyWordInner(lowered, scoreLambda(min, false, lang, false))
    }

    /** TrieWord::getScoremultForLang(Moopad*, LangCode) @007c1180: by the language's index among the
     *  first 4 loaded languages, 0 if not loaded. */
    fun trieWordScoremultForLang(t: TrieWord, lang: Int): Float {
        val n = minOf(loadedLangs.size, 4)
        for (i in 0 until n) if (loadedLangs[i] == lang) return SoftFloat.fdiv(SoftFloat.i2f(t.u8(8 + i)), 255.0f)
        return 0f
    }

    /**
     * LangEnv::modifyWordCaps(std::string const&, bool caps7, bool caps6, bool caps5, bool reset)
     * @004bb81c, on the lowered word (not chomped: surrounding spaces stay in the custom word). Unless [reset], a dictionary word that already has
     * every requested caps bit is left alone. Then on its MooWord: reset clears bits 7/6/5
     * (otherwise bit 5 is set when none of them is), the requested bits are set, a missing
     * focused-language score becomes 0.5, then saveAndReflect and the weird-case hook.
     */
    fun modifyWordCaps(word: ByteArray, caps7: Boolean, caps6: Boolean, caps5: Boolean, reset: Boolean) {
        val lowered = standardiseToLower(word)
        // only the emptiness test uses the chomped word; everything below keeps the whitespace
        if (Shing.chompWhitespaceOnEnds(lowered).isEmpty()) return
        if (!reset) {
            val t = maybeGetRawWord(lowered)
            if (t >= 0) {
                val w = tw(t)
                var need = false
                if (caps7) need = !w.flagged(7)
                if (caps6) need = need or !w.flagged(6)
                if (caps5) need = need or !w.flagged(5)
                if (!need) return
            }
        }
        val mw = obtainWordMod(lowered)
        if (reset) { mw.unflag(7); mw.unflag(6); mw.unflag(5) }
        else if (!mw.flagged(7) && !mw.flagged(6) && !mw.flagged(5)) mw.flag(5)
        if (caps7) mw.flag(7)
        if (caps6) mw.flag(6)
        if (caps5) mw.flag(5)
        if (!mw.hasScoremult(focusedLang)) mw.setScoremultForLang(0.5f, focusedLang)
        saveAndReflect(mw, lowered)
        onDisableWeirdCase(lowered)
    }

    /** TrieWord::getScoremult(Moopad*) @007b0b14: the largest score byte among the active language
     *  indexes ([activeLangIndexMask]), / 255 (0 when none is active). */
    fun trieWordScoremult(t: TrieWord): Float = trieWordScoremult(t, activeLangIndexMask)

    /** TrieWord::isLearnedFCAL(Moopad*) @007b1714: not below 0.02 by [trieWordScoremult], and then
     *  true for a word with an empty language mask (+4 bits 0-3), else flag 0xB (learned). */
    fun trieWordIsLearnedFCAL(t: TrieWord): Boolean {
        // ENGINE QUIRK (see TrieWord.kt): a TrieWord the trie creates (not from the language pack)
        // has stack garbage in its +4 language mask, which the port stores as 0. The oracle's
        // garbage there is nonzero (0xC / 0xE seen), so the engine takes the flag-0xB branch, not
        // the "empty mask = learned" one; do the same for such words.
        if (t.u8(4) and 0xf == 0 && !t.isFromLangDict()) {
            if (SoftFloat.fcmplt(trieWordScoremult(t), Float.fromBits(0x3ca3d70a))) return false
            return t.flagged(0xb)
        }
        return trieWordIsLearnedFCAL(t, activeLangIndexMask)
    }

    companion object {
        /** [trieWordScoremult] for an explicit active-language-index mask. */
        fun trieWordScoremult(t: TrieWord, mask: Int): Float {
            var m = 0
            for (i in 0..3) if (mask and (1 shl i) != 0 && t.u8(8 + i) > m) m = t.u8(8 + i)
            return SoftFloat.fdiv(SoftFloat.i2f(m), 255.0f)
        }

        /** [trieWordIsLearnedFCAL] for an explicit active-language-index mask. */
        fun trieWordIsLearnedFCAL(t: TrieWord, mask: Int): Boolean {
            if (SoftFloat.fcmplt(trieWordScoremult(t, mask), Float.fromBits(0x3ca3d70a))) return false
            if (t.u8(4) and 0xf == 0) return true
            return t.flagged(0xb)
        }
    }

    /** The score MooWord::isLearnedFCAL / markLearnedFCAL test: the max over the score-map entries
     *  of active languages (from 0, strict), or the default score when no entry is active. */
    private fun mooWordActiveScore(mw: MooWord): Float {
        var best = 0f
        var found = false
        for ((lang, byte) in mw.scores) {
            if (lang !in activeLangs) continue
            val v = SoftFloat.fdiv(SoftFloat.i2f(byte), 255.0f)
            found = true
            if (SoftFloat.fcmplt(best, v)) best = v
        }
        return if (found) best else mw.defaultScore
    }

    /** MooWord::isLearnedFCAL(Moopad*) @007b4af8: active score not below 0.02 and flag 0xB. */
    fun mooWordIsLearnedFCAL(mw: MooWord): Boolean =
        !SoftFloat.fcmplt(mooWordActiveScore(mw), Float.fromBits(0x3ca3d70a)) && mw.flagged(0xb)

    /** MooWord::markLearnedFCAL(Moopad*) @007b4c50: flag 0xB; when the active score is below 0.3 every
     *  active language's score byte becomes 0x4C (0.298). */
    fun markLearnedFCAL(mw: MooWord) {
        mw.flag(0xb)
        val s = mooWordActiveScore(mw)
        if (SoftFloat.fcmpge(s, Float.fromBits(0x3e99999a)) || SoftFloat.fcmpun(s, Float.fromBits(0x3e99999a))) return
        for (l in activeLangs) mw.scores[l] = 0x4c
    }

    /** LangEnv::markWordLearned(std::string const&) @004ac790, on the word as given (no lowering):
     *  nothing for a blank word or a TrieWord already isLearnedFCAL; else obtainWordMod,
     *  markLearnedFCAL, saveAndReflect. */
    fun markWordLearned(word: ByteArray) {
        if (Shing.chompWhitespaceOnEnds(word).isEmpty()) return
        val t = maybeGetRawWord(word)
        if (t >= 0 && trieWordIsLearnedFCAL(tw(t))) return
        val mw = obtainWordMod(word)
        markLearnedFCAL(mw)
        saveAndReflect(mw, word)
    }

    /** LangEnv::learnNewWord(std::string const&) @004ac4c0: on lw = standardiseToLower(word), a word
     *  not in the trie or with getScoremult <= 0.02 first gets modifyWordLC(lw, 0.5); then
     *  markWordLearned(lw). (WordLearningModule::onMerge follows it with
     *  markMinimumScoreForWord(word, 0.5, -1).) */
    fun learnNewWord(word: ByteArray) {
        val lw = standardiseToLower(word)
        val t = maybeGetRawWord(lw)
        if (t < 0 || !SoftFloat.fcmpgt(trieWordScoremult(tw(t)), Float.fromBits(0x3ca3d70a))) modifyWordLC(lw, 0.5f)
        markWordLearned(lw)
    }

    /** LangEnv::standardiseToLower(std::string const&) @004758bc (non-Turkish): per code point,
     *  U+2018 / U+2019 -> '\'', else shing_towlower, re-encoded as UTF-8. */
    fun standardiseToLower(word: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (cp in Utf8.split(word)) {
            val c = if (cp ushr 1 == 0x100c) 0x27 else Keycode.shingTowlower(cp)
            Utf8.append(out, c)
        }
        return out.toByteArray()
    }
}

/**
 * The fresh-install default word mods Moopad::Moopad() applies once (then persisted as
 * applied), split into switchable rule sets. Native order: CAPITAL_I's "i", then SCORES, then
 * the rest of CAPITAL_I, then APOSTROPHE_S.
 */
object DefaultWordMods {
    enum class Rule {
        /** modifyWordCaps(w, caps7 = false, caps6 = true, caps5 = false, reset = true) for
         *  i, i'll, i'm, i've, i'd: always capitalised "I...". (The pack's own "i" also carries
         *  spelling-mode bit 7, so the dictionary word is capitalised even without this rule.) */
        CAPITAL_I,
        /** modifyWordLC("i", 1.0) and modifyWordLC(u / o / l / j / k, -0.2 -> 0): the score of "i"
         *  to the maximum and single letters u, o, l, j, k disabled. */
        SCORES,
        /** markMinimumScoreForWord("'s", 0.8, English) then modifyWordCaps("'s", 0, 0, 1, 1). */
        APOSTROPHE_S,
    }

    val ALL: Set<Rule> = Rule.values().toSet()

    fun apply(env: WordModEnv, rules: Set<Rule> = ALL) {
        fun b(s: String) = s.toByteArray(Charsets.UTF_8)
        val iWords = listOf("i'll", "i'm", "i've", "i'd")
        if (Rule.CAPITAL_I in rules) env.modifyWordCaps(b("i"), false, true, false, true)
        if (Rule.SCORES in rules) {
            env.modifyWordLC(b("i"), 1.0f)
            for (w in listOf("u", "o", "l", "j", "k")) env.modifyWordLC(b(w), -0.2f)
        }
        if (Rule.CAPITAL_I in rules) for (w in iWords) env.modifyWordCaps(b(w), false, true, false, true)
        if (Rule.APOSTROPHE_S in rules) {
            env.markMinimumScoreForWord(b("'s"), 0.8f, 1)
            env.modifyWordCaps(b("'s"), false, false, true, true)
        }
    }
}

/**
 * NOT ported engine behavior: an integration switch for the capital-I words, built only from the
 * ported word-mod primitives. Background: the pack's own spelling-mode flags already mark these
 * words for capitalisation ("i" bit 7, "i'm" & co. bit 6), so turning [DefaultWordMods.Rule.CAPITAL_I]
 * off does NOT stop "I" being produced, it only skips the engine's normalisation (bit 6, and score
 * 0.5 for the I-contractions). [set] with enabled = false clears caps bits 7/6/5 through the
 * engine's own reset path (modifyWordCaps(w, false, false, false, reset = true)), so
 * TrieWord::spliceCaps leaves the typed case; enabled = true re-applies the default rule.
 */
object CapitalI {
    val WORDS = listOf("i", "i'll", "i'm", "i've", "i'd")

    fun set(env: WordModEnv, enabled: Boolean) {
        for (w in WORDS) env.modifyWordCaps(w.toByteArray(Charsets.UTF_8), false, enabled, false, true)
    }
}

/** Shing:: string / char helpers used by the word-mod path. */
object Shing {
    /** shing_iswupper(int): code points that shing_towlower changes (equal to the exact table
     *  [Caps.shingIswupper] on every code point: checked exhaustively by gen_trie_learn.py). */
    fun iswupper(cp: Int): Boolean = Keycode.shingTowlower(cp) != cp

    /** Morestring::chompWhitespaceOnEnds(std::string const&): strip C isspace bytes at both ends. */
    fun chompWhitespaceOnEnds(s: ByteArray): ByteArray {
        fun sp(b: Byte) = b.toInt().let { it == 0x20 || it in 0x09..0x0d }
        var a = 0
        var e = s.size
        while (a < e && sp(s[a])) a++
        while (e > a && sp(s[e - 1])) e--
        return s.copyOfRange(a, e)
    }
}
