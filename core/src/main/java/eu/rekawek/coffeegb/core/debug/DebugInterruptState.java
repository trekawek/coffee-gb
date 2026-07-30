package eu.rekawek.coffeegb.core.debug;

/** Detached interrupt controller state. */
public record DebugInterruptState(
        boolean ime,
        boolean imeEnablePending,
        int requestFlags,
        int enableFlags,
        int pendingFlags) {

    public DebugInterruptState {
        DebugValueChecks.unsignedByte("requestFlags", requestFlags);
        DebugValueChecks.unsignedByte("enableFlags", enableFlags);
        DebugValueChecks.unsignedByte("pendingFlags", pendingFlags);
        if ((pendingFlags & ~(requestFlags & enableFlags)) != 0) {
            throw new IllegalArgumentException("Pending interrupts must be enabled requests");
        }
    }
}
