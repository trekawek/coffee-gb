package eu.rekawek.coffeegb.timer;

public class Counter {

    private final int frequency;

    private int clocks;

    private int counter;

    public Counter(int frequency) {
        this.frequency = frequency;
    }

    // this is invoked 4194304 times a second
    public boolean tick() {
        if (++clocks == 4194304 / frequency) {
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

    public void resetCounter() {
        this.counter = 0;
    }

    public void setCounter(int counter) {
        this.counter = counter;
    }
}
