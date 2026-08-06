# P09. Git sync screen and per-entry conflict resolution

Objective: the sync sheet with status, clone, publish, push, and Sync
now, including the three-choice per-file conflict dialog, matching
SyncView.swift behavior exactly.

Requires: P03; benefits from P08 being merged first (commits to sync).

## Context (iOS file to mirror)

`Sources/App/SyncView.swift`, in full. Key behaviors:

- Status section: Unpushed (ahead), Behind remote, Remote
  configured/none; "No git repository" hint when git is nil.
- Publish section (shown when no remote): URL field
  (`https://user:token@host/you/store.git`), init when needed
  (`GitSync.init` commits everything with CLI-style messages), then
  setRemote, then push.
- Clone section (shown when no repo): clones into a scratch dir first;
  a failed clone never harms the current store; on success the clone
  replaces the current store for this format; footer explains the
  matching-key requirement.
- Remote section (when a remote exists): current URL displayed with
  userinfo redacted (`https://•••@host/...`), new-URL field + Update.
- Actions: Sync now (with resolver), Push. Busy state disables buttons.
- Conflict dialog: title "Sync conflict: <path>", body "This entry
  changed on this device and on another device.", choices Keep this
  device's version / Keep the other version / Keep both.
- `UIResolver`: the FFI `ConflictResolver.choose` is called on the sync
  thread and must block until the user picks; iOS bridges with a
  semaphore against an async main-actor dialog. Kotlin: run sync on
  Dispatchers.IO and block the callback on a CompletableDeferred
  resolved by the dialog.
- Outcome strings: ported verbatim (Already up to date / Pulled changes
  from remote / Merged cleanly (N local changes replayed) / Resolved N
  conflicts. Kept both for: ...).

## Work

1. `ui/sync/SyncScreen.kt` + SyncViewModel with the exact section
   logic, redaction function (port the string logic and its tests),
   scratch-dir clone-then-swap, busy/message state.
2. Wire syncStatus into the ViewModel refresh path so the list
   screen's unpushed banner (P03) goes live.
3. `sync/BlockingResolver.kt`: ConflictResolver implementation
   bridging to a Compose dialog via CompletableDeferred; document the
   threading contract in a comment (choose blocks a core thread, never
   the main thread).
4. Error mapping: GitException variants to the ported ErrorMessages
   strings (NonFastForward says sync first; UpstreamRewritten says
   recovery required; DirtyWorkdir named plainly).
5. Tests:
   - unit: URL redaction cases; outcome describe() strings.
   - instrumented (local remotes, no network): init a bare repo in a
     temp dir as origin; publish flow pushes; clone flow clones;
     divergent edits on the same entry produce exactly one resolver
     call per conflicted file and honor each of the three choices
     (KeepBoth results in the second file the core creates). The git
     matrix suite in PassPonyCore already proves the engine; these
     tests prove the Android wiring and dialog plumbing.

## Exit criteria

- Full sync walk on emulator against a bare repo on disk: publish,
  edit on a second checkout, sync, conflict dialog appears, each
  choice behaves, push succeeds, status counts correct throughout.
- No plaintext, entry names are allowed but no secrets, in any log
  line the sync path emits.

## Out of scope

- SSH remotes (not-yet list). Background/auto sync (post-v1).
