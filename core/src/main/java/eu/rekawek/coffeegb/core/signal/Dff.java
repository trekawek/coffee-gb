package eu.rekawek.coffeegb.core.signal;

/**
 * A rising-edge D flip-flop with asynchronous set/reset inputs and explicit commit.
 *
 * <p>The caller supplies a resolved rising-edge pulse, rather than a clock level. Set and reset
 * are asynchronous in the circuit sense: either can change {@link #nextQ()} without a clock
 * pulse. Committed {@link #q()} remains stable until every element in the island commits, so Java
 * evaluation order cannot leak through a chain of flip-flops.
 */
public final class Dff {

    private final SrLatch.Dominance dominance;

    private boolean q;

    private boolean nextQ;

    public Dff(SrLatch.Dominance dominance, boolean initialQ) {
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

    /** Resolves the next state from one stable signal vector. */
    public void resolve(
            boolean d, boolean risingClockEdge, boolean asynchronousSet,
            boolean asynchronousClear) {
        if (asynchronousSet && asynchronousClear) {
            nextQ = dominance == SrLatch.Dominance.SET;
        } else if (asynchronousSet) {
            nextQ = true;
        } else if (asynchronousClear) {
            nextQ = false;
        } else if (risingClockEdge) {
            nextQ = d;
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
