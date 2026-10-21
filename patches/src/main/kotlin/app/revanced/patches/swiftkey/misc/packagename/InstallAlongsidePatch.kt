package app.revanced.patches.swiftkey.misc.packagename

import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringOption
import app.revanced.util.asSequence
import app.revanced.util.forEachInstructionAsSequence
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

private const val ORIGINAL_PACKAGE = "com.touchtype.swiftkey"
private const val KEYBOARD_SERVICE = "com.touchtype.KeyboardService"

private val packageNameOption = stringOption(
    default = "$ORIGINAL_PACKAGE.modded",
    name = "Package name",
    description = "Package name for the patched app. Must differ from the original to install both.",
    required = true,
) { it!!.matches(Regex("^[a-z]\\w*(\\.[a-z]\\w*)+$")) && it != ORIGINAL_PACKAGE }

private val appNameOption = stringOption(
    default = "SwiftKey (modded)",
    name = "App name",
    description = "Name shown in the launcher and the switch keyboard menu.",
    required = true,
)

private val installAlongsideManifestPatch = resourcePatch {
    apply {
        val newPackage = packageNameOption.value!!

        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element
            manifest.setAttribute("package", newPackage)

            // Authorities and custom permissions must be unique per device, or the install fails.
            document.getElementsByTagName("provider").asSequence().map { it as Element }.forEach {
                val authorities = it.getAttribute("android:authorities")
                if (authorities.startsWith("$ORIGINAL_PACKAGE.")) {
                    it.setAttribute("android:authorities", newPackage + authorities.removePrefix(ORIGINAL_PACKAGE))
                }
            }
            (document.getElementsByTagName("permission").asSequence() +
                document.getElementsByTagName("uses-permission").asSequence()).map { it as Element }.forEach {
                val name = it.getAttribute("android:name")
                if (name.startsWith("$ORIGINAL_PACKAGE.")) {
                    it.setAttribute("android:name", newPackage + name.removePrefix(ORIGINAL_PACKAGE))
                }
            }

            // The switch keyboard menu uses the IME service's label, falling back to the app's.
            val application = document.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:label", appNameOption.value!!)
            document.getElementsByTagName("service").asSequence().map { it as Element }
                .first { it.getAttribute("android:name") == KEYBOARD_SERVICE }
                .setAttribute("android:label", appNameOption.value!!)
        }
    }
}

@Suppress("unused")
val installAlongsidePatch = bytecodePatch(
    name = "Install alongside original",
    description = "Changes the package name and app name so the patched app installs next to the Play Store version.",
    use = false,
) {
    compatibleWith(ORIGINAL_PACKAGE)

    packageNameOption()
    appNameOption()

    dependsOn(installAlongsideManifestPatch)

    apply {
        val newPackage = packageNameOption.value!!

        // The app compares its own package name as literals: the main-process check in
        // SwiftKeyApplication, its own IME id ("pkg/.KeyboardService"), its own text fields,
        // FileProvider authorities. Exact matches only: longer strings (intent actions,
        // "com.touchtype.swiftkey.cesar") are separate identifiers and must stay.
        // This includes Resources.getIdentifier lookups: the patcher renames the resource table's package too.
        val replacements = mapOf(
            ORIGINAL_PACKAGE to newPackage,
            "$ORIGINAL_PACKAGE/." to "$newPackage/.",
        )

        forEachInstructionAsSequence(
            match = { _, _, instruction, index ->
                if (instruction.opcode.referenceType != ReferenceType.STRING) return@forEachInstructionAsSequence null
                val string = instruction.getReference<StringReference>()!!.string
                val replacement = replacements[string] ?: return@forEachInstructionAsSequence null
                Triple(index, instruction as OneRegisterInstruction, replacement)
            },
            transform = { method, (index, instruction, replacement) ->
                method.replaceInstruction(index, "${instruction.opcode.name} v${instruction.registerA}, \"$replacement\"")
            },
        )
    }
}
