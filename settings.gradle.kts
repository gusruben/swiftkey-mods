rootProject.name = "swiftkey-mods"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            // ReVanced's gradle plugin lives on GitHub Packages, which needs a GitHub token with read:packages.
            // Set githubPackagesUsername and githubPackagesPassword in ~/.gradle/gradle.properties.
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches-gradle-plugin")
            credentials(PasswordCredentials::class)
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.10"
}

settings {
    extensions {
        defaultNamespace = "app.revanced.extension"

        // Must resolve to an absolute path (not relative),
        // otherwise the extensions in subfolders will fail to find the proguard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

include(":patches:stub")

// Nintype engine (nintype/) + SwiftKey facade, built into a jar the SwiftKey extension depends on.
include(":nintype-glue")
