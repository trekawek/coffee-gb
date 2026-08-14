package eu.rekawek.coffeegb.core.experimental.cpu;

import eu.rekawek.coffeegb.core.signal.Dff;
import eu.rekawek.coffeegb.core.signal.SrLatch;

import java.util.EnumSet;
import java.util.Set;

import static eu.rekawek.coffeegb.core.signal.SrLatch.Dominance.CLEAR;

/**
 * Detached resolve/commit model of the DMG CPU's interrupt, IME, and HALT control cone.
 *
 * <p>The names and cut points come from the DMG SM83 netlist rather than from Coffee GB's
 * behavioral state. {@code irq_latch[0..7]} samples {@code IE & INT} while the data-phase gate is
 * open; {@code int_pending} is its reduction; YOII clocks that level before it reaches the
 * reset-dominant {@code halt} SR latch. The direct HALT decode is absent from decoder2's
 * {@code ctl_idu_inc} product sum (where NOP/STOP is present), and is separately clocked into
 * {@code ctl_op_halt_delayed}. That topology can create the HALT bug as a short decode/IDU
 * conflict rather than a mode. IME is likewise a control latch followed by an exec-phase
 * observation point ({@code ime_state -> ime_n}). There is no per-request "halt blocked" or
 * "instruction blocked" provenance in this cone.</p>
 *
 * <p>This model retains those independently clocked nodes but deliberately does not reproduce the
 * transistor netlist. Its purpose is to test whether the behavioral corner cases can be expressed
 * as ordinary values at those nodes. All next-state wires are derived from one immutable committed
 * snapshot before any primitive is resolved, so reversing Java evaluation and commit order cannot
 * alter a result.</p>
 */
final class DmgCpuControlLatchIsland {

    static final int IF = 0xff0f;

    static final int IE = 0xffff;

    static final int INTERRUPT_MASK = 0x1f;

    enum Control {
        NONE,
        DI,
        EI,
        RETI,
        HALT
    }

    enum EvaluationOrder {
        FORWARD,
        REVERSE
    }

    /** Boundaries that this deliberately small DMG cone does not claim to solve. */
    enum Falsifier {
        /** The exact source-set versus CPU FF0F-write aperture is outside the SM83 core. */
        SOURCE_SET_VS_FF0F_WRITE_APERTURE,
        /** STAT/VBlank reach the CPU through source-specific gates that belong to the PPU cone. */
        PPU_SOURCE_INPUT_PHASES,
        /** The calibrated early IE-write forecast cannot move until the CPU bus is phase-correct. */
        EARLY_IE_WRITE_REQUIRES_BUS_REANCHOR,
        /** Priority capture and the delayed IF reset strobe belong to the interrupt-entry machine. */
        VECTOR_AND_ACKNOWLEDGE_PHASES,
        /** The exact delta path from HALT decode to the one-fetch PC gate still needs a gate trace. */
        HALT_PC_GATE_DELTA_PATH,
        /** CGB's direct PPU interrupt path is not the DMG SM83 topology modeled here. */
        CGB_DIRECT_INTERRUPT_PATH
    }

    record BusWrite(boolean active, int address, int value) {

        BusWrite {
            if (active && (address & ~0xffff) != 0) {
                throw new IllegalArgumentException("address outside 16-bit bus");
            }
            if (active && (value & ~0xff) != 0) {
                throw new IllegalArgumentException("value outside 8-bit bus");
            }
        }

        static BusWrite none() {
            return new BusWrite(false, 0, 0);
        }

        static BusWrite to(int address, int value) {
            return new BusWrite(true, address, value);
        }
    }

    record Inputs(
            int rawRequests,
            int acknowledge,
            BusWrite busWrite,
            boolean dataSampleEdge,
            boolean cpuClockEdge,
            boolean instructionBoundary,
            Control control,
            boolean interruptEntry,
            boolean opcodeFetchStrobe) {

        Inputs {
            if ((rawRequests & ~INTERRUPT_MASK) != 0) {
                throw new IllegalArgumentException("raw interrupt request outside IF");
            }
            if ((acknowledge & ~INTERRUPT_MASK) != 0
                    || Integer.bitCount(acknowledge) > 1) {
                throw new IllegalArgumentException("acknowledge must be one-hot");
            }
            if (busWrite == null || control == null) {
                throw new NullPointerException();
            }
            if (control != Control.NONE && !instructionBoundary) {
                throw new IllegalArgumentException("decoded controls retire at a boundary");
            }
        }

        static Inputs idle() {
            return new Inputs(0, 0, BusWrite.none(), false, false,
                    false, Control.NONE, false, false);
        }

        static Inputs boundary(Control control) {
            return idle().atDataSample().atCpuClock().retiring(control);
        }

        Inputs withRawRequests(int mask) {
            return new Inputs(mask, acknowledge, busWrite, dataSampleEdge, cpuClockEdge,
                    instructionBoundary, control, interruptEntry, opcodeFetchStrobe);
        }

        Inputs withAcknowledge(int mask) {
            return new Inputs(rawRequests, mask, busWrite, dataSampleEdge, cpuClockEdge,
                    instructionBoundary, control, interruptEntry, opcodeFetchStrobe);
        }

        Inputs withBusWrite(BusWrite write) {
            return new Inputs(rawRequests, acknowledge, write, dataSampleEdge, cpuClockEdge,
                    instructionBoundary, control, interruptEntry, opcodeFetchStrobe);
        }

        Inputs atDataSample() {
            return new Inputs(rawRequests, acknowledge, busWrite, true, cpuClockEdge,
                    instructionBoundary, control, interruptEntry, opcodeFetchStrobe);
        }

        Inputs atCpuClock() {
            return new Inputs(rawRequests, acknowledge, busWrite, dataSampleEdge, true,
                    instructionBoundary, control, interruptEntry, opcodeFetchStrobe);
        }

        Inputs retiring(Control retiredControl) {
            return new Inputs(rawRequests, acknowledge, busWrite, dataSampleEdge, cpuClockEdge,
                    true, retiredControl, interruptEntry, opcodeFetchStrobe);
        }

        Inputs enteringInterrupt() {
            return new Inputs(rawRequests, acknowledge, busWrite, dataSampleEdge, cpuClockEdge,
                    instructionBoundary, control, true, opcodeFetchStrobe);
        }

        Inputs withOpcodeFetch() {
            return new Inputs(rawRequests, acknowledge, busWrite, dataSampleEdge, cpuClockEdge,
                    instructionBoundary, control, interruptEntry, true);
        }
    }

    record Observation(
            int readableIf,
            int interruptEnable,
            int runningPending,
            boolean haltWakePending,
            boolean ime,
            boolean eiPending,
            boolean halted,
            boolean haltDecodeHeld,
            boolean dispatchRequest,
            boolean instructionRegisterLoad,
            boolean pcIncrement) {
    }

    record State(
            int interruptFlags,
            int interruptEnable,
            int runningPending,
            boolean haltWakePending,
            boolean ime,
            boolean eiPending,
            boolean haltDecodeHeld,
            boolean halted) {

        State {
            if (((interruptFlags | interruptEnable | runningPending) & ~INTERRUPT_MASK) != 0) {
                throw new IllegalArgumentException("interrupt state outside five DMG sources");
            }
        }
    }

    private final SrLatch[] interruptFlags = new SrLatch[5];

    /** SM83 {@code irq_latch}: the data-phase sample of IE & the externally stored IF bits. */
    private final Dff[] runningPending = new Dff[5];

    /** SM83 YOII: one clock farther from readable IF on the path that resets HALT. */
    private final Dff haltWakePending = new Dff(CLEAR, false);

    /** The delayed EI control node; actual IME consumes it at the following boundary. */
    private final Dff eiPending = new Dff(CLEAR, false);

    private final SrLatch ime = new SrLatch(CLEAR, false);

    /**
     * Coarse phase-held image of the direct-HALT-decode / {@code ctl_op_halt_delayed} cut.
     * The exact sub-clock delta path is intentionally retained as a falsifier.
     */
    private final Dff haltDecodeHeld = new Dff(CLEAR, false);

    /** SM83 {@code halt}; reset wins if its delayed pending input overlaps the set input. */
    private final SrLatch halted = new SrLatch(CLEAR, false);

    private int interruptEnable;

    private int nextInterruptEnable;

    private Observation resolvedObservation;

    private boolean resolutionPending;

    DmgCpuControlLatchIsland() {
        for (int i = 0; i < interruptFlags.length; i++) {
            interruptFlags[i] = new SrLatch(CLEAR, false);
            runningPending[i] = new Dff(CLEAR, false);
        }
    }

    Observation step(Inputs inputs) {
        return step(inputs, EvaluationOrder.FORWARD);
    }

    Observation step(Inputs inputs, EvaluationOrder order) {
        resolve(inputs, order);
        Observation observation = resolvedObservation;
        commit(order);
        return observation;
    }

    void resolve(Inputs inputs, EvaluationOrder order) {
        if (resolutionPending) {
            throw new IllegalStateException("previous resolution has not committed");
        }

        boolean writesIf = inputs.busWrite().active() && inputs.busWrite().address() == IF;
        boolean writesIe = inputs.busWrite().active() && inputs.busWrite().address() == IE;
        nextInterruptEnable = writesIe
                ? inputs.busWrite().value() & INTERRUPT_MASK
                : interruptEnable;

        boolean[] ifSet = new boolean[5];
        boolean[] ifClear = new boolean[5];
        boolean[] pendingInput = new boolean[5];
        int resolvedIf = 0;
        for (int bit = 0; bit < 5; bit++) {
            int mask = 1 << bit;
            ifSet[bit] = (inputs.rawRequests() & mask) != 0
                    || writesIf && (inputs.busWrite().value() & mask) != 0;
            ifClear[bit] = (inputs.acknowledge() & mask) != 0
                    || writesIf && (inputs.busWrite().value() & mask) == 0;
            boolean nextIf = resolveClearDominant(
                    interruptFlags[bit].q(), ifSet[bit], ifClear[bit]);
            if (nextIf) {
                resolvedIf |= mask;
            }
            pendingInput[bit] = nextIf && (nextInterruptEnable & mask) != 0;
        }

        int currentRunningPending = runningPendingMask();
        int nextRunningPending = inputs.dataSampleEdge()
                ? mask(pendingInput)
                : currentRunningPending;
        boolean nextHaltWake = inputs.cpuClockEdge()
                ? currentRunningPending != 0
                : haltWakePending.q();

        boolean consumesEi = inputs.instructionBoundary() && eiPending.q();
        boolean controlClear = inputs.control() == Control.DI || inputs.interruptEntry();
        boolean controlSet = inputs.control() == Control.RETI || consumesEi;
        boolean nextIme = resolveClearDominant(ime.q(), controlSet, controlClear);
        boolean nextEiPending = inputs.instructionBoundary()
                ? inputs.control() == Control.EI && !consumesEi
                : eiPending.q();
        if (inputs.control() == Control.DI
                || inputs.control() == Control.RETI
                || inputs.interruptEntry()) {
            nextEiPending = false;
        }

        boolean nextHaltDecode = inputs.cpuClockEdge()
                ? inputs.control() == Control.HALT
                : haltDecodeHeld.q();
        boolean nextHalted = resolveClearDominant(
                halted.q(), nextHaltDecode, nextHaltWake || inputs.interruptEntry());

        // The candidate bus gate observes a held HALT-decode node, not a semantic "halt bug" flag.
        // Decoder2's IDU-increment equation supports this shape by omitting direct HALT decode.
        // A pending request can prevent the HALT latch from setting while that node still blocks
        // precisely one PC increment. Its exact delta timing remains a falsifier; if HALT really
        // latched, IR load is closed instead.
        boolean dispatch = nextIme && nextRunningPending != 0 && !nextHalted;
        boolean instructionLoad = inputs.opcodeFetchStrobe() && !nextHalted && !dispatch;
        boolean incrementPc = instructionLoad && !haltDecodeHeld.q();

        resolveBits(order, ifSet, ifClear, pendingInput, inputs.dataSampleEdge());
        haltWakePending.resolve(currentRunningPending != 0, inputs.cpuClockEdge(), false, false);
        eiPending.resolve(nextEiPending, true, false, false);
        ime.resolve(controlSet, controlClear);
        haltDecodeHeld.resolve(inputs.control() == Control.HALT,
                inputs.cpuClockEdge(), false, false);
        halted.resolve(nextHaltDecode, nextHaltWake || inputs.interruptEntry());

        resolvedObservation = new Observation(
                0xe0 | resolvedIf,
                nextInterruptEnable,
                nextRunningPending,
                nextHaltWake,
                nextIme,
                nextEiPending,
                nextHalted,
                nextHaltDecode,
                dispatch,
                instructionLoad,
                incrementPc);
        resolutionPending = true;
    }

    void commit(EvaluationOrder order) {
        if (!resolutionPending) {
            throw new IllegalStateException("resolve before commit");
        }
        if (order == EvaluationOrder.FORWARD) {
            commitBitsForward();
            haltWakePending.commit();
            eiPending.commit();
            ime.commit();
            haltDecodeHeld.commit();
            halted.commit();
        } else {
            halted.commit();
            haltDecodeHeld.commit();
            ime.commit();
            eiPending.commit();
            haltWakePending.commit();
            commitBitsReverse();
        }
        interruptEnable = nextInterruptEnable;
        resolutionPending = false;
    }

    Observation observation() {
        int flags = interruptFlagMask();
        int pending = runningPendingMask();
        boolean dispatch = ime.q() && pending != 0 && !halted.q();
        return new Observation(
                0xe0 | flags,
                interruptEnable,
                pending,
                haltWakePending.q(),
                ime.q(),
                eiPending.q(),
                halted.q(),
                haltDecodeHeld.q(),
                dispatch,
                false,
                false);
    }

    State capture() {
        if (resolutionPending) {
            throw new IllegalStateException("capture only at a committed boundary");
        }
        return new State(
                interruptFlagMask(),
                interruptEnable,
                runningPendingMask(),
                haltWakePending.q(),
                ime.q(),
                eiPending.q(),
                haltDecodeHeld.q(),
                halted.q());
    }

    void restore(State state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (resolutionPending) {
            throw new IllegalStateException("restore only at a committed boundary");
        }
        for (int bit = 0; bit < 5; bit++) {
            interruptFlags[bit].restore((state.interruptFlags() & (1 << bit)) != 0);
            runningPending[bit].restore((state.runningPending() & (1 << bit)) != 0);
        }
        interruptEnable = state.interruptEnable();
        nextInterruptEnable = interruptEnable;
        haltWakePending.restore(state.haltWakePending());
        ime.restore(state.ime());
        eiPending.restore(state.eiPending());
        haltDecodeHeld.restore(state.haltDecodeHeld());
        halted.restore(state.halted());
        resolvedObservation = null;
    }

    Set<Falsifier> falsifiers() {
        return EnumSet.allOf(Falsifier.class);
    }

    private void resolveBits(
            EvaluationOrder order,
            boolean[] ifSet,
            boolean[] ifClear,
            boolean[] pendingInput,
            boolean dataSampleEdge) {
        if (order == EvaluationOrder.FORWARD) {
            for (int bit = 0; bit < 5; bit++) {
                interruptFlags[bit].resolve(ifSet[bit], ifClear[bit]);
                runningPending[bit].resolve(
                        pendingInput[bit], dataSampleEdge, false, false);
            }
        } else {
            for (int bit = 4; bit >= 0; bit--) {
                runningPending[bit].resolve(
                        pendingInput[bit], dataSampleEdge, false, false);
                interruptFlags[bit].resolve(ifSet[bit], ifClear[bit]);
            }
        }
    }

    private void commitBitsForward() {
        for (int bit = 0; bit < 5; bit++) {
            interruptFlags[bit].commit();
            runningPending[bit].commit();
        }
    }

    private void commitBitsReverse() {
        for (int bit = 4; bit >= 0; bit--) {
            runningPending[bit].commit();
            interruptFlags[bit].commit();
        }
    }

    private int interruptFlagMask() {
        int result = 0;
        for (int bit = 0; bit < 5; bit++) {
            if (interruptFlags[bit].q()) {
                result |= 1 << bit;
            }
        }
        return result;
    }

    private int runningPendingMask() {
        int result = 0;
        for (int bit = 0; bit < 5; bit++) {
            if (runningPending[bit].q()) {
                result |= 1 << bit;
            }
        }
        return result;
    }

    private static int mask(boolean[] bits) {
        int result = 0;
        for (int bit = 0; bit < bits.length; bit++) {
            if (bits[bit]) {
                result |= 1 << bit;
            }
        }
        return result;
    }

    private static boolean resolveClearDominant(boolean old, boolean set, boolean clear) {
        return clear ? false : set || old;
    }
}
