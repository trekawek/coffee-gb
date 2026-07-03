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

    // the coincidence term feeding the interrupt line; unlike the readable flag it stays
    // low for the first 4 ticks of a line while the comparator output settles
    private boolean intCoincidence;

    private boolean intLine;

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
            // the comparison uses the LY value registered at the line start; the extra
            // latch point at tick 8 handles the LY=153 -> 0 transition during line 153
            if (ticksInLine == 0 || ticksInLine == 8) {
                registeredLy = gpu.getVisibleLy();
            }
            coincidence = registeredLy == gpu.getRegisters().get(LYC);
            if ((ticksInLine >= (gpu.isFirstLine() ? 451 : 452) && gpu.getLine() != 153)
                    || (gpu.getLine() == 153 && ticksInLine >= 4 && ticksInLine < 8)) {
                // when LY changes, the comparison result reads 0 until the new value
                // is registered at the beginning of the next line (lcdon_timing-GS);
                // at the end of line 153 the comparison stays valid: LY already flipped
                // to 0 at tick 8 and keeps that value into line 0, so an LYC=0
                // interrupt fires only once per frame there
                coincidence = false;
            }
            intCoincidence = coincidence;
            if (ticksInLine < 4 && gpu.getLine() != 0 && gpu.getLine() != 153) {
                // the coincidence term feeding the interrupt line settles one machine
                // cycle after the line-start latch (the readable flag is already valid
                // at tick 0, lcdon_timing-GS). This guarantees the STAT interrupt line
                // dips between the previous line's mode-0 term (high until the line
                // ends) and the new line's LY=LYC term, so an enabled mode-0 interrupt
                // cannot swallow the LYC interrupt of the following line (Ken Griffey's
                // Slugfest chains LYC and HBlank interrupts for its per-band palette
                // engine, issue #68). Lines 0 and 153 keep the existing model: LY=0 is
                // registered at tick 8 of line 153 and stays valid into line 0 (single
                // LYC=0 interrupt per frame, Altered Space), and LYC=153 has only the
                // tl=0..3 window.
                intCoincidence = false;
            }

            if (gpu.getLine() == 144 && ticksInLine == 0) {
                interruptManager.requestInterrupt(InterruptType.VBlank);
            }
        }

        updateIntLine(computeIntLine(enableBits));
    }

    public void onLcdEnabled() {
        registeredLy = 0;
    }

    private boolean computeIntLine(int enable) {
        boolean line = (enable & 0b01000000) != 0 && intCoincidence;
        if (gpu.isLcdEnabled()) {
            line |= (enable & 0b00001000) != 0 && gpu.isMode0IntWindow();
            line |= (enable & 0b00010000) != 0 && gpu.getVisibleStatMode() == 1;
            line |= (enable & 0b00100000) != 0 && gpu.isMode2IntWindow();
        }
        return line;
    }

    private void updateIntLine(boolean newLine) {
        if (newLine && !intLine) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
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
        return new StatRegisterMemento(enableBits, registeredLy, coincidence, intCoincidence, intLine);
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
    }

    private record StatRegisterMemento(int enableBits, int registeredLy, boolean coincidence,
                                       boolean intCoincidence, boolean intLine) implements Memento<StatRegister> {
    }
}
