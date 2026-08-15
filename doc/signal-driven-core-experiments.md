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
| Serial DIV-reset timing is a forecast problem | DMG external-netlist-anchored production replacement/deletion; CGB production differential | The old arithmetic reduces exactly to a local divider-stage fall, SCK toggle, and falling-edge shift. CGB normal/fast use the same tested algebra but lack an external topology oracle. |
| Timer overflow needs an explicit 4/8-tick state machine | External gate-model waveform plus production differential | A selected-input fall, sampled TIMA-MSB fall, next-BOGA request, shared load/reset cone, and live TMA reload bus reproduce the apparent timeline without an explicit deadline. Exact Java phase alignment remains fitted. |
| Timer/serial acknowledgement can be centralized inside the current master-tick loop | Falsified and rolled back | Timer runs before CPU while serial runs after it; no placement of one central callback preserves both physical windows. A unified CPU-edge/half-dot island is prerequisite. |
| Java evaluation order can be made unobservable | Self-test support for two bounded contracts | A single-resolve edge-triggered scheduler and a separate fixed-point transparent/async oracle are traversal-order invariant. The allocation-heavy oracle is not a runtime implementation. |
| The existing callback boundary is too opaque to shadow a signal scheduler | Production-differential compatibility harness | Immutable CPU/timer/serial/IF snapshots replay current races, but still carry projected timer state and a source-profile acknowledge countdown. |
| The CPU, Timer, Serial, IF, IME, and HALT seams cannot compose without callback ordering | External gate-waveform-shaped Timer source composed with a constructive edge-triggered fabric | One half-dot fabric resolves a natural NYDU/MOBA Timer request, persistent bus intent, live priority, acknowledge, and control latches symmetrically. Timer writes, Serial pin generation, physical acknowledge placement, transparent settling, PPU, and CGB remain outside it. |
| CPU opcode lookahead is intrinsic to accurate races | Constructive persistent-bus seam plus production differential | T1-T4 state can expose in-flight address, strobes, data, held byte, and acknowledge without re-decoding. No production lookahead is deleted yet, and the one-M-cycle-late read anchor remains a migration debt. |
| HALT, wake, and interrupt acceptance require request provenance | External gate-model waveform plus production differential | Direct HALT decode removes HALT's own IDU increment while opcode load and PC write remain active; the delayed decode only sets the HALT latch. The halt bug is an unchanged next-opcode address, not a delayed next-fetch gate. Exact interrupt-source apertures and silicon equivalence remain open. |
| HDMA must decode opcodes and query future CPU/PPU state | Behavioral request/grant decomposition plus production differential | The detached fabric avoids opcode bytes, but semantic preemption/retire/late-accept inputs and calibrated startup profiles still carry equivalent knowledge. |
| One generic held bus explains all collisions | Falsified | Low-dominant held lines are useful primitives, but VRAM, OAM, cartridge/WRAM, and I/O need distinct grant and receiver topologies. |
| DMG STAT behavior needs a large mode/line exception tree | Behavioral whole-plane partition; two bounded external gate cones | The broad model still encodes calibrated raster cases. Independently, a ripple/partial-decode reset derives LY 153/0 and transparent precharged FF41 latches derive the write glitch with neither `line == 153` nor a semantic `0x78`. |
| The OAM bug is fundamentally a Boolean corruption formula | External gate trace for the coarse mechanism; fitted exact-data hypothesis | Sticky selection/carry-skew/retained lines have gate-trace support. The external model's symmetric SRAM directly fails the exact blocked-write mapping, so the directional feedback split remains fitted against hardware-verified `SpriteBug`, not independently observed. |
| APU frame clocks require an eight-step controller | External-netlist-shaped clock cone plus production differential | Sampled divider and ripple latches generate the selected DMG length/sweep/envelope vector. CGB tap selection and two production adapters remain external profile rules/falsifiers. |
| Pulse-channel quirks require semantic trigger/sweep/length branches | Behavioral whole-control decomposition; bounded CH1 external gate trace | The broad resolver encodes settled truth tables. The restart/adder trace independently falsifies `wasActive` as a causal aperture input and derives shift-zero retrigger behavior from retained BYTE state. |
| Active CH3 wave RAM needs time-window and address-rewrite rules | External-netlist-shaped fitted port plus production differential | One address-owner mux, precharged data bus, and two fitted fetch-valid stages reproduce the access window and address aliasing. Retrigger feedback and electrical collisions remain separate cones. |
| CH4 needs a zero-divisor case and a second LFSR algorithm | External-netlist-anchored steady cone plus external nodelay trigger traces | Complement-loaded prescaler cells and a zero-reset XNOR bank remove both semantic cases. A raw write/APU-clock cone derives two observed trigger alignments without a phase input; production countdowns are not physical node timestamps. |
| The four-dot PPU skew requires two independently running renderers | Fitted constructive datapath, source-tagged production trace, and external gate waveform | One forward graph reproduces selected observations. At a hard LCDC.5 edge, immediate source reset plus bounded retained fetch/FIFO/shifter state replaces a semantic delay; Coffee GB instead launches one post-reset window fetch. Broader overlaps remain outside it. |
| Mid-mode-3 writes require pending-write queues and duplicate register views | Production differential at one CPU-reachable cadence | One source register and fitted consumer delays reproduce selected LCDC/SCX/WX views; the receiver stages and half-dot capture phase are not netlist-derived. |
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

### Lean-branch acceptance matrix

All production code outside `SerialPort.java` is byte-identical to the baseline. The following
lean-cut reruns completed with zero failures/errors:

| Suite | Result |
| --- | ---: |
| Core unit tests, including detached experiments | 1,519 run, 8 skipped |
| Mooneye + dmg-acid2 + cgb-acid2 | 132/132 |
| Blargg aggregate + individual | 54/54 |
| SameSuite + Mealybug strict images | 103/103 |
| Gambatte hardware + GBMicrotest | 5,156/5,156 |
| GBC hardware + misc-gb + Daid | 247/247 |
| RTC3 + MBC30 + cgb-acid-hell + Strikethrough + CasualPokePlayer + BullyGB | 15/15 |

That is 5,707 integration cases in addition to the unit suite. Full-ROM passes establish regression
safety for the lean production cut; they do not validate the output of test-only candidate PPU,
APU, DMA, or scheduler models.

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
the physical acknowledge half-dot, the retained selected source, BOGA/NYDU/MOBA timer state, the
external serial pin, or the ordinal of a CGB double-speed subedge. Those are the fields the first
production clock island must add. Only after those physical states replace the projected fields can
the production cut claim that no peripheral deadline query remains.

## Composed edge-triggered DMG control slice

`DmgControlSignalFabric` is a **constructive edge-triggered composition plus bounded production
differential**, not an integrated emulator. It joins the half-dot router, persistent T1-T4 CPU bus
intent, stateless Timer/Serial request pins, shared IF/IE storage, live priority, an unqualified CPU
acknowledge gate, IME/EI, and HALT/wake in one DRIVE→RESOLVE→CAPTURE→COMMIT boundary.

This composition removes two circular inputs from earlier detached tests. An interrupt-entry bus
cycle no longer supplies a selected source; the fabric derives the one-hot acknowledge from settled
`(IF | rawRequests) & IE`, so a simultaneous Timer+Serial request selects Timer by ordinary
priority. The composed CPU advances only persistent bus state; its older test-local IME/HALT
semantics do not enter the fabric or its memento. Timer and Serial pins arriving on the same
half-dot are therefore sampled symmetrically, whereas the current Timer→CPU→Serial callback order
can expose only the first source at that boundary.

Ten composition tests cover reversed driver/commit order, same-edge clear-dominant request/ack
collisions, live priority, EI/HALT, a held write strobe, the current four-CPU-clock readable-IF to
HALT-wake distance for both Timer and Serial, and exact arbitrary-boundary save/restore replay.
They establish that the selected edge-triggered seams can share one causal boundary without opcode
identity or a peripheral deadline query.

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
external serial input, physical acknowledge and vector-capture phases, PPU source paths, CGB
subedges/direct interrupts, and transparent or asynchronous delta settling remain blockers.
Consequently these tests can name a future deletion set—both peripheral acknowledge forecasts and
their `InterruptManager` flags—but cannot yet delete it safely.

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
cycles. Its remaining named falsifiers are the FF0F source-set/write aperture, PPU request-input
phases, early IE bus timing, vector/acknowledge capture, and CGB's different direct-interrupt path.
The source anchors are `sm83/sm83.sv:4649-4795` (IE and IRQ sample bank), `:8698-8774` (YOII and
HALT paths), `:8812-8886` (IME controls), and `sm83/cells/decoder2.sv:205-213` (IDU-increment
terms). The Java test contains no external source, ROM, or waveform artifact.

## Timer overflow topology

`TimerSignalTopology` is an **external gate-model waveform plus production differential** which
replaces the flattened overflow timeline with two 1 MHz-edge latches:

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
`8 + 4*shift` T. Production's inactive extra four T therefore remains a compatibility projection
outside this cone, not a gate-level consequence of `wasActive`.

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

This is still not a production replacement. The external model is DMG-B, not silicon, and the
dynamic coverage is four shifts, one frequency, one active-retrigger spacing, one natural BEXA
phase, and one NR10 collision. Raw/asynchronous write phases, other BEXA alignments, close negate and
carry collisions, frequency-write/retrigger overlap, intermediate analog timing, and CGB remain
explicit falsifiers.

`DmgEnvelopeWriteRipple` is an **external-netlist-shaped fitted hypothesis plus production
differential** for the four DMG envelope counter cells as master/slave toggles whose
higher-stage clocks are selected from the preceding true or complement output according to the
direction wire. That wiring produces the otherwise odd active-write transforms—up to down becomes
`(-v) & 15`, down to up becomes `(14-v) & 15`—without affine value branches. All 8,192 unlocked
production combinations agree. The same-value low-nibble case uses the JUFY write aperture as a
clock pulse rather than a value exception.

One local replacement hypothesis is explicitly falsified rather than copied: production's
`pendingEnvelopeClock` cannot be inferred from this write cone. The wider topology retains timer
bits and frame phase while period is zero, so an exact timer/phase cone must replace that scheduler
flag before `VolumeEnvelope` can be migrated.

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
- LCDC.1 abort invalidates the future object stage and releases the pop gate on that dot, with no
  three-dot catch-up execution;
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

The decomposition does not pretend digital ownership explains every case. HALT-wake level history,
STOP/speed-switch reverse phase, terminal or overlapping HBlank requests, HDMA disable/LCD-off
handoffs, OAM restart/acquire history, partial address decoding, CGB's WRAM alias, delayed interrupt
stack collisions, dynamic OAM write corruption, invalid-source open-bus decay, and VRAM block
visibility remain explicit migration gates. The safe cut is passive intent tracing first, then the
CPU/VRAM-DMA lease latch, then actual PPU port intents. Promotion additionally requires deriving the
semantic grant inputs from persistent CPU/PPU strobes and deleting the startup profile tables. The
electrical collision and copy sequencers stay in production until each named profile has an oracle.

## Existing positive controls

The architectural diagnosis is selective. The DMG-facing part of `Joypad` already has the desired
shape: four packed sample stages per P10-P13 line, one retained filtered level, and a falling-edge
request. Its remaining size is mostly host-input ownership, deterministic replay, debugger hooks,
fast paths, and the separate SGB ICD2 packet protocol. `InfraredPort` is similarly a small stored RP
output feeding a combinational input mux; its external Full Changer protocol is behavioral device
logic. Neither needs to be rewritten into a general gate graph.

These are useful controls for the proposal. A large source file is not itself evidence of a bad
hardware abstraction, and behavioral algorithms are not targets merely because they live in
`core/`. The migration should focus on places where one subsystem predicts, repairs, duplicates,
or reorders another subsystem's time.

## Current migration decision

The experiments produce bounded candidates for the Clocked Signal Fabric hypothesis, but do not yet
establish a simpler whole-core model. They also move the first safe cut boundary. Do not next
centralize one interrupt source inside `Gameboy.tickSubsystems()`. First introduce:

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
