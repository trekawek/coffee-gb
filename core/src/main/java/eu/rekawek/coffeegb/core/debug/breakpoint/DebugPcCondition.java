package eu.rekawek.coffeegb.core.debug.breakpoint;

/** Inclusive 16-bit program-counter range; an equal pair represents exact-PC matching. */
public record DebugPcCondition(int startAddress, int endAddress)
        implements DebugBreakpointCondition {

    public DebugPcCondition {
        DebugBreakpointChecks.addressRange(startAddress, endAddress);
    }

    public static DebugPcCondition at(int address) {
        return new DebugPcCondition(address, address);
    }

    public static DebugPcCondition range(int startAddress, int endAddress) {
        return new DebugPcCondition(startAddress, endAddress);
    }

    public boolean isExact() {
        return startAddress == endAddress;
    }

    @Override
    public DebugBreakpointKind kind() {
        return DebugBreakpointKind.PROGRAM_COUNTER;
    }
}
