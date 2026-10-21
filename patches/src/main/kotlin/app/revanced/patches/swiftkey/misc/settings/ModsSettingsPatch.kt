package app.revanced.patches.swiftkey.misc.settings

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.swiftkey.typing.keyboardServiceOnCreateMethod
import app.revanced.util.asSequence
import org.w3c.dom.Element

private const val SETTINGS_CLASS_DESCRIPTOR = "Lapp/revanced/extension/swiftkey/settings/ModSettings;"
private const val ACTIVITY_CLASS = "app.revanced.extension.swiftkey.settings.ModsSettingsActivity"

// An action rather than an explicit component: the package name changes with "Install alongside original".
// Implicit intents only reach exported components (Android 14+), hence exported="true".
private const val OPEN_ACTION = "app.revanced.swiftkey.MODS_SETTINGS"

// Wrench outline in the style of SwiftKey's own settings icons (24dp, 1.5 stroke, tinted by the list).
private const val MODS_ICON = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:strokeColor="#ffffff"
        android:strokeWidth="1.5"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="M14.7,6.3a1,1 0,0 0,0 1.4l1.6,1.6a1,1 0,0 0,1.4 0l3.77,-3.77a6,6 0,0 1,-7.94 7.94l-6.91,6.91a2.12,2.12 0,0 1,-3 -3l6.91,-6.91a6,6 0,0 1,7.94 -7.94l-3.76,3.76z"/>
</vector>
"""

private val modsSettingsResourcePatch = resourcePatch {
    apply {
        get("res/drawable/revanced_mods.xml").writeText(MODS_ICON)

        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val activity = document.createElement("activity").apply {
                setAttribute("android:name", ACTIVITY_CLASS)
                setAttribute("android:label", "Mods")
                setAttribute("android:exported", "true")
                // The activity switches to DayNight in code where the platform has it.
                setAttribute("android:theme", "@android:style/Theme.DeviceDefault")
            }
            val filter = document.createElement("intent-filter")
            filter.appendChild(document.createElement("action").apply { setAttribute("android:name", OPEN_ACTION) })
            filter.appendChild(
                document.createElement("category").apply { setAttribute("android:name", "android.intent.category.DEFAULT") },
            )
            activity.appendChild(filter)
            application.appendChild(activity)
        }

        // The home screen only attaches its own click handlers to the keys it knows, so a plain
        // Preference with an <intent> falls through to androidx's default: start the intent.
        document("res/xml/prefsx_home_screen.xml").use { document ->
            val screen = document.documentElement
            val entry = document.createElement("Preference").apply {
                setAttribute("android:key", "revanced_mods_settings")
                setAttribute("android:title", "Mods")
                setAttribute("android:icon", "@drawable/revanced_mods")
            }
            entry.appendChild(document.createElement("intent").apply { setAttribute("android:action", OPEN_ACTION) })

            val layoutAndKeys = screen.getElementsByTagName("Preference").asSequence().map { it as Element }
                .firstOrNull { it.getAttribute("android:key") == "@string/pref_home_launch_layout_and_keys_prefs" }
            val next = layoutAndKeys?.nextSibling
            if (next != null) screen.insertBefore(entry, next) else screen.appendChild(entry)
        }
    }
}

/** Adds the "Mods" settings screen. A dependency of every mod patch with something to configure. */
val modsSettingsPatch = bytecodePatch(
    description = "Adds a Mods screen to SwiftKey's settings.",
) {
    dependsOn(modsSettingsResourcePatch)

    extendWith("extensions/swiftkey.rve")

    apply {
        // Load the settings when the keyboard starts, so the hooks read them from the first key press.
        keyboardServiceOnCreateMethod.addInstruction(
            0,
            "invoke-static { p0 }, $SETTINGS_CLASS_DESCRIPTOR->init(Landroid/content/Context;)V",
        )
    }
}
