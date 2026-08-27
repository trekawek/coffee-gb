package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidPerformanceBoostTest {

    @After
    public void restoreThreadPriorityState() {
        AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY, ignored -> { });
    }

    @Test
    public void accuracyDoesNotTouchThreadPriority() {
        // A previous test/session may have used this JVM thread; clear only a boost installed by
        // this helper before asserting the fresh Accuracy behavior.
        AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY, ignored -> { });
        AtomicInteger calls = new AtomicInteger();
        assertFalse(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY,
                priority -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    @Test
    public void performanceRequestsUrgentDisplayPriority() {
        AtomicInteger selected = new AtomicInteger(Integer.MIN_VALUE);
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE,
                () -> AndroidPerformanceBoost.DEFAULT_THREAD_PRIORITY, selected::set));
        assertEquals(AndroidPerformanceBoost.PERFORMANCE_THREAD_PRIORITY, selected.get());
    }

    @Test
    public void accuracyRestoresActualPrecedingPriority() {
        AtomicInteger selected = new AtomicInteger(Integer.MIN_VALUE);
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE, () -> 7,
                selected::set));
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY, () -> 7,
                selected::set));
        assertEquals(7, selected.get());
    }

    @Test
    public void rejectedSchedulerHintIsNonFatal() {
        assertFalse(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE, () -> 7,
                priority -> { throw new SecurityException("denied"); }));
    }

    @Test
    public void rejectedRestoreCanBeRetriedWithoutLosingOriginalPriority() {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.PERFORMANCE, () -> 11,
                priority -> { }));
        assertFalse(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY, () -> 11,
                priority -> {
                    calls.incrementAndGet();
                    throw new SecurityException("denied");
                }));
        assertTrue(AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY, () -> 11,
                calls::set));
        assertEquals(11, calls.get());
    }

    @Test
    public void accuracyAndPreApi31HintSessionsAreExactNoOps() {
        FakeHintPlatform accuracyPlatform = new FakeHintPlatform(36, 41);
        AndroidPerformanceBoost accuracy = new AndroidPerformanceBoost(accuracyPlatform);
        accuracy.onSessionStarted(ExecutionMode.ACCURACY);
        accuracy.onPlaybackStateChanged(false);
        accuracy.onWorkStarted();
        accuracy.onWorkAborted();
        accuracy.onWorkCompleted();
        accuracy.onSessionStopped();
        assertEquals(0, accuracyPlatform.threadIdCalls);
        assertEquals(0, accuracyPlatform.clockCalls);
        assertEquals(0, accuracyPlatform.createCalls);

        FakeHintPlatform legacyPlatform = new FakeHintPlatform(30, 42);
        AndroidPerformanceBoost legacy = new AndroidPerformanceBoost(legacyPlatform);
        legacy.onSessionStarted(ExecutionMode.PERFORMANCE);
        legacy.onPlaybackStateChanged(false);
        legacy.onWorkStarted();
        legacy.onWorkAborted();
        legacy.onWorkCompleted();
        legacy.close();
        assertEquals(0, legacyPlatform.threadIdCalls);
        assertEquals(0, legacyPlatform.clockCalls);
        assertEquals(0, legacyPlatform.createCalls);
    }

    @Test
    public void performanceCreatesSessionForControllerTidAndReportsControllerCycles() {
        FakeHintPlatform platform = new FakeHintPlatform(31, 73);
        AndroidPerformanceBoost boost = new AndroidPerformanceBoost(platform);

        boost.onSessionStarted(ExecutionMode.PERFORMANCE);
        assertEquals(1, platform.createCalls);
        assertEquals(1, platform.threadIdCalls);
        assertArrayEquals(new int[]{73}, platform.lastThreadIds);
        assertEquals(16_666_667L, AndroidPerformanceBoost.TARGET_WORK_DURATION_NANOS);
        assertEquals(AndroidPerformanceBoost.TARGET_WORK_DURATION_NANOS,
                platform.lastTargetWorkDurationNanos);

        platform.nowNanos = 10_000L;
        // A newly committed session waits for the controller's authoritative playback event.
        boost.onWorkStarted();
        boost.onWorkCompleted();
        assertTrue(platform.sessions.get(0).durations.isEmpty());

        boost.onPlaybackStateChanged(false);
        boost.onWorkStarted();
        platform.nowNanos = 14_250L;
        boost.onWorkCompleted();
        // The pacing gap between controller batches is excluded.
        platform.nowNanos = 100_000L;
        boost.onWorkStarted();
        platform.nowNanos = 104_000L;
        boost.onWorkCompleted();

        assertEquals(List.of(4_250L, 4_000L), platform.sessions.get(0).durations);
    }

    @Test
    public void hardwareProfileSelectsTheMatchingControllerCadenceTarget() {
        assertTargetDuration(ClockSpec.LEGACY, 16_666_667L);
        assertTargetDuration(ClockSpec.SGB, 16_348_444L);
        assertTargetDuration(ClockSpec.SGB2, 16_742_706L);
    }

    @Test
    public void idleAndResumeDiscardThePausedInterval() {
        FakeHintPlatform platform = new FakeHintPlatform(31, 91);
        AndroidPerformanceBoost boost = new AndroidPerformanceBoost(platform);
        boost.onSessionStarted(ExecutionMode.PERFORMANCE);

        platform.nowNanos = 1_000L;
        boost.onPlaybackStateChanged(false);
        boost.onWorkStarted();
        platform.nowNanos = 2_000L;
        boost.onWorkCompleted();
        boost.onPlaybackStateChanged(true);
        platform.nowNanos = 1_000_000L;
        boost.onWorkStarted();
        boost.onWorkCompleted();
        boost.onPlaybackStateChanged(false);
        boost.onWorkStarted();
        platform.nowNanos = 1_000_900L;
        boost.onWorkCompleted();

        assertEquals(List.of(1_000L, 900L), platform.sessions.get(0).durations);
    }

    @Test
    public void pauseDuringActiveCycleDropsItBeforeOrdinaryWorkResumes() {
        FakeHintPlatform platform = new FakeHintPlatform(31, 96);
        AndroidPerformanceBoost boost = new AndroidPerformanceBoost(platform);
        boost.onSessionStarted(ExecutionMode.PERFORMANCE);
        boost.onPlaybackStateChanged(false);

        platform.nowNanos = 100L;
        boost.onWorkStarted();
        platform.nowNanos = 150L;
        boost.onPlaybackStateChanged(true);
        boost.onWorkCompleted();
        platform.nowNanos = 1_000L;
        boost.onPlaybackStateChanged(false);
        boost.onWorkStarted();
        platform.nowNanos = 1_030L;
        boost.onWorkCompleted();

        assertEquals(List.of(30L), platform.sessions.get(0).durations);
    }

    @Test
    public void abortedCycleCannotLeakIntoTheNextCompletedCycle() {
        FakeHintPlatform platform = new FakeHintPlatform(31, 97);
        AndroidPerformanceBoost boost = new AndroidPerformanceBoost(platform);
        boost.onSessionStarted(ExecutionMode.PERFORMANCE);
        boost.onPlaybackStateChanged(false);

        platform.nowNanos = 100L;
        boost.onWorkStarted();
        platform.nowNanos = 150L;
        boost.onWorkAborted();
        platform.nowNanos = 1_000L;
        boost.onWorkStarted();
        platform.nowNanos = 1_030L;
        boost.onWorkCompleted();

        assertEquals(List.of(30L), platform.sessions.get(0).durations);
    }

    @Test
    public void replacementAndDowngradeCannotLeakSamplesAcrossSessions() {
        FakeHintPlatform platform = new FakeHintPlatform(31, 101);
        AndroidPerformanceBoost boost = new AndroidPerformanceBoost(platform);
        boost.onSessionStarted(ExecutionMode.PERFORMANCE);
        FakeHintSession first = platform.sessions.get(0);
        platform.nowNanos = 100L;
        boost.onPlaybackStateChanged(false);
        boost.onWorkStarted();
        platform.nowNanos = 150L;
        boost.onWorkCompleted();
        platform.nowNanos = 200L;
        boost.onWorkStarted();
        platform.nowNanos = 225L;

        boost.onSessionStarted(ExecutionMode.PERFORMANCE);
        FakeHintSession second = platform.sessions.get(1);
        assertEquals(1, first.closeCalls);
        boost.onWorkCompleted();
        assertTrue(second.durations.isEmpty());
        platform.nowNanos = 1_000L;
        boost.onPlaybackStateChanged(false);
        boost.onWorkStarted();
        platform.nowNanos = 1_070L;
        boost.onWorkCompleted();

        boost.onSessionStarted(ExecutionMode.ACCURACY);
        boost.onWorkStarted();
        boost.onWorkCompleted();
        boost.close();
        assertEquals(List.of(50L), first.durations);
        assertEquals(List.of(70L), second.durations);
        assertEquals(1, second.closeCalls);
        assertEquals(2, platform.createCalls);
    }

    @Test
    public void vendorCreationAndReportingFailuresAreNonFatalAndDisarmTheSession() {
        FakeHintPlatform unavailable = new FakeHintPlatform(31, 111);
        unavailable.createFailure = new UnsupportedOperationException("unsupported");
        AndroidPerformanceBoost failedCreate = new AndroidPerformanceBoost(unavailable);
        failedCreate.onSessionStarted(ExecutionMode.PERFORMANCE);
        failedCreate.onPlaybackStateChanged(false);
        failedCreate.onWorkStarted();
        failedCreate.onWorkCompleted();
        assertEquals(1, unavailable.createCalls);
        assertEquals(0, unavailable.clockCalls);

        FakeHintPlatform platform = new FakeHintPlatform(31, 112);
        AndroidPerformanceBoost failedReport = new AndroidPerformanceBoost(platform);
        failedReport.onSessionStarted(ExecutionMode.PERFORMANCE);
        FakeHintSession session = platform.sessions.get(0);
        session.reportFailure = new IllegalStateException("service died");
        session.closeFailure = new IllegalStateException("already gone");
        platform.nowNanos = 10L;
        failedReport.onPlaybackStateChanged(false);
        failedReport.onWorkStarted();
        platform.nowNanos = 20L;
        failedReport.onWorkCompleted();
        failedReport.onWorkStarted();
        platform.nowNanos = 40L;
        failedReport.onWorkCompleted();
        platform.nowNanos = 50L;
        failedReport.onWorkStarted();
        failedReport.onWorkCompleted();
        assertEquals(1, session.reportCalls);
        assertEquals(1, session.closeCalls);
    }

    private static void assertTargetDuration(ClockSpec clockSpec, long expectedNanos) {
        FakeHintPlatform platform = new FakeHintPlatform(31, 120);
        AndroidPerformanceBoost boost = new AndroidPerformanceBoost(platform);
        boost.onHardwareProfile(clockSpec);
        boost.onSessionStarted(ExecutionMode.PERFORMANCE);
        assertEquals(expectedNanos, platform.lastTargetWorkDurationNanos);
        boost.close();
    }

    private static final class FakeHintPlatform implements AndroidPerformanceBoost.HintPlatform {
        private final int sdkInt;
        private final int tid;
        private final List<FakeHintSession> sessions = new ArrayList<>();
        private int threadIdCalls;
        private int clockCalls;
        private int createCalls;
        private int[] lastThreadIds;
        private long lastTargetWorkDurationNanos;
        private long nowNanos;
        private RuntimeException createFailure;

        private FakeHintPlatform(int sdkInt, int tid) {
            this.sdkInt = sdkInt;
            this.tid = tid;
        }

        @Override
        public int sdkInt() {
            return sdkInt;
        }

        @Override
        public int currentThreadId() {
            threadIdCalls++;
            return tid;
        }

        @Override
        public long uptimeNanos() {
            clockCalls++;
            return nowNanos;
        }

        @Override
        public AndroidPerformanceBoost.HintSession createHintSession(
                int[] threadIds, long targetWorkDurationNanos) {
            createCalls++;
            if (createFailure != null) {
                throw createFailure;
            }
            lastThreadIds = Arrays.copyOf(threadIds, threadIds.length);
            lastTargetWorkDurationNanos = targetWorkDurationNanos;
            FakeHintSession session = new FakeHintSession();
            sessions.add(session);
            return session;
        }
    }

    private static final class FakeHintSession implements AndroidPerformanceBoost.HintSession {
        private final List<Long> durations = new ArrayList<>();
        private int reportCalls;
        private int closeCalls;
        private RuntimeException reportFailure;
        private RuntimeException closeFailure;

        @Override
        public void reportActualWorkDuration(long actualDurationNanos) {
            reportCalls++;
            if (reportFailure != null) {
                throw reportFailure;
            }
            durations.add(actualDurationNanos);
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
