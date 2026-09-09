package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** Canonical copy transactions, progress and ownership survive every CPU-clock residue. */
public class DmaWramReplayDotTest {
    @Test
    public void everyDotPrefixMatchesCopyCallbacksAndRestoredScalarTails() {
        for (int source : new int[]{0xc000, 0xd500, 0xdf00}) {
            for (int clocks = 8; clocks < 644; clocks++) {
                Fixture scalar = new Fixture(source, clocks);
                Fixture bulk = new Fixture(source, clocks);
                var saved = scalar.dma.captureState();
                int[] savedOam = scalar.oam.clone();
                int horizon = bulk.dma.performanceNativeCgbWramReplaySpanLimit(54);
                for (int count = 1; count <= 54; count++) {
                    if (count > horizon) continue;
                    scalar.restore(saved, savedOam);
                    bulk.restore(saved, savedOam);
                    long generation = bulk.dma.getPpuBusGeneration();
                    assertEquals(count, bulk.dma.performanceNativeCgbWramReplaySpanLimit(count));
                    assertTrue("preflight must not read the source", bulk.events.isEmpty());
                    scalar.tick(count);
                    for (int dot = 0; dot < count; dot++) bulk.dma.tickPerformanceNativeCgbWramReplayTrusted();
                    assertEquals(count, bulk.dma.getPpuBusGeneration() - generation);
                    same("copy " + source + '/' + clocks + '/' + count, scalar, bulk);

                    // Restore the exact partial prefix before letting the ordinary owner
                    // resume, including copy clocks and the final ownership release tail.
                    var partial = scalar.dma.captureState();
                    int[] partialOam = scalar.oam.clone();
                    scalar.restore(partial, partialOam);
                    bulk.restore(partial, partialOam);
                    scalar.tick(5);
                    bulk.tick(5);
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
                for (int dot = 0; dot < horizon; dot++) bulk.dma.tickPerformanceNativeCgbWramReplayTrusted();
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
    public void invalidOwnerRailsRejectBeforeAnyDotOrCallback() {
        for (int source:new int[]{0,0x8000,0xa000,0xc000,0xdf00,0xe000,0xff00})
            for (int clocks:new int[]{0,1,6,7,8,9,642,643,644,647,648}) {
                Fixture f=new Fixture(source,clocks);
                if(f.dma.performanceNativeCgbWramReplaySpanLimit(1)==1) continue;
                rejected(f,"source/clock rail");
            }
        for(int kind=0;kind<5;kind++) {
            Fixture f=new Fixture(0xc000,32);
            switch(kind) {
                case 0 -> f.speed=1;
                case 1 -> f.dma.setByte(0xff46,0xc1);
                case 2 -> f.dma.tick(true,true);
                case 3 -> f.dma.setVramDmaBusSample(new Hdma.SourceBusSample(0xc000,0x83));
                case 4 -> {f.dma.setCpuInterruptStackWrite(true);f.dma.onCpuBusWrite(0x5a);}
            }
            rejected(f,"transition rail="+kind);
        }
        Fixture debug=new Fixture(0xc000,32);final int[] callbacks={0};
        debug.dma.setDebugHooks(new eu.rekawek.coffeegb.core.debug.DebugHooks(){
            public void onInstructionFetch(int pc){}
            public void onOpcodeFetched(int pc,boolean cb,int opcode){}
            public void onInstructionRetired(boolean known,int pc,int opcode,int cb){}
            public void onInterruptRequested(eu.rekawek.coffeegb.core.debug.DebugInterruptType t){}
            public void onInterruptAccepted(eu.rekawek.coffeegb.core.debug.DebugInterruptType t){}
            public void onDmaEvent(eu.rekawek.coffeegb.core.debug.trace.DmaTrace.Engine engine,
                    eu.rekawek.coffeegb.core.debug.trace.DmaTrace.Kind kind,
                    int source,int destination,int length,int bytes){callbacks[0]++;}
        });
        rejected(debug,"debug callback rail");assertEquals(0,callbacks[0]);
        debug.tick(2);assertTrue("rejected debug events stay on the canonical owner",callbacks[0]>0);
    }

    @Test
    public void inactiveBusMarkerNormalizesAtTheSameExactDot() {
        for(int marker:new int[]{-1,-2,Integer.MIN_VALUE}) {
            Fixture scalar=new Fixture(0xc000,11),fast=new Fixture(0xc000,11);
            for(Fixture f:new Fixture[]{scalar,fast})
                f.dma.setVramDmaBusSample(new Hdma.SourceBusSample(marker,0x9a));
            assertEquals(1,fast.dma.performanceNativeCgbWramReplaySpanLimit(1));
            scalar.tick(1);fast.dma.tickPerformanceNativeCgbWramReplayTrusted();
            same("negative inactive marker="+marker,scalar,fast);
        }
    }

    private static void rejected(Fixture f,String label) {
        f.events.clear();assertEquals(label,0,f.dma.performanceNativeCgbWramReplaySpanLimit(1));
        var before=f.dma.captureState();long generation=f.dma.getPpuBusGeneration();
        assertThrows(AssertionError.class,f.dma::tickPerformanceNativeCgbWramReplayTrusted);
        assertEquals(label,before,f.dma.captureState());assertEquals(label,generation,f.dma.getPpuBusGeneration());
        assertTrue(label,f.events.isEmpty());
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
