package eu.rekawek.coffeegb.timer;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.cpu.SpeedMode;

public class Timer implements AddressSpace {

    private final SpeedMode speedMode;

    private final Counter div;

    private int divClock;

    private final InterruptManager interruptManager;

    private Counter tima;

    private int timaClock;

    private int tma;

    private int tac;

    private int timaTickDivider;

    public Timer(InterruptManager interruptManager, SpeedMode speedMode) {
        this.speedMode = speedMode;
        this.interruptManager = interruptManager;
        this.div = new Counter(0b11);
        this.tima = new Counter(0b00);
    }

    public void tick() {
        div.onClockUpdate(divClock, divClock + 1);
        divClock = (divClock + 1) & 0x3ff;

        if (speedMode.getSpeedMode() == 2 && ++timaTickDivider < 2) {
            return;
        }
        timaTickDivider = 0;

        updateTima(timaClock, timaClock + 1);
        timaClock = (timaClock + 1) & 0x3ff;
    }

    private void updateTima(int oldClock, int newClock) {
        if ((tac & (1 << 2)) == 0) {
            return;
        }
        boolean updated = tima.onClockUpdate(oldClock, newClock);
        if (updated && tima.getCounter() == 0) {
            tima.setDelayedCounter(tma);
            interruptManager.requestInterrupt(InterruptType.Timer);
        }
    }

    @Override
    public boolean accepts(int address) {
        return address >= 0xff04 && address <= 0xff07;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff04:
                div.setCounter(0);
                updateTima(timaClock, 0);
                divClock = 0;
                timaClock = 0;
                break;

            case 0xff05:
                if (!tima.isReloading()) {
                    tima.setCounter(value);
                }
                break;

            case 0xff06:
                tma = value;
                if (tima.isReloading()) {
                    tima.updateDelayedCounter(tma);
                }
                break;

            case 0xff07:
                tac = value;
                tima.setMode(tac & 0b11);
                break;

        }
    }

    @Override
    public int getByte(int address) {
        switch (address) {
            case 0xff04:
                return div.getCounter();

            case 0xff05:
                return tima.getCounter();

            case 0xff06:
                return tma;

            case 0xff07:
                return tac | 0b11111000;
        }
        throw new IllegalArgumentException("Illegal address: " + Integer.toHexString(address));
    }
}
