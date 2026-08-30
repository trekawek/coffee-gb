package eu.rekawek.coffeegb.android;

/**
 * Serial lifecycle policy used by {@link AndroidEmulationRuntime}.
 *
 * <p>The runtime invokes this only from its single owner executor. Keeping the policy free of
 * Android and emulator types lets focused JVM tests model rapid Activity/audio/service races with
 * a fake session and prove that one live session receives at most one background flush at a time.
 */
final class RuntimeLifecycleGate {

    interface SessionCommands {
        void pause();

        void resumeOutputs();

        void requestBatteryFlush();
    }

    private boolean active;
    private boolean background;
    private boolean hostPauseRequested;
    private boolean flushRequested;
    /** Host outputs were paused before there was a session for the controller to pause. */
    private boolean resumeOutputsOnActivation;

    void activated(SessionCommands commands) {
        active = true;
        hostPauseRequested = false;
        flushRequested = false;
        if (background) {
            resumeOutputsOnActivation = false;
            background(commands);
        } else if (resumeOutputsOnActivation) {
            // A document picker can pause host outputs before the first session exists. Starting
            // that session in the foreground must reactivate those outputs without manufacturing
            // a controller resume edge for a machine that is already running.
            resumeOutputsOnActivation = false;
            commands.resumeOutputs();
        }
    }

    void background(SessionCommands commands) {
        background = true;
        if (!active) {
            resumeOutputsOnActivation = true;
            return;
        }
        if (!hostPauseRequested) {
            hostPauseRequested = true;
            commands.pause();
        }
        if (!flushRequested) {
            flushRequested = true;
            commands.requestBatteryFlush();
        }
    }

    void resumedByUser() {
        background = false;
        resumeOutputsOnActivation = false;
        if (active) {
            hostPauseRequested = false;
        }
    }

    /** A bound Activity is visible again; it still must call {@link #resumedByUser()} to play. */
    void foregrounded() {
        background = false;
    }

    void flushCompleted() {
        flushRequested = false;
    }

    void released() {
        active = false;
        background = false;
        hostPauseRequested = false;
        flushRequested = false;
        resumeOutputsOnActivation = false;
    }

    boolean active() {
        return active;
    }
}
