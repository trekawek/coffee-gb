package eu.rekawek.coffeegb.core.debug;

final class DebugValueChecks {

    private DebugValueChecks() {
    }

    static void unsignedByte(String name, int value) {
        range(name, value, 0, 0xff);
    }

    static void unsignedWord(String name, int value) {
        range(name, value, 0, 0xffff);
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
}
