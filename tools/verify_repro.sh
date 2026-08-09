#!/usr/bin/env bash
# Reproducible-build gate for PassPonyAndroid, per NorseHorse's Reproducible
# Builds Playbook (docs/REPRODUCIBLE.md links the full playbook). Never
# extracts an APK to the filesystem to compare it (R4 in the playbook):
# AGP's shortened resource names collide case-insensitively (e.g.
# res/IN.xml vs res/In.xml), and extracting on a case-insensitive
# filesystem -- macOS included -- silently drops one of each pair, which
# corrupts any filesystem-based diff without you noticing. Every
# comparison here reads both ZIPs' entries directly via Python's zipfile
# module, in memory, and hashes them.
#
# Usage:
#   tools/verify_repro.sh rebuild <tag> [candidate.apk]
#   tools/verify_repro.sh compare <a.apk> <b.apk>
#   tools/verify_repro.sh content-hash <apk>
#
# rebuild:  clones <tag> twice into isolated roots (separate
#           GRADLE_USER_HOME, --no-daemon), builds each with $GRADLE_TASK,
#           fails unless the two builds are content-identical, then fails
#           unless the optional candidate APK also matches them. Prints
#           per-dex SHA-256s and any embedded R8 marker. Requires network
#           (clones the repo and, if PASSPONY_CORE_SHA is set, PassPonyCore
#           too) and an Android SDK/NDK matching gradle.properties'
#           ndkVersion. Picks up a JDK 17 for both isolated builds from
#           $JAVA_HOME, or failing that from org.gradle.java.home in your
#           real gradle.properties (see resolve_java_home below) -- set
#           JAVA_HOME yourself first if neither is already configured for
#           JDK 17.
# compare:  content comparison of two APKs via a per-entry SHA-256
#           manifest, excluding only the signature files
#           (META-INF/*.SF|*.RSA|*.DSA|*.EC|MANIFEST.MF). Prints IDENTICAL
#           or a per-file diff, and exits nonzero on any difference.
# content-hash: SHA-256 of the sorted per-entry manifest (same exclusions)
#           -- the "content hash" to publish in release notes. Unlike a
#           whole-file hash it does not change with the signature, so
#           anyone rebuilding from source can reproduce it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_URL="${REPO_URL:-$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)}"
GRADLE_TASK="${GRADLE_TASK:-:app:assembleFossRelease}"
APK_REL_PATH="${APK_REL_PATH:-app/build/outputs/apk/foss/release/app-foss-release.apk}"
# Only meaningful for rebuild: if set, PassPonyCore is cloned fresh and
# pinned to this commit and shared read-only between both builds, matching
# how CI pins it (see .github/workflows/ci.yml). If unset, each clone's own
# scripts/build-core.sh falls back to its own default ($HOME/Apps/PassPonyCore),
# which is the right thing for an ad hoc local run against whatever
# PassPonyCore checkout is already on the machine.
PASSPONY_CORE_SHA="${PASSPONY_CORE_SHA:-}"

# Excludes exactly the files the signing step touches -- everything else is
# covered by F-Droid's own verification (see docs/REPRODUCIBLE.md, "How
# F-Droid's verification actually works").
SIG_FILE_RE='^META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$'

resolve_java_home() {
  # rebuild deliberately points each clone at its own throwaway
  # GRADLE_USER_HOME (isolation between the two builds, and from whatever
  # is cached on this machine) -- but that also hides any org.gradle.java.
  # home a developer has set in their *real*
  # $GRADLE_USER_HOME/gradle.properties (commonly how a machine whose
  # default `java` isn't 17, e.g. one where Android Studio's own bundled
  # JBR is newer, points Gradle at a JDK 17 install). Without it, Gradle's
  # toolchain resolution can fail outright on a machine that in fact has a
  # JDK 17 available, just not somewhere auto-detection finds it, with
  # toolchain auto-download off (no foojay-resolver-convention configured
  # here). JAVA_HOME, if the caller already has it exported, wins outright;
  # otherwise this reads the real global gradle.properties once, before it
  # gets shadowed below. Prints nothing if neither is set, which leaves
  # Gradle's own auto-detection exactly as before this existed.
  if [[ -n "${JAVA_HOME:-}" ]]; then
    printf '%s' "$JAVA_HOME"
    return
  fi
  local props="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
  if [[ -f "$props" ]]; then
    sed -n 's/^org\.gradle\.java\.home=//p' "$props" | tail -n1
  fi
}

manifest_py() {
  # $1 = apk path. Prints "name\tsha256", one per non-signature entry,
  # sorted by name. Never writes entry contents to disk.
  python3 - "$1" "$SIG_FILE_RE" <<'PY'
import sys, zipfile, hashlib, re
apk, sig_re = sys.argv[1], re.compile(sys.argv[2])
with zipfile.ZipFile(apk) as z:
    rows = []
    for info in z.infolist():
        if info.is_dir() or sig_re.match(info.filename):
            continue
        rows.append((info.filename, hashlib.sha256(z.read(info.filename)).hexdigest()))
rows.sort()
for name, digest in rows:
    print(f"{name}\t{digest}")
PY
}

content_hash() {
  manifest_py "$1" | python3 -c "import sys, hashlib; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())"
}

print_dex_info() {
  # $1 = apk path, $2 = label for the printout (e.g. "A" or "candidate").
  python3 - "$1" "$2" <<'PY'
import sys, zipfile, hashlib, re
apk, label = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    for name in sorted(n for n in z.namelist() if re.match(r'^classes\d*\.dex$', n)):
        data = z.read(name)
        digest = hashlib.sha256(data).hexdigest()
        m = re.search(rb'~~R8\{[^}]*\}', data)
        marker = m.group(0).decode('utf-8', 'replace') if m else '(no R8 marker found)'
        print(f"  {label} {name}: sha256={digest}")
        print(f"    {marker}")
PY
}

cmd_content_hash() {
  local apk="${1:?usage: verify_repro.sh content-hash <apk>}"
  content_hash "$apk"
}

cmd_compare() {
  local a="${1:?usage: verify_repro.sh compare <a.apk> <b.apk>}"
  local b="${2:?usage: verify_repro.sh compare <a.apk> <b.apk>}"
  python3 - "$a" "$b" "$SIG_FILE_RE" <<'PY'
import sys, zipfile, hashlib, re
a_path, b_path, sig_re = sys.argv[1], sys.argv[2], re.compile(sys.argv[3])

def manifest(path):
    with zipfile.ZipFile(path) as z:
        out = {}
        for info in z.infolist():
            if info.is_dir() or sig_re.match(info.filename):
                continue
            out[info.filename] = hashlib.sha256(z.read(info.filename)).hexdigest()
        return out

ma, mb = manifest(a_path), manifest(b_path)
only_a = sorted(set(ma) - set(mb))
only_b = sorted(set(mb) - set(ma))
diff = sorted(n for n in (set(ma) & set(mb)) if ma[n] != mb[n])

if not only_a and not only_b and not diff:
    print("IDENTICAL")
    sys.exit(0)

print("DIFFERS")
for n in only_a:
    print(f"  only in A: {n}")
for n in only_b:
    print(f"  only in B: {n}")
for n in diff:
    print(f"  differs:   {n}")
sys.exit(1)
PY
}

cmd_rebuild() {
  local tag="${1:?usage: verify_repro.sh rebuild <tag> [candidate.apk]}"
  local candidate="${2:-}"
  [[ -n "$REPO_URL" ]] || { echo "REPO_URL not set and no 'origin' remote found" >&2; exit 1; }

  local work
  work="$(mktemp -d "${TMPDIR:-/tmp}/verify-repro.XXXXXX")"
  echo "Work directory: $work"

  local core=""
  if [[ -n "$PASSPONY_CORE_SHA" ]]; then
    echo "--- Cloning PassPonyCore @ $PASSPONY_CORE_SHA (shared, read-only) ---"
    git clone --quiet https://github.com/norsehorse-dev/PassPonyCore.git "$work/passpony-core"
    git -C "$work/passpony-core" checkout --quiet "$PASSPONY_CORE_SHA"
    core="$work/passpony-core"
  fi

  local java_home
  java_home="$(resolve_java_home)"
  if [[ -n "$java_home" ]]; then
    echo "Using org.gradle.java.home=$java_home for both builds"
  fi

  local root
  for root in srcA srcB; do
    echo "--- Cloning $tag into $root ---"
    git clone --quiet --branch "$tag" --depth 1 "$REPO_URL" "$work/$root"
    git -C "$work/$root" submodule update --init --recursive --quiet
  done

  for root in srcA srcB; do
    echo "--- Building $root ($GRADLE_TASK) ---"
    if [[ -f "$work/$root/scripts/build-core.sh" ]]; then
      if [[ -n "$core" ]]; then
        PASSPONY_CORE="$core" bash "$work/$root/scripts/build-core.sh"
      else
        bash "$work/$root/scripts/build-core.sh"
      fi
    fi
    local gradle_opts=()
    [[ -n "$java_home" ]] && gradle_opts+=("-Dorg.gradle.java.home=$java_home")
    ( cd "$work/$root" && env GRADLE_USER_HOME="$work/gradle-$root" ./gradlew --no-daemon "${gradle_opts[@]}" "$GRADLE_TASK" )
  done

  local apk_a="$work/srcA/$APK_REL_PATH"
  local apk_b="$work/srcB/$APK_REL_PATH"
  [[ -f "$apk_a" ]] || { echo "Build A did not produce $APK_REL_PATH" >&2; exit 1; }
  [[ -f "$apk_b" ]] || { echo "Build B did not produce $APK_REL_PATH" >&2; exit 1; }

  echo "--- Dex info ---"
  print_dex_info "$apk_a" "A"
  print_dex_info "$apk_b" "B"

  echo "--- Comparing A vs B ---"
  if cmd_compare "$apk_a" "$apk_b"; then
    echo "Two clean builds of $tag are content-identical."
  else
    echo "Two clean builds of $tag DIFFER -- not reproducible." >&2
    exit 1
  fi

  local hash
  hash="$(content_hash "$apk_a")"
  echo "Content hash: $hash"
  echo "buildA APK: $apk_a"

  if [[ -n "$candidate" ]]; then
    echo "--- Comparing candidate ($candidate) vs buildA ---"
    if cmd_compare "$candidate" "$apk_a"; then
      echo "Candidate matches the clean-clone build."
    else
      echo "Candidate DOES NOT match the clean-clone build." >&2
      exit 1
    fi
  fi
}

main() {
  local sub="${1:-}"
  [[ $# -gt 0 ]] && shift
  case "$sub" in
    rebuild) cmd_rebuild "$@" ;;
    compare) cmd_compare "$@" ;;
    content-hash) cmd_content_hash "$@" ;;
    *)
      echo "usage: $(basename "$0") rebuild <tag> [candidate.apk] | compare <a.apk> <b.apk> | content-hash <apk>" >&2
      exit 1
      ;;
  esac
}

main "$@"
