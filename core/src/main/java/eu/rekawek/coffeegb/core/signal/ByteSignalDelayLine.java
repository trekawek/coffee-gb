package eu.rekawek.coffeegb.core.signal;

/**
 * Eight parallel fixed-depth signal paths packed into one allocation-free delay line.
 *
 * <p>The input byte presented to {@link #resolve(int)} becomes visible after exactly
 * {@code stages} commits. {@link #fill(int)} models a reset or transparent phase that initializes
 * every receiver stage from one source level. Only {@link #state()} is portable machine state;
 * the resolved next value is discarded by {@link #restore(long)}.
 */
public final class ByteSignalDelayLine {

    private final int stages;

    private final int outputShift;

    private final long mask;

    private long q;

    private long nextQ;

    public ByteSignalDelayLine(int stages, int initialValue) {
        if (stages < 1 || stages > 7) {
            throw new IllegalArgumentException("stages must be in 1..7");
        }
        this.stages = stages;
        this.outputShift = (stages - 1) * Byte.SIZE;
        this.mask = (1L << (stages * Byte.SIZE)) - 1;
        fill(initialValue);
    }

    public int stages() {
        return stages;
    }

    /** Resolves every stage input from the previously committed byte stages. */
    public void resolve(int input) {
        nextQ = ((q << Byte.SIZE) | byteValue(input)) & mask;
    }

    public void commit() {
        q = nextQ;
    }

    public int output() {
        return (int) (q >>> outputShift) & 0xff;
    }

    /** Makes every stage contain the same source byte. */
    public void fill(int value) {
        long filled = 0;
        for (int stage = 0; stage < stages; stage++) {
            filled |= (long) byteValue(value) << (stage * Byte.SIZE);
        }
        q = filled;
        nextQ = filled;
    }

    /** Packed stage state, with the newest byte in bits 0..7. */
    public long state() {
        return q;
    }

    /** Restores portable state at a clock boundary. */
    public void restore(long state) {
        if ((state & ~mask) != 0) {
            throw new IllegalArgumentException("state has bits outside the delay line");
        }
        q = state;
        nextQ = state;
    }

    private static int byteValue(int value) {
        if ((value & ~0xff) != 0) {
            throw new IllegalArgumentException("value must be an unsigned byte");
        }
        return value;
    }
}
