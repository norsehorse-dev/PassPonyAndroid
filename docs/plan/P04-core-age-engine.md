# P04. PassPonyCore: age-engine feature in pass-ffi

Objective: pass-ffi grows an optional `age-engine` cargo feature that
provides a Rust age (passage) crypto engine over the FFI, tested against
the existing fixture corpus. iOS surface unchanged when the feature is
off.

This packet is PassPonyCore repo work (`~/Apps/PassPonyCore`). The same
identity, attribution, and no-em-dash rules apply there.

## Context

- iOS uses a platform engine (AgePonyCore in Swift). Android instead
  runs age in Rust behind the FFI. The seam stays `CryptoBackend`; the
  age engine is just one more implementer, living Rust-side.
- The `age` crate (the rage project's library implementation) provides
  x25519 identities, recipients, and streaming encrypt/decrypt.
- Fixture stores live in `~/Apps/PassPonyCore/fixtures/` and the parity
  suites in `crates/pass-devtools/tests/`. Read `roundtrip.rs` and
  `parity.rs` first to reuse their corpus and helpers.
- Match iOS AgePonyEngine semantics exactly
  (`~/Apps/PassPony/Sources/Shared/AgePonyEngine.swift`):
  - identities file format: one AGE-SECRET-KEY-1 per line, blank lines
    and `#` comments ignored;
  - encrypt to explicit "age1..." recipients when the store resolves
    them, else to the identities' own public keys (the
    `age -e -i identities` fallback);
  - decrypt tries all loaded identities;
  - errors map to the existing `CryptoError` variants, never panics.

## Work

1. `crates/pass-ffi/Cargo.toml`: `age-engine = ["dep:age"]` feature,
   `age` as optional dependency (no default features beyond what
   encrypt/decrypt needs; no armor, passage files are binary).
2. `crates/pass-ffi/src/age_engine.rs`, gated `#[cfg(feature = "age-engine")]`:
   - `#[derive(uniffi::Object)] pub struct AgeEngine` holding parsed
     identities.
   - Constructors: `from_identities_text(text: String)` (parses,
     zeroizes the input copy, errors on zero usable identities only at
     use time, matching iOS which allows an empty engine but fails
     decrypt/encrypt with NoUsableKey).
   - `pub fn generate_identity() -> GeneratedIdentity` free function:
     returns record { identity_string, recipient_string } so Kotlin can
     write the first-run identities file with the same comment header
     iOS writes.
   - Methods `encrypt(plaintext, recipients) -> Result<Vec<u8>, CryptoError>`
     and `decrypt(ciphertext) -> Result<Vec<u8>, CryptoError>` with the
     exact fallback semantics above.
   - Plaintext buffers wrapped in `zeroize::Zeroizing` internally.
3. Kotlin-side note (for P05, do not implement here): Kotlin will wrap
   `AgeEngine` in the generated `CryptoBackend` interface by delegation.
   Keep AgeEngine's method signatures identical to CryptoBackend's so
   the wrapper is two lines.
4. Tests (`#[cfg(all(test, feature = "age-engine"))]`):
   - round-trip: generate identity, encrypt to its recipient, decrypt;
   - identities-file parsing: comments, blanks, junk lines skipped;
   - recipients-fallback: empty recipient list encrypts to own keys;
   - corpus: open the passage fixture store with AgeEngine and compare
     entry plaintexts against the values the parity suite asserts
     (reuse its fixture identity file).
   - wrong-key decrypt returns `DecryptionFailed`, not a panic.
5. CI (this repo's existing workflow): add a job step building and
   testing with `--features age-engine`, and a
   `cargo ndk`-less check that the feature compiles for
   `--target aarch64-linux-android` via `cargo check` (cross-compile
   check only; full Android builds happen in the app repo).
6. README: one paragraph documenting the feature and who uses it
   (Android only; iOS keeps platform engines).

## Exit criteria

- `cargo test -p pass-ffi --features age-engine` green.
- `cargo test` without the feature untouched and green (iOS surface
  provably unchanged).
- `cargo check -p pass-ffi --features age-engine --target aarch64-linux-android` green.

## Out of scope

- Passphrase-protected identities files (shared not-yet list).
- Any Kotlin, any app-repo changes (P05).
- SSH credentials callback (separate future core work).
