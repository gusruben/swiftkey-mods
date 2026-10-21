package nintype.engine.ports

import nintype.engine.math.CurveMapper
import nintype.engine.math.FastCurveMapper
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.Values

/** Vector adapters for FastCurveMapper / CurveMapper (re/vectors/gen_curvemapper.py). */

private fun pairs(seq: JsonValue): List<Pair<Int, Int>> = seq.asArray().map { p ->
    Leaf.fromJson(p["first"]!!).asInt() to Leaf.fromJson(p["second"]!!).asInt()
}

private fun pairJson(x: Int, y: Int): JsonValue = Values.struct(
    "first" to Leaf("f32", x.toLong() and 0xFFFFFFFFL).toJson(),
    "second" to Leaf("f32", y.toLong() and 0xFFFFFFFFL).toJson(),
)

internal fun decodeFcm(obj: JsonValue): FastCurveMapper {
    val c = FastCurveMapper()
    for ((x, y) in pairs(obj["@0x0:pts"]!!["\$vec"]!!)) c.pushBits(x, y)
    return c
}

private fun encodeFcm(c: FastCurveMapper): JsonValue =
    Values.struct("@0x0:pts" to Values.vec((0 until c.size).map { pairJson(c.xs[it], c.ys[it]) }))

private fun decodeCm(obj: JsonValue): CurveMapper {
    val c = CurveMapper()
    for ((x, y) in pairs(obj["@0x4:map"]!!["\$map"]!!)) c.put(java.lang.Float.intBitsToFloat(x), java.lang.Float.intBitsToFloat(y))
    return c
}

private fun encodeCm(c: CurveMapper): JsonValue =
    Values.struct("@0x4:map" to Values.map((0 until c.size).map { pairJson(c.keyBits(it), c.valueBits(it)) }))

fun registerCurvePorts() {
    PortRegistry.register("FastCurveMapper::get(float)") { case ->
        CaseResult(ret = Leaf.f32(decodeFcm(case.inputs["this"]!!).get(case.args[0].asFloat())))
    }
    PortRegistry.register("FastCurveMapper::finalize()") { case ->
        CaseResult(out = mapOf("this" to encodeFcm(decodeFcm(case.inputs["this"]!!).finalize())))
    }
    PortRegistry.register("FastCurveMapper::operator()(float, float)") { case ->
        val c = decodeFcm(case.inputs["this"]!!).pushBits(case.args[0].asInt(), case.args[1].asInt())
        CaseResult(out = mapOf("this" to encodeFcm(c)))
    }

    PortRegistry.register("CurveMapper::get(float)") { case ->
        CaseResult(ret = Leaf.f32(decodeCm(case.inputs["this"]!!).get(case.args[0].asFloat())))
    }
    PortRegistry.register("CurveMapper::get(float, float)") { case ->
        CaseResult(ret = Leaf.f32(decodeCm(case.inputs["this"]!!).get(case.args[0].asFloat(), case.args[1].asFloat())))
    }
    PortRegistry.register("CurveMapper::getInterpolated(float)") { case ->
        CaseResult(ret = Leaf.f32(decodeCm(case.inputs["this"]!!).getInterpolated(case.args[0].asFloat())))
    }
    PortRegistry.register("CurveMapper::operator()(float, float)") { case ->
        val c = decodeCm(case.inputs["this"]!!).put(case.args[0].asFloat(), case.args[1].asFloat())
        CaseResult(out = mapOf("this" to encodeCm(c)))
    }
    PortRegistry.register("CurveMapper::operator[](float)") { case ->
        val c = decodeCm(case.inputs["this"]!!)
        val r = c.index(case.args[0].asFloat())
        CaseResult(ret = Leaf.f32(r), out = mapOf("this" to encodeCm(c)))
    }
    PortRegistry.register("CurveMapper::clear()") { case ->
        val c = decodeCm(case.inputs["this"]!!)
        c.clear()
        CaseResult(out = mapOf("this" to encodeCm(c)))
    }
}
