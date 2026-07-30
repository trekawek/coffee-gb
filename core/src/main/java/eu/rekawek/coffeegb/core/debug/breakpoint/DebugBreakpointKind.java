package eu.rekawek.coffeegb.core.debug.breakpoint;

/** Stable condition categories exposed to debug clients. */
public enum DebugBreakpointKind {
    PROGRAM_COUNTER,
    MEMORY,
    OPCODE,
    INTERRUPT,
    PPU_STATE,
    COUNTER
}
