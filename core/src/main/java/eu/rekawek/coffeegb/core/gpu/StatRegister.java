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

    private boolean mode0EventArmed;

    private boolean previousMode0Window;

    private boolean previousMode1Window;

    private boolean previousMode2Window;

    private boolean pendingCgbMode2Interrupt;

    private boolean pendingCgbMode0Interrupt;

    // CPU callbacks run before this dot's PPU clocks. Keep their sampled mode
    // separate from the readable latch used by direct PPU observers.
    private int cpuStatModeOverride = -1;

    public StatRegister(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
    }

    // TODO remove circular dependency
    public void init(Gpu gpu) {
        this.gpu = gpu;
    }

    public void tick() {
        boolean settlingLycLine = false;
        if (gpu.isLcdEnabled()) {
            int ticksInLine = gpu.getTicksInLine();
            boolean nativeDoubleTailLycLatch = isNativeDoubleSpeed()
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH
                    && gpu.getLine() != 153;
            // In double-speed mode the PPU's line-144 request is readable during
            // the last two dots of line 143. CPU acceptance remains synchronized
            // to the internal rollover, preserving ordinary VBlank dispatch timing.
            if (isNativeDoubleSpeed() && gpu.getLine() == 143
                    && ticksInLine == CGB_DOUBLE_TAIL_LATCH) {
                interruptManager.requestInterruptBeforeCpuAcceptanceUnphased(
                        InterruptType.VBlank);
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
            if (ticksInLine < 4 && gpu.getLine() != 0 && gpu.getLine() != 153) {
                intCoincidence = false;
                settlingLycLine = coincidence && (enableBits & 0b01000000) != 0;
                if (ticksInLine == 0 && settlingLycLine && !intLine) {
                    // The comparison edge reaches IF at the line-start latch,
                    // before its level contribution to the STAT line settles. Keep the
                    // edge detector latched across that settling window: if IRQ
                    // dispatch clears IF before tick 4, the same comparison must not
                    // be observed as a second edge (Army Men).
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
                interruptManager.requestInterrupt(InterruptType.VBlank);
            }
        }

        if (!settlingLycLine) {
            updateIntLine(computeIntLine(enableBits));
        }
    }

    public void onLcdEnabled() {
        registeredLy = 0;
        lycWriteSuppressed = false;
    }

    public void onLcdDisabled() {
        interruptManager.releaseCpuAcceptance(InterruptType.LCDC);
    }

    /**
     * Models the CGB's LYC write conflicts around the LY latch points.
     */
    public void onLycWrite(int oldValue) {
        if (!gpu.isGbc() || !gpu.isLcdEnabled()) {
            return;
        }
        int ticksInLine = gpu.getTicksInLine();
        if (ticksInLine == 452
                && oldValue == gpu.getVisibleLy()
                && (enableBits & 0b01000000) != 0) {
            // At this phase the comparator sees the old LYC value before the
            // CPU write settles on the register bus.
            updateIntLine(true);
        }
        if (gpu.getLine() == 153
                && ticksInLine == 4
                && oldValue == 0
                && (enableBits & 0b01000000) != 0) {
            // During the CGB's 153-to-0 transition, the old LYC value is
            // compared against the next LY value before this write settles.
            updateIntLine(true);
        }
        if (ticksInLine == 448 || (gpu.getLine() == 153 && ticksInLine == 0)) {
            // A write in the complementary conflict window is not observed by
            // the comparator until the next LY latch point.
            lycWriteSuppressed = true;
        }
    }

    private boolean computeIntLine(int enable) {
        boolean line = (enable & 0b01000000) != 0 && intCoincidence;
        if (gpu.isLcdEnabled()) {
            line |= (enable & 0b00001000) != 0 && gpu.isMode0IntWindow();
            line |= (enable & 0b00010000) != 0 && gpu.isMode1IntWindow();
            line |= (enable & 0b00100000) != 0 && gpu.isMode2IntWindow();
        }
        return line;
    }

    private void commitPendingModeIrqLycImmediately() {
        if (pendingModeIrqLycClock != NO_LYC_IRQ_EVENT
                && pendingModeIrqLycClock < lycIrqClock) {
            modeIrqLycLatch = pendingModeIrqLyc;
            pendingModeIrqLycClock = NO_LYC_IRQ_EVENT;
        }
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
        if (lycPeriod && (oldStat & 0x40) != 0) {
            return false;
        }

        boolean m0LycOrM1;
        if (ly < 143 || (ly == 143 && timeToNextLy > 458 * (1 + doubleSpeed))) {
            if (gpu.isMode0IntWindow()
                    || timeToNextLy <= (ly < 143 ? 4 + 4 * doubleSpeed
                    : 4 + 2 * doubleSpeed)) {
                m0LycOrM1 = lycPeriod && (newStat & 0x40) != 0;
            } else if ((oldStat & 0x08) != 0) {
                m0LycOrM1 = false;
            } else {
                m0LycOrM1 = (newStat & 0x08) != 0
                        || (lycPeriod && (newStat & 0x40) != 0);
            }
        } else if ((oldStat & 0x10) != 0
                && (ly < 153 || timeToNextLy > 3 + 3 * doubleSpeed)) {
            m0LycOrM1 = false;
        } else {
            m0LycOrM1 = ((newStat & 0x10) != 0
                    && (ly < 153 || timeToNextLy > 4 + 2 * doubleSpeed))
                    || (lycPeriod && (newStat & 0x40) != 0);
        }

        boolean m2 = false;
        if ((oldStat & 0x20) == 0 && (newStat & 0x28) == 0x20) {
            if (ly < 143) {
                m2 = timeToNextLy <= 4 * (1 + doubleSpeed) && timeToNextLy > 2;
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
                if (gpu.isGbc() || gpu.hasObjectsOnLine()) {
                    interruptManager.requestInterruptBeforeCpuAcceptance(InterruptType.LCDC);
                } else {
                    interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                }
            } else if (gpu.getLine() == 153
                    && gpu.getTicksInLine() == getNewFrameLycEdgeTick()
                    && coincidence && (enableBits & 0b01000000) != 0) {
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            } else if (gpu.isMode0IntWindow()) {
                if (!gpu.isGbc() && gpu.hasObjectsOnLine()) {
                    // The object-fetch tail has already crossed the DMG's interrupt
                    // synchronizer by the time its delayed mode-0 edge becomes visible.
                    interruptManager.requestInterrupt(InterruptType.LCDC);
                } else {
                    interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
                }
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
            if (gpu.isLcdEnabled()
                    && gpu.getTicksInLine() == 0
                    && (enableBits & 0b00101000) == 0b00001000) {
                // At the HBlank -> OAM boundary, an already-enabled HBlank source
                // masks the transient OAM source. Treating the write as a plain 0xff
                // here creates a second STAT edge and can recursively re-enter a
                // scanline handler (Initial D Gaiden).
                glitchEnable &= ~0b00100000;
            }
            updateIntLine(computeIntLine(glitchEnable));
        }
        enableBits = value & 0b01111000;
    }

    @Override
    public int getByte(int address) {
        return 0b10000000 | enableBits | (coincidence ? 0b100 : 0) | gpu.getVisibleStatMode();
    }

    @Override
    public Memento<StatRegister> saveToMemento() {
        return new StatRegisterMemento(enableBits, registeredLy, coincidence, intCoincidence, intLine,
                lycWriteSuppressed);
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
    }

    private record StatRegisterMemento(int enableBits, int registeredLy, boolean coincidence,
                                       boolean intCoincidence, boolean intLine,
                                       boolean lycWriteSuppressed) implements Memento<StatRegister> {
    }

    private record LycComparison(int ly, int cpuCyclesUntilNextLy) {
    }
}
