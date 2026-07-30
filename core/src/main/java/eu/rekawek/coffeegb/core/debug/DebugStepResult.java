package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** Immutable accounting and coherent final snapshot for a bounded step operation. */
public record DebugStepResult(
        DebugStepKind kind,
        DebugStepStopReason stopReason,
        long ticksExecuted,
        long instructionsRetired,
        DebugSnapshot snapshot) {

    public DebugStepResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(snapshot, "snapshot");
        DebugValueChecks.nonNegative("ticksExecuted", ticksExecuted);
        DebugValueChecks.nonNegative("instructionsRetired", instructionsRetired);
        if (!snapshot.paused()) {
            throw new IllegalArgumentException("A completed step must leave the session paused");
        }
        if (stopReason == DebugStepStopReason.INSTRUCTION_RETIRED
                && kind != DebugStepKind.INSTRUCTION) {
            throw new IllegalArgumentException("Instruction retirement requires instruction step");
        }
        if (stopReason == DebugStepStopReason.MACHINE_CYCLE_COMPLETED
                && kind != DebugStepKind.MACHINE_CYCLE) {
            throw new IllegalArgumentException("Machine-cycle stop requires machine-cycle step");
        }
        if (stopReason == DebugStepStopReason.FRAME_BOUNDARY && kind != DebugStepKind.FRAME) {
            throw new IllegalArgumentException("Frame boundary requires frame step");
        }
    }
}
