package app.revanced.patches.swiftkey.visual

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableField.Companion.toMutable
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/visual/KeyDrawHook;"
private const val EXTENSION_KEY_DRAWABLE_INTERFACE =
    "Lapp/revanced/extension/swiftkey/visual/KeyDrawHook\$KeyDrawable;"

private const val LAYER_DRAWABLE = "Landroid/graphics/drawable/LayerDrawable;"
private const val CANVAS = "Landroid/graphics/Canvas;"
private const val OBJECT = "Ljava/lang/Object;"

private const val STATE_FIELD_NAME = "patch_keyDrawState"

/**
 * Routes the key drawable's draw through the extension, which records where each key's
 * background really draws and can draw between its layers. Used by the ripple and the watermark.
 */
internal val keyDrawHookPatch = bytecodePatch(
    description = "Hooks the drawing of each keyboard key.",
) {
    extendWith("extensions/swiftkey.rve")

    apply {
        // Every key, whether drawn by the keyboard view's onDraw or as a key child view's
        // background, is one of these: layer 0 the theme background, layer 1 the label.
        val keyDrawableClass = firstClassDef(keyDrawableFactoryMethod.returnType)
        val keyDrawableType = keyDrawableClass.type
        if (keyDrawableClass.superclass != LAYER_DRAWABLE) {
            throw PatchException("The key drawable $keyDrawableType is not a direct LayerDrawable subclass")
        }
        if (keyDrawableClass.methods.any { it.name == "draw" && it.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) }) {
            throw PatchException("The key drawable $keyDrawableType already overrides draw")
        }

        keyDrawableClass.interfaces.add(EXTENSION_KEY_DRAWABLE_INTERFACE)

        keyDrawableClass.fields.add(
            ImmutableField(
                keyDrawableType,
                STATE_FIELD_NAME,
                OBJECT,
                AccessFlags.PRIVATE.value,
                null,
                null,
                null,
            ).toMutable(),
        )
        val stateField = "$keyDrawableType->$STATE_FIELD_NAME:$OBJECT"

        fun addMethod(name: String, parameterTypes: List<String>, returnType: String, registers: Int, smali: String) {
            keyDrawableClass.methods.add(
                ImmutableMethod(
                    keyDrawableType,
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

        addMethod(
            "patch_getKeyDrawState",
            emptyList(),
            OBJECT,
            2,
            """
                iget-object v0, p0, $stateField
                return-object v0
            """,
        )

        addMethod(
            "patch_setKeyDrawState",
            listOf(OBJECT),
            "V",
            2,
            """
                iput-object p1, p0, $stateField
                return-void
            """,
        )

        addMethod(
            "patch_drawLayers",
            listOf(CANVAS),
            "V",
            2,
            """
                invoke-super { p0, p1 }, $LAYER_DRAWABLE->draw($CANVAS)V
                return-void
            """,
        )

        addMethod(
            "draw",
            listOf(CANVAS),
            "V",
            2,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->draw($LAYER_DRAWABLE$CANVAS)V
                return-void
            """,
        )
    }
}
