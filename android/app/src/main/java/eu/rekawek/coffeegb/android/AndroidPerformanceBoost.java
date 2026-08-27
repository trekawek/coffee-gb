package eu.rekawek.coffeegb.android;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.SystemClock;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Small, deliberately scoped Android scheduler hints for the emulation/controller thread.
 * Accuracy sessions never open an adaptive performance session or call into
 * {@link Process#setThreadPriority(int)}.
 *
 * <p>The adaptive session is created, reset, and sampled only from synchronous controller event
 * callbacks. One reported work cycle is one near-60-Hz controller PERFORMANCE batch; presentation
 * callbacks are deliberately not its clock because catch-up may suppress them. Terminal runtime
 * teardown calls {@link #onSessionStopped()} only after the controller thread has joined. The
 * methods are synchronized as a final guard around the platform session, whose mutable API must
 * never be raced by teardown.
 */
final class AndroidPerformanceBoost implements AutoCloseable {

    /** Urgent-display is high enough for a 60-Hz producer without using the audio RT class. */
    static final int PERFORMANCE_THREAD_PRIORITY = Process.THREAD_PRIORITY_URGENT_DISPLAY;
    static final int DEFAULT_THREAD_PRIORITY = Process.THREAD_PRIORITY_DEFAULT;
    /**
     * The controller owns only the emulation portion of a host frame. Reserve the remaining third
     * for audio and presentation instead of inviting ADPF to stretch controller work across the
     * complete frame deadline.
     */
    static final int WORK_BUDGET_NUMERATOR = 2;
    static final int WORK_BUDGET_DENOMINATOR = 3;
    static final long TARGET_WORK_DURATION_NANOS = 11_111_111L;

    /** The controller thread survives a ROM replacement; only undo a boost installed by us. */
    private static final ThreadLocal<Integer> ORIGINAL_PRIORITY = new ThreadLocal<>();

    private final HintPlatform hintPlatform;
    private long targetWorkDurationNanos = TARGET_WORK_DURATION_NANOS;
    private HintSession hintSession;
    private boolean running;
    private boolean workCycleActive;
    private long activeWorkStartedNanos;

    AndroidPerformanceBoost(Context context) {
        this(new AndroidHintPlatform(Objects.requireNonNull(context, "context")));
    }

    AndroidPerformanceBoost(HintPlatform hintPlatform) {
        this.hintPlatform = Objects.requireNonNull(hintPlatform, "hintPlatform");
    }

    /** Latches the controller cadence synchronously before the matching session starts. */
    synchronized void onHardwareProfile(ClockSpec clockSpec) {
        Objects.requireNonNull(clockSpec, "clockSpec");
        BigInteger numerator = BigInteger.valueOf(1_000_000_000L)
                .multiply(BigInteger.valueOf(clockSpec.controllerFramesPerSecondDenominator()))
                .multiply(BigInteger.valueOf(WORK_BUDGET_NUMERATOR));
        BigInteger denominator = BigInteger.valueOf(clockSpec.controllerFramesPerSecondNumerator())
                .multiply(BigInteger.valueOf(WORK_BUDGET_DENOMINATOR));
        BigInteger rounded = numerator.add(denominator.shiftRight(1)).divide(denominator);
        targetWorkDurationNanos = rounded.max(BigInteger.ONE)
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    /** Replaces any previous adaptive session at the controller's ownership-commit boundary. */
    synchronized void onSessionStarted(ExecutionMode mode) {
        closeHintSession();
        if (mode != ExecutionMode.PERFORMANCE
                || hintPlatform.sdkInt() < Build.VERSION_CODES.S) {
            return;
        }
        try {
            int tid = hintPlatform.currentThreadId();
            HintSession created = hintPlatform.createHintSession(
                    new int[]{tid}, targetWorkDurationNanos);
            if (created != null) {
                hintSession = created;
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Some API-31+ vendor builds expose the service but reject or fail session creation.
            // PERFORMANCE remains fully functional without the optional adaptive hint.
            closeHintSession();
        }
    }

    /** Resets the work interval across every authoritative pause/resume transition. */
    synchronized void onPlaybackStateChanged(boolean paused) {
        if (hintSession == null) {
            return;
        }
        if (paused) {
            running = false;
            resetWorkAccounting();
            return;
        }
        if (running) {
            return;
        }
        resetWorkAccounting();
        running = true;
    }

    /** Begins one controller-owned PERFORMANCE span after lifecycle work and before guest work. */
    synchronized void onWorkStarted() {
        if (hintSession == null || !running || workCycleActive) {
            return;
        }
        try {
            activeWorkStartedNanos = hintPlatform.uptimeNanos();
            workCycleActive = true;
        } catch (RuntimeException | LinkageError unavailable) {
            disableHintSession();
        }
    }

    /** Ends and reports one controller work cycle immediately before pacing. */
    synchronized void onWorkCompleted() {
        if (hintSession == null || !running || !workCycleActive) {
            return;
        }
        long actualDurationNanos;
        try {
            actualDurationNanos = hintPlatform.uptimeNanos() - activeWorkStartedNanos;
            workCycleActive = false;
            activeWorkStartedNanos = 0L;
        } catch (RuntimeException | LinkageError unavailable) {
            disableHintSession();
            return;
        }
        if (actualDurationNanos <= 0L) {
            return;
        }
        try {
            hintSession.reportActualWorkDuration(actualDurationNanos);
        } catch (RuntimeException | LinkageError unavailable) {
            // Do not repeatedly enter a broken vendor session on the frame hot path. A later ROM
            // ownership commit may create a fresh one if the service recovers.
            disableHintSession();
        }
    }

    /** Drops a controller batch which stopped before consuming its complete tick budget. */
    synchronized void onWorkAborted() {
        resetWorkAccounting();
    }

    /** Ends the active adaptive session at replacement, stop, or terminal close. */
    synchronized void onSessionStopped() {
        closeHintSession();
    }

    @Override
    public void close() {
        onSessionStopped();
    }

    private void disableHintSession() {
        closeHintSession();
    }

    private void closeHintSession() {
        HintSession closing = hintSession;
        hintSession = null;
        running = false;
        resetWorkAccounting();
        if (closing == null) {
            return;
        }
        try {
            closing.close();
        } catch (RuntimeException | LinkageError unavailable) {
            // Closing is terminal from our perspective even if a vendor service has disappeared.
        }
    }

    private void resetWorkAccounting() {
        workCycleActive = false;
        activeWorkStartedNanos = 0L;
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

    /** Test seam which keeps JVM tests independent of Android framework method stubs. */
    interface HintPlatform {
        int sdkInt();

        int currentThreadId();

        long uptimeNanos();

        HintSession createHintSession(int[] threadIds, long targetWorkDurationNanos);
    }

    interface HintSession {
        void reportActualWorkDuration(long actualDurationNanos);

        void close();
    }

    private static final class AndroidHintPlatform implements HintPlatform {
        private final Context context;

        private AndroidHintPlatform(Context context) {
            this.context = context;
        }

        @Override
        public int sdkInt() {
            return Build.VERSION.SDK_INT;
        }

        @Override
        public int currentThreadId() {
            return Process.myTid();
        }

        @Override
        public long uptimeNanos() {
            if (Build.VERSION.SDK_INT >= 35) {
                return Api35.uptimeNanos();
            }
            // uptimeNanos() itself was added after PerformanceHintManager. Android's monotonic
            // System.nanoTime() uses the same uptime (no deep sleep) basis without quantizing
            // API 31-34 workload samples to whole milliseconds.
            return System.nanoTime();
        }

        @Override
        public HintSession createHintSession(int[] threadIds, long targetWorkDurationNanos) {
            // Kept behind the SDK check in onSessionStarted so pre-31 devices are exact no-ops.
            return Api31.createHintSession(context, threadIds, targetWorkDurationNanos);
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        private static HintSession createHintSession(
                Context context, int[] threadIds, long targetWorkDurationNanos) {
            PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
            if (manager == null) {
                return null;
            }
            PerformanceHintManager.Session session =
                    manager.createHintSession(threadIds, targetWorkDurationNanos);
            if (session == null) {
                return null;
            }
            return new HintSession() {
                @Override
                public void reportActualWorkDuration(long actualDurationNanos) {
                    session.reportActualWorkDuration(actualDurationNanos);
                }

                @Override
                public void close() {
                    session.close();
                }
            };
        }
    }

    @TargetApi(35)
    private static final class Api35 {
        private static long uptimeNanos() {
            return SystemClock.uptimeNanos();
        }
    }
}
