package eu.rekawek.coffeegb.android;

import java.util.HashMap;
import java.util.Map;

/** Tracks tilt ownership across staged controller replacements and their asynchronous events. */
final class TiltSessionLifecycle {

    /** Superseded requests remain until their start, failure, or cancellation event arrives. */
    private final Map<Long, Boolean> openRequests = new HashMap<>();
    private long pendingOpenRequestId;
    private boolean pendingOpenUsesTilt;
    private boolean anonymousReloadPending;
    private boolean activeSessionKnown;
    private boolean activeSessionUsesTilt;
    private boolean stoppedSessionKnown;
    private boolean stoppedSessionUsesTilt;

    void beginOpenTransition(long requestId, boolean usesTilt) {
        if (requestId <= 0L) {
            throw new IllegalArgumentException("requestId must be positive");
        }
        openRequests.put(requestId, usesTilt);
        pendingOpenRequestId = requestId;
        pendingOpenUsesTilt = usesTilt;
        anonymousReloadPending = false;
    }

    /** A direct reset is the newest host intent and supersedes any still-staged document open. */
    void beginRequestedAnonymousReloadTransition() {
        pendingOpenRequestId = 0L;
        pendingOpenUsesTilt = false;
        anonymousReloadPending = true;
    }

    /** A controller loading event may be stale, so it cannot supersede a newer document open. */
    void beginObservedAnonymousReloadTransition() {
        if (pendingOpenRequestId == 0L) {
            anonymousReloadPending = true;
        }
    }

    boolean openTransitionPending() {
        return pendingOpenRequestId != 0L;
    }

    boolean anonymousReloadPending() {
        return anonymousReloadPending;
    }

    /** True only after a controller session has actually committed and before it stops. */
    boolean hasActiveSession() {
        return activeSessionKnown;
    }

    /** Accepts either the current document open or a superseded open that became live first. */
    OpenStart openStarted(long requestId) {
        Boolean usesTilt = openRequests.remove(requestId);
        if (usesTilt == null) {
            return new OpenStart(false, false, activeSessionUsesTilt);
        }
        activeSessionKnown = true;
        activeSessionUsesTilt = usesTilt;
        stoppedSessionKnown = false;
        boolean currentTransition = requestId == pendingOpenRequestId
                || (pendingOpenRequestId == 0L && !anonymousReloadPending);
        if (requestId == pendingOpenRequestId) {
            pendingOpenRequestId = 0L;
            pendingOpenUsesTilt = false;
        }
        return new OpenStart(true, currentTransition, activeSessionUsesTilt);
    }

    /**
     * Accepts a controller-owned reset/system reload. A superseded anonymous reload may become
     * the temporary live session without taking ownership away from a newer document open.
     */
    AnonymousStart anonymousStarted() {
        boolean currentTransition = anonymousReloadPending;
        if (!currentTransition && !(pendingOpenRequestId != 0L && stoppedSessionKnown)) {
            return new AnonymousStart(false, false, activeSessionUsesTilt);
        }
        if (stoppedSessionKnown) {
            activeSessionUsesTilt = stoppedSessionUsesTilt;
        }
        activeSessionKnown = true;
        stoppedSessionKnown = false;
        if (currentTransition) {
            anonymousReloadPending = false;
        }
        return new AnonymousStart(true, currentTransition, activeSessionUsesTilt);
    }

    /** Ends committed ownership while optionally keeping the sensor warm across a tilt handoff. */
    boolean stopped() {
        boolean transitionUsesTilt = pendingOpenRequestId != 0L
                ? pendingOpenUsesTilt : anonymousReloadPending && activeSessionUsesTilt;
        boolean keepSensor = activeSessionKnown && activeSessionUsesTilt && transitionUsesTilt;
        stoppedSessionKnown = activeSessionKnown;
        stoppedSessionUsesTilt = activeSessionUsesTilt;
        activeSessionKnown = false;
        activeSessionUsesTilt = false;
        return keepSensor;
    }

    /** Completes a failed current open without disturbing a newer or unrelated transition. */
    OpenFailure openFailed(long requestId) {
        if (openRequests.remove(requestId) == null) {
            return new OpenFailure(false, activeSessionUsesTilt);
        }
        boolean currentTransition = requestId == pendingOpenRequestId
                || (pendingOpenRequestId == 0L && !anonymousReloadPending);
        if (requestId == pendingOpenRequestId) {
            pendingOpenRequestId = 0L;
            pendingOpenUsesTilt = false;
        }
        if (!currentTransition) {
            return new OpenFailure(false, activeSessionUsesTilt);
        }
        stoppedSessionKnown = false;
        return new OpenFailure(true, activeSessionUsesTilt);
    }

    /** Cancels the current anonymous transition and returns any retained live-session capability. */
    boolean anonymousFailed() {
        anonymousReloadPending = false;
        stoppedSessionKnown = false;
        return activeSessionUsesTilt;
    }

    void openCancelled(long requestId) {
        openRequests.remove(requestId);
        if (requestId == pendingOpenRequestId) {
            pendingOpenRequestId = 0L;
            pendingOpenUsesTilt = false;
        }
    }

    void clear() {
        openRequests.clear();
        pendingOpenRequestId = 0L;
        pendingOpenUsesTilt = false;
        anonymousReloadPending = false;
        activeSessionKnown = false;
        activeSessionUsesTilt = false;
        stoppedSessionKnown = false;
    }

    boolean orientationLocked(RuntimeState.Phase phase) {
        if (pendingOpenRequestId == 0L && !anonymousReloadPending) {
            return activeSessionKnown && activeSessionUsesTilt;
        }
        boolean transitionUsesTilt = pendingOpenRequestId != 0L
                ? pendingOpenUsesTilt
                : stoppedSessionKnown ? stoppedSessionUsesTilt : activeSessionUsesTilt;
        return transitionUsesTilt
                && (phase == RuntimeState.Phase.LOADING
                        || phase == RuntimeState.Phase.STOPPED
                        || phase == RuntimeState.Phase.RUNNING
                        || phase == RuntimeState.Phase.PAUSED);
    }

    record OpenStart(boolean accepted, boolean completesCurrentTransition,
            boolean sensorActive) {
    }

    record AnonymousStart(boolean accepted, boolean completesCurrentTransition,
            boolean sensorActive) {
    }

    record OpenFailure(boolean currentTransition, boolean sensorActive) {
    }
}
