package eu.rekawek.coffeegb.memory.cart.rtc;

public class RealTimeClock {

    private final Clock clock;

    private long offsetSec;

    private long clockStart;

    private boolean halt;

    private long latchStart;

    private int haltSeconds;

    private int haltMinutes;

    private int haltHours;

    private int haltDays;

    public RealTimeClock(Clock clock) {
        this.clock = clock;
        this.clockStart = clock.currentTimeMillis();
    }

    public void latch() {
        latchStart = clock.currentTimeMillis();
    }

    public void unlatch() {
        latchStart = 0;
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
        return clockTimeInSec() >= 60 * 60 * 24 * 512;
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
        if (halt && !this.halt) {
            latch();
            haltSeconds = getSeconds();
            haltMinutes = getMinutes();
            haltHours = getHours();
            haltDays = getDayCounter();
            unlatch();
        } else if (!halt && this.halt) {
            offsetSec = haltSeconds + haltMinutes * 60 + haltHours * 60 * 60 + haltDays * 60 * 60 * 24;
            clockStart = clock.currentTimeMillis();
        }
        this.halt = halt;
    }

    public void clearCounterOverflow() {
        while (isCounterOverflow()) {
            offsetSec -= 60 * 60 * 24 * 512;
        }
    }

    private long clockTimeInSec() {
        long now;
        if (latchStart == 0) {
            now = clock.currentTimeMillis();
        } else {
            now = latchStart;
        }
        return (now - clockStart) / 1000 + offsetSec;
    }

    public void deserialize(long[] clockData) {
        long seconds = clockData[0];
        long minutes = clockData[1];
        long hours = clockData[2];
        long days = clockData[3];
        long daysHigh = clockData[4];
        long timestamp = clockData[10];

        this.clockStart = timestamp * 1000;
        this.offsetSec = seconds + minutes * 60 + hours * 60 * 60 + days * 24 * 60 * 60 + daysHigh * 256 * 24 * 60 * 60;
    }

    public long[] serialize() {
        long[] clockData = new long[11];
        latch();
        clockData[0] = clockData[5] = getSeconds();
        clockData[1] = clockData[6] = getMinutes();
        clockData[2] = clockData[7] = getHours();
        clockData[3] = clockData[8] = getDayCounter() % 256;
        clockData[4] = clockData[9] = getDayCounter() / 256;
        clockData[10] = latchStart / 1000;
        unlatch();
        return clockData;
    }
}
