package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;

import java.util.HashSet;
import java.util.Set;

public class Gpu implements AddressSpace {

    private final InterruptManager interruptManager;

    private Mode mode = Mode.OAM_Search;

    private int line;

    private int remainingClocks = mode.getCycles();

    private Set<Mode> enabledInterrupts = new HashSet<>();

    public Gpu(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff41:
                setStat(value);
                break;
        }
    }

    @Override
    public int getByte(int address) {
        switch (address) {
            case 0xff41:
                return getStat();

            case 0xff44:
                return line;
        }
        return 0xff;
    }

    public void proceed(int clockCycles) {
        int c = clockCycles;
        while (c > 0) {
            if (remainingClocks > c) {
                remainingClocks -= c;
                c = 0;
            } else {
                c -= remainingClocks;
                switchMode();
            }
        }
    }

    private void switchMode() {
        switch (mode) {
            case OAM_Search:
                mode = Mode.PixelTransfer;
                break;

            case PixelTransfer:
                mode = Mode.H_Blank;
                break;

            case H_Blank:
                if (++line == 144) {
                    mode = Mode.V_Blank;
                } else {
                    mode = Mode.OAM_Search;
                }
                break;

            case V_Blank:
                if (++line == 154) {
                    line = 0;
                    mode = Mode.OAM_Search;
                }
                break;
        }

        remainingClocks = mode.getCycles();
        if (enabledInterrupts.contains(mode)) {
            interruptManager.requestLcdcInterrupt();
        }
    }

    private int getStat() {
        return 0;
    }

    private void setStat(int value) {
    }
}
