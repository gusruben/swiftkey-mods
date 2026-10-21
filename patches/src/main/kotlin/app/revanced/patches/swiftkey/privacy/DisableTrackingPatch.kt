package app.revanced.patches.swiftkey.privacy

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val disableTrackingPatch = bytecodePatch(
    name = "Disable tracking",
    description = "Disables Adjust attribution tracking and Firebase Crashlytics reporting.",
) {
    compatibleWith("com.touchtype.swiftkey")

    apply {
        // Adjust.initSdk is the only way the app starts Adjust; without it no events are sent.
        adjustInitSdkMethod.returnEarly()
        // The provider still loads, but no longer registers Adjust's lifecycle callbacks.
        adjustLifecycleProviderOnCreateMethod.returnEarly(false)
        // Microsoft already opts out of Firebase Analytics in the manifest, but not Crashlytics.
        crashlyticsCollectionEnabledMethod.returnEarly(false)
    }
}
