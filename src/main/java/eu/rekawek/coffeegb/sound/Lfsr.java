package eu.rekawek.coffeegb.sound;

public class Lfsr {

    private int lfsr;

    public Lfsr() {
        reset();
    }

    public void start() {
        reset();
    }

    public void reset() {
        lfsr = 0x7fff;
    }

    public int nextBit(boolean widthMode7) {
        boolean x = ((lfsr & 1) ^ ((lfsr & 2) >> 1)) != 0;
        lfsr = lfsr >> 1;
        lfsr = lfsr | (x ? (1 << 14) : 0);
        if (widthMode7) {
            lfsr = lfsr | (x ? (1 << 6) : 0);
        }
        return 1 & ~lfsr;
    }

    int getValue() {
        return lfsr;
    }
}
