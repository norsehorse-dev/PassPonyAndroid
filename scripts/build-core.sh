#!/usr/bin/env bash
# Cross-compile pass-ffi from PassPonyCore for Android and generate the
# Kotlin UniFFI bindings. Run from the repo root: bash scripts/build-core.sh
# Prereqs: rustup with the toolchain pinned by PassPonyCore, cargo-ndk
# (cargo install cargo-ndk), an Android NDK (via Android Studio's SDK
# manager), perl and make on PATH (needed by pass-core's vendored OpenSSL
# build for mobile targets).

set -euo pipefail

CORE="${PASSPONY_CORE:-$HOME/Apps/PassPonyCore}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FEATURES="${PASS_FFI_FEATURES:-age-engine}"

[[ -d "$CORE/crates/pass-ffi" ]] || {
  echo "PassPonyCore not found at $CORE (set PASSPONY_CORE)"; exit 1; }

command -v cargo >/dev/null || {
  echo "cargo not found; install rustup first"; exit 1; }

command -v cargo-ndk >/dev/null || {
  echo "cargo-ndk not found; run: cargo install cargo-ndk"; exit 1; }

command -v perl >/dev/null || {
  echo "perl not found; required to cross-compile vendored OpenSSL for Android"; exit 1; }

command -v make >/dev/null || {
  echo "make not found; required to cross-compile vendored OpenSSL for Android"; exit 1; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_ROOT="$HOME/Library/Android/sdk/ndk"
  [[ -d "$NDK_ROOT" ]] || {
    echo "No NDK found under $NDK_ROOT and ANDROID_NDK_HOME is unset; install one via Android Studio's SDK manager"
    exit 1
  }
  ANDROID_NDK_HOME="$NDK_ROOT/$(ls "$NDK_ROOT" | sort -V | tail -n1)"
  export ANDROID_NDK_HOME
fi

pushd "$CORE" >/dev/null

# Run inside the core dir so rustup targets the toolchain pinned by its
# rust-toolchain.toml, not whatever default toolchain the shell has.
rustup target add aarch64-linux-android x86_64-linux-android

echo "Cross-compiling pass-ffi (features: $FEATURES) for arm64-v8a and x86_64"
cargo ndk --platform 26 -t arm64-v8a -t x86_64 -o "$REPO/core/src/main/jniLibs" \
  build --release -p pass-ffi --features "$FEATURES"

# uniffi-bindgen reads its metadata from special sections in the compiled
# library. PassPonyCore's release profile sets strip = "symbols", which
# removes those sections; the host library used for binding generation
# has to be a debug build even though the on-device .so above is release.
echo "Building host library for bindgen (debug profile; release strips the metadata bindgen reads)"
cargo build -p pass-ffi --features "$FEATURES"

# crate-type = cdylib names its output per host platform: .dylib on macOS,
# .so on Linux. Check both rather than assuming the CI (Linux) extension.
HOST_LIB="target/debug/libpass_ffi.dylib"
[[ -f "$HOST_LIB" ]] || HOST_LIB="target/debug/libpass_ffi.so"
[[ -f "$HOST_LIB" ]] || {
  echo "Host library not found at target/debug/libpass_ffi.{dylib,so}"; exit 1; }

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
