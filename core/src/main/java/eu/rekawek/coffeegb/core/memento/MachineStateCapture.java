package eu.rekawek.coffeegb.core.memento;

import java.util.IdentityHashMap;

/**
 * Internal safe-point token for building a transient machine-state view without cloning primitive
 * payloads.
 *
 * <p>Array-owning originators register their live arrays with this token and place those same
 * references in a short-lived memento-shaped view. The consumer must copy or compare the arrays
 * synchronously while the emulator is stopped at its frame boundary. The token is closed before
 * the capture call returns, and neither it nor the transient view may be retained.
 *
 * <p>This is deliberately separate from the normal {@link Originator#saveToMemento()} contract.
 * Legacy, portable-state, boot-state and other callers continue to receive deep-owned arrays.
 */
public final class MachineStateCapture implements AutoCloseable {

    private final IdentityHashMap<Object, Integer> lengths = new IdentityHashMap<>();

    private long borrowedPayloadBytes;

    private int borrowedPayloadArrays;

    private boolean active = true;

    public byte[] bytes(byte[] source) {
        register(source, source.length, 1);
        return source;
    }

    public int[] ints(int[] source) {
        return ints(source, source.length);
    }

    /**
     * Borrows a behavior-relevant prefix of a live array. The transient view still carries the
     * source reference; the snapshot consumer observes only {@code length} elements.
     */
    public int[] ints(int[] source, int length) {
        if (length < 0 || length > source.length) {
            throw new IllegalArgumentException("Invalid borrowed int-array prefix");
        }
        register(source, length, Integer.BYTES);
        return source;
    }

    public long[] longs(long[] source) {
        register(source, source.length, Long.BYTES);
        return source;
    }

    public boolean[] booleans(boolean[] source) {
        register(source, source.length, 1);
        return source;
    }

    public int[][] ints2(int[][] source) {
        ensureActive();
        for (int[] row : source) {
            if (row != null) {
                ints(row);
            }
        }
        return source;
    }

    /**
     * Returns the registered logical length for a primitive payload.
     *
     * <p>An unregistered array means an originator fell back to the legacy deep-copy path. The
     * incremental consumer rejects that mistake instead of silently hiding its allocation.
     */
    public int requireLength(Object source) {
        ensureActive();
        Integer length = lengths.get(source);
        if (length == null) {
            throw new IllegalStateException(
                    "Primitive payload was not registered by the machine-state capture seam");
        }
        return length;
    }

    public int getBorrowedPayloadArrays() {
        ensureActive();
        return borrowedPayloadArrays;
    }

    public long getBorrowedPayloadBytes() {
        ensureActive();
        return borrowedPayloadBytes;
    }

    /** Primitive source payloads are borrowed, never cloned, by this seam. */
    public int getSourcePayloadClones() {
        ensureActive();
        return 0;
    }

    /** Primitive source payloads are borrowed, never cloned, by this seam. */
    public long getSourcePayloadCloneBytes() {
        ensureActive();
        return 0;
    }

    @Override
    public void close() {
        active = false;
        lengths.clear();
    }

    private void register(Object source, int length, int width) {
        ensureActive();
        Integer existing = lengths.putIfAbsent(source, length);
        if (existing != null) {
            if (existing != length) {
                throw new IllegalArgumentException(
                        "A primitive payload cannot be borrowed with two logical lengths");
            }
            return;
        }
        borrowedPayloadArrays++;
        borrowedPayloadBytes = Math.addExact(
                borrowedPayloadBytes,
                Math.multiplyExact((long) length, width));
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("Machine-state capture token is no longer active");
        }
    }
}
