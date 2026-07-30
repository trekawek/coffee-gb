package eu.rekawek.coffeegb.core.debug.breakpoint;

import eu.rekawek.coffeegb.core.debug.DebugInterruptType;

import java.util.Objects;

/** Exact interrupt-acceptance predicate. */
public record DebugInterruptCondition(DebugInterruptType interrupt)
        implements DebugBreakpointCondition {

    public DebugInterruptCondition {
        Objects.requireNonNull(interrupt, "interrupt");
    }

    @Override
    public DebugBreakpointKind kind() {
        return DebugBreakpointKind.INTERRUPT;
    }
}
