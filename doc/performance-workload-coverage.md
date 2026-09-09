# Performance workload coverage

This implements the workload-based approach in [the coverage plan](performance-coverage-plan.md).
The comparison baseline is `b162c357422b4f277a5ec1f66a8d7f9aec695595`. Performance remains an
explicit execution mode: these changes expand its batching coverage without selecting it by ROM
name or recognizing particular polling loops. Accuracy retains the scalar hardware model.

## Current validation status

The union source is integrated and frozen, and validation remains in progress. It includes strict
cap-63, the terminal FF40 retry hint, the combined GPU path, serial denial fallback, the Sachen
safe-peek mapper path, and the LCDC poll-retry path; all new union test classes are installed in
the tree. Full core validation is **3,180 passes with 8 existing skips**: **2,273 unit slots**
(**2,265 passed + 8 skips**), plus **640 timing/graphics**, **54 Blargg** and **221 GBC hardware**
passes. The union receipt `/tmp/coffee-gb-union-validation-prep-20260909/union-validation-receipt.json`
records controller **978 slots / 2 skips**, CLI **42**, Swing **854 slots / 1 skip**, and **297
Android JVM passes**; it also verifies the frozen source snapshot and union APK `aad7ce01…` with
profile `0bc7fef5…`. The installation verification receipt
`/tmp/coffee-gb-candidate-union-20260909/installed-verification.json` records the union APK
and arm64 ART `speed-profile` installation. DeviceOps completed the three union-artifact short
classifications (LCDC, OAM and STAT); the authoritative receipt marks each
**INCONCLUSIVE_SHORT_DIAGNOSTIC**. These are short checks only, while sustained Android
acceptance remains unestablished. The installed terminal-retry-hint `58cc3bf2…` artifact and
isolated measurements remain historical or separate.

The final async ordinary-soak candidate passed **303/303** benchmark Android unit tests,
`assembleBenchmark`, debug lint, and release Java compilation. Its benchmark APK is
`/tmp/coffee-gb-candidate-async-soak-20260909/artifacts-final/app-benchmark-async-final.apk`
(SHA-256 `45e39a47…`), with the same-stem API-31+ profile
`/tmp/coffee-gb-candidate-async-soak-20260909/artifacts-final/app-benchmark-async-final.dm`
(SHA-256 `b464ac36…`) and R8 mapping `29a5196c…`. The full source, dependency, test, and
signature receipt is `/tmp/coffee-gb-candidate-async-soak-20260909/final-validation-receipt.json`.
The APK was installed at 10:20:07 local time and verified with arm64 `speed-profile` /
`install-dm` in `/tmp/coffee-gb-device-handoff-20260909/package-final.txt`. The root-reviewed
`short-v2-after-55ish.log` contains 83 app-only samples, all fresh, drop-free and sequence
contiguous with ordered clocks and maximum snapshot age **4.053769 ms**. After its 25 s warmup,
the **57.466552311 s** app interval recorded **59.7216965693 FPS**, **48,004.4841619 Hz**
consumed PCM, zero new underruns, and work-p95 row median/max **14.7670765/18.834154 ms**.
The log has no paired SurfaceFlinger raw stream and is diagnostic only, not sustained acceptance;
the root-reviewed classification is recorded in
`/tmp/coffee-gb-device-handoff-20260909/short-v2-diagnostic-receipt.json`. The later `short-v2-final.log`
contains the user-muted tail. Live collection requires `coffee-gb-soak-app-v2`; the
parser retains app-v1 only for offline historical records. The async collector rejects drops,
stale/incoherent snapshots, and recordings spanning different generations, and has no terminal drain
acknowledgement, so the final recorded overlap remains the acceptance boundary. The device is
currently muted at the user's request; future captures may provide cadence and PCM/audio-health
diagnostics, but cannot certify the audible-playback portion of the gate while muted. Canonical M2
remains a separate gate whose contract requires `audio_system_music_muted=false`; `system=0`/muted
silent-pcm or relaxed policies change that contract and cannot substitute for canonical accuracy
and performance comparisons. The current mute therefore defers canonical M2 audio certification.

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

The soak collector reports thermal status and sensor means, work p95/max and `headroomRisk` as
run observations. It does not collect physical CPU-frequency telemetry or test work-cost trend
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

The post-union catalogue screen is complete against the clean original-b162 baseline: 36 fresh
JVMs, 18 paired comparisons, three reversed pairs per case, 50,331,648 master ticks of warmup and
33,554,432 master ticks per measured window. All semantic frame, tick, speed and clock-accounting
contracts matched; epoch and bulk counters were intentionally excluded. The validation receipt is
`/tmp/coffee-gb-union-catalogue-cost-run-20260909/result-validation.json`.
These six opaque cases are discovery/title-demo cost samples, not a full catalogue or representative
gameplay-scene survey.
They do not refresh the earlier 96-family intermediate-source discovery sweep or establish
representative gameplay reachability.

| Opaque case | Profile | Reversed candidate/control changes | Median |
| --- | --- | --- | ---: |
| `catalogue_010` | CGB x2 | +0.280%, −6.199%, +1.048% | +0.280% |
| `catalogue_042` | CGB x1 | +10.059%, +7.132%, +1.418% | +7.132% |
| `catalogue_060` | DMG | −1.251%, +6.229%, +3.114% | +3.114% |
| `catalogue_063` | CGB x2 | +4.168%, −0.697%, −2.920% | −0.697% |
| `catalogue_067` | CGB x2 | −1.490%, −3.994%, −11.574% | −3.994% |
| `catalogue_083` | CGB x1 | +6.488%, +13.668%, +13.320% | +13.320% |

A supplemental warmed opposite-order check retained the residual `catalogue_042` and
`catalogue_083` flags: six JVM rows (three paired comparisons) measured **+5.041928%** and
**+4.493181%** respectively, while neutral `catalogue_063` measured **−4.245977%**. The
earlier 18-JVM/9-pair medians were
**+7.003413%**, **+5.363514%**, and **+3.322716%** for 042, 083 and 063. This is attribution
evidence for the remaining rows, not a new union selection or a catalogue-wide claim.

These final-union rows are cost observations, not sustained Android acceptance.

The union ordinary audible short checks were LCDC **52.466681 s / 59.733148 native FPS / 59.735957
SurfaceFlinger FPS / 48,012.185 Hz / +0 underruns**, OAM **52.450005 s / 59.733073 / 59.711905 /
47,998.165 Hz / +0**, and STAT **53.435195 s / 59.717196 / 59.689822 / 48,004.316 Hz / +0**.
These audio rates use the v1 app `hostTimeNanos` interval; collector-arrival-clock rates remain in
the raw receipt and are not mixed with these values. Their row-p95 median/max values were respectively
**14.970154/18.812616**, **15.878077/18.168615**, and **16.331462/20.525308 ms**. The receipt
classifies all three as **INCONCLUSIVE_SHORT_DIAGNOSTIC**; they are short diagnostics only.

The union LCDC USB soak ran 1167.144318 s and failed: stable cadence **59.711467 FPS**, audio
playback **47,985.474 Hz**, native ratio **0.9997096**, presented ratio **0.9997352**, rolling
minimum **0.9885526**, and stable-window underruns **+14**. The absolute audio counter was **52 → 66**
in the stable window and **33 → 66** over the whole run. A system volume/mute transition to 0/muted
occurred during collection; its cause is unknown. The raw receipt is
`/tmp/coffee-gb-candidate-union-20260909/union-device-evidence-receipt.json`; no sustained
acceptance is claimed.

The earlier 900.145669 s LCDC USB soak is historical 58cc context: measured stable app cadence
**59.582855 FPS**, stable app-counter audio **47,883.6738 Hz** over 601.011821 s, stable
underruns **+105**, and one 779.293–786.362 s burst contributed **+81 of +109 observed**
underruns. It is retained separately from the union result.

The 20 s simpleperf profile for historical 58cc is retained at
`/tmp/coffee-gb-terminal-retry-hint-simpleperf-20260909/` with its historical R8 mapping. Mapped self
hotspots were epoch/replay **8.57%**, CPU pipeline **7.69%**, and `Gpu.tick` **6.24%**; inclusive
`Gameboy.runTicks` was **99.60%**. Profiling overhead makes its telemetry diagnostic only. The
read-only context was collected after the profile, so later logcat cannot establish in-window GC
correlation. Later app/system GC records were outside the profile; later context had no OOM,
AudioTrack route/write failure or logged app audio-restart event, while telemetry route-failure,
write-failure and overrun counters stayed zero.

Static-null controls completed with no stable broad tax. The final selected-source
disabled-diagnostics screen used 18 fresh JVMs, 9 paired comparisons and three reversed pairs for
catalogue cases 042, 083 and neutral 063. All semantic fields, including epoch, epoch-tick and
bulk counters, matched. Erased-diagnostics-over-union changes were **−0.300% / +1.681% / −3.666%**
(042, median **−0.300%**), **−0.653% / +1.660% / −1.433%** (083, median **−0.653%**) and
**+2.717% / +1.829% / +0.102%** (063, median **+1.829%**). No repeatable greater-than-3% tax
appeared in these three rows; the overlay remains unselected and does not close every possible
device-specific overhead question. Receipt:
`/tmp/coffee-gb-union-erased-diagnostics-stage-20260909/result-validation.json`.

The integrated combined GPU path and terminal retry hint are in the current working tree;
publication-only and DMA-only alternatives remain historical unselected experiments. A private final host-cost run on the same historical `58cc` pin
completed with 84 timed LYC/Barcode rows (diagnostic rows excluded) and 24 structurally matched
catalogue pairs. Median candidate/baseline changes were **−32.33% (LYC/CGB0)**,
**−17.97% (LYC/CGB0 compatibility)**, then **+9.52%/+5.33%** for DMG Barcode persistent/finite
and **+7.69%/+2.97%** for CGB x2 persistent/finite. Catalogue medians were **+10.01%, +2.37%,
+1.44%, +4.15%, +4.17%, +4.34%, +12.88% and −0.17%** for cases 010, 015, 038, 042, 060, 067,
083 and 063 respectively. The cost handoff is complete; these measured flags remain unresolved
attribution and are separate from sustained Android acceptance.

A second sidecar-instrumented LCDC USB soak on the same historical 58cc candidate ran
1209.475691 s and **failed** its 600 s stable gate. The final collector stable window
measured nativeRatio **0.99654034**, presentedRatio **0.99649521**, rolling ten-second
emulation minimum **0.97660136**, **+235** stable-window audio underruns, and
`headroomRisk=true`. Rendered and submitted frame ratios were 1.0 relative to the
native frames produced by the app; there were no presentation long gaps or
compositor drops. This remains diagnostic evidence, not sustained acceptance. Raw
receipt:
`/tmp/coffee-gb-final-soak-sidecar-run-20260909-58cc-v1/final-receipt.json`
(SHA-256 `8c6b022398a5e1e387961797ef62a62c7cb8fec65422941d7dc55f80b7f79d46`);
burst-boundary correction:
`/tmp/coffee-gb-final-soak-sidecar-run-20260909-58cc-v1/erratum-burst-boundary-v1.json`
(SHA-256 `b169de93b3a78843eeba46b5874d73cdabd24f6e398c84f462c20de180755c82`).

## Isolated candidate measurements

The serial denial fallback, Sachen safe-peek mapper path and LCDC poll-retry path were measured as
separate overlays against the historical `58cc` control. They are selection evidence, not frozen-union
throughput results.

The selected persistent serial fallback screen used 18 fresh JVMs / 9 pairs and three reversed
trials. Its candidate/control changes were **−31.400%**, **−31.222%** and **−41.021%** for
Barcode DMG (median **−31.400%**), **−6.126%**, **−25.947%** and **−17.546%** for Barcode CGB ×2
(median **−17.546%**), and **−12.730%**, **−14.288%** and **−15.681%** for the CGB ×2 CPU
null-endpoint control (median **−14.288%**). The selected finite screen used 12 fresh JVMs / 6
Barcode pairs and no CPU rows; its DMG changes were **−33.137%**, **−33.537%** and **−30.518%**
(median **−33.137%**) and its CGB ×2 changes were **−19.450%**, **−14.175%** and **−16.281%**
(median **−16.281%**). Both screens used 134,217,728 warmup and 33,554,432 measured master ticks,
and all paired structural contracts matched. The finite screen sends one scan and then measures
quiet completion, so it is not sustained scan cost. Receipts are
`/tmp/coffee-gb-serial-denied-scalar-fallback-cost-prep-20260909/cost-run/result-summary.json` and
`/tmp/coffee-gb-serial-denied-scalar-fallback-finite-cost-prep-20260909/cost-run/result-summary.json`.

The Sachen mapper passed 24 mapper tests plus the authored CGB ×2 full-state partition/restore proof.
The release-16 production overlay contained 786 classes, with exactly three changed Sachen classes.
Its three reversed cost pairs retained 479 frames and 33,554,432 ticks; overlay/control changes were
**−0.509%**, **−10.410%** and **−11.332%**. Epoch counts intentionally changed. The LCDC poll-retry
cost screen used 12 fresh JVMs, three measured windows per JVM and 36 rows aggregated before
three pairs per workload. Exported-LCDC changes were **−3.703%**, **−6.544%** and **+2.217%**
(median **−3.703%**) and CPU control changes were **−2.346%**, **−1.124%** and **+3.104%**
(median **−1.124%**), with matching tick accounting.
Receipts and focused-proof paths are recorded in the results document above.

The clean original-b162 case-010 scalar/batched oracle failed before measurement at
`soundMemento.allModeMementos[0].freqDivider`; the warmup mismatch predates the current candidate
implementation, and no state field was normalized or excluded.

## Execution changes

| Workload | Implementation | Boundary retained |
| --- | --- | --- |
| Pending enabled IF with IME disabled | Running epochs across the supported DMG, SGB and CGB clock rows; logical ROM fetches execute once | EI/DI/RETI, HALT bug, interrupt dispatch and ownership changes |
| Repeated STAT/LY reads | CPU receives a short read capability bounded by the next proven register change; indirect and CB reads use the same capability; demand hints avoid unused proof work | Changing reads, writes and read-modify-write operations synchronize at their bus boundary; hints never grant permission and clear on restore |
| STAT source combinations | Stable readable intervals and pending-latch prefixes can batch; native checkpoint aggregation also covers disabled STAT sources | Mode/LY/LYC conflicts, line boundaries and IF/CPU acceptance events |
| Armed HBlank DMA | Native CGB x1/x2 running and settled-HALT waits no longer count as active bus ownership | Request, arbitration, wake and transfer-start events |
| Owned HBlank DMA/GDMA | Short source-read interiors for WRAM or explicitly leased immutable ROM; normal and double speed | Destination block commit, unsafe sources, interrupt arbitration and overlapping OAM DMA |
| Busy PPU or repeated OAM DMA | Native CGB x2 can run restricted ROM/HRAM CPU work and independent peripheral spans; exact PPU replay or a proved quiet plane owns the raster interval | IME, lifecycle and external memory accesses, source-bus ownership and PPU handoffs; OAM replay currently requires a WRAM source |
| Rejected scanline | Quiet HBlank can recover after both pixel machines and delayed output have drained; unobserved LCDC history advances exactly | Window-Y checkpoints and remaining output/latch work |
| Repeated LCDC writes | Native CGB x2 records eligible A-store writes within one bounded CPU packet and applies them at their original dots; existing PPU proofs batch intervening gaps | LCD disable, active STAT sources or phases, DMA ownership, line boundaries and other external accesses |
| LCD off | Native CGB x2 and CGB compatibility join the inert LCD plane; settled HALT batches across all supported clock rows | LCDC access, blank-frame publication and LCD restart |
| Speed changes | Remaining work in the same caller budget re-enters the appropriate CPU scheduler | Clock-mux tail and the original free-running CPU phase |
| Serial/IR | Explicit next-edge capabilities for supported endpoints and countdown-driven IR devices | Bit transfers, acknowledge windows, timeout callbacks and unknown external activity |
| Held input | Settled legacy input and SGB multiplayer snapshots join input spans | Generation changes, sample/filter deadlines and JOYP interrupts |
| SRAM | Ordinary RAM data access for BasicRom, MBC1/2/3/5 uses a capability borrowed for one epoch | Mapper writes, executable RAM, RTC registers, EEPROM, flash and unadvertised subclasses |
| Dense audio/timer activity | Pulse and wave transitions use quotient/remainder arithmetic; sweep calculation splits one exact event; quiet/HALT timer spans count ordinary TIMA increments | Frame sequencer, overflow/reload/wake, wave access and sample publication |
| CGB0 x1 | Native and compatibility epochs use the registered revision's timing plane | Unknown hardware profiles remain unsupported by these proofs |

The existing Performance rendering/audio contract still applies. The scanline compositor snapshots
line-entry state and audio uses its existing compact sample representation. This work does not add
a new mid-line compositor approximation. Full-machine differential tests use scalar **Performance**
when checking scheduler equivalence; hardware suites retain **Accuracy** for exact hardware claims.
Some older Performance write journals have bounded publication skew, so tests of a newly added
inert plane first establish the same scalar entry state instead of misattributing that old skew.

During eligible OAM copies, the quiet PPU plane can cover empty HBlank, disconnected native-CGB
mode-2 scan slots, or an already-armed exact no-object background cursor. Within that plane,
DMA advances between byte edges while preserving every canonical source read, destination write
and copy-clock phase. The detailed PPU lane still interleaves DMA on every dot. Its proved
native double-speed WRAM interior uses the next copy edge directly, retaining source/OAM
callbacks at that exact CPU clock and preserving ownership and the master-dot generation.
Acquisition, completion, collision and pause events remain outside that operation. Mode-2 admission
proves the consumed sprite-height bit stable across the
LCDC history and advances the complete raw history before returning to detailed rendering.
Detailed CPU replay requires at least eight eligible dots and is attempted only for a missing
PPU/DMA plane. Short native STAT register captures instead use canonical scalar ticks until their
copies settle, avoiding repeated setup for spans that cannot amortize its cost.
For a non-DMA detailed packet, the owner rechecks the existing raster/mode-2 and STAT quiet proofs
once after its first exact dot. A pending LCDC write that has now settled can release the remaining packet
back to batching. The CPU packet contains no external writes, and unresolved output/latches or
event boundaries retain detailed replay. Recovered dots have their own diagnostic counter.
After OAM release, an already-active exact background cursor can also retain quiet PPU
advancement under the strict ROM/HRAM CPU lease. Its admission checks every existing cursor
deadline and keeps ordinary CPU bus permissions unchanged. These dots are recorded separately
as `PPU_QUIET_STEADY`.
When the native CGB timing skeleton materializes an invariant background interval, complete
eight-dot tile groups advance their FIFO occupancy and output-delay state arithmetically.
Map, attribute and tile-data reads keep their original order and FIFO-clock positions.
The native CGB visible machine can also group an already-proved eight-dot background tile,
retaining every pixel publication, live palette lookup, packed FIFO payload and output-delay
stamp. Startup, partial tiles, objects and compatibility mode keep their previous execution.
These operations change neither the scanline compositor nor the rendered pixel policy.
The detailed CPU owner also composes the existing disabled/LYC-only STAT checkpoint proof
with its ROM/HRAM access restrictions. Nonaggregate checkpoints keep exact dot replay;
quiet DMA or PPU commits additionally require the existing aggregate proof. During those
CPU packets, explicitly certified bus layers may provide a live, read-only HRAM instruction
view through `FFFD`. Acquisition occurs once at the first non-ROM instruction read and is
discarded at packet exit or restore; data writes remain on the canonical bus. Eligible
immediate register loads and ALU instructions execute as complete instructions when their
operand addresses and remaining budget are proved safe. Every operand still reads once on
its original machine cycle; partial instructions keep the existing boundary execution.

The LCDC write owner admits already-decoded `LDH (a8),A`, `LD (C),A` and `LD (a16),A`
stores to `FF40`, then executes eligible ROM/HRAM instructions within the strict
63-dot limit; ordinary epochs remain bounded to 54 dots.
It retains each write's value and CPU dot in a transient bounded queue. The original CPU bus
applies each write before that dot's GPU work, including its existing conflict latches.
The owner requires native CGB double speed, IME off, LCD enabled, inactive STAT source/phase
planes, inactive DMA, and the dedicated zero-source STAT proof for an eligible line 1–152
interval: visible lines 1–143 or VBlank lines 144–152. Line 0 and line 153 remain scalar; the
generic native checkpoint proof remains bounded at line 142. Its GPU horizon is `ticksInLine`
13 through 439 (`[13,440)`), and each quiet interval is additionally capped by the next queued
write dot and the strict 63-dot packet limit. Timer, audio, input, link and cartridge deadlines
bound it independently. It can cross a mode-2/3 or mode-3/HBlank edge through canonical
GPU/STAT work; existing quiet proofs admit only gaps between writes.
Complete-store execution is limited to this owner's actual `FF40` target with LCD bit 7 on;
other resolved targets stop before their data write. The queue clears after replay or restore.
This adds no pixel approximation and does not
recognize a particular instruction loop.

Other OAM DMA clock/source combinations, overlapping DMA, unsafe transfer sources, unknown asynchronous endpoints, retained mutable PPU
aliases and explicit debugging/history observation remain conservative. A retained detailed path
must be evaluated by its measured cost; a low epoch percentage alone is not a failure. Linked
controller sessions retain their existing Accuracy policy.

## Diagnostics

`Gameboy.setPerformanceDiagnostics` installs nullable, owner-thread counters without attaching a
debug observer or changing epoch eligibility. Recording allocates no per-dot objects. Snapshots
are detached and immutable; diagnostic state and renderer presentation counters are not portable
machine state.

- Scheduler master ticks are disjoint: scalar, running epoch, settled HALT, transfer, phase-only.
- DMA/PPU/STAT replay, batched DMA copies, quiet PPU ticks during active DMA and materialized APU ticks are separate
  nested-work counters. Do not add them
  to master ticks, and do not interpret all epoch ticks as equivalent saved work.
- Line-entry rejection reasons, address classes, bounded reason combinations, span histograms,
  scalar streaks and the most recent 60 emulated-second windows expose recurring patterns.
- Blocker counts describe observed decisions and shortened horizons, not exclusive percentages
  of host time. One decision may have several reasons. Use uninstrumented timings or a sampling
  profiler to determine its cost.
- `SERIAL_UNBOUNDED_ENDPOINT` distinguishes a missing endpoint clock contract from a supported
  endpoint reaching an actual event. This metadata is consulted only with diagnostics enabled;
  it never grants a batching capability.

The usual `Gameboy` epoch/bulk counters remain available. New diagnostics use their own
`coffee-gb-performance-v1` report and do not change the strict Android M2 log protocol.

## Reproducible public workloads

The `PerformanceWorkloads` fixture contains small repository-authored programs, constructed in
memory. `PerformanceWorkloadMain` runs the production `runTicks` scheduler with `PlayerInputHub`,
frame-sized or seeded split budgets, fixed RTC input and deterministic button events. It performs
no debug observation and disables battery persistence.

```sh
./scripts/run-performance-workload.sh \
  --scenario STAT_POLL --profile CGB_X2 \
  --warmup-ticks 41943040 --ticks 41943040 --diagnostics true

./scripts/run-performance-workload.sh \
  --scenario STAT_POLL --profile CGB_X2 \
  --warmup-ticks 41943040 --ticks 41943040 --diagnostics false
```

Use separate diagnostic and throughput runs. `--scalar true` retains Performance rendering/audio
but disables scheduler batching. `--split-seed 9127` exercises caller partitioning. Clock metadata
contains an exact numerator/denominator, including the rational SGB clock. The numerical nominal
rate is also included for report consumers.

`scripts/report-performance-costs.py report.json --format markdown` groups diagnostic runs by
opaque ID and reports replay, rejected lines, small spans, scalar streaks and HALT/transfer work
separately. Its signatures identify workloads to investigate; they are not CPU-time shares or
frame-rate verdicts. Only known report categories are exported. Parser tests run in Java CI.

Profiles: `DMG`, `MGB`, `CGB`, `CGB_X2`, `CGB0`, `CGB0_X2`, `CGB_COMPAT`,
`CGB0_COMPAT`, `SGB`, `SGB2`. DMA and speed-switch scenarios require native CGB.

The integrated authored cost inventory consists of four separate manifests:
`coverage-39-v1` (39 cases), `polling-60-v1` (60 cases), `profile-cost-15-v1`
(15 cases) and `retained-fence-cost-12-v1` (12 cases). The 39-case discovery
matrix remains sparse at 17 scenarios across seven profiles; the polling follow-up
covers all ten profiles across six polling scenarios, the profile follow-up covers
MGB, CGB0_X2 and SGB2, and the retained-fence follow-up covers four fixtures. The
39/60/15/12 manifests remain separate, and all four have now executed with structurally
matching ticks, frames and diagnostic accounting. Long controls cleared 13 of the 15 initial
flags; subsequent three-variant speed controls did not reproduce a stable tax, while the latest
selected Timer controls were mixed, so the remaining timer/CPU review stays unselected. The
receipt is `/tmp/coffee-gb-retained-fence-stage.uRQeLT/final-host-matrix/four-matrix-run-receipt.json`,
with the stale preparation flag corrected by
`/tmp/coffee-gb-device-coverage-review-20260908/host-matrix-execution-erratum.json`.
The results apply to the selected source/class snapshot; later repinning requires a new run.
The historical LYC ten-case and Barcode screens and the selected fallback overlays are recorded
above; they are separate from the four matrices and do not represent final selected-source cost.
The final selected-source disabled-instrumentation/erased-code screen is complete for the bounded
three-row control above; final sustained device gates remain pending. Parser and correctness checks do
not establish Android acceptance.

`--input` accepts ordered CSV rows `tick,player,buttons`, with ticks counted from the cartridge
entry after bootstrap. Buttons use `A+RIGHT` or `-` for release; player indices are zero-based.
Warmup consumes this same input timeline. A batch stops at the next scheduled input event.

`COFFEE_GB_CORE_CLASSES=/absolute/baseline/core/target/classes` puts a separately built baseline
core first on the runner classpath. The harness uses optional reflection only for newly introduced
diagnostic/reference controls, allowing the same compiled workload to run against the baseline.
Build baseline and candidate before timing, alternate their order and repeat apparent regressions
on a quiet host. Startup, compiler work and diagnostic overhead are excluded from `host_ns`.

For private catalogue discovery, `--manifest /private/manifest.txt` reads local ROM paths in place;
`--id sample-001` labels the output. Reports contain no ROM paths, contents, saves or ROM checksums.
Keep the manifest and any input-to-scene mapping private. The local library is authorized for
testing, not redistribution. Matching frame counts establish cadence, not pixel/gameplay parity.
Generic title/menu input cannot certify every gameplay scene.

For visible Android stress testing, export a repository-authored fixture:

```sh
./scripts/run-performance-workload.sh export OAM_DMA CGB_X2 /tmp/perf-oam.gbc
```

The exporter accepts only a built-in scenario/profile and a new output filename. It adds a visible
pattern, sprite source data, a sustained pulse tone and a valid boot header before entering the
same workload loop. The STAT fixture enables all STAT interrupt sources and uses LYC 153. These
are explicitly visible adaptations; compare like-for-like fixtures across artifacts. Exporting
does not read the private ROM library or overwrite existing files.

## Regression protection

Default core unit tests include the new workloads, so Java CI exercises real batching rather than
only setting Performance mode on scalar integration runners. They cover sustained masked IRQ and
polling across all ten profile rows, addressing forms and short budgets, all STAT enable masks on
the physical timing families, scalar-line HBlank recovery, LCD-off and speed transitions, transfer
tails, mapper leases, endpoint deadlines, held input and materialized audio state.

Tests compare full canonical component state, frame events and bus transaction counts where
applicable. They check partial budgets, restored continuation, positive steady-segment coverage
and diagnostic transparency. Existing observer/alias tests retain their fail-closed behavior.

Relevant commands, run from the repository root:

```sh
/opt/maven/bin/mvn -pl core test
/opt/maven/bin/mvn -pl core test -Ptest-mooneye,test-dmgacid2,test-cgbacid2,test-mealybug
/opt/maven/bin/mvn -pl core test -Ptest-blargg-individual,test-blargg
/opt/maven/bin/mvn -pl core test -Ptest-gbmicrotest,test-gbc-hw
/opt/maven/bin/mvn -pl controller -am -Dtest=PerformanceSoakSamplerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 -m unittest discover -s android -p test_performance_soak.py -v
```

## Sustained Android evidence

[The soak workflow](../android/README-soak.md) adds an independent ordinary-playback collector and
analyzer. It checks actual SurfaceFlinger presentations, frame suppression, consumed AudioTrack
PCM, pacing, thermal state and power conditions. Controller work percentiles and native/rendered
frame counts come from a bounded sampler; app submissions are not substituted for compositor
presentations. It verifies the installed signed APK and disables CPU performance hints during the
soak. Its parser/adversarial fixtures run in Android CI.

The final continuous ten minutes of a 15–30 minute run determine the gate. Missing data,
unstable thermal conditions, wrong artifact, locked/hidden UI or an unsuitable display produce
an inconclusive result. A USB-powered run is labelled plugged; it cannot certify battery operation.
The existing strict 600-frame M2 matrix remains a separate comparison gate.

Measured baseline/candidate results and the scope of completed device/catalogue checks are recorded
in [the implementation report](performance-coverage-results.md). Tool availability or a successful
APK build alone does not establish sustained native playback on a device.
