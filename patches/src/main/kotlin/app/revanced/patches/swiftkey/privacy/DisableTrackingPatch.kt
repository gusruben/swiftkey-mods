package app.revanced.patches.swiftkey.privacy

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.util.returnEarly
import org.w3c.dom.Element

private const val ADJUST_PROVIDER = "com.adjust.sdk.SystemLifecycleContentProvider"

private val disableTrackingManifestPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { document ->
            // Adjust's provider auto-starts its lifecycle tracking before any app code runs.
            val providers = document.getElementsByTagName("provider")
            for (i in providers.length - 1 downTo 0) {
                val provider = providers.item(i) as Element
                if (provider.getAttribute("android:name") == ADJUST_PROVIDER) {
                    provider.parentNode.removeChild(provider)
                }
            }

            // Microsoft already opts out of Firebase Analytics this way, but not Crashlytics.
            val application = document.getElementsByTagName("application").item(0) as Element
            document.createElement("meta-data").apply {
                setAttribute("android:name", "firebase_crashlytics_collection_enabled")
                setAttribute("android:value", "false")
            }.let(application::appendChild)
        }
    }
}

@Suppress("unused")
val disableTrackingPatch = bytecodePatch(
    name = "Disable tracking",
    description = "Disables Adjust attribution tracking and Firebase Crashlytics reporting.",
) {
    compatibleWith("com.touchtype.swiftkey")

    dependsOn(disableTrackingManifestPatch)

    apply {
        // Adjust.initSdk is the only way the app starts Adjust; without it no events are sent.
        adjustInitSdkMethod.returnEarly()
    }
}
