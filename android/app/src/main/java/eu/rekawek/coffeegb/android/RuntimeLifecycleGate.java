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

        void requestBatteryFlush();
    }

    private boolean active;
    private boolean background;
    private boolean hostPauseRequested;
    private boolean flushRequested;

    void activated(SessionCommands commands) {
        active = true;
        hostPauseRequested = false;
        flushRequested = false;
        if (background) {
            background(commands);
        }
    }

    void background(SessionCommands commands) {
        background = true;
        if (!active) {
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
    }

    boolean active() {
        return active;
    }
}
