package eu.rekawek.coffeegb.timer;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.SpeedMode;

public class Timer implements AddressSpace {

    private final SpeedMode speedMode;

    private final InterruptManager interruptManager;

    private static final int[] FREQ_TO_BIT = {9, 3, 5, 7};

    private int div, tac, tma, tima;

    private boolean previousBit;

    private boolean overflow;

    private int ticksSinceOverflow;

    public Timer(InterruptManager interruptManager, SpeedMode speedMode) {
        this.speedMode = speedMode;
        this.interruptManager = interruptManager;
    }

    public void tick() {
        updateDiv((div + 1) & 0xffff);
        if (overflow) {
            ticksSinceOverflow++;
            if (ticksSinceOverflow == 4) {
                interruptManager.requestInterrupt(InterruptManager.InterruptType.Timer);
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
        int bitPos = FREQ_TO_BIT[tac & 0b11];
        bitPos <<= speedMode.getSpeedMode() - 1;
        boolean bit = (div & (1 << bitPos)) != 0;
        bit &= (tac & (1 << 2)) != 0;
        if (!bit && previousBit) {
            incTima();
        }
        previousBit = bit;
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
                tac = value;
                break;
        }
    }

    @Override
    public int getByte(int address) {
        switch (address) {
            case 0xff04:
                return div >> 8;

            case 0xff05:
                return tima;

            case 0xff06:
                return tma;

            case 0xff07:
                return tac | 0b11111000;
        }
        throw new IllegalArgumentException();
    }
}
