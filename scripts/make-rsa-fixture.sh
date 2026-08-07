#!/usr/bin/env bash
# Generates app/src/androidTest/assets/pass-rsa-minimal: an RSA-2048 pass
# store PGPonyCore-Kotlin decrypts in software, the one capability Android
# has that iOS's hand-rolled Cv25519/X25519-only parser does not (RSA
# needs the smartcard path there). Uses a scratch GNUPGHOME so it never
# touches the real gpg keyring, mirroring PassPonyCore's own
# fixtures/gen-fixtures.sh conventions: batch mode, loopback pinentry, an
# empty passphrase, fake test-only credentials. Run once; the output is
# committed, not regenerated on every build.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="app/src/androidTest/assets/pass-rsa-minimal"
GNUPGHOME_DIR="$(mktemp -d)"
trap 'rm -rf "$GNUPGHOME_DIR"' EXIT
export GNUPGHOME="$GNUPGHOME_DIR"
chmod 700 "$GNUPGHOME"

gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
    --quick-generate-key "PassPony RSA Fixture <rsa@passpony.test>" rsa2048 encrypt never

FPR="$(gpg --list-keys --with-colons rsa@passpony.test | awk -F: '/^fpr:/{print $10; exit}')"

mkdir -p "$OUT/store" "$OUT/goldens"
printf '%s' "$FPR" > "$OUT/store/gpg-id"
gpg --batch --pinentry-mode loopback --passphrase '' \
    --export-secret-keys --armor "$FPR" > "$OUT/identity.asc"

for name in alpha beta gamma; do
  plain="rsa-fixture-$name-password"
  printf '%s' "$plain" > "$OUT/goldens/${name}.plain"
  printf '%s' "$plain" | gpg --batch --yes --trust-model always \
      --encrypt --recipient "$FPR" --output "$OUT/store/${name}.gpg"
done

echo "RSA fixture written to $OUT (fingerprint $FPR)"
