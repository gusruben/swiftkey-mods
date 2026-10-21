package nintype.engine.ports

import nintype.engine.pipeline.MootouchFactory
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.Values

/** Vector adapters for re/vectors/gen_pipeline.py. */
fun registerPipelinePorts() {
    PortRegistry.register("Emkey::Mootouch::Mootouch()") { c ->
        val w = c.world()
        val body = w["this"]
        val counter = w["counter"].i32(0)
        val clk = w["clock"]
        val (_, next) = MootouchFactory.construct(body, counter, clk.f64(0), clk.f64(8))
        CaseResult(out = mapOf("counter" to Values.struct("@0x0" to Leaf.i32(next).toJson())), mem = mapOf("this" to body))
    }
}
