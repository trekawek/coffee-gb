package eu.rekawek.coffeegb.core.debug;

import java.io.PrintStream;

public final class ConsoleUtil {

    private ConsoleUtil() {
    }

    public static void printSeparator(int width) {
        printSeparator(System.out, width);
    }

    public static void printSeparator(PrintStream output, int width) {
        output.println(String.format("%" + width + "s", "").replace(' ', '-'));
    }
}
