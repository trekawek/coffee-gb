package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.ExecutionMode;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidPerformanceBoostTest {

    @After
    public void restoreThreadPriorityState() {
        AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY, ignored -> { });
    }

    @Test
    public void accuracyDoesNotTouchThreadPriority() {
        // A previous test/session may have used this JVM thread; clear only a boost installed by
        // this helper before asserting the fresh Accuracy behavior.
        AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY, ignored -> { });
        AtomicInteger calls = new AtomicInteger();
        assertFalse(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY,
                priority -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    @Test
    public void performanceRequestsUrgentDisplayPriority() {
        AtomicInteger selected = new AtomicInteger(Integer.MIN_VALUE);
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY, selected::set));
        assertEquals(AndroidPerformanceBoost.PERFORMANCE_THREAD_PRIORITY, selected.get());
    }

    @Test
    public void accuracyRestoresActualPrecedingPriority() {
        AtomicInteger selected = new AtomicInteger(Integer.MIN_VALUE);
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE, () -> 7,
                selected::set));
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY, () -> 7,
                selected::set));
        assertEquals(7, selected.get());
    }

    @Test
    public void rejectedSchedulerHintIsNonFatal() {
        assertFalse(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE, () -> 7,
                priority -> { throw new SecurityException("denied"); }));
    }

    @Test
    public void rejectedRestoreCanBeRetriedWithoutLosingOriginalPriority() {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE, () -> 11,
                priority -> { }));
        assertFalse(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY, () -> 11,
                priority -> {
                    calls.incrementAndGet();
                    throw new SecurityException("denied");
                }));
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY, () -> 11,
                calls::set));
        assertEquals(11, calls.get());
    }
}
