package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class CpuDetailedPpuPerformanceEpochTest {
    @Test
    public void romAndHramLoopsMatchEveryBudgetAndPhaseAcrossRestore() {
        for (int pc : new int[]{0, 0xff80}) {
            for (int phase = 0; phase < 2; phase++) {
                for (int budget = 1; budget <= 63; budget++) {
                    Pair pair = new Pair(pc, 0x21, 0xf0, 0xff, 0x34, 0x7e, 0x04,
                            0xc3, pc & 255, pc >>> 8);
                    pair.advance(phase);
                    var saved = pair.direct.captureState();
                    pair.direct.tick();
                    pair.direct.restoreState(saved);
                    pair.directMemory.clearCounts();
                    pair.scalarMemory.clearCounts();
                    int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(budget);
                    assertEquals("ordinary ROM/HRAM work fragmented", budget, elapsed);
                    pair.complete(elapsed);
                    pair.same("pc=" + pc + " phase=" + phase + " budget=" + budget);
                    assertEquals("HRAM data reads", pair.scalarMemory.reads[0xfff0],
                            pair.directMemory.reads[0xfff0]);
                    if (pc == 0) assertArrayEquals("logical ROM fetched once",
                            pair.scalarMemory.reads, pair.directMemory.reads);
                    assertEquals(0, pair.direct.getPerformanceEpochTerminalAccesses());
                }
            }
        }
    }

    @Test
    public void forbiddenDataCyclesFenceBeforeReadWriteOrReadModifyWrite() {
        for (int address : new int[]{0x1000, 0x8000, 0xa000, 0xc000, 0xe000,
                0xfe00, 0xff00, 0xff0f, 0xff40, 0xff41, 0xff44, 0xff46, 0xfffe, 0xffff}) {
            for (int opcode : new int[]{0x7e, 0x77, 0x34, 0x36, 0xfa, 0xea, 0x08}) {
                if (address < 0x8000 && (opcode == 0x7e || opcode == 0xfa)) continue;
                for (int phase = 0; phase < 2; phase++) {
                    Pair pair = new Pair(0, opcode,
                            opcode == 0x36 ? 0x63 : address & 255, address >>> 8, 0);
                    pair.scalar.getRegisters().setHL(address);
                    pair.direct.getRegisters().setHL(address);
                    pair.advance(phase);
                    int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
                    assertTrue("no pre-bus fence", elapsed < 54);
                    pair.complete(elapsed);
                    pair.same("fence " + address + '/' + opcode + '/' + phase);
                    assertEquals("forbidden read reached bus", 0, pair.directMemory.reads[address]);
                    assertEquals("forbidden write reached bus", 0, pair.directMemory.writes[address]);
                    assertEquals("forbidden write entered journal", 0,
                            pair.direct.getPerformanceEpochTerminalAccesses());
                    // Returning releases the narrow lease: the canonical scalar boundary may
                    // now perform this access once under the owner's actual DMA/PPU ordering.
                    pair.advance(2);
                    pair.same("released fence " + address + '/' + opcode + '/' + phase);
                }
            }
        }
    }

    @Test
    public void lifecycleAndInterruptEntryRemainWithScalarOwner() {
        for (int opcode : new int[]{0x10, 0x76, 0xf3, 0xfb, 0xd9}) {
            for (int pc : new int[]{0, 0xff80}) {
                Pair pair = new Pair(pc, opcode, 0);
                assertEquals(1, pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
                pair.complete(1);
                pair.same("lifecycle prefetch " + opcode + '/' + pc);
                assertEquals(pc, pair.direct.getRegisters().getPC());
            }
        }
        Pair enabled = new Pair(0, 0);
        enabled.directInterrupts.enableInterrupts(false);
        assertEquals(0, enabled.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
        Pair delayed = new Pair(0, 0);
        delayed.directInterrupts.enableInterrupts(true);
        assertEquals(0, delayed.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
        Pair pending = new Pair(0, 0x04, 0xc3, 0, 0);
        for (InterruptManager interrupts : new InterruptManager[]{
                pending.directInterrupts, pending.scalarInterrupts}) {
            interrupts.setByte(0xffff, 1);
            interrupts.requestInterrupt(InterruptManager.InterruptType.VBlank);
        }
        assertEquals(54, pending.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
        pending.complete(54);
        pending.same("IME-off pending IF");
        Pair unknown = new Pair(0, 0);
        unknown.directMemory.peekSafe = false;
        assertEquals(1, unknown.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
        assertEquals("unknown mapper must not be speculatively fetched", 0,
                unknown.directMemory.reads[0]);
    }

    @Test
    public void decodedLifecycleAndMissingRomCapabilitiesCannotEnterDetailedPackets() {
        for (int opcode : new int[]{0x10, 0x76, 0xf3, 0xfb, 0xd9}) {
            for (int prefix = 1; prefix <= 16; prefix++) {
                Pair pair = new Pair(0, opcode, 0);
                pair.advance(prefix);
                if (pair.direct.getState() != Cpu.State.OPCODE) {
                    var before = pair.direct.captureState();
                    assertEquals("decoded lifecycle " + opcode + '/' + prefix,
                            0, pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(54));
                    assertStateEquals("rejected lifecycle must be untouched", before,
                            pair.direct.captureState());
                }
            }
        }
        for (int opcode : new int[]{0x0a, 0x1a, 0x7e, 0xfa}) {
            Pair pair = new Pair(0xff80, opcode, 0x00, 0x40);
            pair.directMemory.leaseAvailable = false;
            pair.scalar.getRegisters().setBC(0x4000);
            pair.direct.getRegisters().setBC(0x4000);
            pair.scalar.getRegisters().setDE(0x4000);
            pair.direct.getRegisters().setDE(0x4000);
            pair.scalar.getRegisters().setHL(0x4000);
            pair.direct.getRegisters().setHL(0x4000);
            int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(54);
            assertTrue(elapsed < 54);
            pair.complete(elapsed);
            pair.same("missing ROM lease " + opcode);
            assertEquals(0, pair.directMemory.reads[0x4000]);
        }
    }

    @Test
    public void foldedControlAndRegisterInstructionsPreserveEveryPartialBudgetAndOperandFence() {
        int[] opcodes = {0x18, 0x20, 0x28, 0x30, 0x38, 0xc3, 0xc2, 0xca, 0xd2, 0xda,
                0x03, 0x13, 0x23, 0x33, 0x0b, 0x1b, 0x2b, 0x3b,
                0x09, 0x19, 0x29, 0x39, 0xf9};
        for (int opcode : opcodes) {
            for (int pc : new int[]{0, 0x7ffe, 0xff80, 0xfffd}) {
                for (int phase = 0; phase < 2; phase++) {
                    Pair pair = new Pair(pc, opcode, 0, 0);
                    for (Cpu cpu : new Cpu[]{pair.direct, pair.scalar}) {
                        cpu.getRegisters().getFlags().setFlagsByte(phase == 0 ? 0 : 0x90);
                        cpu.getRegisters().setBC(0x0fff);
                        cpu.getRegisters().setDE(0x8001);
                        cpu.getRegisters().setHL(0xeffe);
                        cpu.getRegisters().setSP(0xffff);
                    }
                    pair.advance(phase);
                    var directState = pair.direct.captureState();
                    var scalarState = pair.scalar.captureState();
                    var directInterrupts = pair.directInterrupts.captureState();
                    var scalarInterrupts = pair.scalarInterrupts.captureState();
                    for (int budget = 1; budget <= 16; budget++) {
                        pair.direct.restoreState(directState);
                        pair.scalar.restoreState(scalarState);
                        pair.directInterrupts.restoreState(directInterrupts);
                        pair.scalarInterrupts.restoreState(scalarInterrupts);
                        pair.directMemory.clearCounts();
                        pair.scalarMemory.clearCounts();
                        int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(budget);
                        assertTrue(elapsed <= budget);
                        pair.complete(elapsed);
                        pair.same("folded opcode=" + opcode + " pc=" + pc
                                + " phase=" + phase + " budget=" + budget);
                        assertEquals("VRAM operand crossed the read fence", 0,
                                pair.directMemory.reads[0x8000]);
                        assertEquals("accessory operand crossed the read fence", 0,
                                pair.directMemory.reads[0xfffe]);
                        if (pc < 0x8000) assertArrayEquals("logical ROM read once",
                                pair.scalarMemory.reads, pair.directMemory.reads);
                    }
                }
            }
        }
    }

    @Test
    public void releasedHdmaPrefetchFencesOperandCompleteStackReadsAcrossRestore() {
        for (int opcode : new int[]{0xc1, 0xd1, 0xe1, 0xf1, 0xc9}) {
            for (int address : new int[]{0xc000, 0xff7f, 0xfffe}) {
                for (int phase = 0; phase < 2; phase++) {
                    Pair pair = new Pair(0, opcode, 0);
                    for (Cpu cpu : new Cpu[]{pair.direct, pair.scalar}) {
                        cpu.getRegisters().setSP(address);
                        cpu.prefetchOpcodeForHdma();
                        cpu.releaseHdmaPrefetchedOpcode();
                    }
                    pair.advance(phase);
                    assertEquals(Cpu.State.OPERAND, pair.direct.getState());
                    var directState = pair.direct.captureState();
                    var scalarState = pair.scalar.captureState();
                    for (int budget = 1; budget <= 63; budget++) {
                        pair.direct.restoreState(directState);
                        pair.scalar.restoreState(scalarState);
                        pair.directMemory.clearCounts();
                        pair.scalarMemory.clearCounts();
                        int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(budget);
                        assertEquals("only the phase before the stack cycle is admissible",
                                phase == 0 ? 1 : 0, elapsed);
                        pair.complete(elapsed);
                        pair.same("released HDMA prefetch " + opcode + '/' + address
                                + '/' + phase + '/' + budget);
                        assertEquals("stack read must stay outside the packet", 0,
                                pair.directMemory.reads[address]);
                        assertEquals(address, pair.direct.getRegisters().getSP());
                        pair.advance(2);
                        pair.same("canonical stack boundary after release");
                        assertEquals(1, pair.directMemory.reads[address]);
                    }
                }
            }
        }
    }

    @Test
    public void decodedMemoryFamiliesPreserveEveryPrefixAndStackBoundaryAfterHdmaRelease() {
        int[][] programs = {
                {0xc1}, {0xd1}, {0xe1}, {0xf1}, // POP
                {0xc9}, {0xc0}, {0xc8}, {0xd0}, {0xd8}, // RET and both conditions
                {0xc5}, {0xd5}, {0xe5}, {0xf5}, // PUSH
                {0xcb, 0x06}, {0xcb, 0x46}, {0xcb, 0x86}, {0xcb, 0xc6},
                {0x0a}, {0x1a}, {0x7e}, {0x77}, {0x22}, {0x2a}, {0x32}, {0x3a},
                {0x36, 0x5a}, {0xfa}, {0xea}, {0x08}
        };
        for (int[] program : programs) {
            for (int address : new int[]{0x4000, 0x7fff, 0xc000, 0xff7f,
                    0xff80, 0xff81, 0xfffd, 0xfffe}) {
                int opcode = program[0];
                int[] bytes = program;
                if (opcode == 0xfa || opcode == 0xea || opcode == 0x08) {
                    bytes = new int[]{opcode, address & 255, address >>> 8};
                }
                for (int prefix = 0; prefix <= 8; prefix++) {
                    Pair pair = new Pair(0, bytes);
                    for (Cpu cpu : new Cpu[]{pair.direct, pair.scalar}) {
                        cpu.getRegisters().setBC(address);
                        cpu.getRegisters().setDE(address);
                        cpu.getRegisters().setHL(address);
                        cpu.getRegisters().setSP(address);
                        cpu.getRegisters().getFlags().setFlagsByte((prefix & 2) == 0 ? 0 : 0x90);
                        cpu.prefetchOpcodeForHdma();
                        cpu.releaseHdmaPrefetchedOpcode();
                    }
                    pair.advance(prefix);
                    var directState = pair.direct.captureState();
                    var scalarState = pair.scalar.captureState();
                    byte[] directBytes = pair.directMemory.bytes.clone();
                    byte[] scalarBytes = pair.scalarMemory.bytes.clone();
                    for (int budget : new int[]{1, 2, 3, 8, 16}) {
                        pair.direct.restoreState(directState);
                        pair.scalar.restoreState(scalarState);
                        System.arraycopy(directBytes, 0, pair.directMemory.bytes, 0, 65536);
                        System.arraycopy(scalarBytes, 0, pair.scalarMemory.bytes, 0, 65536);
                        pair.directMemory.clearCounts();
                        pair.scalarMemory.clearCounts();
                        int elapsed = pair.direct.runNativeCgbDetailedPpuPerformanceEpoch(budget);
                        assertTrue(elapsed <= budget);
                        pair.complete(elapsed);
                        String label = "decoded memory opcode=" + opcode + " address=" + address
                                + " prefix=" + prefix + " budget=" + budget;
                        pair.same(label);
                        assertArrayEquals(label + " canonical reads", pair.scalarMemory.reads,
                                pair.directMemory.reads);
                        assertEquals("strict accesses cannot be journaled", 0,
                                pair.direct.getPerformanceEpochTerminalAccesses());
                        pair.advance(2);
                        pair.same(label + " scalar continuation");
                    }
                }
            }
        }
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
            for (int i = 0; i < program.length; i++) {
                directMemory.bytes[pc + i] = scalarMemory.bytes[pc + i] = (byte) program[i];
            }
        }

        void advance(int ticks) { for (int i = 0; i < ticks; i++) { direct.tick(); scalar.tick(); } }
        void complete(int ticks) {
            for (int i = 0; i < ticks; i++) scalar.tick();
            direct.replayPerformanceEpochJournal();
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

    private static final class Memory implements AddressSpace, PerformanceRomAccessProvider,
            PerformanceRomAccess {
        final byte[] bytes = new byte[65536];
        final int[] reads = new int[65536];
        final int[] writes = new int[65536];
        boolean peekSafe = true;
        boolean leaseAvailable = true;
        void clearCounts() { java.util.Arrays.fill(reads, 0); java.util.Arrays.fill(writes, 0); }
        @Override public boolean accepts(int address) { return true; }
        @Override public int getByte(int address) { reads[address & 65535]++; return bytes[address & 65535] & 255; }
        @Override public void setByte(int address, int value) { writes[address & 65535]++; bytes[address & 65535] = (byte) value; }
        @Override public PerformanceRomAccess acquirePerformanceRomAccess() { return leaseAvailable ? this : null; }
        @Override public int physicalOffset(int address) { return -1; }
        @Override public int readPhysicalByte(int offset) { throw new AssertionError("logical ROM"); }
        @Override public int readCpuByte(int address) { return getByte(address); }
        @Override public int peekCpuByte(int address) { return peekSafe ? bytes[address & 65535] & 255 : -1; }
    }
}
