# P03. Store browse: paths, model, list, search, demo seed

Objective: the list screen renders real decrypted-index entries from a
seeded demo store in the emulator, with folder browsing and search
matching iOS behavior.

## Context (iOS files to mirror)

- `Sources/Shared/StorePaths.swift`: one place for every path decision.
- `Sources/App/AppModel.swift`: open/refresh/read/save/move/delete plus
  the demo seed list (port the exact demo entries).
- `Sources/App/StoreListView.swift`: unpushed-changes banner, folder
  sections, search across all entries, toolbar (sync, settings, add).
- The FFI index call is `PassStore.entries()`, byte-sorted `EntryRef`
  (name, hidden); hidden entries are filtered from every UI surface.

## Work

1. `StorePaths.kt` (`com.passpony.android.store`):
   - Root container is `context.filesDir` (no app group on Android).
   - `storeRoot(format)`: passage keeps directory name `store`, pass
     uses `pass-store`, same names as iOS for mental parity.
   - Active format in DataStore, default PASSAGE. Expose a
     synchronous-read snapshot for startup (runBlocking on first read is
     acceptable here; it is a one-key preference).
2. `DevCryptoEngine.kt`: debug-only byte-flip `CryptoBackend`, used only
   until P05 lands and afterwards only when no real engine can load, and
   only in debug builds. Release builds must throw instead (mirror the
   iOS "release builds trap" rule).
3. `AppViewModel` (`ui/`): openStore, refresh, visibleEntries (hidden
   filter + case-insensitive substring search), lastError. Same
   structure as AppModel.swift, with StateFlow instead of @Published.
4. Demo seed: debug-only, passage-only, seeded once into an empty store
   after onboarding completes (port the guard exactly; until onboarding
   exists in P14, gate on a DataStore flag defaulting true in debug).
   Port the 18 demo entries verbatim from AppModel.swift.
5. `StoreListScreen`: folder-level browsing (folders first, then
   entries at that level), search field searching all entries flat,
   unpushed-changes banner when `syncStatus.ahead > 0` (stub the status
   as null until P09), navigation to a placeholder detail screen.
   Top bar: sync icon, settings icon, add icon (placeholders allowed).
6. Error surface: `ErrorMessages.kt` port from
   `Sources/Shared/ErrorMessages.swift`: every StoreException /
   GitException / CryptoException variant maps to a calm, localizable
   sentence. UI shows these strings only.

## Exit criteria

- Fresh install on emulator: demo store seeds, list shows folders
  (web/, mail/, finance/, work/, wifi/, dev/) and entries, search for
  "git" finds github and gitlab entries, hidden filter works.
- Kill and relaunch: same store reopens (persistence on disk, not in
  memory).
- Unit tests: search filtering, folder grouping, serviceHint-style name
  handling deferred to P12 (not here).

## Out of scope

- Real crypto engines (P05/P06), entry detail (P07), editing (P08),
  sync status wiring (P09), settings UI (P10).
