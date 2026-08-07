// UniFFI Kotlin bindings and jniLibs generated from PassPonyCore's pass-ffi
// crate, produced by scripts/build-core.sh (see .gitignore: generated
// sources and jniLibs are never committed, PassPonyCore is the source of
// truth). This module also holds the thin Kotlin crypto engines that
// implement the FFI's CryptoBackend interface (P04 continuation).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.passpony.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // UniFFI's generated Kotlin runtime loads the cross-compiled .so
    // through JNA. The @aar classifier pulls in the Android-flavored
    // artifact with jnidispatch prebuilt per ABI, matching how the
    // upstream uniffi-rs Android samples consume it.
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // The generated bindings' async surfaces use coroutines; pass-ffi does
    // not expose any today, so this is currently unused but harmless, and
    // avoids a second edit here the day an async function is added.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // -- Testing (instrumented smoke test) --
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
