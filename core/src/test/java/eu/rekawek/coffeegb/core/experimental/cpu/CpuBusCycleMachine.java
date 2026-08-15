package eu.rekawek.coffeegb.core.experimental.cpu;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Detached CPU bus experiment at the 8.388608 MHz half-dot lattice.
 *
 * <p>The production CPU stores a decoded operation and executes its entire bus access from one
 * machine-cycle callback. This model instead keeps the active bus cycle as persistent state. An
 * arbiter can therefore inspect address, RD, WR, and driven data without decoding a future opcode
 * or asking a subsystem to forecast an event.</p>
 *
 * <p>This is deliberately a bus sequencer, not another opcode interpreter. Tests script the same
 * cycles selected by the existing decoder and compare the resulting terminal bus edges with the
 * production CPU. A future production implementation would make the decoder load these latches at
 * cycle boundaries.</p>
 */
final class CpuBusCycleMachine {

    enum Model {
        NORMAL(2),
        DOUBLE(1);

        private final int halfDotsPerCpuClock;

        Model(int halfDotsPerCpuClock) {
            this.halfDotsPerCpuClock = halfDotsPerCpuClock;
        }

        int halfDotsPerCpuClock() {
            return halfDotsPerCpuClock;
        }
    }

    enum TState {
        T1,
        T2,
        T3,
        T4
    }

    enum CycleKind {
        OPCODE_FETCH(true, false),
        OPERAND_FETCH(true, false),
        MEMORY_READ(true, false),
        MEMORY_WRITE(false, true),
        STACK_READ(true, false),
        STACK_WRITE(false, true),
        HALT_SAMPLE(true, false),
        INTERNAL(false, false),
        INTERRUPT_VECTOR(false, false);

        private final boolean read;

        private final boolean write;

        CycleKind(boolean read, boolean write) {
            this.read = read;
            this.write = write;
        }

        boolean reads() {
            return read;
        }

        boolean writes() {
            return write;
        }
    }

    enum Control {
        NONE,
        DI,
        EI,
        HALT
    }

    enum RunState {
        RUNNING,
        HALTED,
        HALT_REPLAY
    }

    record Cycle(
            CycleKind kind,
            Integer address,
            Integer writeData,
            int interruptAcknowledge,
            boolean instructionEnds,
            Control control) {

        Cycle {
            if (kind == null || control == null) {
                throw new NullPointerException();
            }
            if ((kind.reads() || kind.writes()) != (address != null)) {
                throw new IllegalArgumentException("external cycles require exactly one address");
            }
            if (kind.writes() != (writeData != null)) {
                throw new IllegalArgumentException("write data must exist exactly for write cycles");
            }
            if (address != null && (address & ~0xffff) != 0) {
                throw new IllegalArgumentException("address outside 16-bit bus");
            }
            if (writeData != null && (writeData & ~0xff) != 0) {
                throw new IllegalArgumentException("data outside 8-bit bus");
            }
            if ((interruptAcknowledge & ~0x1f) != 0
                    || Integer.bitCount(interruptAcknowledge) > 1) {
                throw new IllegalArgumentException("interrupt acknowledge must be one-hot");
            }
            if (control != Control.NONE && !instructionEnds) {
                throw new IllegalArgumentException("control effects occur at instruction end");
            }
        }

        static Cycle read(CycleKind kind, int address) {
            return new Cycle(kind, address, null, 0, false, Control.NONE);
        }

        static Cycle readAndFinish(CycleKind kind, int address) {
            return new Cycle(kind, address, null, 0, true, Control.NONE);
        }

        static Cycle finishFetch(int address, Control control) {
            return new Cycle(CycleKind.OPCODE_FETCH, address, null, 0, true, control);
        }

        static Cycle write(CycleKind kind, int address, int data) {
            return new Cycle(kind, address, data, 0, false, Control.NONE);
        }

        static Cycle writeAndFinish(CycleKind kind, int address, int data) {
            return new Cycle(kind, address, data, 0, true, Control.NONE);
        }

        static Cycle interruptWrite(int address, int data, int acknowledge) {
            return new Cycle(
                    CycleKind.STACK_WRITE, address, data, acknowledge, false, Control.NONE);
        }

        /** A physical acknowledge gate; the shared interrupt fabric, not this cycle, owns source. */
        static Cycle interruptAcknowledgeWrite(int address, int data) {
            return new Cycle(
                    CycleKind.STACK_WRITE, address, data, 1, false, Control.NONE);
        }

        static Cycle internal() {
            return new Cycle(CycleKind.INTERNAL, null, null, 0, false, Control.NONE);
        }

        static Cycle interruptVector() {
            return new Cycle(
                    CycleKind.INTERRUPT_VECTOR, null, null, 0, false, Control.NONE);
        }

        static Cycle haltSample(int address) {
            return new Cycle(
                    CycleKind.HALT_SAMPLE, address, null, 0, true, Control.HALT);
        }
    }

    record BusSignals(
            Integer address,
            boolean read,
            boolean write,
            Integer data,
            Integer sampledData,
            boolean sampleOrCommit,
            int interruptAcknowledge) {
    }

    record Observation(
            long halfDot,
            long cpuClock,
            boolean cpuClockEdge,
            long cycleNumber,
            TState tState,
            CycleKind cycleKind,
            boolean instructionEnds,
            Control control,
            BusSignals bus,
            int heldBusData,
            boolean ime,
            boolean eiPending,
            RunState runState) {
    }

    /** Bus-sequencer state at a half-dot commit boundary; CPU control is a separate island. */
    record BusSnapshot(
            Model model,
            int[] memory,
            List<Cycle> queuedCycles,
            Cycle activeCycle,
            long halfDot,
            long cpuClock,
            long cycleNumber,
            int tStateIndex,
            int halfDotInCpuClock,
            int heldBusData) {

        BusSnapshot {
            memory = memory.clone();
            queuedCycles = List.copyOf(queuedCycles);
        }

        @Override
        public int[] memory() {
            return memory.clone();
        }
    }

    private final Model model;

    private final int[] memory = new int[0x10000];

    private final Deque<Cycle> queuedCycles = new ArrayDeque<>();

    private Cycle activeCycle;

    private long halfDot;

    private long cpuClock;

    private long cycleNumber;

    private int tStateIndex;

    private int halfDotInCpuClock;

    private int heldBusData = 0xff;

    private int interruptRequests;

    private int interruptEnable = 0x1f;

    private boolean ime;

    private boolean eiPending;

    private RunState runState = RunState.RUNNING;

    CpuBusCycleMachine(Model model, Cycle... cycles) {
        this.model = model;
        Arrays.fill(memory, 0xff);
        queue(cycles);
    }

    void queue(Cycle... cycles) {
        queuedCycles.addAll(Arrays.asList(cycles));
        if (activeCycle == null) {
            loadNextCycle();
        }
    }

    void poke(int address, int value) {
        memory[address & 0xffff] = value & 0xff;
    }

    int peekMemory(int address) {
        return memory[address & 0xffff];
    }

    void setInterruptLines(int requests, int enable) {
        interruptRequests = requests & 0x1f;
        interruptEnable = enable & 0x1f;
    }

    void setIme(boolean ime) {
        this.ime = ime;
        if (!ime) {
            eiPending = false;
        }
    }

    Observation peek() {
        requireCycle();
        boolean cpuClockEdge = halfDotInCpuClock == model.halfDotsPerCpuClock() - 1;
        TState tState = TState.values()[tStateIndex];
        boolean busActive = tState == TState.T2 || tState == TState.T3;
        boolean terminal = tState == TState.T4 && cpuClockEdge;
        Integer data = null;
        if (busActive && activeCycle.kind().reads()) {
            data = memory[activeCycle.address()];
        } else if (busActive && activeCycle.kind().writes()) {
            data = activeCycle.writeData();
        }
        Integer sampled = terminal && activeCycle.kind().reads()
                ? memory[activeCycle.address()]
                : null;
        int acknowledge = terminal ? activeCycle.interruptAcknowledge() : 0;
        BusSignals bus = new BusSignals(
                activeCycle.address(),
                busActive && activeCycle.kind().reads(),
                busActive && activeCycle.kind().writes(),
                data,
                sampled,
                terminal,
                acknowledge);
        return new Observation(
                halfDot,
                cpuClock,
                cpuClockEdge,
                cycleNumber,
                tState,
                activeCycle.kind(),
                activeCycle.instructionEnds(),
                activeCycle.control(),
                bus,
                heldBusData,
                ime,
                eiPending,
                runState);
    }

    Observation stepHalfDot() {
        return stepHalfDot(true);
    }

    /** Advances only the held bus cycle; a composed control fabric owns IME, IF, and HALT. */
    Observation stepHalfDotBusOnly() {
        return stepHalfDot(false);
    }

    private Observation stepHalfDot(boolean applyDetachedControlSemantics) {
        Observation observation = peek();
        if (observation.bus().sampleOrCommit()) {
            finishCycle(applyDetachedControlSemantics);
        }

        halfDot++;
        halfDotInCpuClock++;
        if (halfDotInCpuClock == model.halfDotsPerCpuClock()) {
            halfDotInCpuClock = 0;
            cpuClock++;
            tStateIndex++;
            if (tStateIndex == TState.values().length) {
                tStateIndex = 0;
                cycleNumber++;
                loadNextCycle();
            }
        }
        return observation;
    }

    boolean hasCycles() {
        return activeCycle != null;
    }

    int heldBusData() {
        return heldBusData;
    }

    int interruptRequests() {
        return interruptRequests;
    }

    boolean ime() {
        return ime;
    }

    boolean eiPending() {
        return eiPending;
    }

    RunState runState() {
        return runState;
    }

    BusSnapshot captureBus() {
        return new BusSnapshot(
                model,
                memory,
                List.copyOf(queuedCycles),
                activeCycle,
                halfDot,
                cpuClock,
                cycleNumber,
                tStateIndex,
                halfDotInCpuClock,
                heldBusData);
    }

    void restoreBus(BusSnapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        if (snapshot.model() != model) {
            throw new IllegalArgumentException("snapshot clock model differs");
        }
        int[] restoredMemory = snapshot.memory();
        System.arraycopy(restoredMemory, 0, memory, 0, memory.length);
        queuedCycles.clear();
        queuedCycles.addAll(snapshot.queuedCycles());
        activeCycle = snapshot.activeCycle();
        halfDot = snapshot.halfDot();
        cpuClock = snapshot.cpuClock();
        cycleNumber = snapshot.cycleNumber();
        tStateIndex = snapshot.tStateIndex();
        halfDotInCpuClock = snapshot.halfDotInCpuClock();
        heldBusData = snapshot.heldBusData();
    }

    private void finishCycle(boolean applyDetachedControlSemantics) {
        if (activeCycle.kind().reads()) {
            heldBusData = memory[activeCycle.address()];
        } else if (activeCycle.kind().writes()) {
            memory[activeCycle.address()] = activeCycle.writeData();
            heldBusData = activeCycle.writeData();
        }
        if (applyDetachedControlSemantics) {
            interruptRequests &= ~activeCycle.interruptAcknowledge();
        }
        if (applyDetachedControlSemantics && activeCycle.instructionEnds()) {
            finishInstruction(activeCycle.control());
        }
    }

    private void finishInstruction(Control control) {
        boolean imeBefore = ime;
        boolean enableAfterInstruction = eiPending;

        if (control == Control.DI) {
            ime = false;
            eiPending = false;
            enableAfterInstruction = false;
        } else if (control == Control.EI) {
            if (enableAfterInstruction) {
                ime = true;
            }
            eiPending = true;
            enableAfterInstruction = false;
        }

        if (enableAfterInstruction) {
            ime = true;
            eiPending = false;
        }

        if (control == Control.HALT) {
            boolean enabledRequest = (interruptRequests & interruptEnable) != 0;
            runState = !imeBefore && ime && enabledRequest
                    ? RunState.HALT_REPLAY
                    : enabledRequest && !ime
                    ? RunState.HALT_REPLAY
                    : RunState.HALTED;
        }
    }

    private void loadNextCycle() {
        activeCycle = queuedCycles.pollFirst();
    }

    private void requireCycle() {
        if (activeCycle == null) {
            throw new IllegalStateException("no active bus cycle");
        }
    }
}
