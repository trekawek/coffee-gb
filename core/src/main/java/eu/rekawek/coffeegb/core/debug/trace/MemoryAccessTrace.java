package eu.rekawek.coffeegb.core.debug.trace;

import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;

import java.util.Objects;

/**
 * One byte read, written, or executed in a stable address-space view.
 * The enclosing {@link TraceEntry#source()} identifies the producer without duplicating it here.
 */
public record MemoryAccessTrace(
        DebugAddressSpace addressSpace,
        DebugMemoryAccess access,
        int address,
        int value)
        implements TraceEvent {

    /** Compatibility constructor for a CPU-facing system-bus observation. */
    public MemoryAccessTrace(DebugMemoryAccess access, int address, int value) {
        this(DebugAddressSpace.SYSTEM_BUS, access, address, value);
    }

    public MemoryAccessTrace {
        Objects.requireNonNull(addressSpace, "addressSpace");
        Objects.requireNonNull(access, "access");
        TraceChecks.range("address", address, 0, 0xffff);
        TraceChecks.range("value", value, 0, 0xff);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.MEMORY;
    }
}
