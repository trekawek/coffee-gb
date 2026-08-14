package eu.rekawek.coffeegb.core.experimental.apu;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detached signal model of the DMG channel-1 sweep restart and adder cone.
 *
 * <p>The cells are named after the generated DMG-CPU-B netlist:
 *
 * <ul>
 *   <li>{@code FYFO -> FEKU -> FARE -> FYTE} turns the NR14 start request into one
 *       {@code CH1_RESTART} pulse and an equally wide pulse delayed by two 1 MHz clocks.</li>
 *   <li>{@code KALA} parallel-loads the three shift TFFs with {@code 7 - shift}.</li>
 *   <li>{@code FEMU} retains a calculation request from {@code BEXA} or
 *       {@code CH1_RESTART_DLY} until {@code BYTE} raises {@code CH1_LD_SUM}.</li>
 *   <li>{@code BUSO/BOJE} use the old settled sum on the BEXA edge. The same edge then opens
 *       FEMU again, so the written-back frequency becomes the operand of the overflow check.</li>
 * </ul>
 *
 * <p>The model consumes clock <em>levels</em>, not a number of scheduler ticks. Consequently a
 * request which reaches FEKU just before a 1 MHz edge and one which reaches it just after that
 * edge naturally differ by four T-cycles; there is no {@code wasActive} timing input.
 */
final class DmgCh1SerialAdder {

    enum Falsifier {
        ACTIVE_RETRIGGER_BEFORE_RESTART_PIPE_DRAINS,
        NR10_WRITE_DURING_RESTART_OR_CALCULATION,
        FREQUENCY_WRITE_DURING_RESTART_PIPELINE,
        SWEEP_TERMINAL_DURING_RESTART_PIPELINE,
        SWEEP_TERMINAL_BEFORE_PREVIOUS_SUM_SETTLES,
        UPSTREAM_CH1_START_APERTURE_MAPPING,
        INTERMEDIATE_SUM_NODE_OBSERVATION,
        CGB_RESTART_PROFILE,
        SUB_T_GATE_PROPAGATION
    }

    static Set<Falsifier> profileFalsifiers() {
        return Set.of(Falsifier.UPSTREAM_CH1_START_APERTURE_MAPPING,
                Falsifier.INTERMEDIATE_SUM_NODE_OBSERVATION,
                Falsifier.CGB_RESTART_PROFILE, Falsifier.SUB_T_GATE_PROPAGATION);
    }

    record State(
            int nr10,
            int frequency,
            int triggerShadow,
            boolean startRequest,
            boolean restart,
            boolean restartStage,
            boolean restartDelayed,
            boolean calculationLatch,
            int shiftCounter,
            boolean loadSum,
            boolean terminalPending,
            int calculationOperand,
            int calculationShift,
            boolean calculationNegate,
            boolean overflowCheckEnabled,
            boolean sumValid,
            int sumResult,
            boolean sumOverflow,
            boolean overflow,
            boolean negateUsed,
            boolean oneMhz,
            boolean ajer2Mhz,
            boolean sweepTerminal) {

        State {
            nr10 &= 0x7f;
            frequency &= 0x7ff;
            triggerShadow &= 0x7ff;
            shiftCounter &= 0x07;
            calculationOperand &= 0x7ff;
            calculationShift &= 0x07;
        }

        static State initial(int nr10, int frequency, boolean oneMhz, boolean ajer2Mhz) {
            return new State(nr10, frequency, frequency, false,
                    false, false, false, false, 7, true, false,
                    frequency, 0, false, false,
                    false, 0, false, false, false,
                    oneMhz, ajer2Mhz, false);
        }

        boolean restartPipelineBusy() {
            return startRequest || restart || restartStage || restartDelayed;
        }
    }

    record Signals(
            boolean apuReset,
            boolean nr10Write,
            int nr10Data,
            boolean frequencyWrite,
            int frequencyData,
            boolean ch1Start,
            boolean bexa,
            boolean oneMhz,
            boolean ajer2Mhz) {

        Signals {
            nr10Data &= 0xff;
            frequencyData &= 0x7ff;
        }

        static Signals clocks(boolean oneMhz, boolean ajer2Mhz) {
            return new Signals(false, false, 0, false, 0,
                    false, false, oneMhz, ajer2Mhz);
        }

        Signals withStart() {
            return new Signals(apuReset, nr10Write, nr10Data, frequencyWrite, frequencyData,
                    true, bexa, oneMhz, ajer2Mhz);
        }

        Signals withBexa() {
            return new Signals(apuReset, nr10Write, nr10Data, frequencyWrite, frequencyData,
                    ch1Start, true, oneMhz, ajer2Mhz);
        }

        Signals withNr10(int value) {
            return new Signals(apuReset, true, value, frequencyWrite, frequencyData,
                    ch1Start, bexa, oneMhz, ajer2Mhz);
        }

        Signals withFrequency(int value) {
            return new Signals(apuReset, nr10Write, nr10Data, true, value,
                    ch1Start, bexa, oneMhz, ajer2Mhz);
        }
    }

    record Resolution(
            State next,
            boolean restartRise,
            boolean restartDelayedRise,
            boolean shiftClockRise,
            boolean loadSumRise,
            boolean frequencyUpdatePulse,
            boolean overflowCheckPulse,
            Set<Falsifier> falsifiers) {

        Resolution {
            falsifiers = Set.copyOf(falsifiers);
        }
    }

    /** Resolve all combinational gates from the old cells, then commit the clocked cells once. */
    static Resolution resolve(State old, Signals signals) {
        if (signals.apuReset()) {
            return new Resolution(
                    State.initial(0, 0, signals.oneMhz(), signals.ajer2Mhz()),
                    false, false, false, false, false, false, Set.of());
        }

        EnumSet<Falsifier> falsifiers = EnumSet.noneOf(Falsifier.class);
        boolean restartWasBusy = old.restartPipelineBusy();
        if (signals.ch1Start() && restartWasBusy) {
            falsifiers.add(Falsifier.ACTIVE_RETRIGGER_BEFORE_RESTART_PIPE_DRAINS);
        }
        if (signals.nr10Write() && (restartWasBusy
                || old.calculationLatch() || old.terminalPending())) {
            falsifiers.add(Falsifier.NR10_WRITE_DURING_RESTART_OR_CALCULATION);
        }
        if (signals.frequencyWrite() && restartWasBusy) {
            falsifiers.add(Falsifier.FREQUENCY_WRITE_DURING_RESTART_PIPELINE);
        }

        int nr10 = signals.nr10Write() ? signals.nr10Data() : old.nr10();
        int frequency = signals.frequencyWrite() ? signals.frequencyData() : old.frequency();
        int triggerShadow = old.triggerShadow();
        boolean startRequest = old.startRequest();
        boolean restart = old.restart();
        boolean restartStage = old.restartStage();
        boolean restartDelayed = old.restartDelayed();
        boolean calculationLatch = old.calculationLatch();
        int shiftCounter = old.shiftCounter();
        boolean loadSum = old.loadSum();
        boolean terminalPending = old.terminalPending();
        int calculationOperand = old.calculationOperand();
        int calculationShift = old.calculationShift();
        boolean calculationNegate = old.calculationNegate();
        boolean overflowCheckEnabled = old.overflowCheckEnabled();
        boolean sumValid = old.sumValid();
        int sumResult = old.sumResult();
        boolean sumOverflow = old.sumOverflow();
        boolean overflow = old.overflow();
        boolean negateUsed = old.negateUsed();

        boolean oneMhzRise = !old.oneMhz() && signals.oneMhz();
        boolean oneMhzFall = old.oneMhz() && !signals.oneMhz();
        boolean ajer2MhzRise = !old.ajer2Mhz() && signals.ajer2Mhz();
        boolean bexaRise = !old.sweepTerminal() && signals.bexa();
        boolean priorSumValid = old.sumValid();
        int priorSumResult = old.sumResult();
        boolean priorSumOverflow = old.sumOverflow();

        boolean loadSumRise = false;
        boolean overflowCheckPulse = false;
        // BYTE samples the terminal detector on AJER. If BEXA falls on that same edge its
        // asynchronous reset was still asserted, so the following AJER edge performs the load.
        if (ajer2MhzRise && old.terminalPending()
                && !old.sweepTerminal() && !signals.bexa()) {
            loadSum = true;
            loadSumRise = !old.loadSum();
            terminalPending = false;
            calculationLatch = false;
            if (old.overflowCheckEnabled()) {
                Calculation calculation = calculate(
                        old.calculationOperand(), old.calculationShift(), old.calculationNegate());
                sumValid = true;
                sumResult = calculation.result();
                sumOverflow = calculation.overflow();
                overflow |= calculation.overflow();
                negateUsed |= old.calculationNegate();
                overflowCheckPulse = true;
            } else {
                sumValid = false;
                sumOverflow = false;
            }
        }

        if (signals.ch1Start()) {
            startRequest = true;
            triggerShadow = frequency;
            // The channel-status set input wins now; a later adder carry can reset it again.
            overflow = false;
            negateUsed = false;
            sumValid = false;
            sumOverflow = false;
        }

        boolean frequencyUpdatePulse = false;
        if (bexaRise) {
            if (restartWasBusy) {
                falsifiers.add(Falsifier.SWEEP_TERMINAL_DURING_RESTART_PIPELINE);
            }
            if (old.calculationLatch() || old.terminalPending()) {
                falsifiers.add(Falsifier.SWEEP_TERMINAL_BEFORE_PREVIOUS_SUM_SETTLES);
            }
            // BUSO/BOJE see the sum which settled before this edge.
            if (priorSumValid && !priorSumOverflow && shift(nr10) != 0) {
                frequency = priorSumResult;
                frequencyUpdatePulse = true;
            }

            shiftCounter = 7 - shift(nr10);
            loadSum = false;
            terminalPending = shift(nr10) == 0;
            calculationLatch = true;
            calculationOperand = frequency;
            calculationShift = shift(nr10);
            calculationNegate = negate(nr10);
            overflowCheckEnabled = true;
            sumValid = false;
        }

        boolean restartRise = false;
        boolean restartDelayedRise = false;
        if (oneMhzRise) {
            boolean sampledRestart = startRequest;
            boolean sampledStage = old.restart();
            boolean sampledDelayed = old.restartStage();

            restart = sampledRestart;
            restartStage = sampledStage;
            restartDelayed = sampledDelayed;
            // FARE feeds EGET/GEFE: as soon as stage 1 rises it clears FEKU and FYFO.
            if (sampledStage) {
                restart = false;
                startRequest = false;
            }

            restartRise = restart && !old.restart();
            restartDelayedRise = restartDelayed && !old.restartDelayed();
            if (restartRise) {
                shiftCounter = 7 - shift(nr10);
                loadSum = false;
                terminalPending = false;
                calculationShift = shift(nr10);
                calculationNegate = negate(nr10);
            }
            if (restartDelayedRise) {
                calculationLatch = true;
                calculationOperand = triggerShadow;
                // BU GE / BUSO disconnect a zero shift from the trigger-time overflow path.
                overflowCheckEnabled = calculationShift != 0;
                terminalPending = calculationShift == 0;
            }
        }

        boolean shiftClockRise = false;
        if (oneMhzFall && calculationLatch && calculationShift != 0) {
            shiftCounter = (shiftCounter + 1) & 0x07;
            shiftClockRise = true;
            if (shiftCounter == 7) {
                terminalPending = true;
            }
        }

        State next = new State(nr10, frequency, triggerShadow, startRequest,
                restart, restartStage, restartDelayed, calculationLatch,
                shiftCounter, loadSum, terminalPending,
                calculationOperand, calculationShift, calculationNegate,
                overflowCheckEnabled, sumValid, sumResult, sumOverflow,
                overflow, negateUsed,
                signals.oneMhz(), signals.ajer2Mhz(), signals.bexa());
        return new Resolution(next, restartRise, restartDelayedRise, shiftClockRise,
                loadSumRise, frequencyUpdatePulse, overflowCheckPulse, falsifiers);
    }

    private record Calculation(int result, boolean overflow) {}

    private static Calculation calculate(int operand, int shift, boolean negate) {
        int delta = operand >>> shift;
        int result = negate ? operand - delta : operand + delta;
        return new Calculation(result, result > 0x7ff);
    }

    private static int shift(int nr10) {
        return nr10 & 0x07;
    }

    private static boolean negate(int nr10) {
        return (nr10 & 0x08) != 0;
    }

    private DmgCh1SerialAdder() {
    }
}
