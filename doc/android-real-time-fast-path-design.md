# Android real-time fast-path design

Status: implementation design from `cbdef341`. This is a sequence of small, independently
revertible commits. It does not change the calibrated timing model, and it is not a replacement
for [emulation-performance-next-pass.md](emulation-performance-next-pass.md), which remains the
prior local-call-count optimization program.

## Objective and evidence

The objective is sustained, visible, audio-on performance of at least 60 FPS on the Redmi 15C for
each of the seven rows below, measured and reported independently. A family average or proxy row
can never satisfy a missing row.

| Row | Hardware/effective mode | Status at this design point |
| --- | --- | --- |
| DMG | DMG-family timing and output | Audio-on visible parent baseline required; 22.889 FPS is audio-off diagnostic only |
| MGB | MGB profile and timing/output | Independent baseline required; no DMG proxy |
| Native CGB | CGB profile with an authentic color cartridge | Baseline pending authentic color-cartridge run |
| CGB0 | CGB0 revision with an authentic color cartridge | Baseline pending authentic color-cartridge run |
| CGB DMG-compatibility | CGB profile, non-color cartridge, `SpeedMode.isDmgCompat() == true` | Independent row; it is not DMG hardware |
| SGB | SGB timing, packet handling, border/output | SGB with audio: 21.847 FPS |
| SGB2 | SGB2 timing, packet handling, border/output | Independent baseline pending |

Forcing a CGB profile on a non-color cartridge is a useful compatibility-mode probe, but it must
never be labelled native CGB. After boot, sample the actual core state (including
`SpeedMode.isDmgCompat()` and the cartridge's effective color mode), rather than trusting the
requested benchmark option. If an authentic color cartridge is unavailable, native CGB/CGB0 is
`pending`, never substituted by forced CGB or a non-color cart. Each result must include profile
ID, family, effective CGB/DMG-compat modes, speed mode, clock identity, audio state, and render
mode. No one profile's result represents another profile.

The present forced-DMG profile has `Gameboy.tick` at 21.92% self time, `Gpu.tick` at 10.94%,
`Sound.tick` at 8.24%, `Cpu.tick` at 6.50%, `DmgPixelFifo.outputTick` at 6.18%,
`StatRegister.tick` at 5.74%, `PixelTransfer.tick` at 5.00%, and `Fetcher.advance` at 3.76%.
The named PPU group is about 29.07% of sampled self time, so deleting that group alone has an
Amdahl ceiling near 32.3 FPS from the 22.889 FPS diagnostic. Reaching 60 requires approximately
2.62x overall speedup and therefore CPU/APU/core work in addition to PPU work. The 22.889 result
is not the required DMG or MGB audio-on visible parent baseline.

## Semantics that every fast path preserves

`Gameboy.tick()` remains one master T-cycle. The complete calibrated dependency contract is:

1. master-tick instrumentation and warm-reset application;
2. pre-subsystem cartridge clock, then optional slot-cartridge clock;
3. STAT CPU-read pre-phase;
4. Timer;
5. DIV-derived APU frame-sequencer sample and commit placement;
6. CPU, HDMA, and speed-switch arbitration;
7. speed-switch callbacks, CPU halt-state latch, deferred frame commit, and DIV-reset
   consumption (including APU frame commit and Serial DIV-reset handling);
8. OAM DMA;
9. APU channel tick and sample production;
10. Serial, infrared, and joypad;
11. CGB HDMA CPU-phase arbitration;
12. GPU, then STAT, then CPU peripheral completion;
13. post-subsystem boot-compatibility resolution, GPU-to-HDMA update/timing notifications, and
    CGB HDMA halt-opcode latching;
14. STOP-frame blanking; LCD-off transition/blank cadence and HDMA LCD switch; or VBlank frame
    ready, SGB VRAM-transfer frame-ready, presentation-suppression latch, and refresh cadence.

Batching must preserve this entire order, including cartridge/slot clocks before the subsystem
loop and post-loop HDMA/LCD-off/STOP/frame/SGB events. A CPU read sees GPU state from the preceding
tick, and a frame-ready event is not equivalent to host presentation.

The derived specifications are normative: `doc/derived/ppu-stat-model.md`,
`doc/derived/cpu-interrupt-model.md`, and `doc/derived/apu-model.md`. In particular, preserve the
one-M-cycle-late CPU read calibration, free-running CPU phase, 455/456-dot line grid, two-machine
PPU skew, STAT edge/level ordering, CGB double-speed clock domain, APU frame-sequencer phase,
wave-RAM and length-counter rules, and exact SGB `ClockSpec` rational arithmetic. Correcting a
calibrated timing quirk is a separate accuracy project, never an incidental optimization.

### Exact batching versus speculation

Exact batching is a different implementation of a known interval. It advances every emulated
state transition and observable event that lies in the interval, preserves the same committed
state at every public boundary, and never needs rollback. A batch may stop early at a CPU bus
strobe, PPU write, interrupt edge, DMA ownership change, frame/audio event, or any other event
that a caller can observe.

Guarded speculation runs a faster implementation under an explicit assumption, but treats its
state and output as private until the interval commits. An invalidating event causes a complete
restore and reference replay. Speculation is correct only when the rollback covers all emulated
state and no external side effect escaped the transaction.

The reference executor is the default. FAST is opt-in and deoptimizes to reference on every
uncertain condition. DIFFERENTIAL runs both implementations against the same deterministic action
stream and stops at the first committed-state divergence. A fast result is not accepted merely
because its final frame looks right.

### No-side-effect and transaction rules

An exact path may publish only the events that the reference path publishes at the same canonical
tick. A speculative path must not call or expose:

- `Display` frame events or partial host frames;
- `SoundSampleEvent`, audio sinks, or output observers;
- SGB packet, border, background, or VRAM-transfer event buses;
- serial/infrared endpoints;
- RTC or wall-clock services, battery flushes, mapper side effects, or rumble;
- debug hooks, breakpoints, input-timeline observers, or replay metadata.

`restoreStateSilently()` suppresses effects during restore, but it does not undo effects emitted by
the speculative forward pass. Speculation therefore needs a transactional event journal/private
output buffers, or it must be disabled for that profile and condition. SGB event traffic and any
active external endpoint are immediate deoptimization conditions until journaling is proven.

Every persistent timing field, cursor, clock phase, queue age, latch, or in-flight bus state must
be represented in its component `ComponentState`, `MachineStateCapture`, and compatibility import
path. A differential rollback checkpoint is an immutable, deep-owned capture containing all
emulated state, including deterministic RTC/wall-clock state, DMG FIFO runtime state, Serial
runtime state, input state, and the action cursor. `MachineStateCapture` may be used for
synchronous hashing only; it is not a rollback object unless all borrowed data has been copied
into the immutable checkpoint. Derived caches and eligibility bits are transient and recomputed
after restore. Preserve the two independent `PixelTransfer` states until a causal replacement
has an explicit mapping for both. The emulated memento must be complete, while transient host
presentation-suppression policy stays outside that memento and unchanged. Prefer the existing
runtime-state supplement pattern (for example, the DMG FIFO runtime state) over changing released
Java-serialization record descriptors without an importer.

## Differential oracle and state fingerprints

The oracle is test-only and uses generated in-memory DMG/CGB programs. SGB rows run generated DMG
programs under SGB/SGB2 profiles. It loads, emits, and retains no external/user ROM or save-file
payload. Synthetic machine-owned program bytes and immutable deep-owned checkpoints are allowed
inside the test machine, but recorders retain neither those payloads nor frame/audio/event arrays.
Disable battery persistence, use service-free mapper/RTC doubles with deterministic wall-clock
state, and construct no slot cartridge. If a mapper/RTC or runtime component cannot be captured
by the state supplement, deopt that case to reference rather than using a partial checkpoint.

Each action is timestamped in master ticks, has an explicit phase and write origin, and is
deterministic:

- MMIO read/write and selected memory writes;
- joypad/input transitions;
- speed-switch, serial, DMA, and SGB control actions where applicable;
- checkpoint, save, restore, and replay actions.

Reference and candidate machines use isolated synchronous event recorders. Each record contains
event type, phase, write origin (CPU, DMA, debugger, boot, or fixture), master tick, a same-tick
ordinal, small scalar fields, and compact hashes of frame/audio/state contents. Immediate MMIO read
values are compared at the read action, and same-tick events are compared by ordinal, not merely
as an unordered set. Hashes are accumulated while data is observed; external/user payloads are
never retained. A mismatch reports the first subsystem, tick, phase, origin, and ordinal, then
replays from the preceding immutable checkpoint with the same action stream. The canonical
fingerprint covers CPU/interrupt, Timer/DIV, GPU/STAT/locks, both PPU machines and output delay,
DMA/HDMA, APU channel/frame state, Serial, Joypad/SGB state, cartridge mapper state, input/action
cursor, and profile/effective-mode identity.

Every fast-path commit must pass arbitrary-phase save/restore continuation equality, not only
frame-boundary equality. At minimum, sweep LCD-enable/first-line, line 153/0, mode-3 writes,
object/window stalls, STAT checkpoints, Timer overflow, APU trigger/frame edges, serial transfer
edges, DMA/HDMA ownership, CGB speed switching, SGB packet activity, and every output-delay dot.

## Redmi paired-run protocol

The Android matrix alternates parent and candidate runs rather than comparing separate days. A
paired dataset has exactly two known build identities: one parent build ID and one candidate build
ID. Every parent run must use the former and every candidate run the latter; mixing identities
within either side rejects the dataset. The two sides use the same benchmark configuration,
profile row, cartridge/input script, audio/render settings, release/profileable mode, device build,
display refresh, charger state, screen-awake policy, process priority, and background-load policy.

Randomize the order of the seven required rows for each measurement block, use the same row/config
for the paired parent/candidate observations, alternate which build runs first, and collect at
least 12 pairs per row. Warm up identically and apply a predeclared cooldown between runs. Record
pre/post temperature and sustained-clock/thermal telemetry; a pair is invalid and must be rerun
after cooldown if the device enters thermal throttling or exceeds the predeclared temperature/
clock validity window. Keep profiler/diagnostic overhead out of measured runs.

The core's physical `frame-ready` event and the host's actual presentation completion are separate
signals. Record unique ready IDs/timestamps (`R`) and presentation IDs/timestamps (`P`), plus
dropped, duplicated, late, and corrupted presentation counts. The visible/audio-on acceptance row
requires exactly 600 physical ready frames, exactly 600 successfully presented frames, zero drops,
duplicates, late/corrupt frames, and matching audio continuity; otherwise the run is incomplete,
not a low FPS sample. Audio-off and frame-suppressed runs remain diagnostics only.

For a valid run with presentation timestamps `p[0] ... p[P-1]` from a monotonic clock, the exact
absolute candidate statistic is interval FPS
`F_abs = (P - 1) / (p[P-1] - p[0])`, with the denominator in seconds and `P = 600`. Report
physical-ready interval FPS separately as `(R - 1)/(r[R-1]-r[0])`. `N/elapsed` over a whole
measurement window is a count-throughput statistic and is not interchangeable with the interval
statistic `(N-1)/(last-first)`; the latter is the only absolute presentation gate. Also report
presented/dropped counts and wall-window/count throughput for diagnosis.

For each individual row `q`, pair run `i` by its immutable pair ID and calculate
`g[q,i] = 100 * (F_candidate[q,i]/F_parent[q,i] - 1)`. With a fixed seed and at least 10,000
resamples of the pair IDs, bootstrap the per-row median `F_candidate` and its 95% percentile CI,
and the per-row median improvement `g` and its 95% CI. A confirmed per-row regression whose CI is
below -3% rejects; an interval crossing the 3% band is inconclusive, not a gain. Report all seven
row CIs; family averages can never hide a missing or regressing row. An individual valid run below
58 FPS is an alarm requiring rerun/investigation.

The final absolute gate is a per-row candidate-FPS CI lower bound of at least 60 FPS, with no
individual valid run below 58 FPS, zero presentation corruption/drop, and audio plus visible
output enabled. A missing native-CGB, CGB0, CGB-compatibility, MGB, SGB, or SGB2 row means the
program is incomplete, regardless of the DMG result.

## One-commit work plan

Every item below is one commit. Do not combine adjacent items. Each item has a focused test gate,
then the full matrix, then the paired Android rows. Revert the individual commit when its gate
fails; leave the reference path intact. Performance effects are evaluated per each of the seven
rows using the paired bootstrap CIs above: medium/high complexity requires the per-row improvement
CI lower bound to be >=3% (with no row regression over 3%), while a tiny exact guard may use a
smaller predeclared effect threshold but must still be measurable and non-regressing in every row
it exercises. No family aggregate is an acceptance substitute.

### M1 — benchmark profile options and effective-mode classification

**Scope:** Add independent DMG, MGB, native CGB, CGB0, CGB-compatibility, SGB, and SGB2 options,
audio on/off, and visible/frame-sink output. After boot, sample actual core state for effective
`gbc`, cartridge color mode, `SpeedMode.isDmgCompat()`, speed mode, and `ClockSpec`; do not infer
compatibility from the requested option. Candidate seams are
`android/app/src/main/java/eu/rekawek/coffeegb/android/DiagnosticsOptions.java`,
`AndroidEmulationRuntime.java`, and `AndroidBenchmarkDiagnostics.java`.

**Guards/invalidation:** This is harness-only. Reject an invalid profile/cartridge combination;
do not silently convert a native-CGB row into a compatibility row or vice versa. If an authentic
color cartridge is unavailable, mark native CGB/CGB0 unavailable rather than substituting forced
CGB or a non-color cart.

**State/memento:** No emulator state changes. Diagnostics remain redacted and host-only.

**Tests:** `DiagnosticsOptions` parsing, profile identity/effective-mode classification,
`HardwareProfileRegistryTest`, and a synthetic configuration test for every registry profile.

**Rows/acceptance:** Produce independent parent baselines for all seven rows, including required
audio-on visible DMG/MGB baselines; 22.889 remains diagnostic audio-off data only. Native CGB/CGB0
remain pending until authentic color-cartridge runs exist. Harness-only overhead must be absent
from the release path; otherwise revert.

**Expected ceiling:** No emulator speedup; measurement correctness only.

### M2 — paired-run and statistical matrix tooling

**Scope:** Add a host/parser or benchmark-side result format for the exactly-two-build alternating
parent/candidate pairs, randomized seven-row order, 600-ready/600-presented validation, exact
interval FPS, per-row bootstrap median/effect CIs, and final 60/58 FPS gates. Reuse bounded
metadata from `AndroidBenchmarkDiagnostics`.

**Guards/invalidation:** Tooling must reject missing rows, mixed build identities, mixed audio/render
modes, thermal/device mismatches, and fewer than 12 paired runs. It must not ingest ROM/save paths
or payloads.

**State/memento:** No emulator state changes; statistical accumulators are benchmark-local.

**Tests:** Parser/property tests with synthetic logs, missing-row tests, bootstrap determinism,
and explicit regression/alarm/lower-bound cases.

**Rows/acceptance:** Run all seven M1 rows, including native CGB/CGB0 only when available. Require
machine-readable per-row parent/candidate build IDs, ready/presented/drop counts, interval FPS,
median CIs, improvement-effect CIs, and thermal-validity alarms.

**Expected ceiling:** No emulator speedup; without valid paired statistics, later gains are not
accepted.

### O1 — synthetic duplicate-reference timeline recorder

**Scope:** Build a test-only runner that executes a generated in-memory program on reference and
candidate machines with timestamped MMIO/input/save/restore actions. Record isolated synchronous
events with explicit action phase/write origin, immediate MMIO read values, and same-tick ordinals;
checkpoints are immutable deep-owned captures, not borrowed snapshots.

**Guards/invalidation:** No production hook, asynchronous listener, external/user ROM or save
payload, retained event payload, slot cartridge, battery persistence, or nondeterministic endpoint/
time source. Service-free mapper/RTC doubles are mandatory; an uncaptured runtime component
deopts the case to reference.

**State/memento:** No production state. Deep-owned checkpoints include RTC/wall-clock surrogate,
DMG FIFO runtime, Serial runtime, input state, and action cursor; `MachineStateCapture` is for
synchronous hashing only.

**Tests:** Generated DMG and CGB programs cover normal/compat CGB, CGB0, speed switch, DMA/HDMA,
Timer/Serial/STAT/APU edges, SGB joypad packets, immediate reads, same-tick ordering, and
first-divergence replay.

**Rows/acceptance:** All seven rows must reproduce identical compact traces for repeated reference
runs and identify an injected candidate divergence at the first timestamp. Recorder overhead is
excluded from Android throughput and must be zero in normal builds.

**Expected ceiling:** No emulator speedup; this is the oracle prerequisite.

### O2 — canonical subsystem fingerprints and save/restore replay

**Scope:** Define one test-only fingerprint schema and continuation checker over the existing
`Gameboy`/component state APIs. Hash arrays while visiting them; never retain payload arrays.
Use `MachineStateCapture` only for that synchronous hash visit; rollback uses immutable,
deep-owned state.

**Guards/invalidation:** Fingerprints/checkpoints are taken only at owner-thread safe points.
Battery persistence is disabled; mapper/RTC and wall-clock services are deterministic and
service-free; no slot cartridge, live endpoint, debug, or host input service is allowed. A missing
runtime-state supplement (including DMG FIFO or Serial runtime) deopts to reference.

**State/memento:** Immutable checkpoints deep-own all fields, including RTC/wall-clock state, DMG
FIFO and Serial runtime, input and action cursor. No new portable fields now; if a later fast path
adds one, the checker requires capture, restore, and legacy import before enabling it. Host
presentation-suppression policy remains transient/outside the emulated memento and unchanged.

**Tests:** Restore/continue at every generated action timestamp and every listed PPU/APU/clock
boundary; compare subsystem hashes, frame/audio hashes, and event order.

**Rows/acceptance:** All seven rows, both CGB effective modes, and both SGB clocks. Zero mismatches
and zero retained payload arrays are required.

**Expected ceiling:** No emulator speedup.

### O3 — opt-in FAST/DIFFERENTIAL execution boundary

**Scope:** Add a reference-default policy seam around `Gameboy.tick()`/`tickSubsystems()`. FAST is
disabled unless explicitly selected; DIFFERENTIAL runs reference and candidate with O1/O2 traces.

**Guards/invalidation:** Deopt to reference for debug instrumentation/retirement tracking,
history/replay, active endpoints or RTC, SGB event traffic, DMA/HDMA, speed switch, pending PPU
writes, host observers, and any unknown profile/effective mode. The guard must fail closed.

**State/memento:** Policy and guard caches are transient. A batch cursor or machine clock phase is
not transient and must be captured by the owning component.

**Tests:** Default-is-reference, policy transitions, restore recomputation, deopt at every guard,
and first-divergence DIFFERENTIAL tests on all rows.

**Rows/acceptance:** No correctness difference, no per-tick allocation, and no more than 1% paired
median regression in any row. Without those properties, revert the boundary.

**Expected ceiling:** 0–2%; this seam is not itself a completion claim.

### E1 — empty-output FIFO early return

**Scope:** In `DmgPixelFifo.outputTick()`, after incrementing `outputTicks`, return when
`delaySize == 0 && firstEntry < 0`. In `ColorPixelFifo.outputTick()`, after incrementing
`outputTicks`, return when `delaySize == 0`. Do not move the increment.

**Guards/invalidation:** The fast return is invalidated by any enqueue, rewind, first-pixel latch,
`clearOutput`, LCD/output reset, or state restore. The output machine remains authoritative.

**State/memento:** No new state. Existing delay stamps and `outputTicks` remain serialized exactly.

**Tests:** `PixelFifoTest`, DMG/CGB output-delay and first-pixel tests, save/restore at empty and
non-empty queues, Mealybug, CGB acid/hardware, and SGB frame-output tests.

**Rows/acceptance:** Measure DMG, MGB, native CGB, CGB0, CGB-compat, SGB, and SGB2 separately.
Keep only when the guard's per-row effect is measurable (at least 0.5% where the guard fires), no
row has a confirmed regression over 3%, and differential correctness is unchanged; no family
average may hide a row. Otherwise revert this tiny guard.

**Expected ceiling:** Approximately 1–3%.

### E2 — timing-skeleton scalar FIFO

**Scope:** Replace only the `renderOutput == false` skeleton FIFO with scalar DMG and CGB timing
implementations. Preserve queue length, object occupancy, cleared-background length, line pixel
count, delay age, rewind, and all fetcher-visible operations. Keep the visible shifted machine's
full `DmgPixelFifo`/`ColorPixelFifo`.

**Guards/invalidation:** Use the scalar implementation only for the timing skeleton. Never route
visible output, SGB VRAM-transfer pixels, debug inspection, or the output machine through it.
Native CGB and CGB-compatibility share CGB timing storage but retain separate output resolvers.

**State/memento:** Add explicit scalar runtime state to `PixelTransfer`/`Gpu`, including both
machines' delay ages and CGB cleared-background state. Preserve old snapshot import; do not assume
that the two released 46-field `PixelTransfer` states can be aliased.

**Tests:** FIFO/PixelTransfer tests, all PPU write synchronization tests, current Mealybug 24/24,
Mooneye/DMG-acid, CGB-acid/GBC hardware, SGB packet/VRAM-transfer, frame/audio differential, and
arbitrary save/restore through every queue operation.

**Rows/acceptance:** All seven rows separately. Medium complexity requires a per-row paired
improvement CI lower bound of at least 3% and no row regression over 3%.

**Expected ceiling:** Approximately 3–8% total; it cannot remove the duplicated fetcher/pipeline.

### E3 — exact output idle-span advance

**Scope:** Add an exact output-stage `advanceIdleSpan(n)` used only when the delay queue is empty,
the first-pixel latch is clear, no pixel-domain write is pending, and the next boundary cannot
publish output. It advances `outputTicks` by `n` without iterating empty output ticks.

**Guards/invalidation:** Stop at every line/frame boundary, pending write, queue enqueue, rewind,
LCD transition, debug boundary, SGB transfer boundary, and output event. Use relative delay ages;
do not derive time from wall clock or assume the legacy 69,905-tick frame for SGB.

**State/memento:** `outputTicks`/relative ages are machine state. Any idle-span cursor is captured
or recomputed from the canonical GPU tick and restored before the next output operation.

**Tests:** Empty/non-empty delay spans, first pixel, HBlank/VBlank tails, LCD disable, SGB/SGB2
rational cadence, CGB compatibility, frame hashes, and arbitrary restore at span endpoints.

**Rows/acceptance:** All seven rows separately. Require at least 1% measurable improvement in
each row and no row regression over 3%; otherwise revert the span scheduler.

**Expected ceiling:** Approximately 1–4% after E1/E2.

### K1 — transient execution-policy and guard seam

**Scope:** Move O3's policy selection into a compact transient owner-thread object so hot code sees
one guarded decision and immutable profile lane. Keep reference as the default and expose the
selected lane in diagnostics.

**Guards/invalidation:** Recompute on profile/effective-mode change, speed switch, restore, debug
hook update, endpoint attach, replay start, SGB packet start, DMA/HDMA, LCD transition, and pending
PPU write. Unknown states select reference.

**State/memento:** The policy object is transient. Persistent event cursors remain component state;
do not serialize Java object identity or a stale eligibility bit.

**Tests:** Guard truth-table and transition tests, restore/deopt tests, O1/O2 DIFFERENTIAL, and
zero-allocation fast/reference dispatch tests.

**Rows/acceptance:** All seven rows, with no more than 1% paired median regression and no
allocations.
This is infrastructure, not a speedup claim; revert if it adds measurable hot-path cost.

**Expected ceiling:** 0–2%.

### K2 — exact serial and joypad event cursors

**Scope:** Batch only proven idle subintervals. Serial may advance its free-running phase arithmetically
only for a NULL endpoint, transfer-off, zero wake delay, and no debug hook; preserve normal/CGB-fast
periods and DIV-reset phase. Joypad may advance to the next settled poll boundary only when input,
SGB packet state, JOYP selection, and observers are unchanged.

**Guards/invalidation:** Invalidate on SB/SC/JOYP writes, transfer start/completion, DIV reset,
interrupt acknowledge/wake, endpoint activity, input changes, SGB packet pulses, player selection,
debug/timeline observers, and speed/effective-mode changes. Do not reorder Timer→CPU→Serial.

**State/memento:** Existing serial clock, signal, received-bit, wake-delay, joypad filter, poll
phase, and SGB packet fields remain authoritative. New cursor/anchor fields must be captured.

**Tests:** Serial phase/DIV-reset/HALT replay, DMG/CGB normal-fast transfer suites, Joypad filter and
SGB packet tests, input timeline replay, full unit/integration battery, and all-profile O1/O2 traces.

**Rows/acceptance:** All seven rows separately. Require a per-row paired improvement CI lower
bound of at least 3% and no row regression over 3%; otherwise delete the production cursor and
retain only any test seam.

**Expected ceiling:** Approximately 1–4%; Joypad is already partly optimized.

### K3 — Timer/APU/CPU batching after clock-router evidence

**Scope:** Only after an exact clock-router/bus-cycle shadow proves the edge placement, batch Timer,
DIV-derived APU frame sequencing, and CPU microcycles between observable bus/clock events. Keep
channel algorithms exact and preserve the existing late-read calibration until a separate change.

**Guards/invalidation:** No batch across Timer/PPU/Serial/STAT writes, DIV/TAC/TIMA/TMA edges,
overflow/reload, APU trigger/wave-RAM access, interrupt request/acknowledge, CPU bus read/write,
DMA/HDMA, HALT/STOP, CGB speed switch, or SGB event boundary. CGB normal/double and DMG/SGB clock
topologies are separate lanes.

**State/memento:** Persist half-dot parity, CPU T-state/bus cycle, divider/ripple, overflow stages,
APU frame phase/pending step, and any clock-mux state. Resolved wires remain derived.

**Tests:** `cpu-interrupt-model.md` and `apu-model.md` phase sweeps, Timer/Serial/STAT precedence,
HALT/STOP, all sound suites, CGB speed/HDMA, SGB clock tests, O1/O2 differential, full battery,
and arbitrary save/restore.

**Rows/acceptance:** All seven rows, including native CGB/CGB0 double speed. High complexity
requires a per-row paired improvement CI lower bound of at least 3% and no row regression over 3%;
a failed clock-edge test reverts the whole K3 commit.

**Expected ceiling:** Approximately 5–15% once the old future-event lookahead is actually removed;
reordering existing callbacks is not an accepted implementation.

### P1 — line-plan eligibility counters and trace

**Scope:** Add transient per-line eligibility counters and invalidation reason bits around `Gpu`,
`StatRegister`, DMA/HDMA, and CPU PPU writes. This measures stable intervals before changing
behavior.

**Guards/invalidation:** Count, but do not skip, lines with CPU PPU reads/writes, LCD edges, pending
latches, objects/windows, DMA/HDMA, speed changes, STAT checkpoints, SGB transfers, or debug hooks.

**State/memento:** Counters and trace buffers are transient and disabled in release. No line plan is
serialized yet.

**Tests:** Eligibility trace versus reference for generated programs, every invalidation reason,
Mealybug/PPU timing tests, CGB native/compat and SGB traces.

**Rows/acceptance:** All seven rows separately. Instrumented mode is excluded from throughput;
disabled mode must be within 1% of the parent. Do not proceed to P2 without measured eligible-line
ratios per row.

**Expected ceiling:** No production speedup; it determines whether P2 is viable.

### P2 — exact profile-specific line plan

**Scope:** Execute only stable intervals from P1 using exact line-local events and CPU-visible
checkpoints. Keep separate DMG/SGB and native-CGB/CGB-compatibility plans; CGB0 revision differences
remain explicit. The plan may advance output, STAT checkpoints, fetch/FIFO timing, and line rollover,
but must stop at every observable bus or PPU event.

**Guards/invalidation:** Any LCDC/SCX/SCY/WX/WY/BGP/OBP/LYC, CGB palette/VRAM-bank, VRAM/OAM, DMA,
HDMA, speed/KEY1, STAT/LY, CPU PPU read requiring a new value, SGB packet/transfer, or debugger
boundary deopts to the reference tick loop before the affected edge.

**State/memento:** Store canonical line cursor and in-flight stage state, or regenerate the plan
deterministically from a captured line-start state. A plan cannot be a transient shortcut if a save
state can observe the middle of it.

**Tests:** Full DMG/STAT/Mealybug 24/24, Mooneye/acid, CGB hardware/acid and speed/HDMA, SGB packet,
border and VRAM-transfer tests, frame/audio differential, and arbitrary restore at every plan event.

**Rows/acceptance:** All seven rows separately. Medium/high complexity requires a per-row paired
improvement CI lower bound of at least 3% and no row regression over 3%. Native CGB and
CGB-compatibility must each have their own eligible-line and throughput reports.

**Expected ceiling:** Approximately 5–15% initially; stable-line eligibility determines the actual
value.

### P3 — PPU-only checkpoint and event journal

**Scope:** Add a private PPU/output checkpoint and transactional journal without enabling speculation.
The journal must stage frame/audio/SGB output and expose commit/discard counters.

**Guards/invalidation:** The journal is mandatory for any future speculative execution. Active
external endpoints, RTC, mapper side effects, rumble, debug, and input observers remain disabled
or force reference.

**State/memento:** A checkpoint is immutable and deep-owned, may not escape the owner thread, and
owns every PPU/output array, FIFO runtime value, serial/input/action cursor, and deterministic
clock/RTC value it covers. `MachineStateCapture` is only a synchronous hash view; it is not the
rollback checkpoint. The canonical machine memento remains authoritative and host presentation
suppression remains transient/outside it.

**Tests:** Event ordering, commit/discard, no escaped payloads, frame/audio/SGB hash equality,
restore after discard, memory boundedness, and all O1/O2 profile traces.

**Rows/acceptance:** All seven rows separately. Default/reference throughput must regress less than
1%; journal allocation must be zero on the reference path. Otherwise revert the journal.

**Expected ceiling:** No direct speedup; it enables P4 safely.

### P4 — guarded speculative fast line

**Scope:** Run a line-level PPU/output executor privately under P2's no-invalidation assumption,
then commit or restore/replay. Start PPU-only; first prove that Timer, CPU, APU, cartridge/slot,
DMA/HDMA, Serial, Joypad, STAT, and post-subsystem frame/SGB/LCD/STOP producers do not advance
speculatively. If any non-PPU producer advances, PPU-only journaling is invalid and a top-level
`Gameboy` journal covering every source is required before enabling the line.

**Guards/invalidation:** Deopt on every P2 invalidator, CPU/PPU bus write, DMA/HDMA ownership,
interrupt/speed event, input/serial/SGB activity, debug/history/replay, endpoint/RTC/mapper/rumble
side effect, or unknown CGB effective mode. Any invalidation discards the journal and replays the
reference line from its checkpoint.

**State/memento:** PPU checkpoint is immutable/deep-owned and includes both old pixel-machine
states until P4's executor has a validated replacement. A whole-machine speculative extension must
include every `GameboyState` component, mapper/RTC and runtime supplements, action/input cursor,
and transaction state, without putting transient host presentation suppression into the emulated
memento or publishing abandoned host frames.

**Tests:** Randomized generated programs and timestamped actions, forced invalidation at every line
dot, first-divergence replay, event-order/hash equality, arbitrary save/restore, and separate
native-CGB and CGB-compatibility lanes. Track commit ratio and rollback cost.

**Rows/acceptance:** All seven rows separately. Require at least 90% committed lines, rollback
overhead below 10% of saved work, a per-row net improvement CI lower bound of at least 3%, no row
regression over 3%, and zero correctness differences. Otherwise leave P4 disabled or revert it.

**Expected ceiling:** Approximately 10–25% when the workload is stable; low-eligibility CGB/SGB
rows should remain on exact/reference execution.

### N1 — optional SoA/JNI kernel

**Scope:** Only if Java exact batching and P4 remain below the absolute gate, move the hot CPU/APU/
PPU kernel to flat primitive SoA code or JNI/NDK. Keep the Java `Gameboy` API, event journal, and
portable state boundary. Native CGB and CGB-compatibility kernels are separate from DMG/SGB where
their timing/output topology differs.

**Guards/invalidation:** JNI unavailable, unsupported ABI, debug/history/replay, active external
side effect, or unknown profile falls back to Java reference. Native and Java kernels must share the
same differential action stream.

**State/memento:** Native pointers/handles are transient and never enter portable mementos. All
authoritative primitive state, including CGB banks/speed and SGB rational phase, is copied through
versioned state codecs. Restore must rebuild native derived pointers before execution.

**Tests:** O1/O2 differential at every boundary, complete unit/integration battery, Android
instrumentation on the Redmi ABI, save/restore, debug fallback, audio/frame/event hashes, and
native memory/leak checks.

**Rows/acceptance:** DMG, MGB, native CGB, CGB0, CGB-compatibility, SGB, and SGB2 must each meet
the final 60 FPS lower-bound gate with audio and visible output. A row below 60 is an incomplete
release, not an average to hide behind another row.

**Expected ceiling:** Potentially 1.5–3x hot-kernel throughput; actual completion is determined
only by the absolute per-row gate.

## Stop, revert, and completion rules

Stop the current commit immediately on a first-divergence fingerprint, event-order mismatch,
arbitrary-phase save/restore mismatch, changed calibrated tick count, escaped speculative side
effect, or a missing memento/import field. Revert the commit rather than adding a new timing
constant or profile exception to mask the failure.

Reject a benchmark change after paired reruns when a per-row bootstrap median regression exceeds
3%, any profile row is missing, the two-build identity/configuration or thermal/device controls are
invalid, presentation is dropped/corrupted, or an individual-run alarm persists below 58 FPS. A
medium/high-complexity change whose per-row improvement CI lower bound is below 3% is not promoted;
retain only a test-only shadow if it provides useful evidence. Speculation with low
commit ratio or high rollback cost is disabled, not forced.

The completion report must contain, for every row, the two build IDs, paired run counts, exact
candidate interval-FPS statistic and bootstrap median/effect CIs, physical ready count and FPS,
presented count, dropped/duplicate/late/corrupt counts, minimum individual FPS, ticks/sec, CPU
utilization, GC/allocation deltas, temperature/clock validity, audio/render settings, effective
profile/mode, correctness-suite results, first-divergence status, save/restore status, and final
commit list. Completion is claimed only when every row's candidate-FPS CI lower bound is at least
60 FPS, every individual valid run is at least 58 FPS with 600/600 presentation and zero drops,
audio and visible output are enabled, and no profile was represented by a different effective
hardware mode.
