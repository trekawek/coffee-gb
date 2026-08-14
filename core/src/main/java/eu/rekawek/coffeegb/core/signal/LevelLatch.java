package eu.rekawek.coffeegb.core.signal;

/**
 * A level-sensitive D latch with asynchronous set/reset and explicit commit.
 *
 * <p>When the gate is open, the resolved input becomes {@link #nextQ()}; when it is closed, the
 * latch retains committed {@link #q()}. Asynchronous controls dominate the data gate according to
 * the selected {@link SrLatch.Dominance}. Multiple transparent stages still require the owning
 * island to perform bounded local delta settling when same-phase propagation is observable.
 */
public final class LevelLatch {

    private final SrLatch.Dominance dominance;

    private boolean q;

    private boolean nextQ;

    public LevelLatch(SrLatch.Dominance dominance, boolean initialQ) {
        if (dominance == null) {
            throw new NullPointerException("dominance");
        }
        this.dominance = dominance;
        restore(initialQ);
    }

    public boolean q() {
        return q;
    }

    public boolean nextQ() {
        return nextQ;
    }

    /** Resolves the next state from one stable signal vector. */
    public void resolve(
            boolean d, boolean gateOpen, boolean asynchronousSet,
            boolean asynchronousClear) {
        if (asynchronousSet && asynchronousClear) {
            nextQ = dominance == SrLatch.Dominance.SET;
        } else if (asynchronousSet) {
            nextQ = true;
        } else if (asynchronousClear) {
            nextQ = false;
        } else if (gateOpen) {
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
