package nintype.engine.ports

import nintype.engine.math.Point2D
import nintype.engine.pathscore.Flag64
import nintype.engine.pathscore.FingerPathData
import nintype.engine.pathscore.FunnelStat
import nintype.engine.pathscore.PredPath
import nintype.engine.pathscore.SlideVSArg
import nintype.engine.pathscore.SlideHistory
import nintype.engine.pathscore.Vecs
import nintype.engine.pathscore.StlSort
import nintype.engine.pathscore.Ctp
import nintype.engine.pathscore.PredPathTrie
import nintype.engine.pathscore.Visitation
import nintype.engine.pathscore.WeightedFloats
import nintype.engine.pathscore.determineSlideDiffs
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.VectorCase

/** Vector adapters for re/vectors/gen_pathscore*.py. Every adapter returns all case images
 * in `mem` so the generator's `unchanged` ranges are checked. */

/** Case world with the known vector fields linked: PredPath steps (0x108), PathSearch
 * touches (0x1bc). */
private fun VectorCase.pw(): MemWorld {
    val w = world()
    for ((name, img) in w.images) {
        if (img.seqs.containsKey(PredPath.OFF_STEPS) && name != "ps") Vecs.link(w, img, PredPath.OFF_STEPS)
        if (name == "ps" && img.seqs.containsKey(0x1bc)) Vecs.link(w, img, 0x1bc)
    }
    return w
}

private fun MemImage.pt(o: Int = 0) = Point2D(f32(o), f32(o + 4))
private fun MemImage.setPt(o: Int, p: Point2D) { setF32(o, p.x); setF32(o + 4, p.y) }

private fun reg(name: String, body: (VectorCase) -> CaseResult) = PortRegistry.register(name, body)

/** Port runs `body(world)`, returns its ret leaf (or none) and all images. */
private fun regW(name: String, body: (VectorCase, MemWorld) -> Leaf?) = reg(name) {
    val w = it.pw()
    val r = body(it, w)
    CaseResult(ret = r, mem = w.images)
}

private fun VectorCase.f(i: Int) = args[i].asFloat()
private fun VectorCase.i(i: Int) = args[i].asInt()
private fun VectorCase.b(i: Int) = args[i].asBool()

fun registerPathscorePorts() {
    registerPathscoreSortPorts()
    registerPathscoreTriePorts()
    val p = "Emkey::PredPath::"
    regW("${p}justVisited(Emkey::MooTouchPoint*)") { _, w -> Leaf.bool(PredPath.justVisited(w["this"], w["t0"])) }
    regW("${p}isChildOf(Emkey::PredPath*)") { _, w -> Leaf.bool(PredPath.isChildOf(w["this"], w["o"])) }
    regW("${p}operator<(Emkey::PredPath const&) const") { _, w -> Leaf.bool(PredPath.lessThan(w["this"], w["o"])) }
    regW("${p}pathsize()") { _, w -> Leaf.i32(PredPath.pathsize(w["this"])) }
    regW("${p}syncApostate()") { _, w -> PredPath.syncApostate(w["this"]); null }
    regW("${p}syncFutureCoverageHeuristics()") { _, w -> PredPath.syncFutureCoverageHeuristics(w["this"]); null }
    regW("${p}determineLanguageCompatibilityScore()") { _, w -> Leaf.f32(PredPath.determineLanguageCompatibilityScore(w["this"])) }
    regW("${p}syncCharMarkovMult()") { _, w -> PredPath.syncCharMarkovMult(w["this"]); null }
    regW("${p}traceVisiLog()") { _, w -> PredPath.traceVisiLog(w["this"]); null }
    regW("${p}handleGraphAreaTapRelease(TouchInfo const&, Point2D const&)") { _, _ -> Leaf.bool(PredPath.handleGraphAreaTapRelease()) }
    regW("${p}markFinisher(Emkey::TWRet)") { c, w -> PredPath.markFinisher(w["this"], c.i(0), c.i(1)); null }
    regW("${p}importantPointCount()") { _, w -> Leaf.i32(PredPath.importantPointCount(w["this"])) }
    regW("${p}pseudoImportantPointCount()") { _, w -> Leaf.i32(PredPath.pseudoImportantPointCount(w["this"])) }
    regW("${p}fing(int)") { c, w -> Leaf.i32(PredPath.fing(w["this"], c.i(0))) }
    regW("${p}otherfing(int)") { c, w -> Leaf.i32(PredPath.otherfing(w["this"], c.i(0))) }
    regW("${p}lastfing(int)") { c, w -> Leaf.i32(PredPath.lastfing(w["this"], c.i(0))) }
    regW("${p}lastotherfing(int)") { c, w -> Leaf.i32(PredPath.lastotherfing(w["this"], c.i(0))) }
    regW("${p}updateHeuristicsSubscore(float, float)") { c, w -> PredPath.updateHeuristicsSubscore(w["this"], c.f(0), c.f(1)); null }
    regW("${p}maybePrune(float, Emkey::FunnelStat&)") { c, w -> Leaf.bool(PredPath.maybePrune(w["this"], c.f(0), w["fs"])) }
    regW("${p}syncPointRevisitOn(Emkey::MooTouchPoint*)") { _, w -> PredPath.syncPointRevisitOn(w["this"], w["t0"]); null }
    regW("${p}syncPointRevisitMult()") { _, w -> PredPath.syncPointRevisitMult(w["this"]); null }
    regW("${p}hasVisited(Emkey::MooTouchPoint*)") { _, w -> Leaf.bool(PredPath.hasVisited(w["this"], w["t0"])) }
    regW("${p}syncClassicSlideVSCache()") { _, w -> PredPath.syncClassicSlideVSCache(w["this"]); null }
    regW("${p}syncNeoSlideVSCache()") { _, w -> PredPath.syncNeoSlideVSCache(w["this"]); null }
    regW("${p}registerSlideVsMult(Emkey::SlideVSArg&, bool, bool, float, float)") { c, w ->
        PredPath.registerSlideVsMult(w["this"], w["arg"], c.b(0), c.b(1), c.f(2), c.f(3)); null
    }

    regW("${p}multForCharSkip(int, float)") { c, w -> Leaf.f32(PredPath.multForCharSkip(w["this"], c.i(0), c.f(1))) }
    regW("${p}syncBalancedNovisitMult()") { _, w -> PredPath.syncBalancedNovisitMult(w["this"]); null }
    regW("${p}getBackwardsVisitationLookback(float)") { c, w -> Leaf.f32(PredPath.getBackwardsVisitationLookback(w["this"], c.f(0))) }
    regW("${p}determinePPSCKScoreMult()") { _, w -> Leaf.f32(PredPath.determinePPSCKScoreMult(w["this"])) }

    regW("${p}maybeRecordSlideVSPenalty(Emkey::Moopad*, Emkey::SlideVSArg&, float&)") { _, w ->
        PredPath.maybeRecordSlideVSPenalty(w["this"], w["mp"], w["arg"], w["hard"], 0); null
    }
    reg("libm::cosf") { CaseResult(ret = Leaf.f32(nintype.engine.pathscore.LibMTrig.cosf(it.f(0)))) }

    regW("${p}markVisited(Emkey::MooTouchPoint*, bool, Emkey::PredHistory*)") { c, w ->
        Visitation.markVisited(w["this"], w[c.note.removePrefix("tp = ")], c.b(0), w["hist"]); null
    }
    regW("${p}updateVisitation(double, int, Emkey::MooTouchPoint*, Emkey::PredHistory*)") { c, w ->
        Visitation.updateVisitation(w["this"], c.args[0].asDouble(), c.i(1)); null
    }
    regW("${p}updateVisitationComplete()") { _, w -> Visitation.updateVisitationComplete(w["this"]); null }
    regW("${p}syncSmoothVisitation(std::vector<float, std::allocator<float>>*)") { _, w ->
        val v = Visitation.syncSmoothVisitation(w["this"])
        w.images["outVec"]?.let { o -> o.seqs[0] = v.map { Leaf("f32", it.toRawBits().toLong() and 0xffffffffL).toJson() }.toMutableList() }
        null
    }

    fun hist(c: VectorCase, w: MemWorld): MemImage = c.note.removePrefix("h = ").let { n ->
        if (n.startsWith("steps[") && !w["this"].seqs.containsKey(PredPath.OFF_STEPS)) MemImage("unread") // never touched
        else if (n.startsWith("steps[")) Vecs.elems(w["this"], PredPath.OFF_STEPS)[n.removePrefix("steps[").removeSuffix("]").toInt()] else w[n]
    }
    regW("${p}syncLastTwoForSlideVS(Emkey::RecentSlideInfo&, Emkey::PredHistory&)") { c, w ->
        SlideHistory.syncLastTwoForSlideVS(w["this"], w["rsi"], hist(c, w)); null
    }
    regW("${p}maybeRecordHistoricNeoSlideVSFrom(Emkey::SlideVSTarget const&, Emkey::PredHistory&, Point2D const&, Emkey::RecentSlideInfo&, Emkey::Moopad*, double, float&)") { c, w ->
        Leaf.bool(SlideHistory.maybeRecordHistoricNeoSlideVSFrom(w["this"], w["target"], hist(c, w), w["pt"].pt(), w["mp"], w["hard"], 0))
    }

    regW("${p}calculateNotLongEnoughMultpen(int, bool)") { c, w -> Leaf.f32(PredPath.calculateNotLongEnoughMultpen(w["this"], c.i(0), c.b(1))) }
    regW("${p}calculateLastCharSameLetterSamePointPenalty()") { _, w -> Leaf.f32(PredPath.calculateLastCharSameLetterSamePointPenalty(w["this"])) }

    regW("${p}imbueWAWithPenaltyReasons(Emkey::WordAlternative&)") { _, w -> nintype.engine.pathscore.PredPathWA.imbueWAWithPenaltyReasons(w["this"], w["wa"]); null }
    regW("${p}properlyCountCoveredImportantPoints()") { _, w -> Leaf.i32(nintype.engine.pathscore.PredPathWA.properlyCountCoveredImportantPoints(w["this"])) }
    regW("${p}obtainNeoVisihist(int)") { c, w -> nintype.engine.pathscore.PredPathWA.obtainNeoVisihist(w["this"], c.i(0)); null }
    regW("Emkey::StepScoreStats::recSkipBranches(int)") { c, w -> nintype.engine.pathscore.StepStats.recSkipBranches(w["this"], c.i(0)); null }
    regW("Emkey::WordAlternative::addPenaltyReason(int, int)") { c, w -> nintype.engine.pathscore.WordAlternative.addPenaltyReason(w["this"], c.i(0), c.i(1)); null }
    regW("Emkey::WordAlternative::canWarnOnAltChoice() const") { _, w -> Leaf.bool(nintype.engine.pathscore.WordAlternative.canWarnOnAltChoice(w["this"])) }

    regW("${p}getFuturePlanarCoveragePoints(Emkey::MooTouchPoint*&, Emkey::MooTouchPoint*&)") { _, w ->
        val (r, a, b) = PredPath.getFuturePlanarCoveragePoints(w["this"])
        w["o1"].refs[0] = a; w["o2"].refs[0] = b
        Leaf.bool(r)
    }

    // FunnelStat
    val fsn = "Emkey::FunnelStat::"
    regW("${fsn}operator++(int)") { _, w -> FunnelStat.inc(w["this"], 0); null }
    regW("${fsn}operator++()") { _, w -> FunnelStat.inc(w["this"], 0); null }
    regW("${fsn}succ()") { _, w -> Leaf.i32(FunnelStat.succ(w["this"], 0)) }
    regW("${fsn}fail1()") { _, w -> Leaf.i32(FunnelStat.fail1(w["this"], 0)) }

    // WeightedFloats
    regW("WeightedFloats::WeightedFloats()") { _, w -> WeightedFloats.init(w["this"], 0); null }
    regW("WeightedFloats::add(float, float)") { c, w -> WeightedFloats.add(w["this"], 0, c.f(0), c.f(1)); null }
    regW("WeightedFloats::mean()") { _, w -> Leaf.f32(WeightedFloats.mean(w["this"], 0)) }
    regW("WeightedFloats::mean(float, float)") { c, w -> Leaf.f32(WeightedFloats.mean(w["this"], 0, c.f(0), c.f(1))) }

    // Flag64
    val fl = "Shing::Flag64::"
    regW("${fl}Flag64()") { _, w -> Flag64.init(w["this"], 0); null }
    regW("${fl}flag(int)") { c, w -> Flag64.flag(w["this"], 0, c.i(0)); null }
    regW("${fl}flag(int, bool)") { c, w -> Flag64.flag(w["this"], 0, c.i(0), c.b(1)); null }
    for (n in listOf("flagged(int) const", "isFlagged(int) const", "operator>>(int) const"))
        regW("$fl$n") { c, w -> Leaf.bool(Flag64.flagged(w["this"], 0, c.i(0))) }

    // SlideVSArg / FingerPathData / determineSlideDiffs
    regW("Emkey::SlideVSArg::SlideVSArg()") { _, w -> SlideVSArg.init(w["this"]); null }
    regW("Emkey::SlideVSArg::markKeyDiff(char, char, Point2D const&, Point2D const&)") { c, w ->
        SlideVSArg.markKeyDiff(w["this"], c.i(0) and 0xff, c.i(1) and 0xff, w["pa"].pt(), w["pb"].pt()); null
    }
    regW("Emkey::determineSlideDiffs(bool, bool, Point2D const&, bool, Point2D const&, Point2D const&, Point2D&, Point2D&)") { c, w ->
        val (a, b) = determineSlideDiffs(c.b(0), c.b(1), w["p0"].pt(), c.b(2), w["p1"].pt(), w["p2"].pt())
        w["outA"].setPt(0, a); w["outB"].setPt(0, b); null
    }
    regW("Emkey::FingerPathData::calcSlideDiff(Emkey::FingerPathData const&, Point2D const&, Emkey::SlideVSArg&) const") { _, w ->
        FingerPathData.calcSlideDiff(w["this"], 0, w["o"], 0, w["pt"].pt(), w["arg"]); null
    }
}

// --- trie-backed PredPath vectors (gen_pathscore_trie.py) ------------------------------------

private val englishTrie: nintype.engine.trie.CompactTrie by lazy { nintype.engine.trie.TrieDump.buildEnglish(java.io.File("../re/out/trie/english")) }

private fun jInt(j: nintype.engine.vectors.JsonValue) = Leaf.fromJson(j).asInt()

/** CombinedTriePath from the trie-area state JSON {main{path,curid,node,pos}, pnode, ppos, pdead, mdead}. */
private fun ctpOf(j: nintype.engine.vectors.JsonValue, pend: nintype.engine.vectors.JsonValue): nintype.engine.trie.CombinedTriePath {
    val o = j.asObject()
    val m = o["main"]!!.asObject()
    val pb = nintype.engine.vectors.Values.parseBytes(m["path"]!!.asObject()["v"]!!.asString())
    val ids = (0 until pb.size / 4).map { nintype.engine.trie.CompactTrie.le32(pb, 4 * it) }
    val main = nintype.engine.trie.CompactTriePath(englishTrie, ArrayList(ids), jInt(m["curid"]!!), jInt(m["node"]!!), jInt(m["pos"]!!))
    val po = pend.asObject()
    check(jInt(po["len"]!!) == 0)
    val root = nintype.engine.trie.PendingTrieNode().apply {
        valued = jInt(po["valued"]!!) != 0
        value.shortest = jInt(po["minDist"]!!).toByte().toInt()
    }
    return nintype.engine.trie.CombinedTriePath(main, if (jInt(o["pnode"]!!) != 0) root else null, jInt(o["ppos"]!!),
        jInt(o["pdead"]!!) and 0xff != 0, jInt(o["mdead"]!!) and 0xff != 0)
}

private fun ctpJson(p: nintype.engine.trie.CombinedTriePath): nintype.engine.vectors.JsonValue {
    val ids = p.main.path
    val b = ByteArray(4 * ids.size); for ((i, v) in ids.withIndex()) nintype.engine.trie.CompactTrie.setLe32(b, 4 * i, v)
    return nintype.engine.vectors.Values.struct(
        "main" to nintype.engine.vectors.Values.struct("path" to nintype.engine.vectors.Values.bytesLeaf(b), "curid" to Leaf.i32(p.main.curid).toJson(),
            "node" to Leaf.i32(p.main.node).toJson(), "pos" to Leaf.i32(p.main.pos).toJson()),
        "pnode" to Leaf.i32(if (p.pnode != null) 1 else 0).toJson(), "ppos" to Leaf.i32(p.ppos).toJson(),
        "pdead" to Leaf("u8", if (p.pendDead) 1L else 0L).toJson(), "mdead" to Leaf("u8", if (p.mainDead) 1L else 0L).toJson())
}

private const val CTP_KEY = "@0x14c:ctp"

/** A trie-backed PredPath port: attaches the cursor, runs, and serializes `this` (image fields
 * + the cursor state) itself when the vector expects an out.this. */
private fun regT(name: String, body: (VectorCase, MemWorld) -> Leaf?) = PortRegistry.register(name) { c ->
    val w = c.pw()
    val pp = w["this"]
    val cj = c.inputs["this"]!!.asObject()[CTP_KEY]
    // an unread cursor (not in the vector): attach a root cursor, its state is never consulted
    Ctp.attach(pp, if (cj != null) ctpOf(cj, c.inputs["pend"]!!) else
        nintype.engine.trie.CombinedTriePath(nintype.engine.trie.CompactTriePath(englishTrie), null))
    val r = body(c, w)
    val outs = LinkedHashMap<String, nintype.engine.vectors.JsonValue>()
    c.outputs["this"]?.let { tmpl ->
        val o = tmpl.asObject()
        val rest = nintype.engine.vectors.JsonValue.JObject(o.filterKeys { it != CTP_KEY })
        val enc = LinkedHashMap(pp.encodeLike(rest).asObject())
        if (o.containsKey(CTP_KEY)) enc[CTP_KEY] = ctpJson(Ctp.of(pp))
        outs["this"] = nintype.engine.vectors.JsonValue.JObject(enc)
    }
    CaseResult(ret = r, out = outs, mem = w.images)
}

fun registerPathscoreTriePorts() {
    val p = "Emkey::PredPath::"
    regT("${p}canProgress()") { _, w -> Leaf.bool(PredPathTrie.canProgress(w["this"])) }
    regT("${p}canFinish()") { _, w -> Leaf.bool(PredPathTrie.canFinish(w["this"])) }
    regT("${p}getWordLength()") { _, w -> Leaf.i32(PredPathTrie.getWordLength(w["this"])) }
    regT("${p}advance(char)") { c, w -> PredPathTrie.advance(w["this"], c.i(0)); null }
    regT("${p}advanceSkipping(char, char)") { c, w -> PredPathTrie.advanceSkipping(w["this"], c.i(0), c.i(1)); null }
    regT("${p}advanceApostrophied(char)") { c, w -> PredPathTrie.advanceApostrophied(w["this"], c.i(0)); null }
    regT("${p}advanceAposPossessive(char)") { c, w -> PredPathTrie.advanceAposPossessive(w["this"], c.i(0)); null }
    regT("${p}multForCapTap()") { c, w ->
        val pp = w["this"]
        Ctp.attachTrie(pp, englishTrie)
        c.inputs["twflags"]?.let { j ->
            val id = pp.i32(0x20)
            val b = englishTrie.trieWords.data(id).copyOfRange(englishTrie.trieWords.off(id), englishTrie.trieWords.off(id) + 16)
            nintype.engine.trie.CompactTrie.setLe32(b, 0, jInt(j.asObject()["@0x0:flags"]!!))
            Ctp.overrideFinisherWord(pp, nintype.engine.trie.TrieWord(b))
        }
        Leaf.f32(PredPathTrie.multForCapTap(pp))
    }
    PortRegistry.register("${p}gatherFinisherTrieWords(std::vector<Emkey::TWRet, std::allocator<Emkey::TWRet>>&)") { c ->
        val w = c.pw()
        val pp = w["this"]
        val cj = c.inputs["this"]!!.asObject()[CTP_KEY]
        Ctp.attach(pp, if (cj != null) ctpOf(cj, c.inputs["pend"]!!) else
            nintype.engine.trie.CombinedTriePath(nintype.engine.trie.CompactTriePath(englishTrie), null))
        val ids = ArrayList<Int>()
        PredPathTrie.gatherFinisherTrieWords(pp, ids)
        val b = ByteArray(4 * ids.size); for ((i, v) in ids.withIndex()) nintype.engine.trie.CompactTrie.setLe32(b, 4 * i, v)
        CaseResult(out = mapOf("ids" to nintype.engine.vectors.Values.bytesLeaf(b)), mem = w.images)
    }
    PortRegistry.register("${p}canAdvanceEx(Emkey::BranchmentData&)") { c ->
        val w = c.pw()
        val pp = w["this"]; val ps = w["ps"]
        val cj = c.inputs["this"]!!.asObject()[CTP_KEY]
        Ctp.attach(pp, if (cj != null) ctpOf(cj, c.inputs["pend"]!!) else
            nintype.engine.trie.CombinedTriePath(nintype.engine.trie.CompactTriePath(englishTrie), null))
        val stats = if (ps.seqs.containsKey(0xea8)) Vecs.link(w, ps, 0xea8) else ArrayList()
        val skips = ArrayList<Int>()
        val r = PredPathTrie.canAdvanceEx(pp, w["bd"], skips, nintype.engine.search.SkipCheckerCaches(), stats)
        c.outputs["ps"]?.asObject()?.get("@0xea8:stepStats")?.let { t ->
            val tl = t.asObject()["\$vec"]!!.asArray()
            ps.seqs[0xea8] = stats.mapIndexed { k, img -> img.encodeLike(tl[k]) }.toMutableList()
        }
        val sb = ByteArray(skips.size) { skips[it].toByte() }
        CaseResult(ret = Leaf.bool(r), out = mapOf("skips" to nintype.engine.vectors.Values.bytesLeaf(sb)), mem = w.images)
    }
    regT("${p}determineUnicodeCompatibilityScore()") { c, w ->
        val pp = w["this"]
        Ctp.attachTrie(pp, englishTrie)
        c.inputs["twflags"]?.let { j ->
            val id = pp.i32(0x20)
            val b = englishTrie.trieWords.data(id).copyOfRange(englishTrie.trieWords.off(id), englishTrie.trieWords.off(id) + 16)
            nintype.engine.trie.CompactTrie.setLe32(b, 0, jInt(j.asObject()["@0x0:flags"]!!))
            Ctp.overrideFinisherWord(pp, nintype.engine.trie.TrieWord(b))
        }
        val board = c.inputs["board"]!!.asObject()["@0x0:keys"]!!.asObject()["\$vec"]!!.asArray().map { jInt(it) }.toSet()
        Leaf.f32(nintype.engine.pathscore.Unicompat.determineUnicodeCompatibilityScore(pp, w["mp"], board))
    }
    regT("${p}determineFinalScore()") { c, w ->
        val pp = w["this"]
        Ctp.attachTrie(pp, englishTrie)
        c.inputs["twflags"]?.let { j ->
            val id = pp.i32(0x20)
            val b = englishTrie.trieWords.data(id).copyOfRange(englishTrie.trieWords.off(id), englishTrie.trieWords.off(id) + 16)
            nintype.engine.trie.CompactTrie.setLe32(b, 0, jInt(j.asObject()["@0x0:flags"]!!))
            Ctp.overrideFinisherWord(pp, nintype.engine.trie.TrieWord(b))
        }
        val board = c.inputs["board"]!!.asObject()["@0x0:keys"]!!.asObject()["\$vec"]!!.asArray().map { jInt(it) }.toSet()
        val chain = HashMap<String, Float>()
        for (e in c.inputs["chain"]!!.asObject()["@0x0:scores"]!!.asObject()["\$map"]!!.asArray()) {
            val o = e.asObject()
            chain[String(nintype.engine.vectors.Values.parseBytes(o["first"]!!.asObject()["v"]!!.asString()), Charsets.ISO_8859_1)] = Leaf.fromJson(o["second"]!!).asFloat()
        }
        nintype.engine.pathscore.FinalScore.determineFinalScore(pp, board, chain)
        null
    }
    PortRegistry.register("${p}PredPath(Emkey::PathSearch*)") { c ->
        val w = c.pw()
        val pp = w["this"]; val ps = w["ps"]
        val po = c.inputs["pend"]!!.asObject()
        val root = nintype.engine.trie.PendingTrieNode().apply {
            valued = jInt(po["valued"]!!) != 0
            value.shortest = jInt(po["minDist"]!!).toByte().toInt()
        }
        nintype.engine.pathscore.PredPathCtor.construct(pp, ps, englishTrie, root,
            jInt(c.inputs["ctpCurid"]!!.asObject()["@0x0:v"]!!))
        val tmpl = c.outputs["this"]!!.asObject()
        val enc = LinkedHashMap(pp.encodeLike(nintype.engine.vectors.JsonValue.JObject(tmpl.filterKeys { it != CTP_KEY })).asObject())
        enc[CTP_KEY] = ctpJson(Ctp.of(pp))
        CaseResult(out = mapOf("this" to nintype.engine.vectors.JsonValue.JObject(enc)), mem = w.images)
    }
    PortRegistry.register("${p}PredPath(Emkey::PredPath*)") { c ->
        val w = c.pw()
        val pp = w["this"]; val par = w["parent"]
        Ctp.attach(par, ctpOf(c.inputs["parent"]!!.asObject()[CTP_KEY]!!, c.inputs["pend"]!!))
        nintype.engine.pathscore.PredPathCtor.copyConstruct(pp, par)
        val tmpl = c.outputs["this"]!!.asObject()
        val rest = tmpl.filterKeys { it != CTP_KEY && it != "@0x108:steps" }
        val enc = LinkedHashMap(pp.encodeLike(nintype.engine.vectors.JsonValue.JObject(rest)).asObject())
        enc[CTP_KEY] = ctpJson(Ctp.of(pp))
        val et = tmpl["@0x108:steps"]!!.asObject()["\$vec"]!!.asArray()
        val elems = Vecs.elems(pp, PredPath.OFF_STEPS)
        enc["@0x108:steps"] = nintype.engine.vectors.Values.vec(elems.mapIndexed { k, img -> img.encodeLike(et[k]) })
        CaseResult(out = mapOf("this" to nintype.engine.vectors.JsonValue.JObject(enc)), mem = w.images)
    }
    PortRegistry.register("${p}getProperWord()") { c ->
        val w = c.pw()
        val pp = w["this"]
        c.inputs["this"]!!.asObject()[CTP_KEY]?.let { Ctp.attach(pp, ctpOf(it, c.inputs["pend"]!!)) }
        Ctp.attachTrie(pp, englishTrie)
        c.inputs["twflags"]?.let { j ->
            val id = pp.i32(0x20)
            val b = englishTrie.trieWords.data(id).copyOfRange(englishTrie.trieWords.off(id), englishTrie.trieWords.off(id) + 16)
            nintype.engine.trie.CompactTrie.setLe32(b, 0, jInt(j.asObject()["@0x0:flags"]!!))
            Ctp.overrideFinisherWord(pp, nintype.engine.trie.TrieWord(b))
        }
        CaseResult(retValue = nintype.engine.vectors.Values.bytesLeaf(PredPathTrie.getProperWord(pp)), mem = w.images)
    }
    PortRegistry.register("${p}getAsWA()") { c ->
        val w = c.pw()
        val pp = w["this"]; val wa = w["wa"]
        c.inputs["this"]!!.asObject()[CTP_KEY]?.let { Ctp.attach(pp, ctpOf(it, c.inputs["pend"]!!)) }
        Ctp.attachTrie(pp, englishTrie)
        c.inputs["twflags"]?.let { j ->
            val id = pp.i32(0x20)
            val b = englishTrie.trieWords.data(id).copyOfRange(englishTrie.trieWords.off(id), englishTrie.trieWords.off(id) + 16)
            nintype.engine.trie.CompactTrie.setLe32(b, 0, jInt(j.asObject()["@0x0:flags"]!!))
            Ctp.overrideFinisherWord(pp, nintype.engine.trie.TrieWord(b))
        }
        PredPathTrie.getAsWA(pp, wa)
        val strs = nintype.engine.vectors.JsonValue.JObject(linkedMapOf(*intArrayOf(0, 4, 8, 0xc, 0x4c).map {
            "@0x${it.toString(16)}" to nintype.engine.vectors.Values.bytesLeaf(nintype.engine.pathscore.Strs.get(wa, it)) }.toTypedArray()))
        CaseResult(out = mapOf("waStr" to strs), mem = w.images)
    }
    PortRegistry.register("${p}result()") { c ->
        val w = c.pw()
        val pp = w["this"]; val pr = w["pr"]
        c.inputs["this"]!!.asObject()[CTP_KEY]?.let { Ctp.attach(pp, ctpOf(it, c.inputs["pend"]!!)) }
        Ctp.attachTrie(pp, englishTrie)
        c.inputs["twflags"]?.let { j ->
            val id = pp.i32(0x20)
            val b = englishTrie.trieWords.data(id).copyOfRange(englishTrie.trieWords.off(id), englishTrie.trieWords.off(id) + 16)
            nintype.engine.trie.CompactTrie.setLe32(b, 0, jInt(j.asObject()["@0x0:flags"]!!))
            Ctp.overrideFinisherWord(pp, nintype.engine.trie.TrieWord(b))
        }
        // the embedded WordAlternative starts from the block's bytes (the ctor keeps some bits)
        val wa = MemImage("pr.wa", 0x50)
        for (i in 0 until 0x50) if (pr.isKnown(0xc + i)) wa.load(i, byteArrayOf(pr.byteAt(0xc + i)))
        PredPathTrie.result(pp, pr, wa, jInt(c.inputs["psTouches"]!!.asObject()["@0x0:count"]!!))
        for (i in 0 until 0x50) if (wa.isWritten(i)) pr.writeBytes(0xc + i, byteArrayOf(wa.byteAt(i)))
        val S = nintype.engine.pathscore.Strs
        val strs = linkedMapOf<String, nintype.engine.vectors.JsonValue>(
            "@0x0" to nintype.engine.vectors.Values.bytesLeaf(S.get(pr, 0)), "@0x78" to nintype.engine.vectors.Values.bytesLeaf(S.get(pr, 0x78)))
        for (o in intArrayOf(0, 4, 8, 0xc, 0x4c)) strs["@0x${(0xc + o).toString(16)}"] = nintype.engine.vectors.Values.bytesLeaf(S.get(wa, o))
        val alts = Vecs.elems(pr, 0x5c).map { a ->
            nintype.engine.vectors.Values.struct("fields" to nintype.engine.vectors.Values.bytesLeaf(a.readBytes(0x10, 0x38)),
                "strings" to nintype.engine.vectors.JsonValue.JObject(linkedMapOf(*intArrayOf(0, 4, 8, 0xc, 0x4c).map {
                    "@0x${it.toString(16)}" to nintype.engine.vectors.Values.bytesLeaf(S.get(a, it)) }.toTypedArray())),
                "debugPath" to Leaf("u32", if (a.refs[0x48] != null) 1L else 0L).toJson())
        }
        CaseResult(out = mapOf("prStr" to nintype.engine.vectors.JsonValue.JObject(strs),
            "alternatives" to nintype.engine.vectors.Values.struct("list" to nintype.engine.vectors.JsonValue.JArray(alts))), mem = w.images)
    }
    regW("${p}syncRouteCoverageHeuristics()") { c, w ->
        val v = Leaf.fromJson(c.inputs["coverage"]!!.asObject()["@0x0:v"]!!).asFloat()
        PredPathTrie.syncRouteCoverageHeuristics(w["this"]) { v }; null
    }
    regT("${p}maximumPathLength()") { _, w -> Leaf.i32(PredPathTrie.maximumPathLength(w["this"])) }
    regT("${p}syncNotLongEnoughMultpen()") { _, w -> PredPathTrie.syncNotLongEnoughMultpen(w["this"]); null }
    regT("${p}syncSkipScoreMultForHeuristics()") { _, w -> PredPathTrie.syncSkipScoreMultForHeuristics(w["this"]); null }
    regT("${p}determineScoreForHeuristics()") { _, w -> PredPathTrie.determineScoreForHeuristics(w["this"]); null }
    PortRegistry.register("${p}getWord()") { c ->
        val w = c.pw()
        val pp = w["this"]
        c.inputs["this"]!!.asObject()[CTP_KEY]?.let { Ctp.attach(pp, ctpOf(it, c.inputs["pend"]!!)) }
        Ctp.attachTrie(pp, englishTrie)
        CaseResult(retValue = nintype.engine.vectors.Values.bytesLeaf(PredPathTrie.getWord(pp)), mem = w.images)
    }
}

// --- unnamed std::sort instantiations (re/vectors/gen_pathscore.py gen_stlsort) ----------------

private class SortSpec(val esz: Int?, val ko: Int, val f64: Boolean, val gt: Boolean)

private val SORT_SPECS = mapOf(
    "A" to SortSpec(32, 0x1c, false, true), "B" to SortSpec(32, 0x8, true, false), "C" to SortSpec(12, 0x0, false, true),
    "D" to SortSpec(null, 0xf8, true, false), "E" to SortSpec(null, 0x128, true, false), "F" to SortSpec(null, 0xf8, true, false),
    "G" to SortSpec(32, 0x10, false, false), "H" to SortSpec(null, 0x74, false, false))

/** FUN name -> (family, piece). */
private val SORT_FUNS = mapOf(
    "FUN_0080f5c0" to ("A" to "intro"), "FUN_0080fef0" to ("A" to "ins"), "FUN_008100f4" to ("A" to "lin"),
    "FUN_00810d88" to ("B" to "intro"), "FUN_00812030" to ("C" to "intro"), "FUN_008127c8" to ("D" to "intro"),
    "FUN_00812f6c" to ("E" to "intro"), "FUN_00813bec" to ("F" to "intro"), "FUN_009840cc" to ("G" to "intro"),
    "FUN_0080acf0" to ("H" to "intro"), "FUN_0080b198" to ("H" to "ins"))

private fun keyLess(sp: SortSpec): (Float, Float) -> Boolean = if (sp.gt) nintype.engine.SoftFloat::fcmpgt else nintype.engine.SoftFloat::fcmplt
private fun keyLessD(sp: SortSpec): (Double, Double) -> Boolean = if (sp.gt) nintype.engine.SoftFloat::dcmpgt else nintype.engine.SoftFloat::dcmplt

fun registerPathscoreSortPorts() {
    for ((fn, fp) in SORT_FUNS) PortRegistry.register(fn) { c ->
        val sp = SORT_SPECS[fp.first]!!
        val w = c.world()
        val arr = w["arr"]
        val n = c.note.removePrefix("n=").toInt()
        fun run(list: MutableList<Any>, less: (Any, Any) -> Boolean) {
            when (fp.second) {
                "intro" -> StlSort.introsortLoop(list, 0, n, c.args[0].asInt(), less)
                "ins" -> StlSort.insertionSort(list, 0, n, less)
                else -> StlSort.unguardedLinearInsert(list, n - 1, less)
            }
        }
        if (sp.esz == null) {
            // elements the original never read (a no-op introsort on <= 16) stay opaque placeholders
            val list: MutableList<Any> = (0 until n).map { arr.ref(4 * it) ?: Any() }.toMutableList()
            val orig = list.toList()
            val fl = keyLess(sp); val dl = keyLessD(sp)
            run(list) { x, y -> x as MemImage; y as MemImage
                if (sp.f64) dl(x.f64(sp.ko), y.f64(sp.ko)) else fl(x.f32(sp.ko), y.f32(sp.ko)) }
            for (k in 0 until n) if (orig[k] !== list[k]) arr.refs[4 * k] = list[k] as MemImage
        } else {
            val es = sp.esz
            val list: MutableList<Any> = (0 until n).map { arr.readBytes(es * it, es) as Any }.toMutableList()
            fun key(b: ByteArray): Long { var v = 0L; for (i in (if (sp.f64) 7 else 3) downTo 0) v = (v shl 8) or (b[sp.ko + i].toLong() and 0xff); return v }
            val fl = keyLess(sp); val dl = keyLessD(sp)
            run(list) { x, y -> x as ByteArray; y as ByteArray
                if (sp.f64) dl(Double.fromBits(key(x)), Double.fromBits(key(y))) else fl(Float.fromBits(key(x).toInt()), Float.fromBits(key(y).toInt())) }
            val orig = (0 until n).map { arr.readBytes(es * it, es) }
            for (k in 0 until n) if (!(list[k] as ByteArray).contentEquals(orig[k])) arr.writeBytes(es * k, list[k] as ByteArray)
        }
        CaseResult(mem = w.images)
    }
}
