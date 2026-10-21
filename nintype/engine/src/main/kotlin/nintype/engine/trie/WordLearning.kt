package nintype.engine.trie

/**
 * SDate: a struct timeval {+0 sec, +4 usec}. SDate() = gettimeofday; isNull() @00c50934 = sec == 0;
 * operator< @00c5132c compares (sec, usec) as signed ints; dDay(n) @00c50ff4 = localtime_r, tm_mday
 * += n, mktime (usec kept). The engine runs in UTC on the oracle, so [dDay] defaults to UTC; pass
 * the device zone for an on-device port (mktime keeps the tm_isdst localtime gave).
 */
data class SDate(val sec: Int, val usec: Int) {
    val isNull get() = sec == 0
    fun lessThan(o: SDate) = sec < o.sec || (sec == o.sec && usec < o.usec)
    fun dDay(n: Int, zone: java.time.ZoneId = java.time.ZoneOffset.UTC): SDate {
        val t = java.time.Instant.ofEpochSecond(sec.toLong()).atZone(zone).plusDays(n.toLong())
        return SDate(t.toEpochSecond().toInt(), usec)
    }
}

/**
 * Emkey::WeirdCaseManager::determineWeirdCaseStatus(word, std::string& lowered, bool& firstUp,
 * bool& allUp, bool& noUp, bool& weird, Flag32& upPositions) @00468164. A code point is upper when
 * shing_iswupper && !shing_iswlower. firstUp = the first is upper and no later one is; allUp = no
 * code point is non-upper (true for ""); noUp = none is upper; weird = some upper but neither
 * firstUp nor allUp. upPositions has bit i for each upper code point i < 32; lowered = towlower
 * of every code point.
 */
object WeirdCase {
    class Status(val lowered: ByteArray, val firstUp: Boolean, val allUp: Boolean, val noUp: Boolean, val weird: Boolean, val upPositions: Int)

    fun determine(word: ByteArray): Status {
        val out = java.io.ByteArrayOutputStream()
        var firstUp = false
        var laterUp = false
        var anyUp = false
        var anyNonUp = false
        var upos = 0
        for ((i, c) in Utf8.split(word).withIndex()) {
            val up = Caps.shingIswupper(c) && !Caps.shingIswlower(c)
            if (i == 0) firstUp = up else if (up) laterUp = true
            if (i <= 31 && up) upos = upos or (1 shl i)
            anyUp = anyUp || up
            anyNonUp = anyNonUp || !up
            Utf8.append(out, Keycode.shingTowlower(c))
        }
        val allUp = !anyNonUp
        val only = firstUp && !laterUp
        return Status(out.toByteArray(), only, allUp, !anyUp, anyUp && !(only || allUp), upos)
    }
}

/**
 * Emkey::PendingWord (0x38 bytes): +4 WordLearningModule*, +8 the word as first typed, +0xC u8 has
 * Hangul, +0xD u8 has non-basic chars, +0xE u8 expiry set, +0x10 its expiry-multiset node, +0x14
 * SDate expiry, +0x1C vector<PendingWordUsageHist> (16 bytes: +0 Flag32 (bit 3 = not typed),
 * +4 [u4], +8 SDate), +0x28 Flag32 caps (0 first-upper, 1 all-upper, 2 weird, 4 all-lower),
 * +0x2C uppercase positions of a weird word, +0x30 points, +0x34 u8 erased. getScore() = 0.5.
 */
class PendingWord(val word: ByteArray) {
    class Usage(val flags: Int, val u4: Int, val date: SDate)
    val hist = ArrayList<Usage>()
    var caps = 0
    var upos = 0
    var points = 0
    var expirySet = false
    var expiry = SDate(0, 0)
    var hasHangul = false
    var hasNonBasic = false
    var erased = false

    fun capsFlagged(bit: Int) = caps and (1 shl bit) != 0

    /** PendingWord::getScore() @008d462c. */
    val score get() = 0.5f

    /** PendingWord::getCapsBools(bool& allUp, bool& firstUp, bool& lower) @008d4644: weird -> (false,
     *  false, true); else flags 1, 0, 4. */
    fun capsBools(): Triple<Boolean, Boolean, Boolean> =
        if (capsFlagged(2)) Triple(false, false, true) else Triple(capsFlagged(1), capsFlagged(0), capsFlagged(4))
}

/**
 * Emkey::WordLearningModule: words typed that are not in the dictionary become pending words (they
 * join the search through the pending trie at score 0.5) and are learned into the dictionary at 9
 * points (3 per typed usage, 1 per other merge).
 *
 * Fields: +0 Moopad*, +0x20C Xnl "many pending words" (more than 260), +0x490 std::map<lowered word,
 * Refcounter<PendingWord>> (count +0x4A4), +0x4B8 multiset<SDate expiry, PendingWord*> (count
 * +0x4C8), +0x548 u8 pending trie in sync (cleared on every change; resyncPendingWordsTrie rebuilds).
 *
 * Hooks: [lower] = LangEnv::standardiseToLower; [learnNewWord] = LangEnv::learnNewWord;
 * [markMinimumScore] = LangEnv::markMinimumScoreForWord(word, 0.5, -1); [now] = gettimeofday
 * (SDate()). PendingWord::save / unsave write [disk] (the appdata/emkey/pendingwords KVStore).
 * The gates onMerge reads: Xnl Moopad+0x1F424, Xnl Moopad+0x2BA68, Moopad::hasUSF1(0xF7).
 */
class WordLearningModule(
    val lower: (ByteArray) -> ByteArray,
    val learnNewWord: (ByteArray) -> Unit = {},
    val markMinimumScore: (ByteArray, Float, Int) -> Unit = { _, _, _ -> },
    val now: () -> SDate = { SDate(0, 0) },
    val usageU4: Int = 0,
    var gateXnl1f424: Boolean = false,
    var gateXnl2ba68: Boolean = false,
    var gateUsfF7: Boolean = false,
    val zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
) {
    val pending = java.util.TreeMap<ByteArray, PendingWord>(::unsignedCompare)
    /** The expiry multiset in tree order (equal dates in insertion order). */
    val expiry = ArrayList<PendingWord>()
    /** +0x548: true once prepareForSearch -> resyncPendingWordsTrie has run on the current map. */
    var trieSynced = true
    var manyPending = false

    companion object {
        fun unsignedCompare(x: ByteArray, y: ByteArray): Int {
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return x.size - y.size
        }

        /**
         * WordLearningModule::wordIsValidForLearning(word) @008d8748: letters (shing_iswalpha), with at
         * most one ' or - anywhere after which only letters follow ("" and a trailing ' or - pass).
         */
        fun wordIsValidForLearning(word: ByteArray): Boolean {
            var sepSeen = false
            for (c in Utf8.split(word)) {
                if (nintype.engine.geometry.Geometry.shingIswalpha(c)) continue
                if (sepSeen || (c != 0x27 && c != 0x2d)) return false
                sepSeen = true
            }
            return true
        }
    }

    /** WordLearningModule::obtainWord(word) @008d84a0: by standardiseToLower(word); a new PendingWord
     *  keeps [word] as given. */
    fun obtainWord(word: ByteArray): PendingWord {
        val lw = lower(word)
        pending[lw]?.let { return it }
        val pw = PendingWord(word.copyOf())
        pending[lw] = pw
        manyPending = pending.size > 0x104
        trieSynced = false
        return pw
    }

    /**
     * The pendingwords Shing::KVStore directory (WLM+4, appdata/emkey/pendingwords): file name =
     * Mexify.mexify(PendingWord+8) -> Flector::binarize(PendingWord). PendingWord::save @008d4184
     * writes it after every usage change (an empty history deletes it); erasePendingWord deletes it.
     */
    val disk = java.util.TreeMap<String, ByteArray>()

    /** PendingWord::save() @008d4184. */
    fun save(pw: PendingWord) {
        val name = Mexify.mexify(pw.word)
        if (pw.hist.isEmpty()) disk.remove(name) else disk[name] = binarize(pw)
    }

    /** Flector<PendingWord> version 3: +0x14 SDate expiry, +0x1C vector<PendingWordUsageHist>
     *  (version 2: Flag32 flags, +4, SDate), +0x28 Flag32 caps, +0x2C uppercase positions. */
    fun binarize(pw: PendingWord): ByteArray = FlecWriter.file(3) {
        date(pw.expiry)
        u32(pw.hist.size)
        for (h in pw.hist) obj(2) { u32(h.flags); u32(h.u4); date(h.date) }
        u32(pw.caps)
        u32(pw.upos)
    }

    /**
     * WordLearningModule::startSlowLoad @008d6c80 + finalizeLoading @008d8810 over [files] (name ->
     * bytes, listed in name order as KVStore::getKeys returns them; loadStep takes them from the
     * back): obtainWord(the lowered key), PendingWord::load (KVStore::get of the new PendingWord's +8
     * name, Flector::loadBinary: expiry, history, caps, positions; a file that is missing or does
     * not parse loads nothing), syncPointsAndExpiry, and a word no longer valid for learning is
     * erased with its file. Names that are not mexified keys are skipped.
     */
    fun load(files: Map<String, ByteArray>, log: (String) -> Unit = {}) {
        disk.clear()
        disk.putAll(files)
        val keys = files.keys.sorted().mapNotNull { n -> Mexify.demexify(n).also { if (it == null) log("pendingwords: skipping $n") } }
        for (key in keys.asReversed()) {
            val lw = lower(key)
            val pw = obtainWord(lw)
            val data = disk[Mexify.mexify(pw.word)]
            if (data != null) {
                try {
                    val parsed = FlecReader.file(data) { _ ->
                        val exp = date()
                        val n = count()
                        val hist = (0 until n).map { obj { _ -> PendingWord.Usage(u32(), u32(), date()) } }
                        Triple(exp, hist, Pair(u32(), u32()))
                    }
                    pw.expiry = parsed.first
                    pw.hist.clear(); pw.hist.addAll(parsed.second)
                    pw.caps = parsed.third.first
                    pw.upos = parsed.third.second
                } catch (x: FlecFormatException) {
                    log("pendingwords: ${Mexify.mexify(pw.word)} unreadable (${x.message}), loaded empty")
                }
            }
            syncPointsAndExpiry(pw)
            if (!wordIsValidForLearning(lw)) {
                erasePendingWord(lw, false)
                disk.remove(Mexify.mexify(key))
            }
        }
    }

    /** PendingWord::regUsage(word, typed) @008d3c30 (+ syncPointsAndExpiry, save). */
    fun regUsage(pw: PendingWord, word: ByteArray, typed: Boolean, stamp: SDate) {
        if (typed) {
            val wc = WeirdCase.determine(word)
            if (wc.weird) {
                pw.caps = pw.caps and 0x13.inv() or 0x4
                pw.upos = wc.upPositions
            } else {
                if (wc.firstUp) pw.caps = pw.caps or 1
                if (wc.allUp) pw.caps = pw.caps or 2
                if (wc.noUp) pw.caps = pw.caps or 0x10
                pw.caps = pw.caps and 0x4.inv()
                pw.upos = 0
            }
        }
        pw.hist.add(PendingWord.Usage(if (typed) 0 else 8, usageU4, stamp))
        syncPointsAndExpiry(pw)
        save(pw)
    }

    /**
     * PendingWord::syncPointsAndExpiry() @008d3f6c: points = 3 per typed usage + 1 per other; the
     * expiry is set once (first usage): last usage date (or now when null) + 0 / 7 / 14 / 21 / 30 / 40
     * days for points < 1 / < 4 / 4 / 5 / 6 / >= 7, inserted into the expiry multiset.
     */
    fun syncPointsAndExpiry(pw: PendingWord) {
        var pts = 0
        var last = SDate(0, 0)
        for (h in pw.hist) {
            pts += if (h.flags and 8 != 0) 1 else 3
            last = h.date
        }
        pw.points = pts
        if (pw.expirySet) return
        val days = when {
            pts < 1 -> 0
            pts < 4 -> 7
            pts < 5 -> 14
            pts < 6 -> 21
            pts < 7 -> 30
            else -> 40
        }
        var d = if (last.isNull) now() else last
        if (days != 0) d = d.dDay(days, zone)
        pw.expiry = d
        pw.expirySet = true
        var i = expiry.size
        while (i > 0 && d.lessThan(expiry[i - 1].expiry)) i--
        expiry.add(i, pw)
    }

    /**
     * WordLearningModule::onMerge(std::string const& word, bool typed) @008d90ac: unless gated, a
     * valid non-empty word gets a usage; at 9 points it is learned (learnNewWord, then
     * markMinimumScoreForWord(word, 0.5, -1)). [stamp] = the static now SDate regUsage reads.
     */
    fun onMerge(word: ByteArray, typed: Boolean, stamp: SDate) {
        if (gateXnl1f424 || gateXnl2ba68 || gateUsfF7) return
        if (!wordIsValidForLearning(word) || word.isEmpty()) return
        val pw = obtainWord(word)
        regUsage(pw, word, typed, stamp)
        if (pw.points >= 9) {
            learnNewWord(word)
            markMinimumScore(word, 0.5f, -1)
        }
    }

    /** WordLearningModule::onMerge(WordAlternative const&) @008d8efc: a dictionary word
     *  (hasTrieWord) cancels its pending word; else onMerge(word, flags & 0x300 != 0). */
    fun onMergeAlternative(word: ByteArray, hasTrieWord: Boolean, flags: Int, stamp: SDate) {
        if (hasTrieWord) maybeCancelPendingWord(lower(word))
        else onMerge(word, flags and 0x300 != 0, stamp)
    }

    /** WLM+0x444 std::map<word, double> late erasures (WLM+0x45C Xnl = not empty). */
    val lateErasures = java.util.TreeMap<ByteArray, Double>(::unsignedCompare)
    var lateXnl = false

    /** PendingWord::unregUsage(word) @008d4318: drops the last usage (an lrand48() draw is made when
     *  it is older than 60 s; both outcomes drop it), then syncPointsAndExpiry (the expiry stays). */
    fun unregUsage(pw: PendingWord) {
        if (pw.hist.isEmpty()) return
        pw.hist.removeAt(pw.hist.size - 1)
        syncPointsAndExpiry(pw)
        save(pw)
    }

    /** WordLearningModule::callLateErasure(word) @008d9a54: erase [word] 20 s after [now] (the static
     *  engine-time double) unless it regains points. */
    fun callLateErasure(word: ByteArray, now: Double) {
        lateXnl = true
        lateErasures[word.copyOf()] = now + 20.0
    }

    /** WordLearningModule::onBackspace(word) @008d97f4: the pending word (by standardiseToLower) loses
     *  its last usage; at 0 points a late erasure is scheduled for [word] as given. */
    fun onBackspace(word: ByteArray, now: Double) {
        val pw = pending[lower(word)] ?: return
        unregUsage(pw)
        if (pw.points == 0) callLateErasure(word, now)
    }

    /** WordLearningModule::performLateErasures() @008d9eb4: each late entry with now > time: when
     *  the pending map has its key verbatim and that word has 0 points, erasePendingWord(key, false) and
     *  drop the entry; with points left the entry stays; without a pending word the entry is dropped. */
    fun performLateErasures(now: Double) {
        val it = lateErasures.entries.iterator()
        while (it.hasNext()) {
            val (k, t) = it.next()
            if (now <= t || now.isNaN() || t.isNaN()) continue
            val pw = pending[k]
            if (pw == null) { it.remove(); continue }
            if (pw.points != 0) continue
            erasePendingWord(k, false)
            it.remove()
        }
        lateXnl = lateErasures.isNotEmpty()
    }

    /**
     * WordLearningModule::onMergeAfterMeringAll(pre, wa) @008d9308 (from Moopad::
     * updateStateAfterMergingAll): [pre] = the text before the merged word. Its trailing letters
     * (shing_iswalpha, keeping a ' or - that has a letter on either side, scanning backwards) are the
     * fragment the word was glued to. No fragment: onMerge(wa). With one: onBackspace(pre); then, with
     * Moopad::hasUSF1(0x10B) ([usf10b]), w = standardiseToLower(fragment + word): a dictionary word
     * ([isTrieWord] = LangEnv::maybeGetTrieWord) cancels its pending word, else onMerge(w, false).
     */
    fun onMergeAfterMergingAll(pre: ByteArray, word: ByteArray, hasTrieWord: Boolean, flags: Int, stamp: SDate, now: Double,
                               usf10b: Boolean, isTrieWord: (ByteArray) -> Boolean) {
        val frag = trailingFragment(pre)
        if (frag.isEmpty()) {
            onMergeAlternative(word, hasTrieWord, flags, stamp)
            return
        }
        onBackspace(pre, now)
        if (!usf10b) return
        val lw = lower(frag + word)
        if (isTrieWord(lw)) maybeCancelPendingWord(lw) else onMerge(lw, false, stamp)
    }

    /** The backwards scan of onMergeAfterMeringAll (on Morestring::utf8reverse(pre)). */
    fun trailingFragment(pre: ByteArray): ByteArray {
        val cps = Utf8.split(pre).reversed()
        val got = ArrayList<Int>()
        var i = 0
        var look = 0
        while (i < cps.size || look != 0) {
            val c = if (look != 0) look else cps[i++]
            val alpha = nintype.engine.geometry.Geometry.shingIswalpha(c)
            if ((c == 0x27 || c == 0x2d) && i < cps.size) {
                look = cps[i++]
                if (!(alpha || nintype.engine.geometry.Geometry.shingIswalpha(look))) break
                got.add(c)
                continue
            }
            look = 0
            if (!alpha) break
            got.add(c)
        }
        val out = java.io.ByteArrayOutputStream()
        for (c in got.reversed()) Utf8.append(out, c)
        return out.toByteArray()
    }

    /**
     * WordLearningModule::resyncPendingWordsTrie() @008d6d8c (via prepareForSearch, which first
     * finishes the slow load; loading from the KVStore is not modelled): unless in sync, refresh every
     * PendingWord's flags from its word (+0xC Hangul: a Hangul code point, which also sets +0xD and
     * stops the scan; +0xD non-basic: a code point >= 0x81) and rebuild [root] from the map keys.
     */
    fun resyncPendingWordsTrie(root: PendingTrieNode) {
        if (trieSynced) return
        trieSynced = true
        for (pw in pending.values) {
            pw.hasHangul = false
            pw.hasNonBasic = false
            for (c in Utf8.split(pw.word)) {
                if (Hangul.isHangul(c)) { pw.hasHangul = true; pw.hasNonBasic = true; break }
                if (c >= 0x81) pw.hasNonBasic = true
            }
        }
        PendingTrie.resync(root, pending.entries.map { it.key to (it.value as Any) })
    }

    /** WordLearningModule::performPruning() @008d7894: while more than 260 words are pending, erase the
     *  one that expires first (the expiry multiset's first entry, by its word); then the "many" Xnl. */
    fun performPruning() {
        while (pending.size > 0x104 && expiry.isNotEmpty()) erasePendingWord(expiry[0].word, false)
        manyPending = pending.size > 0x104
    }

    /** WordLearningModule::onInduce(word) @008d9d60 (LangEnv::saveAndReflect -> Moopad::
     *  onCustomDictionaryWordModification): erasePendingWord(word, true), so a word leaves the pending
     *  map when it is learned (learnNewWord -> modifyWordLC / markWordLearned -> saveAndReflect). */
    fun onInduce(word: ByteArray) = erasePendingWord(word, true)

    /** WordLearningModule::maybeCancelPendingWord(word) @008d8e0c: erasePendingWord(word, false) when present. */
    fun maybeCancelPendingWord(word: ByteArray) {
        if (pending.containsKey(word)) erasePendingWord(word, false)
    }

    /** WordLearningModule::erasePendingWord(word, bool) @008d7db8: by standardiseToLower(word). */
    fun erasePendingWord(word: ByteArray, @Suppress("UNUSED_PARAMETER") b: Boolean) {
        val lw = lower(word)
        val pw = pending[lw] ?: return
        if (pw.expirySet) expiry.remove(pw)
        pw.erased = true
        pending.remove(lw)
        disk.remove(Mexify.mexify(pw.word))
        trieSynced = false
    }
}
