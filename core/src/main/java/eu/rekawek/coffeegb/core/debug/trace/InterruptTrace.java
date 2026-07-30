package eu.rekawek.coffeegb.core.debug.trace;

import eu.rekawek.coffeegb.core.debug.DebugInterruptType;

import java.util.Objects;

/** Request, acceptance, or completion of a named Game Boy interrupt line. */
public record InterruptTrace(Kind kind, DebugInterruptType interrupt) implements TraceEvent {

    public enum Kind {
        REQUESTED,
        ACCEPTED,
        COMPLETED,
        CLEARED
    }

    public InterruptTrace {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(interrupt, "interrupt");
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.INTERRUPT;
    }
}
