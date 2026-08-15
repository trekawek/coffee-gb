package eu.rekawek.coffeegb.core.experimental.cpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.serial.SerialPort;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Serial;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.Control.EI;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.CycleKind.MEMORY_WRITE;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.TState.T4;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgControlSignalFabric.SERIAL_MASK;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgControlSignalFabric.TIMER_MASK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Executable proof for the composed, edge-triggered DMG control-fabric cut. */
public class DmgControlSignalFabricTest {

    private static final int BOTH = TIMER_MASK | SERIAL_MASK;

    @Test
    public void oneResolvedBoundaryRemovesTimerBeforeCpuSerialAfterAsymmetry() {
        DmgControlSignalFabric fabric = fabric(0, BOTH, false, internalCycles(2));

        for (int halfDot = 0; halfDot < 7; halfDot++) {
            fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);
        }
        DmgControlSignalFabric.Observation simultaneous =
                fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.both());

        assertEquals(T4, simultaneous.cpu().tState());
        assertTrue(simultaneous.cpuClockEnable());
        assertEquals(BOTH, simultaneous.wires().rawRequests());
        assertEquals(BOTH, simultaneous.control().readableIf() & 0x1f);
        assertEquals("the CPU data-phase latch sees the settled wire plane",
                BOTH, simultaneous.control().runningPending());

        // Today's Gameboy.tick() invokes Timer before CPU and Serial after CPU. If both complete
        // at this boundary, a sequential sample can see only Timer. That result is impossible in
        // the resolved fabric, regardless of Java driver order.
        int sequentialFlags = 0;
        sequentialFlags |= TIMER_MASK;
        int legacyCpuSample = sequentialFlags;
        sequentialFlags |= SERIAL_MASK;
        assertEquals(TIMER_MASK, legacyCpuSample);
        assertNotEquals(legacyCpuSample, simultaneous.control().runningPending());
    }

    @Test
    public void driveResolveCaptureAndCommitAreEvaluationOrderInvariant() {
        CpuBusCycleMachine.Cycle[] cycles = {
                CpuBusCycleMachine.Cycle.internal(),
                CpuBusCycleMachine.Cycle.write(MEMORY_WRITE, 0xc123, 0x5a),
                CpuBusCycleMachine.Cycle.interruptAcknowledgeWrite(0xcffe, 0x34),
                CpuBusCycleMachine.Cycle.finishFetch(0x0100, EI),
                CpuBusCycleMachine.Cycle.haltSample(0x0101),
                CpuBusCycleMachine.Cycle.internal()
        };
        DmgControlSignalFabric forward = fabric(SERIAL_MASK, BOTH, false, cycles);
        DmgControlSignalFabric reverse = fabric(SERIAL_MASK, BOTH, false, cycles);

        for (int halfDot = 0; halfDot < 48; halfDot++) {
            DmgControlSignalFabric.SourcePins pins = new DmgControlSignalFabric.SourcePins(
                    halfDot % 7 == 1,
                    halfDot % 11 == 3);
            assertEquals("half-dot " + halfDot,
                    forward.stepHalfDot(
                            pins, DmgControlSignalFabric.EvaluationOrder.FORWARD),
                    reverse.stepHalfDot(
                            pins, DmgControlSignalFabric.EvaluationOrder.REVERSE));
            assertEquals(DmgControlSignalFabric.Phase.COMMIT, forward.phase());
            assertEquals(DmgControlSignalFabric.Phase.COMMIT, reverse.phase());
        }
    }

    @Test
    public void timerAndSerialRequestAcknowledgeCollisionsUseOneClearDominantEquation() {
        for (int source : new int[]{TIMER_MASK, SERIAL_MASK}) {
            DmgControlSignalFabric fabric = fabric(
                    source,
                    source,
                    false,
                    CpuBusCycleMachine.Cycle.interruptAcknowledgeWrite(
                            0xcffe, 0x34),
                    CpuBusCycleMachine.Cycle.internal());

            DmgControlSignalFabric.Observation collision = null;
            for (int halfDot = 0; halfDot < 8; halfDot++) {
                DmgControlSignalFabric.SourcePins pins = halfDot == 7
                        ? pins(source)
                        : DmgControlSignalFabric.SourcePins.IDLE;
                collision = fabric.stepHalfDot(pins);
            }

            assertEquals(source, collision.wires().rawRequests());
            assertEquals(source, collision.wires().oneHotAcknowledge());
            assertEquals(0, collision.control().readableIf() & source);
            assertEquals(0, collision.control().runningPending() & source);

            DmgControlSignalFabric.Observation after = fabric.stepHalfDot(pins(source));
            assertEquals("a physically later edge survives", source,
                    after.control().readableIf() & source);
        }
    }

    @Test
    public void acknowledgeGateSelectsPriorityFromSettledIfInsteadOfCycleOracle() {
        DmgControlSignalFabric fabric = fabric(
                BOTH,
                BOTH,
                false,
                CpuBusCycleMachine.Cycle.interruptAcknowledgeWrite(0xcffe, 0x34),
                CpuBusCycleMachine.Cycle.internal());

        DmgControlSignalFabric.Observation acknowledge = null;
        for (int halfDot = 0; halfDot < 8; halfDot++) {
            acknowledge = fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);
        }

        assertEquals("Timer has higher DMG interrupt priority", TIMER_MASK,
                acknowledge.wires().oneHotAcknowledge());
        assertEquals(0, acknowledge.control().readableIf() & TIMER_MASK);
        assertEquals(SERIAL_MASK, acknowledge.control().readableIf() & SERIAL_MASK);
    }

    @Test
    public void timerAndSerialShareTheProductionFourClockHaltObservationPath() {
        assertEquals(4, productionTimerWakeDelay());
        assertEquals(4, productionSerialWakeDelay());
        assertEquals(4, fabricWakeDelay(TIMER_MASK));
        assertEquals(4, fabricWakeDelay(SERIAL_MASK));
    }

    @Test
    public void rawPeripheralPinsAreAnExplicitStatelessBoundary() {
        DmgControlSignalFabric fabric = fabric(0, TIMER_MASK, false, internalCycles(2));

        DmgControlSignalFabric.Observation edge =
                fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.timer());
        DmgControlSignalFabric.Observation next =
                fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);

        assertEquals(TIMER_MASK, edge.wires().rawRequests());
        assertEquals(0, next.wires().rawRequests());
        assertEquals("only the shared IF latch retains the pulse",
                TIMER_MASK, next.control().readableIf() & TIMER_MASK);
    }

    @Test
    public void eiHaltRaceIsACompositionOfHeldCycleIntentAndInterruptLatches() {
        DmgControlSignalFabric fabric = fabric(
                0,
                TIMER_MASK,
                false,
                CpuBusCycleMachine.Cycle.finishFetch(0x0100, EI),
                CpuBusCycleMachine.Cycle.haltSample(0x0101),
                CpuBusCycleMachine.Cycle.internal());

        DmgControlSignalFabric.Observation observation = null;
        for (int halfDot = 0; halfDot < 16; halfDot++) {
            observation = fabric.stepHalfDot(halfDot == 1
                    ? DmgControlSignalFabric.SourcePins.timer()
                    : DmgControlSignalFabric.SourcePins.IDLE);
        }

        assertEquals(T4, observation.cpu().tState());
        assertEquals(CpuBusCycleMachine.Control.HALT, observation.cpu().control());
        assertTrue(observation.control().ime());
        assertFalse(observation.control().eiPending());
        assertTrue(observation.control().haltWakePending());
        assertFalse("wake reset dominates the simultaneous HALT set",
                observation.control().halted());
        assertTrue(observation.control().dispatchRequest());
        assertTrue(observation.control().directHaltDecode());
        assertTrue(observation.control().haltSetDelayed());
        assertTrue("HALT samples the next opcode before interrupt entry",
                observation.control().instructionRegisterLoad());
        assertTrue(observation.control().pcWrite());
        assertFalse(observation.control().iduIncrement());
        assertFalse(observation.control().pcIncrement());
    }

    @Test
    public void cpuWriteIntentRemainsDrivenAcrossT2AndIsNotDecodedAgain() {
        DmgControlSignalFabric fabric = fabric(
                0,
                0,
                false,
                CpuBusCycleMachine.Cycle.writeAndFinish(
                        MEMORY_WRITE, DmgCpuControlLatchIsland.IE, TIMER_MASK),
                CpuBusCycleMachine.Cycle.internal());

        fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);
        fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);
        DmgControlSignalFabric.Observation t2a =
                fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);
        DmgControlSignalFabric.Observation t2b =
                fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);

        assertTrue(t2a.cpu().bus().write());
        assertEquals(t2a.cpu().bus(), t2b.cpu().bus());
        assertEquals(DmgCpuControlLatchIsland.IE, t2a.wires().busWrite().address());
        assertEquals(TIMER_MASK, t2a.wires().busWrite().value());
        assertEquals(TIMER_MASK, t2a.control().interruptEnable());
        assertEquals(CpuBusCycleMachine.Control.NONE, t2a.cpu().control());
    }

    @Test
    public void saveRestoreReplaysClockBusAndControlStateExactly() {
        DmgControlSignalFabric fabric = fabric(0, BOTH, true, internalCycles(8));
        for (int halfDot = 0; halfDot < 11; halfDot++) {
            fabric.stepHalfDot(pattern(halfDot));
        }
        DmgControlSignalFabric.Snapshot snapshot = fabric.snapshot();

        List<DmgControlSignalFabric.Observation> expected = new ArrayList<>();
        for (int halfDot = 11; halfDot < 43; halfDot++) {
            expected.add(fabric.stepHalfDot(pattern(halfDot)));
        }

        fabric.restore(snapshot);
        assertNull(fabric.observation());
        List<DmgControlSignalFabric.Observation> replay = new ArrayList<>();
        for (int halfDot = 11; halfDot < 43; halfDot++) {
            replay.add(fabric.stepHalfDot(pattern(halfDot)));
        }
        assertEquals(expected, replay);
    }

    @Test
    public void retainedStateAndMigrationBlockersKeepTheClaimNarrow() {
        assertEquals(12, DmgControlSignalFabric.retainedState().size());
        assertTrue(DmgControlSignalFabric.blockers().contains(
                DmgControlSignalFabric.Blocker.CALIBRATED_ONE_M_CYCLE_LATE_CPU_BUS));
        assertTrue(DmgControlSignalFabric.blockers().contains(
                DmgControlSignalFabric.Blocker.INTERRUPT_ACKNOWLEDGE_AND_VECTOR_PHASES));
        assertTrue(DmgControlSignalFabric.blockers().contains(
                DmgControlSignalFabric.Blocker.PPU_STAT_AND_VBLANK_SOURCE_PATHS));
        assertTrue(DmgControlSignalFabric.blockers().contains(
                DmgControlSignalFabric.Blocker.CGB_SUBEDGES_AND_DIRECT_INTERRUPT_PATH));
        assertTrue(DmgControlSignalFabric.blockers().contains(
                DmgControlSignalFabric.Blocker.TRANSPARENT_LATCH_AND_ASYNC_FANOUT_SETTLING));
    }

    private static int fabricWakeDelay(int source) {
        DmgControlSignalFabric fabric = fabric(0, source, false, internalCycles(3));
        fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);
        DmgControlSignalFabric.Observation observation =
                fabric.stepHalfDot(pins(source));
        assertEquals(source, observation.control().readableIf() & source);
        assertFalse(observation.control().haltWakePending());

        int cpuClocks = 0;
        while (!observation.control().haltWakePending()) {
            observation = fabric.stepHalfDot(DmgControlSignalFabric.SourcePins.IDLE);
            if (observation.cpuClockEnable()) {
                cpuClocks++;
            }
        }
        return cpuClocks;
    }

    private static int productionTimerWakeDelay() {
        InterruptManager interrupts = initializedInterrupts(Timer);
        Timer timer = new Timer(interrupts, new SpeedMode(false));
        timer.setByte(0xff05, 0xff);
        timer.setByte(0xff07, 0x05);
        return clocksFromReadableIfToHalt(
                interrupts,
                Timer,
                timer::tick,
                64);
    }

    private static int productionSerialWakeDelay() {
        InterruptManager interrupts = initializedInterrupts(Serial);
        SpeedMode speed = new SpeedMode(false);
        SerialPort serial = new SerialPort(interrupts, false, speed);
        serial.setByte(0xff01, 0);
        serial.setByte(0xff02, 0x81);
        return clocksFromReadableIfToHalt(
                interrupts,
                Serial,
                serial::tick,
                8 * 2 * 256 + 16);
    }

    private static InterruptManager initializedInterrupts(
            InterruptManager.InterruptType type) {
        InterruptManager interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f, 0);
        interrupts.setByte(0xffff, 1 << type.ordinal());
        return interrupts;
    }

    private static int clocksFromReadableIfToHalt(
            InterruptManager interrupts,
            InterruptManager.InterruptType type,
            Runnable tick,
            int limit) {
        int remaining = limit;
        while (!interrupts.isInterruptFlagSet(type) && remaining-- > 0) {
            tick.run();
        }
        assertTrue("request did not become readable", remaining > 0);
        assertFalse(interrupts.isInterruptRequestedForHalt());

        int clocks = 0;
        while (!interrupts.isInterruptRequestedForHalt() && remaining-- > 0) {
            tick.run();
            clocks++;
        }
        assertTrue("request did not reach HALT", remaining > 0);
        return clocks;
    }

    private static DmgControlSignalFabric.SourcePins pins(int source) {
        return source == TIMER_MASK
                ? DmgControlSignalFabric.SourcePins.timer()
                : DmgControlSignalFabric.SourcePins.serial();
    }

    private static DmgControlSignalFabric.SourcePins pattern(int halfDot) {
        return new DmgControlSignalFabric.SourcePins(
                halfDot % 9 == 2,
                halfDot % 13 == 5);
    }

    private static DmgControlSignalFabric fabric(
            int flags,
            int enable,
            boolean ime,
            CpuBusCycleMachine.Cycle... cycles) {
        return new DmgControlSignalFabric(flags, enable, ime, cycles);
    }

    private static CpuBusCycleMachine.Cycle[] internalCycles(int count) {
        CpuBusCycleMachine.Cycle[] result = new CpuBusCycleMachine.Cycle[count];
        Arrays.fill(result, CpuBusCycleMachine.Cycle.internal());
        return result;
    }
}
