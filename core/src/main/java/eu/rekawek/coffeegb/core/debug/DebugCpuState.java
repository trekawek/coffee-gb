package eu.rekawek.coffeegb.core.debug;

/** Coarse CPU pipeline state without exposing the mutable core CPU implementation. */
public enum DebugCpuState {
    OPCODE_FETCH,
    EXTENDED_OPCODE_FETCH,
    OPERAND_FETCH,
    EXECUTING,
    INTERRUPT_WAIT,
    INTERRUPT_PUSH,
    INTERRUPT_JUMP,
    STOPPED,
    HALTED,
    SPEED_SWITCH,
    LOCKED
}
