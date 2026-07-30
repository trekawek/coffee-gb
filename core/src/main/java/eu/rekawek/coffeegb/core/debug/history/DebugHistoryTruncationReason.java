package eu.rekawek.coffeegb.core.debug.history;

/** Stable reason why retained reverse history most recently lost checkpoints. */
public enum DebugHistoryTruncationReason {
    NONE,
    FRAME_BUDGET,
    MEMORY_BUDGET,
    CONFIGURATION_CHANGED,
    SESSION_BOUNDARY,
    NONDETERMINISTIC_IO,
    REVERSE_STEP,
    USER_REWIND,
    TOPOLOGY_CHANGED,
    BRANCH_INVALIDATED
}
