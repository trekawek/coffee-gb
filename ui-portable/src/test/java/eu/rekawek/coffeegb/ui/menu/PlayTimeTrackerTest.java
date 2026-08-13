package eu.rekawek.coffeegb.ui.menu;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class PlayTimeTrackerTest {

    @Test
    public void accumulatesOnlyAuthoritativeRunningIntervals() {
        AtomicLong clock = new AtomicLong(100L);
        PlayTimeTracker tracker = new PlayTimeTracker(clock::get);

        tracker.start();
        clock.addAndGet(50L);
        assertEquals(50L, tracker.elapsedNanos());
        tracker.setRunning(false);
        clock.addAndGet(500L);
        assertEquals(50L, tracker.elapsedNanos());
        tracker.setRunning(true);
        clock.addAndGet(30L);
        assertEquals(80L, tracker.elapsedNanos());
        tracker.setRunning(false);
        tracker.clear();
        assertEquals(0L, tracker.elapsedNanos());
    }

    @Test
    public void repeatedStatePublicationsDoNotRestartTheClock() {
        AtomicLong clock = new AtomicLong();
        PlayTimeTracker tracker = new PlayTimeTracker(clock::get);

        tracker.start();
        tracker.setRunning(true);
        clock.addAndGet(7L);
        tracker.setRunning(true);
        clock.addAndGet(11L);

        assertEquals(18L, tracker.elapsedNanos());
    }
}
