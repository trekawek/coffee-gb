package eu.rekawek.coffeegb.core.experimental.cpu;

/**
 * Test-only CPU-clock projection of the DMG timer's NUGA/NYDU/MERY/MOBA request cone.
 *
 * <p><strong>Evidence label: external-gate-waveform-shaped hypothesis.</strong> NYDU samples
 * TIMA bit 7 on BOGA, MERY detects the sampled-MSB fall, and MOBA captures that level at the next
 * BOGA edge. A MOBA rising edge is the raw timer-request wire; the shared IF latch is deliberately
 * outside this island. The topology is anchored in dmg-sim revision {@code ee559e1} at instances
 * {@code nuga}, {@code nydu}, {@code mery}, {@code moba}, and {@code nybo}.
 *
 * <p>This projection retains BOGA phase separately from DIV and is sufficient for natural timer
 * increments and reload/request generation. CPU timer-register writes, the observable DIV ripple
 * transient, CGB's direct TAC path, and exact half-dot bus apertures remain outside it.</p>
 */
final class DmgTimerRequestIsland {

    private static final int[] TIMER_BITS = {9, 3, 5, 7};

    record State(
            int div,
            int tac,
            int tima,
            int tma,
            boolean sampledTimaMsb,
            boolean reloadLevel,
            int bogaPhase) {

        State {
            if ((div & ~0xffff) != 0 || (tac & ~7) != 0
                    || (tima & ~0xff) != 0 || (tma & ~0xff) != 0
                    || bogaPhase < 0 || bogaPhase > 3) {
                throw new IllegalArgumentException("timer state outside DMG widths");
            }
        }

        static State stable(int div, int tac, int tima, int tma, int bogaPhase) {
            return new State(
                    div & 0xffff,
                    tac & 7,
                    tima & 0xff,
                    tma & 0xff,
                    (tima & 0x80) != 0,
                    false,
                    bogaPhase & 3);
        }
    }

    record Observation(State state, boolean timerInputFell, boolean requestPulse) {
    }

    private State state;

    private State nextState;

    private boolean timerInputFell;

    private boolean requestPulse;

    private boolean resolutionPending;

    DmgTimerRequestIsland(State initialState) {
        restore(initialState);
    }

    void resolve(boolean cpuClockEdge) {
        if (resolutionPending) {
            throw new IllegalStateException("previous timer resolution has not committed");
        }
        timerInputFell = false;
        requestPulse = false;
        if (!cpuClockEdge) {
            nextState = state;
            resolutionPending = true;
            return;
        }

        int nextDiv = state.div();
        int nextTima = state.tima();
        boolean oldReload = state.reloadLevel();
        boolean nextReload = oldReload;
        boolean nextSampledMsb = state.sampledTimaMsb();

        boolean oldTimerInput = timerInput(state.div(), state.tac());
        nextDiv = (state.div() + 1) & 0xffff;
        boolean newTimerInput = timerInput(nextDiv, state.tac());
        timerInputFell = oldTimerInput && !newTimerInput;
        if (timerInputFell && !oldReload) {
            nextTima = (nextTima + 1) & 0xff;
        }

        int nextBogaPhase = (state.bogaPhase() + 1) & 3;
        if (nextBogaPhase == 0) {
            // NYDU and MOBA capture the committed input vector. A TIMA ripple launched by this
            // same BOGA edge cannot feed MERY back into MOBA until the following BOGA edge.
            boolean mery = state.sampledTimaMsb() && !msb(state.tima());
            nextReload = mery;
            requestPulse = !oldReload && nextReload;
            nextSampledMsb = oldReload || nextReload ? false : msb(state.tima());
        }

        // The old or newly captured MOBA level owns TIMA through this adapter boundary.
        if (oldReload || nextReload) {
            nextTima = state.tma();
        }
        nextState = new State(
                nextDiv,
                state.tac(),
                nextTima,
                state.tma(),
                nextSampledMsb,
                nextReload,
                nextBogaPhase);
        resolutionPending = true;
    }

    Observation capturedObservation() {
        if (!resolutionPending) {
            throw new IllegalStateException("resolve before observing timer wires");
        }
        return new Observation(nextState, timerInputFell, requestPulse);
    }

    void commit() {
        if (!resolutionPending) {
            throw new IllegalStateException("resolve before timer commit");
        }
        state = nextState;
        resolutionPending = false;
    }

    State capture() {
        if (resolutionPending) {
            throw new IllegalStateException("capture only at a timer commit boundary");
        }
        return state;
    }

    void restore(State restoredState) {
        if (restoredState == null) {
            throw new NullPointerException("restoredState");
        }
        state = restoredState;
        nextState = restoredState;
        timerInputFell = false;
        requestPulse = false;
        resolutionPending = false;
    }

    private static boolean timerInput(int div, int tac) {
        int bit = TIMER_BITS[tac & 3];
        return (tac & 4) != 0 && (div & (1 << bit)) != 0;
    }

    private static boolean msb(int value) {
        return (value & 0x80) != 0;
    }
}
