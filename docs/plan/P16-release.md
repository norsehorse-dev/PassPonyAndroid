# P16. Release engineering: storefronts and the release flow

Objective: Play listing drafted from the iOS ASO bundle, F-Droid
metadata ready for submission, signed APKs attached to a GitHub
release, and a written release checklist.

Requires: everything else merged; P15 green.

## Work

1. Versioning and signing:
   - confirm versionCode 100, versionName "1.0.0";
   - release keystore lives OUTSIDE the repo (`~/Apps/keystores/`,
     house convention), loaded via gitignored keystore.properties,
     exactly the PGPonyAndroid pattern;
   - `assemblePlayRelease` produces the Play artifact (AAB via
     `bundlePlayRelease`), `assembleFossRelease` the direct-install and
     F-Droid-reference APK.
2. fastlane/metadata structure (works for both Play console import and
   F-Droid localized metadata): short and full descriptions in all
   seven languages, converted from the iOS ASO bundle (Kevin supplies
   the bundle; en first, translations from the same corpus). Title
   "PassPony: Git Password Manager" (verify the 30-char limit against
   the live console).
   Screenshots: emulator captures of list, detail with TOTP, sync
   conflict dialog, settings, onboarding, using the demo store,
   light and dark, at Play's required sizes.
3. F-Droid metadata (com.passpony.android.yml draft in-repo under
   `docs/fdroid/` until submission): build recipe compiling
   PassPonyCore from source at the pinned ref (srclib or submodule
   checkout per current fdroiddata conventions), rustup + cargo-ndk
   prebuild steps, `subdir: app`, `gradle: foss`, the reproducible
   flags from P15, AntiFeatures none, license Apache-2.0.
4. GitHub release flow, scripted in `scripts/release.sh`: tag
   `v1.0.0`, build both flavors from the tag, apksigner-sign the foss
   APK, generate sha256sums, draft release notes from a RELEASE_NOTES
   template (honest changelog, no marketing fluff), attach APK +
   checksums. The script stops before anything irreversible (tag push,
   release publish) and prints the exact commands for Kevin to run.
5. `docs/RELEASE_CHECKLIST.md`: the ordered list from version bump to
   post-release verification, including the manual device matrix
   (autofill on a physical device, biometric on a device with real
   biometrics, one pass store and one passage store end to end) and
   the reminder that CI green alone does not validate NFC-class
   device-only behaviors (the PGPony lesson: no publish on a green
   build alone).
6. README final pass: features, first build, Not yet list kept honest
   in both directions against iOS (RSA works here, smartcards nowhere
   yet, SSH remotes nowhere yet).

## Exit criteria

- A dry-run release from a tag on Kevin's machine produces installable
  signed artifacts and a complete draft release.
- Play console draft listing passes validation with the drafted
  metadata and screenshots.
- The F-Droid recipe builds in a clean container following its own
  instructions.

## Out of scope

- Actually publishing (Kevin's call, always).
- Post-1.0 roadmap (armeabi-v7a, Credential Manager, SSH, smartcards).
