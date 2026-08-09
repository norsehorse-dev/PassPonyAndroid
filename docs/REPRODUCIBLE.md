# Reproducible builds

PassPonyAndroid targets a byte-identical build: given the same source commit
and the same pinned toolchain versions, two independent machines produce the
exact same APK contents. This matters most for F-Droid, whose build
infrastructure re-builds every release from source and compares the result
against what's published -- a build that isn't reproducible can't be
verified that way.

This document is PassPony's adaptation of NorseHorse's cross-app
**Reproducible Builds Playbook** (`Reproducible_Builds_Playbook.md`, kept
alongside the release notes for each app -- ask Kevin for the current copy
if you need the full incident history and diagnosis toolbox). Everything
below is the PassPony-specific subset: what's pinned here, and how to run
the gate for this repo. The playbook itself is the source of truth for *why*
each rule exists and for triage when something fails in a way this file
doesn't cover.

## What's pinned, and where

| Input | Pinned value | Source of truth |
| --- | --- | --- |
| Android NDK | 27.2.12479018 | `gradle.properties` (`ndkVersion`) -- read by both `app/build.gradle.kts` (`android.ndkVersion`) and `scripts/build-core.sh` (`sed`), so the two toolchains never drift apart |
| Android Gradle Plugin | 8.13.2 | `build.gradle.kts` (top-level `plugins {}`) |
| Kotlin | 2.1.0 | `build.gradle.kts` (top-level `plugins {}`) |
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| Android build tools | 35.0.0 | `app/build.gradle.kts` (`android.buildToolsVersion`) -- used only for AGP's own build, never to re-sign an APK after the fact (see "Signing" below) |
| JDK | 17 | `app/build.gradle.kts` / `core/build.gradle.kts` (`compileOptions`, `kotlinOptions.jvmTarget`); CI's main build job installs Temurin 17, the reproducible-build workflow additionally checks JDK 21 (see "CI" below) |
| Rust toolchain | whatever `rust-toolchain.toml` in PassPonyCore pins (currently `1.95.0`) | PassPonyCore's own `rust-toolchain.toml`, picked up automatically by `rustup` when `cargo` runs from inside a PassPonyCore checkout |
| PassPonyCore commit | `be1aba698d8815f4749c4889ac304229d5c17d9a` | `.github/workflows/ci.yml` / `.github/workflows/reproducible.yml` (`env.PASSPONY_CORE_SHA`) and `tools/verify_repro.sh` (same env var, passed in) |
| Every Gradle dependency | no `+`, no `latest.release`/`latest.integration` | enforced by CI's "Reject dynamic dependency versions" step, which greps every `.kts` file |

`be1aba69...` is the current PassPonyCore HEAD rather than the `v1.0.0` tag,
because `v1.0.0` predates the `age-engine` feature this build depends on
(confirmed via `git merge-base --is-ancestor v1.0.0 HEAD`, which fails). Bump
this SHA deliberately when PassPonyCore's `age-engine` code changes, and bump
the NDK/AGP/Kotlin/Gradle versions deliberately alongside a real toolchain
upgrade -- never let any of these float to "whatever's newest installed."

## What else makes the build deterministic

- **Rust panic-location strings.** `file!()`/`line!()` macro expansions bake
  the absolute source path into the compiled binary as plain string data
  (not debug info, so `strip = "symbols"` doesn't remove it). Two machines
  checking out PassPonyCore to different paths would otherwise never produce
  byte-identical `.so` files. `scripts/build-core.sh` always sets
  `RUSTFLAGS="--remap-path-prefix=$CORE=/passpony-core --remap-path-prefix=$HOME=/home"`
  to normalize this, unconditionally rather than as an opt-in flag.
- **AGP's VCS/dependency metadata** (playbook 4.2). `app/build.gradle.kts`
  sets `vcsInfo.include = false` and `dependenciesInfo { includeInApk =
  false; includeInBundle = false }`, so the APK doesn't embed the exact git
  commit or a Play-specific dependency manifest, both of which would
  otherwise vary the output without changing the actual app. AGP already
  normalizes ZIP entry timestamps (to 1981-01-01); nothing here needs to
  touch that.
- **Signing degrades to debug, never re-signs after the fact** (playbook
  4.3, R3). `app/build.gradle.kts` falls back to the debug signing config
  whenever `keystore.properties` (gitignored) is absent, which is always
  true for a clean clone -- including F-Droid's buildserver. The release
  procedure below signs by running Gradle *inside* a clean clone with
  `keystore.properties` copied in, never by re-signing an already-built APK
  with the standalone `apksigner` CLI: build-tools 35+'s `apksigner`
  rewrites ZIP alignment (zero-padding becomes `0xd935` extra fields),
  which silently breaks byte-identity with any Gradle-signed build even
  though every file inside still matches. Gradle's own signing step doesn't
  have this problem -- it reuses the same zipflinger layout the unsigned
  build already has.
- **No dynamic dependency versions.** See the table above.

## Three different "the same APK" (playbook section 3)

Worth being precise about, since conflating these has caused real release
mistakes on other NorseHorse apps:

1. **Whole-file SHA-256** (`shasum -a 256 file.apk`). Changes whenever the
   signature changes, so it only proves "is this download the file that was
   published." Publish it for downloaders.
2. **Byte-identical modulo signature**: strip the signing block from both
   files and compare what's left. This is the equivalence F-Droid's
   verification actually requires. Signing via Gradle in a clean clone
   (never re-signing with `apksigner`) guarantees this by construction --
   `tools/verify_repro.sh` doesn't need to prove it separately.
3. **Content-identical**: every file inside the ZIP has identical bytes,
   ignoring ZIP metadata and signature files. This is what
   `tools/verify_repro.sh compare` proves, and what the **content hash**
   (`tools/verify_repro.sh content-hash`) summarizes as a single number:
   the SHA-256 of the sorted per-entry SHA-256 manifest, signature files
   excluded. It's filesystem-independent and identical for signed and
   debug-signed variants of the same build -- publish *this* one in release
   notes for anyone rebuilding from source, not a "hash of the unsigned
   APK" (a clean clone is debug-signed by the fallback config, so its
   whole-file hash is machine-specific and nobody else can ever match it).

Content-identical plus Gradle-produced layout equals byte-identical modulo
signature in practice, because Gradle's zipflinger writes entries
deterministically.

## Running the check yourself: `tools/verify_repro.sh`

```
tools/verify_repro.sh rebuild <tag-or-branch> [candidate.apk]
tools/verify_repro.sh compare <a.apk> <b.apk>
tools/verify_repro.sh content-hash <apk>
```

- **`rebuild`**: clones the given ref twice into isolated work directories
  (separate `GRADLE_USER_HOME`s, `./gradlew --no-daemon`), builds each with
  `$GRADLE_TASK` (default `:app:assembleFossRelease`), fails unless the two
  builds are content-identical, then fails unless the optional candidate
  APK also matches. Prints per-dex SHA-256s and any embedded R8 marker, and
  the work directory path (so you can grab `srcA`'s APK for the next
  step). Requires network (clones this repo and, if `PASSPONY_CORE_SHA` is
  set, PassPonyCore too) and an Android SDK/NDK matching `gradle.properties`'
  `ndkVersion`.
- **`compare`**: content comparison of two APKs via a per-entry SHA-256
  manifest, excluding only `META-INF/*.SF|*.RSA|*.DSA|*.EC|MANIFEST.MF`.
  Reads both ZIPs directly through Python's `zipfile` module and never
  extracts entries to disk -- AGP's shortened resource names collide
  case-insensitively (e.g. `res/IN.xml` vs `res/In.xml`), and extracting on
  a case-insensitive filesystem (macOS included) would silently drop one of
  each pair and corrupt the comparison without any error. Prints
  `IDENTICAL` or a per-file diff and exits nonzero on any real difference.
- **`content-hash`**: the publishable number from section above.

Example, matching the "run it bare" step of the release procedure:

```
PASSPONY_CORE_SHA=be1aba698d8815f4749c4889ac304229d5c17d9a tools/verify_repro.sh rebuild main
```

Prerequisites are the same as `scripts/build-core.sh`'s: `rustup`,
`cargo-ndk`, `perl`, `make`, and the pinned NDK installed via Android
Studio's SDK manager or `sdkmanager`, plus `python3` (standard library
only, no extra packages) for the ZIP comparison.

## CI

Two workflows share `tools/verify_repro.sh`:

- **`.github/workflows/ci.yml`**'s `verify-reproducible` job runs weekly
  (Mondays, plus `workflow_dispatch` for an on-demand run) and calls
  `tools/verify_repro.sh rebuild main` -- a drift check on the default
  branch, not tied to any release.
- **`.github/workflows/reproducible.yml`** runs on every `v*` tag push (the
  actual release gate) with a JDK 17 / JDK 21 matrix on `ubuntu-24.04`,
  `fail-fast: false`. Each leg runs the same `rebuild` gate, uploads the
  unsigned build as an artifact, and -- once a GitHub release asset exists
  for that tag -- downloads it and runs `compare` against a fresh clean
  build. Both matrix legs green means the build is deterministic under
  either JDK; one leg failing the asset comparison while the other passes
  would mean the toolchain output depends on the JDK, and the canonical JDK
  becomes whichever matches F-Droid's own buildserver (pin it via a Gradle
  Java toolchain block if that ever happens).

## Standard release procedure

Adapted from the playbook's section 6 for PassPony's naming. Assumes the
version is bumped and committed, and release notes are drafted. `<tag>` is
like `v1.1.0`.

1. Tag and push (starts the CI gate automatically):

   ```
   git tag <tag>
   git push origin main --tags
   ```

2. Run the local gate with no candidate yet -- this proves determinism and
   produces the canonical clean build:

   ```
   PASSPONY_CORE_SHA=be1aba698d8815f4749c4889ac304229d5c17d9a tools/verify_repro.sh rebuild <tag>
   ```

   Note the printed work directory (`<work>`) and content hash.

3. Produce the signed release APK inside the verified clone -- signing by
   rebuilding with Gradle, never by re-signing afterward (see "Signing"
   above):

   ```
   cp keystore.properties <work>/srcA/
   cd <work>/srcA
   env GRADLE_USER_HOME=<work>/gradleA ./gradlew --no-daemon :app:assembleFossRelease
   cp app/build/outputs/apk/foss/release/app-foss-release.apk /tmp/PassPonyAndroid-X.Y.Z-foss.apk
   ```

4. Gate the exact file that will be uploaded:

   ```
   tools/verify_repro.sh compare /tmp/PassPonyAndroid-X.Y.Z-foss.apk <work>/srcA/app/build/outputs/apk/foss/release/app-foss-release.apk
   ```

   Must print `IDENTICAL`. The content hash from step 2 must still match
   (`tools/verify_repro.sh content-hash /tmp/PassPonyAndroid-X.Y.Z-foss.apk`).

5. Confirm `.github/workflows/reproducible.yml` is green for the tag (both
   JDK legs; the asset comparison leg is skipped until the release asset
   exists).

6. Sign and publish:

   ```
   gpg --detach-sign --armor --output /tmp/PassPonyAndroid-X.Y.Z-foss.apk.asc /tmp/PassPonyAndroid-X.Y.Z-foss.apk
   shasum -a 256 /tmp/PassPonyAndroid-X.Y.Z-foss.apk
   gh release create <tag> /tmp/PassPonyAndroid-X.Y.Z-foss.apk /tmp/PassPonyAndroid-X.Y.Z-foss.apk.asc --title "PassPonyAndroid X.Y.Z" --notes-file RELEASE_NOTES_X.Y.Z.md
   ```

   Release notes should carry both hashes with their meanings: the
   whole-file SHA-256 for downloaders, the content hash for rebuilders.

7. Re-run `.github/workflows/reproducible.yml` on the tag (`workflow_dispatch`)
   so the release-asset comparison runs end to end against the published
   file.

8. fdroiddata (once PassPony is submitted -- tracked separately, see P16):
   new build entry with the tag's commit SHA, versionName/versionCode, and
   the `binary:` URL following the `PassPonyAndroid-X.Y.Z-foss.apk` naming
   this procedure produces.

Clean up `<work>` once the release is out.

## Cross-machine verification result log

Per the playbook's results-log template (section 14) -- one row per
verification event, updated as each one actually happens:

| Date | Step | Result |
| --- | --- | --- |
| | gate rebuild (main) | not yet run |
| | CI legs (JDK 17 / 21) | not yet run -- pending the first `v*` tag |
| | F-Droid outcome | not submitted yet (P16) |
