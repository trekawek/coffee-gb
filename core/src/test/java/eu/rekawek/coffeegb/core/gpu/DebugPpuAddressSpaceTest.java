package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.trace.MemoryAccessTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DebugPpuAddressSpaceTest {

    @Test
    public void observingPpuBusDelegatesOnceAndCarriesSpaceAndProducer() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        delegate.value = 0x5a;
        DebugInstrumentation instrumentation = new DebugInstrumentation(
                1,
                8,
                4,
                EnumSet.of(DebugBreakpointKind.COUNTER),
                EnumSet.of(TraceCategory.MEMORY));
        instrumentation.configureTrace(new TraceConfiguration(
                8, EnumSet.of(TraceCategory.MEMORY)));
        instrumentation.onMasterTickStarted();
        assertTrue(instrumentation.requiresPpuMemoryAccessHooks());
        AddressSpace observed = new DebugPpuAddressSpace(
                delegate, DebugAddressSpace.VIDEO_RAM, instrumentation);

        assertEquals(0x5a, observed.getByte(0x8123));
        observed.setByte(0x8456, 0xa5);

        assertEquals(1, delegate.reads);
        assertEquals(1, delegate.writes);
        assertEquals(0x8456, delegate.lastWriteAddress);
        assertEquals(0xa5, delegate.value);
        var entries = instrumentation.readTrace(TraceReadRequest.initial(8)).entries();
        assertEquals(2, entries.size());
        assertEquals(TraceSource.PPU, entries.get(0).source());
        assertEquals(TraceSource.PPU, entries.get(1).source());

        MemoryAccessTrace read = (MemoryAccessTrace) entries.get(0).event();
        assertEquals(DebugAddressSpace.VIDEO_RAM, read.addressSpace());
        assertEquals(DebugMemoryAccess.READ, read.access());
        assertEquals(0x8123, read.address());
        assertEquals(0x5a, read.value());

        MemoryAccessTrace write = (MemoryAccessTrace) entries.get(1).event();
        assertEquals(DebugAddressSpace.VIDEO_RAM, write.addressSpace());
        assertEquals(DebugMemoryAccess.WRITE, write.access());
        assertEquals(0x8456, write.address());
        assertEquals(0xa5, write.value());
    }

    private static final class CountingAddressSpace implements AddressSpace {

        private int reads;

        private int writes;

        private int lastWriteAddress;

        private int value;

        @Override
        public boolean accepts(int address) {
            return address >= 0x8000 && address < 0xa000;
        }

        @Override
        public void setByte(int address, int value) {
            writes++;
            lastWriteAddress = address;
            this.value = value;
        }

        @Override
        public int getByte(int address) {
            reads++;
            return value;
        }
    }
}
