package eu.rekawek.coffeegb.core.genie;

import com.sun.management.ThreadMXBean;
import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import org.junit.Assume;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GenieTest {

    private static volatile int observedAddress;

    @Test
    public void emptyPatchTableDelegatesExactlyOnce() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        delegate.setByte(0xc123, 0x42);
        Genie genie = new Genie(delegate, false);

        assertEquals(0x42, genie.getByte(0xc123));
        assertEquals(1, delegate.reads);
    }

    @Test
    public void unmatchedAddressFallsBackToDelegateWithPatchesPresent() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        delegate.setByte(0xc124, 0x43);
        Genie genie = new Genie(delegate, false);
        CountingPatch patch = new CountingPatch(0xc123, true, 0x22);

        try (EventBusImpl eventBus = new EventBusImpl(null, null, false)) {
            genie.init(eventBus);
            eventBus.post(new AddPatches(List.of(patch)));

            assertEquals(0x43, genie.getByte(0xc124));
            assertEquals(0, patch.acceptCalls);
            assertEquals(1, delegate.reads);
        }
    }

    @Test
    public void lookupPreservesFirstAcceptedPatchAndIgnoresOtherAddresses() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        delegate.setByte(0xc123, 0x42);
        Genie genie = new Genie(delegate, false);
        CountingPatch rejected = new CountingPatch(0xc123, false, 0x11);
        CountingPatch accepted = new CountingPatch(0xc123, true, 0x22);
        CountingPatch unrelated = new CountingPatch(0xc124, true, 0x33);

        try (EventBusImpl eventBus = new EventBusImpl(null, null, false)) {
            genie.init(eventBus);
            eventBus.post(new AddPatches(List.of(rejected, accepted, unrelated)));

            assertEquals(0x22, genie.getByte(0xc123));
            assertEquals(1, rejected.acceptCalls);
            assertEquals(1, accepted.acceptCalls);
            assertEquals(0, unrelated.acceptCalls);
            assertEquals(1, delegate.reads);
        }
    }

    @Test
    public void conditionalGameGenieStillPerformsItsOwnComparisonRead() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        delegate.setByte(0xc123, 0x42);
        Genie genie = new Genie(delegate, false);

        try (EventBusImpl eventBus = new EventBusImpl(null, null, false)) {
            genie.init(eventBus);
            eventBus.post(new AddPatches(List.of(
                    new GameGenieCheat(0x99, 0xc123, 0x42))));

            assertEquals(0x99, genie.getByte(0xc123));
            assertEquals(2, delegate.reads);
        }
    }

    @Test
    public void emptyPatchHotPathAllocatesNothing() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean allocationBean = (ThreadMXBean) platformBean;
        Assume.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }

        CountingAddressSpace delegate = new CountingAddressSpace();
        Genie genie = new Genie(delegate, false);
        try (EventBusImpl eventBus = new EventBusImpl(null, null, false)) {
            // Keep Genie reachable through its ordinary mutation seam so the JIT cannot treat the
            // private patch map as a closed, permanently empty object during this measurement.
            genie.init(eventBus);
            readMany(genie, 100_000);

            long threadId = Thread.currentThread().getId();
            allocationBean.getThreadAllocatedBytes(threadId);
            long minimumAllocated = Long.MAX_VALUE;
            for (int sample = 0; sample < 5; sample++) {
                long before = allocationBean.getThreadAllocatedBytes(threadId);
                readMany(genie, 100_000);
                long after = allocationBean.getThreadAllocatedBytes(threadId);
                minimumAllocated = Math.min(minimumAllocated, after - before);
            }

            assertEquals("empty Genie lookup allocated", 0L, minimumAllocated);
            assertEquals(600_000, delegate.reads);
            assertEquals(0xc000 + ((100_000 - 1) & 0x1fff), observedAddress);
        }
    }

    private static int readMany(Genie genie, int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            result ^= genie.getByte(0xc000 + (i & 0x1fff));
        }
        return result;
    }

    private static final class CountingAddressSpace implements AddressSpace {
        private final byte[] data = new byte[0x10000];
        /** Volatile so every measured delegate read remains an observable synchronization action. */
        private volatile int reads;

        @Override
        public boolean accepts(int address) {
            return address >= 0 && address < data.length;
        }

        @Override
        public void setByte(int address, int value) {
            data[address] = (byte) value;
        }

        @Override
        public int getByte(int address) {
            reads++;
            observedAddress = address;
            return data[address] & 0xff;
        }
    }

    private static final class CountingPatch implements CheatPatch {
        private final int address;
        private final boolean accepted;
        private final int value;
        private int acceptCalls;

        private CountingPatch(int address, boolean accepted, int value) {
            this.address = address;
            this.accepted = accepted;
            this.value = value;
        }

        @Override
        public int getAddress() {
            return address;
        }

        @Override
        public boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc) {
            acceptCalls++;
            return accepted;
        }

        @Override
        public int getValue() {
            return value;
        }
    }
}
