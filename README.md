# PassPony (Android)

[![CI](https://github.com/norsehorse-dev/PassPonyAndroid/actions/workflows/ci.yml/badge.svg)](https://github.com/norsehorse-dev/PassPonyAndroid/actions/workflows/ci.yml)

A password manager that speaks pass and passage: plain encrypted files,
your keys, git sync. Kotlin and Jetpack Compose over the shared Rust core
([PassPonyCore](https://github.com/norsehorse-dev/passponycore)), the
same core that backs [PassPony iOS](https://github.com/norsehorse-dev/passpony).

Built packet by packet against the plan in `PLAN.md` and `docs/plan/`; see
that directory for how each feature below landed. The v1 feature set below
is implemented and CI-green; release engineering (packaging, storefront
listings, the signed release flow) is the packet in progress now -- see
`docs/plan/P16-release.md` and `docs/RELEASE_CHECKLIST.md`.

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
the SDK Manager), `cargo-ndk` (`cargo install cargo-ndk`).

```
git submodule update --init --recursive   # first time only, or after a plain clone
bash scripts/build-core.sh
./gradlew assembleFossDebug
```

Or open the project in Android Studio after running `scripts/build-core.sh`
once; Gradle sync picks up the generated `:core` sources from there.

`scripts/build-core.sh` cross-compiles pass-ffi for arm64-v8a and x86_64
into `core/src/main/jniLibs/` and generates the Kotlin UniFFI bindings into
`core/src/main/kotlin/`. Both outputs are gitignored; PassPonyCore is the
source of truth. Rerun the script whenever PassPonyCore changes.

PassPonyCore itself is pulled in as a git submodule at
`third_party/passponycore`, pinned to a fixed commit rather than a
sibling checkout you have to keep up to date by hand (that was the
pre-P16 arrangement: a separate `~/Apps/PassPonyCore` checkout plus a
`PASSPONY_CORE_SHA` env var CI and the reproducibility gate each had to
pass around). Clone this repo with `--recurse-submodules`, or run
`git submodule update --init --recursive` after a plain clone.
`PASSPONY_CORE` still overrides `scripts/build-core.sh`'s default if you
want to point it at some other PassPonyCore checkout for local dev.

The pass-format engine is [PGPonyCore-Kotlin](https://github.com/norsehorse-dev/PGPonyCore-Kotlin)
over BouncyCastle, pulled in the same way as a git submodule at
`third_party/pgponycore-kotlin`.

Pinned to commit `0c09788bda4c208297ae7d1668bdfec10b0f24fd`, not a tagged
release: PGPonyCore-Kotlin has none yet. Re-pin to a real tag with
`cd third_party/pgponycore-kotlin && git fetch --tags && git checkout <tag>`
once one exists, then commit the updated submodule reference.

(Bumped from the initial `080815d` pin to drop `kotlin { jvmToolchain(17) }`
in favor of an explicit `compilerOptions.jvmTarget` -- F-Droid's buildserver
disables Gradle's toolchain auto-provisioning, so a toolchain request with
no matching JDK already installed fails outright. See `docs/fdroid/`'s
recipe and NorseHorse's F-Droid Submission Playbook, which hit the same
thing on QuorumPony's own pure-JVM core module.)

## Layout

- `app/`: the Compose app (list/search, entry detail, editing, sync UI
  with the per-file conflict dialog, settings, onboarding) and the
  Autofill service.
- `core/`: generated UniFFI Kotlin bindings and jniLibs `.so` per ABI
  (gitignored, produced by `scripts/build-core.sh`), plus the thin Kotlin
  crypto engines that implement the FFI's `CryptoBackend` interface.
- `third_party/pgponycore-kotlin`: git submodule, pinned to a commit (no
  tagged release exists upstream yet, see the build section above).
- `third_party/passponycore`: git submodule, pinned to a commit -- the
  Rust core cross-compiled by `scripts/build-core.sh` (see the build
  section above and `docs/REPRODUCIBLE.md`).
- `scripts/`: the core build script, the xcstrings-to-strings.xml
  localization converter, the `make-*-fixture.sh` crypto test fixture
  generators, and `release.sh` (the signed dry-run release build driver,
  see `docs/RELEASE_CHECKLIST.md`).
- `tools/verify_repro.sh`: the reproducible-build determinism gate --
  rebuilds a ref twice from clean clones and diffs the result. See
  `docs/REPRODUCIBLE.md`.
- `docs/plan/`: the packet-by-packet build plan.
- `docs/REPRODUCIBLE.md`, `docs/RELEASE_CHECKLIST.md`, `docs/fdroid/`:
  reproducibility documentation and the release process (P15/P16).
- `fastlane/metadata/`: Play/F-Droid store listing text, one directory
  per locale.

## Crypto

passage stores run on a Rust age engine (the `age-engine` feature of
PassPonyCore's pass-ffi), reached through the generated FFI bindings. pass
stores run on PGPonyCore-Kotlin over BouncyCastle: Cv25519 v4, X25519/Ed25519
v6, and RSA keys, all in software. Unlike PassPony iOS (which has no
software RSA path and waits on a smartcard implementation), Android opens
RSA-keyed pass stores today, since BouncyCastle provides RSA natively as a
deliberate capability difference, not an oversight. The recipient-resolution
rule (fingerprint or 16-hex key ID, `.gpg-id` per entry) is the same rule
PassPony iOS uses. A passphrase-protected pass secret key is cached for 5
minutes after entry, sealed with an Android Keystore AES-GCM key
(`crypto/PassphraseCache.kt`), reading the same grace-period constant as
Android's own `store/UnlockGate.kt` -- one 5-minute window shared across
biometric unlock, autofill, and the pass passphrase cache, matching iOS's
UnlockGate parity.

## Not yet

- Smartcard decrypt (NFC and USB-C YubiKey).
- SSH remotes for git sync; use HTTPS with a token for now.
- Passphrase-protected identities files for passage.
- Credential Manager / passkey-era autofill APIs.
- armeabi-v7a (32-bit) builds.

## License

Apache-2.0. See LICENSE.
