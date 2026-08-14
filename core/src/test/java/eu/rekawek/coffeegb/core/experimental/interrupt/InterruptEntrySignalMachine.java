package eu.rekawek.coffeegb.core.experimental.interrupt;

import java.util.ArrayList;
import java.util.List;

/**
 * Detached half-dot model of the interrupt-entry control cone.
 *
 * <p>The model deliberately separates three things that the production CPU currently performs in
 * one {@code IRQ_PUSH_2} callback: the end of the low-byte stack bus cycle, the live priority/vector
 * capture, and the selected source's IF-reset strobe. Peripheral request wires are accepted only at
 * the half-dot on which they occur; no component can inspect a future request.</p>
 *
 * <p>The acknowledge phases are current-implementation constraints, not a claim that the DMG and
 * CGB use identical gates. Relative to the production clear callback (the {@code IRQ_PUSH_2 ->
 * IRQ_JUMP} boundary), the observable races require the DMG reset strobe three CPU clocks later and
 * the CGB strobe eight clocks later. Making those placements explicit exposes an important result:
 * the CGB IF reset completes after vector capture, so vector selection and IF acknowledgement cannot
 * be represented by one atomic operation.</p>
 */
final class InterruptEntrySignalMachine {

    enum Model {
        DMG(false, false),
        CGB_NORMAL(true, false),
        CGB_DOUBLE(true, true);

        private final boolean cgb;

        private final boolean doubleSpeed;

        Model(boolean cgb, boolean doubleSpeed) {
            this.cgb = cgb;
            this.doubleSpeed = doubleSpeed;
        }

        boolean cgb() {
            return cgb;
        }

        int halfDotsPerCpuClock() {
            return doubleSpeed ? 1 : 2;
        }
    }

    enum MachineCycle {
        IRQ_WAIT_1,
        IRQ_WAIT_2,
        IRQ_PUSH_1,
        IRQ_PUSH_2,
        IRQ_JUMP,
        HANDLER_FETCH
    }

    enum TState {
        T1,
        T2,
        T3,
        T4
    }

    enum Source {
        VBLANK(0x40),
        LCDC(0x48),
        TIMER(0x50),
        SERIAL(0x58),
        JOYPAD(0x60);

        private final int vector;

        Source(int vector) {
            this.vector = vector;
        }

        int mask() {
            return 1 << ordinal();
        }

        int vector() {
            return vector;
        }
    }

    enum AddressDrive {
        FLOATING,
        STACK_HIGH,
        STACK_LOW,
        VECTOR
    }

    enum DataDrive {
        FLOATING,
        PC_HIGH,
        PC_LOW
    }

    record BusSignals(
            AddressDrive address,
            DataDrive data,
            boolean read,
            boolean write,
            boolean sampleOrCommit) {
    }

    record Observation(
            long halfDot,
            long cpuClock,
            boolean cpuClockEdge,
            MachineCycle machineCycle,
            TState tState,
            BusSignals bus,
            int requestWires,
            int interruptFlags,
            int acknowledgeWires,
            Source livePriority,
            Source vectorSource,
            boolean vectorCapture,
            boolean productionClearBoundary) {
    }

    private static final Source[] SOURCES = Source.values();

    /** IRQ_JUMP/T3: three clocks after the legacy IRQ_PUSH_2/T4 callback. */
    private static final int DMG_ACK_PHASE = 18;

    /** First handler-fetch T4: eight clocks after the legacy IRQ_PUSH_2/T4 callback. */
    private static final int CGB_ACK_PHASE = 23;

    private final Model model;

    private long halfDot = -1;

    private long cpuClock;

    private int phase;

    private int interruptFlags;

    private int interruptEnable = 0x1f;

    private int requestWires;

    private int acknowledgeWires;

    private long productionClearCpuClock = Long.MIN_VALUE;

    /** The source captured by the vector gate; this also owns a later CGB reset strobe. */
    private Source vectorSource;

    /** DMG reset precedes vector capture by one clock, so retain its selected source. */
    private Source acknowledgedSource;

    private boolean vectorCapture;

    InterruptEntrySignalMachine(Model model) {
        this.model = model;
    }

    void setInterruptEnable(int mask) {
        interruptEnable = mask & 0x1f;
    }

    void presetInterruptFlags(int mask) {
        interruptFlags = mask & 0x1f;
    }

    Observation stepHalfDot(int rawRequestWires) {
        halfDot++;
        requestWires = rawRequestWires & 0x1f;
        acknowledgeWires = 0;
        vectorCapture = false;

        // Raw peripheral wires set their IF latches immediately. A later acknowledge on this same
        // half-dot is clear-dominant, matching the detached timer and serial latch experiments.
        int nextInterruptFlags = interruptFlags | requestWires;

        boolean cpuClockEdge = halfDot % model.halfDotsPerCpuClock() == 0;
        MachineCycle cycle = machineCycle();
        TState tState = tState();
        boolean productionClearBoundary = false;

        if (cpuClockEdge) {
            cpuClock++;

            if (cycle == MachineCycle.IRQ_PUSH_2 && tState == TState.T4) {
                productionClearCpuClock = cpuClock;
                productionClearBoundary = true;
            }

            // Current behavior constrains this CPU microphase to IRQ_JUMP/T3 on DMG and the first
            // handler-fetch T4 on CGB. The peripheral is neither consulted nor asked to forecast.
            if (isAcknowledgePhase()) {
                Source owner = vectorSource != null
                        ? vectorSource
                        : highestPriority(nextInterruptFlags & interruptEnable);
                if (owner != null) {
                    acknowledgeWires = owner.mask();
                    acknowledgedSource = owner;
                    nextInterruptFlags &= ~acknowledgeWires;
                }
            }

            // The priority gate remains live through IRQ_JUMP. On DMG, the immediately preceding
            // reset strobe already captured its owner; on CGB the live gate is sampled here and
            // that selected source is carried to the later reset strobe.
            if (cycle == MachineCycle.IRQ_JUMP && tState == TState.T4) {
                vectorCapture = true;
                vectorSource = acknowledgedSource != null
                        ? acknowledgedSource
                        : highestPriority(nextInterruptFlags & interruptEnable);
            }
        }

        interruptFlags = nextInterruptFlags;
        Observation observation = new Observation(
                halfDot,
                cpuClock,
                cpuClockEdge,
                cycle,
                tState,
                busSignals(cycle, tState),
                requestWires,
                interruptFlags,
                acknowledgeWires,
                highestPriority(interruptFlags & interruptEnable),
                vectorSource,
                vectorCapture,
                productionClearBoundary);

        if (cpuClockEdge) {
            phase++;
        }
        return observation;
    }

    Observation stepCpuClock(int rawRequestWires) {
        Observation observation;
        do {
            boolean nextHalfDotIsCpuClock =
                    (halfDot + 1) % model.halfDotsPerCpuClock() == 0;
            // This convenience method denotes a request coincident with the next enabled CPU
            // clock. Normal-speed models may first traverse an idle half-dot.
            observation = stepHalfDot(nextHalfDotIsCpuClock ? rawRequestWires : 0);
        } while (!observation.cpuClockEdge());
        return observation;
    }

    List<Observation> runCpuClocks(int clocks) {
        List<Observation> trace = new ArrayList<>(clocks);
        for (int i = 0; i < clocks; i++) {
            trace.add(stepCpuClock(0));
        }
        return trace;
    }

    int interruptFlags() {
        return interruptFlags;
    }

    Source vectorSource() {
        return vectorSource;
    }

    long productionClearCpuClock() {
        return productionClearCpuClock;
    }

    private boolean isAcknowledgePhase() {
        return phase == (model.cgb() ? CGB_ACK_PHASE : DMG_ACK_PHASE);
    }

    private MachineCycle machineCycle() {
        int cycle = Math.min(phase / 4, MachineCycle.values().length - 1);
        return MachineCycle.values()[cycle];
    }

    private TState tState() {
        return TState.values()[phase & 3];
    }

    private static BusSignals busSignals(MachineCycle cycle, TState tState) {
        if (cycle == MachineCycle.IRQ_PUSH_1 || cycle == MachineCycle.IRQ_PUSH_2) {
            boolean high = cycle == MachineCycle.IRQ_PUSH_1;
            return new BusSignals(
                    high ? AddressDrive.STACK_HIGH : AddressDrive.STACK_LOW,
                    high ? DataDrive.PC_HIGH : DataDrive.PC_LOW,
                    false,
                    tState == TState.T2 || tState == TState.T3,
                    tState == TState.T4);
        }
        if (cycle == MachineCycle.HANDLER_FETCH) {
            return new BusSignals(
                    AddressDrive.VECTOR,
                    DataDrive.FLOATING,
                    tState == TState.T2 || tState == TState.T3,
                    false,
                    tState == TState.T4);
        }
        return new BusSignals(
                AddressDrive.FLOATING,
                DataDrive.FLOATING,
                false,
                false,
                false);
    }

    private static Source highestPriority(int pending) {
        for (Source source : SOURCES) {
            if ((pending & source.mask()) != 0) {
                return source;
            }
        }
        return null;
    }
}
