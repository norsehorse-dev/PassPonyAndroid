# P08. Add, edit, move, delete: byte-faithful saves and commit parity

Objective: full entry lifecycle matching iOS: add with generator, edit
via the byte-faithful codec helpers, move/rename, delete, each with the
pass-CLI commit message through the git engine when a repo exists.

Requires: P07. Uses P05/P06 engines.

## Context (iOS files to mirror)

- `Sources/App/AddEntryView.swift`: name, password + Generate (25
  chars, pass default charset), optional username and url fields;
  content assembled as `password\n` + `username: ...\n` + `url: ...\n`.
- `Sources/App/EditEntryView.swift` (and MoveEntryView in the same
  file): field-level editing over `entrySetField` / `entrySetPassword`
  only; unknown lines, ordering, spacing, trailing-newline style must
  survive untouched. Removing a field is deliberately absent (codec has
  no delete-with-fidelity primitive yet); do not fake it with line
  filtering.
- `Sources/App/AppModel.swift`: save/move/delete each commit through
  `GitSync.commitPaths` with `commitMessageAdd/Edit/Remove/Rename` and
  the format-appropriate extension (.age or .gpg). Commits are
  best-effort (`try?`): a store without git never blocks the save.
- Core move semantics: passage always re-encrypts the moved entry; pass
  re-encrypts only when the resolved key set differs. The core does
  this; the app just calls `moveEntry`.

## Work

1. `ui/edit/AddEntryScreen.kt`: port the form and the generator
   (SecureRandom, same 94-char charset, length 25). Save via
   ViewModel.saveEntry(isNew = true), then navigate back and refresh.
2. `ui/edit/EditEntryScreen.kt`: initial values from `entryFields` and
   `entryPassword`; on save apply only changed values through
   `entrySetField`/`entrySetPassword` in sequence, then one
   `writeEntry` with the final bytes. Adding a new field appends via
   `entrySetField`. No field deletion UI.
3. `ui/edit/MoveEntryScreen.kt`: single name field prefilled, calls
   moveEntry; on success pop the (now stale) detail screen, same as iOS.
4. Delete: swipe or overflow action on list rows plus a confirm dialog;
   calls removeEntry then commit.
5. ViewModel: port saveEntry/moveEntry/deleteEntry from AppModel.swift
   including commit message calls and refresh-after-mutation ordering.
6. Tests:
   - unit: generator charset and length; content assembly for new
     entries; changed-fields diffing logic.
   - instrumented: edit a fixture entry with odd formatting (comments,
     blank lines, uncommon spacing, no trailing newline) and assert the
     re-read bytes differ only in the edited value (this is the P1
     round-trip guarantee made user-facing; the fixture exists in the
     corpus).
   - instrumented: with a local git repo initialized, add/edit/rename/
     delete produce `git log` messages byte-identical to the pass CLI
     strings from `commitMessage*` helpers.

## Exit criteria

- All tests green; manual walk on emulator matches the iOS flow
  screen for screen.
- A store never left dirty after a mutation when git exists: one
  commit per user action.

## Out of scope

- Sync/push/conflicts (P09). Re-encrypt flows (P10).
