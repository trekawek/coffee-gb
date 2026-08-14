package eu.rekawek.coffeegb.core.experimental.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Display;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.Control.DI;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.Control.EI;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.CycleKind.MEMORY_READ;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.CycleKind.MEMORY_WRITE;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.CycleKind.OPCODE_FETCH;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.CycleKind.OPERAND_FETCH;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.CycleKind.STACK_READ;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.CycleKind.STACK_WRITE;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.Model.DOUBLE;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.Model.NORMAL;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.RunState.HALT_REPLAY;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.TState.T1;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.TState.T2;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.TState.T3;
import static eu.rekawek.coffeegb.core.experimental.cpu.CpuBusCycleMachine.TState.T4;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Differential and falsification tests for a persistent T1-T4 CPU bus-cycle seam. */
public class CpuBusCycleMachineTest {

    private static final int PROGRAM = 0x0100;

    @Test
    public void tStatesPersistForTwoHalfDotsNormallyAndOneAtDoubleSpeed() {
        assertTStateTrace(NORMAL, List.of(T1, T1, T2, T2, T3, T3, T4, T4));
        assertTStateTrace(DOUBLE, List.of(T1, T2, T3, T4));
    }

    @Test
    public void strobesAndTerminalEdgesArePropertiesOfTheActiveCycle() {
        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.read(MEMORY_READ, 0xc123),
                CpuBusCycleMachine.Cycle.writeAndFinish(MEMORY_WRITE, 0xc124, 0x5a));
        machine.poke(0xc123, 0xa5);

        List<CpuBusCycleMachine.Observation> trace = drain(machine);
        List<CpuBusCycleMachine.Observation> read = trace.subList(0, 8);
        assertFalse(read.get(0).bus().read());
        assertTrue(read.get(2).bus().read());
        assertEquals(Integer.valueOf(0xa5), read.get(2).bus().data());
        assertTrue(read.get(4).bus().read());
        assertFalse(read.get(6).bus().read());
        assertFalse(read.get(6).bus().sampleOrCommit());
        assertTrue(read.get(7).bus().sampleOrCommit());
        assertEquals(Integer.valueOf(0xa5), read.get(7).bus().sampledData());

        List<CpuBusCycleMachine.Observation> write = trace.subList(8, 16);
        assertTrue(write.get(2).bus().write());
        assertEquals(Integer.valueOf(0x5a), write.get(2).bus().data());
        assertTrue(write.get(7).bus().sampleOrCommit());
        assertEquals(0x5a, machine.peekMemory(0xc124));
        assertEquals(0x5a, machine.heldBusData());
    }

    @Test
    public void representativeFetchReadAndWriteCyclesMatchProductionTerminalAccesses() {
        assertProductionMatchesModel(
                new Program(new int[]{0x00}, registers -> { }),
                new CpuBusCycleMachine.Cycle[]{
                        CpuBusCycleMachine.Cycle.finishFetch(PROGRAM, CpuBusCycleMachine.Control.NONE)
                });

        assertProductionMatchesModel(
                new Program(new int[]{0x7e}, registers -> registers.setHL(0xc123),
                        new MemoryValue(0xc123, 0xa5)),
                new CpuBusCycleMachine.Cycle[]{
                        CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                        CpuBusCycleMachine.Cycle.readAndFinish(MEMORY_READ, 0xc123)
                });

        assertProductionMatchesModel(
                new Program(new int[]{0x77}, registers -> {
                    registers.setHL(0xc124);
                    registers.setA(0x5a);
                }),
                new CpuBusCycleMachine.Cycle[]{
                        CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                        CpuBusCycleMachine.Cycle.writeAndFinish(MEMORY_WRITE, 0xc124, 0x5a)
                });

        assertProductionMatchesModel(
                new Program(new int[]{0xf0, 0x0f}, registers -> { },
                        new MemoryValue(0xff0f, 0xe5)),
                new CpuBusCycleMachine.Cycle[]{
                        CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                        CpuBusCycleMachine.Cycle.read(OPERAND_FETCH, PROGRAM + 1),
                        CpuBusCycleMachine.Cycle.readAndFinish(MEMORY_READ, 0xff0f)
                });
    }

    @Test
    public void pushAndPopExposeInternalAndStackCyclesWithoutOperationPreview() {
        Program push = new Program(new int[]{0xc5}, registers -> {
            registers.setBC(0x1234);
            registers.setSP(0xd000);
        });
        assertProductionMatchesModel(
                push,
                new CpuBusCycleMachine.Cycle[]{
                        CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                        CpuBusCycleMachine.Cycle.internal(),
                        CpuBusCycleMachine.Cycle.write(STACK_WRITE, 0xcfff, 0x12),
                        CpuBusCycleMachine.Cycle.writeAndFinish(STACK_WRITE, 0xcffe, 0x34)
                });

        Program pop = new Program(
                new int[]{0xc1},
                registers -> registers.setSP(0xd000),
                new MemoryValue(0xd000, 0x78),
                new MemoryValue(0xd001, 0x56));
        assertProductionMatchesModel(
                pop,
                new CpuBusCycleMachine.Cycle[]{
                        CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                        CpuBusCycleMachine.Cycle.read(STACK_READ, 0xd000),
                        CpuBusCycleMachine.Cycle.readAndFinish(STACK_READ, 0xd001)
                });
    }

    @Test
    public void interruptEntryUsesTwoStackCyclesAndAnInternalOneHotAcknowledgeWire() {
        CpuFixture fixture = new CpuFixture(false);
        fixture.cpu.getRegisters().setPC(0x1234);
        fixture.cpu.getRegisters().setSP(0xd000);
        int timer = 1 << Timer.ordinal();
        fixture.interrupts.setByte(0xffff, timer);
        fixture.interrupts.requestInterrupt(Timer);
        fixture.interrupts.enableInterrupts(false);
        fixture.tickMachineCycles(5);

        List<Access> stackWrites = fixture.bus.accesses.stream()
                .filter(access -> access.type() == AccessType.WRITE && access.address() < 0xff00)
                .toList();
        assertEquals(List.of(
                new Access(12, AccessType.WRITE, 0xcfff, 0x12),
                new Access(16, AccessType.WRITE, 0xcffe, 0x34)), stackWrites);

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.internal(),
                CpuBusCycleMachine.Cycle.internal(),
                CpuBusCycleMachine.Cycle.write(STACK_WRITE, 0xcfff, 0x12),
                CpuBusCycleMachine.Cycle.interruptWrite(0xcffe, 0x34, timer),
                CpuBusCycleMachine.Cycle.interruptVector());
        machine.setInterruptLines(timer, timer);
        List<CpuBusCycleMachine.Observation> trace = drain(machine);

        List<CpuBusCycleMachine.Observation> acknowledge = trace.stream()
                .filter(observation -> observation.bus().interruptAcknowledge() != 0)
                .toList();
        assertEquals(1, acknowledge.size());
        assertEquals(timer, acknowledge.get(0).bus().interruptAcknowledge());
        assertEquals(T4, acknowledge.get(0).tState());
        assertEquals(0, machine.interruptRequests());

        // Production's IRQ_PUSH_2 callback performs three logically concurrent operations. IF
        // and IE should instead be internal wires; putting them on the external bus would conflict
        // with the low-byte stack write represented above.
        assertEquals(List.of(
                new Access(16, AccessType.READ, 0xff0f, 0xe5),
                new Access(16, AccessType.READ, 0xffff, timer),
                new Access(16, AccessType.WRITE, 0xcffe, 0x34)),
                fixture.bus.accesses.stream().filter(access -> access.tick() == 16).toList());
    }

    @Test
    public void hdmaCanObserveTheInFlightWriteAndHeldOpcodeInsteadOfPreviewingOps() {
        CpuFixture fixture = new CpuFixture(false);
        fixture.bus.poke(PROGRAM, 0x77);
        fixture.cpu.getRegisters().setPC(PROGRAM);
        fixture.cpu.getRegisters().setHL(0xc123);
        fixture.cpu.getRegisters().setA(0x5a);

        fixture.tickMaster(4); // opcode callback; the write is queued as the next micro-operation
        fixture.tickMaster(2); // production clockCycle == 2
        assertTrue(fixture.cpu.hasInFlightWriteCycleForHdma());
        assertEquals(0x77, fixture.cpu.getBusValueForHdma());

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                CpuBusCycleMachine.Cycle.writeAndFinish(MEMORY_WRITE, 0xc123, 0x5a));
        machine.poke(PROGRAM, 0x77);
        stepHalfDots(machine, 10); // opcode T4 completed, write T1 completed
        CpuBusCycleMachine.Observation writeT2 = machine.peek();
        assertEquals(T2, writeT2.tState());
        assertTrue(writeT2.bus().write());
        assertEquals(Integer.valueOf(0xc123), writeT2.bus().address());
        assertEquals(Integer.valueOf(0x5a), writeT2.bus().data());
        assertEquals(0x77, writeT2.heldBusData());
    }

    @Test
    public void hdmaOpcodeClaimBecomesObservationOfThePersistentFetchCycle() {
        CpuFixture fixture = new CpuFixture(false);
        fixture.bus.poke(PROGRAM, 0x00);
        fixture.cpu.getRegisters().setPC(PROGRAM);
        fixture.tickMaster(2); // production clockCycle == 2
        assertTrue(fixture.cpu.claimCpuRequestSlotForHdma());
        assertEquals(List.of(new Access(2, AccessType.READ, PROGRAM, 0x00)), fixture.bus.accesses);

        // The production helper has sampled and retained the byte: completing the callback at T4
        // consumes the latch without another address-space access.
        fixture.tickMaster(2);
        assertEquals(1, fixture.bus.accesses.size());

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.finishFetch(
                        PROGRAM, CpuBusCycleMachine.Control.NONE));
        machine.poke(PROGRAM, 0x00);
        stepHalfDots(machine, 4);
        CpuBusCycleMachine.Observation opcodeT3 = machine.peek();
        assertEquals(T3, opcodeT3.tState());
        assertTrue(opcodeT3.bus().read());
        assertEquals(Integer.valueOf(PROGRAM), opcodeT3.bus().address());
        assertEquals(Integer.valueOf(0x00), opcodeT3.bus().data());
    }

    @Test
    public void futureDiPreviewBecomesOpcodeBusObservationWithoutASecondRead() {
        CpuFixture fixture = new CpuFixture(true);
        fixture.bus.poke(PROGRAM, 0xf3);
        fixture.cpu.getRegisters().setPC(PROGRAM);
        fixture.tickMaster(1); // production clockCycle == 1, before its atomic fetch callback
        assertTrue(fixture.cpu.doesMode0InstructionWinInterruptAcceptance(true));
        assertEquals(List.of(new Access(1, AccessType.READ, PROGRAM, 0xf3)), fixture.bus.accesses);

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL, CpuBusCycleMachine.Cycle.finishFetch(PROGRAM, DI));
        machine.poke(PROGRAM, 0xf3);
        stepHalfDots(machine, 2);
        CpuBusCycleMachine.Observation opcodeT2 = machine.peek();
        assertEquals(T2, opcodeT2.tState());
        assertTrue(opcodeT2.bus().read());
        assertEquals(Integer.valueOf(PROGRAM), opcodeT2.bus().address());
        assertEquals(Integer.valueOf(0xf3), opcodeT2.bus().data());
        assertFalse(machine.ime());
    }

    @Test
    public void earlyIeWritePreviewCannotBeReplacedAtTheSamePhaseByCurrentCycleSignals() {
        CpuFixture fixture = new CpuFixture(true);
        fixture.bus.poke(PROGRAM, 0x08); // LD (a16),SP
        fixture.bus.poke(PROGRAM + 1, 0xff);
        fixture.bus.poke(PROGRAM + 2, 0xff);
        fixture.cpu.getRegisters().setPC(PROGRAM);
        fixture.cpu.getRegisters().setSP(0x0000); // first write clears every IE source
        fixture.tickMaster(1);

        assertTrue(fixture.cpu.doesMode0InstructionWinInterruptAcceptance(true));
        assertEquals(List.of(
                new Access(1, AccessType.READ, PROGRAM, 0x08),
                new Access(1, AccessType.READ, PROGRAM + 1, 0xff),
                new Access(1, AccessType.READ, PROGRAM + 2, 0xff)), fixture.bus.accesses);

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                CpuBusCycleMachine.Cycle.read(OPERAND_FETCH, PROGRAM + 1),
                CpuBusCycleMachine.Cycle.read(OPERAND_FETCH, PROGRAM + 2),
                CpuBusCycleMachine.Cycle.write(MEMORY_WRITE, 0xffff, 0x00),
                CpuBusCycleMachine.Cycle.writeAndFinish(MEMORY_WRITE, 0x0000, 0x00));
        machine.poke(PROGRAM, 0x08);
        machine.poke(PROGRAM + 1, 0xff);
        machine.poke(PROGRAM + 2, 0xff);
        stepHalfDots(machine, 2);
        CpuBusCycleMachine.Observation opcodeT2 = machine.peek();
        assertEquals(Integer.valueOf(PROGRAM), opcodeT2.bus().address());
        assertEquals(Integer.valueOf(0x08), opcodeT2.bus().data());
        assertFalse("the future IE address is not yet on the bus",
                Integer.valueOf(0xffff).equals(opcodeT2.bus().address()));

        stepHalfDots(machine, 24); // advance to the first write cycle's T2
        CpuBusCycleMachine.Observation ieWriteT2 = machine.peek();
        assertEquals(T2, ieWriteT2.tState());
        assertTrue(ieWriteT2.bus().write());
        assertEquals(Integer.valueOf(0xffff), ieWriteT2.bus().address());
        assertEquals(Integer.valueOf(0), ieWriteT2.bus().data());
    }

    @Test
    public void ifReadMaskMetadataCorrespondsToAnAlreadyStartedReadCycle() {
        CpuFixture fixture = new CpuFixture(true);
        fixture.bus.poke(PROGRAM, 0x7e); // LD A,(HL)
        fixture.cpu.getRegisters().setPC(PROGRAM);
        fixture.cpu.getRegisters().setHL(0xff0f);
        fixture.tickMaster(4);
        fixture.tickMaster(1); // RUNNING, clockCycle == 1
        int accessesBeforeQuery = fixture.bus.accesses.size();
        assertEquals(2, fixture.cpu.getInterruptFlagReadMaskTicks(true));
        assertEquals(accessesBeforeQuery, fixture.bus.accesses.size());

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                CpuBusCycleMachine.Cycle.readAndFinish(MEMORY_READ, 0xff0f));
        machine.poke(PROGRAM, 0x7e);
        machine.poke(0xff0f, 0xe1);
        stepHalfDots(machine, 10);
        assertEquals(T2, machine.peek().tState());
        assertTrue(machine.peek().bus().read());
        assertEquals(Integer.valueOf(0xff0f), machine.peek().bus().address());
    }

    @Test
    public void haltDoubleReadInOneProductionCallbackFalsifiesDirectSingleCycleCutover() {
        CpuFixture fixture = new CpuFixture(false);
        fixture.bus.poke(PROGRAM, 0x76);
        fixture.bus.poke(PROGRAM + 1, 0x00);
        fixture.cpu.getRegisters().setPC(PROGRAM);
        fixture.tickMachineCycles(1);

        assertEquals(Cpu.State.HALTED, fixture.cpu.getState());
        assertEquals(List.of(
                new Access(4, AccessType.READ, PROGRAM, 0x76),
                new Access(4, AccessType.READ, PROGRAM + 1, 0x00)), fixture.bus.accesses);

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM),
                CpuBusCycleMachine.Cycle.haltSample(PROGRAM + 1));
        machine.poke(PROGRAM, 0x76);
        machine.poke(PROGRAM + 1, 0x00);
        List<Access> modelAccesses = modelAccesses(machine);
        assertEquals(List.of(
                new Access(4, AccessType.READ, PROGRAM, 0x76),
                new Access(8, AccessType.READ, PROGRAM + 1, 0x00)), modelAccesses);
    }

    @Test
    public void eiHaltRaceHasSameLatchSemanticsButExposesThePipelineTimingDebt() {
        CpuFixture fixture = new CpuFixture(false);
        fixture.bus.poke(PROGRAM, 0xfb);
        fixture.bus.poke(PROGRAM + 1, 0x76);
        fixture.bus.poke(PROGRAM + 2, 0x00);
        fixture.cpu.getRegisters().setPC(PROGRAM);
        fixture.tickMachineCycles(1); // EI
        int timer = 1 << Timer.ordinal();
        fixture.interrupts.setByte(0xffff, timer);
        fixture.interrupts.requestInterrupt(Timer);
        fixture.tickMachineCycles(1); // HALT and its next-opcode sample

        assertTrue(fixture.interrupts.isIme());
        assertEquals(Cpu.State.OPCODE, fixture.cpu.getState());
        assertEquals(PROGRAM + 1, fixture.cpu.getRegisters().getPC());

        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                NORMAL,
                CpuBusCycleMachine.Cycle.finishFetch(PROGRAM, EI),
                CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM + 1),
                CpuBusCycleMachine.Cycle.haltSample(PROGRAM + 2));
        machine.poke(PROGRAM, 0xfb);
        machine.poke(PROGRAM + 1, 0x76);
        machine.poke(PROGRAM + 2, 0x00);
        stepHalfDots(machine, 8); // EI completes
        machine.setInterruptLines(timer, timer);
        drain(machine);

        assertTrue(machine.ime());
        assertEquals(HALT_REPLAY, machine.runState());
        assertEquals(12, modelAccessesForScriptLength(3).get(2).tick());
        assertEquals("production collapses HALT's two reads at tick 8", 8,
                fixture.bus.accesses.get(fixture.bus.accesses.size() - 1).tick());
    }

    @Test
    public void currentLateReadCalibrationIsOneMachineCycleBehindAPrefetchedBoundary() {
        CpuFixture ldHl = new CpuFixture(false);
        ldHl.bus.poke(PROGRAM, 0x7e);
        ldHl.bus.poke(0xc123, 0xa5);
        ldHl.cpu.getRegisters().setPC(PROGRAM);
        ldHl.cpu.getRegisters().setHL(0xc123);
        ldHl.runOneInstruction();
        assertEquals(8, findAccess(ldHl.bus.accesses, AccessType.READ, 0xc123).tick());

        CpuFixture ldh = new CpuFixture(false);
        ldh.bus.poke(PROGRAM, 0xf0);
        ldh.bus.poke(PROGRAM + 1, 0x05);
        ldh.bus.poke(0xff05, 0xa5);
        ldh.cpu.getRegisters().setPC(PROGRAM);
        ldh.runOneInstruction();
        assertEquals(12, findAccess(ldh.bus.accesses, AccessType.READ, 0xff05).tick());

        // With the opcode already held at the instruction boundary, the corresponding data phases
        // are +4 and +8 T. Matching Coffee GB requires retaining one opcode-fetch M-cycle: removing
        // it is a global re-anchor, not a local conversion from callbacks to persistent bus state.
        assertEquals(4, findAccess(ldHl.bus.accesses, AccessType.READ, 0xc123).tick() - 4);
        assertEquals(4, findAccess(ldh.bus.accesses, AccessType.READ, 0xff05).tick() - 8);
    }

    private static void assertTStateTrace(
            CpuBusCycleMachine.Model model,
            List<CpuBusCycleMachine.TState> expected) {
        CpuBusCycleMachine machine = new CpuBusCycleMachine(
                model, CpuBusCycleMachine.Cycle.internal());
        List<CpuBusCycleMachine.TState> actual = new ArrayList<>();
        while (machine.hasCycles()) {
            actual.add(machine.stepHalfDot().tState());
        }
        assertEquals(expected, actual);
    }

    private static void assertProductionMatchesModel(
            Program program,
            CpuBusCycleMachine.Cycle[] cycles) {
        CpuFixture fixture = new CpuFixture(false);
        for (int i = 0; i < program.bytes().length; i++) {
            fixture.bus.poke(PROGRAM + i, program.bytes()[i]);
        }
        for (MemoryValue value : program.initialMemory()) {
            fixture.bus.poke(value.address(), value.value());
        }
        fixture.cpu.getRegisters().setPC(PROGRAM);
        program.registerSetup().apply(fixture.cpu.getRegisters());
        fixture.runOneInstruction();

        CpuBusCycleMachine machine = new CpuBusCycleMachine(NORMAL, cycles);
        for (int i = 0; i < program.bytes().length; i++) {
            machine.poke(PROGRAM + i, program.bytes()[i]);
        }
        for (MemoryValue value : program.initialMemory()) {
            machine.poke(value.address(), value.value());
        }
        assertEquals(fixture.bus.accesses, modelAccesses(machine));
    }

    private static List<Access> modelAccesses(CpuBusCycleMachine machine) {
        List<Access> accesses = new ArrayList<>();
        while (machine.hasCycles()) {
            CpuBusCycleMachine.Observation observation = machine.stepHalfDot();
            if (!observation.bus().sampleOrCommit() || observation.bus().address() == null) {
                continue;
            }
            int tick = (int) (observation.halfDot() / 2) + 1;
            if (observation.cycleKind().reads()) {
                accesses.add(new Access(
                        tick,
                        AccessType.READ,
                        observation.bus().address(),
                        observation.bus().sampledData()));
            } else if (observation.cycleKind().writes()) {
                accesses.add(new Access(
                        tick,
                        AccessType.WRITE,
                        observation.bus().address(),
                        observation.bus().data() == null
                                ? machine.heldBusData()
                                : observation.bus().data()));
            }
        }
        return accesses;
    }

    private static List<Access> modelAccessesForScriptLength(int cycles) {
        CpuBusCycleMachine.Cycle[] script = new CpuBusCycleMachine.Cycle[cycles];
        for (int i = 0; i < cycles; i++) {
            script[i] = CpuBusCycleMachine.Cycle.read(OPCODE_FETCH, PROGRAM + i);
        }
        CpuBusCycleMachine machine = new CpuBusCycleMachine(NORMAL, script);
        return modelAccesses(machine);
    }

    private static List<CpuBusCycleMachine.Observation> drain(CpuBusCycleMachine machine) {
        List<CpuBusCycleMachine.Observation> observations = new ArrayList<>();
        while (machine.hasCycles()) {
            observations.add(machine.stepHalfDot());
        }
        return observations;
    }

    private static void stepHalfDots(CpuBusCycleMachine machine, int halfDots) {
        for (int i = 0; i < halfDots; i++) {
            machine.stepHalfDot();
        }
    }

    private static Access findAccess(List<Access> accesses, AccessType type, int address) {
        return accesses.stream()
                .filter(access -> access.type() == type && access.address() == address)
                .findFirst()
                .orElseThrow();
    }

    private enum AccessType {
        READ,
        WRITE
    }

    private record Access(int tick, AccessType type, int address, int value) {
    }

    private record MemoryValue(int address, int value) {
    }

    private interface RegisterSetup {

        void apply(eu.rekawek.coffeegb.core.cpu.Registers registers);
    }

    private record Program(
            int[] bytes,
            RegisterSetup registerSetup,
            MemoryValue... initialMemory) {
    }

    private static final class TraceAddressSpace implements AddressSpace {

        private final int[] memory = new int[0x10000];

        private final InterruptManager interrupts;

        private final List<Access> accesses = new ArrayList<>();

        private int tick;

        private TraceAddressSpace(InterruptManager interrupts) {
            this.interrupts = interrupts;
            java.util.Arrays.fill(memory, 0xff);
        }

        @Override
        public boolean accepts(int address) {
            return true;
        }

        @Override
        public void setByte(int address, int value) {
            int normalizedAddress = address & 0xffff;
            int normalizedValue = value & 0xff;
            accesses.add(new Access(tick, AccessType.WRITE, normalizedAddress, normalizedValue));
            if (interrupts.accepts(normalizedAddress)) {
                interrupts.setByte(normalizedAddress, normalizedValue);
            } else {
                memory[normalizedAddress] = normalizedValue;
            }
        }

        @Override
        public int getByte(int address) {
            int normalizedAddress = address & 0xffff;
            int value = interrupts.accepts(normalizedAddress)
                    ? interrupts.getByte(normalizedAddress)
                    : memory[normalizedAddress];
            accesses.add(new Access(tick, AccessType.READ, normalizedAddress, value));
            return value;
        }

        void poke(int address, int value) {
            int normalizedAddress = address & 0xffff;
            if (interrupts.accepts(normalizedAddress)) {
                interrupts.setByte(normalizedAddress, value);
            } else {
                memory[normalizedAddress] = value & 0xff;
            }
        }
    }

    private static final class CpuFixture {

        private final InterruptManager interrupts;

        private final TraceAddressSpace bus;

        private final Cpu cpu;

        private int tick;

        private CpuFixture(boolean gbc) {
            interrupts = new InterruptManager(gbc);
            bus = new TraceAddressSpace(interrupts);
            cpu = new Cpu(bus, interrupts, null, new SpeedMode(gbc), new Display(gbc));
            cpu.getRegisters().setPC(PROGRAM);
            cpu.getRegisters().setSP(0xfffe);
        }

        void tickMaster(int count) {
            for (int i = 0; i < count; i++) {
                tick++;
                bus.tick = tick;
                cpu.tick();
            }
        }

        void tickMachineCycles(int count) {
            tickMaster(count * 4);
        }

        void runOneInstruction() {
            do {
                tickMaster(1);
            } while (cpu.getState() != Cpu.State.OPCODE || tick < 4);
        }
    }
}
