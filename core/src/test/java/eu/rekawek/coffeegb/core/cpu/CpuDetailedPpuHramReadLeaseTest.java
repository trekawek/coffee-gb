package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.PerformanceHramReadAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class CpuDetailedPpuHramReadLeaseTest {
    @Test
    public void liveSelfModifyingInstructionsMatchEveryPrefixAndRestoredCpuPhase() throws Exception {
        boolean used = false;
        for (int phase = 0; phase < 2; phase++) {
            for (int budget = 1; budget <= 54; budget++) {
                Pair pair = selfModifyingPair();
                pair.advance(phase);
                var saved = pair.direct.captureState();
                pair.direct.tick();
                pair.direct.restoreState(saved);
                pair.clearCounts();
                int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(budget);
                assertEquals(budget, elapsed);
                pair.complete(elapsed);
                pair.same("phase=" + phase + " budget=" + budget);
                assertEquals("HRAM data reads must retain the authoritative bus",
                        pair.scalarMemory.reads[0xfff0], pair.directMemory.reads[0xfff0]);
                assertEquals("all actual instruction-byte reads use the view in this fixture",
                        0, instructionBusReads(pair.directMemory));
                assertLeaseCleared(pair.direct);
                used |= pair.directMemory.leasedReads > 0;
                if (budget == 54) {
                    assertEquals("the instruction changes before it is fetched", 0x04,
                            pair.directMemory.bytes[0xff90] & 255);
                    assertTrue("patched INC B must execute", pair.direct.getRegisters().getB() > 0);
                    assertEquals("the original INC C must not be decoded", 0,
                            pair.direct.getRegisters().getC());
                }
            }
        }
        assertTrue("the differential must positively use the new capability", used);
    }

    @Test
    public void canonicalWritesBetweenPacketsAndRestoreNeverReuseByteValues() throws Exception {
        Pair pair = selfModifyingPair();
        int first = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
        pair.complete(first);
        pair.same("first self-modifying packet");
        var savedDirect = pair.direct.captureState();
        var savedScalar = pair.scalar.captureState();
        byte[] savedMemory = pair.directMemory.bytes.clone();
        for (int opcode : new int[]{0x0c, 0x04, 0x14}) {
            pair.direct.restoreState(savedDirect);
            pair.scalar.restoreState(savedScalar);
            System.arraycopy(savedMemory, 0, pair.directMemory.bytes, 0, 65536);
            System.arraycopy(savedMemory, 0, pair.scalarMemory.bytes, 0, 65536);
            pair.clearCounts();
            pair.directMemory.setByte(0xff90, opcode);
            pair.scalarMemory.setByte(0xff90, opcode);
            int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
            pair.complete(elapsed);
            pair.same("live opcode=" + opcode);
            assertTrue(pair.directMemory.leasedReads > 0);
            assertLeaseCleared(pair.direct);
        }
        // Explicitly pin restore's cleanup of the nonportable borrowed reference.
        var field = Cpu.class.getDeclaredField("performanceDetailedPpuHramReadAccess");
        field.setAccessible(true);
        field.set(pair.direct, pair.directMemory.hramView);
        pair.direct.restoreState(savedDirect);
        assertLeaseCleared(pair.direct);
    }

    @Test
    public void missingCapabilityFallsBackAndOrdinaryOrObservedCpuNeverAcquiresIt() throws Exception {
        Pair unknown = selfModifyingPair();
        unknown.directMemory.capability = false;
        int elapsed = unknown.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
        unknown.complete(elapsed);
        unknown.same("unknown HRAM capability");
        assertEquals(0, unknown.directMemory.leasedReads);
        assertTrue(instructionBusReads(unknown.directMemory) > 0);
        assertLeaseCleared(unknown.direct);

        Pair ordinary = selfModifyingPair();
        elapsed = ordinary.direct.runNativeCgbPerformanceEpoch(54);
        ordinary.complete(elapsed);
        ordinary.same("ordinary CPU instruction path");
        assertEquals(0, ordinary.directMemory.hramAcquisitions);
        assertEquals(0, ordinary.directMemory.leasedReads);
        assertTrue(instructionBusReads(ordinary.directMemory) > 0);

        Pair observed = selfModifyingPair();
        observed.direct.setDebugHooks(new TestDebugHooks());
        var before = observed.direct.captureState();
        assertEquals(0, observed.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
        assertEquals(0, observed.directMemory.hramAcquisitions);
        assertStateEquals("observer refusal precedes CPU work", before, observed.direct.captureState());
        assertLeaseCleared(observed.direct);
    }

    @Test
    public void lazyAcquisitionSkipsRomAndUsesFullEntryBudgetWhenRomJumpsToHram() throws Exception {
        Pair rom = new Pair(0x100, 0xc3, 0x00, 0x01);
        int elapsed = rom.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
        assertEquals(54, elapsed);
        rom.complete(elapsed);
        rom.same("ROM-only detailed packet");
        assertEquals("ROM-only detailed work must never traverse the HRAM providers", 0,
                rom.directMemory.hramAcquisitions);
        assertLeaseCleared(rom.direct);

        Pair later = new Pair(0x100, 0xc3, 0x80, 0xff);
        later.put(0xff80, 0x00, 0xc3, 0x80, 0xff);
        elapsed = later.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
        assertEquals(54, elapsed);
        later.complete(elapsed);
        later.same("later HRAM acquisition");
        assertEquals(1, later.directMemory.hramAcquisitions);
        assertEquals("DMA remains at entry until owner commit; retain its full requested proof", 54,
                later.directMemory.lastHramRequested);
        assertTrue(later.directMemory.leasedReads > 0);
        assertLeaseCleared(later.direct);

        Pair denied = selfModifyingPair();
        denied.directMemory.capability = false;
        elapsed = denied.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
        denied.complete(elapsed);
        denied.same("one rejected acquisition per packet");
        assertEquals(1, denied.directMemory.hramAcquisitions);
        assertLeaseCleared(denied.direct);
    }

    @Test
    public void operandAndAccessoryBoundariesNeverWrapTheView() throws Exception {
        for (int start : new int[]{0xfffc, 0xfffd}) {
            Pair pair = new Pair(start, start == 0xfffc ? 0xc3 : 0x06, 0x00, 0x00);
            pair.clearCounts();
            int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
            assertTrue(elapsed < 54);
            pair.complete(elapsed);
            pair.same("operand boundary=" + start);
            assertEquals(0, pair.directMemory.reads[0xfffe]);
            assertEquals(0, pair.directMemory.reads[0xffff]);
            assertEquals(0, pair.directMemory.outsideViewReads);
            assertLeaseCleared(pair.direct);
            pair.advance(2);
            pair.same("released accessory boundary=" + start);
            assertTrue(pair.directMemory.reads[0xfffe] > 0);
        }
    }

    @Test
    public void acquisitionAndReadExceptionsReleaseTheBorrowedReference() throws Exception {
        Pair acquireFailure = selfModifyingPair();
        acquireFailure.directMemory.throwOnAcquire = true;
        int entryPc = acquireFailure.direct.getRegisters().getPC();
        // Lazy acquisition occurs at the first opcode boundary, after the same idle
        // CPU phase that canonical execution consumes before fetching the instruction.
        acquireFailure.scalar.tick();
        var beforeFetch = acquireFailure.scalar.captureState();
        assertThrows(IllegalStateException.class,
                () -> acquireFailure.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
        assertLeaseCleared(acquireFailure.direct);
        assertStateEquals("failed lazy acquisition retains the canonical idle phase", beforeFetch,
                acquireFailure.direct.captureState());
        assertEquals("acquisition failure must precede opcode fetch", entryPc,
                acquireFailure.direct.getRegisters().getPC());
        assertEquals(0, instructionBusReads(acquireFailure.directMemory));
        assertEquals(0, acquireFailure.directMemory.leasedReads);
        acquireFailure.same("failed lazy acquisition before opcode fetch");

        Pair readFailure = selfModifyingPair();
        readFailure.directMemory.throwOnRead = true;
        assertThrows(IllegalStateException.class,
                () -> readFailure.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
        assertLeaseCleared(readFailure.direct);
        var bus = Cpu.class.getDeclaredField("addressSpace");
        bus.setAccessible(true);
        assertSame("the canonical bus must be restored on error", readFailure.directMemory,
                bus.get(readFailure.direct));
    }

    private static Pair selfModifyingPair() {
        Pair pair = new Pair(0xff80, 0x21, 0x90, 0xff, 0x36, 0x04,
                0x21, 0xf0, 0xff, 0xc3, 0x90, 0xff);
        pair.put(0xff90, 0x0c, 0x7e, 0xc3, 0x90, 0xff);
        pair.put(0xfff0, 0x7b);
        pair.clearCounts();
        return pair;
    }

    private static int instructionBusReads(Memory memory) {
        int count = 0;
        for (int address = 0xff80; address <= 0xff94; address++) count += memory.reads[address];
        return count;
    }

    private static void assertLeaseCleared(Cpu cpu) throws Exception {
        var field = Cpu.class.getDeclaredField("performanceDetailedPpuHramReadAccess");
        field.setAccessible(true);
        assertNull("borrowed HRAM cannot escape a packet/state boundary", field.get(cpu));
        var budget = Cpu.class.getDeclaredField("performanceDetailedPpuHramReadBudget");
        budget.setAccessible(true);
        assertEquals("pending acquisition must not escape a packet/state boundary", 0, budget.getInt(cpu));
    }

    private static final class Pair {
        final Memory directMemory = new Memory();
        final Memory scalarMemory = new Memory();
        final InterruptManager directInterrupts = new InterruptManager(true);
        final InterruptManager scalarInterrupts = new InterruptManager(true);
        final Cpu direct = cpu(directMemory, directInterrupts);
        final Cpu scalar = cpu(scalarMemory, scalarInterrupts);
        Pair(int pc, int... program) {
            direct.getRegisters().setPC(pc);
            scalar.getRegisters().setPC(pc);
            put(pc, program);
        }
        void put(int address, int... program) {
            for (int i = 0; i < program.length; i++) {
                directMemory.bytes[address + i] = scalarMemory.bytes[address + i] = (byte) program[i];
            }
        }
        void clearCounts() { directMemory.clearCounts(); scalarMemory.clearCounts(); }
        void advance(int ticks) { for (int i = 0; i < ticks; i++) { direct.tick(); scalar.tick(); } }
        void complete(int ticks) {
            for (int i = 0; i < ticks; i++) scalar.tick();
            assertFalse("strict HRAM work must not create a journal", direct.hasPerformanceEpochJournal());
        }
        void same(String label) {
            assertStateEquals(label + " CPU", scalar.captureState(), direct.captureState());
            assertStateEquals(label + " IF", scalarInterrupts.captureState(), directInterrupts.captureState());
            assertArrayEquals(label + " memory", scalarMemory.bytes, directMemory.bytes);
            assertArrayEquals(label + " canonical writes", scalarMemory.writes, directMemory.writes);
        }
    }

    private static Cpu cpu(Memory memory, InterruptManager interrupts) {
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        assertTrue(speed.onStop());
        return new Cpu(memory, interrupts, null, speed, new Display(false));
    }

    /** An explicit inert-memory provider; counters measure use but are not emulated state. */
    private static final class Memory implements AddressSpace, PerformanceRomAccessProvider {
        final byte[] bytes = new byte[65536];
        final int[] reads = new int[65536];
        final int[] writes = new int[65536];
        boolean capability = true;
        boolean throwOnAcquire;
        boolean throwOnRead;
        int hramAcquisitions;
        int lastHramRequested;
        int leasedReads;
        int outsideViewReads;
        final PerformanceHramReadAccess hramView = address -> {
            if (address < 0xff80 || address > 0xfffd) { outsideViewReads++; return -1; }
            if (throwOnRead) throw new IllegalStateException("test read failure");
            leasedReads++;
            return bytes[address] & 255;
        };
        final PerformanceRomAccess romView = new PerformanceRomAccess() {
            @Override public int physicalOffset(int address) { return -1; }
            @Override public int readPhysicalByte(int offset) { throw new AssertionError("logical ROM"); }
            @Override public int readCpuByte(int address) { return getByte(address); }
            @Override public int peekCpuByte(int address) { return bytes[address & 65535] & 255; }
        };
        void clearCounts() {
            java.util.Arrays.fill(reads, 0);
            java.util.Arrays.fill(writes, 0);
            hramAcquisitions = leasedReads = outsideViewReads = 0;
        }
        @Override public boolean accepts(int address) { return true; }
        @Override public int getByte(int address) { reads[address & 65535]++; return bytes[address & 65535] & 255; }
        @Override public void setByte(int address, int value) { writes[address & 65535]++; bytes[address & 65535] = (byte) value; }
        @Override public PerformanceRomAccess acquirePerformanceRomAccess() { return romView; }
        @Override public PerformanceHramReadAccess acquirePerformanceDetailedPpuHramReadAccess(int requested) {
            hramAcquisitions++;
            lastHramRequested = requested;
            if (throwOnAcquire) throw new IllegalStateException("test acquisition failure");
            return capability ? hramView : null;
        }
    }
}
