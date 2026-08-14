package eu.rekawek.coffeegb.core.experimental.clock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HalfDotSignalSchedulerTest {

    private static final int TIMER = 1 << 2;

    private static final int SERIAL = 1 << 3;

    @Test
    public void fixedAndCpuDomainsKeepTheirHalfDotCadenceAcrossSpeedChanges() {
        ClockProbe probe = new ClockProbe();
        HalfDotSignalScheduler scheduler = scheduler(false,
                new HalfDotSignalScheduler.Island[]{probe}, new int[]{0}, new int[]{0}, new int[]{0});

        for (int i = 0; i < 8; i++) {
            scheduler.tick();
        }
        assertEquals(4, probe.fixedClocks);
        assertEquals(4, probe.cpuClocks);

        scheduler.setDoubleSpeed(true);
        for (int i = 0; i < 8; i++) {
            scheduler.tick();
        }
        assertEquals(8, probe.fixedClocks);
        assertEquals(12, probe.cpuClocks);
        assertEquals(16, scheduler.halfDot());

        scheduler.setDoubleSpeed(false);
        scheduler.tick();
        assertTrue(scheduler.fixedClockEnable());
        assertTrue(scheduler.cpuClockEnable());
        scheduler.tick();
        assertFalse(scheduler.fixedClockEnable());
        assertFalse(scheduler.cpuClockEnable());
    }

    @Test
    public void sameHalfDotTimerSerialAndCpuAcknowledgeAreIndependentOfEveryPhaseOrder() {
        List<int[]> permutations = permutations(4);
        for (boolean doubleSpeed : new boolean[]{false, true}) {
            for (int prefixHalfDots = 0; prefixHalfDots < 4; prefixHalfDots++) {
                for (int[] driveOrder : permutations) {
                    for (int[] resolveOrder : permutations) {
                        for (int[] commitOrder : permutations) {
                            CollisionResult result = runCollision(
                                    doubleSpeed, prefixHalfDots,
                                    driveOrder, resolveOrder, commitOrder);
                            String context = "double=" + doubleSpeed
                                    + ", prefix=" + prefixHalfDots;
                            assertEquals(context, TIMER | SERIAL, result.requests);
                            assertEquals(context, TIMER, result.acknowledgements);
                            assertEquals(context, SERIAL, result.interruptFlags);
                            assertFalse(context, result.timerPending);
                            assertFalse(context, result.serialPending);
                            assertFalse(context, result.acknowledgePending);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void clearDominanceTreatsEquivalentTimerAndSerialCollisionsSymmetrically() {
        for (boolean doubleSpeed : new boolean[]{false, true}) {
            assertEquals(0, runSingleSourceCollision(doubleSpeed, TIMER));
            assertEquals(0, runSingleSourceCollision(doubleSpeed, SERIAL));
        }
    }

    @Test
    public void currentSequential419MhzOrderingIsAFalsifierForOneAtomicEdge() {
        // Gameboy.tick() currently advances Timer before Cpu, but SerialPort after Cpu. If all
        // three callbacks are treated as mutations of IF, physically equivalent request/clear
        // collisions get opposite answers solely from Java order.
        LegacySequentialIf timerCollision = new LegacySequentialIf();
        timerCollision.request(TIMER);
        timerCollision.acknowledge(TIMER);

        LegacySequentialIf serialCollision = new LegacySequentialIf();
        serialCollision.acknowledge(SERIAL);
        serialCollision.request(SERIAL);

        assertFalse((timerCollision.flags & TIMER) != 0);
        assertTrue((serialCollision.flags & SERIAL) != 0);
        assertNotEquals(
                (timerCollision.flags & TIMER) != 0,
                (serialCollision.flags & SERIAL) != 0);
    }

    @Test
    public void schedulerRejectsOrdersThatAreNotPermutations() {
        HalfDotSignalScheduler.Island[] islands = {new ClockProbe(), new ClockProbe()};
        assertThrows(IllegalArgumentException.class,
                () -> scheduler(false, islands, new int[]{0}, new int[]{0, 1}, new int[]{0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler(false, islands, new int[]{0, 0}, new int[]{0, 1}, new int[]{0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler(false, islands, new int[]{0, 2}, new int[]{0, 1}, new int[]{0, 1}));
    }

    private static CollisionResult runCollision(
            boolean doubleSpeed,
            int prefixHalfDots,
            int[] driveOrder,
            int[] resolveOrder,
            int[] commitOrder) {
        OneShotRequest timer = new OneShotRequest(TIMER);
        OneShotRequest serial = new OneShotRequest(SERIAL);
        OneShotAcknowledge cpu = new OneShotAcknowledge(TIMER);
        InterruptFlags interruptFlags = new InterruptFlags();
        HalfDotSignalScheduler scheduler = scheduler(
                doubleSpeed,
                new HalfDotSignalScheduler.Island[]{timer, serial, cpu, interruptFlags},
                driveOrder,
                resolveOrder,
                commitOrder);

        for (int i = 0; i < prefixHalfDots; i++) {
            scheduler.tick();
        }
        timer.arm();
        serial.arm();
        cpu.arm();
        do {
            scheduler.tick();
        } while (timer.pending || serial.pending || cpu.pending);

        return new CollisionResult(
                scheduler.lastRequestWires(),
                scheduler.lastAcknowledgeWires(),
                interruptFlags.flags,
                timer.pending,
                serial.pending,
                cpu.pending);
    }

    private static int runSingleSourceCollision(boolean doubleSpeed, int source) {
        OneShotRequest request = new OneShotRequest(source);
        OneShotAcknowledge cpu = new OneShotAcknowledge(source);
        InterruptFlags flags = new InterruptFlags();
        HalfDotSignalScheduler scheduler = scheduler(
                doubleSpeed,
                new HalfDotSignalScheduler.Island[]{request, cpu, flags},
                new int[]{0, 1, 2},
                new int[]{2, 1, 0},
                new int[]{1, 0, 2});
        request.arm();
        cpu.arm();
        scheduler.tick();
        return flags.flags;
    }

    private static HalfDotSignalScheduler scheduler(
            boolean doubleSpeed,
            HalfDotSignalScheduler.Island[] islands,
            int[] driveOrder,
            int[] resolveOrder,
            int[] commitOrder) {
        return new HalfDotSignalScheduler(
                doubleSpeed, islands, driveOrder, resolveOrder, commitOrder);
    }

    private static List<int[]> permutations(int size) {
        List<int[]> result = new ArrayList<>();
        int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = i;
        }
        do {
            result.add(values.clone());
        } while (nextPermutation(values));
        return result;
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) {
            pivot--;
        }
        if (pivot < 0) {
            return false;
        }
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) {
            successor--;
        }
        int tmp = values[pivot];
        values[pivot] = values[successor];
        values[successor] = tmp;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            tmp = values[left];
            values[left] = values[right];
            values[right] = tmp;
        }
        return true;
    }

    private static final class OneShotRequest implements HalfDotSignalScheduler.Island {

        private final int mask;

        private boolean pending;

        private boolean nextPending;

        private OneShotRequest(int mask) {
            this.mask = mask;
        }

        private void arm() {
            pending = true;
            nextPending = true;
        }

        @Override
        public void drive(boolean fixedClockEnable, boolean cpuClockEnable,
                          HalfDotSignalScheduler.WirePlane wires) {
            if (pending && cpuClockEnable) {
                wires.driveRequest(mask);
            }
        }

        @Override
        public void resolve(boolean fixedClockEnable, boolean cpuClockEnable,
                            HalfDotSignalScheduler.WirePlane wires) {
            nextPending = pending && !cpuClockEnable;
        }

        @Override
        public void commit() {
            pending = nextPending;
        }
    }

    private static final class OneShotAcknowledge implements HalfDotSignalScheduler.Island {

        private final int mask;

        private boolean pending;

        private boolean nextPending;

        private OneShotAcknowledge(int mask) {
            this.mask = mask;
        }

        private void arm() {
            pending = true;
            nextPending = true;
        }

        @Override
        public void drive(boolean fixedClockEnable, boolean cpuClockEnable,
                          HalfDotSignalScheduler.WirePlane wires) {
            if (pending && cpuClockEnable) {
                wires.driveAcknowledge(mask);
            }
        }

        @Override
        public void resolve(boolean fixedClockEnable, boolean cpuClockEnable,
                            HalfDotSignalScheduler.WirePlane wires) {
            nextPending = pending && !cpuClockEnable;
        }

        @Override
        public void commit() {
            pending = nextPending;
        }
    }

    private static final class InterruptFlags implements HalfDotSignalScheduler.Island {

        private int flags;

        private int nextFlags;

        @Override
        public void drive(boolean fixedClockEnable, boolean cpuClockEnable,
                          HalfDotSignalScheduler.WirePlane wires) {
        }

        @Override
        public void resolve(boolean fixedClockEnable, boolean cpuClockEnable,
                            HalfDotSignalScheduler.WirePlane wires) {
            // FF0F is clear-dominant when a selected CPU acknowledge meets a raw request wire.
            nextFlags = (flags | wires.requests()) & ~wires.acknowledgements();
        }

        @Override
        public void commit() {
            flags = nextFlags;
        }
    }

    private static final class ClockProbe implements HalfDotSignalScheduler.Island {

        private int fixedClocks;

        private int cpuClocks;

        private int nextFixedClocks;

        private int nextCpuClocks;

        @Override
        public void drive(boolean fixedClockEnable, boolean cpuClockEnable,
                          HalfDotSignalScheduler.WirePlane wires) {
        }

        @Override
        public void resolve(boolean fixedClockEnable, boolean cpuClockEnable,
                            HalfDotSignalScheduler.WirePlane wires) {
            nextFixedClocks = fixedClocks + (fixedClockEnable ? 1 : 0);
            nextCpuClocks = cpuClocks + (cpuClockEnable ? 1 : 0);
        }

        @Override
        public void commit() {
            fixedClocks = nextFixedClocks;
            cpuClocks = nextCpuClocks;
        }
    }

    private static final class LegacySequentialIf {

        private int flags;

        private void request(int mask) {
            flags |= mask;
        }

        private void acknowledge(int mask) {
            flags &= ~mask;
        }
    }

    private record CollisionResult(
            int requests,
            int acknowledgements,
            int interruptFlags,
            boolean timerPending,
            boolean serialPending,
            boolean acknowledgePending) {
    }
}
