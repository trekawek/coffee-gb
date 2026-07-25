# Hardware profiles and session clocks

## Permanent identity and ownership

Every `GameboyConfiguration` resolves one registered immutable `HardwareProfile` before a
`Gameboy` is constructed. `Gameboy` validates that the value is the authoritative registry
instance and retains it for the session's lifetime; there is no mutable global current profile.
The permanent lowercase-ASCII IDs, in registry order, are:

| ID | Display name | Family/revision | Current capabilities |
|---|---|---|---|
| `dmg` | Game Boy (DMG) | DMG / `dmg` | monochrome LCD, serial link |
| `cgb` | Game Boy Color (CGB) | CGB / `cgb` | color/CGB mode, double speed, infrared, serial link |
| `cgb0` | Game Boy Color (CGB revision 0) | CGB / `cgb0` | CGB capabilities plus the existing revision-zero boot/OAM policy |
| `sgb` | Super Game Boy | SGB / `sgb` | SGB command/input/border path and DMG-side serial link |

IDs are non-ordinal and permanent. Registry iteration is deterministic. Canonical lookup accepts
only these exact strings; persisted settings additionally accept the finite historical aliases
`DMG`, `CGB`, `CGB0`, and `SGB`. Display names, enum ordinals, mixed case, and arbitrary aliases are
never identities. Unknown values fail before core construction and include the complete supported
list in the error.

`HardwareCapabilities`, `BootSpec`, and `ClockSpec` are immutable values owned by a profile.
Mutable arrays, callbacks, clocks as host services, event buses, and UI/device values are absent.
`HardwareProfileIdentity` is a small service-free `(profileId, ClockSpec)` seam suitable for
diagnostics and future #315 replay metadata; this phase does not define a replay file.

The executable source of truth is
[`hardware-profile-matrix.tsv`](../controller/src/test/resources/sgb-baselines/hardware-profile-matrix.tsv).
It pins identity, every capability, exact clock/cadence, boot image selector, authentic-boot divider
preset, skip-boot registers, and CGB handoff ticks. `HardwareProfileMatrixTest` compares every cell
to the registry, while `SgbInventoryGuardTest` scans and fingerprints all production profile,
model, boot, clock, state, compatibility, and platform decisions.

## Resolution and compatibility adapters

Automatic selection preserves the established policy:

- non-CGB cartridges use the configured DMG-games profile (default `sgb`);
- CGB/universal cartridges and Datel's color-header compatibility case use the configured CGB-games
  profile (default `cgb`);
- an SGB selection for a cartridge without the SGB header falls back to CGB as before;
- the reviewed `CGB0_REVISION` ROM compatibility feature canonicalizes an otherwise selected `cgb`
  session to `cgb0`;
- an explicit CLI override is resolved before `Gameboy` construction and its first tick.

`GameboyType` remains a documented deprecated three-value source adapter. DMG/CGB/SGB map to their
canonical profiles; both `cgb` and `cgb0` map back to coarse CGB. The deprecated
`setCgb0Revision` API changes the concrete profile between `cgb` and `cgb0` and rejects CGB0 on a
non-CGB family, so callers cannot create a contradictory revision/type pair. Configuration copies
for restore, rollback replay, boot templates, linked checkpoints, and transaction rollback retain
the exact registered profile.

Mealybug DMG-blob timing, CodeBreaker rumble, Datel pass-through identity, bootstrap choice, and
the SGB-border preference remain explicit configuration/accessory policy. They are not generalized
into hardware capabilities. State and boot-cache keys retain them separately where they affect
behavior.

## Exact clock contract

`ClockSpec` contains an integer master-tick rate and an exact rational controller cadence. All four
Phase-3 profiles deliberately use the legacy Coffee GB values:

```text
master ticks/second       = 4,194,304
controller frames/second = 60 / 1
ticks/controller frame   = floor(4,194,304 / 60) = 69,905
```

The 60-Hz value is Coffee GB's established controller scheduling policy, not a claim that it is the
physical LCD refresh rate. Real SGB/SGB2 timing is explicitly deferred to #344; MGB identity and
timing are deferred to #345.

Conversions use checked integer or `BigInteger` multiply/divide with explicit floor, ceiling, or
nearest rounding. Consumer-owned `RateAccumulator` phases carry the exact remainder. The host
pacer advances nanoseconds by an exact rational phase, and the Swing audio sink converts tick-domain
samples to 44.1 kHz with an exact phase rather than cumulative floating-point rounding. This is an
intentional exactness correction in host sample selection; APU tick output and built-in session
rates remain unchanged. Size calculations are checked before allocation. Host wall-clock pacing is
not emulated time and is never captured.

Production consumers now receive the owning session clock: BasicController, LinkedController,
StateHistory, TimingTicker, Agent, Sound/audio output, MBC3/RTC (including Datel slots), serial/GPS,
four-player adapter timing, and Gameboy frame-derived thresholds. Deprecated
`Gameboy.TICKS_PER_SEC` and `Gameboy.TICKS_PER_FRAME` remain source adapters only; no migrated
production path reads them. Linked candidate groups are preflighted before construction/replay or
live replacement and reject differing master/tick budgets before partial execution.

State prepare is also clock-owned. Generic detached decoding keeps arrays and graphs bounded without
assuming a built-in rate; before machine/session, legacy-import, or rewind mutation, semantic
preflight derives the exact Sound stereo capacity and MBC3 subsecond upper bound from the target
profile's `ClockSpec`. A custom-clock state valid for that target is therefore accepted, while the
same shape or phase is rejected for an incompatible target before any component restore begins.

## Boot and capability parity

BIOS resource selection, CGB/SGB construction, CGB0 MMU/OAM behavior, authentic-boot divider
presets, CGB post-boot peripheral handoff, skip-boot DIV/register values, display capability, RTC,
and serial/audio clocking derive from the resolved profile or a narrow immutable value derived once
from it. Hot CPU/GPU/APU/MMU components may retain narrow booleans; they do not branch on display
names or perform registry lookup per tick.

This phase intentionally preserves two evidence-visible baselines:

- current SGB skip boot uses DMG `BC=0x0013`, while the pinned Pan Docs table lists SGB
  `C=0x14`;
- all current profiles use Coffee GB's legacy master clock/controller cadence.

The behavioral evidence was accessed 2026-07-25 and is pinned to Pan Docs commit
[`fe246067`](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/Power_Up_Sequence.md)
(CC0-1.0), together with Coffee GB's ROM-independent model hashes and compatibility suites. The
disagreement is recorded rather than guessed. No Nintendo boot ROM or proprietary fixture was
added.

## State, rewind, and protocol compatibility

`MachineSnapshot` records the canonical profile ID and rejects a different profile before restore
mutation. StateFile v1 does not gain bytes: its fixed hardware byte plus existing CGB0 flag derive
exactly one canonical ID (`dmg`, `cgb`, `cgb0`, or `sgb`). Full compatibility still includes the
existing bootstrap and accessory flags. Inspection prints the canonical ID. The released v1 golden
fixture decodes and re-encodes byte-for-byte, and an incompatible derived ID produces typed
`HARDWARE_PROFILE_MISMATCH` before the first live-mutation callback.

Protocol v8 is also byte-for-byte unchanged. Its `GameboyType` ordinal and profile flags are pinned
wire compatibility fields which are canonicalized to a registered profile before candidate session
construction. A free-form or unknown profile ID is not accepted in v1/v8. Adding a new portable or
wire identity requires an explicit StateFile extension/version and, when applicable, a new netplay
capability/version; display-name matching is forbidden.

## Desktop and contributor contract

The command line accepts `--profile=<id>`. It conflicts with `--force-dmg` and `--force-cgb`; the
two force flags also conflict with one another. Legacy force flags map to `dmg` and `cgb`.
Malformed/missing/unknown profile values fail before Swing or core session construction. Desktop
menus are generated from registry order and persist canonical IDs while reading the finite legacy
aliases.

To add or rename a profile in a future phase:

1. never reuse or change an existing ID;
2. add the complete immutable registry entry and reviewed finite alias only if migration requires it;
3. update the checked-in matrix and decision inventory/fingerprints;
4. prove boot/register/memory/render/timing parity or document an evidence-backed change;
5. version portable/network metadata if the old fixed identity cannot represent it;
6. add mismatch-before-mutation, settings/CLI, rewind, linked, and compatibility coverage.

SGB2 (`#344`), MGB (`#345`), and replay recording (`#315`) are deliberately outside this phase.
