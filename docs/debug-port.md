# Debug port contract and performance measurement

## Scope

This document specifies the debugger foundation, CPU and peripheral breakpoint/trace contracts,
bounded reverse execution, and coherent inspection seam implemented by `DebugPort`. The API is
platform-neutral and exposes
immutable values only. It does not expose a mutable core component, an AWT/Swing type, a live
array, or a reflection escape hatch. The model is still an internal Coffee GB API until a later
change explicitly versions it for third-party use.

The port supplies pause/resume, coherent snapshots, negotiated stepping, bounded side-effect-free
memory reads, one-byte paused RAM writes, deterministic button input, negotiated breakpoints,
all ten typed trace categories, selective peripheral instrumentation, and the debug-port-backed
console described below. The separate deterministic recorder/player and `CGBR` v1 contract are
documented in [replay-format-v1.md](replay-format-v1.md). Explicitly enabled checkpoint history
supports reverse-frame and replay-forward reverse-instruction operation with a retained-future
cursor and branch invalidation. PC/SP-relative and explicit memory copies, optional physical
graphics/APU payloads, semantic hardware/I/O state, and bounded trace pages share one
owner-safe-point inspection.

The Swing user interface built on this contract is documented separately in
[desktop-debugger.md](desktop-debugger.md), including pane identity, keyboard controls, trace
opt-in/loss reporting, persisted display preferences, and retained-state release.
The non-Swing automation boundary is documented in [headless-cli.md](headless-cli.md); it uses the
same owner-thread safe points and immutable inspection requests, never direct core access.

## Session publication and capabilities

The desktop controller publishes a `Controller.SessionDebugPortEvent(generation, port)` only after
a session has committed. A later event with the same generation and a null port revokes it. A
single-player session publishes the functional port. A linked rollback session publishes a typed
port with no capabilities; every operation returns `UNSUPPORTED_TOPOLOGY` because no one-machine
mutation can safely represent the linked owner. A client must retain the generation with every
view and discard a port as soon as its revocation event arrives.

Capabilities are immutable for one generation and must be negotiated before submitting the
commands they cover. Current implementation availability is:

| Operation | Desktop `BasicController` | Headless Agent | Linked rollback |
|---|---:|---:|---:|
| pause/resume | yes | yes | no |
| snapshot | yes | yes | no |
| instruction step | yes | yes | no |
| machine-cycle step | no | yes | no |
| frame step | yes | yes | no |
| memory read | yes, at most 4096 bytes | yes, at most 4096 bytes | no |
| memory write | yes, one paused RAM/HRAM byte | same | no |
| coherent inspection | yes, 16 blocks / 4096 aggregate bytes | same | no |
| graphics/audio/hardware inspection sections | all three | all three | no |
| audio channel enable/mute | yes, output-only | no | no |
| trace page in coherent inspection | at most 1024 entries | same | no |
| button input | yes | yes | no |
| breakpoints | yes, 7 kinds / at most 128 | yes, 7 kinds / at most 128 | no |
| trace | all 10 `TraceCategory` values / at most 65,536 entries | same | no |
| trace read | at most 1024 entries | at most 1024 entries | no |
| checkpoint history | yes, 1-7200 frames / 8-512 MiB | no | no |
| reverse frame | yes | no | no |
| reverse instruction | yes, for eligible isolated topologies | no | no |

`setAudioChannelEnabled` is a `BasicController`-only extension without its own
`DebugCapabilities` flag. Audio inspection support does not imply channel-control support; the
Headless Agent and linked ports return a typed unsupported result.

`DebugStepKind.MACHINE_CYCLE` is part of the shared model, but the desktop controller returns
`UNSUPPORTED_STEP` for it. A client must not emulate an unsupported granularity by issuing direct
core calls.

## Ownership, queueing, and result delivery

Every machine mutation runs on the session's emulation-owner thread between calls to
`Gameboy.tick()`. Producers such as Swing, a console, or a test receive only `DebugPort`; they add
immutable command data and receive a `CompletionStage<DebugResult<T>>`. They cannot use the port to
run code on the owner or obtain the live `Gameboy`.

The desktop transport is multi-producer and executes owner commands FIFO by admission ID. Its local
capacity is 64 outstanding requests. Capacity includes commands still in the queue, commands
already polled by the owner, and completed results whose synchronous client continuations have not
returned. A process-wide completion service adds a 256-request cap across current and replaced
generations, so permanently blocked clients cannot leak an unbounded series of callback threads.
The headless adapter likewise admits at most 64 debug requests.

A full queue returns `QUEUE_FULL`; it never blocks the emulation thread and never grows. The
desktop owner handles at most 64 commands at one safe point, then returns to its normal work.
Expected failures complete normally as `DebugResult.failure`; clients must inspect the result
rather than treating exceptional completion as the command protocol.

Desktop completions run on bounded daemon result workers. Delivery is correlated by each returned
stage and may be concurrent even though owner command execution is FIFO. The worker bound matches
the global admission bound, so one continuation can wait for another already-admitted result
without starving its delivery; dependency cycles remain client errors. Headless completions are
also dispatched away from its owner. Consequently, an ordinary synchronous continuation never
runs on the emulation thread. A client callback may delay release of its own admission slot, but it
cannot delay guest execution.

The desktop debug lane is distinct from the ordinary application/state event queue. Pause
ownership changes may run at a completed-tick safe point. A queued lifecycle control instead
finishes the current partial frame, then the ordinary queue runs in producer order at its normal
boundary; earlier state/persistence work is never overtaken by a later stop or replacement. This
keeps lifecycle and close preparation responsive without weakening frame-boundary ownership.

## Coherent safe points and counters

A safe point is the boundary after one complete `Gameboy.tick()` and before the next. No snapshot
or command observes a partly updated CPU/peripheral tick. All fields in a `DebugSnapshot` come from
that one boundary and share its session generation, snapshot sequence, master tick, frame, and
frame position. `snapshot()` does not pause the machine; it captures one coherent boundary and the
owner may continue afterward.

Within a desktop generation:

- `sequence` increases for every captured snapshot, including snapshots returned by another
  command;
- `masterTick` counts completed `Gameboy.tick()` calls executed through the debug-aware path;
- `frame` counts controller frame-lattice boundaries;
- `framePosition` is the number of completed ticks since the last such boundary and is zero at a
  boundary;
- `execution.retiredInstructions` is a debugger-attachment-local retirement sequence, not a
  portable machine counter.

These counters reset for a new session generation and are deliberately absent from save states and
rewind snapshots. Clients must not compare counters from different generations or merge component
values from snapshots with different sequences.

Submitting a plain memory read, snapshot, or coherent inspection while the machine is running is
safe because the owner performs it between ticks. The returned value is immutable, but it may
become stale immediately;
clients should label it with its generation/sequence rather than combining it with a later view.
Mapper banks use `-1` and mapper feature flags use `DebugFeatureState.UNKNOWN` when the concrete
mapper has no pure inspection seam; clients must not interpret an unknown value as disabled.

## Typed peripheral inspection

`DebugInspectionRequest` combines four independent optional inputs: PC/SP-relative memory ranges,
explicit memory ranges, the `GRAPHICS`/`AUDIO` section set, and one `TraceReadRequest`. The owner
captures its `DebugSnapshot`, every requested memory and peripheral payload, and the optional trace
page before another guest tick can run. The result presence exactly matches the request; clients
must still correlate the returned snapshot generation and sequence.

The existing caller-selected memory limits remain 16 blocks and 4096 aggregate bytes. Fixed
peripheral payloads do not consume that budget. They are available only through negotiated
`inspectionSections`, and the trace page is separately bounded by
`maxInspectionTraceEntries` (currently 1024). Trace capture must already have been configured;
requesting a page does not enable categories or add producer hooks.

`DebugGraphicsInspection` identifies DMG, CGB compatibility, or native CGB operation and copies
VRAM bank 0, the physical second bank when present, OAM, the DMG palette registers, and CGB BG/OBJ
palette RAM plus their index registers. Palette RAM is 32 little-endian RGB555 values per bank.
`DebugAudioInspection` copies the next frame-sequencer step, NR50-NR52, four ordered channel
records, and all 16 wave-RAM bytes. Each channel includes its five-address NRx0-NRx4 window,
enabled/DAC state, digital output, and length counter/enable state; the unused CH2/CH4 NRx0 slots
are zero. These values come directly from physical GPU/APU storage and therefore neither obey CPU
VRAM/OAM/wave-RAM visibility restrictions nor trigger read side effects. Large byte payloads use
immutable indexed `DebugByteData`, never a live core array.

## Pause and instruction retirement

On the desktop, pausing an actively executing CPU targets the next retirement and stops immediately
after it. The bounded wait is one profile-specific controller frame. Failure to reach a retirement
returns `STEP_LIMIT` at a completed-tick boundary without acquiring a debugger-owned pause. If the
application is already paused or the CPU is halted, stopped, switching speed, or locked, pause
takes effect immediately without waiting for a retirement.

Both the desktop and headless owners process pause/resume between ticks and report
`ALREADY_PAUSED` or `ALREADY_RUNNING` for a redundant debugger-owned transition. A desktop
application pause is a separate owner: stepping is permitted while it is active and acquires a
debugger-owned pause for the result, while `resume()` releases only the debugger's pause. The
returned snapshot can therefore remain paused if the application owner still holds its pause.

An instruction retirement is defined at the CPU's architectural completion boundary:

- an ordinary or CB-prefixed instruction retires once after all operands and operations complete;
- `HALT` and `STOP` each retire once when they enter their resulting idle/speed-switch state;
- the HALT-bug path still gives `HALT` exactly one retirement, and the anomalous following fetch
  belongs to the following instruction;
- an accepted interrupt entry is a separate retirement at the final vector-selection cycle, after
  its waits and stack pushes;
- an illegal opcode retires once as the CPU enters `LOCKED`, then cannot retire again.

An instruction step that starts in `HALTED`, `STOPPED`, or `SPEED_SWITCH` fails with `CPU_IDLE`;
it does not wait indefinitely for an external wake-up. A step starting in `LOCKED` fails with
`CPU_LOCKED`. If the stepped instruction itself enters one of those states, the instruction has
already retired and the result reports that completed boundary. The desktop reports
`INSTRUCTION_RETIRED` for `HALT`/`STOP` entry and `CPU_LOCKED` for lock entry; the headless adapter
uses `CPU_IDLE` as the stop reason when the completed instruction entered an idle state. In all
cases the final snapshot is authoritative.

If a step begins during interrupt wait/push/jump, the interrupt entry's final vector cycle is the
one retirement it awaits. If it begins before an ordinary instruction that is followed by
interrupt acceptance, it stops at that instruction's retirement; the interrupt-entry retirement
is available to the next step. `ticksExecuted` counts completed `Gameboy.tick()` calls and
`instructionsRetired` reports the attachment-local sequence delta.

## Breakpoints, watchpoints, and automatic stops

Breakpoint IDs are non-negative and assigned by the client. `setBreakpoint` installs a new
definition or replaces the definition with the same ID; disabled definitions remain listable but
do no matching. `removeBreakpoint` does not silently accept an unknown ID. Desktop and headless
sessions retain at most 128 definitions and currently negotiate these condition kinds:

| Kind | Condition and observation point | Stop boundary |
|---|---|---|
| `PROGRAM_COUNTER` | Inclusive PC range observed on the real instruction fetch. | Retirement of that instruction. |
| `MEMORY` | `EXECUTE`, `READ`, or `WRITE` in an inclusive address range, optionally constrained by `(observed & mask) == (value & mask)`. | The completed master-tick containing the bus access, including accesses while the CPU is idle. |
| `OPCODE` | Exact base-table or CB-prefixed opcode observed on the real fetch. | Retirement of that instruction. |
| `INTERRUPT` | Exact interrupt at final acceptance, after late priority selection. | Retirement of the accepted interrupt entry. |
| `PPU_STATE` | Exact match on any non-empty combination of owner frame, LY, and PPU mode, observed on PPU transitions and owner frame boundaries. | The completed master-tick or owner frame boundary carrying the matched state. |
| `SERIAL` | `TRANSFER_STARTED` with the outgoing SB byte or `BYTE_TRANSFERRED` with the final received SB byte, optionally constrained by an exact or masked byte value. | The completed master-tick carrying the serial edge. |
| `COUNTER` | Exact master-tick or owner frame-counter value. | The completed tick or frame boundary carrying that value. |

Instruction-only PC/opcode/interrupt hooks do not install the CPU address-space wrapper. The
wrapper is active only for an enabled memory breakpoint or memory trace, and each observed bus
operation still delegates exactly once. A read condition sees the final byte returned to the CPU,
while a write condition sees the attempted byte and never performs a debugger readback. `EXECUTE`
covers actual opcode and operand fetches; side-effect-free debugger reads and speculative
disassembly are deliberately invisible. The current memory condition has no producer or named
address-space field, so watchpoints deliberately remain scoped to the CPU's logical `SYSTEM_BUS`
observations. Physical DMA and PPU accesses are available through `MEMORY` trace with explicit
source and address-space provenance, but the API does not expose them as memory-watchpoint inputs.

`DebugPpuCondition` uses the same controller-owned `frame` counter returned by `DebugSnapshot`, not
the physical `PpuTrace.ppuFrame` counter. `ANY_FRAME`, `ANY_LY`, and a null mode are explicit
wildcards, but at least one field must be constrained. A combined predicate is equality-based and
is reevaluated only when the PPU reports a transition or the owner reports a frame boundary; it is
not level-triggered on every master tick. Attachment and timeline restore align the observed PPU
state silently, so an old level cannot create a synthetic hit.

A serial condition selects either transfer start or completed byte. Its unconstrained form matches
every selected edge; its constrained form compares `(observed & mask) == (value & mask)`, with the
exact-byte constructor using `FF` as the mask. Transfer start observes the outgoing SB value after
SC starts the transfer. Completion observes the final received SB value after the eighth shift and
after the port has finalized its completed state.

A hit is completed only at a coherent safe point, acquires the debugger pause, and records a
`DebugBreakpointHit` containing the breakpoint ID, the immutable definition that matched, the tick
of the observed match, and the final snapshot. Capturing the complete definition keeps the stop
explanation stable when that ID is later edited, removed, or reused. `activePause` is true while
that automatic stop still owns the current debugger pause. It becomes false before the next guest
tick or after any successful state discontinuity, including resume, forward or reverse stepping,
direct Agent ticking, lifecycle-driven partial-frame completion, user rewind, and state or snapshot
restore. Zero-tick requests, snapshot and inspection recapture, and rejected restores do not change
it. A new breakpoint during movement replaces the historical hit with a new active one. The
stopping snapshot itself remains paused and immutable. The compatibility three-argument
constructor represents legacy ID-only hits with an empty definition and defaults `activePause` to
true; owner implementations use the definition-carrying constructor.

A PC or opcode observation can precede its final snapshot by the remaining ticks of that
instruction. A memory observation stops after its enclosing master tick, so its snapshot may
describe a CPU partway through the instruction; this also guarantees that STOP-time bus polling
cannot strand a watchpoint waiting for a retirement that never occurs. `lastBreakpointHit` returns
the most recent automatic stop, including historical hits, and reports `NO_BREAKPOINT_HIT` before
the first one.

The first matching observed event before a stop wins. If several definitions match that same event,
the lowest numeric ID wins, independent of insertion order. Editing or removing a definition
cancels an observation still pending for that ID. An automatic hit preempts a pending pause,
instruction step, or frame step; a preempted step completes normally with
`DebugStepStopReason.BREAKPOINT` and the hit snapshot. Breakpoint handling executes no extra guest
tick after the reported safe point. A separately queued explicit lifecycle action may still
supersede the pause on a later owner iteration so stop, replacement, and close work cannot starve.

## Bounded trace capture

Each session owns one owner-thread-confined overwrite ring. The desktop and headless adapters
negotiate all ten `TraceCategory` values, a capacity from 1 through 65,536 entries, and at most
1024 entries per port read. An empty category set disables capture. A configuration still has to
be a subset of the session's advertised category set; otherwise it returns
`UNSUPPORTED_TRACE_CATEGORY`.

Every `TraceEntry` owns a typed immutable payload and separately records the component that
produced it in `source`. Producer provenance is not duplicated in the payload. The payload
and ordering contract is:

| Category | Payload and source | Observation and deterministic order |
|---|---|---|
| `CPU` | `CpuInstructionTrace(programCounter, opcode, prefixedOpcode)` from `CPU`; `prefixedOpcode` is `-1` except for a CB instruction. | Appended at architectural retirement, after that instruction's bus observations. For `RETI`, the CPU entry precedes the correlated interrupt `COMPLETED` entry. |
| `MEMORY` | `MemoryAccessTrace(addressSpace, access, address, value)` from `CPU`, `DMA`, or `PPU`. CPU observations use `SYSTEM_BUS`; DMA and PPU observations name the physical ROM/RAM/VRAM/OAM/I/O view involved. | Appended after the one real delegate access. Reads/executes carry the returned byte; writes carry the attempted byte without a debug readback. Events retain producer bus order. |
| `INTERRUPT` | `InterruptTrace(kind, interrupt)`; `REQUESTED`/`CLEARED` come from `INTERRUPT_CONTROLLER`, while `ACCEPTED`/`COMPLETED` come from `CPU`. | A newly asserted line produces `REQUESTED`; clearing IF produces `CLEARED`; final late-priority selection produces `ACCEPTED`; the matching, potentially nested, `RETI` produces `COMPLETED`. A peripheral transition that requests an interrupt is appended before its `REQUESTED` event. |
| `PPU` | `PpuTrace(kind, ppuFrame, line, dot, mode)` from `PPU`, with `LCD_ENABLED`, `LCD_DISABLED`, `SCANLINE_STARTED`, `MODE_CHANGED`, and `FRAME_READY`. | LCD edges are explicit. At the VBlank line boundary the order is `SCANLINE_STARTED`, `MODE_CHANGED`, then `FRAME_READY` on the same tick. `ppuFrame` is the physical frame-ready count from machine construction, independent of owner `frame`, and continues while debug hooks are detached. |
| `DMA` | `DmaTrace(engine, kind, sourceAddress, destinationAddress, length, bytesTransferred)` from `DMA`; engines are OAM, general VRAM, and HBlank VRAM DMA. | `STARTED` precedes transfer work. Each committed byte is ordered physical source read, physical destination write, `BYTE_TRANSFERRED`; `COMPLETED` follows the final byte. Restart/cancel emits `CANCELLED` with completed progress before a new `STARTED`. General VRAM DMA may read its source burst before the atomic destination commit, but preserves the same per-commit write/progress order. |
| `TIMER` | `TimerTrace(kind, divider, counter, modulo, control)` from `TIMER`, sampled after the named transition. | Falling-edge effects precede the `DIVIDER_RESET` or `CONTROL_CHANGED` that caused them. Overflow is followed by delayed reload, and `COUNTER_RELOADED` precedes the timer interrupt `REQUESTED`. |
| `SERIAL_IR` | `SerialIrTrace(endpoint, kind, value)` from `SERIAL` or `INFRARED`. Serial kinds are `TRANSFER_STARTED`, `BIT_SHIFTED`, and `BYTE_TRANSFERRED`; IR uses `SIGNAL_CHANGED`. | Serial order is start, eight post-shift byte snapshots, then completion; the last `BIT_SHIFTED` precedes `BYTE_TRANSFERRED`, which sees finalized SB/running state and precedes the serial interrupt `REQUESTED`. IR emits once per effective signal change; bit 0 is local LED output and bit 1 is received physical light. |
| `INPUT` | `InputTrace(kind, buttonMask, changedMask)` from `INPUT`; bits use stable `DebugButton.ordinal()` positions. | One event follows each change to the effective union of input sources. `PRESSED` and `RELEASED` mean all changed bits moved in one direction; a mixed edge is `STATE_CHANGED`. Ownership changes that leave the union unchanged do not duplicate an event. |
| `MAPPER_RTC` | `MapperRtcTrace(kind, register, value)` from `MAPPER` for bank/enable changes and from `RTC` for selection, latch, read, and write operations. | An entry follows the accepted mapper or RTC operation, retaining actual mutation/read order. Current concrete producer hooks cover MBC1, MBC3, and MBC5; mapper implementations without an explicit hook remain silent rather than guessing state through reflection. |
| `APU` | `ApuTrace(kind, channel, register, value)` from `APU`; not-applicable fields are `-1`. | Only accepted register writes emit `REGISTER_WRITTEN`, before a resulting `CHANNEL_TRIGGERED` or real `CHANNEL_DISABLED` transition. `FRAME_SEQUENCER_STEP` precedes the channel effects caused by that step. Repeated writes that do not change enabled state do not invent disable edges. |

Hook attachment, state restore, and detached-state alignment never emit trace events. Within an
emulated tick, callbacks append synchronously in the mutation order above; the entry sequence, not
component type or payload timestamp, is the authoritative cross-category ordering.

`TraceFilter` provides inclusive CPU-PC and memory-address ranges plus accepted memory-access and
interrupt sets. Filtering occurs on the producer path before an immutable event or entry is
constructed. A filter affects the categories for which it has fields; it does not disable an
enabled category by itself. The memory predicate applies to CPU, DMA, and PPU trace observations
using each event's reported 16-bit address, but it has no source/address-space selector. Typed
peripheral categories other than interrupts are unfiltered once enabled. Category selection and
filter replacement are cold-path operations.

Every accepted event receives one monotonically increasing `sequence`; sequence order is
authoritative when several events share a master tick. `readTrace` uses an exclusive
`afterSequence` cursor (`-1` starts at the oldest retained entry) and returns an immutable page.
Readers do not register with or stall the producer. If a cursor falls behind an overwrite,
`missedEventCount` reports the cursor-specific gap, while `droppedEventCount` is the session's
cumulative overwrite/clear count. `oldestAvailableSequence` and `nextSequence` bound the retained
window, and `nextAfterSequence` can be used directly for the next request.

Reconfiguration atomically clears retained entries, preserves the monotonically increasing
sequence space, and adds the cleared entry count to `droppedEventCount`; this makes an old cursor
resume or report loss rather than wait on reused IDs. Desktop rewind likewise suppresses all
breakpoint and trace hooks while replaying restored state, clears pending matches and retained
trace entries with the same accounting, then reattaches the unchanged definitions/configuration.
Successful managed and legacy in-place state loads perform the same pending-match and trace
discontinuity reset around the restored machine. Rewind and state loading therefore cannot
synthesize delayed hits or splice trace events from unrelated timelines.

## Speed and frame semantics

`Gameboy.tick()` is the debugger's master-tick unit. In CGB double-speed mode the CPU can advance
twice as many CPU subcycles per master-tick, but retirement definition and snapshot coherence do
not change. `DebugExecutionState.doubleSpeed` records the sampled speed, and a faster CPU can make
an instruction or supported machine-cycle step require fewer master ticks. Debug metadata never
changes the emulated speed mode.

The desktop frame step targets the next boundary of the selected hardware profile's
`ClockSpec.controllerTicksPerFrame()` lattice. From `framePosition == 0` it executes one complete
controller frame; from a partial frame it executes exactly the remaining ticks. It completes with
`framePosition == 0`, increments `frame`, and returns `FRAME_BOUNDARY`. This boundary is defined by
the controller clock lattice, not by a host paint callback.

Desktop frame stepping continues ticking the machine while the CPU is `HALTED`, `STOPPED`,
`SPEED_SWITCH`, or `LOCKED`. The CPU remains idle or wakes according to ordinary emulated hardware
inputs and interrupts; peripherals advance according to core rules, and the retirement delta may
be zero. In particular, a speed-switch countdown is not skipped, and double speed does not shorten
the PPU/controller frame lattice.

The headless Agent instead negotiates a frame step to the next core frame-ready boundary. It has a
bounded one-emulated-second wait. If STOP/LCD state prevents a frame-ready boundary during that
window, it returns `STEP_LIMIT`. Clients must use capabilities and the returned tick count rather
than assume the desktop lattice applies to every `DebugPort` implementation.

## Bounded checkpoint history and reverse execution

Desktop reverse history is disabled by default and does no capture or retention work until a
client calls `configureHistory` with an enabled `DebugHistoryConfiguration`. The default enabled
configuration retains at most 1800 frame checkpoints and 64 MiB; the negotiated public ranges are
1 through 7200 checkpoints and 8 through 512 MiB. A valid configuration that exceeds a session's
negotiated capability fails before owner admission with `HISTORY_LIMIT`; structurally invalid
configuration values are rejected by the value model itself. Disabling uses the canonical
zero-budget configuration.

If history is enabled at a controller frame boundary, that current boundary becomes the initial
anchor. If it is enabled partway through a frame, capture begins at the next completed boundary.
Thereafter the owner captures exactly one `SessionSnapshot` at each completed controller-frame
lattice boundary. Snapshots use immutable 4 KiB pages with identity-based structural sharing. An
enabled history also records a deterministic, owner-thread transcript between anchors: historical
P1 event input, sampled physical P1-P4 input, instruction-retirement boundaries, and the cartridge
pause state needed by tick-driven RTC replay. Every anchor owns absolute input baselines so replay
does not sample the current keyboard/gamepad state before its first tick. Transcript storage is
bounded, owner-thread-only, and allocated only while history is enabled.

Both limits are strict. The oldest complete anchor segments are evicted until the checkpoint count
and total modeled retention fit; the byte total includes snapshot graphs, input baselines, and
input/retirement transcript storage. An input burst can therefore cause immediate memory-budget
eviction without waiting for the next frame. Reapplying even an equal enabled configuration resets
the history and reports `CONFIGURATION_CHANGED`.

`historyStatus` distinguishes retained anchors from the active historical position. `oldest` and
`newest` are the bounds of all retained frame checkpoints, including an original future preserved
after a reverse. Once an anchor exists, `cursor` is a
`DebugHistoryPosition(masterTick, frame, framePosition)` and can lie on that checkpoint or partway
between two checkpoints. `futureCheckpointCount` reports how many retained anchors follow it.
Status also carries the exact modeled retained bytes, checkpoint count, cumulative budget-eviction
count, and the most recent truncation reason.

`stepBackward(FRAME)` requires an effectively paused desktop session. At a frame boundary it
restores the preceding retained boundary. Partway through a frame it restores the newest retained
boundary, which is the start of that partial frame. The restore is direct and rollback-protected:
it executes no guest tick, advances no timing ticker, and emits no synthetic video or audio event.
MBC3 real-time clocks are re-anchored to the effective pause state after restore, so abandoned host
wall time is never charged to the restored timeline. HuC3 and TAMA5 direct checkpoint restoration
materialize elapsed host time only through the captured boundary before rebinding their anchors.
Speculative apply and rollback keep rumble output silent; after commit, one aggregate rumble event
is published only when the restored motor state differs from the live state it replaced.

`stepBackward(INSTRUCTION)` chooses the newest recorded retirement boundary strictly before the
cursor. Thus a cursor immediately after a retirement undoes that instruction, while a cursor after
idle HALT/STOP ticks returns to the last instruction that actually retired. Retirement metadata
lets the owner skip empty frames and select the target without speculative execution. If no such
boundary is reachable from a retained anchor, the command returns `HISTORY_EXHAUSTED`.

Instruction reverse restores the target frame's anchor in an isolated scratch session with the
same ROM and hardware profile, seeds its historical legacy and physical input baselines, and
applies the retained transcript before each scratch tick. It replays no more than one controller
frame to the selected retirement, captures the result, and applies that result to the live session
as one rollback-protected commit. MBC3 remains deterministic in this path because active execution
is tick-driven; scratch replay never charges host wall time.

The scratch session has no live presentation or host-service ownership. Its intermediate display,
audio, rumble, debug hooks, and breakpoint/trace observations cannot reach live subscribers. Audio
samples produced while reconstructing the target are discarded. Only the committed resulting
frame/state and the command's coherent `DebugSnapshot` become visible after success. A replay or
apply failure restores the complete live machine and serial state, leaves the cursor and retained
future unchanged, returns `INTERNAL_ERROR`, and keeps the emulation owner available. Transient
breakpoint/trace correlation is cleared after that rollback because it cannot be reconstructed
from portable machine state.

The live generation's `masterTick`, `frame`, snapshot sequence, and debugger retirement count stay
monotonic across a reverse. `DebugReverseStepResult.restoredPosition` is the historical cursor,
including its frame position; `replayAnchor` identifies the retained frame checkpoint used for a
direct restore or scratch replay. Its `snapshot` is the coherent post-commit view labelled with the
current live counters. The compatibility `restoredPoint()` accessor names that anchor and must not
be mistaken for an instruction target. Breakpoint definitions and trace configuration survive.
Pending breakpoint correlation and retained trace entries are cleared so observations from two
timelines cannot be spliced. The last hit survives as history with `activePause` false and
continues to describe its original stopping timeline and captured definition.

A successful reverse moves only the cursor. Checkpoints and transcript records after it remain
retained and continue to count against both budgets. `futureCheckpointCount` counts retained
anchors after the cursor; a partial-frame transcript can therefore remain in the future even when
that count is zero. Reverse itself is not a truncation. The first accepted forward step, resume
into forward execution, or real button-state mutation from a historical cursor commits a new
branch: the owner releases the retained future, rebases the branch input baseline to the currently
held live input, and reports `BRANCH_INVALIDATED`. An idempotent input request and observation-only
commands do not branch. A direct debugger memory write cannot be reconstructed by the current
input-only history log, so it more conservatively clears retained rewind/reverse state with
`BRANCH_INVALIDATED` and resets trace correlation before returning its snapshot.

History is available only for an isolated desktop machine using the null serial endpoint or a
disconnected peer-to-peer endpoint, the null infrared endpoint, and no external-I/O owner. A
connected peer, Mobile Adapter ownership, another serial/IR device, MBC7 accelerometer, or Pocket
Camera returns `UNSUPPORTED_TOPOLOGY`. Sensor restrictions apply in every physical cartridge
location.

Reverse-instruction has a narrower scratch-replay topology than direct checkpoint restoration.
Ordinary primary-cartridge MBC3 is supported. HuC3, TAMA5, Datel pass-through/slot cartridges, and
SL_MULTICART configurations are rejected because their host-time or multi-cartridge service
topology is not represented by the bounded transcript. A committed topology handoff clears
retained checkpoints and reports `TOPOLOGY_CHANGED`; reverse rechecks eligibility before touching
the live session.
State loads, legacy imports, ROM replacement, and other session discontinuities likewise release
retained state. User rewind reports `USER_REWIND`. Successful debug reverse clears the separate
user-rewind ring so it cannot reintroduce a state outside the debug cursor.

Closing or replacing the port detaches the input-transcript observer and releases every retained
snapshot and record. In the normal disabled mode no observer, retirement transcript, input record,
or scratch session exists. The completed-frame path performs only the existing enabled check, and
the per-tick path performs no reverse-history work or allocation. If another deterministic capture
already owns the exclusive input-timeline observer, enabling history fails with `SESSION_BUSY`
without changing the current configuration.

## Side-effect-free memory reads and bounded writes

Every read is a bounded copy. The current core exposes a parser-corrected loaded-ROM-image view and
MMU-owned RAM without invoking mapper logic or the ordinary CPU-bus read path:

`inspect(request)` combines one snapshot with ordered memory copies at the same safe point. An
inspection may contain up to 16 blocks and 4096 bytes in total (further bounded by the session's
negotiated memory-read maximum). It returns the original immutable request alongside separate,
ordered anchored and explicit block lists, so clients can map every block without relying on
display labels. Either every range is copied or the command returns one typed failure; partial
results are never published.

Anchored ranges resolve a signed offset from the PC or SP in the inspection's own snapshot. A PC
range starting below `8000` uses the parser-corrected physical `ROM` image and may not cross the
`8000` boundary. This is deliberately best-effort code provenance: in the switchable bank it is
not the mapper's live CPU window. Every other anchor uses the pure `SYSTEM_BUS` view and therefore
fails with `SIDE_EFFECTFUL_ADDRESS` outside WRAM/echo/HRAM. The platform-neutral
`DebugDisassembler` formats a detached code block and includes that provenance in its output.
Coherent inspection is advertised when both snapshot and memory-read capabilities are available;
constructing or polling ordinary emulation state does no inspection work when no command is
submitted.

| Address-space ID | Accepted range | Notes |
|---|---|---|
| `SYSTEM_BUS` | `C000-FDFF`, `FF80-FFFE` | The whole request must remain within one contiguous safe range. |
| `ROM` | `0000-FFFF` | Image offsets in the first 64 KiB of the parser-corrected loaded ROM, not CPU addresses or the mapper's current CPU window; missing image bytes read as `FF`. ROM bytes after offset `FFFF` are not addressable by the 16-bit request model. |
| `WORK_RAM` | `C000-FDFF` | Includes the `E000-FDFF` echo; CGB banked WRAM follows the selected bank. |
| `HIGH_RAM` | `FF80-FFFE` | Excludes interrupt enable at `FFFF`. |

Requests may not wrap the 16-bit address space and may not exceed the negotiated 4096-byte limit.
Cartridge RAM, VRAM, OAM, I/O registers, and every unsafe portion of `SYSTEM_BUS` are currently
unavailable. A named view that has no pure implementation returns `UNSUPPORTED_ADDRESS_SPACE`; a
RAM range that crosses or targets a side-effectful/unavailable address returns
`SIDE_EFFECTFUL_ADDRESS`. The returned `DebugMemoryBlock` owns a defensive byte-array copy.

The Agent's legacy `getByte`, `getMemory`, and `disassemble` helpers select `ROM` below `8000` and
the safe `SYSTEM_BUS` view elsewhere. They reject a request that crosses that selection boundary.
Disassembly is labelled as best-effort and names its source view; in particular, a ROM label says
that bytes came from the corrected physical image rather than the mapper's live CPU window.

`writeMemory(DebugMemoryWrite)` accepts one byte only while the session is effectively paused.
It supports the same safe RAM storage exposed by `SYSTEM_BUS`, `WORK_RAM`, and `HIGH_RAM`; ROM,
cartridge RAM, VRAM, OAM, I/O registers, `FFFF`, and unsafe bus ranges return a typed failure. The
owner writes directly to the owning RAM storage rather than issuing a CPU-bus access, so the command
cannot trigger mapper, I/O, or code-breaker side effects. A successful write returns a new coherent
snapshot. Button input remains a separate typed command applied through the session input event path.

## Legacy debug console and Agent migration

The desktop `--debug` console is a bounded synchronous adapter over the current session's
asynchronous `DebugPort`. It does not retain a `Gameboy`, CPU, register file, address space, sound
engine, or other live core object. The supported machine commands are:

| Command | Debug-port operation |
|---|---|
| `pause` / `p` | `pause()` at the documented safe point |
| `resume` / `r` | `resume()`, releasing only debugger-owned pause state |
| `step [instruction|machine-cycle|frame]` / `s` | negotiated `step(kind)`; instruction is the default |
| `show state` / `state` | one coherent `snapshot()` containing CPU, interrupt, timer, PPU, APU, and mapper state |
| `memory read SPACE ADDRESS LENGTH` / `mem` | one named, side-effect-free `readMemory()` bounded by the session's advertised maximum |

Console numbers are decimal unless prefixed by `0x` or `$`. The existing `cpu show opcode` and
`cpu show opcodes` commands remain useful static opcode-table queries; they do not inspect a live
machine. `help` lists this exact registered surface, and `quit` stops only the console reader.

The adapter waits at most five seconds for a queued result so a broken, closed, or replaced owner
cannot hang the console thread indefinitely. Typed port failures retain their `DebugErrorCode` in
the output. `CONSOLE_TIMEOUT` is deliberately a local adapter diagnostic, not a new port error: a
timed-out command is not cancelled and may still complete on the session, so the console reports
that outcome as indeterminate rather than inviting an unsafe retry.

The historical headless `Agent` inspection helpers now use the same immutable seams. Register,
CPU-cycle/state, IME/IF/IE, LCD/LY, APU status, and mapper-bank reads come from one snapshot;
memory and best-effort disassembly use named memory blocks; stepping and button changes are typed
commands. Frame pixels and audio samples remain detached host adapters outside `DebugPort`, since
they are presentation data rather than private core access.

Historical direct core mutation is not preserved, and the old `Agent.writeMemory` helper remains
absent. Headless callers use `agent.debugPort.writeMemory`, which requires a pause and performs the
one-byte safe-memory write above; Headless does not support rewind or reverse history. The desktop
`BasicController` command additionally clears rewind, reverse, and timeline correlation because its
input-only history cannot reconstruct the mutation. The never-registered `apu chan` console syntax
remains absent. `BasicController` debugger clients may use `setAudioChannelEnabled` instead; it
changes only host output for channels 1 through 4, is available while running, leaves game APU
registers and deterministic history untouched, and returns a coherent snapshot from the owner safe
point.

## Typed errors

`DebugErrorCode` is the stable machine-readable part of a failure; its message is explanatory and
must not be parsed.

| Code | Meaning |
|---|---|
| `NO_ACTIVE_SESSION` | The owner has no live machine for the request. |
| `SESSION_REPLACED` | The command or port belongs to an earlier session generation. |
| `PORT_CLOSED` | The port was closed or its owner shut down. |
| `QUEUE_FULL` | The bounded outstanding-request capacity is exhausted. |
| `INVALID_ARGUMENT` | A value, length, or required argument failed validation. |
| `NOT_PAUSED` | The requested operation, normally step, requires a paused session. |
| `ALREADY_PAUSED`, `ALREADY_RUNNING` | The adapter rejects a redundant state transition. |
| `CPU_IDLE` | An instruction cannot retire while the CPU is halted, stopped, or switching speed. |
| `CPU_LOCKED` | An illegal opcode has permanently locked CPU execution. |
| `UNSUPPORTED_STEP` | The negotiated session does not implement that step granularity. |
| `UNSUPPORTED_ADDRESS_SPACE` | The named memory view has no side-effect-free implementation. |
| `SIDE_EFFECTFUL_ADDRESS` | Some address in the requested range cannot be inspected purely. |
| `UNSUPPORTED_TOPOLOGY` | The requested feature is unavailable for this session topology/capability set. |
| `SESSION_BUSY` | Load, replacement, stop, rewind, or another transition temporarily removes the safe point. |
| `STEP_LIMIT` | A bounded pause/step could not reach its target in the allowed tick window. |
| `BREAKPOINT_LIMIT` | A new definition would exceed the negotiated breakpoint count. |
| `BREAKPOINT_NOT_FOUND` | Removal named an ID that is not installed. |
| `NO_BREAKPOINT_HIT` | No automatic hit has been recorded for this session. |
| `UNSUPPORTED_BREAKPOINT` | The condition kind is not advertised by this session. |
| `UNSUPPORTED_TRACE_CATEGORY` | The configuration contains a trace category not advertised by this session. |
| `TRACE_LIMIT` | Trace capacity or page size exceeds the negotiated bound. |
| `INTERNAL_ERROR` | An unexpected implementation failure was contained instead of escaping on the owner. |
| `HISTORY_DISABLED` | Reverse was requested before checkpoint history was enabled. |
| `HISTORY_EXHAUSTED` | No preceding retained frame or instruction boundary is available. |
| `HISTORY_LIMIT` | A history frame or memory budget is outside the negotiated range. |

## Lifecycle and hook removal

Creating a desktop port does not enable CPU retirement, breakpoint, or trace observation. The first
operation that needs a debug snapshot enables the allocation-free retirement tracker. Producer
hooks are attached selectively from the negotiated configuration:

- CPU hooks serve CPU/memory/interrupt trace and PC, memory, opcode, and interrupt breakpoints; the
  CPU-bus wrapper is narrower and exists only for memory trace or a memory watchpoint.
- PPU hooks serve PPU/memory trace and PPU-state breakpoints; the delegate-once VRAM/OAM observers
  exist only for memory trace.
- interrupt-controller hooks serve interrupt trace or interrupt breakpoints, while DMA hooks serve
  DMA or memory trace.
- timer, input, mapper/RTC, and APU hooks exist only for their corresponding enabled trace
  category; serial/IR hooks exist for serial/IR trace or a serial breakpoint.

Changing breakpoints or trace configuration recomputes those requirements on the owner thread;
unused component hooks and wrappers detach. Attachment aligns component state without producing an
edge. Closing or revoking the port removes every tracker, component hook, and bus observer, cancels
any pending step/pause action, releases the debugger pause, and drops the controller's port
reference. No debugger counter, breakpoint, trace entry, physical PPU trace counter, or pending
interrupt correlation is restored from machine state.

ROM replacement revokes the old generation with `SESSION_REPLACED` before the old machine is torn
down. Ordinary port/session close completes admitted work with `PORT_CLOSED`. A late owner
completion cannot overwrite either terminal result, and `close()` is idempotent. The headless
Agent's port owns its headless session, so closing that port closes the Agent owner as well.

Close and replacement are cancellation boundaries, not transactional rollback. If termination
races an owner command that has already begun, the terminal result can win after that command's
button or pause effect was applied. Clients must treat an in-flight terminal result as
effect-indeterminate and inspect the replacement/current generation rather than retrying a
non-idempotent effect blindly.

Clients must release completed snapshots, trace pages, and stages they no longer need. The port's
trace history is fixed-capacity and overwriting. Its optional reverse history is independently
bounded by both frame count and total modeled snapshot/transcript bytes; a retained future receives
no extra allowance. Neither facility retains unbounded machine history.

## Disabled-retirement benchmark

`core/src/test/java/eu/rekawek/coffeegb/core/DebugDisabledBenchmarkTest.java` is a manual,
opt-in microbenchmark for the normal direct `Gameboy.tick()` path. It deliberately imports and
calls no new debug API, so the exact source can be placed unchanged on the pre-feature revision.
The benchmark is skipped by normal test runs and has no timing/allocation CI assertion.

Run it with a fresh Maven test JVM:

```text
mvn -B -pl core -am \
  -Dtest=DebugDisabledBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcoffeegb.debug.benchmark=true test
```

The deterministic workload is a boot-skipped CGB ROM-only machine executing `JR -2`. It uses a
synchronous event bus and disables LCD/APU output before warmup so periodic presentation delivery
does not obscure CPU-retirement-path allocation. No debugger or retirement tracker is attached.
The harness executes 30,000,000 warmup ticks so HotSpot reaches its steady compiled tier, followed
by nine 5,000,000-tick samples on the same machine. It reports the median/minimum/maximum sample
time and every raw sample.

On HotSpot, `com.sun.management.ThreadMXBean` reports bytes allocated by the invoking test thread
around each measured tick loop. The result includes pre-existing allocation performed by direct
emulation, not merely objects whose type belongs to the debugger. The acceptance question is
therefore the baseline-to-candidate allocation delta: identical per-sample values demonstrate that
the disabled retirement hook adds no per-instruction allocation even when the underlying CPU
workload itself allocates. `allocatedBytesPerMillionTicks` makes a proportional regression easy to
spot in this fixed retirement-dense workload. If the VM does not support thread allocation
accounting, both allocation fields say `unavailable`; that run is not allocation evidence and must
be repeated on a supported VM.

This benchmark isolates the core retirement-disabled branch. It does not measure desktop command
polling, `BasicController` frame ownership, rendering, audio delivery, UI refresh, or an attached
debugger. Those need separate benchmarks when their performance budgets are evaluated.

### Baseline comparison procedure

Use the identical benchmark source, JDK, JVM flags, machine, power policy, and background load for
both revisions. Run the candidate and its pre-feature merge base in separate Maven forks. Because
the source uses no new debug API, it can be copied into an isolated base worktree:

```text
git worktree add /tmp/coffee-gb-debug-base <pre-feature-revision>
cp core/src/test/java/eu/rekawek/coffeegb/core/DebugDisabledBenchmarkTest.java \
  /tmp/coffee-gb-debug-base/core/src/test/java/eu/rekawek/coffeegb/core/
mvn -B -f /tmp/coffee-gb-debug-base/pom.xml -pl core -am \
  -Dtest=DebugDisabledBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcoffeegb.debug.benchmark=true test
```

Collect at least three fresh-fork results per revision and compare the median
`medianTicksPerSecond` values. Compute throughput regression as:

```text
100 * (baseline ticks/second - candidate ticks/second) / baseline ticks/second
```

The disabled-path budget passes when the candidate median regression is at most 1%. For allocation, record
all raw per-sample values. The candidate must add no allocation proportional to instruction count;
compare its measured totals and per-million-tick rate with the baseline instead of assuming the
pre-existing emulator loop allocates zero. Timing and allocation remain report-time decisions
rather than assertions in the test so scheduler, thermal, VM, and host variation cannot make the
normal suite flaky.

## Instrumentation benchmark

`core/src/test/java/eu/rekawek/coffeegb/core/DebugInstrumentationBenchmarkTest.java` is the
companion opt-in benchmark for attached producer paths. Run the unchanged,
baseline-portable disabled test and the companion in adjacent fresh Maven test JVMs:

```text
mvn -B -pl core -am \
  -Dtest=DebugDisabledBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcoffeegb.debug.benchmark=true test
mvn -B -pl core -am \
  -Dtest=DebugInstrumentationBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcoffeegb.debug.benchmark=true test
```

It uses the same deterministic `JR -2` machine and reports four modes:

| Mode | Purpose | Workload |
|---|---|---|
| `pc-no-hit` | One enabled exact-PC breakpoint at an address the loop never fetches; measures instruction-hook matching without a bus wrapper or hit/snapshot allocation. | Same 30,000,000-tick warmup and nine 5,000,000-tick samples as the disabled baseline. |
| `cpu-memory-filter-rejects-all` | CPU and memory categories enabled with a filter that rejects every workload event; verifies filtering before payload construction. | Same geometry as the disabled baseline. |
| `cpu-memory-trace-enabled` | CPU and memory events accepted into a 4096-entry overwrite ring; reports producer throughput, allocation per event, retained bounds, sequence, and drop accounting. | 5,000,000-tick warmup and seven 1,000,000-tick samples to bound the deliberately allocation-heavy run. |
| `all-categories-trace-enabled` | All ten categories negotiated at once; measures the all-category dispatch/attachment configuration and captures every event produced by the same deterministic loop. It is not a stress workload for every peripheral. | Same 5,000,000-tick warmup and seven 1,000,000-tick samples as the accepted CPU/memory trace mode. |

Every mode prints median/minimum/maximum throughput, raw times, thread allocation totals and rates,
and captured-event accounting. Allocation accounting has the same HotSpot requirement as the
disabled test, and the reported per-event figure is explicitly gross: it includes the emulator's
ordinary allocation. The accepted-trace modes assert only deterministic structural facts:
retention equals capacity, sequence continues beyond capacity, and overwrite/missed counts agree.
Both accepted-trace modes apply those ring assertions. The no-hit mode must not produce a hit or
trace; the rejecting mode must retain and drop zero events. There is deliberately no timing or
allocation assertion in ordinary CI.

Compare the no-hit fresh-fork median with the disabled median from the same candidate revision; its
target regression is at most 5%. Compare the candidate's disabled median against the pre-feature
baseline using the earlier at-most-1% budget. The no-hit and rejecting modes should add no
allocation proportional to instruction/event count beyond the emulator's measured disabled-loop
allocation. Full tracing necessarily constructs immutable payload/entry objects, so its relevant
report is throughput and allocation per accepted event. Subtract the disabled
`allocatedBytesPerMillionTicks` from the trace mode's rate before dividing by
`eventsPerMillionTicks` to estimate incremental instrumentation bytes per event. Fixed retained
entry count and monotonic drop accounting establish the memory bound. Scheduler, thermal, GC, and
host variance remain report-time considerations rather than flaky test thresholds.
