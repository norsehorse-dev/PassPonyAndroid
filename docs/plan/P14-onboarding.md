# P14. Onboarding: the seven-slide tour

Objective: the first-run carousel at iOS parity, ending with autofill
enabled without leaving the app. Exit test: fresh install to working
autofill entirely inside the tour, in Spanish.

Requires: P10 (format switch, key import), P11 (biometric), P12
(autofill), P13 (translations).

## Context (iOS files to mirror)

`Sources/App/Onboarding/`: OnboardingView (swipeable pager, Skip top
right, page dots, Next/Done), OnboardingSlide (slide data: icon, tint,
title, body, action), OnboardingPage (per-slide action area), and
OnboardingCloneSheet. The seven slides and their interactive actions:

1. Language: inline language picker, applies live to the remaining
   slides (re-resolve on pick; iOS re-keys the subtree).
2. Welcome: no action.
3. Format: passage/pass picker writing the real StorePaths format.
4. Import or clone: buttons for import keys (pass), import identities
   file (passage), or clone an existing store (the clone sheet with
   URL field, scratch-dir semantics from P09 reused).
5. First pass: an inline mini add-entry form writing to the real store
   (the try-pass slide acts on the REAL store, same as iOS).
6. Biometric: explains the gate; icon reflects available biometric
   type; enabling runs one authenticate() so the permission moment
   happens inside the tour.
7. Autofill: fires `ACTION_REQUEST_SET_AUTOFILL_SERVICE` for this
   service; slide detects on resume whether we are now the autofill
   service and shows the success state.

Completion sets the onboarding-completed flag (the P03 demo-seed guard
reads it), then swaps to the main UI. Replay from Settings resets the
flag only, never the store.

## Work

1. `ui/onboarding/`: OnboardingScreen (HorizontalPager, dots, Skip,
   Next/Done), OnboardingSlide data class, per-slide action
   composables reusing existing screens' logic (clone sheet reuses the
   P09 clone flow; import reuses P10 SAF import; mini add-entry reuses
   the P08 form logic, trimmed).
2. Scrollable slide content at large font scales (PGPony 4.1.1
   lesson: every slide in a scroll container, zero visual change when
   content fits).
3. Language slide wiring through LanguageManager with live
   recomposition of remaining slides.
4. RootView equivalent: MainActivity composes Onboarding when the flag
   is unset, else LockScreen/main UI.
5. Manual test script (documented in the packet PR description):
   fresh install, Spanish from slide one, passage format, clone
   declined, first pass created, biometric enabled, autofill enabled,
   land in main UI, GitHub fill works, all without leaving the app.

## Exit criteria

- The manual walk above passes on emulator and once on a device.
- Skip at any slide lands in a working app (sane defaults: passage,
  no keys, gate on).
- Replay tour from Settings works and does not reseed or wipe
  anything.

## Out of scope

- Any new onboarding content beyond iOS parity.
