package eu.rekawek.coffeegb.core.debug.history;

/** Opaque checkpoint identity and its position on one session's emulated timeline. */
public record DebugHistoryPoint(
        long checkpointId,
        long masterTick,
        long frame) {

    public DebugHistoryPoint {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("Debug checkpoint id must be positive");
        }
        if (masterTick < 0) {
            throw new IllegalArgumentException("Debug checkpoint master tick cannot be negative");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("Debug checkpoint frame cannot be negative");
        }
    }
}
