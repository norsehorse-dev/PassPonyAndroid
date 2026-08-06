# P07. Entry detail, TOTP ring, ephemeral clipboard

Objective: tapping an entry shows the iOS detail screen: masked
password with reveal and copy, key/value fields, live TOTP ring.
Clipboard copies are sensitive-flagged and self-clearing.

Requires: P05 (or P06) for a real engine; P03 for navigation.

## Context (iOS files to mirror)

- `Sources/App/EntryDetailView.swift`: sections (Password, Fields,
  One-time code), reveal toggle, per-item copy buttons, plaintext
  dropped on disappear, overflow menu (Edit fields, Move / rename).
- `Sources/App/TOTPRingView.swift`: 1 s tick from already-decrypted
  bytes (no re-decryption), ring shows secondsRemaining/period, red
  under 5 s, code grouped "123 456", copy button.
- `Sources/App/Clipboard.swift`: 45 s expiry, local-only.
- FFI helpers: `entryPassword`, `entryFields`, `entryTotp(content,
  unixTime)`; the view layer owns the clock.

## Work

1. `ui/detail/EntryDetailScreen.kt` + ViewModel:
   - decrypt once on entry, hold plaintext in memory only; zero the
     reference when the screen leaves composition (structural parity
     with iOS `.onDisappear { content = nil }`).
   - Password section: monospaced, masked as bullets (min 8), reveal
     eye toggle, copy button.
   - Fields section: key left, value right, copy per row.
   - One-time code section shown only when `entryTotp` is non-null.
   - Overflow menu with Edit fields and Move / rename, wired to P08
     destinations (stub targets acceptable until P08 merges, but the
     menu ships here).
2. `ui/components/TotpRing.kt`: Compose port of the ring driven by a
   1 s `LaunchedEffect` clock calling `entryTotp` with the current unix
   time. Same visual rules (track at 25 percent opacity, accent color,
   red at 5 s or less, monospaced countdown number, grouped code).
3. `ui/util/Clipboard.kt`:
   - `copyEphemeral(data)`: ClipData with
     `ClipDescription.EXTRA_IS_SENSITIVE = true`, then a scheduled
     clear after 45 s (app-process scheduler; if the process dies first,
     the sensitive flag plus modern OS auto-expiry is the fallback).
     Clearing checks the clipboard still holds our clip before wiping.
   - One constant, 45, shared with the Settings copy text later.
4. FLAG_SECURE: apply to the activity window while the detail screen
   (or any reveal surface) is visible, per the threat model. Implement
   as a composable effect that increments/decrements a secure-window
   refcount so P10/P12 can reuse it.
5. Unit tests: code grouping (6 and 8 digit), mask length rule,
   clipboard clear-only-own-clip logic (with an abstraction over the
   ClipboardManager).

## Exit criteria

- Demo entry with TOTP shows a live ring that matches a reference
  authenticator for the same secret (JBSWY3DPEHPK3PXP fixtures).
- Copy password, wait 45 s, paste elsewhere: clipboard is empty.
- Screenshot attempt on the detail screen is blocked (emulator shows
  the secure-window behavior).
- Leaving the screen and returning re-decrypts (no cached plaintext).

## Out of scope

- Editing (P08), autofill (P12).
