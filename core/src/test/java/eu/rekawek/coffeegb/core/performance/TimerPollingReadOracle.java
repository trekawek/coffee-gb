package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.memory.Mmu;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/**
 * Test-only oracle for the historical one-master-dot FF04/FF05 read contract. It never changes
 * the candidate's behavior. The scalar replay calls every original bus target once and substitutes
 * only a recorded timer read proven equal to this tick or the immediately preceding Timer tick.
 * Every other observed data transaction and all writes retain exact address/value/dot checks.
 * The complete machine state can then be compared, including CPU, WRAM and Timer state.
 *
 * <p>The authored fixtures keep LCD on, execute ROM, do not write Timer/KEY1 after setup, and run
 * each segment shorter than one frame. Instrumentation reads only existing primitive phase fields;
 * it does not attach debug hooks, call GPU.getByte/capture, or query an admission horizon.
 */
public final class TimerPollingReadOracle implements AutoCloseable {
    private static final int FRAME_DOTS = 70_224;
    private static final Field MMU = field(Gameboy.class, "mmu");
    private static final Field MAP = field(Mmu.class, "addressToSpace");
    private static final Field ACTIVE = field(Cpu.class, "performanceEpochActive");
    private static final Field ELAPSED = field(Cpu.class, "performanceEpochElapsed");
    private static final Field COMMITTED = field(Gameboy.class, "performanceEpochPrefixCommitted");

    private final Gameboy scalar, candidate;
    private final Observer scalarBus, candidateBus;
    private final List<Transaction> transactions = new ArrayList<>();
    private int cursor, oldDiv, oldTima, scalarDot, anchor;
    private boolean recording, replaying;
    private String label;
    private long timerReads, previousDotReads, writes;

    public TimerPollingReadOracle(Gameboy scalar, Gameboy candidate) {
        this.scalar = scalar; this.candidate = candidate;
        scalarBus = new Observer(scalar, false); candidateBus = new Observer(candidate, true);
    }

    public int runTicks(String label, int ticks) {
        return runTicks(label, ticks, null);
    }

    /** A corruption hook is used only by the oracle's own negative tests. */
    int runTicks(String label, int ticks, Consumer<List<Transaction>> corruptTrace) {
        assertTrue("oracle segment must be shorter than one fixed LCD frame", ticks > 0 && ticks < FRAME_DOTS);
        assertTrue(scalar.getGpu().isLcdEnabled()); assertTrue(candidate.getGpu().isLcdEnabled());
        assertEquals(scalar.getGpu().getLine(), candidate.getGpu().getLine());
        assertEquals(scalar.getGpu().getTicksInLine(), candidate.getGpu().getTicksInLine());
        this.label = label; anchor = grid(candidate); cursor = 0; transactions.clear();
        scalarBus.requireInstalled(); candidateBus.requireInstalled();
        int candidateFrames;
        recording = true;
        try { candidateFrames = candidate.runTicks(ticks); }
        finally { recording = false; }
        if (corruptTrace != null) corruptTrace.accept(transactions);
        int scalarFrames = 0;
        replaying = true;
        try {
            for (scalarDot = 1; scalarDot <= ticks; scalarDot++) {
                // Direct owner reads are outside the installed CPU bus and have no side effects.
                oldDiv = scalarBus.original[0xff04].getByte(0xff04);
                oldTima = scalarBus.original[0xff05].getByte(0xff05);
                scalarFrames += scalar.runTicks(1);
            }
        } finally { replaying = false; }
        assertEquals(label + " unconsumed CPU transactions", transactions.size(), cursor);
        assertEquals(label + " frame publications", scalarFrames, candidateFrames);
        return candidateFrames;
    }

    public long timerReads() { return timerReads; }
    public long previousDotReads() { return previousDotReads; }
    public long writes() { return writes; }

    private int cpuDot(Gameboy machine) {
        int dot = Math.floorMod(grid(machine) - anchor, FRAME_DOTS) + 1;
        try {
            if (ACTIVE.getBoolean(machine.getCpu())) {
                // A safe data transaction may run ahead of the published peripheral prefix.
                // Fenced timer reads already flushed that prefix, making this correction zero.
                dot += ELAPSED.getInt(machine.getCpu()) - COMMITTED.getInt(machine);
            }
        } catch (IllegalAccessException e) { throw new AssertionError(e); }
        return dot;
    }

    private int observeRead(Gameboy machine, boolean candidateSide, int address, int actual) {
        // Immutable instruction bytes can use an existing lease and need no transaction oracle.
        if (address < 0x8000) return actual;
        if (candidateSide && recording) {
            transactions.add(new Transaction(cpuDot(machine), address, actual, false));
        } else if (!candidateSide && replaying) {
            Transaction expected = next(false, address);
            int dot = cpuDot(machine);
            assertEquals(label + " scalar clock anchor", scalarDot, dot);
            assertEquals(label + " read dot at " + Integer.toHexString(address), expected.dot, dot);
            if (address == 0xff04 || address == 0xff05) {
                int old = address == 0xff04 ? oldDiv : oldTima;
                assertTrue(label + " Timer sample exceeds the current/previous-dot contract at "
                                + dot + ": observed=" + expected.value + " previous=" + old + " current=" + actual,
                        expected.value == actual || expected.value == old);
                timerReads++;
                if (expected.value != actual) previousDotReads++;
                return expected.value;
            }
            assertEquals(label + " non-Timer read value at " + Integer.toHexString(address),
                    expected.value, actual);
        }
        return actual;
    }

    private void observeWrite(Gameboy machine, boolean candidateSide, int address, int value) {
        if (candidateSide && recording) {
            transactions.add(new Transaction(cpuDot(machine), address, value, true));
        } else if (!candidateSide && replaying) {
            Transaction expected = next(true, address);
            assertEquals(label + " write dot at " + Integer.toHexString(address), expected.dot, cpuDot(machine));
            assertEquals(label + " write value at " + Integer.toHexString(address), expected.value, value);
            writes++;
        }
    }

    private Transaction next(boolean write, int address) {
        assertTrue(label + " unexpected scalar transaction", cursor < transactions.size());
        Transaction expected = transactions.get(cursor++);
        assertEquals(label + " transaction direction", expected.write, write);
        assertEquals(label + " transaction address", expected.address, address);
        return expected;
    }

    private static int grid(Gameboy machine) {
        return machine.getGpu().getLine() * 456 + machine.getGpu().getTicksInLine();
    }

    @Override public void close() { scalarBus.close(); candidateBus.close(); }

    static record Transaction(int dot, int address, int value, boolean write) {}

    private final class Observer implements AddressSpace, AutoCloseable {
        private final Gameboy machine;
        private final boolean candidateSide;
        private final Mmu mmu;
        private final AddressSpace[] indexed, original;

        Observer(Gameboy machine, boolean candidateSide) {
            this.machine = machine; this.candidateSide = candidateSide;
            try {
                mmu = (Mmu) MMU.get(machine);
                indexed = (AddressSpace[]) MAP.get(mmu); original = indexed.clone();
                Arrays.fill(indexed, this);
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
        }
        void requireInstalled() {
            try { assertSame("restore replaced the test CPU bus index", indexed, MAP.get(mmu)); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            assertSame(this, indexed[0xff04]);
        }
        @Override public boolean accepts(int address) { return true; }
        @Override public int getByte(int address) {
            int value = original[address].getByte(address);
            return observeRead(machine, candidateSide, address, value);
        }
        @Override public void setByte(int address, int value) {
            observeWrite(machine, candidateSide, address, value);
            original[address].setByte(address, value);
        }
        @Override public void setByteFromCpu(int address, int value) {
            observeWrite(machine, candidateSide, address, value);
            original[address].setByteFromCpu(address, value);
        }
        @Override public void close() { System.arraycopy(original, 0, indexed, 0, original.length); }
    }

    private static Field field(Class<?> owner, String name) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
