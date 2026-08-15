package eu.rekawek.coffeegb.core.experimental.interrupt;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.AddressDrive.STACK_HIGH;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.AddressDrive.STACK_LOW;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.MachineCycle.HANDLER_FETCH;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.MachineCycle.IRQ_JUMP;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.MachineCycle.IRQ_PUSH_1;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.MachineCycle.IRQ_PUSH_2;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.MachineCycle.IRQ_WAIT_1;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.MachineCycle.IRQ_WAIT_2;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.Model.CGB_DOUBLE;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.Model.CGB_NORMAL;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.Model.DMG;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.Source.LCDC;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.Source.SERIAL;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.Source.TIMER;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.TState.T1;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.TState.T2;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.TState.T3;
import static eu.rekawek.coffeegb.core.experimental.interrupt.InterruptEntrySignalMachine.TState.T4;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Executable constraints for replacing timer/serial acknowledge lookahead with real wires. */
public class InterruptEntrySignalMachineTest {

    @Test
    public void currentCpuStatesAreTheFiveMachineCycleSkeleton() {
        for (boolean gbc : new boolean[]{false, true}) {
            CpuFixture fixture = new CpuFixture(gbc);

            List<Cpu.State> states = new ArrayList<>();
            List<Boolean> timerIf = new ArrayList<>();
            for (int machineCycle = 0; machineCycle < 5; machineCycle++) {
                fixture.tickMachineCycle();
                states.add(fixture.cpu.getState());
                timerIf.add(fixture.interrupts.isInterruptFlagSet(Timer));
            }

            assertEquals(List.of(
                    Cpu.State.IRQ_WAIT_2,
                    Cpu.State.IRQ_PUSH_1,
                    Cpu.State.IRQ_PUSH_2,
                    Cpu.State.IRQ_JUMP,
                    Cpu.State.OPCODE), states);
            assertEquals(List.of(true, true, true, false, false), timerIf);
        }
    }

    @Test
    public void explicitTStatesProduceTwoStackBusCyclesAndOneHandlerFetch() {
        InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(DMG);
        List<InterruptEntrySignalMachine.Observation> trace = machine.runCpuClocks(24);

        for (int i = 0; i < trace.size(); i++) {
            InterruptEntrySignalMachine.Observation observation = trace.get(i);
            int cycle = i / 4;
            int t = i & 3;
            assertEquals(new InterruptEntrySignalMachine.MachineCycle[]{
                    IRQ_WAIT_1, IRQ_WAIT_2, IRQ_PUSH_1, IRQ_PUSH_2, IRQ_JUMP, HANDLER_FETCH
            }[cycle], observation.machineCycle());
            assertEquals(new InterruptEntrySignalMachine.TState[]{T1, T2, T3, T4}[t],
                    observation.tState());
        }

        assertStackCycle(trace.subList(8, 12), STACK_HIGH);
        assertStackCycle(trace.subList(12, 16), STACK_LOW);
        assertTrue(trace.get(21).bus().read());
        assertTrue(trace.get(22).bus().read());
        assertTrue(trace.get(23).bus().sampleOrCommit());
    }

    @Test
    public void rawWireAcknowledgeWindowsMatchDmgT4AndFittedCgbPlacement() {
        for (InterruptEntrySignalMachine.Model model :
                new InterruptEntrySignalMachine.Model[]{DMG, CGB_NORMAL, CGB_DOUBLE}) {
            // DMG T4 is externally grounded; the +4 distance follows from retaining the fitted
            // Coffee callback marker. CGB +8 remains a fitted behavioral constraint.
            int acknowledgeWindow = model.cgb() ? 8 : 4;
            for (InterruptEntrySignalMachine.Source source :
                    new InterruptEntrySignalMachine.Source[]{TIMER, SERIAL}) {
                for (int requestDistance = 0; requestDistance <= 12; requestDistance++) {
                    InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(model);
                    machine.presetInterruptFlags(source.mask());
                    advanceThroughProductionClear(machine);

                    InterruptEntrySignalMachine.Observation acknowledge = null;
                    int clocksAfterClear = 0;
                    while (clocksAfterClear <= Math.max(acknowledgeWindow, requestDistance) + 2) {
                        clocksAfterClear++;
                        int request = clocksAfterClear == requestDistance ? source.mask() : 0;
                        InterruptEntrySignalMachine.Observation observation =
                                machine.stepCpuClock(request);
                        if (observation.acknowledgeWires() != 0) {
                            acknowledge = observation;
                        }
                    }

                    boolean expectedIf = requestDistance > acknowledgeWindow;
                    String label = model + " " + source + " request distance " + requestDistance;
                    assertEquals(label, expectedIf,
                            (machine.interruptFlags() & source.mask()) != 0);
                    assertNotNull(label + " acknowledge", acknowledge);
                    assertEquals(label + " one-hot acknowledge", source.mask(),
                            acknowledge.acknowledgeWires());
                }
            }
        }
    }

    @Test
    public void sameEdgeRequestAndSelectedAcknowledgeAreClearDominant() {
        for (InterruptEntrySignalMachine.Model model :
                new InterruptEntrySignalMachine.Model[]{DMG, CGB_NORMAL, CGB_DOUBLE}) {
            InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(model);
            machine.presetInterruptFlags(SERIAL.mask());
            advanceThroughProductionClear(machine);

            int acknowledgeDistance = model.cgb() ? 8 : 4;
            InterruptEntrySignalMachine.Observation collision = null;
            for (int clock = 1; clock <= acknowledgeDistance; clock++) {
                collision = machine.stepCpuClock(clock == acknowledgeDistance ? SERIAL.mask() : 0);
            }

            assertEquals(SERIAL.mask(), collision.requestWires());
            assertEquals(SERIAL.mask(), collision.acknowledgeWires());
            assertFalse((collision.interruptFlags() & SERIAL.mask()) != 0);
        }
    }

    @Test
    public void cpuClockProjectionConvertsDmgAndFittedCgbToHalfDots() {
        assertAcknowledgeHalfDotDistance(DMG, 4, 8);
        assertAcknowledgeHalfDotDistance(CGB_NORMAL, 8, 16);
        assertAcknowledgeHalfDotDistance(CGB_DOUBLE, 8, 8);
    }

    @Test
    public void transparentPendingBankSamplesHigherSourceBeforeItsApertureCloses() {
        for (InterruptEntrySignalMachine.Model model :
                new InterruptEntrySignalMachine.Model[]{DMG, CGB_NORMAL, CGB_DOUBLE}) {
            InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(model);
            machine.presetInterruptFlags(TIMER.mask());
            advanceThroughProductionClear(machine);

            // This is the production test's insertion point: state has just become IRQ_JUMP.
            InterruptEntrySignalMachine.Observation sample = machine.stepCpuClock(LCDC.mask());
            assertTrue(model.toString(), sample.pendingBankTransparent());
            assertEquals(model.toString(), LCDC, sample.sampledPriority());
            runUntilVectorAndAcknowledge(machine);

            assertEquals(model.toString(), LCDC, machine.vectorSource());
            assertFalse(model.toString(), (machine.interruptFlags() & LCDC.mask()) != 0);
            assertTrue(model.toString(), (machine.interruptFlags() & TIMER.mask()) != 0);
        }
    }

    @Test
    public void dmgHeldPendingBankDrivesBothAcknowledgeAndVector() {
        InterruptEntrySignalMachine beforeClose = new InterruptEntrySignalMachine(DMG);
        beforeClose.presetInterruptFlags(SERIAL.mask());
        advanceThroughProductionClear(beforeClose);

        InterruptEntrySignalMachine.Observation t1 = beforeClose.stepCpuClock(0);
        assertEquals(T1, t1.tState());
        assertFalse(t1.dataPhase());
        assertFalse(t1.writePhase());
        assertTrue(t1.pendingBankTransparent());

        InterruptEntrySignalMachine.Observation t2 = beforeClose.stepCpuClock(TIMER.mask());
        assertEquals(T2, t2.tState());
        assertFalse(t2.dataPhase());
        assertFalse(t2.writePhase());
        assertTrue(t2.pendingBankTransparent());
        assertEquals(TIMER.mask() | SERIAL.mask(), t2.sampledPending());
        assertEquals(TIMER, t2.sampledPriority());

        InterruptEntrySignalMachine.Observation closed = beforeClose.stepCpuClock(0);
        assertEquals(T3, closed.tState());
        assertTrue(closed.dataPhase());
        assertFalse(closed.writePhase());
        assertFalse(closed.pendingBankTransparent());
        assertEquals(0, closed.acknowledgeWires());
        assertFalse(closed.vectorResolved());

        InterruptEntrySignalMachine.Observation evaluate = beforeClose.stepCpuClock(0);
        assertEquals(T4, evaluate.tState());
        assertTrue(evaluate.dataPhase());
        assertTrue(evaluate.writePhase());
        assertFalse(evaluate.pendingBankTransparent());
        assertEquals(TIMER.mask(), evaluate.acknowledgeWires());
        assertEquals(TIMER.mask() | SERIAL.mask(), evaluate.sampledPending());
        assertFalse((evaluate.interruptFlags() & TIMER.mask()) != 0);
        assertTrue((evaluate.interruptFlags() & SERIAL.mask()) != 0);
        assertTrue(evaluate.vectorResolved());
        assertEquals(TIMER, evaluate.vectorSource());

        InterruptEntrySignalMachine afterClose = new InterruptEntrySignalMachine(DMG);
        afterClose.presetInterruptFlags(SERIAL.mask());
        advanceThroughProductionClear(afterClose);
        afterClose.stepCpuClock(0); // IRQ_JUMP/T1
        afterClose.stepCpuClock(0); // IRQ_JUMP/T2: final transparent sample

        InterruptEntrySignalMachine.Observation late = afterClose.stepCpuClock(TIMER.mask());
        assertEquals(T3, late.tState());
        assertFalse(late.pendingBankTransparent());
        assertEquals(SERIAL.mask(), late.sampledPending());
        assertEquals(0, late.acknowledgeWires());
        assertTrue((late.interruptFlags() & TIMER.mask()) != 0);
        assertTrue((late.interruptFlags() & SERIAL.mask()) != 0);

        InterruptEntrySignalMachine.Observation lateEvaluate = afterClose.stepCpuClock(0);
        assertEquals(T4, lateEvaluate.tState());
        assertTrue(lateEvaluate.writePhase());
        assertEquals(SERIAL.mask(), lateEvaluate.acknowledgeWires());
        assertTrue(lateEvaluate.vectorResolved());
        assertEquals(SERIAL, lateEvaluate.vectorSource());
        assertTrue((lateEvaluate.interruptFlags() & TIMER.mask()) != 0);
        assertFalse((lateEvaluate.interruptFlags() & SERIAL.mask()) != 0);
    }

    @Test
    public void ff0fWriteClearDominatesRawRequestInsideLocalIfLatch() {
        InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(DMG);

        InterruptEntrySignalMachine.Observation collision =
                machine.stepCpuClock(TIMER.mask(), true, 0);
        assertEquals(TIMER.mask(), collision.requestWires());
        assertFalse((collision.interruptFlags() & TIMER.mask()) != 0);
        assertFalse((collision.sampledPending() & TIMER.mask()) != 0);

        InterruptEntrySignalMachine.Observation afterWrite = machine.stepCpuClock(TIMER.mask());
        assertTrue((afterWrite.interruptFlags() & TIMER.mask()) != 0);
        assertTrue((afterWrite.sampledPending() & TIMER.mask()) != 0);
    }

    @Test
    public void lateLowerSourceDoesNotRedirectTheVector() {
        for (InterruptEntrySignalMachine.Model model :
                new InterruptEntrySignalMachine.Model[]{DMG, CGB_NORMAL, CGB_DOUBLE}) {
            InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(model);
            machine.presetInterruptFlags(LCDC.mask());
            advanceThroughProductionClear(machine);

            machine.stepCpuClock(TIMER.mask());
            runUntilVectorAndAcknowledge(machine);

            assertEquals(model.toString(), LCDC, machine.vectorSource());
            assertFalse(model.toString(), (machine.interruptFlags() & LCDC.mask()) != 0);
            assertTrue(model.toString(), (machine.interruptFlags() & TIMER.mask()) != 0);
        }
    }

    @Test
    public void cgbFittedHeldOwnerPreventsPostVectorRequestFromStealingDelayedAcknowledge() {
        for (InterruptEntrySignalMachine.Model model :
                new InterruptEntrySignalMachine.Model[]{CGB_NORMAL, CGB_DOUBLE}) {
            InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(model);
            machine.presetInterruptFlags(TIMER.mask());
            advanceThroughProductionClear(machine);
            runUntilVectorResolved(machine);
            assertEquals(TIMER, machine.vectorSource());

            // The CGB IF-reset path is still in flight, but vector priority is no longer live.
            machine.stepCpuClock(LCDC.mask());
            InterruptEntrySignalMachine.Observation acknowledge = runUntilAcknowledgeObserved(machine);

            assertEquals(TIMER.mask(), acknowledge.acknowledgeWires());
            assertEquals(TIMER, machine.vectorSource());
            assertTrue((machine.interruptFlags() & LCDC.mask()) != 0);
            assertFalse((machine.interruptFlags() & TIMER.mask()) != 0);
        }
    }

    @Test
    public void oneUniversalVectorBoundaryClearCannotFitDmgAndCgb() {
        // With the retained fitted Coffee callback marker, the externally grounded DMG T4
        // acknowledge appears at +4, so a point strobe there matches the projection. CGB's later
        // clear remains fitted at +8: a request at +5 must still be consumed there, after the DMG
        // point strobe has ended.
        assertFalse("DMG distance four collides with T4", rawAcknowledgeSurvives(DMG, 4));
        assertFalse("a +4 point strobe consumes it", survivesPointStrobe(4, 4));

        assertFalse("CGB distance five remains inside its fitted window",
                rawAcknowledgeSurvives(CGB_NORMAL, 5));
        assertTrue("a universal +4 point strobe has already ended", survivesPointStrobe(4, 5));
    }

    @Test
    public void closingPendingBankAtProductionClearBoundaryIsFalsified() {
        // TIMER is pending at IRQ_PUSH_2. LCDC arrives while the pending bank is still transparent
        // in IRQ_JUMP and must redirect while TIMER remains pending. Closing the bank at the legacy
        // callback boundary could only keep TIMER or clear/re-request it.
        InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(DMG);
        machine.presetInterruptFlags(TIMER.mask());
        advanceThroughProductionClear(machine);
        InterruptEntrySignalMachine.Source prematurelyHeld = TIMER;

        InterruptEntrySignalMachine.Observation transparent = machine.stepCpuClock(LCDC.mask());
        assertTrue(transparent.pendingBankTransparent());
        runUntilVectorAndAcknowledge(machine);

        assertEquals(TIMER, prematurelyHeld);
        assertEquals(LCDC, machine.vectorSource());
        assertTrue((machine.interruptFlags() & TIMER.mask()) != 0);
    }

    private static void assertStackCycle(
            List<InterruptEntrySignalMachine.Observation> cycle,
            InterruptEntrySignalMachine.AddressDrive address) {
        assertEquals(4, cycle.size());
        for (InterruptEntrySignalMachine.Observation observation : cycle) {
            assertEquals(address, observation.bus().address());
        }
        assertFalse(cycle.get(0).bus().write());
        assertTrue(cycle.get(1).bus().write());
        assertTrue(cycle.get(2).bus().write());
        assertFalse(cycle.get(3).bus().write());
        assertTrue(cycle.get(3).bus().sampleOrCommit());
    }

    private static void assertAcknowledgeHalfDotDistance(
            InterruptEntrySignalMachine.Model model,
            int expectedCpuClocks,
            int expectedHalfDots) {
        InterruptEntrySignalMachine machine = new InterruptEntrySignalMachine(model);
        machine.presetInterruptFlags(SERIAL.mask());
        InterruptEntrySignalMachine.Observation boundary = advanceThroughProductionClear(machine);
        InterruptEntrySignalMachine.Observation acknowledge = runUntilAcknowledgeObserved(machine);

        assertEquals(expectedCpuClocks,
                acknowledge.cpuClock() - boundary.cpuClock());
        assertEquals(expectedHalfDots,
                acknowledge.halfDot() - boundary.halfDot());
    }

    private static InterruptEntrySignalMachine.Observation advanceThroughProductionClear(
            InterruptEntrySignalMachine machine) {
        InterruptEntrySignalMachine.Observation observation;
        do {
            observation = machine.stepCpuClock(0);
        } while (!observation.productionClearBoundary());
        assertEquals(IRQ_PUSH_2, observation.machineCycle());
        assertEquals(T4, observation.tState());
        assertEquals(observation.cpuClock(), machine.productionClearCpuClock());
        return observation;
    }

    private static InterruptEntrySignalMachine.Observation runUntilVectorResolved(
            InterruptEntrySignalMachine machine) {
        InterruptEntrySignalMachine.Observation observation;
        do {
            observation = machine.stepCpuClock(0);
        } while (!observation.vectorResolved());
        assertEquals(IRQ_JUMP, observation.machineCycle());
        assertEquals(T4, observation.tState());
        assertNotNull(observation.vectorSource());
        return observation;
    }

    private static InterruptEntrySignalMachine.Observation runUntilAcknowledgeObserved(
            InterruptEntrySignalMachine machine) {
        InterruptEntrySignalMachine.Observation observation;
        int remaining = 16;
        do {
            observation = machine.stepCpuClock(0);
        } while (observation.acknowledgeWires() == 0 && remaining-- > 0);
        assertTrue("acknowledge strobe did not arrive", remaining > 0);
        return observation;
    }

    private static void runUntilVectorAndAcknowledge(
            InterruptEntrySignalMachine machine) {
        boolean vectorResolved = false;
        boolean acknowledgeObserved = false;
        int remaining = 16;
        while ((!vectorResolved || !acknowledgeObserved) && remaining-- > 0) {
            InterruptEntrySignalMachine.Observation observation = machine.stepCpuClock(0);
            vectorResolved |= observation.vectorResolved();
            acknowledgeObserved |= observation.acknowledgeWires() != 0;
        }
        assertTrue("vector did not resolve", vectorResolved);
        assertTrue("acknowledge strobe did not arrive", acknowledgeObserved);
    }

    private static boolean rawAcknowledgeSurvives(
            InterruptEntrySignalMachine.Model model, int requestDistance) {
        return requestDistance > (model.cgb() ? 8 : 4);
    }

    private static boolean survivesPointStrobe(int strobeDistance, int requestDistance) {
        return requestDistance > strobeDistance;
    }

    private static final class CpuFixture {

        private static final int PROGRAM = 0x100;

        private final Ram memory = new Ram(0, 0x10000);

        private final InterruptManager interrupts;

        private final SpeedMode speedMode;

        private final Cpu cpu;

        private CpuFixture(boolean gbc) {
            interrupts = new InterruptManager(gbc);
            speedMode = new SpeedMode(gbc);
            AddressSpace bus = new AddressSpace() {
                @Override
                public boolean accepts(int address) {
                    return true;
                }

                @Override
                public void setByte(int address, int value) {
                    if (interrupts.accepts(address)) {
                        interrupts.setByte(address, value);
                    } else {
                        memory.setByte(address, value);
                    }
                }

                @Override
                public int getByte(int address) {
                    return interrupts.accepts(address)
                            ? interrupts.getByte(address)
                            : memory.getByte(address);
                }
            };
            cpu = new Cpu(bus, interrupts, null, speedMode, new Display(gbc));
            cpu.getRegisters().setPC(PROGRAM);
            cpu.getRegisters().setSP(0xfffe);
            memory.setByte(PROGRAM, 0x00);
            interrupts.setByte(0xff0f, 0);
            interrupts.setByte(0xffff, 1 << Timer.ordinal());
            interrupts.enableInterrupts(false);
            interrupts.requestInterrupt(Timer);
        }

        private void tickMachineCycle() {
            for (int tick = 0; tick < 4 / speedMode.getSpeedMode(); tick++) {
                cpu.tick();
            }
        }
    }
}
