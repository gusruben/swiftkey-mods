package app.revanced.patches.swiftkey.typing

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.stringReference
import app.revanced.patcher.extensions.typeReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import app.revanced.patches.swiftkey.misc.settings.modsSettingsPatch
import app.revanced.patches.swiftkey.misc.toolbar.registerToolbarItem
import app.revanced.patches.swiftkey.misc.toolbar.toolbarItemsPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/typing/CapitalIPatch;"

// The toolbar id (90) lives in the extension: CapitalIPatch.TOOLBAR_ITEM_ID. Its icons are drawn
// there too (ToolbarIcons), so this patch changes no resources.

private fun Method.findMoveResultRegisterAfterCall(methodName: String): Pair<Int, Int> {
    val callIndex = indexOfFirstInstructionOrThrow {
        opcode == Opcode.INVOKE_DIRECT && methodReference?.name == methodName
    }
    val moveResult = getInstruction(callIndex + 1)
    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("No result for $methodName in $this")
    return callIndex + 1 to (moveResult as OneRegisterInstruction).registerA
}

@Suppress("unused")
val capitalIPatch = bytecodePatch(
    name = "Capital I toggle",
    description = "Adds a toolbar button that turns the automatic \"i\" to \"I\" capitalization (also i'm, i'll, " +
        "i've, i'd) on or off. Drag it from the toolbar's extra items into the bar.",
) {
    compatibleWith("com.touchtype.swiftkey")

    dependsOn(toolbarItemsPatch)

    dependsOn(modsSettingsPatch)


    extendWith("extensions/swiftkey.rve")

    apply {
        // State lives in the extension; it needs a context for its preferences and toasts.
        keyboardServiceOnCreateMethod.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->init(Landroid/content/Context;)V",
        )

        // The "I" comes from the language model itself, not from the auto-capitalize setting,
        // so it's undone where candidates leave native code. Both are rewritten before they're
        // cached, so every reader (suggestion bar, autocorrect commit, term tokens) sees the same text.
        predictionGetPredictionMethod.apply {
            val (moveResultIndex, register) = findMoveResultRegisterAfterCall("convertPrediction")
            addInstructions(
                moveResultIndex + 1,
                """
                    invoke-static { p0, v$register }, $EXTENSION_CLASS_DESCRIPTOR->fixPrediction(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """,
            )
        }

        // Term[] passes as Object[]; the extension swaps the first term in place.
        predictionGetTermMethod.apply {
            val (moveResultIndex, register) = findMoveResultRegisterAfterCall("convertTerms")
            addInstruction(
                moveResultIndex + 1,
                "invoke-static { p0, v$register }, $EXTENSION_CLASS_DESCRIPTOR->fixTerms(Ljava/lang/Object;[Ljava/lang/Object;)V",
            )
        }

        // The button: id in the known toolbar items, built by ToolbarItems (misc/toolbar).
        registerToolbarItem("addCapitalI")
    }
}
