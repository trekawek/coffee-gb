package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** One bounded debugger-owned byte mutation in a named 16-bit memory view. */
public record DebugMemoryWrite(DebugAddressSpace addressSpace, int address, int value) {

    public DebugMemoryWrite {
        Objects.requireNonNull(addressSpace, "addressSpace");
        DebugValueChecks.unsignedWord("address", address);
        DebugValueChecks.unsignedByte("value", value);
    }
}
