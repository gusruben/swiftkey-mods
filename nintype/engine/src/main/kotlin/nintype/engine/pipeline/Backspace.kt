package nintype.engine.pipeline

import nintype.engine.SoftFloat
import nintype.engine.math.FastCurveMapper
import nintype.engine.merge.MooMergeBuffer

/** What [NintypeEngine.backspace] did. */
class BackspaceResult(
    /** false: no word was open (no sitting touches) or a pointer is down; nothing changed, and the
     * host's own backspace applies (Nintype's non-buffer path deletes text in the field). */
    val handled: Boolean,
    /** The word's liquid text after the backspace ("" once it is empty). */
    val text: String,
    /** The ranked candidates of the re-decode (the first listResults of the last merge-buffer
     * update the backspace made; empty when it made none, e.g. a word of taps or an empty word). */
    val candidates: List<Candidate>,
    /** The backspace removed the word's last touches: no word is open now, so the next backspace
     * belongs to the host. */
    val empty: Boolean,
)

/**
 * `Emkey::Moopad::performBackspace()` @005bff20 for the open (uncommitted) word, the path the
 * backspace key's tap takes (BackspaceTouchHandler) while touches sit in the merge buffers.
 *
 * Nintype backspaces by touch group, not by letter: the last chain (the touches between two
 * moments with every pointer up, Mootouch+0xd8 = Moopad+0x2992c) leaves the sitting list, its
 * merge buffer goes, the other buffers backtrack (MooMergeBuffer::onTouchBacktrack) and
 * updateMergeBuffers re-decodes what is left. A tapped letter is one chain, a swipe is one
 * chain, a two-thumb word is one chain per all-up moment. Before that the current prediction is
 * saved to the backspace history (saveCurrentPredictionToBackspaceHistory), which the next
 * decodes read as retype penalties ([retypePenalties], from syncPredictedText), and the
 * backspace counter Moopad+0x2affc goes up (literal and caps scoring read it).
 *
 * UI on the path (Xignals, vibration, the stats Mmap at +0x770, PeckModule's peck-restore
 * records, the large previewer, the number preview, the emoji expander, the correction box's
 * async refresh, syncCurlineEmptiness' Xnl +0x32c20) is left out. Verified by
 * PipelineE2ETest's backspace stage against re/out/e2e_backspace.
 */
object Backspace {
    /** The retype penalty over the seconds since the destruction (static FastCurveMapper in
     * syncBackspaceRetypePenalties; points read from the oracle). */
    private val RETYPE: FastCurveMapper by lazy {
        FastCurveMapper().push(0.2f, 0.95f).push(0.4f, 0.6f).push(0.7f, 0.5f).push(1.2f, 0.7f)
            .push(1.8f, 0.85f).push(2.5f, 1.0f).finalize()
    }

    /** 0.3 s as the binary's double (0x3fd3333340000000). */
    private val SAVE_GAP = nintype.engine.Bits.bitsToDouble(0x3fd3333340000000L)

    fun perform(e: NintypeEngine): BackspaceResult {
        val pad = e.pad
        if (pad.i32(0x29440) != 0 || e.touches.sitting.isEmpty()) {
            return BackspaceResult(false, e.liquidText ?: "", emptyList(), e.touches.sitting.isEmpty() && e.merges.map.isEmpty())
        }
        val now = e.clock.gts
        // the deque<string> at Moopad+0x32e14 is emptied after 8 s without a backspace; only the
        // non-buffer path fills it
        pad.setF64(0x32e40, now)
        e.setXnl(0x29c5c, false)
        e.setXnl(0x29794, false)
        pad.setU8(0x22f41, 1)
        pad.setU8(0x9e7c, 0)
        // clearPeckMootouches: the peck list at +0x29424 (peck restore UI)
        if (Wa.word(e.pred.current).isNotEmpty()) saveCurrentPredictionToBackspaceHistory(e)
        pad.setI32(0x2affc, pad.i32(0x2affc) + 1)
        pad.setI32(0x294c0, -1)
        val c = pad.i32(0x2992c) - 1
        pad.setI32(0x2992c, c)
        val sitting = e.touches.sitting
        while (sitting.isNotEmpty() && sitting.last().body.i32(0xd8) == c) {
            sitting.removeAt(sitting.size - 1)
            e.setXnl(0x2975c, sitting.size > 1)
            e.setXnl(0x29728, sitting.isNotEmpty())
        }
        // PeckModule::onBackspace(c): drops chain c's SecondaryPeckRecords (peck restore UI)
        e.resyncSittingTouchesStartBasedSortation()
        e.merges.map.remove(c)
        if (e.merges.map.isEmpty()) {
            syncCapsOnBufferEmptyingBackspace(e)
            e.pred.s32e5c = ByteArray(0)
            // syncPathSearchCharaMarkovBuffer on the empty word
            pad.setU8(0x22f43, 0)
            pad.setU8(0x22f42, 0)
        }
        for (s in e.merges.map.values) MooMergeBuffer.onTouchBacktrack(s.buf, s.containers(), c)
        if (sitting.isEmpty()) {
            pad.setI32(0x29928, 0)
            e.merges.map.clear()
        } else pad.setI32(0x29928, sitting.last().body.i32(0xd4) + 1)
        val updates = e.mergeUpdateCount
        e.liquidText = null
        e.updateMergeBuffers()
        val cands = if (e.mergeUpdateCount != updates) e.lastUpdateFirstList ?: emptyList() else emptyList()
        e.candidates = cands
        // clearCharaMarkov (parked: no decode effect); calldownSyncCorrectionBoxSoon (UI)
        e.setXnl(0x297c8, false)
        pad.setU8(0x29918, 0)
        val empty = sitting.isEmpty() && e.merges.map.isEmpty()
        return BackspaceResult(true, e.liquidText ?: "", cands, empty)
    }

    /** Moopad::saveCurrentPredictionToBackspaceHistory() @006d2654: unless the last search
     * (PathSearch time, Moopad+0x29f48) is under 0.3 s old, a MoopadReplaceableHistory of the
     * current WordAlternative and alternatives (time = now, ctor) goes on the destroyed-word
     * deque at +0x32d00, which keeps the last 6. */
    private fun saveCurrentPredictionToBackspaceHistory(e: NintypeEngine) {
        val now = e.clock.gts
        if (SoftFloat.dcmplt(SoftFloat.dsub(now, e.pad.f64(0x29f48)), SAVE_GAP)) return
        val prev = previousWordFromProxy(e)
        val h = ReplaceableHistory()
        Wa.assign(h.wa, e.pred.current)
        h.was += Wa.deepCopy(e.pred.alts)
        h.time = now
        if (prev.isNotEmpty()) h.prevWord = prev
        val d = e.langState.destroyed
        d += h
        if (d.size > 6) d.removeAt(0)
    }

    /** Moopad::getCurrentPreviousWordFromProxy(): the word before the cursor in the client's
     * text. Only the empty field is verified (it gives ""). */
    private fun previousWordFromProxy(e: NintypeEngine): ByteArray {
        val t = e.pred.textBeforeCursor
        if (t.isEmpty()) return t
        e.pend("Emkey::Moopad::getCurrentPreviousWordFromProxy (non-empty field)")
        val s = String(t, Charsets.UTF_8).trimEnd()
        return s.substring(s.lastIndexOfAny(charArrayOf(' ', '\n', '\t')) + 1).toByteArray(Charsets.UTF_8)
    }

    /** Moopad::syncCapsOnBufferEmptyingBackspace() @006d1630: the emptied word was capitalised
     * (current WordAlternative flag 8 at Moopad+0x2b034, word not empty): turnOnCaps(""). */
    private fun syncCapsOnBufferEmptyingBackspace(e: NintypeEngine) {
        if (Wa.word(e.pred.current).isEmpty() || e.pad.u8(0x2b034) and 8 == 0) return
        // Moopad::turnOnCaps @006a2acc: on the alphabet board (its board-name check), unless
        // Xnls +0x1f424 / +0x1f458 hold, caps on and Xnl +0x29c5c off
        if (e.pad.u8(0x1f424 + 0xc) == 0 && e.pad.u8(0x1f458 + 0xc) == 0) {
            e.setXnl(0x29bf4, true)
            e.setXnl(0x29c5c, false)
        }
    }

    /** Moopad::syncBackspaceRetypePenalties(vector&) @006dc0b8: each destroyed-word history that
     * matchesCurrentContextAsRecentlyDestructed maps its word to the retype curve at the seconds
     * since its destruction (later entries win); every alternative with such a word has its final
     * score (+0x2c) multiplied by it. The single history at Moopad+0x32cfc is filled by the
     * non-buffer backspace path only (not ported), so it is always empty here. */
    fun retypePenalties(e: NintypeEngine, alts: List<nintype.engine.vectors.MemImage>) {
        val d = e.langState.destroyed
        if (d.isEmpty()) return
        val ctxEmpty = lastChars(e.pred.textBeforeCursor, 100).isEmpty()
        val now = e.clock.gts
        val pen = HashMap<String, Float>()
        for (h in d) {
            if (!matchesCurrentContextAsRecentlyDestructed(e, h, ctxEmpty)) continue
            val x = SoftFloat.d2f(SoftFloat.dsub(now, h.time))
            pen[String(Wa.word(h.wa), Charsets.ISO_8859_1)] = RETYPE.get(x)
        }
        if (pen.isEmpty()) return
        for (w in alts) {
            val p = pen[String(Wa.word(w), Charsets.ISO_8859_1)] ?: continue
            w.setF32(0x2c, SoftFloat.fmul(p, w.f32(0x2c)))
        }
    }

    /** MoopadReplaceableHistory::matchesCurrentContextAsRecentlyDestructed(Moopad*, true, ctxEmpty)
     * @00d190a0 (read from the disassembly; the decompile drops the returns): with an empty
     * context and no recorded previous word, true; otherwise the history needs a previous word
     * equal to the display text (+0x60) of the most recent committed history. */
    private fun matchesCurrentContextAsRecentlyDestructed(e: NintypeEngine, h: ReplaceableHistory, ctxEmpty: Boolean): Boolean {
        val prev = h.prevWord
        if (ctxEmpty && prev == null) return true
        if (prev == null) return false
        val recent = e.langState.replaceable.lastOrNull() ?: return false
        return Wa.s(recent.wa, 0).contentEquals(prev)
    }

    /** The last [n] UTF-8 characters (the client proxy's getTextBeforeCursor(n)). */
    private fun lastChars(t: ByteArray, n: Int): ByteArray {
        val cs = nintype.engine.trie.Utf8.split(t)
        if (cs.size <= n) return t
        val out = java.io.ByteArrayOutputStream()
        for (c in cs.copyOfRange(cs.size - n, cs.size)) nintype.engine.trie.Utf8.append(out, c)
        return out.toByteArray()
    }
}
