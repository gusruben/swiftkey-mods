package nintype.engine.pathscore

import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.SoftFloat.fsub
import nintype.engine.SoftFloat.i2f
import nintype.engine.math.LibM
import nintype.engine.trie.CombinedTriePath
import nintype.engine.trie.CompactTrie
import nintype.engine.vectors.MemImage
import java.util.Collections
import java.util.WeakHashMap

/**
 * The PredPath's embedded CombinedTriePath (+0x14c, 0x28 bytes) as the trie area's Kotlin
 * object, attached to the PredPath image, plus the dictionary trie (TWRet word lookups).
 */
object Ctp {
    private val store: MutableMap<MemImage, CombinedTriePath> = Collections.synchronizedMap(WeakHashMap())
    private val tries: MutableMap<MemImage, CompactTrie> = Collections.synchronizedMap(WeakHashMap())

    fun attach(pp: MemImage, c: CombinedTriePath) { store[pp] = c; tries[pp] = c.main.trie }
    fun of(pp: MemImage): CombinedTriePath = store[pp] ?: throw IllegalStateException("${pp.name}: no CombinedTriePath attached")
    fun has(pp: MemImage) = store.containsKey(pp)
    fun attachTrie(pp: MemImage, t: CompactTrie) { tries[pp] = t }

    private val words: MutableMap<MemImage, nintype.engine.trie.TrieWord> = Collections.synchronizedMap(WeakHashMap())

    /** Replaces the finisher TrieWord image (vectors that alter its flags). */
    fun overrideFinisherWord(pp: MemImage, tw: nintype.engine.trie.TrieWord) { words[pp] = tw }

    /** The finisher TWRet's TrieWord (TWRet id at +0x20), from the trie's pool. */
    fun finisherWord(pp: MemImage): nintype.engine.trie.TrieWord = words[pp] ?: run {
        val t = trie(pp); val id = pp.i32(0x20)
        nintype.engine.trie.TrieWord(t.trieWords.data(id), t.trieWords.off(id))
    }
    fun trie(pp: MemImage): CompactTrie = tries[pp] ?: of(pp).main.trie
}

/** PredPath members that query the trie cursor. Flags = the u16 at 0x2f (bit 6 = apostrophe
 * ending, bit 7 = apostrophe still open, bit 8 = possessive 's, bits 5/6 = can end early). */
object PredPathTrie {
    private fun flags(pp: MemImage) = pp.u8(0x2f) or (pp.u8(0x30) shl 8)

    /** canProgress() @0082354c. */
    fun canProgress(pp: MemImage): Boolean {
        val f = flags(pp)
        if (f and 0x100 != 0) return false
        if (f and 0x60 != 0 && pp.ref(PredPath.OFF_PS)!!.i32(0x160) != 0) return true
        return Ctp.of(pp).canadvance()
    }

    /** canFinish() @00823598. */
    fun canFinish(pp: MemImage): Boolean {
        val f = flags(pp)
        if (f and 0x100 != 0) return true
        if (f and 0x40 != 0) return f and 0x80 == 0
        return Ctp.of(pp).isvalued()
    }

    /** getWordLength() @00823ae4: cursor length, +2 for a possessive 's, +1 for an apostrophe. */
    fun getWordLength(pp: MemImage): Int {
        val l = Ctp.of(pp).lengthsofar()
        val f = flags(pp)
        return if (f and 0x100 != 0) l + 2 else l + ((f ushr 6) and 1)
    }

    /** maximumPathLength() @00823f48. */
    fun maximumPathLength(pp: MemImage): Int = pp.i8(0x2a) + Ctp.of(pp).longestPotentialLeft()

    /** getWord() @00823b20: the pending-word finisher's text (+0x1c; never set on a fresh
     * install, not modelled), else the finisher TrieWord's raw word (TWRet at +0x20, live when
     * its pointer word +0x24 is non-null; TrieWord::getRawWord = the word's StringPool entry),
     * else the cursor's path; then "'s" (flag 0x100) or "'" (flag 0x40). */
    fun getWord(pp: MemImage): ByteArray {
        check(pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
        var w = if (pp.i32(0x24) != 0) Ctp.trie(pp).rawWord(pp.i32(0x20))!! else Ctp.of(pp).pathsofar()
        val f = flags(pp)
        if (f and 0x100 != 0) w += byteArrayOf(0x27, 0x73)
        else if (f and 0x40 != 0) w += byteArrayOf(0x27)
        return w
    }

    /** syncNotLongEnoughMultpen() @0082448c: 0xbc = calculateNotLongEnoughMultpen(maximum length, false). */
    fun syncNotLongEnoughMultpen(pp: MemImage) =
        pp.setF32(0xbc, PredPath.calculateNotLongEnoughMultpen(pp, pp.i8(0x2a) + Ctp.of(pp).longestPotentialLeft(), false))

    /** syncSkipScoreMultForHeuristics() @008245f0: with skipped chars, 0x144 =
     * multForCharSkip(skips, min(maximum length, PS.0x144))^1.7. */
    fun syncSkipScoreMultForHeuristics(pp: MemImage) {
        val n = pp.i8(0x2c)
        if (n == 0) return
        val len = i2f(pp.i8(0x2a) + Ctp.of(pp).longestPotentialLeft())
        var l = pp.ref(PredPath.OFF_PS)!!.f32(0x144)
        if (fcmplt(len, l)) l = len
        pp.setF32(0x144, LibM.powf(PredPath.multForCharSkip(pp, n, l), Float.fromBits(0x3fd9999a)))
    }

    private val PRODUCT = intArrayOf(0xb0, 0xb4, 0xb8, 0x9c, 0xa0, 0xe0, 0xcc, 0xd0, 0xd4, 0xec, 0xbc, 0x144)

    /** determineScoreForHeuristics() @00824a08: H = 0.4(PS.0x144 - n) + (gain + 10 + 2.2n -
     * penalty) into 0x74; when H > 0 it is multiplied by 0x78 x mean(0x88/0x84) x mean(0x80/0x7c) x
     * the twelve multiplier fields x the variant factor. Always clears flag bit 2. */
    fun determineScoreForHeuristics(pp: MemImage) {
        val ps = pp.ref(PredPath.OFF_PS)!!
        Ctp.of(pp).longestPotentialLeft()   // called, result unused
        val n = pp.i8(0x2a)
        val r5 = fsub(ps.f32(0x144), i2f(n))
        val r6 = fmul(i2f(n), Float.fromBits(0x400ccccd))
        val r0 = fsub(fadd(fadd(pp.f32(0x68), 10f), r6), pp.f32(0x6c))
        val h = fadd(fmul(r5, Float.fromBits(0x3ecccccd)), r0)
        pp.setF32(0x74, h)
        if (!(fcmple(h, 0f) || fcmpun(h, 0f))) {
            val m1 = if (pp.i32(0x84) != 0) fdiv(pp.f32(0x88), i2f(pp.i32(0x84))) else 1f
            var x = fmul(pp.f32(0x78), m1)
            val m2 = if (pp.i32(0x7c) != 0) fdiv(pp.f32(0x80), i2f(pp.i32(0x7c))) else 1f
            x = fmul(x, m2)
            for (o in PRODUCT) x = fmul(x, pp.f32(o))
            val v = when (pp.i8(0x34)) { 0 -> 1f; 2 -> Float.fromBits(0x3f4ccccd); 1 -> Float.fromBits(0x3f75c28f); else -> Float.fromBits(0x3f19999a) }
            x = fmul(x, v)
            pp.setF32(0x74, fmul(h, x))
        }
        val f = flags(pp) and 0xfffb
        pp.setU8(0x2f, f and 0xff); pp.setU8(0x30, f ushr 8)
    }

    private fun inc8(pp: MemImage, o: Int) = pp.setU8(o, (pp.u8(o) + 1) and 0xff)

    /** advance(char) @00822c9c: moves the cursor (a failed advance only logs and asserts)
     * and counts the char at 0x2b either way. */
    fun advance(pp: MemImage, c: Int) {
        Ctp.of(pp).advance(c)
        inc8(pp, 0x2b)
    }

    /** advanceSkipping(char a, char b) @00822abc: advances both chars (counting each success at
     * 0x2b), then one more skipped char (0x2c) and one more letter (0x2a). */
    fun advanceSkipping(pp: MemImage, a: Int, b: Int) {
        val c = Ctp.of(pp)
        val ra = c.advance(a)
        val rb = c.advance(b)
        if (ra) inc8(pp, 0x2b)
        if (rb) inc8(pp, 0x2b)
        inc8(pp, 0x2c)
        inc8(pp, 0x2a)
    }

    /** advanceApostrophied(char c) @008228d8: advances the trie's apostrophe ('&') and then c,
     * counting successes at 0x2b (stops at the first failure); bumps the variant (0x34). */
    fun advanceApostrophied(pp: MemImage, ch: Int) {
        val c = Ctp.of(pp)
        if (c.advance(0x26)) {
            inc8(pp, 0x2b)
            if (c.advance(ch)) inc8(pp, 0x2b)
        }
        inc8(pp, 0x34)
    }

    /** advanceAposPossessive(char c) @00823158: an open apostrophe (flag 0x20, expecting '\'')
     * becomes 0x40; a 0x40 (expecting 's') becomes the possessive 0x100. Other states assert. */
    fun advanceAposPossessive(pp: MemImage, @Suppress("UNUSED_PARAMETER") ch: Int) {
        var f = flags(pp)
        if (f and 0x20 != 0) {
            f = (f and 0xff9f) or 0x40
            pp.setU8(0x30, (f ushr 8) and 0xff); pp.setU8(0x2f, f and 0xff)
        } else if (f and 0x40 != 0) {
            f = f and 0xfebf
            pp.setU8(0x2f, f and 0xff); pp.setU8(0x30, ((f or 0x100) ushr 8) and 0xff)
        }
    }

    /** multForCapTap() @008260f0: in literal-tap mode (PS+0x35a) on a valued cursor, 1.4 when
     * the finisher word's first caps flag is set and its third is not (getCapsBools), else 1. */
    fun multForCapTap(pp: MemImage): Float {
        if (pp.ref(PredPath.OFF_PS)!!.u8(0x35a) == 0 || !Ctp.of(pp).isvalued()) return 1f
        var a = false; var c = false
        if (pp.i32(0x24) != 0) { val (x, _, z) = Ctp.finisherWord(pp).capsBools(); a = x; c = z }
        check(pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
        return if (a && !c) Float.fromBits(0x3fb33333) else 1f
    }

    /** gatherFinisherTrieWords(vector<TWRet>&) @008235e0: the cursor's TrieWords (ids), none
     * (after an assert) when an apostrophe ending is pending (flags 0x140). */
    fun gatherFinisherTrieWords(pp: MemImage, out: MutableList<Int>) {
        if (flags(pp) and 0x140 != 0) return
        out.addAll(Ctp.of(pp).getTrieWords())
    }

    private fun b(v: Boolean) = if (v) 1 else 0

    /** canAdvanceEx(BranchmentData& bd) @008226e4: can the path take bd's char (+8)? Records
     * bd+0x23 (cursor can advance), +0x24 (apostrophe then char), +0x25 (closes an open
     * apostrophe / takes the possessive 's), and for longer paths gathers skip-to chars into
     * `skips` (bd+0x28, PathSearch skip-checker cache first), counting them in PathSearch
     * (+0x678 calls, +0x67c checks, +0x680 branches) and the path length's StepScoreStats. */
    fun canAdvanceEx(pp: MemImage, bd: MemImage, skips: MutableList<Int>, caches: nintype.engine.search.SkipCheckerCaches,
                     stats: MutableList<MemImage>): Boolean {
        val ps = pp.ref(PredPath.OFF_PS)!!
        ps.setI32(0x678, ps.i32(0x678) + 1)
        val f = flags(pp)
        if (f and 0x100 != 0) return false
        if (f and 0x40 != 0) {
            var r = bd.i32(8) == 0x73
            bd.setU8(0x25, b(r))
            if (bd.u8(0x20) == 0) { bd.setU8(0x25, 0); return false }
            if (bd.u8(0x21) != 0 && bd.u8(0x22) == 0) { r = false; bd.setU8(0x25, 0) }
            return r
        }
        val c = Ctp.of(pp)
        val ch = bd.u8(8)
        bd.setU8(0x23, b(c.canadvance(ch)))
        bd.setU8(0x24, b(c.canadvance(0x26, bd.u8(8))))
        bd.setU8(0x25, b(pp.u8(0x2f) and 0x20 != 0 && bd.i32(8) == 0x27))
        if (bd.u8(0x20) == 0 || (bd.u8(0x21) != 0 && bd.u8(0x22) == 0)) bd.setU8(0x25, 0)
        if (pp.i32(0x114) <= 3 && pp.i32(0x118) <= 5 && pp.i8(0x2a) >= 3 && c.canadvance() && caches.get(c).isPossible(bd.i32(8))) {
            ps.setI32(0x67c, ps.i32(0x67c) + 1)
            if (c.gatherskipadvance(bd.u8(8), skips)) {
                ps.setI32(0x680, ps.i32(0x680) + skips.size)
                StepStats.recSkipBranches(nintype.engine.search.PathSearch.getStepStats(ps, stats, pp.i8(0x2a)), skips.size)
            }
        }
        if (bd.u8(0x23) or bd.u8(0x24) or bd.u8(0x25) != 0) return true
        return skips.isNotEmpty()
    }

    /** Caps settings from the Moopad image (trie Caps.kt: Xnl bytes at +0x21cac/+0x21ce0/+0x29c68,
     * Moopad::hasUSF1). */
    fun capsSettings(mp: MemImage) = nintype.engine.trie.CapsSettings(mp.u8(0x21cac) != 0, mp.u8(0x21ce0) != 0, mp.u8(0x29c68) != 0,
        usf = { n -> nintype.engine.geometry.MoopadGeometry.hasUSF1(mp, n) != 0 })

    /** getProperWord() @00821a18: getWord(), upper-cased when PathSearch+0x358 (caps lock), else
     * capitalised like the finisher TrieWord (TrieWord::spliceCaps with PathSearch +0x359,
     * +0x35b, +0x35c). Pending words are not modelled. */
    fun getProperWord(pp: MemImage): ByteArray {
        val w = getWord(pp)
        val ps = pp.ref(PredPath.OFF_PS)!!
        if (ps.u8(0x358) != 0) return nintype.engine.trie.Caps.toWUpper(w)
        if (pp.i32(0x24) != 0) return nintype.engine.trie.Caps.spliceCaps(Ctp.finisherWord(pp).i32(0), w, ps.u8(0x359) != 0,
            ps.u8(0x35b) != 0, ps.u8(0x35c) != 0, capsSettings(ps.ref(0x110)!!))
        check(pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
        return w
    }

    /** finalizeWA(WordAlternative&) @00821fa0. The debug branch (Moopad Xnl +0x9230 == 1: keep
     * a copy of the path at +0x48 and its text at +0x4c) uses PredPathCtor.copyConstruct. */
    fun finalizeWA(pp: MemImage, wa: MemImage) {
        if (pp.i32(0x24) != 0 && Ctp.finisherWord(pp).hasWildGuess()) wa.setI32(0x30, wa.i32(0x30) or 0x40)
        val ps = pp.ref(PredPath.OFF_PS)!!
        var f = (wa.i32(0x30) and 0x1000.inv()) or (ps.u8(0x359) shl 12)
        f = (f and 0x2000.inv()) or (ps.u8(0x358) shl 13)
        wa.setI32(0x30, f)
        PredPathWA.imbueWAWithPenaltyReasons(pp, wa)
        if (Strs.get(wa, 4).contentEquals(byteArrayOf(0x27, 0x73))) wa.setI32(0x30, wa.i32(0x30) or 0x400000)
        val mp = ps.ref(0x110)!!
        if (nintype.engine.merge.Xnl.val_(mp, 0x9230) == 1) {
            val copy = MemImage("${pp.name}.debugCopy", PredPath.SIZE)
            PredPathCtor.copyConstruct(copy, pp)
            copy.setI32(0xc, copy.i32(0xc) + 1)
            wa.refs[0x48] = copy
            Strs.set(wa, 0x4c, Ctp.of(pp).pathsofar())
        }
    }

    /** getAsWA() @00823c8c: the path as a WordAlternative (built into `wa`). */
    fun getAsWA(pp: MemImage, wa: MemImage) {
        WordAlternative.init(wa)
        Strs.set(wa, 4, getProperWord(pp))
        if (pp.i32(0x24) != 0) {
            WordAlternative.setTrieWord(wa, pp.i32(0x20), pp.i32(0x24))
            WordAlternative.setEnderTrieWord(wa, pp.i32(0x20), pp.i32(0x24))
        } else check(pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
        val s = pp.f32(0x74); val n = pp.f32(0x100)
        wa.setF32(0x2c, s)
        wa.setF32(0x20, nintype.engine.SoftFloat.fdiv(s, n))
        wa.setF32(0x24, n)
        wa.setI32(0x1c, PredPathWA.properlyCountCoveredImportantPoints(pp))
        val ps = pp.ref(PredPath.OFF_PS)!!
        val caps = if (ps.u8(0x359) == 0 && ps.u8(0x358) == 0) 0 else 1
        val f0 = wa.i32(0x30)
        wa.setI32(0x30, (f0 and 0x8.inv()) or (caps shl 3))
        wa.setI32(0x30, (f0 and 0x4008.inv()) or (caps shl 3) or (ps.u8(0x35b) shl 14))
        wa.setF32(0x40, 1f)
        if (pp.i32(0x24) != 0) {
            val mp = ps.ref(0x110)!!
            wa.setF32(0x40, Ctp.finisherWord(pp).langflowedScoremult(mp.i32(0x21bc8), FloatArray(4) { mp.f32(0x21c64 + 4 * it) }))
        }
        wa.setF32(0x44, pp.f32(0xfc))
        finalizeWA(pp, wa)
    }

    /** A WordAlternative copy (WordAlternative(WordAlternative const&) @00470bc8): bytes,
     * strings and the debug path ref (whose refcount the binary bumps). */
    fun copyWA(src: MemImage, name: String): MemImage {
        val d = MemImage(name, 0x50)
        d.writeBytes(0, src.readBytes(0, 0x50))
        d.refs.putAll(src.refs)
        for (o in intArrayOf(0, 4, 8, 0xc, 0x4c)) Strs.set(d, o, Strs.get(src, o))
        return d
    }

    /** PathResult() @0081e314 (its WordAlternative at +0xc is `wa`). */
    fun initPathResult(pr: MemImage, wa: MemImage) {
        Strs.set(pr, 0, ByteArray(0))
        WordAlternative.init(wa)
        Vecs.put(pr, 0x5c, ArrayList())
        Strs.set(pr, 0x78, ByteArray(0))
        Vecs.put(pr, 0x94, ArrayList())
        for (o in intArrayOf(4, 8, 0x68, 0x6c, 0x74, 0x7c, 0x80, 0x8c, 0x88)) pr.setI32(o, 0)
        pr.setU8(0x70, 0); pr.setU8(0x90, 0)
    }

    /** result() @0082169c: the finished path as a PathResult: +0 proper word, +4 score/norm,
     * +8 norm, +0x68 score*length, +0x6c PathSearch touch count (+0x1a0 vector), +0x70 the cursor
     * can still advance, the WordAlternative at +0xc (`wa`), and that alternative appended to the
     * +0x5c vector. */
    fun result(pp: MemImage, pr: MemImage, wa: MemImage, touchCount: Int) {
        initPathResult(pr, wa)
        Strs.set(pr, 0, getProperWord(pp))
        val n = pp.f32(0x100); val s = pp.f32(0x74)
        pr.setF32(4, nintype.engine.SoftFloat.fdiv(s, n))
        pr.setF32(8, n)
        pr.setF32(0x68, nintype.engine.SoftFloat.fmul(s, nintype.engine.SoftFloat.ui2f(Strs.get(pr, 0).size)))
        pr.setI32(0x6c, touchCount)
        pr.setU8(0x70, if (Ctp.of(pp).canadvance()) 1 else 0)
        wa.setF32(0x40, 1f)
        val ps = pp.ref(PredPath.OFF_PS)!!
        if (pp.i32(0x24) != 0) {
            val mp = ps.ref(0x110)!!
            wa.setF32(0x40, Ctp.finisherWord(pp).langflowedScoremult(mp.i32(0x21bc8), FloatArray(4) { mp.f32(0x21c64 + 4 * it) }))
        } else check(pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
        wa.setF32(0x44, pp.f32(0xfc))
        Strs.set(wa, 4, Strs.get(pr, 0))
        wa.setF32(0x20, pr.f32(4))
        wa.setF32(0x2c, pp.f32(0x74))
        wa.setF32(0x24, pp.f32(0x100))
        if (pp.i32(0x24) != 0) {
            WordAlternative.setTrieWord(wa, pp.i32(0x20), pp.i32(0x24))
            WordAlternative.setEnderTrieWord(wa, pp.i32(0x20), pp.i32(0x24))
        }
        wa.setI32(0x1c, PredPathWA.properlyCountCoveredImportantPoints(pp))
        val caps = if (ps.u8(0x359) != 0) 1 else if (ps.u8(0x358) != 0) 1 else 0
        var f = (wa.i32(0x30) and 0x8.inv()) or (caps shl 3)
        wa.setI32(0x30, f)
        f = (f and 0x4000.inv()) or (ps.u8(0x35b) shl 14); wa.setI32(0x30, f)
        f = (f and 0x8000.inv()) or (ps.u8(0x35c) shl 15); wa.setI32(0x30, f)
        finalizeWA(pp, wa)
        Vecs.elems(pr, 0x5c).add(copyWA(wa, "${pr.name}.alternatives[0]"))
    }

    /** syncRouteCoverageHeuristics() @00820b04: 0xd0 = coverage^(2.7 below 4 letters, else 3.7)
     * where coverage = the cursor's coverage value after syncCompat; from 3 letters a coverage
     * below 0.84 of the best bound (PathSearch+0x124) gives 0.001. `coverage` is the
     * syncCompat + coverageValue step (the trie area's ports); vectors replay its result. */
    fun syncRouteCoverageHeuristics(pp: MemImage, coverage: () -> Float) {
        val v = coverage()
        val n = pp.i8(0x2a)
        pp.setF32(0xd0, LibM.powf(v, Float.fromBits(if (n < 4) 0x402ccccd else 0x406ccccd)))
        if (n < 3) return
        val r = fdiv(v, pp.ref(PredPath.OFF_PS)!!.f32(0x124))
        if (!(nintype.engine.SoftFloat.fcmpge(r, Float.fromBits(0x3f570a3d)) || fcmpun(r, Float.fromBits(0x3f570a3d)))) pp.setF32(0xd0, Float.fromBits(0x3a83126f))
    }

    /** The engine's coverage step for [syncRouteCoverageHeuristics]: CombinedTriePath
     * syncCompat(PathSearch*) then coverageValue(PathSearch*) (psStamp = PathSearch+0x120). */
    fun routeCoverage(pp: MemImage, compat: nintype.engine.trie.CompatSearch): Float {
        val c = Ctp.of(pp)
        val stamp = pp.ref(PredPath.OFF_PS)!!.i32(0x120)
        c.syncCompat(compat, stamp)
        return c.coverageValue(stamp)
    }
}
