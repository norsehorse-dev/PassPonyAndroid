# Canonical Linux release-build environment for PassPony (Android). See
# docs/RELEASE_CHECKLIST.md step 3 and docs/REPRODUCIBLE.md for why this
# exists: a Mac-built release and a Linux-built release of the identical
# commit turned out NOT to be byte-identical. The NDK's darwin-x86_64 and
# linux-x86_64 clang prebuilts for the same NDK version pass different
# default assembler flags (-Wa,--noexecstack -Qunused-arguments) into
# pass-core's vendored OpenSSL build, which changes actual codegen, not
# just an embedded path string, so --remap-path-prefix and
# SOURCE_DATE_EPOCH (which fixed the two earlier gaps) don't touch it.
# F-Droid's own buildserver, like .github/workflows/reproducible.yml,
# always builds on Linux, so a Mac-built release can never byte-match it.
# This image closes the gap by never opening it: every release build now
# runs on the same host class CI and F-Droid use, regardless of which
# machine `docker run` happens on.
#
# Mirrors .github/workflows/reproducible.yml's ubuntu-24.04 toolchain
# setup as closely as possible -- same NDK version (read from
# gradle.properties at image build time isn't practical here, so this is
# pinned directly; keep it in sync with gradle.properties' ndkVersion and
# docs/REPRODUCIBLE.md's pinned-inputs table by hand), same
# ANDROID_SDK_ROOT path, same Rust target setup.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/usr/local/lib/android/sdk
ENV ANDROID_SDK_ROOT=/usr/local/lib/android/sdk
ENV NDK_VERSION=27.2.12479018
ENV ANDROID_NDK_HOME=${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}
ENV CARGO_HOME=/root/.cargo
ENV PATH=${CARGO_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl unzip git perl make python3 ca-certificates openjdk-17-jdk-headless \
      build-essential \
    && rm -rf /var/lib/apt/lists/*

# stable, not none: `cargo install cargo-ndk` below needs an active
# toolchain to build itself with. The actual PassPonyCore build later
# (inside release-entrypoint.sh, via scripts/build-core.sh) runs from
# inside the PassPonyCore checkout, where rust-toolchain.toml overrides
# this and rustup auto-installs the pinned version (1.95.0 as of this
# writing) on first use -- this "stable" toolchain is only for cargo-ndk
# itself and whatever `rustup target add` below applies to.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
RUN rustup target add aarch64-linux-android x86_64-linux-android
RUN cargo install cargo-ndk --locked

# Build number in this URL is current as of 2026-08-10 per
# https://developer.android.com/studio#command-tools -- if this curl
# starts 404ing, that page has the current commandlinetools-linux-*.zip
# filename, swap the build number below (nothing else changes; sdkmanager
# itself handles all further installs).
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && curl -sL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "ndk;${NDK_VERSION}"

COPY release-entrypoint.sh /usr/local/bin/release-entrypoint.sh
RUN chmod +x /usr/local/bin/release-entrypoint.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/release-entrypoint.sh"]
