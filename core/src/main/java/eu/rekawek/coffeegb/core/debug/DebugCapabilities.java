package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** Immutable feature negotiation for one session generation. */
public record DebugCapabilities(
        boolean pauseResume,
        boolean snapshot,
        boolean instructionStep,
        boolean machineCycleStep,
        boolean frameStep,
        boolean memoryRead,
        boolean buttonInput,
        int maxMemoryReadLength) {

    public DebugCapabilities {
        if (maxMemoryReadLength < 0 || maxMemoryReadLength > DebugMemoryRequest.MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid maximum memory-read length: "
                    + maxMemoryReadLength);
        }
        if (memoryRead != (maxMemoryReadLength > 0)) {
            throw new IllegalArgumentException(
                    "Memory-read capability and maximum length must agree");
        }
    }

    public boolean supports(DebugStepKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case INSTRUCTION -> instructionStep;
            case MACHINE_CYCLE -> machineCycleStep;
            case FRAME -> frameStep;
        };
    }
}
