package eu.rekawek.coffeegb.core.experimental.cpu;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.BusWrite.to;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.Control.DI;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.Control.EI;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.Control.HALT;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.Control.NONE;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.Control.RETI;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.EvaluationOrder.FORWARD;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.EvaluationOrder.REVERSE;
import static eu.rekawek.coffeegb.core.experimental.cpu.DmgCpuControlLatchIsland.IE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Executable constraints for replacing CPU interrupt/HALT provenance with physical nodes. */
public class DmgCpuControlLatchIslandTest {

    private static final int PROGRAM = 0x0100;

    @Test
    public void oneRequestHasSeparateReadableRunningAndHaltWakeObservationPoints() {
        for (int bit = 0; bit < 5; bit++) {
            int source = 1 << bit;
            DmgCpuControlLatchIsland island = new DmgCpuControlLatchIsland();
            island.step(DmgCpuControlLatchIsland.Inputs.idle().withBusWrite(to(IE, source)));

            DmgCpuControlLatchIsland.Observation readable = island.step(
                    DmgCpuControlLatchIsland.Inputs.idle().withRawRequests(source));
            assertEquals(source, readable.readableIf() & 0x1f);
            assertEquals(0, readable.runningPending());
            assertFalse(readable.haltWakePending());

            DmgCpuControlLatchIsland.Observation running = island.step(
                    DmgCpuControlLatchIsland.Inputs.idle().atDataSample());
            assertEquals(source, running.runningPending());
            assertFalse(running.haltWakePending());

            DmgCpuControlLatchIsland.Observation wake = island.step(
                    DmgCpuControlLatchIsland.Inputs.idle().atCpuClock());
            assertEquals(source, wake.runningPending());
            assertTrue(wake.haltWakePending());
        }
    }

    @Test
    public void haltBugIsHaltOwnMissingIduIncrementWhenPendingWinsItsLatchRace() {
        int timer = 1 << Timer.ordinal();
        DmgCpuControlLatchIsland island = pendingIsland(timer, false);

        DmgCpuControlLatchIsland.Observation halt = island.step(
                DmgCpuControlLatchIsland.Inputs.boundary(HALT).withOpcodeFetch());
        assertFalse("pending reset dominates HALT set", halt.halted());
        assertTrue(halt.directHaltDecode());
        assertTrue("the delayed copy only reaches the HALT-latch set input",
                halt.haltSetDelayed());
        assertTrue("HALT still samples the next opcode", halt.instructionRegisterLoad());
        assertTrue("the PC write pulse is physically present", halt.pcWrite());
        assertFalse("direct HALT decode is absent from decoder2 ctl_idu_inc",
                halt.iduIncrement());
        assertFalse("writing the non-incremented IDU value leaves PC unchanged",
                halt.pcIncrement());

        DmgCpuControlLatchIsland.Observation followingFetch = island.step(
                DmgCpuControlLatchIsland.Inputs.idle().withOpcodeFetch());
        assertTrue("the delayed copy still overlaps the following fetch",
                followingFetch.haltSetDelayed());
        assertTrue(followingFetch.instructionRegisterLoad());
        assertTrue(followingFetch.iduIncrement());
        assertTrue(followingFetch.pcWrite());
        assertTrue("there is no delayed next-fetch PC gate", followingFetch.pcIncrement());

        DmgCpuControlLatchIsland.Observation followingBoundary = island.step(
                DmgCpuControlLatchIsland.Inputs.boundary(NONE));
        assertFalse("the delayed set copy decays at the following CPU boundary",
                followingBoundary.haltSetDelayed());
    }

    @Test
    public void productionHaltBugMatchesTheUnchangedAddressLeftByHaltOwnIduCycle() {
        CpuFixture production = cpuFixture(0x76, 0x04, 0x00); // HALT; INC B; NOP
        int timer = 1 << Timer.ordinal();
        production.interrupts.setByte(IE, timer);
        production.interrupts.requestInterrupt(Timer);

        production.tickMachineCycle();
        assertEquals(Cpu.State.OPCODE, production.cpu.getState());
        assertEquals(PROGRAM + 1, production.cpu.getRegisters().getPC());

        production.tickMachineCycle();
        assertEquals(1, production.cpu.getRegisters().getB());
        assertEquals(PROGRAM + 1, production.cpu.getRegisters().getPC());
        production.tickMachineCycle();
        assertEquals(2, production.cpu.getRegisters().getB());
        assertEquals(PROGRAM + 2, production.cpu.getRegisters().getPC());

        DmgCpuControlLatchIsland island = pendingIsland(timer, false);
        var halt = island.step(
                DmgCpuControlLatchIsland.Inputs.boundary(HALT).withOpcodeFetch());
        var following = island.step(
                DmgCpuControlLatchIsland.Inputs.idle().withOpcodeFetch());
        assertTrue(halt.pcWrite());
        assertFalse(halt.iduIncrement());
        assertFalse(halt.pcIncrement());
        assertTrue(following.haltSetDelayed());
        assertTrue(following.iduIncrement());
        assertTrue(following.pcWrite());
        assertTrue(following.pcIncrement());
    }

    @Test
    public void aRealHaltPreloadsTheNextOpcodeThenItsDelayedSetDecays() {
        int timer = 1 << Timer.ordinal();
        DmgCpuControlLatchIsland island = new DmgCpuControlLatchIsland();
        island.step(DmgCpuControlLatchIsland.Inputs.idle().withBusWrite(to(IE, timer)));

        DmgCpuControlLatchIsland.Observation entry = island.step(
                DmgCpuControlLatchIsland.Inputs.boundary(HALT).withOpcodeFetch());
        assertTrue(entry.halted());
        assertTrue(entry.instructionRegisterLoad());
        assertTrue(entry.pcWrite());
        assertFalse(entry.iduIncrement());
        assertFalse(entry.pcIncrement());

        // The delayed copy is only a one-cycle set pulse. The HALT SR latch retains the state
        // after that pulse decays; the already loaded next opcode remains ready for wake.
        DmgCpuControlLatchIsland.Observation idle = island.step(
                DmgCpuControlLatchIsland.Inputs.idle().atCpuClock());
        assertTrue(idle.halted());
        assertFalse(idle.haltSetDelayed());
        assertFalse(idle.instructionRegisterLoad());

        island.step(DmgCpuControlLatchIsland.Inputs.idle()
                .withRawRequests(timer).atDataSample());
        DmgCpuControlLatchIsland.Observation wake = island.step(
                DmgCpuControlLatchIsland.Inputs.idle().atCpuClock().withOpcodeFetch());
        assertFalse(wake.halted());
        assertTrue(wake.instructionRegisterLoad());
        assertTrue(wake.iduIncrement());
        assertTrue(wake.pcWrite());
        assertTrue("ordinary wake has no leftover halt-bug gate", wake.pcIncrement());
    }

    @Test
    public void productionOrdinaryWakeHasNoDuplicatedFetchAfterOneIdleClock() {
        CpuFixture production = cpuFixture(0x76, 0x04, 0x00); // HALT; INC B; NOP
        int timer = 1 << Timer.ordinal();
        production.interrupts.setByte(IE, timer);
        production.tickMachineCycle();
        assertEquals(Cpu.State.HALTED, production.cpu.getState());

        production.tickMachineCycle();
        production.interrupts.requestInterrupt(Timer);
        production.tickMachineCycle();
        assertEquals(1, production.cpu.getRegisters().getB());
        assertEquals(PROGRAM + 2, production.cpu.getRegisters().getPC());

        DmgCpuControlLatchIsland island = new DmgCpuControlLatchIsland();
        island.step(DmgCpuControlLatchIsland.Inputs.idle().withBusWrite(to(IE, timer)));
        island.step(DmgCpuControlLatchIsland.Inputs.boundary(HALT).withOpcodeFetch());
        island.step(DmgCpuControlLatchIsland.Inputs.idle().atCpuClock());
        island.step(DmgCpuControlLatchIsland.Inputs.idle()
                .withRawRequests(timer).atDataSample());
        var wake = island.step(
                DmgCpuControlLatchIsland.Inputs.idle().atCpuClock().withOpcodeFetch());
        assertTrue(wake.instructionRegisterLoad());
        assertTrue(wake.pcIncrement());
    }

    @Test
    public void eiThenHaltConsumesTheDelayLatchBeforeHaltDecision() {
        int timer = 1 << Timer.ordinal();
        DmgCpuControlLatchIsland island = new DmgCpuControlLatchIsland();
        island.step(DmgCpuControlLatchIsland.Inputs.boundary(EI));
        assertFalse(island.observation().ime());
        assertTrue(island.observation().eiPending());

        island.step(DmgCpuControlLatchIsland.Inputs.idle().withBusWrite(to(IE, timer)));
        island.step(DmgCpuControlLatchIsland.Inputs.idle()
                .withRawRequests(timer).atDataSample());
        island.step(DmgCpuControlLatchIsland.Inputs.idle().atCpuClock());

        DmgCpuControlLatchIsland.Observation halt = island.step(
                DmgCpuControlLatchIsland.Inputs.boundary(HALT).withOpcodeFetch());
        assertTrue(halt.ime());
        assertFalse(halt.eiPending());
        assertFalse(halt.halted());
        assertTrue("the sequencer sees the dispatch level", halt.dispatchRequest());
        assertTrue("the HALT bus cycle still samples the next opcode",
                halt.instructionRegisterLoad());
        assertTrue(halt.pcWrite());
        assertFalse(halt.iduIncrement());
        assertFalse(halt.pcIncrement());
    }

    @Test
    public void eiDiRetiLatchSequencesMatchTheProductionInterruptManager() {
        DmgCpuControlLatchIsland.Control[] alphabet = {NONE, EI, DI, RETI, HALT};
        for (int encoded = 0; encoded < 625; encoded++) {
            int value = encoded;
            List<DmgCpuControlLatchIsland.Control> sequence = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                sequence.add(alphabet[value % alphabet.length]);
                value /= alphabet.length;
            }

            DmgCpuControlLatchIsland island = new DmgCpuControlLatchIsland();
            InterruptManager production = new InterruptManager(false);
            for (DmgCpuControlLatchIsland.Control control : sequence) {
                applyProductionControl(production, control);
                DmgCpuControlLatchIsland.Observation candidate = island.step(
                        DmgCpuControlLatchIsland.Inputs.boundary(control));
                assertEquals(sequence + " IME after " + control,
                        production.isIme(), candidate.ime());
                assertEquals(sequence + " EI pending after " + control,
                        production.isInterruptEnablePending(), candidate.eiPending());
            }
        }
    }

    @Test
    public void productionEiHaltRaceHasTheSameControlLatchOutcome() {
        Ram memory = new Ram(0, 0x10000);
        InterruptManager interrupts = new InterruptManager(false);
        Cpu cpu = new Cpu(memory, interrupts, null, new SpeedMode(false), new Display(false));
        memory.setByte(PROGRAM, 0xfb);     // EI
        memory.setByte(PROGRAM + 1, 0x76); // HALT
        memory.setByte(PROGRAM + 2, 0x00);
        cpu.getRegisters().setPC(PROGRAM);

        tick(cpu, 4);
        int timer = 1 << Timer.ordinal();
        interrupts.setByte(IE, timer);
        interrupts.requestInterrupt(Timer);
        tick(cpu, 4);

        assertTrue(interrupts.isIme());
        assertEquals(Cpu.State.OPCODE, cpu.getState());
        assertEquals(PROGRAM + 1, cpu.getRegisters().getPC());

        DmgCpuControlLatchIsland island = new DmgCpuControlLatchIsland();
        island.step(DmgCpuControlLatchIsland.Inputs.boundary(EI));
        island.step(DmgCpuControlLatchIsland.Inputs.idle().withBusWrite(to(IE, timer)));
        island.step(DmgCpuControlLatchIsland.Inputs.idle()
                .withRawRequests(timer).atDataSample());
        island.step(DmgCpuControlLatchIsland.Inputs.idle().atCpuClock());
        DmgCpuControlLatchIsland.Observation candidate = island.step(
                DmgCpuControlLatchIsland.Inputs.boundary(HALT).withOpcodeFetch());

        assertEquals(interrupts.isIme(), candidate.ime());
        assertFalse(candidate.halted());
        assertTrue(candidate.dispatchRequest());
        assertTrue(candidate.instructionRegisterLoad());
        assertTrue(candidate.pcWrite());
        assertFalse(candidate.iduIncrement());
    }

    @Test
    public void ieCanOnlyChangeAcceptanceWhenItsRealWriteStrobeExists() {
        int timer = 1 << Timer.ordinal();
        DmgCpuControlLatchIsland island = pendingIsland(timer, true);
        assertTrue(island.observation().dispatchRequest());

        // There is intentionally no opcode/operand input here. Before WR exposes FFFF and zero,
        // the signal island cannot know that a future instruction will disable IE.
        DmgCpuControlLatchIsland.Observation beforeWrite = island.step(
                DmgCpuControlLatchIsland.Inputs.idle().atDataSample());
        assertEquals(timer, beforeWrite.interruptEnable());
        assertEquals(timer, beforeWrite.runningPending());
        assertTrue(beforeWrite.dispatchRequest());

        DmgCpuControlLatchIsland.Observation write = island.step(
                DmgCpuControlLatchIsland.Inputs.idle()
                        .withBusWrite(to(IE, 0)).atDataSample());
        assertEquals(0, write.interruptEnable());
        assertEquals(0, write.runningPending());
        assertFalse(write.dispatchRequest());
        assertTrue(island.falsifiers().contains(
                DmgCpuControlLatchIsland.Falsifier.EARLY_IE_WRITE_REQUIRES_BUS_REANCHOR));
    }

    @Test
    public void sameTimestampOutcomesDoNotDependOnPrimitiveEvaluationOrCommitOrder() {
        List<DmgCpuControlLatchIsland.State> seeds = List.of(
                new DmgCpuControlLatchIsland.State(0, 0, 0,
                        false, false, false, false, false),
                new DmgCpuControlLatchIsland.State(0x15, 0x1f, 0x05,
                        true, false, true, true, false),
                new DmgCpuControlLatchIsland.State(0x0a, 0x0f, 0x0a,
                        false, true, false, false, true));
        DmgCpuControlLatchIsland.Control[] controls = {NONE, DI, EI, RETI, HALT};

        for (DmgCpuControlLatchIsland.State seed : seeds) {
            for (int raw : new int[]{0, 1, 4, 0x1f}) {
                for (int acknowledge : new int[]{0, 1, 4}) {
                    for (int writeCase = 0; writeCase < 3; writeCase++) {
                        for (int edgeBits = 0; edgeBits < 8; edgeBits++) {
                            for (DmgCpuControlLatchIsland.Control control : controls) {
                                boolean boundary = (edgeBits & 4) != 0;
                                if (control != NONE && !boundary) {
                                    continue;
                                }
                                DmgCpuControlLatchIsland.BusWrite write = switch (writeCase) {
                                    case 0 -> DmgCpuControlLatchIsland.BusWrite.none();
                                    case 1 -> to(DmgCpuControlLatchIsland.IF, 0x0a);
                                    case 2 -> to(IE, 0x05);
                                    default -> throw new AssertionError();
                                };
                                DmgCpuControlLatchIsland.Inputs inputs =
                                        new DmgCpuControlLatchIsland.Inputs(
                                                raw,
                                                acknowledge,
                                                write,
                                                (edgeBits & 1) != 0,
                                                (edgeBits & 2) != 0,
                                                boundary,
                                                control,
                                                false,
                                                true);

                                DmgCpuControlLatchIsland forward =
                                        new DmgCpuControlLatchIsland();
                                DmgCpuControlLatchIsland reverse =
                                        new DmgCpuControlLatchIsland();
                                forward.restore(seed);
                                reverse.restore(seed);

                                var forwardObservation = forward.step(inputs, FORWARD);
                                var reverseObservation = reverse.step(inputs, REVERSE);
                                String label = seed + " " + inputs;
                                assertEquals(label, forwardObservation, reverseObservation);
                                assertEquals(label, forward.capture(), reverse.capture());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void unresolvedBoundariesAreFiniteAndNamed() {
        assertEquals(List.of(
                        DmgCpuControlLatchIsland.Falsifier.SOURCE_SET_VS_FF0F_WRITE_APERTURE,
                        DmgCpuControlLatchIsland.Falsifier.PPU_SOURCE_INPUT_PHASES,
                        DmgCpuControlLatchIsland.Falsifier.EARLY_IE_WRITE_REQUIRES_BUS_REANCHOR,
                        DmgCpuControlLatchIsland.Falsifier.VECTOR_AND_ACKNOWLEDGE_PHASES,
                        DmgCpuControlLatchIsland.Falsifier.CGB_DIRECT_INTERRUPT_PATH),
                Arrays.asList(DmgCpuControlLatchIsland.Falsifier.values()));
    }

    private static DmgCpuControlLatchIsland pendingIsland(int source, boolean ime) {
        DmgCpuControlLatchIsland island = new DmgCpuControlLatchIsland();
        if (ime) {
            island.step(DmgCpuControlLatchIsland.Inputs.boundary(RETI));
        }
        island.step(DmgCpuControlLatchIsland.Inputs.idle().withBusWrite(to(IE, source)));
        island.step(DmgCpuControlLatchIsland.Inputs.idle()
                .withRawRequests(source).atDataSample());
        island.step(DmgCpuControlLatchIsland.Inputs.idle().atCpuClock());
        return island;
    }

    private static void applyProductionControl(
            InterruptManager interrupts,
            DmgCpuControlLatchIsland.Control control) {
        switch (control) {
            case EI -> interrupts.enableInterrupts(true);
            case DI -> interrupts.disableInterrupts(true);
            case RETI -> interrupts.enableInterrupts(false);
            case NONE, HALT -> { }
        }
        interrupts.onInstructionFinished();
    }

    private static void tick(Cpu cpu, int ticks) {
        for (int i = 0; i < ticks; i++) {
            cpu.tick();
        }
    }

    private static CpuFixture cpuFixture(int... program) {
        Ram memory = new Ram(0, 0x10000);
        for (int i = 0; i < program.length; i++) {
            memory.setByte(PROGRAM + i, program[i]);
        }
        InterruptManager interrupts = new InterruptManager(false);
        Cpu cpu = new Cpu(memory, interrupts, null, new SpeedMode(false), new Display(false));
        cpu.getRegisters().setPC(PROGRAM);
        return new CpuFixture(cpu, interrupts);
    }

    private record CpuFixture(Cpu cpu, InterruptManager interrupts) {

        private void tickMachineCycle() {
            tick(cpu, 4);
        }
    }
}
