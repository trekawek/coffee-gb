package eu.rekawek.coffeegb.android;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, redacted presentation state owned by {@link AndroidEmulationRuntime}.
 *
 * <p>The Android UI observes snapshots only. It never receives a live controller, a document URI,
 * a filesystem path, or a mutable emulator object.
 */
public record RuntimeState(
        Phase phase,
        String message,
        List<Selection> selections,
        boolean transferReady,
        boolean paused,
        boolean flushPending,
        String romTitle,
        boolean batterySaveActive,
        long sessionGeneration,
        long playTimeNanos,
        boolean tiltOrientationLocked,
        long generation) {

    public RuntimeState {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(romTitle, "romTitle");
        selections = List.copyOf(selections);
        if (sessionGeneration < 0) {
            throw new IllegalArgumentException("sessionGeneration must not be negative");
        }
        if (playTimeNanos < 0) {
            throw new IllegalArgumentException("playTimeNanos must not be negative");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }

    /** Compatibility constructor for callers predating session-scoped tilt presentation. */
    public RuntimeState(Phase phase, String message, List<Selection> selections,
            boolean transferReady, boolean paused, boolean flushPending, String romTitle,
            boolean batterySaveActive, long sessionGeneration, long playTimeNanos,
            long generation) {
        this(phase, message, selections, transferReady, paused, flushPending,
                romTitle, batterySaveActive, sessionGeneration, playTimeNanos, false, generation);
    }

    /** Compatibility constructor for callers that do not expose an active session. */
    public RuntimeState(Phase phase, String message, List<Selection> selections,
            boolean transferReady, boolean paused, boolean flushPending, long generation) {
        this(phase, message, selections, transferReady, paused, flushPending,
                "", false, 0L, 0L, generation);
    }

    /** Compatibility constructor for hosts that expose cartridge metadata but not play time. */
    public RuntimeState(Phase phase, String message, List<Selection> selections,
            boolean transferReady, boolean paused, boolean flushPending, String romTitle,
            boolean batterySaveActive, long sessionGeneration, long generation) {
        this(phase, message, selections, transferReady, paused, flushPending,
                romTitle, batterySaveActive, sessionGeneration, 0L, generation);
    }

    public static RuntimeState stopped() {
        return new RuntimeState(
                Phase.STOPPED,
                "Coffee GB Android is ready. Choose a ROM or ZIP document.",
                List.of(),
                false,
                false,
                false,
                0);
    }

    public enum Phase {
        STOPPED,
        OPENING,
        AWAITING_ARCHIVE_SELECTION,
        AWAITING_RECENT_SELECTION,
        LOADING,
        RUNNING,
        PAUSED,
        FAILED
    }

    /** A user-visible, opaque selection token. It intentionally contains no provider identity. */
    public record Selection(long token, String label) {
        public Selection {
            Objects.requireNonNull(label, "label");
        }
    }
}
