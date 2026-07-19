package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.LYC;

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
}
