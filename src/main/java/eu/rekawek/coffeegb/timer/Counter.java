package eu.rekawek.coffeegb.timer;

import eu.rekawek.coffeegb.Gameboy;

public class Counter {

    private final int frequency;

    private int clocks;

    private int counter;

    public Counter(int frequency) {
        this.frequency = frequency;
    }

    public boolean tick() {
        if (++clocks == Gameboy.TICKS_PER_SEC / frequency) {
            clocks = 0;
            counter = (counter + 1) & 0xff;
            return true;
        } else {
            return false;
        }
    }

    public int getCounter() {
        return counter;
    }

    public void setCounter(int counter) {
        this.counter = counter;
    }
}
