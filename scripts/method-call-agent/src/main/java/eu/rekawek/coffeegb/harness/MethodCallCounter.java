package eu.rekawek.coffeegb.harness;

import java.util.Arrays;
import java.util.Formatter;
import java.util.Locale;

/**
 * Process-local counters used by instrumented core methods.
 *
 * <p>The headless harness executes the emulator on one thread, so a plain long avoids
 * introducing atomic-operation noise while still producing an exact count.</p>
 */
public final class MethodCallCounter {

    private static String[] names = new String[256];
    private static long[] calls = new long[256];
    private static int size;

    private MethodCallCounter() {
    }

    public static synchronized int register(String name) {
        if (size == names.length) {
            names = Arrays.copyOf(names, size * 2);
            calls = Arrays.copyOf(calls, size * 2);
        }
        names[size] = name;
        return size++;
    }

    public static void increment(int methodId) {
        calls[methodId]++;
    }

    public static void reset() {
        Arrays.fill(calls, 0, size, 0L);
    }

    public static long get() {
        long total = 0;
        for (int i = 0; i < size; i++) {
            total += calls[i];
        }
        return total;
    }

    public static String report(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }

        Integer[] methodIds = new Integer[size];
        for (int i = 0; i < size; i++) {
            methodIds[i] = i;
        }
        Arrays.sort(methodIds, (left, right) -> {
            int byCalls = Long.compare(calls[right], calls[left]);
            return byCalls != 0 ? byCalls : names[left].compareTo(names[right]);
        });

        long total = get();
        int resultSize = Math.min(limit, size);
        StringBuilder result = new StringBuilder(resultSize * 128);
        Formatter formatter = new Formatter(result, Locale.ROOT);
        for (int rank = 0; rank < resultSize; rank++) {
            int methodId = methodIds[rank];
            long methodCalls = calls[methodId];
            if (methodCalls == 0) {
                break;
            }
            double share = total == 0 ? 0 : methodCalls * 100.0 / total;
            formatter.format("HOT_METHOD rank=%d calls=%d share=%.6f%% method=%s%n",
                    rank + 1, methodCalls, share, names[methodId]);
        }
        formatter.close();
        return result.toString();
    }
}
