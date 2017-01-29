package eu.rekawek.coffeegb.memory.cart.type;

public class RealTimeClock {

    private long offsetSec;

    private long clockStart = System.currentTimeMillis();

    private boolean halt;

    private long latchStart;

    private int haltSeconds;

    private int haltMinutes;

    private int haltHours;

    private int haltDays;

    public void latch() {
        if (latchStart == 0) {
            latchStart = System.currentTimeMillis();
        }
    }

    public void unlatch() {
        if (latchStart != 0) {
            clockStart += (System.currentTimeMillis() - latchStart);
            latchStart = 0;
        }
    }

    public int getSeconds() {
        return (int) (clockTimeInSec() % 60);
    }

    public int getMinutes() {
        return (int) ((clockTimeInSec() % (60 * 60)) / 60);
    }

    public int getHours() {
        return (int) ((clockTimeInSec() % (60 * 60 * 24)) / (60 * 60));
    }

    public int getDayCounter() {
        return (int) (clockTimeInSec() % (60 * 60 * 24 * 512) / (60 * 60 * 24));
    }

    public boolean isHalt() {
        return halt;
    }

    public boolean isCounterOverflow() {
        return clockTimeInSec() > 60 * 60 * 24 * 512;
    }

    public void setSeconds(int seconds) {
        if (!halt) {
            return;
        }
        haltSeconds = seconds;
    }

    public void setMinutes(int minutes) {
        if (!halt) {
            return;
        }
        haltMinutes = minutes;
    }

    public void setHours(int hours) {
        if (!halt) {
            return;
        }
        haltHours = hours;
    }

    public void setDayCounter(int dayCounter) {
        if (!halt) {
            return;
        }
        haltDays = dayCounter;
    }

    public void setHalt(boolean halt) {
        if (halt) {
            haltSeconds = getSeconds();
            haltMinutes = getMinutes();
            haltHours = getHours();
            haltDays = getDayCounter();
        } else {
            offsetSec = haltSeconds + haltMinutes * 60 + haltHours * 60 * 60 + haltDays * 60 * 60 * 24;
            clockStart = System.currentTimeMillis();
        }
        this.halt = halt;
    }

    public void setCounterOverflow(boolean counterOverflow) {
        if (!halt) {
            return;
        }
        while (isCounterOverflow()) {
            clockStart += 60 * 60 * 24 * 512 * 1000;
        }
    }

    private long clockTimeInSec() {
        long now;
        if (latchStart == 0) {
            now = System.currentTimeMillis();
        } else {
            now = latchStart;
        }
        return (now - clockStart) / 1000 + offsetSec;
    }
}
