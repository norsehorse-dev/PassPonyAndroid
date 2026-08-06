# PassPony Android (project plan)

Self-contained plan for the sessions that build PassPony for Android.
Goal: full parity with PassPony iOS 1.0, over the same Rust core, shipped
to Play, F-Droid, and GitHub APK releases.

The build is sliced into 16 session-sized packets under `docs/plan/`.
Each packet is a standalone brief for one focused session: context, exact
work items, exit criteria, and what not to touch. Run them in order unless
the dependency notes in a packet say otherwise.

Session protocol: start every build session with
"Read docs/plan/00-conventions.md and docs/plan/PXX-<name>.md, then
execute the packet." Nothing else should need to be said.

## Decisions (settled, do not re-litigate)

- age (passage) engine lives in Rust behind the FFI, as an Android-only
  cargo feature (`age-engine`) of pass-ffi. iOS keeps its platform engines
  and its FFI surface unchanged.
- OpenPGP engine is PGPonyCore-Kotlin plus BouncyCastle, consumed as a
  git submodule pinned to a tagged commit and wired in with Gradle
  `includeBuild`. Android opens RSA-keyed pass stores that iOS cannot
  yet; both READMEs keep their "Not yet" lists honest in both directions.
- v1 scope is full iOS parity: browse/search/edit, git sync with
  per-entry conflict resolution, live TOTP, autofill, onboarding tour,
  seven languages.
- Distribution: Play Store, F-Droid, and signed APKs on GitHub releases.
  F-Droid makes reproducibility a requirement, not a nice-to-have.
- ABIs: arm64-v8a and x86_64. armeabi-v7a is deferred; the build script
  takes its ABI list from one variable so adding it later is a one-line
  change.
- applicationId: `com.passpony.android` (house pattern, matches
  PGPonyAndroid). Kotlin package `com.passpony.android`.
- Stack: Kotlin, Jetpack Compose, Material 3, compileSdk 36, minSdk 26,
  targetSdk 36, JDK 17. Per-app locales via AppCompatDelegate so the
  in-app switcher works below Android 13.
- Version line starts at versionName 1.0.0, versionCode 100 (the house
  numbering leaves room below 100 for internal builds).

## What carries over for free

The UniFFI surface in PassPonyCore generates Kotlin as readily as Swift.
Everything below arrives on Android as generated bindings over a
cargo-ndk cross-compiled library, already tested by the parity and git
matrix suites:

- store model, entry codec (byte-faithful field edits)
- git engine: clone, publish, push, sync, per-entry ConflictResolver
- TOTP derivation
- commit-message parity with the pass CLI

The seven-language string corpus ports by converting Localizable.xcstrings
to strings.xml (plurals included; Russian one/few/many maps cleanly to
Android quantity strings). The converter lives in this repo's `scripts/`.

## Repo and directory map

    ~/Apps/PassPonyAndroid        this repo (public, norsehorse-dev, Apache-2.0)
      app/                        Compose app + autofill service
      core/                       Gradle library module: generated Kotlin
                                  bindings + jniLibs .so per ABI (both
                                  gitignored, produced by scripts/build-core.sh)
      third_party/pgponycore-kotlin   git submodule, pinned tag
      scripts/build-core.sh       cargo-ndk build + uniffi-bindgen kotlin
      scripts/xcstrings_to_strings.py  localization converter
      docs/plan/                  this plan's packets
      fastlane/metadata/          Play and F-Droid listing texts

    ~/Apps/PassPonyCore           core changes land here (already public)
      crates/pass-ffi             new cargo feature "age-engine" (age crate);
                                  Kotlin bindgen target added to CI

## Architecture notes

- Engine seam: the FFI's `CryptoBackend` callback interface, implemented
  in Kotlin. passage routes to the Rust age engine through a thin Kotlin
  wrapper; pass routes to PGPonyCore-Kotlin over BouncyCastle (Cv25519
  v4, X25519/Ed25519 v6, and RSA, which BC gives us in software).
- Storage: app-private filesDir. No app-group dance; the autofill
  service runs in the same app process family, which removes the entire
  class of iOS bridge/registration problems.
- Unlock gate: androidx BiometricPrompt with the 5-minute grace window,
  stamp in app-private DataStore. Same policy text as iOS.
- Autofill: android.service.autofill.AutofillService with datasets built
  from the entry-name index and the same domain-hint heuristic as
  AutofillIdentities.serviceHint on iOS. Credential Manager (passkey-era
  API) is explicitly post-v1, mirroring the iOS "Not yet" discipline.
- Git over HTTPS with token remotes, same as iOS. SSH stays on the
  shared not-yet list until the core grows the credentials callback.

## Packet index

Phase 1, scaffold:
- P01 repo + Gradle scaffold (app builds, placeholder UI)
- P02 Rust pipeline (build-core.sh, bindings compile, verify_backend green)
- P03 store browse (folder list, search, demo store renders)

Phase 2, engines:
- P04 age-engine feature in pass-ffi (PassPonyCore repo work)
- P05 passage engine on Android (identities file, round-trip green)
- P06 pass engine on Android (PGPonyCore-Kotlin, keys, RSA, round-trip)

Phase 3, UI parity:
- P07 entry detail + TOTP ring + ephemeral clipboard
- P08 add/edit/move/delete with byte-faithful saves and commit messages
- P09 git sync screen with the three-choice conflict dialog
- P10 settings (format switch, key management, re-encrypt, About)
- P11 unlock gate (BiometricPrompt, grace window, panic lock)

Phase 4, autofill:
- P12 autofill service (inline suggestions, biometric confirm, picker)

Phase 5, onboarding and localization:
- P13 xcstrings converter + seven-language resources
- P14 onboarding tour (seven slides, autofill enable intent)

Phase 6, release engineering:
- P15 CI + reproducible build recipe
- P16 storefront metadata + release flow

Dependency shape: P01 to P03 are strictly sequential. P04 can run in
parallel with P01 to P03 (different repo). P05 needs P02 + P04; P06
needs P02. P07 to P10 need P05 (or P06) for a real engine but are
otherwise independent of each other. P11 needs P03. P12 needs P07 and
P11. P13 anytime after P03; P14 needs P10 + P11 + P12 + P13. P15 needs P02;
P16 last.

P02, P04, P05, and P06 carry the technical risk. Everything after is
porting work with the iOS app as the spec.

## Standards that apply from commit zero

See docs/plan/00-conventions.md. Highlights:

- Repo-local git identity norsehorse-dev before the first commit.
- No AI attribution anywhere, ever.
- local.properties, keystores, build/, .gradle/, .kotlin/, .DS_Store,
  core/src/main/jniLibs/, and generated bindings gitignored from the start.
- No em dashes in anything written for this project.
- Command blocks handed to Kevin: runnable lines only.

## Open questions (decide when reached, not now)

- Play listing name: "PassPony: Git Password Manager" fits Play's
  30-character title limit exactly; confirm at P16 against the live
  console counter.
- Whether the xcstrings converter graduates to PassPonyCore once
  PassPonyDesktop wants the same strings.
- Package-name-to-domain heuristics for autofill beyond exact webDomain
  matching (post-v1 unless P12 testing shows it is trivial).
