package app.revanced.patches.swiftkey.privacy

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

// Adjust ships unobfuscated, so its public entry point is a stable match.
internal val BytecodePatchContext.adjustInitSdkMethod by gettingFirstMethodDeclaratively {
    name("initSdk")
    definingClass("Lcom/adjust/sdk/Adjust;")
}

// Adjust's provider auto-starts its lifecycle tracking before any app code runs.
internal val BytecodePatchContext.adjustLifecycleProviderOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("Lcom/adjust/sdk/SystemLifecycleContentProvider;")
}

// Crashlytics' data collection arbiter: every report and upload asks it whether collection is on.
// Its debug log names the manifest flag, which survives obfuscation.
internal val BytecodePatchContext.crashlyticsCollectionEnabledMethod by gettingFirstMethodDeclaratively(
    "firebase_crashlytics_collection_enabled manifest flag",
) {
    returnType("Z")
    parameterTypes()
}
