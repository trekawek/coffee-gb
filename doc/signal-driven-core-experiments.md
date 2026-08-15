# Signal-driven core: experimental results

Status: overnight architecture spike on `codex/signal-fabric-night`

Companion design: [signal-driven-core.md](signal-driven-core.md)

External-oracle manifest: [signal-oracle-repro.md](signal-oracle-repro.md)

This log uses the following evidence labels. They are deliberately not interchangeable:

- **hardware capture**: a measurement from a physical DMG/CGB;
- **external netlist/gate trace**: a named node or transition in a separately obtained schematic,
  generated netlist, or gate simulation; useful evidence about that model, not hardware capture;
- **fitted hypothesis**: a candidate topology or aperture selected because it reproduces known
  outcomes, but not independently observed;
- **production differential**: equivalence to Coffee GB at its current callback boundaries;
- **self-test/contract**: an internal invariant or truth table of the candidate itself;
- **production replacement/deletion**: a candidate which actually replaces and removes runtime
  code or state; and
- **architecture falsifier**: a test that rejects an apparently convenient migration step.

Each result below should be read at the strongest label it explicitly names. In particular,
passing a self-test or production differential does not promote a fitted hypothesis to recovered
silicon topology.

Passing a production differential is not proof of silicon behavior. Conversely, a schematic-shaped
model is not ready for production until it also preserves Coffee GB's hardware-verified ROM results,
save states, debugger boundaries, and performance.

## Summary

| Hypothesis | Result | Consequence |
| --- | --- | --- |
| Serial DIV-reset timing is a forecast problem | Branch-accepted DMG external-netlist-anchored production replacement/deletion; CGB production differential | The old arithmetic reduces exactly to a local divider-stage fall, SCK toggle, and falling-edge shift. CGB normal/fast use the same tested algebra but lack an external topology oracle. The independently authored hierarchy driver is now reproducible from the branch, but its license classification still needs review before merge. |
| Timer overflow needs an explicit 4/8-tick state machine | External gate-model waveform; local production cut rejected | A selected-input fall, sampled TIMA-MSB fall, next-BOGA request, shared load/reset cone, and live TMA reload bus reproduce the apparent timeline without an explicit deadline. A runtime replacement cannot preserve arbitrary released states because Timer does not own or serialize the independent BOGA/T4 phase; that clock must migrate above Timer first. |
| Timer/serial acknowledgement can be centralized inside the current master-tick loop | Falsified and rolled back | Timer runs before CPU while serial runs after it; no placement of one central callback preserves both physical windows. A unified CPU-edge/half-dot island is prerequisite. |
| Four peripheral acknowledge booleans require four independent storage fields | Branch-accepted exact state reduction | The four independently consumed Timer/Serial/LCDC/VBlank acknowledge levels now occupy one bit-plane. Consumer placement and clear ordering are unchanged; released state records still expose the four booleans. This is storage equivalence, not a centralized scheduler or a silicon-topology claim. |
| Interrupt entry must reread FF0F/FFFF and repair priority at the vector callback | External topology established; production mapping rejected and rolled back | A held owner is the right raw topology, but sampling it in the first half of Coffee GB's current `IRQ_PUSH_2` fails eight Gambatte late STAT-vs-Timer precedence cases. The CPU callback/bus timeline must be re-anchored before the raw T1/T2 aperture can replace the repair. |
| Java evaluation order can be made unobservable | Self-test support for two bounded contracts | A single-resolve edge-triggered scheduler and a separate fixed-point transparent/async oracle are traversal-order invariant. The allocation-heavy oracle is not a runtime implementation. |
| The existing callback boundary is too opaque to shadow a signal scheduler | Production-differential compatibility harness | Immutable CPU/timer/serial/IF snapshots replay current races, but still carry projected timer state and a source-profile acknowledge countdown. |
| The CPU, Timer, Serial, IF, IME, and HALT seams cannot compose without callback ordering | External gate-waveform-shaped Timer source composed with a constructive edge-triggered fabric | One half-dot fabric resolves a natural NYDU/MOBA Timer request, persistent bus intent, priority, acknowledge, and control latches symmetrically. The DMG gate trace further narrows selection to a transparent pending-bank aperture followed by held bits; the composition does not yet model that aperture. Timer writes, Serial pin generation, PPU, and CGB remain outside it. |
| CPU opcode lookahead is intrinsic to accurate races | Constructive persistent-bus seam plus production differential | T1-T4 state can expose in-flight address, strobes, data, held byte, and acknowledge without re-decoding. No production lookahead is deleted yet, and the one-M-cycle-late read anchor remains a migration debt. |
| HALT, wake, and interrupt acceptance require request provenance | External gate-model waveform plus production differential | Direct HALT decode removes HALT's own IDU increment while opcode load and PC write remain active; the delayed decode only sets the HALT latch. Separate local IF latches, a transparent `IE & IF` pending bank, and a wake DFF derive readable, accepted, and HALT-wake observations without provenance. Silicon equivalence and unprobed source phases remain open. |
| HDMA must decode opcodes and query future CPU/PPU state | Behavioral request/grant decomposition; two production reductions rejected and rolled back | The detached fabric avoids opcode bytes, but semantic preemption/retire/late-accept inputs and calibrated startup profiles still carry equivalent knowledge. A contextual one-bit encoding lost an independently retained owner in a whole-machine FF0F race. An exact two-bit encoding preserved behavior but retained both logical latches and grew compiled/live conditional complexity, so production keeps the two booleans. |
| One generic held bus explains all collisions | Falsified | Low-dominant held lines are useful primitives, but VRAM, OAM, cartridge/WRAM, and I/O need distinct grant and receiver topologies. |
| DMG STAT behavior needs a large mode/line exception tree | Behavioral whole-plane partition; two bounded external gate cones | The broad model still encodes calibrated raster cases. Independently, a ripple/partial-decode reset derives LY 153/0 and transparent precharged FF41 latches derive the write glitch with neither `line == 153` nor a semantic `0x78`. |
| STAT write-trigger timing needs a mode/line/speed selector in the live-line branch | Branch-accepted exact dead-control-cone reduction | Both sides of that selector drove the same LYC contribution. Removing it deletes a false timing dependency and five conditional sites without moving the interrupt request or changing state. |
| The OAM bug is fundamentally a Boolean corruption formula | External gate trace for the coarse mechanism; fitted exact-data hypothesis | Sticky selection/carry-skew/retained lines have gate-trace support. The external model's symmetric SRAM directly fails the exact blocked-write mapping, so the directional feedback split remains fitted against hardware-verified `SpriteBug`, not independently observed. |
| APU frame clocks require three independent semantic state fields | External-netlist-shaped clock cone plus branch-accepted reachable-state reduction | Sampled divider and ripple latches generate the selected DMG length/sweep/envelope vector. Production now represents its existing behavior with a ripple phase and sampled tap, using one sentinel for the powered-high blocked pulse. This deletes one live field but does not remove the CGB tap and reset/power profile rules. |
| Length expiry needs separate decrement and mutable zero bookkeeping | Branch-accepted exact gated-counter reduction | The gated pre-decrement terminal pulse is the stop signal. Trigger masks that pulse while the reload mux owns the counter, eliminating redundant local control bookkeeping without changing the released counter shape. |
| Each square channel needs two independent oscillator-phase booleans | Branch-accepted exact phase-ring reduction | For both square channels, `(clock2Mhz, lowFrequencyPhase)` is exactly one four-state ring with transition `(phase - 1) & 3`. The low two bits of one integer per channel replace four booleans, while released state records still expose and accept every old tuple. This is behavioral state reduction, not an APU netlist claim. |
| Envelope saturation needs duplicated semantic endpoint decoders | Branch-accepted exact carry/borrow reduction | The four-bit next-volume carry/borrow is the stop signal. Sharing it across ordinary and pending envelope clocks removes the duplicate decoder/helper while retaining the independent stop, pending-clock, and timer latches. A compatibility guard preserves arbitrary public `int` register writes. This is exact production-boundary algebra, not independent gate evidence. |
| Sweep add/subtract needs two result suffixes | Branch-accepted exact two's-complement reduction | The shifted delta is signed once and enters one shared adder suffix. Java's modulo-2^32 arithmetic makes it exact even for negative/extreme public inputs; no sweep latch or timing boundary moves. |
| Pulse-channel quirks require semantic trigger/sweep/length branches | Branch-accepted CH1 trigger deletion plus behavioral remainder | The broad resolver still encodes settled truth tables, but the restart/adder trace independently falsifies `wasActive` as a causal aperture input. Production now uses the common shorter nonzero-shift path and deletes that dependency/conditional while retaining the ignored public argument; shift-zero behavior comes from retained BYTE state in the bounded cone. DMG is externally grounded and CGB remains differential-only. |
| Active CH3 wave RAM needs time-window and address-rewrite rules | External-netlist-shaped fitted port plus production differential | One address-owner mux, precharged data bus, and two fitted fetch-valid stages reproduce the access window and address aliasing. Retrigger feedback and electrical collisions remain separate cones. |
| CH4 needs a zero-divisor case and a second LFSR algorithm | External-netlist-anchored steady cone; production cut rejected | Complement-loaded prescaler and zero-reset XNOR wiring remove the local semantic cases, but both faithful and lean runtime replacements fail 8 of the 13 SameSuite CH4 ROMs because the trigger/live-write projection is not yet derived. |
| The four-dot PPU skew requires two independently running renderers | Direct single-machine aliases rejected; fitted forward replacement remains constructive | An unshifted alias fails 24/26 strict Mealybug images. A shifted alias plus timing taps reaches 26/26 Mealybug and 129/130 Mooneye, but still fails the ten-sprite mode-0 boundary, grows production, retains every repair path, and cannot map the two released machine states safely. A new forward graph remains the viable migration seam. |
| LCDC.1 object disable must abort the fetch and catch the renderer up three dots | Bounded external DMG-B ownership trace; local production cut rejected | FF40.D1 gates future X matches and final object output, not the byte latches or physical shift banks. A pre-byte fall launches nothing; after low-byte capture, high-byte capture/load/shift retire normally while output is masked. Removing the production catch-up still fails the strict companion image exactly three pixels late: the remaining correction is a dual-renderer phase debt, not object-flight ownership. |
| Mid-mode-3 writes require separate palette, SCX, and WX conflict state | Branch-accepted exact state reduction plus production differential at one CPU-reachable cadence | Palette mix, SCX old-value, and WX just-written pulses now share the existing visible/pending register-latch banks, deleting three live scalar fields. A review-found public-API regression was corrected so only palette reads use the mixed view. Receiver delays and the half-dot capture phase remain fitted. |
| A CGB LCDC.4 collision needs active and pending duration counters | Branch-accepted reachable-state reduction | The pending write strobe is sampled directly into the existing consumer-history bank. Once active, that history was already authoritative, so one field and the countdown branch disappear while released integer slots remain importer-compatible. The CGB waveform itself is still not independently grounded. |
| LCD disable must inspect raster/pixel state to cancel output | External-netlist reset root plus fitted output/fanout hypothesis | XONA/XEBE/XODO/XAPO reduce to one reset root, but the Java cone manually assigns that reset to candidate scanout stages and encodes write envelopes explicitly. |
| CGB speed switching requires timer phase repair and tail-duration tables | Fitted timing hypothesis; gated-DIV routing falsified by contrary emulator evidence | STOP-entry/release counters fit verified durations, but the candidate's gated DIV disagrees with production and SameBoy; hardware capture is required before routing claims. |
| Full DMG gate simulation is impractical even as a development oracle | External gate oracle demonstrated | `dmg-sim` ran its source OAM-bug program to completion and produced inspectable traces. This validates use of that external model as an offline oracle, not its silicon accuracy. |

## Serial: a successful production simplification

The previous DIV-reset path computed a future toggle with phase adjustment arithmetic. Exhaustive
enumeration showed that it is exactly equivalent to the local circuit action:

1. clear the eight-bit serial divider;
2. observe whether the selected stage immediately below the output-clock flip-flop fell;
3. toggle that flip-flop on the fall; and
4. shift only when the output transition is falling.

The retained `SerialPort` keeps its scalar eight-bit divider and output-clock latch. Only the reset
path changed: it tests the preceding divider stage, clears the divider, toggles the output latch if
that stage was high, and shifts if the resulting transition falls. The production diff against the
baseline is one file, nine insertions and twenty deletions. `UnsignedRippleCounter` and
`EdgeDetector` remain test-only specification tools rather than unused runtime framework.

This is **production replacement/deletion** supported by a **production differential**: all divider
phases, both internal clock periods, both old output levels, save/restore, debug callbacks, and the
optimized idle path were exercised. `SerialPortReplayTest` additionally captures and restores every
reachable active transfer phase for DMG normal, CGB normal, and CGB fast clocks, then compares both
ordinary and immediate-DIV-reset continuations. A stateful external `SerialEndpoint` remains outside
the `SerialPort` memento and therefore outside that replay claim.

The DMG interpretation is independently anchored in the pinned external netlist. FF04 write decode
clears the divider cell immediately below the serial clock toggle DFF. Clearing that cell while it
is high produces the edge which toggles SCK; the shift-register clock sees only the resulting SCK
high-to-low transition. The full-hierarchy cone produces all three distinguishing cases: high stage
plus high SCK toggles low and shifts, high stage plus low SCK toggles high without shifting, and a
low stage does neither. Exact nodes, harness digests, commands, and limits are recorded in
[signal-oracle-repro.md](signal-oracle-repro.md#dmg-serial-div-reset-cone). This is evidence about
the DMG-B external model, not a physical capture or CGB topology proof.

This is the desired shape: an apparent timing quirk disappeared when represented as a local stage
transition. The production comparison against `8560a6c2` is one file, `+9/-20` lines. Compiled
`SerialPort` size falls from 9,023 to 8,853 bytes; conditional-transfer count remains 77; construction
allocation is unchanged at about 57.87 bytes per instance; and the idle 100-million-tick audit
allocates zero bytes. The abandoned generic-primitives cut was `+224/-45`, 13,514 compiled bytes,
92 conditional transfers, and about 129.94 construction bytes, so it failed the deletion gate.

The final CPU2-pinned alternating ROM benchmark measured baseline ticks/s of
`6,407,205 / 6,365,119 / 6,772,777` and replacement ticks/s of
`6,518,713 / 6,477,688 / 6,442,723`. The medians differ by nominal `+1.10%`, but the baseline spread
is 6.4%; the defensible conclusion is only **no measured regression greater than 3%**, not a speedup.
An observed contended baseline sample and an earlier unpinned drifting series were excluded.

This is an **observed local benchmark**, not a fully automated acceptance artifact. It used Oracle
HotSpot 21.0.1, the retained `HarryPotterIntroFpsTest` harness, 1,200 emulated warm-up frames, and a
600-frame measurement window with JFR and forced frame suppression disabled. Each worktree run used
this command shape; the authorized local ROM path is intentionally omitted:

```sh
taskset -c 2 env PATH=/opt/maven/bin:/usr/bin:/bin \
  ./scripts/measure-harry-potter-intro-fps.sh '<authorized-local-ROM>'
```

Baseline and replacement were alternated in three pairs (`B-L`, `L-B`, `B-L`) while sibling CPU 6
was monitored. One additional baseline sample (`4,585,500`) was excluded after sibling contention
was observed. The earlier unpinned series was baseline
`4,044,293 / 4,604,011 / 4,665,290` and replacement
`3,992,831 / 4,023,527 / 4,219,370`; it was discarded wholesale because host drift dominated.
Because the contention exclusion rule was not predetermined or automated, an upstream performance
gate must rerun the interleaving with machine-readable host-load capture and a fixed exclusion rule.
The construction-allocation figures came from a one-off `/tmp` harness which was not retained;
only the zero-allocation idle path is directly inspectable in the current code. These limitations
do not reverse the local result, but they keep performance acceptance at branch-observation level.

### Combined retained-cut acceptance matrix

The current branch retains eleven production simplifications across eleven core source files: the
lean serial reset, common CH1 restart path, LCDC.4 reachable-state reduction, frame-sequencer
ripple-state reduction, shared PPU register-conflict banks, peripheral-acknowledge bit-plane, and
the two square-channel phase rings, plus the envelope carry/borrow decoder, STAT dead-control cone,
length terminal gate, and sweep shared-adder reductions. Relative to `8560a6c2`, production core
code is `+151/-219`, a net deletion of 68 lines. The branch has ten fewer live scalar storage fields: one in LCDC, one in
the frame sequencer, three in `GpuRegisterValues`, three in `InterruptManager`, and two across
`SoundMode1`/`SoundMode2`. Released component-state record shapes remain unchanged; capture/restore
projects each reduced live representation onto the old fields.

These eleven changes are not one evidence class. Serial and CH1 delete causal live-path logic with
external DMG-model support. The envelope cut deletes causal decoder logic by exact four-bit algebra.
STAT, length, and the second sweep cut remove exact dead/gated/algebraic control cones. LCDC, frame
sequencing, PPU conflict storage, interrupt acknowledgement, and the square phases are exact
behavior-preserving state reductions at Coffee GB's already calibrated boundaries. Those nine
non-externally-grounded cuts do not satisfy the independent-hardware-evidence condition for
promoting a new architecture slice merely because their production diff is smaller.

At the earlier three-cut checkpoint, after the rejected interrupt owner was rolled back, the
following complete reruns finished with zero failures/errors:

| Suite | Result |
| --- | ---: |
| Core unit tests, including detached experiments | 1,534 run, 8 skipped |
| Mooneye + dmg-acid2 + cgb-acid2 | 132/132 |
| Blargg aggregate + individual | 54/54 |
| SameSuite + Mealybug strict images | 103/103 |
| Gambatte hardware + GBMicrotest | 5,156/5,156 |
| GBC hardware + misc-gb + Daid | 247/247 |
| RTC3 + MBC30 + cgb-acid-hell + Strikethrough + CasualPokePlayer + BullyGB | 15/15 |

That is 5,707 integration cases in addition to the unit suite. Those earlier runs established
regression safety for the first three retained production cuts; they did not by themselves cover
the eight later reductions or validate the output of test-only candidate PPU, APU, DMA, or scheduler
models.

| Current all-eleven-cut branch | Result |
| --- | ---: |
| Final core unit suite | 1,565 run, 0 failures/errors, 8 skipped |
| Final controller unit suite | 904 run, 0 failures/errors, 2 skipped |
| Final 5,707-case integration battery | 5,707/5,707 |

The final rows are direct runs from the combined branch, not sums of isolated worktree results.
They establish regression safety for all eleven retained cuts. They still do not turn test-only
candidate outputs or external-model observations into silicon evidence.
The exact reactor/profile sequence is retained in
[`scripts/verify-signal-reduction-battery.sh`](../scripts/verify-signal-reduction-battery.sh).

A pre-envelope seven-cut `DebugDisabledBenchmarkTest` audit used detached baseline/current
worktrees, fixed CPU affinity, alternating revision order, sibling-core monitoring, and a discard
rule declared before measurement. Host contention triggered the stop rule: only one baseline timing
sample and no current timing sample were acceptable, so wall-clock performance is **inconclusive**
and no regression or improvement claim is made. Allocation is decision-grade: all six invocations
of each revision reported exactly 128,600 bytes over 45 million measured ticks, with the same nine
sample values. Thus those seven cuts show no measured allocation regression, while a quiet-host
timing gate remains required.

One unpaired run on the then-current all-eight-cut HEAD again reported 128,600 bytes over 45 million
measured ticks (`2,857.778` bytes per million ticks). That is only an allocation confirmation for
the same workload: it is not comparative timing evidence, and the workload disables the APU, so it
does not exercise the new envelope decoder.

The same allocation-only command was repeated on the exact all-eleven-cut HEAD after the final
battery and again reported 128,600 bytes over 45 million measured ticks (`2,857.778` bytes per
million ticks). This confirms the allocation total for that disabled-LCD/APU workload only. It is
not a paired timing result and does not exercise the retained APU or PPU transition paths.

The timed JVM used Java 21.0.1, one affinity-visible processor, CGB SKIP boot, disabled LCD/APU,
a deterministic `JR -2` loop, 30 million warm-up ticks, and nine five-million-tick samples. Its
command shape was:

```sh
taskset -c 3 /opt/maven/bin/mvn -q -pl core -am \
  -Dtest=DebugDisabledBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcoffeegb.debug.benchmark=true test
```

`mpstat -P 3,7 1 9` monitored the SMT sibling. The predeclared rule rejected failure,
`IQR/median > 5%`, `max/median > 1.50`, or sibling idle below 90% in at least two pre-result
intervals, and required five accepted runs per revision. Twelve benchmark outputs—two pilots plus
ten measured attempts—were split evenly between revisions. After restarting once from a contended
CPU2/6 pair, only one baseline timing run and no current timing run survived; the gate therefore
stopped without relaxing its thresholds.

“Released-state compatibility” is directional here: saves emitted by released Coffee GB builds
must load into the current branch. The format does not promise that a newer save loads into an older
binary. For these reductions, controller validation accepts the documented state combinations
released machines could emit, including legacy scalar-only PPU conflict state, and rejects the
corresponding never-emitted combinations before mutation.

## Interrupt acknowledgement: the scheduler falsifier

A detached half-dot interrupt-entry model found chronological timer/serial reset strobes that
reproduce the current verified request races without peripheral forecasts. Moving that behavior
into production looked locally successful: focused tests, all Mooneye cases, and Blargg
`interrupt_time` passed.

The exact CGB double-speed boundary then failed. The current master tick is ordered roughly as:

```text
Timer(two CPU subclocks) -> CPU starts acknowledge -> Serial(two CPU subclocks) -> resolver
```

Counting the boundary tick gives timer/serial windows of `2/3` clocks on DMG, `7/8` on CGB normal,
and `6/8` on CGB double. Deferring the resolver gives `3/4`, `8/9`, and `8/10`. Neither placement
can produce the required equal `3/3` or `8/8` windows. Source-specific offsets would merely move
the old forecasts into the scheduler.

The production attempt was therefore fully rolled back. The retained executable falsifier is
`InterruptAcknowledgeSchedulerFalsificationTest`. The required cut is one complete CPU-edge island
that drives Timer, CPU interrupt control, Serial, and the IF latches once per CPU subedge.

A later minimal production retry preserved the existing interrupt owner/late-priority behavior and
moved only Timer/Serial acknowledge into a clear-dominant end-of-tick resolver. It passed the local
source tests but failed one of 4,674 Gambatte cases:
`serial/start_wait_trigger_int8_read_if_2_dmg08_outE8_cgb04c_outE0.gbc` in DMG mode returned `E0`
instead of `E8`. Deferring the clear made acknowledge incorrectly dominate a Serial completion
which hardware places after it; resolving at the CPU callback instead loses the corresponding
Timer-side window because Timer has already run. Re-labelling the current Java interrupt cycle as
raw T1/T2/T4 was separately rejected by 34 Gambatte cases. Both retries were reverted. The missing
interface is therefore an immutable intra-master-tick edge snapshot, not another source-specific
deadline or callback placement. Released snapshots can also contain a pending Timer acknowledge;
that state must remain representable until the new edge fabric has a versioned mapping to its
physical acknowledge latch.

### Acknowledge storage: retained bit-plane reduction

The rejected scheduler moves above do not require four separate Java fields for the acknowledge
levels which remain at their existing consumer boundaries. Production now stores the Timer,
Serial, LCDC, and VBlank acknowledge levels as four bits in one integer. `clearInterrupt` sets the
corresponding bit, each Timer/Serial consumer clears only its own bit, and the PPU consumer maps the
two PPU bits onto its existing tick-signal positions before clearing them together. Joypad remains
outside this plane, as it had no peripheral acknowledge path before the change.

This is a **branch-accepted exact state reduction**, not the previously rejected central
acknowledge resolver. It does not move when any source requests, when any consumer samples, or when
IF is cleared. Exhaustive tests cover all sixteen pending-bit combinations, all 120 orders of the
four individual consumers plus the combined PPU consumer, and capture/restore at all six positions
in each order. A separate contract proves that clearing Joypad creates no acknowledge bit. Both the
ordinary component state and importer memento retain their released four-boolean record shape;
capture expands the plane and restore packs it.

The production diff is `+34/-48`, a net deletion of fourteen lines, and four live booleans become
one live integer, reducing scalar field count by three while retaining four independent logical
levels. The isolated candidate passed 1,540 core unit tests, 4,674 Gambatte cases, 132 Mooneye/acid
cases, and 54 Blargg cases. Those runs are focused evidence for this reduction; the final combined
branch battery remains the acceptance result marked above. No netlist or hardware capture was used
to infer that silicon physically stores these four signals in one bank.

## Half-dot scheduler and order independence

`HalfDotSignalScheduler` is a detached executable contract, not production infrastructure. It
routes a fixed-domain edge every two half-dots and a CPU-domain edge every two half-dots normally
or every half-dot in double speed. Each quantum has DRIVE, RESOLVE, and COMMIT phases.

Timer request, serial request, CPU acknowledge, and IF-latch islands were permuted independently in
all three phases. The test covers all `24^3` order combinations, four initial half-dot phases, and
both CPU speeds. A same-edge timer+serial request with timer acknowledge always commits with only
serial pending. A companion test demonstrates that direct sequential mutation gives opposite
answers for otherwise equivalent timer-before-CPU and serial-after-CPU collisions.

This **self-test/contract** supports one bounded claim: simultaneous old-state drives and one
resolved ORed request vector remove Java callback order from this detached example. The scheduler
does not implement transparent-latch chains, asynchronous fanout visible within the same quantum,
or feedback settling. It therefore does not yet establish that one resolved vector is sufficient
for the proposed hardware-shaped islands.

`DeltaSettlingOracle` supplies that missing mechanism as a separate **test-only self-test/contract**.
External inputs are fixed; every combinational, transparent, and asynchronous cell evaluates from
one immutable vector; all outputs publish atomically; and the process repeats to a fixed point.
An explicit edge then captures every DFF from the settled pre-edge vector, commits them together,
and performs a second settle. A repeated vector or configurable delta bound fails with diagnostics
instead of silently choosing Java order.

Tests cover a three-latch transparent chain which one resolve pass demonstrably strands two stages
behind, asynchronous clear and set/clear dominance, stable cross-coupled NOR feedback, atomic
two-DFF capture, forward/reverse/seeded traversal orders, non-convergent inversion feedback, and
save/restore replay. Portable state contains only external inputs and retained cell storage;
combinational outputs are rebuilt by settling after restore.

The oracle intentionally allocates cloned vectors and history maps for diagnostics. NOT and NOR
are sufficient to express arbitrary Boolean cones, but this dynamic evaluator is an offline
validation tool, not the production hot path. A migrated island should compile the proven topology
to straight-line allocation-free code while preserving the same phase semantics.

## Passive production scheduler boundary

`ProductionBoundaryShadow` is a **passive production-differential compatibility harness**. It
captures immutable, side-effect-free views of CPU state/phase, Timer, Serial, IF/IE/IME, and clock
profile. Timer and Serial replay request wires from that old vector; the CPU drives a delayed
one-hot acknowledge wire; one clear-dominant IF capture commits the result. Request-driver order is
immaterial.

The shadow reproduces the present DMG request leads one through six and CGB leads one through
eleven for both Timer and Serial. It also agrees with a real production boundary where Timer and
Serial request together. Unlike the earlier attempted cut, it does not mutate production. It still
projects `clocksUntilReload`, simulates near-future serial edges, and retains a 3/8-clock
acknowledge countdown, so it relocates rather than eliminates those timing forecasts.

More importantly, it turns the migration blocker into a finite interface rather than a vague need
for finer timing. The current debug boundary cannot expose persistent CPU T-state/address/RD/WR/data,
the physical acknowledge half-dot, the retained sampled-pending bank, BOGA/NYDU/MOBA timer state, the
external serial pin, or the ordinal of a CGB double-speed subedge. Those are the fields the first
production clock island must add. Only after those physical states replace the projected fields can
the production cut claim that no peripheral deadline query remains.

## Composed edge-triggered DMG control slice

`DmgControlSignalFabric` is a **constructive edge-triggered composition plus bounded production
differential**, not an integrated emulator. It joins the half-dot router, persistent T1-T4 CPU bus
intent, stateless Timer/Serial request pins, shared IF/IE storage, ordinary priority, an unqualified
CPU acknowledge gate, IME/EI, and HALT/wake in one DRIVE→RESOLVE→CAPTURE→COMMIT boundary.

This composition removes two circular inputs from earlier detached tests. An interrupt-entry bus
cycle no longer supplies a selected source; the fabric derives the one-hot acknowledge from settled
`(IF | rawRequests) & IE`, so a simultaneous Timer+Serial request selects Timer by ordinary
priority. The composed CPU advances only persistent bus state; its older test-local IME/HALT
semantics do not enter the fabric or its memento. Timer and Serial pins arriving on the same
half-dot are therefore sampled symmetrically, whereas the current Timer→CPU→Serial callback order
can expose only the first source at that boundary.

Ten composition tests cover reversed driver/commit order, same-edge clear-dominant request/ack
collisions, priority, EI/HALT, a held write strobe, the current four-CPU-clock readable-IF to
HALT-wake distance for both Timer and Serial, and exact arbitrary-boundary save/restore replay.
They establish that the selected edge-triggered seams can share one causal boundary without opcode
identity or a peripheral deadline query.

The later **external DMG gate-model trace** corrects one important boundary in this constructive
composition. Five local clear-dominant IF latches feed `IE & IF` latches which are transparent while
the SM83's `data_phase` is low in T1/T2. `data_phase` rises for T3 and closes that aperture. In T4,
`write_phase`—the SoC node named `clk_t4`—evaluates both the one-hot acknowledge and vector network
from the held bank. Default delays make acknowledge settle before the vector bits within the same
T4; nodelay makes both settle in that T4 delta, and the PC takes the vector at the following cycle
boundary. A higher-priority request can redirect the entry only before the T3 close; a readable IF
edge after closure cannot. Thus selection is neither frozen at Coffee GB's current `IRQ_PUSH_2`
callback nor live through all of `IRQ_JUMP`, and `applyLateInterruptPriority` is a scheduler repair
rather than a hardware feature. Mapping that raw M6 boundary onto Coffee GB's current callback
remains fitted, but the DMG T1/T2 close and T4 evaluation no longer are.

The detached test keeps the existing Coffee callback marker at `IRQ_PUSH_2/T4`; raw DMG
acknowledge is four model clocks after that marker. The current peripheral lookahead uses a
three-clock DMG countdown, so this correction exposes a one-clock callback-alignment debt rather
than hiding it in another fitted phase constant. CGB's later eight-clock placement remains
explicitly fitted and receives no support from this DMG trace.

A bounded production attempt mapped this result directly onto the current `IRQ_PUSH_2` callback.
It sampled internal pending flags during the first half of that Java machine cycle and retained one
owner for the existing T4 clear and later vector, deleting the external FF0F/FFFF reads,
`applyLateInterruptPriority`, its re-request/clear repair, and two live snapshot integers. Focused
CPU/interrupt/memento tests (83/83), the unit suite, Mooneye and acid profiles (132/132), and
aggregate plus individual Blargg profiles (54/54) all passed.

The larger Gambatte hardware profile then **falsified and rolled back** that placement. Eight
`irq_precedence/late_m0irq_vs_tima_scx{2,3}` cases, with and without HALT and in both DMG/CGB modes,
reported Timer (`2`) where hardware expects STAT (`4`). The raw external bank still closes after
T1/T2; widening it in the present Java state would only install another fitted phase exception.
The failure shows that Coffee GB's `IRQ_PUSH_2`/PPU callback anchor is not the physical M6 aperture.
Persistent CPU T-state and bus timing must be re-anchored before the held bank can become production
state. The worktree was restored by reverting the cut.

The same external cone makes Timer and Serial request/acknowledge collisions local. Each IF cell is
reset-dominant, so a request asserted while its one-hot acknowledge reset is active is swallowed;
the same request after reset-cone release sets IF normally. FF0F write-zero uses the same local
dominance. A separate wake DFF samples the pending path later, explaining why IF can be readable
before HALT releases. Exact nanosecond aperture widths belong to the delayed external model and are
not emulator constants or silicon measurements.

`DmgTimerControlCompositionTest` narrows one of those upstream cuts. A separate **external
gate-waveform-shaped** island retains DIV, TIMA, the NYDU sampled-MSB cell, the MOBA reload level,
and BOGA phase. Its production-aligned natural request edge arrives at half-dot seven and enters the
same resolved source vector as CPU acknowledge. A same-edge request/ack collision is consequently
one clear-dominant IF equation, while acknowledge cannot suppress the independent Timer reload
level. The raw request is a pulse and reload ownership lasts one BOGA period. A distinct phase test
now places the TIMA ripple on the BOGA boundary: both latches capture the committed old vector, so
the new TIMA MSB fall cannot reach MOBA until half-dot nine, one full four-T-state period later.
Five composition tests also replay Timer and control state from all sixteen half-dot snapshot
phases. No deadline or scripted Timer source is present in that bounded path.

Serial remains a raw upstream pin, and the Timer projection does not yet accept CPU timer-register
writes or reproduce the observable DIV ripple transient. The calibrated one-M-cycle-late CPU bus,
external serial input, integration of the DMG raw-phase aperture with that bus, PPU source paths, CGB
subedges/direct interrupts, and transparent or asynchronous delta settling remain blockers.
Consequently these tests and the held-owner cut can name the remaining future deletion set—both
peripheral acknowledge forecasts and their `InterruptManager` flags—but cannot yet delete that
source-timing layer safely.

## CGB speed-switch clock topology

`CgbSpeedSwitchClockMachine` is a **fitted timing hypothesis plus architecture falsifier** for a
separate CGB clock profile on the half-dot lattice. The candidate has three retained pieces of
control state: an explicit eight-T-state STOP-entry
sequencer clocked from the destination source, a gated 17-bit switch-delay ripple chain, and a
free-running three-bit fixed-domain release ring. PPU, the APU oscillator, and HBlank DMA remain on
the fixed branch. The model places CPU execution, DIV/timer, serial, and OAM DMA on the selected
branch and gates them during the long delay; that routing is a candidate input assumption, not a
hardware-derived result.

Within that assumption, the topology makes the normal-to-double timer adjustment local. Starting
before STOP's last bus
cycle, eight destination-speed T-state edges occupy four fixed dots, cross the selected timer tap,
and then clear DIV. The detached result matches the current TIMA increment without
`Timer.onSpeedSwitch(+4)`. Switching the DIV-to-APU input from bit 12 to bit 13 is another ordinary
wire transition, so the candidate does not need a sound phase callback.

The delay duration and current tests contain an important ambiguity. Production advances DIV twice
per dot during the normal-to-double pause: FF04 is one after 128 dots. At the end, however, exactly
`0x20000` clocks have elapsed, so the 16-bit counter aliases back to zero. A post-STOP DIV capture
therefore cannot distinguish the current free-running implementation from a physically gated DIV.
An immediate ungated mux would also produce eight bit-13 APU falling edges during the pause, while
a global STOP is independently falsified because LY/STAT continue on the fixed branch.

The ambiguity can be made observable with TIMA. A generated ROM resets DIV/TIMA, selects TAC bit 9,
and enters normal-to-double STOP. Coffee GB advances `0x20000` divider clocks and leaves TIMA
`0x80`; the gated candidate leaves it zero. A locally compiled SameBoy revision `213a12ce` also
produces `0x80`: its speed switch leaves STOP immediately, holds the CPU in a separate halt state,
and continues the timer. SameBoy's own implementation labels this timing insufficiently verified,
so agreement between emulators is not hardware proof, but it is concrete contrary evidence against
promoting the gated-DIV candidate.

Ten candidate/falsifier tests, a 51-test focused production differential, and all nine Daid captures
pass. They validate fitted durations and internal consistency, not the stopped-domain routing.
This remains a topology experiment rather than recovered CGB silicon. Public descriptions of
an 8200-T interval conflict with the empirically calibrated 17-bit delay; the two- and eight-dot
tails fit a three-bit phase ring but do not identify its wiring; reverse entry is unverified; and
the HBlank/OAM-conditioned tail adjustments require the retained DMA grant cells. A production cut
would have to save router, entry, delay, and release phases and obtain a real-hardware TIMA capture
before choosing the DIV/serial/OAM branch behavior. The present free-running production path stays.

## Persistent CPU bus cycles

`CpuBusCycleMachine` is a **constructive persistent-bus seam plus production differential** on the
half-dot lattice. The decoder supplies a T1-T4 cycle description once; address, RD, WR, driven data,
sample/commit, held-bus data, and acknowledge intent then remain observable for that cycle's
lifetime. Scripted NOP, LD, LDH, PUSH, POP, interrupt, HALT, DI, and EI/HALT paths match the
production CPU's terminal accesses.

That representation turns several current queries into observations rather than forecasts: HDMA
sees the active write or opcode-fetch latch, IF masking sees an already-started FF0F read, and a
future DI is simply the byte driven during the opcode cycle. It also exposes four honest migration
debts:

- `IRQ_PUSH_2` currently performs IF and IE reads plus a stack write in one callback, so IF/IE must
  be internal wires rather than external bus transactions;
- HALT performs the HALT opcode read and next-opcode sample in one callback;
- early IE-write acceptance looks through two operands before the target address reaches the bus,
  so acceptance must move to the actual WR strobe; and
- moving to physical prefetch timing shifts `LD (HL)` and LDH reads four T-cycles earlier than the
  calibrated production model.

The last item is intentionally asserted as a falsifier. A persistent bus cycle is the right seam,
but cutting it over without re-deriving DIV/PPU boot anchors would trade structural clarity for a
large timing regression.

## DMG interrupt, IME, and HALT control cone

`DmgCpuControlLatchIsland` is an **external gate-model waveform plus production differential** for
a second CPU cut. Five clear-dominant IF latches feed data-phase samples of `IE & INT`; their
reduction is sampled once more by the YOII wake DFF before reaching the reset side of the HALT
latch. EI/DI/RETI operate an IME control latch and a separate execution-phase observation cell.
Direct HALT decode and its delayed copy now have deliberately different ownership.

The generated decoder2 cone omits HALT from the `ctl_idu_inc` product sum where NOP/STOP
participates. A default-delay `dmg-sim` run then made the dynamic consequence observable. During
HALT, `ctl_fetch` and `ctl_reg_pc_we` are asserted while `ctl_idu_inc` stays low. The PC therefore
writes the non-incremented IDU value and remains on the address from which the next opcode is
sampled. `ctl_op_halt_delayed` rises only after direct HALT decode falls and has no fanout into the
IDU/PC cone; it feeds the HALT SR-latch set input. Thus the gate model does **not** suppress a
PC-write pulse on the following instruction. It omits one effective increment on HALT's own IDU
interval, and the unchanged address makes the sampled opcode execute twice when YOII prevents the
HALT latch from setting.

Three bounded runs fix this contract. In the halt-bug case, direct HALT decode rises at
`32,009.149 us`; its fetch/PC-write interval has no IDU increment, the delayed copy rises at
`32,010.004 us`, and the following instruction has normal IDU increment and PC write before PC
settles from `0009` to `000A` at `32,010.984 us`; `INC B` consequently retires twice. In ordinary
HALT, the next opcode is loaded before the HALT latch rises at `32,023.670 us`; the delayed copy
falls at `32,024.642 us` while HALT remains retained, and YOII clears HALT at `32,028.549 us`
before one normal `INC B`. For pending `EI; HALT`, observed IME becomes active before direct HALT
decode, interrupt entry overlaps the delayed set at `32,013.910 us`, HALT never sets, and the ISR
runs once. These are external-model times: `$time` was picoseconds and the displayed microseconds
are raw values divided by `1,000,000`. The exact patch, commands, nodes, and terminal observations
are in [signal-oracle-repro.md](signal-oracle-repro.md#sm83-halt-waveform-probe).

The earlier Java `haltDecodeHeld` interpretation is therefore falsified by this external model.
The test-only island now names that DFF `haltSetDelayed`, lets only direct HALT decode remove the
HALT interval's IDU increment, and lets the delayed copy feed only the HALT-latch set equation. It
does not store a semantic `haltBugMode`, rewind PC, or attach blocked/wake provenance to a request.
Eleven tests cover all five request sources; the waveform-derived halt-bug, ordinary-wake, and
`EI; HALT` contracts; production instruction outcomes; all 625 four-instruction IME sequences; and
forward/reverse primitive evaluation and commit orders.

This remains a bounded external-model explanation, not a silicon capture or complete interrupt
replacement. The island cannot react to an IE write before address FFFF and WR actually exist, so
the current early-IE predictor can only be removed together with persistent, re-anchored CPU bus
cycles. Its remaining named falsifiers are unprobed FF0F source-set/write apertures, PPU
request-input phases, its coarse edge-triggered approximation of the transparent pending bank,
early IE bus timing, integrated vector/acknowledge decode, and CGB's different direct-interrupt
path. The external default-delay model now bounds Timer and Serial FF0F/ack collisions, but not
other sources, all write phases, physical silicon, or CGB.
The source anchors are `sm83/sm83.sv:4649-4795` (IE and IRQ sample bank), `:8698-8774` (YOII and
HALT paths), `:8812-8886` (IME controls), and `sm83/cells/decoder2.sv:205-213` (IDU-increment
terms). The Java test contains no external source, ROM, or waveform artifact.

## Timer overflow topology

`TimerSignalTopologyTest` exercises the `DmgTimerIsland` model as an **external gate-model waveform
plus production differential** which replaces the flattened overflow timeline with two 1 MHz-edge
latches:

```text
NYDU <- sampled TIMA bit 7
MERY  = NYDU.q && !TIMA[7]
MOBA <- MERY
```

`MOBA.q` owns the reload/request level. The same parallel-load wire used by a TIMA write and by the
TMA reload asynchronously resets the sampled-MSB latch. Consequently:

- the delayed reload follows a sampled MSB fall;
- the reload-owned write window is the duration of the reload level;
- a TIMA write before reload cancels it through the same load wire; and
- reload cannot retrigger itself.

The independent default-delay gate trace observes this exact causal ordering. On a natural
overflow, the selected TIMA input falls, the ripple takes TIMA.7 low and raises MERY, and the next
BOGA edge raises MOBA. MEXU then owns TIMA for one BOGA interval, IF.2 rises, and TIMA follows TMA.
A pre-reload FF05 write raises the same MEXU/load cone, clears NYDU/MERY, and prevents MOBA. An FF06
write while MEXU is high changes TMA from `A5` to `3C`, and TIMA follows one simulator nanosecond
later. Resetting DIV while its selected stage is high creates the same falling TIMA input and the
same downstream overflow cone. A later CPU Timer acknowledge clears IF.2 asynchronously while the
already-completed Timer reload remains independent. Exact nodes, commands, timestamps, and limits
are recorded in [signal-oracle-repro.md](signal-oracle-repro.md#dmg-timer-waveform-probe).

An independent topology test sweeps all TIMA/TMA byte values at selected write/reload phases. A
separate production differential samples all four TAC selectors, four low DIV phases around one
selected falling edge, write slots zero through seven, reload ownership, debug delay, and IF state.
This is broad **production-differential** coverage, not an exhaustive product of every legal DIV
and write phase. Production migration is intentionally deferred until T4/BOGA, CPU write strobes,
and the shared IF latch are first-class signals.

The composed half-dot experiment above supplies that shared IF boundary for one natural overflow:
MOBA's rising request and a simultaneous CPU acknowledge are resolved together, clear dominance is
owned only by IF, and Timer reload remains asserted independently. The external static reset cone
supports collision clear-dominance, while the dynamic run verifies a later acknowledge-to-IF clear;
it does not dynamically exercise exact request/ack overlap. The model's 16 ns FF0F-clear transient
is explicitly downgraded as an unsampled default-delay artifact, not a CPU-visible or silicon quirk.

This evidence corrects only the detached island's atomic same-edge rule. Its half-dot phase zero
remains fitted to the current production boundary, whose CPU reads are one M-cycle late and whose
timing constants are integration-calibrated. Production migration remains deferred until physical
T4/BOGA and CPU write/reset phases are first-class, then passes the ROM battery; CGB and a silicon
capture remain separate falsifiers.

A **local production-cut attempt was rejected and reverted**. Replacing
`overflow/ticksSinceOverflow/haltWakeDelay` with NYDU, MOBA, and a private BOGA phase grew Timer by
47 net lines after compatibility and TAC-write adapters, already failing the deletion gate. More
fundamentally, the released `TimerState` does not contain BOGA phase. Deriving it from DIV made the
arbitrary-phase timer differential request one clock early, while forcing phase zero fixed that
test but moved a boundary-shadow request from clock five to clock four. The same old stable state
therefore admits two future events unless the missing clock phase is supplied from outside; a
compatibility mode or event re-anchoring would merely restore semantic branches. Mooneye's Timer
ROM class still passed 13/13 in both variants, illustrating why broad ROM success alone cannot
validate a state-topology migration. The isolated candidate was fully reverted. The prerequisite
is a shared, serialized T4/BOGA clock island above Timer—not another Timer-local counter.

## APU clock topology

`ApuClockSignalIsland` is an **external-netlist-shaped clock cone plus production differential**.
AJER toggles, COKE clocks BARA's sample of the divider tap, and a BARA edge ripples through CARU and
BYLU. The exposed normal-path signals reduce to `HORU=!BARA`, `BUFY=CARU`, and
`BYFE=CATE=BYLU`. One final channel-local divide phase distinguishes the two otherwise identical
low/low phases. Together these candidate latches generate the complete eight-step
length/sweep/envelope pulse vector without an eight-way step table.

Natural edges and CPU-phase-aligned FF04 resets match `FrameSequencer` and representative
`LengthCounter` behavior for DMG, CGB normal, and CGB double speed. The experiment deliberately
labels what the DMG cone does not establish: CGB's bit-13 tap mux, its boot-only PSG divider offset,
the speed-switch commit phase, the production final-four-clock NR52 adjustment, and suppression of
the first falling edge after powering on with the tap high. The last two are executable falsifiers:
the selected gate cone emits step 0 where production suppresses it or injects step 1. They require
a wider reset/power cone or must remain board-profile adapters; hiding them in the ripple island
would be another special-case layer.

The production `FrameSequencer` now has a separate **branch-accepted reachable-state reduction**.
Its previous `step`, `previousBit`, and `skipNextEdge` fields reduce to a ripple phase and the
sampled selected-DIV level. A `-1` ripple-phase sentinel represents the powered-high input pulse
which must be blocked; after that fall, the ordinary phase starts at zero. The apparently missing
combination—step one while a high pulse is blocked—is unreachable because the reset interval which
selects step one lies immediately below the selected divider bit, while the blocked pulse requires
that bit to be high.

An exhaustive differential checks every 16-bit DIV reset value in both speed modes, subsequent
enabled and disabled falling edges, first-half length observations, debug views, and capture/restore
continuations against a frozen copy of the previous three-field machine. Released snapshots still
store `step`, `previousBit`, and `skipNextEdge`; capture expands the live representation and restore
packs it. Controller state validation additionally rejects a blocked nonzero released step, which
the runtime can never emit. Production changes by `+19/-22`, deleting three net lines and one live
scalar field. This proves equivalence to Coffee GB's calibrated sequencer behavior; it does not
show that the sentinel or the exact Java reset projection is a physical DMG/CGB latch encoding.

### Length terminal-count gate

`LengthCounter` has a separate **branch-accepted exact gated-counter reduction**. Its normal clock
and NRx4 extra-clock path previously decremented, then separately decoded zero, carried that result
in a mutable local, and cleared the local again when trigger reloaded the counter. Production now
exposes the gated pre-decrement terminal pulse directly. Trigger masks that pulse at the return
while the zero-load mux owns the reload, so the redundant clear disappears without moving enable,
reload, or channel-stop ordering.

The differential executes 1,728,172 transitions across ordinary clocks, every signed-low-word
load, representative signed boundary/extreme length and full-length values, load muxes,
first-half/enable/trigger combinations, and out-of-byte NRx4 integers. Java decrement and
`fullLength - 1` overflow behavior remain identical. Public method
descriptors and the released State/Memento shape are unchanged. Production changes by `+3/-16`,
`LengthCounter.class` shrinks from 3,484 to 3,458 bytes, and the control-transfer count stays 28.
This is an exact local terminal-gate simplification, not independent evidence for the physical
length-counter implementation.

### Square-channel oscillator phase rings

`SoundMode1` and `SoundMode2` previously stored `clock2Mhz` and `lowFrequencyPhase` as two
booleans each. Their complete transition table is one four-state ring. With bit zero representing
`clock2Mhz` and bit one representing `lowFrequencyPhase`, every oscillator/channel tick is simply
`(phase - 1) & 3`; construction starts at zero and channel start/stop reset the phase to two.
Trigger timing, frequency-divider gating, disabled ticks, and both DMG/CGB paths observe the same
projected bits as before.

The retained **branch-accepted exact phase-ring reduction** stores each phase in the low two bits of
one integer. A finite algebraic contract enumerates all four released tuples, round-trips each one,
checks its next transition, and verifies the trigger's low-frequency-phase observation. Static
bisimulation covers construction/start/stop and repeated transitions; the existing channel tests
and final sound ROM profiles exercise those production paths. Public method descriptors and the
private released `State`/`Memento` component order are unchanged. Production changes by `+22/-33`,
deleting eleven net lines and two live fields. The two hot tick methods shrink by 50 bytecode bytes
in total and the combined conditional-site count falls by one; cold state projection makes the
associated class files 327 bytes larger. That is an explicit released-state adapter cost, not hidden
evidence of a faster or more physical APU. The final unit, SameSuite, and Blargg sound results are
included in the combined matrix above.

## DMG pulse-channel control topology

`Pulse1GateTopology` is a **behavioral decomposition plus production differential** for the stable
results of length, envelope, sweep, trigger, and channel-enable behavior. Its inputs are register
strobes, frame clocks, and old latch state; its outputs are load, calculation, writeback,
overflow-check, and stop pulses. It deliberately does not pretend that a whole sweep calculation is
instantaneous.

The resolver methods directly encode the following settled truth-table behaviors. The signal names
suggest possible cell responsibilities, but this experiment alone does not show that they emerge
from ordinary connectivity:

- the max-minus-one length reload is the same enable-gate level remaining transparent across a
  trigger parallel load;
- a trigger masks the transient length-zero stop, while a later frame pulse still stops the
  channel;
- period zero is the timer load mux selecting eight while the calculation gate remains closed;
- shift zero leaves overflow checking connected but closes frequency writeback;
- the second overflow calculation is feedback from the frequency-load strobe into the adder check
  input;
- NR10 writes replace field latches without reaching the running timer's load input;
- clearing negate resets the channel only when a retained `negate-used` latch proves that a
  subtract calculation occurred; and
- DAC-off, sweep overflow, and length expiry are reset inputs on one reset-dominant channel-status
  latch, so they naturally beat a simultaneous trigger set.

The **production differential** exhausts 2,080 length/enable/trigger/frame-phase combinations, all 256 NR12
trigger loads through forty envelope clocks, 1,152 initial sweep profiles, and live NR10 rewrites
across representative frequencies. It also checks settled `SoundMode1` status. This is not yet a
production replacement: observation during the 1 MHz serial calculation, active retrigger at its
reload edge, active NR12 write behavior, frequency/duty phase, power/reset wiring, CGB restart
hold, and analog DAC transients are named falsifiers. The narrower serial-adder and envelope-ripple
experiments carry separate fitted circuit hypotheses; their evidence must not be retroactively
attributed to this settled resolver.

## Active CH1 adder and envelope-write cones

Two follow-up models expand the places where the settled control experiment deliberately stopped.
`DmgCh1SerialAdder` is a **bounded external gate-model waveform plus production differential**
representing the FYFO request latch through FEKU `RESTART`, FARE, and FYTE
`RESTART_DLY`; KALA's shift load; FEMU's retained calculation request; the serial sum; BYTE's
settled-sum capture; accumulator writeback; and the feedback overflow check.

A pinned default-delay and nodelay trace falsifies the earlier active/inactive aperture hypothesis.
At the same ordinary CPU write phase, inactive and active NR14.7 writes take the same path and time
through DUPE, `CH1_START`, FYFO/FEKU, FARE, and FYTE. Channel-active state is downstream of restart
and has no connection into that request path. For shifts one, three, and seven the complete serial
waveform is also identical; from synchronized `CH1_START` it matches production's shorter bucket,
`8 + 4*shift` T. The branch now uses that common path for every nonzero-shift trigger and removes
the channel-active dependency and conditional from `FrequencySweep`; the public `wasActive`
argument remains as an ignored source-compatibility parameter. This is a narrow production
deletion, not a claim that the bounded cone models the entire sweep unit.

Shift zero exposes the real retained-state behavior. The initial trigger loads KALA's terminal
count, BYTE samples it, and LD_SUM rises before FYTE. An active retrigger has the same request and
restart waveform but produces no second LD_SUM edge: KALA loads terminal seven again while BYTE is
still high. BYTE remains asserted until a nonterminal KALA load or BEXA reset. The detached model
now owns that state instead of inventing another aperture.

The model continues to match representative add/subtract/shift/frequency results, writeback, and
both overflow checks. A natural BEXA trace confirms that the first sum is written to the accumulator
and the same retained request path immediately calculates the second overflow check; it is not a
scheduled second operation. One NR10 shift-seven-to-one collision also confirms that a live register
write does not reload the in-flight serial counter.

The narrow trigger-scheduling cut passes the focused sweep/trigger/memento tests, all 77 SameSuite
cases, and all 24 individual DMG/CGB Blargg sound cases. It adds no state and leaves mementos
unchanged. The external model is DMG-B rather than silicon, and dynamic coverage is four shifts,
one frequency, one active-retrigger spacing, one natural BEXA phase, and one NR10 collision.
Raw/asynchronous write phases, other BEXA alignments, close negate and carry collisions,
frequency-write/retrigger overlap, intermediate analog timing, and CGB topology remain explicit
falsifiers; CGB is supported by differential tests only.

A second, evidence-separate `FrequencySweep` reduction simplifies only the settled integer adder.
The shifted delta is negated for subtract mode and then enters one shared `+= shadowFreq` suffix.
Java's two's-complement `int` arithmetic is modulo 2^32, so this is identical to the previous
separate add/subtract arms even for `Integer.MIN_VALUE`, wraparound, negative public values, and
masked shift distances. The `negging` and overflow latches remain in the same observable order.

The algebraic differential covers 262,144 controller-accepted restored-state tuples and 1,835,078
wide/extreme public calculations. Public descriptors and State/Memento components are unchanged.
Production changes by `+2/-3`, `FrequencySweep.class` shrinks from 4,714 to 4,706 bytes, and one
control-transfer instruction disappears. This is a **branch-accepted exact shared-adder reduction**,
not additional evidence for the physical CH1 serial adder.

`DmgEnvelopeWriteRipple` is an **external-netlist-shaped fitted hypothesis plus production
differential** for the four DMG envelope counter cells as master/slave toggles whose
higher-stage clocks are selected from the preceding true or complement output according to the
direction wire. That wiring produces the otherwise odd active-write transforms—up to down becomes
`(-v) & 15`, down to up becomes `(14-v) & 15`—without affine value branches. All 8,192 unlocked
production combinations agree. The same-value low-nibble case uses the JUFY write aperture as a
clock pulse rather than a value exception.

A narrower production simplification applies to the ordinary four-bit volume counter without
claiming that the whole envelope scheduler has migrated. The previous code decoded volume zero
while counting down and volume fifteen while counting up both before a regular clock and again in a
shared helper used by the pending-clock path. The next four-bit sum's carry/borrow is exactly that
endpoint signal. Production now computes it directly, removes the duplicate helper/decoder, and
leaves the `finished`, `pendingEnvelopeClock`, and timer latches independent.

The exhaustive differential covers all 221,184 controller-accepted state tuples under ordinary,
even-phase APU, and odd-phase APU clocks—663,552 transitions—including direction zero, timer zero,
finished-plus-pending combinations, and both endpoints. Because public `setNr2(int)` historically
accepts unmasked integers, a range guard and a separate sweep across `-65,536..65,535` plus signed
integer extremes preserve those out-of-hardware-domain continuations too. Public descriptors and
both released State/Memento records are unchanged. `VolumeEnvelope` changes by `+9/-11`, shrinks
from 4,066 to 3,984 class bytes, and removes three conditional-transfer sites. This is a
**branch-accepted exact carry/borrow reduction**, not external evidence for the physical envelope
counter.

One local replacement hypothesis is explicitly falsified rather than copied: production's
`pendingEnvelopeClock` cannot be inferred from this write cone. The wider topology retains timer
bits and frame phase while period is zero, so an exact timer/phase cone must replace that scheduler
flag before the complete `VolumeEnvelope` scheduler can be migrated to a gate-derived topology.

## DMG wave-RAM port topology

`DmgWaveRamPortTopology` is an **external-netlist-shaped fitted port plus production differential**.
It treats channel 3's RAM as one physical port: `CH3_ACTIVE` selects the CPU or wave address owner,
a two-stage fitted fetch-valid path supplies chip-select/output-enable, and a closed active-channel
port exposes the precharged `0xff` data bus. While the gate is open, every CPU address aliases the
retained wave address. No access-window timestamp and no requested address rewrite are present.

The topology matches 34,352 active production reads over multiple oscillator periods and every CPU
and wave address. Another 768 write scenarios compare all sixteen bytes after releasing the
channel, for 12,288 storage observations. The named netlist boundary is the AFUM/AGYL/AXOL/BOLE
address ownership path and the BUSA/BANO/AZUS/AZET fetch path.

This is a digital port cone, not the whole dynamic RAM. Sub-T propagation, retrigger row/column
feedback corruption, simultaneous fetch/write electrical behavior, and CGB remain explicit
falsifiers.

## DMG noise-channel clock cone

`DmgNoiseGateTopology` has two different evidence outcomes which must not be conflated.

The steady-state clock and LFSR path is an **external-netlist-anchored cone plus exhaustive algebraic
and production differentials**. JARE/JERO/JAKY complement-load NR43's ratio into the
JYCO/JYRE/JYFU cells; their `111` terminal level is sampled by GARY, and CARY gates the 1 MHz clock
into the CEXO..ESEP ripple. Ratio zero is therefore not a zero-divisor branch: complement-loading
zero yields `111` and leaves the gate open. All eight ratios and all 112 clocked ratio/shift fields
match the steady production periods. Shifts 14 and 15 select no physical tap.

The physical KOMU..HEZU shift bank is zero-reset with XNOR feedback. JOTO samples that feedback on
a separate half-cycle and KAVU selects the seven-bit-width path into JEPE. Taking the bitwise
complement gives Coffee GB's all-one-reset XOR representation. Exhaustive comparison covers every
one of the 32,768 states in both width modes and agrees on the next state and digital output. This
is a useful representation equivalence, not by itself a reason to rewrite the tiny production
`Lfsr`.

The original trigger comparison was an **architecture falsifier of using production scheduler
events as physical node timestamps**, not of the gate cone. Sweeping the first two post-trigger
LFSR clocks over all 112 clocked NR43 fields, four production alignments, inactive/active retrigger,
and all eight candidate CH4/HAMA phases finds no single phase-plus-offset mapping in seven of eight
alignment classes. The concrete inactive/alignment-zero witness needs offset -5 for ratio zero and
-9 for ratio one. That proves the two projections cannot be aligned by a magic constant.

`DmgNoiseTriggerWriteCone` then supplies the missing upstream path as an **external-netlist-anchored
constructive cone**. A caller queues only an ordinary NR44.7 write and supplies raw fixed-speed T
ticks. A four-state reset-seeded clock ring derives the CPU write aperture, DOVA/APU_PHI edge, GYSU
sample, retained HAMA half, HAZO restart, delayed release, ratio terminal, and LFSR clock. There is
no public phase, countdown, alignment, or offset input.

Two independent Icarus nodelay traces distinguish the retained HAMA state. With HAMA low at the
write, GYSU samples at +2 T and the first two LFSR rises are +33/+49 T. Advancing the same raw island
one four-T interval leaves HAMA high at the write and produces +29/+45 T, matching the SameSuite
fixture's internal gate nodes. The executable wrapper derives both vectors. Exact source anchors,
stimulus, tool provenance, commands, and finite falsifiers are recorded in the class Javadoc and
[signal-oracle-repro.md](signal-oracle-repro.md#dmg-ch4-trigger-clock-probe). The minimal probe was
rerun from that manifest; the SameSuite invocation remains observed but not command-reproduced.

This does not invalidate `PolynomialCounter` as an externally correct production scheduler and is
not a production replacement. It means its countdown events are a projection across GYSU/restart,
not timestamps for the internal LFSR clock nodes. Timed sub-T propagation, reset/clock-gate seeding,
other write apertures, STOP, live NR43 collisions, test-mode bypass, CGB, and double speed remain
explicit falsifiers.

An isolated production-cut attempt makes that boundary quantitative. The following numbers are
**discarded-worktree measurements**, not branch-reproducible acceptance evidence: both candidates
were reverted and `/tmp/coffee-gb-noise-production-cut` is clean at base `49e1276b`. The correctness
run used:

```sh
/opt/maven/bin/mvn -pl core test -Ptest-samesuite -Dintegration.test.threadCount=1
```

The lean candidate's weaker Blargg check used profiles
`test-blargg-individual,test-blargg` with two integration threads. No patch should be reconstructed
from the metrics alone.

A faithful raw-clock model
kept HOGA/GYSU, GONE/GORA/GATY, the complement-loaded prescaler, and fourteen ripple stages. It
reduced compiled branch transfers from 31 to 27 but changed production by `+96/-67`, expanded
retained state from seven to ten fields, and failed eight of 77 SameSuite cases—8 of the 13 CH4 ROMs.
A lean immediate-restart projection was `+53/-67`, reduced branches to 19, and still failed the same
eight cases for every raw phase seed. Blargg's 54 aggregate/individual cases passed, showing why the
stricter channel-phase suite is needed.

The exhaustive separating test covered both activity states, four production alignments, and all
112 clocked NR43 fields: 896 vectors with the first two LFSR events. No faithful-cone seed matched a
single complete alignment group even after allowing one constant offset; the lean cone's best seed
matched only 168/896. For inactive alignment zero and NR43 zero, production emits `+11/+19` T while
the lean and faithful phase-zero candidates emit `+4/+12` and `+16/+24`. The missing projection is
frequency/alignment dependent, not a global skew.

Both candidates were discarded. Restoring correctness would reintroduce the existing trigger
alignment/countdown cases and `countdownReloaded` live-write aperture (or equivalent branches), the
faithful model was not a net deletion, save-state mapping was not bijective, and CGB remained
ungrounded. Performance work was intentionally skipped after correctness and deletion gates failed.

The experiment also separates unobservable projection choices. The selected DMG clock cone keeps
its LFSR running behind a length-stop output mask, while production freezes that internal state;
DAC-off and GARY/CARY collision state differ similarly. CGB restart clocks, sub-T feedback
propagation, live NR43 mux writes, test-mode bypass, analog DAC behavior, and reset assertion/release
remain named gates. No production code was changed.

## DMG STAT/control topology

`DmgStatControlPlane` is a **fitted behavioral partition plus production differential**. It accepts
only the terminal pixel dot `E`; it is not a renderer. Its state is split into candidate
responsibilities:

- readable mode latch;
- independent OAM-read, OAM-write, VRAM-read, and VRAM-write gates;
- LY counter, registered LY, readable coincidence, and a retiring comparator contribution;
- eight-dot M2 strobe and an FF41.M2 enable sampled on its leading edge;
- internal VBlank source independent of readable mode;
- M0 source at `E+4`;
- one shared STAT level, rising-edge detector, and IF latch.

The sampled M2-enable latch is a useful ownership hypothesis. A later STAT write cannot
retroactively arm the current pulse, so ordinary/frame-start STAT-write blocking and
HBlank-to-OAM handoff behavior no longer require raster-specific write masks. However, raster
advance still contains calibrated line/dot branches (including line 153), and the transient
all-enabled DMG write remains an explicit `0x78` input vector followed by procedural settle calls.
Those cases have been localized, not derived from a shared gate model.

The **production differential** matches 70,224 consecutive observations in a complete
post-constructor steady frame for
readable mode, visible LY, coincidence, internal M1, OAM read/write gates, and VRAM read gates. It
also passes directed line-153, LCD-enable, source-handoff, and write-glitch self-tests. These checks
show that the partition reproduces the encoded schedule; they do not independently establish its
silicon causes.

Unresolved boundaries are deliberately named rather than hidden in the raster island: constructor
phase, physical `E+4` M0 versus production's earlier prediction signal, mid-transfer window cancel,
divergent renderer/control tails, terminal WX166/X167 reads, HALT read muxing, and central IF
acknowledge. Those belong at pixel-pipeline, CPU-readable-mux, or interrupt-synchronizer boundaries.

The follow-up **external gate-model waveform** resolves two of those cases causally. LY is an
eight-bit ripple whose terminal path decodes only `v7 & v4 & v3 & v0`; a DFF samples that level and
asynchronously resets the ripple. Mode 1 is sampled independently from `v7 & v4`, so it remains set
after readable LY has already returned to zero. On the next line, readable M2/OAM parsing starts
before the separate mode-1 sample clears. There is no eight-bit line-153 equality operation.

The FF41 write glitch is similarly ordinary connectivity in the model. Four transparent enable
latches open while the shared CPU data bus is still precharged high, briefly enabling every STAT
source. With HBlank active, the shared STAT level rises and sets IF.1; the requested zero then
propagates through the still-open latches. FF45 uses the same kind of transparent receiver bank,
followed by a comparator and a separately clocked coincidence latch. No semantic `0x78` event is
needed.

`DmgStatGateConeTest` is a bounded executable extraction of those paths. Its four tests pin the
transition ordering for an ordinary line, terminal ripple reset/mode handoff, FF41 precharge, and
FF45 comparator sampling; its model contains neither `0x78` nor `== 153`. This upgrades those two
local explanations only. It does not replace the broad raster model or establish absolute silicon
delay, LCD startup/disable, every CPU write phase, all source combinations, the sub-nanosecond
mode-1/OAM handoff, CGB, or central IF acknowledgement.

A separate production cleanup removes one exact dead control cone from
`StatRegister.statChangeTriggersStatIrq`. Inside the unchanged live-line branch, the old
mode-0-window/line/speed predicate selected the same LYC contribution in both arms. The predicate
contains only field reads and integer arithmetic, so deleting it cannot move an interrupt request,
callback, debugger event, or state mutation. Public descriptors and State/Memento components are
unchanged. Production changes by `+2/-6`; `StatRegister.class` shrinks from 39,220 to 39,100 bytes,
and five conditional sites disappear. This is a **branch-accepted exact dead-control-cone
reduction**, not another gate/netlist claim.

## Forward-only DMG pixel path

`ForwardDmgPixelPipeline` is a **fitted constructive datapath with bounded self-tests and production
differentials**. It has three coordinates: tile-fetch X, FIFO-pop X, and LCD X. One tile flight
samples its low byte at launch, its high byte three dots
later, reaches the FIFO at dot four, and produces an immutable raw token which crosses a three-dot
scanout. An object fetch gates future FIFO pops but cannot stop or modify scanout tokens already in
flight.

That small spatial graph reproduces the current calibrated sprite costs for every background-fetch
phase (`11,10,9,8,7,6,6,6` dots) and chained same-X sprites. Several present repair paths have
candidate forward-only representations:

- the first window tile is an ordinary flight launched with a FIFO flush; a late LCDC.4 transition
  selects the address seen by the one real high-byte transaction, so no reread or FIFO patch exists;
- object low and high bytes are distinct transactions around the resume edge;
- LCDC.1 drops future object matches and final output immediately; a transaction that has not
  captured its first byte disappears, while committed byte/shift stages continue forward without
  three-dot catch-up execution. Exact pop-gate release remains outside the bounded gate trace;
- WX comparison emits a retained trigger token, while fine SCX controls raw-token validity on a
  different coordinate; and
- a delayed window-enable edge invalidates only matching tokens still inside scanout, flushes valid
  fetch/FIFO stages, and launches a normal window flight. FIFO-pop remains monotonic and LCD-X does
  not advance for invalid tokens. The observed three-dot desynchronization bound is simply scanout
  depth.

Thirteen graph self-tests pass, and bounded formula differentials match production sprite, SCX,
and window timing. Passing the repository's production fixtures or Mealybug image suite does not
exercise this detached graph as the renderer. The graph
refuses a mid-line fine-SCX rephase after raw popping has begun and expires a window trigger once
its matching token crossed the irreversible LCD boundary. Disabled-window insertion glitches,
palette/LCDC output muxing, overlapping object priority, mode-3/STAT completion, and all CGB paths
remain explicit boundaries. Until the graph shadows raw-token and pixel traces for those cases, it
cannot establish that the dual production renderer and its repair paths are deletable.

A separate **direct-alias falsifier** tried collapsing production onto each existing machine. Using
only the unshifted timing machine passed 2/26 strict Mealybug images. Using only the shifted `+4`
pixel machine preserved 26/26 Mealybug, but passed just 279/282 focused PPU/STAT cases and 119/130
Mooneye/DMG-acid cases because the control plane moved four dots late. Adding read-only timing and
terminal-object stage taps recovered 282/282 focused cases and 129/130 Mooneye cases. The remaining
failure was `intr_2_mode0_timing_sprites` test 20 with ten sprites at screen X=3, despite matching
the logged X=160 output and visible/internal HBlank events around dots 310--314.

That best alias still added 63 and removed only 14 production lines, retained rewind, refresh, and
catch-up semantics, and had no safe save-state mapping: released snapshots contain two independent
46-field `PixelTransfer` states, while the alias necessarily overwrote one with the other. It was
therefore reverted rather than kept as a near-pass. Migration now requires a typed per-dot shadow
of source-latch changes, tile-byte transactions, FIFO pushes/pops, object flights, raw/LCD commits,
and mode-3 completion into the forward graph. Only after those events agree for every strict
Mealybug image can the two mementos collapse to one causal pipeline state plus a legacy importer.

`DmgObjectFlightGateCone` supplies **bounded external DMG-B gate-model evidence** for the object
case. Static fanout gives FF40.D1 only three direct consumers: one gate shared by all ten OAM-X
match terms and the two final object-plane output masks. It does not feed the VRAM data mux, low/high
byte latches, or either physical object shift bank. In a CPU-reachable nodelay probe, dropping D1
before the first byte cancels the transaction; dropping it after low-byte capture immediately
withdraws match/output while the high byte, bank load, and eight shifts retire. An enabled control
has the same data/latch/shift timestamps. The default-delay build independently confirms only the
late-disabled ordering, with propagation glitches but no change in ownership.

The Java cone transcribes that ownership with explicit semantic capture/load/shift inputs; its
self-tests are not a second external validation. Coverage is one DMG-B sprite slot/X/row/tile,
two nodelay CPU apertures, one enabled control, one default-delay late aperture, and simplified OAM.
It proves neither the exact fetch/pop schedule nor that production's +3 catch-up can already be
deleted. Other slots, overlap/priority, X flip, additional write phases, physical DMG, and CGB are
finite falsifiers. Reproduction details are in
[signal-oracle-repro.md](signal-oracle-repro.md#dmg-object-enable-flight-probe).

A separate **production-shadow falsifier** tried the obvious local deletion against the strict
Mealybug images. Removing the three synthetic catch-up advances leaves the base
`m3_lcdc_obj_en_change` image exact, but `m3_lcdc_obj_en_change_variant` first differs at pixel
`(153,128)`, with the background band three pixels late. Removing object reread/refresh does not
change that failure. A parallel shadow that retires the already-launched low/high/shift flight while
masking object output also fails at the same pixel; delaying the fetch-control enable worsens the
error by another three pixels. The isolated production probe was discarded after restoring and
recompiling its worktree.

This separates two mechanisms which the current repair conflates. The gate trace grounds object
data ownership, but the catch-up loop pays the phase difference between the CPU-timeline write and
Coffee GB's independently advanced `+4` pixel machine. Deleting it requires rephasing or removing
the dual-machine representation (or arranging for the upstream machine to have already queued the
three background tokens); another local object condition cannot do so.

`ForwardDmgPixelReplayContractTest` makes one of those missing seams executable against the real
`m3_lcdc_win_en_change_multiple_wx` ROM. At deterministic post-FAST_FORWARD tick 128,703, line 1
dot 92, the CPU-visible LCDC changes from `E1` to `C1` while the shifted physical pixel machine has
an active window source. Coffee GB's downstream `pixelMachine.window` path retires eight ticks
later on the same line (observed at dot 101). This is a production trace, not hardware truth.

`DmgWindowSourceLatchCone` then recovers a smaller **external-netlist-shaped control cone** behind
that missing input. In the pinned DMG model, `pyco` samples `wxy_match` on `roco`, `nunu` samples
`pyco` on `mehe`, and the `pynu` NOR latch retains the resulting `in_window` level. Its reset input
reduces to `xofo = NAND(ff40_d5, xahy, ppu_reset_n)`. FF40.D5 is the CPU-write latch output and
reaches `xofo` without a sampled receiver. A falling LCDC.5 can therefore clear an already-active
source asynchronously through ordinary latch wiring; no renderer callback, pixel rewind, or
repaired FIFO token is required. `xahy` reduces to
`(anel || !start_oam_parsing) && ppu_reset_n`; it can reset the source independently, but cannot
delay a low FF40.D5 reset. Eight tests cover two-stage old-state sampling, activation, reset
dominance, reset release/reassertion, unrelated clock edges, and arbitrary-phase replay.

This static topology proves that the measured eight-tick Coffee GB retirement interval cannot
belong to the source latch: the external model's estimated FF40.D5-Q-to-`in_window` propagation is
about 1.78 ns, or 0.0075 DMG dot, and contains no PPU-clocked stage. A source-tagged production
trace locates the software compensation more precisely. On the falling edge the shifted fetcher is
at `GET_TILE_T1` and its FIFO already contains eight WINDOW tokens. The pre-edge window map/push
occurred at -3/-1. Those committed tokens pop at offsets 0 through 7, so an immediately selected BG
replacement can naturally reach the raw boundary at +8 and the three-dot LCD boundary at +11.

Coffee GB currently reaches the same +8 control observation by the wrong causal path: after the
source reset it reads another WINDOW map (`9C01`) at +3, pushes that row at +7, and does not issue
the first BG map (`9801`) until +11. The detached forward graph now carries immutable source tags
through tile, FIFO, raw, scanout, and LCD stages. Its composition test switches the source
immediately, keeps the eight already-committed tokens, and derives the +8/+11 handoff with no
semantic deactivation delay, reread, patch, rewind, or catch-up loop.

An independent **external gate-model waveform** confirms the causal direction while adding a
necessary qualification. In both nodelay and default-delay builds, FF40.D5 asynchronously clears
`in_window`, the live address mux selects BG, `win_start` does not reassert, and no new window-map
transaction launches. Already captured shared fetch/bitplane/shifter state can still retire. The
default-delay model preserves almost two tile payloads in one CPU alignment (BG reaches LD after
15.410 dots) and one staged payload in the other (11.410 dots); nodelay reaches BG after 6.5/10.5
dots. The timing disagreement prevents a silicon latency claim, but both traces reduce the
mechanism to immediate source selection plus at most one retained transaction and one eight-bit
shifter. See [signal-oracle-repro.md](signal-oracle-repro.md#dmg-window-source-handoff-probe).

This is the strongest constructive PPU evidence in the spike, but still not a cut-over proof. It
covers one hard edge and shows that Coffee GB's dual renderer can preserve the correct image while
using a causally wrong extra fetch. The candidate's fixed eight-token composition is only one
subcase of the externally observed bounded in-flight topology; it must also represent a retained
tile/bitplane flight and shifter state. It still lacks broad source-tagged replay across all
Mealybug cases, overlapping objects/window/SCX writes, mode-3/STAT integration, autonomous address
generation, and CGB. The hand-written source-latch cone also omits the separate `nopa`/`nuny`
`win_start` pulse; its Java `activated` field observes only a Q transition.

## PPU register fanout and LCD output/reset

`DmgPpuRegisterFanout` is a **production differential with fitted receiver delays** for an
alternative to the selected CPU-write queue hypothesis: one CPU-visible source register feeds
continuously clocked consumer paths. Timing/control reads the source directly;
only LCDC.5, fine SCX, and WX cross the bounded pixel-domain byte paths. Rapid writes remain ordered
because every stage captures on every edge, and LCD reset makes the paths transparent instead of
replaying queued history. A packed test-only `ByteSignalDelayLine` is the experimental primitive
used for these eight parallel receiver paths; it is deliberately not production infrastructure.

A randomized differential matches the production delayed views over CPU-reachable DMG writes—one
write strobe per four-dot machine cycle—without a pending-write collection. An intentionally denser
write-every-dot trace does not match and is rejected as an impossible CPU waveform, rather than
forcing queue semantics back into the model. The external netlist contains one SCX latch bank and
one WX bank, supporting a single source bank; it does not establish the five Java receiver delays.
Those capture edges remain a boundary approximation until the half-dot source phase and consumers
are derived.

One small production reduction is independent of those still-fitted receiver delays. The CGB
LCDC.4 collision formerly kept separate active and pending dot counters alongside an eight-cell
consumer-history bank. Reachability analysis shows that every active pulse is written into that
history on the same capture edge; only the not-yet-sampled CPU-write strobe carries future
information. Production now stores that boolean strobe directly into the history and removes the
active counter, pending duration counter, and countdown branch. Released state keeps the two
integer slots for import, projecting the active slot as zero and the pending slot as the strobe.
Controller state validation now bounds both released slots to that emitted binary domain.
Focused PPU/output tests (31/31), unit tests, Mealybug (26/26), SameSuite (77/77), Daid (9/9), and
CGB acid2 pass. This is an exact state reduction under the existing calibrated behavior—not an
independent gate trace of the CGB collision.

A second **branch-accepted exact state reduction** reuses `GpuRegisterValues`' existing visible and
pending per-register latch banks for three already-calibrated conflict classes. Palette writes put
the old-or-new byte in their slots, a CGB normal-speed SCX write puts the old byte in the SCX slot,
and a WX write puts a strobe token in the WX slot. Advancing the two banks once per PPU tick replaces
the separate WX duration counter and the visible/pending SCX scalars, deleting three live fields.
The per-register token meanings remain behavioral encodings; sharing their storage does not prove
that DMG/CGB hardware implements one physical bank with those Java values.

The first version exposed a real abstraction hazard which full ROM tests did not find:
`getEffective(SCX)` returned the old fetcher byte and `getEffective(WX)` could return the strobe
token, whereas the released public method returned live values for every non-palette register.
A review falsifier caught that divergence. The retained implementation restricts mixed
`getEffective` reads to BGP/OBP0/OBP1, leaves SCX conflict observation on `getForFetcher`, and leaves
WX observation on `isWxJustChanged`. Its differential now checks `getEffective` for every register.

Bounded exhaustive write/tick sequences in DMG and CGB compare the shared banks with a frozen copy
of the previous six-field conflict machine through depth six. Released snapshots retain the old
array and scalar record fields: capture reconstructs WX duration and SCX slots, while restore maps
them back into the common banks. Together with the API correction, the production diff is
`+36/-38`, a net deletion of two lines. This is preservation of the existing receiver schedule;
the fitted capture delays described above remain migration debt.

Controller validation makes the reachable compatibility boundary explicit. It accepts released
arrays with the SCX/WX slots clear and the conflict held in their scalar fields, or the exact current
array mirrors of those scalars. It rejects never-emitted conflict values in unrelated slots and
malformed array lengths before applying state. This preserves released-save-to-current loading;
newer-save-to-older-binary loading is not part of the versioned state contract.

`DmgLcdOutputSignalCone` is an **external-netlist-boundary plus fitted output hypothesis**. It takes
immutable background/object tokens through three forward validity cells. BGP/OBP and LCDC.0/.1 are
sampled only at the consumer mux, so palette and priority writes do
not modify retired FIFO data. The first visible token opens a local panel-clock latch and captures
its token/palettes; live LCDC is evaluated on the next edge. The palette write-dot envelope
(`old | data`, then `data`) and LCDC set-before-reset behavior are explicit formulas for fitted local
outcomes rather than a simulated latch/delay cone. Exhaustive palette pairs, the mux truth table,
and production write differentials agree. All 24 strict Mealybug hardware-reference images also
pass on the branch, but those integration tests still use the unchanged production renderer and
therefore are regression evidence, not cone output evidence.

The follow-up power cone has **external netlist/gate evidence** reducing nodes
XONA/XEBE/XODO/XAPO to `ppu_reset_n = FF40.7 && hard_reset_n`. That establishes the reset root. The
Java output cone then manually assigns that wire to the pending raw token, every scanout-valid age,
and the panel-opening latch. Disabling LCD at any
age therefore drops the in-flight token without X, LY, mode, FIFO, or token-repair logic; releasing
reset cannot resurrect it. Palette registers retain their data, and already emitted panel output
is irreversible. Separate KAHE/KUPA muxes keep the LCD pins on inverted 8192/4096-Hz refresh clocks
while the PPU is off.

The root reduction does not prove that every proposed scanout stage has that physical reset fanout.
These cones are DMG digital boundaries. Exact sub-dot FF40/pad propagation, analog LCD waveforms,
the physical transparent-write aperture, upstream fetch/FIFO reset fanout, global first-line/relock
state, and CGB remain named migration gates.

## OAM dynamic SRAM

A detached dynamic-SRAM experiment transcribes the generic mechanisms used by the gate simulator:

- word-line selection sticks until precharge;
- the first enabled row claims a floating bit line;
- selected columns share a common keeper; and
- enabled cells receive the resolved bit-line value.

With retained value `a` and two newly selected column values `b` and `c`, common-line feedback
produces `b == c ? b : a`, which is the majority function
`((a ^ c) & (b ^ c)) ^ c`, without a data-dependent corruption branch. Sticky word lines also
produce row copy without a copy routine.

The coarse experiment has a useful falsifier: it feeds the majority result back into one column
that hardware-verified `SpriteBug` behavior preserves. It is therefore a mechanism demonstration,
not a production replacement.

`OamSramControlCone` is an **external-gate-trace mechanism plus fitted exact-data hypothesis**.
Complete symbolic closure of the symmetric two-row/two-column model reaches 5,968 distinct
transformations but cannot produce the exact verified write mapping: every reachable majority
result also corrupts the contributing third column. Separating a column's common-line sample enable
from its feedback enable is the minimum extra control. Columns zero and two sample the common line
while only column zero receives feedback; the old keeper then resolves disagreement to the retained
value. This
matches all eight one-bit truth assignments and 4,096 randomized complete OAM images against
production `SpriteBug.corruptOamWrite`, with no value-dependent path. That differential shows a
compact fit to the existing formula; it is not an independent silicon oracle for the directional
feedback split.

The candidate affected-row families are generated by a fitted address-rail aperture rather than
runtime modulo cases. During
an increment, a two-low-rail transparent carry aperture exposes the old address, then each
intermediate address as low rails clear, then the new address. Sticky word lines therefore select
`{old,new}` for odd rows, `{old,row-2,new}` for rows congruent to two, and
`{old,row-2,row-4,new}` for rows congruent to zero. Randomized production differentials agree for
the regular scan rows; row 16's additional row-zero alias is retained as a separate missing cone.

The evidence boundary matters. The **external netlist/gate trace** establishes sticky selection,
carry-skewed address rails, retained bit lines, column sampling, and absence of an SRAM write
strobe. It does not expose the directional feedback split: that is a minimal fitted topology which
survived the symmetric-model falsifier, not yet an observed transistor fact. The remaining gates
are row-16 alias feedback, first/last scan latches, the exact analog aperture, read-corruption data
selection, and every CGB OAM path.

The full external gate simulation adds concrete evidence. During the source OAM-read-corruption
program, the OAM-A SRAM waveform showed:

```text
word lines: 0x00002 -> 0x00003 -> 0x00007   (no intervening precharge)
address:    retained row 1 -> transient row 0 -> transient row 2
column:     0 -> 1
common:     0x67 -> 0x57
```

The final copied OAM bytes were rows 0 and 2 becoming row 1; all other bytes remained the initial
sequence. This validates sticky selection and keeper/bit-line feedback as the source of the row-copy
shape.

A packed follow-up probe also establishes what this external model cannot answer. Each of the eight
bit positions encoded one complete `(retained a, preceding-column b, preceding-column c)` truth
assignment. With target column zero `a=F0`, preceding column zero `b=CC`, and preceding column two
`c=AA`, the hardware-verified majority mapping would produce `E8` in target column zero while
preserving column two. At scan rows 1, 2, and 4 the simulator instead copied the complete preceding
row: target column zero became `CC`, column two remained `AA`, and every CPU/OAM SRAM write strobe
stayed inactive.

This is not evidence against a directional physical SRAM. The pinned `generic_sram` exposes one
symmetric column mask: every selected bit line contributes to the common keeper and every selected
bit line receives the same feedback. Separate sample and write-back gates do not exist at that
abstraction. Surrounding decoder skew can select different rows or columns over time, but cannot
recover a transistor-direction signal which the array model erased. The packed result therefore
directly falsifies `generic_sram` as an exact blocked-write oracle and leaves the Java directional
split **FITTED**. Promotion requires a transistor-level/licensed OAM model or controlled real-DMG
bit-pattern captures.

A second full run enabled the source program's `PUSH BC` corruption with `SP=FE01`. It
self-terminated with rows 1 and 2 both equal to row 0, while every later byte remained intact.
Instrumenting cell writes exposed a stronger result: neither OAM SRAM's `wr` input asserted during
the corruption. At time 33699216 the row-1 word line rose while all four bit lines still held row
0, and the ordinary read-feedback rule rewrote every differing cell in row 1. At 33700192 the row-2
word line rose before the retained bit lines were cleared and repeated the copy. No encoded PUSH,
row-copy, or corruption equation exists in the simulator.

The ordinary/PUSH traces falsify, for the external gate model, an implementation in which a
forbidden CPU write invokes an SRAM corruption operation. The simulated event is a disturbed
address/precharge/read sequence; writing the cells is what that dynamic read port normally does when
retained lines and a newly selected word line disagree. Comparison with real DMG captures is still
required before promoting the model's exact delay policy or directional feedback split to a
hardware claim.

### Offline-oracle reproducibility

The oracle run used `dmg-sim` revision `ee559e1`, its full timing model, and non-simplified OAM for
5 ms of simulated time. The included SM83 source test was assembled with the project's binutils
fork. A current upstream Icarus Verilog build was required because the packaged compiler could not
elaborate the model; the only testbench adjustment was moving one module instantiation below its
signal declarations. FST was converted to VCD for scripted signal extraction, and a temporary
`$display` probe reported only actual OAM cell value changes.

None of the external netlist, generated Verilog, ROM, waveform, or toolchain is copied into this
branch. Besides keeping the runtime experiment small, this avoids mixing the CC BY-SA schematic
artifacts into Coffee GB's MIT-licensed source. A production control cone should be independently
translated or generated only after a licensing decision; until then the gate model is a development
oracle.

Before an external-netlist observation can promote a production slice, the branch must contain an
MIT-side reproducibility manifest and driver/extraction scripts. The manifest must pin repository
revision, source paths and node names, tool versions, exact commands, local patch diff, probes, and
expected summaries. Licensed netlists, ROMs, and waveforms may remain external; the procedure which
consumes them and checks the claimed observation may not exist only as prose or shell history.

## Bus fabric boundary

`HeldBus8` now has an explicit `beginDrive -> drive -> resolve -> sample -> commit` transaction,
stable owner IDs, line-level owner masks, electrical-contention versus multiple-owner reporting,
and floating-held masks. Exhaustive tests prove commutative resolution and all requester orders.

It intentionally implements only low-dominant held-line behavior. Existing hardware-verified cases
reject a universal collision policy: CGB VRAM can clear a receiving latch, CGB cartridge and WRAM
have different ownership/drop rules, I/O commonly returns masks or `0xff`, and DMG OAM includes
dynamic cell/keeper feedback. The production architecture needs separate bus instances, grants,
and receiver cells composed from small primitives.

## DMA request and grant topology

`DmaRequestGrantTopology` is a **behavioral request/grant decomposition with self-tests and
production differentials**. It separates four responsibilities that are currently interleaved in
`Gameboy.tick()` and the HDMA state machine:

1. a three-cell PPU-to-CPU HBlank synchronizer;
2. retained request, CPU/VRAM-DMA lease, and late-interrupt-eligibility cells;
3. OAM and VRAM DMA sequencers which emit bus intents in their own clock domains; and
4. a pure same-cycle resolver followed by a write-only commit edge.

The CPU publishes claim, interrupt, retire, or relinquish signals. HALT is therefore a relinquish
wire, not opcode `0x76`; DMA never fetches or decodes an instruction. The PPU publishes actual OAM
and VRAM port-use intents rather than a visible mode or a retiring-instruction exception.

Those inputs are not yet raw hardware wires. `dmaPreemptsThisPhase`, lease retirement,
`lateInterruptAccepted`, burst termination, and PPU port intent are semantic oracles supplied by
the fixture, while startup and copy sequencers retain calibrated speed/parity profiles. The model
demonstrates a candidate dependency direction; it does not yet prove that the producers can stop
carrying the same timing knowledge under new names.

The detached model matches all 160 OAM copy edges at normal and double speed, including a mid-copy
speed change; all sixteen VRAM source strobes and the block commit for GDMA and HBlank DMA across
speed/parity profiles; the ordinary three-edge HBlank synchronizer; representative CPU-retire,
late-interrupt, and frame-start grants; DMG's merged main bus versus CGB's split cartridge/WRAM
wires; and the shared source-mux collision where VRAM DMA redirects an OAM copy. All 120 orderings
of a four-master intent set resolve identically. A simultaneous CPU and VRAM-DMA execution intent
is reported as a handshake falsifier, because a valid lease makes it impossible; assigning a
priority would conceal the bug.

A final production-state audit found no honest local OAM-DMA packing/deletion. The retained
`DmaStateIrreducibilityTest` constructs four finite counterexamples: equal CPU-clock age after
different normal/double-speed histories still needs the independent fixed-rate `ticks`; equal
fixed-rate age with CPU-clock ages eight versus nine changes the next byte-copy edge; a same-page
restart is observably blocked despite every other restored component matching a fresh transfer;
and each released PPU ownership/previous/through-restart level independently changes a public
observation or the next tick. Removing any of them would require provenance, state packing, or a
released-state behavior change, so production `Dma` is left untouched.

Two bounded production reductions were attempted and both were rejected. The first contextual
one-boolean encoding treated the same bit as interrupt owner outside a CPU lease and late-interrupt
eligibility inside it. It passed the initial suite but failed a whole-machine review falsifier:
interrupt entry won an arriving HBlank request, software cleared FF0F before the next tick, and the
CPU then claimed the slot. The released machine retains the already-latched interrupt owner across
that ownership change; the contextual encoding silently reinterpreted and lost it, so a later IF
reassertion let CPU and HDMA phases diverge. That abandoned precursor remains in history as
`b4770a0e`.

The follow-up two-bit plane preserved the latches independently and passed exhaustive transition,
restore, whole-machine, unit, Mooneye, Gambatte, GBC-HW, Daid, and acid tests. It was nevertheless a
representation-only packing: both logical levels and their semantic transition branches remained,
`Hdma.class` grew by 517 bytes with six additional conditional sites, and the source reduction was
only seven net lines. It therefore failed the complexity/deletion spirit despite behavioral
equivalence and was rolled back in `5070d997`. Production again stores the two booleans directly.
The exhaustive independent-latch tests and the whole-machine FF0F clear/reassert regression remain
as contract coverage.

The decomposition does not pretend digital ownership explains every case. HALT-wake level history,
STOP/speed-switch reverse phase, terminal or overlapping HBlank requests, HDMA disable/LCD-off
handoffs, OAM restart/acquire history, partial address decoding, CGB's WRAM alias, delayed interrupt
stack collisions, dynamic OAM write corruption, invalid-source open-bus decay, and VRAM block
visibility remain explicit migration gates. The safe cut is passive intent tracing first, then the
CPU/VRAM-DMA lease latch, then actual PPU port intents. Promotion additionally requires deriving the
semantic grant inputs from persistent CPU/PPU strobes and deleting the startup profile tables. The
electrical collision and copy sequencers stay in production until each named profile has an oracle.

## Existing positive controls

The architectural diagnosis is selective. The DMG-facing part of `Joypad` now has a bounded
circuit-shaped interrupt boundary: the released record's four packed per-line history nibbles
remain as a structural compatibility projection, but their OR reconstructs the single physical KERY aggregate sampled by the
BATU/ACEF/AGEM/APUG 1 MHz receiver, and IF is requested only on the ASOK rising edge. Its remaining
size is mostly host-input ownership, deterministic replay, debugger hooks, fast paths, and the
separate SGB ICD2 packet protocol. The independent AWOB STOP-wake phase remains a future shared-clock
task. `InfraredPort` is similarly a small stored RP output feeding a combinational input mux; its
external Full Changer protocol is behavioral device logic. Neither needs to be rewritten into a
general gate graph.

These are useful controls for the proposal. A large source file is not itself evidence of a bad
hardware abstraction, and behavioral algorithms are not targets merely because they live in
`core/`. The migration should focus on places where one subsystem predicts, repairs, duplicates,
or reorders another subsystem's time.

## Current migration decision

The experiments produce bounded candidates for the Clocked Signal Fabric hypothesis, but do not yet
establish a simpler whole-core model. The eleven retained production changes do establish a useful
lower bar: causal local, reachable-state, and state-plane reductions can delete 68 net
core-production lines and ten scalar fields without pretending that their packed Java
representation is recovered silicon. The controller adds 29 net validation lines to reject
never-emitted portable-state combinations; that compatibility safeguard is intentionally excluded
from the core-production count. Only claims carrying their own external evidence may inform
topology; the other retained cuts are behavior-preserving simplifications under the current
calibrated callback boundaries.

Source and live-state reduction are not the same as whole-class bytecode reduction. Several cuts
move conditional work from hot transitions into cold released-state projection, and their nested
class metadata can grow. The square-phase cut, for example, shrinks both hot tick methods while its
compatibility adapters grow the associated class files. The retained set is therefore a tested
production simplification portfolio, not a claim that all eleven independently satisfy the
four-part architecture-slice promotion rule below.

The result also leaves the first architectural cut boundary unchanged. Packing four acknowledge
levels into one integer did not centralize their sampling, and the rejected HDMA encodings did not
remove its CPU/PPU semantic inputs. Do not next centralize one interrupt source inside
`Gameboy.tickSubsystems()`. First introduce:

1. persistent CPU T1-T4 bus-cycle state;
2. a half-dot/CPU-subedge scheduler;
3. Timer, Serial, CPU interrupt control, and IF latches in one resolve/commit island; and
4. only then delete both peripheral acknowledgement forecasts together.

For the PPU, use the detached STAT topology as a shadow control plane behind three explicit seams:
the raster/pipeline control island, the CPU-readable mode/LY mux, and the central interrupt island.
Do not pull CPU read-phase exceptions into the raster model.

An experiment may be promoted to a production architecture slice only when all four conditions hold:

1. semantic oracle inputs, copied timing constants, and feature-level outcome formulas disappear;
2. independent hardware capture or reproducible external-netlist evidence distinguishes the model
   from a refactoring of current production behavior;
3. the slice replaces production code and deletes more prediction, provenance, rollback, refresh,
   duplicated time, branches, or retained state than it adds; and
4. arbitrary-phase save/restore trace equality and a documented throughput/allocation benchmark
   pass alongside the full hardware-verified test battery.

Until then, keep the exact evidence label attached to the experiment and do not describe a fitted
or production-differential result as a rejected silicon hypothesis.
