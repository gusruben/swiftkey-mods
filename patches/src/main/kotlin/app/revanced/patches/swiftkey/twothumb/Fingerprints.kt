package app.revanced.patches.swiftkey.twothumb

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

// These deliberately duplicate a few matchers from visual/ and typing/ so the two-thumb
// patches don't break when those files change.

// The base keyboard view (every key layout extends it) converts a normalised point to pixels.
// The Kotlin null check on that parameter keeps its name, which anchors the class. Its superclass
// is the FrameLayout whose final onTouchEvent every keyboard touch goes through.
internal val BytecodePatchContext.traceKeyboardViewPointMethod by gettingFirstImmutableMethodDeclaratively(
    "virtualPoint",
) {
    returnType("Landroid/graphics/Point;")
    parameterTypes("Landroid/graphics/PointF;")
}

// A key of the layout. Its toString keeps the original layout, which anchors the class.
// The class also has the key's content description getter (the letter, for letter keys).
internal val BytecodePatchContext.traceKeyToStringMethod by gettingFirstImmutableMethodDeclaratively(
    " { Content: ",
    ", Area: ",
) {
    name("toString")
    returnType("Ljava/lang/String;")
}

internal val BytecodePatchContext.traceKeyboardServiceOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("Lcom/touchtype/KeyboardService;")
    returnType("V")
    parameterTypes()
}

internal val BytecodePatchContext.traceKeyboardServiceFinishInputViewMethod by gettingFirstMethodDeclaratively {
    name("onFinishInputView")
    definingClass("Lcom/touchtype/KeyboardService;")
    returnType("V")
    parameterTypes("Z")
}

// The input model's onFlowComplete(breadcrumb, top candidate, secondary candidate). It posts the
// FlowCompleteEvent whose handler commits the top candidate. Its error log keeps this name.
internal val BytecodePatchContext.flowCompleteMethod by gettingFirstMethodDeclaratively(
    "onFlowComplete",
) {
    returnType("V")
    parameterTypes("L", "L", "L")
}

// The main keyboard view (the one that delegates pointers to other key views). This public setter
// keeps its name. The same class listens for "Flow active" and cancels every other pointer while it is.
internal val BytecodePatchContext.mainKeyboardCoachmarkSetterMethod by gettingFirstMethodDeclaratively {
    name("setOnboardingCoachmarkDisplayer")
    returnType("V")
    parameterTypes("L")
}

// The candidate update callable (runs on Fluency's thread). Its error log keeps this tag. It calls
// Fluency's predict and turns the Predictions into SwiftKey's candidates.
internal val BytecodePatchContext.updateCandidatesCallableMethod by gettingFirstMethodDeclaratively(
    "UpdateCandidatesCallable",
) {
    returnType("Ljava/lang/Object;")
    parameterTypes("L")
}

// SwiftKey's "add to user model" Fluency task. It reads the predictor context's model holder and
// checks that holder's "user model writable" flag before Trainer.addSequence; this message keeps the anchor.
internal val BytecodePatchContext.addToUserModelTaskMethod by gettingFirstImmutableMethodDeclaratively(
    "User model must be writable when calling addToUserModel",
)

internal val BytecodePatchContext.keyboardServiceOnDestroyMethod by gettingFirstMethodDeclaratively {
    name("onDestroy")
    definingClass("Lcom/touchtype/KeyboardService;")
    returnType("V")
    parameterTypes()
}

// The bar's candidate tap: onPredictionSelected(breadcrumb, candidate, commit origin, position).
// It forwards to the input model's commit; its error log keeps this name.
internal val BytecodePatchContext.predictionSelectedMethod by gettingFirstMethodDeclaratively(
    "onPredictionSelected",
) {
    returnType("V")
    parameterTypes("L", "L", "L", "I")
}

// SwiftKey's long-press delegate (a key's long-press action): its constructor's argument check
// keeps this message. On a key down it posts the long-press runnable after the delay.
internal val BytecodePatchContext.longPressDelegateConstructorMethod by gettingFirstMethodDeclaratively(
    "LongPressDelegate invalid timeout (must be > 0)",
) {
    name("<init>")
}
