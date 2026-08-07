pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
