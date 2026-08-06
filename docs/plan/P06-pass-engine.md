# P06. pass (OpenPGP) engine on Android

Objective: pass stores open with real OpenPGP crypto through
PGPonyCore-Kotlin over BouncyCastle, including RSA-keyed stores that
iOS cannot open yet. Fixture stores round-trip on the emulator.

Requires: P02. Independent of P04/P05.

## Context

- iOS reference: `Sources/Shared/PGPonyEngine.swift` (recipient
  resolution, locked-key handling, armored/binary tolerance) and
  `PassphraseCache.swift` (time-boxed cache).
- PGPonyCore-Kotlin: github.com/norsehorse-dev/PGPonyCore-Kotlin,
  a pure Kotlin/JVM Gradle project (module `:pgponycore`), packages
  `crypto/`, `crypto/card/`, `crypto/util/`. Its decrypt/encrypt API
  takes BouncyCastle `PGPSecretKeyRing` / `PGPPublicKeyRing` objects
  directly (see PGPCryptoService in the sibling app repo
  `~/Apps/PGPonyAndroid` for the API shape in production use).
- BC handles RSA natively, so "RSA decrypt" costs a test, not an
  implementation. Card/smartcard paths in the core are NOT part of
  PassPony v1.

## Work

1. Submodule: `third_party/pgponycore-kotlin` pinned to the latest
   tagged release of PGPonyCore-Kotlin; wire with `includeBuild` (or
   `include` + project mapping if its build layout demands) so `:app`
   depends on it as a normal project dependency. Add bcprov/bcpg
   (jdk18on, version matching what the submodule expects) to :app.
   Record the pinned tag in the README's build section.
2. `crypto/PgpKeyStore.kt` (port of the iOS PGPKeyStore):
   - key files live in `filesDir/pgp-keys/`; import copies files in
     (SAF document picker arrives in P10; this packet exposes a
     programmatic import for tests).
   - Parse each file: dearmor when armored, extract public key infos
     (fingerprints, key IDs, algorithm, v4/v6) via PGPonyCore-Kotlin /
     BC ring parsing. Secret-only exports derive their public material
     the same way the iOS core does (BC rings carry both; verify with a
     secret-only fixture).
   - Locked keys (passphrase-protected, no cached passphrase or a wrong
     one) are recorded by filename and skipped, never fatal; unprotected
     keys keep working. Expose `lockedKeyFiles` for Settings (P10).
3. `crypto/PgpEngine.kt` implementing `CryptoBackend`:
   - decrypt: try every usable secret key; tolerate armored and binary
     ciphertext; map "no matching key" to `CryptoException.NoUsableKey`
     and everything else to `DecryptionFailed`.
   - encrypt: resolve every `.gpg-id` spec against imported keys by
     uppercase-hex fingerprint (primary or subkey), 16-hex key ID, `0x`
     prefix and inner spaces tolerated, fingerprint-suffix match at 16+
     chars (port the iOS `resolve` exactly); throw
     `Unavailable(reason)` naming the unmatched spec with the same
     wording as iOS; encrypt binary (not armored), to all resolved
     recipients.
   - RSA: no special-casing needed on decrypt; on encrypt, RSA
     recipients are allowed (BC supports them), which is a deliberate
     capability difference from iOS. Note it in README's honesty list.
4. `crypto/PassphraseCache.kt`: passphrase in Android Keystore-backed
   encrypted storage (EncryptedSharedPreferences or a Keystore-wrapped
   blob in filesDir), expiry stamp beside it in DataStore, window
   shared with the P11 UnlockGate constant (5 minutes). Clear on
   expiry read, on panic lock, and on process-death-independent expiry
   (check stamp on every load).
5. EngineProvider: PASS now routes to PgpEngine.
6. Fixture tests (instrumented):
   - Cv25519 v4 fixture store and X25519/Ed25519 v6 fixture store from
     the PassPonyCore corpus: open, decrypt entries, write one entry,
     re-open, decrypt it back byte-identical.
   - An RSA-keyed store fixture: generate once with GnuPG on the host
     (script it under `scripts/make-rsa-fixture.sh`, committed fixture,
     fake credentials), assert decrypt works on Android.
   - Locked-key behavior: protected key without passphrase loads as
     locked, store decrypt with only that key fails NoUsableKey, after
     `PassphraseCache.save()` the engine reloads and decrypts.

## Exit criteria

- All fixture tests green on emulator.
- Switching format to pass in a debug build (via a temporary developer
  toggle if P10 is not done) opens the fixture store end to end.
- No BC or PGPonyCore-Kotlin types leak above the `crypto/` package.

## Out of scope

- Key import UI, .gpg-id initialization UI, locked-key prompt UI (P10).
- Smartcard/YubiKey paths (post-v1, shared not-yet list).
- Detached signing, WKD, keyservers (PGPony features, not pass-store
  features).
