package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;

import java.util.List;
import java.util.Objects;

/** Immutable owner-thread snapshot of the breakpoints installed in one debug session. */
public record DebugBreakpointList(List<DebugBreakpoint> breakpoints) {

    public DebugBreakpointList {
        Objects.requireNonNull(breakpoints, "breakpoints");
        breakpoints = List.copyOf(breakpoints);
        if (breakpoints.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("breakpoints contains null");
        }
    }
}
