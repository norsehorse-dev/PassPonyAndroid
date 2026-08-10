#!/usr/bin/env bash
# Runs inside the release.Dockerfile image. Not meant to be run directly
# on a host -- see docker/README.md for the docker run invocation.
set -euo pipefail

VERSION="${1:?usage: release-entrypoint.sh <version> <ref>, e.g. 1.0.0 v1.0.0}"
REF="${2:?usage: release-entrypoint.sh <version> <ref>, e.g. 1.0.0 v1.0.0}"

# REF can be a tag, a branch, or a raw commit SHA -- RELEASE_CHECKLIST.md
# step 3 builds the dry-run release from the just-pushed commit, before
# step 5 creates the tag, so this can't assume a tag already exists.

[[ -f /keystore/release.keystore ]] || {
  echo "Expected the release keystore bind-mounted read-only at /keystore/release.keystore" >&2
  exit 1
}
[[ -f /keystore-props/keystore.properties ]] || {
  echo "Expected keystore.properties bind-mounted read-only at /keystore-props/keystore.properties" >&2
  exit 1
}

echo "--- Cloning PassPonyAndroid at $REF ---"
git clone https://github.com/norsehorse-dev/PassPonyAndroid.git /work/repo
cd /work/repo
git checkout "$REF"
git submodule update --init --recursive

# keystore.properties as it exists on the host points storeFile at a host
# path (e.g. /Users/kevinstewart/Keys/PassPony/release.keystore) that
# doesn't exist inside this container. Rewrite just that one line to the
# container-internal mount point; storePassword/keyAlias/keyPassword pass
# through untouched, exactly as they are on the host, never typed or
# generated in here.
sed 's#^storeFile=.*#storeFile=/keystore/release.keystore#' \
  /keystore-props/keystore.properties > keystore.properties

echo "--- Running scripts/release.sh $VERSION ---"
bash scripts/release.sh "$VERSION"

echo "--- Copying results to /out ---"
cp -r "release-$VERSION" /out/
echo "Done. Results at release-$VERSION/ on the host."
