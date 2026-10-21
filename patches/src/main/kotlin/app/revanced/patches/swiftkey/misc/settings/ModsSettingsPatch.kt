package app.revanced.patches.swiftkey.misc.settings

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.patches.swiftkey.typing.keyboardServiceOnCreateMethod
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val SETTINGS_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/settings/ModSettings;"
private const val SCREEN_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/settings/ModsSettingsScreen;"
private const val HOME_ENTRY_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/settings/ModsHomeEntry;"

private const val PREFERENCE = "Landroidx/preference/Preference;"
private const val PREFERENCE_GROUP = "Landroidx/preference/PreferenceGroup;"
private const val PREFERENCE_SCREEN = "Landroidx/preference/PreferenceScreen;"

// The Mods screen's host: a plain Activity SwiftKey declares but never shows (see ModsSettingsScreen).
// A manifest entry of our own would need the resources decoded.
private val BytecodePatchContext.hostActivityOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("Lcom/touchtype/installer/none/NoInstaller;")
    returnType("V")
    parameterTypes("Landroid/os/Bundle;")
}

// The home settings screen's onCreatePreferences(rootKey, savedInstanceState).
private val BytecodePatchContext.homeScreenCreatePreferencesMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/touchtype/materialsettingsx/HomeScreenFragment;")
    returnType("V")
    parameterTypes("Ljava/lang/String;", "Landroid/os/Bundle;")
}

private fun MutableClassDef.addAccessor(
    name: String,
    parameterTypes: List<String>,
    returnType: String,
    registers: Int,
    smali: String,
) {
    methods.add(
        ImmutableMethod(
            type,
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

/** Adds the "Mods" settings screen. A dependency of every mod patch with something to configure. */
val modsSettingsPatch = bytecodePatch(
    description = "Adds a Mods screen to SwiftKey's settings.",
) {
    extendWith("extensions/swiftkey.rve")

    apply {
        // Load the settings when the keyboard starts, so the hooks read them from the first key press.
        keyboardServiceOnCreateMethod.addInstruction(
            0,
            "invoke-static { p0 }, $SETTINGS_CLASS_DESCRIPTOR->init(Landroid/content/Context;)V",
        )

        // The host builds the Mods screen when started for it, instead of running its own onCreate.
        hostActivityOnCreateMethod.apply {
            if (implementation!!.registerCount < 3) throw PatchException("No local register in $this")
            val superIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_SUPER && methodReference?.name == "onCreate"
            }
            // Nothing is live yet right after super.onCreate, so v0 is free.
            addInstructionsWithLabels(
                superIndex + 1,
                """
                    invoke-static { p0 }, $SCREEN_CLASS_DESCRIPTOR->onCreate(Landroid/app/Activity;)Z
                    move-result v0
                    if-eqz v0, :host
                    return-void
                """,
                ExternalLabel("host", getInstruction(superIndex + 1)),
            )
            addInstruction(0, "invoke-static { p0 }, $SCREEN_CLASS_DESCRIPTOR->beforeCreate(Landroid/app/Activity;)V")
        }

        // Let the extension build the home screen's entry through interfaces instead of obfuscated names.
        val preferenceClass = firstClassDef(PREFERENCE)
        val groupClass = firstClassDef(PREFERENCE_GROUP)

        fun field(description: String, type: String) = preferenceClass.fields.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type
        } ?: throw PatchException("Could not find the preference's $description field")

        // compareTo orders by the order field, then the title.
        val compareInstructions = preferenceClass.methods.singleOrNull {
            it.name == "compareTo" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        }?.instructions ?: throw PatchException("Could not find the preference's compareTo")
        fun compareField(description: String, opcode: Opcode, type: String) = compareInstructions.firstNotNullOfOrNull {
            it.fieldReference?.takeIf { field ->
                it.opcode == opcode && field.definingClass == PREFERENCE && field.type == type
            }
        } ?: throw PatchException("Could not find the preference's $description field")
        val orderField = compareField("order", Opcode.IGET, "I")
        val titleField = compareField("title", Opcode.IGET_OBJECT, "Ljava/lang/CharSequence;")

        // findPreference(CharSequence) compares each key.
        val keyField = groupClass.methods.singleOrNull {
            it.returnType == PREFERENCE && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;")
        }?.instructions?.firstNotNullOfOrNull {
            it.fieldReference?.takeIf { field -> field.definingClass == PREFERENCE && field.type == "Ljava/lang/String;" }
        } ?: throw PatchException("Could not find the preference's key field")

        val iconField = field("icon", "Landroid/graphics/drawable/Drawable;")
        val intentField = field("intent", "Landroid/content/Intent;")
        val contextField = field("context", "Landroid/content/Context;")
        val childrenField = groupClass.fields.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/util/ArrayList;"
        } ?: throw PatchException("Could not find the preference group's children field")
        // addPreference: the only (Preference) method whose result R8 dropped.
        val addMethod = groupClass.methods.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(PREFERENCE)
        } ?: throw PatchException("Could not find the preference group's add method")

        fun FieldReference.smali() = "$definingClass->$name:$type"

        preferenceClass.interfaces.add("${HOME_ENTRY_CLASS_DESCRIPTOR.removeSuffix(";")}\$Preference;")
        preferenceClass.addAccessor(
            "patch_getKey",
            emptyList(),
            "Ljava/lang/String;",
            2,
            """
                iget-object v0, p0, ${keyField.smali()}
                return-object v0
            """,
        )
        preferenceClass.addAccessor(
            "patch_getOrder",
            emptyList(),
            "I",
            2,
            """
                iget v0, p0, ${orderField.smali()}
                return v0
            """,
        )
        preferenceClass.addAccessor(
            "patch_setOrder",
            listOf("I"),
            "V",
            2,
            """
                iput p1, p0, ${orderField.smali()}
                return-void
            """,
        )
        preferenceClass.addAccessor(
            "patch_setUp",
            listOf("Ljava/lang/String;", "Ljava/lang/CharSequence;", "Landroid/graphics/drawable/Drawable;", "Landroid/content/Intent;"),
            "V",
            5,
            """
                iput-object p1, p0, ${keyField.smali()}
                iput-object p2, p0, ${titleField.smali()}
                iput-object p3, p0, ${iconField.smali()}
                iput-object p4, p0, ${intentField.smali()}
                return-void
            """,
        )

        groupClass.interfaces.add("${HOME_ENTRY_CLASS_DESCRIPTOR.removeSuffix(";")}\$PreferenceGroup;")
        groupClass.addAccessor(
            "patch_getContext",
            emptyList(),
            "Landroid/content/Context;",
            2,
            """
                iget-object v0, p0, ${contextField.smali()}
                return-object v0
            """,
        )
        groupClass.addAccessor(
            "patch_getPreferences",
            emptyList(),
            "Ljava/util/List;",
            2,
            """
                iget-object v0, p0, ${childrenField.smali()}
                return-object v0
            """,
        )
        // The (Context, AttributeSet) constructor the XML inflater uses, so the default preference style applies.
        groupClass.addAccessor(
            "patch_newPreference",
            emptyList(),
            "Ljava/lang/Object;",
            4,
            """
                new-instance v0, $PREFERENCE
                iget-object v1, p0, ${contextField.smali()}
                const/4 v2, 0x0
                invoke-direct { v0, v1, v2 }, $PREFERENCE-><init>(Landroid/content/Context;Landroid/util/AttributeSet;)V
                return-object v0
            """,
        )
        groupClass.addAccessor(
            "patch_addPreference",
            listOf("Ljava/lang/Object;"),
            "V",
            2,
            """
                check-cast p1, $PREFERENCE
                invoke-virtual { p0, p1 }, $PREFERENCE_GROUP->${addMethod.name}($PREFERENCE)V
                return-void
            """,
        )

        // Add the entry once the screen's own setup (click handlers, icon tints) is done.
        homeScreenCreatePreferencesMethod.apply {
            val screenIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_OBJECT && fieldReference?.type == PREFERENCE_SCREEN
            }
            val screenField = getInstruction(screenIndex).fieldReference!!
            val managerField = getInstruction(
                indexOfFirstInstructionReversedOrThrow(screenIndex) {
                    opcode == Opcode.IGET_OBJECT && fieldReference?.type == screenField.definingClass
                },
            ).fieldReference!!
            if (implementation!!.registerCount < 4) throw PatchException("No local register in $this")

            // The return is a branch target: replacing it keeps the branches, so they run the hook too.
            // Every local is dead at the return. p0 can be above v15, which iget can't address.
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            replaceInstruction(returnIndex, "move-object/from16 v0, p0")
            addInstructions(
                returnIndex + 1,
                """
                    iget-object v0, v0, ${managerField.smali()}
                    iget-object v0, v0, ${screenField.smali()}
                    invoke-static { v0 }, $HOME_ENTRY_CLASS_DESCRIPTOR->add(Ljava/lang/Object;)V
                    return-void
                """,
            )
        }
    }
}
