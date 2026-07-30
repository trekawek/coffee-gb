package eu.rekawek.coffeegb.core.debug.history;

import java.util.Objects;

/** Historical machine position on the active reverse-debug timeline. */
public record DebugHistoryPosition(
        long masterTick,
        long frame,
        int framePosition) {

    public DebugHistoryPosition {
        if (masterTick < 0) {
            throw new IllegalArgumentException("Debug history master tick cannot be negative");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("Debug history frame cannot be negative");
        }
        if (framePosition < 0) {
            throw new IllegalArgumentException("Debug history frame position cannot be negative");
        }
    }

    /** Returns the frame-boundary position represented by a retained checkpoint. */
    public static DebugHistoryPosition atCheckpoint(DebugHistoryPoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return new DebugHistoryPosition(checkpoint.masterTick(), checkpoint.frame(), 0);
    }
}
