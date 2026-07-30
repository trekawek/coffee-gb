package eu.rekawek.coffeegb.core.debug.history;

import java.util.Objects;

/** Coherent bounded-history status captured at one emulation-owner safe point. */
public record DebugHistoryStatus(
        DebugHistoryConfiguration configuration,
        int checkpointCount,
        long retainedBytes,
        long evictedCheckpoints,
        DebugHistoryPoint oldest,
        DebugHistoryPoint newest,
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
        if (checkpointCount == 0) {
            if (oldest != null || newest != null || retainedBytes != 0) {
                throw new IllegalArgumentException(
                        "Empty debug history cannot expose points or retained bytes");
            }
        } else {
            Objects.requireNonNull(oldest, "oldest");
            Objects.requireNonNull(newest, "newest");
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
        }
        if (!configuration.enabled()
                && (checkpointCount != 0 || retainedBytes != 0 || oldest != null
                || newest != null)) {
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
}
