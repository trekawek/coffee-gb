package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TiltSessionLifecycleTest {

    @Test
    public void resetDuringInitialOpenCannotSupersedeTheOnlyPendingStart() {
        TiltSessionLifecycle lifecycle = new TiltSessionLifecycle();
        lifecycle.beginOpenTransition(1L, true);

        assertFalse("a selected layout is not yet a committed controller session",
                lifecycle.hasActiveSession());
        // AndroidEmulationRuntime.reset() therefore does not begin or post an anonymous reload.
        TiltSessionLifecycle.OpenStart start = lifecycle.openStarted(1L);

        assertTrue(start.accepted());
        assertTrue(start.completesCurrentTransition());
        assertTrue(start.sensorActive());
        assertFalse(lifecycle.anonymousReloadPending());
        assertTrue(lifecycle.hasActiveSession());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.RUNNING));
    }

    @Test
    public void stagedReplacementFailureRetainsTheStillLiveTiltSession() {
        TiltSessionLifecycle lifecycle = activeSession(true);
        lifecycle.beginOpenTransition(2L, false);

        TiltSessionLifecycle.OpenFailure failure = lifecycle.openFailed(2L);

        assertTrue(failure.currentTransition());
        assertTrue(failure.sensorActive());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.FAILED));
    }

    @Test
    public void committedReplacementFailureReleasesTheStoppedTiltSession() {
        TiltSessionLifecycle lifecycle = activeSession(true);
        lifecycle.beginOpenTransition(2L, true);

        assertTrue("sensor remains registered during a tilt-to-tilt ownership handoff",
                lifecycle.stopped());
        TiltSessionLifecycle.OpenFailure failure = lifecycle.openFailed(2L);

        assertTrue(failure.currentTransition());
        assertFalse(failure.sensorActive());
        assertFalse(lifecycle.orientationLocked(RuntimeState.Phase.FAILED));
    }

    @Test
    public void successfulTiltReplacementStaysLockedAcrossTheStoppedState() {
        TiltSessionLifecycle lifecycle = activeSession(true);
        lifecycle.beginOpenTransition(2L, true);

        assertTrue(lifecycle.stopped());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.STOPPED));
        TiltSessionLifecycle.OpenStart start = lifecycle.openStarted(2L);

        assertTrue(start.accepted());
        assertTrue(start.completesCurrentTransition());
        assertTrue(start.sensorActive());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.RUNNING));
    }

    @Test
    public void anonymousSystemReloadRestoresTheSameActiveTiltCapability() {
        TiltSessionLifecycle lifecycle = activeSession(true);
        lifecycle.beginRequestedAnonymousReloadTransition();

        assertTrue(lifecycle.stopped());
        TiltSessionLifecycle.AnonymousStart start = lifecycle.anonymousStarted();

        assertTrue(start.accepted());
        assertTrue(start.completesCurrentTransition());
        assertTrue(start.sensorActive());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.RUNNING));
    }

    @Test
    public void newerOpenRetainsASupersededAnonymousReloadWhenTheOpenFails() {
        TiltSessionLifecycle lifecycle = activeSession(true);
        lifecycle.beginRequestedAnonymousReloadTransition();
        lifecycle.beginOpenTransition(2L, false);

        assertFalse(lifecycle.stopped());
        TiltSessionLifecycle.AnonymousStart staleStart = lifecycle.anonymousStarted();
        TiltSessionLifecycle.OpenFailure failure = lifecycle.openFailed(2L);

        assertTrue(staleStart.accepted());
        assertFalse(staleStart.completesCurrentTransition());
        assertTrue(staleStart.sensorActive());
        assertTrue(failure.currentTransition());
        assertTrue("the failed newer open retains the controller's temporary reload session",
                failure.sensorActive());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.FAILED));
    }

    @Test
    public void anonymousReloadUsesRetainedTiltSessionAfterNormalOpenFails() {
        TiltSessionLifecycle lifecycle = activeSession(true);
        lifecycle.beginOpenTransition(2L, false);
        assertTrue(lifecycle.openFailed(2L).sensorActive());

        lifecycle.beginRequestedAnonymousReloadTransition();
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.LOADING));
        assertTrue(lifecycle.stopped());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.STOPPED));
        assertTrue(lifecycle.anonymousStarted().sensorActive());
    }

    @Test
    public void anonymousReloadDoesNotInheritTiltFromFailedTiltCandidate() {
        TiltSessionLifecycle lifecycle = activeSession(false);
        lifecycle.beginOpenTransition(2L, true);
        assertFalse(lifecycle.openFailed(2L).sensorActive());

        lifecycle.beginRequestedAnonymousReloadTransition();
        assertFalse(lifecycle.orientationLocked(RuntimeState.Phase.LOADING));
        assertFalse(lifecycle.stopped());
        assertFalse(lifecycle.orientationLocked(RuntimeState.Phase.STOPPED));
        assertFalse(lifecycle.anonymousStarted().sensorActive());
    }

    @Test
    public void supersededTiltOpenBecomesLiveWhenNewerNormalOpenFails() {
        TiltSessionLifecycle lifecycle = activeSession(false);
        lifecycle.beginOpenTransition(2L, true);
        lifecycle.beginOpenTransition(3L, false);

        assertFalse(lifecycle.stopped());
        TiltSessionLifecycle.OpenStart staleStart = lifecycle.openStarted(2L);
        TiltSessionLifecycle.OpenFailure failure = lifecycle.openFailed(3L);

        assertTrue(staleStart.accepted());
        assertFalse(staleStart.completesCurrentTransition());
        assertTrue(staleStart.sensorActive());
        assertTrue(failure.currentTransition());
        assertTrue(failure.sensorActive());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.FAILED));
    }

    @Test
    public void supersededNormalOpenBecomesLiveWhenNewerTiltOpenFails() {
        TiltSessionLifecycle lifecycle = activeSession(true);
        lifecycle.beginOpenTransition(2L, false);
        lifecycle.beginOpenTransition(3L, true);

        assertTrue(lifecycle.stopped());
        TiltSessionLifecycle.OpenStart staleStart = lifecycle.openStarted(2L);
        TiltSessionLifecycle.OpenFailure failure = lifecycle.openFailed(3L);

        assertTrue(staleStart.accepted());
        assertFalse(staleStart.completesCurrentTransition());
        assertFalse(staleStart.sensorActive());
        assertTrue(failure.currentTransition());
        assertFalse(failure.sensorActive());
        assertFalse(lifecycle.orientationLocked(RuntimeState.Phase.FAILED));
    }

    @Test
    public void newerResetRetainsACommittedSupersededOpenWhenResetFails() {
        TiltSessionLifecycle lifecycle = activeSession(false);
        lifecycle.beginOpenTransition(2L, true);
        lifecycle.beginRequestedAnonymousReloadTransition();

        assertFalse(lifecycle.stopped());
        TiltSessionLifecycle.OpenStart staleOpen = lifecycle.openStarted(2L);

        assertTrue(staleOpen.accepted());
        assertFalse(staleOpen.completesCurrentTransition());
        assertTrue(staleOpen.sensorActive());
        assertTrue(lifecycle.anonymousFailed());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.FAILED));
    }

    @Test
    public void observedStaleAnonymousLoadCannotSupersedeNewerOpen() {
        TiltSessionLifecycle lifecycle = activeSession(false);
        lifecycle.beginOpenTransition(2L, true);

        lifecycle.beginObservedAnonymousReloadTransition();

        assertTrue(lifecycle.openTransitionPending());
        assertFalse(lifecycle.anonymousReloadPending());
        assertTrue(lifecycle.orientationLocked(RuntimeState.Phase.LOADING));
    }

    @Test
    public void supersededOpenIsPromotedIfTheNewerRequestIsCancelled() {
        TiltSessionLifecycle lifecycle = activeSession(false);
        lifecycle.beginOpenTransition(2L, true);
        lifecycle.beginOpenTransition(3L, false);
        lifecycle.openCancelled(3L);

        lifecycle.stopped();
        TiltSessionLifecycle.OpenStart start = lifecycle.openStarted(2L);

        assertTrue(start.accepted());
        assertTrue(start.completesCurrentTransition());
        assertTrue(start.sensorActive());
    }

    private static TiltSessionLifecycle activeSession(boolean usesTilt) {
        TiltSessionLifecycle lifecycle = new TiltSessionLifecycle();
        lifecycle.beginOpenTransition(1L, usesTilt);
        TiltSessionLifecycle.OpenStart start = lifecycle.openStarted(1L);
        assertTrue(start.accepted());
        assertTrue(start.completesCurrentTransition());
        return lifecycle;
    }
}
