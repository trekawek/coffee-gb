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
        int divider = Gameboy.TICKS_PER_SEC / frequency;
        if (speedMode != null) {
            divider /= speedMode.getSpeedMode();
        }
        if (++clocks >= divider) {
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
