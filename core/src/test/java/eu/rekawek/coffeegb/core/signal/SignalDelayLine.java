package eu.rekawek.coffeegb.core.signal;

/**
 * A fixed-depth clocked pipeline for a one-bit signal.
 *
 * <p>The input presented to {@link #resolve(boolean)} becomes visible after exactly
 * {@code stages} commits. The implementation uses packed bits and allocates nothing while ticking.
 */
public final class SignalDelayLine {

    private final int stages;

    private final long mask;

    private final long outputMask;

    private long q;

    private long nextQ;

    public SignalDelayLine(int stages, boolean initialLevel) {
        if (stages < 1 || stages > Long.SIZE - 1) {
            throw new IllegalArgumentException("stages must be in 1..63");
        }
        this.stages = stages;
        this.mask = (1L << stages) - 1;
        this.outputMask = 1L << (stages - 1);
        this.q = initialLevel ? mask : 0;
        this.nextQ = q;
    }

    public int stages() {
        return stages;
    }

    public boolean output() {
        return (q & outputMask) != 0;
    }

    public boolean nextOutput() {
        return (nextQ & outputMask) != 0;
    }

    /** Resolves all stage inputs from the previously committed stages. */
    public void resolve(boolean input) {
        nextQ = ((q << 1) | (input ? 1 : 0)) & mask;
    }

    public void commit() {
        q = nextQ;
    }

    /** Packed stage state, with the newest stage in bit zero. */
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
}
