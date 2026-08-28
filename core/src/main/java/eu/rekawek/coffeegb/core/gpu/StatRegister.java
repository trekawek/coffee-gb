package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.LYC;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.SCX;

/**
 * Implements the FF41 STAT register and the PPU interrupt generation, modelled after the
 * DMG-CPU B schematics (ff41_stat sheet):
 *
 * <ul>
 * <li>The LY=LYC comparison uses an LY value registered at the beginning of the line, and its
 * result is a register that is frozen while the PPU is disabled.</li>
 * <li>The STAT interrupt is a single level line: (LY=LYC and enabled) or (mode 0 and enabled)
 * or (mode 1 and enabled) or (enabled short pulse at the beginning of OAM scan). The IF flag
 * is only set on the rising edge of this line, which naturally produces the "STAT interrupt
 * blocking" behaviour.</li>
 * <li>Writing STAT on the DMG briefly enables all interrupt sources ("all interrupts are
 * enabled before data settles"), which can produce spurious interrupts.</li>
 * </ul>
 */
public class StatRegister implements AddressSpace, StatefulComponent<StatRegister> {

    public static final int ADDRESS = 0xff41;

    private static final int NEW_FRAME_LYC_EDGE = 8;

    private static final int CGB_DOUBLE_TAIL_LATCH = 454;

    private static final int ORDINARY_HALT_WAKE_STAT_HOLD_TICKS = 456;

    private static final long NO_LYC_IRQ_EVENT = Long.MAX_VALUE;

    // Distinct from the ordinary -1 "no CPU override" value: the production
    // CPU phase was captured, but no FF41 read has asked us to resolve it yet.
    private static final int CPU_STAT_MODE_UNRESOLVED = -2;

    private static final int STAT_READ_PHASE_RECENT_ORDINARY_HALT_WAKE = 1 << 4;

    private final InterruptManager interruptManager;

    private final GpuTimingSnapshot timing = new GpuTimingSnapshot();

    // Derived from the GPU; never serialized as part of the STAT memento.
    private long timingGeneration = Long.MIN_VALUE;

    private Gpu gpu;

    private boolean gbc;

    private GpuRegisterValues registers;

    // bits 3-6: interrupt enable flags
    private int enableBits;

    private int registeredLy;

    private boolean coincidence;

    // The readable coincidence flag updates at the line-start latch, while
    // its contribution to the STAT interrupt line settles one M-cycle later.
    private boolean intCoincidence;

    private boolean intLine;

    private boolean lycWriteSuppressed;

    private int suppressedLycIrqLine = -1;

    private int modeBlockedLycIrqLine = -1;

    /*
     * The LYC interrupt comparator has its own copies of FF41 and FF45. CPU writes
     * update the register sources immediately, but writes close to a scheduled compare
     * can miss one or both comparator latches. This is separate from the readable
     * coincidence bit above: for non-zero LYC values the interrupt compare happens at
     * dot 454 of the preceding line, while LY and STAT bit 2 change later.
     */
    private int lycIrqStatSource;

    private int lycIrqValueSource;

    private int lycIrqStatLatch;

    private int lycIrqValueLatch;

    private long lycIrqClock;

    private long nextLycIrqEvent = NO_LYC_IRQ_EVENT;

    private long pendingLycWriteIrq = NO_LYC_IRQ_EVENT;

    private long pendingLycComparatorIrq = NO_LYC_IRQ_EVENT;

    private long lastLycIrqRegisterChangeClock = Long.MIN_VALUE;

    private long lastLcdcInterruptAcknowledgeClock = Long.MIN_VALUE;

    private long lastVBlankInterruptAcknowledgeClock = Long.MIN_VALUE;

    private boolean releaseTailLycCpuAcceptance;

    private boolean lycComparatorSignal;

    /*
     * Mode interrupt events have separate copies of FF41 and FF45. Writes near
     * an event remain pending until that event's capture window has passed. The
     * event itself refreshes the copies from the live registers, even when its
     * interrupt is blocked by another STAT source.
     */
    private int modeIrqStatLatch;

    private int modeIrqLycLatch;

    private int pendingModeIrqStat;

    private int pendingModeIrqLyc;

    private long pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;

    private long pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;

    /*
     * Mode 0 predicts its event from the preceding HBlank edge and therefore
     * retains FF41/FF45 longer than the mode-1/mode-2 event copies above.
     * Keep a dedicated pair so its capture window cannot shift the proven
     * mode-2 scheduler boundaries.
     */
    private int mode0IrqStatLatch;

    private int mode0IrqLycLatch;

    private int pendingMode0IrqStat;

    private int pendingMode0IrqLyc;

    private long pendingMode0IrqStatClock = NO_LYC_IRQ_EVENT;

    private long pendingMode0IrqLycClock = NO_LYC_IRQ_EVENT;

    // Mode-source FF41 writes and the CGB's line-143 mode-1 edge are captured
    // by separate latches. Retain the write's raster position so a write in the
    // final CPU slot cannot create a combinational mode edge retroactively.
    private long lastModeIrqStatWriteClock = NO_LYC_IRQ_EVENT;

    private int lastModeIrqStatWriteLineTick = Integer.MIN_VALUE;

    private int lastModeIrqStatWriteOld;

    // The CGB captures whether the shared LCDC IF latch was already asserted at
    // the early line-143 mode event. Clearing IF later in the same capture window
    // must not turn that already-blocked event into a second interrupt edge.
    private boolean cgbMode1IfClearAtCapture;

    private boolean pendingCgbMode1Interrupt;

    // The DMG's line-143 mode event samples IF before the physical VBlank
    // rollover. Retain its clock so an acknowledge on the far side of the
    // event consumes the occurrence instead of recreating it at rollover.
    private long dmgLyc143Mode1CaptureClock = NO_LYC_IRQ_EVENT;

    private boolean mode0EventArmed;

    private boolean previousMode0Window;

    private boolean previousMode1Window;

    private boolean previousMode2Window;

    private boolean pendingCgbMode2Interrupt;

    // Double-speed CGB captures the shared IF state at the early mode-2 edge.
    // If it was already high, only an acknowledge which clears it before the
    // late MSTAT replay edge can expose a new request.
    private boolean pendingCgbMode2IfHighAtCapture;

    // The double-speed event reaches IF at dot 452, then remains in MSTAT's
    // synchronizer through dot 455. If software clears IF in between, the late
    // edge can expose the same occurrence again.
    private boolean pendingCgbMode2LateReplay;

    private long pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;

    // A natural line-edge occurrence has a short CPU IF read-mask phase. An
    // occurrence armed later by an FF41 write is already CPU-visible.
    private boolean cgbMode2CapturedAtLineEdge;

    // Normal-speed CGB captures the new-frame mode-2 event in line 153's tail,
    // then publishes it at the phase-adjusted frame rollover.
    private boolean pendingCgbFrameMode2Interrupt;

    // A double-speed FF41 write can still withdraw the just-published mode-2
    // request while it remains behind the CPU synchronizer.
    private boolean retractableCgbMode2Interrupt;

    private boolean pendingCgbMode0Interrupt;

    // MSTAT predicts the next HBlank event when the current one fires. An SCX
    // write after that point moves the live pixel pipeline but not the already
    // scheduled FF41/FF45 capture boundary.
    private boolean scxChangedSinceMode0Event;

    // CPU callbacks run before this dot's PPU clocks. Keep their sampled mode
    // separate from the readable latch used by direct PPU observers.
    private int cpuStatModeOverride = -1;

    // Packed inputs captured at the CPU/PPU boundary for an eventual FF41 read.
    // This is deliberately transient: a restored state begins a new CPU phase.
    private int cpuStatReadPhaseFlags;

    /*
     * Between STAT event checkpoints, and absent a register write or pending
     * capture deadline, readable coincidence and every STAT source level are
     * piecewise constant. This derived flag brings the full evaluator back in
     * when a mutation can invalidate that invariant.
     */
    private boolean statEvaluationDirty = true;

    private int cpuInterruptFlagReadMaskTicks;

    private boolean cpuMode0InterruptDispatchPhased;

    private boolean cpuMode0InstructionWinsAcceptance;

    // The native-CGB CPU read phase can sample the line-153 coincidence release
    // before the direct PPU-facing flag changes two dots later.
    private boolean suppressCpuReadCoincidence;

    // An ordinary HALT wake retains its CPU-facing mode latch for one scanline.
    // Keep the persistent CPU wake marker separate from this bounded PPU window:
    // long-running post-wake samplers must return to the ordinary STAT mux.
    private long ordinaryHaltWakeStatClock = NO_LYC_IRQ_EVENT;

    private boolean previousOrdinaryHaltWakePhase;

    public StatRegister(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
    }

    private void refreshGpuTiming() {
        long generation = gpu.getTimingGeneration();
        if (generation != timingGeneration) {
            gpu.captureStatTimingForTick(timing);
            timingGeneration = generation;
        }
    }

    // TODO remove circular dependency
    public void init(Gpu gpu) {
        this.gpu = gpu;
        refreshGpuTiming();
        this.gbc = gpu.isGbc();
        this.registers = gpu.getRegistersForStat();
        lycIrqStatSource = enableBits;
        lycIrqValueSource = registers.get(LYC);
        lycIrqStatLatch = lycIrqStatSource;
        lycIrqValueLatch = lycIrqValueSource;
        nextLycIrqEvent = scheduleLycIrqEvent(lycIrqStatSource, lycIrqValueSource);
        modeIrqStatLatch = 0;
        modeIrqLycLatch = lycIrqValueSource;
        mode0IrqStatLatch = 0;
        mode0IrqLycLatch = lycIrqValueSource;
        statEvaluationDirty = true;
    }

    public void tick() {
        interruptManager.finishLcdcReadMaskWindowAndClearCpuReadInterruptPreview();
        lycIrqClock++;
        clearCpuStatReadPhase();
        int ppuTickSignals = interruptManager.consumePpuTickSignals();
        boolean statEventCheckpoint = gpu.isStatEventCheckpointForTick();
        boolean scheduledEvent = nextLycIrqEvent == lycIrqClock
                || pendingLycWriteIrq == lycIrqClock
                || pendingLycComparatorIrq == lycIrqClock;
        boolean hasPendingModeRegisterClock = pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT;
        if (!statEvaluationDirty && !statEventCheckpoint && ppuTickSignals == 0
                && !pendingCgbMode0Interrupt && !hasPendingModeRegisterClock
                && !scheduledEvent) {
            return;
        }

        refreshGpuTiming();
        int currentLine = timing.line;
        int currentTicksInLine = timing.ticksInLine;
        boolean currentGbc = gbc;
        boolean currentDmgCompat = timing.dmgCompat;
        boolean currentLcdEnabled = timing.lcdEnabled;
        boolean currentFirstLine = timing.firstLine;
        int currentMode0InterruptTick = timing.mode0InterruptTick;
        int currentCpuMachineCycleDots = timing.cpuMachineCycleDots;
        boolean currentDoubleSpeed = timing.doubleSpeed;
        boolean currentNativeDoubleSpeed = timing.nativeDoubleSpeed;
        boolean mustEvaluateStat = statEvaluationDirty || statEventCheckpoint
                || ppuTickSignals != 0;
        if ((ppuTickSignals
                & InterruptManager.PPU_TICK_SIGNAL_LCDC_INTERRUPT_ACKNOWLEDGE) != 0) {
            lastLcdcInterruptAcknowledgeClock = lycIrqClock;
            if (currentNativeDoubleSpeed
                    && previousMode0Window
                    && currentTicksInLine == currentMode0InterruptTick + 1
                    && mode0EventArmed
                    && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                    && !((mode0IrqStatLatch & 0x40) != 0
                    && currentLine == mode0IrqLycLatch)) {
                // In native double speed, the mode-0 set latch owns the CPU
                // acknowledge slot immediately following its event. A later
                // acknowledge still consumes the stored request normally.
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            }
        }
        if ((ppuTickSignals
                & InterruptManager.PPU_TICK_SIGNAL_VBLANK_INTERRUPT_ACKNOWLEDGE) != 0) {
            lastVBlankInterruptAcknowledgeClock = lycIrqClock;
        }
        boolean lcdcInterruptFlagWriteClear = (ppuTickSignals
                & InterruptManager.PPU_TICK_SIGNAL_LCDC_INTERRUPT_FLAG_WRITE_CLEAR) != 0;
        if (lcdcInterruptFlagWriteClear
                && currentGbc && !currentDmgCompat
                && previousMode0Window
                && (currentTicksInLine == currentMode0InterruptTick
                + (currentDoubleSpeed ? 2 : 1)
                || currentDoubleSpeed && currentTicksInLine
                == currentMode0InterruptTick + 3)
                && mode0EventArmed
                && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                && !((mode0IrqStatLatch & 0x40) != 0
                && currentLine == mode0IrqLycLatch)) {
            // An FF0F clear and the normal-speed CGB mode-0 set share this bus
            // slot. The captured PPU set wins; a clear in the next slot does not.
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
        }
        if (pendingCgbMode0Interrupt) {
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            pendingCgbMode0Interrupt = false;
            mustEvaluateStat = true;
        }
        boolean pendingModeLatchChanged = false;
        if (pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT) {
            pendingModeLatchChanged = commitPendingModeIrqRegisters();
        }
        if (pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT) {
            pendingModeLatchChanged |= commitPendingMode0IrqRegisters();
        }
        mustEvaluateStat |= pendingModeLatchChanged;
        mustEvaluateStat |= scheduledEvent;
        boolean suppressNaturalModeEdge = mustEvaluateStat
                && updateModeIrqEvents(lcdcInterruptFlagWriteClear);
        if (pendingCgbMode2Interrupt && currentTicksInLine == 452) {
            publishPendingCgbMode2Event();
        }
        if (pendingCgbMode2LateReplay && currentTicksInLine == 455) {
            publishPendingCgbMode2Replay();
        }
        if (pendingCgbMode2LateReplay && retractableCgbMode2Interrupt
                && cgbMode2CapturedAtLineEdge && (modeIrqStatLatch & 0x40) == 0
                && currentTicksInLine == 454) {
            interruptManager.maskLcdcUntilNextPeripheralTick();
        }
        boolean publishCgbFrameMode2 = pendingCgbFrameMode2Interrupt && !currentDoubleSpeed
                && ((getNormalSpeedClockPhase() == 0
                && currentLine == 153 && currentTicksInLine == 455)
                || (getNormalSpeedClockPhase() == 1
                && currentLine == 0 && currentTicksInLine == 0));
        if (publishCgbFrameMode2) {
            // The normal-speed frame mode-2 event captures FF41/FF45 at dot 454.
            // A speed-switch clock rephase moves publication across the rollover.
            if (getNormalSpeedClockPhase() == 1) {
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.LCDC);
            } else {
                interruptManager.requestMode2InterruptBeforeCpuAcceptance(false);
            }
            pendingCgbFrameMode2Interrupt = false;
        }
        if (retractableCgbMode2Interrupt && currentTicksInLine > 454) {
            retractableCgbMode2Interrupt = false;
        }
        if (!mustEvaluateStat) {
            return;
        }
        boolean settlingLycLine = false;
        if (currentLcdEnabled) {
            int ticksInLine = currentTicksInLine;
            if (suppressedLycIrqLine >= 0
                    && registeredLy != suppressedLycIrqLine
                    && timing.visibleLy != suppressedLycIrqLine
                    && !(suppressedLycIrqLine == 153 && currentLine == 153)) {
                suppressedLycIrqLine = -1;
            }
            if (modeBlockedLycIrqLine >= 0
                    && registeredLy != modeBlockedLycIrqLine
                    && timing.visibleLy != modeBlockedLycIrqLine) {
                modeBlockedLycIrqLine = -1;
            }
            boolean lycComparePhase = (currentLine != 153 && ticksInLine == 454)
                    || (currentLine == 153 && ticksInLine == 6);
            if (lycComparePhase) {
                int comparedLy = comparedLycIrqLine();
                int comparedLyc = nextLycIrqEvent == lycIrqClock
                        ? lycIrqValueLatch
                        : lycIrqValueSource;
                lycComparatorSignal = comparedLyc == comparedLy;
            }
            if (releaseTailLycCpuAcceptance && ticksInLine == 455) {
                if (currentGbc || gpu.hasObjectsOnLine()) {
                    interruptManager.releaseCpuAcceptance(InterruptType.LCDC);
                } else {
                    interruptManager.releaseHaltWake(InterruptType.LCDC);
                }
                releaseTailLycCpuAcceptance = false;
            }
            if (nextLycIrqEvent == lycIrqClock) {
                fireLycIrqEvent();
            }
            if (pendingLycWriteIrq == lycIrqClock) {
                interruptManager.requestInterrupt(InterruptType.LCDC);
                pendingLycWriteIrq = NO_LYC_IRQ_EVENT;
            }
            if (pendingLycComparatorIrq == lycIrqClock) {
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                pendingLycComparatorIrq = NO_LYC_IRQ_EVENT;
            }
            boolean nativeDoubleTailLycLatch = currentNativeDoubleSpeed
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH
                    && currentLine != 153;
            // In double-speed mode the PPU's line-144 request is readable during
            // the last two dots of line 143. CPU acceptance remains synchronized
            // to the internal rollover, preserving ordinary VBlank dispatch timing.
            if (currentNativeDoubleSpeed && currentLine == 143
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH) {
                if (!recentVBlankAcknowledgeWins()) {
                    interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                            InterruptType.VBlank);
                }
            }
            if (timing.mode0HaltWakeTick) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            if (currentGbc && ticksInLine == currentCpuMachineCycleDots) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            // The LY=0 comparison reaches IF four dots after readable LY falls
            // (dot 8 normally, dot 6 in native double speed), then crosses the
            // CPU/HALT input synchronizer one CPU M-cycle later.
            if (currentLine == 153 && ticksInLine == getNewFrameLycCpuAcceptTick()) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            if ((currentLine <= 144 || currentGbc) && ticksInLine == 0) {
                // Release a request latched in the preceding line's tail before a
                // possible new edge is registered below. Native double speed also
                // latches LYC requests this way during VBlank (for example 151->152).
                // PixelTransfer still describes the preceding line here. On DMG an
                // object-stalled line holds the early mode-2 edge away from both CPU
                // inputs until rollover; a BG-only line only holds the HALT path.
                if (currentGbc || gpu.hasObjectsOnLine()) {
                    interruptManager.releaseCpuAcceptance(InterruptType.LCDC);
                } else {
                    interruptManager.releaseHaltWake(InterruptType.LCDC);
                }
            }
            if (lycWriteSuppressed
                    && ((currentLine != 153 && ticksInLine == 0)
                    || (currentLine == 153 && ticksInLine == getNewFrameLycEdgeTick()))) {
                lycWriteSuppressed = false;
            }
            // The normal comparison uses LY registered at the line start. Native
            // double speed has a separate tail latch at dot 454; the extra speed-scaled
            // latch handles the LY=153 -> 0 transition during line 153.
            if (ticksInLine == 0 || ticksInLine == getNewFrameLycEdgeTick()
                    || nativeDoubleTailLycLatch) {
                // On monochrome hardware LY has already returned to 0 when line 153
                // starts, but the comparator still samples the short-lived 153 value.
                registeredLy = currentLine == 153 && ticksInLine == 0
                        ? 153
                        : timing.visibleLy;
            }
            coincidence = registeredLy == registers.get(LYC);
            int coincidenceReleaseTick = gpu.getCoincidenceReleaseTick();
            boolean coincidenceRelease = currentGbc
                    && !(currentFirstLine && !currentDoubleSpeed)
                    ? ticksInLine > coincidenceReleaseTick
                    : ticksInLine >= coincidenceReleaseTick;
            boolean nativeDoubleTailComparison = currentNativeDoubleSpeed
                    && ticksInLine >= CGB_DOUBLE_TAIL_LATCH
                    && currentLine != 153;
            if ((coincidenceRelease && !nativeDoubleTailComparison
                    && currentLine != 153)
                    || (!currentGbc && currentLine == 153
                    && ticksInLine >= 4 && ticksInLine < getNewFrameLycEdgeTick())
                    || lycWriteSuppressed) {
                // when LY changes, the comparison result reads 0 until the new value
                // is registered at the beginning of the next line (lcdon_timing-GS);
                // at the end of line 153 the comparison stays valid: LY already flipped
                // to 0 at tick 8 and keeps that value into line 0, so an LYC=0
                // interrupt fires only once per frame there
                coincidence = false;
            }

            intCoincidence = coincidence;
            boolean suppressedLycComparison = registeredLy == suppressedLycIrqLine
                    || timing.visibleLy == suppressedLycIrqLine;
            if (suppressedLycComparison) {
                intCoincidence = false;
            }
            if (ticksInLine < 4 && currentLine != 0
                    && currentLine != 144 && currentLine != 153) {
                intCoincidence = false;
                settlingLycLine = coincidence
                        && !suppressedLycComparison
                        && (enableBits & 0b01000000) != 0;
                boolean mode0ToLycPrecedence = intLine
                        && (enableBits & 0x08) != 0
                        && (enableBits & 0x20) == 0;
                if (ticksInLine == 0 && settlingLycLine
                        && (!intLine || mode0ToLycPrecedence)) {
                    // The comparison edge reaches IF at the line-start latch,
                    // before its level contribution to the STAT line settles. A mode-0
                    // source retiring on this boundary does not mask the higher-priority
                    // LYC edge unless mode 2 is selected too. Keep the edge detector
                    // latched across the settling window: if IRQ dispatch clears IF
                    // before tick 4, the comparison must not be observed twice.
                    if (timing.nativeDoubleSpeed) {
                        interruptManager.requestInterrupt(InterruptType.LCDC);
                    } else if (currentGbc) {
                        interruptManager.requestPhasedInterruptBeforeHaltWake(InterruptType.LCDC);
                    } else {
                        interruptManager.requestInterrupt(InterruptType.LCDC);
                    }
                    intLine = true;
                }
            }
            if (currentLine == 144 && ticksInLine == 0) {
                if (timing.nativeDoubleSpeed) {
                    interruptManager.releaseCpuAcceptance(InterruptType.VBlank);
                } else if (!recentVBlankAcknowledgeWins()) {
                    interruptManager.requestInterrupt(InterruptType.VBlank);
                }
            }
        }

        boolean holdModeBlockedLycLine = modeBlockedLycIrqLine >= 0
                && (registeredLy == modeBlockedLycIrqLine
                || timing.visibleLy == modeBlockedLycIrqLine)
                && !intCoincidence && intLine;
        if (!settlingLycLine && !holdModeBlockedLycLine) {
            boolean newLine = computeIntLine(enableBits);
            if (suppressNaturalModeEdge) {
                // Keep the shared level latch synchronized without recreating
                // an edge that the captured FF41/FF45 copies masked.
                intLine = newLine;
            } else {
                updateIntLine(newLine);
            }
        }
        statEvaluationDirty = false;
    }

    /**
     * Completes a native-CGB PERFORMANCE scalar dot after the GPU owner has advanced it.
     *
     * <p>The CPU-side native prologue has already captured this dot's read phase. Capture the
     * post-GPU timing directly into the reusable snapshot, then run the byte-for-byte scalar
     * evaluator. All non-native paths continue to call {@link #tick()} unchanged.</p>
     */
    public void tickNativeCgbPerformancePostGpu() {
        int nativePostGpuFacts = gpu.getNativeCgbPerformancePostStatFacts();
        if ((nativePostGpuFacts & Gpu.NATIVE_CGB_POST_STAT_FACTS_VALID) == 0) {
            tick();
            return;
        }
        interruptManager.finishLcdcReadMaskWindowAndClearCpuReadInterruptPreview();
        lycIrqClock++;
        clearCpuStatReadPhase();
        int ppuTickSignals = interruptManager.consumePpuTickSignals();
        boolean statEventCheckpoint =
                (nativePostGpuFacts & Gpu.NATIVE_CGB_POST_STAT_CHECKPOINT) != 0;
        boolean scheduledEvent = nextLycIrqEvent == lycIrqClock
                || pendingLycWriteIrq == lycIrqClock
                || pendingLycComparatorIrq == lycIrqClock;
        boolean hasPendingModeRegisterClock = pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT;
        if (!statEvaluationDirty && !statEventCheckpoint && ppuTickSignals == 0
                && !pendingCgbMode0Interrupt && !hasPendingModeRegisterClock
                && !scheduledEvent) {
            return;
        }

        timingGeneration = gpu.captureNativeCgbPerformancePostStatTiming(timing);
        int currentLine = timing.line;
        int currentTicksInLine = timing.ticksInLine;
        boolean currentGbc = gbc;
        boolean currentDmgCompat = timing.dmgCompat;
        boolean currentLcdEnabled = timing.lcdEnabled;
        boolean currentFirstLine = timing.firstLine;
        int currentMode0InterruptTick = timing.mode0InterruptTick;
        int currentCpuMachineCycleDots = timing.cpuMachineCycleDots;
        boolean currentDoubleSpeed = timing.doubleSpeed;
        boolean currentNativeDoubleSpeed = timing.nativeDoubleSpeed;
        boolean mustEvaluateStat = statEvaluationDirty || statEventCheckpoint
                || ppuTickSignals != 0;
        if ((ppuTickSignals
                & InterruptManager.PPU_TICK_SIGNAL_LCDC_INTERRUPT_ACKNOWLEDGE) != 0) {
            lastLcdcInterruptAcknowledgeClock = lycIrqClock;
            if (currentNativeDoubleSpeed
                    && previousMode0Window
                    && currentTicksInLine == currentMode0InterruptTick + 1
                    && mode0EventArmed
                    && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                    && !((mode0IrqStatLatch & 0x40) != 0
                    && currentLine == mode0IrqLycLatch)) {
                // In native double speed, the mode-0 set latch owns the CPU
                // acknowledge slot immediately following its event. A later
                // acknowledge still consumes the stored request normally.
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            }
        }
        if ((ppuTickSignals
                & InterruptManager.PPU_TICK_SIGNAL_VBLANK_INTERRUPT_ACKNOWLEDGE) != 0) {
            lastVBlankInterruptAcknowledgeClock = lycIrqClock;
        }
        boolean lcdcInterruptFlagWriteClear = (ppuTickSignals
                & InterruptManager.PPU_TICK_SIGNAL_LCDC_INTERRUPT_FLAG_WRITE_CLEAR) != 0;
        if (lcdcInterruptFlagWriteClear
                && currentGbc && !currentDmgCompat
                && previousMode0Window
                && (currentTicksInLine == currentMode0InterruptTick
                + (currentDoubleSpeed ? 2 : 1)
                || currentDoubleSpeed && currentTicksInLine
                == currentMode0InterruptTick + 3)
                && mode0EventArmed
                && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                && !((mode0IrqStatLatch & 0x40) != 0
                && currentLine == mode0IrqLycLatch)) {
            // An FF0F clear and the normal-speed CGB mode-0 set share this bus
            // slot. The captured PPU set wins; a clear in the next slot does not.
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
        }
        if (pendingCgbMode0Interrupt) {
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            pendingCgbMode0Interrupt = false;
            mustEvaluateStat = true;
        }
        boolean pendingModeLatchChanged = false;
        if (pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT) {
            pendingModeLatchChanged = commitPendingModeIrqRegisters();
        }
        if (pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT) {
            pendingModeLatchChanged |= commitPendingMode0IrqRegisters();
        }
        mustEvaluateStat |= pendingModeLatchChanged;
        mustEvaluateStat |= scheduledEvent;
        boolean suppressNaturalModeEdge = mustEvaluateStat
                && updateModeIrqEvents(lcdcInterruptFlagWriteClear);
        if (pendingCgbMode2Interrupt && currentTicksInLine == 452) {
            publishPendingCgbMode2Event();
        }
        if (pendingCgbMode2LateReplay && currentTicksInLine == 455) {
            publishPendingCgbMode2Replay();
        }
        if (pendingCgbMode2LateReplay && retractableCgbMode2Interrupt
                && cgbMode2CapturedAtLineEdge && (modeIrqStatLatch & 0x40) == 0
                && currentTicksInLine == 454) {
            interruptManager.maskLcdcUntilNextPeripheralTick();
        }
        boolean publishCgbFrameMode2 = pendingCgbFrameMode2Interrupt && !currentDoubleSpeed
                && ((getNormalSpeedClockPhase() == 0
                && currentLine == 153 && currentTicksInLine == 455)
                || (getNormalSpeedClockPhase() == 1
                && currentLine == 0 && currentTicksInLine == 0));
        if (publishCgbFrameMode2) {
            // The normal-speed frame mode-2 event captures FF41/FF45 at dot 454.
            // A speed-switch clock rephase moves publication across the rollover.
            if (getNormalSpeedClockPhase() == 1) {
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.LCDC);
            } else {
                interruptManager.requestMode2InterruptBeforeCpuAcceptance(false);
            }
            pendingCgbFrameMode2Interrupt = false;
        }
        if (retractableCgbMode2Interrupt && currentTicksInLine > 454) {
            retractableCgbMode2Interrupt = false;
        }
        if (!mustEvaluateStat) {
            return;
        }
        boolean settlingLycLine = false;
        if (currentLcdEnabled) {
            int ticksInLine = currentTicksInLine;
            if (suppressedLycIrqLine >= 0
                    && registeredLy != suppressedLycIrqLine
                    && timing.visibleLy != suppressedLycIrqLine
                    && !(suppressedLycIrqLine == 153 && currentLine == 153)) {
                suppressedLycIrqLine = -1;
            }
            if (modeBlockedLycIrqLine >= 0
                    && registeredLy != modeBlockedLycIrqLine
                    && timing.visibleLy != modeBlockedLycIrqLine) {
                modeBlockedLycIrqLine = -1;
            }
            boolean lycComparePhase = (currentLine != 153 && ticksInLine == 454)
                    || (currentLine == 153 && ticksInLine == 6);
            if (lycComparePhase) {
                int comparedLy = comparedLycIrqLine();
                int comparedLyc = nextLycIrqEvent == lycIrqClock
                        ? lycIrqValueLatch
                        : lycIrqValueSource;
                lycComparatorSignal = comparedLyc == comparedLy;
            }
            if (releaseTailLycCpuAcceptance && ticksInLine == 455) {
                if (currentGbc || gpu.hasObjectsOnLine()) {
                    interruptManager.releaseCpuAcceptance(InterruptType.LCDC);
                } else {
                    interruptManager.releaseHaltWake(InterruptType.LCDC);
                }
                releaseTailLycCpuAcceptance = false;
            }
            if (nextLycIrqEvent == lycIrqClock) {
                fireLycIrqEvent();
            }
            if (pendingLycWriteIrq == lycIrqClock) {
                interruptManager.requestInterrupt(InterruptType.LCDC);
                pendingLycWriteIrq = NO_LYC_IRQ_EVENT;
            }
            if (pendingLycComparatorIrq == lycIrqClock) {
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                pendingLycComparatorIrq = NO_LYC_IRQ_EVENT;
            }
            boolean nativeDoubleTailLycLatch = currentNativeDoubleSpeed
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH
                    && currentLine != 153;
            // In double-speed mode the PPU's line-144 request is readable during
            // the last two dots of line 143. CPU acceptance remains synchronized
            // to the internal rollover, preserving ordinary VBlank dispatch timing.
            if (currentNativeDoubleSpeed && currentLine == 143
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH) {
                if (!recentVBlankAcknowledgeWins()) {
                    interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                            InterruptType.VBlank);
                }
            }
            if (timing.mode0HaltWakeTick) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            if (currentGbc && ticksInLine == currentCpuMachineCycleDots) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            // The LY=0 comparison reaches IF four dots after readable LY falls
            // (dot 8 normally, dot 6 in native double speed), then crosses the
            // CPU/HALT input synchronizer one CPU M-cycle later.
            if (currentLine == 153 && ticksInLine == getNewFrameLycCpuAcceptTick()) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            if ((currentLine <= 144 || currentGbc) && ticksInLine == 0) {
                // Release a request latched in the preceding line's tail before a
                // possible new edge is registered below. Native double speed also
                // latches LYC requests this way during VBlank (for example 151->152).
                // PixelTransfer still describes the preceding line here. On DMG an
                // object-stalled line holds the early mode-2 edge away from both CPU
                // inputs until rollover; a BG-only line only holds the HALT path.
                if (currentGbc || gpu.hasObjectsOnLine()) {
                    interruptManager.releaseCpuAcceptance(InterruptType.LCDC);
                } else {
                    interruptManager.releaseHaltWake(InterruptType.LCDC);
                }
            }
            if (lycWriteSuppressed
                    && ((currentLine != 153 && ticksInLine == 0)
                    || (currentLine == 153 && ticksInLine == getNewFrameLycEdgeTick()))) {
                lycWriteSuppressed = false;
            }
            // The normal comparison uses LY registered at the line start. Native
            // double speed has a separate tail latch at dot 454; the extra speed-scaled
            // latch handles the LY=153 -> 0 transition during line 153.
            if (ticksInLine == 0 || ticksInLine == getNewFrameLycEdgeTick()
                    || nativeDoubleTailLycLatch) {
                // On monochrome hardware LY has already returned to 0 when line 153
                // starts, but the comparator still samples the short-lived 153 value.
                registeredLy = currentLine == 153 && ticksInLine == 0
                        ? 153
                        : timing.visibleLy;
            }
            coincidence = registeredLy == registers.get(LYC);
            int coincidenceReleaseTick = currentFirstLine ? 452 : 454;
            boolean coincidenceRelease = currentGbc
                    && !(currentFirstLine && !currentDoubleSpeed)
                    ? ticksInLine > coincidenceReleaseTick
                    : ticksInLine >= coincidenceReleaseTick;
            boolean nativeDoubleTailComparison = currentNativeDoubleSpeed
                    && ticksInLine >= CGB_DOUBLE_TAIL_LATCH
                    && currentLine != 153;
            if ((coincidenceRelease && !nativeDoubleTailComparison
                    && currentLine != 153)
                    || (!currentGbc && currentLine == 153
                    && ticksInLine >= 4 && ticksInLine < getNewFrameLycEdgeTick())
                    || lycWriteSuppressed) {
                // when LY changes, the comparison result reads 0 until the new value
                // is registered at the beginning of the next line (lcdon_timing-GS);
                // at the end of line 153 the comparison stays valid: LY already flipped
                // to 0 at tick 8 and keeps that value into line 0, so an LYC=0
                // interrupt fires only once per frame there
                coincidence = false;
            }

            intCoincidence = coincidence;
            boolean suppressedLycComparison = registeredLy == suppressedLycIrqLine
                    || timing.visibleLy == suppressedLycIrqLine;
            if (suppressedLycComparison) {
                intCoincidence = false;
            }
            if (ticksInLine < 4 && currentLine != 0
                    && currentLine != 144 && currentLine != 153) {
                intCoincidence = false;
                settlingLycLine = coincidence
                        && !suppressedLycComparison
                        && (enableBits & 0b01000000) != 0;
                boolean mode0ToLycPrecedence = intLine
                        && (enableBits & 0x08) != 0
                        && (enableBits & 0x20) == 0;
                if (ticksInLine == 0 && settlingLycLine
                        && (!intLine || mode0ToLycPrecedence)) {
                    // The comparison edge reaches IF at the line-start latch,
                    // before its level contribution to the STAT line settles. A mode-0
                    // source retiring on this boundary does not mask the higher-priority
                    // LYC edge unless mode 2 is selected too. Keep the edge detector
                    // latched across the settling window: if IRQ dispatch clears IF
                    // before tick 4, the comparison must not be observed twice.
                    if (timing.nativeDoubleSpeed) {
                        interruptManager.requestInterrupt(InterruptType.LCDC);
                    } else if (currentGbc) {
                        interruptManager.requestPhasedInterruptBeforeHaltWake(InterruptType.LCDC);
                    } else {
                        interruptManager.requestInterrupt(InterruptType.LCDC);
                    }
                    intLine = true;
                }
            }
            if (currentLine == 144 && ticksInLine == 0) {
                if (timing.nativeDoubleSpeed) {
                    interruptManager.releaseCpuAcceptance(InterruptType.VBlank);
                } else if (!recentVBlankAcknowledgeWins()) {
                    interruptManager.requestInterrupt(InterruptType.VBlank);
                }
            }
        }

        boolean holdModeBlockedLycLine = modeBlockedLycIrqLine >= 0
                && (registeredLy == modeBlockedLycIrqLine
                || timing.visibleLy == modeBlockedLycIrqLine)
                && !intCoincidence && intLine;
        if (!settlingLycLine && !holdModeBlockedLycLine) {
            boolean newLine = computeIntLine(enableBits);
            if (suppressNaturalModeEdge) {
                // Keep the shared level latch synchronized without recreating
                // an edge that the captured FF41/FF45 copies masked.
                intLine = newLine;
            } else {
                updateIntLine(newLine);
            }
        }
        statEvaluationDirty = false;
    }

    /**
     * Performs the invariant portion of a quiet PERFORMANCE STAT tick.
     *
     * <p>During a proven sprite/window-free mode-3 span no STAT source, event checkpoint, or
     * interrupt acknowledge can change.  The clock and transient CPU-read phase still advance,
     * but the level evaluator and its large collection of scheduled-event branches can wait
     * for the next boundary.  A false result asks the caller to run the complete scalar tick.</p>
     */
    public boolean tickPerformanceQuietIfSafe() {
        if (statEvaluationDirty
                || gpu.isStatEventCheckpointForTick()
                || nextLycIrqEvent == lycIrqClock + 1
                || pendingLycWriteIrq == lycIrqClock + 1
                || pendingLycComparatorIrq == lycIrqClock + 1
                || pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT
                || pendingCgbMode2PublicationClock == lycIrqClock + 1
                || pendingCgbMode0Interrupt
                || interruptManager.hasPpuTickSignals()) {
            tick();
            return false;
        }
        interruptManager.finishLcdcReadMaskWindowAndClearCpuReadInterruptPreview();
        lycIrqClock++;
        clearCpuStatReadPhase();
        return true;
    }

    /** Returns whether a short no-CPU-bus span can keep STAT on its invariant clock path. */
    public boolean canTickPerformanceQuietSpan(int ticks) {
        if (ticks <= 0 || statEvaluationDirty || gpu.isStatEventCheckpointForTick()
                || gpu.isStatEventCheckpointWithin(ticks)
                || pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT
                || pendingCgbMode2PublicationClock != NO_LYC_IRQ_EVENT
                || pendingCgbMode0Interrupt
                || interruptManager.hasPpuTickSignals()) {
            return false;
        }
        long endClock = lycIrqClock + ticks;
        return (nextLycIrqEvent == NO_LYC_IRQ_EVENT || nextLycIrqEvent > endClock)
                && (pendingLycWriteIrq == NO_LYC_IRQ_EVENT || pendingLycWriteIrq > endClock)
                && (pendingLycComparatorIrq == NO_LYC_IRQ_EVENT
                || pendingLycComparatorIrq > endClock);
    }

    /**
     * Returns the largest short PERFORMANCE span which can stay on STAT's invariant clock path.
     * The scheduler normally asks for at most the three non-bus clocks before a CPU boundary;
     * trying the small candidates in descending order keeps this preflight state-only while
     * allowing a nearby STAT checkpoint to shorten, rather than reject, the span.
     */
    public int performanceQuietSpanLimit(int requested) {
        if (requested <= 0) {
            return 0;
        }
        int limit = Math.min(requested, 3);
        for (int candidate = limit; candidate > 0; candidate--) {
            if (canTickPerformanceQuietSpan(candidate)) {
                return candidate;
            }
        }
        return 0;
    }

    /** Same checkpoint walk for settled HALT packets, without the ordinary three-dot cap. */
    public int performanceSettledHaltSpanLimit(int requested) {
        if (requested <= 0 || statEvaluationDirty || gpu.isStatEventCheckpointForTick()
                || pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT
                || pendingCgbMode2PublicationClock != NO_LYC_IRQ_EVENT
                || pendingCgbMode0Interrupt
                || interruptManager.hasPpuTickSignals()) {
            return 0;
        }
        int limit = requested;
        int gpuDistance = gpu.performanceStatCheckpointDistance();
        if (gpuDistance != Integer.MAX_VALUE) {
            limit = Math.min(limit, gpuDistance - 1);
        }
        long clock = lycIrqClock;
        long event = nextLycIrqEvent;
        if (event != NO_LYC_IRQ_EVENT) {
            long distance = event - clock;
            if (distance <= 0) {
                return 0;
            }
            limit = Math.min(limit, (int) Math.min(Integer.MAX_VALUE, distance - 1));
        }
        event = pendingLycWriteIrq;
        if (event != NO_LYC_IRQ_EVENT) {
            long distance = event - clock;
            if (distance <= 0) {
                return 0;
            }
            limit = Math.min(limit, (int) Math.min(Integer.MAX_VALUE, distance - 1));
        }
        event = pendingLycComparatorIrq;
        if (event != NO_LYC_IRQ_EVENT) {
            long distance = event - clock;
            if (distance <= 0) {
                return 0;
            }
            limit = Math.min(limit, (int) Math.min(Integer.MAX_VALUE, distance - 1));
        }
        return Math.max(0, limit);
    }

    /** Advances the invariant STAT clock for a span whose CPU bus is known to be idle. */
    public boolean tickPerformanceQuietSpan(int ticks) {
        // The caller preflights the GPU checkpoint before advancing the raster counters. At
        // this point Gpu has already moved to the span's end, which may itself be a line/STAT
        // checkpoint (notably the 447->448 HBlank tail); re-reading that live GPU predicate
        // would reject an otherwise valid span after the fact.
        if (!canTickPerformanceQuietSpanStateOnly(ticks)) {
            return false;
        }
        interruptManager.finishLcdcReadMaskWindowAndClearCpuReadInterruptPreview(ticks);
        lycIrqClock += ticks;
        clearCpuStatReadPhase();
        return true;
    }

    /** Applies a span after the caller has already passed canTickPerformanceQuietSpan. */
    public void tickPerformanceQuietSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        interruptManager.finishLcdcReadMaskWindowAndClearCpuReadInterruptPreview(ticks);
        lycIrqClock += ticks;
        clearCpuStatReadPhase();
    }

    private boolean canTickPerformanceQuietSpanStateOnly(int ticks) {
        if (ticks <= 0 || statEvaluationDirty
                || pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                || pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                || pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT
                || pendingCgbMode2PublicationClock != NO_LYC_IRQ_EVENT
                || pendingCgbMode0Interrupt
                || interruptManager.hasPpuTickSignals()) {
            return false;
        }
        long endClock = lycIrqClock + ticks;
        return (nextLycIrqEvent == NO_LYC_IRQ_EVENT || nextLycIrqEvent > endClock)
                && (pendingLycWriteIrq == NO_LYC_IRQ_EVENT || pendingLycWriteIrq > endClock)
                && (pendingLycComparatorIrq == NO_LYC_IRQ_EVENT
                || pendingLycComparatorIrq > endClock);
    }

    public void onLcdEnabled() {
        refreshGpuTiming();
        clearCpuStatReadPhase();
        statEvaluationDirty = true;
        registeredLy = 0;
        lycWriteSuppressed = false;
        lycIrqStatLatch = lycIrqStatSource;
        lycIrqValueLatch = lycIrqValueSource;
        nextLycIrqEvent = scheduleLycIrqEvent(lycIrqStatSource, lycIrqValueSource);
        modeIrqLycLatch = lycIrqValueSource;
        pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
        mode0IrqLycLatch = lycIrqValueSource;
        pendingMode0IrqLycClock = NO_LYC_IRQ_EVENT;
        mode0EventArmed = (enableBits & 0x08) != 0;
        previousMode0Window = false;
        previousMode1Window = false;
        previousMode2Window = false;
        cgbMode1IfClearAtCapture = false;
        pendingCgbMode1Interrupt = false;
        dmgLyc143Mode1CaptureClock = NO_LYC_IRQ_EVENT;
        pendingCgbMode0Interrupt = false;
        pendingCgbMode2Interrupt = false;
        pendingCgbMode2IfHighAtCapture = false;
        pendingCgbMode2LateReplay = false;
        pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;
        cgbMode2CapturedAtLineEdge = false;
        pendingCgbFrameMode2Interrupt = false;
        retractableCgbMode2Interrupt = false;
        scxChangedSinceMode0Event = false;
    }

    /** Publishes a scheduled CGB mode-2 event before a same-timestamp CPU bus read. */
    public void preCpuTick() {
        refreshGpuTiming();
        cpuStatModeOverride = gpu.getCpuStatModeOverride();
        if (pendingCgbMode2Interrupt && !timing.doubleSpeed
                && isDeferredCgbMode2Phase()
                && timing.ticksInLine == 450) {
            // A normal-speed CPU memory callback at stored dot 450 completes at
            // the scheduled dot-452 MSTAT boundary. Resolve that boundary before
            // the callback; CPU and HALT acceptance remain blocked until rollover.
            publishPendingCgbMode2Event();
            statEvaluationDirty = true;
        }
    }

    /** Captures all STAT state needed by the production pre-CPU phase. */
    public boolean beginCpuReadPhase(boolean synchronousHaltEntryPhase,
                                     boolean asynchronousHaltEntryPhase,
                                     boolean ordinaryHaltWakePhase,
                                     boolean oneCycleOrdinaryHaltWakePhase) {
        return beginCpuReadPhase(packCpuStatReadPhase(synchronousHaltEntryPhase,
                asynchronousHaltEntryPhase, ordinaryHaltWakePhase,
                oneCycleOrdinaryHaltWakePhase));
    }

    public boolean beginCpuReadPhase(int statReadPhaseFlags) {
        captureCpuStatReadPhasePrepared(statReadPhaseFlags);
        if (!canPublishMode0InterruptEdge()) {
            return false;
        }
        refreshGpuTiming();
        return isMode0InterruptEdgeNextTickPrepared();
    }

    /**
     * Native-CGB PERFORMANCE equivalent of {@link #beginCpuReadPhase(int)}. The native owner
     * supplies the current line and the two double-speed mode-0 lookahead bits directly, so the
     * common scalar miss does not construct or refresh the general GPU timing snapshot.
     */
    public boolean beginNativeCgbPerformanceReadPhase(int statReadPhaseFlags, int phaseWord) {
        captureNativeCgbPerformanceReadPhasePrepared(statReadPhaseFlags);
        if (!canPublishMode0InterruptEdge()
                || (phaseWord & Gpu.NATIVE_CGB_PHASE_MODE0_EDGE_NEXT) == 0) {
            return false;
        }
        return !((mode0IrqStatLatch & 0x40) != 0
                && (phaseWord & Gpu.NATIVE_CGB_PHASE_LINE_MASK) == mode0IrqLycLatch);
    }

    /** Native owner-side copy of the packed CPU phase bookkeeping. */
    private void captureNativeCgbPerformanceReadPhasePrepared(int statReadPhaseFlags) {
        statReadPhaseFlags = captureDurableCpuStatReadPhase(statReadPhaseFlags);
        cpuStatReadPhaseFlags = statReadPhaseFlags;
        cpuStatModeOverride = CPU_STAT_MODE_UNRESOLVED;
    }

    /** Completes the production pre-CPU STAT phase after refreshing only if it needs timing. */
    public void finishCpuReadPhase(int interruptFlagReadMaskTicks,
                                   boolean mode0InterruptDispatchPhased,
                                   boolean mode0InstructionWinsAcceptance) {
        if (!needsCpuInterruptReadTiming()) {
            captureCpuInterruptReadPhasePrepared(interruptFlagReadMaskTicks,
                    mode0InterruptDispatchPhased, mode0InstructionWinsAcceptance);
            return;
        }
        // beginCpuReadPhase may have skipped its refresh when no mode-0 edge
        // was armed.  Pending CGB mode-2 and frame handoff paths still need a
        // current snapshot before their prepared helpers inspect it.
        refreshGpuTiming();
        captureCpuInterruptReadPhasePrepared(interruptFlagReadMaskTicks,
                mode0InterruptDispatchPhased, mode0InstructionWinsAcceptance);
        publishFrameLyc0Mode2HandoffBeforeCpuPrepared();
    }

    /** Completes the native-CGB PERFORMANCE CPU/STAT phase without a timing snapshot. */
    public void finishNativeCgbPerformanceReadPhase(int interruptFlagReadMaskTicks,
                                                    int phaseWord) {
        cpuInterruptFlagReadMaskTicks = interruptFlagReadMaskTicks;
        cpuMode0InterruptDispatchPhased = false;
        cpuMode0InstructionWinsAcceptance = false;
        boolean mode0Enabled = isMode0InterruptPreviewEnabled();
        if (!mode0Enabled && !pendingCgbMode2Interrupt && enableBits != 0x20) {
            return;
        }
        int line = phaseWord & Gpu.NATIVE_CGB_PHASE_LINE_MASK;
        boolean mode0SourceEnabled = mode0Enabled
                && !((mode0IrqStatLatch & 0x40) != 0 && line == mode0IrqLycLatch);
        boolean doubleSpeedMode0 = mode0SourceEnabled
                && enableBits == 0x48
                && (phaseWord & Gpu.NATIVE_CGB_PHASE_MODE0_READ_PREVIEW) != 0;
        interruptManager.setCpuReadPpuInterruptPreview(doubleSpeedMode0, false);
    }

    /** Captures the PPU mux phase before this tick's CPU memory callback. */
    public void captureCpuStatReadPhase(boolean synchronousHaltEntryPhase,
                                        boolean asynchronousHaltEntryPhase,
                                        boolean ordinaryHaltWakePhase) {
        captureCpuStatReadPhase(synchronousHaltEntryPhase, asynchronousHaltEntryPhase,
                ordinaryHaltWakePhase, false);
    }

    public void captureCpuStatReadPhase(boolean synchronousHaltEntryPhase,
                                        boolean asynchronousHaltEntryPhase,
                                        boolean ordinaryHaltWakePhase,
                                        boolean oneCycleOrdinaryHaltWakePhase) {
        refreshGpuTiming();
        captureCpuStatReadPhasePrepared(packCpuStatReadPhase(synchronousHaltEntryPhase,
                asynchronousHaltEntryPhase, ordinaryHaltWakePhase,
                oneCycleOrdinaryHaltWakePhase));
    }

    private static int packCpuStatReadPhase(boolean synchronousHaltEntryPhase,
                                             boolean asynchronousHaltEntryPhase,
                                             boolean ordinaryHaltWakePhase,
                                             boolean oneCycleOrdinaryHaltWakePhase) {
        int statReadPhaseFlags = 0;
        if (synchronousHaltEntryPhase) {
            statReadPhaseFlags |= Cpu.STAT_READ_PHASE_SYNCHRONOUS_HALT_ENTRY;
        }
        if (asynchronousHaltEntryPhase) {
            statReadPhaseFlags |= Cpu.STAT_READ_PHASE_ASYNCHRONOUS_HALT_ENTRY;
        }
        if (ordinaryHaltWakePhase) {
            statReadPhaseFlags |= Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE;
        }
        if (oneCycleOrdinaryHaltWakePhase) {
            statReadPhaseFlags |= Cpu.STAT_READ_PHASE_ONE_CYCLE_ORDINARY_HALT_WAKE;
        }
        return statReadPhaseFlags;
    }

    private void captureCpuStatReadPhasePrepared(int statReadPhaseFlags) {
        boolean ordinaryHaltWakePhase = (statReadPhaseFlags
                & Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE) != 0;
        gpu.captureCpuLyReadPhase(ordinaryHaltWakePhase
                && isMode1InterruptSourceOnly());
        statReadPhaseFlags = captureDurableCpuStatReadPhase(statReadPhaseFlags);
        cpuStatReadPhaseFlags = statReadPhaseFlags;
        cpuStatModeOverride = CPU_STAT_MODE_UNRESOLVED;
    }

    /**
     * Captures the persistent ordinary-HALT-wake edge for a proven no-CPU-read packet. The
     * packet has no FF41/FF44 callback, so it deliberately does not publish a transient STAT
     * mode override or LY read latch. Callers invoke this exactly once before advancing the
     * first positive peripheral prefix.
     */
    public void capturePerformanceNoCpuReadPhaseTrusted(int statReadPhaseFlags) {
        captureDurableCpuStatReadPhase(statReadPhaseFlags);
        clearCpuStatReadPhase();
    }

    /** Updates the memento-backed ordinary/recent window and returns its packed read view. */
    private int captureDurableCpuStatReadPhase(int statReadPhaseFlags) {
        boolean ordinaryHaltWakePhase = (statReadPhaseFlags
                & Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE) != 0;
        if (ordinaryHaltWakePhase && !previousOrdinaryHaltWakePhase) {
            ordinaryHaltWakeStatClock = lycIrqClock;
        }
        previousOrdinaryHaltWakePhase = ordinaryHaltWakePhase;
        boolean recentOrdinaryHaltWakePhase = ordinaryHaltWakePhase
                && ordinaryHaltWakeStatClock != NO_LYC_IRQ_EVENT
                && lycIrqClock - ordinaryHaltWakeStatClock
                <= ORDINARY_HALT_WAKE_STAT_HOLD_TICKS;
        statReadPhaseFlags &= ~STAT_READ_PHASE_RECENT_ORDINARY_HALT_WAKE;
        if (recentOrdinaryHaltWakePhase) {
            statReadPhaseFlags |= STAT_READ_PHASE_RECENT_ORDINARY_HALT_WAKE;
        }
        return statReadPhaseFlags;
    }

    /** Captures PPU edges visible to this tick's CPU IF read before IF itself settles. */
    public void captureCpuInterruptReadPhase() {
        captureCpuInterruptReadPhase(0, false, false);
    }

    public void captureCpuInterruptReadPhase(int interruptFlagReadMaskTicks,
                                             boolean mode0InterruptDispatchPhased,
                                             boolean mode0InstructionWinsAcceptance) {
        refreshGpuTiming();
        captureCpuInterruptReadPhasePrepared(interruptFlagReadMaskTicks,
                mode0InterruptDispatchPhased, mode0InstructionWinsAcceptance);
    }

    private void captureCpuInterruptReadPhasePrepared(int interruptFlagReadMaskTicks,
                                                      boolean mode0InterruptDispatchPhased,
                                                      boolean mode0InstructionWinsAcceptance) {
        cpuInterruptFlagReadMaskTicks = interruptFlagReadMaskTicks;
        cpuMode0InterruptDispatchPhased = mode0InterruptDispatchPhased;
        cpuMode0InstructionWinsAcceptance = mode0InstructionWinsAcceptance;
        boolean mode0Enabled = isMode0InterruptPreviewEnabled();
        // Most ticks have no enabled mode-0 source and no mode-2 event in
        // flight. The per-tick lifecycle clears the prior preview before this
        // point, so there is no state to publish or GPU timing to inspect.
        if (!mode0Enabled && !pendingCgbMode2Interrupt && enableBits != 0x20) {
            return;
        }
        boolean doubleSpeed = timing.doubleSpeed;
        boolean gbc = this.gbc;
        boolean dmgCompat = timing.dmgCompat;
        int line = timing.line;
        int ticksInLine = timing.ticksInLine;
        int mode0InterruptTick = timing.mode0InterruptTick;
        boolean mode0SourceEnabled = mode0Enabled
                && !((mode0IrqStatLatch & 0x40) != 0 && line == mode0IrqLycLatch);
        boolean dmgMode0 = mode0SourceEnabled
                && gpu.isDmgTerminalWindowMode0ReadPreviewPhaseForTick();
        boolean nativeNormalRephased = gbc && !dmgCompat
                && !doubleSpeed && timing.statModeLatchRephasedBySpeedSwitch;
        boolean doubleSpeedMode0 = gbc && !dmgCompat && doubleSpeed
                && line < 144
                && ticksInLine == mode0InterruptTick - 2
                && enableBits == 0x48
                && mode0SourceEnabled;
        boolean earlyVisibleMode2 = nativeNormalRephased
                && line < 144 && ticksInLine == 450
                && pendingCgbMode2Interrupt;
        // The rephased normal-speed CPU bus completes an IF read against the
        // upcoming PPU edge. Keep this read-only: the stored IF and interrupt
        // acceptance paths still settle at their ordinary MSTAT/VBlank clocks.
        boolean frameMode2 = nativeNormalRephased
                && line == 153 && ticksInLine >= 452
                && ticksInLine <= 455 && enableBits == 0x20;
        boolean frameVBlank = nativeNormalRephased
                && line == 143 && ticksInLine >= 452
                && ticksInLine <= 455 && enableBits == 0x20;
        interruptManager.setCpuReadPpuInterruptPreview(
                dmgMode0 || doubleSpeedMode0 || earlyVisibleMode2 || frameMode2,
                frameVBlank);
    }

    private boolean canPublishMode0InterruptEdge() {
        return isMode0InterruptPreviewEnabled()
                && !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
    }

    private boolean needsCpuInterruptReadTiming() {
        return isMode0InterruptPreviewEnabled()
                || pendingCgbMode2Interrupt || enableBits == 0x20;
    }

    private boolean isMode0InterruptPreviewEnabled() {
        return mode0EventArmed && ((enableBits | mode0IrqStatLatch) & 0x08) != 0;
    }

    /** Returns whether this scheduler tick will publish the delayed mode-0 edge. */
    public boolean isMode0InterruptEdgeNextTick() {
        if (gpu == null) {
            return false;
        }
        refreshGpuTiming();
        return isMode0InterruptEdgeNextTickPrepared();
    }

    private boolean isMode0InterruptEdgeNextTickPrepared() {
        return mode0EventArmed
                && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                && !interruptManager.isInterruptFlagSet(InterruptType.LCDC)
                && gbc && !timing.dmgCompat
                && timing.line < 144
                && timing.ticksInLine + 1 == timing.mode0InterruptTick
                && !((mode0IrqStatLatch & 0x40) != 0
                && timing.line == mode0IrqLycLatch);
    }

    /** Publishes line 1's LYC=0-blocked normal-speed mode-2 handoff before CPU I/O. */
    public void publishFrameLyc0Mode2HandoffBeforeCpu() {
        refreshGpuTiming();
        publishFrameLyc0Mode2HandoffBeforeCpuPrepared();
    }

    private void publishFrameLyc0Mode2HandoffBeforeCpuPrepared() {
        if (pendingCgbMode2Interrupt && !timing.doubleSpeed
                && isDeferredCgbMode2Phase()
                && timing.line == 1
                && (enableBits & 0x60) == 0x60
                && modeIrqLycLatch == 0
                && timing.ticksInLine == 450) {
            publishPendingCgbMode2Event();
        }
    }

    public void onLcdDisabled() {
        refreshGpuTiming();
        clearCpuStatReadPhase();
        statEvaluationDirty = true;
        dmgLyc143Mode1CaptureClock = NO_LYC_IRQ_EVENT;
        pendingCgbMode1Interrupt = false;
        pendingCgbMode0Interrupt = false;
        pendingCgbMode2Interrupt = false;
        pendingCgbMode2IfHighAtCapture = false;
        pendingCgbMode2LateReplay = false;
        pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;
        cgbMode2CapturedAtLineEdge = false;
        pendingCgbFrameMode2Interrupt = false;
        retractableCgbMode2Interrupt = false;
        interruptManager.releaseCpuAcceptance(InterruptType.LCDC);
    }

    void onScxWrite() {
        refreshGpuTiming();
        scxChangedSinceMode0Event = true;
        statEvaluationDirty = true;
    }

    boolean isMode0InterruptSourceOnly() {
        return enableBits == 0x08;
    }

    boolean isMode2InterruptSourceOnly() {
        return enableBits == 0x20;
    }

    boolean isMode1InterruptSourceOnly() {
        return enableBits == 0x10;
    }

    /**
     * Models the CGB's LYC write conflicts around the LY latch points.
     */
    public void onLycWrite(int oldValue, int newValue) {
        refreshGpuTiming();
        statEvaluationDirty = true;
        int writtenLyc = newValue & 0xff;
        long writtenValueEvent = scheduleLycIrqEvent(enableBits, writtenLyc);
        if (gbc && timing.lcdEnabled
                && writtenLyc == registeredLy
                && writtenValueEvent - lycIrqClock > 456) {
            suppressedLycIrqLine = writtenLyc;
        }
        if (gbc && !timing.doubleSpeed && timing.lcdEnabled
                && timing.ticksInLine >= 454
                && lycComparatorSignal
                && (enableBits & 0x40) != 0
                && oldValue == timing.visibleLy
                && oldValue != writtenLyc) {
            // The dot-454 compare has already reached the interrupt latch. A CPU
            // write in the following slot can change readable FF45, but cannot
            // withdraw that captured request.
            interruptManager.requestInterrupt(InterruptType.LCDC);
            intLine = true;
        }
        updateLycIrqRegisters(enableBits, writtenLyc);
        if (oldValue != writtenLyc) {
            queueModeIrqLycChange(writtenLyc);
        }
        if (!timing.lcdEnabled || oldValue == writtenLyc) {
            return;
        }
        if (lycRegChangeTriggersStatIrq(oldValue, writtenLyc)) {
            if (gbc) {
                // A write-created comparison crosses the CGB interrupt latch after
                // the write cycle. Native double speed reaches the next CPU sampling
                // edge in three PPU clocks; normal speed uses the five-clock response
                // measured by Gambatte's LYC register-change path.
                int responseClocks = timing.doubleSpeed ? 3 : 5;
                pendingLycWriteIrq = Math.min(
                        pendingLycWriteIrq, lycIrqClock + responseClocks);
            } else {
                interruptManager.requestInterrupt(InterruptType.LCDC);
            }
        }
        if (!gbc) {
            return;
        }
        int ticksInLine = timing.ticksInLine;
        if (ticksInLine == 448 || (timing.line == 153 && ticksInLine == 0)) {
            // A write in the complementary conflict window is not observed by
            // the comparator until the next LY latch point.
            lycWriteSuppressed = true;
        }
    }

    private boolean computeIntLine(int enable) {
        boolean line = (enable & 0b01000000) != 0 && intCoincidence;
        if (timing.lcdEnabled) {
            boolean holdCgbFrameLycToMode2Handoff = gbc
                    && !timing.dmgCompat
                    && timing.line == 153
                    && intCoincidence
                    && lycIrqValueSource == 0
                    && (lastModeIrqStatWriteOld & 0x40) != 0
                    && (enable & 0x60) == 0x20
                    && lastModeIrqStatWriteLineTick >= 0
                    && timing.ticksInLine >= lastModeIrqStatWriteLineTick
                    && cpuCyclesSince(lastModeIrqStatWriteClock) <= 4;
            line |= holdCgbFrameLycToMode2Handoff;
            boolean suppressTailCgbMode0Enable = gbc
                    && timing.line < 144
                    && (lastModeIrqStatWriteOld & 0x08) == 0
                    && (enable & 0x08) != 0
                    && lastModeIrqStatWriteLineTick >= 0
                    && timing.ticksInLine >= lastModeIrqStatWriteLineTick
                    && 456 - lastModeIrqStatWriteLineTick <= 6
                    && lycIrqClock - lastModeIrqStatWriteClock <= 6
                    && !lycComparatorSignal;
            boolean suppressLateCgbModeEnable = gbc
                    && timing.line == 143
                    && lastModeIrqStatWriteLineTick >= 453
                    && lycIrqClock - lastModeIrqStatWriteClock <= 2
                    && (enable & 0x28) != 0;
            line |= !suppressLateCgbModeEnable && !suppressTailCgbMode0Enable
                    && (enable & 0b00001000) != 0 && timing.mode0IntWindow;
            boolean suppressLateCgbMode1Enable = gbc
                    && timing.line == 153
                    && lastModeIrqStatWriteLineTick >= (timing.doubleSpeed ? 453 : 452)
                    && lycIrqClock - lastModeIrqStatWriteClock <= 4
                    && (lastModeIrqStatWriteOld & 0x20) == 0
                    && (enable & 0x10) != 0;
            line |= !suppressLateCgbMode1Enable
                    && (enable & 0b00010000) != 0 && isMode1IrqLineActive();
            boolean suppressLateDmgLine0Mode2Enable = !gbc
                    && timing.line == 0 && timing.ticksInLine < 4
                    && lastModeIrqStatWriteLineTick >= 0
                    && lastModeIrqStatWriteLineTick < 4
                    && lycIrqClock - lastModeIrqStatWriteClock <= 4
                    && (lastModeIrqStatWriteOld & 0x20) == 0;
            line |= !suppressLateCgbModeEnable && !suppressLateDmgLine0Mode2Enable
                    && (enable & 0b00100000) != 0 && timing.mode2IntWindow;
        }
        return line;
    }

    private boolean isMode1IrqLineActive() {
        if (gbc && timing.line == 143) {
            return timing.ticksInLine >= 454;
        }
        return timing.mode1IntWindow;
    }

    private void updateLycIrqRegisters(int stat, int lyc) {
        if (stat != lycIrqStatSource || lyc != lycIrqValueSource) {
            lastLycIrqRegisterChangeClock = lycIrqClock;
        }
        long sourceEvent = scheduleLycIrqEvent(stat, lyc);
        long oldEvent = nextLycIrqEvent;
        nextLycIrqEvent = Math.min(oldEvent, sourceEvent);
        lycIrqStatSource = stat;
        lycIrqValueSource = lyc;

        long cpuCyclesToEvent = cpuCyclesUntil(nextLycIrqEvent);
        if (gbc) {
            int lycCaptureWindow = 6 + 4 * (timing.doubleSpeed ? 1 : 0);
            if (cpuCyclesToEvent > lycCaptureWindow
                    || (sourceEvent != nextLycIrqEvent
                    && cpuCyclesToEvent > 2)) {
                lycIrqValueLatch = lyc;
            }
            if (cpuCyclesToEvent > 2) {
                lycIrqStatLatch = stat;
            }
        } else {
            if (cpuCyclesToEvent > 4 || sourceEvent != nextLycIrqEvent) {
                lycIrqValueLatch = lyc;
            }
            lycIrqStatLatch = stat;
        }
    }

    private void queueModeIrqStatChange(int stat) {
        commitPendingModeIrqRegisters();
        commitPendingMode0IrqRegisters();
        lastModeIrqStatWriteClock = lycIrqClock;
        lastModeIrqStatWriteLineTick = timing.ticksInLine;
        lastModeIrqStatWriteOld = enableBits;
        pendingModeIrqStat = stat;
        pendingModeIrqStatClock = lycIrqClock;
        pendingMode0IrqStat = stat;
        pendingMode0IrqStatClock = lycIrqClock;
        if (!gbc) {
            modeIrqStatLatch = stat;
            pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;
            mode0IrqStatLatch = stat;
            pendingMode0IrqStatClock = NO_LYC_IRQ_EVENT;
        }
        if (timing.lcdEnabled && (stat & 0x08) != 0) {
            mode0EventArmed = true;
        }
    }

    private void queueModeIrqLycChange(int lyc) {
        commitPendingModeIrqRegisters();
        commitPendingMode0IrqRegisters();
        pendingModeIrqLyc = lyc;
        pendingModeIrqLycClock = lycIrqClock;
        pendingMode0IrqLyc = lyc;
        pendingMode0IrqLycClock = lycIrqClock;
    }

    private boolean commitPendingModeIrqRegisters() {
        boolean changed = false;
        if (pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqStatClock) > (gbc ? 2 : 0)) {
            modeIrqStatLatch = pendingModeIrqStat;
            pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;
            changed = true;
        }
        int lycCaptureDelay = gbc ? (timing.doubleSpeed ? 5 : 6) : 1;
        if (pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqLycClock) > lycCaptureDelay) {
            modeIrqLycLatch = pendingModeIrqLyc;
            pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
            changed = true;
        }
        return changed;
    }

    private boolean commitPendingMode0IrqRegisters() {
        boolean changed = false;
        int statCaptureDelay;
        if (!gbc) {
            statCaptureDelay = 0;
        } else if (timing.doubleSpeed) {
            statCaptureDelay = 6;
        } else if ((registers.get(SCX) & 7) == 0) {
            statCaptureDelay = 6;
        } else {
            statCaptureDelay = scxChangedSinceMode0Event ? 4 : 8;
        }
        if (pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingMode0IrqStatClock) > statCaptureDelay) {
            mode0IrqStatLatch = pendingMode0IrqStat;
            pendingMode0IrqStatClock = NO_LYC_IRQ_EVENT;
            changed = true;
        }
        int lycCaptureDelay = gbc
                ? (timing.doubleSpeed || scxChangedSinceMode0Event ? 8 : 10)
                : 1;
        if (pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingMode0IrqLycClock) > lycCaptureDelay) {
            mode0IrqLycLatch = pendingMode0IrqLyc;
            pendingMode0IrqLycClock = NO_LYC_IRQ_EVENT;
            changed = true;
        }
        return changed;
    }

    private long cpuCyclesSince(long clock) {
        int cpuClocksPerDot = 4 / timing.cpuMachineCycleDots;
        return Math.max(0, lycIrqClock - clock) * cpuClocksPerDot
                + getNormalSpeedClockPhase();
    }

    private boolean updateModeIrqEvents(boolean lcdcInterruptFlagWriteClear) {
        boolean currentLcdEnabled = timing.lcdEnabled;
        boolean currentGbc = gbc;
        boolean currentDmgCompat = timing.dmgCompat;
        int currentLine = timing.line;
        int currentTicksInLine = timing.ticksInLine;
        boolean currentFirstLine = timing.firstLine;
        boolean currentDoubleSpeed = timing.doubleSpeed;
        boolean currentNativeDoubleSpeed = timing.nativeDoubleSpeed;
        boolean deferredCgbMode2Phase = currentGbc && currentLcdEnabled
                && !currentFirstLine && currentLine < 144
                && currentTicksInLine >= timing.earlyLineEdgeTick
                && currentTicksInLine <= 452;
        if (!currentLcdEnabled) {
            previousMode0Window = false;
            previousMode1Window = false;
            previousMode2Window = false;
            return false;
        }

        boolean mode0Window = timing.mode0IntWindow;
        boolean mode1Window = timing.mode1IntWindow;
        boolean mode2Window = timing.mode2IntWindow;
        boolean mode0Event = mode0Window && !previousMode0Window;
        boolean mode1Event = mode1Window && !previousMode1Window;
        boolean mode2Event = mode2Window && !previousMode2Window;
        boolean frameMode2AcknowledgeWins = mode2Event
                && isFrameMode2Event()
                && recentFrameMode2AcknowledgeWins();
        boolean mode0AcknowledgeWins = mode0Event
                && recentMode0AcknowledgeWins();
        previousMode0Window = mode0Window;
        previousMode1Window = mode1Window;
        previousMode2Window = mode2Window;

        boolean suppressNaturalModeEdge = false;
        if (mode2Event && mode2EventIsScheduled()) {
            if (deferredCgbMode2Phase
                    && currentTicksInLine == timing.earlyLineEdgeTick) {
                // The early CGB mode level only schedules the line-tail MSTAT event.
                // Its FF41/FF45 blockers are captured at the event itself, after
                // their independent write windows have elapsed.
                boolean ifHigh =
                        interruptManager.isInterruptFlagSet(InterruptType.LCDC);
                pendingCgbMode2Interrupt = !ifHigh || (currentDoubleSpeed && !intLine);
                pendingCgbMode2IfHighAtCapture =
                        currentDoubleSpeed && pendingCgbMode2Interrupt && ifHigh;
                cgbMode2CapturedAtLineEdge = pendingCgbMode2Interrupt;
            } else {
                if (currentGbc) {
                    // Coffee GB publishes the CGB mode-2 request on its early CPU
                    // synchronizer edge. Relative to that edge, the six-clock
                    // register capture window has already elapsed.
                    commitPendingModeIrqLycImmediately();
                }
                int eventLy = currentLine == 0 && currentTicksInLine < 4
                        ? 0 : incrementLy(currentLine);
                boolean blockedByM1 = eventLy == 0 && (modeIrqStatLatch & 0x10) != 0;
                int precedingLy = eventLy == 0 ? 0 : eventLy - 1;
                boolean blockedByLyc = (modeIrqStatLatch & 0x40) != 0
                        && precedingLy == modeIrqLycLatch;
                if (blockedByM1 || blockedByLyc) {
                    suppressNaturalModeEdge = true;
                } else if (currentGbc && !currentDmgCompat
                        && !currentDoubleSpeed && currentLine == 153
                        && currentTicksInLine == 454) {
                    boolean capturedRequest =
                            !interruptManager.isInterruptFlagSet(InterruptType.LCDC)
                                    && !frameMode2AcknowledgeWins;
                    boolean capturedLyc153Source = (modeIrqStatLatch & 0x40) != 0
                            && modeIrqLycLatch == 153;
                    if (capturedRequest && capturedLyc153Source) {
                        // The outgoing LYC=153 source gives the frame event its
                        // dot-454 IF phase. A following IF clear must be able to
                        // consume it before rollover.
                        interruptManager.requestMode2InterruptBeforeCpuAcceptance(false);
                    } else {
                        pendingCgbFrameMode2Interrupt = capturedRequest && !intLine;
                    }
                    suppressNaturalModeEdge = true;
                }
                refreshModeIrqLatches(true);
            }
        }
        if (frameMode2AcknowledgeWins) {
            // The frame-boundary mode-2 edge and an LCDC acknowledge share one
            // set-dominant capture window. When the acknowledge owns that slot,
            // retain the new STAT level without reasserting IF.
            suppressNaturalModeEdge = true;
        }
        // While the CGB's early mode level is waiting for the explicit MSTAT
        // event, keep the shared level synchronized without publishing a second,
        // combinational edge from a late FF41 write.
        suppressNaturalModeEdge |= deferredCgbMode2Phase
                && (pendingCgbMode2Interrupt || (enableBits & 0x20) != 0);
        if (mode1Event) {
            if (currentGbc && currentLine == 143) {
                cgbMode1IfClearAtCapture =
                        !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
            } else {
                refreshModeIrqLatches(false);
            }
            if (!currentGbc
                    && dmgLyc143Mode1CaptureClock != NO_LYC_IRQ_EVENT
                    && lastLcdcInterruptAcknowledgeClock
                    > dmgLyc143Mode1CaptureClock) {
                suppressNaturalModeEdge = true;
            }
            dmgLyc143Mode1CaptureClock = NO_LYC_IRQ_EVENT;
        }
        if (!currentGbc && currentLine == 143
                && currentTicksInLine == 448) {
            dmgLyc143Mode1CaptureClock = NO_LYC_IRQ_EVENT;
            if ((enableBits & 0x50) == 0x50 && intCoincidence) {
                if (interruptManager.isInterruptFlagSet(InterruptType.LCDC)) {
                    dmgLyc143Mode1CaptureClock = lycIrqClock;
                } else if (lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                        && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= 3) {
                    interruptManager.requestInterrupt(InterruptType.LCDC);
                }
            }
        }
        if (currentNativeDoubleSpeed && currentLine == 143
                && currentTicksInLine == 452) {
            cgbMode1IfClearAtCapture =
                    !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
        }
        if (currentGbc && currentLine == 143 && currentTicksInLine == 454) {
            boolean blockedByCapturedMode = (modeIrqStatLatch & 0x28) != 0;
            pendingCgbMode1Interrupt = (enableBits & 0x10) != 0
                    && !blockedByCapturedMode && cgbMode1IfClearAtCapture;
            if (currentNativeDoubleSpeed && pendingCgbMode1Interrupt) {
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.LCDC);
                pendingCgbMode1Interrupt = false;
            }
            if ((enableBits & 0x10) != 0) {
                // Synchronize the shared STAT level without manufacturing an edge;
                // the captured mode-1 event above owns interrupt publication.
                suppressNaturalModeEdge = true;
            }
            refreshModeIrqLatches(false);
        }
        if (currentGbc && !currentDoubleSpeed && currentLine == 143
                && currentTicksInLine == 455 && pendingCgbMode1Interrupt) {
            boolean newlyAsserted =
                    !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
            interruptManager.requestInterrupt(InterruptType.LCDC);
            if (newlyAsserted && timing.statModeLatchRephasedBySpeedSwitch) {
                interruptManager.maskLcdcUntilNextPeripheralTick();
            }
            pendingCgbMode1Interrupt = false;
        }
        if (mode0Event && mode0EventArmed) {
            boolean enabled = ((enableBits | mode0IrqStatLatch) & 0x08) != 0;
            boolean blockedByLyc = (mode0IrqStatLatch & 0x40) != 0
                    && currentLine == mode0IrqLycLatch;
            if (!enabled || blockedByLyc) {
                suppressNaturalModeEdge = true;
            } else if (mode0AcknowledgeWins
                    || (!currentGbc && lcdcInterruptFlagWriteClear)) {
                // At normal speed, an acknowledge already in the mode-0 capture
                // window consumes this occurrence. A same-slot explicit IF clear
                // similarly wins on DMG. Synchronize the shared STAT level without
                // publishing the edge again.
                suppressNaturalModeEdge = true;
            } else if (cpuMode0InstructionWinsAcceptance) {
                interruptManager.requestPhasedInterruptAfterInstruction(
                        InterruptType.LCDC);
                suppressNaturalModeEdge = true;
            } else if (cpuMode0InterruptDispatchPhased) {
                interruptManager.requestPhasedInterruptBeforeHaltWake(
                        InterruptType.LCDC);
                suppressNaturalModeEdge = true;
            } else {
                if (currentGbc && !currentDmgCompat
                        && cpuInterruptFlagReadMaskTicks > 0
                        && mode0InterruptReadSamplesOldLatch()) {
                    interruptManager.maskMode0LcdcReadForTicks(
                            cpuInterruptFlagReadMaskTicks);
                }
                if ((enableBits & 0x08) == 0) {
                    requestMode0InterruptEvent();
                    suppressNaturalModeEdge = true;
                }
            }
            refreshModeIrqLatches(true);
            refreshMode0IrqLatches();
            mode0EventArmed = (enableBits & 0x08) != 0;
        }
        if (mode0Event) {
            scxChangedSinceMode0Event = false;
        }
        return suppressNaturalModeEdge;
    }

    private void commitPendingModeIrqLycImmediately() {
        if (pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                && pendingModeIrqLycClock < lycIrqClock) {
            modeIrqLycLatch = pendingModeIrqLyc;
            pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
        }
    }

    private boolean isDeferredCgbMode2Phase() {
        return gbc && timing.lcdEnabled && !timing.firstLine
                && timing.line < 144
                && timing.ticksInLine >= timing.earlyLineEdgeTick
                && timing.ticksInLine <= 452;
    }

    private boolean canReschedulePendingCgbMode2Event() {
        if (!isDeferredCgbMode2Phase()) {
            return false;
        }
        int eventCpuTimestamp = timing.doubleSpeed ? 452 : 450;
        return timing.ticksInLine < eventCpuTimestamp
                || (!timing.doubleSpeed && timing.ticksInLine == eventCpuTimestamp);
    }

    private void publishPendingCgbMode2Event() {
        commitPendingModeIrqRegisters();
        if (!timing.doubleSpeed
                && pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqStatClock) == 2) {
            modeIrqStatLatch = pendingModeIrqStat;
            pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;
        }
        if (!timing.doubleSpeed
                && pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqLycClock) == 6) {
            // Coffee GB retires the FF45 callback one clock phase later than the
            // MSTAT scheduler's timestamp. Equality here is Gambatte's strict
            // six-clock capture boundary for the mode-2 event only.
            modeIrqLycLatch = pendingModeIrqLyc;
            pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
        }
        int eventLy = incrementLy(timing.line);
        boolean blockedByM1 = eventLy == 0 && (modeIrqStatLatch & 0x10) != 0;
        int precedingLy = eventLy == 0 ? 0 : eventLy - 1;
        boolean blockedByLyc = (modeIrqStatLatch & 0x40) != 0
                && precedingLy == modeIrqLycLatch;
        retractableCgbMode2Interrupt = false;
        pendingCgbMode2LateReplay = false;
        pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;
        if (!blockedByM1 && !blockedByLyc) {
            boolean newlyAsserted =
                    !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
            interruptManager.requestMode2InterruptBeforeCpuAcceptance(false);
            retractableCgbMode2Interrupt = timing.doubleSpeed && newlyAsserted;
            pendingCgbMode2LateReplay = timing.doubleSpeed && timing.line < 143;
            pendingCgbMode2PublicationClock = pendingCgbMode2LateReplay
                    ? lycIrqClock : NO_LYC_IRQ_EVENT;
        }
        pendingCgbMode2Interrupt = false;
        if (!pendingCgbMode2LateReplay) {
            pendingCgbMode2IfHighAtCapture = false;
            cgbMode2CapturedAtLineEdge = false;
        }
        refreshModeIrqLatches(true);
    }

    private void publishPendingCgbMode2Replay() {
        boolean ifHigh = interruptManager.isInterruptFlagSet(InterruptType.LCDC);
        boolean acknowledgeLostRace = pendingCgbMode2IfHighAtCapture
                && (lastLcdcInterruptAcknowledgeClock == Long.MIN_VALUE
                || lycIrqClock - lastLcdcInterruptAcknowledgeClock < 2);
        boolean publishedRequestWasAcknowledged = !pendingCgbMode2IfHighAtCapture
                && lastLcdcInterruptAcknowledgeClock >= pendingCgbMode2PublicationClock;
        if (!ifHigh && !acknowledgeLostRace && !publishedRequestWasAcknowledged) {
            interruptManager.requestMode2InterruptBeforeCpuAcceptance(false);
        }
        pendingCgbMode2LateReplay = false;
        pendingCgbMode2IfHighAtCapture = false;
        pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;
        cgbMode2CapturedAtLineEdge = false;
    }

    private boolean mode2EventIsScheduled() {
        if ((enableBits & 0x20) == 0) {
            return false;
        }
        boolean line0Event = timing.line == 0 && timing.ticksInLine < 4;
        return line0Event || (enableBits & 0x08) == 0;
    }

    private void refreshModeIrqLatches(boolean refreshLyc) {
        modeIrqStatLatch = enableBits;
        pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;
        if (refreshLyc) {
            modeIrqLycLatch = lycIrqValueSource;
            pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
        }
    }

    private void refreshMode0IrqLatches() {
        mode0IrqStatLatch = enableBits;
        pendingMode0IrqStatClock = NO_LYC_IRQ_EVENT;
        mode0IrqLycLatch = lycIrqValueSource;
        pendingMode0IrqLycClock = NO_LYC_IRQ_EVENT;
    }

    private void requestMode0InterruptEvent() {
        if (gbc && !timing.dmgCompat && !timing.doubleSpeed
                && timing.line == 0 && !timing.firstLine
                && (registers.get(SCX) & 7) == 0) {
            // At normal speed and fine-scroll phase zero, line zero's mode-0
            // level reaches STAT on this dot while IF settles after the same-dot
            // CPU read phase.
            pendingCgbMode0Interrupt = true;
        } else if (!gbc && gpu.hasObjectsOnLine()) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        } else {
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
        }
    }

    private long cpuCyclesUntil(long eventClock) {
        if (eventClock == NO_LYC_IRQ_EVENT) {
            return Long.MAX_VALUE;
        }
        int cpuClocksPerDot = 4 / timing.cpuMachineCycleDots;
        return Math.max(0, eventClock - lycIrqClock) * cpuClocksPerDot
                + getNormalSpeedClockPhase();
    }

    private boolean lycRegChangeTriggersStatIrq(int oldValue, int newValue) {
        if ((enableBits & 0x40) == 0 || newValue >= 154
                || lycWriteTriggerBlockedByMode(newValue)) {
            return false;
        }

        LycComparison comparison = getLycComparison();
        int doubleSpeed = timing.doubleSpeed ? 1 : 0;
        if (comparison.cpuCyclesUntilNextLy <= 4 + 4 * doubleSpeed
                + 2 * (gbc ? 1 : 0)) {
            if (oldValue == comparison.ly
                    && comparison.cpuCyclesUntilNextLy > 2 * (gbc ? 1 : 0)) {
                return false;
            }
            comparison = new LycComparison(incrementLy(comparison.ly),
                    comparison.cpuCyclesUntilNextLy);
        }
        return newValue == comparison.ly;
    }

    private boolean lycWriteTriggerBlockedByMode(int newValue) {
        int timeToNextLy = cpuCyclesToNextLy();
        if (timing.line < 144) {
            return (enableBits & 0x08) != 0
                    && timing.mode0IntWindow
                    && newValue == timing.line;
        }
        if (gbc && !timing.doubleSpeed && timing.line == 153) {
            // FF45 is committed near the end of its CPU write cycle. At the short
            // LY=0 hand-off that is two clocks later than the dot timestamp used by
            // the rest of this model (one after a normal-speed clock rephase).
            timeToNextLy -= 2 - getNormalSpeedClockPhase();
        }
        int doubleSpeed = timing.doubleSpeed ? 1 : 0;
        return (enableBits & 0x10) != 0
                && !(timing.line == 153
                && timeToNextLy <= 2 + 2 * doubleSpeed + 2 * (gbc ? 1 : 0));
    }

    private boolean statChangeTriggersStatIrq(int oldStat, int newStat) {
        int newlyEnabled = newStat & ~oldStat & 0x78;
        if (newlyEnabled == 0) {
            return false;
        }

        int ly = timing.line;
        int timeToNextLy = cpuCyclesToNextLy();
        int doubleSpeed = timing.doubleSpeed ? 1 : 0;
        LycComparison comparison = getLycComparison();
        boolean lycPeriod = comparison.ly == lycIrqValueSource
                && comparison.cpuCyclesUntilNextLy > 2;
        boolean cgbVblankLycToMode1Handoff = gbc
                && ly >= 144 && ly < 153
                && (newlyEnabled & 0x10) != 0
                && (newStat & 0x40) == 0
                && timeToNextLy <= 6;
        if (lycPeriod && (oldStat & 0x40) != 0
                && !cgbVblankLycToMode1Handoff) {
            return false;
        }

        boolean m0LycOrM1;
        if (ly < 143 || (ly == 143 && timeToNextLy > 458 * (1 + doubleSpeed))) {
            boolean normalSpeedMode0LevelStillSettling = !timing.doubleSpeed
                    && timing.ticksInLine - timing.mode0InterruptTick <= 20;
            boolean doubleSpeedMode0LevelAlreadyRetiring = timing.doubleSpeed
                    && timeToNextLy <= 8;
            boolean blockedByActiveMode0 = timing.mode0IntWindow
                    && (oldStat & 0x08) != 0
                    && (newlyEnabled & 0x40) != 0
                    && !normalSpeedMode0LevelStillSettling
                    && !doubleSpeedMode0LevelAlreadyRetiring;
            if (blockedByActiveMode0) {
                // The predictive mode-0 event reaches IF before its shared STAT
                // level can block a newly selected LYC source. Once that level has
                // settled, changing FF41 must not manufacture a second rising edge.
                m0LycOrM1 = false;
            } else {
                // Mode 0 only arms the next event here; the live combinational
                // candidate is the newly selected LYC source.
                m0LycOrM1 = lycPeriod && (newStat & 0x40) != 0;
            }
        } else if ((oldStat & 0x10) != 0
                && (ly < 153 || timeToNextLy > 3 + 3 * doubleSpeed
                + 4 * getNormalSpeedClockPhase())) {
            m0LycOrM1 = false;
        } else {
            m0LycOrM1 = ((newStat & 0x10) != 0
                    && (ly < 153 || timeToNextLy > 4 + 2 * doubleSpeed))
                    || (lycPeriod && (newStat & 0x40) != 0);
        }

        boolean m2 = false;
        if ((oldStat & 0x20) == 0 && (newStat & 0x28) == 0x20) {
            if (ly < 143) {
                m2 = timeToNextLy <= 4 * (1 + doubleSpeed)
                        && (timeToNextLy > 2
                        || (!timing.doubleSpeed && timeToNextLy == 2));
                if (timing.doubleSpeed && ly > 0 && timing.ticksInLine <= 2) {
                    // At double speed the CPU write callback retires two dots after
                    // the FF41 bus phase. Preserve the just-crossed mode-2 boundary.
                    m2 = true;
                }
            } else if (ly == 143) {
                m2 = timeToNextLy <= 4 * (1 + doubleSpeed)
                        && timeToNextLy > 4 + 2 * doubleSpeed;
            } else if (ly == 153) {
                m2 = timeToNextLy <= 2 * (1 + doubleSpeed) && timeToNextLy > 2;
            }
        }
        return m0LycOrM1 || m2;
    }

    private LycComparison getLycComparison() {
        int line = timing.line;
        int timeToNextLy = cpuCyclesToNextLy();
        int doubleSpeed = timing.doubleSpeed ? 1 : 0;
        int lineCpuCycles = (timing.firstLine ? 455 : 456) * (1 + doubleSpeed);
        if (line == 153) {
            timeToNextLy -= lineCpuCycles - 6 - 6 * doubleSpeed;
            if (timeToNextLy <= 0) {
                line = 0;
                timeToNextLy += lineCpuCycles;
            }
        } else {
            timeToNextLy -= 2 + 2 * doubleSpeed;
            if (timeToNextLy <= 0) {
                line++;
                timeToNextLy += lineCpuCycles;
            }
        }
        return new LycComparison(line, timeToNextLy);
    }

    private int cpuCyclesToNextLy() {
        int lineDots = timing.firstLine ? 455 : 456;
        return Math.max(0, lineDots - timing.ticksInLine)
                * (timing.doubleSpeed ? 2 : 1) + getNormalSpeedClockPhase();
    }

    private int getNormalSpeedClockPhase() {
        return !timing.doubleSpeed && timing.statModeLatchRephasedBySpeedSwitch ? 1 : 0;
    }

    private static int incrementLy(int ly) {
        return ly == 153 ? 0 : ly + 1;
    }

    private void fireLycIrqEvent() {
        int comparedLy = comparedLycIrqLine();
        boolean enabled = ((lycIrqStatLatch | lycIrqStatSource) & 0x40) != 0;
        boolean blockedByMode = comparedLy > 0 && comparedLy <= 144
                ? (lycIrqStatLatch & 0x20) != 0
                : (lycIrqStatLatch & 0x10) != 0;
        boolean writeSensitiveEvent = lycIrqClock - lastLycIrqRegisterChangeClock <= 32
                || lycIrqStatLatch != lycIrqStatSource
                || lycIrqValueLatch != lycIrqValueSource;
        boolean capturedComparison = enabled && lycIrqValueLatch == comparedLy;
        if (capturedComparison && blockedByMode) {
            // A mode-1/mode-2 STAT source masks the comparator event itself. Keep
            // that decision for the whole equality period: disabling the masking
            // source just after the event must not recreate the missed LYC edge.
            modeBlockedLycIrqLine = comparedLy;
        }
        boolean clearedByRecentAcknowledge = capturedComparison && !blockedByMode
                && timing.line == 153 && recentLyc0AcknowledgeWins();
        if (capturedComparison && !blockedByMode && !clearedByRecentAcknowledge
                && (writeSensitiveEvent || timing.line == 153)) {
            if (timing.line == 153) {
                if (writeSensitiveEvent || timing.doubleSpeed) {
                    interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                } else {
                    // The LY=0 comparator and a CPU read in the same dot use opposite
                    // clock phases. Publish a stable event on the following dot so the
                    // in-flight read sees the old IF value; the request still survives
                    // an FF41/FF45 write in that intervening CPU slot.
                    pendingLycComparatorIrq = Math.min(
                            pendingLycComparatorIrq, lycIrqClock + 1);
                }
            } else if (timing.doubleSpeed) {
                interruptManager.requestInterrupt(InterruptType.LCDC);
            } else if (lycIrqClock - lastLycIrqRegisterChangeClock <= 16) {
                // A source write inside the comparator's response window bypasses
                // the ordinary line-boundary CPU synchronizer. The IF latch is the
                // same one; only its CPU-input phase differs.
                interruptManager.requestInterrupt(InterruptType.LCDC);
            } else {
                // The comparator flag is readable in the preceding line's tail,
                // while the CPU's interrupt input samples it on the line boundary.
                interruptManager.requestInterruptBeforeCpuAcceptance(InterruptType.LCDC);
                releaseTailLycCpuAcceptance = true;
            }
            if (timing.line != 153 || writeSensitiveEvent || timing.doubleSpeed) {
                intLine = true;
            }
        } else if (clearedByRecentAcknowledge) {
            // The acknowledge lands after the frame-tail comparison has entered
            // the shared STAT latch. Clearing IF consumes this occurrence; retain
            // the line level so readable LY=0 cannot manufacture a second edge.
            suppressedLycIrqLine = comparedLy;
            intLine = true;
        }

        if (lycIrqValueLatch != comparedLy && lycIrqValueSource == comparedLy) {
            // The FF45 source changed in the comparator's capture window. The
            // scheduled comparator retained the old value, so the readable LY latch
            // must not recreate the missed edge at the following line boundary.
            suppressedLycIrqLine = comparedLy;
        }

        lycIrqValueLatch = lycIrqValueSource;
        lycIrqStatLatch = lycIrqStatSource;
        nextLycIrqEvent = scheduleLycIrqEvent(lycIrqStatLatch, lycIrqValueLatch);
    }

    private long scheduleLycIrqEvent(int stat, int lyc) {
        if (gpu == null || !timing.lcdEnabled || (stat & 0x40) == 0 || lyc >= 154) {
            return NO_LYC_IRQ_EVENT;
        }

        int targetLine = lyc == 0 ? 153 : lyc - 1;
        int targetTick = lyc == 0 ? 6 : 454;
        int currentLine = timing.line;
        int currentTick = timing.ticksInLine;
        long distance;
        if (currentLine == targetLine && currentTick < targetTick) {
            distance = targetTick - currentTick;
        } else {
            distance = (timing.firstLine ? 455L : 456L) - currentTick;
            currentLine = (currentLine + 1) % 154;
            while (currentLine != targetLine) {
                distance += 456;
                currentLine = (currentLine + 1) % 154;
            }
            distance += targetTick;
        }
        return lycIrqClock + distance;
    }

    private int comparedLycIrqLine() {
        if (timing.line == 153) {
            return 0;
        }
        return timing.line + 1;
    }

    private void updateIntLine(boolean newLine) {
        if (newLine && !intLine) {
            boolean line153ComparisonEdge = coincidence && registeredLy == 153
                    && (enableBits & 0b01000000) != 0
                    && ((timing.line == 153 && timing.ticksInLine == 0)
                    || (timing.nativeDoubleSpeed && timing.line == 152
                    && timing.ticksInLine == CGB_DOUBLE_TAIL_LATCH));
            if (line153ComparisonEdge && recentLyc153AcknowledgeWins()) {
                // The comparator was already high when the CPU acknowledge cleared
                // IF, so preserve its shared level without issuing a second edge.
                intLine = newLine;
                return;
            }
            int earlyMode2Edge = timing.earlyLineEdgeTick;
            boolean nativeDoubleTailLycLatch = timing.nativeDoubleSpeed
                    && timing.ticksInLine == CGB_DOUBLE_TAIL_LATCH
                    && timing.line != 153;
            if (nativeDoubleTailLycLatch) {
                // IF is already readable in the line tail, but running and halted
                // CPUs both accept this direct edge only after the line rolls over.
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.LCDC);
            } else if (timing.line < 144 && timing.ticksInLine == earlyMode2Edge) {
                if (recentDmgMode2AcknowledgeWins()) {
                    // During the final three dots the DMG has already sampled the
                    // next mode-2 source. Keep its level high without recreating IF.
                    intLine = newLine;
                    return;
                }
                if (gbc || gpu.hasObjectsOnLine()) {
                    if (gbc) {
                        interruptManager.requestMode2InterruptBeforeCpuAcceptance(
                                timing.firstLine);
                    } else {
                        interruptManager.requestInterruptBeforeCpuAcceptance(InterruptType.LCDC);
                    }
                } else {
                    interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                }
            } else if (timing.line == 153
                    && timing.ticksInLine == getNewFrameLycEdgeTick()
                    && coincidence && (enableBits & 0b01000000) != 0) {
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            } else if (gbc && !timing.dmgCompat
                    && timing.doubleSpeed
                    && timing.line == 153 && timing.ticksInLine == 454) {
                // The line-zero M2 request is published in the final line's tail,
                // but CPU acceptance remains synchronized to the line-zero rollover.
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.LCDC);
            } else if (timing.mode0IntWindow) {
                requestMode0InterruptEvent();
            } else {
                interruptManager.requestInterrupt(InterruptType.LCDC);
            }
        }
        intLine = newLine;
    }

    private boolean recentLyc0AcknowledgeWins() {
        int captureWindow = timing.doubleSpeed ? 1 : gbc ? 4 : 6;
        return lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= captureWindow;
    }

    private boolean recentLyc153AcknowledgeWins() {
        int captureWindow = timing.doubleSpeed ? 1 : gbc ? 5 : 7;
        return lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= captureWindow;
    }

    private boolean isFrameMode2Event() {
        return gbc && !timing.dmgCompat
                ? timing.line == 153 && timing.ticksInLine == 454
                : timing.line == 0 && timing.ticksInLine < 4;
    }

    private boolean recentFrameMode2AcknowledgeWins() {
        int captureWindow = timing.doubleSpeed ? 1 : 7;
        return lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= captureWindow;
    }

    private boolean recentDmgMode2AcknowledgeWins() {
        return !gbc
                && lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= 3;
    }

    private boolean recentMode0AcknowledgeWins() {
        if (timing.doubleSpeed) {
            return false;
        }
        int captureWindow = gbc ? 3 : 5;
        return lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= captureWindow;
    }

    private boolean mode0InterruptReadSamplesOldLatch() {
        if (interruptManager.isInterruptFlagSet(InterruptType.LCDC)
                || lastLcdcInterruptAcknowledgeClock == Long.MIN_VALUE) {
            return false;
        }
        long acknowledgeAge = lycIrqClock - lastLcdcInterruptAcknowledgeClock;
        boolean acknowledgedThisLine = acknowledgeAge >= 0
                && acknowledgeAge <= timing.ticksInLine;
        // A normal-speed edge delayed beyond dot 253 has crossed the CPU read
        // synchronizer before its data phase, even if that phase is otherwise
        // identical to an earlier HBlank transition.
        return acknowledgedThisLine
                && (timing.doubleSpeed || timing.mode0InterruptTick <= 253);
    }

    private boolean recentVBlankAcknowledgeWins() {
        int captureWindow = timing.doubleSpeed ? 1 : 7;
        return lastVBlankInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastVBlankInterruptAcknowledgeClock <= captureWindow;
    }

    private int getNewFrameLycEdgeTick() {
        return timing.nativeDoubleSpeed ? NEW_FRAME_LYC_EDGE - 2 : NEW_FRAME_LYC_EDGE;
    }

    private int getNewFrameLycCpuAcceptTick() {
        return getNewFrameLycEdgeTick() + timing.cpuMachineCycleDots;
    }

    @Override
    public boolean accepts(int address) {
        return address == ADDRESS;
    }

    @Override
    public void setByte(int address, int value) {
        refreshGpuTiming();
        statEvaluationDirty = true;
        int newEnableBits = value & 0b01111000;
        boolean capturedDmgLycToModeHandoff = !gbc
                && timing.lcdEnabled
                && (enableBits & 0x40) != 0
                && lycIrqValueSource == timing.line
                && cpuCyclesToNextLy() <= 4
                && (((newEnableBits & 0x08) != 0 && timing.mode0IntWindow)
                || ((newEnableBits & 0x10) != 0 && timing.mode1IntWindow)
                || ((newEnableBits & 0x20) != 0 && timing.mode2IntWindow));
        if (capturedDmgLycToModeHandoff) {
            // The outgoing LYC source is still high at the final mode capture.
            // Preserve that shared level so the FF41 handoff cannot create an edge.
            intLine = true;
        }
        if (!gbc) {
            if ((value & 0b01111000) == 0
                    && timing.lcdEnabled
                    && timing.ticksInLine == 0
                    && !timing.mode0IntWindow
                    && !timing.mode1IntWindow
                    && !timing.mode2IntWindow
                    && interruptManager.isInterruptFlagSet(InterruptType.VBlank)) {
                // At the first visible-line latch the retiring VBlank request and the
                // FF41 write share an asynchronous read gate. The next IF read sees
                // bit 0 low even though the IF latch itself remains set.
                interruptManager.maskVBlankOnNextRead();
            }
            // DMG STAT write glitch: all interrupt sources are enabled for a moment
            // before the written data settles
            int glitchEnable = 0b01111000;
            if (timing.line == 0 && timing.ticksInLine < 4
                    && (enableBits & 0x20) == 0) {
                // The line-zero mode-2 event has already sampled FF41. Its transient
                // all-enabled write phase cannot arm that event retroactively.
                glitchEnable &= ~0x20;
            }
            if (timing.lcdEnabled
                    && timing.ticksInLine == 0
                    && (enableBits & 0b00101000) == 0b00001000) {
                // At the HBlank -> OAM boundary, an already-enabled HBlank source
                // masks the transient OAM source. Treating the write as a plain 0xff
                // here creates a second STAT edge and can recursively re-enter a
                // scanline handler (Initial D Gaiden).
                glitchEnable &= ~0b00100000;
            }
            boolean lineStartCoincidenceGlitch = timing.ticksInLine == 0
                    && coincidence && (glitchEnable & 0x40) != 0;
            updateIntLine(computeIntLine(glitchEnable) || lineStartCoincidenceGlitch);
        }
        if (timing.doubleSpeed && timing.ticksInLine <= 454
                && (newEnableBits & 0x20) == 0) {
            if (retractableCgbMode2Interrupt) {
                interruptManager.cancelMode2InterruptBeforeCpuAcceptance();
                retractableCgbMode2Interrupt = false;
            }
            // FF41 can withdraw the captured source and its late replay through
            // dot 454, before the MSTAT occurrence leaves the synchronizer.
            pendingCgbMode2Interrupt = false;
            pendingCgbMode2IfHighAtCapture = false;
            pendingCgbMode2LateReplay = false;
            pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;
            cgbMode2CapturedAtLineEdge = false;
        }
        if (timing.doubleSpeed && pendingCgbMode2LateReplay
                && timing.ticksInLine > 452 && timing.ticksInLine <= 454
                && newEnableBits != enableBits) {
            // FF41 is still inside the late MSTAT capture window. Changing its
            // source mix withdraws the replay of the dot-452 mode-2 occurrence.
            pendingCgbMode2LateReplay = false;
            pendingCgbMode2IfHighAtCapture = false;
            pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;
            cgbMode2CapturedAtLineEdge = false;
        }
        if (pendingCgbFrameMode2Interrupt && gbc && !timing.dmgCompat
                && !timing.doubleSpeed && timing.line == 153
                && timing.ticksInLine <= 454 && (newEnableBits & 0x20) == 0) {
            // A write in the capture dot can still withdraw the frame mode-2
            // occurrence before it is published at rollover.
            pendingCgbFrameMode2Interrupt = false;
        }
        if (pendingCgbMode1Interrupt && gbc && !timing.doubleSpeed
                && timing.line == 143 && timing.ticksInLine == 454
                && (newEnableBits & 0x10) == 0) {
            // The normal-speed CGB captures mode 1 at dot 454 but publishes IF
            // one dot later. A same-dot FF41 write still reaches the captured
            // enable latch; a write after rollover cannot withdraw the event.
            pendingCgbMode1Interrupt = false;
        }
        if (canReschedulePendingCgbMode2Event()) {
            boolean retainedLineEdgeCapture = pendingCgbMode2Interrupt
                    && cgbMode2CapturedAtLineEdge;
            pendingCgbMode2Interrupt = (newEnableBits & 0x28) == 0x20;
            pendingCgbMode2IfHighAtCapture = timing.doubleSpeed
                    && pendingCgbMode2Interrupt
                    && interruptManager.isInterruptFlagSet(InterruptType.LCDC);
            cgbMode2CapturedAtLineEdge = pendingCgbMode2Interrupt
                    && retainedLineEdgeCapture;
        }
        if (gbc && timing.lcdEnabled
                && (newEnableBits & ~enableBits & 0x40) != 0
                && !(lycIrqValueSource == 153 && timing.line == 153
                && timing.ticksInLine < 6)
                && scheduleLycIrqEvent(newEnableBits, lycIrqValueSource)
                - lycIrqClock > 456) {
            // The comparator event for this FF45 value has already passed. Enabling
            // its STAT source in the following CPU slot may create an explicit
            // register-change request, but must not synthesize a line-level edge.
            suppressedLycIrqLine = lycIrqValueSource;
        }
        if (gbc && timing.lcdEnabled
                && ((newEnableBits & ~enableBits & 0x40) != 0
                || ((newEnableBits & ~enableBits & 0x10) != 0
                && (enableBits & 0x40) != 0
                && (newEnableBits & 0x40) == 0
                && timing.line >= 144 && timing.line < 153
                && cpuCyclesToNextLy() <= 6)
                || ((newEnableBits & ~enableBits & 0x20) != 0
                && (newEnableBits & 0x28) == 0x20))
                && statChangeTriggersStatIrq(enableBits, newEnableBits)) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        }
        updateLycIrqRegisters(newEnableBits, lycIrqValueSource);
        queueModeIrqStatChange(newEnableBits);
        enableBits = newEnableBits;
        if (!gbc) {
            // The transient all-enabled phase and the written value are two bus
            // levels on one CPU edge. Settle low now so a following mode edge can
            // create a new interrupt.
            updateIntLine(computeIntLine(enableBits));
        }
    }

    @Override
    public int getByte(int address) {
        refreshGpuTiming();
        resolveCpuStatReadPhase();
        int visibleMode = cpuStatModeOverride >= 0
                ? cpuStatModeOverride
                : gpu.getCpuVisibleStatMode();
        if (enableBits == 0 && gpu.isUnrephasedLineZeroStatTail()) {
            // With no STAT source selected, the LCD-restart clock phase exposes
            // line zero's mode-3 latch for the five-dot mode-0 hand-off. Selecting
            // any interrupt source moves the CPU read onto the ordinary MSTAT path.
            visibleMode = Mode.PixelTransfer.ordinal();
        }
        // A speed switch rephases the native CGB's CPU-facing STAT mux. Its last
        // bus slot of an active scanline (and of line 153) already exposes the next
        // line's mode 2. The LYC source shares this tail mux and keeps the current
        // mode visible in that rephased slot when selected.
        if (gbc && !timing.dmgCompat
                && timing.lcdEnabled && timing.statModeLatchRephasedBySpeedSwitch
                && (timing.line < 143 || timing.line == 153)
                && timing.ticksInLine >= (timing.doubleSpeed ? 453 : 450)
                && (enableBits & 0x40) == 0) {
            visibleMode = Mode.OamSearch.ordinal();
        }
        if (gbc && !timing.doubleSpeed && timing.lcdEnabled
                && (timing.line < 143 && timing.ticksInLine == 454
                || timing.line == 143 && timing.ticksInLine >= 454)
                && !gpu.hasObjectsOnLine() && (enableBits & 0x40) != 0
                && (lycIrqValueSource != registeredLy
                || lycIrqClock - lastLycIrqRegisterChangeClock
                >= timing.ticksInLine)) {
            // The normal-speed CGB comparator and mode read mux share this final
            // object-free tail slot while the comparator is primed for the next LY.
            // A same-line register change that creates the current comparison uses
            // the write-response path and has already released this mux. Object lines
            // use their independently captured mode-2 path.
            visibleMode = Mode.HBlank.ordinal();
        }
        boolean visibleCoincidence = coincidence;
        if (suppressCpuReadCoincidence) {
            visibleCoincidence = false;
        }
        if (gbc && !timing.dmgCompat && timing.doubleSpeed
                && timing.statModeLatchRephasedBySpeedSwitch
                && timing.line == 143 && timing.ticksInLine == 453) {
            // On the rephased double-speed CPU bus, the final line-143 read slot
            // samples the comparison release ahead of the direct readable latch.
            // The following CPU slot lands after the ordinary release at dot 454.
            visibleCoincidence = false;
        }
        if (gbc && !timing.dmgCompat && !timing.doubleSpeed
                && timing.statModeLatchRephasedBySpeedSwitch
                && timing.line == 143 && timing.ticksInLine == 455
                && registeredLy == lycIrqValueSource) {
            visibleCoincidence = true;
        }
        return 0b10000000 | enableBits
                | (visibleCoincidence ? 0b100 : 0) | visibleMode;
    }

    private void resolveCpuStatReadPhase() {
        if (cpuStatModeOverride != CPU_STAT_MODE_UNRESOLVED) {
            return;
        }
        int statReadPhaseFlags = cpuStatReadPhaseFlags;
        cpuStatModeOverride = gpu.getCpuReadStatModeOverride(
                (statReadPhaseFlags & Cpu.STAT_READ_PHASE_SYNCHRONOUS_HALT_ENTRY) != 0,
                (statReadPhaseFlags & Cpu.STAT_READ_PHASE_ASYNCHRONOUS_HALT_ENTRY) != 0,
                (statReadPhaseFlags & Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE) != 0,
                (statReadPhaseFlags & Cpu.STAT_READ_PHASE_ONE_CYCLE_ORDINARY_HALT_WAKE) != 0,
                (statReadPhaseFlags & STAT_READ_PHASE_RECENT_ORDINARY_HALT_WAKE) != 0);
        suppressCpuReadCoincidence = gbc && !timing.dmgCompat
                && !timing.doubleSpeed && timing.line == 153
                && timing.ticksInLine == 6 && coincidence;
    }

    private void clearCpuStatReadPhase() {
        cpuStatModeOverride = -1;
        suppressCpuReadCoincidence = false;
        // Packed inputs are read only while the unresolved sentinel is set.
        // The next capture overwrites them before publishing that sentinel.
    }

    @Override
    public ComponentState<StatRegister> captureState() {
        return new StatRegisterState(enableBits, registeredLy, coincidence, intCoincidence, intLine,
                lycWriteSuppressed, suppressedLycIrqLine, modeBlockedLycIrqLine,
                lycIrqStatSource, lycIrqValueSource, lycIrqStatLatch,
                lycIrqValueLatch, lycIrqClock, nextLycIrqEvent, pendingLycWriteIrq,
                pendingLycComparatorIrq,
                lastLycIrqRegisterChangeClock,
                lastLcdcInterruptAcknowledgeClock,
                lastVBlankInterruptAcknowledgeClock,
                releaseTailLycCpuAcceptance, lycComparatorSignal,
                modeIrqStatLatch, modeIrqLycLatch,
                pendingModeIrqStat, pendingModeIrqLyc,
                pendingModeIrqStatClock, pendingModeIrqLycClock,
                mode0IrqStatLatch, mode0IrqLycLatch,
                pendingMode0IrqStat, pendingMode0IrqLyc,
                pendingMode0IrqStatClock, pendingMode0IrqLycClock,
                lastModeIrqStatWriteClock, lastModeIrqStatWriteLineTick,
                lastModeIrqStatWriteOld,
                cgbMode1IfClearAtCapture, pendingCgbMode1Interrupt,
                dmgLyc143Mode1CaptureClock,
                mode0EventArmed, previousMode0Window,
                previousMode1Window, previousMode2Window,
                pendingCgbMode0Interrupt, pendingCgbMode2Interrupt,
                pendingCgbMode2IfHighAtCapture,
                pendingCgbMode2LateReplay,
                pendingCgbMode2PublicationClock,
                cgbMode2CapturedAtLineEdge,
                pendingCgbFrameMode2Interrupt, retractableCgbMode2Interrupt,
                ordinaryHaltWakeStatClock, previousOrdinaryHaltWakePhase,
                scxChangedSinceMode0Event);
    }

    @Override
    public void restoreState(ComponentState<StatRegister> state) {
        if (!(state instanceof StatRegisterState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.enableBits = mem.enableBits;
        this.registeredLy = mem.registeredLy;
        this.coincidence = mem.coincidence;
        this.intCoincidence = mem.intCoincidence;
        this.intLine = mem.intLine;
        this.lycWriteSuppressed = mem.lycWriteSuppressed;
        this.suppressedLycIrqLine = mem.suppressedLycIrqLine;
        this.modeBlockedLycIrqLine = mem.modeBlockedLycIrqLine;
        this.lycIrqStatSource = mem.lycIrqStatSource;
        this.lycIrqValueSource = mem.lycIrqValueSource;
        this.lycIrqStatLatch = mem.lycIrqStatLatch;
        this.lycIrqValueLatch = mem.lycIrqValueLatch;
        this.lycIrqClock = mem.lycIrqClock;
        this.nextLycIrqEvent = mem.nextLycIrqEvent;
        this.pendingLycWriteIrq = mem.pendingLycWriteIrq;
        this.pendingLycComparatorIrq = mem.pendingLycComparatorIrq;
        this.lastLycIrqRegisterChangeClock = mem.lastLycIrqRegisterChangeClock;
        this.lastLcdcInterruptAcknowledgeClock = mem.lastLcdcInterruptAcknowledgeClock;
        this.lastVBlankInterruptAcknowledgeClock = mem.lastVBlankInterruptAcknowledgeClock;
        this.releaseTailLycCpuAcceptance = mem.releaseTailLycCpuAcceptance;
        this.lycComparatorSignal = mem.lycComparatorSignal;
        this.modeIrqStatLatch = mem.modeIrqStatLatch;
        this.modeIrqLycLatch = mem.modeIrqLycLatch;
        this.pendingModeIrqStat = mem.pendingModeIrqStat;
        this.pendingModeIrqLyc = mem.pendingModeIrqLyc;
        this.pendingModeIrqStatClock = mem.pendingModeIrqStatClock;
        this.pendingModeIrqLycClock = mem.pendingModeIrqLycClock;
        this.mode0IrqStatLatch = mem.mode0IrqStatLatch;
        this.mode0IrqLycLatch = mem.mode0IrqLycLatch;
        this.pendingMode0IrqStat = mem.pendingMode0IrqStat;
        this.pendingMode0IrqLyc = mem.pendingMode0IrqLyc;
        this.pendingMode0IrqStatClock = mem.pendingMode0IrqStatClock;
        this.pendingMode0IrqLycClock = mem.pendingMode0IrqLycClock;
        this.lastModeIrqStatWriteClock = mem.lastModeIrqStatWriteClock;
        this.lastModeIrqStatWriteLineTick = mem.lastModeIrqStatWriteLineTick;
        this.lastModeIrqStatWriteOld = mem.lastModeIrqStatWriteOld;
        this.cgbMode1IfClearAtCapture = mem.cgbMode1IfClearAtCapture;
        this.pendingCgbMode1Interrupt = mem.pendingCgbMode1Interrupt;
        this.dmgLyc143Mode1CaptureClock = mem.dmgLyc143Mode1CaptureClock;
        this.mode0EventArmed = mem.mode0EventArmed;
        this.previousMode0Window = mem.previousMode0Window;
        this.previousMode1Window = mem.previousMode1Window;
        this.previousMode2Window = mem.previousMode2Window;
        this.pendingCgbMode0Interrupt = mem.pendingCgbMode0Interrupt;
        this.pendingCgbMode2Interrupt = mem.pendingCgbMode2Interrupt;
        this.pendingCgbMode2IfHighAtCapture = mem.pendingCgbMode2IfHighAtCapture;
        this.pendingCgbMode2LateReplay = mem.pendingCgbMode2LateReplay;
        this.pendingCgbMode2PublicationClock = mem.pendingCgbMode2PublicationClock;
        this.cgbMode2CapturedAtLineEdge = mem.cgbMode2CapturedAtLineEdge;
        this.pendingCgbFrameMode2Interrupt = mem.pendingCgbFrameMode2Interrupt;
        this.retractableCgbMode2Interrupt = mem.retractableCgbMode2Interrupt;
        this.ordinaryHaltWakeStatClock = mem.ordinaryHaltWakeStatClock;
        this.previousOrdinaryHaltWakePhase = mem.previousOrdinaryHaltWakePhase;
        this.scxChangedSinceMode0Event = mem.scxChangedSinceMode0Event;
        // The timing snapshot is derived and may refer to the GPU state from before
        // the restore.  Let the first consumer recapture it from the restored GPU.
        timingGeneration = Long.MIN_VALUE;
        // This fast-path hint is intentionally not serialized. A restored state
        // always reevaluates once against the restored timing snapshot.
        statEvaluationDirty = true;
        clearCpuStatReadPhase();
    }

    private record StatRegisterState(int enableBits, int registeredLy, boolean coincidence,
                                       boolean intCoincidence, boolean intLine,
                                       boolean lycWriteSuppressed, int suppressedLycIrqLine,
                                       int modeBlockedLycIrqLine,
                                       int lycIrqStatSource,
                                       int lycIrqValueSource, int lycIrqStatLatch,
                                       int lycIrqValueLatch, long lycIrqClock,
                                       long nextLycIrqEvent,
                                       long pendingLycWriteIrq,
                                       long pendingLycComparatorIrq,
                                       long lastLycIrqRegisterChangeClock,
                                       long lastLcdcInterruptAcknowledgeClock,
                                       long lastVBlankInterruptAcknowledgeClock,
                                       boolean releaseTailLycCpuAcceptance,
                                       boolean lycComparatorSignal,
                                       int modeIrqStatLatch, int modeIrqLycLatch,
                                       int pendingModeIrqStat, int pendingModeIrqLyc,
                                       long pendingModeIrqStatClock,
                                       long pendingModeIrqLycClock,
                                       int mode0IrqStatLatch, int mode0IrqLycLatch,
                                       int pendingMode0IrqStat, int pendingMode0IrqLyc,
                                       long pendingMode0IrqStatClock,
                                       long pendingMode0IrqLycClock,
                                       long lastModeIrqStatWriteClock,
                                       int lastModeIrqStatWriteLineTick,
                                       int lastModeIrqStatWriteOld,
                                       boolean cgbMode1IfClearAtCapture,
                                       boolean pendingCgbMode1Interrupt,
                                       long dmgLyc143Mode1CaptureClock,
                                       boolean mode0EventArmed,
                                       boolean previousMode0Window,
                                       boolean previousMode1Window,
                                       boolean previousMode2Window,
                                       boolean pendingCgbMode0Interrupt,
                                       boolean pendingCgbMode2Interrupt,
                                       boolean pendingCgbMode2IfHighAtCapture,
                                       boolean pendingCgbMode2LateReplay,
                                       long pendingCgbMode2PublicationClock,
                                       boolean cgbMode2CapturedAtLineEdge,
                                       boolean pendingCgbFrameMode2Interrupt,
                                       boolean retractableCgbMode2Interrupt,
                                       long ordinaryHaltWakeStatClock,
                                       boolean previousOrdinaryHaltWakePhase,
                                       boolean scxChangedSinceMode0Event) implements ComponentState<StatRegister> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record StatRegisterMemento(int enableBits, int registeredLy, boolean coincidence,
                                       boolean intCoincidence, boolean intLine,
                                       boolean lycWriteSuppressed, int suppressedLycIrqLine,
                                       int modeBlockedLycIrqLine,
                                       int lycIrqStatSource,
                                       int lycIrqValueSource, int lycIrqStatLatch,
                                       int lycIrqValueLatch, long lycIrqClock,
                                       long nextLycIrqEvent,
                                       long pendingLycWriteIrq,
                                       long pendingLycComparatorIrq,
                                       long lastLycIrqRegisterChangeClock,
                                       long lastLcdcInterruptAcknowledgeClock,
                                       long lastVBlankInterruptAcknowledgeClock,
                                       boolean releaseTailLycCpuAcceptance,
                                       boolean lycComparatorSignal,
                                       int modeIrqStatLatch, int modeIrqLycLatch,
                                       int pendingModeIrqStat, int pendingModeIrqLyc,
                                       long pendingModeIrqStatClock,
                                       long pendingModeIrqLycClock,
                                       int mode0IrqStatLatch, int mode0IrqLycLatch,
                                       int pendingMode0IrqStat, int pendingMode0IrqLyc,
                                       long pendingMode0IrqStatClock,
                                       long pendingMode0IrqLycClock,
                                       long lastModeIrqStatWriteClock,
                                       int lastModeIrqStatWriteLineTick,
                                       int lastModeIrqStatWriteOld,
                                       boolean cgbMode1IfClearAtCapture,
                                       boolean pendingCgbMode1Interrupt,
                                       long dmgLyc143Mode1CaptureClock,
                                       boolean mode0EventArmed,
                                       boolean previousMode0Window,
                                       boolean previousMode1Window,
                                       boolean previousMode2Window,
                                       boolean pendingCgbMode0Interrupt,
                                       boolean pendingCgbMode2Interrupt,
                                       boolean pendingCgbMode2IfHighAtCapture,
                                       boolean pendingCgbMode2LateReplay,
                                       long pendingCgbMode2PublicationClock,
                                       boolean cgbMode2CapturedAtLineEdge,
                                       boolean pendingCgbFrameMode2Interrupt,
                                       boolean retractableCgbMode2Interrupt,
                                       long ordinaryHaltWakeStatClock,
                                       boolean previousOrdinaryHaltWakePhase,
                                       boolean scxChangedSinceMode0Event) implements Memento<StatRegister> {
    }

    private record LycComparison(int ly, int cpuCyclesUntilNextLy) {
    }
}
