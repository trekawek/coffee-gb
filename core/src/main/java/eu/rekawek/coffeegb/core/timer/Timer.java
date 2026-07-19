package eu.rekawek.coffeegb.core.timer;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class Timer implements AddressSpace, Serializable, Originator<Timer> {

    private final SpeedMode speedMode;

    private final InterruptManager interruptManager;

    private static final int[] FREQ_TO_BIT = {9, 3, 5, 7};

    // the divider has already counted a few cycles when the CPU fetches its first opcode
    // (reset release), which makes the internal counter reach exactly 0xABCC when the DMG
    // boot ROM hands over at 0x0100 (boot_div-dmgABCmgb); when the bootstrap is skipped,
    // Gameboy presets the counter to that post-boot-ROM value directly
    private int div = 4, tac, tma, tima;

    private boolean previousBit;

    private boolean overflow;

    private int ticksSinceOverflow;

    private boolean divReset;

    private int haltWakeDelay;

    // On the DMG the divider is an asynchronous ripple counter. If the HALT bug is
    // entered in the M-cycle immediately following a DIV reset, the duplicated fetch
    // can line the subsequent DIV read up with the first carry ripple. The high byte
    // briefly exposes the alternating ripple nodes before settling to 0x01.
    private int ticksSinceDivReset = Integer.MAX_VALUE;

    private boolean haltBugDivRipplePending;

    private boolean haltBugDivRippleVisible;

    public Timer(InterruptManager interruptManager, SpeedMode speedMode) {
        this.speedMode = speedMode;
        this.interruptManager = interruptManager;
    }

    public void presetDiv(int value) {
        this.div = value & 0xffff;
        ticksSinceDivReset = Integer.MAX_VALUE;
        haltBugDivRipplePending = false;
        haltBugDivRippleVisible = false;
    }

    public int getDivCounter() {
        return div;
    }

    public void tick() {
        divReset = false;
        for (int i = 0; i < speedMode.getSpeedMode(); i++) {
            tickCpuClock();
        }
    }

    private void tickCpuClock() {
        haltBugDivRippleVisible = false;
        if (haltWakeDelay > 0 && --haltWakeDelay == 0) {
            interruptManager.releaseHaltWake(InterruptManager.InterruptType.Timer);
        }
        int oldDiv = div;
        updateDiv((div + 1) & 0xffff);
        if (ticksSinceDivReset != Integer.MAX_VALUE) {
            ticksSinceDivReset++;
        }
        if (haltBugDivRipplePending && (oldDiv & 0xff) == 0xff && (div & 0xff) == 0) {
            haltBugDivRipplePending = false;
            haltBugDivRippleVisible = true;
        }
        if (overflow) {
            ticksSinceOverflow++;
            if (ticksSinceOverflow == 4) {
                interruptManager.requestInterruptBeforeHaltWake(InterruptManager.InterruptType.Timer);
                haltWakeDelay = 4;
            }
            if (ticksSinceOverflow == 5) {
                tima = tma;
            }
            if (ticksSinceOverflow == 6) {
                tima = tma;
                overflow = false;
                ticksSinceOverflow = 0;
            }
        }
    }

    private void incTima() {
        tima++;
        tima %= 0x100;
        if (tima == 0) {
            overflow = true;
            ticksSinceOverflow = 0;
        }
    }

    private void updateDiv(int newDiv) {
        this.div = newDiv;
        boolean bit = timerInput(div, tac);
        if (!bit && previousBit) {
            incTima();
        }
        previousBit = bit;
    }

    private static boolean timerInput(int div, int tac) {
        int bitPos = FREQ_TO_BIT[tac & 0b11];
        return (tac & (1 << 2)) != 0 && (div & (1 << bitPos)) != 0;
    }

    @Override
    public boolean accepts(int address) {
        return address >= 0xff04 && address <= 0xff07;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff04:
                updateDiv(0);
                divReset = true;
                ticksSinceDivReset = 0;
                haltBugDivRipplePending = false;
                haltBugDivRippleVisible = false;
                break;

            case 0xff05:
                if (ticksSinceOverflow < 5) {
                    tima = value;
                    overflow = false;
                    ticksSinceOverflow = 0;
                }
                break;

            case 0xff06:
                tma = value;
                break;

            case 0xff07:
                if (speedMode.isGbc()) {
                    // TAC changes the input of the timer's falling-edge detector at
                    // the write edge itself. Waiting for the following divider tick
                    // is observably one T-cycle late on CGB (tac_set_enabled).
                    boolean oldInput = timerInput(div, tac);
                    boolean newInput = timerInput(div, value);
                    tac = value;
                    if (oldInput && !newInput) {
                        incTima();
                    }
                    previousBit = newInput;
                } else {
                    tac = value;
                }
                break;
        }
    }

    public boolean consumeDivReset() {
        boolean result = divReset;
        divReset = false;
        return result;
    }

    public boolean isDivResetPending() {
        return divReset;
    }

    /**
     * Records the DMG HALT-bug clock phase. This is intentionally tied to the
     * divider reset phase rather than to a particular instruction stream.
     */
    public void onHaltBug() {
        if (!speedMode.isGbc() && speedMode.getSpeedMode() == 1 && ticksSinceDivReset == 4) {
            haltBugDivRipplePending = true;
        }
    }

    @Override
    public int getByte(int address) {
        switch (address) {
            case 0xff04:
                return haltBugDivRippleVisible ? 0x55 : div >> 8;

            case 0xff05:
                return tima;

            case 0xff06:
                return tma;

            case 0xff07:
                return tac | 0b11111000;
        }
        throw new IllegalArgumentException();
    }

    @Override
    public Memento<Timer> saveToMemento() {
        return new TimerMemento(div, tac, tma, tima, previousBit, overflow, ticksSinceOverflow, divReset,
                haltWakeDelay, ticksSinceDivReset, haltBugDivRipplePending, haltBugDivRippleVisible);
    }

    @Override
    public void restoreFromMemento(Memento<Timer> memento) {
        if (!(memento instanceof TimerMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.div = mem.div;
        this.tac = mem.tac;
        this.tma = mem.tma;
        this.tima = mem.tima;
        this.previousBit = mem.previousBit;
        this.overflow = mem.overflow;
        this.ticksSinceOverflow = mem.ticksSinceOverflow;
        this.divReset = mem.divReset;
        this.haltWakeDelay = mem.haltWakeDelay;
        this.ticksSinceDivReset = mem.ticksSinceDivReset;
        this.haltBugDivRipplePending = mem.haltBugDivRipplePending;
        this.haltBugDivRippleVisible = mem.haltBugDivRippleVisible;
    }

    public record TimerMemento(int div, int tac, int tma, int tima, boolean previousBit, boolean overflow,
                               int ticksSinceOverflow, boolean divReset,
                               int haltWakeDelay, int ticksSinceDivReset,
                               boolean haltBugDivRipplePending,
                               boolean haltBugDivRippleVisible) implements Memento<Timer> {
    }

}
