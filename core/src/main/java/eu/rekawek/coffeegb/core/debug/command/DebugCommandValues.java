package eu.rekawek.coffeegb.core.debug.command;

final class DebugCommandValues {

    private DebugCommandValues() {
    }

    static int parseUnsigned(String name, String value, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Argument " + name + " is required");
        }
        String digits = value;
        int radix = 10;
        if (value.startsWith("0x") || value.startsWith("0X")) {
            digits = value.substring(2);
            radix = 16;
        } else if (value.startsWith("$")) {
            digits = value.substring(1);
            radix = 16;
        }
        final long parsed;
        try {
            parsed = Long.parseLong(digits, radix);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Argument " + name + " is not a decimal or hexadecimal integer: " + value);
        }
        if (parsed < 0 || parsed > maximum) {
            throw new IllegalArgumentException(
                    "Argument " + name + " must be between 0 and " + maximum + ": " + value);
        }
        return (int) parsed;
    }
}
