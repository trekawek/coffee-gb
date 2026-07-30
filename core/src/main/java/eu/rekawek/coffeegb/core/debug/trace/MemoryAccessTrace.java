package eu.rekawek.coffeegb.core.debug.trace;

import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;

import java.util.Objects;

/** One byte read, written, or executed on the emulated address bus. */
public record MemoryAccessTrace(DebugMemoryAccess access, int address, int value)
        implements TraceEvent {

    public MemoryAccessTrace {
        Objects.requireNonNull(access, "access");
        TraceChecks.range("address", address, 0, 0xffff);
        TraceChecks.range("value", value, 0, 0xff);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.MEMORY;
    }
}
