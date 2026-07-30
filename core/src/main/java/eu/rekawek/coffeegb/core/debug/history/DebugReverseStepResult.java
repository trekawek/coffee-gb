package eu.rekawek.coffeegb.core.debug.history;

import eu.rekawek.coffeegb.core.debug.DebugSnapshot;
import eu.rekawek.coffeegb.core.debug.DebugStepKind;

import java.util.Objects;

/** Immutable restored point, coherent machine view, and remaining history after a reverse step. */
public record DebugReverseStepResult(
        DebugStepKind kind,
        DebugHistoryPoint restoredPoint,
        DebugSnapshot snapshot,
        DebugHistoryStatus history) {

    public DebugReverseStepResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(restoredPoint, "restoredPoint");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(history, "history");
        if (kind == DebugStepKind.MACHINE_CYCLE) {
            throw new IllegalArgumentException("Reverse machine-cycle stepping is unsupported");
        }
        if (!snapshot.paused()) {
            throw new IllegalArgumentException("A completed reverse step must leave the session paused");
        }
        if (history.checkpointCount() == 0 || !restoredPoint.equals(history.newest())) {
            throw new IllegalArgumentException(
                    "A completed reverse step must restore the newest retained checkpoint");
        }
    }
}
