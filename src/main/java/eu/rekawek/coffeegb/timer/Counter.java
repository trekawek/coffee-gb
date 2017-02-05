package eu.rekawek.coffeegb.timer;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.SpeedMode;

public class Counter {

    private final int frequency;

    private final SpeedMode speedMode;

    private int clocks;

    private int counter;

    public Counter(int frequency, SpeedMode speedMode) {
        this.frequency = frequency;
        this.speedMode = speedMode;
    }

    public boolean tick() {
        if (++clocks >= Gameboy.TICKS_PER_SEC / frequency / speedMode.getSpeedMode()) {
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
