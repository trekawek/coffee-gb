package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Objects;

/** Mapper-bank or real-time-clock transition. */
public record MapperRtcTrace(Kind kind, int register, long value) implements TraceEvent {

    /** {@code register == -1} means the transition is not tied to a memory-mapped register. */
    public enum Kind {
        ROM_BANK_CHANGED,
        RAM_BANK_CHANGED,
        RAM_ENABLE_CHANGED,
        RTC_LATCHED,
        RTC_REGISTER_READ,
        RTC_REGISTER_WRITTEN
    }

    public MapperRtcTrace {
        Objects.requireNonNull(kind, "kind");
        TraceChecks.range("register", register, -1, 0xff);
        TraceChecks.nonNegative("value", value);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.MAPPER_RTC;
    }
}
