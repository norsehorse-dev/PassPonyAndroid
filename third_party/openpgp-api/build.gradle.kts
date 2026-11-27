// The OpenPGP API client (openpgp-api, from the OpenKeychain project,
// Apache-2.0), vendored as source so the build takes no prebuilt binary
// from JitPack. See README.md here for where the files came from.
plugins {
    id("com.android.library")
}

android {
    namespace = "org.openintents.openpgp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
