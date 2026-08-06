# P01. Repo and Gradle scaffold

Objective: a public-ready repository skeleton whose debug build installs
and shows a placeholder store screen. No Rust yet.

## Context

- Model repo for conventions: `~/Apps/PGPonyAndroid` (Gradle layout,
  flavor split, pinned buildTools). Read its root `build.gradle.kts`,
  `settings.gradle.kts`, and `app/build.gradle.kts` before starting.
  Do not copy its version-history comment blocks.
- This repo already contains PLAN.md and docs/plan/. Keep them.

## Work

1. `git init` in `~/Apps/PassPonyAndroid`, set the repo-local identity
   per 00-conventions, add `.gitignore` and `LICENSE` (Apache-2.0, copy
   text from `~/Apps/PassPony/LICENSE`), commit the plan docs first.
2. Gradle wrapper (current stable Gradle), Kotlin DSL. Root
   `settings.gradle.kts` with modules `:app` and `:core`, repositories
   google() + mavenCentral(), `FAIL_ON_PROJECT_REPOS`.
3. `:app` module:
   - `applicationId com.passpony.android`, namespace the same.
   - compileSdk 36, minSdk 26, targetSdk 36, JDK 17, Kotlin jvmTarget 17.
   - Pin `buildToolsVersion` to what the chosen AGP resolves (write the
     pin down in a comment; this is a reproducibility input).
   - versionCode 100, versionName "1.0.0".
   - Product flavors `play` and `foss` on dimension `distribution`,
     exactly like PGPonyAndroid. No Google dependencies anywhere yet, so
     the flavors differ only in name for now; they exist from day one so
     nothing Google-flavored ever leaks into common code.
   - Release build type: minify on, `vcsInfo.include = false`,
     `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
   - Compose (BOM current stable), Material 3, activity-compose,
     navigation-compose, lifecycle-viewmodel-compose, appcompat (locale
     API only), datastore-preferences, androidx.biometric, core-ktx.
4. `:core` module: empty `com.android.library` shell with the package
   `com.passpony.core`. P02 fills it; creating it now keeps settings and
   CI stable.
5. App skeleton: M3 theme (dynamic color on 12+, sensible dark theme),
   `MainActivity`, a `StoreListScreen` placeholder showing the app name
   and an empty-state message, wired through navigation-compose.
6. App icon: reuse the iOS 1024 px source
   (`~/Apps/PassPony/Sources/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png`),
   generate adaptive icon foreground/background layers from it.
7. README.md: short, honest, modeled on the iOS README structure
   (what it is, first build, layout, crypto note, Not yet, license).
   State plainly that the store format and git engine are shared with
   PassPony iOS through PassPonyCore.

## Exit criteria

- `./gradlew assembleFossDebug assemblePlayDebug` both green.
- App installs on an emulator and shows the placeholder screen in light
  and dark.
- `git log` shows clean history, correct identity, no attribution.

## Out of scope

- Any Rust, any FFI, any real store logic (P02, P03).
- Autofill service registration (P12).
- CI (P15).
