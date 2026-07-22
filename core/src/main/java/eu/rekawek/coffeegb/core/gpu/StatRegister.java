package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

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
public class StatRegister implements AddressSpace, Originator<StatRegister> {

    public static final int ADDRESS = 0xff41;

    private static final int NEW_FRAME_LYC_EDGE = 8;

    private static final int CGB_DOUBLE_TAIL_LATCH = 454;

    private static final int ORDINARY_HALT_WAKE_STAT_HOLD_TICKS = 456;

    private static final long NO_LYC_IRQ_EVENT = Long.MAX_VALUE;

    private final InterruptManager interruptManager;

    private Gpu gpu;

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

    // TODO remove circular dependency
    public void init(Gpu gpu) {
        this.gpu = gpu;
        lycIrqStatSource = enableBits;
        lycIrqValueSource = gpu.getRegisters().get(LYC);
        lycIrqStatLatch = lycIrqStatSource;
        lycIrqValueLatch = lycIrqValueSource;
        nextLycIrqEvent = scheduleLycIrqEvent(lycIrqStatSource, lycIrqValueSource);
        modeIrqStatLatch = 0;
        modeIrqLycLatch = lycIrqValueSource;
        mode0IrqStatLatch = 0;
        mode0IrqLycLatch = lycIrqValueSource;
    }

    public void tick() {
        interruptManager.finishLcdcReadMaskWindow();
        interruptManager.clearCpuReadInterruptPreview();
        lycIrqClock++;
        cpuStatModeOverride = -1;
        suppressCpuReadCoincidence = false;
        if (interruptManager.consumeLcdcInterruptAcknowledge()) {
            lastLcdcInterruptAcknowledgeClock = lycIrqClock;
            if (isNativeDoubleSpeed()
                    && previousMode0Window
                    && gpu.getTicksInLine() == gpu.getMode0InterruptTick() + 1
                    && mode0EventArmed
                    && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                    && !((mode0IrqStatLatch & 0x40) != 0
                    && gpu.getLine() == mode0IrqLycLatch)) {
                // In native double speed, the mode-0 set latch owns the CPU
                // acknowledge slot immediately following its event. A later
                // acknowledge still consumes the stored request normally.
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            }
        }
        if (interruptManager.consumeVBlankInterruptAcknowledge()) {
            lastVBlankInterruptAcknowledgeClock = lycIrqClock;
        }
        boolean lcdcInterruptFlagWriteClear =
                interruptManager.consumeLcdcInterruptFlagWriteClear();
        if (lcdcInterruptFlagWriteClear
                && gpu.isGbc() && !gpu.isDmgCompatMode()
                && previousMode0Window
                && (gpu.getTicksInLine() == gpu.getMode0InterruptTick()
                + (isDoubleSpeed() ? 2 : 1)
                || isDoubleSpeed() && gpu.getTicksInLine()
                == gpu.getMode0InterruptTick() + 3)
                && mode0EventArmed
                && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                && !((mode0IrqStatLatch & 0x40) != 0
                && gpu.getLine() == mode0IrqLycLatch)) {
            // An FF0F clear and the normal-speed CGB mode-0 set share this bus
            // slot. The captured PPU set wins; a clear in the next slot does not.
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
        }
        if (pendingCgbMode0Interrupt) {
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            pendingCgbMode0Interrupt = false;
        }
        commitPendingModeIrqRegisters();
        commitPendingMode0IrqRegisters();
        boolean suppressNaturalModeEdge =
                updateModeIrqEvents(lcdcInterruptFlagWriteClear);
        if (pendingCgbMode2Interrupt && gpu.getTicksInLine() == 452) {
            publishPendingCgbMode2Event();
        }
        if (pendingCgbMode2LateReplay && gpu.getTicksInLine() == 455) {
            publishPendingCgbMode2Replay();
        }
        if (pendingCgbMode2LateReplay && retractableCgbMode2Interrupt
                && cgbMode2CapturedAtLineEdge && (modeIrqStatLatch & 0x40) == 0
                && gpu.getTicksInLine() == 454) {
            interruptManager.maskLcdcUntilNextPeripheralTick();
        }
        boolean publishCgbFrameMode2 = pendingCgbFrameMode2Interrupt && !isDoubleSpeed()
                && ((getNormalSpeedClockPhase() == 0
                && gpu.getLine() == 153 && gpu.getTicksInLine() == 455)
                || (getNormalSpeedClockPhase() == 1
                && gpu.getLine() == 0 && gpu.getTicksInLine() == 0));
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
        if (retractableCgbMode2Interrupt && gpu.getTicksInLine() > 454) {
            retractableCgbMode2Interrupt = false;
        }
        boolean settlingLycLine = false;
        if (gpu.isLcdEnabled()) {
            int ticksInLine = gpu.getTicksInLine();
            if (suppressedLycIrqLine >= 0
                    && registeredLy != suppressedLycIrqLine
                    && gpu.getVisibleLy() != suppressedLycIrqLine
                    && !(suppressedLycIrqLine == 153 && gpu.getLine() == 153)) {
                suppressedLycIrqLine = -1;
            }
            if (modeBlockedLycIrqLine >= 0
                    && registeredLy != modeBlockedLycIrqLine
                    && gpu.getVisibleLy() != modeBlockedLycIrqLine) {
                modeBlockedLycIrqLine = -1;
            }
            boolean lycComparePhase = (gpu.getLine() != 153 && ticksInLine == 454)
                    || (gpu.getLine() == 153 && ticksInLine == 6);
            if (lycComparePhase) {
                int comparedLy = comparedLycIrqLine();
                int comparedLyc = nextLycIrqEvent == lycIrqClock
                        ? lycIrqValueLatch
                        : lycIrqValueSource;
                lycComparatorSignal = comparedLyc == comparedLy;
            }
            if (releaseTailLycCpuAcceptance && ticksInLine == 455) {
                if (gpu.isGbc() || gpu.hasObjectsOnLine()) {
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
            boolean nativeDoubleTailLycLatch = isNativeDoubleSpeed()
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH
                    && gpu.getLine() != 153;
            // In double-speed mode the PPU's line-144 request is readable during
            // the last two dots of line 143. CPU acceptance remains synchronized
            // to the internal rollover, preserving ordinary VBlank dispatch timing.
            if (isNativeDoubleSpeed() && gpu.getLine() == 143
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH) {
                if (!recentVBlankAcknowledgeWins()) {
                    interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                            InterruptType.VBlank);
                }
            }
            if (gpu.isMode0HaltWakeTick()) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            if (gpu.isGbc() && ticksInLine == gpu.getCpuMachineCycleDots()) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            // The LY=0 comparison reaches IF four dots after readable LY falls
            // (dot 8 normally, dot 6 in native double speed), then crosses the
            // CPU/HALT input synchronizer one CPU M-cycle later.
            if (gpu.getLine() == 153 && ticksInLine == getNewFrameLycCpuAcceptTick()) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
            if ((gpu.getLine() <= 144 || gpu.isGbc()) && ticksInLine == 0) {
                // Release a request latched in the preceding line's tail before a
                // possible new edge is registered below. Native double speed also
                // latches LYC requests this way during VBlank (for example 151->152).
                // PixelTransfer still describes the preceding line here. On DMG an
                // object-stalled line holds the early mode-2 edge away from both CPU
                // inputs until rollover; a BG-only line only holds the HALT path.
                if (gpu.isGbc() || gpu.hasObjectsOnLine()) {
                    interruptManager.releaseCpuAcceptance(InterruptType.LCDC);
                } else {
                    interruptManager.releaseHaltWake(InterruptType.LCDC);
                }
            }
            if (lycWriteSuppressed
                    && ((gpu.getLine() != 153 && ticksInLine == 0)
                    || (gpu.getLine() == 153 && ticksInLine == getNewFrameLycEdgeTick()))) {
                lycWriteSuppressed = false;
            }
            // The normal comparison uses LY registered at the line start. Native
            // double speed has a separate tail latch at dot 454; the extra speed-scaled
            // latch handles the LY=153 -> 0 transition during line 153.
            if (ticksInLine == 0 || ticksInLine == getNewFrameLycEdgeTick()
                    || nativeDoubleTailLycLatch) {
                // On monochrome hardware LY has already returned to 0 when line 153
                // starts, but the comparator still samples the short-lived 153 value.
                registeredLy = gpu.getLine() == 153 && ticksInLine == 0
                        ? 153
                        : gpu.getVisibleLy();
            }
            coincidence = registeredLy == gpu.getRegisters().get(LYC);
            int coincidenceReleaseTick = gpu.getCoincidenceReleaseTick();
            boolean coincidenceRelease = gpu.isGbc()
                    && !(gpu.isFirstLine() && !isDoubleSpeed())
                    ? ticksInLine > coincidenceReleaseTick
                    : ticksInLine >= coincidenceReleaseTick;
            boolean nativeDoubleTailComparison = isNativeDoubleSpeed()
                    && ticksInLine >= CGB_DOUBLE_TAIL_LATCH
                    && gpu.getLine() != 153;
            if ((coincidenceRelease && !nativeDoubleTailComparison
                    && gpu.getLine() != 153)
                    || (!gpu.isGbc() && gpu.getLine() == 153
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
                    || gpu.getVisibleLy() == suppressedLycIrqLine;
            if (suppressedLycComparison) {
                intCoincidence = false;
            }
            if (ticksInLine < 4 && gpu.getLine() != 0
                    && gpu.getLine() != 144 && gpu.getLine() != 153) {
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
                    if (isNativeDoubleSpeed()) {
                        interruptManager.requestInterrupt(InterruptType.LCDC);
                    } else if (gpu.isGbc()) {
                        interruptManager.requestPhasedInterruptBeforeHaltWake(InterruptType.LCDC);
                    } else {
                        interruptManager.requestInterrupt(InterruptType.LCDC);
                    }
                    intLine = true;
                }
            }
            if (gpu.getLine() == 144 && ticksInLine == 0) {
                if (isNativeDoubleSpeed()) {
                    interruptManager.releaseCpuAcceptance(InterruptType.VBlank);
                } else if (!recentVBlankAcknowledgeWins()) {
                    interruptManager.requestInterrupt(InterruptType.VBlank);
                }
            }
        }

        boolean holdModeBlockedLycLine = modeBlockedLycIrqLine >= 0
                && (registeredLy == modeBlockedLycIrqLine
                || gpu.getVisibleLy() == modeBlockedLycIrqLine)
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
    }

    public void onLcdEnabled() {
        cpuStatModeOverride = -1;
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
        cpuStatModeOverride = gpu.getCpuStatModeOverride();
        if (pendingCgbMode2Interrupt && !isDoubleSpeed()
                && isDeferredCgbMode2Phase()
                && gpu.getTicksInLine() == 450) {
            // A normal-speed CPU memory callback at stored dot 450 completes at
            // the scheduled dot-452 MSTAT boundary. Resolve that boundary before
            // the callback; CPU and HALT acceptance remain blocked until rollover.
            publishPendingCgbMode2Event();
        }
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
        if (ordinaryHaltWakePhase && !previousOrdinaryHaltWakePhase) {
            ordinaryHaltWakeStatClock = lycIrqClock;
        }
        previousOrdinaryHaltWakePhase = ordinaryHaltWakePhase;
        boolean recentOrdinaryHaltWakePhase = ordinaryHaltWakePhase
                && ordinaryHaltWakeStatClock != NO_LYC_IRQ_EVENT
                && lycIrqClock - ordinaryHaltWakeStatClock
                <= ORDINARY_HALT_WAKE_STAT_HOLD_TICKS;
        gpu.captureCpuLyReadPhase(ordinaryHaltWakePhase
                && isMode1InterruptSourceOnly());
        cpuStatModeOverride = gpu.getCpuReadStatModeOverride(
                synchronousHaltEntryPhase, asynchronousHaltEntryPhase,
                ordinaryHaltWakePhase, oneCycleOrdinaryHaltWakePhase,
                recentOrdinaryHaltWakePhase);
        suppressCpuReadCoincidence = gpu.isGbc() && !gpu.isDmgCompatMode()
                && !isDoubleSpeed() && gpu.getLine() == 153
                && gpu.getTicksInLine() == 6 && coincidence;
    }

    /** Captures PPU edges visible to this tick's CPU IF read before IF itself settles. */
    public void captureCpuInterruptReadPhase() {
        captureCpuInterruptReadPhase(0, false, false);
    }

    public void captureCpuInterruptReadPhase(int interruptFlagReadMaskTicks,
                                             boolean mode0InterruptDispatchPhased,
                                             boolean mode0InstructionWinsAcceptance) {
        cpuInterruptFlagReadMaskTicks = interruptFlagReadMaskTicks;
        cpuMode0InterruptDispatchPhased = mode0InterruptDispatchPhased;
        cpuMode0InstructionWinsAcceptance = mode0InstructionWinsAcceptance;
        boolean dmgMode0 = gpu.isDmgTerminalWindowMode0ReadPreviewPhase()
                && mode0EventArmed
                && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                && !((mode0IrqStatLatch & 0x40) != 0
                && gpu.getLine() == mode0IrqLycLatch);
        boolean nativeNormalRephased = gpu.isGbc() && !gpu.isDmgCompatMode()
                && !isDoubleSpeed() && gpu.isStatModeLatchRephasedBySpeedSwitch();
        boolean doubleSpeedMode0 = isNativeDoubleSpeed()
                && gpu.getLine() < 144
                && gpu.getTicksInLine() == gpu.getMode0InterruptTick() - 2
                && enableBits == 0x48
                && mode0EventArmed
                && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                && !((mode0IrqStatLatch & 0x40) != 0
                && gpu.getLine() == mode0IrqLycLatch);
        boolean earlyVisibleMode2 = nativeNormalRephased
                && gpu.getLine() < 144 && gpu.getTicksInLine() == 450
                && pendingCgbMode2Interrupt;
        // The rephased normal-speed CPU bus completes an IF read against the
        // upcoming PPU edge. Keep this read-only: the stored IF and interrupt
        // acceptance paths still settle at their ordinary MSTAT/VBlank clocks.
        boolean frameMode2 = nativeNormalRephased
                && gpu.getLine() == 153 && gpu.getTicksInLine() >= 452
                && gpu.getTicksInLine() <= 455 && enableBits == 0x20;
        interruptManager.setCpuReadInterruptPreview(
                InterruptType.LCDC,
                dmgMode0 || doubleSpeedMode0 || earlyVisibleMode2 || frameMode2);
        boolean frameVBlank = nativeNormalRephased
                && gpu.getLine() == 143 && gpu.getTicksInLine() >= 452
                && gpu.getTicksInLine() <= 455 && enableBits == 0x20;
        interruptManager.setCpuReadInterruptPreview(InterruptType.VBlank, frameVBlank);
    }

    /** Returns whether this scheduler tick will publish the delayed mode-0 edge. */
    public boolean isMode0InterruptEdgeNextTick() {
        return gpu != null && gpu.isGbc() && !gpu.isDmgCompatMode()
                && gpu.getLine() < 144
                && gpu.getTicksInLine() + 1 == gpu.getMode0InterruptTick()
                && mode0EventArmed
                && ((enableBits | mode0IrqStatLatch) & 0x08) != 0
                && !interruptManager.isInterruptFlagSet(InterruptType.LCDC)
                && !((mode0IrqStatLatch & 0x40) != 0
                && gpu.getLine() == mode0IrqLycLatch);
    }

    /** Publishes line 1's LYC=0-blocked normal-speed mode-2 handoff before CPU I/O. */
    public void publishFrameLyc0Mode2HandoffBeforeCpu() {
        if (pendingCgbMode2Interrupt && !isDoubleSpeed()
                && isDeferredCgbMode2Phase()
                && gpu.getLine() == 1
                && (enableBits & 0x60) == 0x60
                && modeIrqLycLatch == 0
                && gpu.getTicksInLine() == 450) {
            publishPendingCgbMode2Event();
        }
    }

    public void onLcdDisabled() {
        cpuStatModeOverride = -1;
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
        scxChangedSinceMode0Event = true;
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
        int writtenLyc = newValue & 0xff;
        long writtenValueEvent = scheduleLycIrqEvent(enableBits, writtenLyc);
        if (gpu.isGbc() && gpu.isLcdEnabled()
                && writtenLyc == registeredLy
                && writtenValueEvent - lycIrqClock > 456) {
            suppressedLycIrqLine = writtenLyc;
        }
        if (gpu.isGbc() && !isDoubleSpeed() && gpu.isLcdEnabled()
                && gpu.getTicksInLine() >= 454
                && lycComparatorSignal
                && (enableBits & 0x40) != 0
                && oldValue == gpu.getVisibleLy()
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
        if (!gpu.isLcdEnabled() || oldValue == writtenLyc) {
            return;
        }
        if (lycRegChangeTriggersStatIrq(oldValue, writtenLyc)) {
            if (gpu.isGbc()) {
                // A write-created comparison crosses the CGB interrupt latch after
                // the write cycle. Native double speed reaches the next CPU sampling
                // edge in three PPU clocks; normal speed uses the five-clock response
                // measured by Gambatte's LYC register-change path.
                int responseClocks = isDoubleSpeed() ? 3 : 5;
                pendingLycWriteIrq = Math.min(
                        pendingLycWriteIrq, lycIrqClock + responseClocks);
            } else {
                interruptManager.requestInterrupt(InterruptType.LCDC);
            }
        }
        if (!gpu.isGbc()) {
            return;
        }
        int ticksInLine = gpu.getTicksInLine();
        if (ticksInLine == 448 || (gpu.getLine() == 153 && ticksInLine == 0)) {
            // A write in the complementary conflict window is not observed by
            // the comparator until the next LY latch point.
            lycWriteSuppressed = true;
        }
    }

    private boolean computeIntLine(int enable) {
        boolean line = (enable & 0b01000000) != 0 && intCoincidence;
        if (gpu.isLcdEnabled()) {
            boolean holdCgbFrameLycToMode2Handoff = gpu.isGbc()
                    && !gpu.isDmgCompatMode()
                    && gpu.getLine() == 153
                    && intCoincidence
                    && lycIrqValueSource == 0
                    && (lastModeIrqStatWriteOld & 0x40) != 0
                    && (enable & 0x60) == 0x20
                    && lastModeIrqStatWriteLineTick >= 0
                    && gpu.getTicksInLine() >= lastModeIrqStatWriteLineTick
                    && cpuCyclesSince(lastModeIrqStatWriteClock) <= 4;
            line |= holdCgbFrameLycToMode2Handoff;
            boolean suppressTailCgbMode0Enable = gpu.isGbc()
                    && gpu.getLine() < 144
                    && (lastModeIrqStatWriteOld & 0x08) == 0
                    && (enable & 0x08) != 0
                    && lastModeIrqStatWriteLineTick >= 0
                    && gpu.getTicksInLine() >= lastModeIrqStatWriteLineTick
                    && 456 - lastModeIrqStatWriteLineTick <= 6
                    && lycIrqClock - lastModeIrqStatWriteClock <= 6
                    && !lycComparatorSignal;
            boolean suppressLateCgbModeEnable = gpu.isGbc()
                    && gpu.getLine() == 143
                    && lastModeIrqStatWriteLineTick >= 453
                    && lycIrqClock - lastModeIrqStatWriteClock <= 2
                    && (enable & 0x28) != 0;
            line |= !suppressLateCgbModeEnable && !suppressTailCgbMode0Enable
                    && (enable & 0b00001000) != 0 && gpu.isMode0IntWindow();
            boolean suppressLateCgbMode1Enable = gpu.isGbc()
                    && gpu.getLine() == 153
                    && lastModeIrqStatWriteLineTick >= (isDoubleSpeed() ? 453 : 452)
                    && lycIrqClock - lastModeIrqStatWriteClock <= 4
                    && (lastModeIrqStatWriteOld & 0x20) == 0
                    && (enable & 0x10) != 0;
            line |= !suppressLateCgbMode1Enable
                    && (enable & 0b00010000) != 0 && isMode1IrqLineActive();
            boolean suppressLateDmgLine0Mode2Enable = !gpu.isGbc()
                    && gpu.getLine() == 0 && gpu.getTicksInLine() < 4
                    && lastModeIrqStatWriteLineTick >= 0
                    && lastModeIrqStatWriteLineTick < 4
                    && lycIrqClock - lastModeIrqStatWriteClock <= 4
                    && (lastModeIrqStatWriteOld & 0x20) == 0;
            line |= !suppressLateCgbModeEnable && !suppressLateDmgLine0Mode2Enable
                    && (enable & 0b00100000) != 0 && gpu.isMode2IntWindow();
        }
        return line;
    }

    private boolean isMode1IrqLineActive() {
        if (gpu.isGbc() && gpu.getLine() == 143) {
            return gpu.getTicksInLine() >= 454;
        }
        return gpu.isMode1IntWindow();
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
        if (gpu.isGbc()) {
            int lycCaptureWindow = 6 + 4 * (isDoubleSpeed() ? 1 : 0);
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
        lastModeIrqStatWriteLineTick = gpu.getTicksInLine();
        lastModeIrqStatWriteOld = enableBits;
        pendingModeIrqStat = stat;
        pendingModeIrqStatClock = lycIrqClock;
        pendingMode0IrqStat = stat;
        pendingMode0IrqStatClock = lycIrqClock;
        if (!gpu.isGbc()) {
            modeIrqStatLatch = stat;
            pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;
            mode0IrqStatLatch = stat;
            pendingMode0IrqStatClock = NO_LYC_IRQ_EVENT;
        }
        if (gpu.isLcdEnabled() && (stat & 0x08) != 0) {
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

    private void commitPendingModeIrqRegisters() {
        if (pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqStatClock) > (gpu.isGbc() ? 2 : 0)) {
            modeIrqStatLatch = pendingModeIrqStat;
            pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;
        }
        int lycCaptureDelay = gpu.isGbc() ? (isDoubleSpeed() ? 5 : 6) : 1;
        if (pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqLycClock) > lycCaptureDelay) {
            modeIrqLycLatch = pendingModeIrqLyc;
            pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
        }
    }

    private void commitPendingMode0IrqRegisters() {
        int statCaptureDelay;
        if (!gpu.isGbc()) {
            statCaptureDelay = 0;
        } else if (isDoubleSpeed()) {
            statCaptureDelay = 6;
        } else if ((gpu.getRegisters().get(SCX) & 7) == 0) {
            statCaptureDelay = 6;
        } else {
            statCaptureDelay = scxChangedSinceMode0Event ? 4 : 8;
        }
        if (pendingMode0IrqStatClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingMode0IrqStatClock) > statCaptureDelay) {
            mode0IrqStatLatch = pendingMode0IrqStat;
            pendingMode0IrqStatClock = NO_LYC_IRQ_EVENT;
        }
        int lycCaptureDelay = gpu.isGbc()
                ? (isDoubleSpeed() || scxChangedSinceMode0Event ? 8 : 10)
                : 1;
        if (pendingMode0IrqLycClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingMode0IrqLycClock) > lycCaptureDelay) {
            mode0IrqLycLatch = pendingMode0IrqLyc;
            pendingMode0IrqLycClock = NO_LYC_IRQ_EVENT;
        }
    }

    private long cpuCyclesSince(long clock) {
        int cpuClocksPerDot = 4 / gpu.getCpuMachineCycleDots();
        return Math.max(0, lycIrqClock - clock) * cpuClocksPerDot
                + getNormalSpeedClockPhase();
    }

    private boolean updateModeIrqEvents(boolean lcdcInterruptFlagWriteClear) {
        if (!gpu.isLcdEnabled()) {
            previousMode0Window = false;
            previousMode1Window = false;
            previousMode2Window = false;
            return false;
        }

        boolean mode0Window = gpu.isMode0IntWindow();
        boolean mode1Window = gpu.isMode1IntWindow();
        boolean mode2Window = gpu.isMode2IntWindow();
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
            if (isDeferredCgbMode2Phase()
                    && gpu.getTicksInLine() == gpu.getEarlyLineEdgeTick()) {
                // The early CGB mode level only schedules the line-tail MSTAT event.
                // Its FF41/FF45 blockers are captured at the event itself, after
                // their independent write windows have elapsed.
                boolean ifHigh =
                        interruptManager.isInterruptFlagSet(InterruptType.LCDC);
                pendingCgbMode2Interrupt = !ifHigh || (isDoubleSpeed() && !intLine);
                pendingCgbMode2IfHighAtCapture =
                        isDoubleSpeed() && pendingCgbMode2Interrupt && ifHigh;
                cgbMode2CapturedAtLineEdge = pendingCgbMode2Interrupt;
            } else {
                if (gpu.isGbc()) {
                    // Coffee GB publishes the CGB mode-2 request on its early CPU
                    // synchronizer edge. Relative to that edge, the six-clock
                    // register capture window has already elapsed.
                    commitPendingModeIrqLycImmediately();
                }
                int eventLy = gpu.getLine() == 0 && gpu.getTicksInLine() < 4
                        ? 0 : incrementLy(gpu.getLine());
                boolean blockedByM1 = eventLy == 0 && (modeIrqStatLatch & 0x10) != 0;
                int precedingLy = eventLy == 0 ? 0 : eventLy - 1;
                boolean blockedByLyc = (modeIrqStatLatch & 0x40) != 0
                        && precedingLy == modeIrqLycLatch;
                if (blockedByM1 || blockedByLyc) {
                    suppressNaturalModeEdge = true;
                } else if (gpu.isGbc() && !gpu.isDmgCompatMode()
                        && !isDoubleSpeed() && gpu.getLine() == 153
                        && gpu.getTicksInLine() == 454) {
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
        suppressNaturalModeEdge |= isDeferredCgbMode2Phase()
                && (pendingCgbMode2Interrupt || (enableBits & 0x20) != 0);
        if (mode1Event) {
            if (gpu.isGbc() && gpu.getLine() == 143) {
                cgbMode1IfClearAtCapture =
                        !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
            } else {
                refreshModeIrqLatches(false);
            }
            if (!gpu.isGbc()
                    && dmgLyc143Mode1CaptureClock != NO_LYC_IRQ_EVENT
                    && lastLcdcInterruptAcknowledgeClock
                    > dmgLyc143Mode1CaptureClock) {
                suppressNaturalModeEdge = true;
            }
            dmgLyc143Mode1CaptureClock = NO_LYC_IRQ_EVENT;
        }
        if (!gpu.isGbc() && gpu.getLine() == 143
                && gpu.getTicksInLine() == 448) {
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
        if (isNativeDoubleSpeed() && gpu.getLine() == 143
                && gpu.getTicksInLine() == 452) {
            cgbMode1IfClearAtCapture =
                    !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
        }
        if (gpu.isGbc() && gpu.getLine() == 143 && gpu.getTicksInLine() == 454) {
            boolean blockedByCapturedMode = (modeIrqStatLatch & 0x28) != 0;
            pendingCgbMode1Interrupt = (enableBits & 0x10) != 0
                    && !blockedByCapturedMode && cgbMode1IfClearAtCapture;
            if (isNativeDoubleSpeed() && pendingCgbMode1Interrupt) {
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
        if (gpu.isGbc() && !isDoubleSpeed() && gpu.getLine() == 143
                && gpu.getTicksInLine() == 455 && pendingCgbMode1Interrupt) {
            boolean newlyAsserted =
                    !interruptManager.isInterruptFlagSet(InterruptType.LCDC);
            interruptManager.requestInterrupt(InterruptType.LCDC);
            if (newlyAsserted && gpu.isStatModeLatchRephasedBySpeedSwitch()) {
                interruptManager.maskLcdcUntilNextPeripheralTick();
            }
            pendingCgbMode1Interrupt = false;
        }
        if (mode0Event && mode0EventArmed) {
            boolean enabled = ((enableBits | mode0IrqStatLatch) & 0x08) != 0;
            boolean blockedByLyc = (mode0IrqStatLatch & 0x40) != 0
                    && gpu.getLine() == mode0IrqLycLatch;
            if (!enabled || blockedByLyc) {
                suppressNaturalModeEdge = true;
            } else if (mode0AcknowledgeWins
                    || (!gpu.isGbc() && lcdcInterruptFlagWriteClear)) {
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
                if (gpu.isGbc() && !gpu.isDmgCompatMode()
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
        return gpu.isGbc() && gpu.isLcdEnabled() && !gpu.isFirstLine()
                && gpu.getLine() < 144
                && gpu.getTicksInLine() >= gpu.getEarlyLineEdgeTick()
                && gpu.getTicksInLine() <= 452;
    }

    private boolean canReschedulePendingCgbMode2Event() {
        if (!isDeferredCgbMode2Phase()) {
            return false;
        }
        int eventCpuTimestamp = isDoubleSpeed() ? 452 : 450;
        return gpu.getTicksInLine() < eventCpuTimestamp
                || (!isDoubleSpeed() && gpu.getTicksInLine() == eventCpuTimestamp);
    }

    private void publishPendingCgbMode2Event() {
        commitPendingModeIrqRegisters();
        if (!isDoubleSpeed()
                && pendingModeIrqStatClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqStatClock) == 2) {
            modeIrqStatLatch = pendingModeIrqStat;
            pendingModeIrqStatClock = NO_LYC_IRQ_EVENT;
        }
        if (!isDoubleSpeed()
                && pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                && cpuCyclesSince(pendingModeIrqLycClock) == 6) {
            // Coffee GB retires the FF45 callback one clock phase later than the
            // MSTAT scheduler's timestamp. Equality here is Gambatte's strict
            // six-clock capture boundary for the mode-2 event only.
            modeIrqLycLatch = pendingModeIrqLyc;
            pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
        }
        int eventLy = incrementLy(gpu.getLine());
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
            retractableCgbMode2Interrupt = isDoubleSpeed() && newlyAsserted;
            pendingCgbMode2LateReplay = isDoubleSpeed() && gpu.getLine() < 143;
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
        boolean line0Event = gpu.getLine() == 0 && gpu.getTicksInLine() < 4;
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
        if (gpu.isGbc() && !gpu.isDmgCompatMode() && !isDoubleSpeed()
                && gpu.getLine() == 0 && !gpu.isFirstLine()
                && (gpu.getRegisters().get(SCX) & 7) == 0) {
            // At normal speed and fine-scroll phase zero, line zero's mode-0
            // level reaches STAT on this dot while IF settles after the same-dot
            // CPU read phase.
            pendingCgbMode0Interrupt = true;
        } else if (!gpu.isGbc() && gpu.hasObjectsOnLine()) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        } else {
            interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
        }
    }

    private long cpuCyclesUntil(long eventClock) {
        if (eventClock == NO_LYC_IRQ_EVENT) {
            return Long.MAX_VALUE;
        }
        int cpuClocksPerDot = 4 / gpu.getCpuMachineCycleDots();
        return Math.max(0, eventClock - lycIrqClock) * cpuClocksPerDot
                + getNormalSpeedClockPhase();
    }

    private boolean lycRegChangeTriggersStatIrq(int oldValue, int newValue) {
        if ((enableBits & 0x40) == 0 || newValue >= 154
                || lycWriteTriggerBlockedByMode(newValue)) {
            return false;
        }

        LycComparison comparison = getLycComparison();
        int doubleSpeed = isDoubleSpeed() ? 1 : 0;
        if (comparison.cpuCyclesUntilNextLy <= 4 + 4 * doubleSpeed
                + 2 * (gpu.isGbc() ? 1 : 0)) {
            if (oldValue == comparison.ly
                    && comparison.cpuCyclesUntilNextLy > 2 * (gpu.isGbc() ? 1 : 0)) {
                return false;
            }
            comparison = new LycComparison(incrementLy(comparison.ly),
                    comparison.cpuCyclesUntilNextLy);
        }
        return newValue == comparison.ly;
    }

    private boolean lycWriteTriggerBlockedByMode(int newValue) {
        int timeToNextLy = cpuCyclesToNextLy();
        if (gpu.getLine() < 144) {
            return (enableBits & 0x08) != 0
                    && gpu.isMode0IntWindow()
                    && newValue == gpu.getLine();
        }
        if (gpu.isGbc() && !isDoubleSpeed() && gpu.getLine() == 153) {
            // FF45 is committed near the end of its CPU write cycle. At the short
            // LY=0 hand-off that is two clocks later than the dot timestamp used by
            // the rest of this model (one after a normal-speed clock rephase).
            timeToNextLy -= 2 - getNormalSpeedClockPhase();
        }
        int doubleSpeed = isDoubleSpeed() ? 1 : 0;
        return (enableBits & 0x10) != 0
                && !(gpu.getLine() == 153
                && timeToNextLy <= 2 + 2 * doubleSpeed + 2 * (gpu.isGbc() ? 1 : 0));
    }

    private boolean statChangeTriggersStatIrq(int oldStat, int newStat) {
        int newlyEnabled = newStat & ~oldStat & 0x78;
        if (newlyEnabled == 0) {
            return false;
        }

        int ly = gpu.getLine();
        int timeToNextLy = cpuCyclesToNextLy();
        int doubleSpeed = isDoubleSpeed() ? 1 : 0;
        LycComparison comparison = getLycComparison();
        boolean lycPeriod = comparison.ly == lycIrqValueSource
                && comparison.cpuCyclesUntilNextLy > 2;
        boolean cgbVblankLycToMode1Handoff = gpu.isGbc()
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
            boolean normalSpeedMode0LevelStillSettling = !isDoubleSpeed()
                    && gpu.getTicksInLine() - gpu.getMode0InterruptTick() <= 20;
            boolean doubleSpeedMode0LevelAlreadyRetiring = isDoubleSpeed()
                    && timeToNextLy <= 8;
            boolean blockedByActiveMode0 = gpu.isMode0IntWindow()
                    && (oldStat & 0x08) != 0
                    && (newlyEnabled & 0x40) != 0
                    && !normalSpeedMode0LevelStillSettling
                    && !doubleSpeedMode0LevelAlreadyRetiring;
            if (blockedByActiveMode0) {
                // The predictive mode-0 event reaches IF before its shared STAT
                // level can block a newly selected LYC source. Once that level has
                // settled, changing FF41 must not manufacture a second rising edge.
                m0LycOrM1 = false;
            } else if (gpu.isMode0IntWindow()
                    || timeToNextLy <= (ly < 143 ? 4 + 4 * doubleSpeed
                    : 4 + 2 * doubleSpeed)) {
                m0LycOrM1 = lycPeriod && (newStat & 0x40) != 0;
            } else {
                // Selecting mode 0 while the PPU is in mode 2 or 3 only arms
                // the next HBlank event; it is not a combinational STAT source.
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
                        || (!isDoubleSpeed() && timeToNextLy == 2));
                if (isDoubleSpeed() && ly > 0 && gpu.getTicksInLine() <= 2) {
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
        int line = gpu.getLine();
        int timeToNextLy = cpuCyclesToNextLy();
        int doubleSpeed = isDoubleSpeed() ? 1 : 0;
        int lineCpuCycles = (gpu.isFirstLine() ? 455 : 456) * (1 + doubleSpeed);
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
        int lineDots = gpu.isFirstLine() ? 455 : 456;
        return Math.max(0, lineDots - gpu.getTicksInLine())
                * (isDoubleSpeed() ? 2 : 1) + getNormalSpeedClockPhase();
    }

    private int getNormalSpeedClockPhase() {
        return !isDoubleSpeed() && gpu.isStatModeLatchRephasedBySpeedSwitch() ? 1 : 0;
    }

    private boolean isDoubleSpeed() {
        return gpu.getCpuMachineCycleDots() == 2;
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
                && gpu.getLine() == 153 && recentLyc0AcknowledgeWins();
        if (capturedComparison && !blockedByMode && !clearedByRecentAcknowledge
                && (writeSensitiveEvent || gpu.getLine() == 153)) {
            if (gpu.getLine() == 153) {
                if (writeSensitiveEvent || isDoubleSpeed()) {
                    interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                } else {
                    // The LY=0 comparator and a CPU read in the same dot use opposite
                    // clock phases. Publish a stable event on the following dot so the
                    // in-flight read sees the old IF value; the request still survives
                    // an FF41/FF45 write in that intervening CPU slot.
                    pendingLycComparatorIrq = Math.min(
                            pendingLycComparatorIrq, lycIrqClock + 1);
                }
            } else if (isDoubleSpeed()) {
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
            if (gpu.getLine() != 153 || writeSensitiveEvent || isDoubleSpeed()) {
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
        if (gpu == null || !gpu.isLcdEnabled() || (stat & 0x40) == 0 || lyc >= 154) {
            return NO_LYC_IRQ_EVENT;
        }

        int targetLine = lyc == 0 ? 153 : lyc - 1;
        int targetTick = lyc == 0 ? 6 : 454;
        int currentLine = gpu.getLine();
        int currentTick = gpu.getTicksInLine();
        long distance;
        if (currentLine == targetLine && currentTick < targetTick) {
            distance = targetTick - currentTick;
        } else {
            distance = (gpu.isFirstLine() ? 455L : 456L) - currentTick;
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
        if (gpu.getLine() == 153) {
            return 0;
        }
        return gpu.getLine() + 1;
    }

    private void updateIntLine(boolean newLine) {
        if (newLine && !intLine) {
            boolean line153ComparisonEdge = coincidence && registeredLy == 153
                    && (enableBits & 0b01000000) != 0
                    && ((gpu.getLine() == 153 && gpu.getTicksInLine() == 0)
                    || (isNativeDoubleSpeed() && gpu.getLine() == 152
                    && gpu.getTicksInLine() == CGB_DOUBLE_TAIL_LATCH));
            if (line153ComparisonEdge && recentLyc153AcknowledgeWins()) {
                // The comparator was already high when the CPU acknowledge cleared
                // IF, so preserve its shared level without issuing a second edge.
                intLine = newLine;
                return;
            }
            int earlyMode2Edge = gpu.getEarlyLineEdgeTick();
            boolean nativeDoubleTailLycLatch = isNativeDoubleSpeed()
                    && gpu.getTicksInLine() == CGB_DOUBLE_TAIL_LATCH
                    && gpu.getLine() != 153;
            if (nativeDoubleTailLycLatch) {
                // IF is already readable in the line tail, but running and halted
                // CPUs both accept this direct edge only after the line rolls over.
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.LCDC);
            } else if (gpu.getLine() < 144 && gpu.getTicksInLine() == earlyMode2Edge) {
                if (recentDmgMode2AcknowledgeWins()) {
                    // During the final three dots the DMG has already sampled the
                    // next mode-2 source. Keep its level high without recreating IF.
                    intLine = newLine;
                    return;
                }
                if (gpu.isGbc() || gpu.hasObjectsOnLine()) {
                    if (gpu.isGbc()) {
                        interruptManager.requestMode2InterruptBeforeCpuAcceptance(
                                gpu.isFirstLine());
                    } else {
                        interruptManager.requestInterruptBeforeCpuAcceptance(InterruptType.LCDC);
                    }
                } else {
                    interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                }
            } else if (gpu.getLine() == 153
                    && gpu.getTicksInLine() == getNewFrameLycEdgeTick()
                    && coincidence && (enableBits & 0b01000000) != 0) {
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            } else if (gpu.isGbc() && !gpu.isDmgCompatMode()
                    && gpu.getCpuMachineCycleDots() == 2
                    && gpu.getLine() == 153 && gpu.getTicksInLine() == 454) {
                // The line-zero M2 request is published in the final line's tail,
                // but CPU acceptance remains synchronized to the line-zero rollover.
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.LCDC);
            } else if (gpu.isMode0IntWindow()) {
                requestMode0InterruptEvent();
            } else {
                interruptManager.requestInterrupt(InterruptType.LCDC);
            }
        }
        intLine = newLine;
    }

    private boolean isNativeDoubleSpeed() {
        return gpu.isGbc() && !gpu.isDmgCompatMode()
                && gpu.getCpuMachineCycleDots() == 2;
    }

    private boolean recentLyc0AcknowledgeWins() {
        int captureWindow = isDoubleSpeed() ? 1 : gpu.isGbc() ? 4 : 6;
        return lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= captureWindow;
    }

    private boolean recentLyc153AcknowledgeWins() {
        int captureWindow = isDoubleSpeed() ? 1 : gpu.isGbc() ? 5 : 7;
        return lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= captureWindow;
    }

    private boolean isFrameMode2Event() {
        return gpu.isGbc() && !gpu.isDmgCompatMode()
                ? gpu.getLine() == 153 && gpu.getTicksInLine() == 454
                : gpu.getLine() == 0 && gpu.getTicksInLine() < 4;
    }

    private boolean recentFrameMode2AcknowledgeWins() {
        int captureWindow = isDoubleSpeed() ? 1 : 7;
        return lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= captureWindow;
    }

    private boolean recentDmgMode2AcknowledgeWins() {
        return !gpu.isGbc()
                && lastLcdcInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastLcdcInterruptAcknowledgeClock <= 3;
    }

    private boolean recentMode0AcknowledgeWins() {
        if (isDoubleSpeed()) {
            return false;
        }
        int captureWindow = gpu.isGbc() ? 3 : 5;
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
                && acknowledgeAge <= gpu.getTicksInLine();
        // A normal-speed edge delayed beyond dot 253 has crossed the CPU read
        // synchronizer before its data phase, even if that phase is otherwise
        // identical to an earlier HBlank transition.
        return acknowledgedThisLine
                && (isDoubleSpeed() || gpu.getMode0InterruptTick() <= 253);
    }

    private boolean recentVBlankAcknowledgeWins() {
        int captureWindow = isDoubleSpeed() ? 1 : 7;
        return lastVBlankInterruptAcknowledgeClock != Long.MIN_VALUE
                && lycIrqClock - lastVBlankInterruptAcknowledgeClock <= captureWindow;
    }

    private int getNewFrameLycEdgeTick() {
        return isNativeDoubleSpeed() ? NEW_FRAME_LYC_EDGE - 2 : NEW_FRAME_LYC_EDGE;
    }

    private int getNewFrameLycCpuAcceptTick() {
        return getNewFrameLycEdgeTick() + gpu.getCpuMachineCycleDots();
    }

    @Override
    public boolean accepts(int address) {
        return address == ADDRESS;
    }

    @Override
    public void setByte(int address, int value) {
        int newEnableBits = value & 0b01111000;
        boolean capturedDmgLycToModeHandoff = !gpu.isGbc()
                && gpu.isLcdEnabled()
                && (enableBits & 0x40) != 0
                && lycIrqValueSource == gpu.getLine()
                && cpuCyclesToNextLy() <= 4
                && (((newEnableBits & 0x08) != 0 && gpu.isMode0IntWindow())
                || ((newEnableBits & 0x10) != 0 && gpu.isMode1IntWindow())
                || ((newEnableBits & 0x20) != 0 && gpu.isMode2IntWindow()));
        if (capturedDmgLycToModeHandoff) {
            // The outgoing LYC source is still high at the final mode capture.
            // Preserve that shared level so the FF41 handoff cannot create an edge.
            intLine = true;
        }
        if (!gpu.isGbc()) {
            if ((value & 0b01111000) == 0
                    && gpu.isLcdEnabled()
                    && gpu.getTicksInLine() == 0
                    && !gpu.isMode0IntWindow()
                    && !gpu.isMode1IntWindow()
                    && !gpu.isMode2IntWindow()
                    && interruptManager.isInterruptFlagSet(InterruptType.VBlank)) {
                // At the first visible-line latch the retiring VBlank request and the
                // FF41 write share an asynchronous read gate. The next IF read sees
                // bit 0 low even though the IF latch itself remains set.
                interruptManager.maskVBlankOnNextRead();
            }
            // DMG STAT write glitch: all interrupt sources are enabled for a moment
            // before the written data settles
            int glitchEnable = 0b01111000;
            if (gpu.getLine() == 0 && gpu.getTicksInLine() < 4
                    && (enableBits & 0x20) == 0) {
                // The line-zero mode-2 event has already sampled FF41. Its transient
                // all-enabled write phase cannot arm that event retroactively.
                glitchEnable &= ~0x20;
            }
            if (gpu.isLcdEnabled()
                    && gpu.getTicksInLine() == 0
                    && (enableBits & 0b00101000) == 0b00001000) {
                // At the HBlank -> OAM boundary, an already-enabled HBlank source
                // masks the transient OAM source. Treating the write as a plain 0xff
                // here creates a second STAT edge and can recursively re-enter a
                // scanline handler (Initial D Gaiden).
                glitchEnable &= ~0b00100000;
            }
            boolean lineStartCoincidenceGlitch = gpu.getTicksInLine() == 0
                    && coincidence && (glitchEnable & 0x40) != 0;
            updateIntLine(computeIntLine(glitchEnable) || lineStartCoincidenceGlitch);
        }
        if (isDoubleSpeed() && gpu.getTicksInLine() <= 454
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
        if (isDoubleSpeed() && pendingCgbMode2LateReplay
                && gpu.getTicksInLine() > 452 && gpu.getTicksInLine() <= 454
                && newEnableBits != enableBits) {
            // FF41 is still inside the late MSTAT capture window. Changing its
            // source mix withdraws the replay of the dot-452 mode-2 occurrence.
            pendingCgbMode2LateReplay = false;
            pendingCgbMode2IfHighAtCapture = false;
            pendingCgbMode2PublicationClock = NO_LYC_IRQ_EVENT;
            cgbMode2CapturedAtLineEdge = false;
        }
        if (pendingCgbFrameMode2Interrupt && gpu.isGbc() && !gpu.isDmgCompatMode()
                && !isDoubleSpeed() && gpu.getLine() == 153
                && gpu.getTicksInLine() <= 454 && (newEnableBits & 0x20) == 0) {
            // A write in the capture dot can still withdraw the frame mode-2
            // occurrence before it is published at rollover.
            pendingCgbFrameMode2Interrupt = false;
        }
        if (pendingCgbMode1Interrupt && gpu.isGbc() && !isDoubleSpeed()
                && gpu.getLine() == 143 && gpu.getTicksInLine() == 454
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
            pendingCgbMode2IfHighAtCapture = isDoubleSpeed()
                    && pendingCgbMode2Interrupt
                    && interruptManager.isInterruptFlagSet(InterruptType.LCDC);
            cgbMode2CapturedAtLineEdge = pendingCgbMode2Interrupt
                    && retainedLineEdgeCapture;
        }
        if (gpu.isGbc() && gpu.isLcdEnabled()
                && (newEnableBits & ~enableBits & 0x40) != 0
                && !(lycIrqValueSource == 153 && gpu.getLine() == 153
                && gpu.getTicksInLine() < 6)
                && scheduleLycIrqEvent(newEnableBits, lycIrqValueSource)
                - lycIrqClock > 456) {
            // The comparator event for this FF45 value has already passed. Enabling
            // its STAT source in the following CPU slot may create an explicit
            // register-change request, but must not synthesize a line-level edge.
            suppressedLycIrqLine = lycIrqValueSource;
        }
        if (gpu.isGbc() && gpu.isLcdEnabled()
                && ((newEnableBits & ~enableBits & 0x40) != 0
                || ((newEnableBits & ~enableBits & 0x10) != 0
                && (enableBits & 0x40) != 0
                && (newEnableBits & 0x40) == 0
                && gpu.getLine() >= 144 && gpu.getLine() < 153
                && cpuCyclesToNextLy() <= 6)
                || ((newEnableBits & ~enableBits & 0x20) != 0
                && (newEnableBits & 0x28) == 0x20))
                && statChangeTriggersStatIrq(enableBits, newEnableBits)) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        }
        updateLycIrqRegisters(newEnableBits, lycIrqValueSource);
        queueModeIrqStatChange(newEnableBits);
        enableBits = newEnableBits;
        if (!gpu.isGbc()) {
            // The transient all-enabled phase and the written value are two bus
            // levels on one CPU edge. Settle low now so a following mode edge can
            // create a new interrupt.
            updateIntLine(computeIntLine(enableBits));
        }
    }

    @Override
    public int getByte(int address) {
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
        if (gpu.isGbc() && !gpu.isDmgCompatMode()
                && gpu.isLcdEnabled() && gpu.isStatModeLatchRephasedBySpeedSwitch()
                && (gpu.getLine() < 143 || gpu.getLine() == 153)
                && gpu.getTicksInLine() >= (isDoubleSpeed() ? 453 : 450)
                && (enableBits & 0x40) == 0) {
            visibleMode = Mode.OamSearch.ordinal();
        }
        if (gpu.isGbc() && !isDoubleSpeed() && gpu.isLcdEnabled()
                && (gpu.getLine() < 143 && gpu.getTicksInLine() == 454
                || gpu.getLine() == 143 && gpu.getTicksInLine() >= 454)
                && !gpu.hasObjectsOnLine() && (enableBits & 0x40) != 0
                && (lycIrqValueSource != registeredLy
                || lycIrqClock - lastLycIrqRegisterChangeClock
                >= gpu.getTicksInLine())) {
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
        if (gpu.isGbc() && !gpu.isDmgCompatMode() && isDoubleSpeed()
                && gpu.isStatModeLatchRephasedBySpeedSwitch()
                && gpu.getLine() == 143 && gpu.getTicksInLine() == 453) {
            // On the rephased double-speed CPU bus, the final line-143 read slot
            // samples the comparison release ahead of the direct readable latch.
            // The following CPU slot lands after the ordinary release at dot 454.
            visibleCoincidence = false;
        }
        if (gpu.isGbc() && !gpu.isDmgCompatMode() && !isDoubleSpeed()
                && gpu.isStatModeLatchRephasedBySpeedSwitch()
                && gpu.getLine() == 143 && gpu.getTicksInLine() == 455
                && registeredLy == lycIrqValueSource) {
            visibleCoincidence = true;
        }
        return 0b10000000 | enableBits
                | (visibleCoincidence ? 0b100 : 0) | visibleMode;
    }

    @Override
    public Memento<StatRegister> saveToMemento() {
        return new StatRegisterMemento(enableBits, registeredLy, coincidence, intCoincidence, intLine,
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
    public void restoreFromMemento(Memento<StatRegister> memento) {
        if (!(memento instanceof StatRegisterMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
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
    }

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
