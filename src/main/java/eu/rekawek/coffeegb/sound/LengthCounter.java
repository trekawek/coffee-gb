package eu.rekawek.coffeegb.sound;

import static eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC;

public class LengthCounter {

    private final int DIVIDER = TICKS_PER_SEC / 256;

    private int length;

    private int cycle;

    public void tick() {
        if (length == 0) {
            return;
        }
        if (++cycle == DIVIDER) {
            cycle = 0;
            length--;
        }
    }

    public boolean isDisabled() {
        return length == 0;
    }

    public void setLength(int length) {
        this.length = length;
    }
}
