package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;

import java.util.Objects;
import java.util.Optional;

/**
 * Breakpoint definition and match tick paired with the coherent safe-point snapshot that stopped.
 *
 * <p>The captured definition describes the breakpoint at the instant it matched. Later edits or
 * reuse of the same id therefore cannot change the explanation of a historical stop. The active
 * pause flag is separate from the captured paused snapshot: movement makes the hit historical,
 * while the original stopping snapshot remains immutable.
 */
public record DebugBreakpointHit(
        DebugBreakpointId breakpointId,
        long matchMasterTick,
        DebugSnapshot snapshot,
        Optional<DebugBreakpoint> breakpoint,
        boolean activePause) {

    public DebugBreakpointHit {
        Objects.requireNonNull(breakpointId, "breakpointId");
        if (matchMasterTick < 0) {
            throw new IllegalArgumentException(
                    "Breakpoint match tick must not be negative: " + matchMasterTick);
        }
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(breakpoint, "breakpoint");
        if (!snapshot.paused()) {
            throw new IllegalArgumentException(
                    "A breakpoint hit must contain the paused stopping snapshot");
        }
        if (snapshot.masterTick() < matchMasterTick) {
            throw new IllegalArgumentException(
                    "Breakpoint snapshot cannot precede its match tick");
        }
        breakpoint.ifPresent(definition -> {
            if (!breakpointId.equals(definition.id())) {
                throw new IllegalArgumentException(
                        "Breakpoint definition id must match the hit id");
            }
        });
    }

    /** Creates a fully described breakpoint hit. */
    public DebugBreakpointHit(
            DebugBreakpoint breakpoint,
            long matchMasterTick,
            DebugSnapshot snapshot,
            boolean activePause) {
        this(
                Objects.requireNonNull(breakpoint, "breakpoint").id(),
                matchMasterTick,
                snapshot,
                Optional.of(breakpoint),
                activePause);
    }

    /**
     * Compatibility constructor for callers that captured only the legacy breakpoint identity.
     *
     * <p>Owner implementations should use the definition-carrying constructor instead.
     */
    public DebugBreakpointHit(
            DebugBreakpointId breakpointId,
            long matchMasterTick,
            DebugSnapshot snapshot) {
        this(breakpointId, matchMasterTick, snapshot, Optional.empty(), true);
    }

    /** Returns this hit when its ownership already matches, otherwise a historical copy. */
    public DebugBreakpointHit withActivePause(boolean activePause) {
        return this.activePause == activePause
                ? this
                : new DebugBreakpointHit(
                        breakpointId, matchMasterTick, snapshot, breakpoint, activePause);
    }
}
