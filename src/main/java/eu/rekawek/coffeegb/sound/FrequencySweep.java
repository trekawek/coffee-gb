package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.Gameboy;

public class FrequencySweep {

    private static final int DIVIDER = Gameboy.TICKS_PER_SEC / 128;

    // sweep parameters
    private int period;

    private boolean negate;

    private int shift;

    // current process variables
    private int timer;

    private int shadowFreq;

    private boolean overflow;

    private int nr13, nr14;

    private int i;

    private boolean enabled;

    public void trigger() {
        this.shadowFreq = nr13 | ((nr14 & 0b111) << 8);
        this.timer = period;
        this.enabled = period != 0 || shift != 0;
        this.overflow = false;
        if (shift > 0) {
            calculate();
        }
    }

    public void setNr10(int value) {
        this.period = value >> 4;
        this.negate = (value & (1 << 3)) != 0;
        this.shift = value & 0b111;
        this.timer = period;
    }

    public void setNr13(int value) {
        this.nr13 = value;
    }

    public void setNr14(int value) {
        this.nr14 = value;
        if ((value & (1 << 7)) != 0) {
            trigger();
        }
    }

    public int getNr13() {
        return nr13;
    }

    public int getNr14() {
        return nr14;
    }

    public void tick() {
        if (++i == DIVIDER) {
            i = 0;
            if (enabled && period != 0) {
                if (--timer == 0) {
                    timer = period;
                    int newFreq = calculate();
                    if (newFreq <= 2047 && shift != 0) {
                        shadowFreq = newFreq;
                        nr13 = shadowFreq & 0xff;
                        nr14 = (shadowFreq & 0x700) >> 8;
                    }
                    calculate();
                }
            }
        }
    }

    private int calculate() {
        int diff = shadowFreq >> shift;
        if (negate) {
            diff = (~diff) & 0x7FF;
        }
        int newFreq = shadowFreq + diff;
        if (newFreq > 2047) {
            overflow = true;
        }
        return newFreq;
    }

    public boolean isEnabled() {
        return !overflow;
    }
}
