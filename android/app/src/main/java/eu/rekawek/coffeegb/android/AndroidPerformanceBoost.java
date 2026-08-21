package eu.rekawek.coffeegb.android;

import android.os.Process;
import eu.rekawek.coffeegb.core.ExecutionMode;

/**
 * Small, deliberately scoped Android scheduler hint for the emulation/controller thread.
 * Accuracy sessions never call into {@link Process#setThreadPriority(int)}.
 */
final class AndroidPerformanceBoost {

    /** Urgent-display is high enough for a 60-Hz producer without using the audio RT class. */
    static final int PERFORMANCE_THREAD_PRIORITY = Process.THREAD_PRIORITY_URGENT_DISPLAY;
    static final int DEFAULT_THREAD_PRIORITY = Process.THREAD_PRIORITY_DEFAULT;

    /** The controller thread survives a ROM replacement; only undo a boost installed by us. */
    private static final ThreadLocal<Integer> ORIGINAL_PRIORITY = new ThreadLocal<>();

    private AndroidPerformanceBoost() {
    }

    static boolean apply(ExecutionMode mode) {
        return apply(mode, () -> Process.getThreadPriority(Process.myTid()),
                Process::setThreadPriority);
    }

    static boolean apply(ExecutionMode mode, ThreadPriorityGetter getter,
            ThreadPrioritySetter setter) {
        if (getter == null || setter == null) {
            return false;
        }
        try {
            if (mode == ExecutionMode.PERFORMANCE) {
                Integer originalPriority = ORIGINAL_PRIORITY.get();
                if (originalPriority == null) {
                    originalPriority = getter.get();
                }
                setter.set(PERFORMANCE_THREAD_PRIORITY);
                if (ORIGINAL_PRIORITY.get() == null) {
                    ORIGINAL_PRIORITY.set(originalPriority);
                }
                return true;
            }
            Integer originalPriority = ORIGINAL_PRIORITY.get();
            if (originalPriority != null) {
                setter.set(originalPriority);
                ORIGINAL_PRIORITY.remove();
                return true;
            }
            return false;
        } catch (RuntimeException unavailable) {
            // A restricted vendor process may reject the hint. The emulator remains functional
            // and diagnostics will expose the observed priority for the benchmark report.
            return false;
        }
    }

    @FunctionalInterface
    interface ThreadPrioritySetter {
        void set(int priority);
    }

    @FunctionalInterface
    interface ThreadPriorityGetter {
        int get();
    }
}
