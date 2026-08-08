package eu.rekawek.coffeegb.harness;

/**
 * Process-local counter used by instrumented core methods.
 *
 * <p>The headless harness executes the emulator on one thread, so a plain long avoids
 * introducing atomic-operation noise while still producing an exact count.</p>
 */
public final class MethodCallCounter {

    private static long calls;

    private MethodCallCounter() {
    }

    public static void increment() {
        calls++;
    }

    public static void reset() {
        calls = 0;
    }

    public static long get() {
        return calls;
    }
}
