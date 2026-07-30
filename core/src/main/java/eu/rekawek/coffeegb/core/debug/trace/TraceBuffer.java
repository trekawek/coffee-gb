package eu.rekawek.coffeegb.core.debug.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-capacity overwrite ring for trace entries.
 *
 * <p>This class is deliberately owner-thread-confined: append and read operations must run at
 * emulation-owner safe points. It performs no locking on the per-event path. Cursor consumers do
 * not register with the ring, so they cannot delay capture; a slow cursor instead observes an
 * explicit missed count after overwritten entries. Appends receive monotonically increasing
 * sequence numbers, and that append order is authoritative when master ticks are equal.
 */
public final class TraceBuffer {

    public static final long NOT_APPENDED = -1;

    private final TraceConfiguration configuration;

    private final long enabledCategoryMask;

    private final TraceEntry[] entries;

    private int head;

    private int size;

    private long nextSequence;

    private long droppedEventCount;

    public TraceBuffer(int capacity) {
        this(TraceConfiguration.allCategories(capacity));
    }

    public TraceBuffer(TraceConfiguration configuration) {
        this(configuration, 0, 0);
    }

    private TraceBuffer(
            TraceConfiguration configuration,
            long nextSequence,
            long droppedEventCount) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.entries = new TraceEntry[configuration.capacity()];
        TraceChecks.nonNegative("nextSequence", nextSequence);
        TraceChecks.nonNegative("droppedEventCount", droppedEventCount);
        this.nextSequence = nextSequence;
        this.droppedEventCount = droppedEventCount;
        long categoryMask = 0;
        for (TraceCategory category : configuration.categories()) {
            categoryMask |= 1L << category.ordinal();
        }
        this.enabledCategoryMask = categoryMask;
    }

    /**
     * Returns an empty replacement with a new fixed configuration while preserving this
     * attachment's sequence space. Retained entries are explicitly counted as dropped, so cursors
     * from before reconfiguration resume or report loss instead of silently waiting on reset IDs.
     */
    public TraceBuffer reconfigured(TraceConfiguration replacement) {
        Objects.requireNonNull(replacement, "replacement");
        long replacementDroppedEventCount = droppedEventCount > Long.MAX_VALUE - size
                ? Long.MAX_VALUE
                : droppedEventCount + size;
        return new TraceBuffer(replacement, nextSequence, replacementDroppedEventCount);
    }

    public TraceConfiguration configuration() {
        return configuration;
    }

    /**
     * Cheap producer guard for avoiding all payload construction while a category is disabled.
     * Hot instrumentation sites should call this before constructing a typed event.
     */
    public boolean isEnabled(TraceCategory category) {
        Objects.requireNonNull(category, "category");
        return (enabledCategoryMask & (1L << category.ordinal())) != 0;
    }

    public boolean isEnabled() {
        return enabledCategoryMask != 0;
    }

    /**
     * Appends an already-created payload, or returns {@link #NOT_APPENDED} when its category is
     * filtered. Callers that need a zero-construction disabled path must guard construction with
     * {@link #isEnabled(TraceCategory)}.
     */
    public long append(long masterTick, TraceSource source, TraceEvent event) {
        TraceChecks.nonNegative("masterTick", masterTick);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(event, "event");
        if (!isEnabled(event.category())) {
            return NOT_APPENDED;
        }
        return appendAccepted(masterTick, source, event);
    }

    private long appendAccepted(long masterTick, TraceSource source, TraceEvent event) {
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Trace sequence space exhausted");
        }
        long sequence = nextSequence;
        TraceEntry entry = new TraceEntry(sequence, masterTick, source, event);
        nextSequence++;
        int writeIndex;
        if (size < entries.length) {
            writeIndex = (head + size) % entries.length;
            size++;
        } else {
            writeIndex = head;
            head = (head + 1) % entries.length;
            if (droppedEventCount != Long.MAX_VALUE) {
                droppedEventCount++;
            }
        }
        entries[writeIndex] = entry;
        return sequence;
    }

    /** Copies at most the request's bounded limit without advancing any global reader state. */
    public TraceReadResult read(TraceReadRequest request) {
        Objects.requireNonNull(request, "request");
        long oldestAvailableSequence = size == 0
                ? nextSequence
                : entries[head].sequence();
        long desiredSequence = request.afterSequence() == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : request.afterSequence() + 1;
        long missedEventCount = desiredSequence < oldestAvailableSequence
                ? oldestAvailableSequence - desiredSequence
                : 0;
        long firstSequence = Math.max(desiredSequence, oldestAvailableSequence);
        int resultSize = firstSequence >= nextSequence
                ? 0
                : (int) Math.min((long) request.maxEntries(), nextSequence - firstSequence);
        List<TraceEntry> resultEntries = new ArrayList<>(resultSize);
        if (resultSize > 0) {
            int offset = (int) (firstSequence - oldestAvailableSequence);
            for (int i = 0; i < resultSize; i++) {
                resultEntries.add(entries[(head + offset + i) % entries.length]);
            }
        }
        long nextAfterSequence;
        if (!resultEntries.isEmpty()) {
            nextAfterSequence = resultEntries.get(resultEntries.size() - 1).sequence();
        } else if (missedEventCount > 0) {
            nextAfterSequence = oldestAvailableSequence - 1;
        } else {
            nextAfterSequence = request.afterSequence();
        }
        return new TraceReadResult(
                resultEntries,
                nextAfterSequence,
                missedEventCount,
                droppedEventCount,
                oldestAvailableSequence,
                nextSequence);
    }

    public int size() {
        return size;
    }

    public long nextSequence() {
        return nextSequence;
    }

    public long droppedEventCount() {
        return droppedEventCount;
    }
}
