package eu.rekawek.coffeegb.memory.cart.rtc;

import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.memory.cart.battery.FileBattery;

public class RealTimeClock {

    private final Clock clock;

    private final Battery battery;

    private long offsetSec;

    private long clockStart;

    private boolean halt;

    private long latchStart;

    private int haltSeconds;

    private int haltMinutes;

    private int haltHours;

    private int haltDays;

    public RealTimeClock(Battery battery, Clock clock) {
        this.clock = clock;
        this.battery = battery;
        long[] data = battery.loadClock();
        offsetSec = data[0];
        clockStart = data[1] == 0 ? clock.currentTimeMillis() : data[1];
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
            battery.saveClock(new long[] {offsetSec, clockStart});
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
}
