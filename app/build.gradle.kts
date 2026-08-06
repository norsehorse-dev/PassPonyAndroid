import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load signing properties from keystore.properties at project root.
// File is gitignored, see .gitignore. Never commit it.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.passpony.android"
    compileSdk = 36
    // Pinned rather than left to float with whatever AGP resolves by
    // default: an uncontrolled input to the F-Droid reproducible build
    // (see docs/plan/P15-ci-reproducible.md). Bump this deliberately
    // alongside an AGP upgrade, matching the PGPonyAndroid convention.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.passpony.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Falls back to debug signing when no keystore.properties exists
            // (a fresh clone with no release key) so a plain assembleRelease
            // never hard-fails.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            // AGP VCS info embedding makes the release APK depend on which
            // working tree it was built from; disabling it is required for
            // F-Droid's byte-identical reproducible build comparison.
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Distribution flavors, mirroring the PGPonyAndroid pattern:
    // play -> Google Play build.
    // foss -> F-Droid / IzzyOnDroid / direct APK, no Google dependencies.
    // Both flavors are identical for now; the split exists from day one so
    // nothing Google-flavored ever leaks into common code later.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Drop the Google-signed dependency-metadata blob from build outputs.
    // F-Droid and IzzyOnDroid prefer it gone; has no effect on behavior.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    // -- Jetpack Compose --
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // -- DataStore (store format + onboarding + unlock-gate prefs) --
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // -- AndroidX core --
    implementation("androidx.core:core-ktx:1.15.0")

    // -- Per-app language preferences (P13) --
    // AppCompatDelegate.setApplicationLocales() is the only piece used;
    // pulling the library in is required for correct Activity recreation
    // on locale change on API levels below 33.
    implementation("androidx.appcompat:appcompat:1.7.0")

    // -- Biometric unlock gate (P11) --
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // -- Testing --
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
