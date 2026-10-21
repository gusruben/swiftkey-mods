import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Builds the Nintype engine (../nintype) together with the SwiftKey facade in src/main into one
// library that the SwiftKey extension depends on. Kotlin stdlib comes from the extension build.
plugins {
    kotlin("jvm")
}

sourceSets {
    main {
        kotlin {
            srcDir(rootProject.file("nintype/engine/src/main/kotlin"))
            srcDir(rootProject.file("nintype/dict/kotlin"))
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"; targetCompatibility = "11"
}
