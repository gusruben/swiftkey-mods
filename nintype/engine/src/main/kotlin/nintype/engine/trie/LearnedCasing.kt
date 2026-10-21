package nintype.engine.trie

/**
 * Emkey::WeirdCase (map value, 0x10 bytes): +0 vector<Flag32> casings (uppercase-position masks,
 * the current one first), +0xC u8 active.
 */
class WeirdCaseEntry {
    val casings = ArrayList<Int>()
    var active = false

    /**
     * WeirdCase::updateWeirdUsage(bool used, Flag32 f) @00467684. used: a known mask moves to the
     * front (swapped with the first); a new one is appended and then swapped with the first; active
     * becomes true. Not used: active becomes false. Returns the engine's "needs save" result.
     */
    fun updateWeirdUsage(used: Boolean, f: Int): Boolean {
        if (!used) {
            val old = active
            active = false
            return !old
        }
        val i = casings.indexOf(f)
        if (i >= 0) {
            if (i != 0) { casings[i] = casings[0]; casings[0] = f }
            val old = active
            active = true
            return old
        }
        casings.add(f)
        if (casings.size >= 2) { val t = casings[0]; casings[0] = casings[casings.size - 1]; casings[casings.size - 1] = t }
        active = true
        return true
    }
}

/**
 * Emkey::WeirdCaseManager (Moopad+0x22C28): the user's learned unusual casings ("iPhone"), keyed by
 * the lowered word (+0x100 std::map<string, WeirdCase>). [lower] = LangEnv::standardiseToLower,
 * [usf121] = Moopad::hasUSF1(0x121) (keep the word as given in applyWeirdCase).
 */
class WeirdCaseManager(val lower: (ByteArray) -> ByteArray, var usf121: Boolean = false) {
    val map = java.util.TreeMap<ByteArray, WeirdCaseEntry>(WordLearningModule::unsignedCompare)
    /** Shing::Saver::needSave was called: the manager rewrites appdata/emkey/weirdcases on the next
     *  frame (Saver). */
    var dirty = false

    /** Flector<WeirdCaseManager> version 2: +0x100 map<string, WeirdCase> (WeirdCase version 2:
     *  vector<Flag32> casings, +0xC bool active). */
    fun binarize(): ByteArray = FlecWriter.file(2) {
        u32(map.size)
        for ((k, v) in map) { str(k); obj(2) { u32(v.casings.size); for (c in v.casings) u32(c); u8(if (v.active) 1 else 0) } }
    }

    /** The constructor's Flector::load of the weirdcases file (all or nothing). */
    fun load(data: ByteArray) {
        val m = FlecReader.file(data) { _ ->
            (0 until count()).map { str() to obj { _ -> WeirdCaseEntry().also { e -> repeat(count()) { e.casings.add(u32()) }; e.active = bool() } } }
        }
        map.clear()
        for ((k, v) in m) map[k] = v
    }

    private fun charCount(s: ByteArray) = Utf8.split(s).size

    /** WeirdCaseManager::onChooseCasing(word) @0046832c (on commit): a weird casing is registered for
     *  the lowered word, any other casing marks it inactive. */
    fun onChooseCasing(word: ByteArray) {
        val st = WeirdCase.determine(word)
        if (st.weird) registerWeirdCase(st.lowered, st.upPositions) else markWeirdCaseInactive(st.lowered)
    }

    /** WeirdCaseManager::registerWeirdCase(lowered, Flag32) @004684b0: words of 3+ code points. */
    fun registerWeirdCase(key: ByteArray, f: Int) {
        if (key.isEmpty() || charCount(key) < 3) return
        val e = map[key]
        if (e != null) { if (e.updateWeirdUsage(true, f)) dirty = true; return }
        map.getOrPut(key.copyOf()) { WeirdCaseEntry() }.updateWeirdUsage(true, f)
        dirty = true
    }

    /** WeirdCaseManager::markWeirdCaseInactive(lowered) @00468610: the entry is erased. */
    fun markWeirdCaseInactive(key: ByteArray) {
        if (key.isEmpty()) return
        if (map.remove(key) != null) dirty = true
    }

    /** WeirdCaseManager::disableWeirdCase(word) @00468758: markWeirdCaseInactive(standardiseToLower(word)). */
    fun disableWeirdCase(word: ByteArray) = markWeirdCaseInactive(lower(word))

    /** WeirdCaseManager::maybeGetWeirdCase(key) @004689d0: the current mask (0 for keys under 3 code
     *  points, unknown keys or no masks; active is not checked). */
    fun maybeGetWeirdCase(key: ByteArray): Int {
        if (charCount(key) <= 2) return 0
        val wc = map[key] ?: return 0
        return wc.casings.firstOrNull() ?: 0
    }

    /** WeirdCaseManager::convertWeirdCase(key, word) @00468b2c: [word] cased by [key]'s current mask
     *  when the entry exists and is active (and [word] has 3+ code points), else [word]. */
    fun convertWeirdCase(key: ByteArray, word: ByteArray): ByteArray {
        if (charCount(word) <= 2) return word.copyOf()
        val wc = map[key] ?: return word.copyOf()
        if (wc.casings.isEmpty() || !wc.active) return word.copyOf()
        return applyWeirdCase(word, wc.casings[0])
    }

    /**
     * WeirdCaseManager::applyWeirdCase(word, Flag32) @00468e50: unless [usf121] the word is
     * standardiseToLower'd first; code point i is upper-cased when bit i of [f] is set. Past
     * position 31 the last tested bit (bit 31) keeps applying (the engine reuses the stale flag).
     */
    fun applyWeirdCase(word: ByteArray, f: Int): ByteArray {
        val s = if (usf121) word else lower(word)
        val out = java.io.ByteArrayOutputStream()
        var up = false
        for ((i, c) in Utf8.split(s).withIndex()) {
            if (i <= 31) up = f and (1 shl i) != 0
            Utf8.append(out, if (up) Caps.shingTowupper(c) else c)
        }
        return out.toByteArray()
    }
}

/** Emkey::ExplicitTypeMeta (BadExplicitManager map value): +0 count, +4 last usage (unix s), +8 last destruction. */
class ExplicitTypeMeta {
    var count = 0
    var lastUsage = 0
    var lastDestruction = 0

    /** ExplicitTypeMeta::onUsage @00464ba4: lastUsage = now; count - 1, floored at -10. */
    fun onUsage(nowSec: Int) {
        lastUsage = nowSec
        count = if (count < -9) -10 else count - 1
    }

    /** ExplicitTypeMeta::onDestruction @00464a94: within 2 s of the last usage count + 2 (capped at
     *  6); lastDestruction = now. */
    fun onDestruction(nowSec: Int) {
        if (nowSec - lastUsage <= 2) count = minOf(count + 2, 6)
        lastDestruction = nowSec
    }
}

/**
 * Emkey::BadExplicitManager (Moopad+0x22DF0): short explicitly typed words the user keeps
 * deleting right after typing them (+0x100 std::map<string, ExplicitTypeMeta>, keyed by the lowered
 * word). [lower] = LangEnv::standardiseToLower. Times are the static now SDate's seconds.
 */
class BadExplicitManager(val lower: (ByteArray) -> ByteArray) {
    val map = java.util.TreeMap<ByteArray, ExplicitTypeMeta>(WordLearningModule::unsignedCompare)
    /** Shing::Saver::needSave was called (appdata/emkey/badexplicit is rewritten on the next frame). */
    var dirty = false

    /** Flector<BadExplicitManager> version 2: +0x100 map<string, ExplicitTypeMeta> (version 2:
     *  int count, int last usage, int last destruction). */
    fun binarize(): ByteArray = FlecWriter.file(2) {
        u32(map.size)
        for ((k, v) in map) { str(k); obj(2) { int(v.count); int(v.lastUsage); int(v.lastDestruction) } }
    }

    fun load(data: ByteArray) {
        val m = FlecReader.file(data) { _ ->
            (0 until count()).map { str() to obj { _ -> ExplicitTypeMeta().also { e -> e.count = int(); e.lastUsage = int(); e.lastDestruction = int() } } }
        }
        map.clear()
        for ((k, v) in m) map[k] = v
    }

    /** BadExplicitManager::onUsage(WordAlternative) @00465d3c: WA+0x1C <= 3 and WA flag 0x100. */
    fun onUsage(word: ByteArray, wa1c: Int, flags: Int, nowSec: Int) {
        if (wa1c > 3 || flags and 0x100 == 0) return
        map.getOrPut(lower(word)) { ExplicitTypeMeta() }.onUsage(nowSec)
        dirty = true
    }

    /** BadExplicitManager::onDestruction(WordAlternative) @00465a98: same filter, onDestruction. */
    fun onDestruction(word: ByteArray, wa1c: Int, flags: Int, nowSec: Int) {
        if (wa1c > 3 || flags and 0x100 == 0) return
        map.getOrPut(lower(word)) { ExplicitTypeMeta() }.onDestruction(nowSec)
        dirty = true
    }

    /** BadExplicitManager::onDestructionMaybe(word) @004656b8: words of at most 3 code points with an
     *  existing entry. */
    fun onDestructionMaybe(word: ByteArray, nowSec: Int) {
        if (Utf8.split(word).size > 3) return
        map[lower(word)]?.let { it.onDestruction(nowSec); dirty = true }
    }

    /** BadExplicitManager::maybeGet(key) @004655e0 (exact key, not lowered). */
    fun maybeGet(key: ByteArray): ExplicitTypeMeta? = map[key]
}

/** PendingWord::getWeirdCapsedVersion(word) @008d496c: a weird-cased pending word (caps flag 2) gets
 *  its stored uppercase positions (+0x2C) through WeirdCaseManager::applyWeirdCase; else [word]. */
fun PendingWord.getWeirdCapsedVersion(word: ByteArray, wcm: WeirdCaseManager): ByteArray =
    if (capsFlagged(2)) wcm.applyWeirdCase(word, upos) else word.copyOf()
