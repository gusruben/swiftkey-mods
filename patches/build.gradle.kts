group = "app.revanced"

// Filled in by GitHub Actions; empty for local builds.
val repoUrl = System.getenv("GITHUB_REPOSITORY")?.let { "https://github.com/$it" } ?: ""

patches {
    about {
        name = "SwiftKey Mods"
        description = "ReVanced patches for Microsoft SwiftKey, including two-finger swipe typing ported from Nintype"
        source = repoUrl
        author = "SwiftKey Mods"
        contact = repoUrl
        website = repoUrl
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.apksig)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters"
        )
    }
}

