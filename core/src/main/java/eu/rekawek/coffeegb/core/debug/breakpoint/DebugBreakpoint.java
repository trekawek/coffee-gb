package eu.rekawek.coffeegb.core.debug.breakpoint;

import java.util.Objects;

/** Immutable breakpoint definition. */
public record DebugBreakpoint(
        DebugBreakpointId id,
        boolean enabled,
        DebugBreakpointCondition condition) {

    public DebugBreakpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(condition, "condition");
    }

    /** Returns this value when its state already matches, otherwise a copy with the same id. */
    public DebugBreakpoint withEnabled(boolean enabled) {
        return this.enabled == enabled ? this : new DebugBreakpoint(id, enabled, condition);
    }

    public DebugBreakpoint enable() {
        return withEnabled(true);
    }

    public DebugBreakpoint disable() {
        return withEnabled(false);
    }
}
