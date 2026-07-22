package eu.rekawek.coffeegb.core.memory.cart.rtc;

import eu.rekawek.coffeegb.core.Gameboy;
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

    private long subSecondTicks;

    private transient boolean emulationPaused;

    private transient long pauseStartedMillis;

    private boolean latched;

    private int latchedSeconds;

    private int latchedMinutes;

    private int latchedHours;

    private int latchedDays;

    private boolean latchedHalt;

    private boolean latchedCounterOverflow;

    public RealTimeClock(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    public void latch() {
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
        return latched ? latchedSeconds : seconds;
    }

    public int getMinutes() {
        return latched ? latchedMinutes : minutes;
    }

    public int getHours() {
        return latched ? latchedHours : hours;
    }

    public int getDayCounter() {
        return latched ? latchedDays : days;
    }

    public boolean isHalt() {
        return latched ? latchedHalt : halt;
    }

    public boolean isCounterOverflow() {
        return latched ? latchedCounterOverflow : counterOverflow;
    }

    public void setSeconds(int seconds) {
        this.seconds = seconds & 0x3f;
        subSecondTicks = 0;
    }

    public void setMinutes(int minutes) {
        this.minutes = minutes & 0x3f;
    }

    public void setHours(int hours) {
        this.hours = hours & 0x1f;
    }

    public void setDayCounter(int dayCounter) {
        this.days = dayCounter & 0x1ff;
    }

    public void setDayCounterLow(int value) {
        days = (days & 0x100) | (value & 0xff);
    }

    public void setDayCounterHigh(int value) {
        days = (days & 0xff) | ((value & 1) << 8);
    }

    public void setHalt(boolean halt) {
        this.halt = halt;
    }

    public void setCounterOverflow(boolean counterOverflow) {
        this.counterOverflow = counterOverflow;
    }

    public void clearCounterOverflow() {
        setCounterOverflow(false);
    }

    /** Advances the independent MBC3 oscillator by one Game Boy master-clock tick. */
    public void tick() {
        if (halt || emulationPaused) {
            return;
        }
        if (++subSecondTicks == Gameboy.TICKS_PER_SEC) {
            subSecondTicks = 0;
            advanceSeconds(1);
        }
    }

    /**
     * Uses wall time while the emulator is explicitly paused. Active emulation remains
     * tick-driven so RTC diagnostics are independent of host speed.
     */
    public void setEmulationPaused(boolean paused) {
        if (emulationPaused == paused) {
            return;
        }
        if (paused) {
            emulationPaused = true;
            pauseStartedMillis = timeSource.currentTimeMillis();
        } else {
            catchUpPausedTime();
            emulationPaused = false;
        }
    }

    private void catchUpPausedTime() {
        if (!emulationPaused) {
            return;
        }
        long now = timeSource.currentTimeMillis();
        long elapsedMillis = Math.max(0, now - pauseStartedMillis);
        pauseStartedMillis = now;
        if (halt || elapsedMillis == 0) {
            return;
        }

        advanceSeconds(elapsedMillis / 1000);
        long elapsedTicks = (elapsedMillis % 1000) * Gameboy.TICKS_PER_SEC / 1000;
        long ticks = subSecondTicks + elapsedTicks;
        advanceSeconds(ticks / Gameboy.TICKS_PER_SEC);
        subSecondTicks = ticks % Gameboy.TICKS_PER_SEC;
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
        subSecondTicks = 0;
        emulationPaused = false;
        pauseStartedMillis = 0;

        long now = timeSource.currentTimeMillis();
        long timestampMillis = clockData[10] * 1000;
        if (!halt && timestampMillis > 0 && now > timestampMillis) {
            advanceSeconds((now - timestampMillis) / 1000);
        }
    }

    public long[] serialize() {
        catchUpPausedTime();
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
        catchUpPausedTime();
        return new RealTimeClockMemento(seconds, minutes, hours, days, halt, counterOverflow,
                subSecondTicks, latched, latchedSeconds, latchedMinutes, latchedHours, latchedDays, latchedHalt,
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
        subSecondTicks = mem.subSecondTicks;
        latched = mem.latched;
        latchedSeconds = mem.latchedSeconds;
        latchedMinutes = mem.latchedMinutes;
        latchedHours = mem.latchedHours;
        latchedDays = mem.latchedDays;
        latchedHalt = mem.latchedHalt;
        latchedCounterOverflow = mem.latchedCounterOverflow;
        if (emulationPaused) {
            pauseStartedMillis = timeSource.currentTimeMillis();
        }
    }

    private record RealTimeClockMemento(int seconds, int minutes, int hours, int days, boolean halt,
                                        boolean counterOverflow, long subSecondTicks,
                                        boolean latched, int latchedSeconds, int latchedMinutes, int latchedHours,
                                        int latchedDays, boolean latchedHalt,
                                        boolean latchedCounterOverflow) implements Memento<RealTimeClock> {
    }
}
