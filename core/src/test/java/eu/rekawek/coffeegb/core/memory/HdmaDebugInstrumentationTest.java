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
import static org.junit.Assert.assertTrue;

public class HdmaDebugInstrumentationTest {

    @Test
    public void generalDmaTracesSourceSlotsThenAtomicDestinationCommitAndProgress() {
        CountingAddressSpace memory = new CountingAddressSpace();
        for (int i = 0; i < 0x10; i++) {
            memory.values[0x1200 + i] = 0xa0 + i;
        }
        Hdma hdma = configuredHdma(memory);
        DebugInstrumentation instrumentation = instrumentation(64);
        instrumentation.configureTrace(new TraceConfiguration(
                64, EnumSet.of(TraceCategory.MEMORY, TraceCategory.DMA)));
        hdma.setDebugHooks(instrumentation);
        instrumentation.onMasterTickStarted();

        hdma.setByte(0xff55, 0x00);
        for (int i = 0; i < 38; i++) {
            instrumentation.onMasterTickStarted();
            hdma.tick();
        }

        assertEquals(0x10, memory.reads);
        assertEquals(0x10, memory.writes);
        List<TraceEntry> entries = instrumentation.readTrace(
                TraceReadRequest.initial(64)).entries();
        assertEquals(50, entries.size());
        DmaTrace started = dma(entries.get(0));
        assertEquals(DmaTrace.Engine.VRAM_GENERAL, started.engine());
        assertEquals(DmaTrace.Kind.STARTED, started.kind());
        assertEquals(0x1200, started.sourceAddress());
        assertEquals(0x8000, started.destinationAddress());
        assertEquals(0x10, started.length());
        assertEquals(0, started.bytesTransferred());

        for (int i = 0; i < 0x10; i++) {
            TraceEntry entry = entries.get(1 + i);
            MemoryAccessTrace read = memory(entry);
            assertEquals(TraceSource.DMA, entry.source());
            assertEquals(DebugAddressSpace.ROM, read.addressSpace());
            assertEquals(DebugMemoryAccess.READ, read.access());
            assertEquals(0x1200 + i, read.address());
            assertEquals(0xa0 + i, read.value());
        }

        long commitTick = entries.get(17).masterTick();
        for (int i = 0; i < 0x10; i++) {
            TraceEntry writeEntry = entries.get(17 + i * 2);
            TraceEntry progressEntry = entries.get(18 + i * 2);
            MemoryAccessTrace write = memory(writeEntry);
            DmaTrace progress = dma(progressEntry);
            assertEquals(commitTick, writeEntry.masterTick());
            assertEquals(commitTick, progressEntry.masterTick());
            assertEquals(DebugAddressSpace.VIDEO_RAM, write.addressSpace());
            assertEquals(DebugMemoryAccess.WRITE, write.access());
            assertEquals(0x8000 + i, write.address());
            assertEquals(0xa0 + i, write.value());
            assertEquals(DmaTrace.Kind.BYTE_TRANSFERRED, progress.kind());
            assertEquals(i + 1, progress.bytesTransferred());
        }

        TraceEntry completedEntry = entries.get(49);
        DmaTrace completed = dma(completedEntry);
        assertEquals(commitTick, completedEntry.masterTick());
        assertEquals(DmaTrace.Kind.COMPLETED, completed.kind());
        assertEquals(0x10, completed.bytesTransferred());
        assertTrue(!hdma.isTransferInProgress());
    }

    @Test
    public void pendingHblankDmaCanCancelWithoutAFalseCompletion() {
        CountingAddressSpace memory = new CountingAddressSpace();
        Hdma hdma = configuredHdma(memory);
        hdma.onLcdSwitch(true);
        DebugInstrumentation instrumentation = instrumentation(8);
        instrumentation.configureTrace(new TraceConfiguration(
                8, EnumSet.of(TraceCategory.DMA)));
        hdma.setDebugHooks(instrumentation);

        hdma.setByte(0xff55, 0x81);
        hdma.setByte(0xff55, 0x00);

        List<TraceEntry> entries = instrumentation.readTrace(
                TraceReadRequest.initial(8)).entries();
        assertEquals(2, entries.size());
        assertEquals(DmaTrace.Engine.VRAM_HBLANK, dma(entries.get(0)).engine());
        assertEquals(DmaTrace.Kind.STARTED, dma(entries.get(0)).kind());
        assertEquals(0x20, dma(entries.get(0)).length());
        assertEquals(DmaTrace.Kind.CANCELLED, dma(entries.get(1)).kind());
        assertEquals(0, dma(entries.get(1)).bytesTransferred());
        assertTrue(!hdma.hasPendingHblankTransfer());
    }

    private static Hdma configuredHdma(AddressSpace memory) {
        Hdma hdma = new Hdma(memory, new SpeedMode(true));
        hdma.setByte(0xff51, 0x12);
        hdma.setByte(0xff52, 0x00);
        hdma.setByte(0xff53, 0x00);
        hdma.setByte(0xff54, 0x00);
        return hdma;
    }

    private static DebugInstrumentation instrumentation(int capacity) {
        return new DebugInstrumentation(
                1,
                capacity,
                Math.min(4, capacity),
                EnumSet.of(DebugBreakpointKind.COUNTER),
                EnumSet.of(TraceCategory.MEMORY, TraceCategory.DMA));
    }

    private static MemoryAccessTrace memory(TraceEntry entry) {
        return (MemoryAccessTrace) entry.event();
    }

    private static DmaTrace dma(TraceEntry entry) {
        assertEquals(TraceSource.DMA, entry.source());
        return (DmaTrace) entry.event();
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
