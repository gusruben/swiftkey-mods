package nintype.engine.ports

import nintype.engine.merge.ACModel
import nintype.engine.merge.ACSlot
import nintype.engine.merge.ACWord
import nintype.engine.merge.AutospaceModule
import nintype.engine.merge.AutospaceSplitoffStats
import nintype.engine.merge.MooMergeBuffer
import nintype.engine.merge.PendingWord
import nintype.engine.merge.SplitoffStats
import nintype.engine.merge.TimedAutospaceStats
import nintype.engine.merge.Xnl
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.VectorCase
import nintype.engine.vectors.Values

/** Vector adapters for re/vectors/gen_merge*.py. */

private fun VectorCase.img(name: String) = MemImage.decode(inputs[name]!!, name)
private fun reg(name: String, body: (VectorCase) -> CaseResult) = PortRegistry.register(name, body)
private fun regF(name: String, body: (VectorCase) -> Float) = reg(name) { CaseResult(ret = Leaf.f32(body(it))) }
private fun u8(v: Int) = Leaf("u8", v.toLong() and 0xff)

/** Element images of a `$vec` of offset-keyed structs at [off]; written back by [vecBack]. */
private fun elems(img: MemImage, off: Int): List<MemImage> = img.seq(off).map { MemImage.decode(it) }
private fun vecBack(img: MemImage, off: Int, els: List<MemImage>) {
    val tmpl = img.seq(off).toList()
    img.seqs[off] = els.mapIndexed { i, e -> e.encodeLike(tmpl[i]) }.toMutableList()
}

/** PathSearch touch vector (`$vec` of Mootouch structs, each with a `@0x244` points `$vec`). */
private fun touches(img: MemImage, off: Int): List<TimedAutospaceStats.Touch> = img.seq(off).map { el ->
    val n = el.asObject().entries.firstOrNull { it.key.startsWith("@0x244") }?.value?.asObject()?.get("\$vec")?.asArray()?.size ?: 0
    TimedAutospaceStats.Touch(MemImage.decode(el), n)
}

private fun gapsJson(st: SplitoffStats): JsonValue = JsonValue.JObject(mapOf("\$map" to JsonValue.JArray(
    st.gaps.map { (k, v) -> JsonValue.JObject(linkedMapOf("first" to Leaf.i32(k).toJson(), "second" to Leaf.f32(v).toJson())) })))

/** Replays recorded Mootouch::isSimpleTap results (in.calls) in call order, checking which
 * touch each call was for (node index in the sitting list, -1 = the mt argument). */
private class TapReplay(c: VectorCase) {
    private val calls = c.inputs["calls"]?.asArray() ?: emptyList()
    private var i = 0
    fun next(node: Int): Int {
        if (i >= calls.size) error("unexpected isSimpleTap call #$i (node $node)")
        val o = calls[i++].asObject()
        val want = (o["node"] as JsonValue.JNumber).value.toInt()
        if (want != node) error("isSimpleTap call #${i - 1}: expected node $want, port asked for $node")
        return Leaf.fromJson(o["ret"]!!).asInt()
    }
    fun done() { if (i != calls.size) error("port made $i isSimpleTap calls, original made ${calls.size}") }
}

private fun sitting(mp: MemImage, r: TapReplay): List<AutospaceModule.Sitting> =
    mp.seq(0x294c4).mapIndexed { k, el -> val n = MemImage.decode(el); AutospaceModule.Sitting(n.u8(0x276), n.i32(0xc4)) { r.next(k) } }

private fun VectorCase.pt(name: String) = MemImage.decode(inputs[name]!!).let { nintype.engine.math.Point2D(it.f32(0), it.f32(4)) }

fun registerMergePorts() {
    reg("Xnl::val() const") { CaseResult(ret = u8(Xnl.val_(it.img("this"), 0))) }

    // AutospaceSplitoffStats
    val ass = "Emkey::AutospaceSplitoffStats::"
    reg("${ass}shouldSplit()") { CaseResult(ret = Leaf.bool(AutospaceSplitoffStats.shouldSplit(it.img("this")))) }
    reg("${ass}AutospaceSplitoffStats()") {
        val s = it.img("this"); AutospaceSplitoffStats.construct(s); CaseResult(mem = mapOf("this" to s))
    }

    // PendingWord
    regF("Emkey::PendingWord::getScore()") { PendingWord.getScore() }
    reg("Emkey::PendingWord::hasHangul()") { CaseResult(ret = u8(PendingWord.hasHangul(it.img("this")))) }
    reg("Emkey::PendingWord::hasNonBasic()") { CaseResult(ret = u8(PendingWord.hasNonBasic(it.img("this")))) }

    // AutospaceModule
    val am = "Emkey::AutospaceModule::"
    regF("${am}staticAutospaceTimedMergeTime()") { AutospaceModule.staticAutospaceTimedMergeTime(it.img("this")) }
    regF("${am}dynamicAutospaceTimedMergeTime()") { AutospaceModule.dynamicAutospaceTimedMergeTime(it.img("this")) }
    regF("${am}absoluteAutospaceTimeLimit()") { AutospaceModule.absoluteAutospaceTimeLimit(it.img("this")) }
    regF("${am}touchStayTime()") { AutospaceModule.touchStayTime(it.img("this")) }
    regF("${am}calculateTimingReductionBetweenTouches(float, int)") {
        AutospaceModule.calculateTimingReductionBetweenTouches(it.img("this"), it.args[0].asFloat(), it.args[1].asInt())
    }
    reg("${am}onDetectEarlySplit(float)") {
        val a = it.img("this"); AutospaceModule.onDetectEarlySplit(a, it.args[0].asFloat()); CaseResult(mem = mapOf("this" to a))
    }
    reg("${am}peckLock(std::string const&)") { val a = it.img("this"); AutospaceModule.peckLock(a); CaseResult(mem = mapOf("this" to a)) }
    reg("${am}onNonBufferBackspace()") { val a = it.img("this"); AutospaceModule.clearPeckLock(a); CaseResult(mem = mapOf("this" to a)) }
    reg("${am}onJustAfterUpload()") { val a = it.img("this"); AutospaceModule.clearPeckLock(a); CaseResult(mem = mapOf("this" to a)) }
    regF("${am}timeUntilAutospaceMerge()") {
        AutospaceModule.timeUntilAutospaceMerge(it.img("this"), it.img("globals").f64(0))
    }

    // MooMergeBuffer
    val mmb = "Emkey::MooMergeBuffer::"
    reg("${mmb}MooMergeBuffer()") { val b = it.img("this"); MooMergeBuffer.construct(b); CaseResult(mem = mapOf("this" to b)) }
    reg("${mmb}init(int)") { val b = it.img("this"); MooMergeBuffer.init(b, it.args[0].asInt()); CaseResult(mem = mapOf("this" to b)) }
    reg("${mmb}markSweepMaster(int)") { val b = it.img("this"); MooMergeBuffer.markSweepMaster(b, it.args[0].asInt()); CaseResult(mem = mapOf("this" to b)) }
    reg("${mmb}markSweeped(int&)") {
        val b = it.img("this"); val cnt = it.img("count"); val box = intArrayOf(cnt.i32(0))
        MooMergeBuffer.markSweeped(b, box); cnt.setI32(0, box[0])
        CaseResult(mem = mapOf("this" to b, "count" to cnt))
    }
    reg("${mmb}clearSweepFlags()") {
        val b = it.img("this"); val rs = elems(b, MooMergeBuffer.RESULTS)
        MooMergeBuffer.clearSweepFlags(b, rs); vecBack(b, MooMergeBuffer.RESULTS, rs)
        CaseResult(mem = mapOf("this" to b))
    }

    // timed-autospace split statistics
    reg("Emkey::PathSearch::getStatsForTimedAutospace()") {
        val ps = it.img("this")
        val st = TimedAutospaceStats.getStatsForTimedAutospace(touches(ps, 0x1a0), ps.ptr(0x110)!!)
        CaseResult(retValue = Values.struct(
            "@0x0:gaps" to gapsJson(st), "@0x14:count" to Leaf.u32(st.gaps.size).toJson(),
            "@0x18:splitTouch" to Leaf.i32(st.splitTouch).toJson(), "@0x1c:maxGap" to Leaf.f32(st.maxGap).toJson(),
            "@0x20:dist" to Leaf.f32(st.dist).toJson(), "@0x24:npts" to Leaf.i32(st.npts).toJson()))
    }
    reg("${am}syncAutospaceStatsPostSearch()") {
        val a = it.img("this"); val mp = a.ptr(0)!!
        val flags = mp.seq(0x294c4).map { el -> MemImage.decode(el).u8(0x26e) }
        val st = TimedAutospaceStats.syncAutospaceStatsPostSearch(a, touches(mp, 0x29dc0 + 0x1a0), flags)
        TimedAutospaceStats.writeScalars(a, AutospaceModule.SPLITOFF, st)
        a.seqs[AutospaceModule.SPLITOFF] = gapsJson(st).asObject()["\$map"]!!.asArray().toMutableList()
        CaseResult(mem = mapOf("this" to a))
    }

    // automerge family: real touches (in.g present, gen_merge_automerge.py) or replayed isSimpleTap (in.calls)
    fun boolRet(v: Int) = CaseResult(ret = Leaf.bool(v != 0))
    class Fam(val am: MemImage, val mp: MemImage, val sitting: List<AutospaceModule.Sitting>, val mtTap: () -> Int, val mtX: Int, val done: () -> Unit)
    fun fam(c: VectorCase): Fam {
        val w = c.world()
        val a = w["this"]; val mp = a.ptr(0) ?: a.ref(0)!!
        if (c.inputs.containsKey("g")) {
            val g = MemImage.decode(c.inputs["g"]!!)
            val env = nintype.engine.touch.TouchEnv(explicitModel, mp, g.f64(0), g.f32(8))
            val sit = mp.seq(0x294c4).map { AutospaceModule.Sitting.of(nintype.engine.touch.MootouchCodec.mootouchFromJson(it), env) }
            val mt = c.inputs["mt"]?.let { nintype.engine.touch.MootouchCodec.mootouchFromJson(it) }
            return Fam(a, mp, sit, { if (mt!!.isSimpleTap(env)) 1 else 0 }, mt?.body?.i32(0xbc) ?: 0, {})
        }
        val r = TapReplay(c)
        val mtX = c.inputs["mt"]?.let { MemImage.decode(it).i32(0xbc) } ?: 0
        return Fam(a, mp, sitting(mp, r), { r.next(-1) }, mtX, { r.done() })
    }
    fun pos(c: VectorCase) = MemImage.decode(c.inputs["pos"]!!).let { nintype.engine.math.Point2D(it.f32(0), it.f32(4)) }
    reg("${am}automergeAllowanceCheck(double, Point2D const&, int, bool, int)") {
        val f = fam(it)
        val v = AutospaceModule.automergeAllowanceCheck(f.am, it.args[0].asDouble(), pos(it), it.args[1].asInt(), it.args[2].asInt(), it.args[3].asInt(), f.sitting)
        f.done(); boolRet(v)
    }
    reg("${am}newTouchShouldAutomerge(Emkey::Mootouch&, double, Point2D const&, int)") {
        val f = fam(it)
        val v = AutospaceModule.newTouchShouldAutomerge(f.am, f.mtX, f.mtTap, it.args[0].asDouble(), pos(it), it.args[1].asInt(), f.sitting)
        f.done(); boolRet(v)
    }
    reg("${am}checkSittingTapness()") { val f = fam(it); val v = AutospaceModule.checkSittingTapness(f.sitting); f.done(); boolRet(if (v) 1 else 0) }
    reg("${am}newTapIsProbablyForSlowPecking(bool, int)") {
        val f = fam(it); val v = AutospaceModule.newTapIsProbablyForSlowPecking(f.sitting, it.args[0].asBool(), it.args[1].asInt()); f.done(); boolRet(if (v) 1 else 0)
    }
    reg("${am}newTouchIsProbablyForSlowPecking(Emkey::Mootouch&)") {
        val f = fam(it); val v = AutospaceModule.newTouchIsProbablyForSlowPecking(f.sitting, f.mtX, f.mtTap); f.done(); boolRet(v)
    }
    reg("${am}newPressShouldAutomerge(double, Point2D const&, int, int)") {
        val f = fam(it); val w = it.world(); val geo = GeoWorld(w)
        val bi = w["board"]
        val v = AutospaceModule.newPressShouldAutomerge(w["this"], it.args[0].asDouble(), pos(it), it.args[1].asInt(), it.args[2].asInt(),
            geo.board("board"), bi.ref(0xfc4)?.let { geo.key(it) }, bi.ref(0xfac)?.let { geo.key(it) }, f.sitting)
        f.done(); boolRet(v)
    }

    // ChasingFloat speed limit + the per-frame timer
    reg("ChasingFloat::chaseByFractionWithSpeedLimit(float, float, float)") {
        val cf = it.img("this")
        nintype.engine.merge.ChasingFloatSpeedLimit.chase(cf, 0, it.args[0].asFloat(), it.args[1].asFloat(), it.args[2].asFloat())
        CaseResult(mem = mapOf("this" to cf))
    }
    reg("${am}updateAutospaceTimedMerge()") {
        val a = it.img("this"); val g = it.img("globals")
        val eff = MemImage("effects", 2)
        var fin = 0; var tmp = 0
        AutospaceModule.updateAutospaceTimedMerge(a, g.f64(0), g.f64(8), a.ptr(0)!!.seq(0x294c4).size,
            object : AutospaceModule.TimedMergeHost {
                override fun maybeFinishWord() { fin++ }
                override fun turnOnTempAutospace() { tmp++ }
            })
        eff.setU8(0, fin); eff.setU8(1, tmp)
        CaseResult(mem = mapOf("this" to a, "effects" to eff))
    }

    // MooMergeBuffer result bookkeeping
    fun orderOf(b: MemImage) = b.seq(0x1c).map { el ->
        val o = el.asObject(); Pair(Leaf.fromJson(o["first"]!!).asFloat(), Leaf.fromJson(o["second"]!!).asInt())
    }.toMutableList()
    fun containers(b: MemImage) = MooMergeBuffer.Containers(b.seq(0).toMutableList(), b.seq(0xc).toMutableList(), orderOf(b))
    fun writeBack(b: MemImage, c: MooMergeBuffer.Containers<JsonValue, JsonValue>) {
        b.seqs[0] = c.results.toMutableList(); b.seqs[0xc] = c.acs.toMutableList()
        b.seqs[0x1c] = c.order.map { (k, v) -> JsonValue.JObject(linkedMapOf("first" to Leaf.f32(k).toJson(), "second" to Leaf.u32(v).toJson())) as JsonValue }.toMutableList()
    }
    fun one(name: String, v: Leaf): MemImage = MemImage(name, 8).also { m -> m.writeLE(0, Leaf.byteWidth(v.type), v.bits) }
    fun f64Out(d: Double): JsonValue = Values.struct("@0x0" to Leaf.f64(d).toJson())
    reg("${mmb}getSweepChoice(int&, double*, Emkey::WordAlternative*, std::vector<Emkey::WordAlternative, std::allocator<Emkey::WordAlternative>>*, std::vector<Emkey::PathResult, std::allocator<Emkey::PathResult>>*, Emkey::ACCollectage*, bool*)") {
        val b = it.img("this"); val rs = elems(b, 0)
        val idx = it.img("idx"); val t = it.img("t"); val last = it.img("isLast")
        val i = MooMergeBuffer.sweepChoiceIndex(b, rs)
        var tv = t.f64(0)
        if (i != null) {
            idx.setI32(0, i); tv = MooMergeBuffer.returnSweepT(rs, i); last.setU8(0, if (MooMergeBuffer.returnSweepIsLast(rs, i)) 1 else 0)
        }
        CaseResult(ret = Leaf.bool(i != null), out = mapOf("t" to f64Out(tv)), mem = mapOf("idx" to idx, "isLast" to last))
    }
    reg("${mmb}returnSweepChoice(int, double*, Emkey::WordAlternative*, std::vector<Emkey::WordAlternative, std::allocator<Emkey::WordAlternative>>*, std::vector<Emkey::PathResult, std::allocator<Emkey::PathResult>>*, Emkey::ACCollectage*, bool*)") {
        val rs = elems(it.img("this"), 0); val i = it.args[0].asInt()
        CaseResult(ret = Leaf.i32(1), out = mapOf("t" to f64Out(MooMergeBuffer.returnSweepT(rs, i))),
            mem = mapOf("isLast" to one("isLast", Leaf("u8", if (MooMergeBuffer.returnSweepIsLast(rs, i)) 1L else 0L))))
    }
    reg("${mmb}markDeadAfter(int)") {
        val b = it.img("this"); val c = containers(b); MooMergeBuffer.markDeadAfter(b, c, it.args[0].asInt()); writeBack(b, c)
        CaseResult(mem = mapOf("this" to b))
    }
    reg("${mmb}onTouchBacktrack(int)") {
        val b = it.img("this"); val c = containers(b); MooMergeBuffer.onTouchBacktrack(b, c, it.args[0].asInt()); writeBack(b, c)
        CaseResult(mem = mapOf("this" to b))
    }
    reg("${mmb}bestResultIslandCount()") {
        val b = it.img("this"); CaseResult(ret = Leaf.i32(MooMergeBuffer.bestResultIslandCount(b, orderOf(b))))
    }


    // Flag32 / PendingWord::getCapsBools / ACCollectage leaves
    reg("Shing::Flag32::flagged(int) const") { boolRet(if (nintype.engine.merge.Flag32.flagged(it.img("this").i32(0), it.args[0].asInt())) 1 else 0) }
    reg("Emkey::PendingWord::getCapsBools(bool&, bool&, bool&)") {
        val (a, b, c) = PendingWord.getCapsBools(it.img("this"))
        fun u8img(n: String, v: Boolean) = MemImage(n, 4).also { m -> m.setU8(0, if (v) 1 else 0) }
        CaseResult(mem = mapOf("a" to u8img("a", a), "b" to u8img("b", b), "c" to u8img("c", c)))
    }
    reg("Emkey::ACCollectage::closeSlotsBesidesZero()") {
        val ac = it.img("this"); val sl = elems(ac, 0xc)
        nintype.engine.merge.ACCollectage.closeSlotsBesidesZero(sl); vecBack(ac, 0xc, sl)
        CaseResult(mem = mapOf("this" to ac))
    }
    reg("Emkey::ACCollectage::markCurrentStarter(std::string const&)") {
        val ac = it.img("this")
        val sj = it.inputs["s"]!!.asObject().entries.first().value.asObject()["v"]!!.asString()
        val s = Values.parseBytes(sj)
        val r = nintype.engine.merge.ACCollectage.markCurrentStarter(ac, s)
        CaseResult(out = mapOf("starter" to Values.struct("@0x0:text" to Values.bytesLeaf(r))), mem = mapOf("this" to ac))
    }

    // MooMergeBuffer::update: callee-trace replay (re/vectors/gen_merge_update.py)
    reg("${mmb}update(int, bool, bool, Emkey::Moopad*)") { c ->
        val b = c.img("this"); val mp = c.img("mp"); val g = c.img("globals")
        val st = nintype.engine.merge.MergeBufferUpdate.State(
            b.seq(0).map { el -> nintype.engine.merge.MergeBufferUpdate.PR(0f, 0f, 0, MemImage.decode(el).f32(0x68)) }.toMutableList(), b.seq(0xc).size, orderOf(b))
        val host = UpdateReplay(c.inputs["calls"]?.asArray() ?: emptyList(), mp)
        val lut = nintype.engine.merge.MergeBufferUpdate.update(b, st, c.args[0].asInt(), c.args[1].asBool(), c.args[2].asBool(),
            mp, g.f64(0), g.f64(8), host)
        host.done()
        b.seqs[0] = st.results.map { Values.struct("@0x68:score" to Leaf.f32(it.score68).toJson()) }.toMutableList()
        b.seqs[0xc] = MutableList(st.acs) { Values.struct() }
        b.seqs[0x1c] = st.order.map { (k, v) -> JsonValue.JObject(linkedMapOf("first" to Leaf.f32(k).toJson(), "second" to Leaf.u32(v).toJson())) as JsonValue }.toMutableList()
        val gl = Values.struct("@0x8:lastUpdateT" to Leaf.f64(lut).toJson())
        CaseResult(out = mapOf("globals" to gl), mem = mapOf("this" to b))
    }

    // text helpers
    reg("Hangul::is_litamo(int)") { boolRet(if (nintype.engine.merge.ShingText.isLitamo(it.args[0].asInt())) 1 else 0) }
    reg("Morestring::appendUtf8Char(std::string&, unsigned int)") {
        val init = Values.parseBytes(it.inputs["s"]!!.asObject().entries.first().value.asObject()["v"]!!.asString())
        val o = java.io.ByteArrayOutputStream(); o.write(init)
        nintype.engine.merge.ShingText.appendUtf8Char(o, it.args[0].asInt())
        CaseResult(out = mapOf("s" to Values.struct("@0x0:text" to Values.bytesLeaf(o.toByteArray()))))
    }

    // mootouchlist_getExplicitResult (re/vectors/gen_merge_explicit.py)
    reg("Emkey::mootouchlist_getExplicitResult(std::vector<Emkey::Mootouch*, std::allocator<Emkey::Mootouch*>>, Emkey::Moopad*, float&, float&, bool&, std::vector<unsigned int, std::allocator<unsigned int>>&)") { c ->
        val mts = c.inputs["touches"]!!.asObject()["\$vec"]!!.asArray().map { nintype.engine.touch.MootouchCodec.mootouchFromJson(it) }
        val g = MemImage.decode(c.inputs["g"]!!); val mp = MemImage.decode(c.inputs["mp"]!!)
        val env = nintype.engine.touch.TouchEnv(explicitModel, mp, g.f64(0), g.f32(8))
        val codes = MemImage.decode(c.inputs["codes"]!!).seq(0).map { Leaf.fromJson(it).asInt() }.toMutableList()
        val o = nintype.engine.merge.ExplicitResult.Out(MemImage.decode(c.inputs["a"]!!).f32(0), MemImage.decode(c.inputs["b"]!!).f32(0), false, codes)
        nintype.engine.merge.ExplicitResult.mootouchlistGetExplicitResult(mts, env, mp, o)
        CaseResult(retValue = Values.struct("@0x0:text" to Values.bytesLeaf(o.text)), out = mapOf(
            "a" to Values.struct("@0x0" to Leaf.f32(o.a).toJson()), "b" to Values.struct("@0x0" to Leaf.f32(o.b).toJson()),
            "forced" to Values.struct("@0x0" to Leaf("u8", if (o.forced) 1L else 0L).toJson()),
            "codes" to Values.struct("@0x0:codes" to JsonValue.JObject(mapOf("\$vec" to JsonValue.JArray(o.codes.map { Leaf.u32(it).toJson() }))))))
    }
    // ACCollectage model (re/vectors/gen_merge_ac.py)
    fun mpOf(c: VectorCase) = c.inputs["mp"]?.let { MemImage.decode(it) } ?: MemImage("mp")
    reg("${acn}ACCollectage(Emkey::Moopad*)") { c -> val m = ACModel(null); m.construct(mpOf(c)); CaseResult(out = mapOf("this" to acToJson(m))) }
    reg("${acn}reset()") { c -> val m = acFromJson(c.inputs["this"]!!, mpOf(c)); m.reset(); CaseResult(out = mapOf("this" to acToJson(m))) }
    reg("${acn}operator=(Emkey::ACCollectage const&)") { c ->
        val m = acFromJson(c.inputs["this"]!!, mpOf(c)); m.assign(acFromJson(c.inputs["o"]!!, mpOf(c)))
        CaseResult(out = mapOf("this" to acToJson(m)))
    }
    reg("${acn}isEmpty()") { boolRet(if (acFromJson(it.inputs["this"]!!, null).isEmpty()) 1 else 0) }
    reg("${acn}hasLeftCandidates()") { boolRet(if (acFromJson(it.inputs["this"]!!, null).hasLeftCandidates()) 1 else 0) }
    reg("${acn}hasRightCandidates()") { boolRet(if (acFromJson(it.inputs["this"]!!, null).hasRightCandidates()) 1 else 0) }
    reg("${mmb}getMostRecentAutoCompleteBuffer(Emkey::ACCollectage*)") { c ->
        val mp = mpOf(c)
        val list = c.inputs["this"]!!.asObject()["acs"]!!.asArray().map { acFromJson(it, mp) }
        val ac = acFromJson(c.inputs["ac"]!!, mp)
        MooMergeBuffer.getMostRecentAutoCompleteBuffer(list, ac)
        CaseResult(out = mapOf("ac" to acToJson(ac)))
    }
    reg("${mmb}getExplicitResult(Emkey::Moopad*, float&, float&, bool&, std::vector<unsigned int, std::allocator<unsigned int>>&)") { c ->
        val b = MemImage.decode(c.inputs["this"]!!); val mp = mpOf(c); val g = MemImage.decode(c.inputs["g"]!!)
        val env = nintype.engine.touch.TouchEnv(explicitModel, mp, g.f64(0), g.f32(8))
        val mts = mp.seq(0x294c4).map { nintype.engine.touch.MootouchCodec.mootouchFromJson(it) }
        val o = nintype.engine.merge.ExplicitResult.Out(MemImage.decode(c.inputs["a"]!!).f32(0), MemImage.decode(c.inputs["b"]!!).f32(0),
            MemImage.decode(c.inputs["forced"]!!).u8(0) != 0, mutableListOf())
        MooMergeBuffer.getExplicitResult(b, mts, env, mp, o)
        CaseResult(retValue = Values.struct("@0x0:text" to Values.bytesLeaf(o.text)), out = mapOf(
            "a" to Values.struct("@0x0" to Leaf.f32(o.a).toJson()), "b" to Values.struct("@0x0" to Leaf.f32(o.b).toJson()),
            "forced" to Values.struct("@0x0" to Leaf("u8", if (o.forced) 1L else 0L).toJson()),
            "codes" to Values.struct("@0x0:codes" to JsonValue.JObject(mapOf("\$vec" to JsonValue.JArray(o.codes.map { Leaf.u32(it).toJson() }))))))
    }
}

private val explicitModel by lazy { nintype.engine.geometry.KeyboardModel.nintype() }

private const val acn = "Emkey::ACCollectage::"
private fun bytesOf(j: JsonValue) = Values.parseBytes(j.asObject()["v"]!!.asString())
private fun leafI(j: JsonValue) = Leaf.fromJson(j).asInt()
private fun wordFrom(j: JsonValue) = j.asObject().let { ACWord(bytesOf(it["text"]!!), bytesOf(it["rest"]!!)) }
private fun wordTo(w: ACWord): JsonValue = JsonValue.JObject(linkedMapOf("text" to Values.bytesLeaf(w.text), "rest" to Values.bytesLeaf(w.rest)))
private fun arr(l: List<JsonValue>): JsonValue = JsonValue.JArray(l)

fun acFromJson(j: JsonValue, mp: MemImage?): ACModel {
    val o = j.asObject(); val m = ACModel(mp)
    m.starter = bytesOf(o["starter"]!!); m.starterLen = leafI(o["starterLen"]!!)
    for (s in o["slots"]!!.asArray()) { val so = s.asObject(); m.slots.add(ACSlot(so["words"]!!.asArray().mapTo(mutableListOf(), ::wordFrom), leafI(so["open"]!!))) }
    o["set18"]!!.asArray().mapTo(m.set18, ::bytesOf); o["set30"]!!.asArray().mapTo(m.set30, ::bytesOf)
    for (p in o["byScore"]!!.asArray()) { val po = p.asObject(); m.byScore.add(Pair(Leaf.fromJson(po["key"]!!).asFloat(), wordFrom(po["word"]!!))) }
    m.f60 = leafI(o["f60"]!!)
    o["dq64"]!!.asArray().mapTo(m.dq64, ::wordFrom); o["dq8c"]!!.asArray().mapTo(m.dq8c, ::wordFrom); o["dqb4"]!!.asArray().mapTo(m.dqb4, ::wordFrom)
    m.fdc = leafI(o["fdc"]!!); m.fe0 = leafI(o["fe0"]!!)
    return m
}

fun acToJson(m: ACModel): JsonValue = JsonValue.JObject(linkedMapOf(
    "starter" to Values.bytesLeaf(m.starter), "starterLen" to Leaf.i32(m.starterLen).toJson(),
    "slots" to arr(m.slots.map { s -> JsonValue.JObject(linkedMapOf("words" to arr(s.words.map(::wordTo)), "open" to Leaf("u8", s.open.toLong()).toJson())) }),
    "set18" to arr(m.set18.map { Values.bytesLeaf(it) }), "set30" to arr(m.set30.map { Values.bytesLeaf(it) }),
    "byScore" to arr(m.byScore.map { (k, w) -> JsonValue.JObject(linkedMapOf("key" to Leaf.f32(k).toJson(), "word" to wordTo(w))) }),
    "f60" to Leaf.i32(m.f60).toJson(),
    "dq64" to arr(m.dq64.map(::wordTo)), "dq8c" to arr(m.dq8c.map(::wordTo)), "dqb4" to arr(m.dqb4.map(::wordTo)),
    "fdc" to Leaf.i32(m.fdc).toJson(), "fe0" to Leaf.i32(m.fe0).toJson()))

/** Replays update's recorded direct callees (in.calls), checking names and argument bits. */
private class UpdateReplay(private val calls: List<JsonValue>, private val mp: MemImage) :
    nintype.engine.merge.MergeBufferUpdate.Host<nintype.engine.merge.MergeBufferUpdate.PR> {
    override fun fields(r: nintype.engine.merge.MergeBufferUpdate.PR) = r
    override fun emptyResult() = nintype.engine.merge.MergeBufferUpdate.PR(0f, 0f, 0, 0f)
    private var i = 0
    private fun next(fnPart: String, vararg args: Leaf): Map<String, JsonValue> {
        if (i >= calls.size) error("extra call $fnPart (only ${calls.size} recorded)")
        val o = calls[i].asObject()
        val fn = o["fn"]!!.asString()
        if (!fn.contains(fnPart)) error("call #$i: port called $fnPart, original called $fn")
        val rec = o["args"]?.asArray()?.map { Leaf.fromJson(it) } ?: emptyList()
        if (rec.size != args.size || rec.indices.any { k -> rec[k].bits != args[k].bits })
            error("call #$i $fn: args ${args.map { java.lang.Long.toHexString(it.bits) }} vs recorded ${rec.map { java.lang.Long.toHexString(it.bits) }}")
        i++
        o["mp"]?.let { m -> val after = MemImage.decode(m)
            for ((off, w) in listOf(0x2a110 to 1, 0x2a11a to 1, 0x2affc to 4)) if (after.isKnown(off)) mp.writeLE(off, w, after.readLE(off, w)) }
        return o
    }
    private fun peek(): String? = if (i < calls.size) calls[i].asObject()["fn"]!!.asString() else null
    private fun pr(o: Map<String, JsonValue>): nintype.engine.merge.MergeBufferUpdate.PR {
        val m = MemImage.decode(o["ret"]!!)
        return nintype.engine.merge.MergeBufferUpdate.PR(m.f32(4), m.f32(8), m.u8(0x3e), m.f32(0x68))
    }
    private fun b(o: Map<String, JsonValue>) = Leaf.fromJson(o["ret"]!!).asInt() and 0xff != 0
    fun done() { if (i != calls.size) error("port made $i calls, original made ${calls.size} (next: ${peek()})") }

    override fun contextHas(which: Int) = b(next("Context::has"))
    override fun reset(start: Int, end: Int) { next("PathSearch::reset(int, int)", Leaf.i32(start), Leaf.i32(end)) }
    override fun syncBucketStepAllowance(f: Float) { next("syncBucketStepAllowance", Leaf.f32(f)) }
    override fun performSteps(n: Int) { next("performSteps", Leaf.i32(n)) }
    override fun listResults(n: Int) { next("listResults", Leaf.i32(n)) }
    override fun topResult() = pr(next("topResult"))
    override fun newACCollectage() { next("ACCollectage::ACCollectage") }
    override fun gatherAutocomplete() { next("gatherAutocomplete") }
    override fun mootouchIndexOfNearSpaceTapper() = Leaf.fromJson(next("mootouchIndexOfNearSpaceTapper")["ret"]!!).asInt()
    override fun mootouchIndexOfApostropheEnder() = Leaf.fromJson(next("mootouchIndexOfApostropheEnder")["ret"]!!).asInt()
    override fun resetForPrePostcut(start: Int, end: Int, idx: Int, post: Boolean) {
        next("resetForPrePostcut", Leaf.i32(start), Leaf.i32(end), Leaf.i32(idx), Leaf.bool(post))
    }
    override fun resetForAutospaceSplitSearch(start: Int, end: Int, idx: Int, post: Boolean) {
        next("resetForAutospaceSplitSearch", Leaf.i32(start), Leaf.i32(end), Leaf.i32(idx), Leaf.bool(post))
    }
    override fun mergeFromSpaceSplitters(top: nintype.engine.merge.MergeBufferUpdate.PR, pre: nintype.engine.merge.MergeBufferUpdate.PR,
                                         post: nintype.engine.merge.MergeBufferUpdate.PR, w: Float, idx: Int) =
        pr(next("mergeFromSpaceSplitters", Leaf.f32(w), Leaf.i32(idx)))
    override fun syncAutospaceStatsPostSearch() {
        val r = MemImage.decode(next("syncAutospaceStatsPostSearch")["ret"]!!)
        mp.setI32(0x8388, r.i32(0x8388)); mp.setF32(0x83a0, r.f32(0x83a0)); if (r.isKnown(0x838c)) mp.setF32(0x838c, r.f32(0x838c))
    }
    override fun shouldSplit() = b(next("shouldSplit"))
    override fun onDetectEarlySplit(gap: Float) { next("onDetectEarlySplit", Leaf.f32(gap)) }
    override fun apostropheMergeAllowed(post: nintype.engine.merge.MergeBufferUpdate.PR) = peek()?.contains("mergeFromSpaceSplitters") == true
}
