package nintype.engine.trie

/**
 * Emkey::ACHist (16 bytes): +0 the word as typed, +4 its trie string (get_a_triestring), +8 SDate.
 */
data class ACHist(val word: ByteArray, val ts: ByteArray, val date: SDate) {
    override fun equals(other: Any?) = other is ACHist && other.word.contentEquals(word) && other.ts.contentEquals(ts) && other.date == date
    override fun hashCode() = word.contentHashCode()
}

/**
 * Emkey::ACStem (0x140 bytes), the autocomplete history of one trie-string prefix: +8 the stem,
 * +0xC dirty Trigger (saves it), +0xF4 u8 has a save file, +0xFC vector<ACLockedWord> (locked /
 * favourite words: written by onLockUsage / markFavorite, not ported), +0x108 deque<ACHist>
 * recent words (oldest first, at most 12 after a new word), +0x130 the last cancelled ACHist.
 */
/**
 * Emkey::ACLockedWord (0x18 bytes): +0 the word, +4 its trie string, +8 s8 slot (the suggestion slot
 * it was picked from), +9..+0xB padding the engine leaves as c7 ff ff, +0xC 0, +0x10 Flag32
 * favourite prefix lengths, +0x14 Flag32 shortcut prefix lengths (bit n = picked after typing n
 * characters).
 */
class ACLockedWord(val word: ByteArray, val ts: ByteArray) {
    /** The +9..+0xB padding bytes: c7 ff ff as the engine builds one, 0 when loaded from a file
     *  (only visible in memory dumps). */
    var pad = 0xffffc7
    var slot = 0
    var fav = 0
    var shortcut = 0
    val inactive get() = fav == 0 && shortcut == 0
}

private fun bit(n: Int) = if (n in 0..31) 1 shl n else 0

class ACStem(val stem: ByteArray, created: SDate) {
    val hist = ArrayList<ACHist>()
    /** +0xFC vector<ACLockedWord>. */
    val locked = ArrayList<ACLockedWord>()
    var onDisk = false
    /** The +0xC save Trigger is armed (needSave): ACStem::saveIt runs when it fires. */
    var dirty = false
    /** +0x130: an ACHist() (empty, dated by gettimeofday at construction) until the first cancel. */
    var cancelled = ACHist(ByteArray(0), ByteArray(0), created)

    /**
     * ACStem::onStandardUsage(word) @010574b8. A word already in the deque: with one entry its date
     * becomes [now]; otherwise the engine copies the first match, erases the deque's FRONT entry (not
     * the match: begin() is used where the match's position was meant) and appends the copy dated
     * [now], so a repeated word duplicates and the oldest word drops. A new word is appended dated
     * [clock] (ACHist() = gettimeofday) and the front is dropped once there are 13.
     */
    fun onStandardUsage(word: ByteArray, now: SDate, clock: SDate) {
        dirty = true
        val i = hist.indexOfFirst { it.word.contentEquals(word) }
        if (i < 0) {
            hist.add(ACHist(word.copyOf(), PendingTrie.getATriestring(word), clock))
            if (hist.size >= 13) hist.removeAt(0)
            return
        }
        if (hist.size == 1) {
            hist[0] = hist[0].copy(date = now)
            return
        }
        val cp = hist[i]
        hist.removeAt(0)
        hist.add(cp.copy(date = now))
    }

    /** ACStem::onCancel(word) @01057090: the first match, when no more than 5 s old by [clock]
     *  (SDate::hasPassed(5.0)), is erased and kept as [cancelled]. */
    fun onCancel(word: ByteArray, clock: SDate) {
        val i = hist.indexOfFirst { it.word.contentEquals(word) }
        if (i < 0) return
        val h = hist[i]
        if (hasPassed(h.date, clock, 5.0)) return
        cancelled = h
        hist.removeAt(i)
    }

    /**
     * ACStem::onLockingUsage(word, slot, prefix) @01056b0c: the word's locked entry (appended if
     * new) gets [slot] and shortcut bit len(prefix); nothing changes when it already has both.
     */
    fun onLockingUsage(word: ByteArray, slot: Int, prefix: ByteArray) {
        val lw = locked.firstOrNull { it.word.contentEquals(word) }
            ?: ACLockedWord(word.copyOf(), PendingTrie.getATriestring(word)).also { locked.add(it) }
        if (lw.slot == (slot and 0xff).toByte().toInt() && lw.shortcut and bit(prefix.size) != 0) return
        lw.slot = slot.toByte().toInt()
        lw.shortcut = lw.shortcut or bit(prefix.size)
        dirty = true
    }

    /**
     * ACStem::maybeForgetSlot(word, slot, prefix) @0105616c: every other locked word in the same
     * slot whose shortcut bit len(prefix) is set and whose trie string begins with [prefix] loses
     * that bit in both masks; when another locked word exists, moveInactiveLockedwordsToRecentHist.
     */
    fun maybeForgetSlot(word: ByteArray, slot: Int, prefix: ByteArray, clock: SDate) {
        var other = false
        for (lw in locked) {
            if (lw.word.contentEquals(word)) continue
            other = true
            if (lw.slot == slot.toByte().toInt() && lw.shortcut and bit(prefix.size) != 0 && startsWith(lw.ts, prefix)) {
                lw.shortcut = lw.shortcut and bit(prefix.size).inv()
                lw.fav = lw.fav and bit(prefix.size).inv()
            }
        }
        if (other) moveInactiveLockedwordsToRecentHist(clock)
    }

    /**
     * ACStem::moveInactiveLockedwordsToRecentHist() @01056258: locked words with both masks empty
     * leave the locked list; those not already in the recent words are appended to them (dated by
     * gettimeofday, dropping the oldest past 12).
     */
    fun moveInactiveLockedwordsToRecentHist(clock: SDate) {
        if (locked.none { it.inactive }) return
        val inHist = hist.map { BytesKey(it.word) }.toHashSet()
        val keep = ArrayList<ACLockedWord>()
        for (lw in locked) {
            if (!lw.inactive) { keep.add(lw); continue }
            if (BytesKey(lw.word) in inHist) continue
            hist.add(ACHist(lw.word.copyOf(), lw.ts.copyOf(), clock))
            if (hist.size > 12) hist.removeAt(0)
        }
        locked.clear()
        locked.addAll(keep)
        dirty = true
    }

    /** Flector<ACStem> version 2: +0xF8 stem, +0xFC vector<ACLockedWord> (version 4: word, trie
     *  string, s8 slot, +0xC, +0x10 favourite mask, +0x14 shortcut mask), +0x108 deque<ACHist>
     *  (version 4: word, trie string, SDate). The cancelled ACHist is not saved. */
    fun binarize(): ByteArray = FlecWriter.file(2) {
        str(stem)
        u32(locked.size)
        for (lw in locked) obj(4) { str(lw.word); str(lw.ts); schar(lw.slot); u32(0); u32(lw.fav); u32(lw.shortcut) }
        u32(hist.size)
        for (h in hist) obj(4) { str(h.word); str(h.ts); date(h.date) }
    }

    /** Flector::load of a stem file into this fresh stem (all or nothing). */
    fun unbinarize(data: ByteArray) {
        val (lws, hs) = FlecReader.file(data) { _ ->
            str()
            val lws = (0 until count()).map { obj { _ ->
                ACLockedWord(str(), str()).also { lw -> lw.pad = 0; lw.slot = schar(); u32(); lw.fav = u32(); lw.shortcut = u32() } } }
            val hs = (0 until count()).map { obj { _ -> ACHist(str(), str(), date()) } }
            Pair(lws, hs)
        }
        locked.clear(); locked.addAll(lws)
        hist.clear(); hist.addAll(hs)
    }

    private fun startsWith(a: ByteArray, p: ByteArray): Boolean {
        if (a.size < p.size) return false
        for (i in p.indices) if (a[i] != p[i]) return false
        return true
    }

    companion object {
        /** SDate::hasPassed(float) @00c51584: (now.sec - sec) + (now.usec - usec) / 1e6 > secs, in doubles. */
        fun hasPassed(d: SDate, now: SDate, secs: Double): Boolean =
            (now.sec - d.sec).toDouble() + (now.usec - d.usec).toDouble() / 1000000.0 > secs
    }
}

/**
 * The learning side of Emkey::AutoCompEnv: +8 std::set<string> of every stem created, +0x20
 * shingcachemap<string, Refcounter<ACStem>> (pruning, threshold 1, MFU 60, LRU 120) whose entries
 * are saved per stem (+0xF4) and reloaded by ACStem::init, so the cache is modelled as a plain map
 * (lossless while each stem is saved before it is evicted). [gated] = Xnl Moopad+0x1F424.
 */
class AutoCompLearn(var gated: Boolean = false, val log: (String) -> Unit = {}) {
    /** The stems in memory (the +0x20 cache, modelled without eviction). */
    val stems = java.util.TreeMap<ByteArray, ACStem>(WordLearningModule::unsignedCompare)
    /** +8 std::set<string>: every stem created, plus (AutoCompEnv::AutoCompEnv) demexify of each file
     *  name in the save directory. */
    val known = java.util.TreeSet<ByteArray>(WordLearningModule::unsignedCompare)
    /** The appdata/emkeyautoc directory: Mexify.mexify(stem) -> ACStem::binarize (Flector::save via
     *  .temp + rename). */
    val disk = java.util.TreeMap<String, ByteArray>()

    /** AutoCompEnv::AutoCompEnv's directory scan over saved stem [files] (name -> bytes); the stems
     *  themselves load lazily, in ACStem::init. Names that are not mexified keys are skipped. */
    fun load(files: Map<String, ByteArray>) {
        stems.clear(); known.clear(); disk.clear()
        for ((n, d) in files) {
            val k = Mexify.demexify(n)
            if (k == null) { log("emkeyautoc: skipping $n"); continue }
            disk[n] = d
            known.add(k)
        }
    }

    /** ACStem::saveIt @01055c24 for every stem whose save trigger is armed (when the triggers fire). */
    fun flush() {
        for (st in stems.values) if (st.dirty) saveIt(st)
    }

    private fun saveIt(st: ACStem) {
        disk[Mexify.mexify(st.stem)] = st.binarize()
        st.onDisk = true
        st.dirty = false
    }

    /** AutoCompEnv::obtainStemInner(stem) @01059430: get or create; ACStem::init @01055c64 loads the
     *  stem's file when there is one (+0xF4 = it loaded). */
    fun obtainStemInner(stem: ByteArray, clock: SDate = SDate(0, 0)): ACStem = stems.getOrPut(stem.copyOf()) {
        known.add(stem.copyOf())
        ACStem(stem.copyOf(), clock).also { st ->
            val data = disk[Mexify.mexify(stem)]
            if (data != null) {
                try { st.unbinarize(data); st.onDisk = true } catch (x: FlecFormatException) {
                    log("emkeyautoc: ${Mexify.mexify(stem)} unreadable (${x.message}), starting empty")
                }
            }
        }
    }

    /**
     * AutoCompEnv::obtainStem(stem) @010591ec: stems of 3+ bytes are obtainStemInner. A shorter stem
     * without a save file is filled once by performStemImportation and then saved (+0xF4 = 1).
     */
    fun obtainStem(stem: ByteArray, clock: SDate): ACStem {
        val st = obtainStemInner(stem, clock)
        if (stem.size >= 3 || st.onDisk) return st
        performStemImportation(st, stem)
        saveIt(st)
        return st
    }

    /**
     * AutoCompEnv::performStemImportation(stem, s) @01059538: every known stem beginning with [s]
     * (in set order, [s] included) contributes its ACHists to a std::map keyed by -unixSeconds (a
     * later entry with the same second replaces an earlier one); the 13 newest are pushed onto the
     * front of the stem's deque, newest first, so they end up oldest first. (The locked-word half
     * copies ACLockedWords, which are always empty here: their writers are not ported.)
     */
    fun performStemImportation(st: ACStem, s: ByteArray) {
        val m = java.util.TreeMap<Long, ACHist>()
        for (k in known.tailSet(s, true).toList()) {
            if (!startsWith(k, s)) break
            for (h in obtainStemInner(k).hist) m[-h.date.sec.toLong()] = h
        }
        var n = 0
        for (h in m.values) {
            if (n >= 13) break  // the engine's counter starts at -1 and stops once it reaches 12: 13 entries
            st.hist.add(0, h)
            n++
        }
        // The locked words: per slot (>= 0), the last one (stem order, then list order) whose shortcut or
        // favourite mask has bit len(s) set; appended in slot order.
        val bySlot = java.util.TreeMap<Int, ACLockedWord>()
        for (k in known.tailSet(s, true).toList()) {
            if (!startsWith(k, s)) break
            for (lw in obtainStemInner(k).locked) {
                if (lw.slot < 0) continue
                val b = bit(s.size)
                if (lw.shortcut and b == 0 && lw.fav and b == 0) continue
                bySlot[lw.slot] = ACLockedWord(lw.word.copyOf(), lw.ts.copyOf()).also { it.slot = lw.slot; it.fav = lw.fav; it.shortcut = lw.shortcut }
            }
        }
        st.locked.addAll(bySlot.values)
    }

    private fun startsWith(a: ByteArray, p: ByteArray): Boolean {
        if (a.size < p.size) return false
        for (i in p.indices) if (a[i] != p[i]) return false
        return true
    }

    /** The 3-, 2- and 1-byte prefixes of get_a_triestring(word) when it has at least 4 bytes. */
    private fun forStems(word: ByteArray, clock: SDate, f: (ACStem) -> Unit) {
        val ts = PendingTrie.getATriestring(word)
        if (ts.size < 4) return
        for (n in intArrayOf(3, 2, 1)) f(obtainStem(ts.copyOfRange(0, n), clock))
    }

    /** AutoCompEnv::onStandardUsage(word) @0105a61c ([now] = the static now SDate, [clock] = gettimeofday). */
    fun onStandardUsage(word: ByteArray, now: SDate, clock: SDate) {
        if (gated) return
        forStems(word, clock) { it.onStandardUsage(word, now, clock) }
    }

    /**
     * AutoCompEnv::onLockUsage(word, slot, prefix) @0105b060 (Moopad::onAutoCompleteChoice): for a
     * word whose trie string is 4+ bytes, the stem of the typed [prefix] (its first 3 bytes) gets
     * onLockingUsage and maybeForgetSlot.
     */
    fun onLockUsage(word: ByteArray, slot: Int, prefix: ByteArray, clock: SDate) {
        if (PendingTrie.getATriestring(word).size <= 3) return
        val key = if (prefix.size > 3) prefix.copyOfRange(0, 3) else prefix
        val st = obtainStem(key, clock)
        st.onLockingUsage(word, slot, prefix)
        st.maybeForgetSlot(word, slot, prefix, clock)
    }

    /**
     * AutoCompEnv::markFavorite(word, slot, n, value) @0105ad30: in the stem of the word's trie
     * string cut to min(3, n) bytes, the word's locked entry gets favourite bit n set to [value].
     */
    fun markFavorite(word: ByteArray, n: Int, value: Boolean, clock: SDate) {
        val ts = PendingTrie.getATriestring(word)
        if (ts.size <= 3) return
        var key = ts.copyOfRange(0, 3)
        if (n >= 0 && n < key.size) key = key.copyOfRange(0, n)
        val st = obtainStem(key, clock)
        if (n !in 0..31) return
        val lw = st.locked.firstOrNull { it.word.contentEquals(word) } ?: return
        lw.fav = if (value) lw.fav or (1 shl n) else lw.fav and (1 shl n).inv()
        st.dirty = true
    }

    /** AutoCompEnv::onCancel(word) @0105a9b4 (no gate). */
    fun onCancel(word: ByteArray, clock: SDate) = forStems(word, clock) { it.onCancel(word, clock) }
}
