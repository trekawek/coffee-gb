# Debug port contract and disabled-overhead measurement

## Scope

This document pins the Phase 1 debugger contract implemented by `DebugPort`. The API is
platform-neutral and exposes immutable values only. It does not expose a mutable core component,
an AWT/Swing type, a live array, or a reflection escape hatch. The model is still an internal
Coffee GB API until a later change explicitly versions it for third-party use.

Phase 1 supports pause/resume, coherent snapshots, negotiated stepping, bounded side-effect-free
memory reads, and deterministic button input. Breakpoints, watchpoints, tracing, checkpoints, and
replay are later phases; clients must not infer those capabilities from this interface.

## Session publication and capabilities

The desktop controller publishes a `Controller.SessionDebugPortEvent(generation, port)` only after
a session has committed. A later event with the same generation and a null port revokes it. A
single-player session publishes the functional port. A linked rollback session publishes a typed
port with no capabilities; every operation returns `UNSUPPORTED_TOPOLOGY` because no one-machine
mutation can safely represent the linked owner. A client must retain the generation with every
view and discard a port as soon as its revocation event arrives.

Capabilities are immutable for one generation and must be negotiated before submitting a command.
The current implementations advertise:

| Operation | Desktop `BasicController` | Headless Agent | Linked rollback |
|---|---:|---:|---:|
| pause/resume | yes | yes | no |
| snapshot | yes | yes | no |
| instruction step | yes | yes | no |
| machine-cycle step | no | yes | no |
| frame step | yes | yes | no |
| memory read | yes, at most 4096 bytes | yes, at most 4096 bytes | no |
| button input | yes | yes | no |

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

Submitting a plain memory read or snapshot while the machine is running is safe because the owner
performs it between ticks. The returned value is immutable, but it may become stale immediately;
clients should label it with its generation/sequence rather than combining it with a later view.
Mapper banks use `-1` and mapper feature flags use `DebugFeatureState.UNKNOWN` when the concrete
mapper has no pure inspection seam; clients must not interpret an unknown value as disabled.

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

## Side-effect-free memory

Every read is a bounded copy. The current core exposes a parser-corrected loaded-ROM-image view and
MMU-owned RAM without invoking mapper logic or the ordinary CPU-bus read path:

| Address-space ID | Accepted range | Notes |
|---|---|---|
| `SYSTEM_BUS` | `C000-FDFF`, `FF80-FFFE` | The whole request must remain within one contiguous safe range. |
| `ROM` | `0000-FFFF` | Image offsets in the first 64 KiB of the parser-corrected loaded ROM, not CPU addresses or the mapper's current CPU window; missing image bytes read as `FF`. ROM bytes after offset `FFFF` are not addressable by the Phase 1 16-bit request model. |
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

There is no Phase 1 memory-write command. Button input is a separate typed command and is applied by
the owner through the session input event path.

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
| `INTERNAL_ERROR` | An unexpected implementation failure was contained instead of escaping on the owner. |

## Lifecycle and hook removal

Creating a desktop port does not enable CPU retirement observation. The first operation that needs
a debug snapshot enables the allocation-free retirement tracker. Closing or revoking the port
removes that tracker, cancels any pending step/pause action, releases the debugger pause, and drops
the controller's port reference. No debugger counter is restored from machine state.

ROM replacement revokes the old generation with `SESSION_REPLACED` before the old machine is torn
down. Ordinary port/session close completes admitted work with `PORT_CLOSED`. A late owner
completion cannot overwrite either terminal result, and `close()` is idempotent. The headless
Agent's port owns its headless session, so closing that port closes the Agent owner as well.

Close and replacement are cancellation boundaries, not transactional rollback. If termination
races an owner command that has already begun, the terminal result can win after that command's
button or pause effect was applied. Clients must treat an in-flight terminal result as
effect-indeterminate and inspect the replacement/current generation rather than retrying a
non-idempotent effect blindly.

Clients must release completed snapshots and stages they no longer need. Phase 1 has no trace or
reverse-history buffer, so the port itself retains no unbounded machine history.

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

The issue budget passes when the candidate median regression is at most 1%. For allocation, record
all raw per-sample values. The candidate must add no allocation proportional to instruction count;
compare its measured totals and per-million-tick rate with the baseline instead of assuming the
pre-existing emulator loop allocates zero. Timing and allocation remain report-time decisions
rather than assertions in the test so scheduler, thermal, VM, and host variation cannot make the
normal suite flaky.

### Phase 1 checked-in report

The following comparison was run on 2026-07-30. The candidate was the complete uncommitted Phase 1
working tree based on the recorded base revision; its final commit SHA did not yet exist when the
measurement was made. Both sides used Java 21.0.1 HotSpot, Maven 3.8.6, Linux 7.0.0-28, and an
Intel Core i7-1165G7 with 8 available processors under the `powersave` governor. Each number below
comes from a separate Maven test JVM with the benchmark flags shown above.

| Field | Baseline | Candidate |
|---|---|---|
| Git revision | `6edb35a1f19238611b74b68fdf9c2fd74a126562` | Phase 1 working tree on that base (final SHA pending) |
| Java / VM | 21.0.1 / HotSpot | same |
| Maven / benchmark flags | 3.8.6 / `coffeegb.debug.benchmark=true` | same |
| OS / kernel | Linux 7.0.0-28 | same |
| CPU / governor / available processors | i7-1165G7 / powersave / 8 | same |
| Fresh-fork median ticks/s, run 1 | 16,501,826.947 | 16,832,362.762 |
| Fresh-fork median ticks/s, run 2 | 16,093,173.449 | 16,428,177.446 |
| Fresh-fork median ticks/s, run 3 | 16,390,386.917 | 16,912,168.496 |
| Median of fresh-fork medians | 16,390,386.917 | 16,832,362.762 |
| Raw allocated bytes in every fork | `[133347544,133347728,133347528,133347744,133347528,133347728,133347544,133347728,133347528]` | identical |
| Allocated bytes per million ticks | 26,669,524.444 | 26,669,524.444 |

The calculated throughput regression is **-2.6966%** (the candidate was faster), so the `<= 1%`
budget passes. All six allocation vectors were byte-identical: 1,200,128,600 bytes across 45
million measured ticks per fork, giving zero candidate allocation delta.

Raw sample times in nanoseconds, retained so the medians can be independently recalculated:

```text
BASE1 [295978248,301630741,324893139,330585338,301914809,302996754,308376488,303410884,301298389]
BASE2 [305416634,311156799,312385981,312198521,312921376,308696507,310690742,305787990,307809726]
BASE3 [307842862,306073174,305056862,302711127,304940496,302485111,306046863,321039565,304150993]
CAND1 [290677912,293098447,301474726,298556573,297046830,310254318,290274075,292914842,300119478]
CAND2 [297017300,304355125,298326050,304470051,310511426,304722702,300290046,302669316,306510243]
CAND3 [295645115,291396092,305735179,296932139,315209558,294213686,295712315,295606281,293423504]
```
