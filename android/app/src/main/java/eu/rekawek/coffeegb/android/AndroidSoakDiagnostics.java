package eu.rekawek.coffeegb.android;

import android.os.Process;
import android.util.Log;
import eu.rekawek.coffeegb.controller.Controller;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Opt-in ordinary-playback evidence. No ROM identifiers, pixels, or audio content are recorded.
 *
 * <p>The controller callback only copies its owner envelope and submits one immutable request.
 * AudioTrack and AudioManager calls run on this short-lived daemon worker, so an Android media
 * service stall cannot hold the controller or PCM accounting monitors. A request already being
 * serviced makes the next request an explicit drop; it is never emitted out of sequence.</p>
 */
final class AndroidSoakDiagnostics implements AutoCloseable {
    static final String EXTRA_ENABLED = "coffee_gb_soak";
    private static final long MAX_FRESH_LATENCY_NANOS = 2_000_000_000L;

    private final long recordingGeneration = System.nanoTime();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong submissionFailures = new AtomicLong();
    private final AtomicLong droppedRequests = new AtomicLong();
    private final AtomicBoolean workerBusy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object workerStateLock = new Object();
    private final ExecutorService worker;
    private final Consumer<String> recordSink;
    private final IntSupplier controllerPriority;

    AndroidSoakDiagnostics() {
        this(message -> Log.i("CoffeeGbSoak", message), AndroidSoakDiagnostics::currentControllerPriority);
    }

    /** Package-private sink seam for the bounded concurrency tests. */
    AndroidSoakDiagnostics(Consumer<String> recordSink) {
        this(recordSink, AndroidSoakDiagnostics::currentControllerPriority);
    }

    /** Package-private priority seam keeps JVM tests independent of unmocked Android process APIs. */
    AndroidSoakDiagnostics(Consumer<String> recordSink, IntSupplier controllerPriority) {
        this.recordSink = Objects.requireNonNull(recordSink, "recordSink");
        this.controllerPriority = Objects.requireNonNull(controllerPriority, "controllerPriority");
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coffee-gb-soak-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static int currentControllerPriority() {
        return Process.getThreadPriority(Process.myTid());
    }

    void submitted() {
        submitted.incrementAndGet();
    }

    void submissionFailed() {
        submissionFailures.incrementAndGet();
    }

    void sample(Controller.PerformanceSoakSampleEvent event, AndroidAudioSink audio,
            boolean visible, boolean hintsActive) {
        if (!BuildConfig.DIAGNOSTICS_ENABLED || closed.get()) return;
        Request request = new Request(
                sequence.incrementAndGet(), System.nanoTime(), event.getSessionGeneration(),
                event.getHostTimeNanos(), event.getMasterTicks(), event.getNativeFrames(),
                event.getRenderedFrames(), event.getSuppressedFrames(), event.getWorkP95Nanos(),
                event.getWorkMaxNanos(), event.getPacingDebt(), event.getClockNumerator(),
                event.getClockDenominator(), event.getHardwareProfile(), event.getSpeed(),
                event.getExecutionMode(), event.getDmgCompat(), visible, hintsActive,
                controllerPriority.getAsInt(), submitted.get(),
                submissionFailures.get(), audio);
        if (!workerBusy.compareAndSet(false, true)) {
            droppedRequests.incrementAndGet();
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    emit(request);
                } finally {
                    workerBusy.set(false);
                    synchronized (workerStateLock) {
                        workerStateLock.notifyAll();
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            workerBusy.set(false);
            droppedRequests.incrementAndGet();
            synchronized (workerStateLock) {
                workerStateLock.notifyAll();
            }
        }
    }

    /** Package-private wait seam for tests that must separate record delivery from worker idle. */
    boolean awaitIdleForTesting(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (workerStateLock) {
            while (workerBusy.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                TimeUnit.NANOSECONDS.timedWait(workerStateLock, remaining);
            }
            return true;
        }
    }

    private void emit(Request request) {
        if (closed.get()) return;
        AndroidAudioSink.SoakDiagnosticSnapshot snapshot = request.audio() == null
                ? null : request.audio().snapshotForSoak(request.requestedAtNanos());
        if (closed.get()) return;
        try {
            JSONObject out = new JSONObject();
            out.put("schema", "coffee-gb-soak-app-v2");
            out.put("recordingGeneration", recordingGeneration);
            out.put("sessionGeneration", request.sessionGeneration());
            out.put("hostTimeNanos", request.hostTimeNanos());
            out.put("masterTicks", request.masterTicks());
            out.put("nativeFrames", request.nativeFrames());
            out.put("renderedFrames", request.renderedFrames());
            out.put("suppressedFrames", request.suppressedFrames());
            out.put("submittedFrames", request.submittedFrames());
            out.put("submissionFailures", request.submissionFailures());
            out.put("workP95Nanos", request.workP95Nanos());
            out.put("workMaxNanos", request.workMaxNanos());
            out.put("pacingDebt", request.pacingDebt());
            out.put("clockNumerator", request.clockNumerator());
            out.put("clockDenominator", request.clockDenominator());
            out.put("hardwareProfile", request.hardwareProfile());
            out.put("dmgCompat", request.dmgCompat());
            out.put("speed", request.speed());
            out.put("executionMode", request.executionMode());
            out.put("visible", request.visible());
            out.put("hintsActive", request.hintsActive());
            out.put("controllerPriority", request.controllerPriority());
            AndroidAudioSink.Stats a = snapshot == null ? null : snapshot.stats();
            String status = snapshotStatus(snapshot, request.requestedAtNanos());
            out.put("audioAvailable", a != null && snapshot.available());
            out.put("audioActive", a != null && a.active());
            out.put("audioPaused", a != null && a.paused());
            out.put("audioPlaying", a != null && a.outputPlaying());
            out.put("audioOpen", a != null && a.outputOpen());
            out.put("audioMuted", a != null && a.muted());
            out.put("audioVolume", a == null ? -1 : a.volume());
            out.put("systemVolume", a == null ? -1 : a.systemVolume());
            out.put("systemMuted", a == null || a.systemMusicMuted());
            out.put("audioSampleRate", a == null ? -1 : a.sampleRate());
            out.put("audioPlaybackFrames", a == null ? -1 : a.playbackPositionFrames());
            out.put("audioWrittenFrames", a == null ? -1 : a.pcmWrittenFrames());
            out.put("audioUnderruns", a == null ? -1 : a.outputUnderruns());
            out.put("audioOverruns", a == null ? -1 : a.overruns());
            out.put("audioDiscardedBytes", a == null ? -1 : a.pcmDiscardedBytes());
            out.put("audioWriteFailures", a == null ? -1 : a.writeFailures());
            out.put("audioRouteFailures", a == null ? -1 : a.routeFailures());
            out.put("audioRestarts", a == null ? -1 : a.restarts());
            out.put("audioOutputIdentity", a == null ? -1 : a.outputIdentity());
            out.put("audioQueueIdentity", a == null ? -1 : a.queueIdentity());
            out.put("audioSnapshotSequence", request.sequence());
            out.put("audioSnapshotDropped", droppedRequests.get());
            out.put("audioSnapshotRequestedAtNanos", request.requestedAtNanos());
            out.put("audioSnapshotCapturedAtNanos",
                    snapshot == null ? -1 : snapshot.capturedAtNanos());
            out.put("audioSnapshotCompletedAtNanos",
                    snapshot == null ? -1 : snapshot.completedAtNanos());
            out.put("audioSnapshotRouteGeneration",
                    snapshot == null ? -1 : snapshot.routeGeneration());
            out.put("audioSnapshotStatus", status);
            if (closed.get()) return;
            recordSink.accept(out.toString());
        } catch (JSONException | RuntimeException ignored) {
            // An incomplete record cannot qualify as evidence, and diagnostics must not affect
            // the owner thread or the ordinary audio policy.
        }
    }

    private static String snapshotStatus(AndroidAudioSink.SoakDiagnosticSnapshot snapshot,
            long requestedAtNanos) {
        if (snapshot == null || snapshot.stats() == null || !snapshot.available()) {
            return "UNAVAILABLE";
        }
        if (!snapshot.coherent()) {
            return "INCOHERENT";
        }
        long completed = snapshot.completedAtNanos();
        if (completed < requestedAtNanos
                || completed - requestedAtNanos > MAX_FRESH_LATENCY_NANOS) {
            return "STALE";
        }
        return "FRESH";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow();
    }

    private record Request(long sequence, long requestedAtNanos, long sessionGeneration,
                           long hostTimeNanos, long masterTicks, long nativeFrames,
                           long renderedFrames, long suppressedFrames, long workP95Nanos,
                           long workMaxNanos, boolean pacingDebt, long clockNumerator,
                           long clockDenominator, String hardwareProfile, int speed,
                           String executionMode, boolean dmgCompat, boolean visible,
                           boolean hintsActive, int controllerPriority, long submittedFrames,
                           long submissionFailures, AndroidAudioSink audio) {
    }
}
