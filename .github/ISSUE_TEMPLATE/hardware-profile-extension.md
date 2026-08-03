---
name: Hardware profile extension
about: Propose an evidence-backed immutable Coffee GB hardware profile
title: "[Profile] "
labels: ""
assignees: ""
---

<!-- Complete every section. docs/hardware-profile-contribution.md is normative. -->

## Permanent identity and aliases

- [ ] Canonical lowercase ASCII ID and hardware revision are permanent and non-ordinal.
- [ ] Every compatibility alias has a real migration source; arbitrary names remain rejected.

## Evidence, licenses, and uncertainty

- [ ] Each hardware claim cites a pinned public source, license, access date, exact field, and derivation.
- [ ] Conflicts, unknowns, intentionally shared behavior, and non-goals are explicit.

## Exact clock and cadence math

- [ ] Master clock and cadence use exact integer/rational values with checked arithmetic.
- [ ] Long-run frame, RTC, serial, audio, and host-pacing evidence is attached.

## Capabilities and quirks

- [ ] Capabilities are immutable and only evidence-backed model quirks are introduced.

## Authentic and skip boot policy

- [ ] Authentic boot-ROM availability/validation and deterministic skip defaults are documented.
- [ ] Unsupported bootstrap combinations reject before core construction.

## Construction and Auto resolution

- [ ] Explicit selection resolves before the first tick; Auto behavior is documented and tested.

## State, rewind, netplay, and future replay

- [ ] StateFile version/coarse-family compatibility and mismatch-before-mutation are covered.
- [ ] MachineSnapshot/rewind retains exact identity; protocol and future replay limits are explicit.

## CLI, UI, settings, and diagnostics

- [ ] Registry-generated choices, persisted canonical ID, reload, errors, and diagnostic identity are tested.

## Legal fixtures and provenance

- [ ] Every artifact has origin, redistribution permission, generator/capture method, and SHA-256.
- [ ] No proprietary boot ROM, game asset, or automatic download is included.

## Positive, boundary, malformed, reset, and mismatch tests

- [ ] Before-failing regressions cover construction, reset, continuation, malformed identity, and atomic rejection.

## Baselines and documentation

- [ ] Construction/state behavior, model baselines, user docs, and contributor docs agree.

## Java 16 build and compatibility CI

- [ ] `mvn -B clean test`, Swing compile, required compatibility profiles, fixture hashes, and hosted checks pass.
