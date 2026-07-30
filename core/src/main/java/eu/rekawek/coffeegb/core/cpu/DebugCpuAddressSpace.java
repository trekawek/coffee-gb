package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;

import java.util.Objects;

/** Active-only observer around the CPU-facing DMA-aware address space. */
final class DebugCpuAddressSpace implements AddressSpace {

    private final AddressSpace delegate;

    private final DebugHooks hooks;

    DebugCpuAddressSpace(AddressSpace delegate, DebugHooks hooks) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    @Override
    public boolean accepts(int address) {
        return delegate.accepts(address);
    }

    @Override
    public int getByte(int address) {
        return getByte(address, DebugMemoryAccess.READ);
    }

    int getByte(int address, DebugMemoryAccess access) {
        int value = delegate.getByte(address);
        hooks.onMemoryAccess(access, address, value);
        return value;
    }

    @Override
    public void setByte(int address, int value) {
        delegate.setByte(address, value);
        hooks.onMemoryAccess(DebugMemoryAccess.WRITE, address, value);
    }
}
