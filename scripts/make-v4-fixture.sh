#!/usr/bin/env bash
# Generates app/src/androidTest/assets/pass-v4-minimal: an Ed25519
# (cert/sign) + Cv25519 (encrypt subkey) v4 pass store via real gpg.
#
# Not sourced from the PassPonyCore corpus (fixtures/pass/minimal)
# despite the name match: that corpus's alpha/beta/gamma.gpg turned out
# to be encoded with an old pre-RFC9580 draft AEAD packet (tag 20, "gpg
# --list-packets" shows "aead encrypted packet"), not the standard SEIPD
# (tag 18) format — even this machine's own gpg 2.2.27 cannot fully
# parse it ("invalid packet", "unknown version" on re-parse), and
# PGPonyCore-Kotlin correctly rejects it as lacking recognized integrity
# protection. Whatever produced that corpus fixture had non-default AEAD
# settings; this script uses gpg's plain defaults, which verified above
# to produce ordinary tag=18 SEIPDv1 output.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="app/src/androidTest/assets/pass-v4-minimal"
GNUPGHOME_DIR="$(mktemp -d)"
trap 'rm -rf "$GNUPGHOME_DIR"' EXIT
export GNUPGHOME="$GNUPGHOME_DIR"
chmod 700 "$GNUPGHOME"

gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
    --quick-generate-key "PassPony V4 Fixture <v4@passpony.test>" ed25519 cert never

FPR="$(gpg --list-keys --with-colons v4@passpony.test | awk -F: '/^fpr:/{print $10; exit}')"

gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
    --quick-add-key "$FPR" cv25519 encr never

mkdir -p "$OUT/store" "$OUT/goldens"
printf '%s' "$FPR" > "$OUT/store/gpg-id"
gpg --batch --pinentry-mode loopback --passphrase '' \
    --export-secret-keys --armor "$FPR" > "$OUT/identity.asc"

for name in alpha beta gamma; do
  plain="v4-fixture-$name-password"
  printf '%s' "$plain" > "$OUT/goldens/${name}.plain"
  printf '%s' "$plain" | gpg --batch --yes --trust-model always \
      --encrypt --recipient "$FPR" --output "$OUT/store/${name}.gpg"
done

echo "v4 fixture written to $OUT (fingerprint $FPR)"
gpg --list-packets "$OUT/store/alpha.gpg" | grep -E "tag=|mdc_method" || true
