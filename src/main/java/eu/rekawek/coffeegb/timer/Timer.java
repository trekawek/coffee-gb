package eu.rekawek.coffeegb.timer;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;

public class Timer implements AddressSpace {

    private final Counter div = new Counter(16384);

    private final InterruptManager interruptManager;

    private Counter tima;

    private int tma;

    private int tac;

    public Timer(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
        setTima();
    }

    public void tick() {
        div.tick();
        if ((tac & (1 << 2)) != 0) {
            boolean updated = tima.tick();
            if (updated && tima.getCounter() == 0) {
                tima.setCounter(tma);
                interruptManager.requestInterrupt(InterruptType.Timer);
            }
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
                break;

            case 0xff05:
                tima.setCounter(value);
                break;

            case 0xff06:
                tma = value;
                break;

            case 0xff07:
                tac = value;
                setTima();
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
                return tac;
        }
        throw new IllegalArgumentException("Illegal address: " + Integer.toHexString(address));
    }

    public void setTima() {
        switch (tac & 0b11) {
            case 0b00:
                tima = new Counter(4096);
                break;

            case 0b01:
                tima = new Counter(262144);
                break;

            case 0b10:
                tima = new Counter(65536);
                break;

            case 0b11:
                tima = new Counter(16384);
                break;
        }
    }
}
