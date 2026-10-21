package app.revanced.patches.swiftkey.misc.toolbar

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/toolbar/ToolbarItems;"
private const val EXTENSION_ICONS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/toolbar/ToolbarIcons;"

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
        // The on/off item class (autocorrect, incognito): 12 constructor parameters, see
        // ToolbarItems.toggleItem. Built reflectively, so the extension never names it.
        val toggleItemClass = toolbarItemFactoryMethod.instructions.firstNotNullOfOrNull { instruction ->
            instruction.methodReference?.takeIf { reference ->
                val parameters = reference.parameterTypes.map(CharSequence::toString)
                reference.name == "<init>" && parameters.size == 12 && parameters[0] == "I" &&
                    parameters[2] == "Ljava/util/function/Supplier;" &&
                    parameters[9] == "Ljava/util/Collection;" &&
                    parameters[11] == "Ljava/util/function/Supplier;"
            }?.definingClass
        } ?: throw PatchException("Could not find the toggle toolbar item class")

        toolbarItemFactoryMethod.apply {
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

        hookToolbarIcons(toggleItemClass)
    }
}

/**
 * The mods' buttons draw their icons in code (ToolbarIcons), so they need no resources. An item's
 * icon is a resource id: the item interface's int getter other than getItemId. Everywhere SwiftKey
 * reads it, the id goes straight into Context.getDrawable or ImageView.setImageResource (the bar,
 * the toggle's redraw, the expanded grid, the customiser); each of those calls goes through
 * ToolbarIcons instead, which draws the mods' ids and passes every other id on unchanged.
 */
private fun BytecodePatchContext.hookToolbarIcons(toggleItemClass: String) {
    val itemInterface = classDefs.firstOrNull { it.type == toggleItemClass }?.interfaces?.singleOrNull()
        ?: throw PatchException("Expected one interface on $toggleItemClass")
    val iconGetter = classDefs.firstOrNull { it.type == itemInterface }?.methods?.singleOrNull {
        it.returnType == "I" && it.parameterTypes.isEmpty() && it.name != "getItemId"
    }?.name ?: throw PatchException("Could not find the toolbar item icon getter in $itemInterface")

    fun Instruction.isIconCall() = methodReference?.let { reference ->
        reference.name == iconGetter && reference.returnType == "I" && reference.parameterTypes.isEmpty() &&
            (reference.definingClass == itemInterface || reference.definingClass == toggleItemClass)
    } == true

    fun MethodReference.hookFor() = when {
        name == "setImageResource" && returnType == "V" && parameterTypes.map(CharSequence::toString) == listOf("I") ->
            "setImageResource(Landroid/widget/ImageView;I)V"
        name == "getDrawable" && definingClass == "Landroid/content/Context;" &&
            parameterTypes.map(CharSequence::toString) == listOf("I") ->
            "getDrawable(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;"
        else -> null
    }

    val readers = classDefs.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.implementation?.instructions?.any { it.isIconCall() } == true
        }.map { classDef.type to it }
    }
    if (readers.isEmpty()) throw PatchException("Could not find where toolbar icons are loaded")

    readers.forEach { (type, reader) ->
        val method = firstClassDef(type).methods.first { MethodUtil.methodSignaturesMatch(it, reader) }
        method.instructions.withIndex().filter { (_, instruction) -> instruction.isIconCall() }.forEach { (index, _) ->
            val moveResult = method.getInstruction(index + 1)
            if (moveResult.opcode != Opcode.MOVE_RESULT) throw PatchException("No icon id result in $method")
            val idRegister = (moveResult as OneRegisterInstruction).registerA
            // The id's use follows within a few instructions (after a cast or a view lookup).
            val useIndex = (index + 2 until minOf(index + 10, method.instructions.size)).firstOrNull { useIndex ->
                val use = method.getInstruction(useIndex)
                use.opcode == Opcode.INVOKE_VIRTUAL && use.methodReference?.hookFor() != null &&
                    (use as FiveRegisterInstruction).registerCount == 2 && use.registerD == idRegister
            } ?: throw PatchException("Unhandled toolbar icon use in $method")
            val use = method.getInstruction<FiveRegisterInstruction>(useIndex)
            method.replaceInstruction(
                useIndex,
                "invoke-static { v${use.registerC}, v${use.registerD} }, " +
                    "$EXTENSION_ICONS_DESCRIPTOR->${method.getInstruction(useIndex).methodReference!!.hookFor()}",
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
