package nintype.engine.curve

import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.VectorCase

/** Decodes a vector case's "c" struct snapshot into a [Curve]. The `points`
 * array is always serialized at its fixed size (4); only the first `count`
 * entries are meaningful, matching the padding a real fixed-size C array
 * would have. */
private fun decodeCurve(struct: JsonValue): Curve {
    val fields = struct.asObject()
    val count = Leaf.fromJson(fields["count"]!!).asInt()
    val points = fields["points"]!!.asArray().map { p ->
        val pf = p.asObject()
        Point(Leaf.fromJson(pf["x"]!!).asFloat(), Leaf.fromJson(pf["y"]!!).asFloat())
    }
    return Curve(points, count)
}

/** Registers the `nt_curve_eval` port adapter (see Curve.kt) so both the
 * curve vector test and the scorecard tool can dispatch to it by name. */
fun registerCurvePort() {
    PortRegistry.register("nt_curve_eval") { case: VectorCase ->
        val curve = decodeCurve(case.inputs["c"]!!)
        val x = case.args[0].asFloat()
        val result = evalCurve(curve, x)
        CaseResult(ret = Leaf.f32(result))
    }
}
