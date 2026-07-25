package eu.rekawek.coffeegb.core.memento;

import java.util.IdentityHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Internal safe-point token for building a transient machine-state view without cloning primitive
 * payloads.
 *
 * <p>Dominant array owners first declare their live backing identities and logical lengths through
 * {@link Originator#declareMachineStatePayloads(MachineStateCapture)}. Their token-aware mementos
 * must then register those exact identities. A helper clone such as {@code
 * capture.ints(ram.clone())} leaves the declared live backing unmatched and therefore fails
 * deterministically. Every primitive array in the transient view, including smaller non-dominant
 * payloads, must still be registered by the token-aware path.
 *
 * <p>The consumer must copy or compare the verified arrays synchronously while the emulator is
 * stopped at its frame boundary. The token is closed before the capture call returns, and neither
 * it nor the transient view may be retained. This is deliberately separate from the normal {@link
 * Originator#saveToMemento()} contract. Legacy, portable-state, boot-state and other callers
 * continue to receive deep-owned arrays.
 */
public final class MachineStateCapture implements AutoCloseable {

    private final IdentityHashMap<Object, Integer> declaredPayloads = new IdentityHashMap<>();

    private final IdentityHashMap<Object, Integer> registeredLengths = new IdentityHashMap<>();

    private long verifiedPayloadBytes;

    private int verifiedPayloadArrays;

    private boolean active = true;

    private MachineStateCapture() {}

    /**
     * Builds and consumes one verified transient machine view.
     *
     * <p>Declaration reads only explicit owner fields and does not build a memento graph. The
     * source then builds exactly one short-lived record view.
     */
    public static <V, R> R withVerifiedView(
            Consumer<MachineStateCapture> declaration,
            Function<MachineStateCapture, V> source,
            BiFunction<V, MachineStateCapture, R> consumer) {
        if (declaration == null || source == null || consumer == null) {
            throw new IllegalArgumentException(
                    "Machine-state declaration, source and consumer are required");
        }
        try (MachineStateCapture capture = new MachineStateCapture()) {
            declaration.accept(capture);
            V view = source.apply(capture);
            capture.requireAllDeclaredPayloadsVerified();
            return consumer.apply(view, capture);
        }
    }

    public void declareBytes(byte[] source) {
        declare(source, source.length, 1);
    }

    public void declareInts(int[] source) {
        declareInts(source, source.length);
    }

    public void declareInts(int[] source, int length) {
        if (length < 0 || length > source.length) {
            throw new IllegalArgumentException("Invalid declared int-array prefix");
        }
        declare(source, length, Integer.BYTES);
    }

    public void declareLongs(long[] source) {
        declare(source, source.length, Long.BYTES);
    }

    public void declareBooleans(boolean[] source) {
        declare(source, source.length, 1);
    }

    public void declareInts2(int[][] source) {
        ensureActive();
        for (int[] row : source) {
            if (row != null) {
                declareInts(row);
            }
        }
    }

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
        Integer length = registeredLengths.get(source);
        if (length == null) {
            throw new IllegalStateException(
                    "Primitive payload was not registered by the machine-state capture seam");
        }
        return length;
    }

    /** Number of dominant primitive backing arrays verified against explicit owner declarations. */
    public int getVerifiedPayloadArrays() {
        ensureActive();
        return verifiedPayloadArrays;
    }

    /** Logical bytes whose dominant live backing identity and length were explicitly verified. */
    public long getVerifiedPayloadBytes() {
        ensureActive();
        return verifiedPayloadBytes;
    }

    @Override
    public void close() {
        active = false;
        declaredPayloads.clear();
        registeredLengths.clear();
    }

    private void declare(Object source, int length, int width) {
        ensureActive();
        if (source == null) {
            throw new IllegalArgumentException("Declared primitive payload is required");
        }
        Integer existing = declaredPayloads.putIfAbsent(source, length);
        if (existing != null && existing != length) {
            throw new IllegalArgumentException(
                    "A dominant primitive payload cannot be declared with two logical layouts");
        }
    }

    private void register(Object source, int length, int width) {
        ensureActive();
        if (source == null) {
            throw new IllegalArgumentException("Primitive payload is required");
        }
        Integer existingLength = registeredLengths.putIfAbsent(source, length);
        if (existingLength != null) {
            if (existingLength != length) {
                throw new IllegalArgumentException(
                        "A primitive payload cannot be registered with two logical lengths");
            }
            return;
        }
        Integer declared = declaredPayloads.remove(source);
        if (declared == null) {
            return;
        }
        if (declared != length) {
            throw new IllegalStateException(
                    "Dominant primitive payload layout changed during machine-state capture");
        }
        verifiedPayloadArrays++;
        verifiedPayloadBytes = Math.addExact(
                verifiedPayloadBytes,
                Math.multiplyExact((long) length, width));
    }

    private void requireAllDeclaredPayloadsVerified() {
        ensureActive();
        if (!declaredPayloads.isEmpty()) {
            throw new IllegalStateException(
                    "A dominant primitive payload was copied or omitted by machine-state capture");
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("Machine-state capture token is no longer active");
        }
    }
}
