#!/usr/bin/env bash
# Release build driver for PassPony (Android) -- P16
# (docs/plan/P16-release.md item 4). Builds a signed dry-run release from
# the current commit, on the machine holding the real release keystore.
# Stops BEFORE anything irreversible -- it never pushes a git tag or
# publishes a GitHub release -- and prints the exact commands for those
# steps at the end, for you to run once you've reviewed the output.
#
# Usage: scripts/release.sh <version>   (e.g. scripts/release.sh 1.0.0)
#
# Signing note (see docs/REPRODUCIBLE.md, R3 of NorseHorse's Reproducible
# Builds Playbook): this signs by running a real Gradle release build with
# keystore.properties present at the repo root -- never by running the
# standalone `apksigner` CLI against an already-built APK afterward.
# apksigner from build-tools 35+ rewrites ZIP alignment when it re-signs,
# which breaks byte-identity with what a from-source rebuild (F-Droid's,
# or tools/verify_repro.sh's) produces. Signing has to happen inside the
# one real Gradle invocation that builds the APK, not as a separate step
# glued on after -- this is a deliberate departure from P16's original
# wording ("apksigner-sign the foss APK"), written before P15 adopted the
# playbook and learned why that specific approach breaks reproducibility.
set -euo pipefail

VERSION="${1:?usage: scripts/release.sh <version>, e.g. scripts/release.sh 1.0.0}"
TAG="v$VERSION"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO_ROOT/release-$VERSION"

[[ -f "$REPO_ROOT/keystore.properties" ]] || {
  echo "keystore.properties not found at the repo root -- this must run on the machine holding the real release keystore, not a CI runner or a throwaway clone." >&2
  exit 1
}

if ! git -C "$REPO_ROOT" diff --quiet || ! git -C "$REPO_ROOT" diff --cached --quiet; then
  echo "Working tree has uncommitted changes -- commit or stash before releasing." >&2
  exit 1
fi

CURRENT_VERSION_NAME="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$REPO_ROOT/app/build.gradle.kts")"
[[ "$CURRENT_VERSION_NAME" == "$VERSION" ]] || {
  echo "app/build.gradle.kts's versionName is \"$CURRENT_VERSION_NAME\", not \"$VERSION\" -- bump versionName and versionCode first (see docs/RELEASE_CHECKLIST.md), commit that, then rerun." >&2
  exit 1
}

echo "--- Building foss release APK ---"
( cd "$REPO_ROOT" && bash scripts/build-core.sh && ./gradlew --no-daemon :app:assembleFossRelease )

echo "--- Building play release AAB ---"
( cd "$REPO_ROOT" && ./gradlew --no-daemon :app:bundlePlayRelease )

FOSS_APK="$REPO_ROOT/app/build/outputs/apk/foss/release/app-foss-release.apk"
PLAY_AAB="$REPO_ROOT/app/build/outputs/bundle/playRelease/app-play-release.aab"
[[ -f "$FOSS_APK" ]] || { echo "Expected foss APK not found at $FOSS_APK" >&2; exit 1; }
[[ -f "$PLAY_AAB" ]] || { echo "Expected play AAB not found at $PLAY_AAB" >&2; exit 1; }

mkdir -p "$OUT"
DEST_APK="$OUT/PassPonyAndroid-$VERSION-foss.apk"
DEST_AAB="$OUT/PassPonyAndroid-$VERSION-play.aab"
cp "$FOSS_APK" "$DEST_APK"
cp "$PLAY_AAB" "$DEST_AAB"

echo "--- Checksums ---"
( cd "$OUT" && shasum -a 256 "$(basename "$DEST_APK")" "$(basename "$DEST_AAB")" | tee "PassPonyAndroid-$VERSION-SHA256SUMS.txt" )

echo "--- Content hash (see docs/REPRODUCIBLE.md) ---"
CONTENT_HASH="$(bash "$REPO_ROOT/tools/verify_repro.sh" content-hash "$DEST_APK")"
echo "$CONTENT_HASH"

NOTES="$OUT/RELEASE_NOTES-$VERSION.md"
if [[ -f "$REPO_ROOT/RELEASE_NOTES.template.md" ]]; then
  sed -e "s/{{VERSION}}/$VERSION/g" -e "s/{{TAG}}/$TAG/g" -e "s/{{CONTENT_HASH}}/$CONTENT_HASH/g" \
    "$REPO_ROOT/RELEASE_NOTES.template.md" > "$NOTES"
else
  echo "No RELEASE_NOTES.template.md at the repo root -- writing a minimal stub instead." >&2
  {
    echo "# PassPony (Android) $VERSION"
    echo
    echo "<!-- Fill in: what changed since the last release. Honest changelog, no marketing fluff. -->"
    echo
    echo "## Verification"
    echo
    echo "- Content hash: \`$CONTENT_HASH\`"
    echo "- SHA-256 checksums: see \`PassPonyAndroid-$VERSION-SHA256SUMS.txt\`"
    echo "- Reproduce this build yourself: \`tools/verify_repro.sh rebuild $TAG PassPonyAndroid-$VERSION-foss.apk\`"
  } > "$NOTES"
fi

echo
echo "Built: $OUT"
ls -la "$OUT"
echo
echo "Nothing has been tagged, pushed, or published. Once you've filled in $NOTES and gone through docs/RELEASE_CHECKLIST.md's device matrix, the remaining steps are yours to run:"
echo
echo "  git tag -a $TAG -m \"PassPony (Android) $VERSION\""
echo "  git push origin $TAG"
echo "  gh release create $TAG \"$DEST_APK\" \"$DEST_AAB\" \"$OUT/PassPonyAndroid-$VERSION-SHA256SUMS.txt\" --title \"$VERSION\" --notes-file \"$NOTES\""
