# Contributing a hardware profile

This is the normative extension process for Coffee GB hardware profiles. Start with the
[`hardware-profile-extension` issue template](../.github/ISSUE_TEMPLATE/hardware-profile-extension.md)
and keep one model/evidence decision per reviewable phase. A new profile is not a renamed enum
value: it is a permanent portable compatibility identity.

<!-- profile-registry-ids: dmg,cgb,cgb0,sgb,sgb2,mgb -->

## 1. Permanent identity and aliases

Choose one lowercase ASCII, non-ordinal ID and a precise family/revision. IDs are permanent after
release. Add an alias only for a documented persisted value that users already have; never accept
display names, mixed case, or enum ordinals as identity. Update the authoritative registry and the
machine-readable hardware profile matrix in the same change.

## 2. Evidence, licenses, and uncertainty

Before changing behavior, record each public source at an immutable revision where possible,
license/redistribution status, access date, exact field or observation, and derivation math. An
authoritative technical or hardware source is required for hardware claims; emulator source may
corroborate but cannot be the sole evidence. Record disagreements, unknown fields, behavior shared
with an existing model, and deliberately deferred quirks instead of guessing.

## 3. Exact clock and cadence math

Represent master clocks and cadences as checked exact integers/rationals. Audit controller/PPU,
timer, serial/IR, RTC, APU/audio conversion, host pacing, rollback/rewind stamps, and clock-derived
state bounds. Prove long-run exact totals/remainders and keep host wall time separate from emulated
time. Reuse an existing immutable `ClockSpec` when the evidence says the identity is exact.

## 4. Capabilities and quirks

Compose immutable shared capabilities and boot policies. Add a profile flag only for a demonstrated
hardware distinction; ROM, mapper, accessory, renderer preference, and compatibility-database
switches stay separate. Add before-failing positive and boundary tests for every changed behavior.

## 5. Authentic and skip boot policy

Document the exact boot-ROM identity, legal acquisition policy, verified post-boot registers/DIV,
and unknown values. Never bundle, download, or substitute a different model's copyrighted boot
ROM. If no validated user-supplied path exists, `NORMAL` and `FAST_FORWARD` must reject before core
construction; `SKIP` needs deterministic cited defaults and reset/copy tests.

## 6. Construction and Auto resolution

Resolve one registered instance before the first tick. Document whether a cartridge header can
actually identify the revision; if it cannot, Auto must preserve existing behavior. Test explicit
construction, reset/reload, restore/rollback copies, unknown IDs, and legacy adapters.

## 7. State, rewind, netplay, and future replay

Define the StateFile version and coarse-family rule without reusing an undefined bit. Preserve old
fixture bytes and meanings. Add deterministic encode/decode/apply, inspection, malformed/unknown,
cross-profile mismatch-before-mutation, and MachineSnapshot/rewind tests. A protocol unable to
represent the exact identity must reject before session construction or payload write. No replay
support may be claimed until a versioned replay format carries the canonical ID.

## 8. CLI, UI, settings, and diagnostics

Executable choices must come from `HardwareProfileRegistry`; persisted values use canonical IDs.
Test CLI, desktop selection, restart/reload, invalid settings, diagnostics, and list order. Future Android
frontends have the same registry-consumer contract but require no speculative platform code.

## 9. Legal fixtures and provenance

For every ROM, trace, screenshot, palette, or generated fixture, record origin, license or explicit
redistribution permission, version/commit, SHA-256, generator/capture method, and scope of evidence.
Never commit Nintendo boot ROMs, proprietary game assets, or automatically downloaded artifacts.

## 10. Tests, matrices, inventory, and verification

### Positive, boundary, malformed, reset, and mismatch tests

Cover positive, boundary, malformed, reset, deterministic continuation, and atomic mismatch paths.

### Matrices, inventory, and documentation

Update model baselines, hardware matrix, decision inventory/fingerprints, StateFile/netplay/rewind
docs, README/CLI/UI guidance, and provenance.

### Java 16 build and compatibility CI

Run the Java-16 `mvn -B clean test` reactor, downstream
Swing compilation/tests, all affected compatibility profiles, fixture SHA checks, `git diff --check`,
static architecture scans, and exact-head hosted CI. Report every skipped or unavailable check.
