package eu.rekawek.coffeegb.core.debug.breakpoint;

/**
 * Immutable, data-only predicate understood by {@link DebugBreakpointMatcher}.
 *
 * <p>Conditions intentionally contain no expression language or executable callbacks.
 */
public interface DebugBreakpointCondition {

    DebugBreakpointKind kind();
}
