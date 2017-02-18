package eu.rekawek.coffeegb.timer;

public class Counter {

    private int mode;

    private int counter;

    private int delayedCounter;

    private int delay = 0;

    public Counter(int mode) {
        this.mode = mode;
    }

    public boolean onClockUpdate(int oldClock, int newClock) {
        if (delay > 0) {
            if (--delay == 0) {
                counter = delayedCounter;
            }
        }

        int modeMask = getModeMask();
        if ((oldClock & modeMask) == modeMask && (newClock & modeMask) == 0) {
            counter = (counter + 1) & 0xff;
            return true;
        } else {
            return false;
        }
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public int getCounter() {
        return counter;
    }

    public void setCounter(int counter) {
        this.counter = counter;
    }

    public void setDelayedCounter(int delayedCounter) {
        this.counter = 0;
        this.delayedCounter = delayedCounter;
        this.delay = 4;
    }

    public void updateDelayedCounter(int delayedCounter) {
        this.delayedCounter = delayedCounter;
    }

    public boolean isReloading() {
        return delay > 0;
    }

    public int getModeMask() {
        switch (mode) {
            case 0b00:
                return 1 << 9;
            case 0b01:
                return 1 << 3;
            case 0b10:
                return 1 << 5;
            case 0b11:
                return 1 << 7;
        }
        throw new IllegalStateException();
    }
}
