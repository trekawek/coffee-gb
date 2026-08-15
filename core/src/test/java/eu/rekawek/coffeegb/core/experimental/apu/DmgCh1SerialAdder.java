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
 * <p>The modeled boundary begins at synchronized {@code CH1_START}, not at the CPU's NR14 write.
 * A pinned dmg-sim trace shows that ordinary CPU writes always reach that boundary on one fixed
 * phase: inactive and active triggers both take two T from NR14 to {@code CH1_START}, and their
 * request/restart waveforms are identical. For nonzero shifts the serial-adder waveform is also
 * identical. Shift zero is the useful exception: the inactive trigger raises BYTE/LD_SUM early,
 * while an active retrigger before BEXA has no second LD_SUM edge because BYTE is still high.
 * That history dependence is produced by BYTE's retained state, not by channel-active status;
 * channel-active has no connection to DUPE, EZEC, FYFO, FEKU, FARE, or FYTE. The model can still
 * execute a deliberately late request, but that is now a counterfactual phase probe rather than
 * an explanation for production's {@code wasActive} timing branch.
 *
 * <p>Static and dynamic provenance: {@code https://github.com/msinger/dmg-sim} revision
 * {@value #NETLIST_REVISION}, {@code dmg_cpu_b/dmg_cpu_b.sv}; Icarus Verilog 14.0-devel
 * (1d2aa1b), both {@code TIMING=default} and {@code TIMING=nodelay}. The minimal generated boot
 * program issued a trigger while inactive and the same CPU-phase trigger while active for shifts
 * 0, 1, 3, and 7. These are logic-model observations, not measured silicon.
 */
final class DmgCh1SerialAdder {

    static final String NETLIST_REVISION = "ee559e1d963e1cc522df512e3bae1b4e5ff96fb5";

    enum Evidence {
        STATIC_NETLIST_HAS_NO_ACTIVE_TO_TRIGGER_PATH,
        DEFAULT_DELAY_FIXED_PHASE_RETRIGGER_TRACE,
        DEFAULT_DELAY_SHIFT_ZERO_STICKY_BYTE_TRACE,
        NODELAY_FIXED_PHASE_RETRIGGER_TRACE,
        DEFAULT_DELAY_BEXA_FEEDBACK_TRACE
    }

    enum Falsifier {
        ACTIVE_RETRIGGER_BEFORE_RESTART_PIPE_DRAINS,
        NR10_WRITE_DURING_RESTART_OR_CALCULATION,
        FREQUENCY_WRITE_DURING_RESTART_PIPELINE,
        SWEEP_TERMINAL_DURING_RESTART_PIPELINE,
        SWEEP_TERMINAL_BEFORE_PREVIOUS_SUM_SETTLES,
        RAW_NR14_WRITE_TO_CH1_START_FRONT_END,
        NATURAL_BEXA_CLOCK_ALIGNMENT,
        INTERMEDIATE_SUM_NODE_OBSERVATION,
        CGB_RESTART_PROFILE,
        SUB_T_GATE_PROPAGATION
    }

    static Set<Falsifier> profileFalsifiers() {
        return Set.of(Falsifier.RAW_NR14_WRITE_TO_CH1_START_FRONT_END,
                Falsifier.NATURAL_BEXA_CLOCK_ALIGNMENT,
                Falsifier.INTERMEDIATE_SUM_NODE_OBSERVATION,
                Falsifier.CGB_RESTART_PROFILE, Falsifier.SUB_T_GATE_PROPAGATION);
    }

    static Set<Evidence> evidence() {
        return Set.copyOf(EnumSet.allOf(Evidence.class));
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
                    false, false, false, false, 7, false, false,
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
        // BYTE samples the terminal detector on every AJER edge. Once the counter is terminal,
        // COPY remains high and therefore BYTE/LD_SUM remains high until KALA loads a nonterminal
        // shift count or BEXA asserts BYTE's asynchronous reset. This is observable on a
        // shift-zero active retrigger: KALA loads seven again, so there is no second LD_SUM edge.
        if (ajer2MhzRise && !old.sweepTerminal() && !signals.bexa()) {
            boolean sampledTerminal = old.terminalPending()
                    || (old.loadSum() && old.shiftCounter() == 7);
            loadSum = sampledTerminal;
            loadSumRise = sampledTerminal && !old.loadSum();
            if (sampledTerminal) {
                terminalPending = false;
                calculationLatch = false;
                if (loadSumRise && old.overflowCheckEnabled()) {
                    Calculation calculation = calculate(old.calculationOperand(),
                            old.calculationShift(), old.calculationNegate());
                    sumValid = true;
                    sumResult = calculation.result();
                    sumOverflow = calculation.overflow();
                    overflow |= calculation.overflow();
                    negateUsed |= old.calculationNegate();
                    overflowCheckPulse = true;
                } else if (loadSumRise) {
                    sumValid = false;
                    sumOverflow = false;
                }
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
                // KALA itself presents terminal count 7 for shift zero. BYTE captures it on the
                // next AJER edge if it was low. If it was already high, no new edge occurs.
                terminalPending = shift(nr10) == 0;
                calculationShift = shift(nr10);
                calculationNegate = negate(nr10);
                overflowCheckEnabled = false;
            }
            if (restartDelayedRise) {
                if (calculationShift != 0) {
                    calculationLatch = true;
                    calculationOperand = triggerShadow;
                    overflowCheckEnabled = true;
                } else {
                    // The zero-shift BYTE pulse already happened at KALA; its asserted LD_SUM
                    // reset keeps FYTE from reopening FEMU.
                    calculationLatch = false;
                    terminalPending = false;
                }
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
