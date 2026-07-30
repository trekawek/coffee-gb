package eu.rekawek.coffeegb.core.debug;

/** Granularity requested by {@link DebugPort#step(DebugStepKind)}. */
public enum DebugStepKind {
    INSTRUCTION,
    MACHINE_CYCLE,
    FRAME
}
