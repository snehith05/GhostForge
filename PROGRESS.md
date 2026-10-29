# Progress

Updated 2026-10-29.

## Current state: M2 complete

Core: `bash tools/t.sh` -> **62 tests: 62 passed, 0 failed, 0 skipped**
App: instrumented tests pass on-device, confirmed three times by reading the
runner's own log -- see the `connectedAndroidTest` gotcha in `CLAUDE.md` for
why the Gradle task's own exit code can't be trusted for that on this machine.

### Done

- Three new component types -- `checklist`, `status_board`, `assignment_board`
  -- with their own `SpecValidator` branches, reusing `requireEnumField`/
  `validateColumns` helpers shared with `status_counter`/`record_list`.
- `core/runtime/RenderGate.kt`: re-validates a stored *value* against an
  already-admitted field, at both render and write time. `Admission.Admitted`
  only ever proved the spec was consistent; this is what makes "fail safe on
  bad data" true of data written after admission too, including by a value
  that predates a later field-bound change.
- `bucketByEnum`: shared lane-grouping for `status_counter`, `status_board`
  and `assignment_board` -- a declared-but-empty lane for every option, plus
  an explicit unsafe lane rather than silently dropping bad values.
- Android `app` module: Compose screens for all six component types, a
  `RecordStore` that creates one real SQLite table per dataset (not an
  entity/attribute/value table -- `Limits.kt` already anticipated this in
  M1), and a Room `change_log` table, one database file per installed app.
- Every write, not just every read, goes through `RenderGate` first
  (`AppRuntimeViewModel`) -- bad data is rejected before it reaches storage.
- Instrumented test for process-death survival: closes and reopens a
  `GhostForgeDatabase` against the same file, confirms both the record and
  its change-log event are still there; a second test confirms a re-delivered
  `eventId` is ignored, not duplicated.
- Gradle now carries the Android/Compose/KSP/Room toolchain: wrapper bumped
  to 8.13 (AGP 8.11.1's minimum), `android.useAndroidX=true`, Compose BOM
  pinned to 2024.04.01 (newer BOMs on this machine require compileSdk 37 /
  AGP 9.1, neither installed; see `CLAUDE.md` for the version-pin reasoning).

### Verified, not just written

Two `SpecValidator` rules and one `RenderGate` rule were mutation-tested the
same way M1's were: disabling the check made exactly the expected test fail,
nothing else. The instrumented tests ran against a real booted emulator three
times, after fixing an actual mid-test crash by switching the emulator to
`-gpu swiftshader_indirect -no-window` (the default host-GPU backend died on
this machine's Iris Xe + gfxstream combination).

A full second-pass review (separate from writing the code) caught one real
bug and one real gap, both fixed before this milestone was called done:

- `RenderGate` treated a stored empty string on a *required* text field as
  fine, because the required check only looked for `RawValue.Null`. The
  sample spec's required `household` field has no default, so submitting the
  form empty would have created a record with no household name. Fixed:
  `checkText` now treats an empty string as missing when `required`, matching
  `SpecValidator`'s own `spec.name.isEmpty()` convention.
- `MainActivity` never wired `onResult`, so a rejected write (correctly kept
  out of storage) produced zero feedback -- "Add" would silently do nothing.
  Fixed with a `Snackbar` showing the rejection reason.

Two lower-severity latent races were fixed alongside them: a write could
structurally race `init`'s table setup (now gated on a `CompletableDeferred`,
not just "fast enough in practice"), and `_records` updated via a
read-modify-write that two concurrent dataset refreshes could clobber (now
`MutableStateFlow.update {}`). Both were unreachable through today's
single-dataset sample app, but cheap to close properly rather than leave as
known debt.

## Next: M3 -- deterministic generation + repair loop

Per the working agreement this is architecture -- switch to **Opus**.

## Carry forward

- `ChangeLogEntry` has no logical/vector clock. Hand-rolled sync (the owner's
  choice this session, over a CRDT library -- see `CLAUDE.md` Layout) still
  needs one for cross-device ordering; add it when M5 defines the merge
  algorithm, not before.
- Device identity is `Settings.Secure.ANDROID_ID`, a placeholder. M4 owns
  real identity; swap it there.
- `checklist`/`status_board`/`assignment_board`'s exact fields (`title_field`
  + `done_field`; `group_by`/`assigned_to` + `columns`) were designed this
  session -- nothing in the repo specified their shape before now. **Check
  them against the external design doc** and adjust if they diverge; nothing
  downstream depends on the current shape yet, so this is cheap to change
  now and expensive later.
- `bucketByEnum` puts a stored `null` on an *optional* group-by field in the
  same unsafe lane as genuinely corrupt data. Currently unreachable --
  `RecordFormScreen` always supplies a default -- but a real gap if a future
  write path can leave an optional enum field unset.

## Open questions for the owner

1. **Two Android phones?** M6 cannot be tested on emulators. If only one
   device is available, M6 stalls regardless of code quality.
2. **Scarce-resource semantics:** `status_transition` prevents a status
   retreating, but two volunteers claiming the same supply needs a stronger
   rule than any per-field policy can express. Deferred to M5, flagged here so
   it is not forgotten.
3. **Does the new component shape match the design doc?** See Carry forward
   above -- raising it here too so a `/clear` doesn't lose it.
