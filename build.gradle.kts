// Top-level build file for PassPony Android
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    // For :pgponycore (P06's PGPonyCore-Kotlin submodule) — a plain
    // kotlin("jvm") module, same Kotlin version as the rest of the build so
    // there is exactly one Kotlin compiler in play.
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
}
