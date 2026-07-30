package eu.rekawek.coffeegb.core.debug;

/** Exact safe-point condition that ended a bounded step request. */
public enum DebugStepStopReason {
    INSTRUCTION_RETIRED,
    MACHINE_CYCLE_COMPLETED,
    FRAME_BOUNDARY,
    BREAKPOINT,
    CPU_IDLE,
    CPU_LOCKED,
    STEP_LIMIT
}
