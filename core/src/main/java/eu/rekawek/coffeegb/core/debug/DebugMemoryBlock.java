package eu.rekawek.coffeegb.core.debug;

import java.util.Arrays;
import java.util.Objects;

/** Independently owned bytes read from one named memory view at one safe point. */
public final class DebugMemoryBlock {

    private final DebugAddressSpace addressSpace;

    private final int startAddress;

    private final byte[] bytes;

    public DebugMemoryBlock(DebugAddressSpace addressSpace, int startAddress, byte[] bytes) {
        this.addressSpace = Objects.requireNonNull(addressSpace, "addressSpace");
        Objects.requireNonNull(bytes, "bytes");
        new DebugMemoryRequest(addressSpace, startAddress, bytes.length);
        this.startAddress = startAddress;
        this.bytes = bytes.clone();
    }

    public DebugAddressSpace addressSpace() {
        return addressSpace;
    }

    public int startAddress() {
        return startAddress;
    }

    public int length() {
        return bytes.length;
    }

    public int endExclusive() {
        return startAddress + bytes.length;
    }

    public byte byteAt(int index) {
        return bytes[index];
    }

    public int unsignedByteAt(int index) {
        return bytes[index] & 0xff;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DebugMemoryBlock that)) return false;
        return startAddress == that.startAddress
                && addressSpace == that.addressSpace
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(addressSpace, startAddress);
        return 31 * result + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "DebugMemoryBlock[addressSpace=" + addressSpace
                + ", startAddress=" + startAddress + ", length=" + bytes.length + "]";
    }
}
