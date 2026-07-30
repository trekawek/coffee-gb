package eu.rekawek.coffeegb.core.debug.history;

/** Immutable reverse-history feature and limit negotiation for one session generation. */
public record DebugHistoryCapabilities(
        boolean checkpointHistory,
        boolean reverseFrame,
        boolean reverseInstruction,
        int maxFrames,
        long maxMemoryBudgetBytes) {

    public DebugHistoryCapabilities {
        if (!checkpointHistory) {
            if (reverseFrame || reverseInstruction || maxFrames != 0
                    || maxMemoryBudgetBytes != 0) {
                throw new IllegalArgumentException(
                        "Disabled checkpoint history cannot expose reverse operations or limits");
            }
        } else {
            if (maxFrames < DebugHistoryConfiguration.MIN_FRAMES
                    || maxFrames > DebugHistoryConfiguration.MAX_FRAMES) {
                throw new IllegalArgumentException(
                        "Debug history capability frame limit is outside the public range");
            }
            if (maxMemoryBudgetBytes < DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES
                    || maxMemoryBudgetBytes
                    > DebugHistoryConfiguration.MAX_MEMORY_BUDGET_BYTES) {
                throw new IllegalArgumentException(
                        "Debug history capability memory limit is outside the public range");
            }
        }
    }

    public static DebugHistoryCapabilities disabled() {
        return new DebugHistoryCapabilities(false, false, false, 0, 0);
    }
}
