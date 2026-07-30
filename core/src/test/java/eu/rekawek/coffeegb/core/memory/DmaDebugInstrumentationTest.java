package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.trace.DmaTrace;
import eu.rekawek.coffeegb.core.debug.trace.MemoryAccessTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmaDebugInstrumentationTest {

    @Test
    public void oamDmaReportsEachPhysicalAccessOnceAndInBusOrder() {
        Fixture fixture = new Fixture(EnumSet.of(TraceCategory.MEMORY, TraceCategory.DMA), 512);

        fixture.start(0x12);
        fixture.tick(648);

        assertFalse(fixture.dma.isTransferInProgress());
        assertEquals(0xa0, fixture.source.reads);
        assertEquals(0xa0, fixture.oam.writes);
        List<TraceEntry> entries = fixture.trace(512);
        assertEquals(482, entries.size());

        DmaTrace started = dma(entries.get(0));
        assertEquals(DmaTrace.Engine.OAM, started.engine());
        assertEquals(DmaTrace.Kind.STARTED, started.kind());
        assertEquals(0x1200, started.sourceAddress());
        assertEquals(0xfe00, started.destinationAddress());
        assertEquals(0xa0, started.length());
        assertEquals(0, started.bytesTransferred());

        for (int i = 0; i < 0xa0; i++) {
            TraceEntry readEntry = entries.get(1 + i * 3);
            TraceEntry writeEntry = entries.get(2 + i * 3);
            TraceEntry byteEntry = entries.get(3 + i * 3);
            MemoryAccessTrace read = memory(readEntry);
            MemoryAccessTrace write = memory(writeEntry);
            DmaTrace byteTransferred = dma(byteEntry);

            assertEquals(TraceSource.DMA, readEntry.source());
            assertEquals(DebugAddressSpace.ROM, read.addressSpace());
            assertEquals(DebugMemoryAccess.READ, read.access());
            assertEquals(0x1200 + i, read.address());
            assertEquals((0x40 + i) & 0xff, read.value());

            assertEquals(TraceSource.DMA, writeEntry.source());
            assertEquals(DebugAddressSpace.OAM, write.addressSpace());
            assertEquals(DebugMemoryAccess.WRITE, write.access());
            assertEquals(0xfe00 + i, write.address());
            assertEquals(read.value(), write.value());

            assertEquals(DmaTrace.Kind.BYTE_TRANSFERRED, byteTransferred.kind());
            assertEquals(i + 1, byteTransferred.bytesTransferred());
            assertEquals(readEntry.masterTick(), writeEntry.masterTick());
            assertEquals(readEntry.masterTick(), byteEntry.masterTick());
        }

        DmaTrace completed = dma(entries.get(entries.size() - 1));
        assertEquals(DmaTrace.Kind.COMPLETED, completed.kind());
        assertEquals(0xa0, completed.bytesTransferred());
        assertTrue(entries.get(entries.size() - 1).masterTick()
                > entries.get(entries.size() - 2).masterTick());
    }

    @Test
    public void restartReportsCancelledProgressAndRestoreOrAttachIsSilent() {
        Fixture fixture = new Fixture(EnumSet.of(TraceCategory.DMA), 16);
        fixture.start(0x12);
        fixture.tick(12);
        var activeState = fixture.dma.captureState();
        long beforeRestore = fixture.instrumentation.readTrace(
                TraceReadRequest.initial(16)).nextSequence();

        fixture.dma.restoreState(activeState);
        fixture.dma.setDebugHooks(null);
        fixture.dma.setDebugHooks(fixture.instrumentation);
        assertEquals(beforeRestore, fixture.instrumentation.readTrace(
                TraceReadRequest.initial(16)).nextSequence());

        fixture.start(0x13);
        List<TraceEntry> entries = fixture.trace(16);
        assertEquals(5, entries.size());
        assertEquals(List.of(
                        DmaTrace.Kind.STARTED,
                        DmaTrace.Kind.BYTE_TRANSFERRED,
                        DmaTrace.Kind.BYTE_TRANSFERRED,
                        DmaTrace.Kind.CANCELLED,
                        DmaTrace.Kind.STARTED),
                entries.stream().map(entry -> dma(entry).kind()).toList());
        DmaTrace cancelled = dma(entries.get(3));
        assertEquals(0x1200, cancelled.sourceAddress());
        assertEquals(2, cancelled.bytesTransferred());
        DmaTrace restarted = dma(entries.get(4));
        assertEquals(0x1300, restarted.sourceAddress());
        assertEquals(0, restarted.bytesTransferred());
    }

    private static MemoryAccessTrace memory(TraceEntry entry) {
        return (MemoryAccessTrace) entry.event();
    }

    private static DmaTrace dma(TraceEntry entry) {
        assertEquals(TraceSource.DMA, entry.source());
        return (DmaTrace) entry.event();
    }

    private static final class Fixture {

        private final CountingAddressSpace source = new CountingAddressSpace();

        private final CountingAddressSpace oam = new CountingAddressSpace();

        private final Dma dma = new Dma(source, oam, new SpeedMode(false));

        private final DebugInstrumentation instrumentation;

        private Fixture(EnumSet<TraceCategory> categories, int capacity) {
            for (int i = 0; i < 0xa0; i++) {
                source.values[0x1200 + i] = (0x40 + i) & 0xff;
                oam.values[0xfe00 + i] = 0xee;
            }
            instrumentation = new DebugInstrumentation(
                    1,
                    capacity,
                    Math.min(8, capacity),
                    EnumSet.of(DebugBreakpointKind.COUNTER),
                    EnumSet.of(TraceCategory.MEMORY, TraceCategory.DMA));
            instrumentation.configureTrace(new TraceConfiguration(capacity, categories));
            dma.setDebugHooks(instrumentation);
        }

        private void start(int sourcePage) {
            instrumentation.onMasterTickStarted();
            dma.setByte(0xff46, sourcePage);
        }

        private void tick(int count) {
            for (int i = 0; i < count; i++) {
                instrumentation.onMasterTickStarted();
                dma.tick();
            }
        }

        private List<TraceEntry> trace(int maxEntries) {
            return instrumentation.readTrace(
                    TraceReadRequest.initial(maxEntries)).entries();
        }
    }

    private static final class CountingAddressSpace implements AddressSpace {

        private final int[] values = new int[0x10000];

        private int reads;

        private int writes;

        @Override
        public boolean accepts(int address) {
            return address >= 0 && address < values.length;
        }

        @Override
        public void setByte(int address, int value) {
            writes++;
            values[address] = value & 0xff;
        }

        @Override
        public int getByte(int address) {
            reads++;
            return values[address];
        }
    }
}
