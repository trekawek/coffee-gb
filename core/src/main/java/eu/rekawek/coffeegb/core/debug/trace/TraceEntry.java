package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Objects;

/** One sequenced trace payload captured at a completed master tick. */
public record TraceEntry(long sequence, long masterTick, TraceSource source, TraceEvent event) {

    public TraceEntry {
        TraceChecks.nonNegative("sequence", sequence);
        TraceChecks.nonNegative("masterTick", masterTick);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(event, "event");
        TraceChecks.knownEvent(event);
    }

    public TraceCategory category() {
        return event.category();
    }
}
