package eu.rekawek.coffeegb.core.debug.trace;

import java.util.List;
import java.util.Objects;

/** Immutable bounded page plus overwrite accounting for one cursor read. */
public record TraceReadResult(
        List<TraceEntry> entries,
        long nextAfterSequence,
        long missedEventCount,
        long droppedEventCount,
        long oldestAvailableSequence,
        long nextSequence) {

    public TraceReadResult {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
        if (entries.size() > TraceReadRequest.MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "entries must contain at most " + TraceReadRequest.MAX_ENTRIES + " values");
        }
        if (nextAfterSequence < -1) {
            throw new IllegalArgumentException(
                    "nextAfterSequence must be -1 or non-negative: " + nextAfterSequence);
        }
        TraceChecks.nonNegative("missedEventCount", missedEventCount);
        TraceChecks.nonNegative("droppedEventCount", droppedEventCount);
        if (missedEventCount > droppedEventCount) {
            throw new IllegalArgumentException(
                    "missedEventCount must not exceed cumulative droppedEventCount");
        }
        TraceChecks.nonNegative("oldestAvailableSequence", oldestAvailableSequence);
        TraceChecks.nonNegative("nextSequence", nextSequence);
        if (oldestAvailableSequence > nextSequence) {
            throw new IllegalArgumentException(
                    "oldestAvailableSequence must not exceed nextSequence");
        }
        long previous = -1;
        for (TraceEntry entry : entries) {
            Objects.requireNonNull(entry, "entries contains null");
            if (entry.sequence() <= previous) {
                throw new IllegalArgumentException("entries must be ordered by sequence");
            }
            if (entry.sequence() < oldestAvailableSequence || entry.sequence() >= nextSequence) {
                throw new IllegalArgumentException("entry is outside the advertised buffer range");
            }
            previous = entry.sequence();
        }
        if (!entries.isEmpty() && nextAfterSequence != entries.get(entries.size() - 1).sequence()) {
            throw new IllegalArgumentException(
                    "nextAfterSequence must identify the last returned entry");
        }
    }

    public TraceReadRequest nextRequest(int maxEntries) {
        return new TraceReadRequest(nextAfterSequence, maxEntries);
    }
}
