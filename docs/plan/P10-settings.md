# P10. Settings: formats, keys, re-encrypt, language, About

Objective: the settings sheet at iOS parity: store format switcher,
OpenPGP key management (import, locked-key unlock, .gpg-id init),
re-encrypt with preview, language switcher, tour replay, About and
More from NorseHorse, diagnostics.

Requires: P05, P06; P09 for openStore refresh wiring.

## Context (iOS file to mirror)

`Sources/App/SettingsView.swift`, plus `InitializeStoreView.swift` and
`ReencryptView.swift`. Section inventory to port:

1. Store format picker (passage / pass) with the footer "Each format
   keeps its own store. Switching never touches the other store's
   data." Switching repoints StorePaths and reopens the store.
2. pass-only: OpenPGP keys section: imported key file list with
   delete, "Import key file..." via the system document picker (accept
   any file, parse-or-reject with a calm message), empty-state footer
   text ported.
3. pass-only: Locked keys section (shown when PgpKeyStore reports
   locked files): file list, passphrase field, Unlock button; footer
   states the cache window (minutes from the shared grace constant).
   Unlock saves to PassphraseCache and reloads the engine.
4. pass-only: Store recipients (.gpg-id) section: shows current lines,
   or "Initialize store with key..." when absent (disabled until a key
   is imported), launching the InitializeStore flow: pick from
   available keys (fingerprint, algorithm, v4/v6 badge), write
   `.gpg-id`, commit.
5. Maintenance: "Re-encrypt store..." launching the ReencryptView port:
   subpath field (empty = whole store), preview via
   `reencryptTargets(subpath)` listing affected entries, confirm runs
   `reencryptSubtree` and commits with `commitMessageReencrypt`.
6. Language picker: System plus the seven languages, each name shown in
   its own language verbatim (English, Espanol with the correct
   character, Francais, Deutsch, simplified Chinese, Portugues
   (Brasil), Russian; take the exact strings from the iOS picker).
   Applies immediately via LanguageManager (P13 lands the translations;
   the picker ships here and works for English immediately).
7. Replay tour button (sets the onboarding flag; live once P14 lands).
8. About: version from BuildConfig (versionName), core version from
   `coreVersion()`, license note, source link.
9. More from NorseHorse: same app list and links as the iOS section.
10. Diagnostics: store path, entry count, format, ABI; nothing
    sensitive.

## Work

- `ui/settings/SettingsScreen.kt` + ViewModel, sections above.
- `ui/settings/InitializeStoreScreen.kt`, `ReencryptScreen.kt` ports.
- SAF import: copy the picked document into `pgp-keys/` with its
  display name; reject non-key files after a parse attempt with the
  ported error string.
- Tests: unit-test the key-list summarization (fingerprint formatting,
  v6 badge), .gpg-id write format (one spec per line, newline
  terminated), and re-encrypt preview plumbing with a fake store.

## Exit criteria

- Format switch flips between the two seeded stores live.
- Import an armored public+secret key, initialize a fresh pass store,
  add an entry, all on emulator.
- A passphrase-protected key shows in Locked keys, unlocks with the
  right passphrase, engine reloads, entry decrypts.
- Re-encrypt preview lists exactly the entries under the subpath and
  the run commits with the CLI-parity message.

## Out of scope

- Language translations themselves (P13). Onboarding (P14).
