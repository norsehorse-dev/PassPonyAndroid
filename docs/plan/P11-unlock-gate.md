# P11. Unlock gate: biometric auth, grace window, panic lock

Objective: the app opens behind biometric/credential auth with the
5-minute grace window, shared by the autofill service (P12), with a
panic-lock action that clears every cached secret.

Requires: P03. Blocks P12 and P14.

## Context (iOS files to mirror)

- `Sources/Shared/UnlockGate.swift`: 300 s grace period, `isFresh`
  check on a stored last-unlock stamp, `markUnlocked`, `lock`, and
  `authenticate` presenting the system sheet. Devices with no
  protection configured at all treat auth as passed (dev convenience;
  document it the same way).
- `Sources/Shared/PassphraseCache.swift` (already ported in P06):
  expiry shares the same grace constant so "unlocked" means one thing.
- PGPonyAndroid prior art: `ui/keyring/BiometricGate.kt` and
  `ui/components/LockScreen.kt` (read for BiometricPrompt handling and
  the scroll-container lesson from 4.1.1).

## Work

1. `store/UnlockGate.kt`:
   - `GRACE_PERIOD = 5 * 60` seconds, single source of truth (used by
     PassphraseCache and the Settings copy).
   - stamp in app-private DataStore; `isFresh`, `markUnlocked`,
     `lock()` (also clears PassphraseCache).
   - `authenticate(activity, reason)`: androidx BiometricPrompt with
     `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` allowed authenticators; if
     `BiometricManager.canAuthenticate` reports no way to auth at all,
     mark unlocked and pass (the iOS fresh-simulator rule, same
     comment).
2. `ui/LockScreen.kt`: shown at app start when not fresh; app content
   composes only after unlock. Scrollable at large font scales (the
   PGPony 4.1.1 lesson). Reason string matches iOS: "Unlock your
   password store."
3. Panic lock: a lock action in the list screen toolbar overflow and in
   Settings; calls `UnlockGate.lock()` and returns to LockScreen.
   Background expiry: re-check `isFresh` on every ON_START; a lapsed
   window relocks.
4. FLAG_SECURE on the lock screen and passphrase prompts (reuse the P07
   secure-window mechanism).
5. Tests: grace-window math around the boundary; lock clears the
   passphrase cache; ON_START relock logic with a fake clock.

## Exit criteria

- Fresh launch requires auth; relaunch within 5 minutes does not;
  after 5 minutes it does. Panic lock relocks immediately and a
  subsequent pass-store decrypt requires the passphrase again (cache
  cleared).
- Enrolled-biometric emulator and PIN-only emulator both work;
  no-credential emulator passes through with the documented rule.

## Out of scope

- Autofill's use of the gate (P12). Configurable grace period
  (post-v1, same as iOS).
