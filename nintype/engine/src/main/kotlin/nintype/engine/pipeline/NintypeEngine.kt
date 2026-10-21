package nintype.engine.pipeline

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dadd
import nintype.engine.SoftFloat.ddiv
import nintype.engine.SoftFloat.dmul
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.f2d
import nintype.engine.SoftFloat.f2iz
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.i2d
import nintype.engine.SoftFloat.i2f
import nintype.engine.geometry.KeyboardModel
import nintype.engine.geometry.MoopadGeometry
import nintype.engine.merge.MooMergeBuffer
import nintype.engine.math.Point2D
import nintype.engine.touch.Mootouch
import nintype.engine.touch.TouchEnv
import nintype.engine.vectors.MemImage

/** A decoder step whose port does not exist yet. The engine either records it and goes on
 * ([NintypeEngine.strict] = false: checkpoint testing) or throws. */
class NotPortedException(val function: String) : RuntimeException("not ported yet: $function")

/** One ranked candidate: the word, PredPath+0x74 final score, PredPath+0xfc word prior. */
data class Candidate(val word: String, val score: Float, val prior: Float)

/** Calls into the decoder made by the pipeline, for staged comparison with the oracle trace.
 * Touches are named by their unique id (Mootouch+0xc0). */
interface PipelineObserver {
    fun record(mt: Int, p: Point2D, rerecord: Boolean, clock: EngineClock) {}
    fun update(mt: Int, clock: EngineClock) {}
    /** markReleased entry: [body] is the touch's state after its last record. */
    fun release(mt: Int, body: MemImage) {}
    fun sit(mt: Int, flag: Boolean) {}
    /** sitTouch entry: the touch's SaveableTouch records (40 bytes each). */
    fun sitRecords(mt: Int, recs: List<ByteArray>) {}
    fun mergeBuffers() {}
    /** syncPredictedText at each Moopad::recordPredStepsDebug: the step label, the alternatives
     * and the chosen WordAlternative. */
    fun predStep(label: String, alts: List<MemImage>, chosen: MemImage) {}
    /** The markliquid text op (UTF-8 bytes). */
    fun liquid(text: ByteArray) {}
    /** The solidify text op (UTF-8 bytes). */
    fun solidify(text: ByteArray) {}
    /** A call on the word-commit path (see re/vectors/gen_pipeline_commit.py). */
    fun commitCall(fn: String, args: String) {}
    /** registerSittingTouch, after syncVisitationPoint on the appended touch. */
    fun visited(mt: Int, touch: Mootouch) {}
    /** MooMergeBuffer::update entry: the buffer's start / lastTouch and the call's arguments. */
    fun mergeUpdate(start: Int, lastTouch: Int, last: Int, first: Boolean, full: Boolean) {}
    /** The sitting list's Mootouch bodies at updateMergeBuffers entry, in order. */
    fun sittingAtMerge(bodies: List<MemImage>) {}
}

/**
 * The Kotlin NintypeEngine: the JNI entry points NINLib.onTouchEvent / NINLib.step and what
 * they run down to Mootouch::record and MooMergeBuffer::update (engine/src/main/kotlin/
 * nintype/engine/merge/PIPELINE.md has the oracle's per-event call tree).
 *
 * Ported here (exact, see PipelineE2ETest): the clocks ([EngineClock]), the RoenGL touch
 * transform ([RoenTouchTracker]), Moopad::obtainTouch and Mootouch() ([TouchBook]),
 * Moopad::handleMooTouch's decoder path (earliest-touch scan + newPressShouldAutomerge on
 * DOWN, the MOVE subdivision with its interpolated record clock), performMooTouchRelease
 * and Moopad::update's per-frame record clock and live-touch loop, plus the SaveableTouch
 * history ([SaveableTouch]) that markReleased and calcDepartureVelocity read back. UI work on
 * the same paths (sounds, emoji boards, drawers, Xignals, force-touch UI) is left out. Steps
 * that still need a port are reported through [pending].
 */
private const val AUTOSPACE_OFF = 0x8074

class NintypeEngine(
    val model: KeyboardModel,
    val boot: BootState,
    var strict: Boolean = true,
    val observer: PipelineObserver = object : PipelineObserver {},
    /** The PathSearch / PathResult / ACCollectage side of MooMergeBuffer::update, built for
     * this engine; null until the search port provides it (the update then runs as a pending
     * step). Defaults to search's PipelineMergeHost when it is on the classpath. */
    mergeHostFor: ((NintypeEngine) -> MergeHostFactory)? = MergeHosts.default,
    /** A LangState shared between engines (one per process: the trie build is the expensive
     * part); null builds this engine's own on first use. Its learning objects are then shared too. */
    sharedLangState: LangState? = null,
) {
    val mergeHost: MergeHostFactory? by lazy { mergeHostFor?.invoke(this) }
    val clock = boot.clock()
    /** The Moopad image (settings, Xnls, embedded modules), from the booted emulator. */
    val pad: MemImage = boot.moopad()
    /** AutospaceModule embedded at Moopad+0x8074 (merge area's model; +0 points at the Moopad).
     * The merge ports take it as its own image, so it is a working copy that [withAutospace]
     * refreshes from [pad] before and writes back after every call: the Moopad's view of those
     * bytes (AutospaceSplitoffStats at +0x8370.., read by MooMergeBuffer::update) stays current. */
    val autospace: MemImage = MemImage("am", nintype.engine.merge.AutospaceModule.SIZE).also { it.ptrs[0] = pad }

    /** Runs [f] on the AutospaceModule image, synced with [pad] around the call. Search's merge
     * host should use it for AutospaceModule::syncAutospaceStatsPostSearch / onDetectEarlySplit. */
    fun <T> withAutospace(f: (MemImage) -> T): T {
        val n = nintype.engine.merge.AutospaceModule.SIZE
        autospace.load(0, pad.readBytes(AUTOSPACE_OFF, n))
        try { return f(autospace) } finally { pad.load(AUTOSPACE_OFF, autospace.readBytes(0, n)) }
    }
    val touches = TouchBook(pad, boot.mootouchCounter, clock)
    val merges = MergeBuffers()
    /** LangEnv's englishTrie and the pending-word trie root (empty on a fresh engine). */
    val trie: nintype.engine.trie.CompactTrie get() = langState.trie
    val pendingRoot = nintype.engine.trie.PendingTrieNode()
    private val roen = RoenTouchTracker()
    val origin: Point2D = model.origin
    /** Steps that ran unported (strict = false), in call order. */
    val pending: MutableSet<String> = LinkedHashSet()
    /** The decoder's ranked candidates of the last merge-buffer update (once ported). */
    var candidates: List<Candidate> = emptyList()
        internal set
    private var lastListed: List<Candidate> = emptyList()
    var liquidText: String? = null
        internal set

    private val imbue by lazy {
        ImbueStartingArea(pad, model.board, model.board, model.roenWidth,
            { k -> nintype.engine.geometry.NintypeLayout.KEY_TYPES.getOrElse(model.keys.indexOf(k)) { "" } },
            boot.emojiExpanderXnl, ::pend)
    }

    fun pend(name: String) {
        if (strict) throw NotPortedException(name)
        pending += name
    }

    private fun env(): TouchEnv = TouchEnv(model, pad, clock.recordTime, boot.roenScale,
        clock.recordDtf, clock.gts, clock.recordDts, clock.lastDtFactor)

    private fun uid(mt: Mootouch) = mt.body.i32(0xc0)

    private fun record(mt: Mootouch, p: Point2D, rerecord: Boolean) {
        observer.record(uid(mt), p, rerecord, clock)
        mt.record(env(), p, rerecord)
    }

    /** NINLib.onTouchEvent(pid, action, x, y, pressure, size, ms): surface px, ms of
     * System.currentTimeMillis. Decodes synchronously on the UP that lifts the last pointer. */
    fun onTouchEvent(pid: Int, action: Int, x: Float, y: Float, pressure: Float, size: Float, ms: Long) {
        val ti = roen.build(pid, action, x, y, pressure, size, clock.touchTime(ms))
        // KeyboardPage / Moopad::handleTouchEvent UI filters (tutorial, emoji crown, bottom bar,
        // menus) never swallow a touch on the bare letter board.
        handleMooTouch(ti)
    }

    /** NINLib.step() with the gettimeofday clock at [clockUs]. */
    fun step(clockUs: Long) {
        clock.step(clockUs)
        moopadUpdate()
    }

    // --- Moopad::handleMooTouch(TouchInfo const&) @008f3d10 ------------------------------

    private fun handleMooTouch(ti: TouchInfo) {
        if (pad.i32(MoopadGeometry.CUR_BOARD) == 0) return   // no current board
        clock.setRecordForTouch(ti.t)
        when {
            ti.isDown -> down(ti)
            ti.isMove -> move(ti)
            ti.isUp -> performMooTouchRelease(ti)
        }
    }

    private fun down(ti: TouchInfo) {
        // the earliest live touch (by +0xf8) and the live point count
        var earliestT = clock.recordTime
        var startPos = ti.pos() - origin
        var points = 0
        for (o in touches.live.values) {
            points += o.pts.size
            val ot = o.body.f64(0xf8)
            if (!(nintype.engine.SoftFloat.dcmpge(ot, earliestT) || nintype.engine.SoftFloat.dcmpun(ot, earliestT))) {
                earliestT = ot
                startPos = Point2D(o.body.f32(0x128), o.body.f32(0x12c))   // Mootouch::getStartpos
            }
        }
        val board = model.board
        val sitting = touches.sitting.map { nintype.engine.merge.AutospaceModule.Sitting.of(it, env()) }
        val merge = withAutospace { am -> nintype.engine.merge.AutospaceModule.newPressShouldAutomerge(am, earliestT, startPos, points + 1,
            pad.i32(0x29440), board, board.named[0xfc4], board.named[0xfac], sitting) }
        if (merge == 1) {
            forceMergeAllBuffers(); turnOnTempAutospace()
        }
        val mt = touches.obtainTouch(ti.pid, ti.pos() - origin)
        // caps: when the caps Xnls (Moopad+0x29c28 / +0x29bf4) are on, or the shift key
        // (board+0xfa8) is held and this touch is on it, the press turns caps off and marks +0xcc
        val shift = board.named[0xfa8]
        if (pad.u8(0x29c28 + 0xc) != 0 || pad.u8(0x29bf4 + 0xc) != 0 ||
            (shift != null && shift.hasToucher() && mt.body.i32(0x14) == model.indexOf(shift))) {
            turnOffCaps()
            mt.body.setU8(0xcc, 1)
        }
        mt.body.setU8(0xb5, board.img.u8(0x7a))      // TouchInfo flag "..." is never set here
        if (MoopadGeometry.peckmodeActive(pad) == 1) mt.body.setU8(0xb7, 1)
        mt.body.setI32(0xe4, 0)
        record(mt, ti.pos() - origin, false)
        if (mt.body.u8(0xb5) != 0) SaveableTouch.record(mt, ti.pos() - origin, nintype.engine.geometry.MoopadGeometry.translateTwerkForce(pad, ti.force().force), clock.recordTime)
        mt.body.setI32(0x268, mt.body.i32(0x268) and 8.inv())
        imbue.run(mt)
        mt.syncForceKeyEligibility(env())
        mt.body.setU8(0xb8, 0)
        mt.takeForceTouchUpdate(env(), ti.force())
    }

    private fun move(ti: TouchInfo) {
        val existing = touches.live[ti.pid] ?: return
        val len = ti.dpos().length()
        val mt = touches.obtainTouch(ti.pid, ti.pos() - origin)
        check(mt === existing)
        val forceBefore = mt.body.f32(0x98)
        mt.takeForceTouchUpdate(env(), ti.force())
        val forceAfter = mt.body.f32(0x98)
        // slide distance for the slide sound (+0x280, reset to +0x284 every soundsys_slidedisp
        // units; the sound itself is UI)
        val acc = nintype.engine.SoftFloat.fadd(len, mt.body.f32(0x280))
        mt.body.setF32(0x280, acc)
        val thr = nintype.engine.SoftFloat.fadd(mt.body.f32(0x284), i2f(boot.slideDisp))
        if (!(nintype.engine.SoftFloat.fcmple(acc, thr) || nintype.engine.SoftFloat.fcmpun(acc, thr))) mt.body.setF32(0x280, mt.body.f32(0x284))
        var n = f2iz(Math.ceil(fmul(len, 0.25f).toDouble()).toFloat())   // msun ceilf: exact
        if (n < 1) n = 1
        val cur = ti.pos()
        if (n == 1 || mt.body.u8(0xb5) == 0) {
            record(mt, cur - origin, false)
            if (mt.body.u8(0xb5) != 0) SaveableTouch.record(mt, cur - origin, nintype.engine.geometry.MoopadGeometry.translateTwerkForce(pad, ti.force().force), clock.recordTime)
        } else {
            val prev = ti.pos() - ti.dpos()
            val tPrev = if (mt.body.u8(0x1d0) != 0) mt.body.f64(0x1c8) else clock.recordTime
            val tCur = clock.recordTime
            val fn = i2f(n)
            val dn = i2d(n)
            for (i in 1..n) {
                val f = fdiv(i2f(i), fn)
                val p = prev.interpolate(cur, f) - origin
                val fd = f2d(f)
                clock.recordTime = dadd(dmul(tCur, fd), dmul(tPrev, dsub(1.0, fd)))
                clock.recordDtf = ddiv(clock.lastDtFactor, dn)
                clock.recordDts = ddiv(clock.lastDt, dn)
                record(mt, p, false)
                val force = nintype.engine.SoftFloat.fadd(fmul(forceAfter, f), fmul(forceBefore, nintype.engine.SoftFloat.fsub(1f, f)))
                SaveableTouch.record(mt, p, force, clock.recordTime)
            }
        }
        maybeRetireSlidingMootouch(mt)
    }

    /** Moopad::forceMergeAllBuffers(std::string const&) @0069e25c -> (WordAlternative const&,
     * std::string const&) @006eb1c4 with a default WordAlternative: sets the merge-all request
     * Moopad+0x29d68, copies the WordAlternative into Moopad+0x29d6c.. (the explicit choice the
     * merge-all path commits; default here, and that path is still pending) and runs
     * updateMergeBuffers. */
    /** MooMergeBuffer::update calls so far, and the first listResults of the latest one (null:
     * it listed nothing); [Backspace] reads them around its re-decode. */
    internal var mergeUpdateCount = 0
    internal var lastUpdateFirstList: List<Candidate>? = null

    /** The backspace key on the open word (Moopad::performBackspace, [Backspace]): removes the
     * word's last touch group and re-decodes the rest. Runs at the clock of the last [step], as
     * the backspace key's touch event does. With no word open it changes nothing and returns
     * handled = false. */
    fun backspace(): BackspaceResult = Backspace.perform(this)

    fun forceMergeAllBuffers() {
        pad.setU8(0x29d68, 1)
        updateMergeBuffers()
    }

    /** Moopad::turnOnTempAutospace(std::string const&) @006a1d78: with Xnl Moopad+0x29aa4 on,
     * Xnls +0x297c8 and +0x29794 = true (the autospacelog line is logging). */
    fun turnOnTempAutospace() {
        if (pad.u8(0x29aa4 + 0xc) == 0) return
        setXnl(0x297c8, true)
        setXnl(0x29794, true)
    }

    /** Moopad::maybeRetireSlidingMootouch(Mootouch&) @006923a8 (5145 insns). Over the e2e corpus
     * it returns false on every call (1235 per 8 gestures) and writes only the slide scratch
     * block Moopad+0x29958..0x29a07, which nothing else reads (read hooks over all 28 gestures).
     * Its retiring branch handles slides that begin on a function key (space cursor slide,
     * backspace slide; slide UI in SCOPE.md). So: a no-op for touches that start on a letter key,
     * a pending step otherwise. */
    private fun maybeRetireSlidingMootouch(mt: Mootouch) {
        val k = mt.body.i32(0x20)   // the touch's first key
        val type = nintype.engine.geometry.NintypeLayout.KEY_TYPES.getOrElse(k) { "" }
        if (k < 0 || type.isNotEmpty()) pend("Emkey::Moopad::maybeRetireSlidingMootouch (touch starting on ${if (k < 0) "no key" else type})")
    }

    /** Moopad::turnOffCaps(std::string const&) @0061a0e0: Xnl Moopad+0x29bf4 = false (the
     * observers it fires are UI). */
    fun turnOffCaps() {
        pad.setU8(0x29bf4 + 0xc, 0)
        pad.setU8(0x29bf4 + 0xd, 0)
    }

    // --- Moopad::performMooTouchRelease(TouchInfo const&) @008f625c ---------------------

    private fun performMooTouchRelease(ti: TouchInfo) {
        if (!touches.live.containsKey(ti.pid)) return
        clock.setRecordForTouch(ti.t)
        val mt = touches.obtainTouch(ti.pid, ti.pos() - origin)
        record(mt, ti.pos() - origin, false)
        mt.takeForceTouchUpdate(env(), ti.force())
        observer.release(uid(mt), mt.body)
        mt.markReleased(env())
        if (mt.body.u8(0xb5) != 0) SaveableTouch.record(mt, ti.pos() - origin, mt.body.f32(0xa4), clock.recordTime)
        val curX = mt.body.f32(0x130)
        val curY = mt.body.f32(0x134)
        sitTouch(mt, false)
        pad.setF64(0x2b530, ti.t)
        pad.setF32(0x2b538, curX); pad.setF32(0x2b53c, curY)
    }

    // --- Moopad::sitTouch(Mootouch&, bool, TouchInfo const*) @008f6508 ------------------

    private fun sitTouch(mt: Mootouch, flag: Boolean) {
        observer.sit(uid(mt), flag)
        observer.sitRecords(uid(mt), mt.body.seq(SaveableTouch.OFF).map {
            MemImage.decode(it).readBytes(0, SaveableTouch.SIZE)
        })
        val b = mt.body
        pad.setU8(0x9c7c, 0)
        // the earliest earlier-ending live touch: its last-record time, start and point count
        var tRef = b.f64(0xf8)
        var startPos = Point2D(b.f32(0x128), b.f32(0x12c))
        var points = mt.pts.size
        for (o in touches.live.values) {
            if (o === mt || o.body.u8(0x26e) and 2 != 0) continue
            val ot = o.body.f64(0x110)
            if (nintype.engine.SoftFloat.dcmpge(ot, b.f64(0xf8)) || nintype.engine.SoftFloat.dcmpun(ot, b.f64(0xf8))) continue
            if (!(nintype.engine.SoftFloat.dcmpge(ot, tRef) || nintype.engine.SoftFloat.dcmpun(ot, tRef))) {
                tRef = ot
                startPos = Point2D(o.body.f32(0x128), o.body.f32(0x12c))
                points += o.pts.size
            }
            // the SpaceCompleterHolderTouchHandler claim check here is UI (SCOPE.md: ablated
            // with no decode change)
        }
        // SpaceCompleterHolderTouchHandler::sitTouchPassFilter: UI, as above
        b.setI32(0x268, b.i32(0x268) and 4.inv())          // not claimed by the space completer
        // Moopad+0x29920 / sitTouch's tap-release routing (see tapRelease)
        val earlierLiveEnd = touches.live.values.any { o ->
            o !== mt && o.body.u8(0x26e) and 2 == 0 && run {
                val ot = o.body.f64(0x110)
                !(nintype.engine.SoftFloat.dcmpge(ot, b.f64(0xf8)) || nintype.engine.SoftFloat.dcmpun(ot, b.f64(0xf8)))
            }
        }
        val overlapsSitting = touches.sitting.any { nintype.engine.SoftFloat.dcmplt(b.f64(0xf8), it.body.f64(0x118)) }
        val (aux, spaceTap) = tapRelease(mt, earlierLiveEnd, overlapsSitting)
        val b108 = b.u8(0x108) != 0
        val routed = aux || spaceTap || b108 || b.u8(0xb4) != 0
        if (routed) {
            // a copy goes to the tap-release list (Moopad+0x29920); no sitting registration
            touches.tapReleases += mt
            // nothing in the port reads the list back; keep it bounded (the binary's trimming is
            // in the tap-release UI paths)
            while (touches.tapReleases.size > 8) touches.tapReleases.removeAt(0)
        } else {
            // with timed autospace (Xnl 0x7fa4) a new gesture after a non-tap one commits first
            if (!nintype.engine.SoftFloat.dcmplt(dsub(b.f64(0xf8), pad.f64(0x29718)), Bits.bitsToDouble(0x3fb99999a0000000L)) &&
                pad.u8(0x7fa4 + 0xc) == 1) pend("Emkey::Moopad::sitTouch (timed-autospace commit of the previous gesture, Xnl 0x7fa4)")
            val sitting = touches.sitting.map { nintype.engine.merge.AutospaceModule.Sitting.of(it, env()) }
            val merge = withAutospace { am -> nintype.engine.merge.AutospaceModule.newTouchShouldAutomerge(am, b.i32(0xbc),
                { if (mt.isSimpleTap(env())) 1 else 0 }, tRef, startPos, points, sitting) }
            if (merge == 1) {
                forceMergeAllBuffers(); turnOnTempAutospace()
            }
            registerSittingTouch(mt, flag)
            // Moopad::onSitTouchRegister -> PeckModule::onSitTouchRegister default-constructs a
            // Mootouch in its own map (operator[]): on the decoder side that only consumes one unique
            // id. Matches every sit of the corpus (ids go 1, 3, 5 for three taps).
            touches.counter = MootouchFactory.construct(MemImage("peck", MootouchFactory.SIZE), touches.counter, clock.recordTime, clock.gts).second
            // its peck-typing state (SecondaryPeckRecord deque, syncPeckRestorableness) feeds
            // only the peck restore UI: stubbing the whole call left all 31 e2e decodes
            // (candidates, text, text ops) identical in the oracle
        }
        // syncBottomSpacingness / markActualCurrentKey act on the live object after the copy
        touches.live.values.remove(mt)
        // after the erase: Xnl Moopad+0x2946c = (live touches left); the live touches' maximum
        // +0xa4 (where +0x94 is set) into Moopad+0x294ac and force modeness into +0x294b0
        pad.setI32(0x29440, touches.live.size)
        pad.setU8(0x2946c + 0xc, if (touches.live.isEmpty()) 0 else 1)
        pad.setF32(0x294ac, 0f); pad.setF32(0x294b0, 0f)
        for (o in touches.live.values) {
            if (o.body.u8(0x94) == 0) continue
            val v = o.body.f32(0xa4)
            if (nintype.engine.SoftFloat.fcmplt(pad.f32(0x294ac), v)) pad.setF32(0x294ac, v)
            // Mootouch::forceModeness (into +0x294b0) is force-touch UI (SCOPE.md: ui)
        }
        pad.setF64(0x294b8, b.f64(0x110))                    // the last lift's time
        // the gesture counter (Moopad+0x2991c, copied into each touch's +0xd0 by obtainTouch)
        // advances when the last pointer lifts
        if (touches.live.isEmpty()) pad.setI32(0x2991c, pad.i32(0x2991c) + 1)
        when {
            b.u8(0xb4) != 0 -> pend("Emkey::Moopad::sitTouch (+0xb4 touch)")
            spaceTap && !b108 -> spaceTapRelease(mt, flag)
            aux && !b108 -> pend("Emkey::Moopad::maybePerformAuxiliaryKeySimpleTapRelease (aux key tap)")
            b108 -> pend("Emkey::Moopad::sitTouch (+0x108 touch)")
            else -> {
                pad.setU8(0x22f41, 0)
                // the chain counter advances when an ordinary lift leaves no pointer down (sitTouch+0x159c)
                if (!flag && touches.live.isEmpty()) {
                    pad.setI32(0x2992c, pad.i32(0x2992c) + 1)
                    updateMergeBuffers()
                }
            }
        }
        // Moopad::addReleasePoints (TouchReleasePoints with a random palette colour, for
        // drawing): stubbed in the oracle, all 31 decodes identical
    }

    /** sitTouch's tap-release analysis (@008e6a10..008e6cec): (aux, spaceTap). aux = a simple
     * tap that a function key handles on release (Moopad::maybePerformAuxiliaryKeySimpleTapRelease,
     * through the CabaxHolder of the touch's key); spaceTap = a tap or short slide toward the
     * space key, or a probably protracted space tap. Both skip the sitting list.
     * [earlierLiveEnd]: another live touch's +0x110 precedes this one's start (sitTouch's sp+0x3c);
     * [overlapsSitting]: a sitting touch's +0x118 is after this one's start (sp+0x60). The
     * SpaceCompleter claim flags (sp+0x19f) and TouchInfo flags (sp+0x38) are UI / never set. */
    private fun tapRelease(mt: Mootouch, earlierLiveEnd: Boolean, overlapsSitting: Boolean): Pair<Boolean, Boolean> {
        val b = mt.body
        val e = env()
        val simple = mt.isSimpleTap(e)
        val sk = mt.pts.firstOrNull()?.i32(0x2f0)?.let { e.key(it) }   // Mootouch::getStartingKey
        if (simple && sk != null) {
            var f = if (pad.u8(0x296f4 + 0xc) != 0 || pad.u8(0x2b68c + 0xc) == 1) Bits.bitsToFloat(0x3dcccccd) else Bits.bitsToFloat(0x3ea8f5c3)
            if (MoopadGeometry.hasUSF1(pad, 0x124) != 0) f = Bits.bitsToFloat(0x3c23d70a)
            if (sk.isRechooser()) {
                val thr = nintype.engine.SoftFloat.fsub(i2f(sk.y1), fmul(f, MoopadGeometry.curboardVertlen(pad)))
                val y = b.f32(0x134)
                if (!(nintype.engine.SoftFloat.fcmpge(y, thr) || nintype.engine.SoftFloat.fcmpun(y, thr))) return true to false
            }
        }
        if (b.u8(0xb5) == 0 || model.board.img.u8(0x7a) == 0) return true to false
        if (b.u8(0xb7) != 0) return true to false
        if (earlierLiveEnd) return false to false
        if (simple && b.i32(0x268) and 0x800 != 0) return true to false
        val d = mt.getUnitDisplacement(e)
        val f12 = Bits.bitsToFloat(0x3f99999a)
        if (!(nintype.engine.SoftFloat.fcmpge(d, f12) || nintype.engine.SoftFloat.fcmpun(d, f12))) {
            if (mt.isSimplishSlideOrTapTowards(e, 0x20)) return false to true
            val prot = mt.isProbablyProtractedSpaceTap(e)
            if (prot || !simple) return false to prot
        } else if (!simple) return false to false
        val c = canPerformAuxSimpleTapRelease(Point2D(b.f32(0x130), b.f32(0x134)), overlapsSitting)
        if (c || !simple) return c to false
        return mt.isSimpleUnslideableTap(e) to false
    }

    /** Moopad::canPerformAuxSimpleTapRelease(Point2D const&, bool) @0069ff28: the key under
     * [p] (MooBoard::findKey of the truncated position) exists, is not a rechooser, is not "^" or
     * "<" when [overlapping], and has Mookey+0x98 in {0, 0x20}. */
    private fun canPerformAuxSimpleTapRelease(p: Point2D, overlapping: Boolean): Boolean {
        val k = model.board.findKey(f2iz(p.x), f2iz(p.y)) ?: return false
        if (k.isRechooser()) return false
        if (overlapping && (k.name == "^" || k.name == "<")) return false
        return k.img.i32(0x98) or 0x20 == 0x20
    }

    /** sitTouch's space-tap release (@008e73a4..008e7e6c): the word commit. Counter, then
     * forceMergeAllBuffers (Moopad::onSitTouchSpacebar after it has no decoder effect). */
    private fun spaceTapRelease(mt: Mootouch, flag: Boolean) {
        val b = mt.body
        if (!flag && b.i32(0xbc) != 0) pad.setI32(0x2992c, pad.i32(0x2992c) + 1)
        val count = touches.sitting.size
        if (b.u8(0x26b) and 0x10 != 0 ||
            (count != 0 && pad.u8(0x7f34 + 0xc) == 1 && MoopadGeometry.hasUSF1(pad, 0xd5) == 0))
            pend("Emkey::Moopad::turnOffTempAutospace")
        // the Shing::Context values pushed around the commit (the tap's duration and start time)
        // only matter to updateMergeBuffers while another pointer is down
        if (touches.live.isNotEmpty()) pend("Emkey::Moopad::sitTouch (space commit with a pointer down: Shing::Context values)")
        forceMergeAllBuffers()
        // Moopad::onSitTouchSpacebar: stubbed in the oracle, the three commit gestures' decodes and
        // text ops stay identical (the space goes in with the merge-all's solidify)
    }

    /** Moopad::registerSittingTouch(Mootouch&, bool) @008f91f4: number the touch (+0xd4 sit
     * index; +0xd8 chain id: the current chain counter Moopad+0x2992c, or with the bool set the
     * chain of a time-overlapping sitting touch, else a new one), run the
     * finger-collision detachment checks, append it to the sitting list and resort it. */
    private fun registerSittingTouch(mt: Mootouch, flag: Boolean) {
        val b = mt.body
        if (pad.u8(0x2d960) == 0) { pad.setU8(0x2d960, 1); pad.setF64(0x2d968, b.f64(0xf8)) }
        pad.setU8(0x33074, 0)
        pad.setU8(0x29940, 0)
        val idx = pad.i32(0x29928); pad.setI32(0x29928, idx + 1)
        b.setI32(0xd4, idx)
        val chain = if (!flag) pad.i32(0x2992c) else {
            val o = touches.sitting.firstOrNull { nintype.engine.search.MootouchOps.timeOverlaps(it.body, b, 0f) }
            if (o != null) o.body.i32(0xd8) else pad.i32(0x2992c).also { pad.setI32(0x2992c, it + 1) }
        }
        b.setI32(0xd8, chain)
        // two-thumb collision marks (touch's ports): mt against the live touches in pid order
        // (mt itself included; the self check returns false), else against the sitting ones,
        // each pass stopping at the first mark; then each sitting touch against mt
        val e = env()
        if (touches.live.values.none { mt.checkFingerCollisionDetachmentPointsFromAnotherTouch(e, it) })
            touches.sitting.any { mt.checkFingerCollisionDetachmentPointsFromAnotherTouch(e, it) }
        touches.sitting.any { it.checkLastTouchFingerCollisionDetachmentPointsToANewTouch(mt) }
        touches.sitting += mt
        VisitationPoint.sync(mt, model)
        observer.visited(uid(mt), mt)
        pad.setU8(0x2975c + 0xc, if (touches.sitting.size > 1) 1 else 0)
        pad.setU8(0x29728 + 0xc, 1)
        if (touches.sitting.size > 1) resyncSittingTouchesStartBasedSortation()
        pad.setU8(0x32ea0 + 0xc, 0); pad.setU8(0x32f08 + 0xc, 0)
        if (!flag) pad.setF64(0x29718, b.f64(0x110))
    }

    // --- Moopad::updateMergeBuffers(std::string const&) @0067a698 --------------------------

    /** Moopad::updateMergeBuffers: one MooMergeBuffer::update per touch group of the sitting
     * touches, drops buffers outside the group range, sweep marking (Moopad+0x766), then
     * syncPredictedText and the dead-buffer cleanup. Moopad+0x29d68 = merge-all request (set by
     * forceMergeAllBuffers): with it the commit path (reinforcementsOnMergingAll, history,
     * char Markov) runs instead of the 0x297fc trigger; that path is still pending. */
    internal fun updateMergeBuffers() {
        observer.mergeBuffers()
        observer.sittingAtMerge(touches.sitting.map { it.body })
        val full = pad.u8(0x29d68) != 0
        pad.setU8(0x29d68, 0); pad.setU8(0x2b32c, 0)
        // with a pointer still down only a merge-all request proceeds (the Shing::Context debug
        // override never holds in the decoder)
        if (pad.i32(0x29440) != 0 && !full) return
        // group range of the sitting touches
        var lo = 0; var hi = 0; var any = false
        for (mt in touches.sitting) {
            val c = mt.body.i32(0xd8)
            if (any) { if (hi < c) hi = c; if (c < lo) lo = c } else { lo = c; hi = c }
            any = true
        }
        if (any) for (c in lo..hi) {
            val slot = merges.obtain(c)
            observer.mergeUpdate(slot.buf.i32(MooMergeBuffer.START_TOUCH), slot.buf.i32(MooMergeBuffer.LAST_TOUCH), hi, c == lo, true)
            val f = mergeHost
            if (f == null) pend("Emkey::MooMergeBuffer::update (Host: PathSearch / PathResult / ACCollectage)")
            else {
                // the oracle driver's candidates: the first listResults of the latest update, or
                // the latest listResults overall when that update listed nothing
                var first: List<Candidate>? = null
                merges.update(slot, hi, c == lo, true, pad, clock.gts, ListingHost(f.host(slot), f) {
                    val l = f.lastCandidates()
                    if (first == null) first = l
                    lastListed = l
                })
                candidates = first ?: lastListed
                lastUpdateFirstList = first
            }
            mergeUpdateCount++
        }
        // buffers inside the range: m = the lowest start of a live buffer, pulled down by dead
        // buffers whose [start, last] covers it; buffers outside the range are dropped
        // (the binary walks the map from its last node down)
        var m = hi
        var anyDead = false
        var outside = false
        for (s in merges.map.descendingMap().values) {
            val start = s.buf.i32(MooMergeBuffer.START_TOUCH)
            if (s.key < lo || s.key > hi) { outside = true; continue }
            val dead = s.buf.u8(MooMergeBuffer.DEAD) != 0
            anyDead = anyDead or dead
            if (dead && (start > m || m > s.buf.i32(MooMergeBuffer.LAST_TOUCH))) continue
            if (start < m) m = start
        }
        if (outside) merges.map.keys.removeIf { it < lo || it > hi }
        if (merges.map.isEmpty()) { m = hi; anyDead = false }
        if (pad.u8(0x766) != 0) {
            val v = merges.map.values.toList()
            for ((i, s) in v.withIndex()) {
                if (i == 0) MooMergeBuffer.markSweepMaster(s.buf, v.size - 1)
                else MooMergeBuffer.markDeadAfter(s.buf, s.containers(), -1)
            }
        }
        syncPredictedText()
        if (full) mergeAll()
        else if (pad.u8(0x297fc + 0xc) == 1) pend("Roen::Trigger (Moopad+0x29830) = true")
        predCalls += "cleanup full=$full anyDead=$anyDead m=$m bufs=" + merges.map.values.joinToString(",") {
            "${it.key}:s${it.buf.i32(MooMergeBuffer.START_TOUCH)}/l${it.buf.i32(MooMergeBuffer.LAST_TOUCH)}/d${it.buf.u8(MooMergeBuffer.DEAD)}"
        }
        if (anyDead) {
            val drop = merges.map.values.filter { full || it.buf.i32(MooMergeBuffer.LAST_TOUCH) < m }
            for (s in drop) {
                clearSittingTouchIslands(s.buf.i32(MooMergeBuffer.START_TOUCH))
                merges.map.remove(s.key)
            }
        }
        // no buffers and no sitting touches left: the group counter restarts (Letterflower is UI)
        if (merges.map.isEmpty() && touches.sitting.isEmpty()) pad.setI32(0x2992c, 0)
    }

    /** updateMergeBuffers' merge-all branch (@0066af4c..0066cf00): the word commit. The forced
     * WordAlternative (Moopad+0x29d6c), history (MoopadReplaceableHistory, savePathResult*,
     * SaveableTouchSet), reinforcementsOnMergingAll, the character Markov step for ' ' and the
     * reset of the forced WordAlternative come first (pending), then
     * updateStateAfterMergingAll. */
    private fun mergeAll() {
        val wa = Wa.copy(pred.current)
        // PathSearch::getTopResultantPathIfMatching + keystats_addPending: typing stats (SCOPE.md: ui)
        val hist = ReplaceableHistory()
        if (predPathResults.isNotEmpty()) pend("Emkey::Moopad::savePathResultFromSplitsToOldHistory")
        // SaveableTouchSet of the sitting touches: the replaceable history's touch copy (UI replay)
        reinforcementsOnMergingAll(wa, hist)
        // TextOnlyHistArchive::remember: text history (SCOPE.md: ui)
        pred.s32e5c = nintype.engine.trie.Caps.toWLower(Wa.word(hist.wa))
        pad.setI32(0x2affc, 0)
        progressCharaMarkov(0x20)
        if (langState.replaceable.size > 0x1e) { langState.replaceable.removeFirst(); langState.history.removeAt(0) }
        // the forced WordAlternative (Moopad+0x29d6c) is reset
        pred.forced = Wa.new("wa29d6c")
        val upload = uploadText(wa)
        observer.commitCall("onJustBeforeUpload", "s=${hex(upload)}")
        // Moopad::onJustBeforeUpload: (SCOPE.md ui-glue) the client's sendSol (solidify text op)
        solidify(upload)
        updateStateAfterMergingAll(wa, upload, ByteArray(0))
    }

    /** The text the commit uploads (the space logic of updateMergeBuffers @0066b6xx): the display
     * text plus a space with the default settings; the temp-autospace / Shing::Context variants are
     * pending. */
    private fun uploadText(wa: MemImage): ByteArray {
        val s0 = Wa.s(wa, 0)
        if (pad.u8(0x297c8 + 0xc) != 0 || pad.u8(0x7fa4 + 0xc) != 0 || pad.u8(0x29794 + 0xc) != 0)
            pend("Emkey::Moopad::updateMergeBuffers (upload text with temp autospace: Xnl 0x297c8 / 0x7fa4 / 0x29794)")
        if (Wa.flags(wa) and 0xc != 0) pend("Emkey::Moopad::updateMergeBuffers (upload text, WA flags 0xc)")
        return if (s0.isEmpty()) s0 else s0 + byteArrayOf(0x20)
    }

    /** The client's solidify (SpringboardMoopadUser::sendSol): the text op, and the text before the
     * cursor grows by it (SpringboardMoopadUser+0x544). */
    private fun solidify(text: ByteArray) {
        observer.solidify(text)
        pred.textBeforeCursor = pred.textBeforeCursor + text
    }

    /** Moopad::reinforcementsOnMergingAll(WordAlternative&, Refcounter<MoopadReplaceableHistory>&)
     * @006d7dc0: the learning on a commit and the replaceable-history entry for it. */
    private fun reinforcementsOnMergingAll(wa: MemImage, hist: ReplaceableHistory) {
        observer.commitCall("reinforcementsOnMergingAll", "wa=${waLog(wa)}")
        val cur = pred.current
        if (Wa.word(pred.forced).isEmpty()) {
            if (pad.u8(0x33074) == 0) {
                if (canReinforceFromThis(cur) && pad.u8(0x1f424 + 0xc) == 0) maybeReinforceOnStandardMerge(cur)
            } else {
                hist.chosen = true
                if (pred.alts.isNotEmpty()) maybeReinforceOnChoiceChoosing(pred.alts[0], cur)
            }
        } else {
            Wa.assign(wa, pred.forced)
            hist.chosen = true
            if (canReinforceFromThis(wa)) maybeReinforceOnChoiceChoosing(cur, pred.forced)
        }
        hist.was += Wa.copy(wa)
        if (Wa.flags(wa) and 0x900 != 0) {
            observer.commitCall("onChooseCasing", "word=${hex(Wa.word(wa))}")
            langState.weirdCase.onChooseCasing(Wa.word(wa))
        }
        observer.commitCall("badExplicitOnUsage", "wa=${waLog(wa)}")
        langState.badExplicit.onUsage(Wa.word(wa), wa.i32(0x1c), Wa.flags(wa), nowDate().sec)
        if (Wa.summary(cur) != Wa.summary(wa) && Wa.word(cur).isNotEmpty()) hist.was += Wa.copy(cur)
        Wa.assign(hist.wa, wa)
        for (alt in pred.alts) if (Wa.summary(alt) != Wa.summary(wa)) hist.was += Wa.copy(alt)
        if (Wa.word(wa).isNotEmpty() && pad.u8(0x1f424 + 0xc) == 0) {
            if (canReinforceFromThis(wa)) {
                if (Wa.flags(wa) and 0x300200 == 0x200) {
                    observer.commitCall("maybeStartUpvoting", "word=${hex(Wa.word(wa))} b=${(Wa.flags(wa) shr 8) and 1}")
                    pend("Emkey::Moopad::maybeStartUpvoting")
                }
                observer.commitCall("maybeReinforceExplicitCorrectionAfterSelection", "wa=${waLog(wa)}")
                if (pred.s32e8c.isNotEmpty()) pend("Emkey::Moopad::maybeReinforceExplicitCorrectionAfterSelection (retype history)")
            }
            langState.replaceable.addLast(hist)
            langState.history.add(Wa.word(hist.wa).copyOf())
            onHistoryChange(1)
        }
        observer.commitCall("recordRecentlyUsedWord", "wa=${waLog(wa)}")
        recordRecentlyUsedWord(Wa.word(wa))
    }

    /** WordAlternative::canReinforceFromThis() @008463ec: neither flag 0x400 nor 0x20000. */
    private fun canReinforceFromThis(w: MemImage) = Wa.flags(w) and 0x400 == 0 && Wa.flags(w) and 0x20000 == 0

    /** Moopad::maybeReinforceOnStandardMerge(WordAlternative&) @006dcba0: (Xnl 0x2ba68 off) the
     * retype reinforcement over the destroyed-word histories (Moopad+0x32cfc and the deque at
     * +0x32d08); without any (no backspace since boot) nothing happens. */
    private fun maybeReinforceOnStandardMerge(w: MemImage) {
        observer.commitCall("maybeReinforceOnStandardMerge", "wa=${waLog(w)}")
        if (pad.u8(0x2ba68 + 0xc) != 0) return
        if (langState.destroyed.isNotEmpty()) pend("Emkey::Moopad::maybeReinforceOnStandardMerge (retype histories)")
    }

    private fun maybeReinforceOnChoiceChoosing(a: MemImage, b: MemImage) {
        observer.commitCall("maybeReinforceOnChoiceChoosing", "a=${waLog(a)} b=${waLog(b)}")
        pend("Emkey::Moopad::maybeReinforceOnChoiceChoosing")
    }

    /** Moopad::onHistoryChange(int) @00d212b4: signalTopMovement (UI), clearCharaMarkov, the
     * history trigger (+0x32d28). */
    private fun onHistoryChange(n: Int) {
        observer.commitCall("onHistoryChange", "n=$n")
        pend("Emkey::Moopad::clearCharaMarkov")
        pad.setU8(0x32d28 + 0xc, 1)
    }

    /** Moopad::recordRecentlyUsedWord(std::string const&) @00657548 (with Xnls 0x1f424 / 0x43a0
     * off): the recently used words deque (+0x9888). */
    private fun recordRecentlyUsedWord(word: ByteArray) {
        if (pad.u8(0x1f424 + 0xc) != 0) return
        // with Xnl 0x43a0 off (the default) the recently-used deque is emptied; with it on the
        // word is added unless one of the newest entries already holds it (pending)
        if (pad.u8(0x43a0 + 0xc) == 0) { pred.recentlyUsed.clear(); return }
        pend("Emkey::Moopad::recordRecentlyUsedWord (Xnl 0x43a0: deque +0x9888)")
    }

    /** Moopad::progressCharaMarkov(char) @006d85b4 (the char deque at +0x22f18 gets [c], keeps
     * its last chars, then syncCharaMarkov pads it to three) and
     * Moopad::syncPathSearchCharaMarkovBuffer() @00630564: the last two bytes of the committed
     * word, lower-cased (+0x32e5c), into +0x22f43 (last) / +0x22f42 (the one before, ' ' for a
     * one-byte word); 0 / 0 for an empty one. The deque and syncCharaMarkov's output are pending. */
    private fun progressCharaMarkov(c: Int) {
        observer.commitCall("progressCharaMarkov", "c=$c")
        pend("Emkey::Moopad::progressCharaMarkov / syncCharaMarkov (char deque +0x22f18)")
        val w = pred.s32e5c
        if (w.isEmpty()) { pad.setU8(0x22f43, 0); pad.setU8(0x22f42, 0); return }
        pad.setU8(0x22f43, w[w.size - 1].toInt() and 0xff)
        pad.setU8(0x22f42, if (w.size > 1) w[w.size - 2].toInt() and 0xff else 0x20)
    }

    /** Moopad::updateStateAfterMergingAll(WordAlternative&, std::string const& upload,
     * std::string const& pre) @006d8878. */
    private fun updateStateAfterMergingAll(wa: MemImage, upload: ByteArray, pre: ByteArray) {
        observer.commitCall("updateStateAfterMergingAll", "wa=${waLog(wa)} a=${hex(upload)} b=${hex(pre)}")
        // typing statistics in the Mmap at Moopad+0x770 (+0x7b8, +0x7bc): stats (UI)
        val word = Wa.word(wa)
        pred.recentWords.addLast(word.copyOf())
        if (pred.recentWords.size > 5) pred.recentWords.removeFirst()
        pad.setI32(0x2af9c, pad.i32(0x2a104))
        observer.commitCall("interactuteOnMerge", "wa=${waLog(wa)}")
        // Interactute::onMerge: the tutorial / two-finger training game (SCOPE.md: ui)
        if (Wa.flags(wa) and 0x40 != 0) pend("Emkey::wg_onWordMerge (WildGuesser)")
        else if (Wa.flags(wa) and 0x80 != 0) pend("Emkey::wg_onWildUsage (WildGuesser)")
        observer.commitCall("onMergeAfterMeringAll", "pre=${hex(pre)} wa=${waLog(wa)}")
        val ls = langState
        ls.wlm.onMergeAfterMergingAll(pre, word, Wa.trieWord(wa) != null, Wa.flags(wa), nowDate(), clock.gts,
            MoopadGeometry.hasUSF1(pad, 0x10b) != 0) { w -> ls.env.maybeGetTrieWord(w) >= 0 }
        // Morestring::endsWith(word, ...) -> SBRenderer::enableIlluminati: an easter egg (UI)
        val tw = Wa.trieWord(wa)
        if (tw != null) {
            if (pad.u8(0x4f00 + 0xc) == 0) {
                val raw = ls.trie.getRawWord(tw)
                observer.commitCall("acOnStandardUsage", "word=${hex(raw)}")
                ls.autoComp.onStandardUsage(raw, nowDate(), nowDate())
            }
            observer.commitCall("langflowOnWordUsage", "")
            pend("Emkey::LangEnv::langflow_onWordUsage")
        } else if (pad.u8(0x4f00 + 0xc) == 0) {
            val lw = nintype.engine.trie.Caps.toWLower(word)
            observer.commitCall("acOnStandardUsage", "word=${hex(lw)}")
            ls.autoComp.onStandardUsage(lw, nowDate(), nowDate())
        }
        if (Wa.s(wa, 0).isNotEmpty()) pred.s2b5ec = ByteArray(0)   // addFadingChoice: UI
        for (s in merges.map.values.toList()) clearSittingTouchIslands(s.buf.i32(MooMergeBuffer.START_TOUCH))
        merges.map.clear()
        pad.setF64(0x29d58, clock.gts)
        syncPredictedText()
        pad.setU8(0x22f08, 0)
        pend("Emkey::Moopad::updateStateAfterMergingAll (auto-caps 0x29918, TextboxSelData)")
        observer.commitCall("onJustAfterMergingAll", "wa=${waLog(wa)} s=${hex(upload)}")
        pend("Emkey::Moopad::onJustAfterMergingAll")
    }

    /** The static "now" SDate the learning code reads: the last NINLib.step's gettimeofday. */
    fun nowDate(): nintype.engine.trie.SDate = nintype.engine.trie.SDate((clock.lastClockUs / 1_000_000L).toInt(), (clock.lastClockUs % 1_000_000L).toInt())

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun waLog(w: MemImage) = "${hex(Wa.s(w, 0))}/${hex(Wa.word(w))}/%08x/%d".format(Wa.flags(w) and 0x07ffffff, w.i32(0x1c))

    /** Moopad::clearSittingTouchIslands(int) @006d9534: drop the sitting touches of group [c]. */
    private fun clearSittingTouchIslands(c: Int) {
        touches.sitting.removeIf { it.body.i32(0xd8) == c }
        setXnl(0x29728, touches.sitting.isNotEmpty())
        setXnl(0x2975c, touches.sitting.size > 1)
        resyncSittingTouchesStartBasedSortation()
    }

    // --- syncPredictedText and its inputs ------------------------------------------------

    /** Moopad::syncPredictedText(std::string const&) @006d4670 ([SyncPredictedText]). */
    private fun syncPredictedText() {
        pad.setI32(0x29d4c, merges.map.size)
        SyncPredictedText(this).run()
    }

    /** MooMergeBuffer::getSweepChoice(int&, double*, WordAlternative*, vector*, vector*,
     * ACCollectage*, bool*) @0089eda4 on [slot]: the sweep master's result, walked back past
     * results that score <= 0.5 or not above 0.2 x the previous one (merge's sweepChoiceIndex),
     * then returnSweepChoice: its WordAlternative (+0xc), alternatives (+0x5c), time (+0x88),
     * PathResults (+0x94, into [predPathResults]) and whether it is the last result. null = the
     * function returned 0 (no sweep master). [sweepOverride] stands in while there is no host. */
    fun sweepChoice(slot: MergeSlot): SweepChoice? {
        sweepOverride?.let { return it(slot) }
        val f = mergeHost ?: run { pend("Emkey::MooMergeBuffer::getSweepChoice (no merge host)"); return null }
        val views = try { slot.results.map { f.pathResult(it) } } catch (x: NotImplementedError) {
            pend("MergeHostFactory.pathResult (search host)"); return null
        }
        val i = nintype.engine.merge.MooMergeBuffer.sweepChoiceIndex(slot.buf, views.map { it.image }) ?: return null
        val v = views[i]
        predPathResults.clear(); predPathResults.addAll(v.subResults)
        return SweepChoice(true, i, v.time, i + 1 == views.size, Wa.copy(v.wa), Wa.deepCopy(v.alts))
    }

    /** Test hook: the n-th sweep choice from the oracle while MooMergeBuffer::update has no host. */
    var sweepOverride: ((MergeSlot) -> SweepChoice?)? = null

    /** MooMergeBuffer::getMostRecentAutoCompleteBuffer(ACCollectage*) @0089ed74. */
    fun mostRecentAutoComplete(slot: MergeSlot, ac: nintype.engine.merge.ACModel) {
        val acs = slot.acs.filterIsInstance<nintype.engine.merge.ACModel>()
        nintype.engine.merge.MooMergeBuffer.getMostRecentAutoCompleteBuffer(acs, ac)
    }

    /** MooMergeBuffer::getExplicitResult(Moopad*, float&, float&, bool&, vector<uint>&) @0089e510. */
    fun explicitResult(slot: MergeSlot): nintype.engine.merge.ExplicitResult.Out {
        val o = nintype.engine.merge.ExplicitResult.Out(0f, 0f, false, ArrayList())
        nintype.engine.merge.MooMergeBuffer.getExplicitResult(slot.buf, touches.sitting, env(), pad, o)
        return o
    }

    /** The client's markLiquid (SpringboardMoopadUser::markMoopadLiquid): the liquid text. */
    fun liquid(text: ByteArray) {
        liquidText = String(text, Charsets.UTF_8)
        observer.liquid(text)
    }

    val pred = PredState(pad)
    val predConfig = PredConfig()
    /** Debug: the state at the last 32 syncPredictedText entries / buffer cleanups. */
    val predCalls: MutableList<String> = object : ArrayList<String>() {
        override fun add(element: String): Boolean { if (size >= 32) removeAt(0); return super.add(element) }
    }
    /** The LangEnv side (trie agent's ports), built on first use from the englishTrie. */
    val langState: LangState by lazy { sharedLangState ?: boot.langState(pad) }
    /** syncPredictedText's language queries: the trie ports when the trie dump is present. */
    var lang: PredLang = if (boot.trieDir != null) TrieLang(this) else PendingLang(::pend)
    /** Moopad+0x2b0b8: the chosen result's PathResults (host handles). */
    val predPathResults = ArrayList<Any?>()

    internal fun setXnl(off: Int, v: Boolean) = pad.setU8(off + 0xc, if (v) 1 else 0)

    /** Moopad::resyncSittingTouchesStartBasedSortation() @006cfbc4. Rebuilds the sitting
     * touches sorted by +0xf8 (the vector<Mootouch*> at Moopad+0x294cc, [TouchBook.sittingByStart]),
     * then summarises the sitting list into Moopad Xnls that the merge/search side reads
     * (0x295a8, 0x295dc, 0x29644 among them):
     *   0x29574 any +0x26c bit 0x1000   0x2950c any bit 0x2000   0x29540 any isSimplishTap among those
     *   0x294d8 any +0xbc > 0            0x295a8 any !isSimpleTap   0x29644 any +0x102
     *   0x29678 any point with u16 +0x224 bit 8   i32 0x296e0 / Xnl 0x296ac: isMidStraightSlowdown points
     *   0x296e4 any isSlidePauserPoint   f64 0x29720: max(gts, +0x110) for the first touch, then
     *   each later touch's +0x110 (the binary's loop overwrites it unconditionally after the first)
     * When every sitting touch is a simple tap it measures the tap rhythm over the start-sorted
     * list. When there are at least 2 gaps and every gap lies within [0.8, 1.2] x the mean, 4+
     * taps get +0x26c bit 0x4000, and with a mean gap over 0.2 s all of them (3+) get bit 0x8000
     * (Xnl 0x29610 = steady rhythm). Xnl 0x295dc = !irregular or any 0x4000/0x8000 bit.
     * std::sort is introsort: stable for up to 16 elements, which the stable sort here matches;
     * larger lists only differ on equal start times. */
    internal fun resyncSittingTouchesStartBasedSortation() {
        val v = touches.sittingByStart
        v.clear()
        v.addAll(touches.sitting.sortedWith { a, c ->
            val x = a.body.f64(0xf8); val y = c.body.f64(0xf8)
            if (nintype.engine.SoftFloat.dcmplt(x, y)) -1 else if (nintype.engine.SoftFloat.dcmplt(y, x)) 1 else 0
        })
        var t = clock.gts
        pad.setF64(0x29720, t)
        var first = true
        var hi1000 = false; var hi2000 = false; var simplish = false; var ordered = false
        var notSimple = false; var b102 = false; var pt8 = false; var mids = 0; var pauser = false
        for (mt in touches.sitting) {
            val b = mt.body
            val simple = mt.isSimpleTap(env())
            val hi = b.i32(0x26c)
            if (hi and 0x2000 != 0) { simplish = simplish or mt.isSimplishTap(); hi2000 = true }
            val f110 = b.f64(0x110)
            if (!first || nintype.engine.SoftFloat.dcmplt(t, f110)) t = f110
            pad.setF64(0x29720, t)
            for (p in mt.pts) {
                if (nintype.engine.emkey.MooTouchPoint.isMidStraightSlowdown(p)) mids++
                pauser = pauser or nintype.engine.emkey.MooTouchPoint.isSlidePauserPoint(p)
                pt8 = pt8 or (p.u16(0x224) and 8 != 0)
            }
            notSimple = notSimple or !simple
            b102 = b102 or (b.u8(0x102) != 0)
            ordered = ordered or (b.i32(0xbc) > 0)
            hi1000 = hi1000 or (hi and 0x1000 != 0)
            first = false
        }
        setXnl(0x29574, hi1000); setXnl(0x2950c, hi2000); setXnl(0x29540, simplish)
        setXnl(0x294d8, ordered); setXnl(0x295a8, notSimple); setXnl(0x29644, b102)
        setXnl(0x29678, pt8)
        pad.setI32(0x296e0, mids); setXnl(0x296ac, mids > 0)
        setXnl(0x296e4, pauser)
        if (notSimple) { setXnl(0x295dc, false); setXnl(0x29610, false); return }
        // tap rhythm over the start-sorted taps
        var sum = 0f
        var gaps = 0
        for (i in 1 until v.size) {
            sum = d2f(dadd(f2d(sum), dsub(v[i].body.f64(0xf8), v[i - 1].body.f64(0xf8))))
            gaps++
        }
        if (gaps < 2) { setXnl(0x295dc, true); setXnl(0x29610, false); return }
        val avg = fdiv(sum, i2f(gaps))
        val hiT = d2f(dmul(f2d(avg), 1.2))
        val loT = fmul(avg, 0.8f)
        var irregular = false
        for (i in 1 until v.size) {
            val d = d2f(dsub(v[i].body.f64(0xf8), v[i - 1].body.f64(0xf8)))
            if (nintype.engine.SoftFloat.fcmpgt(d, hiT) || nintype.engine.SoftFloat.fcmplt(d, loT)) { irregular = true; break }
        }
        val any8000 = touches.sitting.any { it.body.i32(0x26c) and 0x8000 != 0 }
        val any4000 = touches.sitting.any { it.body.i32(0x26c) and 0x4000 != 0 }
        setXnl(0x295dc, any8000 || any4000 || !irregular)
        if (irregular) { setXnl(0x29610, any8000); return }
        val n = touches.sitting.size
        if (n >= 4) for (mt in touches.sitting) mt.body.setI32(0x26c, mt.body.i32(0x26c) or 0x4000)
        if (nintype.engine.SoftFloat.fcmple(avg, 0.2f) || nintype.engine.SoftFloat.fcmpun(avg, 0.2f) || n < 3) {
            setXnl(0x29610, any8000); return
        }
        for (mt in touches.sitting) mt.body.setI32(0x26c, mt.body.i32(0x26c) or 0x8000)
        setXnl(0x29610, true)
    }

    // --- Moopad::update() @006cd960 -----------------------------------------------------

    private fun moopadUpdate() {
        clock.setRecordForFrame()
        // Roen::Trigger::fire (Moopad+0x390) has AutospaceModule::updateAutospaceTimedMerge bound
        // only with timed autospace on (Xnl 0x7fd8; never fired with the boot defaults, checked
        // in the oracle)
        if (pad.u8(0x7fd8 + 0xc) != 0) {
            // the host callbacks run after the module's own writes (they write back first)
            var finish = false; var temp = false
            withAutospace { am ->
                nintype.engine.merge.AutospaceModule.updateAutospaceTimedMerge(am, clock.gts, clock.lastDtFactor,
                    touches.sitting.size, object : nintype.engine.merge.AutospaceModule.TimedMergeHost {
                        override fun maybeFinishWord() { finish = true }
                        override fun turnOnTempAutospace() { temp = true }
                    })
            }
            if (finish) pend("Emkey::Moopad::maybeFinishWord (autospace_timed)")
            if (temp) turnOnTempAutospace()
        }
        for (mt in touches.live.values.toList()) maybeRetireSlidingMootouch(mt)
        for (mt in touches.live.values.toList()) {
            observer.update(uid(mt), clock)
            observer.record(uid(mt), Point2D(mt.body.f32(0x130), mt.body.f32(0x134)), true, clock)
            mt.update(env())
            SaveableTouch.recordFrame(mt, clock.gts, clock.lastDtFactor, clock.lastDt)
        }
    }
}
