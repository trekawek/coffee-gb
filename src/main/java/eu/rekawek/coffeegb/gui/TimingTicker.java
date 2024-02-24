package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.Gameboy;

public class TimingTicker implements Runnable {

    private final long PERIODS_PER_SECOND = 65536;
    private final long TICKS_PER_PERIOD = Gameboy.TICKS_PER_SEC / PERIODS_PER_SECOND;
    private final long PERIOD_IN_NANOS = 1_000_000_000 / PERIODS_PER_SECOND;

    private long lastSleep = System.nanoTime();

    private long ticks = 0;

    private volatile boolean delayEnabled = true;

    @Override
    public void run() {
        if (++ticks < TICKS_PER_PERIOD) {
            return;
        }
        ticks = 0;
        if (delayEnabled) {
            while (System.nanoTime() - lastSleep < PERIOD_IN_NANOS) {
            }
        }
        lastSleep = System.nanoTime();
    }

    public void setDelayEnabled(boolean delayEnabled) {
        this.delayEnabled = delayEnabled;
    }
}
