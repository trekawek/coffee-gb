package eu.rekawek.coffeegb.core.debug;

/** Stable, machine-readable failures returned by the asynchronous debug API. */
public enum DebugErrorCode {
    NO_ACTIVE_SESSION,
    SESSION_REPLACED,
    PORT_CLOSED,
    QUEUE_FULL,
    INVALID_ARGUMENT,
    NOT_PAUSED,
    ALREADY_PAUSED,
    ALREADY_RUNNING,
    CPU_IDLE,
    CPU_LOCKED,
    UNSUPPORTED_STEP,
    UNSUPPORTED_ADDRESS_SPACE,
    SIDE_EFFECTFUL_ADDRESS,
    UNSUPPORTED_TOPOLOGY,
    SESSION_BUSY,
    STEP_LIMIT,
    INTERNAL_ERROR
}
