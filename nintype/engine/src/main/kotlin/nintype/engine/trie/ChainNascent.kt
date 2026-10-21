package nintype.engine.trie

import nintype.engine.merge.GnuSortList

/** A chainlement held by a NascentChain (same 12-byte layout as NascentChainlement): +0 id, +4
 *  word, +8 / +9 / +0xA flags. */
class ChainElement(val word: ByteArray, var id: Int = 0, var f8: Int = 0, var f9: Int = 0, var fa: Int = 0) {
    /** NascentChainlement::takeAdditionContexts() @0097513c without its debug contexts: +8 = 1
     *  unless +9 is set. */
    fun takeAdditionContexts(): ChainElement { if (f9 == 0) f8 = 1; return this }
}

/**
 * Emkey::NascentChain (in ChainEnv's std::list at +0xABC): the text one text field holds, as
 * chainlements, before it is archived into the main chain. +8 f64 creation time (engine time,
 * never updated), +0x10 vector<NascentChainlement>, +0x1C the text before it was short (it starts
 * at the field's start), +0x1D the same for the text after.
 */
class NascentChain(val time: Double) {
    val els = ArrayList<ChainElement>()
    var f1c = false
    var f1d = false

    /** NascentChain::setup(NasVec&) @009783ac: the context's elements, and its short flags. */
    fun setup(nas: NasVec) {
        els.clear()
        for (e in nas.els) els.add(ChainElement(e.word.copyOf(), e.id, e.f8, e.f9, e.fa).takeAdditionContexts())
        f1c = nas.preShort
        f1d = nas.postShort
    }

    private fun wordAt(i: Int) = els[i].word

    /** NascentChain::findStarter(NasVec&) @00976348: where the context's elements before the
     *  cursor occur in the chain, last match first; with a short text before only at the start,
     *  and at the start only when the chain's own text began there or the context's does. */
    fun findStarter(nas: NasVec): IntArray? {
        val u = nas.f2c
        var i = els.size - u
        while (i >= 0) {
            val compare = if (i == 0) { if (f1c && !nas.preShort) break; true } else !nas.preShort
            if (compare && (0 until u).all { nas.els[it].word.contentEquals(wordAt(i + it)) }) return intArrayOf(i, i + u)
            i--
        }
        return null
    }

    /** NascentChain::findEnder(NasVec&, int from) @00975df4: the first place at or after [from]
     *  where the context's elements after the cursor occur in the chain. */
    fun findEnder(nas: NasVec, from: Int): IntArray? {
        val u = nas.els.size - nas.f30
        val last = els.size - u
        if (last < from) return null
        var r = from
        while (true) {
            if ((0 until u).all { nas.els[nas.f30 + it].word.contentEquals(wordAt(r + it)) }) return intArrayOf(r, r + u)
            if (r >= last) return null
            r++
        }
    }

    /** NascentChain::replaceWithFocus(IntRange const&, NasVec&) @009767c4: elements [a, b) give way
     *  to the context's cursor elements (+0x34..+0x38); the short flags follow when the range
     *  touches either end. */
    fun replaceWithFocus(a: Int, b: Int, nas: NasVec) {
        if (a == nas.f2c) f1c = nas.preShort
        if (nas.els.size + b - nas.f30 == els.size) f1d = nas.postShort
        val tail = ArrayList(els.subList(b, els.size))
        if (a > els.size) repeat(a - els.size) { els.add(ChainElement(ByteArray(0))) } else while (els.size > a) els.removeAt(els.size - 1)
        if (nas.f34 != -1) for (k in nas.f34..nas.f38) {
            val e = nas.els[k]
            els.add(ChainElement(e.word.copyOf(), e.id, e.f8, e.f9, e.fa).takeAdditionContexts())
        }
        els.addAll(tail)
    }

    /**
     * NascentChain::induct(NasVec&) @00977be4: tries to take the edited context as an edit of this
     * chain. Shrinks to the context when it lost a word, takes a lone cursor word into an almost
     * empty chain, else locates the context's words before the cursor (findStarter) and after it
     * (findEnder, at most 3 elements away) and replaces what lies between with the cursor elements.
     * False when the context does not belong to this chain.
     */
    fun induct(nas: NasVec): Boolean {
        val n = nas.els.size
        if (n == 0 && els.size == 1) { els.clear(); return true }
        if (n == 1 && els.size == 2) {
            if (wordAt(0).contentEquals(nas.els[0].word)) { els.removeAt(1); return true }
            if (wordAt(1).contentEquals(nas.els[0].word)) { els.removeAt(0); return true }
        }
        if (nas.f34 >= 0 && 1 - nas.f34 + nas.f38 == 1 && n == nas.f30 && nas.f2c == 0 && els.size <= 1) { setup(nas); return true }
        if (n - nas.f30 + nas.f2c > els.size) return false
        val after = n - nas.f30
        if (nas.f2c >= 1) {
            val st = findStarter(nas) ?: return false
            if (after >= 1) {
                val en = findEnder(nas, st[1]) ?: return false
                if (distanceFrom(en, st, 999999) > 3) return false
                val g = gap(en, st) ?: return false
                replaceWithFocus(g[0], g[1], nas)
                return true
            }
            if (els.size - st[1] > 2) return false
            replaceWithFocus(minOf(st[1], els.size), maxOf(st[1], els.size), nas)
            return true
        }
        if (after >= 1) {
            val en = findEnder(nas, 0) ?: return false
            if (en[0] > 2) return false
            replaceWithFocus(0, en[0], nas)
            return true
        }
        if (els.isEmpty()) { setup(nas); return true }
        return false
    }

    /** NascentChain::cutAfterFrontSucking(int) @00975898. */
    fun cutAfterFrontSucking(k: Int) {
        f1c = false
        repeat(k) { els.removeAt(0) }
    }

    companion object {
        /** Shing::IntRange::distanceFrom(IntRange const&, int) @01089b04 on valid ranges. */
        fun distanceFrom(a: IntArray, b: IntArray, @Suppress("UNUSED_PARAMETER") maxv: Int): Int = when {
            a[0] < b[1] && b[0] < a[1] -> 0
            a[0] < b[0] -> b[0] + 1 - a[1]
            else -> a[0] + 1 - b[1]
        }

        /** Shing::IntRange::getGap(IntRange const&) @01089db4: the range between two disjoint ranges
         *  (null, an invalid range, when they overlap). */
        fun gap(a: IntArray, b: IntArray): IntArray? = when {
            a[0] < b[1] && b[0] < a[1] -> null
            a[0] > b[0] -> intArrayOf(minOf(a[0], b[1]), maxOf(b[1], a[0]))
            else -> intArrayOf(minOf(a[1], b[0]), maxOf(a[1], b[0]))
        }

        private val ENDERS = listOf(".".toByteArray(), "?".toByteArray(), "!".toByteArray())
        fun isEnderWord(w: ByteArray) = ENDERS.any { it.contentEquals(w) }

        /** The 1-based index of the first ".", "?" or "!" (the whole length when there is none), as
         *  absorbFirstSentence and maybeSuckTailOff count it. */
        fun firstSentenceLength(els: List<ChainElement>): Int {
            var k = 0
            for ((i, e) in els.withIndex()) { k = i + 1; if (isEnderWord(e.word)) break }
            return k
        }
    }
}

/**
 * The text-tracking half of Emkey::ChainEnv (Moopad+0x31128): the main chain ([store]) and the
 * nascent chains. Moopad::inductToChainKeeper(false, "", 0, 0) @006b3c9c calls
 * [inductChainModificationNoOldtext] with the client's text before the cursor (latestNChars(100)),
 * its current word / selection (vtable +0x30) and the text after (vtable +0x40), gated by Xnl
 * Moopad+0x317E0 (on) and +0x1F424 (off).
 *
 * [maybeArchiveOldNascents] runs from Roen::Trigger ChainEnv+0x2D0: [archiveTrigger] is set when a
 * nascent chain is created and left set while any remain; the trigger hangs off ChainEnv+0x100 (fed
 * by its owner's trigger at +0x478) with infreq(4), so it fires on every 4th firing of its parent
 * while set. The trigger plumbing belongs to the Moopad update loop; this class only keeps the flag.
 */
class ChainEnv(val store: ChainStore = ChainStore()) {
    /** std::list<NascentChain> at +0xABC, in list order (new chains are appended). */
    val nascents = ArrayList<NascentChain>()
    /** +0xAA8 f64 time and +0xAB0 / +0xAB4 / +0xAB8 texts of the last induct, for its 0.1 s repeat filter. */
    var lastTime = 0.0
    var lastPre = ByteArray(0)
    var lastCur = ByteArray(0)
    var lastPost = ByteArray(0)
    /** Roen::Trigger +0x2D0 (armed). */
    var archiveTrigger = false

    /** ChainEnv::getSortedNascents() @009894a8: by creation time, oldest first (gnustl std::sort). */
    fun getSortedNascents(): MutableList<NascentChain> = ArrayList(nascents).also { GnuSortList.sort(it) { a, b -> a.time < b.time } }

    /**
     * ChainEnv::inductChainModificationNoOldtext(pre, cur, post) @0098a674 at engine time [now]:
     * when the chain is enabled (Xnl +0x844) and [cur] is under 51 bytes, skips a repeat of the last
     * texts within 0.1 s, then derives the context and, unless the cursor spans 3 or more elements,
     * offers it to each nascent chain, oldest first (NascentChain::induct); when none takes it, a new
     * nascent chain is started from it.
     */
    fun inductChainModificationNoOldtext(pre: ByteArray, cur: ByteArray, post: ByteArray, now: Double) {
        if (!store.enabled || cur.size >= 0x33) return
        val dt = now - lastTime
        if (dt < REPEAT_WINDOW && pre.contentEquals(lastPre) && post.contentEquals(lastPost) && cur.contentEquals(lastCur)) return
        lastTime = now
        lastPre = pre.copyOf(); lastPost = post.copyOf(); lastCur = cur.copyOf()
        val sorted = getSortedNascents()
        val nas = ChainDerive.deriveChainlements(pre, cur, post)
        if (!(nas.f34 < 0 || 1 - nas.f34 + nas.f38 < 3)) return
        for (nc in sorted) if (nc.induct(nas)) return
        val nc = NascentChain(now)
        nc.setup(nas)
        nascents.add(nc)
        archiveTrigger = true
    }

    /** ChainEnv::noRecentDuplicate(NascentChain&) @0098a124: false when the sentence (up to its
     *  first ender, else all of it) already follows a recorded position of its first word that lies
     *  within 0.8 of the ring. */
    fun noRecentDuplicate(tmp: List<ChainElement>): Boolean {
        if (tmp.isEmpty()) return true
        val ids = IntArray(tmp.size)
        for ((i, e) in tmp.withIndex()) { val h = store.maybeGetWord(e.word); if (h < 0) return true; ids[i] = h }
        val meta = store.metas[ids[0]] ?: return true
        val hp = store.currentHistoricalPos()
        for (p in meta.getWordPositions()) {
            val d = nintype.engine.SoftFloat.fdiv(nintype.engine.SoftFloat.i2f(hp - p), 200000f)
            if (nintype.engine.SoftFloat.fcmpgt(d, 0.8f)) continue
            var k = 0
            while (k < ids.size) {
                val h2 = store.wordAtHistpos(p + k)
                if (store.isSentenceEnder(ids[k])) return false
                if (h2 != ids[k]) break
                k++
            }
            if (k == ids.size) return false
        }
        return true
    }

    /** ChainEnv::maybeArchiveNascent(NascentChain*) @009899d4: sentence by sentence
     *  (NascentChain::absorbFirstSentence), each of at least 3 elements, 2 of them typed (+8 / +9),
     *  and not a recent duplicate goes into the main chain, with "." added when it has no ender;
     *  then the chain is dropped. */
    fun maybeArchiveNascent(nc: NascentChain) {
        while (nc.els.isNotEmpty()) {
            val k = NascentChain.firstSentenceLength(nc.els)
            val tmp = ArrayList(nc.els.subList(0, k))
            nc.cutAfterFrontSucking(k)
            if (tmp.size < 3) continue
            var cnt = 0
            for (e in tmp) cnt += (e.f8 or e.f9) and 0xff
            if (cnt < 2 || !noRecentDuplicate(tmp)) continue
            for (e in tmp) if (e.word.isNotEmpty()) store.addWordAtEnd(e.word)
            if (!NascentChain.isEnderWord(tmp[tmp.size - 1].word)) store.addWordAtEnd(".".toByteArray())
        }
        nascents.remove(nc)
    }

    /** ChainEnv::maybeSuckTailOff(NascentChain*) @00989f88: a chain of 81+ elements with 56+ after
     *  its first sentence hands that sentence to the main chain. */
    fun maybeSuckTailOff(nc: NascentChain) {
        if (nc.els.size < 0x51) return
        val k = NascentChain.firstSentenceLength(nc.els)
        if (nc.els.size - k < 0x38) return
        for (e in nc.els.subList(0, k)) if (e.word.isNotEmpty()) store.addWordAtEnd(e.word)
        nc.cutAfterFrontSucking(k)
    }

    /** ChainEnv::maybeArchiveOldNascents() @00989194 at engine time [now]: in creation order, the
     *  oldest chain only gets maybeSuckTailOff, the next two are archived once older than 80 s
     *  (float), the rest at once; the trigger stays armed while chains remain. (The save calls that
     *  follow write files only.) */
    fun maybeArchiveOldNascents(now: Double) {
        for ((r, nc) in getSortedNascents().withIndex()) {
            when {
                r > 2 -> maybeArchiveNascent(nc)
                r >= 1 -> if (nintype.engine.SoftFloat.fcmpgt(nintype.engine.SoftFloat.d2f(now - nc.time), 80.0f)) maybeArchiveNascent(nc)
                else -> maybeSuckTailOff(nc)
            }
        }
        archiveTrigger = nascents.isNotEmpty()
    }

    /** ChainEnv::gatherSuggestions with this env's main chain. */
    fun gatherSuggestions(before: ByteArray, coll: SuggestionsColl, ranker: ChainNextWordRanker) =
        ChainGather.gatherSuggestions(store, before, coll, ranker)

    companion object {
        /** The double 0x3FB9999AA0000000 (the f32 1.45 bit pattern as a high word: about 0.1). */
        val REPEAT_WINDOW = Double.fromBits(0x3FB9999AA0000000L)
    }
}
