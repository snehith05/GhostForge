# Progress

Updated 2026-10-29.

## Current state: M1 complete

`bash tools/t.sh` -> **36 tests: 36 passed, 0 failed, 0 skipped**

### Done

- Gradle 8.11.1 / Kotlin 2.1.0 / JDK 17 pure-JVM `core` module, no Android dep.
- Spec schema v1.0: 5 field types (text, number, boolean, enum, timestamp),
  3 component types (record_form, record_list, status_counter), per-field merge
  policy, default-deny permissions.
- `SpecGate.admit()`: three ordered rejection stages -- size, then strict
  shape, then semantics.
- `SpecValidator`: identifiers, uniqueness, reference resolution, numeric and
  length bounds, enum option rules, default legality, merge-policy
  compatibility, enum-only grouping, per-dataset and total field budgets.
- `tools/t.sh` + `tools/testsum.py`: failures only, full output on build failure.
- Fixed AVG TLS interception breaking all Gradle downloads (see CLAUDE.md).

### Verified, not just written

The suite was mutation-tested: disabling the enum-only `group_by` rule made
exactly one test fail, the right one. The runner was confirmed to report test
failures, build failures and all-pass distinctly.

## Next: M2 -- Compose runtime + persistence

Switch to **Sonnet** for this one. Scope:

1. Android app module (Compose, minSdk 26), depending on `core`.
2. Render each component type from a spec; the runtime must reject at render
   what the validator rejected at admission, and fail safe on bad data.
3. Room/SQLite: records plus an append-only change log keyed by event id.
   Every change carries enough metadata to detect duplicate delivery -- M5
   depends on this, so design the event row now.
4. Add the remaining components (checklist, status_board, assignment_board)
   with validator branches; the `when` in `validateComponents` is exhaustive so
   the compiler flags omissions.
5. Instrumented tests for persistence surviving process death.

Carry forward: `SpecGate.encode` is **not canonical** yet. M4 needs a canonical
byte form for hashing and signing, so do not use `encode` for integrity
purposes until that exists.

## Open questions for the owner

1. **Two Android phones?** M6 cannot be tested on emulators. If only one
   device is available, M6 stalls regardless of code quality.
2. **Sync strictness (M5):** hand-rolled event log with per-field policies, or
   evaluate Automerge? The hand-rolled path is lighter and already implied by
   the `MergePolicy` enum; a CRDT library is heavier but better studied.
3. **Scarce-resource semantics:** `status_transition` prevents a status
   retreating, but two volunteers claiming the same supply needs a stronger
   rule than any per-field policy can express. Deferred to M5, flagged here so
   it is not forgotten.
