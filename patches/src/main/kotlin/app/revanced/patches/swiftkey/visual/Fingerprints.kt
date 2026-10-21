package app.revanced.patches.swiftkey.visual

import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

// The trail's point (position, start time, distance along the stroke) is a Kotlin data class.
// Its generated toString keeps the original names, so it anchors the class through obfuscation.
internal val BytecodePatchContext.renderPointToStringMethod by gettingFirstImmutableMethodDeclaratively(
    "RenderPoint(point=",
) {
    name("toString")
    returnType("Ljava/lang/String;")
}

// The base keyboard view (every key layout extends it) converts a normalised point to pixels.
// The Kotlin null check on that parameter keeps its name, which anchors the class.
// The same class resolves the key under each pointer and draws the keys in onDraw.
internal val BytecodePatchContext.keyboardViewPointMethod by gettingFirstImmutableMethodDeclaratively(
    "virtualPoint",
) {
    returnType("Landroid/graphics/Point;")
    parameterTypes("Landroid/graphics/PointF;")
}

// A key's geometry (its area plus the theme's inset for the background, both normalised).
// Only the helper that turns the inset into a ratio throws this.
internal val BytecodePatchContext.keyGeometryInsetRatioMethod by gettingFirstImmutableMethodDeclaratively(
    "Divisor cannot have 0 height or width",
) {
    returnType("Landroid/graphics/RectF;")
    parameterTypes()
}

// Builds a key's drawable for the theme: a LayerDrawable subclass holding the theme's key
// background (layer 0) and the label rendered from the key's content (layer 1).
// Its Kotlin null checks keep the parameter names.
internal val BytecodePatchContext.keyDrawableFactoryMethod by gettingFirstMethodDeclaratively(
    "area",
    "styleId",
    "subStyle",
) {
    returnType("L")
    parameterTypes("L", "L", "L", "L", "L")
}

// Key content that stacks a top label (the long-press symbol) over a bottom one (the letter).
internal val BytecodePatchContext.hintContentToStringMethod by gettingFirstImmutableMethodDeclaratively(
    "{Bottom: ",
    ", Top: ",
) {
    name("toString")
    returnType("Ljava/lang/String;")
}

// The "Long-press symbols" setting. While it's off, the layout drops every key's top label.
internal val BytecodePatchContext.alwaysShowTopTextPreferenceMethod by gettingFirstMethodDeclaratively(
    "pref_should_always_show_top_text",
) {
    returnType("Z")
    parameterTypes()
}

// Space key content shown when fewer than two languages are installed: just the "Microsoft
// SwiftKey" wordmark bitmap, no text. Its generated toString keeps the class's original name
// and anchors it through obfuscation.
internal val BytecodePatchContext.brandedSpaceKeyContentToStringMethod by gettingFirstImmutableMethodDeclaratively(
    "BrandedSpaceKeyContent",
) {
    name("toString")
    returnType("Ljava/lang/String;")
}
