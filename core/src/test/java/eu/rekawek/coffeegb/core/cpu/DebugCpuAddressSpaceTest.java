package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DebugCpuAddressSpaceTest {

    @Test
    public void delegatesEachReadOnceAndReportsTheFinalValue() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        CapturingHooks hooks = new CapturingHooks();
        DebugCpuAddressSpace observed = new DebugCpuAddressSpace(delegate, hooks);

        assertEquals(0x5a, observed.getByte(0xc123));
        assertEquals(1, delegate.reads);
        assertEquals(DebugMemoryAccess.READ, hooks.access);
        assertEquals(0xc123, hooks.address);
        assertEquals(0x5a, hooks.value);

        assertEquals(0x5a, observed.getByte(0x100, DebugMemoryAccess.EXECUTE));
        assertEquals(2, delegate.reads);
        assertEquals(DebugMemoryAccess.EXECUTE, hooks.access);
    }

    @Test
    public void reportsOneAttemptedWriteWithoutReadingBack() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        CapturingHooks hooks = new CapturingHooks();
        DebugCpuAddressSpace observed = new DebugCpuAddressSpace(delegate, hooks);

        observed.setByte(0xff00, 0x33);

        assertEquals(1, delegate.writes);
        assertEquals(0, delegate.reads);
        assertEquals(DebugMemoryAccess.WRITE, hooks.access);
        assertEquals(0xff00, hooks.address);
        assertEquals(0x33, hooks.value);
    }

    @Test
    public void failedDelegationDoesNotInventAnObservation() {
        CountingAddressSpace delegate = new CountingAddressSpace();
        delegate.failure = new IllegalStateException("bus failure");
        CapturingHooks hooks = new CapturingHooks();
        DebugCpuAddressSpace observed = new DebugCpuAddressSpace(delegate, hooks);

        assertThrows(IllegalStateException.class, () -> observed.getByte(0));
        assertEquals(0, hooks.events);
    }

    private static final class CountingAddressSpace implements AddressSpace {

        int reads;
        int writes;
        RuntimeException failure;

        @Override
        public boolean accepts(int address) {
            return true;
        }

        @Override
        public void setByte(int address, int value) {
            writes++;
            if (failure != null) throw failure;
        }

        @Override
        public int getByte(int address) {
            reads++;
            if (failure != null) throw failure;
            return 0x5a;
        }
    }

    private static final class CapturingHooks implements DebugHooks {

        int events;
        DebugMemoryAccess access;
        int address;
        int value;

        @Override
        public void onInstructionFetch(int programCounter) {
        }

        @Override
        public void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
        }

        @Override
        public void onInstructionRetired(
                boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode) {
        }

        @Override
        public void onMemoryAccess(DebugMemoryAccess access, int address, int value) {
            events++;
            this.access = access;
            this.address = address;
            this.value = value;
        }

        @Override
        public void onInterruptRequested(DebugInterruptType interrupt) {
        }

        @Override
        public void onInterruptAccepted(DebugInterruptType interrupt) {
        }
    }
}
