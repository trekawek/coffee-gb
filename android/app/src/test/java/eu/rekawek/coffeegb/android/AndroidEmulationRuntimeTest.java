package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidEmulationRuntimeTest {

    @Test
    public void resetReloadIsAcceptedAfterItsPreviousSessionHasAlreadyStopped() {
        // The controller posts STOPPED before the no-request STARTED event for a reset.  The
        // active layout and its monotonic generation, rather than a RUNNING/PAUSED state, retain
        // the identity needed to accept that replacement session.
        assertTrue(AndroidEmulationRuntime.isResetReload(null, true, 12L, 11L));
    }

    @Test
    public void resetReloadRejectsMissingOrStaleGenerationsAndNormalOpenRequests() {
        assertFalse(AndroidEmulationRuntime.isResetReload(null, true, null, 11L));
        assertFalse(AndroidEmulationRuntime.isResetReload(null, true, 11L, 11L));
        assertFalse(AndroidEmulationRuntime.isResetReload(7L, true, 12L, 11L));
        assertFalse(AndroidEmulationRuntime.isResetReload(null, false, 12L, 11L));
    }
}
