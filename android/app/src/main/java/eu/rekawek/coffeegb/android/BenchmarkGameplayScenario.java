package eu.rekawek.coffeegb.android;

/**
 * Deterministic, benchmark-only input preconditioning timeline.
 *
 * <p>The timeline is advanced only by a native display frame-ready callback.  It deliberately
 * does not use wall-clock time, UI callbacks, or an input scheduler.  A caller applies the value
 * returned by {@link #onFrameReady()} only when it is not {@link #UNCHANGED}; this keeps the
 * PlayerInputHub source quiet between the handful of physical button transitions.</p>
 */
final class BenchmarkGameplayScenario {

    enum NativeFrameKind {
        DMG,
        GBC,
        SGB
    }

    static final int UNCHANGED = -1;
    static final int NONE_MASK = 0;
    static final int RIGHT_MASK = 1 << 0;
    static final int A_MASK = 1 << 4;
    static final int B_MASK = 1 << 5;
    static final int START_MASK = 1 << 7;

    private enum Phase {
        DISABLED,
        RUNNING,
        PAUSE_REQUESTED,
        ENDPOINT_SENT,
        READY
    }

    private final DiagnosticsOptions.BenchmarkScenario scenario;
    private final BenchmarkWorkload.Timeline workloadTimeline;
    private final NativeFrameKind nativeFrameKind;
    private final int releaseBeforeActionFrames;
    private final int firstActionMask;
    private final int firstActionFrames;
    private final int releaseBetweenActionsFrames;
    private final int secondActionMask;
    private final int secondActionFrames;
    private final int releaseAfterActionFrames;
    private final int endpointFrame;
    private Phase phase;
    private int frame;
    private int mask;
    private long sessionGeneration;

    BenchmarkGameplayScenario(DiagnosticsOptions.BenchmarkScenario scenario) {
        this(scenario, scenario == DiagnosticsOptions.BenchmarkScenario.CGB_ACTION_V1
                ? NativeFrameKind.GBC : NativeFrameKind.DMG);
    }

    BenchmarkGameplayScenario(DiagnosticsOptions.BenchmarkScenario scenario,
            NativeFrameKind nativeFrameKind) {
        this.scenario = scenario == null ? DiagnosticsOptions.BenchmarkScenario.NONE : scenario;
        this.workloadTimeline = null;
        this.nativeFrameKind = nativeFrameKind == null ? NativeFrameKind.DMG : nativeFrameKind;
        if (this.scenario == DiagnosticsOptions.BenchmarkScenario.DMG_ACTION_V1) {
            releaseBeforeActionFrames = 120;
            firstActionMask = START_MASK;
            firstActionFrames = 3;
            releaseBetweenActionsFrames = 60;
            secondActionMask = RIGHT_MASK;
            secondActionFrames = 120;
            releaseAfterActionFrames = 10;
        } else if (this.scenario == DiagnosticsOptions.BenchmarkScenario.CGB_ACTION_V1) {
            releaseBeforeActionFrames = 670;
            firstActionMask = B_MASK;
            firstActionFrames = 3;
            releaseBetweenActionsFrames = 120;
            secondActionMask = RIGHT_MASK;
            secondActionFrames = 120;
            releaseAfterActionFrames = 10;
        } else {
            releaseBeforeActionFrames = 0;
            firstActionMask = NONE_MASK;
            firstActionFrames = 0;
            releaseBetweenActionsFrames = 0;
            secondActionMask = NONE_MASK;
            secondActionFrames = 0;
            releaseAfterActionFrames = 0;
        }
        endpointFrame = releaseBeforeActionFrames + firstActionFrames
                + releaseBetweenActionsFrames + secondActionFrames + releaseAfterActionFrames;
        phase = enabled() ? Phase.DISABLED : Phase.READY;
    }

    /** Creates a scenario from a workload-owned immutable timeline. */
    BenchmarkGameplayScenario(BenchmarkWorkload.Timeline timeline, NativeFrameKind nativeFrameKind) {
        this.scenario = DiagnosticsOptions.BenchmarkScenario.NONE;
        this.workloadTimeline = timeline;
        this.nativeFrameKind = nativeFrameKind == null ? NativeFrameKind.DMG : nativeFrameKind;
        releaseBeforeActionFrames = 0;
        firstActionMask = NONE_MASK;
        firstActionFrames = 0;
        releaseBetweenActionsFrames = 0;
        secondActionMask = NONE_MASK;
        secondActionFrames = 0;
        releaseAfterActionFrames = 0;
        endpointFrame = timeline != null && timeline.complete() ? timeline.endpointFrame() : 0;
        phase = enabled() ? Phase.DISABLED : Phase.READY;
    }

    boolean enabled() {
        return workloadTimeline != null ? workloadTimeline.complete()
                : scenario != DiagnosticsOptions.BenchmarkScenario.NONE;
    }

    /** Begins one generation-bound timeline after the controller materializes a paused session. */
    synchronized void beginSession(long generation) {
        if (!enabled()) {
            phase = Phase.READY;
            return;
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("Session generation must be positive");
        }
        sessionGeneration = generation;
        frame = 0;
        mask = NONE_MASK;
        phase = Phase.RUNNING;
    }

    /**
     * Advances one physical frame and returns a new mask only when a transition is due.
     * {@link #pauseRequested()} becomes true on the final released frame.
     */
    synchronized int onFrameReady() {
        return onFrameReady(nativeFrameKind, sessionGeneration);
    }

    synchronized int onFrameReady(NativeFrameKind kind, long generation) {
        if (kind != nativeFrameKind || generation <= 0L || generation != sessionGeneration) {
            return UNCHANGED;
        }
        if (phase != Phase.RUNNING) {
            return UNCHANGED;
        }
        frame++;
        int next = maskForFrame(frame);
        boolean changed = next != mask;
        mask = next;
        if (frame >= endpointFrame) {
            phase = Phase.PAUSE_REQUESTED;
        }
        return changed ? next : UNCHANGED;
    }

    synchronized boolean pauseRequested() {
        return phase == Phase.PAUSE_REQUESTED;
    }

    /** Consumes the endpoint edge exactly once; readiness still waits for controller evidence. */
    synchronized boolean consumePauseRequest() {
        if (phase != Phase.PAUSE_REQUESTED) {
            return false;
        }
        phase = Phase.ENDPOINT_SENT;
        return true;
    }

    /** Completes the controller pause handshake before the host may request its anchor. */
    synchronized void markPreconditionReady() {
        if (phase == Phase.ENDPOINT_SENT) {
            phase = Phase.READY;
        }
    }

    synchronized boolean preconditionReady() {
        return phase == Phase.READY;
    }

    synchronized boolean acceptsNativeFrame(NativeFrameKind kind, long generation) {
        return phase == Phase.RUNNING && kind == nativeFrameKind
                && generation > 0L && generation == sessionGeneration;
    }

    synchronized long sessionGeneration() {
        return sessionGeneration;
    }

    NativeFrameKind nativeFrameKind() {
        return nativeFrameKind;
    }

    synchronized void resetSession() {
        frame = 0;
        mask = NONE_MASK;
        sessionGeneration = 0L;
        phase = enabled() ? Phase.DISABLED : Phase.READY;
    }

    synchronized int frameForTesting() {
        return frame;
    }

    synchronized int maskForTesting() {
        return mask;
    }

    synchronized int endpointFrameForTesting() {
        return endpointFrame;
    }

    private int maskForFrame(int frameNumber) {
        if (workloadTimeline != null) {
            return workloadTimeline.maskForFrame(frameNumber);
        }
        int cursor = releaseBeforeActionFrames;
        if (frameNumber < cursor) {
            return NONE_MASK;
        }
        cursor += firstActionFrames;
        if (frameNumber < cursor) {
            return firstActionMask;
        }
        cursor += releaseBetweenActionsFrames;
        if (frameNumber < cursor) {
            return NONE_MASK;
        }
        cursor += secondActionFrames;
        if (frameNumber < cursor) {
            return secondActionMask;
        }
        return NONE_MASK;
    }
}
