package eu.rekawek.coffeegb.core.experimental.clock;

import eu.rekawek.coffeegb.core.signal.HalfDotClockRouter;
import eu.rekawek.coffeegb.core.signal.UnsignedRippleCounter;

/**
 * Detached candidate for the CGB STOP/speed-switch clock island.
 *
 * <p>The model deliberately contains topology, not subsystem callbacks: a fixed 4.19 MHz branch,
 * normal/double CPU-clock sources, clock gates, and three small counters. The final STOP bus cycle
 * is visible, so the extra four divider clocks of a normal-to-double switch arise from routing its
 * four T states at double speed. There is no {@code Timer.onSpeedSwitch(+4)} operation. Likewise,
 * resume latency is the distance to wrap of a free-running phase ring, not a 2/8-dot lookup.
 *
 * <p><strong>Hardware-derived constraints:</strong> the PPU and APU oscillator stay on the fixed
 * branch; CPU, DIV/timer, serial, and OAM DMA share the selected-speed branch; DIV is cleared and
 * held while the CPU clock is stopped; the DIV-APU tap is bit 12 in normal speed and bit 13 in
 * double speed. These constraints come from public CGB timing documentation and independently
 * observable register behavior.
 *
 * <p><strong>Empirically pinned structure:</strong> a 17-stage switch sequencer reproduces Coffee
 * GB's and Daid's 0x20000 selected-clock pause, and a three-stage release phase ring spans the
 * observed 2/8-dot tail family. Those counter widths are behavioral hypotheses, not a recovered
 * CGB netlist. In particular, public descriptions that quote a much shorter 8200-T STOP interval
 * conflict with the 17-stage interpretation; the project/Daid calibration may include a different
 * observation window. The precise phase relationship between the release ring, LCD line phase,
 * and HDMA arbitration is still unknown.
 *
 * <p><strong>Explicit hypothesis:</strong> KEY1 selects the destination clock for STOP's last
 * eight T states. This turns the current timer's hidden +4 adjustment into four ordinary
 * between-dot edges on a normal-to-double transition. The reverse-direction entry timing and the
 * release-ring wiring need hardware traces before production use. The model routes HDMA's clock
 * on the fixed branch, but deliberately does not claim to explain the current HBlank/OAM ownership
 * adjustments: a production cut also needs persistent DMA grant state, and must save the router,
 * entry, sequencer, and release-ring phases.
 */
final class CgbSpeedSwitchClockMachine {

    /** Eight explicit destination-clock T states in STOP's final entry sequence. */
    static final int STOP_ENTRY_COUNTER_BITS = 3;

    /** 0x20000 selected-clock edges, expressed as a candidate ripple-chain width. */
    static final int SWITCH_SEQUENCER_BITS = 17;

    /** Eight possible fixed-domain release phases; no tail-duration table is present. */
    static final int RELEASE_PHASE_BITS = 3;

    enum Speed {
        NORMAL(false),
        DOUBLE(true);

        private final boolean doubleSpeed;

        Speed(boolean doubleSpeed) {
            this.doubleSpeed = doubleSpeed;
        }

        boolean isDoubleSpeed() {
            return doubleSpeed;
        }

        Speed opposite() {
            return this == NORMAL ? DOUBLE : NORMAL;
        }
    }

    enum State {
        RUN_NORMAL,
        RUN_DOUBLE,
        STOP_ENTRY,
        SWITCH_DELAY,
        MUX_SETTLE
    }

    /** Signals resolved for one half-dot, plus the state visible after their commit. */
    record Signals(
            long halfDot,
            State stateBefore,
            State stateAfter,
            Speed visibleSpeed,
            boolean fixedEdge,
            boolean cpuClockEdge,
            boolean cpuRunEdge,
            boolean dividerClockEdge,
            boolean serialClockEdge,
            boolean oamDmaClockEdge,
            boolean ppuClockEdge,
            boolean apuOscillatorEdge,
            boolean hdmaClockEdge,
            boolean switchSequencerEdge,
            boolean dividerReset,
            boolean timerFallingEdge,
            boolean apuFrameFallingEdge,
            boolean muxReleased,
            int dividerAfter,
            int releasePhaseAfter) {
    }

    private static final int DIV_WIDTH = 16;

    private static final int NORMAL_APU_TAP = 12;

    private static final int DOUBLE_APU_TAP = 13;

    private final HalfDotClockRouter clockRouter;

    private final UnsignedRippleCounter divider = new UnsignedRippleCounter(DIV_WIDTH, 0);

    private final UnsignedRippleCounter stopEntry =
            new UnsignedRippleCounter(STOP_ENTRY_COUNTER_BITS, 0);

    private final UnsignedRippleCounter switchSequencer =
            new UnsignedRippleCounter(SWITCH_SEQUENCER_BITS, 0);

    private final UnsignedRippleCounter releasePhase;

    private Speed runningSpeed;

    private Speed targetSpeed;

    private State state;

    private boolean timerEnabled;

    private int timerBit;

    private int tima;

    private boolean timerInput;

    private boolean apuFrameInput;

    private long halfDots;

    private long fixedEdges;

    private long cpuClockEdges;

    private long cpuRunEdges;

    private long dividerClockEdges;

    private long serialClockEdges;

    private long oamDmaClockEdges;

    private long ppuClockEdges;

    private long apuOscillatorEdges;

    private long hdmaClockEdges;

    private long stopEntryEdges;

    private long switchDelayEdges;

    private long timerFallingEdges;

    private long apuFrameFallingEdges;

    CgbSpeedSwitchClockMachine(Speed initialSpeed, int initialReleasePhase) {
        if (initialSpeed == null) {
            throw new NullPointerException("initialSpeed");
        }
        if (initialReleasePhase < 0 || initialReleasePhase >= (1 << RELEASE_PHASE_BITS)) {
            throw new IllegalArgumentException("initialReleasePhase must fit the phase ring");
        }
        clockRouter = new HalfDotClockRouter(HalfDotClockRouter.Phase.FIXED_DOMAIN_EDGE);
        releasePhase = new UnsignedRippleCounter(RELEASE_PHASE_BITS, initialReleasePhase);
        runningSpeed = initialSpeed;
        targetSpeed = initialSpeed;
        state = runState(initialSpeed);
        timerInput = timerLevel(divider.value());
        apuFrameInput = apuFrameLevel(divider.value(), initialSpeed);
    }

    void presetDivider(int value) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException("value must be a 16-bit unsigned integer");
        }
        divider.restore(value);
        timerInput = timerLevel(value);
        apuFrameInput = apuFrameLevel(value, visibleSpeed());
    }

    void configureTimer(boolean enabled, int selectedBit, int initialTima) {
        if (selectedBit < 0 || selectedBit >= DIV_WIDTH) {
            throw new IllegalArgumentException("selectedBit must be in 0..15");
        }
        if (initialTima < 0 || initialTima > 0xff) {
            throw new IllegalArgumentException("initialTima must be a byte");
        }
        timerEnabled = enabled;
        timerBit = selectedBit;
        tima = initialTima;
        timerInput = timerLevel(divider.value());
    }

    /**
     * Begins at T1 of STOP's final eight-T-state entry sequence, before the old callback model
     * atomically consumes that sequence. The destination clock is already selected, while opcode
     * execution remains gated after the entry sequencer completes.
     */
    void requestSpeedSwitch() {
        if (!isRunning()) {
            throw new IllegalStateException("a switch can only begin while running");
        }
        targetSpeed = runningSpeed.opposite();
        stopEntry.restore(0);
        switchSequencer.restore(0);
        state = State.STOP_ENTRY;
    }

    Signals stepHalfDot() {
        State stateBefore = state;
        Speed sourceSpeed = isRunning() ? runningSpeed : targetSpeed;
        clockRouter.resolve(sourceSpeed.isDoubleSpeed());

        boolean fixedEdge = clockRouter.fixedDomainClockEnable();
        boolean selectedClockEdge = clockRouter.cpuDomainClockEnable();
        boolean inRun = isRunning();
        boolean inEntry = state == State.STOP_ENTRY;
        boolean inDelay = state == State.SWITCH_DELAY;
        boolean inSettle = state == State.MUX_SETTLE;

        boolean cpuClockEdge = selectedClockEdge && (inRun || inEntry);
        boolean cpuRunEdge = selectedClockEdge && inRun;
        boolean dividerClockEdge = selectedClockEdge && (inRun || inEntry);
        boolean serialClockEdge = cpuClockEdge;
        boolean oamDmaClockEdge = cpuClockEdge;
        boolean switchSequencerEdge = selectedClockEdge && (inEntry || inDelay);

        stopEntry.resolve(selectedClockEdge && inEntry, false);
        switchSequencer.resolve(selectedClockEdge && inDelay, false);
        releasePhase.resolve(fixedEdge, false);

        boolean entryComplete = inEntry && selectedClockEdge
                && stopEntry.value() != 0 && stopEntry.nextValue() == 0;
        boolean delayComplete = inDelay && selectedClockEdge
                && switchSequencer.value() != 0 && switchSequencer.nextValue() == 0;
        boolean releasePhaseWrapped = fixedEdge
                && releasePhase.value() != 0 && releasePhase.nextValue() == 0;
        // Phase zero waits for a complete eight-edge lap instead of releasing immediately.
        if (fixedEdge && releasePhase.value() == 0 && releasePhase.nextValue() == 1) {
            releasePhaseWrapped = false;
        }
        boolean muxReleased = inSettle && releasePhaseWrapped;

        // The reset wire is asserted after the eighth entry clock. Counter clear dominates that
        // clock in the divider cell, but edge consumers still see the resulting high-to-low tap.
        boolean dividerReset = entryComplete;
        divider.resolve(dividerClockEdge, dividerReset);
        int dividerAfter = (int) divider.nextValue();

        Speed visibleSpeed = visibleSpeed();
        boolean nextTimerInput = timerLevel(dividerAfter);
        boolean timerFallingEdge = timerInput && !nextTimerInput;
        boolean nextApuFrameInput = apuFrameLevel(dividerAfter, visibleSpeed);
        boolean apuFrameFallingEdge = apuFrameInput && !nextApuFrameInput;

        State nextState = state;
        if (entryComplete) {
            nextState = State.SWITCH_DELAY;
        } else if (delayComplete) {
            nextState = State.MUX_SETTLE;
        } else if (muxReleased) {
            runningSpeed = targetSpeed;
            nextState = runState(runningSpeed);
        }

        if (fixedEdge) {
            fixedEdges++;
            ppuClockEdges++;
            apuOscillatorEdges++;
            hdmaClockEdges++;
        }
        if (cpuClockEdge) {
            cpuClockEdges++;
        }
        if (cpuRunEdge) {
            cpuRunEdges++;
        }
        if (dividerClockEdge) {
            dividerClockEdges++;
        }
        if (serialClockEdge) {
            serialClockEdges++;
        }
        if (oamDmaClockEdge) {
            oamDmaClockEdges++;
        }
        if (selectedClockEdge && inEntry) {
            stopEntryEdges++;
        }
        if (selectedClockEdge && inDelay) {
            switchDelayEdges++;
        }
        if (timerFallingEdge) {
            timerFallingEdges++;
            if (timerEnabled) {
                tima = (tima + 1) & 0xff;
            }
        }
        if (apuFrameFallingEdge) {
            apuFrameFallingEdges++;
        }

        divider.commit();
        stopEntry.commit();
        switchSequencer.commit();
        releasePhase.commit();
        clockRouter.commit();
        timerInput = nextTimerInput;
        apuFrameInput = nextApuFrameInput;
        state = nextState;

        Signals signals = new Signals(
                halfDots,
                stateBefore,
                nextState,
                visibleSpeed,
                fixedEdge,
                cpuClockEdge,
                cpuRunEdge,
                dividerClockEdge,
                serialClockEdge,
                oamDmaClockEdge,
                fixedEdge,
                fixedEdge,
                fixedEdge,
                switchSequencerEdge,
                dividerReset,
                timerFallingEdge,
                apuFrameFallingEdge,
                muxReleased,
                dividerAfter,
                (int) releasePhase.value());
        halfDots++;
        return signals;
    }

    private boolean isRunning() {
        return state == State.RUN_NORMAL || state == State.RUN_DOUBLE;
    }

    private Speed visibleSpeed() {
        return isRunning() ? runningSpeed : targetSpeed;
    }

    private boolean timerLevel(long div) {
        return timerEnabled && (div & (1L << timerBit)) != 0;
    }

    private static boolean apuFrameLevel(long div, Speed speed) {
        int tap = speed == Speed.DOUBLE ? DOUBLE_APU_TAP : NORMAL_APU_TAP;
        return (div & (1L << tap)) != 0;
    }

    private static State runState(Speed speed) {
        return speed == Speed.NORMAL ? State.RUN_NORMAL : State.RUN_DOUBLE;
    }

    State state() {
        return state;
    }

    Speed visibleSpeedValue() {
        return visibleSpeed();
    }

    int divider() {
        return (int) divider.value();
    }

    int tima() {
        return tima;
    }

    int releasePhase() {
        return (int) releasePhase.value();
    }

    long halfDots() {
        return halfDots;
    }

    long fixedEdges() {
        return fixedEdges;
    }

    long cpuClockEdges() {
        return cpuClockEdges;
    }

    long cpuRunEdges() {
        return cpuRunEdges;
    }

    long dividerClockEdges() {
        return dividerClockEdges;
    }

    long serialClockEdges() {
        return serialClockEdges;
    }

    long oamDmaClockEdges() {
        return oamDmaClockEdges;
    }

    long ppuClockEdges() {
        return ppuClockEdges;
    }

    long apuOscillatorEdges() {
        return apuOscillatorEdges;
    }

    long hdmaClockEdges() {
        return hdmaClockEdges;
    }

    long stopEntryEdges() {
        return stopEntryEdges;
    }

    long switchDelayEdges() {
        return switchDelayEdges;
    }

    long timerFallingEdges() {
        return timerFallingEdges;
    }

    long apuFrameFallingEdges() {
        return apuFrameFallingEdges;
    }
}
