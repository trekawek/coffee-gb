package eu.rekawek.coffeegb.core.debug.breakpoint;

import java.util.Objects;

/** Exact value of a monotonic tick or frame counter. */
public record DebugCounterCondition(DebugCounterType counter, long value)
        implements DebugBreakpointCondition {

    public DebugCounterCondition {
        Objects.requireNonNull(counter, "counter");
        DebugBreakpointChecks.nonNegative("value", value);
    }

    public static DebugCounterCondition atMasterTick(long masterTick) {
        return new DebugCounterCondition(DebugCounterType.MASTER_TICK, masterTick);
    }

    public static DebugCounterCondition atFrame(long frame) {
        return new DebugCounterCondition(DebugCounterType.FRAME, frame);
    }

    @Override
    public DebugBreakpointKind kind() {
        return DebugBreakpointKind.COUNTER;
    }
}
