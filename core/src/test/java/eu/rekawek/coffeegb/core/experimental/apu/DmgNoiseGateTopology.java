package eu.rekawek.coffeegb.core.experimental.apu;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detached DMG channel-4 clock and LFSR cone.
 *
 * <p>Netlist anchors: in {@code right-col/j.nl}, JARE/JERO/JAKY.~q drive the parallel data of
 * JYCO/JYRE/JYFU; in {@code h.nl}/{@code g.nl}, HUCE resolves to
 * {@code CH4_RESTART | GARY}, HYNO is their {@code 111} terminal decode, and GARY samples HYNO
 * on GYBA={@code !CH4_1MHZ}. CARY in {@code c.nl} is exactly
 * {@code CH4_1MHZ & GARY}. Consequently ratio zero is not a special divider: loading
 * {@code ~000 == 111} simply leaves GARY's gate continuously open. Ratios one through seven
 * start at {@code ~ratio} and naturally take {@code ratio} ripple clocks to reach {@code 111}.
 *
 * <p>The physical 15-stage LFSR (KOMU through HEZU) resets to zero and feeds back XNOR. JOTO is
 * a separate half-cycle feedback sampler: HURA XNORs HYRO/HEZU into JOTO, then KAVU selects
 * JOTO rather than JUXE for JEPE.d when FF22.D3 is high. After a complete selected-tap cycle,
 * Coffee GB's production {@code Lfsr} is the exact bitwise complement of those fifteen stages
 * (all-one reset and XOR feedback). Seven-bit mode is therefore one mux, not another algorithm.
 *
 * <p>This island advances one 4.194304 MHz master T-cycle through DRIVE, RESOLVE, and COMMIT.
 * It intentionally starts at CH4_START, after the CPU/APU_PHI synchronizer. Propagation within
 * a T-cycle and non-DMG wiring profiles remain named falsifiers rather than hidden offsets.
 */
final class DmgNoiseGateTopology {

    enum Falsifier {
        CGB_CLOCK_AND_RESTART_PROFILE,
        CPU_WRITE_TO_APU_PHI_PHASE,
        SUB_T_LFSR_BUFFER_PROPAGATION,
        LIVE_SHIFT_MUX_WRITE_EDGE,
        DAC_DISABLE_GARY_COLLISION,
        APU_TEST_MODE_BYPASS,
        ANALOG_DAC_TRANSIENT,
        APU_RESET_ASSERTION_AND_RELEASE
    }

    /** Limits of a differential against today's scheduler-oriented production projection. */
    enum ProductionProjectionBoundary {
        TRIGGER_COUNTDOWN_ALIGNMENT,
        COUNTDOWN_RELOADED_WRITE_WINDOW,
        INACTIVE_CHANNEL_LFSR_FREEZE,
        DAC_OFF_BACKGROUND_COUNTER
    }

    static Set<Falsifier> falsifiers() {
        return Set.copyOf(EnumSet.allOf(Falsifier.class));
    }

    static Set<ProductionProjectionBoundary> productionProjectionBoundaries() {
        return Set.copyOf(EnumSet.allOf(ProductionProjectionBoundary.class));
    }

    /** Signals intentionally entering this bounded cone from register/control logic. */
    enum InputBoundary {
        LATCHED_NR43_BITS,
        CH4_START_AFTER_GYSU,
        CH4_AMP_EN_N_FROM_NR42,
        LENGTH_STOP_EFOT
    }

    static Set<InputBoundary> inputBoundaries() {
        return Set.copyOf(EnumSet.allOf(InputBoundary.class));
    }

    record LfsrStep(int physicalState, int output) {

        LfsrStep {
            physicalState &= 0x7fff;
            output &= 1;
        }
    }

    record Observation(
            boolean ch4Rising,
            boolean ch4Falling,
            boolean hamaRising,
            boolean hamaFalling,
            boolean restartRising,
            boolean restartHigh,
            boolean delayedStartRising,
            boolean delayedStartHigh,
            boolean ratioLoadHigh,
            boolean noiseCounterClockRising,
            boolean lfsrClockRising,
            int digitalOutput) {
    }

    private static final int COMMITTED = 0;

    private static final int DRIVEN = 1;

    private static final int RESOLVED = 2;

    private int nr43;

    /** JYCO/JYRE/JYFU.q, low bit first. */
    private int ratioCounter;

    /** GARY.q, which opens the CH4_1MHZ frequency-counter clock gate. */
    private boolean gary;

    /** CEXO through ESEP, represented after ripple settling. */
    private int frequencyCounter;

    /** Zero-reset, XNOR-feedback form of the fifteen physical LFSR stages. */
    private int physicalLfsr;

    /** Four T phases produce the 1 MHz CH4 clock. Phases 0 and 1 are high. */
    private int masterPhase;

    /** HAMA is the buffered JESO.q 512 kHz level. */
    private boolean hama;

    /** HAZO request level, presented after GYSU's APU_PHI sampling. */
    private boolean startRequest;

    /** GONE.q = CH4_RESTART. */
    private boolean restart;

    /** GORA.q, which clears GONE and the HAZO request latch. */
    private boolean restartDelay;

    /** GATY.q, whose rising edge releases JERY's CH4_FDIS latch. */
    private boolean delayedStart;

    /** JERY.~q. It freezes the ratio ripple but is not itself the GARY output gate. */
    private boolean frequencyDisabled;

    /** Derived CH4_AMP_EN_N inverse level from NR42.7..3, feeding JERY and CH4_ACTIVE. */
    private boolean dacEnabled;

    /** GENA.q. It only masks output; it is deliberately absent from the LFSR clocks. */
    private boolean channelActive;

    private int nextRatioCounter;

    private boolean nextGary;

    private int nextFrequencyCounter;

    private int nextPhysicalLfsr;

    private int nextMasterPhase;

    private boolean nextHama;

    private boolean nextStartRequest;

    private boolean nextRestart;

    private boolean nextRestartDelay;

    private boolean nextDelayedStart;

    private boolean nextFrequencyDisabled;

    private boolean nextChannelActive;

    private boolean ch4Rising;

    private boolean ch4Falling;

    private boolean hamaRising;

    private boolean hamaFalling;

    private boolean restartRising;

    private boolean delayedStartRising;

    private boolean ratioLoadHigh;

    private boolean noiseCounterClockRising;

    private boolean lfsrClockRising;

    private int phase;

    private DmgNoiseGateTopology(int nr43, boolean dacEnabled) {
        this.nr43 = nr43 & 0xff;
        this.dacEnabled = dacEnabled;
        this.frequencyDisabled = true;
    }

    /**
     * Reset-cell seed used by the upstream CPU-write/GYSU experiment. Unlike {@link #steady(int)}
     * and {@link #triggerDifferentialSeed(int, int, boolean)}, this does not preload the ratio
     * cells or import a retained clock half: all DFF-backed clock state remains at its physical
     * zero-reset value.
     */
    static DmgNoiseGateTopology resetSeed(int nr43) {
        return new DmgNoiseGateTopology(nr43, true);
    }

    /**
     * A legal settled state just after restart has loaded the ratio cells and JERY has enabled
     * their clock. This avoids importing trigger phase into steady-state divider comparisons.
     */
    static DmgNoiseGateTopology steady(int nr43) {
        return triggerDifferentialSeed(nr43, 0, true);
    }

    /**
     * Synthetic but internally legal seed for trigger comparison. {@code clockPhase} selects
     * CH4_1MHZ's four T phases plus HAMA's retained half (0..7); {@code backgroundActive}
     * selects JERY's already-released state. It does not claim to be a silicon power-on state.
     */
    static DmgNoiseGateTopology triggerDifferentialSeed(
            int nr43, int clockPhase, boolean backgroundActive) {
        if (clockPhase < 0 || clockPhase >= 8) {
            throw new IllegalArgumentException("clockPhase outside 0..7: " + clockPhase);
        }
        DmgNoiseGateTopology topology = new DmgNoiseGateTopology(nr43, true);
        topology.ratioCounter = ratioParallelLoad(nr43);
        topology.masterPhase = clockPhase & 3;
        topology.hama = (clockPhase & 4) != 0;
        topology.frequencyDisabled = !backgroundActive;
        topology.channelActive = backgroundActive;
        return topology;
    }

    /** CPU-independent CH4_START input, already sampled by GYSU on APU_PHI. */
    void latchSynchronizedStart() {
        require(COMMITTED, "latchSynchronizedStart");
        startRequest = true;
    }

    void writeNr43(int value) {
        require(COMMITTED, "writeNr43");
        nr43 = value & 0xff;
        // HUCE = CH4_RESTART | GARY. TFFNL parallel load is transparent while HUCE is high.
        if (restart || gary) {
            ratioCounter = ratioParallelLoad(nr43);
        }
    }

    /** Resolves CH4_AMP_EN_N from the five DAC-driving NR42 bits. */
    void writeNr42(int value) {
        require(COMMITTED, "writeNr42");
        dacEnabled = (value & 0xf8) != 0;
        if (!dacEnabled) {
            frequencyDisabled = true;
            channelActive = false;
        }
    }

    /** EFOT's length-terminal pulse resets GENA/CH4_ACTIVE but does not enter the clock cone. */
    void driveLengthStopPulse() {
        require(COMMITTED, "driveLengthStopPulse");
        channelActive = false;
    }

    /** DRIVE: advances only the external 4 MHz clock phase. */
    void drive() {
        require(COMMITTED, "drive");
        nextMasterPhase = (masterPhase + 1) & 3;
        boolean oldCh4 = ch4Level(masterPhase);
        boolean newCh4 = ch4Level(nextMasterPhase);
        ch4Rising = !oldCh4 && newCh4;
        ch4Falling = oldCh4 && !newCh4;
        phase = DRIVEN;
    }

    /** RESOLVE: settles the restart pipeline, prescaler, tap ripple, and XNOR bank. */
    void resolve() {
        require(DRIVEN, "resolve");
        nextRatioCounter = ratioCounter;
        nextGary = gary;
        nextFrequencyCounter = frequencyCounter;
        nextPhysicalLfsr = physicalLfsr;
        nextHama = hama;
        nextStartRequest = startRequest;
        nextRestart = restart;
        nextRestartDelay = restartDelay;
        nextDelayedStart = delayedStart;
        nextFrequencyDisabled = frequencyDisabled;
        nextChannelActive = channelActive;

        hamaRising = false;
        hamaFalling = false;
        restartRising = false;
        delayedStartRising = false;
        noiseCounterClockRising = false;
        lfsrClockRising = false;

        if (ch4Rising) {
            nextHama = !hama;
            hamaRising = !hama && nextHama;
            hamaFalling = hama && !nextHama;
        }

        if (hamaRising) {
            // GONE, GORA, and GATY all sample the old levels. GORA's resulting high level then
            // asynchronously clears both GONE and HAZO before the next HAMA edge.
            nextDelayedStart = restartDelay;
            nextRestartDelay = restart;
            if (restart) {
                nextRestart = false;
                nextStartRequest = false;
            } else {
                nextRestart = startRequest;
            }
            restartRising = !restart && nextRestart;
            delayedStartRising = !delayedStart && nextDelayedStart;
        }

        if (delayedStartRising && dacEnabled) {
            // GATY -> HAPU supplies JERY's active-low set input; JERY retains the enabled level.
            nextFrequencyDisabled = false;
        }

        if (restartRising) {
            nextChannelActive = dacEnabled;
        }

        if (nextRestart) {
            // CH4_RESTART resets GARY, JOTO, and all fifteen shift-stage DFFRs. It does not
            // reset CEXO..ESEP, which makes retrigger preserve frequency phase.
            nextGary = false;
            nextPhysicalLfsr = 0;
            nextRatioCounter = ratioParallelLoad(nr43);
        } else {
            // JYCO sees HAMA as its active-low toggle clock. The other two cells ripple from it.
            if (hamaFalling && !frequencyDisabled && !gary) {
                nextRatioCounter = (ratioCounter + 1) & 7;
            }

            // GARY samples the ratio terminal on CH4_1MHZ's falling edge. Its resulting high
            // level immediately opens HUCE and transparently reloads all three ratio cells.
            if (ch4Falling) {
                nextGary = nextRatioCounter == 7;
                if (nextGary) {
                    nextRatioCounter = ratioParallelLoad(nr43);
                }
            }

            // CARY is literally CH4_1MHZ & GARY. FDIS affects the low prescaler clock, not CARY.
            if (ch4Rising && gary) {
                noiseCounterClockRising = true;
                int oldCounter = frequencyCounter;
                nextFrequencyCounter = (frequencyCounter + 1) & 0x3fff;
                int shift = nr43 >>> 4;
                lfsrClockRising = !selectedTap(oldCounter, shift)
                        && selectedTap(nextFrequencyCounter, shift);
                if (lfsrClockRising) {
                    nextPhysicalLfsr = clockPhysicalLfsr(physicalLfsr,
                            (nr43 & 0x08) != 0).physicalState();
                }
            }
        }

        ratioLoadHigh = nextRestart || nextGary;
        phase = RESOLVED;
    }

    Observation observation() {
        require(RESOLVED, "observation");
        int output = nextChannelActive ? nextPhysicalLfsr & 1 : 0;
        return new Observation(ch4Rising, ch4Falling, hamaRising, hamaFalling,
                restartRising, nextRestart, delayedStartRising, nextDelayedStart,
                ratioLoadHigh, noiseCounterClockRising, lfsrClockRising, output);
    }

    /** COMMIT: publishes all retained cell outputs simultaneously. */
    void commit() {
        require(RESOLVED, "commit");
        ratioCounter = nextRatioCounter;
        gary = nextGary;
        frequencyCounter = nextFrequencyCounter;
        physicalLfsr = nextPhysicalLfsr;
        masterPhase = nextMasterPhase;
        hama = nextHama;
        startRequest = nextStartRequest;
        restart = nextRestart;
        restartDelay = nextRestartDelay;
        delayedStart = nextDelayedStart;
        frequencyDisabled = nextFrequencyDisabled;
        channelActive = nextChannelActive;
        phase = COMMITTED;
    }

    Observation tick() {
        drive();
        resolve();
        Observation observation = observation();
        commit();
        return observation;
    }

    int nr43() {
        return nr43;
    }

    int ratioCounter() {
        return ratioCounter;
    }

    boolean ratioLoadHigh() {
        return restart || gary;
    }

    int frequencyCounter() {
        return frequencyCounter;
    }

    int physicalLfsr() {
        return physicalLfsr;
    }

    boolean frequencyDisabled() {
        return frequencyDisabled;
    }

    boolean channelActive() {
        return channelActive;
    }

    /** Raw ATYK/AVOK divide phase, exposed only to compose adjacent detached gate islands. */
    int masterPhase() {
        return masterPhase;
    }

    /** Raw JESO/HAMA retained half, exposed only to compose adjacent detached gate islands. */
    boolean hama() {
        return hama;
    }

    static int ratioParallelLoad(int nr43) {
        return ~(nr43 & 7) & 7;
    }

    static int physicalFromConventional(int conventionalState) {
        return ~conventionalState & 0x7fff;
    }

    static int conventionalFromPhysical(int physicalState) {
        return ~physicalState & 0x7fff;
    }

    static LfsrStep clockPhysicalLfsr(int physicalState, boolean widthMode7) {
        physicalState &= 0x7fff;
        int bit0 = physicalState & 1;
        int bit1 = physicalState >>> 1 & 1;
        int feedback = bit0 == bit1 ? 1 : 0;
        int next = physicalState >>> 1 | feedback << 14;
        if (widthMode7) {
            next = next & ~(1 << 6) | feedback << 6;
        }
        return new LfsrStep(next, next & 1);
    }

    static boolean selectedTap(int frequencyCounter, int shift) {
        return shift >= 0 && shift < 14 && (frequencyCounter & 1 << shift) != 0;
    }

    private static boolean ch4Level(int masterPhase) {
        return (masterPhase & 2) == 0;
    }

    private void require(int expected, String operation) {
        if (phase != expected) {
            throw new IllegalStateException(operation + " called outside its signal phase");
        }
    }
}
