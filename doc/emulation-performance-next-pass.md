# Emulation performance: next optimization program

This document is an implementation specification for an agent that can follow exact edits and
validation gates but should not be expected to make new timing-model decisions. Execute the pull
requests in order. Create, benchmark, review, and merge one pull request before starting the next
one. Rebase every new branch on the then-current `master`.

The primary objective remains the exact method-entry count required to emulate the first 1,800
frames of the Harry Potter intro. The secondary objective is host throughput. A change that lowers
the exact count but materially slows the uninstrumented emulator must be reverted.

## Locked baseline

The analyzed production revision is `76fa014cfb6dcbed837b57a5e661c4bbc865a51f`. Later
harness-only commits do not change this baseline.

| Probe | Frames | Emulated ticks | Production-core method calls |
| --- | ---: | ---: | ---: |
| Acceptance run | 1,800 | 126,143,697 | 26,457,973,021 |
| Fast diagnostic run | 120 | 8,414,547 | 1,674,198,575 |

The 120-frame run is boot-heavy. Use it to inspect a patch quickly, but never use it instead of the
1,800-frame acceptance run.

The current 120-frame call leaders are:

| Method | Calls | Share |
| --- | ---: | ---: |
| `Gpu.isGbc()` | 80,043,340 | 4.781% |
| `Gpu.getLine()` | 73,002,755 | 4.360% |
| `SpeedMode.getSpeedMode()` | 51,770,327 | 3.092% |
| `Gpu.isDmgCompatMode()` | 49,147,180 | 2.936% |
| `Gpu.getCpuMachineCycleDots()` | 41,526,618 | 2.480% |
| `GpuRegisterValues.get(...)` | 35,946,544 | 2.147% |
| `Gpu.getTicksInLine()` | 33,708,001 | 2.013% |
| `Gpu.isLcdEnabled()` | 33,658,190 | 2.010% |
| `Gpu.getMode0InterruptTick()` | 31,275,172 | 1.868% |
| `StatRegister.isDoubleSpeed()` | 16,894,857 | 1.009% |
| `PixelTransfer.prepareForTick(int)` | 16,829,098 | 1.005% |
| `Cpu.isInstructionRetiringForHdma()` | 16,829,094 | 1.005% |
| `InterruptManager.setCpuReadInterruptPreview(...)` | 16,829,094 | 1.005% |
| `Gpu.setCpuRetiringInstructionForHdma(boolean)` | 16,829,094 | 1.005% |
| `Timer.isDivResetPending()` | 16,829,094 | 1.005% |
| `PixelTransfer.advanceWindowWy()` | 15,440,160 | 0.922% |
| `PixelTransfer.checkWindowY(int, int)` | 15,440,160 | 0.922% |
| `PixelTransfer.outputTick()` | 15,440,160 | 0.922% |

An uninstrumented JDK 21 JFR run at the same revision measured 600 frames in 10.523512 seconds,
or 4,003,835 ticks/second. Its leading sampled methods were:

| Method | Samples |
| --- | ---: |
| `Gameboy.tickSubsystems()` | 14.43% |
| `StatRegister.tick()` | 11.88% |
| `PixelTransfer.checkWindowY(int, int)` | 10.54% |
| `SoundMode1.tick(boolean)` | 7.77% |
| `Cpu.tick()` | 7.10% |
| `SoundMode2.tick(boolean)` | 5.55% |
| `ColorPixelFifo.outputTick()` | 5.55% |
| `StatRegister.updateModeIrqEvents(boolean)` | 4.00% |
| `Mmu.getByte(int)` | 3.33% |
| `Joypad.tick()` | 2.66% |

JFR is sampled and noisy; percentages are directional. The method-call result and tick count are
exact. Also note that the call-count harness uses normal boot while the throughput probe uses
skipped boot, so compare each probe only with its own previous result.

## Non-negotiable correctness rules

Every executor must follow these rules:

1. Do not edit the harness, its embedded save, its frame target, or the ROM path.
2. The 1,800-frame result must still report exactly 126,143,697 ticks. Any different tick count is
   a failed patch, even when the call count is lower.
3. Preserve the order of CPU, timer, APU, DMA, PPU, STAT, serial, IR, and joypad effects in
   `Gameboy.tickSubsystems()` unless an individual PR below explicitly specifies an equivalent
   reorder.
4. Do not cache a mutable PPU register or timing value across a CPU write, a PPU tick, LCD
   enable/disable, a speed switch, or state restore unless this document explicitly defines the
   invalidation boundary.
5. Add no object, array, record, lambda, stream, or iterator allocation to a per-tick or per-pixel
   path. Reusable mutable snapshots must be allocated once as fields.
6. Derived/transient caches do not belong in portable mementos. Recompute them at the next defined
   boundary and after restore where required.
7. Preserve public compatibility overloads used by focused tests when introducing a faster
   production-only overload.
8. Do not combine two numbered PRs. A small PR makes an exact regression attributable and
   reversible.
9. If a specified assumption is contradicted by current source after rebasing, stop that PR and
   report the contradiction. Do not invent a replacement timing model.

## Measurement protocol for every PR

The harness is versioned on `master`. Invoke these scripts from the repository root of the checkout
being measured.

Use this exact ROM environment value:

```bash
export HARRY_POTTER_ROM="/mnt/nas/emu/roms/gbc/H/Harry Potter and the Sorcerer's Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc"
```

Before editing a PR, record a short parent result:

```bash
HARRY_POTTER_TARGET_FRAMES=120 \
HARRY_POTTER_METHOD_CALL_TOP=100 \
./scripts/count-harry-potter-intro-method-calls.sh
```

After editing:

```bash
mvn -q -pl core test
mvn -q test

HARRY_POTTER_TARGET_FRAMES=120 \
HARRY_POTTER_METHOD_CALL_TOP=100 \
./scripts/count-harry-potter-intro-method-calls.sh

HARRY_POTTER_TARGET_FRAMES=1800 \
./scripts/count-harry-potter-intro-method-calls.sh
```

For PRs whose acceptance section mentions throughput, run the uninstrumented probe three times and
compare the median ticks/second with three runs of the parent commit:

```bash
./scripts/measure-harry-potter-intro-fps.sh
```

For a refreshed profile, use a unique file and inspect it with `jfr view hot-methods`:

```bash
HARRY_POTTER_JFR=/tmp/coffee-gb-next.jfr \
./scripts/measure-harry-potter-intro-fps.sh
jfr view hot-methods /tmp/coffee-gb-next.jfr
```

Each PR description must contain this table, filled with exact values:

| Measurement | Parent | Patch | Delta |
| --- | ---: | ---: | ---: |
| 120-frame ticks | | | |
| 120-frame calls | | | |
| 1,800-frame ticks | | | |
| 1,800-frame calls | | | |
| Median uninstrumented ticks/sec, when required | | | |

Reject a patch if calls do not strictly decrease, ticks change, a focused/full test fails, or
median uninstrumented throughput regresses by more than 3%. A result inside the 3% throughput band
is inconclusive rather than a speedup.

## PR sequence

### PR 1: cache immutable console mode in STAT

Branch: `codex/perf-stat-static-gbc`

Risk: low. Expected short-run reduction: approximately 4–5%.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/StatRegister.java`

Implementation:

1. Add `private boolean gbc;` beside the existing `Gpu gpu` reference. It cannot be `final` without
   changing the circular initialization contract.
2. In `init(Gpu gpu)`, assign `this.gbc = gpu.isGbc();` immediately after assigning `this.gpu`.
3. Replace every `gpu.isGbc()` expression in `StatRegister` with `gbc`. There are 49 source
   occurrences at the analyzed revision.
4. Do not replace `gpu.isDmgCompatMode()`: compatibility mode is mutable.
5. Do not add `gbc` to `StatRegisterState`; it is immutable hardware configuration, not machine
   state.

Why this is safe: `Gpu.gbc` is fixed by the hardware profile at construction and never changes on
speed switch, compatibility-mode switch, LCD transition, or restore.

Focused tests:

```bash
mvn -q -pl core -Dtest=StatRegisterTest,CpuPpuInterruptTimingTest test
```

Acceptance:

- `Gpu.isGbc()` should fall from 80,043,340 calls to fewer than 5,000,000 in the 120-frame profile.
- Total 120-frame calls should fall by at least 4% relative to this PR's parent.
- The exact 1,800-frame tick count must be unchanged.

### PR 2: gate steady-state and rare Gameboy work

Branch: `codex/perf-steady-state-gates`

Risk: low. Expected short-run reduction: approximately 1.5–2%.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/Gameboy.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/ir/InfraredPort.java`

Implementation A — one-time boot compatibility:

Change only the call in `Gameboy.tick()` from an unconditional method call to:

```java
if (!bootCompatibilityResolved) {
    applyBootCompatibilityIfReady();
}
```

Leave the constructor/boot-sequence call unchanged. Leave the restore-time computation of
`bootCompatibilityResolved` unchanged.

Implementation B — common no-HDMA branch:

In the final `else` branch of `Gameboy.tickSubsystems()`, replace the current unconditional GPU
setter pair with this exact shape:

```java
boolean retiringIntoHdmaRequest = hdma.isHblankRequestArrivingAfterCpuTick()
        && cpu.isInstructionRetiringForHdma();
if (retiringIntoHdmaRequest) {
    gpu.setCpuRetiringInstructionForHdma(true);
    try {
        cpu.tick();
    } finally {
        gpu.setCpuRetiringInstructionForHdma(false);
    }
} else {
    cpu.tick();
}
```

The rare HDMA check must be first. Do not change the separate active-HDMA branch guarded by
`hdma.isCpuInstructionRequestOwner()`; its unconditional true/false bracket is timing-sensitive.

Implementation C — absent IR debugger:

At the end of `InfraredPort.tick()`, call `notifyDebugSignalChange()` only when
`debugHooks != null`. Do not change `setByte`, restore, endpoint, or Full Changer behavior.

Focused tests:

```bash
mvn -q -pl core \
  -Dtest=GameboyHdmaArbitrationTest,HdmaTest,GameboyBootStateTest,InfraredPortTest,InfraredPortDebugHooksTest \
  test
```

Acceptance:

- `Gpu.setCpuRetiringInstructionForHdma(boolean)` must disappear from the 120-frame top 100 or
  become rare.
- `Cpu.isInstructionRetiringForHdma()` should lose approximately one call per master tick; one
  post-CPU call remains through `isCpuRequestSlotInProgressForHdma()`.
- `InfraredPort.notifyDebugSignalChange()` must disappear from a headless harness run.
- Total 120-frame calls must fall by at least 1.2% relative to the parent.

### PR 3: consume the DIV-reset event once

Branch: `codex/perf-div-reset-snapshot`

Risk: medium-low. Expected short-run reduction: approximately 1%.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/Gameboy.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/sound/Sound.java`

Current production code queries `Timer.isDivResetPending()` twice and consumes the same boolean in
`Sound.tick()`. The only production readers are `Gameboy` and `Sound`; confirm this with `rg` before
editing.

Implementation:

1. Keep `Sound.tick()` as a compatibility wrapper:

   ```java
   public void tick() {
       tick(timer.consumeDivReset());
   }
   ```

2. Move the existing body to `public void tick(boolean divReset)`. Remove its internal
   `timer.consumeDivReset()` call and use the parameter.
3. Keep `Sound.tickFrameSequencer()` as a compatibility wrapper whose current behavior is
   unchanged. Add `public void tickFrameSequencer(boolean divReset)` containing the implementation;
   replace the internal `timer.isDivResetPending()` condition with the parameter.
4. In the pre-CPU location in `Gameboy.tickSubsystems()`, call
   `sound.tickFrameSequencer(false)`. At that point `timer.tick()` has just cleared the event. A
   speed-switch tail also cannot retain an event because the previous tick's `Sound.tick` consumed
   it.
5. Immediately after the CPU section, where `Gameboy` currently calls
   `timer.isDivResetPending()`, use:

   ```java
   boolean divReset = timer.consumeDivReset();
   if (divReset) {
       sound.tickFrameSequencer(true);
       sound.commitFrameSequencerClock();
       serialPort.onDivReset();
   }
   ```

6. At the existing later sound location, call `sound.tick(divReset)`.
7. Do not move any frame-sequencer commit, serial reset, DMA tick, or channel tick relative to the
   existing sequence.

Focused tests:

```bash
mvn -q -pl core \
  -Dtest=TimerTest,TimerDoubleSpeedTest,SoundFrameSequencerTimingTest,SoundMementoTest,DmaSpeedSwitchTest \
  test
```

Acceptance:

- `Timer.isDivResetPending()` must disappear from the production harness top 100.
- `Timer.consumeDivReset()` must remain approximately once per master tick.
- Total 120-frame calls must fall by at least 0.7% relative to the parent.

### PR 4: refresh GPU speed and compatibility state only when dirty

Branch: `codex/perf-gpu-timing-mode-dirty`

Risk: medium. Expected short-run reduction: approximately 2%.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/cpu/SpeedMode.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/Gpu.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/PixelTransfer.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/ColorPixelFifo.java`

Implementation:

1. Add `private boolean timingModeDirty = true;` to `Gpu`.
2. Install a listener which marks this flag and immediately invokes `prepareForTick()` on the
   owner thread. The guarded prepare operation performs the refresh only on an actual mode change.
   This retains the current immediate listener semantics for FF4C writes and state restore; a
   listener that merely leaves the flag dirty would make direct observations stale until the next
   master tick.
3. In `Gpu.prepareForTick()`, keep the existing `timingSnapshotPrepared = true` lifecycle, but
   guard all speed/compatibility reads with `if (timingModeDirty)`. Inside the guard:

   - read `speedMode.getSpeedMode()` once;
   - read `speedMode.isDmgCompat()` once;
   - assign `speedModeValue` and `dmgCompatValue`;
   - propagate both values to both `PixelTransfer` instances;
   - clear `timingModeDirty`.

   Do not put this guarded body in another method called every tick; that would add one method
   entry back to the metric.
4. Change the PixelTransfer update method to accept both primitives, for example
   `prepareForTick(int speedModeValue, boolean dmgCompatValue)`. It must only assign fields and
   forward compatibility mode to its FIFO.
5. Add a primitive `dmgCompatValue` field to `ColorPixelFifo`. Initialize it in the constructor,
   update it from PixelTransfer, and replace both hot `speedMode.isDmgCompat()` calls in
   `resolvePixel` and `setOverlay` with the field. Keep no long-lived reference solely for hot
   compatibility queries if it is no longer needed.
6. In `SpeedMode.setByte()` for FF4C, call `notifyTimingStateChanged()` when and only when
   `dmgCompat` actually changes. `setDmgCompat`, `onStop`, and `restoreState` must continue to
   notify.
7. Verify these boundaries explicitly:

   - constructor: dirty state is refreshed before first use;
   - speed switch: `SpeedMode.onStop()` marks dirty and `Gpu.onSpeedSwitch()` refreshes it;
   - post-boot compatibility: `setDmgCompat()` marks dirty and the existing prepare call refreshes;
   - restore: `SpeedMode.restoreState()` marks dirty after component restore ordering.

Do not remove `Gpu.prepareForTick()` or `timingSnapshotPrepared` in this PR.

Focused tests:

```bash
mvn -q -pl core \
  -Dtest=SpeedModeTest,DmaSpeedSwitchTest,GpuDisplayEnableTimingTest,GpuCpuWriteSynchronizationTest,ColorPixelFifoWindowStartTest,GameboyMementoTest \
  test
```

Acceptance:

- `PixelTransfer.prepareForTick(...)` must become rare and disappear from the top 100.
- `SpeedMode.getSpeedMode()` must lose at least one call per master tick.
- `SpeedMode.isDmgCompat()` must fall below 1,000,000 calls in the 120-frame profile.
- Total 120-frame calls must fall by at least 1.5% relative to the parent.

### PR 5: add a real fast path to WY sampling

Branch: `codex/perf-pixel-window-y-fast-path`

Risk: medium. Expected call-count reduction: 1–2%. This PR is also expected to improve real
throughput because `PixelTransfer.checkWindowY(int, int)` currently accounts for about 10.5% of
JFR samples.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/PixelTransfer.java`
- `core/src/test/java/eu/rekawek/coffeegb/core/gpu/phase/PixelTransferWindowTriggerTest.java`

Implementation:

1. Inline the exact body of `advanceWindowWy()` at the start of
   `checkWindowY(int line, int ticksInLine)`, then delete the private helper. Do not change its
   decrement/apply order.
2. Save `windowWyOldOnWriteTick` to a local and reset the field exactly once on every call, as the
   current code does.
3. Before reading `r.get(WY)`, determine whether this tick is one of the only comparator
   checkpoints:

   - line 153, dot 454 for DMG or normal-speed CGB;
   - line 0, dot 1 for double-speed CGB;
   - current-line checkpoint on visible lines below 143: dot 450 DMG, 446 normal-speed CGB, or
     449 double-speed CGB;
   - upcoming-line checkpoint on visible lines below 143: dot 454 DMG, 450 normal-speed CGB, or
     453 double-speed CGB.

4. If there is no checkpoint, return after advancing delayed WY and clearing the one-tick old-WY
   latch. This is the steady-state path.
5. At a checkpoint, choose `primaryWy` from the saved old-WY value when present; otherwise read WY
   once. Read LY at most once and reuse it for current/upcoming comparisons. Preserve the existing
   `isWindowDisplay()` condition and `setWindowYTriggered` calls.
6. Do not change the no-argument `checkWindowY()` legacy sampler used by focused phase tests and LCD
   enable.
7. Add/retain tests for all four speed/model checkpoint grids, a WY write colliding with a
   checkpoint, delayed WY application on a non-checkpoint tick, and the line-153/line-0 handoff.

Focused tests:

```bash
mvn -q -pl core \
  -Dtest=PixelTransferWindowTriggerTest,PixelTransferScxTimingTest,GpuDisplayEnableTimingTest,StatRegisterTest \
  test
```

Acceptance:

- `PixelTransfer.advanceWindowWy()` must be absent.
- `GpuRegisterValues.get(...)` should lose approximately two calls per LCD-on master tick.
- Total 120-frame calls must fall by at least 1.2% relative to the parent.
- Run parent and patch throughput three times. Keep the patch only if the median does not regress;
  treat a gain under 3% as inconclusive.

### PR 6: batch PPU interrupt-manager bookkeeping

Branch: `codex/perf-ppu-interrupt-batching`

Risk: medium-low. Expected short-run reduction: approximately 2%.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/cpu/InterruptManager.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/StatRegister.java`
- `core/src/test/java/eu/rekawek/coffeegb/core/cpu/InterruptManagerTest.java`

Implementation A — preview publication:

Add one method that publishes LCDC and VBlank preview bits together. It must preserve any non-PPU
preview bits:

```java
public void setCpuReadPpuInterruptPreview(boolean lcdc, boolean vblank) {
    int preview = (lcdc ? 1 << InterruptType.LCDC.ordinal() : 0)
            | (vblank ? 1 << InterruptType.VBlank.ordinal() : 0);
    cpuReadInterruptPreview =
            (cpuReadInterruptPreview & ~PPU_INTERRUPT_MASK) | preview;
}
```

In `StatRegister.captureCpuInterruptReadPhase(...)`, compute both booleans first and make this one
call instead of two `setCpuReadInterruptPreview` calls. Keep the old single-type API for tests and
other callers.

Implementation B — end-of-PPU-read cleanup:

Add one method containing the current `finishLcdcReadMaskWindow()` body followed by
`cpuReadInterruptPreview = 0`. Use it once at the beginning of `StatRegister.tick()` instead of the
current finish + clear pair. Keep the old methods.

Implementation C — PPU CPU events:

Add a packed `consumePpuTickSignals()` operation that returns and clears, at one instant:

- LCDC interrupt acknowledge;
- VBlank interrupt acknowledge;
- LCDC IF-write-clear.

Expose public bit constants for decoding the returned `int`. Replace the three consecutive consume
calls in `StatRegister.tick()` with one call and local bit checks. Preserve the current order of the
three resulting branches in STAT.

Focused tests:

```bash
mvn -q -pl core -Dtest=InterruptManagerTest,StatRegisterTest,CpuPpuInterruptTimingTest test
```

Acceptance:

- Preview publication must fall from two calls per tick to one.
- Finish/clear must fall from two calls per tick to one.
- The three PPU signal-consume calls must become one.
- Total 120-frame calls must fall by at least 1.5% relative to the parent.

### PR 7: replace STAT's repeated dynamic GPU getters with a reusable snapshot

Branch: `codex/perf-stat-gpu-timing-snapshot`

Risk: high. Expected reduction: large; require at least 5% before keeping it.

Files:

- new `core/src/main/java/eu/rekawek/coffeegb/core/gpu/GpuTimingSnapshot.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/Gpu.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/StatRegister.java`
- new or existing tests under `core/src/test/java/eu/rekawek/coffeegb/core/gpu/`

Do not begin this PR until a new top-100 run confirms that STAT's dynamic GPU getters still account
for at least 8% in aggregate.

Snapshot shape:

Create a package-private, allocation-free mutable holder. Allocate one instance as a field of
`StatRegister`, never in a method. Keep it deliberately lean: eagerly capturing rare, complex
values five times per tick can cost more than the getter calls being removed. It needs these
primitive fields:

```text
line, ticksInLine, visibleLy, earlyLineEdgeTick, mode0InterruptTick,
cpuMachineCycleDots,
dmgCompat, lcdEnabled, firstLine, statModeLatchRephasedBySpeedSwitch,
mode0HaltWakeTick, mode0IntWindow, mode1IntWindow, mode2IntWindow,
doubleSpeed, nativeDoubleSpeed
```

`gbc` is intentionally absent because PR 1 caches the immutable value directly.

GPU capture method:

1. Add package-private `void captureStatTiming(GpuTimingSnapshot target)` to `Gpu`.
2. Assign simple values directly from GPU fields. Do not call public getters for `line`,
   `ticksInLine`, `lcdEnabled`, `firstLine`, compatibility mode, clock mode, or rephase state.
3. Compute `visibleLy`, early-line edge, CPU machine-cycle dots, mode windows, double speed, and
   native double speed directly from GPU fields and already captured primitives. Do not call their
   public getters from the capture method.
4. Compute mode-0 interrupt tick once using the existing formula. On CGB it is directly
   `mode0IntFrom`; on DMG preserve the LCDC/window and sprite-edge condition exactly. Derive the
   mode-0 window and HALT-wake tick from that one result.
5. Do not eagerly capture `getCoincidenceReleaseTick`, `getCpuVisibleStatMode`,
   `hasObjectsOnLine`, `isDmgTerminalWindowMode0ReadPreviewPhase`, or
   `isUnrephasedLineZeroStatTail`. Their call sites are comparatively rare or phase-specific; keep
   those live calls in STAT.
6. The capture method must not mutate GPU, FIFO, register, interrupt, or display state.

STAT refresh contract:

Add `refreshGpuTiming()` that calls the GPU capture method. It must be invoked at the beginning of
every externally reachable timing-sensitive operation:

| Entry point | Reason |
| --- | --- |
| `init` | scheduling begins during initialization |
| `tick` | GPU has just advanced |
| `onLcdEnabled`, `onLcdDisabled` | GPU changed LCD/line state before callback |
| `preCpuTick` | focused tests invoke it directly |
| four-argument `captureCpuStatReadPhase` | starts the production pre-CPU phase |
| three-argument `captureCpuInterruptReadPhase` | direct callers/tests must be safe |
| `isMode0InterruptEdgeNextTick` | direct callers/tests must be safe |
| `publishFrameLyc0Mode2HandoffBeforeCpu` | direct callers/tests must be safe |
| `onScxWrite`, `onLycWrite` | GPU invokes them at write boundaries |
| `setByte`, `getByte` | direct register tests and CPU I/O |

Do not refresh at the end of `StatRegister.restoreState()`: Gameboy restores `SpeedMode` later in
the component sequence. The snapshot is transient and every timing-sensitive entry above refreshes
it before use, after all owners have been restored.

The three `isMode*InterruptSourceOnly` methods do not need a refresh because they use only STAT
state and are called from inside `Gpu.tick()`.

Replacement map:

| Existing expression | Replacement |
| --- | --- |
| `gpu.getLine()` | `timing.line` |
| `gpu.getTicksInLine()` | `timing.ticksInLine` |
| `gpu.getVisibleLy()` | `timing.visibleLy` |
| `gpu.getEarlyLineEdgeTick()` | `timing.earlyLineEdgeTick` |
| `gpu.getMode0InterruptTick()` | `timing.mode0InterruptTick` |
| `gpu.getCpuMachineCycleDots()` | `timing.cpuMachineCycleDots` |
| `gpu.isDmgCompatMode()` | `timing.dmgCompat` |
| `gpu.isLcdEnabled()` | `timing.lcdEnabled` |
| `gpu.isFirstLine()` | `timing.firstLine` |
| `gpu.isStatModeLatchRephasedBySpeedSwitch()` | `timing.statModeLatchRephasedBySpeedSwitch` |
| `gpu.isMode0HaltWakeTick()` | `timing.mode0HaltWakeTick` |
| `gpu.isMode0IntWindow()` | `timing.mode0IntWindow` |
| `gpu.isMode1IntWindow()` | `timing.mode1IntWindow` |
| `gpu.isMode2IntWindow()` | `timing.mode2IntWindow` |
| `isDoubleSpeed()` | `timing.doubleSpeed` |
| `isNativeDoubleSpeed()` | `timing.nativeDoubleSpeed` |

Do not replace these side-effectful or argument-dependent calls:

- `gpu.captureCpuLyReadPhase(...)`;
- `gpu.getCpuReadStatModeOverride(...)`;
- `gpu.getCoincidenceReleaseTick()`;
- `gpu.getCpuVisibleStatMode()`;
- `gpu.hasObjectsOnLine()`;
- `gpu.isDmgTerminalWindowMode0ReadPreviewPhase()`;
- `gpu.isUnrephasedLineZeroStatTail()`.

Delete `StatRegister.isDoubleSpeed()` and `isNativeDoubleSpeed()` only after all call sites have
been replaced. The snapshot and refresh helper are transient derived state and must not be added to
`StatRegisterState`.

Tests:

1. Add a package-level equivalence test that captures a snapshot and compares every field with its
   corresponding existing GPU getter across DMG, native CGB, compatibility mode, normal/double
   speed, LCD off/on, first line, visible lines, VBlank, and line 153.
2. Run:

   ```bash
   mvn -q -pl core \
     -Dtest=StatRegisterTest,CpuPpuInterruptTimingTest,GpuDisplayEnableTimingTest,GpuCpuWriteSynchronizationTest,GameboyMementoTest \
     test
   ```

Acceptance:

- The sum of calls to `Gpu.getLine`, `getTicksInLine`, `isDmgCompatMode`,
  `getCpuMachineCycleDots`, `isLcdEnabled`, `getMode0InterruptTick`, `isFirstLine`, and
  `isStatModeLatchRephasedBySpeedSwitch` must fall by at least 60%.
- Total 120-frame calls must fall by at least 5% relative to the parent. Otherwise revert this
  architecture; its complexity is not justified.
- The 1,800-frame tick count and all STAT tests must remain exact.
- No `GpuTimingSnapshot` constructor may appear in a hot-method run after initialization.

### PR 8: share one pre-CPU STAT snapshot

Branch: `codex/perf-stat-pre-cpu-phase`

Risk: high. This PR depends on PR 7.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/Gameboy.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/StatRegister.java`

PR 7 deliberately refreshes the timing snapshot at each public entry for safety. Production calls
four such entries consecutively without a GPU transition. Collapse only the production sequence;
keep safe wrappers for focused tests.

Implementation:

1. Add a production entry resembling:

   ```java
   public boolean beginCpuReadPhase(
           boolean synchronousHaltEntry,
           boolean asynchronousHaltEntry,
           boolean ordinaryHaltWake,
           boolean oneCycleOrdinaryHaltWake) {
       refreshGpuTiming();
       captureCpuStatReadPhasePrepared(...);
       return isMode0InterruptEdgeNextTickPrepared();
   }
   ```

2. Add a second production entry:

   ```java
   public void finishCpuReadPhase(
           int interruptFlagReadMaskTicks,
           boolean mode0InterruptDispatchPhased,
           boolean mode0InstructionWinsAcceptance) {
       captureCpuInterruptReadPhasePrepared(...);
       publishFrameLyc0Mode2HandoffBeforeCpuPrepared();
   }
   ```

3. Move current method bodies into private `...Prepared` helpers that never refresh. Existing public
   APIs remain wrappers that refresh and invoke one prepared helper, preserving direct-test safety.
4. In `Gameboy.tickSubsystems()`, replace the original sequence with:

   ```java
   boolean mode0InterruptEdgeNextTick = statRegister.beginCpuReadPhase(
           cpu.isSynchronousHaltEntryStatPhase(),
           cpu.isAsynchronousHaltEntryStatPhase(),
           cpu.isOrdinaryHaltWakeStatPhase(),
           cpu.isOneCycleOrdinaryHaltWakeStatPhase());
   statRegister.finishCpuReadPhase(
           cpu.getInterruptFlagReadMaskTicks(mode0InterruptEdgeNextTick),
           cpu.isMode0InterruptDispatchPhased(mode0InterruptEdgeNextTick),
           cpu.doesMode0InstructionWinInterruptAcceptance(mode0InterruptEdgeNextTick));
   ```

This preserves the original order: STAT read capture, edge prediction, CPU-derived read phase,
interrupt preview, then frame LYC=0 handoff.

Focused tests:

```bash
mvn -q -pl core -Dtest=StatRegisterTest,CpuPpuInterruptTimingTest,GameboyHdmaArbitrationTest test
```

Acceptance:

- Production must perform one pre-CPU GPU timing capture and one post-GPU capture per master tick,
  except on actual register writes/reads that require a fresh view.
- The old four public STAT operations must no longer appear once each per master tick in the
  harness.
- Calls must strictly decrease and ticks must remain exact. No fixed percentage is imposed because
  the result depends on PR 7's capture implementation.

### PR 9: pack same-instant CPU phase queries

Branch: `codex/perf-cpu-phase-snapshots`

Risk: medium-high. Implement in two commits but one PR; benchmark each commit and drop a commit that
does not help.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/Gameboy.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/cpu/Cpu.java`

Commit A — STAT phase flags:

1. Define public bit constants in `Cpu` for synchronous HALT entry, asynchronous HALT entry,
   ordinary HALT wake, and one-cycle ordinary HALT wake.
2. Add one `getStatReadPhaseFlags()` method that reads the four existing fields and
   `haltedCpuCycles` directly. Do not implement it by calling the four existing getters, because
   those calls are the cost being removed.
3. Decode the returned bits into the arguments of `StatRegister.beginCpuReadPhase` in Gameboy.
4. Keep the four existing getters for tests/debug callers.

Commit B — post-CPU HDMA phase flags:

1. Define bits for `hasInFlightWriteCycleForHdma`, `isCpuRequestSlotInProgressForHdma`, and
   `isInterruptClaimedAtHdmaSample`.
2. Add one method that computes all three values directly at the same post-CPU point. Do not call
   the existing getters from inside it. In particular, inline the pure state expression currently
   used by `isInstructionRetiringForHdma()`.
3. Decode the bits into the existing `hdma.advanceHblankRequest(...)` call.
4. Keep the existing APIs for focused arbitration tests.

Do not combine the two `Cpu.getState()` reads or the two `Cpu.isSpeedSwitching()` reads: the CPU can
change both values between those points.

Focused tests:

```bash
mvn -q -pl core \
  -Dtest=CpuPpuInterruptTimingTest,GameboyHdmaArbitrationTest,HdmaTest,DmaSpeedSwitchTest \
  test
```

Acceptance:

- Commit A should replace four once-per-tick calls with one and save approximately three method
  entries per master tick.
- Commit B should replace three once-per-tick calls plus a nested retirement query with one.
- Each commit must independently lower calls with exact ticks.

### PR 10: add a settled-input joypad path

> Historical note: the later DMG schematic audit retained this settled-input optimization but
> corrected the receiver to one aggregate BATU/ACEF/AGEM/APUG pipeline clocked at 1 MHz. The
> four-sample timing statements below describe this PR's pre-audit baseline, not current master-tick
> latency; see `dmg-schematic-class-map.md`.

Branch: `codex/perf-joypad-settled-input`

Risk: medium. Primary purpose: real throughput; expected exact-count reduction in headless runs is
about one call per tick.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/joypad/Joypad.java`
- `core/src/test/java/eu/rekawek/coffeegb/core/joypad/JoypadHotPathTest.java`
- `core/src/test/java/eu/rekawek/coffeegb/core/joypad/JoypadInterruptTest.java`

Implementation:

1. When `playerInputSource == PlayerInputSource.RELEASED`, use the package-owned released snapshot
   directly instead of invoking the source lambda. Do not skip comparison with `sampledInput`;
   deterministic replay may have seeded a non-released prior sample.
2. Precompute a static 16-entry table mapping each four-bit active-low input-line value to the
   corresponding fully settled 16-bit history (one four-sample nibble per line). Static
   initialization is outside the metric.
3. After computing `inputLines`, before the four-line filter loop, return when both are true:

   ```text
   inputHistory == SETTLED_HISTORY[inputLines]
   filteredInputLines == inputLines
   ```

4. Do not add a new persisted `settled` boolean; deriving the condition keeps arbitrary valid
   restored filter states correct.
5. Selection writes, physical input changes, legacy input changes, multiplayer selection, and
   replay seeding must continue through the existing four-sample transition path.

Tests to add:

- released steady state takes the fast path without changing state;
- a press still waits four samples and interrupts on the same tick as before;
- a release still waits four samples;
- selector-line changes still pass through the filter;
- a restored partially settled history does not take the fast path;
- replay-seeded physical input is released on the next `RELEASED` source sample.

Focused tests:

```bash
mvn -q -pl core \
  -Dtest=JoypadHotPathTest,JoypadInterruptTest,JoypadInputTimelineObserverTest,JoypadSgbPacketTest \
  test
```

Acceptance:

- `PlayerInputSource.lambda$static$0()` must disappear from the headless hot list.
- Calls must strictly decrease and ticks remain exact.
- Run three parent and three patch throughput probes; require no median regression.

### PR 11: fuse pixel-output call boundaries

Branch: `codex/perf-pixel-output-fusion`

Risk: medium. This PR targets exact call volume after PR 5 removes WY work.

Files:

- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/Gpu.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/PixelTransfer.java`
- `core/src/main/java/eu/rekawek/coffeegb/core/gpu/ColorPixelFifo.java`

Implementation:

1. Add one PixelTransfer operation for the output-producing machine that performs the current
   `fifo.outputTick()` and then the current `machineTick()` body. In `Gpu.tick()`, replace
   `pixelMachine.outputTick(); pixelMachine.machineTick();` with that one call. Keep the timing-only
   `pixelTransferPhase.outputTick()` call because its delay line is semantically required.
2. In `ColorPixelFifo.putPixelToScreen()`, call the three-queue `popEntry(...)` implementation
   directly and remove the no-argument forwarding helper if it has no remaining caller.
3. `resolvePixel(int)` has one caller. Inline its body into the delayed-output loop and remove the
   helper only if the resulting method remains readable and the JIT/throughput gate passes. This
   step is optional within the PR; keep it only when it lowers calls without throughput regression.
4. Preserve output ordering exactly: increment output tick, drain eligible delayed entries in
   order, then advance the active pixel machine.

Focused tests:

```bash
mvn -q -pl core \
  -Dtest=PixelFifoTest,ColorPixelFifoWindowStartTest,PixelTransferWindowTriggerTest,PixelTransferSpriteTimingTest,GpuDisplayEnableTimingTest \
  test
```

Acceptance:

- The output-producing PixelTransfer instance must use one outer call instead of two per LCD-on
  tick.
- The forwarding `ColorPixelFifo.popEntry()` call must disappear.
- Keep optional resolve inlining only when calls decrease and median throughput does not regress.

## Conditional CPU-time PRs after the exact-call program

After PR 11, produce a new 1,800-frame top-100 list and a new JFR. Do not use the old percentages to
justify further edits. The following PRs are authorized only if their subsystem is still prominent.

### Conditional PR A: pulse-channel hot loops

Proceed only if `SoundMode1.tick` plus `SoundMode2.tick` still account for at least 8% of JFR
samples.

Allowed changes:

- Inline `SoundMode1.updateSweep()` into its sole hot caller while preserving the unconditional
  sweep-overflow update.
- Return the current output expression directly from `SoundMode1.tick`, `SoundMode2.tick`, and
  `SoundMode4.tick` instead of calling their own `getCurrentOutput()` methods.
- Cache repeated register-derived values in locals within one tick only.

Forbidden changes:

- Do not skip every other APU tick.
- Do not stop ticking sweep, envelope, length, polynomial, or wave state merely because output is
  currently zero.
- Do not batch multiple master ticks; CPU register writes can occur between them.

Validation:

```bash
mvn -q -pl core \
  -Dtest=SoundFrameSequencerTimingTest,SoundMementoTest,SoundMode3Test,SoundOutputObserverTest \
  test
./scripts/measure-harry-potter-intro-audio-timing.sh
./scripts/measure-harry-potter-intro-audio.sh
```

Keep only changes that preserve exact audio probe output and improve median uninstrumented
throughput by at least 2%. Method-count-only inlining is not enough for this conditional PR.

### Conditional PR B: CPU speed/configuration locals

Proceed only if `Cpu.tick()` remains above 6% of JFR samples or `SpeedMode.getSpeedMode()` remains in
the top ten exact methods.

Allowed first step:

1. Read `speedMode.getSpeedMode()` once at the beginning of `Cpu.tick()` and reuse it for all checks
   that occur before the STOP instruction can call `speedMode.onStop()`.
2. Cache immutable color-hardware capability in a CPU field initialized in the constructor and
   replace `speedMode.isGbc()` calls. Do not confuse color hardware with mutable DMG compatibility.
3. Keep speed queries in public predictive methods unless they are included in a same-instant
   packed query from PR 9.

Do not cache `Cpu.State`, opcode state, clock cycle, IME, or interrupt flags across any operation
that can mutate them. Require all CPU timing, interrupt, HDMA, and speed-switch focused tests plus a
2% median throughput improvement.

### Conditional PR C: MMU dispatch

Proceed only if `Mmu.getByte(int)` remains above 3% of JFR samples.

First, inline the private `getSpace(address)` forwarding method into `getByte` and the private write
path by reading `addressToSpace[address]` once into a local. Keep argument validation and bus
listener/rumble ordering unchanged. Benchmark this small change before considering anything else.

Do not add broad ROM/WRAM range shortcuts without a separate design: `BiosShadow`, cartridge
mappers, DMA bus ownership, debugger wrappers, Game Genie, and memory-mapped I/O all rely on the
current dispatch chain. An interface call visible under `Mmu.getByte` may actually be child mapper
work attributed to the caller.

Require all memory, DMA, mapper, boot, Game Genie, and state replay tests. Keep the change only with
at least a 1% median throughput improvement.

## Explicitly rejected shortcuts

The executor must not try these without a new design:

- A long-lived cache of LY, dot, mode-0 edge, LCD state, or STAT mode with no phase-specific refresh.
- Moving `StatRegister.tick()` before `Gpu.tick()` or moving CPU register callbacks after PPU.
- Skipping one of the two PixelTransfer machines; one is the calibrated timing skeleton and the
  other produces visible pixels.
- Skipping FIFO output during HBlank/VBlank; delayed pixels can still leave the LCD stage.
- Treating `SpeedMode.isGbc()` and DMG compatibility as the same property.
- Consuming DIV reset after `Sound.tick()` or before the post-CPU frame-sequencer/serial handling.
- Combining pre-CPU and post-CPU `Cpu.State` or speed-switch checks.
- Per-tick immutable records for snapshots.
- Changing the emulator's tick rate, frame endpoint, bootstrap mode, or harness instrumentation to
  improve the metric.

## Completion report

After the final kept PR, report:

1. final `master` commit;
2. each PR URL and merge commit;
3. baseline and final 1,800-frame ticks/calls;
4. absolute and percentage call reduction from 26,457,973,021;
5. final top-30 methods;
6. three parent and three final throughput results with medians;
7. final JFR top methods;
8. all focused/full test commands and GitHub CI status;
9. skipped/reverted PRs and the exact failed gate.

Success means exact emulation progress is unchanged, method calls are materially lower, and real
throughput is no worse. A smaller call count alone is not permission to retain a complicated or
slower implementation.
