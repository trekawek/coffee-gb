package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** Canonical copy transactions, progress and ownership survive every CPU-clock residue. */
public class DmaWramCopySpanTest {
    @Test
    public void copyPrefixesMatchScalarTransactionsAndRestoredContinuations() {
        for (int source : new int[]{0xc000, 0xd500, 0xdf00}) {
            for (int clocks = 8; clocks < 644; clocks++) {
                Fixture scalar = new Fixture(source, clocks);
                Fixture bulk = new Fixture(source, clocks);
                var saved = scalar.dma.captureState();
                int[] savedOam = scalar.oam.clone();
                int horizon = bulk.dma.performanceNativeCgbWramReplaySpanLimit(54);
                for (int count : new int[]{1, 2, 3, 7, 16, 31, 54}) {
                    if (count > horizon) continue;
                    scalar.restore(saved, savedOam);
                    bulk.restore(saved, savedOam);
                    long generation = bulk.dma.getPpuBusGeneration();
                    assertEquals(count, bulk.dma.performanceNativeCgbWramReplaySpanLimit(count));
                    assertTrue("preflight must not read the source", bulk.events.isEmpty());
                    scalar.tick(count);
                    bulk.dma.advancePerformanceNativeCgbWramCopySpanTrusted(count);
                    assertEquals(count, bulk.dma.getPpuBusGeneration() - generation);
                    same("copy " + source + '/' + clocks + '/' + count, scalar, bulk);

                    scalar.tick(3);
                    bulk.tick(3);
                    same("restored scalar continuation", scalar, bulk);
                }
            }
        }
    }

    @Test
    public void finalCopyAndOwnershipReleaseRemainSeparateScalarEvents() {
        for (int clocks = 630; clocks < 644; clocks++) {
            Fixture scalar = new Fixture(0xc000, clocks);
            Fixture bulk = new Fixture(0xc000, clocks);
            int horizon = bulk.dma.performanceNativeCgbWramReplaySpanLimit(54);
            if (horizon > 0) {
                scalar.tick(horizon);
                bulk.dma.advancePerformanceNativeCgbWramCopySpanTrusted(horizon);
                same("last copy prefix", scalar, bulk);
            }
            assertEquals(0, bulk.dma.performanceNativeCgbWramReplaySpanLimit(1));
            assertTrue(bulk.dma.isTransferInProgress());
            assertTrue(bulk.dma.ownsOamForPpu());
            for (int tail = 0; tail < 4; tail++) {
                scalar.tick(1);
                bulk.tick(1);
                same("ownership release dot " + tail, scalar, bulk);
            }
            assertFalse(bulk.dma.isTransferInProgress());
            assertFalse(bulk.dma.ownsOamForPpu());
        }
    }

    @Test
    public void rejectedPrefixesHaveNoBusOrClockEffects() {
        Fixture fixture = new Fixture(0xc000, 640);
        var state = fixture.dma.captureState();
        long generation = fixture.dma.getPpuBusGeneration();
        assertThrows(IllegalStateException.class,
                () -> fixture.dma.advancePerformanceNativeCgbWramCopySpanTrusted(3));
        assertEquals(state, fixture.dma.captureState());
        assertEquals(generation, fixture.dma.getPpuBusGeneration());
        assertTrue(fixture.events.isEmpty());
        fixture.dma.setVramDmaBusSample(new Hdma.SourceBusSample(0xc000, 0x83));
        assertThrows(IllegalStateException.class,
                () -> fixture.dma.advancePerformanceNativeCgbWramCopySpanTrusted(1));
        assertTrue(fixture.events.isEmpty());
    }

    private static void same(String context, Fixture scalar, Fixture bulk) {
        assertEquals(context, scalar.dma.captureState(), bulk.dma.captureState());
        assertEquals(context + " byte transaction phase/order", scalar.events, bulk.events);
        assertArrayEquals(context, scalar.oam, bulk.oam);
        assertEquals(context, scalar.dma.getPpuBusGeneration(), bulk.dma.getPpuBusGeneration());
        assertEquals(context, scalar.dma.getCpuBusValue(), bulk.dma.getCpuBusValue());
        assertEquals(context, scalar.dma.isCpuAccessBlocked(0xc000, true),
                bulk.dma.isCpuAccessBlocked(0xc000, true));
    }

    private record Event(boolean write, int address, int value, int clocks, int ticks,
                         int nextByte, long generation, boolean ownedBefore, boolean owned) {}

    private static final class Fixture {
        final int[] oam = new int[160];
        final List<Event> events = new ArrayList<>();
        final Dma dma;
        int speed = 1;

        Fixture(int source, int clocks) {
            dma = new Dma(new AddressSpace() {
                @Override public boolean accepts(int address) { return true; }
                @Override public int getByte(int address) {
                    int value = (address * 37 ^ address >>> 5 ^ 0x69) & 255;
                    record(false, address, value);
                    return value;
                }
                @Override public void setByte(int address, int value) { fail("source write"); }
            }, new AddressSpace() {
                @Override public boolean accepts(int address) {
                    return address >= 0xfe00 && address < 0xfea0;
                }
                @Override public int getByte(int address) { return oam[address - 0xfe00]; }
                @Override public void setByte(int address, int value) {
                    oam[address - 0xfe00] = value;
                    record(true, address, value);
                }
            }, new SpeedMode(true) {
                @Override public int getSpeedMode() { return speed; }
            });
            dma.setByte(0xff46, source >>> 8);
            // Switching after an arbitrary normal-speed prefix exercises all four CPU-clock
            // residues, including the odd residues a native-x2-only startup cannot generate.
            tick(clocks);
            speed = 2;
            events.clear();
        }

        void restore(eu.rekawek.coffeegb.core.state.ComponentState<Dma> state, int[] bytes) {
            dma.restoreState(state);
            System.arraycopy(bytes, 0, oam, 0, oam.length);
            events.clear();
        }

        void record(boolean write, int address, int value) {
            var state = (Dma.DmaState) dma.captureState();
            events.add(new Event(write, address, value, state.transferClocks(), state.ticks(),
                    state.currentByte(), dma.getPpuBusGeneration(),
                    dma.ownedOamForPpuBeforeTick(), dma.ownsOamForPpu()));
        }

        void tick(int count) {
            for (int i = 0; i < count; i++) dma.tick(false, false);
        }
    }
}
