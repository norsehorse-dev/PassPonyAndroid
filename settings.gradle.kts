pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download a matching JDK itself (from the foojay Disco
    // API) whenever a module's toolchain requirement -- JDK 17 here,
    // core/build.gradle.kts and app/build.gradle.kts -- isn't already
    // satisfied by something already installed and auto-detected. Without
    // this, "Cannot find a Java installation... Toolchain download
    // repositories have not been configured" is a hard failure on any
    // machine/clone that doesn't already have a JDK 17 sitting somewhere
    // Gradle's own auto-detection happens to look, which is exactly what
    // tools/verify_repro.sh rebuild's clean, isolated clones hit even on
    // machines (like this project's own) that build fine day to day
    // through whatever JDK the IDE or a prior global gradle.properties
    // setting was already supplying. CI doesn't strictly need this --
    // actions/setup-java already puts a JDK 17 on PATH -- but it's a
    // harmless, more portable fallback there too.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PassPonyAndroid"
include(":app")
include(":core")

// PGPonyCore-Kotlin (P06): a git submodule (third_party/pgponycore-kotlin),
// pinned to a specific commit rather than a tagged release — the upstream
// repo has none yet (single "Initial 3.0.0 Commit" as of this pin). See
// README's build section for the pinned SHA and how to re-pin once a real
// release exists. Included directly as a subproject (not via includeBuild
// dependency substitution) since the module publishes no group/version of
// its own — it is meant to be consumed as source, the way this submodule
// pattern is used.
include(":pgponycore")
project(":pgponycore").projectDir = file("third_party/pgponycore-kotlin/pgponycore")
