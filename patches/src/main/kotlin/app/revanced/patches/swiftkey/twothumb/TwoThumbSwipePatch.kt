package app.revanced.patches.swiftkey.twothumb

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.swiftkey.misc.settings.modsSettingsPatch
import app.revanced.patches.swiftkey.misc.toolbar.registerToolbarItem
import app.revanced.patches.swiftkey.misc.toolbar.toolbarItemsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/twothumb/TwoThumbSwipe;"
private const val EXTENSION_KEYBOARD_VIEW_INTERFACE =
    "Lapp/revanced/extension/swiftkey/twothumb/TwoThumbSwipe\$KeyboardView;"

private const val EXTENSION_MODE_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/twothumb/TwoThumbMode;"
private const val EXTENSION_INPUT_MODEL_INTERFACE =
    "Lapp/revanced/extension/swiftkey/twothumb/TwoThumbMode\$InputModel;"

private const val EXTENSION_KEY_FEEDBACK_INTERFACE = "Lapp/revanced/extension/swiftkey/twothumb/TwoThumbSwipe\$KeyFeedback;"

private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val RECT_F = "Landroid/graphics/RectF;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val OBJECT = "Ljava/lang/Object;"
private const val PREDICTIONS = "Lcom/microsoft/fluency/Predictions;"
private const val PREDICTION = "Lcom/microsoft/fluency/Prediction;"
private const val TRAINER = "Lcom/microsoft/fluency/Trainer;"

// Data files the engine reads, bundled in the patches jar under swiftkey/nintype/ and written
// to the APK's assets/nintype/ (the extension copies them to storage on first start).
private val NINTYPE_ASSETS = listOf(
    "e2e/BOOT.json",
    "e2e/moopad_boot.bin",
    "e2e_search_boot.json",
    "trie/english/english.nbipack",
)

// Toolbar button icons: two thumbs, filled when two-thumb mode is on, outlined when off.
private fun thumbsDrawable(pathData: String, evenOdd: Boolean) =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="24dp"
        android:height="24dp"
        android:viewportWidth="24"
        android:viewportHeight="24">
        <path
            android:fillColor="#ffffff"
            ${if (evenOdd) "android:fillType=\"evenOdd\"" else ""}
            android:pathData="$pathData"/>
    </vector>
    """.trimIndent() + "\n"

private const val THUMBS = "M2.5,13a4.5,5.5 0,1 1,9 0a4.5,5.5 0,1 1,-9 0z" +
    "M12.5,11a4.5,5.5 0,1 1,9 0a4.5,5.5 0,1 1,-9 0z"
private const val THUMB_HOLES = "M4.5,13a2.5,3.5 0,1 1,5 0a2.5,3.5 0,1 1,-5 0z" +
    "M14.5,11a2.5,3.5 0,1 1,5 0a2.5,3.5 0,1 1,-5 0z"

private val nintypeAssetsPatch = resourcePatch {
    apply {
        get("res/drawable/revanced_two_thumb_on.xml").writeText(thumbsDrawable(THUMBS, false))
        get("res/drawable/revanced_two_thumb_off.xml").writeText(thumbsDrawable(THUMBS + THUMB_HOLES, true))
        for (name in NINTYPE_ASSETS) {
            val stream = object {}.javaClass.classLoader.getResourceAsStream("swiftkey/nintype/$name")
                ?: throw PatchException("Missing bundled resource swiftkey/nintype/$name")
            val target = get("assets/nintype/$name", false)
            target.parentFile.mkdirs()
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}

private fun Instruction.isInvoke() = opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_VIRTUAL

private fun Instruction.invokeMnemonic() =
    if (opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"

@Suppress("unused")
val twoThumbSwipePatch = bytecodePatch(
    name = "Two-finger swipe",
    description = "Two-finger mode: Nintype's typing (a port of its decoder) on SwiftKey's letter keys, taps and " +
        "swipes with either thumb, on and off from a toolbar button or Settings > Mods. Off is stock SwiftKey.",
) {
    compatibleWith("com.touchtype.swiftkey")

    dependsOn(nintypeAssetsPatch, modsSettingsPatch, toolbarItemsPatch)

    extendWith("extensions/swiftkey.rve")

    apply {
        // 1. Start: load the engine in the background.
        traceKeyboardServiceOnCreateMethod.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->init(Landroid/content/Context;)V",
        )

        // Commit an open two-thumb word when the keyboard goes away.
        traceKeyboardServiceFinishInputViewMethod.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->onKeyboardHidden()V",
        )

        // Toolbar button "Two-finger swipe" (id in TwoThumbMode.TOOLBAR_ITEM_ID).
        registerToolbarItem("addTwoThumb")

        // 2. Geometry: key list, key area and label on the base keyboard view (all key layouts).
        val keyboardViewClass = traceKeyboardViewPointMethod.classDef
        val keyboardViewType = keyboardViewClass.type
        val onDrawInstructions = keyboardViewClass.methods.singleOrNull {
            it.name == "onDraw" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/graphics/Canvas;")
        }?.instructions?.toList() ?: throw PatchException("Could not find the keyboard view's onDraw method")

        fun onDrawInstruction(description: String, predicate: (Instruction) -> Boolean) =
            onDrawInstructions.firstOrNull(predicate)
                ?: throw PatchException("Could not find the $description in the keyboard view's onDraw")

        val keyListIndex = onDrawInstructions.indexOfFirst {
            it.opcode == Opcode.IGET_OBJECT && it.fieldReference?.type == "Ljava/util/ArrayList;"
        }.takeIf { it >= 0 } ?: throw PatchException("Could not find the key list in the keyboard view's onDraw")
        val keyListField = onDrawInstructions[keyListIndex].fieldReference!!
        val keyboardModelField = onDrawInstructions.getOrNull(keyListIndex - 1)?.fieldReference
            ?.takeIf { it.type == keyListField.definingClass }
            ?: throw PatchException("Could not find the keyboard model field in the keyboard view's onDraw")
        val keyAreaField = onDrawInstruction("key area field") {
            it.opcode == Opcode.IGET_OBJECT && it.fieldReference?.type == RECT_F
        }.fieldReference!!
        val geometryType = keyAreaField.definingClass
        val keyGeometryInstruction = onDrawInstruction("key geometry call") {
            it.isInvoke() && it.methodReference?.let { reference ->
                reference.returnType == geometryType && reference.parameterTypes.isEmpty()
            } == true
        }
        val keyGeometryMethod = keyGeometryInstruction.methodReference!!
        val keyType = keyGeometryMethod.definingClass
        val keyClassType = traceKeyToStringMethod.definingClass
        val keyLabelMethod = traceKeyToStringMethod.immutableClassDef.methods.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == CHAR_SEQUENCE && it.parameterTypes.isEmpty()
        }?.let { "$keyClassType->${it.name}()$CHAR_SEQUENCE" }
            ?: throw PatchException("Could not find the key label method in $keyClassType")

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
                ).toMutable().apply { addInstructionsWithLabels(0, smali) },
            )
        }

        addAccessor(
            "patch_getTwoThumbKeys", emptyList(), "Ljava/util/List;", 2,
            """
                iget-object v0, p0, $keyboardModelField
                iget-object v0, v0, $keyListField
                return-object v0
            """,
        )
        addAccessor(
            "patch_getTwoThumbKeyArea", listOf(OBJECT), RECT_F, 3,
            """
                instance-of v0, p1, $keyType
                if-eqz v0, :not_a_key
                check-cast p1, $keyType
                ${keyGeometryInstruction.invokeMnemonic()} { p1 }, $keyGeometryMethod
                move-result-object v0
                iget-object v0, v0, $keyAreaField
                return-object v0
                :not_a_key
                const/4 v0, 0x0
                return-object v0
            """,
        )
        addAccessor(
            "patch_getTwoThumbKeyLabel", listOf(OBJECT), CHAR_SEQUENCE, 3,
            """
                instance-of v0, p1, $keyClassType
                if-eqz v0, :not_a_key
                check-cast p1, $keyClassType
                invoke-virtual { p1 }, $keyLabelMethod
                move-result-object v0
                return-object v0
                :not_a_key
                const/4 v0, 0x0
                return-object v0
            """,
        )

        // Key feedback: every keyboard view's base class holds SwiftKey's key feedback object (the
        // one whose methods call View.performHapticFeedback / Vibrator.vibrate). Its key press
        // (View, I)V gives the vibration (via its other (View, I)V, which calls
        // performHapticFeedback) and then the sound for the given type (0 key, 32 space, -5 delete),
        // each behind SwiftKey's own setting: what SwiftKey's key actions (ic0.g) call.
        val baseViewType = keyboardViewClass.superclass ?: throw PatchException("The keyboard view has no superclass")
        val baseViewClass = firstClassDef(baseViewType)
        val feedbackField = baseViewClass.fields.firstOrNull { field ->
            classDefs.firstOrNull { it.type == field.type }?.methods?.any { method ->
                method.implementation?.instructions?.any {
                    it.methodReference?.let { reference ->
                        reference.name == "performHapticFeedback" && reference.definingClass == "Landroid/view/View;"
                    } == true
                } == true
            } == true
        } ?: throw PatchException("Could not find the key feedback field in $baseViewType")
        val feedbackClass = firstClassDef(feedbackField.type)
        fun callsHaptic(method: com.android.tools.smali.dexlib2.iface.Method) =
            method.implementation?.instructions?.any {
                it.methodReference?.let { reference ->
                    reference.name == "performHapticFeedback" && reference.definingClass == "Landroid/view/View;"
                } == true
            } == true
        val keyPressMethod = feedbackClass.methods.singleOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "I") &&
                !callsHaptic(method) &&
                method.implementation?.instructions?.any {
                    it.methodReference?.let { reference ->
                        reference.definingClass == feedbackClass.type &&
                            reference.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "I")
                    } == true
                } == true
        } ?: throw PatchException("Could not find the key press feedback method in ${feedbackClass.type}")
        // Key sounds are parked for now: call the vibration-only key press (View)V, which checks
        // SwiftKey's vibration setting and strength itself, instead of the sound + vibration one.
        val hapticMethod = feedbackClass.methods.singleOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")
        } ?: throw PatchException("Could not find the key vibration method in ${feedbackClass.type}")
        baseViewClass.interfaces.add(EXTENSION_KEY_FEEDBACK_INTERFACE)
        baseViewClass.methods.add(
            ImmutableMethod(
                baseViewType,
                "patch_twoThumbKeyFeedback",
                listOf(ImmutableMethodParameter("I", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        iget-object v0, p0, $feedbackField
                        if-eqz v0, :none
                        invoke-virtual { v0, p0 }, ${feedbackClass.type}->${hapticMethod.name}(Landroid/view/View;)V
                        :none
                        return-void
                    """,
                )
            },
        )

        // 3. Gate: the main keyboard view's dispatch f(breadcrumb, MotionEvent), at its start.
        val mainKeyboardClass = mainKeyboardCoachmarkSetterMethod.classDef
        val dispatchMethod = mainKeyboardClass.methods.singleOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
                method.returnType == "Z" && method.parameterTypes.size == 2 &&
                method.parameterTypes[1].toString() == MOTION_EVENT
        } ?: throw PatchException("Could not find the main keyboard view's pointer dispatch method")
        dispatchMethod.apply {
            val registers = implementation!!.registerCount
            if (registers - 3 < 1) throw PatchException("No local register in $this")
            // p0 .. p2 = this, breadcrumb, MotionEvent; v0 is a free local at entry.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->filterTouch(Landroid/view/View;$OBJECT$MOTION_EVENT)$MOTION_EVENT
                    move-result-object p2
                    if-nez p2, :dispatch
                    const/4 v0, 0x1
                    return v0
                    :dispatch
                    nop
                """,
            )
        }

        // 3b. Long-press delay: every post(delay, runnable) in SwiftKey's long-press delegate gets
        // its delay through TwoThumbMode.longPressDelay, which shortens it only for a letter down the
        // two-thumb glue hands over after the pointer was already held (else it would wait twice).
        run {
            val delegateClass = longPressDelegateConstructorMethod.classDef
            var hooked = 0
            delegateClass.methods.filter { it.implementation != null }.forEach { method ->
                val calls = method.instructions.withIndex().filter { (_, insn) ->
                    insn.opcode == Opcode.INVOKE_INTERFACE && insn.methodReference?.let { ref ->
                        ref.returnType == "V" &&
                            ref.parameterTypes.map(CharSequence::toString) == listOf("J", "Ljava/lang/Runnable;")
                    } == true
                }.map { it.index }
                calls.reversed().forEach { index ->
                    val call = method.getInstruction<FiveRegisterInstruction>(index)
                    val low = call.registerD
                    if (call.registerE != low + 1) throw PatchException("Unexpected delay registers in $method")
                    method.addInstructions(
                        index,
                        """
                            invoke-static { v$low, v${low + 1} }, $EXTENSION_MODE_DESCRIPTOR->longPressDelay(J)J
                            move-result-wide v$low
                        """,
                    )
                    hooked++
                }
            }
            if (hooked == 0) throw PatchException("No delayed post in the long-press delegate ${delegateClass.type}")

            // 3c. Key repeat: the repeat delegate is the long-press delegate's sibling (same
            // superclass) that holds the repeat behaviour, an interface of exactly ()I (the first
            // delay) and (I)I (the interval after repeat i). Its delayed post (the first repeat, at a
            // key down) goes through TwoThumbMode.repeatDelay, which shortens it only for a held
            // backspace the glue hands over.
            val delegateSuper = delegateClass.superclass
            fun isRepeatBehaviour(type: String): Boolean {
                val iface = classDefs.firstOrNull { it.type == type } ?: return false
                if (!AccessFlags.INTERFACE.isSet(iface.accessFlags)) return false
                val shapes = iface.methods.map { it.parameterTypes.map(CharSequence::toString) to it.returnType }.toSet()
                return shapes == setOf(emptyList<String>() to "I", listOf("I") to "I")
            }
            val repeatClasses = classDefs.filter { classDef ->
                classDef.superclass == delegateSuper && classDef.type != delegateClass.type &&
                    classDef.fields.any { !AccessFlags.STATIC.isSet(it.accessFlags) && isRepeatBehaviour(it.type) }
            }
            val repeatClass = repeatClasses.singleOrNull()
                ?: throw PatchException("Expected one key-repeat delegate, found ${repeatClasses.map { it.type }}")
            var repeatHooked = 0
            firstClassDef(repeatClass.type).methods.filter { it.implementation != null }.forEach { method ->
                val calls = method.instructions.withIndex().filter { (_, insn) ->
                    insn.opcode == Opcode.INVOKE_INTERFACE && insn.methodReference?.let { ref ->
                        ref.returnType == "V" &&
                            ref.parameterTypes.map(CharSequence::toString) == listOf("J", "Ljava/lang/Runnable;")
                    } == true
                }.map { it.index }
                calls.reversed().forEach { index ->
                    val call = method.getInstruction<FiveRegisterInstruction>(index)
                    val low = call.registerD
                    if (call.registerE != low + 1) throw PatchException("Unexpected delay registers in $method")
                    method.addInstructions(
                        index,
                        """
                            invoke-static { v$low, v${low + 1} }, $EXTENSION_MODE_DESCRIPTOR->repeatDelay(J)J
                            move-result-wide v$low
                        """,
                    )
                    repeatHooked++
                }
            }
            if (repeatHooked != 1) throw PatchException("Expected one delayed post in the key-repeat delegate ${repeatClass.type}, found $repeatHooked")
        }

        // 4. Substitution: the candidate update callable, right after Fluency's predict call.
        val updateCandidatesMethod = updateCandidatesCallableMethod
        var requestTypeEnum: String? = null
        updateCandidatesMethod.apply {
            val callIndex = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL && it.methodReference?.returnType == PREDICTIONS
            }.takeIf { it >= 0 } ?: throw PatchException("Could not find the predict call in $this")
            val moveIndex = callIndex + 1
            val move = getInstruction<OneRegisterInstruction>(moveIndex)
            if (getInstruction<Instruction>(moveIndex).opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw PatchException("Unexpected instruction after the predict call in $this")
            }
            val predictionsRegister = move.registerA
            // Next: iget-object vT, p0, <request field>; iget-object vT, vT, <type field>;
            // check-cast vT, <enum>; sget-object vS, <enum constant>. Both vT and vS are
            // overwritten there, so they are free at the hook.
            if (getInstruction<Instruction>(moveIndex + 1).opcode != Opcode.IGET_OBJECT ||
                getInstruction<Instruction>(moveIndex + 2).opcode != Opcode.IGET_OBJECT ||
                getInstruction<Instruction>(moveIndex + 3).opcode != Opcode.CHECK_CAST ||
                getInstruction<Instruction>(moveIndex + 4).opcode != Opcode.SGET_OBJECT
            ) {
                throw PatchException("Unexpected request type read after the predict call in $this")
            }
            val requestGet = getInstruction<TwoRegisterInstruction>(moveIndex + 1)
            val typeGet = getInstruction<TwoRegisterInstruction>(moveIndex + 2)
            val enumGet = getInstruction<OneRegisterInstruction>(moveIndex + 4)
            val requestField = getInstruction<Instruction>(moveIndex + 1).fieldReference!!
            val typeField = getInstruction<Instruction>(moveIndex + 2).fieldReference!!
            requestTypeEnum = (getInstruction<ReferenceInstruction>(moveIndex + 3).reference as TypeReference).type
            val typeRegister = typeGet.registerA
            val requestRegister = enumGet.registerA
            if (typeRegister == requestRegister || typeRegister > 15 || requestRegister > 15 ||
                predictionsRegister > 15 || requestGet.registerB > 15
            ) {
                throw PatchException("Unexpected registers around the predict call in $this")
            }
            addInstructions(
                moveIndex + 1,
                """
                    iget-object v$requestRegister, p0, $requestField
                    iget-object v$typeRegister, v$requestRegister, $typeField
                    invoke-static { v$predictionsRegister, v$typeRegister, v$requestRegister }, $EXTENSION_CLASS_DESCRIPTOR->substitute($OBJECT$OBJECT$OBJECT)$OBJECT
                    move-result-object v$predictionsRegister
                    check-cast v$predictionsRegister, $PREDICTIONS
                """,
            )
        }

        // 4b. Shared vocabulary: at the start of the same callable (Fluency's thread), hand the
        // predictor context and SwiftKey's own "user model writable" check to VocabBridge. Both
        // references come from SwiftKey's add-to-user-model task, which performs that very check.
        run {
            val taskInstructions = addToUserModelTaskMethod.instructions.toList()
            val predictorType = updateCandidatesCallableMethod.parameterTypes.single().toString()
            val holderField = taskInstructions.firstNotNullOfOrNull { instruction ->
                instruction.takeIf { it.opcode == Opcode.IGET_OBJECT }?.fieldReference
                    ?.takeIf { it.definingClass == predictorType }
            } ?: throw PatchException("Could not find the predictor's model holder in the add-to-user-model task")
            val writableMethod = taskInstructions.firstNotNullOfOrNull { instruction ->
                instruction.takeIf { it.opcode == Opcode.INVOKE_VIRTUAL }?.methodReference?.takeIf {
                    it.definingClass == holderField.type && it.returnType == "Z" && it.parameterTypes.isEmpty()
                }
            } ?: throw PatchException("Could not find the user model writable check")
            updateCandidatesCallableMethod.apply {
                if (implementation!!.registerCount - 2 < 1) throw PatchException("No local register in $this")
                if (implementation!!.registerCount - 1 > 15) throw PatchException("Predictor register out of range in $this")
                addInstructionsWithLabels(
                    0,
                    """
                        iget-object v0, p1, $holderField
                        if-eqz v0, :no_holder
                        invoke-virtual { v0 }, $writableMethod
                        move-result v0
                        invoke-static { p1, v0 }, Lapp/revanced/extension/swiftkey/twothumb/VocabBridge;->onFluencyTask(Ljava/lang/Object;Z)V
                        :no_holder
                        nop
                    """,
                )
            }
        }

        // 4c. Two-thumb bar refresh: SwiftKey's input model (the onFlowComplete class) gets
        // patch_refreshTwoThumbBar(), which calls its own "update candidates" method
        // (breadcrumb, clear cache, request type) with a new breadcrumb, true and DEFAULT. Every
        // constructor hands the instance to TwoThumbMode.
        run {
            val enumType = requestTypeEnum ?: throw PatchException("No candidate request type")
            val inputModelClass = flowCompleteMethod.classDef
            val inputModelType = inputModelClass.type
            val breadcrumbType = flowCompleteMethod.parameterTypes.first().toString()
            val refreshMethod = inputModelClass.methods.singleOrNull { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf(breadcrumbType, "Z", enumType)
            } ?: throw PatchException("Could not find the update candidates method in $inputModelType")
            if (firstClassDef(breadcrumbType).methods.none { it.name == "<init>" && it.parameterTypes.isEmpty() }) {
                throw PatchException("No breadcrumb constructor in $breadcrumbType")
            }
            val enumClinit = firstClassDef(enumType).methods.singleOrNull { it.name == "<clinit>" }
                ?.instructions?.toList() ?: throw PatchException("No static initialiser in $enumType")
            val defaultIndex = enumClinit.indexOfFirst {
                it.opcode == Opcode.CONST_STRING &&
                    ((it as ReferenceInstruction).reference as StringReference).string == "DEFAULT"
            }.takeIf { it >= 0 } ?: throw PatchException("No DEFAULT constant in $enumType")
            val defaultField = enumClinit.drop(defaultIndex).firstOrNull {
                it.opcode == Opcode.SPUT_OBJECT && it.fieldReference?.let { f ->
                    f.type == enumType && f.definingClass == enumType
                } == true
            }?.fieldReference ?: throw PatchException("No DEFAULT field in $enumType")

            inputModelClass.interfaces.add(EXTENSION_INPUT_MODEL_INTERFACE)
            inputModelClass.methods.add(
                ImmutableMethod(
                    inputModelType,
                    "patch_refreshTwoThumbBar",
                    emptyList(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(4),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            new-instance v0, $breadcrumbType
                            invoke-direct { v0 }, $breadcrumbType-><init>()V
                            const/4 v1, 0x1
                            sget-object v2, $defaultField
                            invoke-virtual { p0, v0, v1, v2 }, $inputModelType->${refreshMethod.name}(${breadcrumbType}Z$enumType)V
                            return-void
                        """,
                    )
                },
            )
            // patch_resyncTwoThumbEditor(): SwiftKey's own selection-update handler with every
            // position -1 (as its refresh after a language load does): it re-reads the editor into
            // its mirror and re-applies its composing region, so a key passed on after the glue's
            // writes works from the editor's real text.
            val selectionMethod = inputModelClass.methods.singleOrNull { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf(breadcrumbType) + List(6) { "I" }
            } ?: throw PatchException("Could not find the selection update method in $inputModelType")
            inputModelClass.methods.add(
                ImmutableMethod(
                    inputModelType,
                    "patch_resyncTwoThumbEditor",
                    emptyList(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(9),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            move-object v0, p0
                            new-instance v1, $breadcrumbType
                            invoke-direct { v1 }, $breadcrumbType-><init>()V
                            const/4 v2, -0x1
                            const/4 v3, -0x1
                            const/4 v4, -0x1
                            const/4 v5, -0x1
                            const/4 v6, -0x1
                            const/4 v7, -0x1
                            invoke-virtual/range { v0 .. v7 }, $inputModelType->${selectionMethod.name}(${breadcrumbType}IIIIII)V
                            return-void
                        """,
                    )
                },
            )
            val constructors = inputModelClass.methods.filter { it.name == "<init>" && it.implementation != null }
            if (constructors.isEmpty()) throw PatchException("No constructor in $inputModelType")
            constructors.forEach { constructor ->
                val returns = constructor.instructions.withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
                returns.sortedDescending().forEach { index ->
                    constructor.addInstruction(
                        index,
                        "invoke-static/range { p0 .. p0 }, $EXTENSION_MODE_DESCRIPTOR->setInputModel($OBJECT)V",
                    )
                }
            }
        }

        // 4d. Bar taps: candidates the two-thumb bar created are committed by TwoThumbMode (its
        // anchored write), never by SwiftKey's own replace.
        predictionSelectedMethod.apply {
            if (implementation!!.registerCount - 5 < 1) throw PatchException("No local register in $this")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p2 .. p2 }, $EXTENSION_MODE_DESCRIPTOR->onCandidateSelected($OBJECT)Z
                    move-result v0
                    if-eqz v0, :stock
                    return-void
                    :stock
                    nop
                """,
            )
        }

        // Save Nintype's learned state when the keyboard service goes away.
        keyboardServiceOnDestroyMethod.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->onDestroy()V",
        )

        // 5. Learning guard: the Fluency trainer wrapper's learnFrom overloads that take a Prediction.
        val trainerType = classDefs.firstOrNull { classDef ->
            classDef.type.startsWith("Lcom/touchtype_fluency/") && TRAINER in classDef.interfaces &&
                classDef.methods.any { it.name == "learnFrom" && it.implementation != null }
        }?.type ?: throw PatchException("Could not find the Fluency trainer wrapper")
        val learnFromMethods = firstClassDef(trainerType).methods.filter { method ->
            method.name == "learnFrom" && method.implementation != null && method.returnType == "V" &&
                method.parameterTypes.lastOrNull()?.toString() == PREDICTION
        }
        if (learnFromMethods.isEmpty()) throw PatchException("No learnFrom(..., Prediction) in $trainerType")
        learnFromMethods.forEach { method ->
            if (method.implementation!!.registerCount <= method.parameterTypes.size + 1) {
                throw PatchException("No local register in $method")
            }
            val predictionParameter = "p${method.parameterTypes.size}"
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { $predictionParameter }, $EXTENSION_CLASS_DESCRIPTOR->skipLearning($OBJECT)Z
                    move-result v0
                    if-eqz v0, :learn
                    return-void
                    :learn
                    nop
                """,
            )
        }
    }
}
