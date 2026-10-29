# GhostForge

Offline-first, peer-to-peer mini-application platform for Android.

Describe a problem in plain language, get a validated mini-app on your device,
and share it directly with nearby devices -- no internet, no app store, no
developer.

> **Status: early development.** Milestone 1 of 6. The specification format and
> its validator exist and are tested; there is no UI and no networking yet.
> Not usable yet, and explicitly not for life-critical use.

## Why

During outages, disasters and remote fieldwork, people keep their phones but
lose the software they need to coordinate. A group chat can say "five families
need water"; it cannot track who has been served, who is responsible, or which
edits happened while a device was offline. GhostForge generates a small
structured tool for exactly that, locally, and shares it device to device.

## Approach

Generation produces a **validated specification**, not source code. The runtime
assembles the app from a library of pre-verified components, so a shared app
cannot express arbitrary behaviour on a recipient's phone. Three ordered
rejection stages guard the boundary: document size, then strict structural
parsing, then semantic validation.

That constrains what a generated app can do by construction rather than by
hoping a language model behaves.

## Architecture

| Layer | Role |
|---|---|
| DREAMCODE | plain language to a structured specification |
| Verification | schema, capability and behavioural checks before sharing |
| Runtime | renders apps from pre-verified components only |
| Sync engine | reconciles edits made while devices were disconnected |
| GHOSTNET | peer-to-peer discovery and transport, no internet required |

## Layout

- `core/` — pure Kotlin/JVM: schema, validation, and later generation and sync.
  No Android dependency, so it tests headlessly.
- `tools/` — build and test helpers.
- `docs/` — decision records.

## Build

Requires JDK 17.

```
bash tools/t.sh
```

See `CLAUDE.md` for the design invariants and `PROGRESS.md` for current state.

## Licence

Not yet chosen.
