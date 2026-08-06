# P15. CI and the reproducible build recipe

Objective: GitHub Actions proving every push (Rust cross-build, Kotlin
build, unit tests, fixture round-trip on an emulator), and a written,
executed reproducible-build recipe that F-Droid can follow: two clean
builds, byte-identical APK.

Requires: P02 (pipeline); realistic once P05/P06 exist.

## Work

1. Workflow `ci.yml` (ubuntu-latest):
   - checkout with submodules;
   - checkout PassPonyCore as a sibling (actions/checkout with path;
     pin the ref in one place at the top of the workflow, bump it
     deliberately);
   - rustup with the toolchain from PassPonyCore's rust-toolchain.toml,
     cargo-ndk, NDK via the SDK manager (pin the exact NDK release in
     one variable shared with gradle.properties `ndkVersion`);
   - `bash scripts/build-core.sh`;
   - `./gradlew test assembleFossDebug assemblePlayDebug lint`;
   - emulator job (x86_64 image, API 34): the instrumented fixture
     suites from P02/P05/P06/P08/P09. Use the standard emulator
     runner action with KVM.
2. PassPonyCore CI (that repo): ensure the age-engine feature and the
   Android cargo check from P04 are in its matrix (may already be
   done; verify, do not duplicate).
3. Reproducibility hardening in this repo:
   - deterministic Rust: build with `--remap-path-prefix` mapping both
     the core checkout and HOME to fixed tokens (wire RUSTFLAGS in
     build-core.sh, always on, so dev and CI builds agree); strip is
     already in the core's release profile.
   - Gradle: `vcsInfo.include false` and dependenciesInfo off (done in
     P01, verify), pinned buildTools, pinned AGP/Kotlin via the version
     catalog, no dynamic versions anywhere (lint rule or a grep check
     in CI).
   - Document every pinned input in `docs/REPRODUCIBLE.md`: NDK
     release, Rust toolchain, JDK distribution and version, Gradle,
     AGP, buildTools, submodule tag, PassPonyCore ref.
4. The recipe itself in `docs/REPRODUCIBLE.md`: from a bare machine,
   clone, install pinned tools, build `assembleFossRelease` twice in
   different directories, compare with apksigner-aware diff (unsigned
   APK compare, or apksigcopier for the signed case, the F-Droid
   convention). Execute the recipe once and record the result in the
   doc.
5. CI badge and a `verify` job that runs the double-build comparison
   weekly (scheduled), catching drift early rather than at F-Droid
   submission.

## Exit criteria

- CI green on a fresh push, emulator suite included.
- The double-build produces byte-identical unsigned APKs on two
  different machines (CI runner and Kevin's Mac count as the two).
- docs/REPRODUCIBLE.md complete enough that a stranger could follow it.

## Out of scope

- Storefront metadata and the actual F-Droid submission files (P16).
- Release signing automation (P16).
