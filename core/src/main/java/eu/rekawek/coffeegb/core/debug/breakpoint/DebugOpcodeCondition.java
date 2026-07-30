package eu.rekawek.coffeegb.core.debug.breakpoint;

/** Exact base-table or {@code CB}-prefixed opcode byte. */
public record DebugOpcodeCondition(boolean cbPrefixed, int opcode)
        implements DebugBreakpointCondition {

    public DebugOpcodeCondition {
        DebugBreakpointChecks.unsignedByte("opcode", opcode);
    }

    public static DebugOpcodeCondition base(int opcode) {
        return new DebugOpcodeCondition(false, opcode);
    }

    public static DebugOpcodeCondition cb(int opcode) {
        return new DebugOpcodeCondition(true, opcode);
    }

    @Override
    public DebugBreakpointKind kind() {
        return DebugBreakpointKind.OPCODE;
    }
}
