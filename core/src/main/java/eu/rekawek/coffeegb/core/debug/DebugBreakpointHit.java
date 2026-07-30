package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;

import java.util.Objects;

/** Breakpoint identity and match tick paired with the coherent safe-point snapshot that stopped. */
public record DebugBreakpointHit(
        DebugBreakpointId breakpointId,
        long matchMasterTick,
        DebugSnapshot snapshot) {

    public DebugBreakpointHit {
        Objects.requireNonNull(breakpointId, "breakpointId");
        if (matchMasterTick < 0) {
            throw new IllegalArgumentException(
                    "Breakpoint match tick must not be negative: " + matchMasterTick);
        }
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.paused()) {
            throw new IllegalArgumentException(
                    "A breakpoint hit must contain the paused stopping snapshot");
        }
        if (snapshot.masterTick() < matchMasterTick) {
            throw new IllegalArgumentException(
                    "Breakpoint snapshot cannot precede its match tick");
        }
    }
}
