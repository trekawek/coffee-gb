package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Contract tests for the allocation-free native-CGB and physical-DMG CPU bus fences. */
public final class CpuPerformanceEpochTest {

    @Test
    public void unsafeWriteIsDeferredAndReplayedOnce() {
        CountingMemory memory = new CountingMemory();
        memory.bytes[0] = 0x3e; // LD A,1
        memory.bytes[1] = 0x01;
        memory.bytes[2] = (byte) 0xe0; // LDH (FF40),A
        memory.bytes[3] = 0x40;
        InterruptManager interrupts = new InterruptManager(true);
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        assertTrue(speed.onStop());
        Cpu cpu = new Cpu(memory, interrupts, null, speed, new Display(false));

        int elapsed = cpu.runPerformanceEpoch(54);

        assertTrue(elapsed > 0);
        assertEquals("unsafe write must not reach the target before replay", 0, memory.writes);
        assertTrue(cpu.hasPerformanceEpochJournal());
        assertTrue(cpu.replayPerformanceEpochJournal());
        assertEquals(1, memory.writes);
        assertEquals(0xff40, memory.lastWriteAddress);
        assertEquals(1, cpu.getPerformanceEpochTerminalAccesses());
        assertTrue("journal is one-shot", !cpu.replayPerformanceEpochJournal());
    }

    @Test
    public void normalSpeedNeverEntersNativeEpoch() {
        CountingMemory memory = new CountingMemory();
        memory.bytes[0] = 0x00;
        Cpu cpu = new Cpu(memory, new InterruptManager(true), null,
                new SpeedMode(true), new Display(false));
        assertEquals(0, cpu.runPerformanceEpoch(54));
        assertEquals(0L, cpu.getPerformanceEpochCount());
    }

    @Test
    public void physicalDmgFourDotEpochMatchesScalarRomWramLoopAtEveryBudget()
            throws Exception {
        for (int entryPhase = 0; entryPhase < 4; entryPhase++) {
            for (int budget = 1; budget <= Cpu.PERFORMANCE_EPOCH_MAX_TICKS; budget++) {
                ParityMemory directMemory = new ParityMemory();
                directMemory.bytes[0x0000] = 0x21; // LD HL,C000
                directMemory.bytes[0x0001] = 0x00;
                directMemory.bytes[0x0002] = (byte) 0xc0;
                directMemory.bytes[0x0003] = 0x7e; // LD A,(HL)
                directMemory.bytes[0x0004] = 0x3c; // INC A
                directMemory.bytes[0x0005] = 0x77; // LD (HL),A
                directMemory.bytes[0x0006] = 0x18; // JR 0003
                directMemory.bytes[0x0007] = (byte) 0xfb;
                directMemory.bytes[0xc000] = 0x23;
                ParityMemory scalarMemory = new ParityMemory();
                System.arraycopy(directMemory.bytes, 0, scalarMemory.bytes, 0,
                        directMemory.bytes.length);

                InterruptManager directInterrupts = new InterruptManager(false);
                InterruptManager scalarInterrupts = new InterruptManager(false);
                Cpu direct = new Cpu(directMemory, directInterrupts, null,
                        new SpeedMode(false), new Display(false));
                Cpu scalar = new Cpu(scalarMemory, scalarInterrupts, null,
                        new SpeedMode(false), new Display(false));
                CpuPair pair = new CpuPair(direct, scalar, directInterrupts, scalarInterrupts,
                        directMemory, scalarMemory);

                for (int tick = 0; tick < entryPhase; tick++) {
                    direct.tick();
                    scalar.tick();
                }

                assertEquals("physical-DMG phase " + entryPhase + " budget " + budget, budget,
                        direct.runPhysicalDmgPerformanceEpoch(budget));
                for (int tick = 0; tick < budget; tick++) {
                    scalar.tick();
                }
                assertCpuPairEquals(pair);
            }
        }
    }

    @Test
    public void cgbCompatibilityFourDotEpochMatchesScalarAtEveryBudget()
            throws Exception {
        for (int entryPhase = 0; entryPhase < 4; entryPhase++) {
            for (int budget = 1; budget <= Cpu.PERFORMANCE_EPOCH_MAX_TICKS; budget++) {
                ParityMemory directMemory = new ParityMemory();
                directMemory.bytes[0x0000] = 0x21; // LD HL,C000
                directMemory.bytes[0x0001] = 0x00;
                directMemory.bytes[0x0002] = (byte) 0xc0;
                directMemory.bytes[0x0003] = 0x7e; // LD A,(HL)
                directMemory.bytes[0x0004] = 0x3c; // INC A
                directMemory.bytes[0x0005] = 0x77; // LD (HL),A
                directMemory.bytes[0x0006] = 0x18; // JR 0003
                directMemory.bytes[0x0007] = (byte) 0xfb;
                directMemory.bytes[0xc000] = 0x23;
                ParityMemory scalarMemory = new ParityMemory();
                System.arraycopy(directMemory.bytes, 0, scalarMemory.bytes, 0,
                        directMemory.bytes.length);

                InterruptManager directInterrupts = new InterruptManager(true);
                InterruptManager scalarInterrupts = new InterruptManager(true);
                SpeedMode directSpeed = new SpeedMode(true);
                SpeedMode scalarSpeed = new SpeedMode(true);
                directSpeed.setDmgCompat(true);
                scalarSpeed.setDmgCompat(true);
                Cpu direct = new Cpu(directMemory, directInterrupts, null,
                        directSpeed, new Display(false));
                Cpu scalar = new Cpu(scalarMemory, scalarInterrupts, null,
                        scalarSpeed, new Display(false));
                CpuPair pair = new CpuPair(direct, scalar, directInterrupts, scalarInterrupts,
                        directMemory, scalarMemory);

                for (int tick = 0; tick < entryPhase; tick++) {
                    direct.tick();
                    scalar.tick();
                }

                assertEquals("CGB compatibility phase " + entryPhase + " budget " + budget,
                        budget, direct.runCgbCompatibilityPerformanceEpoch(budget));
                for (int tick = 0; tick < budget; tick++) {
                    scalar.tick();
                }
                assertCpuPairEquals(pair);
            }
        }
    }

    @Test
    public void nativeCgbAndPhysicalDmgEntryPointsAreTopologyIsolated() {
        Cpu physicalDmg = new Cpu(new CountingMemory(), new InterruptManager(false), null,
                new SpeedMode(false), new Display(false));
        assertFalse(physicalDmg.performanceEpochEntryEligible());
        assertEquals(0, physicalDmg.runPerformanceEpoch(54));
        assertTrue(physicalDmg.performancePhysicalDmgEpochEntryEligible());

        Cpu nativeCgb = new Cpu(new CountingMemory(), new InterruptManager(true), null,
                doubleSpeed(), new Display(false));
        assertTrue(nativeCgb.performanceEpochEntryEligible());
        assertFalse(nativeCgb.performancePhysicalDmgEpochEntryEligible());
        assertEquals(0, nativeCgb.runPhysicalDmgPerformanceEpoch(54));

        Cpu nativeNormalSpeed = new Cpu(new CountingMemory(), new InterruptManager(true), null,
                new SpeedMode(true), new Display(false));
        assertFalse(nativeNormalSpeed.performanceCgbCompatibilityEpochEntryEligible());
        assertEquals(0, nativeNormalSpeed.runCgbCompatibilityPerformanceEpoch(54));
    }

    @Test
    public void nativeEpochRomLeaseMatchesScalarAcrossCpuBankBoundary() throws Exception {
        LeasedMemory directMemory = new LeasedMemory(0x4000, 0xc000);
        directMemory.bytes[0x3fff] = 0x3e; // LD A,d8
        directMemory.bytes[0x4000] = 0x5a;
        directMemory.physicalBytes[0x7fff] = 0x3e;
        directMemory.physicalBytes[0xc000] = 0x5a;
        ParityMemory scalarMemory = new ParityMemory();
        System.arraycopy(directMemory.bytes, 0, scalarMemory.bytes, 0,
                directMemory.bytes.length);

        InterruptManager directInterrupts = new InterruptManager(true);
        InterruptManager scalarInterrupts = new InterruptManager(true);
        Cpu direct = new Cpu(directMemory, directInterrupts, null,
                doubleSpeed(), new Display(false));
        Cpu scalar = new Cpu(scalarMemory, scalarInterrupts, null,
                doubleSpeed(), new Display(false));
        direct.getRegisters().setPC(0x3fff);
        scalar.restoreState(direct.captureState());

        assertEquals(4, direct.runPerformanceEpoch(4));
        for (int i = 0; i < 4; i++) {
            scalar.tick();
        }

        assertDeepEquals("cpu", scalar.captureState(), direct.captureState());
        assertDeepEquals("interrupts", scalarInterrupts.captureState(),
                directInterrupts.captureState());
        assertEquals(0x5a, direct.getRegisters().getA());
        assertEquals(1, directMemory.leaseAcquisitions);
        assertEquals(2, directMemory.leaseReads);
        assertEquals(0x7fff, directMemory.physicalReadOffsets[0]);
        assertEquals(0xc000, directMemory.physicalReadOffsets[1]);
        assertEquals("leased instruction bytes bypass the CPU bus", 0,
                directMemory.busReads);
        assertEquals(2, scalarMemory.reads);
    }

    @Test
    public void unavailableOrUnmappedRomLeaseFallsBackToAuthoritativeBus() {
        LeasedMemory unavailable = new LeasedMemory(0, 0x4000);
        unavailable.leaseAvailable = false;
        unavailable.bytes[0] = 0x3e; // LD A,d8
        unavailable.bytes[1] = 0x61;
        Cpu unavailableCpu = new Cpu(unavailable, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));

        assertEquals(4, unavailableCpu.runPerformanceEpoch(4));
        assertEquals(0x61, unavailableCpu.getRegisters().getA());
        assertEquals(1, unavailable.leaseAcquisitions);
        assertEquals(2, unavailable.busReads);
        assertEquals(0, unavailable.leaseReads);

        LeasedMemory unmapped = new LeasedMemory(0, 0x4000);
        unmapped.mapRom = false;
        unmapped.bytes[0] = 0x3e; // LD A,d8
        unmapped.bytes[1] = 0x72;
        Cpu unmappedCpu = new Cpu(unmapped, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));

        assertEquals(4, unmappedCpu.runPerformanceEpoch(4));
        assertEquals(0x72, unmappedCpu.getRegisters().getA());
        assertEquals(1, unmapped.leaseAcquisitions);
        assertEquals(2, unmapped.leaseMapProbes);
        assertEquals(2, unmapped.busReads);
        assertEquals(0, unmapped.leaseReads);
    }

    @Test
    public void mapperWriteTerminatesLeaseAndNextEpochReacquiresNewBank() {
        LeasedMemory memory = new LeasedMemory(0, 0x4000);
        memory.bankWritesSelectUpperWindow = true;
        memory.physicalBytes[0x4000] = 0x3e; // LD A,02 in bank 1
        memory.physicalBytes[0x4001] = 0x02;
        memory.physicalBytes[0x4002] = (byte) 0xea; // LD (2000),A
        memory.physicalBytes[0x4003] = 0x00;
        memory.physicalBytes[0x4004] = 0x20;
        memory.physicalBytes[0x4005] = 0x3e; // stale-bank sentinel
        memory.physicalBytes[0x4006] = 0x11;
        memory.physicalBytes[0x8005] = 0x3e; // LD A,77 in bank 2
        memory.physicalBytes[0x8006] = 0x77;
        Cpu cpu = new Cpu(memory, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));
        cpu.getRegisters().setPC(0x4000);

        assertTrue(cpu.runPerformanceEpoch(54) > 0);
        assertTrue(cpu.hasPerformanceEpochJournal());
        assertEquals("bank must not change before journal replay", 0x4000,
                memory.selectedUpperWindowBase);
        assertTrue(cpu.replayPerformanceEpochJournal());
        assertEquals(0x8000, memory.selectedUpperWindowBase);

        assertEquals(4, cpu.runPerformanceEpoch(4));
        assertEquals(0x77, cpu.getRegisters().getA());
        assertEquals(2, memory.leaseAcquisitions);
        assertEquals(0x8005, memory.physicalReadOffsets[memory.leaseReads - 2]);
        assertEquals(0x8006, memory.physicalReadOffsets[memory.leaseReads - 1]);
    }

    @Test
    public void scalarAndPhysicalDmgFetchesNeverAcquireNativeRomLease() {
        LeasedMemory scalarMemory = new LeasedMemory(0, 0x4000);
        scalarMemory.bytes[0] = 0x00; // NOP on the authoritative bus
        scalarMemory.physicalBytes[0] = 0x76; // HALT only in the lease
        Cpu scalar = new Cpu(scalarMemory, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));

        scalar.tick();
        scalar.tick();

        assertEquals(Cpu.State.OPCODE, scalar.getState());
        assertEquals(1, scalar.getRegisters().getPC());
        assertEquals(0, scalarMemory.leaseAcquisitions);
        assertEquals(1, scalarMemory.busReads);

        LeasedMemory physicalDmgMemory = new LeasedMemory(0, 0x4000);
        physicalDmgMemory.bytes[0] = 0x00;
        physicalDmgMemory.physicalBytes[0] = 0x76;
        Cpu physicalDmg = new Cpu(physicalDmgMemory, new InterruptManager(false), null,
                new SpeedMode(false), new Display(false));

        assertEquals(4, physicalDmg.runPhysicalDmgPerformanceEpoch(4));
        assertEquals(Cpu.State.OPCODE, physicalDmg.getState());
        assertEquals(1, physicalDmg.getRegisters().getPC());
        assertEquals(0, physicalDmgMemory.leaseAcquisitions);
        assertEquals(1, physicalDmgMemory.busReads);
    }

    @Test
    public void physicalDmgOamWriteFlushesPrefixAndIsNeverJournaled() {
        CountingMemory memory = new CountingMemory();
        memory.bytes[0] = 0x3e; // LD A,42
        memory.bytes[1] = 0x42;
        memory.bytes[2] = (byte) 0xea; // LD (FE00),A
        memory.bytes[3] = 0x00;
        memory.bytes[4] = (byte) 0xfe;
        Cpu cpu = new Cpu(memory, new InterruptManager(false), null,
                new SpeedMode(false), new Display(false));
        int[] committedPrefix = {0};
        cpu.setPerformanceEpochPrefixCommitter(ticks -> committedPrefix[0] = ticks);

        int elapsed = cpu.runPhysicalDmgPerformanceEpoch(54);

        assertTrue(elapsed > committedPrefix[0]);
        assertTrue(committedPrefix[0] > 0);
        assertEquals(1, memory.writes);
        assertEquals(0xfe00, memory.lastWriteAddress);
        assertEquals(0x42, memory.bytes[0xfe00] & 0xff);
        assertFalse(cpu.hasPerformanceEpochJournal());
        assertEquals(1, cpu.getPerformanceEpochTerminalAccesses());
    }

    @Test
    public void unsafeReadFlushesCompletedPrefixAndDelegatesOnce() {
        CountingMemory memory = new CountingMemory();
        memory.bytes[0] = (byte) 0xf0; // LDH A,(FF00+a8)
        memory.bytes[1] = 0x44;
        memory.bytes[0xff44] = 0x66;
        Cpu cpu = new Cpu(memory, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));
        int[] committedPrefix = {0};
        cpu.setPerformanceEpochPrefixCommitter(ticks -> committedPrefix[0] = ticks);

        int elapsed = cpu.runPerformanceEpoch(54);

        assertTrue(elapsed > committedPrefix[0]);
        assertTrue("unsafe read did not flush its completed prefix", committedPrefix[0] > 0);
        assertEquals(1, memory.reads[0xff44]);
        assertEquals(0x66, cpu.getRegisters().getA());
        assertEquals(1, cpu.getPerformanceEpochTerminalAccesses());
        assertFalse(cpu.hasPerformanceEpochJournal());
    }

    @Test
    public void stopAndRetiRemainScalarAcrossEpochBoundaries() {
        CountingMemory stopMemory = new CountingMemory();
        stopMemory.bytes[0] = 0x10;
        stopMemory.bytes[1] = 0x00;
        Cpu stop = new Cpu(stopMemory, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));
        assertTrue(stop.runPerformanceEpoch(54) > 0);
        assertEquals(Cpu.State.EXT_OPCODE, stop.getState());
        assertEquals("pending STOP padding must remain scalar", 0, stop.runPerformanceEpoch(54));

        CountingMemory retiMemory = new CountingMemory();
        retiMemory.bytes[0] = (byte) 0xd9;
        Cpu reti = new Cpu(retiMemory, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));
        assertTrue(reti.runPerformanceEpoch(54) > 0);
        assertTrue(reti.getState() != Cpu.State.OPCODE);
        assertEquals("pending RETI retirement must remain scalar", 0, reti.runPerformanceEpoch(54));
    }

    @Test
    public void anyPendingEnabledInterruptRejectsEpochBeforeHaltBugDecode() {
        CountingMemory memory = new CountingMemory();
        memory.bytes[0] = 0x76; // HALT
        InterruptManager interrupts = new InterruptManager(true);
        interrupts.setByte(0xffff, 1);
        interrupts.requestInterrupt(InterruptManager.InterruptType.VBlank);
        Cpu cpu = new Cpu(memory, interrupts, null, doubleSpeed(), new Display(false));

        assertFalse(cpu.performanceEpochEntryEligible());
        assertEquals(0, cpu.runPerformanceEpoch(54));
        assertEquals(0, cpu.getRegisters().getPC());
    }

    @Test
    public void instructionBlockedPendingInterruptStillRejectsEpoch() {
        CountingMemory memory = new CountingMemory();
        memory.bytes[0] = 0x00;
        InterruptManager interrupts = new InterruptManager(true);
        interrupts.setByte(0xffff, 1);
        interrupts.requestPhasedInterruptAfterInstruction(
                InterruptManager.InterruptType.VBlank);
        Cpu cpu = new Cpu(memory, interrupts, null, doubleSpeed(), new Display(false));

        assertFalse(cpu.performanceEpochEntryEligible());
        assertEquals(0, cpu.runPerformanceEpoch(54));
        assertEquals(0, cpu.getRegisters().getPC());
    }

    @Test
    public void directBaseOpcodesMatchScalarPipelineForRandomizedState() throws Exception {
        Random random = new Random(0x5a17a9e1L);
        for (int opcode = 0; opcode < 0x100; opcode++) {
            if (!isDirectBaseOpcode(opcode)) {
                continue;
            }
            for (int iteration = 0; iteration < 8; iteration++) {
                assertDirectMatchesScalar(random, opcode, -1, 0, 2, -1);
            }
        }
    }

    @Test
    public void directOperandOpcodesMatchScalarPipelineForRandomizedState() throws Exception {
        Random random = new Random(0x0b9e4d31L);
        for (int opcode = 0; opcode < 0x100; opcode++) {
            int operandLength = directOperandLength(opcode);
            if (operandLength == 0) {
                continue;
            }
            for (int iteration = 0; iteration < 8; iteration++) {
                int flags = random.nextInt(0x10) << 4;
                if (isConditionalDirectOperandOpcode(opcode)) {
                    flags = forceCondition(flags, (opcode >>> 3) & 0x03, false);
                }
                assertDirectMatchesScalar(random, opcode, -1, operandLength,
                        (operandLength + 1) * 2, flags);
            }
        }
    }

    @Test
    public void directExtendedOpcodesMatchScalarPipelineForRandomizedState() throws Exception {
        Random random = new Random(0x6cb17e42L);
        for (int opcode = 0; opcode < 0x100; opcode++) {
            if ((opcode & 0x07) == 6) {
                continue;
            }
            for (int iteration = 0; iteration < 8; iteration++) {
                assertDirectMatchesScalar(random, 0xcb, opcode, 0, 4, -1);
            }
        }
    }

    @Test
    public void directDaaMatchesScalarForEveryAccumulatorAndFlagCombination() throws Exception {
        Random random = new Random(0xdaaffeL);
        for (int accumulator = 0; accumulator < 0x100; accumulator++) {
            for (int flags = 0; flags < 0x100; flags += 0x10) {
                CpuPair pair = newCpuPair(random, 0x27, -1, 0, flags);
                pair.direct.getRegisters().setA(accumulator);
                pair.scalar.getRegisters().setA(accumulator);
                assertPairAfterTicks(pair, 2);
            }
        }
    }

    @Test
    public void directCbBitPreservesCarry() throws Exception {
        Random random = new Random(0xb17ca44eL);
        for (int bit = 0; bit < 8; bit++) {
            for (int target = 0; target < 8; target++) {
                if (target == 6) {
                    continue;
                }
                int opcode = 0x40 | bit << 3 | target;
                CpuPair pair = newCpuPair(random, 0xcb, opcode, 0, 0x10);
                assertPairAfterTicks(pair, 4);
                assertTrue(pair.direct.getRegisters().getFlags().isC());
            }
        }
    }

    @Test
    public void completeDirectBranchesMatchScalarAtEveryBudget() throws Exception {
        Random random = new Random(0xb12a9c4eL);
        int[] opcodes = {0x18, 0x20, 0x28, 0x30, 0x38,
                0xc2, 0xca, 0xd2, 0xda, 0xc3};
        for (int opcode : opcodes) {
            for (boolean taken : new boolean[]{false, true}) {
                if (opcode == 0x18 || opcode == 0xc3) {
                    if (!taken) {
                        continue;
                    }
                }
                int operandLength = opcode < 0x40 ? 1 : 2;
                int machineCycles = operandLength + 1 + (taken ? 1 : 0);
                for (int budget = 1; budget <= machineCycles * 2; budget++) {
                    CpuPair pair = newCpuPair(random, opcode, -1, operandLength, 0);
                    if (opcode != 0x18 && opcode != 0xc3) {
                        int condition = (opcode >>> 3) & 0x03;
                        int flags = forceCondition(random.nextInt(0x10) << 4,
                                condition, taken);
                        pair.direct.getRegisters().getFlags().setFlagsByte(flags);
                        pair.scalar.getRegisters().getFlags().setFlagsByte(flags);
                    }
                    assertPairAfterTicks(pair, budget);
                }
            }
        }
    }

    @Test
    public void completeDirectMemoryInstructionsMatchScalarAtEveryBudget() throws Exception {
        Random random = new Random(0x5afe4eadL);
        int[] opcodes = {0x02, 0x12, 0x0a, 0x1a, 0x22, 0x2a, 0x32, 0x3a,
                0x34, 0x35, 0x36, 0x46, 0x70, 0x7e, 0x77,
                0x86, 0x8e, 0x96, 0x9e, 0xa6, 0xae, 0xb6, 0xbe,
                0xe0, 0xf0, 0xe2, 0xf2, 0xea, 0xfa};
        for (int opcode : opcodes) {
            int operandLength = opcode == 0xea || opcode == 0xfa ? 2
                    : opcode == 0x36 || opcode == 0xe0 || opcode == 0xf0 ? 1 : 0;
            int machineCycles = 2 + operandLength + (opcode == 0x34 || opcode == 0x35 ? 1 : 0);
            for (int budget = 1; budget <= machineCycles * 2; budget++) {
                CpuPair pair = newCpuPair(random, opcode, -1, operandLength, -1);
                configureSafeMemoryInstruction(pair, opcode);
                assertPairAfterTicks(pair, budget);
            }
        }
    }

    @Test
    public void completeDirectWordInstructionsMatchScalarAtEveryBudget() throws Exception {
        Random random = new Random(0x16b17a11L);
        int[] opcodes = {0x03, 0x13, 0x23, 0x33, 0x0b, 0x1b, 0x2b, 0x3b,
                0x09, 0x19, 0x29, 0x39, 0xf9};
        for (int opcode : opcodes) {
            for (int budget = 1; budget <= 4; budget++) {
                CpuPair pair = newCpuPair(random, opcode, -1, 0, -1);
                setWordRegisters(pair, 0xc120, 0xc240, 0xc360, 0xc480);
                assertPairAfterTicks(pair, budget);
            }
        }
    }

    @Test
    public void oddEpochBudgetLeavesTheScalarClockPhaseContinuation() throws Exception {
        Random random = new Random(0x0ddc10cL);
        CpuPair pair = newCpuPair(random, 0x00, -1, 0, 0);
        int initialPc = pair.direct.getRegisters().getPC();

        assertEquals(1, pair.direct.runPerformanceEpoch(1));
        pair.scalar.tick();
        assertCpuPairEquals(pair);
        assertEquals("first dot is phase-only", pair.scalar.getRegisters().getPC(),
                pair.direct.getRegisters().getPC());

        assertEquals(1, pair.direct.runPerformanceEpoch(1));
        pair.scalar.tick();
        assertCpuPairEquals(pair);
        assertEquals("second dot reaches the opcode boundary", (initialPc + 1) & 0xffff,
                pair.direct.getRegisters().getPC());
    }

    @Test
    public void twoByteUnsafeStoreStopsAtEachJournalSlot() {
        CountingMemory memory = new CountingMemory();
        memory.bytes[0] = 0x08; // LD (a16),SP
        memory.bytes[1] = 0x40;
        memory.bytes[2] = (byte) 0xff;
        Cpu cpu = new Cpu(memory, new InterruptManager(true), null,
                doubleSpeed(), new Display(false));
        cpu.getRegisters().setSP(0x1234);

        assertTrue(cpu.runPerformanceEpoch(54) > 0);
        assertTrue(cpu.replayPerformanceEpochJournal());
        assertEquals(1, memory.writes);
        assertEquals(0xff40, memory.lastWriteAddress);
        assertEquals(0x34, memory.bytes[0xff40] & 0xff);

        assertTrue(cpu.runPerformanceEpoch(54) > 0);
        assertTrue(cpu.replayPerformanceEpochJournal());
        assertEquals(2, memory.writes);
        assertEquals(0xff41, memory.lastWriteAddress);
        assertEquals(0x12, memory.bytes[0xff41] & 0xff);
        assertEquals(Cpu.State.OPCODE, cpu.getState());
    }

    private static void assertDirectMatchesScalar(Random random, int opcode, int extendedOpcode,
                                                   int operandLength, int ticks, int flags)
            throws Exception {
        CpuPair pair = newCpuPair(random, opcode, extendedOpcode, operandLength, flags);
        assertPairAfterTicks(pair, ticks);
        CpuPair physicalDmgPair = newCpuPair(
                random, opcode, extendedOpcode, operandLength, flags, false);
        assertPhysicalDmgPairAfterTicks(physicalDmgPair, ticks * 2);
    }

    private static CpuPair newCpuPair(Random random, int opcode, int extendedOpcode,
                                      int operandLength, int forcedFlags) {
        return newCpuPair(random, opcode, extendedOpcode, operandLength, forcedFlags, true);
    }

    private static CpuPair newCpuPair(Random random, int opcode, int extendedOpcode,
                                      int operandLength, int forcedFlags, boolean gbc) {
        int pc = 0x0100 + random.nextInt(0x7000);
        ParityMemory directMemory = new ParityMemory();
        directMemory.bytes[pc] = (byte) opcode;
        int next = (pc + 1) & 0xffff;
        if (extendedOpcode >= 0) {
            directMemory.bytes[next] = (byte) extendedOpcode;
        } else {
            for (int i = 0; i < operandLength; i++) {
                directMemory.bytes[(next + i) & 0xffff] = (byte) random.nextInt(0x100);
            }
        }
        ParityMemory scalarMemory = new ParityMemory();
        System.arraycopy(directMemory.bytes, 0, scalarMemory.bytes, 0,
                directMemory.bytes.length);

        InterruptManager directInterrupts = new InterruptManager(gbc);
        InterruptManager scalarInterrupts = new InterruptManager(gbc);
        SpeedMode directSpeed = gbc ? doubleSpeed() : new SpeedMode(false);
        SpeedMode scalarSpeed = gbc ? doubleSpeed() : new SpeedMode(false);
        Cpu direct = new Cpu(directMemory, directInterrupts, null,
                directSpeed, new Display(false));
        Cpu scalar = new Cpu(scalarMemory, scalarInterrupts, null,
                scalarSpeed, new Display(false));
        Registers registers = direct.getRegisters();
        registers.setA(random.nextInt(0x100));
        registers.setB(random.nextInt(0x100));
        registers.setC(random.nextInt(0x100));
        registers.setD(random.nextInt(0x100));
        registers.setE(random.nextInt(0x100));
        registers.setH(random.nextInt(0x100));
        registers.setL(random.nextInt(0x100));
        registers.setSP(random.nextInt(0x10000));
        registers.setPC(pc);
        registers.getFlags().setFlagsByte(forcedFlags >= 0
                ? forcedFlags : random.nextInt(0x10) << 4);
        scalar.restoreState(direct.captureState());
        scalarInterrupts.restoreState(directInterrupts.captureState());
        return new CpuPair(direct, scalar, directInterrupts, scalarInterrupts,
                directMemory, scalarMemory);
    }

    private static void assertPairAfterTicks(CpuPair pair, int ticks) throws Exception {
        assertEquals("epoch should consume the complete safe instruction", ticks,
                pair.direct.runPerformanceEpoch(ticks));
        for (int i = 0; i < ticks; i++) {
            pair.scalar.tick();
        }
        assertCpuPairEquals(pair);
    }

    private static void assertPhysicalDmgPairAfterTicks(CpuPair pair, int ticks)
            throws Exception {
        assertEquals("physical-DMG epoch should consume the complete safe instruction", ticks,
                pair.direct.runPhysicalDmgPerformanceEpoch(ticks));
        for (int i = 0; i < ticks; i++) {
            pair.scalar.tick();
        }
        assertCpuPairEquals(pair);
    }

    private static void configureSafeMemoryInstruction(CpuPair pair, int opcode) {
        setWordRegisters(pair, 0xc100, 0xc120, 0xc140, 0xc300);
        if (opcode == 0xe0 || opcode == 0xf0) {
            int pc = (pair.direct.getRegisters().getPC() + 1) & 0xffff;
            pair.directMemory.bytes[pc] = (byte) 0x80;
            pair.scalarMemory.bytes[pc] = (byte) 0x80;
        } else if (opcode == 0xe2 || opcode == 0xf2) {
            pair.direct.getRegisters().setC(0x80);
            pair.scalar.getRegisters().setC(0x80);
        } else if (opcode == 0xea || opcode == 0xfa) {
            int pc = (pair.direct.getRegisters().getPC() + 1) & 0xffff;
            pair.directMemory.bytes[pc] = 0x60;
            pair.directMemory.bytes[(pc + 1) & 0xffff] = (byte) 0xc1;
            pair.scalarMemory.bytes[pc] = 0x60;
            pair.scalarMemory.bytes[(pc + 1) & 0xffff] = (byte) 0xc1;
        }
        for (int address : new int[]{0xc100, 0xc120, 0xc140, 0xc160, 0xff80}) {
            int value = (address >>> 3 ^ opcode * 37) & 0xff;
            pair.directMemory.bytes[address] = (byte) value;
            pair.scalarMemory.bytes[address] = (byte) value;
        }
    }

    private static void setWordRegisters(CpuPair pair, int bc, int de, int hl, int sp) {
        pair.direct.getRegisters().setBC(bc);
        pair.direct.getRegisters().setDE(de);
        pair.direct.getRegisters().setHL(hl);
        pair.direct.getRegisters().setSP(sp);
        pair.scalar.getRegisters().setBC(bc);
        pair.scalar.getRegisters().setDE(de);
        pair.scalar.getRegisters().setHL(hl);
        pair.scalar.getRegisters().setSP(sp);
    }

    private static void assertCpuPairEquals(CpuPair pair) throws Exception {
        assertDeepEquals("cpu", pair.scalar.captureState(), pair.direct.captureState());
        assertDeepEquals("interrupts", pair.scalarInterrupts.captureState(),
                pair.directInterrupts.captureState());
        assertArrayEquals("memory", pair.scalarMemory.bytes, pair.directMemory.bytes);
        assertEquals("read count", pair.scalarMemory.reads, pair.directMemory.reads);
        assertEquals("last read", pair.scalarMemory.lastReadAddress,
                pair.directMemory.lastReadAddress);
        assertEquals("write count", pair.scalarMemory.writes, pair.directMemory.writes);
        assertEquals("last write", pair.scalarMemory.lastWriteAddress,
                pair.directMemory.lastWriteAddress);
    }

    private static void assertDeepEquals(String path, Object expected, Object actual)
            throws Exception {
        if (expected == actual) {
            return;
        }
        assertNotNull(path + " actual", actual);
        assertNotNull(path + " expected", expected);
        assertEquals(path + " type", expected.getClass(), actual.getClass());
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            assertEquals(path + " length", length, Array.getLength(actual));
            for (int i = 0; i < length; i++) {
                assertDeepEquals(path + '[' + i + ']', Array.get(expected, i),
                        Array.get(actual, i));
            }
            return;
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertDeepEquals(path + '.' + component.getName(),
                        accessor.invoke(expected), accessor.invoke(actual));
            }
            return;
        }
        assertEquals(path, expected, actual);
    }

    private static boolean isDirectBaseOpcode(int opcode) {
        if (opcode == 0x00 || opcode == 0x07 || opcode == 0x0f
                || opcode == 0x17 || opcode == 0x1f || opcode == 0x27
                || opcode == 0x2f || opcode == 0x37 || opcode == 0x3f
                || opcode == 0xe9) {
            return true;
        }
        int register = (opcode >>> 3) & 0x07;
        if (((opcode & 0xc7) == 0x04 || (opcode & 0xc7) == 0x05)
                && register != 6) {
            return true;
        }
        if (opcode >= 0x40 && opcode <= 0x7f && opcode != 0x76) {
            return register != 6 && (opcode & 0x07) != 6;
        }
        return opcode >= 0x80 && opcode <= 0xbf && (opcode & 0x07) != 6;
    }

    private static int directOperandLength(int opcode) {
        if ((opcode & 0xcf) == 0x01) {
            return 2;
        }
        int register = (opcode >>> 3) & 0x07;
        if ((opcode & 0xc7) == 0x06 && register != 6 || (opcode & 0xc7) == 0xc6) {
            return 1;
        }
        if ((opcode & 0xe7) == 0x20) {
            return 1;
        }
        if ((opcode & 0xe7) == 0xc2 || (opcode & 0xe7) == 0xc4) {
            return 2;
        }
        return 0;
    }

    private static boolean isConditionalDirectOperandOpcode(int opcode) {
        return (opcode & 0xe7) == 0x20
                || (opcode & 0xe7) == 0xc2
                || (opcode & 0xe7) == 0xc4;
    }

    private static int forceCondition(int flags, int condition, boolean value) {
        int mask = condition < 2 ? 0x80 : 0x10;
        boolean flagMustBeSet = (condition & 1) == 1 ? value : !value;
        return flagMustBeSet ? flags | mask : flags & ~mask;
    }

    private static SpeedMode doubleSpeed() {
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        assertTrue(speed.onStop());
        return speed;
    }

    private static final class CpuPair {
        private final Cpu direct;
        private final Cpu scalar;
        private final InterruptManager directInterrupts;
        private final InterruptManager scalarInterrupts;
        private final ParityMemory directMemory;
        private final ParityMemory scalarMemory;

        private CpuPair(Cpu direct, Cpu scalar,
                        InterruptManager directInterrupts, InterruptManager scalarInterrupts,
                        ParityMemory directMemory, ParityMemory scalarMemory) {
            this.direct = direct;
            this.scalar = scalar;
            this.directInterrupts = directInterrupts;
            this.scalarInterrupts = scalarInterrupts;
            this.directMemory = directMemory;
            this.scalarMemory = scalarMemory;
        }
    }

    private static final class ParityMemory implements AddressSpace {
        private final byte[] bytes = new byte[0x10000];
        private int reads;
        private int lastReadAddress = -1;
        private int writes;
        private int lastWriteAddress = -1;

        @Override
        public boolean accepts(int address) {
            return address >= 0 && address <= 0xffff;
        }

        @Override
        public void setByte(int address, int value) {
            writes++;
            lastWriteAddress = address & 0xffff;
            bytes[lastWriteAddress] = (byte) value;
        }

        @Override
        public int getByte(int address) {
            reads++;
            lastReadAddress = address & 0xffff;
            return bytes[lastReadAddress] & 0xff;
        }
    }

    private static final class CountingMemory implements AddressSpace {
        private final byte[] bytes = new byte[0x10000];
        private int writes;
        private int lastWriteAddress;
        private final int[] reads = new int[0x10000];

        @Override
        public boolean accepts(int address) {
            return address >= 0 && address <= 0xffff;
        }

        @Override
        public void setByte(int address, int value) {
            writes++;
            lastWriteAddress = address & 0xffff;
            bytes[address & 0xffff] = (byte) value;
        }

        @Override
        public int getByte(int address) {
            reads[address & 0xffff]++;
            return bytes[address & 0xffff] & 0xff;
        }
    }

    private static final class LeasedMemory
            implements AddressSpace, PerformanceRomAccessProvider {

        private final byte[] bytes = new byte[0x10000];
        private final byte[] physicalBytes = new byte[0x10001];
        private final int lowerWindowBase;
        private int selectedUpperWindowBase;
        private int leasedUpperWindowBase;
        private final int[] physicalReadOffsets = new int[64];
        private boolean leaseAvailable = true;
        private boolean mapRom = true;
        private boolean bankWritesSelectUpperWindow;
        private int leaseAcquisitions;
        private int leaseMapProbes;
        private int leaseReads;
        private int busReads;

        private final PerformanceRomAccess lease = new PerformanceRomAccess() {
            @Override
            public int physicalOffset(int cpuAddress) {
                leaseMapProbes++;
                if (!mapRom || cpuAddress < 0 || cpuAddress >= 0x8000) {
                    return -1;
                }
                return cpuAddress < 0x4000
                        ? lowerWindowBase + cpuAddress
                        : leasedUpperWindowBase + cpuAddress - 0x4000;
            }

            @Override
            public int readPhysicalByte(int physicalOffset) {
                physicalReadOffsets[leaseReads++] = physicalOffset;
                return physicalBytes[physicalOffset] & 0xff;
            }
        };

        private LeasedMemory(int lowerWindowBase, int upperWindowBase) {
            this.lowerWindowBase = lowerWindowBase;
            this.selectedUpperWindowBase = upperWindowBase;
            this.leasedUpperWindowBase = upperWindowBase;
        }

        @Override
        public PerformanceRomAccess acquirePerformanceRomAccess() {
            leaseAcquisitions++;
            leasedUpperWindowBase = selectedUpperWindowBase;
            return leaseAvailable ? lease : null;
        }

        @Override
        public boolean accepts(int address) {
            return address >= 0 && address <= 0xffff;
        }

        @Override
        public void setByte(int address, int value) {
            if (bankWritesSelectUpperWindow && address >= 0x2000 && address < 0x4000) {
                selectedUpperWindowBase = (value & 0xff) * 0x4000;
                return;
            }
            bytes[address & 0xffff] = (byte) value;
        }

        @Override
        public int getByte(int address) {
            busReads++;
            if (bankWritesSelectUpperWindow && address >= 0 && address < 0x8000) {
                int physicalOffset = address < 0x4000
                        ? lowerWindowBase + address
                        : selectedUpperWindowBase + address - 0x4000;
                return physicalBytes[physicalOffset] & 0xff;
            }
            return bytes[address & 0xffff] & 0xff;
        }
    }
}
