package nintype.engine.ports

import nintype.engine.emkey.Calculate
import nintype.engine.emkey.DirectionalCoverage
import nintype.engine.emkey.MooTouchPoint
import nintype.engine.emkey.Moopad
import nintype.engine.math.LibM
import nintype.engine.math.Point2D
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.VectorCase
import nintype.engine.vectors.Values

/** Vector adapters for re/vectors/gen_touchmath.py and gen_libm.py. */

private fun pt(j: JsonValue): Point2D = MemImage.decode(j).let { Point2D(it.f32(0), it.f32(4)) }

private fun ptJson(p: Point2D): JsonValue = Values.struct("@0x0:x" to Leaf.f32(p.x).toJson(), "@0x4:y" to Leaf.f32(p.y).toJson())

private fun VectorCase.p(name: String) = pt(inputs[name]!!)
private fun VectorCase.f(i: Int) = args[i].asFloat()
private fun VectorCase.img(name: String) = MemImage.decode(inputs[name]!!, name)
private fun VectorCase.mpF(off: Int) = img("mp").f32(off)

private fun reg(name: String, body: (VectorCase) -> CaseResult) = PortRegistry.register(name, body)
private fun regF(name: String, body: (VectorCase) -> Float) = reg(name) { CaseResult(ret = Leaf.f32(body(it))) }
private fun regP(name: String, body: (VectorCase) -> Point2D) = reg(name) { CaseResult(retValue = ptJson(body(it))) }

fun registerTouchMathPorts() {
    // libm
    regF("libm::acosf") { LibM.acosf(it.f(0)) }
    regF("libm::atanf") { LibM.atanf(it.f(0)) }
    regF("libm::sqrtf") { LibM.sqrtf(it.f(0)) }
    regF("libm::powf") { LibM.powf(it.f(0), it.f(1)) }
    regF("libgcc::__aeabi_fadd") { nintype.engine.SoftFloat.fadd(it.f(0), it.f(1)) }
    regF("libgcc::__aeabi_fsub") { nintype.engine.SoftFloat.fsub(it.f(0), it.f(1)) }
    reg("libgcc::__aeabi_dadd") { CaseResult(ret = Leaf.f64(nintype.engine.SoftFloat.dadd(it.args[0].asDouble(), it.args[1].asDouble()))) }
    reg("libgcc::__aeabi_dsub") { CaseResult(ret = Leaf.f64(nintype.engine.SoftFloat.dsub(it.args[0].asDouble(), it.args[1].asDouble()))) }

    // Point2D
    regF("Point2D::length() const") { it.p("this").length() }
    regF("Point2D::lengthsquared() const") { it.p("this").lengthSquared() }
    regF("Point2D::arg() const") { it.p("this").arg() }
    regF("Point2D::argDeg() const") { it.p("this").argDeg() }
    regF("Point2D::distance(Point2D const&) const") { it.p("this").distance(it.p("o")) }
    regF("Point2D::lengthFrom(Point2D const&) const") { it.p("this").lengthFrom(it.p("o")) }
    regF("Point2D::distanceSquared(Point2D const&) const") { it.p("this").distanceSquared(it.p("o")) }
    regF("Point2D::manhattandistance(Point2D const&) const") { it.p("this").manhattanDistance(it.p("o")) }
    regF("Point2D::xorydistance(Point2D const&) const") { it.p("this").xorYDistance(it.p("o")) }
    regF("Point2D::degreesVs(Point2D const&) const") { it.p("this").degreesVs(it.p("o")) }
    regF("Point2D::argDifference(Point2D const&) const") { it.p("this").argDifference(it.p("o")) }
    regF("dot(Point2D const&, Point2D const&)") { Point2D.dot(it.p("this"), it.p("o")) }
    regP("Point2D::normalise() const") { it.p("this").normalise() }
    regP("Point2D::conjugate() const") { it.p("this").conjugate() }
    regP("Point2D::operator-(Point2D const&) const") { it.p("this") - it.p("o") }
    regP("Point2D::operator+(Point2D const&) const") { it.p("this") + it.p("o") }
    regP("Point2D::operator*(float) const") { it.p("this") * it.f(0) }
    regP("Point2D::interpolate(Point2D const&, float) const") { it.p("this").interpolate(it.p("o"), it.f(0)) }

    // Moopad
    regF("Emkey::Moopad::unitDist(Point2D const&)") { Moopad.unitDist(it.p("a"), it.mpF(Moopad.OFF_UNIT)) }
    regF("Emkey::Moopad::unitDist(Point2D const&, Point2D const&)") { Moopad.unitDist(it.p("a"), it.p("b"), it.mpF(Moopad.OFF_UNIT)) }
    regP("Emkey::Moopad::unitDiff(Point2D const&, Point2D const&)") { Moopad.unitDiff(it.p("a"), it.p("b"), it.mpF(Moopad.OFF_UNIT)) }
    regF("Emkey::Moopad::hybridDist(Point2D const&, Point2D const&)") { Moopad.hybridDist(it.p("a"), it.p("b"), it.mpF(Moopad.OFF_HYBRID)) }
    regP("Emkey::Moopad::hybridDiff(Point2D const&, Point2D const&)") { Moopad.hybridDiff(it.p("a"), it.p("b"), it.mpF(Moopad.OFF_HYBRID)) }
    regF("Emkey::Moopad::balancedDist(Point2D const&, Point2D const&)") {
        Moopad.balancedDist(it.p("a"), it.p("b"), it.mpF(Moopad.OFF_KEY_W), it.mpF(Moopad.OFF_KEY_H))
    }
    regP("Emkey::Moopad::balancedDiff(Point2D const&, Point2D const&)") {
        Moopad.balancedDiff(it.p("a"), it.p("b"), it.mpF(Moopad.OFF_KEY_W), it.mpF(Moopad.OFF_KEY_H))
    }
    regF("Emkey::Moopad::xForgivenLength(Point2D const&, Point2D const&, bool, Point2D const&, float)") {
        Moopad.xForgivenLength(it.p("a"), it.p("b"), it.args[0].asBool(), it.p("dir"), it.f(1),
            it.mpF(Moopad.OFF_XFORGIVEN), it.mpF(Moopad.OFF_KEY_H))
    }

    // Emkey::calculate_*
    regF("Emkey::calculate_backtouchtravel_mult(double, double)") { Calculate.backtouchtravelMult(it.args[0].asDouble(), it.args[1].asDouble()) }
    regF("Emkey::calculate_backtravel_mult(double, double, bool)") {
        Calculate.backtravelMult(it.args[0].asDouble(), it.args[1].asDouble(), it.args[2].asBool())
    }
    regF("Emkey::calculate_strict_backtravel_mult(double, double)") { Calculate.strictBacktravelMult(it.args[0].asDouble(), it.args[1].asDouble()) }
    regF("Emkey::calculate_coverage_score(Point2D const&, Point2D const&, Point2D const&, Emkey::Moopad*)") {
        Calculate.coverageScore(it.p("p1"), it.p("p2"), it.p("key"), it.mpF(Moopad.OFF_UNIT))
    }
    reg("Emkey::calculate_triecheck_lookbuffer(int)") { CaseResult(ret = Leaf.i32(Calculate.triecheckLookbuffer(it.args[0].asInt()))) }
    regF("Emkey::calculateImportantPointRevisitMult(int)") { Calculate.importantPointRevisitMult(it.args[0].asInt()) }
    regF("Emkey::calculatePointRevisitMult(int)") { Calculate.pointRevisitMult(it.args[0].asInt()) }
    reg("Emkey::calculate_triecheck_indexes(int, float, int, int&, int&)") {
        val (lo, hi) = Calculate.triecheckIndexes(it.args[0].asInt(), it.f(1), it.args[2].asInt())
        CaseResult(out = mapOf("lo" to Values.struct("@0x0" to Leaf.i32(lo).toJson()), "hi" to Values.struct("@0x0" to Leaf.i32(hi).toJson())))
    }

    // MooTouchPoint
    val mtp = "Emkey::MooTouchPoint::"
    regF("${mtp}getImportanceScore(bool)") { MooTouchPoint.getImportanceScore(it.img("this"), it.args[0].asBool()) }
    regF("${mtp}revisitPenalty()") { MooTouchPoint.revisitPenalty(it.img("this")) }
    regF("${mtp}revisitMultpen()") { MooTouchPoint.revisitMultpen(it.img("this")) }
    regF("${mtp}estimateCharCount()") { MooTouchPoint.estimateCharCount(it.img("this")) }
    reg("${mtp}isMidStraightSlowdown()") { CaseResult(ret = Leaf("u16", if (MooTouchPoint.isMidStraightSlowdown(it.img("this"))) 1L else 0L)) }
    reg("${mtp}isSlidePauserPoint()") { CaseResult(ret = Leaf.bool(MooTouchPoint.isSlidePauserPoint(it.img("this")))) }
    reg("${mtp}isVisitationPoint()") { CaseResult(ret = Leaf.bool(MooTouchPoint.isVisitationPoint(it.img("this")))) }
    regF("${mtp}twerkStopDuration()") { MooTouchPoint.twerkStopDuration(it.img("this")) }
    regF("${mtp}displacementInsensitivityFactor()") { MooTouchPoint.displacementInsensitivityFactor(it.img("this")) }
    regF("${mtp}timePenalty(double)") { MooTouchPoint.timePenalty(it.img("this"), it.args[0].asDouble()) }
    regF("${mtp}novisitMultpen()") { MooTouchPoint.novisitMultpen(it.img("this")) }
    regF("${mtp}nonGenericSoftIndexDistance(Emkey::MooTouchPoint*)") { MooTouchPoint.nonGenericSoftIndexDistance(it.img("this"), it.img("o")) }
    reg("${mtp}syncAngle(Emkey::MooTouchPoint&, Emkey::MooTouchPoint&)") {
        val w = it.world()
        MooTouchPoint.syncAngle(w["this"], w["prev"], w["next"])
        CaseResult(mem = w.images)
    }
    reg("${mtp}determineDirFromArg(float)") {
        val tp = MemImage("this", MooTouchPoint.SIZE)
        MooTouchPoint.determineDirFromArg(tp, it.f(0))
        CaseResult(mem = mapOf("this" to tp))
    }
    val finds: Map<String, (nintype.engine.vectors.MemImage, Float) -> nintype.engine.vectors.MemImage> = mapOf(
        "findEarlierSameFing" to MooTouchPoint::findEarlierSameFing,
        "findLaterSameFing" to MooTouchPoint::findLaterSameFing,
        "findCloserSameFingShiftedBy" to MooTouchPoint::findCloserSameFingShiftedBy,
        "findEarlierAnyFing" to MooTouchPoint::findEarlierAnyFing,
        "findLaterAnyFing" to MooTouchPoint::findLaterAnyFing,
        "findCloserAnyFingShiftedBy" to MooTouchPoint::findCloserAnyFingShiftedBy,
    )
    for ((n, fn) in finds) reg("${mtp}$n(float)") {
        val w = it.world()
        CaseResult(retValue = Values.ref(fn(w[it.inputs["this"]!!["\$ref"]!!.asString()], it.f(0)).name))
    }
    // syncCoverage(Point2D const&, Point2D const&, Emkey::Moopad*): registered by ports/TouchPorts.kt (touch area, both branches)
    regF("${mtp}coverageForChar(int)") { MooTouchPoint.coverageForChar(it.img("this"), it.args[0].asInt()) }
    reg("${mtp}determineDirection(Emkey::MooTouchPoint&)") {
        val w = it.world()
        MooTouchPoint.determineDirection(w["this"], w["o"])
        CaseResult(mem = w.images)
    }

    // DirectionalCoverage: bins = $vec at +0x14
    fun dc(img: MemImage) = DirectionalCoverage(img.seq(0x14).map { Leaf.fromJson(it).asInt() }.toIntArray())
    fun dcBack(img: MemImage, d: DirectionalCoverage): Map<String, MemImage> {
        img.seqs[0x14] = d.bins.map { Leaf("f32", it.toLong() and 0xFFFFFFFFL).toJson() }.toMutableList()
        return mapOf("this" to img)
    }
    val dcn = "Emkey::DirectionalCoverage::"
    reg("${dcn}getIndex(Point2D const&)") { CaseResult(ret = Leaf.i32(DirectionalCoverage.getIndex(it.p("dir")))) }
    reg("${dcn}getCoverage(Point2D const&)") {
        val img = it.img("this"); val d = dc(img)
        val r = d.getCoverage(it.p("dir"))
        CaseResult(ret = Leaf.f32(r), mem = dcBack(img, d))
    }
    reg("${dcn}getCoverage(int)") {
        val img = it.img("this"); val d = dc(img)
        CaseResult(ret = Leaf.f32(d.getCoverage(it.args[0].asInt())), mem = dcBack(img, d))
    }
    reg("${dcn}record(Emkey::DirectionalCoverage const&, float)") {
        val img = it.img("this"); val d = dc(img)
        d.record(dc(it.img("o")), it.f(0))
        CaseResult(mem = dcBack(img, d))
    }
    reg("${dcn}record(Point2D const&, float, float)") {
        val img = it.img("this"); val d = dc(img)
        d.record(it.p("dir"), it.f(0), it.f(1))
        CaseResult(mem = dcBack(img, d))
    }
}
