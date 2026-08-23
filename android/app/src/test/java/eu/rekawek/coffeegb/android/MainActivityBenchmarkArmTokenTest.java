package eu.rekawek.coffeegb.android;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MainActivityBenchmarkArmTokenTest {

    @Test
    public void earlyTokenRemainsBoundToItsPausedSessionUntilAnchorCompletion() {
        MainActivity.BenchmarkArmTokenLatch latch =
                new MainActivity.BenchmarkArmTokenLatch();
        latch.put("benchmark-token-0001", 31L);

        assertTrue(latch.pendingFor(31L));
        assertFalse(latch.pendingFor(32L));
        assertNull(latch.take(32L));
        assertTrue(latch.pendingFor(31L));
        assertEquals("benchmark-token-0001", latch.take(31L));
        assertFalse(latch.pendingFor(31L));
    }

    @Test
    public void loadingOrSessionReplacementCanDiscardAnOldToken() {
        MainActivity.BenchmarkArmTokenLatch latch =
                new MainActivity.BenchmarkArmTokenLatch();
        latch.put("benchmark-token-0001", 31L);

        assertFalse(latch.onStateTransition(31L, state(RuntimeState.Phase.PAUSED, 31L)));
        assertTrue(latch.pendingFor(31L));
        assertTrue(latch.onStateTransition(31L, state(RuntimeState.Phase.LOADING, 31L)));

        assertFalse(latch.pendingFor(31L));

        latch.put("benchmark-token-0002", 31L);
        assertTrue(latch.onStateTransition(31L, state(RuntimeState.Phase.PAUSED, 32L)));

        assertFalse(latch.pendingFor(31L));
        assertNull(latch.take(32L));
    }

    private static RuntimeState state(RuntimeState.Phase phase, long sessionGeneration) {
        return new RuntimeState(phase, phase.name(), List.of(), true,
                phase == RuntimeState.Phase.PAUSED, false, "", false,
                sessionGeneration, 0L, 1L);
    }
}
