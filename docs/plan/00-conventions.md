# 00. Conventions (read before every packet)

These rules bind every session working in this repo or in PassPonyCore
on this project's behalf. They are not preferences; violating them has
caused real damage before.

## Git identity and attribution

- Immediately after `git init` (and before the first commit), set the
  repo-local identity:

```
git config user.name "norsehorse-dev"
git config user.email "norsehorse-dev@users.noreply.github.com"
```

- NEVER commit as Kevin's personal account. NEVER add Co-Authored-By,
  "Generated with", session links, or any other AI attribution to commit
  messages, PR bodies, tags, code comments, release notes, or docs. If a
  wrong identity or AI credit reaches a commit, fix it before any push.
- Commit messages: imperative, concise, no trailers.

## Writing style

- No em dashes and no en dashes in anything written for this project:
  code comments, docs, strings, commit messages, storefront texts. Use a
  comma, a colon, parentheses, or two sentences.
- Command blocks handed to Kevin contain only runnable lines. No comment
  lines inside the block; explanation goes in prose around it.

## Repo hygiene

- `.gitignore` from commit zero includes: `local.properties`, `*.keystore`,
  `keystore.properties`, `.DS_Store`, `build/`, `.gradle/`, `.kotlin/`,
  `.idea/`, `core/src/main/jniLibs/`, `core/src/main/kotlin/uniffi/`.
  Generated bindings and compiled `.so` files are build products; the
  sources of truth are PassPonyCore and `scripts/build-core.sh`.
- Never commit secrets, tokens, or real credentials. Demo data uses
  obviously fake values (see the iOS demo seed for tone).
- License: Apache-2.0, same LICENSE text as the iOS repo.

## Code conventions

- Kotlin, Jetpack Compose, Material 3. One activity (MainActivity),
  Compose navigation. ViewModels own state; composables stay dumb.
- Package root `com.passpony.android`. Suggested subpackages: `store`
  (model + engine seam), `crypto` (engines), `ui/<screen>`, `autofill`,
  `i18n`, `sync`.
- Follow PGPonyAndroid patterns where they exist (theme, LanguageManager,
  BiometricGate, ClipboardService are all prior art worth reading at
  `~/Apps/PGPonyAndroid`), but do not copy its monoliths: keep files
  under roughly 500 lines.
- Every FFI call that can throw maps errors through one user-facing
  message helper (the iOS `ErrorMessages.swift` port). Never surface raw
  exception text with entry names or paths in UI toasts.
- Threat model commitments (PassPonyCore THREAT_MODEL.md) are binding:
  no decrypted content at rest, no plaintext or entry names in logs,
  clipboard auto-clear, FLAG_SECURE on reveal and passphrase screens,
  panic lock clears cached secrets, no telemetry.

## Definition of done for every packet

- The exit criteria in the packet pass, demonstrated (test output or
  emulator screenshot as appropriate).
- `./gradlew assembleDebug` and `./gradlew test` are green.
- No em dashes introduced anywhere (grep before finishing):

```
LC_ALL=C grep -rnE $'\xe2\x80\x94|\xe2\x80\x93' --include=*.kt --include=*.md --include=*.xml app core docs scripts
```

- Working tree committed with a clean, attribution-free message; nothing
  pushed unless Kevin says so.
- If the packet changed PassPonyCore: its own tests green (`cargo test`),
  same identity and attribution rules apply there.

## Environment facts

- Kevin's machine: Apple Silicon Mac. Android Studio, rustup, and the
  Android NDK are assumed present; `scripts/build-core.sh` checks and
  says exactly what is missing rather than half-failing.
- PassPonyCore checkout is a sibling at `~/Apps/PassPonyCore` (override
  with `PASSPONY_CORE`), same convention as the iOS repo.
- Rust toolchain is pinned by PassPonyCore's rust-toolchain.toml
  (currently 1.95.0); the build script runs cargo from inside that
  directory so the pin applies.
