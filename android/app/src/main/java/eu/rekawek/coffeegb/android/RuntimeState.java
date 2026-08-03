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
        long generation) {

    public RuntimeState {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(message, "message");
        selections = List.copyOf(selections);
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
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
