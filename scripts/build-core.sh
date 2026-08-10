#!/usr/bin/env bash
# Cross-compile pass-ffi from PassPonyCore for Android and generate the
# Kotlin UniFFI bindings. Run from the repo root: bash scripts/build-core.sh
# Prereqs: rustup with the toolchain pinned by PassPonyCore, cargo-ndk
# (cargo install cargo-ndk), an Android NDK matching gradle.properties'
# ndkVersion (via Android Studio's SDK manager), perl and make on PATH
# (needed by pass-core's vendored OpenSSL build for mobile targets),
# python3 (standard library only -- used to read cargo metadata's JSON).

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# PassPonyCore is a git submodule (third_party/passponycore, see P16 --
# it used to be a separate sibling checkout pinned by a SHA env var CI
# and tools/verify_repro.sh each had to pass around, which is exactly the
# pattern the F-Droid Submission Playbook flags as fragile; PGPonyCore-
# Kotlin already worked this way). PASSPONY_CORE still overrides it for
# local dev against some other checkout.
CORE="${PASSPONY_CORE:-$REPO/third_party/passponycore}"
FEATURES="${PASS_FFI_FEATURES:-age-engine}"

[[ -d "$CORE/crates/pass-ffi" ]] || {
  echo "PassPonyCore not found at $CORE -- if this is a fresh clone, run: git submodule update --init --recursive (or set PASSPONY_CORE to point at an existing checkout)"; exit 1; }

command -v cargo >/dev/null || {
  echo "cargo not found; install rustup first"; exit 1; }

command -v cargo-ndk >/dev/null || {
  echo "cargo-ndk not found; run: cargo install cargo-ndk"; exit 1; }

command -v perl >/dev/null || {
  echo "perl not found; required to cross-compile vendored OpenSSL for Android"; exit 1; }

command -v make >/dev/null || {
  echo "make not found; required to cross-compile vendored OpenSSL for Android"; exit 1; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  # gradle.properties' ndkVersion is the single source of truth (see its
  # own comment there, and docs/REPRODUCIBLE.md) -- read it here instead
  # of picking whatever's newest installed, so this cross-compile and
  # AGP's own NDK resolution always agree without a second place to edit.
  NDK_VERSION="$(sed -n 's/^ndkVersion=//p' "$REPO/gradle.properties")"
  [[ -n "$NDK_VERSION" ]] || {
    echo "Could not read ndkVersion from $REPO/gradle.properties"; exit 1; }
  NDK_ROOT="$HOME/Library/Android/sdk/ndk"
  ANDROID_NDK_HOME="$NDK_ROOT/$NDK_VERSION"
  [[ -d "$ANDROID_NDK_HOME" ]] || {
    echo "NDK $NDK_VERSION not found at $ANDROID_NDK_HOME; install it via Android Studio's SDK manager (SDK Tools > NDK, pick $NDK_VERSION), or set ANDROID_NDK_HOME to override"
    exit 1
  }
  export ANDROID_NDK_HOME
fi

pushd "$CORE" >/dev/null

# Run inside the core dir so rustup targets the toolchain pinned by its
# rust-toolchain.toml, not whatever default toolchain the shell has.
rustup target add aarch64-linux-android x86_64-linux-android

# Deterministic builds: rustc otherwise bakes the absolute PassPonyCore
# checkout path and $HOME into panic-location strings (file!()/line!()
# macro expansions), which survive PassPonyCore's `strip = "symbols"`
# release profile since they're plain string data, not debug info --
# two machines building from different checkout paths would otherwise
# never produce a byte-identical binary. Always on, not opt-in, so dev
# and CI builds already agree without a second flag to remember (see
# docs/plan/P15-ci-reproducible.md, docs/REPRODUCIBLE.md). Applied to
# both cargo invocations below for consistency, even though the host
# debug build isn't itself shipped.
export RUSTFLAGS="--remap-path-prefix=$CORE=/passpony-core --remap-path-prefix=$HOME=/home${RUSTFLAGS:+ $RUSTFLAGS}"

echo "Cross-compiling pass-ffi (features: $FEATURES) for arm64-v8a and x86_64"
cargo ndk --platform 26 -t arm64-v8a -t x86_64 -o "$REPO/core/src/main/jniLibs" \
  build --release -p pass-ffi --features "$FEATURES"

# uniffi-bindgen reads its metadata from special sections in the compiled
# library. PassPonyCore's release profile sets strip = "symbols", which
# removes those sections; the host library used for binding generation
# has to be a debug build even though the on-device .so above is release.
echo "Building host library for bindgen (debug profile; release strips the metadata bindgen reads)"
cargo build -p pass-ffi --features "$FEATURES"

# Resolve cargo's actual target dir via `cargo metadata` rather than
# assuming "$CORE/target" -- PassPonyCore's own .cargo/config.toml pins
# target-dir to a fixed absolute path (/tmp/passponycore-cargo-target, see
# docs/REPRODUCIBLE.md) so the vendored OpenSSL build's baked-in
# ENGINESDIR/MODULESDIR don't depend on checkout location; asking cargo
# directly (instead of hardcoding that path here too) keeps this script
# correct regardless of whether that pin ever changes or is overridden.
CARGO_TARGET_DIR_RESOLVED="$(cargo metadata --no-deps --format-version 1 2>/dev/null | python3 -c 'import json, sys; print(json.load(sys.stdin)["target_directory"])')"
[[ -n "$CARGO_TARGET_DIR_RESOLVED" ]] || {
  echo "Could not resolve cargo's target directory via 'cargo metadata'"; exit 1; }

# crate-type = cdylib names its output per host platform: .dylib on macOS,
# .so on Linux. Check both rather than assuming the CI (Linux) extension.
HOST_LIB="$CARGO_TARGET_DIR_RESOLVED/debug/libpass_ffi.dylib"
[[ -f "$HOST_LIB" ]] || HOST_LIB="$CARGO_TARGET_DIR_RESOLVED/debug/libpass_ffi.so"
[[ -f "$HOST_LIB" ]] || {
  echo "Host library not found at $CARGO_TARGET_DIR_RESOLVED/debug/libpass_ffi.{dylib,so}"; exit 1; }

BINDINGS_OUT="$(mktemp -d)"
cargo run -q -p pass-ffi --features "cli,$FEATURES" --bin uniffi-bindgen -- \
  generate --library "$HOST_LIB" --language kotlin --out-dir "$BINDINGS_OUT"

popd >/dev/null

KOTLIN_OUT="$REPO/core/src/main/kotlin"
rm -rf "$KOTLIN_OUT/uniffi"
mkdir -p "$KOTLIN_OUT"
cp -r "$BINDINGS_OUT/uniffi" "$KOTLIN_OUT/"

echo "Done. Produced:"
echo "  $REPO/core/src/main/jniLibs/arm64-v8a/libpass_ffi.so"
echo "  $REPO/core/src/main/jniLibs/x86_64/libpass_ffi.so"
echo "  $KOTLIN_OUT/uniffi/pass_ffi/pass_ffi.kt"
