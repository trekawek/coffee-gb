package eu.rekawek.coffeegb.core.debug;

import java.util.Arrays;
import java.util.Objects;

/** Independently owned indexed bytes used by fixed debugger peripheral payloads. */
public final class DebugByteData {

    private final byte[] bytes;

    public DebugByteData(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public int length() {
        return bytes.length;
    }

    public byte byteAt(int index) {
        return bytes[index];
    }

    public int unsignedByteAt(int index) {
        return bytes[index] & 0xff;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof DebugByteData that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "DebugByteData[length=" + bytes.length + "]";
    }
}
