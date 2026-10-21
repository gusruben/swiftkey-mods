package app.revanced.patches.swiftkey.misc.toolbar

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.stringReference
import app.revanced.patcher.extensions.typeReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.swiftkey.typing.toolbarBuildMethod
import app.revanced.patches.swiftkey.typing.toolbarItemFactoryMethod
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/toolbar/ToolbarItems;"

/**
 * Lets the mods add toolbar buttons: the toolbar item factory asks ToolbarItems.create for every id
 * it has no case for. Each button's patch also calls [registerToolbarItem] with its ToolbarItems
 * add method, which appends its id to the known items (new ids start in the toolbar's extra items).
 */
internal val toolbarItemsPatch = bytecodePatch(
    description = "Adds the mods' toolbar buttons.",
) {
    extendWith("extensions/swiftkey.rve")

    apply {
        toolbarItemFactoryMethod.apply {
            // The on/off item class (autocorrect, incognito): 12 constructor parameters, see
            // ToolbarItems.toggleItem. Built reflectively, so the extension never names it.
            val toggleItemClass = instructions.firstNotNullOfOrNull { instruction ->
                instruction.methodReference?.takeIf { reference ->
                    val parameters = reference.parameterTypes.map(CharSequence::toString)
                    reference.name == "<init>" && parameters.size == 12 && parameters[0] == "I" &&
                        parameters[2] == "Ljava/util/function/Supplier;" &&
                        parameters[9] == "Ljava/util/Collection;" &&
                        parameters[11] == "Ljava/util/function/Supplier;"
                }?.definingClass
            } ?: throw PatchException("Could not find the toggle toolbar item class")

            val messageIndex = indexOfFirstInstructionOrThrow {
                stringReference?.string == "No toolbar item for toolbar item type "
            }
            // Ids no case handles fall through to this throw. The exception's register is
            // overwritten there anyway, so it's free to use before it.
            val throwStartIndex = indexOfFirstInstructionReversedOrThrow(messageIndex) {
                opcode == Opcode.NEW_INSTANCE && typeReference?.type == "Ljava/lang/IllegalArgumentException;"
            }
            val freeRegister = getInstruction<OneRegisterInstruction>(throwStartIndex).registerA
            // The message is built from the id: the first argument of the next static call.
            val idRegister = getInstruction<FiveRegisterInstruction>(
                indexOfFirstInstructionOrThrow(messageIndex, Opcode.INVOKE_STATIC),
            ).registerC
            if (freeRegister > 15 || idRegister > 15) throw PatchException("Toolbar item registers out of range")

            addInstructionsWithLabels(
                throwStartIndex,
                """
                    const-class v$freeRegister, $toggleItemClass
                    invoke-static { v$freeRegister, v$idRegister }, $EXTENSION_CLASS_DESCRIPTOR->create(Ljava/lang/Class;I)Ljava/lang/Object;
                    move-result-object v$freeRegister
                    if-eqz v$freeRegister, :not_ours
                    return-object v$freeRegister
                """,
                ExternalLabel("not_ours", getInstruction(throwStartIndex)),
            )
        }
    }
}

/** Appends a button id to SwiftKey's known toolbar items: [addMethod] is a ToolbarItems method (List)List. */
internal fun BytecodePatchContext.registerToolbarItem(addMethod: String) {
    val knownItemsField = toolbarBuildMethod.instructions.firstNotNullOfOrNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.SGET_OBJECT }?.fieldReference
            ?.takeIf { it.type == "Ljava/util/List;" }
    } ?: throw PatchException("Could not find the known toolbar items list")

    firstClassDef(knownItemsField.definingClass).methods.first { it.name == "<clinit>" }.apply {
        // At the final return-void every register is free.
        addInstructions(
            instructions.lastIndex,
            """
                sget-object v0, $knownItemsField
                invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->$addMethod(Ljava/util/List;)Ljava/util/List;
                move-result-object v0
                sput-object v0, $knownItemsField
            """,
        )
    }
}
