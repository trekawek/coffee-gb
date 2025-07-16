package eu.rekawek.coffeegb.debug;

public final class ConsoleUtil {

    private ConsoleUtil() {
    }

    public static void printSeparator(int width) {
        System.out.println(String.format("%" + width + "s", "").replace(' ', '-'));
    }
}
