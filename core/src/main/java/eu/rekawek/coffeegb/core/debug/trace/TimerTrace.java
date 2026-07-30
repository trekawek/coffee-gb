package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Objects;

/** Timer transition with the timer registers sampled at that transition. */
public record TimerTrace(Kind kind, int divider, int counter, int modulo, int control)
        implements TraceEvent {

    public enum Kind {
        DIVIDER_RESET,
        COUNTER_INCREMENTED,
        COUNTER_OVERFLOWED,
        COUNTER_RELOADED,
        CONTROL_CHANGED
    }

    public TimerTrace {
        Objects.requireNonNull(kind, "kind");
        TraceChecks.range("divider", divider, 0, 0xffff);
        TraceChecks.range("counter", counter, 0, 0xff);
        TraceChecks.range("modulo", modulo, 0, 0xff);
        TraceChecks.range("control", control, 0, 0x07);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.TIMER;
    }
}
