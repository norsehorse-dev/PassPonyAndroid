# Release build container

Builds the signed release APK/AAB on Linux, matching CI's and F-Droid's
build host, instead of on macOS. See `docs/RELEASE_CHECKLIST.md` step 3
and `docs/REPRODUCIBLE.md` for why: a Mac-built release and a Linux-built
release of the same commit are not byte-identical (the NDK's per-host
clang prebuilts pass different default flags into the vendored OpenSSL
build), and F-Droid always builds from source on Linux.

Requires Docker Desktop (or another local Docker) on the machine holding
the real release keystore. Never runs anywhere else -- there's no CI
integration here on purpose; the keystore stays local and bind-mounted
read-only, never baked into the image or passed as a build arg.

Build the image once (rebuild it whenever `gradle.properties`' `ndkVersion`
or the pinned Rust toolchain changes):

```
cd ~/Apps/PassPonyAndroid
docker build -t passpony-release -f docker/release.Dockerfile docker
```

Run a release. `<ref>` is whatever `RELEASE_CHECKLIST.md` has you on at
that step: the just-pushed commit SHA (or `main`) for step 3's dry-run
build, since that happens before step 5 creates the tag, or the tag
itself if redoing a release that's already tagged. The container clones
fresh from GitHub rather than using any local working tree, the same way
`.github/workflows/reproducible.yml` does, so `<ref>` has to already be
pushed:

```
docker run --rm \
  -v ~/Keys/PassPony/release.keystore:/keystore/release.keystore:ro \
  -v ~/Apps/PassPonyAndroid/keystore.properties:/keystore-props/keystore.properties:ro \
  -v ~/Apps/PassPonyAndroid:/out \
  passpony-release <version> <ref>
```

Adjust the first two `-v` paths if your keystore or `keystore.properties`
live somewhere else. Results land at `release-<version>/` under the repo
root, same as a direct `scripts/release.sh` run, ready for
`tools/verify_repro.sh compare` and `gh release create`.
