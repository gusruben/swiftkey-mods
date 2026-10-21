package nintype.engine.pipeline

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dcmple
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dcmpun
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmpge
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.FastCurveMapper
import nintype.engine.vectors.MemImage

/**
 * The switches for syncPredictedText's splice steps. Defaults = Nintype's behaviour; which ones
 * the SwiftKey integration turns off is decided later (inventory/SCOPE.md "Later decisions").
 */
class PredConfig(
    /** The typed-letters ("explicit") alternative inserted into the list (literalinsert). */
    var literalInsert: Boolean = true,
    /** Moopad::filterInvalids: drop alternatives scoring under 0.01. */
    var filterInvalids: Boolean = true,
    /** Moopad::spliceAutoComplete (with Xnl 0x29574). */
    var autoComplete: Boolean = true,
    /** Moopad::spliceWildGuesses. */
    var wildGuesses: Boolean = true,
    /** Moopad::spliceCustomScoring (super-exiled words to 0.0001). */
    var customScoring: Boolean = true,
    /** filterRepeatingResults. */
    var filterRepeats: Boolean = true,
    /** Moopad::performNeoMarkov. */
    var neoMarkov: Boolean = true,
    /** Moopad::spliceCapsToExplicit. */
    var capsToExplicit: Boolean = true,
    /** Moopad::spliceWeirdCaps. */
    var weirdCaps: Boolean = true,
    /** Moopad::maybeTransform (its default branch copies the word into the display text). */
    var transform: Boolean = true,
    /** Moopad::spliceSmartQuotes. */
    var smartQuotes: Boolean = true,
    /** The caps-lock upper-casing (Xnl 0x29c28). */
    var capsLockUpper: Boolean = true,
    /** Moopad::syncBackspaceRetypePenalties. */
    var backspaceRetype: Boolean = true,
    /** Moopad::spliceThiccAutocompleteSlots. */
    var thiccAutocomplete: Boolean = true,
)

/** MooMergeBuffer::getSweepChoice's results: [ok] its return, [index] the chosen result,
 * [t] its +0x88 time, [last] whether it is the buffer's last result, the chosen result's
 * WordAlternative and a copy of its alternatives (+0x5c). */
class SweepChoice(val ok: Boolean, val index: Int, val t: Double, val last: Boolean,
                  val chosen: MemImage, val alts: MutableList<MemImage>)

/**
 * The language-model queries syncPredictedText makes (trie area). TrieWords are named by id.
 * [PendingLang] reports each as a pending step until the trie port supplies it.
 */
interface PredLang {
    /** LangEnv::maybeGetTrieWord(std::string const&) (LangEnv at Moopad+0x21608). */
    fun trieWord(word: ByteArray): Int?
    fun isDisabledFCAL(tw: Int): Boolean
    fun isUnfavored(tw: Int): Boolean
    fun isSuperExiled(tw: Int): Boolean
    fun rawWord(tw: Int): ByteArray
    /** TrieWord::spliceCaps(std::string&, bool, bool, bool, Moopad*). */
    fun spliceCaps(tw: Int, s: ByteArray, b1: Boolean, b4000: Boolean, b8000: Boolean): ByteArray
    /** WeirdCaseManager::convertWeirdCase(key = the trie word's raw word, word = the WA's word). */
    fun weirdCase(raw: ByteArray, word: ByteArray): ByteArray
    /** spliceWeirdCaps on a pending word: WordLearningModule::obtainWord + getWeirdCapsedVersion. */
    fun pendingWeirdCapsed(word: ByteArray): ByteArray
    /** BadExplicitManager::maybeGet (Moopad+0x22df0): the count, null when absent. */
    fun badExplicitCount(lower: ByteArray): Int?
    /** Moopad::performNeoMarkov. */
    fun neoMarkov(alts: MutableList<MemImage>)
}

class PendingLang(private val pend: (String) -> Unit) : PredLang {
    override fun trieWord(word: ByteArray): Int? { pend("Emkey::LangEnv::maybeGetTrieWord"); return null }
    override fun isDisabledFCAL(tw: Int): Boolean { pend("Emkey::TrieWord::isDisabledFCAL"); return false }
    override fun isUnfavored(tw: Int): Boolean { pend("Emkey::TrieWord::isUnfavored"); return false }
    override fun isSuperExiled(tw: Int): Boolean { pend("Emkey::TrieWord::isSuperExiled (by id)"); return false }
    override fun rawWord(tw: Int): ByteArray { pend("Emkey::TrieWord::getRawWord"); return ByteArray(0) }
    override fun spliceCaps(tw: Int, s: ByteArray, b1: Boolean, b4000: Boolean, b8000: Boolean): ByteArray {
        pend("Emkey::TrieWord::spliceCaps (by id)"); return s
    }
    override fun weirdCase(raw: ByteArray, word: ByteArray): ByteArray { pend("Emkey::WeirdCaseManager::convertWeirdCase"); return word }
    override fun pendingWeirdCapsed(word: ByteArray): ByteArray { pend("Emkey::PendingWord::getWeirdCapsedVersion"); return word }
    override fun badExplicitCount(lower: ByteArray): Int? { pend("Emkey::BadExplicitManager::maybeGet"); return null }
    override fun neoMarkov(alts: MutableList<MemImage>) { pend("Emkey::Moopad::performNeoMarkov (hooks)") }
}

/**
 * Moopad state that lives outside the byte image (heap containers and std::strings), owned by
 * the engine: the current WordAlternative (Moopad+0x2b004; its scalar bytes are also mirrored
 * into the image, which the binary reads at 0x2b014 / 0x2b020 / 0x2b034), the alternatives
 * vector (+0x2b298), the strings at +0x2aff4 (committed-chain text), +0x2aff8 (explicit text),
 * +0x2b000 (wild-guess stem), +0x32e8c, the ACCollectage (+0x2b378) and the text before the
 * cursor (SpringboardMoopadUser+0x544, the solidified text so far).
 */
class PredState(pad: MemImage) {
    val current: MemImage = Wa.new("currentWA")
    val alts = ArrayList<MemImage>()
    var s2aff4 = ByteArray(0)
    var s2aff8 = ByteArray(0)
    var s2b000 = ByteArray(0)
    var s32e8c = ByteArray(0)
    val ac = nintype.engine.merge.ACModel(pad).also { it.reset() }
    var textBeforeCursor = ByteArray(0)
    /** The two WordAlternative slots at Moopad+0x2b2a4 / +0x2b2f4 (reset each call). */
    val slotA: MemImage = Wa.new("wa2b2a4")
    val slotB: MemImage = Wa.new("wa2b2f4")
    /** The forced WordAlternative a merge-all commits instead (Moopad+0x29d6c). */
    var forced: MemImage = Wa.new("wa29d6c")
    /** Moopad+0x32e5c: the last committed word, lower-cased. */
    var s32e5c = ByteArray(0)
    /** Moopad+0x2b5ec. */
    var s2b5ec = ByteArray(0)
    /** The recently used words deque (+0x9888; recordRecentlyUsedWord). */
    val recentlyUsed = ArrayDeque<ByteArray>()
    /** The recent committed words deque (+0x98b8), at most 5. */
    val recentWords = ArrayDeque<ByteArray>()
    /** The last WordAlternative with a non-empty word (Moopad+0x2b054). */
    val lastNonEmpty: MemImage = Wa.new("wa2b054")
    /** RecentWATransformed deque (+0x2af6c): (display text, time), at most 5. */
    val recentTransformed = ArrayDeque<Pair<ByteArray, Double>>()
    /** std::multimap<double, std::string> at Moopad+0x9870 (size +0x9884), in key order. */
    val history = ArrayList<Pair<Double, ByteArray>>()
}

/**
 * `Emkey::Moopad::syncPredictedText(std::string const&)` @006d4670: turns the merge buffers'
 * sweep choice into the alternatives list and the current (liquid) word.
 */
class SyncPredictedText(private val e: NintypeEngine) {
    private val pad get() = e.pad
    private val st get() = e.pred
    private val cfg get() = e.predConfig
    private fun xnl(off: Int) = pad.u8(off + 0xc)
    private fun setXnl(off: Int, v: Boolean) = pad.setU8(off + 0xc, if (v) 1 else 0)
    private fun step(label: String, alts: List<MemImage>, chosen: MemImage) = e.observer.predStep(label, alts, chosen)

    fun run() {
        e.predCalls += "map=${pad.i32(0x29d4c)} c294c0=${pad.i32(0x294c0)} sitting=${e.touches.sitting.size} live=${e.touches.live.size}"
        if (pad.i32(0x29d4c) == 0) {
            pad.setI32(0x294c0, -1)
            syncCorrectionBox()
            return
        }
        // the Shing::Context debug override never holds: return when the sitting count is unchanged
        val count = e.touches.sitting.size
        if (pad.i32(0x294c0) == count) return
        pad.setI32(0x294c0, count)
        // MooBoard::clearLiteralingKeys: key highlighting (UI)
        if (pad.u8(0x33074) == 0) {
            st.s2aff4 = ByteArray(0)
            setCurrentWA(Wa.new())
            st.s2b000 = ByteArray(0)
            pad.setF64(0x2b0b0, e.clock.gts)
            st.s2aff8 = ByteArray(0)
        }
        // SyncPredStep vector (+0x2b5ac): debug
        setXnl(0x2b490, false); setXnl(0x2b4c4, false); setXnl(0x2b4f8, false)
        st.ac.reset()
        st.alts.clear()
        Wa.assign(st.slotA, Wa.new()); Wa.assign(st.slotB, Wa.new())
        setXnl(0x2b344, false)
        e.predPathResults.clear()
        var did = false
        val first = e.merges.map.firstEntry()?.value
        if (first != null) {
            val sweep = e.sweepChoice(first)
            val chosen = sweep?.chosen ?: Wa.new()
            val alts: MutableList<MemImage> = sweep?.alts ?: ArrayList()
            val ok = sweep?.ok ?: false
            step("ein", alts, chosen)
            e.mostRecentAutoComplete(first, st.ac)
            setXnl(0x2b490, !st.ac.isEmpty())
            setXnl(0x2b4c4, st.ac.hasLeftCandidates())
            setXnl(0x2b4f8, st.ac.hasRightCandidates())
            if (xnl(0x29574) == 1) {
                setXnl(0x302e8, false); setXnl(0x30530, false); setXnl(0x30778, false); setXnl(0x309c0, false)
            } else loadEdgeternsFromAC()
            val ex = e.explicitResult(first)
            if (ex.text.isNotEmpty() && st.s2aff8.isEmpty()) st.s2aff8 = ex.text.copyOf()
            if (st.s2aff8.isNotEmpty() && cfg.literalInsert) literalInsert(alts, ex, sweep?.last ?: false)
            step("literalinsert", alts, chosen)
            if (ok) splices(chosen, alts)
            if (cfg.capsLockUpper && xnl(0x29c28) == 1) {
                val a = pad.f64(0x29c90); val b = pad.f64(0x29720)
                if (!(nintype.engine.SoftFloat.dcmpge(a, b) || dcmpun(a, b))) {
                    Wa.setS(chosen, 0, nintype.engine.trie.Caps.toWUpper(Wa.s(chosen, 0)))
                    for (w in alts) Wa.setS(w, 0, nintype.engine.trie.Caps.toWUpper(Wa.s(w, 0)))
                }
            }
            if (alts.isNotEmpty()) {
                val dt = dsub(e.clock.gts, pad.f64(0x32e40))
                val k = Bits.bitsToDouble(0x3f9eb852e0000000L)
                if (!(dcmple(dt, k) || dcmpun(dt, k)) && cfg.backspaceRetype) syncBackspaceRetypePenalties(alts)
                if (Wa.s(chosen, 0).contentEquals(st.s32e8c)) alts[0].setF32(0x2c, fmul(alts[0].f32(0x2c), 0.5f))
            }
            if (ok) {
                if (st.s2aff4.isNotEmpty()) st.s2aff4 = st.s2aff4 + byteArrayOf(0x20)
                st.s2aff4 = st.s2aff4 + Wa.s(chosen, 0)
                if (pad.u8(0x33074) == 0) {
                    setCurrentWA(chosen)
                    pad.setF64(0x2b0b0, sweep!!.t)
                }
                st.alts.clear(); st.alts.addAll(Wa.deepCopy(alts))
            }
            did = true
        }
        Wa.sort(st.alts)
        if (did && cfg.thiccAutocomplete) spliceThiccAutocompleteSlots()
        tail()
    }

    /** Moopad::syncCorrectionBox(std::string const&) @006b46e8 with no merge buffers: the word
     * state is cleared (the strings, the current WordAlternative, +0x2b0b0 = the clock, the
     * autocomplete Xnls and ACCollectage, the alternatives, the WordAlternative slots, the
     * PathResults; MooBoard literaling keys and the large previewer are UI), then the client's
     * markLiquid("", "", []) and inductToChainKeeper; +0x1f600 = 9954.5 (f64 bits 0x40c3714000000000).
     * historySyncProcedure(true, ...) is pending. */
    private fun syncCorrectionBox() {
        for (o in intArrayOf(0x2b194, 0x2b0f8, 0x2b0c4, 0x2b1fc, 0x2b12c, 0x2b160)) setXnl(o, false)
        e.pend("Emkey::Moopad::historySyncProcedure")
        st.s2aff4 = ByteArray(0); st.s2aff8 = ByteArray(0)
        setCurrentWA(Wa.new())
        st.s2b000 = ByteArray(0)
        pad.setF64(0x2b0b0, e.clock.gts)
        setXnl(0x2b490, false); setXnl(0x2b4c4, false); setXnl(0x2b4f8, false)
        st.ac.reset()
        // Moopad::loadBufferModeTapossi: UI
        if (xnl(0x29574) == 1) {
            setXnl(0x302e8, false); setXnl(0x30530, false); setXnl(0x30778, false); setXnl(0x309c0, false)
        } else loadEdgeternsFromAC()
        st.alts.clear()
        Wa.assign(st.slotA, Wa.new()); Wa.assign(st.slotB, Wa.new())
        setXnl(0x2b344, false)
        e.predPathResults.clear()
        // syncSlideternModeFromPredictionAlterns: UI
        e.liquid(ByteArray(0))
        inductToChainKeeper()
        pad.setI32(0x1f600, 0); pad.setI32(0x1f604, 0x40c37140)
    }

    // --- the literal ("explicit") alternative (@006c4ec0..006c5cc0) ----------------------------

    private val C_SPEED by lazy {      // static @0x1b93b58
        FastCurveMapper().push(30f, 1.5f).push(40f, 1f).push(50f, Bits.bitsToFloat(0x3f333333)).push(80f, Bits.bitsToFloat(0x3ecccccd))
            .push(100f, Bits.bitsToFloat(0x3e4ccccd)).push(120f, Bits.bitsToFloat(0x3e4ccccd)).finalize()
    }
    private val C_LEN by lazy {        // static @0x1b93b64
        FastCurveMapper().push(1f, 0f).push(2f, 0f).push(3f, Bits.bitsToFloat(0x3dcccccd)).push(4f, Bits.bitsToFloat(0x3e4ccccd))
            .push(5f, Bits.bitsToFloat(0x3e99999a)).push(7f, 0.5f).push(10f, 1f).finalize()
    }
    private val C_GAP by lazy {        // static @0x1b93b70
        FastCurveMapper().push(0f, 1f).push(0.5f, Bits.bitsToFloat(0x3f333333)).push(1f, 0.5f)
            .push(2f, Bits.bitsToFloat(0x3dcccccd)).push(3f, 0f).finalize()
    }
    private val F999 = 999f
    private val F998 = 998f

    private fun literalInsert(alts: MutableList<MemImage>, ex: nintype.engine.merge.ExplicitResult.Out, lastResult: Boolean) {
        val text = st.s2aff8
        val prev = latestCharBeforeCursor()
        var glued = 0
        if (nintype.engine.geometry.Geometry.shingIswalpha(prev)) {
            var t = 0
            if (xnl(0x297c8) == 1) t = xnl(0x29794)
            glued = t xor 1
        }
        val lit = Wa.new("literal")
        Wa.setS(lit, 4, text)
        val nchars = nintype.engine.trie.Utf8.split(text).size
        lit.setI32(0x1c, nchars)
        val lower = nintype.engine.trie.Caps.toWLower(text)
        var quote = 0
        if (lower.contentEquals(byteArrayOf(0x27, 0x27))) {
            val q = if (xnl(0x38a8) != 0) byteArrayOf(0x22)
            else utf8(if (prev < 1 || isSpace(prev)) 0x201c else 0x201d)
            Wa.setS(lit, 0, q); Wa.setS(lit, 4, q); quote = 1
        } else if (lower.contentEquals(byteArrayOf(0x27))) {
            val q = if (xnl(0x3874) != 0) byteArrayOf(0x27)
            else utf8(if (prev < 1 || isSpace(prev)) 0x2018 else 0x2019)
            Wa.setS(lit, 0, q); Wa.setS(lit, 4, q); quote = 1
        }
        // MooBoard::setLiteralingKeys(ex.codes): key highlighting (UI)
        val tw = e.lang.trieWord(lower)
        var weak = 0
        if (tw != null && e.lang.isDisabledFCAL(tw)) weak = (if (pad.i32(0x2affc) < 2) 1 else 0) and (glued xor 1)
        if (tw != null && e.lang.isUnfavored(tw) && pad.i32(0x2affc) < 1) weak = weak or (glued xor 1)
        val bad = e.lang.badExplicitCount(lower)
        if (bad != null && bad > 1) weak = weak or (glued xor 1)
        // the literal's score
        val sb = fsub(ex.b, C_LEN.get(i2f(nchars)))
        val speed = C_SPEED.get(pad.f32(0x2a104))
        var f = 0.5f
        if (xnl(0x29a70) == 0 && xnl(0x4d2c) == 0) {
            f = if (xnl(0x3b4c) != 0) speed else if (pad.i32(0x2affc) > 0) 1.5f else Bits.bitsToFloat(0x3eb33333)
        }
        var v = fmul(f, fmul(C_GAP.get(sb), 10f))
        if (nchars == 1) v = fmul(v, 0.5f)
        if (xnl(0x29a70) == 0 && xnl(0x4d2c) == 0) {
            val lim = if (xnl(0x1f458) == 0 && xnl(0x1f424) == 0) pad.i32(0x12a4) else 0
            if (nchars >= lim) { Wa.flagLiteralReason(lit, 0); v = F999 }
        }
        if (!lastResult) { Wa.flagLiteralReason(lit, 1); v = F999 }
        if (pad.i32(0x2affc) > 0 && xnl(0x54b0) == 0) { Wa.flagLiteralReason(lit, 8); v = F999 }
        var a = if (weak == 0) v else Bits.bitsToFloat(0x3c23d70a)
        if (glued == 1) { Wa.flagLiteralReason(lit, 2); a = F999 }
        if (quote == 1) { Wa.flagLiteralReason(lit, 3); a = F999 }
        if (xnl(0x29540) == 1) { Wa.flagLiteralReason(lit, 4); a = F999 }
        if (xnl(0x29610) == 1 && xnl(0x29a70) == 0 && xnl(0x4d2c) == 0 && xnl(0x4cc4) == 0) { Wa.flagLiteralReason(lit, 5); a = F999 }
        if (ex.forced) { Wa.flagLiteralReason(lit, 6); a = F999 }
        for ((i, mt) in e.touches.sitting.withIndex()) {
            if (i != 0 && (mt.body.u8(0xcc) != 0 || mt.body.u8(0xcd) != 0)) { Wa.flagLiteralReason(lit, 7); a = F999; break }
        }
        // WordAlternative::print: debug
        var m = 0f
        for (w in alts) if (fcmplt(m, w.f32(0x2c))) m = w.f32(0x2c)
        var s = fadd(m, Bits.bitsToFloat(0xbc23d70a.toInt()))
        if (fcmplt(s, a)) s = a
        lit.setF32(0x24, 1f)
        lit.setF32(0x20, s)
        lit.setF32(0x2c, s)
        lit.setI32(0x30, (Wa.flags(lit) and 0xfffffcdf.toInt()) or ((if (ex.forced) 1 else 0) shl 5) or 0x300)
        nintype.engine.pathscore.WordAlternative.setTrieWord(lit, tw ?: 0, if (tw == null) 0 else 1)
        lit.setI32(0x30, (quote shl 0x14) or (glued shl 0x15) or (Wa.flags(lit) and 0xffcfffff.toInt()))
        for (w in alts) {
            if (Wa.word(w).contentEquals(Wa.word(lit))) {
                val ws = w.f32(0x20)
                if (!(fcmpge(ws, a) || fcmpun(ws, a))) { Wa.assign(w, lit); return }
            }
        }
        alts.add(lit)
    }

    private fun isSpace(c: Int) = c == 0x20 || c in 9..13

    private fun utf8(cp: Int): ByteArray = java.io.ByteArrayOutputStream().also { nintype.engine.trie.Utf8.append(it, cp) }.toByteArray()

    /** Moopad::getLatestCharBeforeCursor() @0069b3ec: the last code point of the text before the
     * cursor (0 when it is empty). */
    fun latestCharBeforeCursor(): Int = nintype.engine.trie.Utf8.split(st.textBeforeCursor).lastOrNull() ?: 0

    // --- the splice steps (@006c5d7c..006c62f0) ------------------------------------------------

    private fun splices(chosen: MemImage, alts: MutableList<MemImage>) {
        if (cfg.filterInvalids) filterInvalids(chosen, alts)
        step("after invalid filter", alts, chosen)
        if (cfg.autoComplete) spliceAutoComplete(alts)
        step("after autocomp", alts, chosen)
        if (cfg.wildGuesses) spliceWildGuesses(chosen, alts)
        step("after WG", alts, chosen)
        if (cfg.customScoring) { spliceCustomScoring(chosen); for (w in alts) spliceCustomScoring(w) }
        step("after custom scoring", alts, chosen)
        Wa.sort(alts)
        if (cfg.filterRepeats) filterRepeatingResults(alts)
        step("after filtering", alts, chosen)
        if (cfg.neoMarkov) e.lang.neoMarkov(alts)
        if (alts.isNotEmpty()) Wa.assign(chosen, alts[0])
        step("after neomarkov", alts, chosen)
        if (xnl(0x4e98) == 0 && cfg.capsToExplicit) { spliceCapsToExplicit(chosen); for (w in alts) spliceCapsToExplicit(w) }
        step("after capstoexplicit", alts, chosen)
        if (xnl(0x4e98) == 0 && cfg.weirdCaps) { spliceWeirdCaps(chosen); for (w in alts) spliceWeirdCaps(w) }
        step("after weirdcaps", alts, chosen)
        if (cfg.transform) { maybeTransform(chosen); for (w in alts) maybeTransform(w) }
        if (xnl(0x3840) == 0 && cfg.smartQuotes) { spliceSmartQuotes(chosen); for (w in alts) spliceSmartQuotes(w) }
        step("after transform & smartq", alts, chosen)
    }

    /** Moopad::filterInvalids(WordAlternative&, vector&) @006e2970: with any alternative under
     * 0.01 (final score), keep only those at or above it; an emptied list gets a placeholder
     * (flag 0x80000, 0x24 = 1, 0x20 = 0x28 = 9999); then [chosen] = the list's first. */
    private fun filterInvalids(chosen: MemImage, alts: MutableList<MemImage>) {
        val lim = Bits.bitsToFloat(0x3c23d70a)
        if (alts.none { fcmplt(it.f32(0x2c), lim) }) return
        val kept = alts.filter { !fcmplt(it.f32(0x2c), lim) }.mapTo(ArrayList()) { Wa.copy(it) }
        alts.clear(); alts.addAll(kept)
        if (alts.isEmpty()) {
            val p = Wa.new("placeholder")
            Wa.orFlags(p, 0x80000)
            p.setF32(0x24, 1f); p.setF32(0x2c, 9999f); p.setF32(0x20, 9999f)
            alts.add(p)
        }
        Wa.assign(chosen, alts[0])
    }

    /** Moopad::spliceAutoComplete(WordAlternative&, vector&) @006e3908: with Xnl 0x29574 the list
     * is replaced by the ACCollectage's slot-0 alternatives (or a placeholder without slot 0). */
    private fun spliceAutoComplete(alts: MutableList<MemImage>) {
        if (xnl(0x29574) != 1) return
        e.pend("Emkey::Moopad::spliceAutoComplete (ACCollectage::hasSlot / gatherSlotAlternatives / applyCaps)")
    }

    /** Moopad::spliceWildGuesses(WordAlternative&, vector&) @006e3e68: Moopad+0x2b000 = "";
     * a wild-guess choice (flag 0x40, unless Xnl 0x3c84 or its trie word's scoremult > 0.7) adds
     * WildGuesser guesses. */
    private fun spliceWildGuesses(chosen: MemImage, alts: MutableList<MemImage>) {
        st.s2b000 = ByteArray(0)
        if (xnl(0x3c84) != 0 || Wa.flags(chosen) and 0x40 == 0) return
        e.pend("Emkey::Moopad::spliceWildGuesses (TrieWord::getScoremult, wg_getGuesses)")
    }

    /** Moopad::spliceCustomScoring(WordAlternative&) @006e45ec: (the JumpmanGame word bonus is a
     * game; never active) a super-exiled trie word's final score becomes 0.0001, unless it is the
     * literal with Moopad+0x2affc > 2. */
    private fun spliceCustomScoring(w: MemImage) {
        if (Wa.flags(w) and 0x100 != 0 && pad.i32(0x2affc) > 2) return
        val tw = Wa.trieWord(w) ?: return
        if (e.lang.isSuperExiled(tw)) w.setF32(0x2c, Bits.bitsToFloat(0x38d1b717))
    }

    /** filterRepeatingResults(vector&, Moopad*) @006e7170: drop later duplicates of a word (exact
     * bytes); then, when a literal (flag 0x100) was seen, flag 0x200 on every non-literal whose
     * trie word's raw word is the literal's lower-cased word. */
    private fun filterRepeatingResults(alts: MutableList<MemImage>) {
        val seen = java.util.TreeSet<ByteArray>(Comparator(::unsignedCompare))
        var lit = ByteArray(0)
        var i = 0
        while (i < alts.size) {
            val w = alts[i]
            if (Wa.flags(w) and 0x100 != 0) lit = nintype.engine.trie.Caps.toWLower(Wa.word(w))
            if (seen.contains(Wa.word(w))) { alts.removeAt(i); continue }
            seen.add(Wa.word(w).copyOf())
            i++
        }
        if (lit.isEmpty()) return
        for (w in alts) {
            if (Wa.flags(w) and 0x100 != 0) continue
            val tw = Wa.trieWord(w) ?: continue
            if (e.lang.rawWord(tw).contentEquals(lit)) Wa.orFlags(w, 0x200)
        }
    }

    /** std::less<std::string>: the first differing byte as unsigned, then the length (written out:
     * Arrays.compareUnsigned needs Android API 33). */
    private fun unsignedCompare(x: ByteArray, y: ByteArray): Int {
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    /** Moopad::spliceCapsToExplicit(WordAlternative&) @006e313c: an unforced literal (flags &
     * 0x200120 == 0x100, Moopad+0x2affc == 0) of under 5 chars takes its trie word's caps. */
    private fun spliceCapsToExplicit(w: MemImage) {
        val fl = Wa.flags(w)
        if (fl and 0x200100 != 0x100 || pad.i32(0x2affc) != 0 || fl and 0x20 != 0 || w.i32(0x1c) >= 5) return
        val tw = e.lang.trieWord(Wa.word(w)) ?: return
        val b4 = fl and 0x4000 != 0; val b8 = fl and 0x8000 != 0
        Wa.setS(w, 4, e.lang.spliceCaps(tw, Wa.word(w), false, b4, b8))
        Wa.setS(w, 0, e.lang.spliceCaps(tw, Wa.s(w, 0), false, b4, b8))
    }

    /** Moopad::spliceWeirdCaps(WordAlternative&) @006e492c: (not for a literal while
     * Moopad+0x2affc > 0) a trie word (without flag 0x200000) gets WeirdCaseManager's case of its
     * raw word; a pending word (flags & 0x200004 == 4) its learned weird caps; a change sets
     * flag 0x800000. */
    private fun spliceWeirdCaps(w: MemImage) {
        if (Wa.flags(w) and 0x100 != 0 && pad.i32(0x2affc) > 0) return
        val tw = Wa.trieWord(w)
        if (tw != null && Wa.flags(w) and 0x200000 == 0) {
            val raw = e.lang.rawWord(tw)
            val out = e.lang.weirdCase(raw, Wa.word(w))
            if (!out.contentEquals(raw)) { Wa.setS(w, 4, out); Wa.orFlags(w, 0x800000) }
            return
        }
        if (Wa.flags(w) and 0x200004 != 4) return
        val out = e.lang.pendingWeirdCapsed(Wa.word(w))
        if (!out.contentEquals(Wa.word(w))) { Wa.setS(w, 4, out); Wa.orFlags(w, 0x800000) }
    }

    /** Moopad::maybeTransform(WordAlternative&) @006daffc: with every text transform setting
     * off, the display text (+0) = the word (+4). */
    private fun maybeTransform(w: MemImage) {
        if (xnl(0x20900) == 1) { e.pend("Emkey::performSMSSavingTransform (Xnl 0x20900)"); return }
        if (xnl(0x32c4) == 1 && Wa.word(w).size == 7) e.pend("Emkey::Moopad::maybeTransform (Xnl 0x32c4 seven-letter check)")
        if (xnl(0x20934) == 1) e.pend("Emkey::TransformerEnv::transform (Xnl 0x20934)")
        else Wa.setS(w, 0, Wa.word(w))
        if (xnl(0x2097c) == 1) e.pend("Emkey::TransformerEnv::zalgoify (Xnl 0x2097c)")
        else if (xnl(0x209b0) == 1) e.pend("Emkey::TransformerEnv::applyModifier (Xnl 0x209b0)")
    }

    /** Moopad::spliceSmartQuotes(WordAlternative&) @006e4cc8: (unless Xnl 0x20900 or flag
     * 0x100000) a display text containing an apostrophe goes through reallySpliceSmartQuotes. */
    private fun spliceSmartQuotes(w: MemImage) {
        if (xnl(0x20900) != 0 || Wa.flags(w) and 0x100000 != 0) return
        if (Wa.s(w, 0).contains(0x27)) reallySpliceSmartQuotes(w)
    }

    /** Moopad::reallySpliceSmartQuotes(WordAlternative&) @006e4d6c: every apostrophe byte of the
     * display text becomes U+2019, the other bytes stay. */
    private fun reallySpliceSmartQuotes(w: MemImage) {
        val out = java.io.ByteArrayOutputStream()
        for (b in Wa.s(w, 0)) if (b.toInt() == 0x27) nintype.engine.trie.Utf8.append(out, 0x2019) else out.write(b.toInt())
        Wa.setS(w, 0, out.toByteArray())
    }

    /** Moopad::syncBackspaceRetypePenalties(vector&) @006dc0b8: penalties (a static curve over
     * the time since the destruction) for alternatives that retype a recently destroyed word, from
     * the destroyed-word histories (Moopad+0x32cfc, the deque at +0x32d08) that match the current
     * context. With none (no backspace since boot; the backspace paths are not ported) the penalty
     * map stays empty and nothing changes. */
    private fun syncBackspaceRetypePenalties(alts: MutableList<MemImage>) {
        Backspace.retypePenalties(e, alts)
    }

    /** Moopad::spliceThiccAutocompleteSlots() @006e325c: with Xnl 0x1f7d0 (on by default), each
     * ACCollectage slot that ACCollectage::hasSlot reports fills the autocomplete WordAlternative
     * slots (+0x2b2a4 / +0x2b2f4) from gatherSlotAlternatives. The port's ACCollectage is empty
     * until search's gatherAutocomplete builds it, so this is a no-op until then. */
    private fun spliceThiccAutocompleteSlots() {
        if (xnl(0x1f7d0) != 1) return
        if (st.ac.slots.any { it.open != 0 && it.words.isNotEmpty() })
            e.pend("Emkey::Moopad::spliceThiccAutocompleteSlots (ACCollectage::gatherSlotAlternatives / applyCaps)")
    }

    /** Moopad::loadEdgeternsFromAC() @006f735c: the edge autocomplete buttons. Decoder-visible part:
     * button i's shown Xnl (Moopad+0x300d4 + 0x248 i + 0x214) = ACCollectage::hasSlot(i) with Xnl
     * 0xff0 on and 0x29574 off; the button texts / fonts / icons are UI. */
    private fun loadEdgeternsFromAC() {
        pad.setU8(0x2ff61, 0)
        if (xnl(0xff0) == 1 && xnl(0x29574) == 0) {
            for (i in 0 until 4) {
                val has = i < st.ac.slots.size && st.ac.slots[i].open != 0 && st.ac.slots[i].words.isNotEmpty()
                if (has) e.pend("Emkey::ACCollectage::hasSlot / edge autocomplete buttons")
                setXnl(0x300d4 + 0x248 * i + 0x214, has)
            }
        } else {
            setXnl(0x302e8, false); setXnl(0x30530, false); setXnl(0x30778, false); setXnl(0x309c0, false)
        }
    }

    /** Moopad::setCurrentWA(WordAlternative const&) @006b7ef8: Moopad+0x2b004 = [w] (the image
     * mirror too); a non-empty word is also kept as the last non-empty WA (+0x2b054); +0x2b0a8 =
     * RoenGL::globaltimestamp unless both the word and the kept one are empty. */
    fun setCurrentWA(w: MemImage) {
        Wa.assign(st.current, w)
        pad.writeBytes(0x2b004, w.readBytes(0, Wa.SIZE))
        if (Wa.word(w).isNotEmpty()) {
            Wa.assign(st.lastNonEmpty, w)
            pad.writeBytes(0x2b054, w.readBytes(0, Wa.SIZE))
            pad.setF64(0x2b0a8, e.clock.gts)
        } else if (Wa.word(st.lastNonEmpty).isNotEmpty()) pad.setF64(0x2b0a8, e.clock.gts)
    }

    /** Moopad::inductToChainKeeper(false, "", 0, 0) @006b3c9c: with Xnl 0x317e0 (on by default)
     * and 0x1f424 off it feeds the text before / after the cursor to ChainEnv (Moopad+0x31128:
     * senseExpansion and the chain keeper, the chain predictor's history). ChainEnv is the trie
     * area's port in progress. */
    private fun inductToChainKeeper() {
        if (xnl(0x317e0) != 1 || xnl(0x1f424) != 0) return
        e.pend("Emkey::Moopad::inductToChainKeeper (ChainEnv::senseExpansion / chain keeper)")
    }

    /** The end of syncPredictedText (@006d6880..006d6e30): Xnls from the current WA, the
     * RecentWATransformed and history entries, then propagateLiquidToClient (the markliquid text
     * op with the current WA's display text). The rest of the tail is UI. */
    private fun tail() {
        if (st.alts.isEmpty()) {
            for (o in intArrayOf(0x2b0f8, 0x2b0c4, 0x2b194, 0x2b1fc, 0x2b12c, 0x2b160)) setXnl(o, false)
        } else {
            if (pad.u8(0x33074) == 0) {
                setCurrentWA(st.alts[0])
                val fl = pad.i32(0x2b034)
                setXnl(0x2b194, fl and 0x100 != 0)
                setXnl(0x2b1fc, fl and 0x200 != 0)
                if (fl and 0x100 != 0) pad.setF64(0x2b0b0, e.clock.gts)
                setXnl(0x2b1c8, pad.i32(0x97cc) <= pad.i32(0x2b020))
            } else { setXnl(0x2b194, false); setXnl(0x2b1fc, false) }
            val ac = Wa.flags(st.current) and 0x20000 != 0
            setXnl(0x2b0c4, ac)
            setXnl(0x2b0f8, ac && pad.i32(0x2b014) == 0)
            val fl = pad.i32(0x2b034)
            setXnl(0x2b12c, fl and 0x80000 != 0)
            setXnl(0x2b160, fl and 0x40000 != 0)
            // getAutocompleteEnderChars -> setLiteralingKeys: UI
        }
        if (st.alts.size < 2) { setXnl(0x2b230, false); setXnl(0x2b264, false) }
        else {
            val f = st.alts[1].i32(0x30)
            setXnl(0x2b230, f and 0x100 != 0); setXnl(0x2b264, f and 0x200 != 0)
        }
        // RecentWATransformed deque (+0x2af6c): at most 5 (display text, time)
        st.recentTransformed.addLast(Wa.s(st.current, 0).copyOf() to e.clock.gts)
        if (st.recentTransformed.size > 5) st.recentTransformed.removeFirst()
        // the multimap<double, std::string> at +0x9870 (size +0x9884): at most 40, oldest erased
        val t = e.clock.gts
        var at = st.history.size
        while (at > 0 && dcmplt(t, st.history[at - 1].first)) at--
        st.history.add(at, t to Wa.s(st.current, 0).copyOf())
        if (st.history.size > 0x28) st.history.removeAt(0)
        pad.setI32(0x9884, st.history.size)
        propagateLiquidToClient()
    }

    /** Moopad::propagateLiquidToClient() @006b8270: the client's markLiquid(current display text,
     * the explicit text, the alternatives' display texts). The temp-autospace space push
     * (Xnl 0x29794 with 0x7fa4 / 0x297c8) and inductToChainKeeper are pending. */
    private fun propagateLiquidToClient() {
        if (xnl(0x29794) == 1 && (xnl(0x7fa4) != 0 || xnl(0x297c8) == 1)) e.pend("Emkey::Moopad::pushSpaceIfTempAutospaceIsOn")
        e.liquid(Wa.s(st.current, 0))
        inductToChainKeeper()
    }
}
