# PassPony (Android)

[![CI](https://github.com/norsehorse-dev/PassPonyAndroid/actions/workflows/ci.yml/badge.svg)](https://github.com/norsehorse-dev/PassPonyAndroid/actions/workflows/ci.yml)

A password manager that speaks pass and passage: plain encrypted files,
your keys, git sync. Kotlin and Jetpack Compose over the shared Rust core
([PassPonyCore](https://github.com/norsehorse-dev/passponycore)), the
same core that backs [PassPony iOS](https://github.com/norsehorse-dev/passpony).

Built packet by packet against the plan in `PLAN.md` and `docs/plan/`; see
that directory for how each feature below landed.

Released on [F-Droid](https://f-droid.org/packages/com.passpony.android/)
and as a signed APK on
[GitHub releases](https://github.com/norsehorse-dev/PassPonyAndroid/releases/latest).
Both come from the same reproducible build; see `docs/REPRODUCIBLE.md` and
`docs/RELEASE_CHECKLIST.md` for the release process. Android 8.0 or later,
arm64-v8a and x86_64.

- Browse, search, and edit entries in pass (OpenPGP) and passage (age)
  stores. Each format keeps its own store; switching never touches the
  other store's data.
- One-time codes: scan a QR code, paste an otpauth link, or type the
  key; live codes with a countdown ring. Reads and writes the same
  `otpauth://` line as pass-otp, including entries made with
  `pass otp insert`. Counter-based (HOTP) codes advance and commit the
  counter the way `pass otp code` does.
- Import from Google Authenticator: scan its export QR codes and each
  account becomes its own one-time code entry.
- Password generator with length and character-class options; the
  untouched default is pass's own 25 from the full set.
- Import an existing age identity for passage stores, plaintext or
  passphrase-protected (the `age -p` format gopass's age backend writes,
  binary or armored); the passphrase is asked for on import and the file
  is decrypted on the device. Imported identities are added to the ones
  already here, never replacing them.
- Post-quantum passage stores (3.0): a new store uses age's hybrid
  ML-KEM-768 + X25519 keys (`age1pq1...`) by default, with classic keys
  as a choice while the store is empty. A store is one kind or the other,
  and both kinds work with PassPony for iOS and with age 1.3 or later on
  a desktop.
- Move an existing classic passage store to post-quantum keys from
  Settings (3.0). Every entry is saved again in one commit. Git history
  is not rewritten, so older copies stay protected by the classic key
  only.
- Folder stores (3.0, #14): keep a store in a folder you pick, so an app
  like Syncthing can sync the encrypted files without a git remote. The
  folder is opened in place; PassPony keeps no second copy. See "Syncing
  with a folder" below.
- Adding an entry under a name that is taken asks before replacing it
  (3.0).
- Keys backup: an encrypted `.passpony` backup of every key on the
  device, locked with a generated recovery code (a plain age file; see
  PassPonyCore's `docs/BACKUP.md`), a plain identities export, and
  restore. A banner says so until the generated age identity is backed up.
- Ephemeral, local-only clipboard that clears after 45 seconds.
- Git sync over HTTPS or SSH: clone, publish, push, and per-entry
  conflict resolution. The SSH key is made in the app or imported.
  Commit messages match the pass CLI format, so a store stays
  interoperable with desktop pass and passage users.
- Passkeys on Android 14 and later: save and use them in your browser.
  They live in your entries and sync like everything else.
- Phones sharing a passage store add their own key when they sync, and
  you approve each new one before entries are re-encrypted to it.
- System Autofill service. Only entry names are indexed; nothing decrypts
  until you fill.
- Biometric unlock with a 5-minute grace window shared with autofill.
- A Diagnostics screen under Settings with a copyable report that holds
  no entry names, secrets, fingerprints, or remote credentials.
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

The pass-format engine is in the same core (its `pgp-engine` feature,
rpgp), so there is no second submodule. Up to 2.1.0 it was
PGPonyCore-Kotlin over BouncyCastle.

## Layout

- `app/`: the Compose app (list/search, entry detail, editing, sync UI
  with the per-file conflict dialog, settings, onboarding) and the
  Autofill service.
- `core/`: generated UniFFI Kotlin bindings and jniLibs `.so` per ABI
  (gitignored, produced by `scripts/build-core.sh`), plus the thin Kotlin
  crypto engines that implement the FFI's `CryptoBackend` interface.
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

passage stores run on a Rust age engine (the `age-pq` feature of
PassPonyCore's pass-ffi, which adds post-quantum `age1pq1...` keys to the
classic engine), reached through the generated FFI bindings. pass
stores run on the core's Rust OpenPGP engine (rpgp, the `pgp-engine`
feature), the same code PassPony iOS and Desktop run: Cv25519 v4,
X25519/Ed25519 v6, RSA and NIST-curve keys, and the OCB packets current
GnuPG writes. ElGamal and brainpool keys work too; rpgp has no arithmetic
for those, so the core does that one step itself (see its
`THREAT_MODEL.md`). Not supported: secp256k1 and Ed448/Cv448 encryption
keys. Up to 2.1.0 this was PGPonyCore-Kotlin over BouncyCastle, which
opened secp256k1 keys and could not read GnuPG's OCB packets. The recipient-resolution
rule (fingerprint or 16-hex key ID, `.gpg-id` per entry) is the same rule
PassPony iOS uses. A passphrase-protected pass secret key is cached for 5
minutes after entry, sealed with an Android Keystore AES-GCM key
(`crypto/PassphraseCache.kt`), reading the same grace-period constant as
Android's own `store/UnlockGate.kt` -- one 5-minute window shared across
biometric unlock, autofill, and the pass passphrase cache, matching iOS's
UnlockGate parity.

The SSH key for git remotes (2.0) is sealed the same way under its own
Keystore key (`crypto/SshKeyStore.kt`) and unsealed per sync; the core
hands it to libssh2 from memory. SSH hosts are trusted on first use and
pinned in an app-private known_hosts file; a changed host key is refused.

Passkeys (2.0, Android 14 and later) are stored as one `passkey:` line
each inside the encrypted entry, so they sync and back up with the rest
of the store. The provider serves browsers on the bundled privileged list
(`res/raw/passkey_browsers.json`, Google's published list); a native
app's request is declined, since confirming an app's claim to a site would
mean contacting that site. Key generation and ES256 signing use the
platform's software EC provider (`passkey/PasskeyVault.kt`); the unlock
gate is the user verification.

Phones share a passage store through its root `.age-recipients` (2.0):
after a sync, clone or publish, PassPony adds this device's public key
there and commits, so everything saved from then on is readable by every
listed device. When another device's key shows up, PassPony asks before
re-encrypting the existing entries to it, or removing it
(`store/DeviceKeys.kt`). Secret keys never leave a device. pass stores'
`.gpg-id` is left alone.

A store's keys are post-quantum or classic, never both, because age will
not encrypt one file to both kinds (3.0). PassPony reads the kind from
`.age-recipients`, or from the entries when there is no such file. A
classic key that shows up in a post-quantum store came from a device on
an older version: nothing can be saved while it is listed, so PassPony
asks about it on every sync and offers only to remove it. Where two keys
have to be compared by eye, a post-quantum key (1,959 characters) is shown
with a fingerprint, the first 16 bytes of SHA-256 over the key as written,
which is the same on iOS and from `sha256sum`.

Moving a classic store (3.0, `ui/settings/MovePostQuantumScreen.kt`)
waits for two things on the device: a post-quantum key, and a keys backup
made after that key existed. It then writes `.age-recipients` with this
device's post-quantum key alone, saves every entry it can read again, and
commits both together. Other devices list their own post-quantum key at
their next sync and are approved like any new device. A folder with its
own `.age-recipients` that lists a classic key stops the move. If a move
is interrupted, or another device saves an entry before it updates,
Settings offers to finish, which saves the entries again. What post-quantum
keys do not cover: the git history from before the move, entry names, and
a pass (OpenPGP) store.

## Syncing with a folder

Settings, Storage, "Use a folder…" points the store at a folder you pick
with the system picker. PassPony gets access to that one folder through
the Storage Access Framework and to nothing else; it asks for no storage
permission. An existing store in PassPony's own storage can be moved in
(every file is copied and read back before the original is removed) and a
folder store can be moved back out.

With Syncthing: share one folder between your devices, and point PassPony
at it on each phone. A desktop uses the same folder as its
`PASSWORD_STORE_DIR` or `PASSAGE_DIR`. Each phone lists its own key in
`.age-recipients` when it opens the store, and you approve a new device's
key on a device that can already read the entries, the same way as with
git sync.

- **No git.** A folder store never commits, pulls or pushes, and the Sync
  screen says so. libgit2 cannot work on a document tree, and a sync tool
  carrying a `.git` directory between devices is how repositories get
  corrupted. A folder that already contains `.git` still opens; PassPony
  ignores it. Moving a store into a folder leaves its git history behind.
- **Changes show up when you come back to the app,** or from "Reload from
  folder" in the list's menu. Nothing watches the folder.
- **Conflict copies.** When two devices change an entry before they have
  synced, Syncthing keeps both. PassPony labels the copy
  (`name.sync-conflict-<date>-<time>-<device>`) and offers to keep it as
  the entry or delete it.
- **Temp files.** A save writes `.<name>.passpony-tmp`, renames it to
  `.<name>.passpony-tmp-ready` when complete, then replaces the entry. They
  exist for a moment; add `.*.passpony-tmp*` to `.stignore` if you would
  rather they never sync.
- **A folder that goes away** (deleted, moved, access revoked) gets its own
  screen with "Pick the folder again". PassPony never shows an empty store
  in its place.

Anything that can write to the folder can add, change or delete entries,
and can add a key to `.age-recipients`. PassPony asks before re-encrypting
anything to a key it has not been asked about, which is what stops a key
slipped into the file from being handed your entries.

## Keys kept in another app

A pass store's OpenPGP keys can stay in a key app, PGPony or OpenKeychain,
through the OpenPGP provider API both implement. Settings, "Where the keys
are kept", "Use keys from another app…". PassPony then holds no key
material for that store: the key app decrypts and encrypts, shows its own
passphrase screen when it needs one, and decides how long a
passphrase is remembered. PassPony's own unlock still comes first.

- The key app is pinned to the signing certificate it had when you chose
  it. The same package name signed by someone else is refused.
- The key app asks you once to allow PassPony.
- A new store's `.gpg-id` is written from a key you pick in the key app,
  as its full fingerprint.
- Saving encrypts through the key app too, to the keys in `.gpg-id`. If
  the key app does not hold one of them, the save fails with its message.
- After switching, PassPony offers to delete the key files it had
  imported, once the key app has decrypted an entry. It never deletes them
  by itself.
- Re-encrypting a store goes through the key app one entry at a time. If
  the key app stops to ask for something, the run stops there; tap
  "Re-encrypt now" again and it carries on. Entries already done stay
  readable.
- A backup made in this mode does not contain the OpenPGP keys. Back them
  up in the key app.
- One store uses PassPony's keys or one key app, never a mix. passage
  stores are not affected.

The client code is the openpgp-api library from the OpenKeychain project,
vendored as source in `third_party/openpgp-api`.

## Not yet

- Smartcard decrypt (NFC and USB-C YubiKey).
- Passkeys for native apps (browsers only in 2.0).
- Git on a folder store, and watching a folder for changes as they happen.
- Conflict copies from sync tools other than Syncthing.
- Post-quantum keys for pass (OpenPGP) stores.
- A device key held in hardware (PassPony for iOS has one in the Secure
  Enclave).
- armeabi-v7a (32-bit) builds.

## License

Apache-2.0. See LICENSE.
