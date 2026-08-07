#!/usr/bin/env bash
# Generates app/src/androidTest/assets/pass-edit-minimal: a single-entry
# v4 Cv25519 pass store whose entry combines odd-formatting properties
# drawn from PassPonyCore's own corpus and core test suite (no single
# fixture there has all of them at once): a field with no space after
# the colon ("username:kevin" -- entry.rs's set_field is documented to
# preserve that exact spacing convention on edit), a non-field plain
# line, a blank line, a line with trailing spaces, a non-field
# otpauth:// line, and a final field line with NO trailing newline.
#
# Generated fresh with gpg's plain defaults rather than reusing the
# corpus's own gamma.gpg because (like pass-v4-minimal, see
# make-v4-fixture.sh) the corpus's .gpg files are encoded with an
# obsolete pre-RFC9580 draft AEAD packet PGPonyCore-Kotlin can't
# decrypt; this produces standard SEIPD/tag=18 output, verified below.
#
# Exists for PgpEditByteFidelityTest: edit the "username" field via
# entrySetField and assert every other byte -- the plain line, the
# blank line, the trailing spaces, the otpauth line, and the missing
# final newline on the last field -- survives untouched.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="app/src/androidTest/assets/pass-edit-minimal"
GNUPGHOME_DIR="$(mktemp -d)"
trap 'rm -rf "$GNUPGHOME_DIR"' EXIT
export GNUPGHOME="$GNUPGHOME_DIR"
chmod 700 "$GNUPGHOME"

gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
    --quick-generate-key "PassPony Edit Fixture <edit@passpony.test>" ed25519 cert never

FPR="$(gpg --list-keys --with-colons edit@passpony.test | awk -F: '/^fpr:/{print $10; exit}')"

gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
    --quick-add-key "$FPR" cv25519 encr never

mkdir -p "$OUT/store" "$OUT/goldens"
printf '%s\n' "$FPR" > "$OUT/store/gpg-id"
gpg --batch --pinentry-mode loopback --passphrase '' \
    --export-secret-keys --armor "$FPR" > "$OUT/identity.asc"

printf 'gamma-pw\nusername:kevin\nline two\n\nline four with trailing spaces  \notpauth://totp/Example:kevin?secret=JBSWY3DPEHPK3PXP&issuer=Example\nurl:example.com' \
    > "$OUT/goldens/gamma.plain"
gpg --batch --yes --trust-model always --encrypt --recipient "$FPR" \
    --output "$OUT/store/gamma.gpg" "$OUT/goldens/gamma.plain"

echo "edit fixture written to $OUT (fingerprint $FPR)"
gpg --list-packets "$OUT/store/gamma.gpg" | grep -E "tag=|mdc_method" || true
