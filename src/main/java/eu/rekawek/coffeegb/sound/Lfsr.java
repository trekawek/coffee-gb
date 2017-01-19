package eu.rekawek.coffeegb.sound;

import java.util.Random;

public class Lfsr {

    private int lfsr = new Random().nextInt(0x7fff); // 15 bit

    public int nextBit(boolean widthMode7) {
        boolean x = ((lfsr & 1) ^ ((lfsr & 2) >> 1)) != 0;
        lfsr = lfsr >> 1;
        lfsr = lfsr | (x ? (1 << 14) : 0);
        if (widthMode7) {
            lfsr = lfsr | (x ? (1 << 6) : 0);
        }
        return 1 & ~lfsr;
    }

    public int getValue() {
        return lfsr;
    }
}
