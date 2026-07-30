package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** A non-wrapping request in a named 16-bit memory view. */
public record DebugMemoryRequest(DebugAddressSpace addressSpace, int address, int length) {

    public static final int MAX_LENGTH = 0x10000;

    public DebugMemoryRequest {
        Objects.requireNonNull(addressSpace, "addressSpace");
        DebugValueChecks.unsignedWord("address", address);
        DebugValueChecks.range("length", length, 0, MAX_LENGTH);
        if ((long) address + length > MAX_LENGTH) {
            throw new IllegalArgumentException("Memory request wraps the 16-bit address space");
        }
    }

    public int endExclusive() {
        return address + length;
    }
}
