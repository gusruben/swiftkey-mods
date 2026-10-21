package app.revanced.patches.swiftkey.visual

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.revanced.patches.swiftkey.misc.settings.modsSettingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/visual/KeyHintWatermark;"
private const val SETTINGS_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/settings/ModSettings;"
private const val EXTENSION_HINT_CONTENT_INTERFACE =
    "Lapp/revanced/extension/swiftkey/visual/KeyHintWatermark\$HintContent;"

private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val OBJECT = "Ljava/lang/Object;"

private fun Instruction.isBooleanValue() = opcode == Opcode.INVOKE_VIRTUAL && methodReference?.let {
    it.definingClass == "Ljava/lang/Boolean;" && it.name == "booleanValue"
} == true

@Suppress("unused")
val watermarkKeyHintsPatch = bytecodePatch(
    name = "Watermark key hints",
    description = "Draws each key's long-press symbol as a large, faint watermark behind its letter, " +
        "cropped to the key, instead of the small corner hint. Works with any theme and replaces " +
        "the \"Long-press symbols\" setting.",
) {
    compatibleWith("com.touchtype.swiftkey")

    dependsOn(keyDrawHookPatch)

    dependsOn(modsSettingsPatch)


    extendWith("extensions/swiftkey.rve")

    apply {
        // With the setting off, the layout drops each key's top label before the key is built,
        // so there would be nothing to draw. The stock hint is hidden below instead. With the
        // watermark switched off in Mods, the user's own setting applies again.
        alwaysShowTopTextPreferenceMethod.apply {
            if (implementation!!.registerCount <= parameterTypes.size + 1) {
                throw PatchException("No free register in the long-press symbols setting getter")
            }
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $SETTINGS_CLASS_DESCRIPTOR->watermarkHints()Z
                    move-result v0
                    if-eqz v0, :stock_setting
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("stock_setting", getInstruction(0)),
            )
        }

        // Letter keys with a long-press symbol have content that stacks a top label over the letter.
        val contentType = hintContentToStringMethod.definingClass
        val topField = hintContentToStringMethod.findFieldFromToString(", Top: ")
        val contentClass = firstClassDef(contentType)

        // Its render reads one boolean: whether the key's theme style shows top text. If so it
        // splits the key between both labels; if not it renders the letter alone, centred.
        val renderMethod = contentClass.methods.singleOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.instructionsOrNull?.any { it.isBooleanValue() } == true
        } ?: throw PatchException("Could not find the hint content's render method")
        val renderParameters = renderMethod.parameterTypes.map(CharSequence::toString)
        if (renderParameters.size != 3) throw PatchException("Unexpected render method $renderMethod")
        val renderInstructions = renderMethod.instructions.toList()

        // The last label render is the letter alone, in the main text style.
        val letterRenderIndex = renderInstructions.indexOfLast {
            it.opcode == Opcode.INVOKE_INTERFACE && it.methodReference?.let { reference ->
                reference.name == renderMethod.name && reference.returnType == renderMethod.returnType &&
                    reference.parameterTypes.map(CharSequence::toString) == renderParameters
            } == true
        }.takeIf { it > 0 } ?: throw PatchException("Could not find the letter's render call")
        val labelRenderMethod = renderInstructions[letterRenderIndex].methodReference!!
        val mainStyleField = renderInstructions[letterRenderIndex - 1].takeIf {
            it.opcode == Opcode.SGET_OBJECT && it.fieldReference?.type == renderParameters[2]
        }?.fieldReference ?: throw PatchException("Could not find the main text style")
        if (topField.type != labelRenderMethod.definingClass) {
            throw PatchException("The top label field $topField is not key content")
        }

        // Lets the extension render the top label like the letter, to read its text and font.
        contentClass.interfaces.add(EXTENSION_HINT_CONTENT_INTERFACE)
        contentClass.methods.add(
            ImmutableMethod(
                contentType,
                "patch_buildHint",
                listOf(OBJECT, OBJECT).map { ImmutableMethodParameter(it, null, null) },
                DRAWABLE,
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(5),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $topField
                        check-cast p1, ${renderParameters[0]}
                        check-cast p2, ${renderParameters[1]}
                        sget-object v1, $mainStyleField
                        invoke-interface { v0, p1, p2, v1 }, $labelRenderMethod
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )

        renderMethod.apply {
            val flagIndex = renderInstructions.indexOfFirst { it.isBooleanValue() }
            if (getInstruction(flagIndex + 1).opcode != Opcode.MOVE_RESULT) {
                throw PatchException("Could not find the top text flag in $this")
            }
            val flagRegister = getInstruction<OneRegisterInstruction>(flagIndex + 1).registerA

            // Hide the stock corner hint: the letter renders alone and centred, as with the setting off.
            addInstructions(
                flagIndex + 2,
                """
                    invoke-static/range { v$flagRegister .. v$flagRegister }, $EXTENSION_CLASS_DESCRIPTOR->showStockTopText(Z)Z
                    move-result v$flagRegister
                """,
            )

            // The renderer and style are untouched at entry.
            addInstructions(
                0,
                "invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->onRenderHintContent($OBJECT$OBJECT$OBJECT)V",
            )
        }

        // The content renders while the key drawable is built; hand its top label to that drawable.
        keyDrawableFactoryMethod.apply {
            instructions.withIndex().filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }.reversed().forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS_DESCRIPTOR->onKeyDrawableCreated($DRAWABLE)V",
                    )
                }

            addInstructions(0, "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->clearPendingHint()V")
        }
    }
}
