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

    lint {
        // See lint.xml: suppresses a known false-positive NewApi warning
        // in UniFFI's generated Cleaner fallback code, scoped to the
        // generated uniffi/ path only.
        lintConfig = file("lint.xml")
    }
}

// Build the Rust core (cross-compiled pass-ffi .so + generated UniFFI Kotlin
// bindings) during the Gradle build, by wrapping scripts/build-core.sh in a
// preBuild-wired task. This is what lets the F-Droid recipe use `gradle: [foss]`
// with no `build:` block: on F-Droid's fresh clone the generated outputs are
// absent (both are gitignored), so Gradle produces the .so itself, during the
// build and after the scanner. Every other caller (local dev, CI,
// tools/verify_repro.sh, the docker/ release) still runs build-core.sh
// explicitly first, so the outputs already exist and this task no-ops.
val cargoBinDir: String =
    (System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo") + "/bin"

val buildCore = tasks.register<Exec>("buildCore") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-core.sh")
    val soFile = file("src/main/jniLibs/arm64-v8a/libpass_ffi.so")
    val bindings = file("src/main/kotlin/uniffi/pass_ffi/pass_ffi.kt")
    outputs.files(soFile, bindings)
    // Idempotent: only build when the generated outputs are missing (local
    // dev, CI, verify_repro, and the docker release all run build-core.sh
    // first, so this no-ops for them).
    onlyIf { !soFile.exists() || !bindings.exists() }
    // Resolve PATH and the NDK lazily, at execution time and only when the
    // task actually runs. Evaluating android.sdkDirectory at configuration
    // time would break `gradle` itself whenever the SDK location isn't set
    // yet (a fresh clone with no local.properties), even for tasks that never
    // touch the core.
    val ndkVersionProp = providers.gradleProperty("ndkVersion")
    doFirst {
        // F-Droid runs the gradle step in a shell that does NOT inherit the
        // PATH export from the recipe's prebuild:, so put cargo/cargo-ndk on
        // PATH here. Resolve the NDK the way AGP/F-Droid provide it, since
        // build-core.sh's own fallback is a macOS-only path.
        environment("PATH", "$cargoBinDir${File.pathSeparator}${System.getenv("PATH") ?: ""}")
        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK")
            ?: run {
                val sdkDir = System.getenv("ANDROID_HOME")
                    ?: System.getenv("ANDROID_SDK_ROOT")
                    ?: android.sdkDirectory.absolutePath
                "$sdkDir/ndk/${ndkVersionProp.get()}"
            }
        environment("ANDROID_NDK_HOME", ndkHome)
    }
}

tasks.named("preBuild") { dependsOn(buildCore) }

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
