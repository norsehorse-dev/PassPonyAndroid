#!/usr/bin/env bash
# Generates app/src/androidTest/assets/pass-v6-minimal and pass-v6-locked
# (an RFC 9580 X25519/Ed25519 v6 pass store, unprotected and
# passphrase-protected). See GenerateV6FixtureTest's doc comment for why
# this goes through PGPonyCore-Kotlin's own key generation rather than an
# external tool: the GnuPG on this toolchain (2.2.27) predates v6 support
# and sequoia-sq is not installed. Run once; the output is committed, not
# regenerated on every build.
set -euo pipefail
cd "$(dirname "$0")/.."
GENERATE_FIXTURES=1 ./gradlew :app:testFossDebugUnitTest \
  --tests "com.passpony.android.crypto.fixtures.GenerateV6FixtureTest" "$@"
echo "v6 fixtures written to app/src/androidTest/assets/pass-v6-minimal and pass-v6-locked"
