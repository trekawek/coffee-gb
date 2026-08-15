package eu.rekawek.coffeegb.core.experimental.cpu;

import eu.rekawek.coffeegb.core.signal.HalfDotClockRouter;

import java.util.EnumSet;
import java.util.Set;

import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.BusWrite;
import static eu.rekawek.coffeegb.core.signal.HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES;

/**
 * Test-only vertical slice through the DMG clock, CPU bus, peripheral request, IF, IME, and HALT
 * control fabric.
 *
 * <p>The timer and serial cones end at raw request pins. Their detailed ripple state is already
 * covered by their detached experiments and is intentionally not copied here. This fabric proves
 * the more useful boundary: both pins, the CPU's persistent bus intent, and its unqualified
 * acknowledge gate are resolved from one immutable half-dot before any state element commits.
 * The one-hot acknowledge is selected from settled {@code (IF | rawRequests) & IE}; its source is
 * not supplied by the scripted bus cycle.</p>
 *
 * <p>A decoded {@link CpuBusCycleMachine.Cycle} is loaded before it becomes active. After launch,
 * this class sees only held cycle kind/control, T1..T4, address, RD, WR, data, and terminal strobes;
 * it never sees an opcode. Nor can a peripheral answer a deadline or completion-lookahead query.
 * Reversing DRIVE and COMMIT order is therefore a meaningful executable order-invariance test
 * for this edge-triggered control slice. Transparent-latch settling is explicitly out of scope.</p>
 */
final class DmgControlSignalFabric {

    static final int TIMER_MASK = 1 << 2;

    static final int SERIAL_MASK = 1 << 3;

    enum Phase {
        DRIVE,
        RESOLVE,
        CAPTURE,
        COMMIT
    }

    enum EvaluationOrder {
        FORWARD,
        REVERSE
    }

    /** State that survives a half-dot; raw peripheral request pins deliberately are not state. */
    enum RetainedState {
        CLOCK_PHASE,
        CPU_ACTIVE_CYCLE,
        CPU_T_STATE,
        CPU_HELD_BUS_DATA,
        SHARED_IF_LATCHES,
        INTERRUPT_ENABLE_LATCHES,
        RUNNING_PENDING_LATCHES,
        HALT_WAKE_LATCH,
        HALT_DECODE_LATCH,
        HALT_LATCH,
        IME_LATCH,
        EI_DELAY_LATCH
    }

    /** Production cuts that this DMG vertical slice cannot yet replace. */
    enum Blocker {
        /** Coffee's CPU currently samples reads one M-cycle late; moving it changes all anchors. */
        CALIBRATED_ONE_M_CYCLE_LATE_CPU_BUS,
        /** The physical acknowledge gate and vector-capture phases are still scripted bus intent. */
        INTERRUPT_ACKNOWLEDGE_AND_VECTOR_PHASES,
        /** STAT/VBlank have source-specific gates and half-dot phases inside the PPU. */
        PPU_STAT_AND_VBLANK_SOURCE_PATHS,
        /** CGB has two CPU subedges per dot and a different direct PPU interrupt route. */
        CGB_SUBEDGES_AND_DIRECT_INTERRUPT_PATH,
        /** The timer experiment still needs named BOGA/NYDU/MOBA latches at this boundary. */
        TIMER_INTERNAL_GATE_LATCHES,
        /** External serial transfers need a sampled link-pin signal. */
        SERIAL_EXTERNAL_INPUT_PIN,
        /** One-pass wire resolution cannot model transparent or asynchronous fanout settling. */
        TRANSPARENT_LATCH_AND_ASYNC_FANOUT_SETTLING
    }

    record SourcePins(boolean timerRequest, boolean serialRequest) {

        static final SourcePins IDLE = new SourcePins(false, false);

        static SourcePins timer() {
            return new SourcePins(true, false);
        }

        static SourcePins serial() {
            return new SourcePins(false, true);
        }

        static SourcePins both() {
            return new SourcePins(true, true);
        }
    }

    record ResolvedWires(int rawRequests, int oneHotAcknowledge, BusWrite busWrite) {
    }

    record Observation(
            long halfDot,
            boolean fixedClockEnable,
            boolean cpuClockEnable,
            CpuBusCycleMachine.Observation cpu,
            ResolvedWires wires,
            DmgCpuControlLatchIsland.Observation control) {
    }

    record Snapshot(
            HalfDotClockRouter.Phase clockPhase,
            CpuBusCycleMachine.BusSnapshot cpu,
            DmgCpuControlLatchIsland.State control) {
    }

    private final HalfDotClockRouter clock = new HalfDotClockRouter(BETWEEN_FIXED_DOMAIN_EDGES);

    private final CpuBusCycleMachine cpu;

    private final DmgCpuControlLatchIsland control = new DmgCpuControlLatchIsland();

    private Phase phase = Phase.COMMIT;

    private long halfDot;

    private Observation lastObservation;

    DmgControlSignalFabric(
            int interruptFlags,
            int interruptEnable,
            boolean ime,
            CpuBusCycleMachine.Cycle... cycles) {
        cpu = new CpuBusCycleMachine(CpuBusCycleMachine.Model.NORMAL, cycles);
        control.restore(new DmgCpuControlLatchIsland.State(
                interruptFlags & 0x1f,
                interruptEnable & 0x1f,
                0,
                false,
                ime,
                false,
                false,
                false));
    }

    Observation stepHalfDot(SourcePins pins) {
        return stepHalfDot(pins, EvaluationOrder.FORWARD);
    }

    Observation stepHalfDot(SourcePins pins, EvaluationOrder order) {
        if (pins == null || order == null) {
            throw new NullPointerException();
        }
        if (phase != Phase.COMMIT) {
            throw new IllegalStateException("previous half-dot did not commit");
        }

        // Immutable committed snapshot used by every phase below.
        CpuBusCycleMachine.Observation oldCpu = cpu.peek();
        clock.resolve(false);
        boolean fixedClockEnable = clock.fixedDomainClockEnable();
        boolean cpuClockEnable = clock.cpuDomainClockEnable();
        if (cpuClockEnable != oldCpu.cpuClockEdge()) {
            throw new IllegalStateException("clock router and CPU T-state lost phase lock");
        }

        phase = Phase.DRIVE;
        WirePlane plane = new WirePlane();
        int[] driverOrder = order == EvaluationOrder.FORWARD
                ? new int[]{0, 1, 2}
                : new int[]{2, 1, 0};
        for (int driver : driverOrder) {
            switch (driver) {
                case 0 -> plane.driveRequest(pins.timerRequest() ? TIMER_MASK : 0);
                case 1 -> plane.driveRequest(pins.serialRequest() ? SERIAL_MASK : 0);
                case 2 -> driveCpu(oldCpu, plane);
                default -> throw new AssertionError(driver);
            }
        }

        phase = Phase.RESOLVE;
        DmgCpuControlLatchIsland.Observation oldControl = control.observation();
        ResolvedWires wires = plane.resolve(
                oldControl.readableIf() & 0x1f,
                oldControl.interruptEnable());

        phase = Phase.CAPTURE;
        boolean terminal = oldCpu.bus().sampleOrCommit();
        DmgCpuControlLatchIsland.Control retiredControl =
                terminal && oldCpu.instructionEnds()
                        ? mapControl(oldCpu.control())
                        : DmgCpuControlLatchIsland.Control.NONE;
        DmgCpuControlLatchIsland.Inputs inputs = new DmgCpuControlLatchIsland.Inputs(
                wires.rawRequests(),
                wires.oneHotAcknowledge(),
                wires.busWrite(),
                terminal,
                cpuClockEnable,
                terminal && oldCpu.instructionEnds(),
                retiredControl,
                terminal && oldCpu.cycleKind()
                        == CpuBusCycleMachine.CycleKind.INTERRUPT_VECTOR,
                terminal && oldCpu.cycleKind()
                        == CpuBusCycleMachine.CycleKind.OPCODE_FETCH);
        DmgCpuControlLatchIsland.EvaluationOrder controlOrder =
                order == EvaluationOrder.FORWARD
                        ? DmgCpuControlLatchIsland.EvaluationOrder.FORWARD
                        : DmgCpuControlLatchIsland.EvaluationOrder.REVERSE;
        control.resolve(inputs, controlOrder);
        DmgCpuControlLatchIsland.Observation captured = control.capturedObservation();

        phase = Phase.COMMIT;
        if (order == EvaluationOrder.FORWARD) {
            control.commit(controlOrder);
            cpu.stepHalfDotBusOnly();
            clock.commit();
        } else {
            clock.commit();
            cpu.stepHalfDotBusOnly();
            control.commit(controlOrder);
        }

        lastObservation = new Observation(
                halfDot++,
                fixedClockEnable,
                cpuClockEnable,
                oldCpu,
                wires,
                captured);
        return lastObservation;
    }

    Observation observation() {
        return lastObservation;
    }

    Snapshot snapshot() {
        if (phase != Phase.COMMIT) {
            throw new IllegalStateException("snapshot only at a commit boundary");
        }
        return new Snapshot(clock.phase(), cpu.captureBus(), control.capture());
    }

    void restore(Snapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        if (phase != Phase.COMMIT) {
            throw new IllegalStateException("restore only at a commit boundary");
        }
        clock.restore(snapshot.clockPhase());
        cpu.restoreBus(snapshot.cpu());
        control.restore(snapshot.control());
        halfDot = snapshot.cpu().halfDot();
        lastObservation = null;
    }

    Phase phase() {
        return phase;
    }

    static Set<RetainedState> retainedState() {
        return EnumSet.allOf(RetainedState.class);
    }

    static Set<Blocker> blockers() {
        return EnumSet.allOf(Blocker.class);
    }

    private static void driveCpu(
            CpuBusCycleMachine.Observation oldCpu, WirePlane plane) {
        plane.driveAcknowledge(oldCpu.bus().interruptAcknowledge());
        if (oldCpu.bus().write()) {
            plane.driveBusWrite(BusWrite.to(
                    oldCpu.bus().address(), oldCpu.bus().data()));
        }
    }

    private static DmgCpuControlLatchIsland.Control mapControl(
            CpuBusCycleMachine.Control control) {
        return switch (control) {
            case NONE -> DmgCpuControlLatchIsland.Control.NONE;
            case DI -> DmgCpuControlLatchIsland.Control.DI;
            case EI -> DmgCpuControlLatchIsland.Control.EI;
            case HALT -> DmgCpuControlLatchIsland.Control.HALT;
        };
    }

    /** Commutative wire collector. No drive may read it until it is frozen. */
    private static final class WirePlane {

        private int requests;

        private boolean acknowledgeGate;

        private BusWrite busWrite = BusWrite.none();

        private boolean frozen;

        private void driveRequest(int mask) {
            requireOpen();
            requests |= mask & 0x1f;
        }

        private void driveAcknowledge(int mask) {
            requireOpen();
            acknowledgeGate |= (mask & 0x1f) != 0;
        }

        private void driveBusWrite(BusWrite write) {
            requireOpen();
            if (busWrite.active() && !busWrite.equals(write)) {
                throw new IllegalStateException("two owners drove the CPU write bus");
            }
            busWrite = write;
        }

        private ResolvedWires resolve(int storedFlags, int interruptEnable) {
            requireOpen();
            frozen = true;
            int pending = (storedFlags | requests) & interruptEnable & 0x1f;
            int selectedAcknowledge = acknowledgeGate
                    ? Integer.lowestOneBit(pending)
                    : 0;
            return new ResolvedWires(requests, selectedAcknowledge, busWrite);
        }

        private void requireOpen() {
            if (frozen) {
                throw new IllegalStateException("wire plane already resolved");
            }
        }
    }
}
