# Deterministic replay format v1

## Scope and ownership

Coffee GB replay files use the bounded `CGBR` format implemented by
`controller.replay.ReplayCodec`. Version 1 records one standalone `Session`; linked topology and
host-backed peripherals are deliberately outside its contract. A replay contains identity and
input data, not a ROM. Playback therefore requires the caller to supply the matching ROM bytes.
The supported bounded command-line playback surface, exit codes, and optional CGBI input-script
format are documented in [headless-cli.md](headless-cli.md).

`ReplayRecorder` and `ReplayPlayer` are owner-thread APIs. A session and its input-timeline observer
have at most one recorder owner. From a successful `ReplayRecorder.start()` until finish, close, or
failure detaches it, that recorder exclusively owns calls that advance the session with `tick()` or
`frame()`. Starting another deterministic capture or attaching over an existing capture rejects.

The same recorder lifetime holds the session's deterministic-capture reservation. Recording
preflight requires an empty serial port, and `Session.setSerialEndpoint` rejects serial endpoint or
backend changes until the reservation is released. The player always creates a new
service-isolated session; it never mutates the configuration or a live session supplied by the
caller. Identity and schema incompatibilities fail before construction. Target-dependent embedded
state is fully prepared against the isolated session before candidate state is applied, and any
failure is reported through the replay compatibility API.

The format is not Java native serialization. All integers are big-endian, all tags have explicit
numeric values, strings use strict length-prefixed UTF-8, and all collections have checked counts
before allocation.

## Envelope and sections

The fixed 72-byte envelope is:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `CGBR` |
| 4 | 2 | format version (`1`) |
| 6 | 2 | header size (`72`) |
| 8 | 8 | required feature flags |
| 16 | 8 | optional feature flags |
| 24 | 4 | section count |
| 28 | 4 | envelope flags (`0`) |
| 32 | 8 | encoded payload length |
| 40 | 32 | SHA-256 of the encoded payload |

Version 1 defines no feature or envelope flags. A reader rejects an unknown required feature bit;
optional feature bits do not change the meaning needed to play the file. The encoder emits zero.

The payload contains sections in strictly increasing ID order. Every section starts with a
24-byte header: section ID `u16`, schema version `u16`, flags `u32`, encoded length `u64`, and
decoded length `u64`. Flag bit 0 means required and bit 1 means raw DEFLATE. Only the input and
checkpoint sections may be compressed, and compression is used only when it makes the canonical
body smaller. Unknown required sections reject; unknown optional sections are bounded and skipped.

| ID | Required | Contents |
|---:|:---:|---|
| 1 | yes | ROM/profile/clock and behavior identity |
| 2 | yes | initial mode, deterministic RTC epoch, initial tick/frame |
| 3 | yes | ordered input transitions |
| 4 | yes | periodic and final subsystem hashes |
| 5 | no | producer, creation time, and user note |
| 6 | conditional | raw StateFile v2 SESSION bytes for embedded-state mode |

Section 6 is present and required only for `EMBEDDED_SESSION_STATE`. It is never compressed again;
StateFile owns its own compression choice and checksum.

## Identity and compatibility

Identity includes the primary ROM SHA-256, an optional pass-through-slot ROM SHA-256, exact
canonical hardware-profile ID, and the two reduced `ClockSpec` rationals: master ticks per second
and controller frames per second. It also includes exactly one bootstrap flag, the replay semantics
version, required StateFile version, and the behavior flags for Mealybug DMG timing, CodeBreaker
rumble, and SGB-border behavior. Derived or rounded tick rates are not accepted as substitutes.

Before playback, `ReplayCompatibility` checks every identity field. Embedded state must be an exact
StateFile v2 `SESSION` root for player zero with the same ROM/profile identity and an empty serial
peripheral. Pocket Camera and MBC7 sensor cartridges, HuC3 and TAMA5 host-wall-clock mappers,
serial devices, non-null infrared endpoints, already-latched physical input, and an MBC3 RTC at a
host-time pause boundary are rejected in replay v1. The live paused-RTC preflight is a
side-effect-free status query: it does not catch up or otherwise mutate the clock. No validation
path partially applies state to the caller's session.

## Initial state and privacy

`BOOT_REFERENCE` is the default and privacy-safe mode. Recording starts only when the current
machine exactly matches a newly built battery-free boot reference, legacy input is released, and no
physical input is already latched. The replay stores no RAM snapshot in this mode.

`EMBEDDED_SESSION_STATE` supports an in-progress frame-safe session, but the caller must explicitly
set `includeSensitiveInitialState`. Its StateFile can contain complete machine memory and cartridge
RAM. An application must explain that inclusion before enabling the option. Neither mode stores ROM
or boot-ROM bytes, filesystem paths, host names, user names, network credentials, or battery files.

Playback clones only immutable ROM and hardware choices. It replaces live controls with
`ReplayInputSource`, wall time with `VirtualTimeSource`, serial and infrared with empty endpoints,
and battery persistence with disabled/null storage. Replay v1 has no pause/reset/external-I/O event,
so recording code must end or discard a recording before any such lifecycle boundary.

## Input timing

Replay ticks are zero-based executed master-tick indices. Input and checkpoint ticks are limited to
`0..(Long.MAX_VALUE - 1)`; reserving `Long.MAX_VALUE` for the successor position lets the player
advance safely after the largest representable replay tick. Records at tick `N` have two ordered
phases:

1. `LEGACY_P1_BEFORE_TICK` changes the historical P1 event-owned button set before CPU work for
   tick `N`. Multiple transitions at one tick retain their source order.
2. `PHYSICAL_JOYPAD_SAMPLE` supplies one coalesced absolute mask per changed P1-P4 player at the
   ordinary joypad sample later in tick `N`. The CPU observes that physical latch on the following
   tick, matching normal emulation order.

Every record carries player, absolute mask, and XOR changed mask. Stable bits are Right, Left, Up,
Down, A, B, Select, and Start at bits 0 through 7. The mapping is explicit in `JoypadButtonMask` and
does not depend on enum declaration order. Attaching an observer or restoring state silently aligns
its masks; it never invents a transition.

Legacy P1 events must be posted by the recorder owner between calls to `tick()`. A legacy event
observed while a recorder tick is in progress fails the recording instead of assigning it an
ambiguous phase; an event observed from another thread likewise fails the owner-thread check.
Physical P1-P4 changes remain inside the ordinary joypad sample during `tick()`. The combined two
phases may contain at most 64 records at one tick and 1,000,000 records in the complete replay.

## Checkpoints and divergence

Each checkpoint describes state after its named tick. The last checkpoint is mandatory and is the
authoritative replay duration/final expectation. A checkpoint stores eight SHA-256 values:

- full domain over canonical, diagnostic-free, uncompressed StateFile v2 bytes and the sampled
  physical P1-P4 masks;
- CPU/interrupt/timer/speed;
- memory/MMU/RAM/DMA/patch state;
- PPU/display/SGB graphics and FIFO runtime;
- APU;
- cartridge/mapper/RTC/rumble;
- serial/infrared plus empty peripheral runtime;
- joypad plus event-owned held P1 input and sampled physical P1-P4 masks.

Subsystem hashes use a versioned, domain-separated canonical walker over detached `StateValue`
types. Physical input is a live service latch outside StateFile, so checkpoints append its four
stable `JoypadButtonMask` bytes explicitly to both the full and input hash domains. It does not
affect the other six subsystem domains. Hashing does not use reflection ordering, object identity,
`toString()`, or Java serialization. Playback compares a checkpoint immediately after its tick. At
the first mismatch it stops before another input or tick and returns the tick, frame,
expected/actual hashes, and the mismatching subsystems.

Let `T` be `ClockSpec.controllerTicksPerFrame()`, let `(t0, f0)` be the initial tick and frame, and
let `d = checkpoint.tick - t0`. Every checkpoint must use the exact nonnegative-integer formula:

```text
checkpoint.frame = f0 + (d / T) + (if d % T == T - 1 then 1 else 0)
                 = f0 + floor((d + 1) / T)
```

Every checkpoint except the final one is frame-aligned: `d % T == T - 1`, so it describes state
after the last tick of a complete controller frame. The final checkpoint may be mid-frame but must
still use the same formula. The v1 player accepts only `t0 = 0` and `f0 = 0`, including when the
machine itself came from an embedded in-progress state.

## Playback work budget

`ReplayPlayer.playToEnd(maximumTicks)` has no unbounded or default-budget form. For active playback,
the caller must supply a positive maximum number of ticks it is willing to execute synchronously.
Playback returns on the final checkpoint or first divergence; if neither occurs within that budget,
it throws `EXECUTION_BUDGET_EXCEEDED`. The encoded final tick defines replay duration, but never
silently authorizes that amount of work. Callers that schedule work incrementally can use `step()`
and retain the equivalent budget policy themselves.

## Bounds

Replay v1 enforces these structural and resource boundaries. Byte and aggregate-count limits needed
for allocation or decompression are checked before proportional work:

| Resource | Limit |
|---|---:|
| total file / aggregate decoded sections | 64 MiB |
| one encoded or decoded section | 32 MiB |
| embedded StateFile | 32 MiB |
| sections | 16 |
| input records at one tick | 64 |
| input records in total | 1,000,000 |
| maximum input/checkpoint tick | `9,223,372,036,854,775,806` (`Long.MAX_VALUE - 1`) |
| hash checkpoints | 65,536 |
| metadata section | 16 KiB |
| user note | 4,096 characters |
| canonical profile ID | 32 UTF-8 bytes |

Checksums, reserved fields, exact fixed-record lengths, UTF-8, section order, duplicate IDs,
compression completion, trailing data, and decoded-size totals are all validated.

## Disabled-path cost

Normal emulation does not instantiate a recorder/player and does not encode or hash state. The core
input observer is null by default. Legacy and physical source changes perform one null check only
when a real source transition already occurred; unchanged master ticks allocate nothing and execute
no replay callback. Periodic state hashing is recorder-only work.

## Golden fixture

The synthetic-ROM fixture lives at
`controller/src/test/resources/replay-v1/synthetic-input.cgbreplay`. Its README records provenance,
size, and SHA-256. Tests inspect it without a ROM, decode and re-encode identical bytes, then build
the documented synthetic ROM and verify deterministic playback through the final hashes.

Normal tests never rewrite the fixture. Its test-only updater is enabled explicitly with:

```bash
mvn -B -pl controller -am \
  -Dtest=ReplayGoldenFixtureTest \
  -DcoffeeGb.updateReplayGolden=true test
```

Review the binary diff, inspector output, size, and SHA-256 before committing an intentional format
change. A byte change to a v1 fixture requires either a demonstrated canonical-codec correction or
a new format/schema version; it must not silently redefine released bytes.
