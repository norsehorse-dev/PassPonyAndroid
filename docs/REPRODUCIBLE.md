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
| PassPonyCore commit | `65792fce6ebc69ad8e3fca8cbd6db23d899712aa` | the `third_party/passponycore` git submodule (see `.gitmodules`) -- a gitlink committed alongside the rest of the tree, not an env var. `tools/verify_repro.sh` and both CI workflows pick it up automatically via `git submodule update --init --recursive` on each clone |
| Cargo `target-dir` for PassPonyCore | `/tmp/passponycore-cargo-target` | PassPonyCore's own `.cargo/config.toml`, so it applies no matter who checks the repo out -- see "What else makes the build deterministic" below |
| `SOURCE_DATE_EPOCH` for PassPonyCore | `1786387087` (any non-empty value works -- see below) | PassPonyCore's own `.cargo/config.toml` (`[env]` table) |
| Every Gradle dependency | no `+`, no `latest.release`/`latest.integration` | enforced by CI's "Reject dynamic dependency versions" step, which greps every `.kts` file |

`65792fce...` is the current PassPonyCore HEAD rather than the `v1.0.0` tag,
because `v1.0.0` predates the `age-engine` feature this build depends on
(confirmed via `git merge-base --is-ancestor v1.0.0 HEAD`, which fails). Bump
the submodule pointer deliberately when PassPonyCore's `age-engine` code
changes -- `cd third_party/passponycore && git fetch && git checkout <sha>`,
then from the repo root `git add third_party/passponycore && git commit` --
and bump the NDK/AGP/Kotlin/Gradle versions deliberately alongside a real
toolchain upgrade -- never let any of these float to "whatever's newest
installed."

(Before P16, PassPonyCore was a separate sibling checkout, pinned by a
`PASSPONY_CORE_SHA` env var that `.github/workflows/ci.yml` /
`reproducible.yml` and `tools/verify_repro.sh` each had to pass around
independently -- one of the fragile patterns NorseHorse's F-Droid
Submission Playbook flags, since a real F-Droid recipe has no way to
inject an env var into the build it clones. The submodule pin replaces
that: the pinned commit travels with the repo itself.)

## What else makes the build deterministic

- **Rust panic-location strings.** `file!()`/`line!()` macro expansions bake
  the absolute source path into the compiled binary as plain string data
  (not debug info, so `strip = "symbols"` doesn't remove it). Two machines
  checking out PassPonyCore to different paths would otherwise never produce
  byte-identical `.so` files. `scripts/build-core.sh` always sets
  `RUSTFLAGS="--remap-path-prefix=$CORE=/passpony-core --remap-path-prefix=$HOME=/home"`
  to normalize this, unconditionally rather than as an opt-in flag.
- **`openssl-sys`'s vendored OpenSSL build.** `pass-core`'s Cargo.toml
  depends directly on `openssl-sys = { vendored }` on mobile targets only
  (`git2` needs an OpenSSL to link against and there's no system one to
  find when cross-compiling), and that build bakes an absolute,
  `OUT_DIR`-derived install path into `libcrypto` at two runtime constants,
  `ENGINESDIR` and `MODULESDIR` -- functionally inert here (nothing in this
  app dynamically loads OpenSSL engines/providers by name), but still
  compiled-in bytes that differ whenever the checkout path differs.
  `RUSTFLAGS --remap-path-prefix` doesn't touch this: that's a rustc
  mechanism for panic-location/debuginfo strings, and this is OpenSSL's own
  C build system (`openssl-src`'s `Build` struct hardcodes `--prefix` under
  `OUT_DIR` with no override -- only `--openssldir` is exposed, via
  `openssl_dir()`, and that's not what's leaking). First surfaced by this
  gate once `third_party/passponycore` became a real submodule and srcA
  and srcB's checkouts genuinely landed at different paths (a shared single
  clone had been masking it by coincidence -- see the log entry below).
  Fixed at the source: PassPonyCore's own `.cargo/config.toml` pins
  `target-dir` to `/tmp/passponycore-cargo-target`, so `OUT_DIR` (and the
  baked `ENGINESDIR`/`MODULESDIR`) is identical no matter who checks the
  repo out -- this app's build scripts, this gate, or eventually F-Droid's
  buildserver.
- **OpenSSL's embedded build timestamp.** Once the `target-dir` pin above
  closed the `ENGINESDIR`/`MODULESDIR` gap, this gate found exactly one
  remaining difference between two builds a few minutes apart: a literal
  `built on: <date>` string OpenSSL's build stamps into `libcrypto`
  (`crypto/buildinf.h`, generated by `mkbuildinf.pl`) from the real
  wall-clock time. OpenSSL has honored `SOURCE_DATE_EPOCH` for this since
  2017 (`openssl/openssl#4639`) -- when set to *any* value, `mkbuildinf.pl`
  emits a fixed `reproducible build, date unspecified` string instead of
  formatting a timestamp, so the specific epoch chosen doesn't affect
  byte-identity. PassPonyCore's `.cargo/config.toml` pins it via cargo's
  `[env]` table (which does reach build-script subprocesses, including
  `openssl-src`'s `perl Configure`/`make` invocations) to this commit's own
  timestamp, with `force = false` (the default) so an externally-set
  `SOURCE_DATE_EPOCH` -- e.g. from F-Droid's buildserver, which commonly
  sets one as standard practice -- wins over this pin rather than being
  overridden.
- **The build host itself.** The two gaps above are both about *where*
  the checkout lives; this one is about *which OS builds it*. A
  Mac-built release and a Linux-built release of the identical commit
  turned out not to be byte-identical either, `libpass_ffi.so` differing
  on both ABIs while `classes.dex` matched. The NDK ships separate
  prebuilt clang binaries per host OS inside the "same" NDK version
  (`darwin-x86_64` for macOS, `linux-x86_64` for Linux), and they pass
  different default assembler flags into pass-core's vendored OpenSSL
  build (`linux-x86_64`'s adds `-Wa,--noexecstack -Qunused-arguments`,
  `darwin-x86_64`'s doesn't) -- confirmed via OpenSSL's own embedded
  `compiler:` build-info line, which records the exact `CC` invocation
  used. `--noexecstack` changes the compiled ELF layout, so this is a
  real codegen difference, not an embedded path or timestamp string, and
  neither `--remap-path-prefix` nor `SOURCE_DATE_EPOCH` can reach it.
  Fixed by never building releases directly on macOS: `docker/` holds a
  Linux release-build container (see `docker/README.md`) that mirrors
  `.github/workflows/reproducible.yml`'s `ubuntu-24.04` toolchain, so
  every release comes from the same host class CI and F-Droid's own
  buildserver use, regardless of which machine runs `docker run`.
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
  step). Requires network (clones this repo; PassPonyCore comes along
  automatically via each clone's own `git submodule update --init
  --recursive`) and an Android SDK/NDK matching `gradle.properties`'
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
tools/verify_repro.sh rebuild main
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
   tools/verify_repro.sh rebuild <tag>
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
| 2026-08-09 | gate rebuild (main @ `6c6bd24`), Kevin's Mac | IDENTICAL -- `tools/verify_repro.sh rebuild main`, two independent clones/builds byte-identical. Content hash `2c00bfc46561dcba9315a12fe08d220da1c8bd0d1d56b5417b9d650013e27950`. PassPonyCore was still a separate SHA-pinned clone shared by both builds at this point (pre-P16-submodule), which happened to mask the `openssl-sys` gap below. |
| 2026-08-10 | gate rebuild (main, `feee09a`), GitHub Actions `workflow_dispatch` | DIFFERS -- `classes.dex` identical, `libpass_ffi.so` differed on both ABIs. Root-caused via `strings` diff to `openssl-sys`'s vendored OpenSSL build baking the absolute, checkout-path-derived `ENGINESDIR`/`MODULESDIR` into `libcrypto` (see "What else makes the build deterministic" above). First real double-build test since `third_party/passponycore` became a submodule with its own per-clone checkout path; the prior shared-clone architecture had been masking this by accident, not by fixing it. |
| 2026-08-10 | gate rebuild (main, `4fe1054`), Kevin's Mac -- after PassPonyCore `608d06a` (`target-dir` pin) | DIFFERS -- `ENGINESDIR`/`MODULESDIR` gap confirmed closed (full unfiltered `strings` diff of both `.so`s: 31,209 lines each, exactly one line differed). That one line was OpenSSL's `built on: <date>` build timestamp -- see the `SOURCE_DATE_EPOCH` entry above, added in response. |
| 2026-08-10 | gate rebuild (main, `19d8a80`), Kevin's Mac -- after PassPonyCore `65792fc` (`SOURCE_DATE_EPOCH` pin) | IDENTICAL -- `tools/verify_repro.sh rebuild main`, two independent clones/builds byte-identical. Content hash `a9f75ddb03dd7d4d15f46235413b42596d7c21d228943f1dfb6c415b1553796a`. Reproducible-build gate fully green again: the `ENGINESDIR`/`MODULESDIR` and `built on:` gaps found once `third_party/passponycore` became a real submodule (see the two `DIFFERS` rows above) are both closed at the source in PassPonyCore, not papered over in this gate. |
| 2026-08-10 | download verification (v1.0.0 GitHub release), fetched fresh from github.com, not the local build output | IDENTICAL -- downloaded `PassPonyAndroid-1.0.0-foss.apk` and `PassPonyAndroid-1.0.0-play.aab` straight from the release page and confirmed both SHA-256s match `PassPonyAndroid-1.0.0-SHA256SUMS.txt` exactly: foss `6040ddc1c1ae9bba4f1b3cde693ed127fb7b406f949461bf0eb3a7d1a727b788`, play `7fcb32c82172e3400dcd33365418b78dd3f66834d7ddb7ad09ca20492ba53da6`. Since the downloaded file is byte-identical to the local build, the content hash from `scripts/release.sh`'s run (`a9f75ddb03dd7d4d15f46235413b42596d7c21d228943f1dfb6c415b1553796a`) carries over without needing to be recomputed separately.
| 2026-08-10 | CI legs (JDK 17 / 21), `.github/workflows/reproducible.yml` on tag `v1.0.0` | FAILED on both legs, same step: "Compare the published release asset against a fresh clean build" -- `libpass_ffi.so` differed on both ABIs, `classes.dex` untouched. Both legs' own two-clone-on-Linux determinism gate passed, so this wasn't a JDK issue; it was the published (Mac-built) asset vs. a Linux CI build. Root-caused via a `strings` diff of the Mac-built and CI-built `.so`: OpenSSL's embedded `compiler:` line showed the NDK's `darwin-x86_64` clang prebuilt omitting `-Wa,--noexecstack -Qunused-arguments`, which the `linux-x86_64` prebuilt of the *same* NDK version (`27.2.12479018`) adds by default. That's a real codegen difference (`--noexecstack` changes the compiled ELF layout), not just an embedded path string, so it's outside what `--remap-path-prefix`/`SOURCE_DATE_EPOCH` can fix. See "What else makes the build deterministic" above for the resolution. |
| 2026-08-11 | rebuild inside `docker/release.Dockerfile` (`linux/amd64` pinned), tag `v1.0.0`, Kevin's Mac | Rebuilt the release from scratch inside the Linux container introduced in response to the CI-legs failure above -- the build-host fix described in "What else makes the build deterministic." Content hash `df742ffada13752e1a9b2232d8391b6cb91fdd4c183684f3f2fa5df468e2ab12`, deliberately different from the Mac-built `a9f75ddb...` hash above: this is the Linux-built replacement, not a rebuild of the same artifact. Physical-device matrix (`RELEASE_CHECKLIST.md` step 4) rerun and passed against this exact `foss.apk` before the assets below were published. |
| 2026-08-11 | re-upload of Linux-built assets to the existing `v1.0.0` GitHub release (`gh release upload --clobber`), then download verification fetched fresh from github.com | IDENTICAL -- downloaded `PassPonyAndroid-1.0.0-foss.apk` and `PassPonyAndroid-1.0.0-play.aab` straight from the release page and confirmed both SHA-256s match the re-uploaded `PassPonyAndroid-1.0.0-SHA256SUMS.txt` exactly: foss `38147dd6a3b08fc20df6d8d67a673ac3a30e012c04a4012207acae8a85523cb0`, play `3693897961f286aed9138bc6928718b21828f16d5ed5e447617a58e3f644fd58`. Supersedes the 2026-08-10 download-verification row above, which checked the since-replaced Mac-built assets. |
| 2026-08-11 | CI legs (JDK 17 / 21) re-run, `.github/workflows/reproducible.yml` on tag `v1.0.0` (`workflow_dispatch`, run `31447518769`), against the re-uploaded Linux-built assets | IDENTICAL on both legs -- the asset-comparison step that failed against the Mac-built asset (row above) passed against the Linux-built one. Reproducible-build gate fully green for `v1.0.0`; the build-host gap is closed. |
| 2026-08-11 | F-Droid fork pipeline dry-run (norsehorse/pgponyandroid fork, `metadata/com.passpony.android.canonical.yml`) against tag `v1.0.0` | FAILED twice, both failures environmental rather than app defects, and both informative. (1) `checkupdates`: "Couldn't find any version information" -- the scratch metadata file was named `com.passpony.android.canonical.yml`, so the pipeline derived app id `com.passpony.android.canonical`, which no manifest can match; the real submission file's name is the applicationId exactly, per the playbook. (2) `fdroid build`: `E: Unable to locate package openjdk-17-jdk-headless` -- the current buildserver image is `buildserver-trixie` (Debian 13), which ships JDK 21 preinstalled and default; trixie's repos don't carry openjdk-17 at all, so the recipe's JDK 17 install (based on stale JDK-11-era intel) was itself the failure. Also surfaced that `v1.0.0`'s tagged tree pins PGPonyCore-Kotlin at a commit with `kotlin { jvmToolchain(17) }`, which fails under toolchain-auto-provisioning-disabled JDK-21-only conditions. All three resolved by v1.0.1: submodule bumped to drop jvmToolchain, recipe stripped of the JDK install, new tag cut since tags are immutable. |
| 2026-08-11 | rebuild inside `docker/release.Dockerfile`, ref `main` pre-tag (v1.0.1, `5acd1d4`), Kevin's Mac | Linux-built 1.0.1 release. Content hash `a1fd79339506513ba40e15a666f6b01bd73fef62a30f7035c3ee8e4a9fc9bfa8`. Only changes from the verified 1.0.0 build: versionCode/versionName bump and the PGPonyCore-Kotlin submodule's build-file swap (same JVM 17 bytecode target via `compilerOptions.jvmTarget`, no code changes). |
| 2026-08-11 | download verification (v1.0.1 GitHub release), fetched fresh from github.com, not the local build output | IDENTICAL -- downloaded `PassPonyAndroid-1.0.1-foss.apk` and `PassPonyAndroid-1.0.1-play.aab` straight from the release page and confirmed both SHA-256s match `PassPonyAndroid-1.0.1-SHA256SUMS.txt` exactly: foss `bb041a14d1ae1503e0b2ff496174653dcd3f9dc31a13d554edc420fc3e0d64ce`, play `383bcf0e924b77bda7dfa5f67c3451bd2e7ecf830b83a1b3486d38ffbbd8ae6c`. |
| | CI legs (JDK 17 / 21), `.github/workflows/reproducible.yml` on tag `v1.0.1` | pending -- triggered automatically by the tag push; log the result here once both legs finish |
| | F-Droid outcome | not submitted yet (P16) |
