package eu.rekawek.coffeegb.core.signal;

/**
 * Routes one 8.388608 MHz half-dot quantum into the portable Game Boy clock domains.
 *
 * <p>The fixed PPU/APU domain receives one clock-enable strobe every two quanta. The CPU domain
 * receives that same cadence in DMG and CGB normal speed, and a strobe on every quantum in CGB
 * double speed. Speed is a resolved input wire, not router state: changing it can alter only the
 * CPU strobe and can never re-anchor the independently running fixed domain.
 *
 * <p>A caller resolves the current quantum, lets clocked consumers capture their next state, and
 * then calls {@link #commit()}. The initial phase must be explicit because reset/boot integration
 * determines which host invocation corresponds to a fixed-domain edge.
 */
public final class HalfDotClockRouter {

    public static final int HALF_DOT_QUANTA_PER_SECOND = 8_388_608;

    public static final int FIXED_DOMAIN_CLOCKS_PER_SECOND = 4_194_304;

    public enum Phase {
        FIXED_DOMAIN_EDGE,
        BETWEEN_FIXED_DOMAIN_EDGES;

        private Phase next() {
            return this == FIXED_DOMAIN_EDGE
                    ? BETWEEN_FIXED_DOMAIN_EDGES
                    : FIXED_DOMAIN_EDGE;
        }
    }

    private Phase phase;

    private Phase nextPhase;

    private boolean fixedDomainClockEnable;

    private boolean cpuDomainClockEnable;

    public HalfDotClockRouter(Phase initialPhase) {
        restore(initialPhase);
    }

    /**
     * Resolves the clock enables for one half-dot quantum without advancing the phase.
     *
     * @param doubleSpeed false for DMG and CGB normal speed; true for CGB double speed
     */
    public void resolve(boolean doubleSpeed) {
        fixedDomainClockEnable = phase == Phase.FIXED_DOMAIN_EDGE;
        cpuDomainClockEnable = doubleSpeed || fixedDomainClockEnable;
        nextPhase = phase.next();
    }

    /** One PPU/APU clock edge is present in the resolved quantum. */
    public boolean fixedDomainClockEnable() {
        return fixedDomainClockEnable;
    }

    /** One CPU/timer/serial/DMA clock edge is present in the resolved quantum. */
    public boolean cpuDomainClockEnable() {
        return cpuDomainClockEnable;
    }

    /** Phase at the preceding commit boundary. */
    public Phase phase() {
        return phase;
    }

    /** Phase that will become current at the next commit boundary. */
    public Phase nextPhase() {
        return nextPhase;
    }

    public void commit() {
        phase = nextPhase;
        nextPhase = phase;
        fixedDomainClockEnable = false;
        cpuDomainClockEnable = false;
    }

    /** Restores all behavioral phase state and clears derived strobes. */
    public void restore(Phase restoredPhase) {
        if (restoredPhase == null) {
            throw new NullPointerException("restoredPhase");
        }
        phase = restoredPhase;
        nextPhase = restoredPhase;
        fixedDomainClockEnable = false;
        cpuDomainClockEnable = false;
    }
}
