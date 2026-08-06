# P02. Rust pipeline: cargo-ndk + UniFFI Kotlin bindings

Objective: `scripts/build-core.sh` cross-compiles pass-ffi for Android,
generates Kotlin bindings, and a JVM-visible smoke test proves the FFI
seam end to end (`coreVersion()` returns, `verifyBackend()` round-trips).

## Context

- Reference implementation: the iOS script at
  `~/Apps/PassPony/scripts/build-core.sh`. Same shape, different targets.
- pass-ffi already exposes everything needed (see
  `~/Apps/PassPonyCore/crates/pass-ffi/src/lib.rs` and `store_api.rs`):
  `CryptoBackend` foreign trait, `verify_backend`, `core_version`,
  `PassStore`, `GitSync`, `ConflictResolver`, entry helpers, TOTP.
- pass-core depends on git2 0.20 with vendored OpenSSL on Android
  targets (already conditioned in its Cargo.toml). Vendored OpenSSL
  cross-compiles under NDK clang; perl and make must exist on the host.
- UniFFI is 0.32; the bindgen invocation pattern is documented at the
  top of pass-ffi/src/lib.rs (generate from the built library).

## Work

1. `scripts/build-core.sh` in this repo:
   - `CORE="${PASSPONY_CORE:-$HOME/Apps/PassPonyCore}"`, fail with a
     one-line message if missing.
   - Preflight checks: `cargo`, `cargo-ndk` (install hint:
     `cargo install cargo-ndk`), NDK present (honor `ANDROID_NDK_HOME`,
     else locate under `$HOME/Library/Android/sdk/ndk`), `rustup target
     add aarch64-linux-android x86_64-linux-android` run from inside
     $CORE so the pinned toolchain applies.
   - ABI list in one variable: `ABIS="arm64-v8a x86_64"`.
   - Build: from $CORE,
     `cargo ndk --platform 26 -t arm64-v8a -t x86_64 -o <this-repo>/core/src/main/jniLibs build --release -p pass-ffi`
     (add `--features age-engine` once P04 lands; the script takes an
     optional `PASS_FFI_FEATURES` env so P04 needs no script change).
   - Bindings: build the host cdylib, then run uniffi-bindgen with
     `--language kotlin` into `core/src/main/kotlin/`, mirroring the
     iOS script's use of `--library`.
   - Print a final "Done" line naming the produced files.
2. `:core` module wiring:
   - `net.java.dev.jna:jna:<current>@aar` dependency (UniFFI Kotlin
     runtime loads the .so through JNA), plus kotlinx-coroutines-core
     (generated code uses it for async surfaces; harmless if unused).
   - Nothing else. Generated sources and jniLibs stay gitignored.
3. Smoke test, `core/src/androidTest/`: instrumented test that
   - asserts `coreVersion()` is nonempty,
   - implements `CryptoBackend` in Kotlin as the byte-flip engine (the
     same stand-in the core's own tests use) and asserts
     `verifyBackend(flip)` succeeds,
   - opens a `PassStore` in a temp dir (`StoreFormat.PASSAGE`), writes an
     entry through the flip backend, reads it back byte-identical, and
     checks `entryPassword`, `entryFields`, `entryTotp` against the
     values in pass-ffi's own `store_object_round_trips_through_ffi_surface`
     test.
4. Document the one-time host setup in README (rustup, cargo-ndk, NDK
   via Android Studio SDK manager).

## Notes

- `GitSync` has a constructor named `init`; in Kotlin that call is
  `GitSync.`init`(root, format)` with backticks. Swift needed the same
  escape. Do not rename the constructor in the core.
- If x86_64 vendored-OpenSSL compilation fails on the host, fix the
  script environment rather than dropping the ABI; CI emulators need it.

## Exit criteria

- `bash scripts/build-core.sh` from a clean checkout produces
  `core/src/main/jniLibs/{arm64-v8a,x86_64}/libpass_ffi.so` and
  compiling Kotlin bindings.
- The instrumented smoke test passes on an emulator.
- `./gradlew :app:assembleFossDebug` still green with :core linked in.

## Out of scope

- age engine (P04), real crypto (P05/P06), any UI.
