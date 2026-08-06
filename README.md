# PassPony (Android)

A password manager that speaks pass and passage: plain encrypted files,
your keys, git sync. Kotlin and Jetpack Compose over the shared Rust core
([PassPonyCore](https://github.com/norsehorse-dev/passponycore)), the
same core that backs [PassPony iOS](https://github.com/norsehorse-dev/passpony).

This repository is a work in progress, built packet by packet against the
plan in `PLAN.md` and `docs/plan/`. The features below are the v1 target,
not all of them exist yet; see `docs/plan/` for what has landed.

- Browse, search, and edit entries in pass (OpenPGP) and passage (age)
  stores. Each format keeps its own store; switching never touches the
  other store's data.
- Live TOTP with a countdown ring; ephemeral, local-only clipboard that
  clears after 45 seconds.
- Git sync: clone, publish, push, and per-entry conflict resolution.
  Commit messages match the pass CLI format, so a store stays
  interoperable with desktop pass and passage users.
- System Autofill service. Only entry names are indexed; nothing decrypts
  until you fill.
- Biometric unlock with a 5-minute grace window shared with autofill.
- Localized in English, Spanish, French, German, Simplified Chinese,
  Brazilian Portuguese, and Russian, with live in-app language switching.
- First-run tour: pick a language, choose a format, clone an existing
  store or import keys, add a first pass, and turn on autofill without
  leaving the app.

## First build

Prereqs: Android Studio (current stable), rustup, the Android NDK (via
the SDK Manager), `cargo-ndk` (`cargo install cargo-ndk`), PassPonyCore
checked out (default assumed at `~/Apps/PassPonyCore`, override with
`PASSPONY_CORE`).

```
bash scripts/build-core.sh
./gradlew assembleFossDebug
```

Or open the project in Android Studio after running `scripts/build-core.sh`
once; Gradle sync picks up the generated `:core` sources from there.

`scripts/build-core.sh` cross-compiles pass-ffi for arm64-v8a and x86_64
into `core/src/main/jniLibs/` and generates the Kotlin UniFFI bindings into
`core/src/main/kotlin/`. Both outputs are gitignored; PassPonyCore is the
source of truth. Rerun the script whenever PassPonyCore changes.

The pass-format engine is [PGPonyCore-Kotlin](https://github.com/norsehorse-dev/PGPonyCore-Kotlin)
over BouncyCastle, pulled in as a git submodule at
`third_party/pgponycore-kotlin`. Clone with `--recurse-submodules`, or run
`git submodule update --init` after a plain clone.

## Layout

- `app/`: the Compose app (list/search, entry detail, editing, sync UI
  with the per-file conflict dialog, settings, onboarding) and the
  Autofill service.
- `core/`: generated UniFFI Kotlin bindings and jniLibs `.so` per ABI
  (gitignored, produced by `scripts/build-core.sh`), plus the thin Kotlin
  crypto engines that implement the FFI's `CryptoBackend` interface.
- `third_party/pgponycore-kotlin`: git submodule, pinned to a tagged
  release.
- `scripts/`: the core build script and the xcstrings-to-strings.xml
  localization converter.
- `docs/plan/`: the packet-by-packet build plan.

## Crypto

passage stores run on a Rust age engine (the `age-engine` feature of
PassPonyCore's pass-ffi), reached through the generated FFI bindings. pass
stores run on PGPonyCore-Kotlin over BouncyCastle: Cv25519 v4, X25519/Ed25519
v6, and RSA keys, all in software. Unlike PassPony iOS (which has no
software RSA path and waits on a smartcard implementation), Android opens
RSA-keyed pass stores today, since BouncyCastle provides RSA natively.

## Not yet

- Smartcard decrypt (NFC and USB-C YubiKey).
- SSH remotes for git sync; use HTTPS with a token for now.
- Passphrase-protected identities files for passage.
- Credential Manager / passkey-era autofill APIs.
- armeabi-v7a (32-bit) builds.

## License

Apache-2.0. See LICENSE.
