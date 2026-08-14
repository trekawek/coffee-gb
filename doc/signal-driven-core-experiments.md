# Signal-driven core: experimental results

Status: overnight architecture spike on `codex/signal-fabric-night`

Companion design: [signal-driven-core.md](signal-driven-core.md)

This log distinguishes three kinds of evidence:

- **silicon topology**: derived from the DMG schematic/netlist or gate-level simulation;
- **production differential**: equivalent to Coffee GB at its current callback boundaries; and
- **architecture falsifier**: a test that rejects an apparently convenient migration step.

Passing a production differential is not proof of silicon behavior. Conversely, a schematic-shaped
model is not ready for production until it also preserves Coffee GB's hardware-verified ROM results,
save states, debugger boundaries, and performance.

## Summary

| Hypothesis | Result | Consequence |
| --- | --- | --- |
| Serial DIV-reset timing is a forecast problem | Rejected | The old arithmetic reduces exactly to a local ripple-stage fall and output-clock transition. This replacement is in production code on the branch. |
| Timer overflow needs an explicit 4/8-tick state machine | Rejected for DMG topology | Two sampled latches around TIMA bit 7 generate overflow detection, reload ownership, cancellation, and the request edge. |
| Timer/serial acknowledgement can be centralized inside the current master-tick loop | Falsified and rolled back | Timer runs before CPU while serial runs after it; no placement of one central callback preserves both physical windows. A unified CPU-edge/half-dot island is prerequisite. |
| Java evaluation order can be made unobservable | Supported | A detached DRIVE/RESOLVE/COMMIT scheduler is invariant across every permutation tested. |
| CPU opcode lookahead is intrinsic to accurate races | Rejected at the bus-cycle seam | Persistent T1-T4 state exposes the in-flight address, strobes, data, held byte, and acknowledge wire. The current one-M-cycle-late read anchor remains a deliberate migration debt. |
| HDMA must decode opcodes and query future CPU/PPU state | Rejected at the request/grant seam | Retained lease cells and explicit bus intents reproduce representative arbitration without opcode knowledge; electrical and handoff profiles remain named blockers. |
| One generic held bus explains all collisions | Falsified | Low-dominant held lines are useful primitives, but VRAM, OAM, cartridge/WRAM, and I/O need distinct grant and receiver topologies. |
| DMG STAT behavior needs a large mode/line exception tree | Rejected for the ordinary raster/control plane | Independent mode, gate, LY, comparator, M2-enable, source-level, and IF latches match a complete steady frame. Several CPU/renderer boundary cases remain explicit falsifiers. |
| The OAM bug is fundamentally a Boolean corruption formula | Partly rejected | Sticky word lines, bit-line precharge, column sharing, and keeper feedback generate row copy and the majority truth function. The coarse SRAM model does not yet reproduce every preserved column. |
| APU frame clocks require an eight-step controller | Rejected for the selected DMG clock cone | Sampled divider and ripple latches generate length, sweep, and envelope pulses. CGB tap selection and two production adapters remain external profile rules/falsifiers. |
| Pulse-channel quirks require semantic trigger/sweep/length branches | Rejected for settled DMG control | Parallel-load priority, transparent clock gates, a feedback check strobe, and one reset-dominant status latch generate the tested length, sweep, envelope-load, and enable behavior. Active serial-adder and envelope-write timing remain outside the model. |
| The four-dot PPU skew requires two independently running renderers | Rejected for the bounded DMG pixel path | One forward address/data/FIFO/scanout graph reproduces sampled-byte timing, calibrated OBJ stalls, OBJ abort, and retained window insertion without rewind, reread, refresh, or catch-up. Several output and overlap cones remain explicit falsifiers. |
| CGB speed switching requires timer phase repair and tail-duration tables | Plausibly rejected, not yet proved | Explicit STOP-entry clocks, a gated switch sequencer, and a release phase ring fit the verified durations and remove the local repair in a detached model. Existing post-switch DIV tests cannot distinguish gated DIV from a free-running counter that wraps to the same value. |
| Full DMG gate simulation is impractical even as a development oracle | Rejected | The external `dmg-sim` model was compiled and ran its source OAM-bug program to self-termination, producing an inspectable FST waveform and SRAM dump. It remains an offline, DMG-only oracle. |

## Serial: a successful production simplification

The previous DIV-reset path computed a future toggle with phase adjustment arithmetic. Exhaustive
enumeration showed that it is exactly equivalent to the local circuit action:

1. clear the eight-bit serial divider;
2. observe whether the selected stage immediately below the output-clock flip-flop fell;
3. toggle that flip-flop on the fall; and
4. shift only when the output transition is falling.

The retained `SerialPort` implementation uses `UnsignedRippleCounter` and `EdgeDetector`; it no
longer predicts clocks-to-next-toggle. All divider phases, both internal clock periods, both old
output levels, save/restore, debug callbacks, and the optimized idle path were exercised. The full
unit and ROM battery run after the change remained green, and a baseline/branch throughput sample
showed no measurable median regression.

This is the desired shape: an apparent timing quirk disappeared when represented as the transition
of the stage that physically owns it.

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

This supports the central architectural claim: simultaneous old-state drive plus one resolved
signal vector is enough to remove Java callback order from the result.

## CGB speed-switch clock topology

`CgbSpeedSwitchClockMachine` explores a separate CGB clock profile on the half-dot lattice. The
candidate has three retained pieces of control state: an explicit eight-T-state STOP-entry
sequencer clocked from the destination source, a gated 17-bit switch-delay ripple chain, and a
free-running three-bit fixed-domain release ring. PPU, the APU oscillator, and HBlank DMA remain on
the fixed branch. CPU execution, DIV/timer, serial, and OAM DMA are on the selected branch and are
gated during the long delay.

This topology makes the normal-to-double timer adjustment local. Starting before STOP's last bus
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

Nine candidate tests, a 51-test focused production differential, and all nine Daid captures pass.
This remains a topology hypothesis rather than recovered CGB silicon. Public descriptions of an
8200-T interval conflict with the empirically calibrated 17-bit delay; the two- and eight-dot tails
fit a three-bit phase ring but do not identify its wiring; reverse entry is unverified; and the
HBlank/OAM-conditioned tail adjustments require the retained DMA grant cells. A production cut
would have to save router, entry, delay, and release phases and obtain an independent mid-pause
hardware observation before changing DIV/APU behavior.

## Persistent CPU bus cycles

`CpuBusCycleMachine` is a detached T1-T4 sequencer on the half-dot lattice. The decoder supplies a
cycle description once; address, RD, WR, driven data, sample/commit, held-bus data, and one-hot IRQ
acknowledge then remain observable signals for the lifetime of that cycle. Scripted NOP, LD, LDH,
PUSH, POP, interrupt, HALT, DI, and EI/HALT paths match the production CPU's terminal accesses.

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

## Timer overflow topology

The DMG gate model reduces the current flattened overflow timeline to two 1 MHz-edge latches:

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

An independent topology test exhaustively sweeps TIMA values and write/reload phases. A separate
production differential covers all TAC taps, legal DIV phases, TIMA/TMA writes, reload ownership,
debug delay, and IF state. The differential proves equivalence at today's callback boundary, while
the topology test remains the silicon-shaped oracle. Production migration is intentionally
deferred until T4/BOGA, CPU write strobes, and the shared IF latch are first-class signals.

## APU clock topology

`ApuClockSignalIsland` transcribes the selected DMG clock cone: AJER toggles, COKE clocks BARA's
sample of the divider tap, and a BARA edge ripples through CARU and BYLU. The exposed normal-path
signals reduce to `HORU=!BARA`, `BUFY=CARU`, and `BYFE=CATE=BYLU`. One final channel-local divide
phase distinguishes the two otherwise identical low/low phases. Together these latches generate
the complete eight-step length/sweep/envelope pulse vector without an eight-way step table.

Natural edges and CPU-phase-aligned FF04 resets match `FrameSequencer` and representative
`LengthCounter` behavior for DMG, CGB normal, and CGB double speed. The experiment deliberately
labels what the DMG cone does not establish: CGB's bit-13 tap mux, its boot-only PSG divider offset,
the speed-switch commit phase, the production final-four-clock NR52 adjustment, and suppression of
the first falling edge after powering on with the tap high. The last two are executable falsifiers:
the selected gate cone emits step 0 where production suppresses it or injects step 1. They require
a wider reset/power cone or must remain board-profile adapters; hiding them in the ripple island
would be another special-case layer.

## DMG pulse-channel control topology

`Pulse1GateTopology` asks whether the stable results of length, envelope, sweep, trigger, and
channel-enable behavior need feature-level branches. Its inputs are register strobes, frame clocks,
and old latch state; its outputs are load, calculation, writeback, overflow-check, and stop pulses.
It deliberately does not pretend that a whole sweep calculation is instantaneous.

The following behaviors emerge from ordinary cell connectivity:

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

The differential exhausts 2,080 length/enable/trigger/frame-phase combinations, all 256 NR12
trigger loads through forty envelope clocks, 1,152 initial sweep profiles, and live NR10 rewrites
across representative frequencies. It also checks settled `SoundMode1` status. This is not yet a
production replacement: observation during the 1 MHz serial calculation, active retrigger at its
reload edge, active NR12 write behavior, frequency/duty phase, power/reset wiring, CGB restart
hold, and analog DAC transients are named falsifiers. The next useful cone is the serial adder
itself, not another settled-result condition.

## DMG STAT/control topology

`DmgStatControlPlane` is a detached raster/control island. It accepts only the terminal pixel dot
`E`; it is not a renderer. Its state is split into physical responsibilities:

- readable mode latch;
- independent OAM-read, OAM-write, VRAM-read, and VRAM-write gates;
- LY counter, registered LY, readable coincidence, and a retiring comparator contribution;
- eight-dot M2 strobe and an FF41.M2 enable sampled on its leading edge;
- internal VBlank source independent of readable mode;
- M0 source at `E+4`;
- one shared STAT level, rising-edge detector, and IF latch.

The sampled M2-enable latch is especially useful. A later STAT write cannot retroactively arm the
current pulse, so ordinary/frame-start STAT-write blocking and HBlank-to-OAM handoff behavior no
longer require raster-specific write masks. The transient all-enabled DMG write is one input vector.

The model matches 70,224 consecutive observations in a complete post-constructor steady frame for
readable mode, visible LY, coincidence, internal M1, OAM read/write gates, and VRAM read gates. It
also passes directed line-153, LCD-enable, source-handoff, and write-glitch tests.

Unresolved boundaries are deliberately named rather than hidden in the raster island: constructor
phase, physical `E+4` M0 versus production's earlier prediction signal, mid-transfer window cancel,
divergent renderer/control tails, terminal WX166/X167 reads, HALT read muxing, and central IF
acknowledge. Those belong at pixel-pipeline, CPU-readable-mux, or interrupt-synchronizer boundaries.

The external full-gate waveform independently supports the split. After LCD enable, raw FF44, the
two readable FF41 mode bits, and coincidence change on distinct propagation boundaries rather than
as one atomic mode/line transition. Brief combinational hazards are also visible in the raw vector.
Those observations should be represented by the owning latches and receiver sample edges, not by a
larger `Mode` enum or by publishing every gate hazard as a CPU-visible event.

## Forward-only DMG pixel path

`ForwardDmgPixelPipeline` is a bounded datapath experiment with three coordinates: tile-fetch X,
FIFO-pop X, and LCD X. One tile flight samples its low byte at launch, its high byte three dots
later, reaches the FIFO at dot four, and produces an immutable raw token which crosses a three-dot
scanout. An object fetch gates future FIFO pops but cannot stop or modify scanout tokens already in
flight.

That small spatial graph reproduces the current calibrated sprite costs for every background-fetch
phase (`11,10,9,8,7,6,6,6` dots) and chained same-X sprites. More importantly, several present repair
paths disappear:

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

Thirteen graph tests plus the production sprite, SCX, and window timing fixtures pass. The graph
refuses a mid-line fine-SCX rephase after raw popping has begun and expires a window trigger once
its matching token crossed the irreversible LCD boundary. Disabled-window insertion glitches,
palette/LCDC output muxing, overlapping object priority, mode-3/STAT completion, and all CGB paths
remain explicit boundaries. This is the desired failure mode: add a derived latch or stage only
when evidence identifies it; never recover a consumed pixel.

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

A second full run enabled the source program's `PUSH BC` corruption with `SP=FE01`. It
self-terminated with rows 1 and 2 both equal to row 0, while every later byte remained intact.
Instrumenting cell writes exposed a stronger result: neither OAM SRAM's `wr` input asserted during
the corruption. At time 33699216 the row-1 word line rose while all four bit lines still held row
0, and the ordinary read-feedback rule rewrote every differing cell in row 1. At 33700192 the row-2
word line rose before the retained bit lines were cleared and repeated the copy. No encoded PUSH,
row-copy, or corruption equation exists in the simulator.

This directly falsifies a model in which a forbidden CPU write invokes an SRAM corruption
operation. The physical event is a disturbed address/precharge/read sequence; writing the cells is
what this dynamic read port normally does when its retained lines and newly selected word line
disagree. Comparison with real DMG captures is still required before claiming that the generic
simulator's exact delay policy is silicon-accurate.

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

`DmaRequestGrantTopology` separates four responsibilities that are currently interleaved in
`Gameboy.tick()` and the HDMA state machine:

1. a three-cell PPU-to-CPU HBlank synchronizer;
2. retained request, CPU/VRAM-DMA lease, and late-interrupt-eligibility cells;
3. OAM and VRAM DMA sequencers which emit bus intents in their own clock domains; and
4. a pure same-cycle resolver followed by a write-only commit edge.

The CPU publishes claim, interrupt, retire, or relinquish signals. HALT is therefore a relinquish
wire, not opcode `0x76`; DMA never fetches or decodes an instruction. The PPU publishes actual OAM
and VRAM port-use intents rather than a visible mode or a retiring-instruction exception.

The detached model matches all 160 OAM copy edges at normal and double speed, including a mid-copy
speed change; all sixteen VRAM source strobes and the block commit for GDMA and HBlank DMA across
speed/parity profiles; the ordinary three-edge HBlank synchronizer; representative CPU-retire,
late-interrupt, and frame-start grants; DMG's merged main bus versus CGB's split cartridge/WRAM
wires; and the shared source-mux collision where VRAM DMA redirects an OAM copy. All 120 orderings
of a four-master intent set resolve identically. A simultaneous CPU and VRAM-DMA execution intent
is reported as a handshake falsifier, because a valid lease makes it impossible; assigning a
priority would conceal the bug.

The topology does not pretend digital ownership explains every case. HALT-wake level history,
STOP/speed-switch reverse phase, terminal or overlapping HBlank requests, HDMA disable/LCD-off
handoffs, OAM restart/acquire history, partial address decoding, CGB's WRAM alias, delayed interrupt
stack collisions, dynamic OAM write corruption, invalid-source open-bus decay, and VRAM block
visibility remain explicit migration gates. The safe cut is passive intent tracing first, then the
CPU/VRAM-DMA lease latch, then actual PPU port intents. The electrical collision and copy
sequencers stay in production until each named profile has an oracle.

## Current migration decision

The experiments strengthen the Clocked Signal Fabric hypothesis, but they also move the first safe
cut boundary. Do not next centralize one interrupt source inside `Gameboy.tickSubsystems()`. First
introduce:

1. persistent CPU T1-T4 bus-cycle state;
2. a half-dot/CPU-subedge scheduler;
3. Timer, Serial, CPU interrupt control, and IF latches in one resolve/commit island; and
4. only then delete both peripheral acknowledgement forecasts together.

For the PPU, use the detached STAT topology as a shadow control plane behind three explicit seams:
the raster/pipeline control island, the CPU-readable mode/LY mux, and the central interrupt island.
Do not pull CPU read-phase exceptions into the raster model.

The acceptance criterion remains deletion. A production slice is successful only when it removes
prediction, provenance, rollback, refresh, duplicated time, or source-specific scheduling state
while retaining the hardware-verified test battery.
