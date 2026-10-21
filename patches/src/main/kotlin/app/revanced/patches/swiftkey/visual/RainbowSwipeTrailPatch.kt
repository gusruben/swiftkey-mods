package app.revanced.patches.swiftkey.visual

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import app.revanced.patches.swiftkey.misc.settings.modsSettingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/visual/RainbowTrailPatch;"

private fun Instruction.isPaintSetColor() = opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
    it.definingClass == "Landroid/graphics/Paint;" && it.name == "setColor" && it.returnType == "V" &&
        it.parameterTypes.map(CharSequence::toString) == listOf("I")
} == true

// Registers taken by a parameter list, plus p0 for `this`. Long and double take two.
private fun List<String>.parameterRegisterCount() = 1 + sumOf { if (it == "J" || it == "D") 2 else 1 }

@Suppress("unused")
val rainbowSwipeTrailPatch = bytecodePatch(
    name = "Rainbow swipe trail",
    description = "Makes the Flow (swipe typing) trail cycle through the rainbow along its length, regardless of the theme.",
) {
    compatibleWith("com.touchtype.swiftkey")

    dependsOn(modsSettingsPatch)


    extendWith("extensions/swiftkey.rve")

    apply {
        val renderPointType = renderPointToStringMethod.definingClass
        val distanceField = renderPointToStringMethod.findFieldFromToString(", distance=")

        // The trail view draws each stroke one call per segment (or per lone dot), passing the
        // render points involved. Every Flow trail type (LEGACY, SNOW, DEFAULT, LIGHT_DEFAULT) sets
        // the shared Paint's colour in those calls from the theme colours and the point's age.
        // Renderers that never call setColor there (the plain path one) are left alone.
        fun Method.isTrailDrawMethod() = returnType == "V" &&
            !AccessFlags.STATIC.isSet(accessFlags) &&
            parameterTypes.firstOrNull()?.toString() == "Landroid/graphics/Canvas;" &&
            parameterTypes.any { it.toString() == renderPointType } &&
            instructionsOrNull?.any { it.isPaintSetColor() } == true

        val drawMethods = classDefs.flatMap { classDef -> classDef.methods.filter { it.isTrailDrawMethod() } }
        if (drawMethods.isEmpty()) throw PatchException("Could not find the swipe trail draw methods")

        drawMethods.map { firstMethod(it) }.forEach { method ->
            val parameterTypes = method.parameterTypes.map(CharSequence::toString)
            if (method.implementation!!.registerCount <= parameterTypes.parameterRegisterCount()) {
                throw PatchException("No local register to use in $method")
            }

            // Keep the theme's alpha (the fade-out) but swap the colour for a hue from the extension.
            method.instructions.withIndex().filter { (_, instruction) -> instruction.isPaintSetColor() }
                .map { it.index }.reversed().forEach { index ->
                    val colorRegister = method.getInstruction<FiveRegisterInstruction>(index).registerD

                    method.addInstructions(
                        index,
                        """
                            invoke-static { v$colorRegister }, $EXTENSION_CLASS_DESCRIPTOR->getTrailColor(I)I
                            move-result v$colorRegister
                        """,
                    )
                }

            // The last point parameter is the one being drawn to; its distance along the stroke picks the hue.
            // v0 is an unassigned local at entry. Parameter registers can be above v15, which iget can't address.
            val pointRegister = parameterTypes.take(parameterTypes.lastIndexOf(renderPointType)).parameterRegisterCount()
            method.addInstructions(
                0,
                """
                    move-object/from16 v0, p$pointRegister
                    iget v0, v0, $distanceField
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->setPointDistance(F)V
                """,
            )
        }
    }
}
