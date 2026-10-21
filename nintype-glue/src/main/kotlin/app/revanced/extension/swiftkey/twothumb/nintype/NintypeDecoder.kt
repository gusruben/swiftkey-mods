package app.revanced.extension.swiftkey.twothumb.nintype

import nintype.engine.Bits
import nintype.engine.geometry.HostKey
import nintype.engine.geometry.KeyboardModel
import nintype.engine.pipeline.BootState
import nintype.engine.pipeline.LangState
import nintype.engine.pipeline.NintypeEngine
import nintype.engine.pipeline.PipelineObserver
import nintype.engine.search.PipelineMergeHost
import nintype.engine.vectors.Json
import java.io.File

/**
 * Raw touches of one gesture, every pointer, in host (SwiftKey view) pixels and uptime ms.
 * Preallocated and append-only so the UI thread can fill it without allocating per sample.
 * [action]: 0 down, 1 move, 2 up (NINLib.onTouchEvent's codes).
 */
class TouchLog(capacity: Int = 1024) {
    @JvmField var size = 0
    @JvmField var pid = IntArray(capacity)
    @JvmField var action = IntArray(capacity)
    @JvmField var x = FloatArray(capacity)
    @JvmField var y = FloatArray(capacity)
    @JvmField var pressure = FloatArray(capacity)
    @JvmField var touchSize = FloatArray(capacity)
    @JvmField var time = LongArray(capacity)

    fun add(pid: Int, action: Int, x: Float, y: Float, pressure: Float, size: Float, time: Long) {
        if (this.size == this.pid.size) grow()
        val i = this.size++
        this.pid[i] = pid; this.action[i] = action; this.x[i] = x; this.y[i] = y
        this.pressure[i] = pressure; this.touchSize[i] = size; this.time[i] = time
    }

    fun clear() { size = 0 }

    fun copy(): TouchLog = TouchLog(maxOf(size, 1)).also { c ->
        System.arraycopy(pid, 0, c.pid, 0, size); System.arraycopy(action, 0, c.action, 0, size)
        System.arraycopy(x, 0, c.x, 0, size); System.arraycopy(y, 0, c.y, 0, size)
        System.arraycopy(pressure, 0, c.pressure, 0, size); System.arraycopy(touchSize, 0, c.touchSize, 0, size)
        System.arraycopy(time, 0, c.time, 0, size); c.size = size
    }

    private fun grow() {
        val n = pid.size * 2
        pid = pid.copyOf(n); action = action.copyOf(n); x = x.copyOf(n); y = y.copyOf(n)
        pressure = pressure.copyOf(n); touchSize = touchSize.copyOf(n); time = time.copyOf(n)
    }
}

/** One decode: Nintype's typed (liquid) text and its ranked candidates, best first. */
class DecodeResult(
    /** The word Nintype would type (Moopad's liquid text, its own capitalisation), or null. */
    @JvmField val text: String?,
    @JvmField val words: Array<String>,
    @JvmField val scores: FloatArray,
    @JvmField val micros: Long,
    /** Unported engine steps the decode ran past (strict = false), for the log. */
    @JvmField val pending: String,
)

/**
 * SwiftKey's side of the Nintype engine: loads the boot image and the English pack once
 * ([load], slow, call off the UI thread), builds a board from SwiftKey's key rects
 * ([buildModel]) and decodes one gesture per fresh engine ([decode], one word per gesture,
 * with the language state shared across engines).
 *
 * Data directory layout (what BootState.load expects under re/out):
 *   e2e/BOOT.json, e2e/moopad_boot.bin, e2e_search_boot.json, trie/english/english.nbipack
 * Not thread safe: use one decoder thread.
 */
class NintypeDecoder private constructor(val boot: BootState, lang: LangState) {
    private var lastGen = 0L

    /**
     * Whether each decode gets its own LangState. Off: one LangState shared by every engine
     * (safe since search's 2026-10-20 coverage-stamp fix; heap stays flat). On: the decode uses
     * the ready LangState and [ensureReady] builds the next one afterwards (the workaround).
     */
    @JvmField var freshLangPerGesture = false

    /** The LangState the next decode uses; null after a decode until [ensureReady]. */
    var lang: LangState? = lang
        private set

    /** Builds the next decode's LangState if needed (a few hundred ms). Call on the decoder thread
     * right after a decode, so the next gesture finds it ready. */
    fun ensureReady(): LangState = lang ?: boot.langState(boot.moopad(), learnedDir).also { lang = it }

    /** Set by [load]; every LangState built here loads from and saves to it. */
    var learnedDir: File? = null
        private set

    val isReady: Boolean get() = lang != null

    companion object {
        /** NINLib's clocks start at (1500000000 s, 0 us); boot leaves gts at 1.68 s. Gestures
         * are replayed from here, as the oracle corpus is. */
        private const val FIRST_FRAME_US = 1_500_000_001_681_000L

        @JvmField var lastLoadMillis = 0L

        /** Loads everything a decode needs. Takes a few hundred ms to seconds; never on the UI thread. */
        @JvmStatic
        fun load(root: File): NintypeDecoder = load(root, null)

        /** [learnedDir]: where Nintype's learned state lives (loaded here, saved by [saveLearned]); null = not persisted. */
        @JvmStatic
        fun load(root: File, learnedDir: File?): NintypeDecoder {
            val t0 = System.nanoTime()
            val e2e = File(root, "e2e")
            val j = Json.parse(File(e2e, "BOOT.json").readText()).asObject()
            fun d(k: String) = Bits.bitsToDouble(java.lang.Long.parseUnsignedLong(j[k]!!.asString().removePrefix("0x"), 16))
            val boot = BootState(d("gts"), d("prevGts"), d("ld"), d("ldf"), d("recordtime"), d("recorddtf"), d("recorddts"),
                j["mootouch_counter"]!!.asInt(),
                Bits.bitsToFloat(java.lang.Long.parseLong(j["roenScale"]!!.asString().removePrefix("0x"), 16).toInt()),
                File(e2e, "moopad_boot.bin").readBytes(),
                j["emojiExpanderXnl"]?.asInt() ?: 0, j["slideDisp"]?.asInt() ?: 20,
                trieDir = File(root, "trie/english"),
                searchBoot = File(root, "e2e_search_boot.json"))
            val lang = boot.langState(boot.moopad(), learnedDir)
            val dec = NintypeDecoder(boot, lang)
            dec.learnedDir = learnedDir
            lastLoadMillis = (System.nanoTime() - t0) / 1_000_000
            return dec
        }

        /**
         * A board from SwiftKey's keys. [rects] holds left, top, right, bottom in view px per key.
         * Only letter keys (and the apostrophe) carry a chara; the spacebar is U+0020; function
         * keys are passed with chara 0 so the edge letters' touch ranges stop at them, as on
         * Nintype's board. One letter key = 54 x 54 board units (GLUE.md, "The mapping SwiftKey
         * must apply"). Returns null when the layout has no letters (symbols, emoji, numbers).
         */
        @JvmStatic
        fun buildModel(labels: Array<String?>, rects: FloatArray, count: Int): KeyboardModel? {
            val keys = ArrayList<HostKey>(count)
            val widths = ArrayList<Float>()
            val heights = ArrayList<Float>()
            for (i in 0 until count) {
                val label = labels[i] ?: ""
                val l = rects[4 * i]; val t = rects[4 * i + 1]; val r = rects[4 * i + 2]; val b = rects[4 * i + 3]
                if (r - l < 2f || b - t < 2f) continue
                val chara = charaOf(label)
                val type = typeOf(label, chara)
                keys += HostKey(label, chara, l, t, r, b, typeName = type)
                if (chara in 'a'.code..'z'.code) { widths += r - l; heights += b - t }
            }
            if (widths.size < 20) return null
            widths.sort(); heights.sort()
            return KeyboardModel.fromRects(keys, widths[widths.size / 2], heights[heights.size / 2])
        }

        private fun charaOf(label: String): Int {
            if (label.length == 1) {
                val c = label[0].lowercaseChar()
                if (c in 'a'..'z' || c == '\'') return c.code
                return 0
            }
            return if (label.equals("space", ignoreCase = true)) 0x20 else 0
        }

        private fun typeOf(label: String, chara: Int): String = when {
            chara == 0x20 -> "space"
            label.contains("shift", ignoreCase = true) -> "caps"
            label.contains("delete", ignoreCase = true) || label.contains("backspace", ignoreCase = true) -> "backspace"
            label.contains("enter", ignoreCase = true) || label.contains("return", ignoreCase = true) -> "ret"
            else -> ""
        }

        private val NO_OBSERVER = object : PipelineObserver {}
    }

    /**
     * Engine mode. true: one long-lived engine per KeyboardModel, its clock running on across
     * gestures, so Moopad-side context (caps, previous-word chain) carries over. A kept word is
     * closed with forceMergeAllBuffers (Nintype's space-tap commit, with its learning); a word the
     * user replaced or deleted drops the engine instead. false: a fresh engine per gesture on the
     * shared LangState. Both learn only kept words.
     */
    @JvmField var persistentEngine = false
    private var engine: NintypeEngine? = null
    private var engineModel: KeyboardModel? = null
    /** Persistent mode: the next frame index on the engine's clock. */
    private var frame = 0L
    /** Persistent mode: the last decoded word is still open (not committed or discarded). */
    private var openWord = false

    private fun frameUs(k: Long) = FIRST_FRAME_US + (k * 50_000 + 2) / 3

    private fun newEngine(model: KeyboardModel, lang: LangState): NintypeEngine {
        val e = NintypeEngine(model, boot, strict = false, observer = NO_OBSERVER, mergeHostFor = PipelineMergeHost,
            sharedLangState = lang)
        // Seek stamps visited nodes with the search generation; keep it counting up across engines.
        val host = e.mergeHost as? PipelineMergeHost.Factory
        if (host != null && host.gen < lastGen) host.gen = lastGen
        return e
    }

    /**
     * Decodes [log] (host px, uptime ms) on [model]: the touches replayed on NINLib's clock with
     * 60 Hz frames between them, the result read after the last lift.
     * [textBeforeCursor] is the editor text before the gesture (context for the literal scoring
     * and the previous-word Markov).
     */
    fun decode(model: KeyboardModel, log: TouchLog, textBeforeCursor: String?, maxWords: Int): DecodeResult {
        val t0 = System.nanoTime()
        val e: NintypeEngine
        if (persistentEngine) {
            // A word the user did not keep is dropped with its engine: Nintype has no way to close
            // a word without learning it, and a fresh engine on the shared LangState is cheap.
            if (openWord) { engine = null; openWord = false }
            if (engine == null || engineModel !== model) {
                engine = newEngine(model, ensureReady()); engineModel = model; frame = 0
            }
            e = engine!!
            setHistory(e.langState, textBeforeCursor)
            // a gap of 30 frames (0.5 s) since the last word, so nothing auto-merges into it
            if (frame > 0) repeat(30) { e.step(frameUs(frame++)) }
        } else {
            engine = null; openWord = false
            val lang = ensureReady()
            if (freshLangPerGesture) this.lang = null
            setHistory(lang, textBeforeCursor)
            e = newEngine(model, lang)
            frame = 0
        }
        if (textBeforeCursor != null) e.pred.textBeforeCursor = textBeforeCursor.toByteArray(Charsets.UTF_8)
        if (log.size > 0) {
            val base = log.time[0]
            // the first touch lands 3 frames after the current frame (the oracle corpus' phase)
            val touch0Ms = frameUs(frame + 3) / 1000
            val ox = model.origin.x; val oy = model.origin.y
            for (i in 0 until log.size) {
                val ms = touch0Ms + (log.time[i] - base)
                while (frameUs(frame) < ms * 1000) e.step(frameUs(frame++))
                val b = model.toBoard(log.x[i], log.y[i])
                e.onTouchEvent(log.pid[i], log.action[i], 2f * (b.x + ox), 2f * (b.y + oy),
                    log.pressure[i], log.touchSize[i], ms)
            }
            e.step(frameUs(frame++)); e.step(frameUs(frame++))
        }
        (e.mergeHost as? PipelineMergeHost.Factory)?.let { lastGen = maxOf(lastGen, it.gen) + 1 }
        val seen = LinkedHashSet<String>()
        val words = ArrayList<String>()
        val scores = ArrayList<Float>()
        for (c in e.candidates) {
            if (words.size >= maxWords) break
            if (c.word.isEmpty() || !seen.add(c.word.lowercase())) continue
            words += c.word; scores += c.score
        }
        val text = e.liquidText?.trim()?.takeIf { it.isNotEmpty() }
        val pending = e.pending.distinct().joinToString("; ")
        // The word stays open until the user keeps it ([commitLast]) or the next decode drops it.
        lastEngine = e
        lastText = text
        if (persistentEngine) { openWord = true; e.pending.clear() }
        return DecodeResult(text, words.toTypedArray(), scores.toFloatArray(), (System.nanoTime() - t0) / 1000, pending)
    }

    private var lastEngine: NintypeEngine? = null
    private var lastText: String? = null

    /**
     * The user committed [word] after the last decode. When it is the word Nintype chose, run
     * Nintype's own commit on that engine (forceMergeAllBuffers: reinforcementsOnMergingAll and
     * the trie's learning into the shared LangState). Other words are not learned. Returns whether
     * it learned.
     */
    fun commitLast(word: String): Boolean {
        val e = lastEngine ?: return false
        lastEngine = null
        if (!word.equals(lastText, ignoreCase = true)) return false
        try {
            e.forceMergeAllBuffers()
        } catch (t: Throwable) {
            if (e === engine) engine = null
            throw t
        }
        if (e === engine) openWord = false
        return true
    }

    /**
     * Decodes a synthetic swipe over t-h-e on [model] so the first real gesture does not pay for
     * class loading and JIT. Returns the word it decoded (expected "the").
     */
    fun warmUp(model: KeyboardModel): String? {
        val log = TouchLog(64)
        val pts = "the".map { c -> model.keyFor(c.code)?.let { k -> model.toHost(k.centre) } ?: return null }
        var t = 0L
        log.add(0, 0, pts[0].x, pts[0].y, 1f, 0.1f, t)
        for (k in 1 until pts.size) for (s in 1..6) {
            t += 16
            log.add(0, 1, pts[k - 1].x + (pts[k].x - pts[k - 1].x) * s / 6, pts[k - 1].y + (pts[k].y - pts[k - 1].y) * s / 6, 1f, 0.1f, t)
        }
        log.add(0, 2, pts.last().x, pts.last().y, 1f, 0.1f, t + 16)
        val r = decode(model, log, null, 3)
        lastEngine = null
        ensureReady()
        return r.text
    }

    /**
     * Teaches Nintype [words] (e.g. SwiftKey's personal vocabulary) the way its learning module
     * does once a pending word is learned: LangEnv::learnNewWord, then
     * markMinimumScoreForWord(word, 0.5, -1). Words the trie already has at a usable score are
     * skipped. Nintype never runs learnNewWord on those (WordLearningModule::onMerge only sees
     * words without a TrieWord), and on one it replaces the word's dictionary score with the
     * learned score 0.298 and clears its flags, so the word sinks or leaves the candidates (the
     * oracle does the same: re/vectors/gen_trie_learned_e2e.py). Words still in the trie but
     * disabled (score at most 0.02) go through learnNewWord, which re-enables them at 0.5.
     * Returns how many it learned.
     */
    fun learnWords(words: Array<String>): Int {
        val lang = ensureReady()
        val env = lang.env
        var n = 0
        for (w in words) {
            if (w.isEmpty()) continue
            val b = w.toByteArray(Charsets.UTF_8)
            val t = env.maybeGetTrieWord(env.standardiseToLower(b))
            if (t >= 0 && env.trieWordScoremult(lang.tw(t)) > 0.02f) continue
            env.learnNewWord(b)
            env.markMinimumScoreForWord(b, 0.5f, -1)
            n++
        }
        return n
    }

    /** Writes Nintype's learned state (only the files that changed; never throws). False when
     * there is no learned dir or a write failed. */
    fun saveLearned(): Boolean = lang?.saveLearned() ?: false

    /** LangState.history (committed words, oldest first): what NeoMarkov reads as context.
     * Rebuilt from the editor before every decode, so edits and stock typing count too. */
    private fun setHistory(lang: LangState, text: String?) {
        lang.history.clear()
        if (text == null) return
        val words = text.split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        for (w in words.takeLast(3)) lang.history += w.toByteArray(Charsets.UTF_8)
    }
}
