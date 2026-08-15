package eu.rekawek.coffeegb.core.signal;

/**
 * A clocked set/reset latch with an explicit resolution and commit boundary.
 *
 * <p>{@link #q()} always exposes the state from the previous commit. A caller first resolves the
 * set and clear wires, may inspect {@link #nextQ()}, and only then advances the latch with
 * {@link #commit()}. This prevents Java call order from deciding same-clock set/clear races.
 */
public final class SrLatch {

    public enum Dominance {
        SET,
        CLEAR
    }

    private final Dominance dominance;

    private boolean q;

    private boolean nextQ;

    public SrLatch(Dominance dominance, boolean initialQ) {
        if (dominance == null) {
            throw new NullPointerException("dominance");
        }
        this.dominance = dominance;
        this.q = initialQ;
        this.nextQ = initialQ;
    }

    public boolean q() {
        return q;
    }

    public boolean nextQ() {
        return nextQ;
    }

    /** Resolves the next state without changing the currently visible state. */
    public void resolve(boolean set, boolean clear) {
        if (set && clear) {
            nextQ = dominance == Dominance.SET;
        } else if (set) {
            nextQ = true;
        } else if (clear) {
            nextQ = false;
        } else {
            nextQ = q;
        }
    }

    public void commit() {
        q = nextQ;
    }

    /** Restores portable state at a clock boundary. */
    public void restore(boolean restoredQ) {
        q = restoredQ;
        nextQ = restoredQ;
    }
}
