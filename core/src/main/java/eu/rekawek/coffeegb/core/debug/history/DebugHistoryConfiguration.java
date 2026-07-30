package eu.rekawek.coffeegb.core.debug.history;

/** Immutable retained-history limits selected by one debugger client. */
public record DebugHistoryConfiguration(
        boolean enabled,
        int maxFrames,
        long memoryBudgetBytes) {

    public static final int MIN_FRAMES = 1;

    public static final int DEFAULT_MAX_FRAMES = 1_800;

    public static final int MAX_FRAMES = 7_200;

    public static final long MIN_MEMORY_BUDGET_BYTES = 8L * 1024L * 1024L;

    public static final long DEFAULT_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L;

    public static final long MAX_MEMORY_BUDGET_BYTES = 512L * 1024L * 1024L;

    public DebugHistoryConfiguration {
        if (!enabled) {
            if (maxFrames != 0 || memoryBudgetBytes != 0) {
                throw new IllegalArgumentException(
                        "Disabled debug history must have zero frame and memory budgets");
            }
        } else {
            if (maxFrames < MIN_FRAMES || maxFrames > MAX_FRAMES) {
                throw new IllegalArgumentException(
                        "Debug history frame budget must be between "
                                + MIN_FRAMES + " and " + MAX_FRAMES);
            }
            if (memoryBudgetBytes < MIN_MEMORY_BUDGET_BYTES
                    || memoryBudgetBytes > MAX_MEMORY_BUDGET_BYTES) {
                throw new IllegalArgumentException(
                        "Debug history memory budget must be between "
                                + MIN_MEMORY_BUDGET_BYTES + " and "
                                + MAX_MEMORY_BUDGET_BYTES + " bytes");
            }
        }
    }

    public static DebugHistoryConfiguration disabled() {
        return new DebugHistoryConfiguration(false, 0, 0);
    }

    public static DebugHistoryConfiguration defaults() {
        return new DebugHistoryConfiguration(
                true, DEFAULT_MAX_FRAMES, DEFAULT_MEMORY_BUDGET_BYTES);
    }
}
