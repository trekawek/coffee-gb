package eu.rekawek.coffeegb.android;

import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import eu.rekawek.coffeegb.core.hardware.HardwareProfile;

/**
 * Bounded, redacted benchmark telemetry for the profileable QA APK.
 *
 * <p>All records are key/value pairs under one stable tag.  This class receives only lifecycle,
 * hardware, frame-boundary and host-audio metadata; it never receives a URI, ROM title, ROM
 * bytes, save bytes, pixels, hash, or filesystem path.  The release variant constructs no active
 * instance because {@link BuildConfig#DIAGNOSTICS_ENABLED} is a compile-time false constant.</p>
 */
final class AndroidBenchmarkDiagnostics {

    static final String TAG = "CoffeeGbBench";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int INTERVAL_FRAMES = 60;
    private static final int FINAL_FRAME = 600;

    private final DiagnosticsOptions options;
    private final boolean enabled;
    private long launchNanos;
    private long openNanos;
    private long preparationNanos;
    private long firstFrameNanos;
    private long previousIntervalNanos;
    private long previousIntervalFrame;
    private long controllerCpuStartNanos;
    private long gcCountStart;
    private long gcTimeStart;
    private long allocBytesStart;
    private long frames;
    private HardwareProfile profile;
    private boolean emulationStarted;

    AndroidBenchmarkDiagnostics(DiagnosticsOptions options) {
        this.options = options == null ? DiagnosticsOptions.disabled() : options;
        enabled = BuildConfig.DIAGNOSTICS_ENABLED && this.options.enabled;
    }

    boolean enabled() {
        return enabled;
    }

    boolean frameSink() {
        return enabled && options.render == DiagnosticsOptions.Render.FRAME_SINK;
    }

    void sessionLaunch() {
        if (!enabled) {
            return;
        }
        launchNanos = now();
        openNanos = 0L;
        preparationNanos = 0L;
        firstFrameNanos = 0L;
        previousIntervalNanos = 0L;
        previousIntervalFrame = 0L;
        controllerCpuStartNanos = 0L;
        gcCountStart = 0L;
        gcTimeStart = 0L;
        allocBytesStart = 0L;
        frames = 0L;
        profile = null;
        emulationStarted = false;
        record("event=session_launch launch_ns=" + launchNanos
                + " hardware=" + options.hardware.name().toLowerCase()
                + " audio=" + (options.audioOutput ? "on" : "off")
                + " render=" + (options.render == DiagnosticsOptions.Render.FRAME_SINK
                        ? "sink" : "presentation")
                + " warmup=" + (options.runtimeWarmup ? "on" : "off"));
    }

    void openStart() {
        if (!enabled) {
            return;
        }
        openNanos = now();
        record("event=rom_open_start wall_ns=" + openNanos
                + " since_launch_ms=" + elapsedMillis(openNanos, launchNanos));
    }

    void noRecentEntry() {
        if (enabled) {
            record("event=recent_missing");
        }
    }

    void hardwareProfile(HardwareProfile next) {
        if (!enabled || next == null) {
            return;
        }
        profile = next;
        record("event=hardware_profile profile=" + next.id()
                + " family=" + next.family().name().toLowerCase());
    }

    /** Called on the emulation/event thread before the first physical frame. */
    void emulationStarted() {
        if (!enabled) {
            return;
        }
        long preparationOrigin = openNanos == 0L ? launchNanos : openNanos;
        preparationNanos = now();
        firstFrameNanos = 0L;
        previousIntervalNanos = 0L;
        previousIntervalFrame = 0L;
        frames = 0L;
        controllerCpuStartNanos = threadCpuNanos();
        gcCountStart = globalGcCount();
        gcTimeStart = globalGcTime();
        allocBytesStart = globalAllocBytes();
        emulationStarted = true;
        record("event=emulation_started wall_ns=" + preparationNanos
                + " prep_ms=" + elapsedMillis(preparationNanos, preparationOrigin)
                + " hardware=" + (profile == null ? "unknown" : profile.id()));
        openNanos = 0L;
    }

    /**
     * Counts one authoritative physical presentation event.  DMG transfer events are filtered by
     * {@link NativeFrameStore} before this method is called for SGB sessions.
     */
    void physicalFrame() {
        if (!enabled || !emulationStarted) {
            return;
        }
        long count = ++frames;
        long current = now();
        if (count == 1L) {
            firstFrameNanos = current;
            previousIntervalNanos = current;
            previousIntervalFrame = 1L;
            record("event=first_frame frame=1 wall_ns=" + current
                    + " since_launch_ms=" + elapsedMillis(current, launchNanos)
                    + " prep_to_frame_ms=" + elapsedMillis(current, preparationNanos));
        }
        if (count % INTERVAL_FRAMES == 0L && count <= FINAL_FRAME) {
            interval(current, count);
        }
    }

    void audioStats(AndroidAudioSink.AudioStats stats) {
        if (!enabled || stats == null) {
            return;
        }
        record("event=audio_output sample_rate=" + stats.sampleRate()
                + " min_buffer_bytes=" + stats.minimumBufferBytes()
                + " configured_buffer_bytes=" + stats.configuredBufferBytes()
                + " actual_buffer_bytes=" + stats.actualBufferBytes());
    }

    private void interval(long current, long frame) {
        long elapsed = Math.max(1L, current - firstFrameNanos);
        long intervalElapsed = Math.max(1L, current - previousIntervalNanos);
        long intervalFrames = frame - previousIntervalFrame;
        double fps = Math.max(0L, frame - 1L) * (double) NANOS_PER_SECOND / elapsed;
        double intervalFps = intervalFrames * (double) NANOS_PER_SECOND / intervalElapsed;
        long cpu = threadCpuNanos();
        long cpuBase = controllerCpuStartNanos == 0L ? cpu : controllerCpuStartNanos;
        long cpuElapsed = Math.max(0L, cpu - cpuBase);
        double utilization = cpuElapsed * 100.0 / Math.max(1L, current - preparationNanos);
        record("event=frames frame=" + frame
                + " wall_ms=" + elapsedMillis(current, firstFrameNanos)
                + " wall_delta_ms=" + elapsedMillis(current, launchNanos)
                + " fps=" + format(fps)
                + " interval_fps=" + format(intervalFps)
                + " controller_cpu_ms=" + (cpuElapsed / NANOS_PER_MILLI)
                + " controller_util_pct=" + format(utilization)
                + " gc_count_delta=" + delta(globalGcCount(), gcCountStart)
                + " gc_time_ms_delta=" + delta(globalGcTime(), gcTimeStart)
                + " alloc_bytes_delta=" + delta(globalAllocBytes(), allocBytesStart));
        previousIntervalNanos = current;
        previousIntervalFrame = frame;
        if (frame == FINAL_FRAME) {
            record("event=final_result frame=600 wall_ms="
                    + elapsedMillis(current, firstFrameNanos) + " fps=" + format(fps)
                    + " controller_cpu_ms=" + (cpuElapsed / NANOS_PER_MILLI)
                    + " controller_util_pct=" + format(utilization)
                    + " gc_count_delta=" + delta(globalGcCount(), gcCountStart)
                    + " gc_time_ms_delta=" + delta(globalGcTime(), gcTimeStart)
                    + " alloc_bytes_delta=" + delta(globalAllocBytes(), allocBytesStart));
        }
    }

    private static long now() {
        return SystemClock.elapsedRealtimeNanos();
    }

    private static long threadCpuNanos() {
        try {
            return Debug.threadCpuTimeNanos();
        } catch (RuntimeException unavailable) {
            return 0L;
        }
    }

    private static long globalGcCount() {
        try {
            return Debug.getGlobalGcInvocationCount();
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }

    private static long globalGcTime() {
        try {
            return runtimeStat("art.gc.gc-time");
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }

    private static long runtimeStat(String key) {
        String value = Debug.getRuntimeStat(key);
        if (value == null || value.isBlank()) {
            return -1L;
        }
        return Long.parseLong(value);
    }

    private static long globalAllocBytes() {
        try {
            return Debug.getGlobalAllocSize();
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }

    private static long elapsedMillis(long end, long start) {
        return start <= 0L ? -1L : Math.max(0L, (end - start) / NANOS_PER_MILLI);
    }

    private static long delta(long value, long baseline) {
        return value < 0L || baseline < 0L ? -1L : Math.max(0L, value - baseline);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void record(String message) {
        Log.i(TAG, message);
    }
}
