package app.revanced.patches.swiftkey.privacy

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext

// Adjust ships unobfuscated, so its public entry point is a stable match.
internal val BytecodePatchContext.adjustInitSdkMethod by gettingFirstMethodDeclaratively {
    name("initSdk")
    definingClass("Lcom/adjust/sdk/Adjust;")
}
