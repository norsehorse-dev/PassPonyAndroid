# P05. passage engine on Android

Objective: passage stores open with real age crypto. The fixture store
from PassPonyCore round-trips on the emulator; first run generates and
persists an identities file exactly like iOS.

Requires: P02 (pipeline), P04 (age-engine feature).

## Context (iOS files to mirror)

- `Sources/Shared/AgePonyEngine.swift` and its `AgeIdentityStore`:
  identities file location, first-run generation with the comment
  header, 0600 permissions, engine loading.
- The Rust side now exposes `AgeEngine` and `generateIdentity()` from
  P04; Kotlin only wraps and manages the file.

## Work

1. Rebuild core with the feature: run
   `PASS_FFI_FEATURES=age-engine bash scripts/build-core.sh` and make
   the script default `PASS_FFI_FEATURES` to `age-engine` from now on
   (it is Android-only by definition here).
2. `crypto/AgeIdentityStore.kt`:
   - identities file at `filesDir/identities` (same filename as iOS).
   - `loadOrCreateEngine()`: on first run call `generateIdentity()`,
     write the same three-line format iOS writes (`# created by
     PassPony`, `# public key: <recipient>`, the secret line), set file
     permissions owner-only, then construct `AgeEngine` from the file
     text.
   - Never log identity material. Never copy it into a String that
     outlives the call more than necessary.
3. `crypto/EngineProvider.kt`: `engine(format)` returns the passage
   engine (AgeEngine wrapped as `CryptoBackend`) for PASSAGE and, until
   P06, the debug flip engine for PASS in debug builds and a throwing
   stub in release.
4. Swap the ViewModel and demo seed from DevCryptoEngine to
   EngineProvider. Demo entries now encrypt with real age.
5. Fixture verification (instrumented test):
   - bundle the passage fixture store + fixture identities file from
     PassPonyCore's corpus as androidTest assets;
   - copy to a temp dir, open with the real engine, assert entry
     plaintexts match the corpus expectations;
   - write one entry, read it back byte-identical, then decrypt the
     written file with the corpus tooling expectations in mind (content
     equality is the assertion; commit parity is P08/P09 territory).

## Exit criteria

- Fresh install: store seeds through real age crypto, entries visible.
- Instrumented fixture test green on emulator (both ABIs if the host
  can run x86_64 images; arm64 image acceptable on Apple Silicon).
- An identities file created on first run matches the iOS file shape
  byte-for-byte modulo the key material itself.

## Out of scope

- pass format (P06). Import/export of identities (P10). Passphrase
  protection (not-yet list).
