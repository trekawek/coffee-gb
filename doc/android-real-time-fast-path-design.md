# Core execution modes and real-time fast paths

Status: implementation design for the next performance pass. The Android M2 matrix is a
practical baseline for measuring progress; it is deliberately not a release-quality statistical
or device-lab protocol. More elaborate benchmark hardening is deferred until the core fast paths
have demonstrated value.

The important boundary is the core. Android, Swing, and the controller choose a mode and consume
its outputs; they do not implement emulation shortcuts.

## Objective

Add an opt-in performance executor that makes the existing emulator fast enough for sustained
real-time operation while keeping the current executor as the reference. The required hardware
rows stay separate:

| Row | Effective hardware | Real-time cadence |
| --- | --- | ---: |
| DMG | DMG | 59.7275 FPS |
| MGB | MGB | 59.7275 FPS |
| Native CGB | CGB with a color cartridge | 59.7275 FPS |
| CGB0 | CGB revision 0 with a color cartridge | 59.7275 FPS |
| CGB DMG-compatibility | CGB hardware, non-color cartridge, effective DMG compatibility | 59.7275 FPS |
| SGB | SGB | 61.168 FPS |
| SGB2 | SGB2 | 59.7275 FPS |

The cadence is derived from the core clock (4,194,304 / 70,224 for legacy/CGB/SGB2 and
47,250,000 / 772,464 for SGB), not from an Android timer or an overclocked emulation loop.
Native CGB, CGB0, and CGB DMG-compatibility are different rows even when they use the same
cartridge in a diagnostic run. A requested profile is not evidence of the effective profile:
the benchmark records the state selected by the core after boot.

Performance mode must keep audio enabled and continuous, produce every physical frame, and retain
the same input, serial, SGB, and mapper semantics. Frameskipping, muting, clock overclocking,
duplicate-frame counting, and presentation-accounting tricks are not performance techniques.

## Core API and ownership

The stable public API is:

    eu.rekawek.coffeegb.core.ExecutionMode
    Gameboy.GameboyConfiguration#setExecutionMode(ExecutionMode)
    Gameboy.GameboyConfiguration#getExecutionMode()
    Gameboy#getExecutionMode()

ExecutionMode has two values:

- ACCURACY: the current T-cycle/dot-accurate executor. This is the default and remains the
  reference implementation.
- PERFORMANCE: guarded exact batching and compatible simplifications. It may use a faster
  strategy only while its assumptions hold and must deoptimize to ACCURACY when they do not.

The mode is selected when a Gameboy session is constructed. It is session metadata, not part of
the emulated hardware state. Core code owns all mode-dependent strategy seams; it must not depend
on Android, Swing, controller, display, or audio classes. Frontends may expose a mode selector, but
they only pass the selected value into GameboyConfiguration.

Runtime switching is not required for the first implementation. A frontend setting change may
stop and recreate the session with the new configuration. If switching is added later, it is
allowed only at a verified owner-thread boundary (or by recreating the session); never switch
halfway through a CPU bus cycle, PPU fetch, audio block, DMA transfer, or SGB event.

### Shared semantics

Both modes use the same canonical machine state and public event boundaries. Gameboy.tick() is
still one master T-cycle in the reference model, and all fast paths must preserve the order and
phase of CPU, Timer/DIV, APU, DMA/HDMA, Serial, Joypad, GPU/STAT, mapper, SGB, LCD, and frame/audio
events. The derived timing documents remain normative:

- [PPU/STAT model](derived/ppu-stat-model.md)
- [CPU interrupt model](derived/cpu-interrupt-model.md)
- [APU model](derived/apu-model.md)

An exact batch advances a known interval and stops at every externally observable event. A
guarded simplification may speculate only with a complete core-owned checkpoint and private
outputs; on an invalidating event it discards the candidate work, restores the checkpoint, and
replays with ACCURACY. No partial frame, audio sample, serial byte, SGB packet, mapper side
effect, debug callback, or host event may escape before commit.

Typical deoptimization triggers include a CPU-visible read/write, interrupt edge, Timer/DIV edge,
DMA/HDMA ownership change, PPU register or VRAM/OAM write, audio register write, speed switch,
SGB transfer, active endpoint, debugger/history/replay observer, save/restore, or an unknown
hardware/profile state. The guard is fail-closed: an uncertain case uses Accuracy.

### Event-horizon executor

Instruction, PPU, and APU batching share one prerequisite: core components must state how far they
can advance before scalar ordering matters. A horizon is measured in master T-cycles and is
half-open: `nextEventHorizonTicks(max)` returns the largest `n` for which the next `n` ticks contain
no observable boundary. A boundary on the next tick returns zero. `advanceExact(n)` is valid only
for a positive `n` within that horizon and updates canonical component state directly.

The first executor quantum is deliberately small: at most four T-cycles in normal speed and two in
CGB double speed. It is eligible only when every participating component agrees. Initially that
means an ordinary owner-thread session with no debugger/history/replay observer, active link or IR
endpoint, SGB transfer, clocked cartridge, DMA/HDMA hand-off, speed switch, pending input, or
unresolved PPU write. CPU horizons stop before a bus or interrupt boundary; Timer and Serial stop
before their next edge; audio stops before a channel, frame-sequencer, register, or buffer event;
PPU/STAT stop before a dot, mode, line, lock, interrupt, write, or frame boundary.

`Gameboy.tick()` remains the exact one-T public reference. A bounded Performance executor may use
the minimum component horizon; otherwise it immediately performs one Accuracy tick. Save/debug,
restore, replay, and netplay boundaries materialize any transient cursor first. Horizon and cache
fields are derived session data and never enter portable hardware state.

The scheduler is introduced as vertical slices rather than a generic framework:

1. Skip only phase/quiet work for CPU, Timer/DIV, inactive Serial, settled Joypad, and inactive DMA.
2. Add exact constant-output APU blocks while still emitting every stereo sample.
3. Add edge-free PPU/STAT spans while retaining both calibrated pixel machines.
4. Extend CPU batching only between non-bus micro-operations and consolidate deoptimization
   reporting.

Each slice must delete real scalar work and pass its whole-core throughput gate. Merely moving the
existing per-T loop behind a new API is not an optimization.

## Save states, replays, and netplay

Save states remain canonical hardware-state captures. They do not serialize ExecutionMode or
fast-path cursors. A state saved in either mode must load into a session configured for either
mode and continue from the same canonical state; any derived fast-path cache is rebuilt after
restore. Adding a persistent field to a fast path requires the normal memento, legacy-import, and
save/restore differential coverage.

Replays and netplay must not silently mix execution semantics. A replay records its mode metadata
and either requires the playback session to use the same mode or deliberately replays in
ACCURACY. Netplay peers negotiate matching modes; if they do not match, both sides fall back to
ACCURACY before starting. A later cross-mode determinism proof may relax this rule, but a visual
similarity check is not proof.

## Frontend propagation

The setting follows one path:

| Surface | Required behavior |
| --- | --- |
| Controller/session | Add mode to the session configuration and expose the effective mode in session diagnostics. |
| Persisted application settings | Store the user's preference as ACCURACY or PERFORMANCE; invalid/missing values load as ACCURACY. |
| Swing | Add a selector to the existing emulation/preferences UI and restart the session when it changes. |
| Android | Add a selector and benchmark launch option; create the core configuration before starting the emulation thread and show the effective mode in diagnostics. |
| Headless/Agent | Accept the mode in construction/options and report it in run metadata; default to ACCURACY. |

No frontend should branch on individual batching strategies. The only frontend-visible policy is
the requested mode and the core-reported effective mode/deoptimization status.

## Practical benchmark baseline

M2 is intentionally small enough to run while developing the core:

1. Run ACCURACY and PERFORMANCE independently with the same ROM, input, hardware profile, audio,
   and visible-output settings.
2. Warm up once, then measure a fixed 600-frame window with audio on. Record effective row, mode,
   physical ready frames, successfully presented frames, drops, elapsed time, and audio
   continuity. A missing/dropped/corrupt frame or audio interruption makes that run incomplete.
3. Compare before/after medians per row and retain the change only when it is faster without
   changing the Accuracy differential. Use the same seven rows above; no family average fills a
   missing row.

The benchmark may use the existing paired parent/candidate runner, but it need not grow new
bootstrap, thermal, SurfaceFlinger, or device-control machinery for this phase. Those controls are
follow-up work. The immediate goal is a trustworthy enough signal that a core change helps on the
phone while preserving real cadence and audio.

## Small implementation chunks

Each chunk is one small, independently revertible commit. Every performance commit includes:

- a before/after measurement for each row it exercises;
- Accuracy-vs-Performance differential tests from the same deterministic action stream;
- save/restore continuation coverage at the new batch boundary;
- the focused core tests plus the relevant compatibility suite.

If a differential trace, event order, tick count, audio/frame count, or memento test changes,
disable the fast path and revert the commit. A speedup that only works with Accuracy disabled
or output suppressed does not count.

### 1. Core mode API and default

**Change:** Keep ExecutionMode in core, propagate it through GameboyConfiguration, and select the
executor at session construction. Keep Accuracy as the default and expose the effective mode from
Gameboy.

**Acceptance:** Existing callers compile unchanged; default configurations and restored sessions
are ACCURACY; explicit PERFORMANCE survives configuration copies; no mode field is added to
portable hardware mementos.

**Tests/measurement:** Core API/default tests, configuration-copy tests, save-state load in both
modes, and an Accuracy before/after run proving unchanged frames, audio, and tick counts.

### 2. Controller exposure and persistent setting

**Change:** Add the mode to controller session/configuration APIs and persisted application
settings. Apply it before the emulation owner thread starts.

**Acceptance:** Missing or invalid settings use Accuracy; a changed setting creates a new session
with the requested mode; an active session never changes mode mid-instruction.

**Tests/measurement:** Persistence round trip, session restart, controller/headless construction,
and replay/netplay mismatch tests. Compare controller overhead with the setting disabled.

### 3. Desktop/Android selectors and diagnostics

**Change:** Add Swing and Android selectors plus benchmark launch options. Report requested and
effective mode, including a visible deoptimization-to-Accuracy indication when applicable.

**Acceptance:** UI selection affects the next session only; benchmark logs identify the effective
mode and all seven hardware rows independently; no Android/Swing class is referenced by core.

**Tests/measurement:** Swing preference/session test, Android option/diagnostic unit test, headless
smoke test, and one visible phone run in each mode with audio on.

### 4. Exact event-driven peripheral batching

**Change:** In core, batch idle intervals for Timer/DIV-derived peripheral work, Serial, Joypad,
and other peripherals only to the next observable event. Keep the existing subsystem ordering and
clock phases.

**Acceptance:** A batch stops before every MMIO/interrupt/DMA/speed/input/serial/SGB/audio event;
physical frame/audio counts and cadence remain unchanged; uncertain intervals immediately use
Accuracy.

**Tests/measurement:** Generated event traces, phase sweeps around Timer/Serial/DIV edges, input
and SGB tests, arbitrary save/restore at batch endpoints, and per-row before/after ticks/sec.

### 5. Guarded scanline/tile-batched PPU

**Change:** Add a core-owned line/tile strategy shared by DMG, MGB, native CGB, CGB0,
DMG-compatibility, and SGB lanes where their semantics permit. Stop at CPU-visible PPU writes,
fetch/FIFO changes, STAT edges, DMA/HDMA, LCD transitions, and SGB transfer boundaries.

**Acceptance:** Stable lines use the fast strategy; every invalidating write deoptimizes before
the affected dot; no frame is published from an uncommitted line.

**Tests/measurement:** Mealybug, DMG acid, CGB acid/GBC hardware, CGB0 timing, SGB border/transfer,
mid-line write differential traces, save/restore at every line event, and per-row measurements.

**First retained slice (2026-08-21):** PERFORMANCE may defer and replay only the unshifted DMG
timing skeleton for a normal-speed, sprite-free, window-free steady background line on the DMG
and MGB profiles. The shifted pixel-producing machine, output delay, frame publication, APU, CPU,
DMA observation, and master-tick cadence still run once per T. Writes, reads/observers, DMA
ownership, delayed PPU writes, FIFO deoptimization, window history, save/restore, and mutable
component alias escape materialize or disable the slice before it can affect canonical state.
Every other profile and line shape remains scalar.

The keep gate used a synthetic in-memory loop with visible output and all four APU channels
enabled: 5,000,000 warm-up T followed by 30,000,000 measured T, six interleaved samples per mode.
All samples produced 427 physical frames. Median throughput was 17.952M T/s in ACCURACY versus
18.694M T/s in PERFORMANCE on DMG (+4.13%), and 18.173M versus 18.682M T/s on MGB (+2.80%).
Differential coverage compares full canonical state at arm, uninterrupted full-span endpoints,
line boundaries, invalidating writes, retained aliases, window-history fallback, cross-mode
restore continuation, visible-frame hashes, and audio-buffer hashes.

**Second retained slice (2026-08-21):** The same exact timing cursor also covers native CGB and
CGB0 steady background lines. The specialized fetcher preserves CGB tile-map attributes, VRAM
bank selection, X/Y flips, palettes, and tile addressing while the shifted color pixel machine
continues to produce every visible pixel on its ordinary dot path. CGB DMG-compatibility, SGB,
and SGB2 remain scalar in this native-color slice; CGB DMG-compatibility is covered by the
following compatibility-specific slice. The cursor cannot arm before boot compatibility is
resolved, during OAM or VRAM DMA, after a tile-select conflict, in double speed, or across a
register/memory, debugger, history, save-state, or mutable-alias boundary.

The keep gate used a generated CGB-compatible loop with visible color output and all four APU
channels enabled: 20,000,000 warm-up T followed by 100,000,000 measured T in eight alternating
Accuracy/Performance pairs per revision. Native CGB improved from 17.408M to 17.871M T/s
(+2.59%; paired median +2.90%), and CGB0 improved from 17.296M to 17.978M T/s (+3.79%; paired
median +3.97%). Every pair improved. Each mode produced the same 1,424 physical frames, 1,430
audio buffers, 99,964,150 stereo sample frames, and identical frame/audio hashes. A scalar-only
control run measured just +0.77% CGB and +0.18% CGB0 apparent mode difference, below the retained
effect. Focused full-span, boot, GDMA, compatibility, invalidation, and cross-mode restore tests
were followed by the full core unit suite, Mealybug, DMG/CGB acid, Mooneye, and Blargg suites.

**Third retained slice (2026-08-21):** The same scalar CGB timing FIFO is also eligible for the
ordinary CGB hardware with a non-color cartridge after the boot compatibility handoff has
resolved. The shifted color pixel machine remains responsible for the visible DMG-compatible
palette/BGP/OBP behavior, so the cursor only defers the unshifted timing skeleton and does not
change compatibility rendering or cadence. CGB0 DMG-compatibility remains explicitly scalar
until its revision-specific timing is measured; native CGB0 remains eligible under the preceding
slice. VBK/SVBK compatibility masking, fine-SCX line spans, boot-resolution gating, write
materialization, and cross-mode frame/audio continuation are covered by synthetic differential
tests.

The compatibility keep gate used the same generated visible-output loop with all four APU
channels enabled: 20,000,000 warm-up T followed by 100,000,000 measured T in eight alternating
Accuracy/Performance pairs. Both modes produced 1,424 physical frames per run. Accuracy's median
throughput was 17.996M T/s versus 18.462M T/s in PERFORMANCE (+2.59%); every alternating pair
improved. The comparison is against the prior scalar compatibility path, with no frame skipping,
audio suppression, or clock change.

### 6. Instruction-level CPU batching

**Change:** Batch CPU instructions/machine cycles only between observable bus and interrupt events.
Preserve the calibrated late-read behavior, free-running CPU phase, HALT/STOP, DMA/HDMA arbitration,
and CGB speed domains.

**Acceptance:** A batch never crosses a bus read/write, interrupt request/acknowledge, timer edge,
DMA/HDMA handoff, speed switch, or debugger/replay boundary. Accuracy remains the fallback.

**Tests/measurement:** CPU interrupt and timing suites, HALT/STOP and DMA/HDMA tests, CGB speed
switch tests, arbitrary instruction-boundary save/restore, differential traces, and per-row
before/after measurements.

### 7. Block-based APU generation

**Change:** Generate APU output in exact blocks split at register writes, frame-sequencer events,
triggers, wave-RAM access, and other observable boundaries. Keep sample phase, channel timing,
length counters, and audio event shape canonical.

**Acceptance:** Audio remains enabled, continuous, and sample-count compatible with Accuracy; a
register write or trigger ends the current block before the next block starts; failed block guards
deopt to Accuracy without muting or dropping audio.

**Tests/measurement:** DMG/CGB sound suites, wave/length/trigger edge tests, sample hash or
deterministic audio comparison, save/restore inside a block, frame/audio event differential, and
per-row audio-on measurements.

### 8. Automatic deoptimization and compatibility coverage

**Change:** Consolidate guard state and deoptimization reasons in core. Make every strategy report
whether it committed or fell back, without exposing strategy details to frontends.

**Acceptance:** All uncertain/unsupported cases select Accuracy; deoptimization is observable in
diagnostics but does not alter hardware state; no fast path is enabled for an unknown effective
profile. The supported compatibility corpus passes in both modes.

**Tests/measurement:** Guard truth-table and transition tests, forced invalidation at every event
type, full DMG/MGB/CGB/CGB0/CGB-compat/SGB/SGB2 regression runs, cross-mode save/restore,
replay/netplay mode policy tests, and a final per-row before/after report. Promote only changes
that preserve Accuracy traces and improve the measured rows; otherwise leave the strategy disabled.

## Completion criteria

The program is complete when PERFORMANCE is a real core-owned executor, ACCURACY remains the
default reference, and every required row can run with visible output, continuous audio, and its
real hardware cadence. The report must show the mode, effective hardware row, before/after
throughput, frame/audio counts, and differential/compatibility results for all seven rows. A
missing row, muted audio, skipped frame, overclocked clock, or silently relabelled profile is an
incomplete result.
