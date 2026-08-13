package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class AndroidStateSaveCompletionTrackerTest {

    @Test
    public void newerSameSlotSaveReleasesOlderActivityCallback() {
        AndroidStateSaveCompletionTracker tracker = new AndroidStateSaveCompletionTracker();
        Runnable oldCallback = () -> { };
        Runnable newCallback = () -> { };

        tracker.register(2, 10L, oldCallback);
        tracker.register(2, 11L, newCallback);

        assertNull(tracker.complete(10L));
        assertSame(newCallback, tracker.complete(11L));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    public void callbacklessSaveStillSupersedesPriorCallbackForThatSlot() {
        AndroidStateSaveCompletionTracker tracker = new AndroidStateSaveCompletionTracker();
        Runnable callback = () -> { };

        tracker.register(1, 20L, callback);
        tracker.register(1, 21L, null);

        assertNull(tracker.complete(20L));
        assertNull(tracker.complete(21L));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    public void differentSlotsRetainIndependentCallbacks() {
        AndroidStateSaveCompletionTracker tracker = new AndroidStateSaveCompletionTracker();
        Runnable first = () -> { };
        Runnable second = () -> { };

        tracker.register(0, 30L, first);
        tracker.register(1, 31L, second);

        assertSame(first, tracker.complete(30L));
        assertSame(second, tracker.complete(31L));
        assertEquals(0, tracker.pendingCount());
    }
}
