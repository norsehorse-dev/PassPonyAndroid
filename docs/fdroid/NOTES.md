# Notes on com.passpony.android.yml

The yml next to this file is the submission copy: it is kept in
`fdroid rewritemeta` canonical form, with no comments, so it can go
into a fdroiddata MR (or a fork pipeline test) as-is. Validate after
any edit with `fdroid rewritemeta` + `fdroid lint` against a scratch
copy, then commit only the canonical output (playbook section 2).
Everything explaining *why* the recipe looks the way it does lives
here instead, because rewritemeta silently deletes yml comments on
every pass.

## Why each piece is the way it is

**`commit:`** is `git rev-list -n 1 v1.0.1`, never the tag name.
Reviewers ask for the full hash even when a tag would resolve (caught
on QuorumPony). Re-pin it, and bump versionName/versionCode/
CurrentVersion/CurrentVersionCode together, whenever a new tagged
release ships.

**Why the pinned release is v1.0.1, not v1.0.0.** v1.0.0's tagged tree
pins PGPonyCore-Kotlin at a commit whose build.gradle.kts still had
`kotlin { jvmToolchain(17) }`. F-Droid's buildserver runs with Gradle
toolchain auto-provisioning disabled, so that request fails hard
("Cannot find a Java installation... Toolchain auto-provisioning is
not enabled") -- QuorumPony's pure-JVM core module hit the identical
failure. The fix (explicit `compilerOptions.jvmTarget` instead) is in
the submodule commit v1.0.1 pins; tags are immutable, so a new tag and
version bump it was.

**No JDK in `sudo:`.** An earlier draft installed
openjdk-17-jdk-headless, believing the buildserver defaulted to JDK 11.
A real pipeline run (norsehorse/pgponyandroid fork, 2026-08-11) showed
the current buildserver-trixie image ships JDK 21 preinstalled and set
as default, and Debian trixie's repos don't carry openjdk-17 at all --
that install step was itself the build failure. AGP 8.13.2 needs 17 or
newer, and `.github/workflows/reproducible.yml` proves the build
byte-identical under JDK 21, so the buildserver default is used as-is.

**`sudo:` installs perl, make, build-essential, pkg-config, and
libssl-dev.** The build container starts without all five: pass-core's
vendored OpenSSL build for mobile targets needs perl and make (see
`scripts/build-core.sh`), `cargo install cargo-ndk` needs a host C
compiler/linker ("linker 'cc' not found"), and a host-side openssl-sys
build in the dependency tree needs pkg-config plus the system OpenSSL
headers ("The pkg-config command could not be found"). All three
failures reproduced on the fork pipeline (2026-08-11) and are the
identical sequence `docker/release.Dockerfile` went through, fixed the
identical way.

**rustup installs in `prebuild:`, NOT `sudo:`.** The sudo block runs
as root, so a rustup install there lands in /root/.cargo -- but
prebuild runs as the vagrant user, whose PATH points at
/home/vagrant/.cargo/bin, and the build died with "rustup: command
not found" (fork pipeline run, 2026-08-11) when the install lived in
sudo. Installing from prebuild puts it in the home that actually runs
the build.

**`ndk: 27.2.12479018` must be declared.** F-Droid only provisions an
NDK, and only substitutes `$$NDK$$`, when the build entry asks for one
-- without the field, `export ANDROID_NDK_HOME="$$NDK$$"` expanded to
an empty string (same fork run). Keep the version in sync with
`gradle.properties`' ndkVersion by hand.

**The foojay strip in `prebuild:`.** F-Droid's scanner rejects a build
outright just for referencing org.gradle.toolchains.foojay-resolver (a
"usual suspect": it can fetch an unpinned JDK from the network
mid-build), regardless of whether it would ever trigger. Harmless to
remove -- it was only ever a fallback for machines without a suitable
JDK already installed, and the buildserver has its own.

**The core build lives in `build:`, NOT `prebuild:`.** F-Droid's
scanner runs between the two, and it hard-errors on any `.so` it finds
in the source tree -- including ones prebuild just legitimately built
from source ("Found shared library at core/src/main/jniLibs/...",
fork pipeline run, 2026-08-11). The `build:` field exists exactly for
this: it runs after the scan, before the gradle build. The commands
mirror `scripts/build-core.sh` (the authoritative, commented version
this must stay in sync with): install rustup, pin the toolchain
PassPonyCore's own rust-toolchain.toml names, install cargo-ndk,
cross-compile with the remap-path-prefix / SOURCE_DATE_EPOCH /
target-dir determinism pins (see `docs/REPRODUCIBLE.md`). Commands run
from `subdir:` (app/), not the repo root; only
`$$SDK$$`/`$$NDK$$`/`$$VERSION$$`/`$$VERCODE$$`/`$$COMMIT$$` and
declared srclib names get substituted.

**`Binaries:` and `AllowedAPKSigningKeys:`** opt into F-Droid's
Reproducible Builds flow, added at reviewer request on the MR. The
Binaries URL pattern uses `%v` (versionName) against the GitHub
release naming `scripts/release.sh` produces; the key is the release
signing cert's lowercase SHA-256, extracted from the published
v1.0.1 foss APK. F-Droid will rebuild from source and verify against
the published, developer-signed APK instead of signing with their own
key. rewritemeta places Binaries next to Repo and
AllowedAPKSigningKeys after the Builds block on its own; let it.

**No `srclibs:`.** PassPonyCore and PGPonyCore-Kotlin are git
submodules of this repo; `submodules: true` runs
`git submodule update --init --recursive` right after the clone,
before prebuild. See `docs/REPRODUCIBLE.md` for why PassPonyCore
became a submodule (a real F-Droid recipe has no way to inject an env
var into the build it clones, which the old separate-checkout
architecture needed).

**No `Summary:`/`Description:`.** `fdroid update` reads
`fastlane/metadata/android/en-US/short_description.txt` and
`full_description.txt` straight from this repo and uses those when the
metadata file doesn't set its own (confirmed via `fdroid lint` passing
with both fields absent). One source of truth instead of two copies
that can drift.

**Not opting into Reproducible Builds
(`Binaries:`/`AllowedAPKSigningKeys:`) yet**, though nothing blocks
it -- the Linux-built release is verified reproducible
(`docs/REPRODUCIBLE.md`'s cross-machine log). Add the two fields with
the release APK's signing cert SHA-256 before submission if opting in.

## Submitting

The GitLab fork/MR steps only Kevin can do (his account) are in
NorseHorse's F-Droid Submission Playbook, section 3. File name in the
MR must be exactly `metadata/com.passpony.android.yml` -- the pipeline
derives the app id from the filename, which is how a scratch file
named `com.passpony.android.canonical.yml` made checkupdates fail
during the 2026-08-11 fork test.
