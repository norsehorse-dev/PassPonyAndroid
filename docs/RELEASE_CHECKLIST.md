# Release checklist

The ordered steps for shipping a PassPony (Android) release, from version
bump through post-release verification. Per P16
(`docs/plan/P16-release.md` item 5).

CI green is necessary but not sufficient. CI runs on an emulator, which
covers software crypto, UI, and the fixture round-trips, but it cannot
exercise NFC-class or real-biometric-class device-only behavior. This is
the PGPony lesson: a green build shipped once with a broken on-device
autofill path that no emulator run could have caught, because the
emulator doesn't have a real fingerprint sensor or a real Autofill host
app to fill into the way a physical device does. No publish on a green
build alone -- the manual device matrix below is mandatory every release,
not just the first one.

## 1. Version bump

- Bump `versionCode` and `versionName` in `app/build.gradle.kts`. Commit
  this by itself, before anything else in this checklist.
- Confirm `versionName` matches the tag you're about to cut (e.g.
  `versionName = "1.1.0"` for tag `v1.1.0`) -- `scripts/release.sh`
  refuses to run otherwise.

## 2. CI green on `main`

- Push the version bump, confirm `.github/workflows/ci.yml`'s `build` job
  (including the emulator instrumented tests) is green on the resulting
  commit.

## 3. Build the dry-run release

- On the machine holding the real release keystore, inside the release
  container (see `docker/README.md`) -- never directly on macOS, and
  never CI. A Mac-built release and a Linux-built release of the same
  commit are not byte-identical (the NDK's darwin-x86_64 and
  linux-x86_64 clang prebuilts for the same NDK version pass different
  default flags into pass-core's vendored OpenSSL build), and F-Droid's
  own buildserver always builds on Linux, so a Mac-built release could
  never pass F-Droid's Reproducible Builds verification. See
  `docs/REPRODUCIBLE.md`'s verification log for how this was found.

  ```
  docker run --rm \
    -v ~/Keys/PassPony/release.keystore:/keystore/release.keystore:ro \
    -v ~/Apps/PassPonyAndroid/keystore.properties:/keystore-props/keystore.properties:ro \
    -v ~/Apps/PassPonyAndroid:/out \
    passpony-release <version> <commit-sha-just-pushed>
  ```

- This builds both flavors, signs via the real Gradle release build (see
  `scripts/release.sh`'s own note on why not standalone `apksigner`),
  writes checksums and a content hash, and drafts release notes from
  `RELEASE_NOTES.template.md`. It stops before tagging, pushing, or
  publishing anything.
- Fill in the drafted `RELEASE_NOTES-<version>.md`: honest changelog, no
  marketing fluff.

## 4. Manual device matrix

Every item below needs a real device, not the emulator CI already
covered. Do this for every release, not just the first.

- **Autofill on a physical device.** Install the dry-run foss APK,
  enable PassPony as the system Autofill service, and fill a real login
  form in an actual browser or app -- not just the in-app demo store.
  Confirm the picker fallback ("Search in PassPony") also works when no
  domain match exists.
- **Biometric unlock on a device with real biometrics enrolled.**
  Fingerprint or face unlock, not the emulator's simulated fingerprint.
  Confirm the 5-minute grace window (shared between unlock and autofill,
  see `crypto/PassphraseCache.kt` and `store/UnlockGate.kt`) behaves the
  same on-device as it does in tests, including after the app is
  backgrounded and the grace window lapses.
- **One pass (OpenPGP) store, end to end, on-device.** Import a real key,
  clone or initialize a store, decrypt an existing entry, add a new one,
  sync it, and confirm a wrong/locked key is handled without crashing.
- **One passage (age) store, end to end, on-device.** Same shape as
  above, with an age identity instead of an OpenPGP key.

Any failure here blocks the release. Fix it, rerun this whole checklist
from step 2, not just the failing item.

## 5. Tag, push, publish

Only after the device matrix passes:

```
git tag -a v<version> -m "PassPony (Android) <version>"
git push origin v<version>
gh release create v<version> release-<version>/*.apk release-<version>/*.aab release-<version>/*SHA256SUMS.txt --title "<version>" --notes-file release-<version>/RELEASE_NOTES-<version>.md
```

Pushing the tag also triggers `.github/workflows/reproducible.yml` (the
tag-triggered determinism gate) and, once GitHub's release asset is live,
`workflow_dispatch`-triggerable comparison against it -- confirm that run
is green too before considering the release final.

## 6. Post-release verification

- Download the published release APK fresh (not the local build output)
  and confirm its SHA-256 matches `PassPonyAndroid-<version>-SHA256SUMS.txt`.
- Log the release in `docs/REPRODUCIBLE.md`'s cross-machine verification
  result log: the CI legs row (JDK 17 / 21, from
  `.github/workflows/reproducible.yml`) and, once submitted, the F-Droid
  outcome row.
- If this is a Play release: confirm the store listing shows the new
  version and the AAB passed Play's own pre-launch report without new
  crashes.
- If this is an F-Droid submission: track the build against
  `docs/fdroid/com.passpony.android.yml`'s draft recipe once fdroiddata's
  own build completes, and update that file (or note the diff) if
  anything about the recipe needed to change from the draft.
