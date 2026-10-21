package nintype.engine.ports

import nintype.engine.touch.ChasingFloat
import nintype.engine.touch.PlanarCoverage
import nintype.engine.touch.TouchPoint
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.Values

/** Vector adapters for re/vectors/gen_touch.py (area `touch`). */

private fun leafJ(t: String, bits: Long) = Leaf(t, bits).toJson()

private fun pcOf(img: MemImage) = PlanarCoverage(
    img.f64(0), img.f64(8), img.u8(0x10),
    img.seq(0x14).map { Leaf.fromJson(it).asInt() }.toMutableList(), img.f32(0x20),
)

private fun pcJson(p: PlanarCoverage): JsonValue = Values.struct(
    "@0x0:tMin" to Leaf.f64(p.tMin).toJson(), "@0x8:tMax" to Leaf.f64(p.tMax).toJson(),
    "@0x10:hasTime" to leafJ("u8", p.hasTime.toLong()),
    "@0x14:areas" to Values.vec(p.areas.map { leafJ("f32", it.toLong() and 0xFFFFFFFFL) }),
    "@0x20:maxCoverage" to Leaf.f32(p.maxCoverage).toJson(),
)

private val touchModel by lazy { nintype.engine.geometry.KeyboardModel.nintype() }

private fun envOf(c: nintype.engine.vectors.VectorCase): nintype.engine.touch.TouchEnv {
    val g = MemImage.decode(c.inputs["g"]!!)
    return nintype.engine.touch.TouchEnv(touchModel, MemImage.decode(c.inputs["mp"]!!), g.f64(0), g.f32(8),
        g.f64(0x10), g.f64(0x18), g.f64(0x20), g.f64(0x28))
}

private fun ptOf(c: nintype.engine.vectors.VectorCase, n: String) = MemImage.decode(c.inputs[n]!!).let { nintype.engine.math.Point2D(it.f32(0), it.f32(4)) }

private fun ptIn(c: nintype.engine.vectors.VectorCase) = MemImage.decode(c.inputs["p"]!!).let { nintype.engine.math.Point2D(it.f32(0), it.f32(4)) }

/** A Mootouch from a raw-layout case body (no codec "points" keys): vectors from the $vec keys. */
private fun rawMootouch(c: nintype.engine.vectors.VectorCase): nintype.engine.touch.Mootouch {
    val b = MemImage.decode(c.inputs["this"]!!, "this")
    val pix = ArrayDeque(b.seq(0x1ec).map { MemImage.decode(it).let { im -> im.i32(0) to im.i32(4) } })
    return nintype.engine.touch.Mootouch(b, listOf(0x244, 0x250, 0x25c).map { o -> b.seq(o).map { MemImage.decode(it) }.toMutableList() }, pix)
}

private fun envOfRaw(c: nintype.engine.vectors.VectorCase): nintype.engine.touch.TouchEnv {
    val g = c.inputs["g"]?.let { MemImage.decode(it) } ?: MemImage("g", 0x30)
    return nintype.engine.touch.TouchEnv(touchModel, c.inputs["mp"]?.let { MemImage.decode(it) } ?: MemImage("mp", 16),
        g.f64(0), g.f32(8), g.f64(0x10), g.f64(0x18), g.f64(0x20), g.f64(0x28))
}

/** Whole-Mootouch trace adapters (re/vectors/gen_touch_record.py). */
private fun mtPort(name: String, body: (nintype.engine.touch.Mootouch, nintype.engine.touch.TouchEnv, nintype.engine.vectors.VectorCase) -> Leaf?) =
    PortRegistry.register(name) { c ->
        if (c.inputs["this"]!!.asObject().keys.none { it.endsWith(":points") }) {
            // fuzz vectors over a raw Mootouch body (no point vectors): plain image in, image out
            val w = c.world()
            val b = w["this"]
            val pix = ArrayDeque(b.seq(0x1ec).map { MemImage.decode(it).let { im -> im.i32(0) to im.i32(4) } })
            val mt = nintype.engine.touch.Mootouch(b, listOf(0x244, 0x250, 0x25c).map { o -> b.seq(o).map { MemImage.decode(it) }.toMutableList() }, pix)
            val g = c.inputs["g"]?.let { MemImage.decode(it) } ?: MemImage("g", 0x30)
            val env = nintype.engine.touch.TouchEnv(touchModel, w.images["mp"] ?: MemImage("mp", 16), g.f64(0), g.f32(8),
                g.f64(0x10), g.f64(0x18), g.f64(0x20), g.f64(0x28))
            return@register CaseResult(ret = body(mt, env, c), mem = w.images)
        }
        val mt = nintype.engine.touch.MootouchCodec.mootouchFromJson(c.inputs["this"]!!)
        val ret = body(mt, envOf(c), c)
        CaseResult(ret = ret, out = mapOf("this" to nintype.engine.touch.MootouchCodec.mootouchToJson(mt)))
    }

/** Two-touch trace adapters: in/out "this" and "o", both whole Mootouches. */
private fun mt2Port(name: String, body: (nintype.engine.touch.Mootouch, nintype.engine.touch.Mootouch, nintype.engine.touch.TouchEnv, nintype.engine.vectors.VectorCase) -> Leaf?) =
    PortRegistry.register(name) { c ->
        val a = nintype.engine.touch.MootouchCodec.mootouchFromJson(c.inputs["this"]!!)
        val b = nintype.engine.touch.MootouchCodec.mootouchFromJson(c.inputs["o"]!!)
        val ret = body(a, b, envOf(c), c)
        CaseResult(ret = ret, out = mapOf("this" to nintype.engine.touch.MootouchCodec.mootouchToJson(a),
            "o" to nintype.engine.touch.MootouchCodec.mootouchToJson(b)))
    }

fun registerTouchPorts() {
    val mtn = "Emkey::Mootouch::"
    mt2Port("${mtn}checkFingerCollisionDetachmentPointsFromAnotherTouch(Emkey::Mootouch&)") { a, b, e, _ ->
        Leaf.bool(a.checkFingerCollisionDetachmentPointsFromAnotherTouch(e, b)) }
    mt2Port("${mtn}checkLastTouchFingerCollisionDetachmentPointsToANewTouch(Emkey::Mootouch&)") { a, b, _, _ ->
        Leaf.bool(a.checkLastTouchFingerCollisionDetachmentPointsToANewTouch(b)) }
    mtPort("${mtn}checkLastTouchFingerCollisionAgainst(Point2D const&, double)") { m, _, c ->
        Leaf.bool(m.checkLastTouchFingerCollisionAgainst(ptIn(c), c.args[0].asDouble())) }
    mtPort("${mtn}record(Point2D const&, bool)") { mt, env, c -> mt.record(env, ptIn(c), c.args[0].asBool()); null }
    mtPort("${mtn}pushPointEx(bool)") { mt, env, c -> Leaf.i32(mt.pushPointEx(env, c.args[0].asBool())) }
    mtPort("${mtn}updateCurrentKey(Point2D const&)") { mt, env, c -> Leaf.bool(mt.updateCurrentKey(env, ptIn(c))) }
    mtPort("${mtn}recordTwerkTarget(Point2D const&)") { mt, _, c -> mt.recordTwerkTarget(ptIn(c)); null }
    mtPort("${mtn}pushPixMovementFrame(float, int)") { mt, _, c -> mt.pushPixMovementFrame(c.args[0].asFloat(), c.args[1].asInt()); null }
    mtPort("${mtn}isSimpleTap()") { mt, env, _ -> Leaf.bool(mt.isSimpleTap(env)) }
    mtPort("${mtn}determineTwerkerPoint(Emkey::MooTouchTwerkPoint&)") { mt, _, c -> mt.determineTwerkerPoint(c.args[0].asInt()); null }
    mtPort("${mtn}insertArtificialVisitationPointsAndGetRidOfUnimportantPoints()") { mt, _, _ ->
        mt.insertArtificialVisitationPointsAndGetRidOfUnimportantPoints(); null
    }
    mtPort("${mtn}markReleased()") { mt, env, _ -> mt.markReleased(env); null }
    mtPort("${mtn}finalizeSlownessDetection()") { mt, env, _ -> mt.finalizeSlownessDetection(env); null }
    mtPort("${mtn}syncDampeningState()") { mt, env, _ -> mt.syncDampeningState(env); null }
    mtPort("${mtn}calcDepartureVelocity()") { mt, _, _ -> mt.calcDepartureVelocity(); null }
    mtPort("${mtn}takeForceTouchUpdate(TouchInfo const&)") { mt, env, c ->
        val t = MemImage.decode(c.inputs["ti"]!!)
        mt.takeForceTouchUpdate(env, nintype.engine.touch.ForceInfo(t.u8(0x48), t.f32(0x4c), t.f32(0x50))); null
    }
    mtPort("${mtn}syncForceKeyEligibility()") { mt, env, _ -> mt.syncForceKeyEligibility(env); null }
    fun q(sig: String, body: (nintype.engine.touch.Mootouch, nintype.engine.touch.TouchEnv, nintype.engine.vectors.VectorCase) -> Leaf) = mtPort("$mtn$sig", body)
    fun fa(c: nintype.engine.vectors.VectorCase, i: Int) = c.args[i].asFloat()
    q("isSimplishTap()") { m, _, _ -> Leaf.bool(m.isSimplishTap()) }
    q("hasNotMoved()") { m, _, _ -> Leaf.bool(m.hasNotMoved()) }
    q("hardlyMovedAtAll()") { m, _, _ -> Leaf.bool(m.hardlyMovedAtAll()) }
    q("getDurationSoFar()") { m, _, _ -> Leaf.f32(m.getDurationSoFar()) }
    q("didStationary(float)") { m, _, c -> Leaf.bool(m.didStationary(fa(c, 0))) }
    q("isStationaryHolding(float)") { m, e, c -> Leaf.bool(m.isStationaryHolding(e, fa(c, 0))) }
    q("isRecentlyStationaryHolding(float)") { m, _, c -> Leaf.bool(m.isRecentlyStationaryHolding(fa(c, 0))) }
    q("getSimpleDisplacementPixels()") { m, _, _ -> Leaf.f32(m.getSimpleDisplacementPixels()) }
    q("getUnitDisplacement()") { m, e, _ -> Leaf.f32(m.getUnitDisplacement(e)) }
    q("isSimpleSwipe(float)") { m, _, c -> Leaf.bool(m.isSimpleSwipe(fa(c, 0))) }
    q("isTowards(Point2D const&, float, float)") { m, _, c -> Leaf.bool(m.isTowards(ptOf(c, "dir"), fa(c, 0), fa(c, 1))) }
    q("getStartingKey()") { m, _, _ -> Leaf.i32(m.getStartingKey()) }
    q("isDirectionalishSwipe(float)") { m, e, c -> Leaf.bool(m.isDirectionalishSwipe(e, fa(c, 0))) }
    q("isSimplishSlideOrTapTowards(char)") { m, e, c -> Leaf.bool(m.isSimplishSlideOrTapTowards(e, c.args[0].asInt())) }
    q("isSimpleUnslideableTap()") { m, e, _ -> Leaf.bool(m.isSimpleUnslideableTap(e)) }
    q("isProbablyProtractedSpaceTap()") { m, e, _ -> Leaf.bool(m.isProbablyProtractedSpaceTap(e)) }
    q("finishedDecaying()") { m, e, _ -> Leaf.bool(m.finishedDecaying(e)) }
    PortRegistry.register("${mtn}charaForLiteralWord(bool&)") { c ->
        val (v, lit) = rawMootouch(c).charaForLiteralWord(envOfRaw(c))
        CaseResult(ret = Leaf.i32(v), out = mapOf("lit" to Values.struct("@0x0:flag" to Leaf("u8", if (lit) 1L else 0x5aL).toJson())))
    }
    mtPort("${mtn}markActualCurrentKey(Emkey::Mookey*)") { m, e, c -> m.markActualCurrentKey(e, c.args[0].asInt()); null }
    mtPort("${mtn}syncBottomSpacingness(bool)") { m, e, c -> m.syncBottomSpacingness(e, c.args[0].asBool()); null }
    PortRegistry.register("${mtn}getRecentDirection(float)") { c ->
        val d = rawMootouch(c).getRecentDirection(c.args[0].asFloat())
        CaseResult(retValue = Values.struct("@0x0:x" to Leaf.f32(d.x).toJson(), "@0x4:y" to Leaf.f32(d.y).toJson()))
    }
    q("findLatestImportantPointBefore(Emkey::MooTouchPoint*)") { m, _, c ->
        Leaf.i32(m.findLatestImportantPointBefore(MemImage.decode(c.inputs["q"]!!).f64(0x128))) }
    q("isSimpleSwipeTowards(Point2D const&, float, float)") { m, _, c -> Leaf.bool(m.isSimpleSwipeTowards(ptOf(c, "dir"), fa(c, 0), fa(c, 1))) }
    for ((sig, skip) in listOf("getPixMovementFor(float, float&, float&)" to false,
        "getOldPixMovementsAfterFor(float, float, float&, float&)" to true)) {
        PortRegistry.register("$mtn$sig") { c ->
            val m = rawMootouch(c)
            val (sp, t) = m.pixMovement(if (skip) fa(c, 0) else null, fa(c, if (skip) 1 else 0))
            CaseResult(out = mapOf("res" to Values.struct("@0x0:speed" to Leaf.f32(sp).toJson(), "@0x4:time" to Leaf.f32(t).toJson())))
        }
    }
    PortRegistry.register("${mtn}getSwipeDir()") { c ->
        val b = c.world()["this"]
        val d = nintype.engine.touch.Mootouch(b, List(3) { mutableListOf<MemImage>() }, ArrayDeque()).getSwipeDir()
        CaseResult(retValue = Values.struct("@0x0:x" to Leaf.f32(d.x).toJson(), "@0x4:y" to Leaf.f32(d.y).toJson()))
    }
    mtPort("${mtn}update()") { mt, env, _ -> mt.update(env); null }
    mtPort("${mtn}findPointClosestToTime_WhileRecording(double)") { mt, _, c -> Leaf.i32(mt.findPointClosestToTime(c.args[0].asDouble())) }

    val reg = PortRegistry::register
    val mtp = "Emkey::MooTouchPoint::"

    fun mem(name: String, body: (MemImage) -> Unit) = reg(name) { c ->
        val w = c.world()
        body(w["this"])
        CaseResult(mem = w.images)
    }
    mem("${mtp}syncScores()", TouchPoint::syncScores)
    mem("${mtp}markDetached()", TouchPoint::markDetached)
    mem("${mtp}syncLeadPoints()", TouchPoint::syncLeadPoints)
    mem("${mtp}sortTravs()", TouchPoint::sortTravs)
    reg("${mtp}normalizeTravkeyScores(float)") { c ->
        val w = c.world(); TouchPoint.normalizeTravkeyScores(w["this"], c.args[0].asFloat()); CaseResult(mem = w.images)
    }
    reg("${mtp}pushTrav(char, int, float)") { c ->
        val w = c.world()
        val i = TouchPoint.pushTrav(w["this"], c.args[0].asInt(), c.args[1].asInt(), c.args[2].asFloat())
        CaseResult(ret = Leaf.i32(i), mem = w.images)
    }
    reg("${mtp}bestKeyScore()") { c -> val w = c.world(); CaseResult(ret = Leaf.f32(TouchPoint.bestKeyScore(w["this"])), mem = w.images) }
    reg("${mtp}bestKey()") { c -> val w = c.world(); CaseResult(ret = Leaf("u8", TouchPoint.bestKey(w["this"]).toLong()), mem = w.images) }
    reg("${mtp}probablyContains(char, float)") { c ->
        val w = c.world()
        CaseResult(ret = Leaf.bool(TouchPoint.probablyContains(w["this"], c.args[0].asInt(), c.args[1].asFloat())), mem = w.images)
    }
    reg("${mtp}syncRoughness(Emkey::MooTouchPoint*)") { c ->
        val w = c.world()
        TouchPoint.syncRoughness(w["this"], w.images["prev"])
        CaseResult(mem = w.images)
    }

    reg("${mtp}markOnFocus(bool)") { c ->
        val w = c.world(); TouchPoint.markOnFocus(w["this"], c.args[0].asBool(), MemImage.decode(c.inputs["g"]!!).f64(0)); CaseResult(mem = w.images)
    }
    mem("${mtp}finaliseFocusTime()", TouchPoint::finaliseFocusTime)
    reg("${mtp}twerkConsume(Emkey::MooTouchPoint&)") { c ->
        val w = c.world(); CaseResult(ret = Leaf.bool(TouchPoint.twerkConsume(w["this"], w["o"])), mem = w.images)
    }
    reg("${mtp}getAssocCKPosElseDriftpos(char)") { c ->
        val p = TouchPoint.getAssocCKPosElseDriftpos(c.world()["this"], c.args[0].asInt())
        CaseResult(retValue = Values.struct("@0x0:x" to Leaf.f32(p.x).toJson(), "@0x4:y" to Leaf.f32(p.y).toJson()))
    }
    reg("${mtp}associateCK(Emkey::TravKeyData)") { c ->
        val w = c.world()
        TouchPoint.associateCK(w["this"], nintype.engine.touch.TravKeyData(IntArray(8) { c.args[it].asInt() }))
        CaseResult(mem = w.images)
    }

    reg("${mtp}syncStatesOnRelease(Emkey::MooTouchPoint*, Emkey::Moopad*)") { c ->
        val w = c.world()
        TouchPoint.syncStatesOnRelease(w["this"], w.images["prev"], MemImage.decode(c.inputs["mp"]!!).f32(0x1f4a8))
        CaseResult(mem = w.images)
    }

    reg("${mtp}syncCoverage(Point2D const&, Point2D const&, Emkey::Moopad*)") { c ->
        val w = c.world()
        TouchPoint.syncCoverage(w["this"], ptOf(c, "p1"), ptOf(c, "p2"), MemImage.decode(c.inputs["mp"]!!).f32(0x1f4a8), touchModel.board)
        CaseResult(mem = w.images)
    }

    reg("${mtp}takeForceTouchUpdate(TouchInfo const&, Emkey::Moopad*)") { c ->
        val w = c.world()
        val t = MemImage.decode(c.inputs["ti"]!!)
        TouchPoint.takeForceTouchUpdate(w["this"], nintype.engine.touch.ForceInfo(t.u8(0x48), t.f32(0x4c), t.f32(0x50)),
            MemImage.decode(c.inputs["mp"]!!))
        CaseResult(mem = w.images)
    }

    reg("${mtp}considerSlideVSHist(bool, Emkey::MooTouchPoint&, Emkey::Moopad*)") { c ->
        val w = c.world()
        TouchPoint.considerSlideVSHist(w["this"], c.args[0].asBool(), w["o"], Values.ref("o"), MemImage.decode(c.inputs["mp"]!!).f32(0x1f4ac))
        CaseResult(mem = w.images)
    }

    reg("${mtp}getPlanarCoverage(int)") { c -> CaseResult(ret = Leaf.i32(TouchPoint.planarCoverageIndex(c.world()["this"], false, c.args[0].asInt()))) }
    reg("${mtp}getOtherPlanarCoverage(int)") { c -> CaseResult(ret = Leaf.i32(TouchPoint.planarCoverageIndex(c.world()["this"], true, c.args[0].asInt()))) }
    reg("${mtp}receiveNTT(Emkey::NextTouchTarget&)") { c ->
        val w = c.world()
        TouchPoint.receiveNTT(w["this"], c.inputs["t"]!!, w["p"])
        CaseResult(mem = w.images)
    }
    for ((sig, size) in listOf("Emkey::PlanarCoverage::PlanarCoverage()" to 0x24, "Emkey::DirectionalCoverage::DirectionalCoverage()" to 0x20)) {
        reg(sig) { c ->
            val img = MemImage.decode(c.inputs["this"]!!, "this")
            img.writeBytes(0, ByteArray(0x11))           // tMin, tMax, hasTime (padding 0x11..0x14 untouched)
            img.seqs[0x14] = mutableListOf()              // empty vector
            if (size == 0x24) img.setI32(0x20, 0)        // maxCoverage
            CaseResult(mem = mapOf("this" to img))
        }
    }

    val codec = nintype.engine.touch.MootouchCodec
    reg("${mtp}MooTouchPoint(Emkey::MooTouchPoint const&)") { c ->
        CaseResult(out = mapOf("this" to codec.pointToJson(codec.copyPoint(codec.pointFromJson(c.inputs["src"]!!)))))
    }
    reg("${mtp}MooTouchPoint(Emkey::MooTouchPoint&&)") { c ->
        val src = codec.pointFromJson(c.inputs["src"]!!)
        val d = codec.movePoint(src)
        CaseResult(out = mapOf("this" to codec.pointToJson(d), "src" to codec.pointToJson(src)))
    }
    reg("${mtp}operator=(Emkey::MooTouchPoint const&)") { c ->
        val d = codec.pointFromJson(c.inputs["this"]!!)
        codec.assignPoint(d, codec.pointFromJson(c.inputs["src"]!!))
        CaseResult(out = mapOf("this" to codec.pointToJson(d)))
    }
    reg("${mtp}MooTouchPoint()") { c ->
        val p = codec.pointFromJson(c.inputs["this"]!!)
        codec.constructPoint(p)
        CaseResult(out = mapOf("this" to codec.pointToJson(p)))
    }

    val pcn = "Emkey::PlanarCoverage::"
    reg("${pcn}record(int, float, double)") { c ->
        val p = pcOf(c.world()["this"])
        p.record(c.args[0].asInt(), c.args[1].asFloat(), c.args[2].asDouble())
        CaseResult(out = mapOf("this" to pcJson(p)))
    }
    reg("${pcn}getCoverage(int)") { c -> CaseResult(ret = Leaf.f32(pcOf(c.world()["this"]).getCoverage(c.args[0].asInt()))) }
    reg("${pcn}checkCoverageSatisfaction(Shing::Flag64&, float)") { c ->
        val w = c.world()
        CaseResult(ret = Leaf.bool(pcOf(w["this"]).checkCoverageSatisfaction(w["flags"].readLE(0, 8), c.args[0].asFloat())))
    }

    reg("ChasingFloat::chaseByFraction(float, float)") { c ->
        val img = c.world()["this"]
        val cf = ChasingFloat(img.f32(0), img.f32(4), img.f32(8))
        cf.chaseByFraction(c.args[0].asFloat(), c.args[1].asFloat())
        img.setF32(0, cf.cur); img.setF32(4, cf.mid)
        CaseResult(mem = mapOf("this" to img))
    }
}
