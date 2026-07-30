package eu.rekawek.coffeegb.core.debug.history;

import java.util.Objects;

/**
 * Coherent bounded-history status captured at one emulation-owner safe point.
 *
 * <p>{@code oldest} and {@code newest} bound all retained frame anchors, including an original
 * future kept after reversing. {@code cursor} is the current historical machine position and may
 * therefore lie before {@code newest} or between frame anchors.</p>
 */
public record DebugHistoryStatus(
        DebugHistoryConfiguration configuration,
        int checkpointCount,
        long retainedBytes,
        long evictedCheckpoints,
        DebugHistoryPoint oldest,
        DebugHistoryPoint newest,
        DebugHistoryPosition cursor,
        int futureCheckpointCount,
        DebugHistoryTruncationReason lastTruncationReason) {

    public DebugHistoryStatus {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(lastTruncationReason, "lastTruncationReason");
        if (checkpointCount < 0) {
            throw new IllegalArgumentException("Debug checkpoint count cannot be negative");
        }
        if (retainedBytes < 0) {
            throw new IllegalArgumentException("Debug retained bytes cannot be negative");
        }
        if (evictedCheckpoints < 0) {
            throw new IllegalArgumentException("Debug evicted-checkpoint count cannot be negative");
        }
        if (futureCheckpointCount < 0 || futureCheckpointCount > checkpointCount) {
            throw new IllegalArgumentException(
                    "Debug future-checkpoint count must fit the retained checkpoint count");
        }
        if (checkpointCount == 0) {
            if (oldest != null || newest != null || retainedBytes != 0
                    || futureCheckpointCount != 0) {
                throw new IllegalArgumentException(
                        "Empty debug history cannot expose checkpoints or retained bytes");
            }
        } else {
            Objects.requireNonNull(oldest, "oldest");
            Objects.requireNonNull(newest, "newest");
            Objects.requireNonNull(cursor, "cursor");
            if (!configuration.enabled()) {
                throw new IllegalArgumentException(
                        "Disabled debug history cannot retain checkpoints");
            }
            if (checkpointCount == 1 && !oldest.equals(newest)) {
                throw new IllegalArgumentException(
                        "A single debug checkpoint must be both oldest and newest");
            }
            if (checkpointCount > 1 && oldest.checkpointId() >= newest.checkpointId()) {
                throw new IllegalArgumentException(
                        "Multiple debug checkpoints require increasing identifiers");
            }
            if (oldest.masterTick() > newest.masterTick()
                    || oldest.frame() > newest.frame()) {
                throw new IllegalArgumentException(
                        "Debug history points must be monotonic");
            }
            if (cursor.masterTick() < oldest.masterTick()
                    || cursor.frame() < oldest.frame()) {
                throw new IllegalArgumentException(
                        "Debug history cursor cannot precede the oldest retained checkpoint");
            }
            boolean cursorDoesNotFollowNewest =
                    cursor.masterTick() <= newest.masterTick()
                            && cursor.frame() <= newest.frame();
            boolean cursorAtOrAfterNewest = cursor.masterTick() >= newest.masterTick()
                    && cursor.frame() >= newest.frame();
            if ((futureCheckpointCount > 0 && !cursorDoesNotFollowNewest)
                    || (futureCheckpointCount == 0 && !cursorAtOrAfterNewest)) {
                throw new IllegalArgumentException(
                        "Debug future checkpoints and cursor position must agree");
            }
            if (futureCheckpointCount == checkpointCount) {
                throw new IllegalArgumentException(
                        "At least one retained checkpoint must not follow the cursor");
            }
        }
        if (!configuration.enabled()
                && (checkpointCount != 0 || retainedBytes != 0 || oldest != null
                || newest != null || cursor != null || futureCheckpointCount != 0)) {
            throw new IllegalArgumentException(
                    "Disabled debug history must have an empty status");
        }
        if (configuration.enabled()
                && (checkpointCount > configuration.maxFrames()
                || retainedBytes > configuration.memoryBudgetBytes())) {
            throw new IllegalArgumentException(
                    "Debug history status exceeds its configured bounds");
        }
    }

    /** Compatibility constructor for the original destructive reverse-history model. */
    public DebugHistoryStatus(
            DebugHistoryConfiguration configuration,
            int checkpointCount,
            long retainedBytes,
            long evictedCheckpoints,
            DebugHistoryPoint oldest,
            DebugHistoryPoint newest,
            DebugHistoryTruncationReason lastTruncationReason) {
        this(configuration, checkpointCount, retainedBytes, evictedCheckpoints, oldest, newest,
                newest == null ? null : DebugHistoryPosition.atCheckpoint(newest), 0,
                lastTruncationReason);
    }
}
