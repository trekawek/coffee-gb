package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;

import java.util.Objects;

/** Active-only, delegate-once observer for physical PPU fetch buses. */
final class DebugPpuAddressSpace implements AddressSpace {

    private final AddressSpace delegate;

    private final DebugAddressSpace addressSpace;

    private final DebugHooks hooks;

    DebugPpuAddressSpace(
            AddressSpace delegate, DebugAddressSpace addressSpace, DebugHooks hooks) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.addressSpace = Objects.requireNonNull(addressSpace, "addressSpace");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    @Override
    public boolean accepts(int address) {
        return delegate.accepts(address);
    }

    @Override
    public int getByte(int address) {
        int value = delegate.getByte(address);
        hooks.onMemoryAccess(
                addressSpace,
                TraceSource.PPU,
                DebugMemoryAccess.READ,
                address,
                value);
        return value;
    }

    @Override
    public void setByte(int address, int value) {
        delegate.setByte(address, value);
        hooks.onMemoryAccess(
                addressSpace,
                TraceSource.PPU,
                DebugMemoryAccess.WRITE,
                address,
                value);
    }
}
