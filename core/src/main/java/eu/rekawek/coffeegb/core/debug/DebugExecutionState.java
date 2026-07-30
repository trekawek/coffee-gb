package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** Detached CPU pipeline and execution-speed state. */
public record DebugExecutionState(
        DebugCpuState cpuState,
        int opcode,
        int extendedOpcode,
        int machineCycle,
        boolean doubleSpeed,
        boolean haltBug,
        long retiredInstructions) {

    public DebugExecutionState {
        Objects.requireNonNull(cpuState, "cpuState");
        DebugValueChecks.range("opcode", opcode, -1, 0xff);
        DebugValueChecks.range("extendedOpcode", extendedOpcode, -1, 0xff);
        DebugValueChecks.nonNegative("machineCycle", machineCycle);
        DebugValueChecks.nonNegative("retiredInstructions", retiredInstructions);
    }
}
