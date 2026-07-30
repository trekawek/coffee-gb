package eu.rekawek.coffeegb.core.debug.breakpoint;

/**
 * Stable, caller-assigned identity of a breakpoint within a debug session.
 *
 * <p>The matching engine never derives identity from a condition. Editing or toggling a
 * breakpoint can therefore retain the same id.
 */
public record DebugBreakpointId(long value) {

    public DebugBreakpointId {
        DebugBreakpointChecks.nonNegative("value", value);
    }
}
