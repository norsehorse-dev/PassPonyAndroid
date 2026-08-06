# P12. Autofill service

Objective: PassPony fills logins system-wide: suggestions inline above
the keyboard (API 30+) or in the dropdown, biometric confirm before any
decrypt when the grace window has lapsed, and a full picker fallback.
Exit test is the GitHub login filling on a device.

Requires: P07 (detail/decrypt paths), P11 (gate). Mirrors the iOS
credential-provider extension's behavior, minus every app-group
complication (same app, same storage).

## Context

- iOS reference: `Sources/Autofill/CredentialProviderViewController.swift`
  (no-UI fast path when fresh, escalate to auth when lapsed, picker
  fallback ranked by service hint) and
  `Sources/Shared/AutofillIdentities.swift` (names-only index; the
  domain heuristic: deepest path component containing a dot wins,
  `web/github.com` gives `github.com`, else the leaf name).
- Android surface: `android.service.autofill.AutofillService`,
  `BIND_AUTOFILL_SERVICE` permission, fill requests carry an
  AssistStructure; browsers set `webDomain`, apps identify by package.
- Threat model: nothing decrypts until the user picks a dataset and,
  if the window lapsed, authenticates. The FillResponse itself carries
  names only.

## Work

1. `autofill/PassPonyAutofillService.kt`:
   - Parse the AssistStructure: collect nodes with autofill hints or
     input types indicating username/password; bail with null response
     when none found.
   - Determine the request's domain: `webDomain` when present, else
     the requesting package name.
   - Match entries: serviceHint(entryName) equality with the webDomain
     (suffix-tolerant: hint equals domain or domain ends with "." +
     hint). Package requests match nothing in v1 (picker path).
   - Build up to N (5) datasets, each showing the entry name, each
     wrapped in an authentication PendingIntent (below): no plaintext
     in any dataset at fill-response time.
   - Always append a "Search in PassPony" picker dataset launching the
     picker activity.
   - Inline presentations (keyboard chips) on API 30+, RemoteViews
     dropdown otherwise.
2. `autofill/AutofillAuthActivity.kt`: translucent activity handling a
   dataset pick: if `UnlockGate.isFresh` skip straight to decrypt; else
   `authenticate()`; then decrypt the entry, extract password (first
   line) and username (`username` or `login` field, same keys as iOS),
   build the result Dataset with real AutofillValues, setResult, finish.
   Failures cancel cleanly, never leak an error containing plaintext.
3. `autofill/AutofillPickerActivity.kt`: Compose list of all visible
   entries ranked by the service hint (hits first, rest after; search
   field re-filters all), same ranking rule as the iOS picker. Pick
   goes through the same auth-then-decrypt path.
4. `serviceHint` lives in `store/ServiceHint.kt` with unit tests ported
   from the iOS rule (web/github.com, wifi/home, nested folders,
   dotless names).
5. Manifest: service declaration with the autofill intent filter and
   meta-data XML; Settings gains a row deep-linking to the system
   autofill-service chooser (`ACTION_REQUEST_SET_AUTOFILL_SERVICE`),
   full onboarding integration is P14.
6. Tests: unit for hint matching and structure parsing (fake
   structures); manual matrix on device/emulator: Chrome login form
   (webDomain path), an app login (picker path), lapsed window
   (biometric prompt appears), fresh window (no prompt).

## Exit criteria

- github.com login in Chrome on a physical device: inline chip
  appears, tap, biometric confirm (window lapsed), fields fill.
- With the window fresh, the same fill happens without a prompt.
- An app with no matching entry still offers the picker route.
- No entry plaintext in any dump of the FillResponse (verify with
  `adb shell dumpsys autofill` while a response is pending).

## Out of scope

- Credential Manager / passkeys (post-v1, mirrored not-yet).
- SaveInfo (offering to save typed passwords): post-v1, iOS QuickType
  has no equivalent either.
- Package-name-to-domain heuristics beyond the picker (open question).
