# Performance coverage implementation and validation

Baseline: `b162c357422b4f277a5ec1f66a8d7f9aec695595`.
The [original plan](performance-coverage-plan.md) records the pre-change audit;
[the workload guide](performance-workload-coverage.md) describes the implemented APIs,
fixtures, commands and retained execution contracts.

**Validation is in progress.** The results below distinguish completed checks from
pending gates. Passing unit tests or improved desktop throughput does not certify
sustained Android playback.

## Current selected validation status

The union source is integrated and frozen, and validation is in progress. It includes strict
cap-63, the terminal FF40 retry hint, the combined GPU path, serial denial fallback, the Sachen
safe-peek mapper path, and the LCDC poll-retry path; all new union test classes are installed in
the tree. Full core validation is **3,180 passes with 8 existing skips**: **2,273 unit slots**
(**2,265 passed + 8 skips**), plus **640 timing/graphics**, **54 Blargg** and **221 GBC hardware**
passes. The union receipt `/tmp/coffee-gb-union-validation-prep-20260909/union-validation-receipt.json`
also records controller **978 slots / 2 skips**, CLI **42**, Swing **854 slots / 1 skip**, and
**297 Android JVM passes**. The frozen source snapshot produced union APK `aad7ce01…` with
profile `0bc7fef5…`; installation verification and arm64 ART `speed-profile` are recorded in
`/tmp/coffee-gb-candidate-union-20260909/installed-verification.json`. DeviceOps completed
the three union-artifact short classifications (LCDC, OAM and STAT). Each is explicitly
`INCONCLUSIVE_SHORT_DIAGNOSTIC` in
`/tmp/coffee-gb-candidate-union-20260909/union-device-evidence-receipt.json`: the short
collector is diagnostic evidence, not sustained device acceptance. The long soak, strict M2,
battery and gameplay gates remain separate.
The installed `58cc3bf2…` artifact and isolated measurements below remain historical or
candidate-specific evidence.

The final async ordinary-soak candidate passed **303/303** benchmark Android unit tests,
`assembleBenchmark`, debug lint, and release Java compilation. The installed pair is APK
`/tmp/coffee-gb-candidate-async-soak-20260909/artifacts-final/app-benchmark-async-final.apk`
(SHA-256 `45e39a47…`) and same-stem API-31+ DM
`/tmp/coffee-gb-candidate-async-soak-20260909/artifacts-final/app-benchmark-async-final.dm`
(SHA-256 `b464ac36…`); the R8 mapping is `29a5196c…`. Final lineage and signature evidence are in
`/tmp/coffee-gb-candidate-async-soak-20260909/final-validation-receipt.json` and
`final-deployment-handoff.json`; package/profile verification with arm64 `speed-profile` /
`install-dm` is recorded in `/tmp/coffee-gb-device-handoff-20260909/package-final.txt`.
The root-reviewed `short-v2-after-55ish.log` contains 83 app-only samples, all fresh, drop-free
and sequence-contiguous with ordered clocks and maximum snapshot age **4.053769 ms**. After its
25 s warmup, the **57.466552311 s** app interval recorded **59.7216965693 FPS**,
**48,004.4841619 Hz** consumed PCM, zero new underruns, and work-p95 row median/max
**14.7670765/18.834154 ms**. The log has no paired SurfaceFlinger raw stream and is diagnostic
only, not sustained acceptance; the root-reviewed classification is recorded in
`/tmp/coffee-gb-device-handoff-20260909/short-v2-diagnostic-receipt.json`. The later `short-v2-final.log`
contains the user-muted tail. Live ordinary collection requires
`coffee-gb-soak-app-v2`; app-v1 remains an offline historical parser format. The collector rejects
dropped, stale, incoherent, unavailable, or snapshots from a replaced generation and has no
terminal drain acknowledgement. The device is currently muted at the user's request, so future
captures may provide cadence and PCM/audio-health diagnostics but cannot certify audible playback
or the full audio-dependent gate while muted. Canonical M2 remains a separate gate whose contract
requires `audio_system_music_muted=false`; `system=0`/muted silent-pcm or relaxed policies change
that contract and cannot substitute for canonical accuracy and performance comparisons. The
current mute therefore defers canonical M2 audio certification.

The final45e app-v2 LCDC ordinary soak used native CGB ×2, ordinary `PERFORMANCE`, visible
presentation, priority 0 and hints off; it is complete and failed the sustained gate. Raw data is
`/tmp/coffee-gb-lcdc-ordinary-soak-20260909.jsonl` (SHA-256
`fdfc4ea580104b1d280be8679ec8c3fdb485359b82d0ad4ad08c3c2da13ac20c`); the authoritative parser
overlap was **975.6705330255 s**, with **972** app samples that were fresh, drop-free and ordered.
Its stable window covered app clock **379.644040..980.941129** (**601.301142497 s**) at
**59.6805784386 FPS** and **47,962.0017248 Hz** consumed PCM, with underruns **38 → 77**
(**+39** stable and whole run), native/presented ratios **0.999221052265/0.999232739353**,
rolling minimum **0.953210389476**, zero suppressed frames and compositor drops, one ≥50 ms gap,
76 debts, thermal delta **[1.0, 0.2166667]**, and `headroomRisk=true`. The maximum work row was
**42.347385 ms** (p95 median/max **15.008538/24.142769 ms**). The capture was muted throughout,
and failure reasons included mute, rolling emulation/render/submission/presentation/audio/underrun
conditions and consecutive samples below 95% of native cadence. A burst from
397.855–410.929 s contributed all +39 underruns, with four consecutive one-second FPS values
**51.059/52.754/53.199/54.880**; no new underrun followed. This is an intermittent failure,
not a constant below-native pattern, and does not justify relaxing the gate. The verified long-run
receipt is `/tmp/coffee-gb-lcdc-ordinary-soak-20260909-receipt.json` (SHA-256
`0dd0d0e0cc6fdc5faf42ed71b14f8c749dafbe59909876daf70b98c1575ef3ee`), and the report is
`/tmp/coffee-gb-lcdc-ordinary-soak-20260909-report.json` (SHA-256
`2c34e92613b986603b4a05636ce730bdee3e790f98bca2eb931e71d917d7db95`). The retained empty
errors log contains no relevant ART, GC, runtime or audio errors for the interval, but cannot
exclude GC or identify the burst cause.

### Completed union catalogue screen

The frozen union source completed the post-union catalogue screen against the clean original-b162
baseline: **36 fresh JVMs**, **18 paired comparisons**, and **3 reversed pairs per case**. Warmup was
50,331,648 master ticks and each measured window was 33,554,432 master ticks, with diagnostics and
assertions disabled. All semantic frame, tick, speed and clock-accounting contracts matched; epoch
and bulk counters were intentionally excluded because the selected owners change those counters.
The raw validation receipt is
`/tmp/coffee-gb-union-catalogue-cost-run-20260909/result-validation.json`.

| Opaque case | Profile | Candidate/control changes by reversed trial | Median | Reading |
| --- | --- | --- | ---: | --- |
| `catalogue_010` | CGB x2 | +0.280%, −6.199%, +1.048% | +0.280% | cleared |
| `catalogue_042` | CGB x1 | +10.059%, +7.132%, +1.418% | +7.132% | recurring cost |
| `catalogue_060` | DMG | −1.251%, +6.229%, +3.114% | +3.114% | mixed |
| `catalogue_063` | CGB x2 | +4.168%, −0.697%, −2.920% | −0.697% | mixed |
| `catalogue_067` | CGB x2 | −1.490%, −3.994%, −11.574% | −3.994% | cleared |
| `catalogue_083` | CGB x1 | +6.488%, +13.668%, +13.320% | +13.320% | recurring cost |

These are final-union cost observations for six opaque catalogue discovery/title-demo cases; they
are not a full catalogue or representative gameplay-scene survey, and they do not turn the
remaining device gates into an acceptance result.

The warmed opposite-order catalogue check retained the two residual flags: six JVM rows (three
paired comparisons) were **+5.041928%** for 042 and **+4.493181%** for 083, while neutral 063
was **−4.245977%**. The earlier 18-JVM/9-pair medians were **+7.003413%**, **+5.363514%**, and
**+3.322716%** respectively.
These checks strengthen attribution of the remaining rows but do not select a new overlay.

The final selected-source disabled-diagnostics screen also completed as a bounded control: 18 fresh
JVMs, 9 paired comparisons, and three reversed pairs each for `catalogue_042`, `catalogue_083` and
neutral `catalogue_063`, with 50,331,648 master ticks of warmup and 33,554,432 measured ticks.
All semantic fields, including epoch, epoch-tick and bulk counters, matched. Erased-diagnostics
over-union changes were **−0.300% / +1.681% / −3.666%** (042, median **−0.300%**),
**−0.653% / +1.660% / −1.433%** (083, median **−0.653%**) and
**+2.717% / +1.829% / +0.102%** (063, median **+1.829%**). No repeatable greater-than-3%
diagnostic tax appeared in these three rows; the erased-diagnostics overlay remains unselected and
does not close every possible device-specific overhead question. Receipt:
`/tmp/coffee-gb-union-erased-diagnostics-stage-20260909/result-validation.json`.

### Historical installed 58cc validation status

| Gate | Current result |
| --- | --- |
| Core unit battery | **2,255 slots**: **2,247 passed** and 8 existing skips; no failures |
| Hardware checks | **915 passed** |
| Combined current count | **3,170 slots**: **3,162 passed** and 8 skips |
| Prior 5bf focused correctness evidence | **17 tests passed** with assertions enabled; controller/UI dependency JARs are byte-identical to the current build, while core changed |
| Prior 5bf Controller/CLI/Swing evidence | Controller: **978** with 2 existing skips; CLI: **42**; Swing: **854** with 1 existing skip; no failures/errors; the current 2,255 core slots cover the new stack |
| Android JVM suite | **297 passed**, 0 failures/errors/skips |

The historical candidate is `/tmp/coffee-gb-candidate-terminal-retry-hint-20260909/candidate.apk`,
SHA-256 `58cc3bf2f47eb1aaf7b08bef63c16f7ec3900ca9641b5a60c5fa4e67ceade01d`, with matching API-35
`.dm` and R8 mapping recorded in the private device receipt. Its historical source/APK mapping is
recorded in `/tmp/coffee-gb-candidate-terminal-retry-hint-20260909/deployment.json`.

Long-warm controls cleared **13 of the 15 initial flags**. `SPEED_SWITCH/CGB` remained
**+15.777% / +5.853%** in the persistent first observation, while `TIMER/CGB_X2` was
**+4.085% / +0.989%** and mixed. These are measured flags with unresolved attribution; later
selected speed controls did not reproduce a stable tax and the latest Timer controls were mixed.

### Latest selected union Android and workload status

The three fresh ordinary visible/audible checks used the installed union APK `aad7ce01…` with
25 s warmup and a requested 55 s collection, plugged power, `PERFORMANCE`, native CGB x2,
priority 0, hints off, volume 8/unmuted, and a 60 Hz display. The authoritative short receipt
classifies all three as `INCONCLUSIVE_SHORT_DIAGNOSTIC`; these are short diagnostics and do not
establish the 15-minute sustained gate. App and SurfaceFlinger intervals are independent. Work
p95 values are the median and maximum of reported row p95 values, not a pooled frame percentile.

| Fixture | App interval | Native FPS | SurfaceFlinger FPS | Audio playback Hz | Underruns | Work p95 row median / max | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| LCDC | 52.466681 s | 59.733148 | 59.735957 | 48,012.185 | +0 | 14.970154 / 18.812616 ms | inconclusive short diagnostic |
| OAM | 52.450005 s | 59.733073 | 59.711905 | 47,998.165 | +0 | 15.878077 / 18.168615 ms | inconclusive short diagnostic |
| STAT | 53.435195 s | 59.717196 | 59.689822 | 48,004.316 | +0 | 16.331462 / 20.525308 ms | inconclusive short diagnostic |

The union short and long receipts are
`/tmp/coffee-gb-candidate-union-20260909/union-device-evidence-receipt.json` and
`/tmp/coffee-gb-candidate-union-20260909/lcdc-union-plugged-soak.jsonl`. The union LCDC soak ran
1167.144318 s and **failed** its 600 s stable gate. Its stable window measured **59.711467 FPS**,
**47,985.474 Hz** audio playback, native ratio **0.9997096**, presented ratio **0.9997352**,
rolling ten-second minimum **0.9885526**, and **+14** stable-window underruns (absolute audio
counter **52 → 66**; the run began at absolute **33**). The raw collection recorded a system
volume/mute transition from 8/unmuted to 0/muted inside the window; the cause is unknown and is
not attributed to cleanup or a user action. No sustained acceptance is claimed.

### Historical installed 58cc soak context

The historical uninterrupted LCDC USB soak then ran 900.145669 s and **failed** its 600 s stable gate:
measured stable app cadence was **59.582855 FPS** and SurfaceFlinger was **59.581791 FPS**;
stable app-counter audio playback was **47,883.6738 Hz** over a 601.011821 s counter interval,
with **+105** stable-window underruns. The raw observed post-sample total was **+109**, dominated
by one 779.293–786.362 s burst contributing **+81**. The collector's 59.7275006 FPS field is
the native target, not the measured result. The soak recorded six long presentation gaps, 144
pacing-debt samples and a 33.479384 ms maximum reported row p95. It establishes no sustained
acceptance; the raw receipt and read-only burst analysis are
`/tmp/coffee-gb-candidate-terminal-retry-hint-20260909/long-device-evidence-receipt.json` and
`/tmp/coffee-gb-candidate-terminal-retry-hint-20260909/lcdc-soak-analysis-20260909.md`.

A second sidecar-instrumented LCDC USB soak on the same historical 58cc candidate ran
1209.475691 s and also **failed** its 600 s stable gate. Its final collector stable
window measured nativeRatio **0.99654034**, presentedRatio **0.99649521**, rolling
ten-second emulation minimum **0.97660136**, **+235** stable-window audio underruns,
and `headroomRisk=true`. Rendered and submitted frame ratios were 1.0 relative to
the native frames produced by the app; there were no presentation long gaps or
compositor drops. It provides diagnostic context only and establishes no sustained
acceptance. The immutable raw receipt is
`/tmp/coffee-gb-final-soak-sidecar-run-20260909-58cc-v1/final-receipt.json`
(SHA-256 `8c6b022398a5e1e387961797ef62a62c7cb8fec65422941d7dc55f80b7f79d46`);
the corrected burst-boundary erratum is
`/tmp/coffee-gb-final-soak-sidecar-run-20260909-58cc-v1/erratum-burst-boundary-v1.json`
(SHA-256 `b169de93b3a78843eeba46b5874d73cdabd24f6e398c84f462c20de180755c82`).

### Profile and stall-context review

A 20 s simpleperf profile of historical 58cc used the ordinary visible LCDC scene and
`PERFORMANCE`, native CGB x2, priority 0, hints off and volume 8/unmuted. The raw profile and
mapped controller reports are retained under
`/tmp/coffee-gb-terminal-retry-hint-simpleperf-20260909/`; the receipt is
`simpleperf-receipt.json`. Controller-normalized mapped self attribution led with
`Gameboy.tryPerformanceEpochOrDetailedReplay` **8.57%**,
`Cpu.tickPerformanceEpochInstructionPipelineAtMachineCycle` **7.69%**, and `Gpu.tick` **6.24%**;
inclusive rows led with `Gameboy.runTicks` **99.60%** and the epoch/replay owner **83.66%**.
These percentages are sampled CPU attribution, and profiler overhead depressed the accompanying
telemetry to 55.388 FPS with +172 audio underruns, so they are diagnostic only.

The read-only PID 24165 context review was collected after the profile: the profile host window was
02:24:40–02:25:07 UTC, while available logcat begins later at device 04:25:59. Therefore that
logcat cannot establish whether a GC, OOM or audio-route event occurred inside the profile window.
The later context's one app concurrent-mark-compact event at 04:26:43.134 (18 MB freed; reported
pause/total fields `626us,1.588ms total 35.605ms`) and separate system-server GC at 04:26:20.600
are outside the profile. In the later context there were no OOM entries, AudioTrack route/write
failures or logged app audio-restart event; the cumulative `audioRestarts=1` counter stayed
constant, and app telemetry route-failure, write-failure and overrun counters stayed at zero.
Context meminfo showed 141,987 kB total PSS; AudioTrack continued regular output. This evidence
does not identify the long-soak burst and cannot establish in-profile GC correlation.

Static-null controls completed with no stable broad tax. The integrated combined GPU path, including
the empty-selected-OAM/LCDC-retention work, and the terminal FF40 retry hint are part of the current
working-tree stack. Publication-only and DMA-only alternatives remain historical unselected
experiments.

A private final host-cost run on the same historical `58cc` pin completed with assertions disabled: the LYC/
Barcode plan produced 84 timed rows (diagnostic rows excluded), and the catalogue plan produced 24
structurally matched pairs. Candidate/baseline median ratios are **0.6767 (−32.33%) for
LYC/CGB0** and **0.8203 (−17.97%) for LYC/CGB0 compatibility**; the other LYC profiles were
modest or mixed. Barcode medians were **+9.52% (DMG persistent)**, **+5.33% (DMG finite)**,
**+7.69% (CGB x2 persistent)** and **+2.97% (CGB x2 finite)**. Catalogue medians were **+10.01%
(010)**, **+2.37% (015)**, **+1.44% (038)**, **+4.15% (042)**, **+4.17% (060)**, **+4.34%
(067)**, **+12.88% (083)** and **−0.17% (063)**. The cost handoff is complete; these measured
host-cost flags remain unresolved attribution and are separate from sustained Android acceptance.

## Isolated candidate measurements

The following results compare one reviewed overlay at a time with the historical `58cc` control. They
are retained for attribution and selection; none is a measurement of the frozen union.

### Serial denial fallback

The selected persistent fallback screen used 18 fresh JVMs / 9 pairs, three reversed trials,
134,217,728 warmup master ticks and 33,554,432 measured master ticks. Candidate/control changes
by trial were **−31.400%**, **−31.222%** and **−41.021%** for Barcode DMG (median **−31.400%**),
**−6.126%**, **−25.947%** and **−17.546%** for Barcode CGB ×2 (median **−17.546%**), and
**−12.730%**, **−14.288%** and **−15.681%** for the CGB ×2 CPU null-endpoint control
(median **−14.288%**). The selected finite fallback screen used 12 fresh JVMs / 6 Barcode
pairs and no CPU rows. Its trial changes were **−33.137%**, **−33.537%** and **−30.518%** for
Barcode DMG (median **−33.137%**) and **−19.450%**, **−14.175%** and **−16.281%** for
Barcode CGB ×2 (median **−16.281%**), with the same 134,217,728/33,554,432 tick windows.
All paired rows returned with exact structural tick/frame/state contracts. The finite workload sends
one scan and then measures quiet completion; it is not a sustained scan-cost claim. Receipts are
`/tmp/coffee-gb-serial-denied-scalar-fallback-cost-prep-20260909/cost-run/result-summary.json` and
`/tmp/coffee-gb-serial-denied-scalar-fallback-finite-cost-prep-20260909/cost-run/result-summary.json`.
These are isolated fallback-overlay values and do not establish the frozen union's cost.

### Sachen safe peek

The cooked Sachen mapper proof passed 24 mapper tests and the authored native-CGB ×2 scalar/batched
partition/restore proof. The proof retained full state comparisons across budgets 1, 7, 54, 55,
62, 63, 64, 97, 512 and 4096 and partitions 63, 54+9 and 9+54; the positive replay witness was
327,964 ticks. A separate same-process old-denied/new-enabled oracle passed the full machine-state and frame
oracle at warmup and measurement; epoch counters were intentionally excluded because the route
changes them.

The release-16 overlay classpath contained 786 production classes; exactly three differed: `SachenMmc.class`
and its two memento/state nested classes. It then ran three reversed pairs against the historical
`58cc` control. Every measured window retained 479 frame events, 33,554,432 ticks, CGB ×2 and
the other accounting fields. Overlay/control host-time changes were **−0.509%**, **−10.410%** and
**−11.332%**; the median was **−10.410%**. Epoch counts changed from 1,806,012 to 1,487,099 and
must not be treated as an invariant. Receipts are
`/tmp/coffee-gb-sachen-peek-stage-20260909/validation-20260909a/sachen-proof-receipt.json` and
`/tmp/coffee-gb-sachen-case010-cost-run-20260909/cost-validation-receipt.json`.
This is an isolated mapper-overlay result, not a final union measurement.

### LCDC poll retry

The LCDC poll-retry stage passed 16 existing LCDC tests, 6 new seam tests and 11 restore/retention
tests. Its corrected actual-hub untimed run used one source opened before construction, FAST_FORWARD,
1,000,000 warmup ticks and 3,000,000 measured ticks; the candidate retained 43 measured frames and showed the
expected changed replay counters. In the separate 12-JVM cost screen, each fresh JVM ran three measured windows; the 36 rows
were aggregated before each pair ratio, giving three pairs per workload. Exported-LCDC
candidate/control changes by fork were **−3.703%**, **−6.544%** and **+2.217%** (median
**−3.703%**); CPU control changes were **−2.346%**, **−1.124%** and **+3.104%** (median
**−1.124%**). Tick accounting matched in every window, while LCDC replay counters were allowed
to change by design. The cost receipt is
`/tmp/coffee-gb-lcdc-poll-retry-cost-run-20260909-v2/final-cost-receipt-v1.json`; focused and
untimed proof details are in
`/tmp/coffee-gb-lcdc-poll-retry-stage-20260909/validation-receipt-v3.json`.
These are candidate-stage measurements and do not certify the frozen union.

### Original b162 scalar/batched attribution

A clean original-b162 case-010 oracle was run with the exact CGB FAST_FORWARD/input protocol. Its
execution classpath used `/tmp/coffee-gb-performance-baseline/core/target/classes`, rather than the
separate immutable snapshot directory; all 774 class hashes were independently verified against
that snapshot before and after execution. The scalar `runTicksUntilStop` path and batched
`runTicks` path already diverged at the warmup full-state comparison at
`soundMemento.allModeMementos[0].freqDivider`. The process returned 1, the measured window never
started, and no state field was normalized or excluded. This is preexisting b162 behavior, not a
candidate cost result. The path-only receipt is
`/tmp/coffee-gb-b162-case010-scalar-batched-stage-20260909/execution-20260909/execution-receipt.json`.

## Implemented coverage

| Plan item | Change | Correctness and recovery evidence |
| --- | --- | --- |
| F1: pending interrupts | IME-off epochs cover all ten known profile/clock rows, including a stable, uncleared phased mode-2 STAT request | CPU-written DI/IE/STAT workloads; all STAT masks; restored EI, dispatch, RETI, IF-clear and HALT transitions |
| F2: timing-register access | Short-lived STAT/LY read capabilities cover all CPU addressing forms; demand hints avoid read-proof work in unrelated instructions | Stable-value horizons, event-phase sweeps, read/modify/write fences, bus-read counts and hint invalidation after restore |
| F3: STAT work | Pending latches limit spans to their next event; very short native capture intervals use a scalar lease; disabled-source native checkpoints aggregate | Register/IF/acceptance tests, all masks, LYC edge lines, finite-burst recovery and whole-machine polling scenarios |
| F4: HBlank DMA | Normal/double-speed armed waits and settled HALT batch; owned WRAM/immutable-ROM source interiors batch | Waiting versus bus ownership, 1–31-dot tails, scalar destination commits, HALT and restore tests |
| F5: rejected lines | Empty HBlank recovers after scalar rendering; unobserved LCDC history advances exactly in bounded work; native mode-2 checks the consumed sprite-size history; detailed packets recheck the quiet plane after their first exact dot | Repeated mode-3-entry writes, window checkpoints, delayed-output state, restored history rings, partial OAM scan slots and positive/negative first-dot recovery checks |
| F6: LCD/speed topology | LCD-off epochs/HALT cover missing native and compatibility rows; remaining caller budget re-enters the new clock topology | Exact blank-frame counts, frozen GPU state, split budgets and speed-switch tails |
| F7: serial/IR | Known endpoints advertise exact edge/callback horizons; inactive countdown interiors batch | Transfer edges, timeout/acknowledgement callbacks, restored partial spans and conservative unknown endpoints |
| F8: mapper access | Logical instruction reads execute once; ordinary BasicRom/MBC1/2/3/5 RAM data access borrows a typed capability | Mapper/overlay invalidation, canonical reads, RAM access and explicit special-window rejection |
| F9: held input | Settled legacy input and SGB multiplayer snapshots retain spans | Input generations, filtering/sample boundaries, JOYP interrupts and restored state |
| F10: timer/APU | Ordinary quiet/HALT TIMA increments and pulse/wave edges advance arithmetically; sweep splits its actual event | Overflow/reload/wake boundaries, channel state, materialized samples and extreme frequency cases |
| F11: transfer-heavy work | Native CGB x2 ROM/HRAM CPU work and independent peripherals batch while OAM/PPU/STAT retain exact replay; eligible HBlank, disconnected mode-2 and exact background intervals advance in bulk | WRAM-source ownership limits, CPU lifecycle/bus fences, full component state, short restored tails and positive steady coverage |
| F12: CGB0 | Registered native/compatibility x1 rows use their revision timing proofs | Separate revision/profile tests; unknown profiles remain excluded |
| F13: deliberate observation | Existing debugger, history, mutable-alias and linked-session policies remain explicit; counters use detached snapshots | Diagnostic transparency and existing observer/alias recovery protections |

Repeated OAM DMA also exposed a pre-existing native-x2 write-journal seam: an FF46
write omitted its same-dot DMA clock. The commit now applies that clock once, matching
the scalar owner and the existing x1 journal seam. Detailed PPU intervals retain canonical
per-dot DMA. Within a separately proved quiet PPU interval, owned WRAM copies advance from
one byte edge to the next, preserving each source read, OAM write and copy-clock phase.

The detailed native-x2 CPU lane admits ROM and HRAM through `FFFD` only, with IME off.
Lifecycle instructions, externally observable memory accesses and PPU/ownership events
end the lease. It neither journals arbitrary writes nor introduces a pixel approximation.
The exact no-object background cursor can run during eligible native-x2 OAM transfers;
the approximate scanline renderer retains its DMA rejection.
Detailed replay is attempted only when it can supply a missing PPU/DMA execution path,
and requires at least eight eligible dots before CPU execution. Whole-instruction folding
in that lane covers narrowly proved register arithmetic and ROM/HRAM control flow; other
instructions retain individual boundary checks.

## Reference contracts

Accuracy remains the scalar hardware model. Scheduler tests compare batched and
forced-scalar Performance with the same rendering/audio policy, including materialized
component state and restored continuations. The existing line-entry scanline composition
and compact audio representations remain deliberate Performance differences.

Diagnostics separate scalar, epoch, HALT, transfer and phase master ticks from nested
DMA/PPU/STAT replay and APU materialization. Rejected-line counts, span lengths and
scalar streaks identify persistent patterns. These counters are not CPU-time attribution;
instrumented timings are excluded from baseline/candidate throughput comparisons.

The existing native-x2 deferred-write contract can leave a one-dot difference in a
historical STAT-write timestamp after its captures have settled. Active-write tests check
exact partition and restore invariance; finite-burst recovery checks compare CPU and
bus-visible results, then exact quiet execution from an identical settled state. They do
not claim full forced-scalar memento equivalence across the deferred-write interval.

Catalogue discovery exposed a decoded-CPU boundary gap in the initial detailed runner:
HDMA's released opcode prefetch can leave POP in an operand-complete state before its
stack access. The initial check inspected only the running state. An authored test
reproduced the same strict-bus exception against the prior compiled candidate; checking
both states now fences the stack access before entering the detailed owner. Tests cover
partial budgets, restored prefetches and other stack/data instructions.

## Correctness receipts

| Check | Baseline | Candidate | Status |
| --- | ---: | ---: | --- |
| Initial complete core unit battery | 2,058 | 2,111 | Eight existing skips on both. Candidate had one obsolete CPU-only HDMA admission assertion; corrected ownership expectations passed 98 focused tests. |
| Mooneye, DMG/CGB acid2, Mealybug | 158 | 158 | All passed |
| Blargg individual and combined | 54 | 54 | All passed |
| AntonioND GB/CGB hardware captures | 221 | 221 | All passed |
| Automated GB microtests | 482 | 482 | All passed |
| Updated Android JVM tests | — | 297 | All passed, including pinned APKs `950b83b8…`, `ad8a99ac…` and provisional `e19684f0…`; signed R8 APK and install-time ART profile packaging verified |
| Combined STAT checkpoint, cold HRAM and timing-tile overlay | — | 76 | All passed before the LCDC write-timeline integration; overlapping focused coverage, not the final-source battery |
| LCDC write timeline with STAT/HRAM/timing-tile integration | — | 92 | All passed with no skips; source stayed unchanged during the 226.6-second focused run. Complete snapshot unit and hardware checks passed separately below. |
| Complete core unit rerun, 09:53 discovery snapshot | — | 2,133 | All passed; eight existing skips |
| Complete core unit rerun, LCDC-gap `ad8a99ac…` source snapshot | — | 2,190 | All passed; eight existing skips; 559.8 seconds. This receipt predates the provisional CPU/DMA/output extensions. |
| Complete hardware rerun, LCDC-gap `ad8a99ac…` source snapshot | — | 915 | All passed with no skips: 640 Mooneye/acid2/Mealybug/microtests, 54 Blargg and 221 AntonioND; 331.3 seconds combined. |
| Provisional whole-instruction CPU, DMA-dot and exact output-tile union | — | 176 | All passed in 358.7 seconds before integration; six production files matched the tested overlays when integrated. Complete post-integration battery and device acceptance remain open. |
| Soak collector/analyzer fixtures | — | 22 | All passed |
| Sanitized diagnostic report fixtures | — | 12 | All passed |

Later focused checks cover the phased-STAT request, detailed CPU/DMA replay and bounded
LCDC history changes. The 09:53 discovery snapshot's complete rerun passed 2,133 tests with
no failures or errors and the same eight skips. Later profitability and mode-2 changes
require their own receipts. Focused test counts
overlap the full suite and must not be added to it as distinct tests. A focused
STAT/APU check passed 227 tests with no failures, errors or skips, including both capture
recovery tests and high-frequency scalar channel comparisons. A later 465-test focused set
passed after the released-HDMA CPU fence fix and WRAM copy changes; this includes the
mode-2/history composition test and all DMA copy-clock residues. Hardware integration
tests execute Accuracy; they do not substitute for positive batching coverage tests.

The first-dot raster/mode-2 recovery then passed a 322-test focused set, including restored
pending-write prefixes, a nonrecoverable mode-3 case, CPU/STAT/DMA tests and workload checks.
These focused sets overlap and are separate from the later complete LCDC-gap snapshot rerun.

An experimental PPU-first admission order passed 347 focused tests but was reverted after
isolated controls consistently added about 3–4% host time to the exact sprite-filled OAM
fixture. The scalar-first capture alternative remains isolated as well. Neither experiment
is part of the retained implementation.

The retained LCDC history arithmetic uses a small zero-drain wrapper and a separate drain
helper. A subsequent 347-test set passed with this change and exact steady-background
continuation after OAM release, including all short history prefixes, restored continuation,
subsequent writes and positive coverage on both sides of transfer release. The steady plane
covers 5.83% of dots in a separate exact OAM-fixture diagnostic run; these controls have not
established a throughput gain from that plane. The isolated cold-helper comparison on the
prior owner reduced OAM host time by 2.38% and 2.12% in two forks, removing the earlier
consistent history-arithmetic regression. These overlapping focused receipts do not replace
the separately recorded complete snapshot battery.

Exact eight-dot native-CGB timing-skeleton tile advancement subsequently passed 48 focused
tests, including complete state, ordered VRAM reads, all SCX residues, CGB/CGB0 at both speeds,
and restored partial intervals. Two isolated host forks reduced the exact OAM fixture's cost
by 1.93% and 6.11%. LCDC controls changed −0.54% and +0.76%, while CPU controls improved in
both forks. A combined STAT-checkpoint/cold-HRAM/timing-tile overlay then passed 76 focused
tests. The operation is included in the pinned `950b83b8…` APK measured below. The later
LCDC write-timeline integration passed 92 focused tests and the complete snapshot battery
recorded above. The visible pixel machine is unchanged.

The complete LCDC-gap rerun retained source-manifest hash `24b3660a…` and verified all 461
shared core/controller/portable production files against the APK build manifest `003ee8c0…`.
Sources and the immutable production-class snapshot remained unchanged through all stages.
These passes apply to the tested snapshot; subsequent implementation changes need their
own validation, and host/device throughput gates remain open.

At 14:54 UTC, the exact tested CPU/DMA/output union was provisionally integrated: six
production files and five test classes with 16 new methods. Its 176-test focused pass
includes prior CPU, LCDC, DMA and PPU coverage; it is not an additional disjoint battery.
The prior `ad8a99ac…` full-suite, catalogue and device receipts do not apply automatically
to this new source. Its new Android build passed 297 JVM tests; final retention and the
complete post-integration core battery remain open.

Subsequent staged checks passed for opcode classification metadata (93 methods), the
BasicRom physical reader (96 unique methods), native VBlank history/checkpoint coverage
(207 unique methods after correcting two obsolete fixture expectations), and the smaller
one-use LCDC retry (three methods). These overlapping checks precede their combined
integration and do not replace its full battery.

Arbitrary-dot restore tests found three omitted Fetcher coordinates in both the original
baseline and the current implementation. New captures now retain the tile column, sampled
horizontal position and object-fetch flag. The controller accepts historical records with
deterministic defaults and preserves their encoded form; old files cannot recover values
they never stored. Core restore/FIFO checks passed 16 methods and portable/legacy controller
checks passed 12 before integration. Execution and tick behavior are unchanged.

The canonical-state/hash review found two separate intentional causes for the historical digest
changes. First, the Fetcher memento now retains three hardware coordinates (`tileMapX`,
`xBasePosition` and `xBaseObjectFetch`); current canonical StateFiles and replay semantics v2
include them. Replay semantics v1 keeps its established hash domain and uses a detached
projection that removes only those three appended coordinates for legacy checkpoint hashing;
it leaves the live/canonical StateFile and restore path complete. The unchanged committed v1
replay fixture is verified, but a legacy replay that depends on corrected emulation state may
still diverge. Second, CGB/CGB0 SKIP construction now publishes `HdmaState.lcdEnabled` from
the post-boot GPU level. That real hardware-state change accounts for the residual CGB/CGB0
historical baseline difference; it is retained in canonical state and is not hidden by the
legacy replay projection.

Six persistent MMIO polling fixtures now cover IF, IE, DIV, TIMA, JOYP and NR52. Two grouped
tests cover 66 form/profile/event/restore scenes; the independently tested Timer read oracle
covers 30 further scenes and rejects stale samples or shifted writes. Only the original
current/previous-dot Timer read contract is accepted; other bus transactions and complete
machine state remain compared. The portable 39-case runner and separate 60-case polling
manifest are checked by the combined 24-test Python suite. The four separate host matrices
then executed 126 cases × 3 trials with matching frame and diagnostic accounting. The immutable
run receipt is `/tmp/coffee-gb-retained-fence-stage.uRQeLT/final-host-matrix/four-matrix-run-receipt.json`;
the stale preparation flag is corrected by
`/tmp/coffee-gb-device-coverage-review-20260908/host-matrix-execution-erratum.json`. Long
controls have run: 13 of the 15 initial flags cleared. The subsequent three-variant speed
controls did not reproduce a stable tax, while the latest selected Timer controls were mixed;
these remaining timer/CPU controls stay unselected pending attribution. Results apply to the
selected source/class snapshot recorded by that receipt; any later source repinning requires a
fresh attribution and run. The matrix receipt includes enabled-versus-disabled diagnostic
measurements, and the final selected-source erased-diagnostics screen is now complete for three
bounded catalogue rows with no repeatable greater-than-3% tax. The later LYC ten-case and Barcode screens were run as isolated historical 58cc/overlay
measurements; they are recorded in the current-status and isolated-measurement sections and do
not represent final selected-source cost. The post-union catalogue run is recorded above as
complete.


## Host comparison

The selected-source host comparison has executed the four separate 39/60/15/12 matrices:
126 cases × 3 trials, with separate alternated baseline/candidate JVMs, warmup before timing,
and matching frame and diagnostic accounting. The receipt is
`/tmp/coffee-gb-retained-fence-stage.uRQeLT/final-host-matrix/four-matrix-run-receipt.json`,
with execution bookkeeping corrected by
`/tmp/coffee-gb-device-coverage-review-20260908/host-matrix-execution-erratum.json`.
The approved longer paired controls have run and cleared 13 of the 15 initial flags. The later
three-variant speed controls did not reproduce a stable tax; the latest selected Timer controls
were **+3.845% / +1.913%** and mixed, and the timer/CPU controls do not favor the overlay. Any
later source repinning requires a fresh matrix and attribution. Enabled-versus-disabled
diagnostic measurements are recorded, while final selected-source disabled-instrumentation/
erased-code measurements include the completed bounded final-source screen above, which found no
repeatable greater-than-3% tax in its three rows. The LYC ten-case and Barcode screens are separate
historical/overlay measurements; they do not represent final selected-source cost.

A local peripheral-horizon sharing experiment was rejected before integration. Both versions
passed their boundary/state tests, but the exact exported LCDC fixture took more host time in
both reversed-order forks: 14.68%/7.95% for eager acquisition and 3.35%/4.41% for acquisition
after the first graphics journal. All measured windows retained 239 frames. The shared ordinary
audio horizon also reduced detailed-epoch coverage. Additional controls were stopped after
the target regression was established; their incomplete rows are not an acceptance result.

Two smaller admission experiments also remain outside the implementation. Moving the GPU's
quiet-mode rejection earlier passed 90 focused tests but increased `083` host time by
2.26%, 2.86% and 4.49% in three trials. Removing repeated HDMA commit proofs passed the
combined 83-test IR/HDMA check but had mixed costs, with regressions in two of three trials.
The separate inert-IR shortcut has mixed results and is likewise unselected. Simpler source
control flow did not reliably produce cheaper compiled execution in these measurements.

Isolated detailed-STAT checkpoint composition passed 243 tests, and a live HRAM instruction
read capability with a separate fallback helper passed 38. Their combination passed another
28 interaction tests. Two paired host forks reduced the exact exported OAM fixture's cost by
5.03% and 6.30%, but increased LCDC cost by 4.17% and 8.61%; CPU controls varied between
forks. These changes are provisionally included in the next working device candidate;
their retention still depends on further cost evaluation and device evidence.

A later nine-control profiling run, before the final STAT/APU cost adjustments, measured
2.22× baseline throughput for repeated OAM DMA and 1.59× for repeated LCDC writes. The
LYC-write workload was 3.4% slower in host time, down from a previously measured 51.4%
regression. Held-input and high-rate audio controls still cost about 10% more than baseline;
their sampled hot paths motivated the final adjustments. These are intermediate controls,
not a completed matrix or an Android acceptance result.

A subsequent isolated APU experiment compared identical current classes with only the three
pulse/wave arithmetic methods changed. Extra single-expiry/power-of-two branches cost 4.73%
and 2.87% more host time in alternating paired forks than the generic quotient arithmetic.
Those late branches were removed; the general F10 arithmetic implementation remains.

The subsequent first-dot recovery snapshot has two independent paired JVM forks per
control (32 million warmup ticks, then three 16-million-tick measurements per fork):

| Workload, native CGB ×2 | Candidate host-time change from original baseline |
| --- | ---: |
| Repeated LCDC writes | −43.3%, −50.5% |
| Repeated OAM DMA | −54.5%, −57.7% |
| CPU control | +1.4%, +7.5% |
| Repeated LYC writes | +7.8%, +1.7% |

An isolated prior-owner overlay on the same classes attributes an 8.1% and 24.0%
LCDC host-time reduction to first-dot recovery. The spread between independent JVMs
exceeds their within-fork variation, so the small control regressions remain unresolved;
these measurements do not certify the 3% investigation threshold or device cadence.
Later admission and history-arithmetic experiments are outside this snapshot.

A later isolated comparison used the pinned LCDC-gap `ad8a99ac…` core snapshot as its
reference. It compared whole-instruction CPU immediate/LCDC-store extensions, a WRAM
DMA-dot plus exact output-tile extension, and their combination. These were staged
overlays, not the source used for that APK's device results or its complete test battery.
The exact exported OAM/LCDC fixtures used FAST_FORWARD boot; the CPU control used SKIP.
All used native CGB ×2, fixed RTC/input, disabled diagnostics and no battery persistence.

Each of the 24 separate JVMs warmed up for 134,217,728 ticks (32 emulated seconds), then
measured three consecutive 33,554,432-tick windows (eight seconds each). Two forks reversed
variant order, with JDK 21, Serial GC and CPU affinity fixed to core 2. The values below
compare each fork's median host nanoseconds per tick with its LCDC-gap reference;
negative values mean less host time. They are not comparisons with the original revision.

| Workload | CPU extensions, forks 1 / 2 | DMA/output extensions, forks 1 / 2 | All extensions, forks 1 / 2 |
| --- | ---: | ---: | ---: |
| Exported OAM | +1.04% / +0.32% | −2.52% / −6.83% | +1.80% / −7.08% |
| Exported LCDC | −3.84% / −4.50% | +8.80% / +0.52% | −1.31% / −6.83% |
| CPU control | +5.21% / −2.20% | +0.53% / −7.56% | +2.27% / −6.81% |

All 72 rows were retained and independently checked against the raw output. Within each
scene and measurement round, every variant matched emulated ticks, frame events, epoch
counts, epoch ticks and bulk ticks. The CPU control produced 478, 477 and 478 frame events
in successive windows in both forks; both exported workloads produced 478 in every window.
The driver's initial assertion incorrectly required the same frame count across different
windows. Corrected postprocessing checks equality across variants at the same window and
reproduces the reported medians; no measurement was rerun to repair that assertion.

The CPU extensions consistently reduced the target LCDC cost. The DMA/output combination
reduced OAM cost in both forks but increased LCDC cost; combining all changes still produced
mixed OAM/control results between forks. These longer measurements narrow the retention
decision but do not resolve control variation, establish the final 3% regression gate, or
predict sustained Android cadence. The raw-output receipt is `37b07cd5…`; the summary is
`3796a564…`. Final measurements must identify the exact retained source combination.
The combined overlay was subsequently integrated provisionally after the 176-test union
pass; this does not convert the stage comparison into a final host/device acceptance result.

Another isolated GPU experiment used that CPU/DMA/output union as its reference. It
compared extracting the existing dot-phase operation into a shared helper with using an
additional specialized entry inside the LCDC owner. The same 32-second warmup and three
eight-second measurement windows ran in 18 separate JVMs with two reversed-order forks:

| Workload | Helper extraction, forks 1 / 2 | Specialized interior entry, forks 1 / 2 |
| --- | ---: | ---: |
| Exported LCDC | −0.94% / −6.04% | +4.90% / +6.91% |
| Exported OAM | −4.10% / −3.66% | −1.93% / +3.44% |
| CPU control | −6.44% / +11.18% | −9.86% / +0.88% |

All 54 raw rows matched ticks, same-window frame events and epoch/bulk accounting across
variants; independent postprocessing reproduced the medians. The specialized entry was
rejected because its target LCDC cost increased in both forks. Helper extraction remains
unintegrated: its target improvements coexist with a large change of sign in the CPU
control. Neither variant is part of APK `e19684f0…`. Raw and summary receipts are
`5a5c1305…` and `ff8de8b5…`; no final acceptance follows from these stage measurements.

Two further isolated experiments used the same 32-second warmup, three eight-second
measurement windows and reversed two-fork order against the immutable `e19684f0…`
core. These changes are staged; they are not part of that APK:

| Experiment / workload | Host-time change, forks 1 / 2 |
| --- | ---: |
| Immutable CPU opcode classification / LCDC v2 | +1.46% / −1.90% |
| Immutable CPU opcode classification / OAM v2 | −3.13% / +2.00% |
| Immutable CPU opcode classification / CPU | −9.25% / +0.08% |
| Native VBlank history advancement / LCDC v2 | −5.33% / −4.43% |
| LCDC packet admission (measured lines 143–152) / LCDC v2 | −3.61% / −2.05% |
| Both VBlank changes / LCDC v2 | −8.38% / −3.33% |

The CPU experiment passed 93 focused methods and all 18 measured pairs matched ticks,
frames and scheduler accounting exactly. Its target cost changes are mixed. The VBlank
history and packet experiments passed 9 and 24 focused methods respectively; their
combination covered 207 methods successfully after correcting two fixture expectations.
All six four-variant measurement groups retained identical ticks and frames, with bounded
scheduler accounting. VBlank coverage retains the existing line/frame, pending-write,
STAT-source and observation guards; no new rendering approximation is involved.
Unrelated workload controls and device measurements still determine retention.

Additional correctness-proven stages include a one-use LCDC owner retry hint (19 unique
focused methods) and a physical ROM reader for exact `BasicRom` instances (96 unique
methods). The hint grants no permissions: all admission checks repeat, and rejection or
a packet without writes expires it. The ROM reader also satisfies the existing immutable
HDMA-source proof; every 1–31-dot transfer prefix, destination commit and restored
continuation is checked with nonuniform data. These focused sets overlap prior coverage;
they are neither a combined-source battery nor device acceptance.

The disabled-diagnostics comparison ran against the immutable `e19684f0…` core
snapshot. Identically compiled stock owners and nested classes match all 36 original class
files byte for byte. The alternative changes only four diagnostic fields to static final
null and makes their setters reject nonnull diagnostics. Bytecode inspection confirms those
fields, null initialization and setters; null-guard instructions remain before JIT folding.
Thus its separate measurement concerns warmed nullable versus statically absent diagnostics,
including possible layout/compiler effects, rather than proving zero interpreted overhead.

The first run used five synthetic scenarios with SKIP boot, eight emulated seconds of
warmup and three four-second measurement windows per JVM. Three forks per variant/scenario
produced 30 sequential JVMs and 45 matched pairs. All pairs agree exactly on ticks, frame
events, epoch counts, epoch ticks and bulk ticks; independent raw-row checks reproduce
every reported median. The table reports `100 × (stock / static-null − 1)` using each
fork's median host nanoseconds per tick, so positive values mean greater stock cost:

| Synthetic scenario | Stock cost relative to static-null, forks 1 / 2 / 3 |
| --- | ---: |
| CPU | −9.99% / −5.32% / +8.04% |
| LYC writes | −1.84% / −0.51% / +0.51% |
| Audio | +2.92% / −2.11% / −7.89% |
| OAM DMA | +1.43% / +6.49% / −2.01% |
| LCDC writes | +6.65% / +1.36% / +5.02% |

The LCDC result motivated a separate follow-up using the exact exported OAM/LCDC v2
fixtures, FAST_FORWARD boot, 32 emulated seconds of warmup and three eight-second windows
per JVM. Its 12 JVMs provide three stock/static-null forks per fixture. All 18 matched
pairs retain 478 frame events per window and exact ticks/epoch/bulk accounting. This
follow-up reports the reverse ratio, `100 × (static-null / stock − 1)`; positive values
mean greater cost after removing nullable fields:

| Exported fixture | Static-null cost relative to stock, forks 1 / 2 / 3 |
| --- | ---: |
| OAM v2 | +1.38% / +3.34% / +4.18% |
| LCDC v2 | −1.93% / +1.62% / −2.05% |

Both runs use native CGB ×2, fixed RTC/input, CPU 2, JDK 21 and Serial GC, with diagnostics
disabled and alternating variant order. Their scenes, boot histories, warmups and ratio
denominators differ. The small synthetic LCDC result cannot be generalized to the exported
fixture; conversely, the exported result does not establish negligible overhead in all
workloads. The changes of sign, including higher OAM cost after removing nullable fields,
also prevent assigning the ratios solely to null-check instructions. Synthetic raw/summary receipts are `4be13fe8…` /
`9253bcf0…`; exported receipts are `44c6a52a…` / `64f0d759…`. Enabled-diagnostics overhead
still requires the separate workload matrix.

These are host JVM measurements. The source-matched Android R8/DEX review finds no retained
`PerformanceDiagnostics` type mapping or CPU/Gameboy diagnostic field/setter mappings;
both mapped DEX classes are present and their fields contain no diagnostic descriptor.
That supports removal in APK `e19684f0…`, without quantifying a device saving. The host
ratios therefore do not attribute the Android cadence deficit to disabled diagnostics.

Expanded polling probes reproduced the original revision's documented current-dot sampling
skew. After identical scalar startup, original and candidate batched TIMA/DMG reads both
produced 252 where scalar produced 253; indirect DIV/native-CGB×2 reads both produced 3
where scalar produced 4. Final Timer state matched. The retained contract permits the
current CPU dot's pre-Timer view; tests must pin that narrow bound rather than discard CPU
or RAM differences generally. No timer synchronization change was made from these probes.

A later selection compared the `e19684f0…` production classes with BasicRom physical reads,
opcode metadata and native VBlank coverage, with and without a smaller one-use LCDC retry.
It retained 18 JVMs and 54 windows, using the exported v2 LCDC/OAM fixtures and CPU control,
the same 32-second warmup, three eight-second measurements and reversed second-fork order.
The following percentages are candidate host cost relative to that preceding candidate:

| Scene | Without retry, forks 1 / 2 | With smaller retry, forks 1 / 2 |
| --- | ---: | ---: |
| LCDC v2 | −22.31% / −17.39% | −23.17% / −19.78% |
| OAM v2 | −7.89% / −7.78% | −6.90% / −10.53% |
| CPU | +1.33% / −10.30% | +2.79% / −7.08% |

All 18 matched groups retain equal frame events and ticks, bounded disjoint scheduler
accounting, and exact non-LCDC epoch accounting. LCDC packet counts legitimately change.
Raw receipt `477ff14a…` identifies this selection. The retained cold one-use retry
combination is integrated for the next device candidate. Earlier retry/union results showed
OAM and CPU regressions, and these later measurements do not erase that variability or isolate
each component's cost. Host improvements are not a sustained Android acceptance result.

The separate FF40 early-dispatch comparison remains rejected and is not part of that retained
selection. Its archived 18-pair, 12-JVM, 36-row comparison measured FF40 relative to the prior
candidate at forks 1/2 as LCDC −10.34335%/+4.62425%, OAM −7.49083%/+0.51614% and CPU
−10.83821%/+0.16443%. The second fork regressed LCDC, and the pooled agent median was not
accepted, so this FF40 stage is excluded from the integrated source and device attribution.

## Catalogue discovery

The paired discovery sweep completed against the immutable **09:53 UTC intermediate
snapshot**, before subsequent profitability tuning, detailed CPU admission fixes, owned
mode-2 batching and DMA copy strides. Its fixed-seed
sample contains 96 distinct filename families
from 3,132 uncompressed local GB/GBC files across 22 configured-hardware/header-mapper
strata. These use the configured DMG/CGB profile and raw-header mapper; parser/bootstrap
overrides can change the effective mapping or enter compatibility mode. All 96 parse
successfully. Resolved mappings include 76 STANDARD samples and 20 samples across 12
special mapper policies; STANDARD spans 16 cartridge types. There are 51 configured CGB
and 45 configured DMG sessions. Among the 95 successful pairs, final CPU multipliers are
CGB ×1 for 31, CGB ×2 for 19, and DMG ×1 for 45; final compatibility mode was not recorded.

The sweep uses production `runTicks`, FAST_FORWARD bootstrap, 12 emulated seconds of
warmup and eight measured seconds, with the same deterministic Start/A/direction schedule
and fixed RTC source. Separate JDK 21 JVMs alternate baseline/candidate order, with
diagnostics disabled, one assigned host CPU and Serial GC. The sweep pauses between
complete pairs for builds and unrelated measurements. ROMs are read in place with battery
persistence disabled. Shareable
records contain opaque workload IDs, not library paths, ROM/save data or their checksums.

| Intermediate discovery result | Measurement |
| --- | ---: |
| Successful baseline/candidate pairs | 95 of 96 |
| Candidate-only failures | 1, subsequently reproduced and fixed |
| Frame-count mismatches / zero-frame successful pairs | 0 / 0 |
| Geometric mean / median candidate-to-baseline throughput | 0.9694× / 0.9681× |
| Candidate minimum / median multiple of nominal emulated clock | 3.015× / 6.011× |
| Single-trial cases above 3% additional host time | 50 of 95 |
| Candidate throughput below 90% / above 110% of baseline | 7 / 2 |
| Minimum / median combined epoch plus other batched tick coverage | 82.30% / 89.78% |

Every successful scene retains substantial batching. Low epoch counts alone would still
misclassify some cases: `catalogue_083` spends 0.62% of ticks in CPU epochs and 90.68% in
other batched spans. The uninstrumented bulk counter combines HALT, transfer and phase-only
spans; diagnostics are required to separate them. Conversely, the worst relative regression retains 96.68% epoch
coverage. Repeated throughput measurements and separate diagnostic cost signatures are
needed to distinguish expensive work inside spans from scalar fallback.

The seven strongest single-trial flags are below. Ratios measure throughput, not host-time
increase; these are **intermediate-artifact discovery flags**, not confirmed regressions in
the final source or Android acceptance results.

| Opaque ID | Configured profile / final CPU multiplier | Resolved mapper policy / cartridge family | Throughput ratio |
| --- | --- | --- | ---: |
| `catalogue_063` | CGB / ×2 | STANDARD / MBC1 | 0.830× |
| `catalogue_010` | CGB / ×2 | SACHEN_COOKED / MBC5 | 0.835× |
| `catalogue_037` | DMG / ×1 | STANDARD / MBC3 | 0.865× |
| `catalogue_032` | CGB / ×1 | MAKON_NT_OLD_2 / ROM | 0.868× |
| `catalogue_042` | CGB / ×1 | STANDARD / HuC1 | 0.875× |
| `catalogue_060` | DMG / ×1 | STANDARD / ROM | 0.876× |
| `catalogue_083` | CGB / ×1 | WISDOM_TREE / ROM | 0.883× |

`catalogue_028` exposed a strict detailed-CPU bus rejection during a POP low-byte read.
The baseline completed the scene; the intermediate candidate threw `IllegalStateException`.
Investigation produced an authored regression: a released HDMA opcode prefetch can leave
a zero-operand POP in OPERAND, while the old boundary proof examined data cycles only in
RUNNING. The corrected proof and focused CPU family tests pass. The identical private
scene also passes after the fix, producing 478 frame events and 27,690,479 epoch ticks out
of 33,554,432 measured ticks. That recovery receipt is separate from the original failed
discovery result.

The follow-up snapshot captured at **11:51:40 UTC** includes the first-dot HBlank/mode-2
suffix recovery and the corrected DMA/CPU paths. Its selected 14-case set completed **42
paired trials**: the seven flags above; slow cases `004` and `067`; lower-batching cases
`015` and `038`; positive controls `080` and `044`; and failure recovery `028`. Numeric
suffixes refer to the same `catalogue_` opaque IDs. All pairs completed without errors or
frame-count mismatches, including all three `028` candidate runs. Every case has explicitly
recorded version order balanced 2:1 across its three trials; completed timing observations
were retained when the third trial's order was corrected.

The geometric mean of the 14 case-median throughput ratios is **0.9840× baseline**. This is
a deliberately selected investigation set, not a new estimate for the full catalogue.
Seven case medians exceed the 3% additional-host-time investigation threshold:

| Opaque ID | Median throughput ratio | Median additional host time | Above 3% in every pair |
| --- | ---: | ---: | --- |
| `catalogue_083` | 0.902× | 10.88% | Yes |
| `catalogue_010` | 0.905× | 10.47% | Yes |
| `catalogue_060` | 0.912× | 9.66% | Yes |
| `catalogue_067` | 0.913× | 9.59% | Yes |
| `catalogue_042` | 0.922× | 8.41% | Yes |
| `catalogue_038` | 0.954× | 4.87% | No |
| `catalogue_015` | 0.955× | 4.70% | Yes |

The positive controls `044` and `080` improve median throughput by 17.55% and 25.14%.
`063` has a 0.975× median ratio but a 6.09% candidate coefficient of variation; its worst
pair must not be treated as a stable effect. Other candidate and all baseline cases have
coefficients of variation below 5%. These remain desktop results for the stated immutable
snapshot; further source changes and Android cadence require separate validation.

The separate **29-case diagnostic set completed** against the same snapshot. It combines
the eight slowest exposures, twelve lowest combined batching shares and twelve worst
discovery throughput ratios, with duplicates removed. Every report reconciles all measured
ticks, exposes all counters available in that snapshot and matches its original baseline
scene's frame count.
Instrumented timings do not enter throughput comparisons.

| Observed work signatures | Cases |
| --- | ---: |
| Materialized audio | 17 |
| Materialized audio and HALT/transfer dominance | 5 |
| Materialized audio and a long scalar run | 5 |
| All three above | 1 |
| HALT/transfer dominance with no materialized audio | 1 |

The maximum scalar share is 17.70%; no case has two consecutive scalar-heavy one-second
windows. Detailed PPU and STAT replay reach at most 1.74% each. Rejected lines reach 1.05%
in `004`; the longest rejected-line streak is two lines. These are work counters, not
sampled CPU time, and do not establish the device cadence gate.

The persistent `010` regression occurs with 94.56% epoch coverage, 1.32% PPU/STAT replay,
98.18% audio materialization and effectively no rejected-line exposure. INPUT is its most
frequent admission limiter. In contrast, `083` spends 90.45% of ticks in HALT batching,
0.23% in PHASE and 0.62% in epochs, with no materialized audio; `042` spends 73.98% in HALT
and materializes audio on 98.18% of ticks. These observations prioritize owner/peripheral
and APU sampling without assuming a single cause for every regression.

The `010` follow-up recorded three alternating baseline/candidate execution-stack pairs,
starting JFR after the same twelve-second warmup and stopping after the same eight-second
measured scene. Only execution/native stack sampling was enabled, at a 5 ms period, with
non-safepoint debug metadata enabled on both variants. The recordings contain 775 baseline
and 854 candidate Coffee GB samples. Inclusive sampled counts show the shift in work:

| Method | Baseline samples | Candidate samples |
| --- | ---: | ---: |
| `Gameboy.tickSubsystems` | 316 | 146 |
| `Gameboy.tryPerformanceEpoch` | 292 | 461 |
| `Cpu.tickPerformanceEpochInstructionPipelineAtMachineCycle` | 129 | 196 |
| `Gameboy.commitPerformanceEpochPeripherals` | 39 | 84 |

Additional candidate leaf hotspots include ROM-access acquisition, STAT checkpoint
admission and infrared horizon calculation. This evidence prioritizes admission and CPU
read-lease cost while retaining the reduced scalar work; materialized-audio coverage alone
does not identify the regression's cause. Inclusive samples overlap and are not additive
CPU-time measurements. Profiling timings are excluded from the paired throughput results.

The same bounded profiling procedure for `083` produced three successful pairs, each
with native CGB ×1 and 478 frame events. On the later history/steady-background snapshot,
Coffee GB stack samples total 423 for baseline and 485 for candidate. HALT-owner samples
rise from 109 to 145 and phase-owner samples from 50 to 68, while HALT commit samples
remain 24 in each. GPU quiet admission rises from 15 to 26 samples and infrared settled
horizon work from one to eight; SpeedMode getter leaf samples rise from eight to 18.
These observations point to repeated admission work, including unsuccessful short spans,
and do not establish any single predicate as the cause of the throughput regression.

Six cases retain scalar runs lasting 0.14–0.68 emulated seconds: `002`, `029`, `034`, `062`,
`077` and `087`. Their timing windows remain in the results; the scripted scenes have not
been inspected sufficiently to classify those intervals as harmless transitions. Older
snapshots report newly added counters as unavailable, rather than measured zero. The
current DMA counters distinguish per-dot replay from batched copy work, and recovered
PPU suffix ticks are coverage rather than a cost or failure signature. The later
`PPU_QUIET_STEADY` counter is unavailable in this snapshot; it must not be read as zero.

Catalogue repeats complement the authored F1–F12 workload matrix. The final artifact must
retain these family checks; the catalogue's mapper labels cannot substitute for them:

| Plan coverage | Authored workload or focused complement | Clock / mapper coverage |
| --- | --- | --- |
| F1–F3 | Pending-IF recovery, `MASKED_IRQ`, `STAT_POLL`, `LY_POLL`, `LYC_WRITES`, all STAT masks | All ten registered profile/clock rows; scalar edge and restored continuation checks |
| F4, F11 | `HBLANK_DMA`, `OAM_DMA`, released-prefetch CPU fences and owned mode-2/history composition | Native CGB/CGB0; x1/x2 request/ownership tests and positive coverage for each advertised transfer plane |
| F5–F6 | `RASTER_WRITES`, `LCDC_WRITES`, `LCD_OFF`, `LCD_OFF_HALT`, `SPEED_SWITCH` | Applicable native, compatibility and SGB rows; clock switches only on native CGB hardware |
| F7 | `SERIAL`, endpoint and infrared deadline/callback/restore tests | Normal/fast serial clocks; known endpoints and explicit unsupported-observer exclusions |
| F8 | `SRAM`, ROM/RAM capability and mapper invalidation tests | Ordinary BasicRom/MBC1/2/3/5 RAM; explicit rejection of side-effecting RTC/EEPROM/flash/overlay windows |
| F9–F10 | `HELD_INPUT`, multiplayer generations, `TIMER`, `AUDIO`, materialized sample comparisons | Released/held inputs, SGB multiplayer, all applicable CPU/APU clock domains |
| F12 | Separate CGB0 and CGB0-compatibility rows in the above tests | Revision-specific timing, without treating configured CGB catalogue runs as CGB0 evidence |

This is a short scripted scene sample. It does not establish that every sample reached
gameplay, or that later scenes have equivalent cost. Matching frame counts establish
cadence, not commercial pixel/audio/state equivalence. Hardware rows not represented by
the configured DMG/CGB sample receive authored tests and separate device checks.

## Android evidence

Device: Redmi 25078RA3EE, Android API 35. The device floor for a claim covering all low-end
Android hardware has not been specified. Tests below use audible output, visible authored fixtures,
Performance mode, disabled CPU performance hints and actual SurfaceFlinger presentations.

The following short measurements used an **intermediate** signed APK before the final
LCDC-history and OAM-HBlank recovery changes. Each authored fixture was identified visually
from its unique title before measurement. They established two sustained workload cliffs
and are not final acceptance results.

| Authored native-CGB x2 workload | Duration | Native FPS | Presented FPS | Work p95 | New audio underruns |
| --- | ---: | ---: | ---: | ---: | ---: |
| STAT polling, all sources enabled | 43.4 s | 59.76 | 59.71 | 14.66 ms | 0 |
| Repeated LCDC writes | 42.8 s | 19.05 | 19.06 | 52.62 ms | 819 |
| Repeated WRAM OAM DMA | 42.4 s | 20.20 | 20.22 | 49.62 ms | 861 |

These runs were USB-powered, thermal status 0, with measured CPU temperature around
50°C and skin temperature 33–35°C. Short results are inconclusive for the 15-minute soak
gate even when cadence is healthy. Earlier attempts without verified scene identity do
not count as evidence for the labelled workload.

A subsequent intermediate signed APK, still before the latest mode-2, CPU admission and
pending-write changes, improved the OAM fixture to 36.63 native / 36.73 presented FPS over
63.85 seconds, and LCDC to 27.37 / 27.42 FPS over 62.95 seconds. Both still failed
cadence and accumulated audio underruns (1,638 and 1,730 respectively), with no suppressed
frames. Sampling on that APK showed the emulation thread predominantly in AOT/JIT code;
the cliff was not attributable to running the core in the interpreter. A measured stream
predicate hotspot in pending PPU writes was replaced with bounded index iteration.

The next signed APK (`5489ff46…`) included those mode-2/admission/iteration changes but
preceded the released-HDMA CPU fix and WRAM byte-stride copy. After 25 seconds of warmup,
its clean audible OAM segment measured 44.81 native / 45.19 presented FPS over 30.28 seconds
(p95 22.27 ms, 528 new underruns). LCDC measured 34.63 / 34.63 FPS over 62.86 seconds
(p95 29.08 ms, 1,733 underruns). Both remained below cadence with no suppression or CPU
hints, actual nonzero unmuted audio, plugged power and thermal status 0. Sampling again
showed predominantly AOT/JIT execution. GPU work and repeated owner/peripheral admission
remain material costs; these measurements are failures, not sustained acceptance.

The subsequent **intermediate** APK (`4ea1ea9b…`) includes the released-HDMA CPU fence,
WRAM byte-stride copy and exact first-dot PPU recovery. Its signed APK and matching API35
install-time profile were verified against the installed package; ART reported
`speed-profile` / `install-dm`. All 297 Android JVM tests passed. Each same-title v2 fixture
had 25 seconds of ordinary warmup before its short measurement:

| Authored native-CGB x2 workload | Duration | Native FPS | Presented FPS | Final recent work p95 | New audio underruns |
| --- | ---: | ---: | ---: | ---: | ---: |
| Repeated WRAM OAM DMA | 63.53 s | 47.40 | 47.39 | 24.87 ms | 947 |
| Repeated LCDC writes | 62.91 s | 40.80 | 40.81 | 24.62 ms | 1,352 |

Both retained zero suppressed frames, actual unmuted media volume 1, no CPU performance
hints, controller priority 0 and USB power. Thermal status stayed 0; final CPU/skin
temperatures were 45.9/32°C for OAM and 48.7/33°C for LCDC. OAM's first/last thirds measured
47.42/47.30 native FPS; LCDC's measured 40.80/40.79. The flat cadence after warmup and
continuing audio underruns establish remaining persistent cliffs. These short records
are **INCONCLUSIVE for the full soak protocol**, and their observed cadence fails the
playback requirement; neither workload passes acceptance.

Separate 15-second `simpleperf` samples followed the timing runs, using each installed
APK's exact R8 mapping and AOT disassembly. Percentages below are sampled process CPU
cycles. Inclusive costs contain callees and overlap; they must not be added together.

| Sampled path | LCDC self / inclusive | OAM self / inclusive |
| --- | ---: | ---: |
| Epoch/replay owner | 8.16% / 72.63% | 6.13% / 50.89% |
| Scalar subsystem loop | 2.25% / 12.25% | 4.14% / 31.88% |
| CPU epoch instruction pipeline | 5.82% / 14.94% | 5.45% / 14.96% |
| `Gpu.tick` | 4.44% / 10.06% | 4.76% / 11.06% |
| Exact steady PPU cursor | — | 0.41% / 8.68% |
| Scalar native STAT update | 1.82% / 2.56% | 3.49% / 5.10% |
| Scalar OAM DMA tick | — | 2.76% / 4.28% |

LCDC's GPU-tick inclusive share fell from 22.24% in `5489ff46…` to 10.06%; repeated CPU,
GPU, STAT and peripheral admission checks now account for a material part of its remaining
cost. LCDC history ticking itself measured 1.28% self time. OAM retains substantial scalar
owner and bus work despite the quiet-copy path. Controller execution was predominantly
AOT/JIT code: 63.21%/18.91% of all sampled cycles in LCDC and 49.46%/33.19% in OAM; the
controller interpreter symbol was 0.06% in each. ART compilation settings were unchanged.
Profiling intervals do not count as cadence evidence. The device was stopped afterward,
and original media volume 0 and DND mode 1 were restored and verified.

A later host-only phase histogram used the exact exported OAM fixture with the retained
steady-cursor/history changes. Over 4,194,304 measured dots, 378,873 were scalar (9.03%).
The largest recorded zero-horizon cause was STAT at 164,745 dots, followed by DMA at
43,583; detailed and ordinary input deadlines accounted for 38,358 and 21,448 respectively.
These are existing evaluated deadlines, with no extra proof queries, and the instrumented
run matched the complete canonical state of an uncounted run. Only 5,509 scalar handoff dots
materialized 925,512 previously deferred dots in each pixel machine, explaining why scalar
CPU-time share exceeds scalar-dot share. The same diagnostic counted 651,002 detailed HRAM
opcode peeks in addition to 1,005,838 epoch instruction fetches. These counts identify work
to investigate; they do not establish the benefit of a proposed optimization.

The next **intermediate** APK (`950b83b8…`, built 13:41 UTC on 8 September 2026)
adds the existing STAT-checkpoint proof in the detailed owner, cold HRAM-read helpers and
exact timing-skeleton tile advancement. It precedes the LCDC write-timeline integration.
The build receipt pins source-manifest hash `cafa0cd4…`, core JAR `ef3367c4…`, signed APK
`950b83b8…` and API35 profile `0eddf1aa…`; the installed APK/profile matched those artifacts
and ART reported `speed-profile` / `install-dm`. All 297 Android JVM tests passed again.
The checkout still reports the baseline Git HEAD, so these working-tree/source and artifact
identities, rather than HEAD alone, identify this candidate.

After 25 seconds of ordinary warmup, its visually verified v2 fixtures produced:

| Authored native-CGB x2 workload | Audible duration | Native FPS | Presented FPS | Final recent work p95 | New audio underruns |
| --- | ---: | ---: | ---: | ---: | ---: |
| Repeated WRAM OAM DMA | 53.35 s | 53.06 | 53.14 | 24.32 ms | 491 |
| Repeated LCDC writes | 52.66 s | 39.52 | 39.52 | 27.87 ms | 1,198 |

Both runs retained zero suppressed frames, actual unmuted media volume 1, no CPU performance
hints, controller priority 0 and USB power. Thermal status remained 0; final CPU/skin
readings were 45.5/32°C for OAM and 44.9/32°C for LCDC, with battery temperature 28°C.
The first/last thirds measured 53.22/52.80 native FPS for OAM and 39.52/39.60 for LCDC.
The observed cadence and continuing underruns remain a **FAIL** for smooth playback.
These intentionally stopped short collections retain continuous audible/compositor sample
intervals; their collector status is **INCONCLUSIVE for sustained acceptance**. Neither
satisfies the 15-minute/thermally settled soak gate.

A separate 15-second OAM `simpleperf` run on `950b83b8…` retained 76,107 CPU-cycle
samples and used its matching R8 mapping. The epoch/replay owner measured 7.00% self /
57.74% inclusive, `Gpu.tick` 6.22% / 13.28%, the CPU instruction pipeline 5.90% / 14.16%,
and scalar `Dma.tick` 3.40% / 5.33%. Scalar `Gameboy.tick` retained 26.65% inclusive cost;
steady pixel output retained 5.33%. These overlapping sampled costs identify the remaining
work on that artifact; they are separate from the unprofiled cadence interval and do not
prove the benefit of any single change or certify the later LCDC-timeline APK.

The LCDC write-gap APK (`ad8a99ac…`, built 14:09 UTC) adds a bounded native-x2
LCD-on FF40 write timeline. It preserves individual CPU write dots and advances only
independently proved quiet gaps between them. Existing STAT, CPU observation/lifecycle,
DMA and peripheral-event fences remain part of its admission contract. The 92-test
combined integration includes populated-pixel/register-bit cases, memory/lifecycle fences,
restored prefixes and real PCM, TIMA, serial and input deadlines after multiple writes.
Its source manifest is `003ee8c0…`, core JAR `2ae1948d…`, APK `ad8a99ac…` and API35
profile `0590e254…`. All 297 Android JVM tests passed; the installed APK/profile hashes
matched and ART again reported `speed-profile` / `install-dm`.

After the same 25-second warmup, the visually verified v2 fixtures measured:

| Authored native-CGB x2 workload | Audible duration | Native FPS | Presented FPS | Final recent work p95 | New audio underruns |
| --- | ---: | ---: | ---: | ---: | ---: |
| Repeated LCDC writes | 52.44 s | 47.06 | 47.07 | 23.12 ms | 798 |
| Repeated WRAM OAM DMA | 52.48 s | 50.82 | 50.82 | 20.28 ms | 600 |
| Repeated WRAM OAM DMA, repeat | 52.41 s | 51.11 | 51.12 | 19.93 ms | 584 |

LCDC native cadence was 19.1% higher than the preceding `950b83b8…` short interval.
OAM cadence was 4.2% lower in this single warmer run: final CPU/skin/battery readings were
47.4/34/30°C, compared with 45.5/32/28°C previously. That comparison does not isolate a
code regression; matched host controls and a repeated device interval after cooling are
needed. Both observed workloads remain a **FAIL** for smooth playback.

Suppression stayed zero, media volume was 1, CPU hints were disabled, priority was 0 and
power was USB. Thermal status was 0 in both runs. LCDC's final CPU/skin/battery readings
were 46.2/33/29°C; first/last thirds measured 46.86/47.22 native FPS, versus 51.14/50.81
for OAM. Collections were intentionally stopped after 55 seconds; the retained continuous
intervals are **INCONCLUSIVE for sustained acceptance**. Original DND mode 1 and media
volume 0 were restored and verified afterward. The source-pinned core suite passed 2,190
tests with eight existing skips and all 915 hardware cases without failures, errors or
hardware skips. These snapshot correctness passes do not establish sustained playback.

The valid OAM repeat on the same APK confirmed the persistent deficit: 51.11 native /
51.12 presented FPS with 584 new underruns and no suppressed frames. First/last thirds
were 51.11/51.09 native FPS. It retained the same audible, priority-0, hints-off policy
and thermal status 0, but CPU temperature moved from 50.2 to 49.2°C with skin 35°C and
battery 31°C. It was still warmer than `950b83b8…`, so this is a repeat, not a cooled
comparison. A preceding setup attempt remained in a paused menu and collected zero app
samples; its missing live-session/audio/work evidence excludes it entirely from cadence
and comparison results. The valid repeat followed verified gameplay resumption. DND mode 1
and media volume 0 were restored and verified at 14:35 UTC; the phone was left paused.

The next provisional CPU/DMA/output APK (`e19684f0…`) finished building at 14:57:52 UTC
after 297 Android JVM tests passed. Its source manifest is `e729a22d…`, core JAR
`409d7652…` and API35 profile `4826a573…`; installed APK/profile verification at 14:59 UTC
confirmed ART `speed-profile` compilation with reason `install-dm`. The new immutable host
snapshot independently matches all 461 shared core/controller/portable production sources
and all 785 packaged core class entries. Its own source/class manifests are `ea85a749…` /
`c0dd89ea…`. These receipts establish artifact attribution; the complete workload matrix
and full post-integration core battery remain open.

Its first OAM short interval retained 52.41 audible seconds: 55.06 native / 55.02 presented
FPS, 18.55 ms p95 work, 377 new audio underruns and zero suppressed frames. First/last thirds
were 55.32/54.90 native FPS. The policy remained volume 1, priority 0 and hints off on USB,
with thermal status 0; CPU temperature moved 45.3→46.1°C, skin stayed 33°C, and battery
moved 28→29°C. The persistent cadence deficit and underruns remain a diagnostic failure.
This short classification is inconclusive for the sustained soak; it cannot establish an
isolated code improvement over the earlier, warmer measurements.

The corresponding LCDC short interval retained 52.48 audible seconds: 47.98 native /
47.97 presented FPS, 22.91 ms p95 work, 748 new underruns and zero suppressed frames.
First/last thirds were 47.76/48.10 native FPS. It used the same audible policy and USB
power with thermal status 0; CPU temperature moved 49.2→49.7°C, skin stayed 34°C and
battery moved 29→30°C. This also remains a diagnostic failure and an inconclusive sustained
soak. Both authored workloads retain persistent deficits on this artifact.

A separate valid 15-second LCDC CPU-cycle profile retained 75,852 samples. The reported
self shares include the CPU instruction pipeline at 6.33%, owner at 5.52% and `Gpu.tick`
at 5.49%. Its source-matched mapping and fresh profile are retained separately from cadence
measurements. An earlier attempt remained paused and is excluded; those samples are not
merged into this profile or used to explain gameplay costs. Sampling identifies work to
investigate but does not supply a throughput or sustained-cadence result.

A clean original-revision baseline APK (`bc8bb0e3…`) is now staged separately for M2.
Its checkout remained clean at `b162c357…` before and after a clean Maven/Android rebuild.
Installed Maven JAR hashes and the project dependencies actually resolved by Gradle
matched the freshly rebuilt core/controller/ui-portable artifacts. Its 296 Android tests,
signed APK, matching API35 profile and shared signing certificate passed verification.
It has not yet been used for a device comparison. Earlier intermediate APKs are not
substitutes for this source-pinned baseline.

The selected `7b74a315…` artifact has the short rechecks recorded above: OAM is 54.859 native /
54.889 SurfaceFlinger FPS with +386 audio underruns, LCDC is 54.093 / 54.089 with +436
underruns, and STAT is 59.763 / 59.752 with zero new underruns but short-only status. OAM and
LCDC are diagnostic cadence failures; none establishes sustained acceptance. The final async
artifact is installed with arm64 `speed-profile` / `install-dm`, and its root-reviewed app-v2 short
diagnostic is recorded above; strict 600-frame M2 comparisons and sustained 15-minute gameplay/workload
runs remained pending in that historical snapshot. USB power or simulated battery-unplug
reporting could not certify battery operation; the completed battery-v3 evidence is recorded in
the current status above. The soak requires a thermally settled final
continuous ten minutes and records cadence, suppression, actual presentation, active PCM
consumption, faults, work headroom and power conditions.

## Remaining scope

The union source is integrated and frozen, and full core validation is complete at **3,180 passes
with 8 existing skips**: **2,273 unit slots** (**2,265 passed + 8 skips**), plus **640
timing/graphics**, **54 Blargg** and **221 GBC hardware** passes. The union validation receipt
`/tmp/coffee-gb-union-validation-prep-20260909/union-validation-receipt.json` verifies the frozen
source snapshot, core class set and union APK `aad7ce01…` with profile `0bc7fef5…`; the
installation verification and arm64 ART `speed-profile` record are retained at
`/tmp/coffee-gb-candidate-union-20260909/installed-verification.json`. The authoritative DeviceOps
receipt classifies the LCDC, OAM and STAT short checks **INCONCLUSIVE_SHORT_DIAGNOSTIC**; the long
union soak failed and no sustained device acceptance is claimed. The installed 58cc artifact and
its short/soak data are historical.

The final async ordinary-soak candidate passed **303/303** benchmark Android unit tests,
`assembleBenchmark`, debug lint and release Java compilation. Its staged install pair is
`/tmp/coffee-gb-candidate-async-soak-20260909/artifacts-final/app-benchmark-async-final.apk`
(SHA-256 `45e39a47…`) and same-stem API-31+ DM
`/tmp/coffee-gb-candidate-async-soak-20260909/artifacts-final/app-benchmark-async-final.dm`
(SHA-256 `b464ac36…`), with R8 mapping `29a5196c…`. It was installed and verified with arm64
`speed-profile` / `install-dm`; the package receipt is
`/tmp/coffee-gb-device-handoff-20260909/package-final.txt`. The root-reviewed app-v2 short
diagnostic is recorded above; no sustained ordinary, battery, or M2 gate is claimed. The final
source/build/signature receipt is
`/tmp/coffee-gb-candidate-async-soak-20260909/final-validation-receipt.json`; live collection
requires app-v2 asynchronous telemetry, while app-v1 remains an offline historical parser format.

The current soak collector reports thermal status and sensor means, work p95/max and
`headroomRisk`. It does not collect physical CPU-frequency telemetry or test work-cost trend
stabilization; display refresh and emulated master-tick/clock measurements are separate signals,
not CPU-frequency or full work-trend stabilization evidence.

A separate final45e commercial USB v2 ordinary-gameplay capture used plugged power and the
bounded UP/DOWN input helper. The finalized app/device overlap was **904.3716803809 s** and the
parser returned **FAIL** only because audio was not continuously audible while the system remained
muted. For the parser's final **end−602..end+2** app selection (**597 samples**, **601.399992959 s**
of host-clock span), observed app cadence was **59.727303659 FPS** (native ratio
**0.999999058033**), captured playback was **47,999.350074 Hz**, and work-p95 row median/max was
**12.886846/16.728693 ms**; maximum work was **23.210538 ms**, with five pacing-debt samples.
Rendered/submitted ratios were 1, presented ratio **1.00000639144162**, and there were zero
suppressed frames, compositor drops, long gaps, or new underruns across the run. Thermal delta was
**[0.0, −0.1166666667] °C**, with `headroomRisk=true`. Raw app/device data is
`/tmp/coffee-gb-commercial-usb-soak-v2-20260909.jsonl` (SHA-256
`66825216e67c50511b751ccfb2399e720c7908d75102d026e400496eab44e24c`); the input and orchestration
timelines are `/tmp/coffee-gb-commercial-usb-input-v2-20260909.jsonl` and
`/tmp/coffee-gb-commercial-usb-orchestration-v2-20260909.jsonl`. This is plugged diagnostic
evidence: it does not certify audible playback; the battery result is recorded separately below.

A final45e commercial battery v3 ordinary-gameplay capture used actual unplugged power with the
same bounded input helper. The finalized app/device overlap was **1006.5463449904 s** with **1002 FRESH**
app samples, and the parser returned **FAIL** only because audio was not continuously audible while
the system remained muted. For the parser's final **end−602..end+2** app selection (**597 samples**,
**601.649990805 s** of host-clock span), observed native-frame event cadence was **59.727417185 FPS**,
emulated-tick-equivalent cadence was **59.727444522 FPS**, captured playback was **47,999.838933 Hz**,
and work-p95 row median/max was **12.830692/15.239385 ms**; maximum work was **25.300923 ms**.
Native ratio was **0.999999061609**, rolling minimum **0.999615423816**, presented ratio
**0.999999531412**, rendered/submitted ratios were 1, and there were zero suppressed frames,
compositor drops, long gaps, pacing-debt samples, or new underruns in the whole and stable runs.
Thermal delta was
**[1.0, 0.95] °C**, with `headroomRisk=true`. Raw app/device data is
`/tmp/coffee-gb-commercial-battery-soak-v3-20260909.jsonl` (SHA-256
`574afef27e684744026991530b3560cd4ec792c8093393a1b2ea4d3c34e5e88c`); input and orchestration
timelines are `/tmp/coffee-gb-commercial-battery-input-v3-20260909.jsonl` and
`/tmp/coffee-gb-commercial-battery-orchestration-v3-20260909.jsonl`. This is valid unplugged
cadence/PCM evidence for the run, but it does not certify audible playback. The earlier battery v2
setup remains an inconclusive missing-live-work attempt, and the earlier short USB setup remains a
separate inconclusive setup record; neither is cadence evidence. Full audible, canonical M2, and
broader validation remain open.

The final operator receipt is `/tmp/coffee-gb-commercial-validation-receipt-20260909.json`
(SHA-256 `0e69a9c0446fd077f06ecfd26c825ffd2d10941cf2abc1fc958e5725edbe465c`), with child USB
receipt `/tmp/coffee-gb-commercial-usb-soak-receipt-20260909.json` (SHA-256
`8333682a08290d816ac8032d2b060233b6760d79dd46d751bf5aefe660d600cc`) and battery receipt
`/tmp/coffee-gb-commercial-battery-soak-receipt-20260909.json` (SHA-256
`f355b4e77a6ec782bf009d2b321d4b54a57f57cc53c3475a43d19d01cd7578a8`). It verifies restoration of
the original 60,000 ms screen timeout after the temporary 1,800,000 ms setup value, muted system
audio, 96% battery after the run, released inputs, no remaining helper/collector processes, and
collector cleanup that disabled TimeStats. The two ADB transports resolve to the same phone by `ro.serialno`
despite transport-specific device hashes.

A separate post-test cleanup incident occurred after both valid captures and their successful
cleanup: at 12:28:17 local time, a redundant soak-off intent while the phone was backgrounded
triggered `BackgroundServiceStartNotAllowedException` in `MainActivity.onStart` through
`EmulationService.start`; PID 13713 died; Coffee GB was no longer running and the launcher was
foregrounded. The separate erratum is
`/tmp/coffee-gb-device-handoff-20260909/post-cleanup-exit-erratum-20260909.json`.
The service-start path is unchanged from b162; the current diff only adds soak fields and intent
handling. This incident was outside both captures and did not affect their raw data or cadence
windows. The collector already disables itself on exit; do not repeat cleanup `am start` once the
phone is backgrounded or asleep.

The four 39/60/15/12 matrices, long controls and isolated LYC/Barcode and fallback screens are
recorded as historical or candidate-specific measurements. The post-union 36-JVM catalogue screen
is complete against the selected source and matched baseline, with the per-case frame/tick
contracts and established attribution checks retained in the receipt above. Later source repinning
requires rerunning affected rows. The selected serial, Sachen and LCDC overlay values above do
not stand in for frozen-union cost.

The earlier 96-family catalogue sweep was an intermediate-source discovery result. The final-source
36-JVM screen covers six selected opaque cost cases; it does not refresh that broad discovery or
establish reachability and cost for representative gameplay scenes.

The remaining platform gates are a qualifying audible ordinary gameplay soak (the plugged USB v2
run failed only its audibility criterion), strict M2 comparison with currently unverified
recent-slot mapping, and a qualifying audible battery/gameplay result. The physical-unplugged
battery v3 run is recorded above with valid cadence/PCM evidence but failed its audibility
criterion while muted. The clean b162 baseline does not contain the
ordinary-soak diagnostics/controller sampler, so candidate ordinary soaks are absolute evidence;
the baseline remains the strict-M2 parent. A bounded final-source disabled-diagnostics screen
covering three catalogue rows found no repeatable greater-than-3% tax; it remains unselected
evidence rather than a catalogue-wide or device-wide overhead claim.

The documented supported in-game fences and mapper/endpoint states have focused correctness evidence
and selected cost rows where described. Arbitrary external/debugger/linked-Accuracy policies,
unknown asynchronous endpoints, retained mutable PPU aliases and special mapper windows remain
scalar or fail-closed by contract; they are unsupported policy classes rather than evidence for a
new optimization claim. The device gates still need to establish sustained coverage for the
supported workloads on the final artifact.

No catalogue-wide or all-device sustained-cadence claim is made by this report.
