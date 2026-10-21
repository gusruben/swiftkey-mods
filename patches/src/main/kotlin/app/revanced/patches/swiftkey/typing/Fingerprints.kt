package app.revanced.patches.swiftkey.typing

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

// Fluency's Java API ships unobfuscated, since the native library binds to it by name.
// Both getters convert from native once and cache the result on the prediction.
internal val BytecodePatchContext.predictionGetPredictionMethod by gettingFirstMethodDeclaratively {
    name("getPrediction")
    definingClass("Lcom/microsoft/fluency/Prediction;")
    returnType("Ljava/lang/String;")
}

// The typed override, not the AbstractList bridge that returns Object.
internal val BytecodePatchContext.predictionGetTermMethod by gettingFirstMethodDeclaratively {
    name("get")
    definingClass("Lcom/microsoft/fluency/Prediction;")
    returnType("Lcom/microsoft/fluency/Term;")
    parameterTypes("I")
}

internal val BytecodePatchContext.keyboardServiceOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("Lcom/touchtype/KeyboardService;")
    returnType("V")
    parameterTypes()
}

// Builds a toolbar item for an item id; ids without a case end in this exception.
internal val BytecodePatchContext.toolbarItemFactoryMethod by gettingFirstMethodDeclaratively(
    "No toolbar item for toolbar item type ",
) {
    returnType("Ljava/lang/Object;")
}

// Builds the toolbar from the saved item order. It keeps only ids in the static list of
// known toolbar items, read here, so that list is how a new id gets in.
internal val BytecodePatchContext.toolbarBuildMethod by gettingFirstImmutableMethodDeclaratively(
    "seen_ordering",
    "changed_ordering",
    "toolbar_default_order_override",
)
