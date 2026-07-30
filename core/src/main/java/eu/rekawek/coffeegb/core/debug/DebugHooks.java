package eu.rekawek.coffeegb.core.debug;

/**
 * Optional primitive-only observations installed while a debug session needs core hooks.
 *
 * <p>Implementations run on the emulation owner. Call sites must retain a null/absent fast path
 * so ordinary execution constructs no debug values.
 */
public interface DebugHooks {

    /** Whether the CPU should install its active bus wrapper for read/write/fetch observations. */
    default boolean requiresMemoryAccessHooks() {
        return true;
    }

    void onInstructionFetch(int programCounter);

    void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode);

    void onInstructionRetired(
            boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode);

    void onMemoryAccess(DebugMemoryAccess access, int address, int value);

    void onInterruptRequested(DebugInterruptType interrupt);

    void onInterruptAccepted(DebugInterruptType interrupt);
}
