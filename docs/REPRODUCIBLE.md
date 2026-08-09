# Reproducible builds

PassPonyAndroid targets a byte-identical build: given the same source commit
and the same pinned toolchain versions, two independent machines produce the
exact same unsigned APK. This matters most for F-Droid, whose build
infrastructure re-builds every release from source and compares the result
against what's published -- a build that isn't reproducible can't be verified
that way.

## What's pinned, and where

| Input | Pinned value | Source of truth |
| --- | --- | --- |
| Android NDK | 27.2.12479018 | `gradle.properties` (`ndkVersion`) -- read by both `app/build.gradle.kts` (`android.ndkVersion`) and `scripts/build-core.sh` (`sed`), so the two toolchains never drift apart |
| Android Gradle Plugin | 8.13.2 | `build.gradle.kts` (top-level `plugins {}`) |
| Kotlin | 2.1.0 | `build.gradle.kts` (top-level `plugins {}`) |
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| Android build tools | 35.0.0 | `app/build.gradle.kts` (`android.buildToolsVersion`) |
| JDK | 17 | `app/build.gradle.kts` / `core/build.gradle.kts` (`compileOptions`, `kotlinOptions.jvmTarget`); CI installs Temurin 17 |
| Rust toolchain | whatever `rust-toolchain.toml` in PassPonyCore pins (currently `1.95.0`) | PassPonyCore's own `rust-toolchain.toml`, picked up automatically by `rustup` when `cargo` runs from inside a PassPonyCore checkout |
| PassPonyCore commit | `be1aba698d8815f4749c4889ac304229d5c17d9a` | `.github/workflows/ci.yml` (`env.PASSPONY_CORE_SHA`) and `scripts/verify-reproducible.sh` (same env var, passed in) |

`be1aba69...` is the current PassPonyCore HEAD rather than the `v1.0.0` tag,
because `v1.0.0` predates the `age-engine` feature this build depends on
(confirmed via `git merge-base --is-ancestor v1.0.0 HEAD`, which fails). Bump
this SHA deliberately when PassPonyCore's `age-engine` code changes, and bump
the NDK/AGP/Kotlin/Gradle versions deliberately alongside a real toolchain
upgrade -- never let any of these float to "whatever's newest installed."

## What else makes the build deterministic

A pinned toolchain alone isn't enough -- a few specific non-determinism
sources have to be closed off:

- **Rust panic-location strings.** `file!()`/`line!()` macro expansions bake
  the absolute source path into the compiled binary as plain string data
  (not debug info, so `strip = "symbols"` doesn't remove it). Two machines
  checking out PassPonyCore to different paths would otherwise never produce
  byte-identical `.so` files. `scripts/build-core.sh` always sets
  `RUSTFLAGS="--remap-path-prefix=$CORE=/passpony-core --remap-path-prefix=$HOME=/home"`
  to normalize this, unconditionally rather than as an opt-in flag.
- **AGP's VCS/dependency metadata.** `app/build.gradle.kts` sets
  `vcsInfo.include = false` and `dependenciesInfo { includeInApk = false;
  includeInBundle = false }`, so the APK doesn't embed the exact git commit
  or a Play-specific dependency manifest, both of which would otherwise vary
  the output without changing the actual app.
- **No dynamic dependency versions.** `.github/workflows/ci.yml` greps every
  `.kts` file for a trailing `+` version specifier (e.g. `1.2.+`) or
  `latest.release`/`latest.integration`, and fails the build if it finds
  one -- an unpinned dependency can silently resolve to a different artifact
  on a different day, which nothing else here would catch.

## Running the check yourself

`scripts/verify-reproducible.sh` builds the FOSS-flavor release APK twice, in
two independent clean clones of this repo, and diffs the result:

```
PASSPONY_CORE_SHA=be1aba698d8815f4749c4889ac304229d5c17d9a bash scripts/verify-reproducible.sh
```

Prerequisites are the same as `scripts/build-core.sh`'s: `rustup`,
`cargo-ndk`, `perl`, `make`, and the pinned NDK (`ndkVersion` in
`gradle.properties`) installed via Android Studio's SDK manager or
`sdkmanager`. The script clones PassPonyCore fresh from GitHub, then clones
*this* repo twice locally (no push required -- a local clone is enough), runs
`scripts/build-core.sh` and `./gradlew assembleFossRelease` in each, and
compares the two resulting APKs with `cmp`. A pass looks like:

```
Reproducible build check PASSED: APKs are byte-identical.
```

A failure unpacks both APKs and runs `diff -rq` between them so the first
differing file is easy to spot; the full report is also written to
`build-verify/report.txt`.

This same script runs automatically once a week in CI
(`.github/workflows/ci.yml`'s `verify-reproducible` job, gated on
`github.event_name == 'schedule'`, plus `workflow_dispatch` for an on-demand
run), and uploads `build-verify/report.txt` as an artifact if it fails.

## Cross-machine verification

Running the script above proves the build is reproducible *on one machine*
-- it doesn't rule out a difference tied to the machine itself (OS version,
locale, timezone, filesystem case-sensitivity). The real test is comparing
an APK built on the CI runner against one built independently on a
developer's own machine:

1. Trigger `.github/workflows/ci.yml` (push, or `workflow_dispatch`) and
   download the `foss-debug-apk` artifact -- or, for a release-config
   comparison, run the `verify-reproducible` job via `workflow_dispatch` and
   pull `sha256 A`/`sha256 B` from its logs.
2. On a separate machine, check out the same commit and run
   `./gradlew assembleFossRelease` (after `scripts/build-core.sh`, using the
   same `PASSPONY_CORE_SHA`).
3. Compare `sha256sum` of both APKs.

Latest recorded result: **not yet run** -- pending `.github/workflows/ci.yml`
actually running on GitHub (see the open question about pushing this repo).
This section will be updated with the date, both machines involved, and the
matching (or differing) SHA-256 once that comparison has been performed.
