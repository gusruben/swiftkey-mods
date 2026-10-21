android {
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    // Nintype engine + the SwiftKey facade, built from source by :nintype-glue. R8 keeps only what the extension reaches.
    implementation(project(":nintype-glue"))
}
