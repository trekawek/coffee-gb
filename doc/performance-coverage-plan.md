# Performance mode: sustained workload coverage plan

Analysis date: 2026-09-08. Source revision: `b162c357422b4f277a5ec1f66a8d7f9aec695595`.
Scope: code and existing test/benchmark audit; implementation is deferred. No game catalogue or
device benchmarks were run for this analysis. The conditions below are demonstrated by source,
but their wall-clock severity must be measured. They are risks of sustained slowdown, not claims
that a particular game currently runs below its native cadence.

This is the historical pre-implementation audit. See the [implementation guide](performance-workload-coverage.md)
and [validation results](performance-coverage-results.md) for subsequent work and measured findings.

## Objective and main conclusion

Make ordinary gameplay sustain its hardware cadence on the lowest-supported Android device,
including workloads that repeatedly access timing registers or use less common hardware features.
Short event-related misses are acceptable; a persistent game pattern must not strand the whole
emulator on an expensive execution path.

The current implementation has enough general machinery to build on. It also has important
coverage asymmetries: an optimization proven for one clock/profile/access pattern often has no
equivalent for another. The highest-priority gaps are pending interrupts with IME disabled,
repeated MMIO polling, normal-speed HBlank DMA waits, and lines rejected at mode-3 entry.

The remedy is a workload coverage program with measured recovery bounds, not an expanding list
of game-specific exceptions. A fast-path rejection should affect only the subsystem and interval
that require detailed execution. A frequently recurring event must have an affordable path even
when it cannot be batched.

“60 FPS” means native emulation speed here: DMG/CGB produce approximately **59.7275 frames/s**;
SGB has a different clock and produces approximately **61.168 frames/s**. Measure emulated time,
rendered frames, presentation, audio continuity, and host work separately.

## What actually falls back

`ExecutionMode` is session configuration, not a switch that flips to Accuracy after a slow event.
Several independent optimizations can stop working while the session still says Performance:

| Layer | Current execution choices | Meaning of losing its fast path |
| --- | --- | --- |
| Controller | Frame-sized `runTicks`, measured stop-aware batches, scalar/debug stepping | Losing the production batch entry also prevents its CPU epochs and direct-line arming. |
| CPU/scheduler | Up to 54-dot running epochs; settled-HALT spans; normal-speed 1–3-dot phase spans; scalar ticks | At x1 a rejected epoch can retain short phase batches. At x2 the ordinary phase-only fallback is unavailable. |
| PPU | Broad approximate scanline renderer; older exact steady-background cursor; scalar dual pixel machines | A CPU fence does not necessarily lose scanline rendering. A rejected line can lose both rendering and subsequent quiet-span coverage. |
| STAT | Invariant spans; narrow native-CGB checkpoint replay/aggregation; scalar evaluation | An epoch can still replay PPU/STAT dot by dot, so “epoch ticks” alone overstates saved work. |
| Peripherals | Arithmetic/deferred timer, audio, input, mapper and idle-link advancement | One rejecting subsystem can veto the combined scheduler span, even if the others are independently quiet. |

Source anchors: `Gameboy.java:891` (`runTicks`), `:1169` (fallback scheduler), `:1231`
(native CGB scheduler), `:1299` (normal-speed scheduler), `:1349` (phase spans), `:2222`
(epoch peripheral commit); `Cpu.java:884` (phase-only limit).

Two semantic qualifications matter when designing tests:

- `PerformanceScanlineRenderer.java:20` explicitly snapshots mode-3 entry and treats subsequent
  mid-line writes as next-line changes. It already supports sprites, windows, CGB color and both
  CGB speeds. Ordinary STAT/LY reads do **not** cancel this direct renderer.
- The native epoch scheduler documents frozen peripheral views and bounded publication skew
  (`Gameboy.java:1193`). Performance audio is decimated. Preserve these existing contracts during
  this program; do not introduce additional approximation to meet a throughput target. Exact
  Accuracy parity is required where a shortcut claims exactness, not for deliberately different
  pixel/audio representations.

The older `doc/android-real-time-fast-path-design.md` and
`doc/emulation-performance-next-pass.md` explain history and intent, but several narrow-path
descriptions no longer describe current production code. In particular, their old rendering
restrictions and Harry Potter call-count objective must not define this program's coverage.

## Fallback inventory

Priority is based on persistence and architectural reach; measured cost will determine ordering
within each priority. “Bounded” does not automatically mean cheap when repeated every scanline.

| ID / priority | Trigger and current behavior | Persistence / recovery | Implementation direction |
| --- | --- | --- | --- |
| F1 / P1 | Raw `IE & IF` pending blocks running CPU epochs on DMG/MGB/SGB, CGB compatibility and native CGB x2 even with IME off. Native CGB x1 has a special allowance. | Can last indefinitely in a `DI` region. Native x1 still fragments at opcode fetch for logical ROM readers whose next byte cannot safely be peeked. | Generalize the IME-off execution proof; preserve canonical HALT/EI/RETI/interrupt boundaries and mapper read side effects. |
| F2 / P1 | FF41 polling and most MMIO reads end/fence CPU epochs. Only native CGB x2 has the stable FF44 exception. Writes generally terminate epochs and may materialize peripheral state. | Repeated throughout visible lines or the entire frame. Direct scanline rendering usually survives, but CPU batches may become very small. | Horizon-backed reads for proven stable register values, then timestamped synchronization at real changing reads/writes. Keep executing instructions; do not recognize one particular polling loop. |
| F3 / P1 | STAT checkpoint paths handle only selected settled states. Native x2 replay is LYC-only (`0x40`), ordinary lines 1–142, excluding equality and predecessor lines. Arithmetic aggregation is narrower still. | Other enable combinations and repeated LYC/STAT writes repeatedly select scalar evaluation or replay. A high STAT level alone is not proof of a fallback. | Derive next observable event from the full STAT state, with exact read/IF/acceptance planes. Extend by state class and phase, not register constants used by one game. |
| F4 / P1 | Normal-speed CGB rejects all-subsystem epochs and phase spans for an armed HBlank DMA transfer, including time between bursts. Native x2 supports a specific armed-wait and owned-data path. HALT has additional exclusions. | Can cover nearly every visible line whenever DMA is continuously armed. | Separate armed-but-waiting from bus ownership at x1 and during settled HALT; extend owned-data batching where source and arbitration proofs permit it. |
| F5 / P1 | Direct scanline arming is attempted only at mode-3 entry. Active OAM DMA/ownership transitions, pending PPU writes or conflict latches reject that line. HBlank span guards then require that line to have used direct rendering. | A hazard lasting a few dots can cost a whole mode-3 plus HBlank. An aligned write repeated every line can make this permanent in practice. | Recover quiet HBlank independently of how the line rendered. Add bounded scalar prefix/tail handling or safe remaining-line rendering only with an explicit output/state proof. |
| F6 / P2 | Native CGB x2 epochs require LCD on. LCD-off batching exists for physical DMG/SGB and native CGB x1, with narrower compatibility coverage. Speed changes transfer scheduler ownership only at existing seams. | Long LCD-off decompression/VRAM work can remain scalar; frequent toggles repeat the first-line cost. | Add missing LCD-off clock/profile paths, maintain exact blank callbacks, and re-dispatch at topology changes instead of waiting unnecessarily for the next controller batch. |
| F7 / P2 | Active internal-clock serial transfers reject quiet/epoch paths even between edges. Most attached endpoint types do not advertise idle capabilities. Non-null IR endpoints and active IR devices also reject spans. | Repeated link probing or continuous peripheral traffic can veto batching for long periods. | Component-specific next-edge/callback horizons; retain detailed execution at actual transfers and unknown external events. |
| F8 / P2 | All cartridge RAM/RTC/EEPROM/flash window accesses are CPU fences. Physical ROM readers exist for MBC5/MBC7; other mappers use a logical reader. Cheat/overlay/debug layers can disable borrowed ROM access. | RAM-heavy routines, bank-switch loops or overlays repeatedly fragment epochs. Logical ROM access alone is normally supported, not a complete fallback. | Typed mapper/window capabilities, canonical single reads for side effects, and leases invalidated on mapping changes. No assumption that every ROM read is inert. |
| F9 / P2 | Legacy held-button input, arbitrary custom input sources, and held SGB multiplayer hub snapshots reject quiet input spans. | Can last for an entire button hold or session. Normal single-player Android `PlayerInputHub` held input already works once settled. | Explicit next input sample/generation contracts; extend multiplayer held-state handling while preserving JOYP filters and interrupts. |
| F10 / P2 | Frequent timer overflow/reload/wake boundaries shorten spans. Dense APU/wave-RAM accesses flush deferred sound; very high channel rates still have repeated edge work inside the channel batch. | Continuous synthesized/digitized sound or timer-driven routines can make these costs dominant without a literal mode switch. | Measure event density and flush cost; optimize arithmetic channel transitions and necessary scalar seams. Ordinary TIMA increments already batch in running epochs. |
| F11 / P2 | OAM DMA blocks combined spans; general DMA and overlapping DMA/speed-switch ownership retain detailed execution. Native x2 owned HBlank data batching admits only specific source/ownership states. | One ordinary OAM DMA per frame is bounded; repeated transfers or multi-line GDMA can dominate a workload. | Batch safe transfer interiors and/or allow unrelated subsystems to advance independently; preserve bus samples, OAM corruption, commit order and arbitration edges. |
| F12 / P2 | CGB0 native/compatibility x1 lacks ordinary-CGB running epochs despite having direct scanline rendering. Unknown profiles deliberately fail closed. | Profile-wide loss, independent of the game. | Fill known-profile gaps with separate timing proofs; retain explicit unsupported status for unknown profiles. |
| F13 / host | Debug instrumentation, retirement tracking, history/replay and some controller features select scalar paths. Exposing mutable PPU aliases permanently marks the GPU unsafe for batching. Linked sessions explicitly select Accuracy. | Observation lifetime; mutable aliases remain unsafe for the lifetime of the exposed object, including after restore. | Keep deliberate policies explicit. Use detached inspection in ordinary flows and confirm removing observation restores eligible paths. Never clear an alias guard while a retained reference can mutate state. |

Relevant source locations, grouped for implementation:

- **F1/F2/F8:** `cpu/Cpu.java:510–623,631–705,3907–3982`; `memory/cart/Cartridge.java:220`;
  `memory/cart/MapperPerformanceRomAccess.java`; `genie/Genie.java`.
- **F3:** `gpu/StatRegister.java:937–1100`; `Gameboy.java:1660–1724,2246`.
- **F4/F6/F11/F12:** `Gameboy.java:1470–1510,1539–1635,1784–1866,1980–2076,2184–2202`;
  `memory/Hdma.java:784–970`; `memory/Dma.java`.
- **F5/F13:** `gpu/Gpu.java:860–872,994–1050,1148–1213,1328–1350,2009–2110`;
  `Gameboy.java:891,3107`; `controller/BasicController.kt:1085–1153`.
- **F7/F9/F10:** `serial/SerialPort.java:245–256`; `ir/InfraredPort.java:119–169`;
  `joypad/Joypad.java:540–581`; `timer/Timer.java` performance span methods;
  `sound/Sound.java` materialization/span methods and `SoundMode1/2/3/4`.

Paths above are relative to `core/src/main/java/eu/rekawek/coffeegb/core/` unless prefixed
`controller/`, which denotes the corresponding class in the controller module.

Existing protection worth retaining: MBC3's clock already advances arithmetically; the current
clocked multicart wrappers delegate their quiet-span capability. The conservative default for
future clocked mappers is not evidence of a missing current MBC3 fast path. Likewise, ordinary
audio remains lazy on many scalar scheduler boundaries, and normal Android held input is not a
global veto. Tests should prevent these established wins from being lost while filling gaps.

F5 also has a second form: writes **after successful direct-line arming** usually preserve
composition, but pending conflict latches and LCDC history can repeatedly reject quiet spans
(`Gpu.java:1115–1126`). LCDC's fixed-point history takes nine dots to settle. Track this separately
from rejected-line streaks: a high direct-rendered-line count can coexist with poor packet coverage.

## Implementation sequence and deliverables

### 1. Establish truthful coverage and cost accounting (F1–F13)

Add allocation-free, opt-in diagnostics at existing scheduler/renderer decisions. Do not attach
debug hooks or retirement observers to gather them: those disable the paths being measured.

- Count requested and committed spans, rejected attempts, fence address **classes**, exact
  boundaries, materialized/replayed dots, direct/scalar lines and transitions back to batching.
- Maintain disjoint scheduler tick accounting: running epoch, HALT/transfer span, phase-only and
  scalar. Track PPU/STAT/APU strategy separately; nested work must not be added to scheduler totals.
- Record span-size distributions, scalar duty cycle, longest scalar interval, rejected line
  streaks and rolling one-second summaries. Track both many tiny misses and long continuous misses.
- Distinguish an unavailable feature/profile, an event horizon, an observation blocker and an
  insufficiently implemented state. A single aggregate “effective Accuracy” flag is misleading.
- Record rejection reason combinations in a bounded form when necessary: a first-failing guard
  alone can hide the next bottleneck. Avoid walks, logs, strings or allocation on each dot.
- Measure host CPU/wall cost through sampling and uninstrumented parent/candidate runs. A low
  batch percentage alone is not a failure if its replacement is fast enough.

Current counters are incomplete: `Gpu.performanceScanlineFallbacks` counts invalid predicted
ends, not the normal failed arming guards. `Gameboy` counts successful epochs/bulk ticks but not
all declined work. Preserve compatibility with the strict Android benchmark log parser by
versioning its schema or keeping new diagnostics in a separate versioned record.

**Exit gate:** tick accounting reconciles; representative blockers produce expected reason and
recovery records; diagnostics do not change machine behavior or batching eligibility; overhead
is measured and disabled-mode overhead is negligible. Capture baseline reports before fixes.

### 2. Build a production-scheduler workload runner and regression matrix

Extend existing `GameboyPerformanceEpochTest`, `CpuPerformanceEpochTest`, scanline differential
tests and peripheral span tests. They already contain valuable synthetic programs and deep-state
comparisons. Extract reusable fixtures rather than adding another game-specific harness.

The new runner must use `runTicks()` with the production input hub or an explicitly supported
deterministic input source. Limit chunks to the next input/checkpoint boundary. Use the measured
stop-aware API where exact frame callbacks require it. Do not use ordinary `runTicksUntilStop()`
for throughput: that API deliberately uses scalar execution.

The current Mooneye/integration runners, headless batch runner and Agent are unsuitable as the
only performance coverage runner: they normally call `tick()`, and Agent/headless observation can
enable retirement tracking. Merely setting `ExecutionMode.PERFORMANCE` is insufficient.

Produce redistributable synthetic ROM programs with repeatable steady phases, transition phases,
and a recovery phase. Drive public CPU/bus behavior for end-to-end coverage; use direct component
fixtures for exhaustive boundary-state proofs. Every performance regression asserts both behavior
and the intended sustained path/recovery, not just `epochTicks > 0` somewhere during startup.

**Exit gate:** the runner demonstrates real epochs and direct lines, detects a known intentionally
blocked path, reproduces F1/F2/F4/F5 independently, and reports stable steady-state coverage without
observer contamination. Check chunk-partition invariance with frame-sized and seeded split budgets
under the same deterministic inputs. Score steady segments before diagnostic captures/restores so
materialization does not hide an admission problem. Correctness and timing runs use the same
scenario/input definition.

### 3. Remove indefinite CPU admission blockers and generalize timing-register access (F1–F3/F8)

Split into reviewable changes:

1. Generalize running execution under IME=0 and pending IF across supported profiles. Preserve
   HALT bug/entry, EI delay, RETI, IE/IF changes and read-phase acceptance. For mappers without a
   safe peek, perform the canonical fetch once at its real boundary and continue from decoded
   state; do not duplicate a potentially side-effecting read.
2. Extend stable LY reads and add STAT reads backed by a proven next-change horizon, including
   readable mode, coincidence and CPU read-phase behavior. Cover all addressing forms. A value
   must not be reused across mode/line/LYC/write or interrupt-read-phase changes.
3. Provide an efficient fenced execution path for changing MMIO: synchronize the required state
   at the actual bus boundary, execute the access once, recompute affected deadlines, resume
   batching. Preserve current pre-/post-CPU ordering and publish DMA/APU writes exactly once.
4. Broaden STAT event advancement for enable-mask combinations and sustained level states.
   Start with invariant intervals, then explicit scheduled events. Preserve mode-source blocking,
   LY/LYC conflicts, line 153, IF clear/ack races, and the distinction between readable IF and
   CPU dispatch/HALT wake. Bound expensive replay instead of silently counting it as fast work.

**Exit gate:** long polling/IME-off scenarios remain fast across all supported clock rows, all
poll forms see the same observations as their reference contract, and transition sweeps preserve
interrupt results. No loop-pattern recognizers or ROM-specific admission flags.

### 4. Complete DMA, LCD and known-profile coverage (F4/F6/F11/F12)

First extend HBlank **waiting** intervals to native CGB x1 and settled-HALT states. Waiting does
not own the bus; the next ownership/arbitration boundary must still terminate the interval.
Then cover owned transfer interiors in separate changes: x1 HBlank data, GDMA, and OAM DMA where
their observable transaction sequences permit batching. Unsafe sources retain canonical reads.

Add native CGB x2 LCD-off spans and fill justified compatibility/profile gaps. Advance timer,
audio, serial, input and cartridge clocks while preserving stopped LCD state and exact host blank
callbacks. Re-enter the appropriate scheduler at speed/compatibility transitions; never reset
the free-running CPU phase to make it convenient. Extend CGB0 x1 using its own state/phase tests.

**Exit gate:** continuously armed HBlank DMA preserves fast waiting intervals; LCD-off loops do
not require full-machine scalar execution; transfer source reads/VRAM commit/interrupt ordering
and mid-transfer restore match the reference. Speed-switch tails remain independently verified.

### 5. Bound the cost of a rejected raster line (F5/F3)

Start with the least speculative improvement: allow quiet HBlank batching after a scalar-rendered
line once both pixel machines/output tails have drained. The historical `performanceScanlineLine`
flag must not stand in for a proof that present hardware state is quiet.

Next optimize recurring mode-3 entry conflicts. Prefer batching delayed-latch maturation and
safe exact pixel/fetch spans. If adding a mid-line re-entry to the approximate renderer, design
and test its scalar prefix, remaining pixels, sprite selection, window row and pending output
ownership explicitly. Do not redraw already-published pixels or clear pending writes to pass a
guard. A new rendering approximation is a separate design decision, not an implicit optimization.

For genuinely dense per-line hazards, make the retained detailed PPU path affordable through
event/fetch/pixel-block operations. “It falls back correctly” is not the acceptance condition.

**Exit gate:** repeated writes aligned around mode-3 entry no longer force needless scalar
HBlank; a resolved hazard has a documented re-entry bound; repeated hazards meet device cadence.
Normal sprites/windows/color output retain their existing broad renderer coverage.

### 6. Prevent one busy peripheral from vetoing unrelated work (F7–F11)

Implement next-observable-event capabilities for serial/IR endpoints and input sources, with
explicit support rather than assumptions about external polling. Batch internal serial phase and
countdowns up to actual bit edges; expose input sample deadlines for custom/multiplayer sources.

For APU/timer-heavy loops, first remove redundant whole-component materialization and optimize
bounded arithmetic event advancement. Preserve wave-RAM access/retrigger effects, sweep and
frame-sequencer edges, sample timing, and uninterrupted audible output. Keep muted benchmark
calendars outside the normal acceptance path.

If a subsystem still requires per-tick work, let it own a short detailed interval while other
components retain independently proven batching. Introduce this scheduler change incrementally
with one subsystem and explicit observation deadlines. Do not build a second unconstrained
global scheduler or run CPU ahead across observable interrupts/accesses.

Extend ordinary SRAM and mapper access capabilities only after profiling shows material fence
cost. Preserve bank switching, RTC/EEPROM/flash effects, overlays, and opaque future hardware.
Unknown clocked components retain conservative behavior, with visible diagnostics.

**Exit gate:** active serial polling, held multiplayer input and high-density audio/mapper
scenarios have bounded costs; unsupported endpoint capabilities are reported distinctly; input,
link bits, audio and mapper state remain correct at all tested boundaries.

### 7. Run catalogue discovery and Android release gates continuously

Start discovery after steps 1–2, and repeat it after each substantial coverage extension. Steps
3–6 can be prioritized using actual fallback duty cycle and Android time. Finish only after the
regression matrix and device gates below pass; a reduction in method calls is supporting evidence,
not the primary success criterion.

## Workload matrix

Use exhaustive small dimensions and deterministic pairwise/seeded combinations for larger ones.
Include adversarial combinations around shared clocks: STAT+HALT+timer, STAT+HDMA+speed switch,
OAM DMA+mode-3 entry, audio-register writes+sample edges, and mapper changes+instruction fetch.

| Family | Required variations |
| --- | --- |
| Hardware | DMG, MGB, native CGB x1/x2, native CGB0 x1/x2, CGB and CGB0 DMG compatibility; SGB/SGB2 retained as separate rows. Normal/skip/fast-forward boot, supported speed changes and restore. |
| CPU/interrupt | ROM/WRAM/HRAM execution; register, indirect and CB memory operations; stack ranges; IME on/off, all IE/IF combinations, EI/DI/RETI, repeated HALT, HALT bug, pending masked interrupts and interrupt dispatch. Include physical and logical mapper readers. |
| Polling | FF41/FF44/FF45/FF0F/FFFF/FF04–FF07/FF00/FF01–FF02/FF26; LDH immediate, LDH C, absolute, indirect and BIT/read-modify-write forms; sparse through maximum legal access density. |
| STAT/PPU | All 16 combinations of STAT bits 3–6; LYC matching/nonmatching and 0/143–153/out-of-visible-range values; every CPU-access phase near events; SCX 0–7; zero through ten sprites, shared/edge X positions; window on/off and boundary WX/WY; CGB banks/palettes; changed and same-value LCDC/SCX/SCY/WX/WY/BGP/OBP writes before/at/after mode-3 entry and mid-line. Assert packet coverage after successful composition separately from line coverage, including LCDC's nine-dot history drain. |
| DMA | OAM transfers aligned before/at/after mode-3 entry, normal once-per-frame and repeated stress; HBlank DMA continuously armed, start/cancel/restart/HALT; GDMA; ROM/WRAM/VRAM/cart-window sources where hardware permits; overlaps, speed transitions and state restore. |
| Timer/audio | Every TAC rate, TMA from rare to near-continuous overflow, DIV resets and HALT wake; all channels active, extreme frequencies, envelopes/sweep, wave RAM reads/writes/retriggers and rapid volume/pan changes crossing compact sample boundaries. |
| Cartridge | ROM-only, MBC1/2/3/5/7 and remaining supported mapper families, RTC and clocked multicarts; ordinary SRAM-heavy work, frequent remapping, special data windows and supported cheat/overlay configurations. Unknown capability cases remain explicit. |
| Link/input/SGB | Disconnected/internal serial probes, active internal/external transfers, known printer/mobile/GPS/barcode endpoints and IR devices; hub held/released input, changing buttons, custom sources, SGB multiplayer and transfer commands. Linked-controller Accuracy policy is a separate feature scope. |
| Recovery/observation | One hazard followed by quiet work; hazard every line/frame; repeated save/load, pause/resume and debug attach/detach; retained mutable aliases; input at packet boundaries; split versus unsplit run budgets. |

For every scenario store: supported hardware rows, baseline execution signature, expected behavior,
known existing approximation, intended fast-path coverage, event recovery bound and device result.
Do not exhaustively multiply every row by every axis; ensure every guard outcome and recovery
transition is covered, then use combination coverage to find interactions.

## Correctness and performance gates

### Correctness

Use three complementary references:

1. **Accuracy** for claimed exact timing/state behavior and existing hardware-verified suites.
2. **Forced-scalar Performance under the same rendering/audio policy** for scheduler equivalence.
   Use a deliberate test control; do not rely only on incidental custom-input rejection forever.
3. **Existing Performance rendering/audio contract** for intentional approximations. Stable-line
   output can be compared directly; raster-effect differences must be explicit and attributable.

Compare CPU/bus-visible state, interrupt outcomes, frame/event counts, relevant pixels/audio,
DMA/mapper/input state and materialized component state. Use fixed master-tick and real event
checkpoints, including partial batches. Check save/restore across both modes and arbitrary
optimized boundaries; emulated pending state belongs in component state, derived caches and
diagnostics do not. Rebuild leases/caches on restore and preserve externally exposed alias safety.

Run focused subsystem and whole-machine differential tests per change. Run core tests and the
Mooneye/acid/Blargg battery when timing interlocks are touched; add relevant Mealybug, gbmicrotest,
gbc-hw and other suites for the changed hardware behavior. Keep Accuracy's hardware results
unchanged. Execute applicable Performance scenarios through the actual batch runner with positive
coverage checks; the existing scalar integration suite alone cannot certify a batching change.

### Catalogue discovery

Use the authorized local GB/GBC library in place with battery persistence disabled or isolated
in memory. Never copy/publish ROM or save contents or their checksums. Use opaque workload IDs in
shareable reports and keep any ID-to-local-path mapping private.
Do not reuse existing headless JSON unchanged: `HeadlessBatchRunner.kt:221–235` includes ROM
SHA-256 metadata. New reports contain opaque IDs and bounded aggregate diagnostics only; local
ROM/save state comparisons stay in memory without content or checksum dumps.

Begin with automated boot/title/demo sweeps, stratified by hardware/mapper, then collect
repeatable menu/gameplay scenes and longer transitions. An idle title-screen pass is only that
scene's coverage. Cluster by observed register/access/event signatures and retain scenes adding
new behavior. Rank sustained scalar duty, repeated rejected lines, small epochs and actual host
time. Reproduce each costly class with a minimal synthetic program for public CI.

Report games/scenes reached, hardware rows tested, duration and uncovered areas honestly. This
reduces catalogue risk but cannot prove every possible scene in every cartridge. Permanent
protection comes from adding each newly found behavior to the workload matrix.

### Android

Retain the existing signed-artifact/ART/audio/compositor comparison workflow and its strict gates.
It currently uses one configured Redmi, two recent ROM slots over seven hardware rows, twelve
parent/candidate pairs and 600-frame windows. That is useful comparison evidence, not a sustained
catalogue benchmark. It deliberately excludes thermal throttling and requires plugged-in operation.

Add a separate steady workload/gameplay soak of at least 15 minutes on the designated
lowest-supported device, with a thermally settled final ten-minute evaluation. Extend the run
until observed temperature, frequency and host-work cost stabilize; elapsed warm-up time alone
does not establish this. Set a bounded run limit and report inconclusive if settling is not
established. Initially use the existing configured Redmi
as the baseline; a claim about all low-end devices requires an explicit supported device floor.
Include audible sound, visible output, battery operation and operation without performance hints.
Record thermal/frequency/power conditions so failures are attributable instead of discarded.

Proposed acceptance policy, to freeze before implementing optimizations:

- In each settled scene, every rolling 10-second window delivers at least 99% of nominal
  emulated time, with no accumulating pacing/audio debt. Two consecutive one-second windows
  below 95% indicate a sustained failure. Report all shorter misses and their cause.
- Render and present at least 99% of native frames over each rolling 10-second steady window,
  using a display fast enough for the profile (in particular, retain 120 Hz for the SGB gate).
  On a slower display, report native production separately and explicitly account for expected
  refresh-conversion drops versus overload drops; do not claim 99% native presentation there.
  Also measure frame-gap tails and suppression streaks.
  Do not accept persistent rendering suppression as a way to meet emulated-time throughput.
- Require continuous audio without recurring underruns. Keep any muted/relaxed-APU experiment
  separate from acceptance.
- Target controller work p95 at or below roughly two thirds of its frame budget (about 11.1 ms
  at 60 Hz), leaving headroom for audio/UI/host variation; treat missing headroom as a risk to
  resolve even when short mean FPS passes.
- Use repeated parent/candidate runs; flag a greater-than-3% host-work regression for investigation.
  No already-covered workload may lose sustained cadence to improve another.

These are proposed sustained-play tolerances, not a relaxation of the existing stricter
600-frame compositor gate. Transition windows remain in the report, separately identified from
steady scenes; repeated or long transitions cannot be discarded to manufacture a pass.

`BasicController.kt:1100–1111` can suppress frame rendering when pacing debt accumulates in
ordinary play, whereas benchmark mode disables that behavior. Therefore log emulated, rendered,
submitted, actually presented and suppressed frames separately in the soak.

## Completion criteria

Each inventory item must have a measured workload, an explicit supported behavior, a correctness
test and a sustained-performance/recovery result. A retained scalar path is acceptable if it is
fast enough on the supported device; an unmeasured permanent veto is not complete.

Deliver the implementation as small changes with the triggering workload, before/after path
coverage, correctness result and Android cost. End with the full matrix and catalogue discovery
report, including deliberate feature exclusions and remaining unsupported hardware. Keep the
runner and representative synthetic workloads in CI so future fixes cannot silently exchange one
game's throughput for another's.

Recommended first implementation batch: diagnostics and runner, then IME-off pending interrupts,
STAT/LY polling, native CGB x1 HBlank waits, and recovery after rejected scanlines. Those changes
address the clearest recurring and persistent risks while building evidence for the remaining
peripheral and profile work.
