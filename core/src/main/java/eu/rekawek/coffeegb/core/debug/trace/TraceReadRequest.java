package eu.rekawek.coffeegb.core.debug.trace;

/** Bounded exclusive-cursor read from a trace buffer. */
public record TraceReadRequest(long afterSequence, int maxEntries) {

    public static final int MAX_ENTRIES = 4096;

    /** {@code afterSequence == -1} starts at the oldest event still retained. */
    public TraceReadRequest {
        if (afterSequence < -1) {
            throw new IllegalArgumentException(
                    "afterSequence must be -1 or non-negative: " + afterSequence);
        }
        TraceChecks.range("maxEntries", maxEntries, 1, MAX_ENTRIES);
    }

    public static TraceReadRequest initial(int maxEntries) {
        return new TraceReadRequest(-1, maxEntries);
    }
}
