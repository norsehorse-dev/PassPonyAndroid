// Empty shell for now. P02 fills this in with the UniFFI Kotlin bindings
// generated from PassPonyCore's pass-ffi crate, plus the JNA dependency
// the generated runtime needs to load the cross-compiled .so. Creating the
// module now (rather than in P02) keeps settings.gradle.kts and CI stable
// across that packet.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.passpony.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
