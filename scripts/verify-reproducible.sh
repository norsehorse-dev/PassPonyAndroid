#!/usr/bin/env bash
# Reproducible-build self-check: builds the FOSS release APK twice, in two
# separate clean clones of this repo, and diffs the results byte-for-byte.
# Used by .github/workflows/ci.yml's weekly verify-reproducible job, and can
# be run locally the same way. See docs/REPRODUCIBLE.md for the full recipe,
# why each of the determinism fixes it depends on exists (RUSTFLAGS
# remap-path-prefix, pinned NDK/AGP/Kotlin, vcsInfo/dependenciesInfo off),
# and how to additionally compare a CI-produced APK against a build on a
# second physical machine -- the real cross-machine test this script alone
# does not perform, since it always runs both builds on one machine.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$REPO/build-verify"
CORE_SHA="${PASSPONY_CORE_SHA:?PASSPONY_CORE_SHA must be set (see .github/workflows/ci.yml)}"

rm -rf "$WORK"
mkdir -p "$WORK"

echo "Cloning PassPonyCore @ $CORE_SHA (once, read-only, reused by both builds)..."
git clone --quiet https://github.com/norsehorse-dev/PassPonyCore.git "$WORK/core-src"
git -C "$WORK/core-src" checkout --quiet "$CORE_SHA"

build_once() {
  local label="$1" outdir="$2"
  echo "--- Build $label ---"
  rm -rf "$outdir"
  git clone --quiet "$REPO" "$outdir"
  git -C "$outdir" submodule update --init --recursive --quiet
  PASSPONY_CORE="$WORK/core-src" bash "$outdir/scripts/build-core.sh"
  (cd "$outdir" && ./gradlew --quiet assembleFossRelease)
}

build_once "A" "$WORK/a"
build_once "B" "$WORK/b"

APK_A="$(find "$WORK/a/app/build/outputs/apk/foss/release" -name '*.apk' | head -n1)"
APK_B="$(find "$WORK/b/app/build/outputs/apk/foss/release" -name '*.apk' | head -n1)"

{
  echo "Build A: $APK_A"
  echo "Build B: $APK_B"
  echo "sha256 A: $(sha256sum "$APK_A")"
  echo "sha256 B: $(sha256sum "$APK_B")"
} | tee "$WORK/report.txt"

if ! cmp -s "$APK_A" "$APK_B"; then
  {
    echo "APKs differ -- unpacking both for a content diff..."
  } | tee -a "$WORK/report.txt"
  rm -rf "$WORK/unpack-a" "$WORK/unpack-b"
  mkdir -p "$WORK/unpack-a" "$WORK/unpack-b"
  unzip -q "$APK_A" -d "$WORK/unpack-a"
  unzip -q "$APK_B" -d "$WORK/unpack-b"
  diff -rq "$WORK/unpack-a" "$WORK/unpack-b" | tee -a "$WORK/report.txt" || true
  echo "Reproducible build check FAILED: APKs are not byte-identical." | tee -a "$WORK/report.txt"
  exit 1
fi

echo "Reproducible build check PASSED: APKs are byte-identical." | tee -a "$WORK/report.txt"
