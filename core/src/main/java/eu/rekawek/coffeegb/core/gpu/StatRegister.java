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

    private final InterruptManager interruptManager;

    private Gpu gpu;

    // bits 3-6: interrupt enable flags
    private int enableBits;

    private int registeredLy;

    private boolean coincidence;

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
        if (gpu.isLcdEnabled()) {
            int ticksInLine = gpu.getTicksInLine();
            if (lycWriteSuppressed
                    && ((gpu.getLine() != 153 && ticksInLine == 0)
                    || (gpu.getLine() == 153 && ticksInLine == 8))) {
                lycWriteSuppressed = false;
            }
            // the comparison uses the LY value registered at the line start; the extra
            // latch point at tick 8 handles the LY=153 -> 0 transition during line 153
            if (ticksInLine == 0 || ticksInLine == 8) {
                // On monochrome hardware LY has already returned to 0 when line 153
                // starts, but the comparator still samples the short-lived 153 value.
                registeredLy = gpu.getLine() == 153 && ticksInLine == 0
                        ? 153
                        : gpu.getVisibleLy();
            }
            coincidence = registeredLy == gpu.getRegisters().get(LYC);
            if ((!gpu.isGbc()
                    && ticksInLine >= (gpu.isFirstLine() ? 451 : 452)
                    && gpu.getLine() != 153)
                    || (!gpu.isGbc() && gpu.getLine() == 153
                    && ticksInLine >= 4 && ticksInLine < 8)
                    || lycWriteSuppressed) {
                // when LY changes, the comparison result reads 0 until the new value
                // is registered at the beginning of the next line (lcdon_timing-GS);
                // at the end of line 153 the comparison stays valid: LY already flipped
                // to 0 at tick 8 and keeps that value into line 0, so an LYC=0
                // interrupt fires only once per frame there
                coincidence = false;
            }
            if (gpu.getLine() == 144 && ticksInLine == 0) {
                interruptManager.requestInterrupt(InterruptType.VBlank);
            }
            if (gpu.getLine() <= 144 && ticksInLine == 0) {
                interruptManager.releaseHaltWake(InterruptType.LCDC);
            }
        }

        updateIntLine(computeIntLine(enableBits));
    }

    public void onLcdEnabled() {
        registeredLy = 0;
        lycWriteSuppressed = false;
    }

    public void onLcdDisabled() {
        interruptManager.releaseHaltWake(InterruptType.LCDC);
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
        boolean line = (enable & 0b01000000) != 0 && coincidence;
        if (gpu.isLcdEnabled()) {
            line |= (enable & 0b00001000) != 0 && gpu.isMode0IntWindow();
            line |= (enable & 0b00010000) != 0 && gpu.getVisibleStatMode() == 1;
            line |= (enable & 0b00100000) != 0 && gpu.isMode2IntWindow();
        }
        return line;
    }

    private void updateIntLine(boolean newLine) {
        if (newLine && !intLine) {
            int earlyMode2Edge = gpu.isFirstLine() ? 451 : 452;
            if (gpu.getLine() < 144 && gpu.getTicksInLine() == earlyMode2Edge) {
                interruptManager.requestInterruptBeforeHaltWake(InterruptType.LCDC);
            } else {
                interruptManager.requestInterrupt(InterruptType.LCDC);
            }
        }
        intLine = newLine;
    }

    @Override
    public boolean accepts(int address) {
        return address == ADDRESS;
    }

    @Override
    public void setByte(int address, int value) {
        if (!gpu.isGbc()) {
            // DMG STAT write glitch: all interrupt sources are enabled for a moment
            // before the written data settles
            updateIntLine(computeIntLine(0b01111000));
        }
        enableBits = value & 0b01111000;
    }

    @Override
    public int getByte(int address) {
        return 0b10000000 | enableBits | (coincidence ? 0b100 : 0) | gpu.getVisibleStatMode();
    }

    @Override
    public Memento<StatRegister> saveToMemento() {
        return new StatRegisterMemento(enableBits, registeredLy, coincidence, intLine, lycWriteSuppressed);
    }

    @Override
    public void restoreFromMemento(Memento<StatRegister> memento) {
        if (!(memento instanceof StatRegisterMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.enableBits = mem.enableBits;
        this.registeredLy = mem.registeredLy;
        this.coincidence = mem.coincidence;
        this.intLine = mem.intLine;
        this.lycWriteSuppressed = mem.lycWriteSuppressed;
    }

    private record StatRegisterMemento(int enableBits, int registeredLy, boolean coincidence, boolean intLine,
                                       boolean lycWriteSuppressed) implements Memento<StatRegister> {
    }
}
