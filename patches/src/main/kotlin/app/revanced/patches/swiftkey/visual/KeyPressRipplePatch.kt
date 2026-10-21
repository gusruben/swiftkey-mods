package app.revanced.patches.swiftkey.visual

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.type
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.revanced.patches.swiftkey.misc.settings.modsSettingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/visual/KeyPressRipple;"
private const val EXTENSION_KEYBOARD_VIEW_INTERFACE =
    "Lapp/revanced/extension/swiftkey/visual/KeyPressRipple\$KeyboardView;"

private const val RECT_F = "Landroid/graphics/RectF;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val OBJECT = "Ljava/lang/Object;"

// Registers taken by a parameter list, plus p0 for `this`. Long and double take two.
private fun List<String>.registersWithThis() = 1 + sumOf { if (it == "J" || it == "D") 2 else 1 }

private fun Instruction.isInvoke() = opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_VIRTUAL

private fun Instruction.invokeMnemonic() =
    if (opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"

@Suppress("unused")
val keyPressRipplePatch = bytecodePatch(
    name = "Key press ripple",
    description = "Sends a thin rainbow ring outwards from the edge of every key you press.",
) {
    compatibleWith("com.touchtype.swiftkey")

    // Records where each key's background really draws, which the ring and its dimmed overlap follow.
    dependsOn(keyDrawHookPatch)

    dependsOn(modsSettingsPatch)


    extendWith("extensions/swiftkey.rve")

    apply {
        val keyboardViewClass = keyboardViewPointMethod.classDef
        val keyboardViewType = keyboardViewClass.type

        fun findMethod(description: String, predicate: (List<String>, String) -> Boolean) =
            keyboardViewClass.methods.singleOrNull { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
                    predicate(method.parameterTypes.map(CharSequence::toString), method.returnType)
            } ?: throw PatchException("Could not find the keyboard view's $description method")

        // onDraw draws every key: it reads the key list, the theme, each key's drawable and its area.
        // Taking all of these references from it keeps the patch free of hardcoded obfuscated names.
        val onDrawInstructions = keyboardViewClass.methods.singleOrNull {
            it.name == "onDraw" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/graphics/Canvas;")
        }?.instructions?.toList() ?: throw PatchException("Could not find the keyboard view's onDraw method")

        fun onDrawIndex(description: String, predicate: (Instruction) -> Boolean) =
            onDrawInstructions.indexOfFirst(predicate).takeIf { it >= 0 }
                ?: throw PatchException("Could not find the $description in the keyboard view's onDraw")

        fun onDrawInstruction(description: String, predicate: (Instruction) -> Boolean) =
            onDrawInstructions[onDrawIndex(description, predicate)]

        val keyListIndex = onDrawIndex("key list") {
            it.opcode == Opcode.IGET_OBJECT && it.fieldReference?.type == "Ljava/util/ArrayList;"
        }
        val keyListField = onDrawInstructions[keyListIndex].fieldReference!!
        val keyboardModelField = onDrawInstructions.getOrNull(keyListIndex - 1)?.fieldReference
            ?.takeIf { it.type == keyListField.definingClass }
            ?: throw PatchException("Could not find the keyboard model field in the keyboard view's onDraw")

        val keyDrawableInstruction = onDrawInstruction("key drawable call") {
            it.isInvoke() && it.methodReference?.let { reference ->
                reference.returnType == DRAWABLE && reference.parameterTypes.size == 1
            } == true
        }
        val keyDrawableMethod = keyDrawableInstruction.methodReference!!
        val keyType = keyDrawableMethod.definingClass
        val themeType = keyDrawableMethod.parameterTypes.single().toString()

        val themeField = onDrawInstruction("theme field") {
            it.opcode == Opcode.IGET_OBJECT && it.fieldReference?.type == themeType
        }.fieldReference!!

        val keyGeometryInstruction = onDrawInstruction("key geometry call") {
            it.isInvoke() && it.methodReference?.let { reference ->
                reference.definingClass == keyType && reference.parameterTypes.isEmpty() &&
                    reference.returnType.startsWith("L")
            } == true
        }
        val keyGeometryMethod = keyGeometryInstruction.methodReference!!
        val geometryType = keyGeometryMethod.returnType

        val keyAreaField = onDrawInstruction("key area field") {
            it.opcode == Opcode.IGET_OBJECT && it.fieldReference?.let { reference ->
                reference.definingClass == geometryType && reference.type == RECT_F
            } == true
        }.fieldReference!!

        // The geometry class has two RectF getters: the inset ratio (with the "Divisor" check)
        // and the background rect (area minus inset). The extension uses the latter only for a key
        // drawable that hasn't drawn yet.
        if (keyGeometryInsetRatioMethod.definingClass != geometryType) {
            throw PatchException("The key geometry class from onDraw is not the one with the inset ratio helper")
        }
        val keyBackgroundMethod = keyGeometryInsetRatioMethod.immutableClassDef.methods.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == RECT_F &&
                it.parameterTypes.isEmpty() && it.name != keyGeometryInsetRatioMethod.name
        }?.let { "$geometryType->${it.name}()$RECT_F" }
            ?: throw PatchException("Could not find the key background rect method")

        // f(…, MotionEvent) wraps the event in a touch event object and reads it back with a cast.
        val touchEventInstructions = findMethod("touch") { parameters, returnType ->
            returnType == "Z" && parameters.size == 2 && parameters[1] == "Landroid/view/MotionEvent;"
        }.instructions.toList()
        val motionEventField = touchEventInstructions.withIndex().firstOrNull { (index, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                touchEventInstructions.getOrNull(index + 1)?.let {
                    it.opcode == Opcode.CHECK_CAST && it.type == "Landroid/view/MotionEvent;"
                } == true
        }?.value?.fieldReference ?: throw PatchException("Could not find the touch event's MotionEvent field")

        // Resolves the key under one pointer of a touch event. It runs for every pointer on every
        // event, including the delegated ones the main keyboard forwards to other key views.
        val keyForPointerMethod = findMethod("key for pointer") { parameters, returnType ->
            parameters == listOf(motionEventField.definingClass, "I") && returnType.startsWith("L")
        }

        // Let the extension reach the keys through an interface instead of obfuscated names.
        // Subclasses (main keyboard, popups, etc.) inherit the methods.
        keyboardViewClass.interfaces.add(EXTENSION_KEYBOARD_VIEW_INTERFACE)

        fun addAccessor(name: String, parameterTypes: List<String>, returnType: String, registers: Int, smali: String) {
            keyboardViewClass.methods.add(
                ImmutableMethod(
                    keyboardViewType,
                    name,
                    parameterTypes.map { ImmutableMethodParameter(it, null, null) },
                    returnType,
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(registers),
                ).toMutable().apply { addInstructions(0, smali) },
            )
        }

        addAccessor(
            "patch_getRippleKeys",
            emptyList(),
            "Ljava/util/List;",
            2,
            """
                iget-object v0, p0, $keyboardModelField
                iget-object v0, v0, $keyListField
                return-object v0
            """,
        )

        addAccessor(
            "patch_getRippleKeyDrawable",
            listOf(OBJECT),
            DRAWABLE,
            3,
            """
                iget-object v0, p0, $themeField
                check-cast p1, $keyType
                ${keyDrawableInstruction.invokeMnemonic()} { p1, v0 }, $keyDrawableMethod
                move-result-object v0
                return-object v0
            """,
        )

        addAccessor(
            "patch_getRippleKeyArea",
            listOf(OBJECT),
            RECT_F,
            3,
            """
                check-cast p1, $keyType
                ${keyGeometryInstruction.invokeMnemonic()} { p1 }, $keyGeometryMethod
                move-result-object v0
                iget-object v0, v0, $keyAreaField
                return-object v0
            """,
        )

        addAccessor(
            "patch_getRippleKeyBackground",
            listOf(OBJECT),
            RECT_F,
            3,
            """
                check-cast p1, $keyType
                ${keyGeometryInstruction.invokeMnemonic()} { p1 }, $keyGeometryMethod
                move-result-object v0
                invoke-virtual { v0 }, $keyBackgroundMethod
                move-result-object v0
                return-object v0
            """,
        )

        keyForPointerMethod.apply {
            val localCount = implementation!!.registerCount -
                parameterTypes.map(CharSequence::toString).registersWithThis()
            if (localCount < 3) throw PatchException("Not enough local registers in $this")

            // At each return only the result is live, so any other local is free. The touch event
            // parameter may already be overwritten by then (the result reuses p1 in 9.13.17.6),
            // which is why the event is handed over separately at entry.
            instructions.withIndex().filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }.reversed().forEach { index ->
                    val resultRegister = getInstruction<OneRegisterInstruction>(index).registerA
                    val (viewRegister, keyRegister) = (0 until localCount).filter { it != resultRegister }.take(2)

                    addInstructions(
                        index,
                        """
                            move-object/from16 v$viewRegister, p0
                            move-object/from16 v$keyRegister, v$resultRegister
                            invoke-static { v$viewRegister, v$keyRegister }, $EXTENSION_CLASS_DESCRIPTOR->onKeyResolved(Landroid/view/View;Ljava/lang/Object;)V
                        """,
                    )
                }

            // Every local is unassigned at entry. Parameter registers can be above v15, which iget can't address.
            addInstructions(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v0, v0, $motionEventField
                    move/from16 v1, p2
                    invoke-static { v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->onResolveKey(Ljava/lang/Object;I)V
                """,
            )
        }
    }
}
