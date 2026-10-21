package app.revanced.extension.swiftkey.twothumb.nintype

import nintype.engine.geometry.KeyboardModel
import nintype.engine.pipeline.NintypeEngine
import nintype.engine.pipeline.PipelineObserver
import nintype.engine.pipeline.Wa
import nintype.engine.search.PipelineMergeHost

/**
 * Nintype's real input model, live: one long-lived engine fed every letter-key touch as it
 * happens. A word builds up across lifts; the engine reports it through [Listener.liquid] (the
 * current word, after each merge-buffer update) and [Listener.solidify] (text it commits on its
 * own, e.g. its timed autospace when a new press starts a new word). [commit] closes the word the
 * way Nintype's space tap does. Not thread safe: call everything on one thread.
 */
class LiveSession(private val decoder: NintypeDecoder, val model: KeyboardModel, private val listener: Listener) {

    interface Listener {
        /** The current (liquid) word, Nintype's ranked candidates (best first) and the literal (the
         * exact keys typed, Nintype's way of typing words it does not know), null when there is none. */
        fun liquid(text: String, candidates: Array<String>, literal: String?)

        /** Text Nintype committed (usually "word "). [explicit]: from [commit], not the engine itself. */
        fun solidify(text: String, explicit: Boolean)
    }

    private var explicitCommit = false
    private var suppressSolidify = false

    private val observer = object : PipelineObserver {
        override fun liquid(text: ByteArray) {
            val e = engine ?: return
            val words = LinkedHashSet<String>()
            for (c in e.candidates) if (c.word.isNotEmpty()) words += c.word
            listener.liquid(String(text, Charsets.UTF_8), words.toTypedArray(), literal)
        }

        override fun solidify(text: ByteArray) {
            if (suppressSolidify) return
            listener.solidify(String(text, Charsets.UTF_8), explicitCommit)
        }
    }

    private var engine: NintypeEngine? = null
    /** The engine of the open word (null before the first touch or after [dropWord]); for checks. */
    val currentEngine: NintypeEngine? get() = engine
    private var frame = 0L
    private var baseUptime = -1L
    private var baseMs = 0L

    /**
     * The open word's literal alternative: the exact keys typed, as syncPredictedText's literal
     * insert puts it in the final alternatives (flag 0x100), or the explicit text when the list
     * dropped it as a duplicate. The search candidates never hold it. Null with no word open.
     */
    val literal: String?
        get() {
            val e = engine ?: return null
            if (!hasWord) return null
            for (w in e.pred.alts) if (Wa.flags(w) and 0x100 != 0) return display(w).takeIf { it.isNotEmpty() }
            return String(e.pred.s2aff8, Charsets.UTF_8).takeIf { it.isNotEmpty() }
        }

    private fun display(w: nintype.engine.vectors.MemImage): String {
        val s0 = Wa.s(w, 0)
        return String(if (s0.isNotEmpty()) s0 else Wa.word(w), Charsets.UTF_8)
    }

    /** Pointers down in the engine. */
    val liveTouches: Int get() = engine?.touches?.live?.size ?: 0

    /** Whether the engine holds an uncommitted word (sitting touches or merge buffers). */
    val hasWord: Boolean get() = engine?.let { it.touches.sitting.isNotEmpty() || it.merges.map.isNotEmpty() || it.touches.live.isNotEmpty() } == true

    private fun frameUs(k: Long) = FIRST_FRAME_US + (k * 50_000 + 2) / 3

    private fun ensureEngine(): NintypeEngine = engine ?: NintypeEngine(model, decoder.boot, strict = false, observer = observer,
        mergeHostFor = PipelineMergeHost, sharedLangState = decoder.ensureReady()).also {
        engine = it
        frame = 0
        baseUptime = -1
    }

    private fun engineMs(uptime: Long): Long {
        if (baseUptime < 0) {
            baseUptime = uptime
            baseMs = frameUs(frame + 3) / 1000
        }
        return baseMs + (uptime - baseUptime)
    }

    /** Runs the engine's 60 Hz frames up to [uptime]. Long idle gaps are skipped (one big step). */
    fun advance(uptime: Long) {
        val e = engine ?: return
        if (baseUptime < 0) return
        val us = engineMs(uptime) * 1000
        if (e.touches.live.isEmpty() && us - frameUs(frame) > 500_000) {
            // nothing to record while idle: jump to 3 frames before now
            frame = maxOf(frame, (us - FIRST_FRAME_US) * 3 / 50_000 - 3)
        }
        while (frameUs(frame) < us) e.step(frameUs(frame++))
    }

    /** The editor text before the cursor, for the literal scoring (set at each word start). */
    fun setContext(textBeforeCursor: String?) {
        val e = ensureEngine()
        if (textBeforeCursor != null && !hasWord) e.pred.textBeforeCursor = textBeforeCursor.toByteArray(Charsets.UTF_8)
    }

    /** One touch sample in host px: action 0 down, 1 move, 2 up; [uptime] in ms. */
    fun touch(pid: Int, action: Int, x: Float, y: Float, pressure: Float, size: Float, uptime: Long) {
        val e = ensureEngine()
        val ms = engineMs(uptime)
        advance(uptime)
        val b = model.toBoard(x, y)
        e.onTouchEvent(pid, action, 2f * (b.x + model.origin.x), 2f * (b.y + model.origin.y), pressure, size, ms)
    }

    /** Closes the current word with Nintype's commit (learning included). [emitText]: report its
     * solidify text to the listener (explicit = true), or commit silently (the host already did). */
    fun commit(emitText: Boolean) {
        val e = engine ?: return
        if (!hasWord) return
        explicitCommit = true
        suppressSolidify = !emitText
        try {
            e.forceMergeAllBuffers()
        } finally {
            explicitCommit = false
            suppressSolidify = false
            e.pending.clear()
        }
    }

    /**
     * Nintype's backspace on the open word ([NintypeEngine.backspace]): the word's last touch
     * group (a tap, a swipe, or one two-thumb burst) goes and the rest is re-decoded; the new word
     * reaches [Listener.liquid] as usual (unless it is empty). [uptime] in ms, as for [touch].
     * Returns null when no word is open (the host's own backspace applies); a result with
     * handled = false when a pointer is still down (Nintype ignores the key then).
     */
    fun backspace(uptime: Long): nintype.engine.pipeline.BackspaceResult? {
        val e = engine ?: return null
        if (!hasWord) return null
        advance(uptime)
        try {
            return e.backspace()
        } finally {
            e.pending.clear()
        }
    }

    /**
     * Takes the live touch [pid] back out of the word (a held letter the host turns into its own
     * long-press): the touch is lifted at ([x], [y]) and, when that lift left it as its own touch
     * group (no other pointer down during its life, so the lift closed a chain holding only it),
     * removed with [backspace]. Returns null when no word is open afterwards; handled = false when
     * the touch could not be isolated (it then stays in the word as an ordinary lift).
     */
    fun removeTouch(pid: Int, x: Float, y: Float, pressure: Float, size: Float, uptime: Long): nintype.engine.pipeline.BackspaceResult? {
        val e = engine ?: return null
        if (!e.touches.live.containsKey(pid)) return null
        val sitting = e.touches.sitting
        val before = sitting.size
        touch(pid, 2, x, y, pressure, size, uptime)
        if (e.touches.live.isNotEmpty()) return nintype.engine.pipeline.BackspaceResult(false, e.liquidText ?: "", emptyList(), false)
        if (sitting.size == before) {
            // the lift added nothing to the word: it stands as it was
            return if (hasWord) nintype.engine.pipeline.BackspaceResult(true, e.liquidText ?: "", e.candidates, false) else null
        }
        val last = sitting.maxByOrNull { it.body.i32(0xd4) }!!
        val chain = last.body.i32(0xd8)
        if (chain != e.pad.i32(0x2992c) - 1 || sitting.count { it.body.i32(0xd8) == chain } != 1) {
            return nintype.engine.pipeline.BackspaceResult(false, e.liquidText ?: "", emptyList(), false)
        }
        // not the user's backspace: Nintype's backspace counter (Moopad+0x2affc, which makes the
        // literal the word, reason 8) stays where it was
        e.pad.setI32(0x2affc, e.pad.i32(0x2affc) - 1)
        return backspace(uptime)
    }

    /**
     * Closes the word with [word] as Nintype's explicit choice: the matching alternative of the
     * final list (the literal included) goes in the forced WordAlternative (Moopad+0x29d6c), which
     * the merge-all commit takes instead of its own pick (reinforcementsOnMergingAll), so the
     * replaceable history, bad-explicit usage and word learning see the chosen word. Silent, as
     * [commit] with emitText = false. False (nothing done) when no alternative has that word.
     */
    fun commitChoice(word: String): Boolean {
        val e = engine ?: return false
        if (!hasWord) return false
        val wa = e.pred.alts.firstOrNull { display(it).equals(word, ignoreCase = true) || String(Wa.word(it), Charsets.UTF_8).equals(word, ignoreCase = true) }
            ?: return false
        e.pred.forced = Wa.copy(wa, "wa29d6c")
        commit(false)
        return true
    }

    /** Drops the current word without learning it (Nintype cannot close one silently, so the
     * engine goes; the next touch starts a fresh one on the same LangState). */
    fun dropWord() {
        engine = null
    }

    companion object {
        private const val FIRST_FRAME_US = 1_500_000_001_681_000L
    }
}
