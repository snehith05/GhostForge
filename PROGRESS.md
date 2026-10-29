# Progress

Updated 2026-10-29.

## Current state: M3 complete

Core: `bash tools/t.sh` -> **157 tests: 157 passed, 0 failed, 0 skipped**
App: unchanged since M2 and compiles against the new core. Its instrumented
tests are as M2 left them (see the `connectedAndroidTest` gotcha in `CLAUDE.md`).

### Done

- `core/gen`: `SpecGenerator.generate(description)` turns plain language into
  an admitted spec with no language model anywhere on the path. The
  interpreter picks one of seven templates by weighted keywords and keeps the
  matched words, so the choice is explainable. Lists the user spells out
  ("statuses: pending, done", "volunteers: Asha, Ravi") become enum options.
  The template builds a typed candidate, `SpecRepair` brings it into
  compliance, and `SpecGate.admit` has the final word (invariant 1).
- Template catalog: request tracker, incident log, inventory, volunteer
  roster, checklist, and registry, plus a plain-records fallback for anything
  unrecognised (non-English text included). A field or board appears only when
  the user listed what it needs. People are never invented, except the
  roster's placeholder teams, since a roster without people is meaningless.
- `SpecRepair` fixes every validator `(code, path)` that has a mechanical fix,
  and gives up on `MALFORMED`, a foreign `schema_version`, and anything that
  would mean inventing a dataset, field or option (invariants 11 and 12). A
  renamed id carries its references with it. Removals and trims are applied
  against the report's original indices, so a round with several errors fixes
  the right elements.
- Determinism: no randomness, clock or locale on the path. Case mapping uses
  the invariant locale, and hex is built by hand instead of with
  `String.format`. Truncation never splits a surrogate pair, and lone
  surrogates in the input are dropped, so every document survives UTF-8
  encoding and can travel between devices.
- `tools/testsum.py` fix: on this machine, Python's piped stdout is cp1252, so
  the first failure message containing Kannada or an emoji crashed the script.
  Every later failure and the summary line silently vanished (the exit code was
  still non-zero). The script now always writes UTF-8 with backslash escapes.

### Verified, not just written

The 95 new tests include a 500-case seeded fuzz. For any input, generation
yields an admitted app, the only repairs are to the user's own name and
description, and the document survives a UTF-8 round trip.

Seven load-bearing rules were mutation-tested, and each break was caught by
exactly the tests meant to catch it. The rules: rename propagation, the
surrogate guard, removal order, reserving correctly spelled ids, never
touching `schema_version`, dropping lone surrogates, and template validity.
The testsum.py bug surfaced because two of those runs printed truncated output.

## Next

1. **Sonnet: the "describe your app" screen.** This is UI, not architecture.
   The app gets a text field and a Generate button. It shows what was
   understood ("a request tracker, because of: request, deliver") and any
   repairs, then opens the result in the M2 runtime. This replaces
   `SampleSpecs` as the way in. Enum option ids should display nicely
   (`in_progress` -> "In progress", with a leading `opt_` removed).
2. **Opus: M4, identity, signing, manifest and revocation.** Signing needs a
   canonical byte form. `SpecGate.encode` is deterministic but not canonical.

## Carry forward

- `ChangeLogEntry` has no logical/vector clock yet. Add it when M5 defines
  the merge, not before.
- Device identity is `Settings.Secure.ANDROID_ID`, a placeholder until M4.
- `bucketByEnum` puts a null on an *optional* group-by field in the unsafe
  lane. Unreachable today, because the form always supplies a default.
- **New:** `app_id` derives from the name, so two authors who type the same
  description get the same `app_id`. M4's manifest must namespace apps by
  author identity.
- **New:** the interpreter only understands English. Kannada or Hindi input
  falls back to plain records with a hashed `app_id`, which still works but
  is generic.
- **New:** `status_transition` follows the order the user listed statuses in.
  A branching workflow (approved vs rejected) is not really linear, which is
  M5's merge-semantics problem.
- **New:** generated inventory quantities are last-write-wins, which is the
  scarce-resource question below.
- **New:** repair accepts any typed candidate, so a future LLM generator can
  reuse it. Malformed model output would also need a parse-only step and the
  report fed back to the model. Building that must keep invariants 1 and 11.

## Open questions for the owner

1. **Two Android phones?** M6 cannot be tested on emulators. If only one
   device is available, M6 stalls regardless of code quality.
2. **Scarce-resource semantics:** `status_transition` prevents a status
   retreating, but two volunteers claiming the same supply needs a stronger
   rule than any per-field policy can express. Deferred to M5.
3. **Do M2's component shapes and M3's template catalog match the design
   doc?** Both were designed without it, because it is neither in the repo nor
   anywhere on this machine. A copy in `docs/` would let future sessions check
   directly instead of guessing.
