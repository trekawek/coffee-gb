package eu.rekawek.coffeegb.ui.menu;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Small monotonic session timer driven by authoritative running/paused transitions.
 *
 * <p>The clock is injectable so host adapters can test a pause-menu capture without waiting for
 * wall time.  A caller snapshots {@link #elapsedNanos()} immediately before it requests a pause;
 * later transitions cannot alter that detached value.
 */
public final class PlayTimeTracker {

    private final LongSupplier nanoClock;
    private long accumulatedNanos;
    private long runningSinceNanos = -1L;

    public PlayTimeTracker() {
        this(System::nanoTime);
    }

    public PlayTimeTracker(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Starts a new session at zero elapsed play time. */
    public synchronized void start() {
        accumulatedNanos = 0L;
        runningSinceNanos = now();
    }

    /** Drops all retained session timing data. */
    public synchronized void clear() {
        accumulatedNanos = 0L;
        runningSinceNanos = -1L;
    }

    /** Applies the controller-owned effective playback state. */
    public synchronized void setRunning(boolean running) {
        if (running) {
            if (runningSinceNanos < 0L) {
                runningSinceNanos = now();
            }
            return;
        }
        if (runningSinceNanos >= 0L) {
            accumulatedNanos = saturatingAdd(accumulatedNanos,
                    Math.max(0L, now() - runningSinceNanos));
            runningSinceNanos = -1L;
        }
    }

    /** Returns elapsed time without mutating the currently running interval. */
    public synchronized long elapsedNanos() {
        if (runningSinceNanos < 0L) {
            return accumulatedNanos;
        }
        return saturatingAdd(accumulatedNanos, Math.max(0L, now() - runningSinceNanos));
    }

    private long now() {
        return nanoClock.getAsLong();
    }

    private static long saturatingAdd(long first, long second) {
        if (Long.MAX_VALUE - first < second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }
}
