package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.Gameboy;

public class FrequencySweep {

    private final int sweep;

    private final boolean negate;

    private final int sweepShift;

    private final int initialFreq;

    private int freq;

    private boolean finished;

    private int i;

    public FrequencySweep(int value, int initialFreq) {
        this.sweep = value >> 4;
        this.negate = (value & (1 << 3)) != 0;
        this.sweepShift = value & 0b111;
        this.initialFreq = initialFreq;
        start();
    }

    public boolean isEnabled() {
        return sweep > 0;
    }

    public void start() {
        freq = initialFreq;
        i = 0;
    }

    public void tick() {
        if (finished) {
            return;
        }
        if (++i == sweep * Gameboy.TICKS_PER_SEC / 128) {
            i = 0;
            int diff = freq >> sweepShift;
            if (negate) {
                diff = (~diff) & 0x7FF;
            }
            freq = freq + diff;
            if (freq > 2047) {
                freq = 2047;
                finished = true;
            }
        }
    }

    public int getFreq() {
        return freq;
    }
}
