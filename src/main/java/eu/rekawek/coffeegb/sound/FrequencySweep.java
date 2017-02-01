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

    private int nr13, nr14;

    private int i;

    private boolean overflow;

    private boolean counterEnabled;

    private boolean negging;

    public void start() {
        counterEnabled = false;
        i = 8192;
    }

    public void trigger() {
        this.negging = false;
        this.overflow = false;

        this.shadowFreq = nr13 | ((nr14 & 0b111) << 8);
        this.timer = period == 0 ? 8 : period;
        this.counterEnabled = period != 0 || shift != 0;

        if (shift > 0) {
            calculate();
        }
    }

    public void setNr10(int value) {
        this.period = (value >> 4) & 0b111;
        this.negate = (value & (1 << 3)) != 0;
        this.shift = value & 0b111;
        if (negging && !negate) {
            overflow = true;
        }
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
            if (!counterEnabled) {
                return;
            }
            if (--timer == 0) {
                timer = period == 0 ? 8 : period;
                if (period != 0) {
                    int newFreq = calculate();
                    if (!overflow && shift != 0) {
                        shadowFreq = newFreq;
                        nr13 = shadowFreq & 0xff;
                        nr14 = (shadowFreq & 0x700) >> 8;
                        calculate();
                    }
                }
            }
        }
    }

    private int calculate() {
        int freq = shadowFreq >> shift;
        if (negate) {
            freq = shadowFreq - freq;
            negging = true;
        } else {
            freq = shadowFreq + freq;
        }
        if (freq > 2047) {
            overflow = true;
        }
        return freq;
    }

    public boolean isEnabled() {
        return !overflow;
    }
}
