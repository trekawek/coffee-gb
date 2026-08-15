package eu.rekawek.coffeegb.core.experimental.interrupt;

import java.util.ArrayList;
import java.util.List;

/**
 * Detached half-dot model of the interrupt-entry control cone.
 *
 * <p>The model deliberately separates things that the production CPU currently performs in one
 * {@code IRQ_PUSH_2} callback: the end of the low-byte stack bus cycle, the phase-transparent
 * {@code IE & IF} pending bank, vector resolution, and the selected source's IF-reset strobe.
 * Peripheral request wires are accepted only at the half-dot on which they occur; no component can
 * inspect a future request.</p>
 *
 * <p>On DMG, the sampled-pending bank and local clear-dominant IF latches are derived from the pinned
 * external gate model. The bank is transparent while {@code data_phase} is low in T1/T2, closes
 * when that phase rises for T3, and drives both acknowledge and vector during the T4
 * {@code write_phase/clk_t4} evaluation. The CGB phase placements remain behavioral constraints,
 * not a claim that CGB uses the same gates. In both cases, the held pending bits are the common
 * input to the one-hot acknowledge and vector paths; no semantic "late priority" repair is
 * needed.</p>
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
            boolean dataPhase,
            boolean writePhase,
            BusSignals bus,
            int requestWires,
            int interruptFlags,
            int sampledPending,
            boolean pendingBankTransparent,
            int acknowledgeWires,
            Source sampledPriority,
            Source vectorSource,
            boolean vectorResolved,
            boolean productionClearBoundary) {
    }

    private static final Source[] SOURCES = Source.values();

    private final Model model;

    private long halfDot = -1;

    private long cpuClock;

    private int phase;

    private int interruptFlags;

    private int interruptEnable = 0x1f;

    private int requestWires;

    /** Active-high {@code IE & IF} bits held when the CPU data phase closes. */
    private int sampledPending;

    private int acknowledgeWires;

    private boolean acknowledgeIssued;

    private long productionClearCpuClock = Long.MIN_VALUE;

    /** The source last resolved by the vector gate. */
    private Source vectorSource;

    private boolean vectorResolved;

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
        return stepHalfDot(rawRequestWires, false, 0);
    }

    Observation stepHalfDot(int rawRequestWires, boolean cpuIfWrite, int cpuIfData) {
        halfDot++;
        requestWires = rawRequestWires & 0x1f;
        acknowledgeWires = 0;
        vectorResolved = false;

        // Each physical IF bit is a local set/reset latch. A CPU FF0F write drives its set/reset
        // inputs directly; an acknowledge below remains clear-dominant over either write-data=1 or
        // a raw request on the same modeled instant.
        int nextInterruptFlags = interruptFlags | requestWires;
        if (cpuIfWrite) {
            nextInterruptFlags = cpuIfData & 0x1f;
        }

        boolean cpuClockEdge = halfDot % model.halfDotsPerCpuClock() == 0;
        MachineCycle cycle = machineCycle();
        TState tState = tState();
        boolean dataPhase = tState == TState.T3 || tState == TState.T4;
        boolean writePhase = tState == TState.T4;
        boolean pendingBankTransparent = isPendingBankTransparent(cycle, tState);
        boolean productionClearBoundary = false;

        if (pendingBankTransparent) {
            sampledPending = nextInterruptFlags & interruptEnable;
        }

        if (cpuClockEdge) {
            cpuClock++;

            if (cycle == MachineCycle.IRQ_PUSH_2 && tState == TState.T4) {
                productionClearCpuClock = cpuClock;
                productionClearBoundary = true;
            }

            // The one-hot acknowledge is decoded from the held pending bank. The peripheral is
            // neither consulted nor asked to forecast a later request.
            if (!acknowledgeIssued && isAcknowledgePhase(cycle, tState)) {
                acknowledgeIssued = true;
                Source owner = highestPriority(sampledPending);
                if (owner != null) {
                    acknowledgeWires = owner.mask();
                    nextInterruptFlags &= ~acknowledgeWires;
                }
            }

            // The vector network resolves from the same held bank as acknowledge. The external
            // DMG model has no second source-capture latch here: the PC consumes this combinational
            // result at the following cycle boundary.
            if (isVectorResolvePhase(cycle, tState)) {
                vectorResolved = true;
                vectorSource = highestPriority(sampledPending);
            }
        }

        interruptFlags = nextInterruptFlags;
        Observation observation = new Observation(
                halfDot,
                cpuClock,
                cpuClockEdge,
                cycle,
                tState,
                dataPhase,
                writePhase,
                busSignals(cycle, tState),
                requestWires,
                interruptFlags,
                sampledPending,
                pendingBankTransparent,
                acknowledgeWires,
                highestPriority(sampledPending),
                vectorSource,
                vectorResolved,
                productionClearBoundary);

        if (cpuClockEdge) {
            phase++;
        }
        return observation;
    }

    Observation stepCpuClock(int rawRequestWires) {
        return stepCpuClock(rawRequestWires, false, 0);
    }

    Observation stepCpuClock(int rawRequestWires, boolean cpuIfWrite, int cpuIfData) {
        Observation observation;
        do {
            boolean nextHalfDotIsCpuClock =
                    (halfDot + 1) % model.halfDotsPerCpuClock() == 0;
            // This convenience method denotes a request coincident with the next enabled CPU
            // clock. Normal-speed models may first traverse an idle half-dot.
            observation = stepHalfDot(
                    nextHalfDotIsCpuClock ? rawRequestWires : 0,
                    nextHalfDotIsCpuClock && cpuIfWrite,
                    cpuIfData);
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

    private boolean isPendingBankTransparent(MachineCycle cycle, TState tState) {
        if (model.cgb()) {
            // Fitted only: the CGB legacy collision window constrains the held owner after vector
            // resolution, but this experiment has no CGB gate-level phase oracle.
            return cycle.ordinal() <= MachineCycle.IRQ_JUMP.ordinal();
        }
        // In the DMG model data_phase is low during T1/T2 and high during T3/T4. The latch opens on
        // the low level in every entry cycle, then its final M6 value is held after T2.
        return cycle.ordinal() <= MachineCycle.IRQ_JUMP.ordinal()
                && (tState == TState.T1 || tState == TState.T2);
    }

    private boolean isAcknowledgePhase(MachineCycle cycle, TState tState) {
        return model.cgb()
                ? cycle == MachineCycle.HANDLER_FETCH && tState == TState.T4
                : cycle == MachineCycle.IRQ_JUMP && tState == TState.T4;
    }

    private static boolean isVectorResolvePhase(MachineCycle cycle, TState tState) {
        return cycle == MachineCycle.IRQ_JUMP && tState == TState.T4;
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
