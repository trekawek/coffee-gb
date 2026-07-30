package eu.rekawek.coffeegb.core.debug.history;

import eu.rekawek.coffeegb.core.debug.DebugSnapshot;
import eu.rekawek.coffeegb.core.debug.DebugStepKind;

import java.util.Objects;

/** Restored historical position, replay anchor, coherent machine view, and retained history. */
public record DebugReverseStepResult(
        DebugStepKind kind,
        DebugHistoryPosition restoredPosition,
        DebugHistoryPoint replayAnchor,
        DebugSnapshot snapshot,
        DebugHistoryStatus history) {

    public DebugReverseStepResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(restoredPosition, "restoredPosition");
        Objects.requireNonNull(replayAnchor, "replayAnchor");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(history, "history");
        if (kind == DebugStepKind.MACHINE_CYCLE) {
            throw new IllegalArgumentException("Reverse machine-cycle stepping is unsupported");
        }
        if (!snapshot.paused()) {
            throw new IllegalArgumentException("A completed reverse step must leave the session paused");
        }
        if (!restoredPosition.equals(history.cursor())) {
            throw new IllegalArgumentException(
                    "A completed reverse step must restore the history cursor");
        }
        if (history.checkpointCount() == 0
                || replayAnchor.checkpointId() < history.oldest().checkpointId()
                || replayAnchor.checkpointId() > history.newest().checkpointId()) {
            throw new IllegalArgumentException(
                    "A completed reverse step requires a retained replay anchor");
        }
        if (replayAnchor.masterTick() > restoredPosition.masterTick()
                || replayAnchor.frame() > restoredPosition.frame()) {
            throw new IllegalArgumentException(
                    "A replay anchor cannot follow the restored position");
        }
        if (kind == DebugStepKind.FRAME
                && (restoredPosition.framePosition() != 0
                || replayAnchor.masterTick() != restoredPosition.masterTick()
                || replayAnchor.frame() != restoredPosition.frame())) {
            throw new IllegalArgumentException(
                    "A reverse-frame result must restore its frame-boundary anchor");
        }
    }

    /** Compatibility constructor for direct frame-checkpoint restoration. */
    public DebugReverseStepResult(
            DebugStepKind kind,
            DebugHistoryPoint restoredPoint,
            DebugSnapshot snapshot,
            DebugHistoryStatus history) {
        this(kind, DebugHistoryPosition.atCheckpoint(restoredPoint), restoredPoint,
                snapshot, history);
    }

    /** Compatibility accessor for clients that only support direct checkpoint restoration. */
    public DebugHistoryPoint restoredPoint() {
        return replayAnchor;
    }
}
