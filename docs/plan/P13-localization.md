# P13. Localization: xcstrings converter and seven languages

Objective: every user-facing string externalized to resources, the iOS
string corpus converted mechanically to strings.xml for all seven
languages, and the in-app language switcher working on all supported
API levels.

Requires: P03 onward merged (the more UI exists, the fewer keys added
later); can run any time after P03.

## Context

- Source of truth: `~/Apps/PassPony/Sources/Shared/Localizable.xcstrings`
  (JSON; en, es, fr, de, zh-Hans, pt-BR, ru) plus
  `InfoPlist.xcstrings` for the display-name strings if reused.
- iOS language machinery: `Sources/Shared/AppLanguage.swift` (override
  stored, "" follows system, applied live). Android equivalent:
  AppCompatDelegate.setApplicationLocales, which persists per-app
  locale itself on API 26 to 32 and delegates to the platform on 33+.
  PGPonyAndroid `i18n/LanguageManager.kt` is working prior art.
- Russian plurals: xcstrings variations map to Android quantities
  one/few/many/other directly.

## Work

1. `scripts/xcstrings_to_strings.py` (Python 3, stdlib only):
   - input: path to Localizable.xcstrings (default the sibling iOS
     checkout); output: `app/src/main/res/values[-locale]/strings.xml`
     for en (values/), es, fr, de, ru, `values-b+zh+Hans/`,
     `values-b+pt+BR/`.
   - key mapping: deterministic snake_case from the xcstrings key with
     a stable hash suffix on collision; emit a mapping report so
     Kotlin call sites can be written against the generated names.
   - value conversion: XML-escape, apostrophes escaped, `%@` and
     `%lld`-family to positional `%1$s` / `%1$d` in argument order,
     literal percent to `%%` when any format arg exists.
   - plurals: xcstrings plural variations become `<plurals>` items.
   - strings missing a translation fall back to omitting the key (the
     resource system then falls back to en); the script prints per-
     language coverage counts.
   - idempotent: same input, byte-identical output (sorted keys, fixed
     header comment naming the generator and source).
2. Run the converter, commit the generated resources (they are release
   inputs, unlike the FFI bindings; regeneration is a reviewable diff).
3. Sweep the app for hardcoded strings; replace with resource lookups
   using the mapping report. Compose usage via stringResource.
4. `i18n/LanguageManager.kt`: get/set current override, list of the
   seven locales with verbatim native names (copy the exact strings
   from the iOS picker), wired to the P10 settings picker.
   AndroidManifest gains the AppCompat locale-persistence service
   entry required below API 33, and `android:localeConfig` for 33+.
5. Tests: converter unit tests run by pytest or plain asserts under
   `scripts/tests/` (format-specifier conversion, plural mapping,
   escaping, idempotence); an androidTest asserting the Spanish
   resources load and a known key resolves.

## Exit criteria

- App runs fully in Spanish and Russian via the in-app switcher on an
  API 26 emulator image and an API 34+ image, no restart oddities.
- Converter reruns produce an empty git diff against committed
  resources.
- No hardcoded user-facing strings remain (lint check
  `HardcodedText` clean, exceptions annotated and justified).

## Out of scope

- New translations beyond the seven. Storefront texts (P16).
