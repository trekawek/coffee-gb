package eu.rekawek.coffeegb.core.debug.breakpoint;

import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;

import java.util.Objects;

/**
 * Inclusive 16-bit memory range, optionally constrained by an observed byte value.
 *
 * <p>A value constraint matches when {@code (observedValue & valueMask) == (value &
 * valueMask)}. The address-only constructor has no value constraint. A zero mask is rejected
 * because it is indistinguishable from an address-only condition.
 */
public final class DebugMemoryCondition implements DebugBreakpointCondition {

    private final DebugMemoryAccess access;

    private final int startAddress;

    private final int endAddress;

    private final boolean valueConstrained;

    private final int value;

    private final int valueMask;

    public DebugMemoryCondition(
            DebugMemoryAccess access,
            int startAddress,
            int endAddress) {
        this(access, startAddress, endAddress, false, 0, 0);
    }

    /** Creates an exact-value condition with an implicit {@code 0xff} mask. */
    public DebugMemoryCondition(
            DebugMemoryAccess access,
            int startAddress,
            int endAddress,
            int value) {
        this(access, startAddress, endAddress, true, value, 0xff);
    }

    public DebugMemoryCondition(
            DebugMemoryAccess access,
            int startAddress,
            int endAddress,
            int value,
            int valueMask) {
        this(access, startAddress, endAddress, true, value, valueMask);
    }

    private DebugMemoryCondition(
            DebugMemoryAccess access,
            int startAddress,
            int endAddress,
            boolean valueConstrained,
            int value,
            int valueMask) {
        this.access = Objects.requireNonNull(access, "access");
        DebugBreakpointChecks.addressRange(startAddress, endAddress);
        if (valueConstrained) {
            DebugBreakpointChecks.unsignedByte("value", value);
            DebugBreakpointChecks.range("valueMask", valueMask, 1, 0xff);
        }
        this.startAddress = startAddress;
        this.endAddress = endAddress;
        this.valueConstrained = valueConstrained;
        this.value = value;
        this.valueMask = valueMask;
    }

    public DebugMemoryAccess access() {
        return access;
    }

    public int startAddress() {
        return startAddress;
    }

    public int endAddress() {
        return endAddress;
    }

    public boolean hasValueConstraint() {
        return valueConstrained;
    }

    /** Returns the configured byte, or zero when {@link #hasValueConstraint()} is false. */
    public int value() {
        return value;
    }

    /** Returns the configured mask, or zero when {@link #hasValueConstraint()} is false. */
    public int valueMask() {
        return valueMask;
    }

    @Override
    public DebugBreakpointKind kind() {
        return DebugBreakpointKind.MEMORY;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DebugMemoryCondition that)) return false;
        return startAddress == that.startAddress
                && endAddress == that.endAddress
                && valueConstrained == that.valueConstrained
                && value == that.value
                && valueMask == that.valueMask
                && access == that.access;
    }

    @Override
    public int hashCode() {
        int result = access.hashCode();
        result = 31 * result + startAddress;
        result = 31 * result + endAddress;
        result = 31 * result + Boolean.hashCode(valueConstrained);
        result = 31 * result + value;
        result = 31 * result + valueMask;
        return result;
    }

    @Override
    public String toString() {
        return "DebugMemoryCondition[access=" + access
                + ", startAddress=" + startAddress
                + ", endAddress=" + endAddress
                + ", valueConstrained=" + valueConstrained
                + ", value=" + value
                + ", valueMask=" + valueMask + ']';
    }
}
