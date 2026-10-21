package nintype.engine.vectors

import nintype.engine.curve.registerCurvePort
import nintype.engine.ports.registerCurvePorts
import nintype.engine.ports.registerTouchMathPorts

/** Single place that registers every ported function into [PortRegistry].
 * Call [registerAll] once before running vector tests or the scorecard.
 * Add one line here per new ported function -- this is what makes a
 * function show up as "ported" in SCORE.md independent of whether a
 * vector file exists yet to verify it. */
object Ports {
    private var done = false

    @Synchronized
    fun registerAll() {
        if (done) return
        registerCurvePort()
        registerCurvePorts()
        registerTouchMathPorts()
        nintype.engine.ports.registerMergePorts()
        nintype.engine.ports.registerSearchPorts()
        nintype.engine.ports.registerPathscorePorts()
        nintype.engine.ports.registerGeometryPorts()
        nintype.engine.ports.registerTouchPorts()
        nintype.engine.ports.registerTriePorts()
        nintype.engine.ports.registerPipelinePorts()
        done = true
    }
}
