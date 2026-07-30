package eu.rekawek.coffeegb.core.debug.breakpoint;

final class DebugBreakpointChecks {

    private DebugBreakpointChecks() {
    }

    static void address(String name, int value) {
        range(name, value, 0, 0xffff);
    }

    static void unsignedByte(String name, int value) {
        range(name, value, 0, 0xff);
    }

    static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in [" + minimum + ", " + maximum + "]: " + value);
        }
    }

    static void nonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }

    static void addressRange(int startAddress, int endAddress) {
        address("startAddress", startAddress);
        address("endAddress", endAddress);
        if (startAddress > endAddress) {
            throw new IllegalArgumentException(
                    "startAddress must not exceed endAddress: "
                            + startAddress + " > " + endAddress);
        }
    }
}
