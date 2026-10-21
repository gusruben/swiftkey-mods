package app.revanced.patches.swiftkey.visual

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val hideSpaceBarBrandingPatch = bytecodePatch(
    name = "Hide space bar branding",
    description = "Removes the \"Microsoft SwiftKey\" wordmark drawn on the space bar when fewer than " +
        "two languages are installed (the normal case). The space bar is left blank; the language " +
        "name shown instead when two or more languages are installed is untouched.",
) {
    compatibleWith("com.touchtype.swiftkey")

    apply {
        val brandedClass = brandedSpaceKeyContentToStringMethod.definingClass

        // The method that decodes and draws the wordmark bitmap, in the same class as the
        // toString anchor above. Its own name is obfuscated, but it is the only method on this
        // class that returns the space key drawable type.
        val drawMethod = classDefs[brandedClass]?.methods?.firstOrNull { it.returnType == "Lqc0/p;" }
            ?: throw PatchException("Could not find the branded space key draw method")

        // Make it return the same "draw nothing" drawable the app itself already uses when
        // branding is turned off, instead of decoding and tinting the wordmark bitmap.
        firstMethod(drawMethod).addInstructions(
            0,
            """
                new-instance v0, Lqc0/d;
                invoke-direct { v0 }, Lqc0/d;-><init>()V
                return-object v0
            """,
        )
    }
}
