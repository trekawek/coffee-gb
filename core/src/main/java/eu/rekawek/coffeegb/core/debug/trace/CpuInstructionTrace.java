package eu.rekawek.coffeegb.core.debug.trace;

/** A CPU instruction at its architectural retirement boundary. */
public record CpuInstructionTrace(int programCounter, int opcode, int prefixedOpcode)
        implements TraceEvent {

    /** Use {@code -1} for {@code prefixedOpcode} when the instruction has no CB prefix. */
    public CpuInstructionTrace {
        TraceChecks.range("programCounter", programCounter, 0, 0xffff);
        TraceChecks.range("opcode", opcode, 0, 0xff);
        TraceChecks.range("prefixedOpcode", prefixedOpcode, -1, 0xff);
        if ((opcode == 0xcb) != (prefixedOpcode >= 0)) {
            throw new IllegalArgumentException(
                    "prefixedOpcode must be present exactly when opcode is 0xcb");
        }
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.CPU;
    }
}
