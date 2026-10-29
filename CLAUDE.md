# GhostForge

Offline-first Android platform: describe a problem in plain language, get a
validated mini-app on device, share it peer-to-peer with nearby devices with no
internet. Full design doc lives outside the repo; `docs/` holds decisions.

## Stack decision

Native **Kotlin + Jetpack Compose**, not Flutter. Nearby Connections,
foreground services and crypto are all native anyway, so Flutter would only add
a platform-channel boundary. iOS is deliberately out of scope.

`core/` is a **pure Kotlin/JVM module with no Android dependency**. Schema,
validation, generation and sync all live there so they test headlessly in
seconds. Anything needing an Android API belongs in the app module, not here.

## Build and test

```
bash tools/t.sh          # ALWAYS use this, never raw ./gradlew test
```

`tools/t.sh` prints only failing tests plus a one-line summary. A build or
compile failure is printed in full, because a swallowed compile error is
invisible. Raw Gradle test output is too verbose for an agent transcript.

### Machine gotcha: AVG breaks all artifact downloads

AVG does TLS inspection on this machine and its root certificate is not in the
JDK truststore, so Gradle fails with `PKIX path building failed` and reports it
misleadingly as "plugin not found". Fixed in `~/.gradle/gradle.properties`:

```
org.gradle.jvmargs=-Xmx2g -Djavax.net.ssl.trustStoreType=Windows-ROOT
```

That file is user-level on purpose: the flag is Windows-only, so committing it
would break other platforms. **Do not set `org.gradle.jvmargs` in the project's
`gradle.properties`** -- a project value shadows the user one and the truststore
flag is lost. If downloads start failing with PKIX again, check that first.
`curl` works regardless, so curl succeeding proves nothing about Java.

## Invariants

These are load-bearing. Breaking one is a security regression, not a style
change.

1. **`SpecGate.admit()` is the only way to turn untrusted text into an
   `AppSpec`.** Holding an `Admission.Admitted` is proof a spec cleared both
   structural and semantic checks. Nothing else parses received data.
2. **Closed world.** `ignoreUnknownKeys = false`. An unknown key, component
   type or field type is rejected, never ignored. A newer-schema document must
   not be partially executed by an older build.
3. **No executable strings.** The spec format describes data and composition
   only. There is no expression language and no field any layer evaluates. A
   feature that seems to need one gets a new validated component type instead.
4. **`schema_version` must equal `Limits.SCHEMA_VERSION` exactly.** Not a
   minimum, not a range.
5. **`Limits` is a security boundary**, not preferences. Every bound caps what
   a hostile spec can cost the device rendering it. Raising one needs a reason.
6. **Identifiers are narrow by design** (`^[a-z][a-z0-9_]{1,47}$`). They become
   SQLite column names and storage keys, so they are constrained at the
   boundary rather than escaped at each use site.
7. **Every field declares a `merge` policy** and the validator rejects
   incompatible pairings (e.g. `status_transition` on text, `max` on an enum).
   One policy for a whole app is unsafe: free text tolerates last-write-wins,
   a delivery status does not.
8. **`status_counter.group_by` must name an enum field.** An enum's option set
   is bounded by the spec, so group count is known ahead of render; grouping by
   text would let record content dictate unbounded work.
9. **Error codes are the stable contract.** Tests and the M3 repair loop
   dispatch on `ErrorCode`; messages may change freely. Never assert on message
   text.
10. **Validation collects all errors** in one pass, so the repair loop can fix a
    whole document per round trip instead of one error at a time.

## Layout

```
core/src/main/kotlin/dev/ghostforge/core/spec/
  Limits.kt         every bound, one auditable place
  AppSpec.kt        the spec model; Field/Component are sealed
  Validation.kt     ErrorCode, ValidationError, ValidationReport, Admission
  SpecValidator.kt  semantic rules
  SpecGate.kt       the admission entry point
tools/t.sh          filtered test runner
tools/testsum.py    JUnit XML -> failures only
docs/               decision records
```

`Field` and `Component` are **sealed** with `type` as the JSON discriminator,
so an unknown type fails at parse time structurally. The validator never has to
remember to check for it. Adding a component type means adding a sealed subclass
*and* a branch in `validateComponents` -- the `when` is exhaustive, so the
compiler will point at what is missing.

## Milestones

M1 spec schema + validator + test runner      DONE
M2 Compose runtime + Room persistence         next
M3 deterministic generation + repair loop
M4 identity, signing, manifest, revocation
M5 sync engine (event log, merge, convergence)
M6 GHOSTNET / Nearby Connections

Ordering is deliberate: M6 is last because Nearby Connections **cannot be
tested on emulators** (no real Bluetooth/Wi-Fi Direct radios), so it needs two
physical phones. Everything before it is verifiable headlessly.

See `PROGRESS.md` for current state.

## Working agreement

- **Model per milestone.** Sonnet for UI, validators and mechanical work; Opus
  for architecture (the schema, and M5 sync). The user switches with `/model`;
  ask them to, do not assume.
- **`/clear` between milestones.** The user types it. `CLAUDE.md` plus
  `PROGRESS.md` are what make that safe, so update `PROGRESS.md` before a clear.
- **Push every session.** Remote is `origin`
  (`github.com/snehith05/GhostForge`, public). Credentials are cached in Git
  Credential Manager, so pushing needs no prompt. Commit and push before the
  session ends.
- Keep this file under 200 lines.
- End commit messages with:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

## Not in scope for the MVP

No arbitrary code generation, no WebAssembly, no distributed inference, no
guaranteed multi-hop routing, no global revocation, no custom crypto protocol,
no life-critical deployment. On-device LLM is M3-optional at best; the
deterministic template path must always work without it.
