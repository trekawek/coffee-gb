package eu.rekawek.coffeegb.core.memory.cart.rtc;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class RealTimeClock implements Serializable, Originator<RealTimeClock> {

    private static final long SECONDS_PER_DAY = 24 * 60 * 60;

    private static final long SECONDS_PER_CYCLE = 512 * SECONDS_PER_DAY;

    private final TimeSource timeSource;

    private int seconds;

    private int minutes;

    private int hours;

    private int days;

    private boolean halt;

    private boolean counterOverflow;

    private long lastUpdateMillis;

    private int subSecondMillis;

    private boolean latched;

    private int latchedSeconds;

    private int latchedMinutes;

    private int latchedHours;

    private int latchedDays;

    private boolean latchedHalt;

    private boolean latchedCounterOverflow;

    public RealTimeClock(TimeSource timeSource) {
        this.timeSource = timeSource;
        this.lastUpdateMillis = timeSource.currentTimeMillis();
    }

    public void latch() {
        updateClock();
        latchedSeconds = seconds;
        latchedMinutes = minutes;
        latchedHours = hours;
        latchedDays = days;
        latchedHalt = halt;
        latchedCounterOverflow = counterOverflow;
        latched = true;
    }

    public void unlatch() {
        latched = false;
    }

    public int getSeconds() {
        updateClock();
        return latched ? latchedSeconds : seconds;
    }

    public int getMinutes() {
        updateClock();
        return latched ? latchedMinutes : minutes;
    }

    public int getHours() {
        updateClock();
        return latched ? latchedHours : hours;
    }

    public int getDayCounter() {
        updateClock();
        return latched ? latchedDays : days;
    }

    public boolean isHalt() {
        updateClock();
        return latched ? latchedHalt : halt;
    }

    public boolean isCounterOverflow() {
        updateClock();
        return latched ? latchedCounterOverflow : counterOverflow;
    }

    public void setSeconds(int seconds) {
        updateClock();
        this.seconds = seconds & 0x3f;
        subSecondMillis = 0;
    }

    public void setMinutes(int minutes) {
        updateClock();
        this.minutes = minutes & 0x3f;
    }

    public void setHours(int hours) {
        updateClock();
        this.hours = hours & 0x1f;
    }

    public void setDayCounter(int dayCounter) {
        updateClock();
        this.days = dayCounter & 0x1ff;
    }

    public void setDayCounterLow(int value) {
        updateClock();
        days = (days & 0x100) | (value & 0xff);
    }

    public void setDayCounterHigh(int value) {
        updateClock();
        days = (days & 0xff) | ((value & 1) << 8);
    }

    public void setHalt(boolean halt) {
        updateClock();
        this.halt = halt;
    }

    public void setCounterOverflow(boolean counterOverflow) {
        updateClock();
        this.counterOverflow = counterOverflow;
    }

    public void clearCounterOverflow() {
        setCounterOverflow(false);
    }

    private void updateClock() {
        long now = timeSource.currentTimeMillis();
        long elapsedMillis = Math.max(0, now - lastUpdateMillis);
        lastUpdateMillis = now;
        if (halt || elapsedMillis == 0) {
            return;
        }

        long elapsedWithRemainder = elapsedMillis + subSecondMillis;
        subSecondMillis = (int) (elapsedWithRemainder % 1000);
        advanceSeconds(elapsedWithRemainder / 1000);
    }

    private void advanceSeconds(long count) {
        while (count > 0 && (seconds > 59 || minutes > 59 || hours > 23)) {
            tickSecond();
            count--;
        }
        if (count == 0) {
            return;
        }

        long total = days * SECONDS_PER_DAY + hours * 3600L + minutes * 60L + seconds + count;
        if (total >= SECONDS_PER_CYCLE) {
            counterOverflow = true;
        }
        total %= SECONDS_PER_CYCLE;
        days = (int) (total / SECONDS_PER_DAY);
        total %= SECONDS_PER_DAY;
        hours = (int) (total / 3600);
        total %= 3600;
        minutes = (int) (total / 60);
        seconds = (int) (total % 60);
    }

    private void tickSecond() {
        if (seconds == 59) {
            seconds = 0;
            tickMinute();
        } else if (seconds == 63) {
            seconds = 0;
        } else {
            seconds = (seconds + 1) & 0x3f;
        }
    }

    private void tickMinute() {
        if (minutes == 59) {
            minutes = 0;
            tickHour();
        } else if (minutes == 63) {
            minutes = 0;
        } else {
            minutes = (minutes + 1) & 0x3f;
        }
    }

    private void tickHour() {
        if (hours == 23) {
            hours = 0;
            if (++days == 512) {
                days = 0;
                counterOverflow = true;
            }
        } else if (hours == 31) {
            hours = 0;
        } else {
            hours = (hours + 1) & 0x1f;
        }
    }

    public void deserialize(long[] clockData) {
        seconds = (int) clockData[0] & 0x3f;
        minutes = (int) clockData[1] & 0x3f;
        hours = (int) clockData[2] & 0x1f;
        int control = (int) clockData[4];
        days = ((control & 1) << 8) | ((int) clockData[3] & 0xff);
        halt = (control & 0x40) != 0;
        counterOverflow = (control & 0x80) != 0;
        latched = false;
        subSecondMillis = 0;

        long now = timeSource.currentTimeMillis();
        lastUpdateMillis = now;
        long timestampMillis = clockData[10] * 1000;
        if (!halt && timestampMillis > 0 && now > timestampMillis) {
            advanceSeconds((now - timestampMillis) / 1000);
        }
    }

    public long[] serialize() {
        updateClock();
        long control = ((days >> 8) & 1) | (halt ? 0x40 : 0) | (counterOverflow ? 0x80 : 0);
        long[] clockData = new long[11];
        clockData[0] = clockData[5] = seconds;
        clockData[1] = clockData[6] = minutes;
        clockData[2] = clockData[7] = hours;
        clockData[3] = clockData[8] = days & 0xff;
        clockData[4] = clockData[9] = control;
        clockData[10] = timeSource.currentTimeMillis() / 1000;
        return clockData;
    }

    @Override
    public Memento<RealTimeClock> saveToMemento() {
        updateClock();
        return new RealTimeClockMemento(seconds, minutes, hours, days, halt, counterOverflow, lastUpdateMillis,
                subSecondMillis, latched, latchedSeconds, latchedMinutes, latchedHours, latchedDays, latchedHalt,
                latchedCounterOverflow);
    }

    @Override
    public void restoreFromMemento(Memento<RealTimeClock> memento) {
        if (!(memento instanceof RealTimeClockMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        seconds = mem.seconds;
        minutes = mem.minutes;
        hours = mem.hours;
        days = mem.days;
        halt = mem.halt;
        counterOverflow = mem.counterOverflow;
        lastUpdateMillis = mem.lastUpdateMillis;
        subSecondMillis = mem.subSecondMillis;
        latched = mem.latched;
        latchedSeconds = mem.latchedSeconds;
        latchedMinutes = mem.latchedMinutes;
        latchedHours = mem.latchedHours;
        latchedDays = mem.latchedDays;
        latchedHalt = mem.latchedHalt;
        latchedCounterOverflow = mem.latchedCounterOverflow;
    }

    private record RealTimeClockMemento(int seconds, int minutes, int hours, int days, boolean halt,
                                        boolean counterOverflow, long lastUpdateMillis, int subSecondMillis,
                                        boolean latched, int latchedSeconds, int latchedMinutes, int latchedHours,
                                        int latchedDays, boolean latchedHalt,
                                        boolean latchedCounterOverflow) implements Memento<RealTimeClock> {
    }
}
