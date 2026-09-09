package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import eu.rekawek.coffeegb.core.timer.Timer;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.*;

/** Workload coverage of CPU epoch contracts, independent of a particular game's instruction loop. */
public class CpuPerformanceCoverageTest {

    private enum Lane {
        DMG, SGB, DMG_MODE2, DMG_LCD_OFF, CGB_COMPAT, CGB_COMPAT_LCD_OFF,
        CGB_X1, CGB_X1_LCD_OFF, CGB_X2, CGB_X2_LCD_OFF;

        boolean color() { return ordinal() >= CGB_COMPAT.ordinal(); }

        SpeedMode speed() {
            SpeedMode speed = new SpeedMode(color());
            if (this == CGB_COMPAT || this == CGB_COMPAT_LCD_OFF) {
                speed.setDmgCompat(true);
            } else if ((this == CGB_X2 || this == CGB_X2_LCD_OFF)) {
                speed.setByte(0xff4d, 1);
                assertTrue(speed.onStop());
            }
            return speed;
        }

        int run(Cpu cpu, int budget, int proof) {
            return switch (this) {
                case DMG -> cpu.runPhysicalDmgPerformanceEpoch(budget, proof);
                case SGB -> cpu.runSgbPerformanceEpoch(budget, proof);
                case DMG_MODE2 -> cpu.runPhysicalDmgMode2PerformanceEpoch(budget, proof);
                case DMG_LCD_OFF -> cpu.runPhysicalDmgNormalSpeedLcdOffPerformanceEpoch(budget, proof);
                case CGB_COMPAT -> cpu.runCgbCompatibilityPerformanceEpoch(budget, proof);
                case CGB_COMPAT_LCD_OFF -> cpu.runCgbCompatibilityLcdOffPerformanceEpoch(budget, proof);
                case CGB_X1 -> cpu.runNativeCgbNormalSpeedPerformanceEpoch(budget, proof);
                case CGB_X1_LCD_OFF -> cpu.runNativeCgbNormalSpeedLcdOffPerformanceEpoch(budget, proof);
                case CGB_X2 -> cpu.runNativeCgbPerformanceEpoch(budget, proof);
                case CGB_X2_LCD_OFF -> cpu.runNativeCgbDoubleSpeedLcdOffPerformanceEpoch(budget, proof);
            };
        }
    }

    @Test
    public void ownedDmaHeldOpcodeFreezesCpuAtBothSpeedsAndEveryClockPhase() throws Exception {
        for (Lane lane : new Lane[]{Lane.CGB_X1, Lane.CGB_X2}) {
            for (int phase = 0; phase < 4; phase++) {
                for (int opcode : new int[]{0x00, 0x3e, 0x76, 0xcb, 0xc3}) {
                    Pair pair = new Pair(lane, opcode, 0x00, 0x00);
                    for (int tick = 0; tick < phase % (lane == Lane.CGB_X2 ? 2 : 4); tick++) {
                        pair.scalar.tick();
                        pair.direct.tick();
                    }
                    assertFalse(pair.direct.performanceHdmaOwnedBlockCpuFrozenEligible());
                    pair.scalar.prefetchOpcodeForHdma();
                    pair.direct.prefetchOpcodeForHdma();
                    assertTrue(pair.direct.performanceHdmaOwnedBlockCpuFrozenEligible());
                    var before = pair.direct.captureState();
                    for (int tick = 0; tick < 31; tick++) {
                        assertEquals(opcode, pair.scalar.getBusValueForHdma());
                        pair.scalar.prefetchOpcodeForHdma();
                    }
                    assertDeepEquals("held CPU must not clock", before, pair.direct.captureState());
                    pair.assertEquivalent("held DMA opcode " + lane + '/' + phase + '/' + opcode);
                    pair.direct.restoreState(before);
                    assertTrue(pair.direct.performanceHdmaOwnedBlockCpuFrozenEligible());
                    pair.pendingInterrupt(false);
                    assertFalse("interrupt-visible frozen window needs the scalar arbiter",
                            pair.direct.performanceHdmaOwnedBlockCpuFrozenEligible());
                }
            }
            Pair stop = new Pair(lane, 0x10, 0x00);
            stop.direct.prefetchOpcodeForHdma();
            assertFalse(stop.direct.performanceHdmaOwnedBlockCpuFrozenEligible());
            for (int opcode : new int[]{0x7e, 0xea, 0xcd, 0xcb}) {
                Pair decoded = new Pair(lane, opcode, opcode == 0xcb ? 0x46 : 0x00, 0xc0);
                int guard = 0;
                while (decoded.direct.getState() != Cpu.State.RUNNING && guard++ < 32) {
                    decoded.scalar.tick();
                    decoded.direct.tick();
                }
                assertTrue("decoded instruction setup", guard < 32);
                assertTrue(decoded.direct.performanceHdmaOwnedBlockCpuFrozenEligible());
                for (int tick = 0; tick < 31; tick++) {
                    assertEquals(opcode, decoded.scalar.getBusValueForHdma());
                    decoded.scalar.prefetchOpcodeForHdma();
                }
                decoded.assertEquivalent("already-decoded DMA " + lane + '/' + opcode);
            }
        }
    }

    @Test
    public void synchronizedImeOffMode2RequestsBatchButNewEdgesAndImeRemainScalar() throws Exception {
        for (Lane lane : Lane.values()) {
            for (boolean firstLine : new boolean[]{false, true}) {
                Pair pair = new Pair(lane, 0x04, 0x18, 0xfd);
                for (InterruptManager interrupts : new InterruptManager[]{
                        pair.scalarInterrupts, pair.directInterrupts}) {
                    interrupts.setByte(0xffff, 2);
                    interrupts.requestMode2InterruptBeforeCpuAcceptance(firstLine);
                    interrupts.releaseCpuAcceptance(InterruptManager.InterruptType.LCDC);
                }
                assertEquals("new synchronizer rise must remain scalar " + lane,
                        0, lane.run(pair.direct, 54, 0));
                pair.scalar.tick();
                pair.direct.tick();
                for (int budget = 1; budget <= 54; budget++) {
                    int elapsed = lane.run(pair.direct, budget, 0);
                    assertEquals("stable masked mode-2 input fragmented " + lane, budget, elapsed);
                    pair.complete(elapsed);
                    pair.assertEquivalent("stable phased input " + lane + '/' + firstLine + '/' + budget);
                }
                var beforeIme = pair.direct.captureState();
                var beforeInterrupts = pair.directInterrupts.captureState();
                pair.directInterrupts.enableInterrupts(false);
                assertEquals("IME-on phased request may dispatch", 0, lane.run(pair.direct, 54, 0));
                pair.direct.restoreState(beforeIme);
                pair.directInterrupts.restoreState(beforeInterrupts);
                pair.scalarInterrupts.setByte(0xff0f, 0);
                pair.directInterrupts.setByte(0xff0f, 0);
                assertEquals("new synchronizer fall must remain scalar", 0, lane.run(pair.direct, 54, 0));
                pair.scalar.tick();
                pair.direct.tick();
                assertEquals(54, lane.run(pair.direct, 54, 0));
                pair.complete(54);
                pair.assertEquivalent("released phased input " + lane);
            }
        }
    }

    @Test
    public void pendingImeMaskedRequestsKeepLogicalMapperLoopsInFullEpochs() throws Exception {
        for (Lane lane : Lane.values()) {
            Pair pair = new Pair(lane, 0x04, 0xfa, 0x00, 0xc0, 0xa8, 0x18, 0xf9);
            pair.pendingInterrupt(false);
            Random random = new Random(0x1f + lane.ordinal());
            long ticks = 0;
            for (int epoch = 0; epoch < 1000; epoch++) {
                int budget = epoch < 54 ? epoch + 1 : 1 + random.nextInt(54);
                int elapsed = lane.run(pair.direct, budget, 0);
                assertEquals(lane + " fragmented an IME=0 compute loop", budget, elapsed);
                pair.complete(elapsed);
                ticks += elapsed;
                if (epoch % 100 == 0) {
                    pair.assertEquivalent(lane + " epoch " + epoch);
                }
            }
            pair.assertEquivalent(lane.toString());
            assertTrue(ticks > 25000);
            assertTrue(pair.directInterrupts.hasRawPendingEnabledInterrupt());
            assertEquals(0, pair.direct.getPerformanceEpochFenceAttemptCount());
            assertTrue("logical ROM reader was bypassed", pair.directMemory.logicalReads > 0);
        }
    }

    @Test
    public void invariantPpuReadsCoverAddressingFormsAtEveryBudgetAndPhase() throws Exception {
        int[][] programs = {
                {0xf0, 0x41}, {0xf2}, {0xfa, 0x41, 0xff}, {0x7e},
                {0x46}, {0xcb, 0x46}, {0xbe}, {0x0a}, {0x1a}, {0x2a}, {0x3a}
        };
        for (Lane lane : Lane.values()) {
            for (int register : new int[]{0xff41, 0xff44}) {
                for (int[] original : programs) {
                    int[] program = original.clone();
                    if (program[0] == 0xf0 || program[0] == 0xfa) {
                        program[1] = register & 0xff;
                    }
                    // Cover every budget for LDH and all phase/short-boundary classes for the
                    // remaining addressing forms, including decoded CB and indirect reads.
                    int step = program[0] == 0xf0 ? 1 : 7;
                    for (int budget = 1; budget <= 54; budget += step) {
                        Pair pair = new Pair(lane, program);
                        int phase = budget % (lane == Lane.CGB_X2 || lane == Lane.CGB_X2_LCD_OFF ? 2 : 4);
                        for (int tick = 0; tick < phase; tick++) {
                            pair.direct.tick();
                            pair.scalar.tick();
                        }
                        pair.setAddressRegisters(register);
                        pair.directMemory.bytes[register] = (byte) 0xd2;
                        pair.scalarMemory.bytes[register] = (byte) 0xd2;
                        int proof = register == 0xff41
                                ? Cpu.PERFORMANCE_STABLE_STAT_READ : Cpu.PERFORMANCE_STABLE_LY_READ;
                        int elapsed = lane.run(pair.direct, budget, proof);
                        assertEquals(lane + " fenced invariant read " + register, budget, elapsed);
                        pair.complete(elapsed);
                        pair.assertEquivalent(lane + " opcode " + program[0] + " budget " + budget);
                        assertEquals(0, pair.direct.getPerformanceEpochFenceAttemptCount());
                    }
                }
            }
        }
    }

    @Test
    public void sustainedStatPollingUsesExplicitProofAndDoesNotLeakToNextEpoch() throws Exception {
        for (Lane lane : Lane.values()) {
            Pair pair = new Pair(lane, 0xf0, 0x41, 0xe6, 0x03, 0x18, 0xfa);
            pair.pendingInterrupt(false);
            for (int epoch = 0; epoch < 500; epoch++) {
                assertEquals(54, lane.run(pair.direct, 54, Cpu.PERFORMANCE_STABLE_STAT_READ));
                pair.complete(54);
            }
            pair.assertEquivalent(lane.toString());
            assertTrue(pair.directMemory.reads[0xff41] > 500);
            assertEquals(0, pair.direct.getPerformanceEpochFenceAttemptCount());

            int elapsed = lane.run(pair.direct, 54, Cpu.PERFORMANCE_STABLE_LY_READ);
            pair.complete(elapsed);
            assertTrue(lane + " leaked its STAT proof", elapsed < 54);
            assertTrue(pair.direct.getPerformanceEpochFenceAttemptCount() > 0);
            pair.assertEquivalent(lane + " revoked proof");
        }
    }

    @Test
    public void timingReadHintsRequestOnlyFutureProofsAndDecayAfterRestoreOrOtherWork() throws Exception {
        for (Lane lane : Lane.values()) {
            for (int address : new int[]{0xff41, 0xff44}) {
                Pair pair = new Pair(lane, 0xf0, address & 255, 0xc3, 0, 0);
                assertFalse(pair.direct.consumePerformancePpuReadHint());
                int elapsed = lane.run(pair.direct, 54, 0);
                pair.complete(elapsed);
                pair.assertEquivalent(lane + " demand fence " + address);
                assertTrue("missing first-read demand", pair.direct.consumePerformancePpuReadHint());
                assertFalse("hint was not consumed", pair.direct.consumePerformancePpuReadHint());

                int proof = address == 0xff41 ? Cpu.PERFORMANCE_STABLE_STAT_READ
                        : Cpu.PERFORMANCE_STABLE_LY_READ;
                assertEquals(54, lane.run(pair.direct, 54, proof));
                pair.complete(54);
                pair.assertEquivalent(lane + " renewed read demand " + address);
                assertTrue("admitted read must renew demand", pair.direct.consumePerformancePpuReadHint());

                assertEquals(54, lane.run(pair.direct, 54, proof));
                pair.complete(54);
                var saved = pair.direct.captureState();
                pair.direct.restoreState(saved);
                assertFalse("restore cannot retain a host optimization hint",
                        pair.direct.consumePerformancePpuReadHint());
                pair.assertEquivalent(lane + " restored read demand " + address);

                while (pair.direct.getState() != Cpu.State.OPCODE) {
                    pair.direct.tick();
                    pair.scalar.tick();
                }
                pair.direct.getRegisters().setPC(0x200);
                pair.scalar.getRegisters().setPC(0x200);
                pair.direct.consumePerformancePpuReadHint();
                assertEquals(54, lane.run(pair.direct, 54, proof));
                pair.complete(54);
                assertFalse("register/ROM-only work must let demand expire",
                        pair.direct.consumePerformancePpuReadHint());
                pair.assertEquivalent(lane + " read demand expired " + address);
            }
        }
    }

    @Test
    public void unrelatedMmioReadsAndPpuWritesDoNotRequestStableReadProofs() throws Exception {
        for (Lane lane : Lane.values()) {
            for (int address : new int[]{0xff00, 0xff04, 0xff26, 0xff41, 0xff44}) {
                int opcode = address == 0xff41 || address == 0xff44 ? 0xe0 : 0xf0;
                Pair pair = new Pair(lane, opcode, address & 255);
                int elapsed = lane.run(pair.direct, 54, 0);
                pair.complete(elapsed);
                assertFalse(lane + " unrelated fence requested a PPU read proof " + address,
                        pair.direct.consumePerformancePpuReadHint());
                pair.assertEquivalent(lane + " unrelated proof demand " + address);
            }
        }
    }

    @Test
    public void readProofDoesNotAuthorizePpuWritesOrReadModifyWrite() throws Exception {
        for (Lane lane : Lane.values()) {
            for (int opcode : new int[]{0xe0, 0x34, 0xcb}) {
                Pair pair = opcode == 0xcb ? new Pair(lane, 0xcb, 0xc6)
                        : new Pair(lane, opcode, 0x41);
                pair.setAddressRegisters(0xff41);
                int elapsed = lane.run(pair.direct, 54, 3);
                assertTrue(lane + " crossed an MMIO write", elapsed < 54);
                pair.complete(elapsed);
                pair.assertEquivalent(lane + " write opcode " + opcode);
                assertTrue(pair.direct.getPerformanceEpochFenceAttemptCount() > 0);
            }
        }
    }

    @Test
    public void interruptEnableAndHaltControlSeamsMatchScalarWithPendingRequests() throws Exception {
        for (Lane lane : Lane.values()) {
            for (int opcode : new int[]{0x76, 0xfb, 0xf3, 0xd9, 0x10}) {
                for (int budget = 1; budget <= 54; budget++) {
                    Pair pair = new Pair(lane, opcode, 0x00, 0x04);
                    pair.pendingInterrupt(false);
                    pair.direct.getRegisters().setSP(0xc000);
                    pair.scalar.getRegisters().setSP(0xc000);
                    int elapsed = lane.run(pair.direct, budget, 0);
                    pair.complete(elapsed);
                    pair.assertEquivalent(lane + " control " + opcode + " budget " + budget);
                    assertTrue(elapsed <= (lane == Lane.CGB_X2 || lane == Lane.CGB_X2_LCD_OFF ? 2 : 4));
                }
            }
            Pair enabled = new Pair(lane, 0x00);
            enabled.pendingInterrupt(true);
            assertEquals(lane + " consumed IME=1 dispatch", 0, lane.run(enabled.direct, 54, 0));
        }
    }

    @Test
    public void haltBugTimerSamplingUsesFinalTickAndSurvivesRestore() throws Exception {
        for (int age = 0; age <= 6; age++) {
            for (int budget = 1; budget <= 8; budget++) {
                Memory directMemory = new Memory();
                Memory scalarMemory = new Memory();
                directMemory.bytes[0] = scalarMemory.bytes[0] = 0x76;
                SpeedMode directSpeed = new SpeedMode(false);
                SpeedMode scalarSpeed = new SpeedMode(false);
                InterruptManager directInterrupts = pendingInterrupts(false);
                InterruptManager scalarInterrupts = pendingInterrupts(false);
                Timer directTimer = new Timer(directInterrupts, directSpeed);
                Timer scalarTimer = new Timer(scalarInterrupts, scalarSpeed);
                directTimer.setByte(0xff04, 0);
                scalarTimer.setByte(0xff04, 0);
                for (int tick = 0; tick < age; tick++) {
                    directTimer.tick();
                    scalarTimer.tick();
                }
                Cpu direct = new Cpu(directMemory, directInterrupts, null, directSpeed,
                        new Display(false), directTimer);
                Cpu scalar = new Cpu(scalarMemory, scalarInterrupts, null, scalarSpeed,
                        new Display(false), scalarTimer);
                int total = 0;
                while (total < 4) {
                    int elapsed = direct.runPhysicalDmgPerformanceEpoch(budget);
                    assertTrue(elapsed > 0);
                    for (int tick = 0; tick < elapsed; tick++) {
                        directTimer.tick();
                        scalarTimer.tick();
                        scalar.tick();
                    }
                    direct.replayPerformanceEpochJournal();
                    total += elapsed;
                    assertDeepEquals("HALT CPU", scalar.captureState(), direct.captureState());
                    assertDeepEquals("HALT timer", scalarTimer.captureState(), directTimer.captureState());
                }
                direct.restoreState(direct.captureState());
                directTimer.restoreState(directTimer.captureState());
                directInterrupts.restoreState(directInterrupts.captureState());
                for (int tick = 0; tick < 270; tick++) {
                    directTimer.tick();
                    scalarTimer.tick();
                    direct.tick();
                    scalar.tick();
                    assertEquals("DIV ripple at age " + age, scalarTimer.getByte(0xff04),
                            directTimer.getByte(0xff04));
                }
                assertDeepEquals("restored HALT CPU", scalar.captureState(), direct.captureState());
                assertDeepEquals("restored HALT timer", scalarTimer.captureState(), directTimer.captureState());
            }
        }
    }

    @Test
    public void armedHblankWaitUsesOptedInLogicalPeeksAndKeepsHaltScalar() throws Exception {
        for (Lane lane : new Lane[]{Lane.CGB_X1, Lane.CGB_X2}) {
            Pair pair = new Pair(lane, 0x04, 0x18, 0xfd);
            pair.directMemory.peekSafe = true;
            pair.pendingInterrupt(false);
            for (int epoch = 0; epoch < 500; epoch++) {
                int elapsed = lane == Lane.CGB_X1
                        ? pair.direct.runNativeCgbNormalSpeedArmedHblankWaitPerformanceEpoch(54, 0)
                        : pair.direct.runNativeCgbArmedHblankWaitPerformanceEpoch(54, 0);
                assertEquals("safe logical peek fragmented " + lane, 54, elapsed);
                pair.complete(elapsed);
            }
            pair.assertEquivalent("armed logical " + lane);

            for (boolean safePeek : new boolean[]{false, true}) {
                Pair halt = new Pair(lane, 0x76, 0x04);
                halt.directMemory.peekSafe = safePeek;
                halt.pendingInterrupt(false);
                int elapsed = lane == Lane.CGB_X1
                        ? halt.direct.runNativeCgbNormalSpeedArmedHblankWaitPerformanceEpoch(54, 0)
                        : halt.direct.runNativeCgbArmedHblankWaitPerformanceEpoch(54, 0);
                assertEquals(lane == Lane.CGB_X1 ? 3 : 1, elapsed);
                halt.complete(elapsed);
                halt.assertEquivalent("armed HALT " + lane);
                assertEquals("HALT was consumed by lookahead", 0, halt.directMemory.logicalReads);
            }
        }
    }

    @Test
    public void optionalDiagnosticsClassifiesFencesWithoutChangingExecution() throws Exception {
        Pair pair = new Pair(Lane.CGB_X1, 0xf0, 0x41);
        PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(70224);
        pair.direct.setPerformanceDiagnostics(diagnostics);
        int elapsed = Lane.CGB_X1.run(pair.direct, 54, 0);
        pair.complete(elapsed);
        pair.assertEquivalent("diagnostics");
        assertEquals(Long.valueOf(1), diagnostics.snapshot().fences().get(PerformanceDiagnostics.Fence.STAT));
    }

    private static InterruptManager pendingInterrupts(boolean color) {
        InterruptManager result = new InterruptManager(color);
        result.setByte(0xffff, 1);
        result.requestInterrupt(InterruptManager.InterruptType.VBlank);
        return result;
    }

    private static final class Pair {
        final Memory directMemory = new Memory();
        final Memory scalarMemory = new Memory();
        final InterruptManager directInterrupts;
        final InterruptManager scalarInterrupts;
        final Cpu direct;
        final Cpu scalar;

        Pair(Lane lane, int... program) {
            directInterrupts = new InterruptManager(lane.color());
            scalarInterrupts = new InterruptManager(lane.color());
            direct = new Cpu(directMemory, directInterrupts, null, lane.speed(), new Display(false));
            scalar = new Cpu(scalarMemory, scalarInterrupts, null, lane.speed(), new Display(false));
            for (int index = 0; index < program.length; index++) {
                directMemory.bytes[index] = scalarMemory.bytes[index] = (byte) program[index];
            }
        }

        void pendingInterrupt(boolean ime) {
            for (InterruptManager interrupts : new InterruptManager[]{directInterrupts, scalarInterrupts}) {
                interrupts.setByte(0xffff, 1);
                interrupts.requestInterrupt(InterruptManager.InterruptType.VBlank);
                if (ime) {
                    interrupts.enableInterrupts(false);
                }
            }
        }

        void setAddressRegisters(int address) {
            for (Cpu cpu : new Cpu[]{direct, scalar}) {
                cpu.getRegisters().setBC(address);
                cpu.getRegisters().setDE(address);
                cpu.getRegisters().setHL(address);
            }
        }

        void complete(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                scalar.tick();
            }
            direct.replayPerformanceEpochJournal();
        }

        void assertEquivalent(String label) throws Exception {
            assertDeepEquals(label + " CPU", scalar.captureState(), direct.captureState());
            assertDeepEquals(label + " interrupts", scalarInterrupts.captureState(), directInterrupts.captureState());
            assertArrayEquals(label + " memory", scalarMemory.bytes, directMemory.bytes);
            assertArrayEquals(label + " canonical reads", scalarMemory.reads, directMemory.reads);
        }
    }

    /** Each logical read is observable, so speculative HALT lookahead would fail read parity. */
    private static final class Memory implements AddressSpace, PerformanceRomAccessProvider, PerformanceRomAccess {
        final byte[] bytes = new byte[65536];
        final int[] reads = new int[65536];
        int logicalReads;
        boolean peekSafe;

        @Override public int peekCpuByte(int address) {
            return peekSafe ? bytes[address & 0xffff] & 0xff : -1;
        }
        @Override public boolean accepts(int address) { return true; }
        @Override public int getByte(int address) {
            int normalized = address & 0xffff;
            reads[normalized]++;
            return bytes[normalized] & 0xff;
        }
        @Override public void setByte(int address, int value) { bytes[address & 0xffff] = (byte) value; }
        @Override public PerformanceRomAccess acquirePerformanceRomAccess() { return this; }
        @Override public int physicalOffset(int address) { return -1; }
        @Override public int readPhysicalByte(int offset) { throw new AssertionError("No physical backing"); }
        @Override public int readCpuByte(int address) { logicalReads++; return getByte(address); }
    }

    private static void assertDeepEquals(String path, Object expected, Object actual) throws Exception {
        if (expected == actual) {
            return;
        }
        assertNotNull(path, expected);
        assertNotNull(path, actual);
        assertEquals(path, expected.getClass(), actual.getClass());
        if (expected.getClass().isArray()) {
            assertEquals(path, Array.getLength(expected), Array.getLength(actual));
            for (int index = 0; index < Array.getLength(expected); index++) {
                assertDeepEquals(path + '[' + index + ']', Array.get(expected, index), Array.get(actual, index));
            }
        } else if (expected.getClass().isRecord()) {
            for (RecordComponent component : expected.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertDeepEquals(path + '.' + component.getName(), accessor.invoke(expected), accessor.invoke(actual));
            }
        } else {
            assertEquals(path, expected, actual);
        }
    }
}
