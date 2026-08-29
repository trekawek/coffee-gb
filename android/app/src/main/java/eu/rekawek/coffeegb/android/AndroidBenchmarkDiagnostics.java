package eu.rekawek.coffeegb.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Bounded, redacted benchmark telemetry for the profileable QA APK.
 *
 * <p>All records are key/value pairs under one stable tag.  This class receives only lifecycle,
 * hardware, frame-boundary and host-audio metadata; it never receives a URI, ROM title, ROM
 * bytes, save bytes, pixels, hash, or filesystem path.  The release variant constructs no active
 * instance because {@link BuildConfig#DIAGNOSTICS_ENABLED} is a compile-time false constant.</p>
 */
final class AndroidBenchmarkDiagnostics {

    /** Callback used by the runtime to revoke one measured generation on a bad host-audio read. */
    interface SystemAudioViolationSink {
        void onViolation(long generation, long sessionGeneration);
    }

    /** Bounded log seam; production uses Android Log, benchmark JVM tests inject a no-op sink. */
    interface RecordSink {
        void write(String message);
    }

    static final String TAG = "CoffeeGbBench";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int INTERVAL_FRAMES = 60;
    private static final int FINAL_FRAME = 600;
    private static final int UNKNOWN = -1;
    /** Leaves explicit headroom below Android's approximately 4 KiB per-record payload cliff. */
    static final int MAX_LOG_RECORD_BYTES = 3_750;

    private enum Phase {
        IDLE,
        SCENARIO_RUNNING,
        WARMING,
        ANCHOR_READY,
        ARMED,
        CORE_FROZEN,
        SUBMISSIONS_COMPLETE,
        DONE
    }

    private final Context context;
    private final DiagnosticsOptions options;
    private final boolean enabled;
    private final RecordSink recordSink;
    private final LongSupplier monotonicNanos;
    /** SHA-256 of the installed base APK; never supplied by launch metadata. */
    private final String artifactId;
    /** SHA-256 of Android ID plus stable device characteristics, kept redacted. */
    private final String deviceId;
    private long launchNanos;
    private long openNanos;
    private long preparationNanos;
    private long firstFrameNanos;
    private long previousIntervalNanos;
    private long previousIntervalFrame;
    private long controllerCpuStartNanos;
    /** Latest CPU-time sample captured on the emulation/controller callback thread. */
    private volatile long controllerCpuLatestNanos;
    /** Latest Android thread priority observed on the emulation/controller callback thread. */
    private volatile int controllerThreadPriority = UNKNOWN;
    private long gcCountStart;
    private long gcTimeStart;
    private long allocBytesStart;
    private long readyFrames;
    private long submittedFrames;
    private long readyFirstNanos;
    private long readyLastNanos;
    private long submittedFirstNanos;
    private long submittedLastNanos;
    private long firstSubmittedId;
    private long lastSubmittedId;
    private long droppedFrames;
    private long duplicateFrames;
    private long lateFrames;
    private long corruptFrames;
    private boolean finalResultEmitted;
    /** True only after the out-of-epoch drain post has completed (or sink mode is terminal). */
    private boolean benchmarkDrainSuccess;
    private boolean warmupComplete;
    private boolean measurementArmed;
    private Phase phase = Phase.IDLE;
    private long benchmarkGeneration;
    /** Opaque host arm token copied into every run-bound record. */
    private String benchmarkToken = "unknown";
    private long activeSessionGeneration;
    private long scenarioSessionGeneration;
    private int scenarioExpectedFrames;
    private int scenarioCompletedFrames;
    private boolean scenarioCompleted;
    private boolean scenarioSourceClosed;
    private boolean scenarioAudioDrained;
    /** Terminal for one active session: visibility cannot be reconstructed before ARM. */
    private boolean preArmVisibilityLost;
    /** Sticky visibility poison inherited by every session generation in this runtime. */
    private boolean nextSessionVisibilityLost;
    private boolean speedFinalSample;
    private int speedModeFinal = UNKNOWN;
    /** Audio counters sampled at the physical ready-600 boundary, before compositor drain delay. */
    private AndroidAudioSink.Stats audioTerminalStats;
    /** Output/queue identities sampled with the terminal PCM counters. */
    private long audioTerminalOutputIdentity;
    private long audioTerminalQueueIdentity;
    private String workloadNonce = "unknown";
    private long liveInputMutations;
    private HardwareProfile profile;
    private Boolean effectiveGbc;
    private Boolean effectiveDmgCompat;
    private Integer effectiveSpeedMode;
    private DiagnosticsOptions.EffectiveMode effectiveMode = DiagnosticsOptions.EffectiveMode.UNKNOWN;
    private ClockSpec effectiveClock;
    private boolean emulationStarted;
    private EnvironmentSample environmentStart = EnvironmentSample.unavailable();
    private int environmentSampleCount;
    private int thermalWorst;
    private int systemLoadWorstMilli;
    private int cpuFreqMinKHz;
    private int displayRefreshMinMillihz;
    private int displayBadCount;
    private int interactiveBadCount;
    private int pluggedBadCount;
    private int powerSaveBadCount;
    private int stayAwakeBadCount;
    private int priorityBadCount;
    private int importanceBadCount;
    private int batteryTempMin;
    private int batteryTempMax;
    private AndroidAudioSink audioSink;
    /** JVM-only seam used by production-length diagnostics tests; real runs always read audioSink. */
    private AndroidAudioSink.AudioBaseline systemAudioBaselineForTesting;
    /** Absolute sink ledger captured when the current emulation session materializes. */
    private AndroidAudioSink.AudioBaseline audioSessionBaseline =
            AndroidAudioSink.AudioBaseline.unavailable();
    private AndroidAudioSink.AudioBaseline audioBaseline =
            AndroidAudioSink.AudioBaseline.unavailable();
    private boolean audioFocusGranted;
    private long audioFocusLossCount;
    private long audioFocusStartLossCount;
    private DiagnosticsOptions.AudioPolicy benchmarkAudioPolicy =
            DiagnosticsOptions.AudioPolicy.CANONICAL;
    private boolean benchmarkAudioRequested;
    private boolean benchmarkAudioActiveAtBoundary;
    private boolean benchmarkAudioDisabledAfterBoundary;
    private long benchmarkAudioSkippedTicks;
    private long benchmarkAudioZeroSampleSlots;
    private long benchmarkAudioZeroSampleEvents;
    private long benchmarkAudioMaxDebt;
    private long benchmarkAudioApuReads;
    private long benchmarkAudioApuWrites;
    private long benchmarkAudioFrameSequencerCommits;
    private long benchmarkAudioDroppedChannelTicks;
    /** Silent-PCM host proof: ARM, ten interval boundaries, and one terminal read. */
    private int systemAudioSampleCount;
    private int systemAudioBadCount;
    private boolean systemAudioBadLatched;
    private int systemAudioLastFrame;
    private final SystemAudioViolationSink systemAudioViolationSink;

    AndroidBenchmarkDiagnostics(DiagnosticsOptions options) {
        this(null, options, AndroidBenchmarkDiagnostics::logRecord,
                AndroidBenchmarkDiagnostics::systemNow);
    }

    AndroidBenchmarkDiagnostics(Context context, DiagnosticsOptions options) {
        this(context, options, AndroidBenchmarkDiagnostics::logRecord,
                AndroidBenchmarkDiagnostics::systemNow);
    }

    AndroidBenchmarkDiagnostics(Context context, DiagnosticsOptions options,
            SystemAudioViolationSink systemAudioViolationSink) {
        this(context, options, AndroidBenchmarkDiagnostics::logRecord,
                AndroidBenchmarkDiagnostics::systemNow, null, null, systemAudioViolationSink);
    }

    AndroidBenchmarkDiagnostics(Context context, DiagnosticsOptions options, RecordSink recordSink) {
        this(context, options, recordSink, AndroidBenchmarkDiagnostics::systemNow);
    }

    AndroidBenchmarkDiagnostics(Context context, DiagnosticsOptions options, RecordSink recordSink,
            LongSupplier monotonicNanos) {
        this(context, options, recordSink, monotonicNanos, null, null);
    }

    /** Test seam for production-length redacted identities without depending on an Android APK. */
    AndroidBenchmarkDiagnostics(Context context, DiagnosticsOptions options, RecordSink recordSink,
            LongSupplier monotonicNanos, String artifactIdOverride, String deviceIdOverride) {
        this(context, options, recordSink, monotonicNanos, artifactIdOverride, deviceIdOverride,
                (generation, sessionGeneration) -> { });
    }

    AndroidBenchmarkDiagnostics(Context context, DiagnosticsOptions options, RecordSink recordSink,
            LongSupplier monotonicNanos, String artifactIdOverride, String deviceIdOverride,
            SystemAudioViolationSink systemAudioViolationSink) {
        this.context = context == null ? null : context.getApplicationContext();
        this.options = options == null ? DiagnosticsOptions.disabled() : options;
        this.recordSink = recordSink == null ? AndroidBenchmarkDiagnostics::logRecord : recordSink;
        this.monotonicNanos = monotonicNanos == null
                ? AndroidBenchmarkDiagnostics::systemNow : monotonicNanos;
        this.systemAudioViolationSink = systemAudioViolationSink == null
                ? (generation, sessionGeneration) -> { } : systemAudioViolationSink;
        enabled = BuildConfig.DIAGNOSTICS_ENABLED && this.options.enabled;
        artifactId = enabled && artifactIdOverride != null
                ? boundedDigestOverride(artifactIdOverride)
                : enabled ? sha256File(this.context == null ? null
                        : this.context.getPackageCodePath()) : "unavailable";
        deviceId = enabled && deviceIdOverride != null
                ? boundedDigestOverride(deviceIdOverride)
                : enabled ? deviceIdentity(this.context) : "unavailable";
    }

    private static String boundedDigestOverride(String value) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Benchmark identity override must be SHA-256 hex");
        }
        return value;
    }

    boolean enabled() {
        return enabled;
    }

    boolean frameSink() {
        return enabled && options.render == DiagnosticsOptions.Render.FRAME_SINK;
    }

    /** Installs the app-persisted opaque selection nonce after the recent entry is selected. */
    synchronized void setWorkloadNonce(String nonce) {
        if (!enabled || nonce == null || !nonce.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            return;
        }
        workloadNonce = nonce;
    }

    /** Returns whether the host may issue the one-shot compositor-baseline arm action. */
    synchronized boolean benchmarkAnchorReady(long sessionGeneration) {
        return enabled && sessionGeneration > 0L
                && sessionGeneration == activeSessionGeneration
                && !preArmVisibilityLost
                && phase == Phase.ANCHOR_READY && !measurementArmed
                && scenarioCompleted && scenarioSourceClosed && scenarioAudioDrained;
    }

    /** Completes the host anchor only after the renderer has returned from unlockCanvasAndPost. */
    synchronized void benchmarkAnchorPosted(long sessionGeneration, boolean success) {
        if (sessionGeneration <= 0L || sessionGeneration != activeSessionGeneration) {
            if (enabled) {
                record("event=benchmark_anchor success=false phase="
                        + phase.name().toLowerCase() + " reason=stale_session");
            }
            return;
        }
        if (preArmVisibilityLost) {
            record("event=benchmark_anchor success=false phase="
                    + phase.name().toLowerCase() + " reason=visibility_lost");
            return;
        }
        if (!enabled || phase != Phase.WARMING || !warmupComplete) {
            if (enabled) {
                record("event=benchmark_anchor success=false phase="
                        + phase.name().toLowerCase() + " reason=not_warming");
            }
            return;
        }
        if (!success) {
            record("event=benchmark_anchor success=false phase=warming reason=post_failed");
            return;
        }
        phase = Phase.ANCHOR_READY;
        record("event=benchmark_anchor success=true phase=anchor_ready");
    }

    synchronized long benchmarkGeneration() {
        return benchmarkGeneration;
    }

    synchronized boolean acceptsFrameEpoch(long generation) {
        return enabled && measurementArmed && benchmarkGeneration == generation;
    }

    /**
     * Arms one explicit, opaque measurement generation after the host has captured its baseline.
     * The controller receives the matching [Controller.BenchmarkArmEvent] before its Resume event,
     * so timing debt and frame counters reset while the core is still paused.
     */
    synchronized boolean armBenchmark(long sessionGeneration, long generation, String token,
            AndroidAudioSink.AudioBaseline baseline) {
        boolean audioPending = options.audioOutput && baseline != null
                && (baseline.pendingBytes() != 0L || baseline.queuedBytes() != 0L);
        boolean audioNotPlaying = options.audioOutput && baseline != null
                && !baseline.outputPlaying();
        boolean silentPolicyValid = !options.audioPolicy.isSilent()
                || (validSilentPcmBaseline(baseline)
                && validSilentPcmSessionEvidence(audioSessionBaseline, baseline));
        boolean silentFocusHistoryValid = !options.audioPolicy.isSilent()
                || audioFocusLossCount == 0L;
        // A bad ARM read is itself a generation-bound policy violation. Capture it before the
        // ordinary admission checks so unavailable output cannot be mistaken for a benign reject.
        if (enabled && options.audioPolicy.isSilent() && generation > 0L
                && sessionGeneration > 0L && sessionGeneration == activeSessionGeneration
                && !validSystemAudioSample(baseline)) {
            benchmarkGeneration = generation;
            benchmarkToken = token == null ? "unknown" : token;
            systemAudioSampleCount = 0;
            systemAudioBadCount = 0;
            systemAudioBadLatched = false;
            systemAudioLastFrame = 0;
            sampleSystemAudioLocked(0, baseline);
            return false;
        }
        if (!enabled || phase != Phase.ANCHOR_READY || !warmupComplete
                || sessionGeneration <= 0L || sessionGeneration != activeSessionGeneration
                || preArmVisibilityLost
                || !scenarioCompleted || !scenarioSourceClosed || !scenarioAudioDrained
                || generation <= 0L || token == null
                || !token.matches("[a-z0-9][a-z0-9._-]{15,63}") || baseline == null
                || audioPending || audioNotPlaying
                || (options.audioOutput && !audioFocusGranted) || !silentPolicyValid
                || !silentFocusHistoryValid) {
            if (enabled) {
                record("event=benchmark_arm_rejected phase=" + phase.name().toLowerCase()
                        + " reason=" + (preArmVisibilityLost ? "visibility_lost"
                        : audioPending ? "audio_pending"
                        : !silentPolicyValid ? "silent_pcm_baseline"
                        : !silentFocusHistoryValid ? "audio_focus_history"
                        : audioNotPlaying ? "audio_not_playing"
                        : options.audioOutput && !audioFocusGranted
                                ? "audio_focus" : "not_anchor_ready"));
            }
            return false;
        }
        benchmarkGeneration = generation;
        benchmarkToken = token;
        benchmarkAudioPolicy = options.audioPolicy;
        benchmarkAudioRequested = options.audioPolicy.isSilent();
        benchmarkAudioActiveAtBoundary = false;
        benchmarkAudioDisabledAfterBoundary = false;
        benchmarkAudioSkippedTicks = 0L;
        benchmarkAudioZeroSampleSlots = 0L;
        benchmarkAudioZeroSampleEvents = 0L;
        benchmarkAudioMaxDebt = 0L;
        benchmarkAudioApuReads = 0L;
        benchmarkAudioApuWrites = 0L;
        benchmarkAudioFrameSequencerCommits = 0L;
        benchmarkAudioDroppedChannelTicks = 0L;
        systemAudioSampleCount = 0;
        systemAudioBadCount = 0;
        systemAudioBadLatched = false;
        systemAudioLastFrame = 0;
        audioTerminalOutputIdentity = 0L;
        audioTerminalQueueIdentity = 0L;
        audioBaseline = baseline;
        audioFocusStartLossCount = audioFocusLossCount;
        resetMeasurementWindow();
        controllerThreadPriority = threadPriority();
        environmentStart = sampleEnvironment(controllerThreadPriority);
        observeEnvironment(environmentStart);
        controllerCpuStartNanos = threadCpuNanos();
        controllerCpuLatestNanos = controllerCpuStartNanos;
        gcCountStart = globalGcCount();
        gcTimeStart = globalGcTime();
        allocBytesStart = globalAllocBytes();
        warmupComplete = true;
        measurementArmed = true;
        emulationStarted = true;
        phase = Phase.ARMED;
        sampleSystemAudioLocked(0, baseline);
        if (systemAudioBadLatched) {
            return false;
        }
        record("event=benchmark_arm generation=" + generation + " token=" + token
                + " phase=armed");
        recordMatrixRun();
        return true;
    }

    synchronized boolean setAudioBaseline(AndroidAudioSink.AudioBaseline baseline) {
        if (!enabled || phase != Phase.ANCHOR_READY || baseline == null) {
            return false;
        }
        audioBaseline = baseline;
        return true;
    }

    /** True after the current silent-PCM generation has been irreversibly revoked. */
    synchronized boolean systemAudioBadLatched() {
        return systemAudioBadLatched;
    }

    /** Bounded test seam for exercising the read-only system-audio fail-closed latch. */
    synchronized void sampleSystemAudioForTesting(int frame,
            AndroidAudioSink.AudioBaseline baseline) {
        sampleSystemAudioLocked(frame, baseline);
    }

    private static boolean validSilentPcmBaseline(AndroidAudioSink.AudioBaseline baseline) {
        return baseline != null
                && baseline.active()
                && !baseline.paused()
                && baseline.outputOpen()
                && baseline.outputPlaying()
                && !baseline.muted()
                && baseline.volume() == 100
                && baseline.pendingBytes() == 0L
                && baseline.queuedBytes() == 0L
                && baseline.queuedFrames() == 0
                && baseline.systemVolume() == 0
                && baseline.systemVolumeMax() > 0
                && baseline.systemMusicMuted()
                && baseline.sampleRate() > 0
                && baseline.queueCapacityFrames() > 0
                && baseline.maximumFrameBytes() > 0
                && baseline.playbackPositionFrames() >= 0L
                && baseline.inputEvents() > 0L
                && baseline.inputFrames() > 0L
                && baseline.writtenBytes() > 0L
                && baseline.writtenFrames() > 0L
                && baseline.writeFailures() == 0L
                && baseline.routeFailures() == 0L
                && !baseline.reopenPending()
                && baseline.outputIdentity() != 0L
                && baseline.queueIdentity() != 0L;
    }

    /**
     * Proves that the PCM evidence belongs to this emulation session rather than an older ROM
     * played through the runtime's lifetime-owned sink. The ARM snapshot stays absolute for the
     * final conservation ledger; only this admission check consumes the session-start delta.
     */
    private static boolean validSilentPcmSessionEvidence(
            AndroidAudioSink.AudioBaseline sessionStart,
            AndroidAudioSink.AudioBaseline arm) {
        if (sessionStart == null || arm == null) {
            return false;
        }
        long[] startCounters = {
                sessionStart.inputEvents(), sessionStart.inputFrames(),
                sessionStart.writtenBytes(), sessionStart.writtenFrames(),
                sessionStart.writeFailures(), sessionStart.routeFailures()
        };
        for (long counter : startCounters) {
            if (counter < 0L) {
                return false;
            }
        }
        return arm.inputEvents() > sessionStart.inputEvents()
                && arm.inputFrames() > sessionStart.inputFrames()
                && arm.writtenBytes() > sessionStart.writtenBytes()
                && arm.writtenFrames() > sessionStart.writtenFrames()
                && arm.writeFailures() == sessionStart.writeFailures()
                && arm.routeFailures() == sessionStart.routeFailures();
    }

    /** Returns true only for an affirmative, readable muted system-music state. */
    private static boolean validSystemAudioSample(AndroidAudioSink.AudioBaseline baseline) {
        return baseline != null && baseline.systemVolume() == 0
                && baseline.systemVolumeMax() > 0 && baseline.systemMusicMuted();
    }

    /** Samples only the selected silent policy. Canonical runs intentionally retain normal audio. */
    private void sampleSystemAudioLocked(int frame, AndroidAudioSink.AudioBaseline baseline) {
        if (!options.audioPolicy.isSilent() || systemAudioBadLatched || phase == Phase.DONE) {
            return;
        }
        systemAudioSampleCount++;
        systemAudioLastFrame = frame;
        if (!validSystemAudioSample(baseline)) {
            systemAudioBadCount++;
            if (!systemAudioBadLatched) {
                systemAudioBadLatched = true;
                measurementArmed = false;
                phase = Phase.CORE_FROZEN;
                // The owner-thread freeze and app-owned PCM pause are the safety boundary. Run
                // that callback before telemetry; logging must not delay the transition. Keep a
                // callback failure visible, but always emit the invalidation record in finally.
                try {
                    systemAudioViolationSink.onViolation(benchmarkGeneration,
                            activeSessionGeneration);
                } finally {
                    recordBenchmarkInvalidated(activeSessionGeneration, "system_audio_unmuted");
                }
            }
        }
    }

    /** Called by the physical frame boundary after the core has materialized frame-600 counters. */
    private void sampleSystemAudioTerminalLocked() {
        if (!options.audioPolicy.isSilent() || systemAudioBadLatched) {
            return;
        }
        AndroidAudioSink.AudioBaseline baseline = systemAudioCheckpointBaselineLocked();
        sampleSystemAudioLocked(FINAL_FRAME, baseline);
    }

    private AndroidAudioSink.AudioBaseline systemAudioCheckpointBaselineLocked() {
        if (audioSink != null) {
            return audioSink.benchmarkBaseline();
        }
        return systemAudioBaselineForTesting == null
                ? AndroidAudioSink.AudioBaseline.unavailable() : systemAudioBaselineForTesting;
    }

    synchronized void audioFocusResult(boolean granted) {
        if (!enabled) {
            return;
        }
        audioFocusGranted = granted;
        if (!granted) {
            audioFocusLossCount++;
        }
    }

    synchronized void audioFocusLost() {
        if (!enabled) {
            return;
        }
        // A focus-loss callback is authoritative even during warm-up/anchor.  Clear the current
        // grant immediately so ARM cannot consume a stale successful request; retain the loss
        // counter so the epoch baseline records the pre-arm history separately.
        audioFocusGranted = false;
        audioFocusLossCount++;
    }

    synchronized void inputMutation() {
        if (enabled && measurementArmed) {
            liveInputMutations++;
        }
    }

    synchronized boolean benchmarkWindowLocked() {
        return enabled && measurementArmed;
    }

    synchronized long inputMutationCount() {
        return liveInputMutations;
    }

    /** Returns true once the visible 600th submission has closed the bounded renderer window. */
    synchronized boolean submissionLimitReached() {
        return enabled && options.render == DiagnosticsOptions.Render.PRESENTATION
                && submittedFrames >= FINAL_FRAME;
    }

    /** Bounded state seam for benchmark-variant tests; never part of the release API. */
    synchronized String phaseForTesting() {
        return phase.name();
    }

    /** Bounded state seam for benchmark-variant tests; never part of the release API. */
    synchronized long readyFramesForTesting() {
        return readyFrames;
    }

    /** Bounded state seam for benchmark-variant tests; never part of the release API. */
    synchronized long submittedFramesForTesting() {
        return submittedFrames;
    }

    /** Bounded state seam for benchmark-variant tests; never part of the release API. */
    synchronized long corruptFramesForTesting() {
        return corruptFrames;
    }

    /** Bounded state seam for benchmark-variant tests; never part of the release API. */
    synchronized boolean finalResultEmittedForTesting() {
        return finalResultEmitted;
    }

    synchronized int systemAudioSampleCountForTesting() {
        return systemAudioSampleCount;
    }

    synchronized int systemAudioBadCountForTesting() {
        return systemAudioBadCount;
    }

    synchronized int systemAudioLastFrameForTesting() {
        return systemAudioLastFrame;
    }

    synchronized void benchmarkFrameBoundary(Controller.BenchmarkFrameBoundaryEvent event) {
        if (!enabled || event == null || event.getFrame() != FINAL_FRAME) {
            return;
        }
        sampleSystemAudioTerminalLocked();
        if (!measurementArmed || systemAudioBadLatched) {
            return;
        }
        if (!options.audioPolicy.externalValue().equals(event.getBenchmarkAudioPolicy())
                || event.getBenchmarkAudioRequested() != options.audioPolicy.isSilent()) {
            // The controller's terminal evidence is generation-bound.  A policy/token drift
            // cannot be reinterpreted as canonical output, so leave the run frozen without a
            // final_result record.
            measurementArmed = false;
            phase = Phase.CORE_FROZEN;
            return;
        }
        // Rebind the final evidence to the actual core state sampled at physical Display frame
        // 600. A CGB speed/profile transition must not be hidden behind boot-time labels.
        effectiveGbc = event.getEffectiveGbc();
        effectiveDmgCompat = event.getEffectiveDmgCompat();
        effectiveMode = DiagnosticsOptions.EffectiveMode.classify(
                profile, effectiveGbc, effectiveDmgCompat);
        speedModeFinal = event.getSpeedMode();
        speedFinalSample = speedModeFinal == 1 || speedModeFinal == 2;
        benchmarkAudioPolicy = DiagnosticsOptions.AudioPolicy.fromExternalValue(
                event.getBenchmarkAudioPolicy());
        benchmarkAudioRequested = event.getBenchmarkAudioRequested();
        benchmarkAudioActiveAtBoundary = event.getBenchmarkAudioActiveAtBoundary();
        benchmarkAudioDisabledAfterBoundary = event.getBenchmarkAudioDisabledAfterBoundary();
        benchmarkAudioSkippedTicks = event.getBenchmarkAudioSkippedTicks();
        benchmarkAudioZeroSampleSlots = event.getBenchmarkAudioZeroSampleSlots();
        benchmarkAudioZeroSampleEvents = event.getBenchmarkAudioZeroSampleEvents();
        benchmarkAudioMaxDebt = event.getBenchmarkAudioMaxDebt();
        benchmarkAudioApuReads = event.getBenchmarkAudioApuReads();
        benchmarkAudioApuWrites = event.getBenchmarkAudioApuWrites();
        benchmarkAudioFrameSequencerCommits = event.getBenchmarkAudioFrameSequencerCommits();
        benchmarkAudioDroppedChannelTicks = event.getBenchmarkAudioDroppedChannelTicks();
        record("event=speed_sample frame=600 effective_gbc=" + event.getEffectiveGbc()
                + " effective_dmg_compat=" + event.getEffectiveDmgCompat()
                + " speed_mode_final=" + speedModeFinal + " speed_mode_sample=frame_600"
                + " performance_bulk_spans=" + event.getPerformanceBulkSpans()
                + " performance_bulk_ticks=" + event.getPerformanceBulkTicks()
                + " performance_epoch_count=" + event.getPerformanceEpochCount()
                + " performance_epoch_ticks=" + event.getPerformanceEpochTicks()
                + " performance_epoch_max_ticks=" + event.getPerformanceEpochMaxTicks()
                + " performance_epoch_raster_fast_ticks="
                + event.getPerformanceEpochRasterFastTicks()
                + " performance_epoch_mode2_replay_ticks="
                + event.getPerformanceEpochMode2ReplayTicks()
                + " performance_epoch_mode2_bulk_ticks="
                + event.getPerformanceEpochMode2BulkTicks()
                + " performance_epoch_lcd_off_ticks="
                + event.getPerformanceEpochLcdOffTicks()
                + " benchmark_audio_policy=" + event.getBenchmarkAudioPolicy()
                + " benchmark_audio_requested=" + event.getBenchmarkAudioRequested()
                + " benchmark_audio_active_at_boundary="
                + event.getBenchmarkAudioActiveAtBoundary()
                + " benchmark_audio_disabled_after="
                + event.getBenchmarkAudioDisabledAfterBoundary()
                + " benchmark_audio_skipped_ticks=" + event.getBenchmarkAudioSkippedTicks()
                + " benchmark_audio_zero_sample_slots="
                + event.getBenchmarkAudioZeroSampleSlots()
                + " benchmark_audio_zero_sample_events="
                + event.getBenchmarkAudioZeroSampleEvents()
                + " benchmark_audio_max_debt=" + event.getBenchmarkAudioMaxDebt()
                + " benchmark_audio_apu_reads=" + event.getBenchmarkAudioApuReads()
                + " benchmark_audio_apu_writes=" + event.getBenchmarkAudioApuWrites()
                + " benchmark_audio_frame_sequencer_commits="
                + event.getBenchmarkAudioFrameSequencerCommits()
                + " benchmark_audio_dropped_channel_ticks="
                + event.getBenchmarkAudioDroppedChannelTicks());
        if (phase == Phase.ARMED) {
            phase = Phase.CORE_FROZEN;
        }
        emitFinalResult();
    }

    synchronized void sessionLaunch() {
        if (!enabled) {
            return;
        }
        launchNanos = monotonicNanos.getAsLong();
        openNanos = 0L;
        preparationNanos = 0L;
        firstFrameNanos = 0L;
        previousIntervalNanos = 0L;
        previousIntervalFrame = 0L;
        controllerCpuStartNanos = 0L;
        controllerCpuLatestNanos = 0L;
        controllerThreadPriority = UNKNOWN;
        gcCountStart = 0L;
        gcTimeStart = 0L;
        allocBytesStart = 0L;
        readyFrames = 0L;
        submittedFrames = 0L;
        readyFirstNanos = 0L;
        readyLastNanos = 0L;
        submittedFirstNanos = 0L;
        submittedLastNanos = 0L;
        firstSubmittedId = 0L;
        lastSubmittedId = 0L;
        droppedFrames = 0L;
        duplicateFrames = 0L;
        lateFrames = 0L;
        corruptFrames = 0L;
        finalResultEmitted = false;
        benchmarkDrainSuccess = false;
        warmupComplete = false;
        measurementArmed = false;
        phase = Phase.WARMING;
        benchmarkGeneration = 0L;
        speedFinalSample = false;
        speedModeFinal = UNKNOWN;
        audioTerminalStats = null;
        audioTerminalOutputIdentity = 0L;
        audioTerminalQueueIdentity = 0L;
        workloadNonce = "unknown";
        liveInputMutations = 0L;
        profile = null;
        effectiveGbc = null;
        effectiveDmgCompat = null;
        effectiveSpeedMode = null;
        effectiveMode = DiagnosticsOptions.EffectiveMode.UNKNOWN;
        effectiveClock = null;
        emulationStarted = false;
        environmentStart = EnvironmentSample.unavailable();
        environmentSampleCount = 0;
        thermalWorst = UNKNOWN;
        systemLoadWorstMilli = UNKNOWN;
        cpuFreqMinKHz = UNKNOWN;
        displayRefreshMinMillihz = UNKNOWN;
        displayBadCount = 0;
        interactiveBadCount = 0;
        pluggedBadCount = 0;
        powerSaveBadCount = 0;
        stayAwakeBadCount = 0;
        priorityBadCount = 0;
        importanceBadCount = 0;
        batteryTempMin = UNKNOWN;
        batteryTempMax = UNKNOWN;
        audioSink = null;
        systemAudioBaselineForTesting = null;
        audioSessionBaseline = AndroidAudioSink.AudioBaseline.unavailable();
        audioBaseline = AndroidAudioSink.AudioBaseline.unavailable();
        audioFocusGranted = false;
        audioFocusLossCount = 0L;
        audioFocusStartLossCount = 0L;
        benchmarkAudioPolicy = DiagnosticsOptions.AudioPolicy.CANONICAL;
        benchmarkAudioRequested = false;
        benchmarkAudioActiveAtBoundary = false;
        benchmarkAudioDisabledAfterBoundary = false;
        benchmarkAudioSkippedTicks = 0L;
        benchmarkAudioZeroSampleSlots = 0L;
        benchmarkAudioZeroSampleEvents = 0L;
        benchmarkAudioMaxDebt = 0L;
        benchmarkAudioApuReads = 0L;
        benchmarkAudioApuWrites = 0L;
        benchmarkAudioFrameSequencerCommits = 0L;
        benchmarkAudioDroppedChannelTicks = 0L;
        benchmarkToken = "unknown";
        systemAudioSampleCount = 0;
        systemAudioBadCount = 0;
        systemAudioBadLatched = false;
        systemAudioLastFrame = 0;
        nextSessionVisibilityLost = false;
        record("event=session_launch launch_ns=" + launchNanos
                + " hardware=" + options.hardware.name().toLowerCase()
                + " requested_hardware=" + options.hardware.externalValue()
                + " audio=" + (options.audioOutput ? "on" : "off")
                + " render=" + (options.render == DiagnosticsOptions.Render.FRAME_SINK
                        ? "sink" : "presentation")
                + " warmup=" + (options.runtimeWarmup ? "on" : "off"));
    }

    synchronized void openStart() {
        if (!enabled) {
            return;
        }
        openNanos = monotonicNanos.getAsLong();
        record("event=rom_open_start wall_ns=" + openNanos
                + " since_launch_ms=" + elapsedMillis(openNanos, launchNanos));
    }

    synchronized void noRecentEntry() {
        if (enabled) {
            record("event=recent_missing");
        }
    }

    synchronized void hardwareProfile(Controller.HardwareProfileEvent next) {
        if (!enabled || next == null || next.getProfile() == null) {
            return;
        }
        profile = next.getProfile();
        effectiveGbc = next.getEffectiveGbc();
        effectiveDmgCompat = next.getEffectiveDmgCompat();
        effectiveSpeedMode = next.getEffectiveSpeedMode();
        effectiveMode = DiagnosticsOptions.EffectiveMode.classify(
                profile, effectiveGbc, effectiveDmgCompat);
        effectiveClock = next.getIdentity() == null ? null : next.getIdentity().clockSpec();
        record("event=hardware_profile requested_hardware=" + options.hardware.externalValue()
                + " requested_profile=" + requestedProfile()
                + " profile=" + profile.id()
                + " family=" + profile.family().name().toLowerCase()
                + " effective_gbc=" + valueOrUnknown(effectiveGbc)
                + " effective_dmg_compat=" + valueOrUnknown(effectiveDmgCompat)
                + " effective_mode=" + effectiveMode.externalValue()
                + " speed_mode_initial=" + valueOrUnknown(effectiveSpeedMode)
                + " speed_mode_sample=boot_resolved"
                + " " + clockFields());
    }

    /**
     * Called on the emulation/event thread once the selected session has materialized.  The
     * controller benchmark policy has already paused the core; this method only establishes the
     * host-visible anchor-ready state.  Counters and environment baselines wait for ARM.
     */
    synchronized void beginSession(long sessionGeneration) {
        AndroidAudioSink.AudioBaseline sessionBaseline = audioSink == null
                ? AndroidAudioSink.AudioBaseline.unavailable() : audioSink.benchmarkBaseline();
        beginSession(sessionGeneration, sessionBaseline);
    }

    /** Deterministic package-private seam for diagnostics fixtures. */
    synchronized void beginSession(long sessionGeneration,
            AndroidAudioSink.AudioBaseline sessionBaseline) {
        if (!enabled || sessionGeneration <= 0L) {
            return;
        }
        activeSessionGeneration = sessionGeneration;
        audioSessionBaseline = sessionBaseline == null
                ? AndroidAudioSink.AudioBaseline.unavailable() : sessionBaseline;
        benchmarkGeneration = 0L;
        measurementArmed = false;
        scenarioExpectedFrames = options.benchmarkScenario == DiagnosticsOptions.BenchmarkScenario.NONE
                ? 0 : -1;
        scenarioSessionGeneration = 0L;
        scenarioCompletedFrames = 0;
        scenarioCompleted = options.benchmarkScenario == DiagnosticsOptions.BenchmarkScenario.NONE;
        scenarioSourceClosed = scenarioCompleted;
        scenarioAudioDrained = scenarioCompleted;
        // A benchmark runtime is one visibility-continuous attempt. Once the host disappears,
        // every replacement generation in that runtime remains poisoned; only sessionLaunch on
        // a fresh runtime clears nextSessionVisibilityLost.
        preArmVisibilityLost = nextSessionVisibilityLost;
        phase = scenarioCompleted ? Phase.IDLE : Phase.SCENARIO_RUNNING;
        if (preArmVisibilityLost) {
            recordBenchmarkInvalidated(sessionGeneration);
        }
    }

    synchronized void invalidateSession() {
        activeSessionGeneration = 0L;
        benchmarkGeneration = 0L;
        measurementArmed = false;
        scenarioCompleted = false;
        scenarioSessionGeneration = 0L;
        scenarioSourceClosed = false;
        scenarioAudioDrained = false;
        preArmVisibilityLost = false;
        audioSessionBaseline = AndroidAudioSink.AudioBaseline.unavailable();
        phase = Phase.IDLE;
    }

    /** Latches a visibility discontinuity against the current and every replacement session. */
    synchronized void benchmarkVisibilityLost() {
        if (!enabled) {
            return;
        }
        nextSessionVisibilityLost = true;
        if (measurementArmed) {
            liveInputMutations++;
        }
        if (activeSessionGeneration <= 0L || preArmVisibilityLost) {
            return;
        }
        preArmVisibilityLost = true;
        recordBenchmarkInvalidated(activeSessionGeneration);
    }

    /** Run-bound evidence retained even when visibility is lost after final_result was emitted. */
    private void recordBenchmarkInvalidated(long sessionGeneration) {
        recordBenchmarkInvalidated(sessionGeneration, "visibility_lost");
    }

    private void recordBenchmarkInvalidated(long sessionGeneration, String reason) {
        record("event=benchmark_invalidated artifact_id=" + artifactId
                + " pair_id=" + options.pairId
                + " matrix_block=" + options.matrixBlock
                + " row_order=" + options.rowOrder
                + " run_side=" + options.runSide.externalValue()
                + " session_generation=" + sessionGeneration
                + " phase=" + phase.name().toLowerCase()
                + " reason=" + reason);
    }

    synchronized boolean benchmarkPreArmValid(long sessionGeneration) {
        return enabled && sessionGeneration > 0L
                && sessionGeneration == activeSessionGeneration && !preArmVisibilityLost;
    }

    synchronized void benchmarkScenarioCompleted(long sessionGeneration, int completedFrames,
            int expectedFrames, boolean completed, boolean sourceClosed, boolean audioDrained) {
        if (!enabled || sessionGeneration <= 0L || sessionGeneration != activeSessionGeneration
                || options.benchmarkScenario == DiagnosticsOptions.BenchmarkScenario.NONE) {
            return;
        }
        scenarioExpectedFrames = expectedFrames;
        scenarioCompletedFrames = completedFrames;
        scenarioSessionGeneration = sessionGeneration;
        scenarioCompleted = completed && completedFrames == expectedFrames
                && !preArmVisibilityLost;
        scenarioSourceClosed = sourceClosed;
        scenarioAudioDrained = audioDrained;
        record("event=scenario_complete artifact_id=" + artifactId
                + " pair_id=" + options.pairId
                + " matrix_block=" + options.matrixBlock
                + " row_order=" + options.rowOrder
                + " run_side=" + options.runSide.externalValue()
                + " session_generation=" + sessionGeneration
                + " input_contract=" + options.benchmarkScenario.externalValue()
                + " completed=" + scenarioCompleted
                + " completed_frames=" + completedFrames
                + " expected_frames=" + expectedFrames
                + " source_closed=" + sourceClosed
                + " audio_drained=" + audioDrained);
    }

    synchronized void emulationStarted(long sessionGeneration) {
        if (!enabled) {
            return;
        }
        if (sessionGeneration <= 0L || sessionGeneration != activeSessionGeneration
                || preArmVisibilityLost
                || !scenarioCompleted || !scenarioSourceClosed || !scenarioAudioDrained) {
            return;
        }
        long preparationOrigin = openNanos == 0L ? launchNanos : openNanos;
        preparationNanos = monotonicNanos.getAsLong();
        firstFrameNanos = 0L;
        previousIntervalNanos = 0L;
        previousIntervalFrame = 0L;
        readyFrames = 0L;
        submittedFrames = 0L;
        readyFirstNanos = 0L;
        readyLastNanos = 0L;
        submittedFirstNanos = 0L;
        submittedLastNanos = 0L;
        firstSubmittedId = 0L;
        lastSubmittedId = 0L;
        droppedFrames = 0L;
        duplicateFrames = 0L;
        lateFrames = 0L;
        corruptFrames = 0L;
        finalResultEmitted = false;
        benchmarkDrainSuccess = false;
        controllerThreadPriority = UNKNOWN;
        environmentStart = EnvironmentSample.unavailable();
        resetEnvironmentAggregate();
        controllerCpuStartNanos = 0L;
        controllerCpuLatestNanos = 0L;
        gcCountStart = 0L;
        gcTimeStart = 0L;
        allocBytesStart = 0L;
        emulationStarted = false;
        measurementArmed = false;
        warmupComplete = options.runtimeWarmup;
        // A successful disposable warmup is necessary but not sufficient: the host must still
        // post one real out-of-epoch buffer on this SurfaceView before taking SF's baseline.
        phase = Phase.WARMING;
        record("event=emulation_started wall_ns=" + preparationNanos
                + " session_generation=" + activeSessionGeneration
                + " prep_ms=" + elapsedMillis(preparationNanos, preparationOrigin)
                + " requested_hardware=" + options.hardware.externalValue()
                + " profile=" + (profile == null ? "unknown" : profile.id())
                + " effective_gbc=" + valueOrUnknown(effectiveGbc)
                + " effective_dmg_compat=" + valueOrUnknown(effectiveDmgCompat)
                + " effective_mode=" + effectiveMode.externalValue()
                + " speed_mode_initial=" + valueOrUnknown(effectiveSpeedMode)
                + " speed_mode_sample=boot_resolved"
                + " " + clockFields());
        record("event=warmup_complete completed=" + warmupComplete
                + " phase=" + phase.name().toLowerCase());
        openNanos = 0L;
    }

    /** Resets all counters whose zero point belongs to the host-arm token. */
    private void resetMeasurementWindow() {
        preparationNanos = monotonicNanos.getAsLong();
        firstFrameNanos = 0L;
        previousIntervalNanos = 0L;
        previousIntervalFrame = 0L;
        controllerCpuStartNanos = 0L;
        controllerCpuLatestNanos = 0L;
        controllerThreadPriority = UNKNOWN;
        gcCountStart = 0L;
        gcTimeStart = 0L;
        allocBytesStart = 0L;
        readyFrames = 0L;
        submittedFrames = 0L;
        readyFirstNanos = 0L;
        readyLastNanos = 0L;
        submittedFirstNanos = 0L;
        submittedLastNanos = 0L;
        firstSubmittedId = 0L;
        lastSubmittedId = 0L;
        droppedFrames = 0L;
        duplicateFrames = 0L;
        lateFrames = 0L;
        corruptFrames = 0L;
        finalResultEmitted = false;
        benchmarkDrainSuccess = false;
        speedFinalSample = false;
        speedModeFinal = UNKNOWN;
        audioTerminalStats = null;
        audioTerminalOutputIdentity = 0L;
        audioTerminalQueueIdentity = 0L;
        environmentStart = EnvironmentSample.unavailable();
        resetEnvironmentAggregate();
    }

    private void recordMatrixRun() {
        record("event=matrix_run artifact_id=" + artifactId
                + " pair_id=" + options.pairId
                + " matrix_block=" + options.matrixBlock
                + " row_order=" + options.rowOrder
                + " run_side=" + options.runSide.externalValue()
                + " first_side=" + options.firstSide.externalValue()
                + " session_generation=" + activeSessionGeneration
                + " benchmark_generation=" + benchmarkGeneration
                + " benchmark_token=" + benchmarkToken
                + " workload_nonce=" + workloadNonce
                + " warmup=" + warmupComplete
                + " input_contract=" + options.benchmarkScenario.externalValue()
                + " scenario_session_generation=" + scenarioSessionGeneration
                + " scenario_completed=" + scenarioCompleted
                + " scenario_completed_frames=" + scenarioCompletedFrames
                + " scenario_expected_frames=" + scenarioExpectedFrames
                + " scenario_source_closed=" + scenarioSourceClosed
                + " scenario_audio_drained=" + scenarioAudioDrained
                + " execution_mode=" + DiagnosticsOptions.executionModeValue(options.executionMode)
                + " thermal_window=" + options.thermalWindow
                + " audio=" + (options.audioOutput ? "on" : "off")
                + " render=" + (options.render == DiagnosticsOptions.Render.FRAME_SINK
                        ? "sink" : "presentation")
                + " availability=available"
                + " requested_hardware=" + options.hardware.externalValue()
                + " benchmark_audio_policy=" + options.audioPolicy.externalValue()
                + " surface_vote_hz=" + options.displayTargetHz
                + " display_target_hz=" + options.displayTargetHz
                + " surface_content_rate_millihz=" + options.surfaceContentRateMillihz
                + " " + matrixHardwareEvidenceFields()
                + " " + environmentStartFields()
                + " " + audioBaselineFields());
    }

    synchronized void setAudioSink(AndroidAudioSink audioSink) {
        this.audioSink = audioSink;
    }

    /** Test-only source for a stable read-only system-audio snapshot. */
    synchronized void setSystemAudioBaselineForTesting(AndroidAudioSink.AudioBaseline baseline) {
        this.systemAudioBaselineForTesting = baseline;
    }

    /** Counts one core frame-ready event after SGB transfer filtering. */
    synchronized boolean frameReady() {
        if (!enabled || !emulationStarted || !measurementArmed) {
            return false;
        }
        if (phase != Phase.ARMED) {
            // A physical frame after the arm-relative 600th boundary is an invalid producer
            // overrun. Do not let it extend the measured window or disappear silently.
            if (phase == Phase.CORE_FROZEN || phase == Phase.SUBMISSIONS_COMPLETE) {
                corruptFrames++;
            }
            return false;
        }
        long count = ++readyFrames;
        long current = monotonicNanos.getAsLong();
        readyLastNanos = current;
        if (count == 1L) {
            readyFirstNanos = current;
            firstFrameNanos = current;
            previousIntervalNanos = current;
            previousIntervalFrame = 1L;
        }
        if (count == 1L) {
            record("event=first_frame frame=1 wall_ns=" + current
                    + " since_launch_ms=" + elapsedMillis(current, launchNanos)
                    + " prep_to_frame_ms=" + elapsedMillis(current, preparationNanos));
        }
        if (count % INTERVAL_FRAMES == 0L && count <= FINAL_FRAME) {
            if (options.audioPolicy.isSilent()) {
                AndroidAudioSink.AudioBaseline checkpoint = systemAudioCheckpointBaselineLocked();
                sampleSystemAudioLocked((int) count, checkpoint);
                if (systemAudioBadLatched) {
                    return false;
                }
            }
            interval(current, count);
        }
        if (count == FINAL_FRAME) {
            // The renderer intentionally waits before posting the SF drain.  Freeze the audio
            // evidence now so that that delay (and any AudioTrack empty-poll underrun) cannot
            // contaminate the emulated 600-frame measurement window.
            AndroidAudioSink.AudioBaseline terminalBaseline = systemAudioCheckpointBaselineLocked();
            audioTerminalOutputIdentity = terminalBaseline.outputIdentity();
            audioTerminalQueueIdentity = terminalBaseline.queueIdentity();
            audioTerminalStats = audioSink == null ? null : audioSink.stats();
            // Freeze diagnostics immediately. The controller boundary event freezes the core
            // before its next chunk; submissions already in flight remain countable.
            phase = Phase.CORE_FROZEN;
            return true;
        }
        return false;
    }

    /** Compatibility alias for callers from the pre-M2 diagnostics seam. */
    void physicalFrame() {
        frameReady();
    }

    /** Called only after the Android Surface completes {@code unlockCanvasAndPost}. */
    synchronized void frameSubmitted(long submissionId) {
        if (!enabled || !emulationStarted || !measurementArmed) {
            return;
        }
        if (phase == Phase.SUBMISSIONS_COMPLETE) {
            duplicateFrames++;
            return;
        }
        if (phase != Phase.ARMED && phase != Phase.CORE_FROZEN) {
            return;
        }
        long current = monotonicNanos.getAsLong();
        if (submissionId <= 0L || submissionId <= lastSubmittedId) {
            duplicateFrames++;
            return;
        }
        // NativeFrameStore is the sole owner of discard accounting.  A submission-id gap is
        // useful evidence for duplicate/order validation, but counting it here would double
        // count the same takeLatest/reused-slot discard already reported by frameDropped().
        lastSubmittedId = submissionId;
        long count = ++submittedFrames;
        if (count == 1L) {
            firstSubmittedId = submissionId;
            submittedFirstNanos = current;
        }
        submittedLastNanos = current;
        if (count == FINAL_FRAME) {
            phase = Phase.SUBMISSIONS_COMPLETE;
        }
    }

    /** Completes the one post-window neutral drain after the 600th measured submission. */
    synchronized void benchmarkDrainPosted(boolean success) {
        if (!enabled || options.render != DiagnosticsOptions.Render.PRESENTATION
                || !measurementArmed || phase != Phase.SUBMISSIONS_COMPLETE
                || submittedFrames < FINAL_FRAME || finalResultEmitted) {
            return;
        }
        benchmarkDrainSuccess = success;
        if (!success) {
            // The drain is compositor-boundary evidence.  Keep the record analyzable, but make
            // the failure visible in the final counters so the host gate cannot accept it.
            corruptFrames++;
        }
        emitFinalResult();
    }

    synchronized void frameDropped() {
        if (enabled && emulationStarted && measurementArmed
                && (phase == Phase.ARMED || phase == Phase.CORE_FROZEN)) {
            droppedFrames++;
        }
    }

    synchronized void frameLate() {
        if (enabled && emulationStarted && measurementArmed
                && (phase == Phase.ARMED || phase == Phase.CORE_FROZEN)) {
            lateFrames++;
        }
    }

    synchronized void frameCorrupt() {
        if (enabled && emulationStarted && measurementArmed
                && (phase == Phase.ARMED || phase == Phase.CORE_FROZEN)) {
            corruptFrames++;
        }
    }

    synchronized void audioStats(AndroidAudioSink.AudioStats stats) {
        if (!enabled || stats == null) {
            return;
        }
        record("event=audio_output sample_rate=" + stats.sampleRate()
                + " min_buffer_bytes=" + stats.minimumBufferBytes()
                + " configured_buffer_bytes=" + stats.configuredBufferBytes()
                + " actual_buffer_bytes=" + stats.actualBufferBytes());
    }

    private void interval(long current, long frame) {
        // CPU/priority/environment are checkpoint telemetry; keep the per-frame path to owner
        // counters and timestamps so diagnostics do not perturb the measured lane.
        sampleControllerCpu();
        controllerThreadPriority = threadPriority();
        observeEnvironment(sampleEnvironment(controllerThreadPriority));
        long elapsed = Math.max(1L, current - firstFrameNanos);
        long intervalElapsed = Math.max(1L, current - previousIntervalNanos);
        long intervalFrames = frame - previousIntervalFrame;
        double fps = Math.max(0L, frame - 1L) * (double) NANOS_PER_SECOND / elapsed;
        double intervalFps = intervalFrames * (double) NANOS_PER_SECOND / intervalElapsed;
        long cpu = controllerCpuLatestNanos;
        long cpuBase = controllerCpuStartNanos;
        long cpuElapsed = Math.max(0L, cpu - cpuBase);
        double utilization = cpuElapsed * 100.0 / Math.max(1L, current - preparationNanos);
        record("event=frames frame=" + frame
                + " ready_count=" + readyFrames
                + " submitted_count=" + submittedFrames
                + " wall_ms=" + elapsedMillis(current, firstFrameNanos)
                + " wall_delta_ms=" + elapsedMillis(current, launchNanos)
                + " fps=" + format(fps)
                + " interval_fps=" + format(intervalFps)
                + " effective_mode=" + effectiveMode.externalValue()
                + " speed_mode_sample=boot_resolved"
                + " controller_cpu_ms=" + (cpuElapsed / NANOS_PER_MILLI)
                + " controller_util_pct=" + format(utilization)
                + " gc_count_delta=" + delta(globalGcCount(), gcCountStart)
                + " gc_time_ms_delta=" + delta(globalGcTime(), gcTimeStart)
                + " alloc_bytes_delta=" + delta(globalAllocBytes(), allocBytesStart));
        previousIntervalNanos = current;
        previousIntervalFrame = frame;
        // Frame-sink finalization is deferred to the physical frame-600 boundary; that callback
        // performs the terminal system-audio sample and speed-token binding.
    }

    private void emitFinalResult() {
        boolean terminal = options.render == DiagnosticsOptions.Render.FRAME_SINK
                ? readyFrames >= FINAL_FRAME : submittedFrames >= FINAL_FRAME;
        // Visible runs must not finalize merely because the 600th measured submission raced
        // ahead of the frame-600 speed callback.  The renderer's one delayed out-of-epoch drain
        // is the explicit latch that lets the host take a complete TimeStats after-dump.
        if (finalResultEmitted || systemAudioBadLatched || !terminal || !speedFinalSample
                || (options.render == DiagnosticsOptions.Render.PRESENTATION
                && !benchmarkDrainSuccess)) {
            return;
        }
        finalResultEmitted = true;
        double readyFps = intervalFps(readyFrames, readyFirstNanos, readyLastNanos);
        double submissionFps = intervalFps(
                submittedFrames, submittedFirstNanos, submittedLastNanos);
        long current = Math.max(readyLastNanos, submittedLastNanos);
        // This method runs on the renderer thread for visible output. Use only the latest
        // controller-thread snapshot captured by frameReady(); thread CPU clocks are per-thread.
        long cpu = controllerCpuLatestNanos;
        long cpuBase = controllerCpuStartNanos;
        long cpuElapsed = Math.max(0L, cpu - cpuBase);
        double utilization = cpuElapsed * 100.0 / Math.max(1L, current - preparationNanos);
        EnvironmentSample environmentEnd = sampleEnvironment(controllerThreadPriority);
        observeEnvironment(environmentEnd);
        record("event=final_result " + matrixIdentityFields()
                + " frame=600"
                + " ready_count=" + readyFrames + " submitted_count=" + submittedFrames
                + " dropped_count=" + droppedFrames + " duplicate_count=" + duplicateFrames
                + " late_count=" + lateFrames + " corrupt_count=" + corruptFrames
                + " ready_first_id=" + (readyFrames == 0L ? 0L : 1L)
                + " ready_last_id=" + readyFrames
                + " ready_first_ns=" + readyFirstNanos
                + " ready_last_ns=" + readyLastNanos
                + " submission_first_id=" + firstSubmittedId
                + " submission_last_id=" + lastSubmittedId
                + " submission_first_ns=" + submittedFirstNanos
                + " submission_last_ns=" + submittedLastNanos
                + " ready_interval_fps=" + formatExact(readyFps)
                + " submission_interval_fps=" + formatExact(submissionFps)
                + " wall_ms=" + elapsedMillis(current, firstFrameNanos)
                + " fps=" + format(submissionFps)
                + " " + finalHardwareEvidenceFields()
                + " " + environmentEndFields(environmentEnd)
                + " environment_sample_count=" + environmentSampleCount
                + " thermal_worst=" + thermalWorst
                + " system_load_worst_milli=" + systemLoadWorstMilli
                + " cpu_freq_min_khz=" + cpuFreqMinKHz
                + " " + audioEvidenceFields()
                + " system_audio_sample_count=" + systemAudioSampleCount
                + " system_audio_bad_count=" + systemAudioBadCount
                + " display_refresh_min_millihz=" + displayRefreshMinMillihz
                + " display_bad_count=" + displayBadCount
                + " interactive_bad_count=" + interactiveBadCount
                + " plugged_bad_count=" + pluggedBadCount
                + " power_save_bad_count=" + powerSaveBadCount
                + " stay_awake_bad_count=" + stayAwakeBadCount
                + " priority_bad_count=" + priorityBadCount
                + " importance_bad_count=" + importanceBadCount
                + " battery_temp_min=" + batteryTempMin
                + " battery_temp_max=" + batteryTempMax
                + " live_input_mutations=" + liveInputMutations
                + " audio_focus_granted=" + audioFocusGranted
                + " audio_focus_start_loss_count=" + audioFocusStartLossCount
                + " audio_focus_loss_count=" + audioFocusLossCount
                + " surface_vote_hz=" + options.displayTargetHz
                + " display_target_hz=" + options.displayTargetHz
                + " surface_content_rate_millihz=" + options.surfaceContentRateMillihz
                + " speed_mode_sample=frame_600"
                + " drain_success=" + benchmarkDrainSuccess
                + " controller_cpu_ms=" + (cpuElapsed / NANOS_PER_MILLI)
                + " controller_util_pct=" + format(utilization)
                + " gc_count_delta=" + delta(globalGcCount(), gcCountStart)
                + " gc_time_ms_delta=" + delta(globalGcTime(), gcTimeStart)
                + " alloc_bytes_delta=" + delta(globalAllocBytes(), allocBytesStart));
        phase = Phase.DONE;
        measurementArmed = false;
        emulationStarted = false;
    }

    private String matrixIdentityFields() {
        return "build_profile=" + BuildConfig.BUILD_TYPE
                + " artifact_id=" + artifactId + " pair_id=" + options.pairId
                + " matrix_block=" + options.matrixBlock + " row_order=" + options.rowOrder
                + " run_side=" + options.runSide.externalValue()
                + " session_generation=" + activeSessionGeneration
                + " benchmark_generation=" + benchmarkGeneration
                + " benchmark_token=" + benchmarkToken
                + " workload_nonce=" + workloadNonce
                + " warmup=" + warmupComplete
                + " input_contract=" + options.benchmarkScenario.externalValue()
                + " scenario_session_generation=" + scenarioSessionGeneration
                + " scenario_completed=" + scenarioCompleted
                + " scenario_completed_frames=" + scenarioCompletedFrames
                + " scenario_expected_frames=" + scenarioExpectedFrames
                + " scenario_source_closed=" + scenarioSourceClosed
                + " scenario_audio_drained=" + scenarioAudioDrained
                + " execution_mode=" + DiagnosticsOptions.executionModeValue(options.executionMode);
    }

    private String matrixHardwareEvidenceFields() {
        return "build_profile=" + BuildConfig.BUILD_TYPE
                + " requested_profile=" + requestedProfile()
                + " profile=" + (profile == null ? "unknown" : profile.id())
                + " effective_gbc=" + valueOrUnknown(effectiveGbc)
                + " effective_dmg_compat=" + valueOrUnknown(effectiveDmgCompat)
                + " effective_mode=" + effectiveMode.externalValue()
                + " device_id=" + deviceId
                + " speed_mode_initial=" + valueOrUnknown(effectiveSpeedMode)
                + " " + clockFields();
    }

    private String finalHardwareEvidenceFields() {
        // matrixIdentityFields already contributes the immutable build marker on final_result;
        // do not repeat build_profile here because the host parser rejects duplicate keys.
        return "requested_profile=" + requestedProfile()
                + " profile=" + (profile == null ? "unknown" : profile.id())
                + " effective_gbc=" + valueOrUnknown(effectiveGbc)
                + " effective_dmg_compat=" + valueOrUnknown(effectiveDmgCompat)
                + " effective_mode=" + effectiveMode.externalValue()
                + " device_id=" + deviceId
                + " speed_mode_initial=" + valueOrUnknown(effectiveSpeedMode)
                + " " + clockFields()
                + " speed_mode_final=" + speedModeFinal;
    }

    private String environmentStartFields() {
        return "thermal_start=" + environmentStart.thermalStatus
                + " battery_temp_start=" + environmentStart.batteryTemperatureDeciC
                + " display_refresh_start_millihz=" + environmentStart.displayRefreshMillihz
                + " display_state_start=" + environmentStart.displayState
                + " interactive_start=" + environmentStart.interactive
                + " plugged_start=" + environmentStart.plugged
                + " power_save_start=" + environmentStart.powerSave
                + " stay_awake_start=" + environmentStart.stayAwake
                + " stay_on_plugged_mask_start=" + environmentStart.stayOnPluggedMask
                + " thread_priority_start=" + environmentStart.threadPriority
                + " app_importance_start=" + environmentStart.appImportance
                + " system_load_start_milli=" + environmentStart.systemLoadMilli
                + " cpu_count_start=" + environmentStart.cpuCount
                + " memory_available_start_bytes=" + environmentStart.memoryAvailableBytes;
    }

    private static String environmentEndFields(EnvironmentSample environment) {
        return "thermal_end=" + environment.thermalStatus
                + " battery_temp_end=" + environment.batteryTemperatureDeciC
                + " display_refresh_end_millihz=" + environment.displayRefreshMillihz
                + " display_state_end=" + environment.displayState
                + " interactive_end=" + environment.interactive
                + " plugged_end=" + environment.plugged
                + " power_save_end=" + environment.powerSave
                + " stay_awake_end=" + environment.stayAwake
                + " stay_on_plugged_mask_end=" + environment.stayOnPluggedMask
                + " thread_priority_end=" + environment.threadPriority
                + " app_importance_end=" + environment.appImportance
                + " system_load_end_milli=" + environment.systemLoadMilli
                + " cpu_count_end=" + environment.cpuCount
                + " memory_available_end_bytes=" + environment.memoryAvailableBytes;
    }

    private String audioEvidenceFields() {
        AndroidAudioSink.Stats stats = audioTerminalStats != null
                ? audioTerminalStats : (audioSink == null ? null : audioSink.stats());
        if (stats == null) {
            return "audio_active=false audio_sample_rate=0 audio_overruns=-1"
                    + " audio_underruns=-1 audio_track_underruns=-1 audio_restarts=-1 audio_paused=true"
                    + " audio_min_buffer_bytes=0 audio_configured_buffer_bytes=0"
                    + " audio_actual_buffer_bytes=0 audio_pcm_input_events=0"
                    + " audio_pcm_input_frames=0 audio_pcm_enqueued_bytes=0"
                    + " audio_pcm_enqueued_frames=0 audio_pcm_written_bytes=0"
                    + " audio_pcm_written_frames=0 audio_write_failures=-1"
                    + " audio_pcm_discarded_bytes=0 audio_pcm_pending_bytes=0"
                    + " audio_pcm_queued_bytes=0 audio_queue_frames=0"
                    + " audio_output_open=false audio_output_playing=false audio_muted=true"
                    + " audio_volume=0 audio_route_failures=-1 audio_playback_position_frames=-1"
                    + " audio_system_volume=-1 audio_system_volume_max=-1"
                    + " audio_system_music_muted=true audio_queue_capacity_frames=0"
                    + " audio_max_frame_bytes=0"
                    + " audio_output_identity=" + audioTerminalOutputIdentity
                    + " audio_queue_identity=" + audioTerminalQueueIdentity
                    + " benchmark_audio_policy=" + benchmarkAudioPolicy.externalValue()
                    + " benchmark_audio_flags=" + compactAudioFlags()
                    + " benchmark_audio_calendar=" + compactAudioCalendar();
        }
        return "audio_active=" + stats.active()
                + " audio_sample_rate=" + stats.sampleRate()
                + " audio_overruns=" + stats.overruns()
                + " audio_underruns=" + stats.underruns()
                + " audio_track_underruns=" + stats.outputUnderruns()
                + " audio_restarts=" + stats.restarts()
                + " audio_paused=" + stats.paused()
                + " audio_min_buffer_bytes=" + stats.minimumBufferBytes()
                + " audio_configured_buffer_bytes=" + stats.configuredBufferBytes()
                + " audio_actual_buffer_bytes=" + stats.actualBufferBytes()
                + " audio_pcm_input_events=" + stats.pcmInputEvents()
                + " audio_pcm_input_frames=" + stats.pcmInputFrames()
                + " audio_pcm_enqueued_bytes=" + stats.pcmEnqueuedBytes()
                + " audio_pcm_enqueued_frames=" + stats.pcmEnqueuedFrames()
                + " audio_pcm_written_bytes=" + stats.pcmWrittenBytes()
                + " audio_pcm_written_frames=" + stats.pcmWrittenFrames()
                + " audio_write_failures=" + stats.writeFailures()
                + " audio_pcm_discarded_bytes=" + stats.pcmDiscardedBytes()
                + " audio_pcm_pending_bytes=" + stats.pcmPendingBytes()
                + " audio_pcm_queued_bytes=" + stats.pcmQueuedBytes()
                + " audio_queue_frames=" + stats.queuedFrames()
                + " audio_output_open=" + stats.outputOpen()
                + " audio_output_playing=" + stats.outputPlaying()
                + " audio_muted=" + stats.muted()
                + " audio_volume=" + stats.volume()
                + " audio_route_failures=" + stats.routeFailures()
                + " audio_playback_position_frames=" + stats.playbackPositionFrames()
                + " audio_system_volume=" + stats.systemVolume()
                + " audio_system_volume_max=" + stats.systemVolumeMax()
                + " audio_system_music_muted=" + stats.systemMusicMuted()
                + " audio_queue_capacity_frames=" + stats.queueCapacityFrames()
                + " audio_max_frame_bytes=" + stats.maximumFrameBytes()
                + " audio_output_identity=" + audioTerminalOutputIdentity
                + " audio_queue_identity=" + audioTerminalQueueIdentity
                + " benchmark_audio_policy=" + benchmarkAudioPolicy.externalValue()
                + " benchmark_audio_flags=" + compactAudioFlags()
                + " benchmark_audio_calendar=" + compactAudioCalendar();
    }

    /**
     * Compact final-result policy flags in requested, active-at-boundary, disabled-after order.
     * The speed_sample event intentionally keeps the expanded policy schema for host diagnostics.
     */
    private String compactAudioFlags() {
        return (benchmarkAudioRequested ? "1" : "0")
                + (benchmarkAudioActiveAtBoundary ? "1" : "0")
                + (benchmarkAudioDisabledAfterBoundary ? "1" : "0");
    }

    /** Compact final-result calendar in skipped, zero-slots, zero-events, debt, APU reads,
     * APU writes, frame-sequencer commits, dropped-channel-ticks order. */
    private String compactAudioCalendar() {
        return benchmarkAudioSkippedTicks + "," + benchmarkAudioZeroSampleSlots + ","
                + benchmarkAudioZeroSampleEvents + "," + benchmarkAudioMaxDebt + ","
                + benchmarkAudioApuReads + "," + benchmarkAudioApuWrites + ","
                + benchmarkAudioFrameSequencerCommits + ","
                + benchmarkAudioDroppedChannelTicks;
    }

    private String audioBaselineFields() {
        AndroidAudioSink.AudioBaseline baseline = audioBaseline;
        return "audio_start_input_events=" + baseline.inputEvents()
                + " audio_start_input_frames=" + baseline.inputFrames()
                + " audio_start_enqueued_bytes=" + baseline.enqueuedBytes()
                + " audio_start_enqueued_frames=" + baseline.enqueuedFrames()
                + " audio_start_written_bytes=" + baseline.writtenBytes()
                + " audio_start_written_frames=" + baseline.writtenFrames()
                + " audio_start_write_failures=" + baseline.writeFailures()
                + " audio_start_discarded_bytes=" + baseline.discardedBytes()
                + " audio_start_pending_bytes=" + baseline.pendingBytes()
                + " audio_start_queued_bytes=" + baseline.queuedBytes()
                + " audio_start_playback_position_frames=" + baseline.playbackPositionFrames()
                + " audio_start_overruns=" + baseline.overruns()
                + " audio_start_underruns=" + baseline.underruns()
                + " audio_start_track_underruns=" + baseline.outputUnderruns()
                + " audio_start_restarts=" + baseline.restarts()
                + " audio_start_route_failures=" + baseline.routeFailures()
                + " audio_start_output_open=" + baseline.outputOpen()
                + " audio_start_output_playing=" + baseline.outputPlaying()
                + " audio_start_sample_rate=" + baseline.sampleRate()
                + " audio_start_queue_capacity_frames=" + baseline.queueCapacityFrames()
                + " audio_start_max_frame_bytes=" + baseline.maximumFrameBytes()
                + " audio_start_active=" + baseline.active()
                + " audio_start_paused=" + baseline.paused()
                + " audio_start_muted=" + baseline.muted()
                + " audio_start_volume=" + baseline.volume()
                + " audio_start_system_volume=" + baseline.systemVolume()
                + " audio_start_system_volume_max=" + baseline.systemVolumeMax()
                + " audio_start_system_music_muted=" + baseline.systemMusicMuted()
                + " audio_start_queued_frames=" + baseline.queuedFrames()
                + " audio_start_reopen_pending=" + baseline.reopenPending()
                + " audio_start_output_identity=" + baseline.outputIdentity()
                + " audio_start_queue_identity=" + baseline.queueIdentity();
    }

    private EnvironmentSample sampleEnvironment(int observedThreadPriority) {
        if (!enabled || context == null) {
            return EnvironmentSample.unavailable();
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        int thermal = Build.VERSION.SDK_INT >= 29 && power != null
                ? power.getCurrentThermalStatus() : UNKNOWN;
        boolean interactive = power != null && power.isInteractive();
        boolean powerSave = power != null && power.isPowerSaveMode();

        Intent battery = null;
        try {
            battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (RuntimeException ignored) {
            // Keep the sample explicitly unavailable; the parser will reject visible runs.
        }
        int batteryTemperature = battery == null ? UNKNOWN
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN);
        int plugged = battery == null ? UNKNOWN
                : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, UNKNOWN);
        int refreshMillihz = UNKNOWN;
        int displayState = UNKNOWN;
        try {
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                refreshMillihz = Math.round(display.getRefreshRate() * 1000.0f);
                displayState = display.getState();
            }
        } catch (RuntimeException ignored) {
                // Keep the sample explicitly unavailable.
        }
        int stayOnPluggedMask = stayOnPluggedMask(context);
        boolean displayIsOn = displayState == Display.STATE_ON;
        boolean stayAwake = interactive && displayIsOn && plugged > 0
                && stayOnPluggedMask >= 0 && (stayOnPluggedMask & plugged) != 0;
        return new EnvironmentSample(thermal, batteryTemperature, refreshMillihz, displayState,
                interactive, plugged, powerSave, stayAwake, stayOnPluggedMask,
                observedThreadPriority, appImportance(), loadAverageMilli(),
                availableMemoryBytes(context), Runtime.getRuntime().availableProcessors(),
                cpuFrequencyKHz());
    }

    private void resetEnvironmentAggregate() {
        environmentSampleCount = 0;
        thermalWorst = UNKNOWN;
        systemLoadWorstMilli = UNKNOWN;
        cpuFreqMinKHz = UNKNOWN;
        displayRefreshMinMillihz = UNKNOWN;
        displayBadCount = 0;
        interactiveBadCount = 0;
        pluggedBadCount = 0;
        powerSaveBadCount = 0;
        stayAwakeBadCount = 0;
        priorityBadCount = 0;
        importanceBadCount = 0;
        batteryTempMin = UNKNOWN;
        batteryTempMax = UNKNOWN;
    }

    private void observeEnvironment(EnvironmentSample sample) {
        if (sample == null) {
            return;
        }
        environmentSampleCount++;
        if (sample.thermalStatus >= 0) {
            thermalWorst = thermalWorst < 0
                    ? sample.thermalStatus : Math.max(thermalWorst, sample.thermalStatus);
        }
        if (sample.systemLoadMilli >= 0) {
            systemLoadWorstMilli = systemLoadWorstMilli < 0
                    ? sample.systemLoadMilli
                    : Math.max(systemLoadWorstMilli, sample.systemLoadMilli);
        }
        if (sample.cpuFrequencyKHz > 0) {
            cpuFreqMinKHz = cpuFreqMinKHz < 0
                    ? sample.cpuFrequencyKHz
                    : Math.min(cpuFreqMinKHz, sample.cpuFrequencyKHz);
        }
        if (sample.displayRefreshMillihz > 0) {
            displayRefreshMinMillihz = displayRefreshMinMillihz < 0
                    ? sample.displayRefreshMillihz
                    : Math.min(displayRefreshMinMillihz, sample.displayRefreshMillihz);
        } else {
            displayBadCount++;
        }
        if (!sample.interactive) {
            interactiveBadCount++;
        }
        if (sample.plugged <= 0) {
            pluggedBadCount++;
        }
        if (sample.powerSave) {
            powerSaveBadCount++;
        }
        if (!sample.stayAwake) {
            stayAwakeBadCount++;
        }
        if (sample.threadPriority != expectedThreadPriority()) {
            priorityBadCount++;
        }
        if (sample.appImportance != 100) {
            importanceBadCount++;
        }
        if (validBatteryTemperature(sample.batteryTemperatureDeciC)) {
            batteryTempMin = batteryTempMin < 0
                    ? sample.batteryTemperatureDeciC
                    : Math.min(batteryTempMin, sample.batteryTemperatureDeciC);
            batteryTempMax = batteryTempMax < 0
                    ? sample.batteryTemperatureDeciC
                    : Math.max(batteryTempMax, sample.batteryTemperatureDeciC);
        } else {
            batteryTempMin = UNKNOWN;
            batteryTempMax = UNKNOWN;
        }
    }

    private static boolean validBatteryTemperature(int deciCelsius) {
        return deciCelsius >= 0 && deciCelsius <= 400;
    }

    private int expectedThreadPriority() {
        return options.executionMode == ExecutionMode.PERFORMANCE
                ? AndroidPerformanceBoost.PERFORMANCE_THREAD_PRIORITY : 0;
    }

    private static int stayOnPluggedMask(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN, UNKNOWN);
        } catch (RuntimeException ignored) {
            return UNKNOWN;
        }
    }

    private static int threadPriority() {
        try {
            return Process.getThreadPriority(Process.myTid());
        } catch (RuntimeException unavailable) {
            return UNKNOWN;
        }
    }

    private static int appImportance() {
        try {
            ActivityManager.RunningAppProcessInfo process =
                    new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(process);
            return process.importance;
        } catch (RuntimeException unavailable) {
            return UNKNOWN;
        }
    }

    private static long availableMemoryBytes(Context context) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (manager != null) {
                manager.getMemoryInfo(memory);
                return memory.availMem > 0L ? memory.availMem : UNKNOWN;
            }
        } catch (RuntimeException ignored) {
            // Keep the sample explicitly unavailable; the parser will reject visible runs.
        }
        return UNKNOWN;
    }

    /** Reads only Android's fixed load-average virtual file; no user-selected path is accepted. */
    private static int loadAverageMilli() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"))) {
            String first = reader.readLine();
            if (first == null) {
                return UNKNOWN;
            }
            String token = first.trim().split("\\s+")[0];
            double load = Double.parseDouble(token);
            if (Double.isFinite(load) && load >= 0.0 && load <= 1000.0) {
                return (int) Math.round(load * 1000.0);
            }
        } catch (Exception unavailable) {
            // Keep the sample explicitly unavailable; the parser will reject visible runs.
        }
        return UNKNOWN;
    }

    /** Reads only the fixed primary-CPU frequency node; no user-selected path is accepted. */
    private static int cpuFrequencyKHz() {
        try (BufferedReader reader = new BufferedReader(new FileReader(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))) {
            String value = reader.readLine();
            int frequency = value == null ? UNKNOWN : Integer.parseInt(value.trim());
            return frequency > 0 && frequency <= 10_000_000 ? frequency : UNKNOWN;
        } catch (Exception unavailable) {
            return UNKNOWN;
        }
    }

    private static double intervalFps(long count, long first, long last) {
        if (count < 2L || first <= 0L || last <= first) {
            return 0.0;
        }
        return (count - 1L) * (double) NANOS_PER_SECOND / (last - first);
    }

    private static long systemNow() {
        return SystemClock.elapsedRealtimeNanos();
    }

    private static long threadCpuNanos() {
        try {
            return Debug.threadCpuTimeNanos();
        } catch (RuntimeException unavailable) {
            return 0L;
        }
    }

    private void sampleControllerCpu() {
        long sample = threadCpuNanos();
        if (sample > controllerCpuLatestNanos) {
            controllerCpuLatestNanos = sample;
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

    private static String formatExact(double value) {
        return String.format(java.util.Locale.ROOT, "%.9f", value);
    }

    private String requestedProfile() {
        HardwareProfile requested = options.hardware.profileOverride();
        return requested == null ? "auto" : requested.id();
    }

    private static String valueOrUnknown(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private String clockFields() {
        if (effectiveClock == null) {
            return "clock_ticks_num=unknown clock_ticks_den=unknown"
                    + " clock_frames_num=unknown clock_frames_den=unknown"
                    + " clock_ticks_frame=unknown";
        }
        return "clock_ticks_num=" + effectiveClock.ticksPerSecondNumerator()
                + " clock_ticks_den=" + effectiveClock.ticksPerSecondDenominator()
                + " clock_frames_num=" + effectiveClock.controllerFramesPerSecondNumerator()
                + " clock_frames_den=" + effectiveClock.controllerFramesPerSecondDenominator()
                + " clock_ticks_frame=" + effectiveClock.controllerTicksPerFrame();
    }

    private void record(String message) {
        int encodedBytes = message.getBytes(StandardCharsets.UTF_8).length;
        if (encodedBytes > MAX_LOG_RECORD_BYTES) {
            throw new IllegalStateException(
                    "Benchmark telemetry record exceeds bounded Android payload: "
                            + encodedBytes + " bytes");
        }
        recordSink.write(message);
    }

    private static void logRecord(String message) {
        Log.i(TAG, message);
    }

    private static String sha256File(String path) {
        if (path == null || path.isBlank()) {
            return "unavailable";
        }
        try (InputStream input = new FileInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length > 0) {
                    digest.update(buffer, 0, length);
                }
            }
            return hex(digest.digest());
        } catch (Exception unavailable) {
            return "unavailable";
        }
    }

    private static String sha256Text(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            return "unavailable";
        }
    }

    /** Hashes a per-install Android identifier with stable device characteristics; never logs raw values. */
    private static String deviceIdentity(Context context) {
        if (context == null) {
            return "unavailable";
        }
        String androidId;
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
        } catch (RuntimeException unavailable) {
            return "unavailable";
        }
        if (androidId == null || androidId.isBlank()) {
            return "unavailable";
        }
        String raw = androidId + "\u0000" + Build.FINGERPRINT + "\u0000" + Build.MODEL
                + "\u0000" + Build.DEVICE + "\u0000" + Build.HARDWARE + "\u0000"
                + Arrays.toString(Build.SUPPORTED_ABIS);
        return sha256Text(raw);
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return text.toString();
    }

    private record EnvironmentSample(int thermalStatus, int batteryTemperatureDeciC,
            int displayRefreshMillihz, int displayState, boolean interactive, int plugged,
            boolean powerSave, boolean stayAwake, int stayOnPluggedMask, int threadPriority,
            int appImportance, int systemLoadMilli, long memoryAvailableBytes, int cpuCount,
            int cpuFrequencyKHz) {
        static EnvironmentSample unavailable() {
            return new EnvironmentSample(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN,
                    false, UNKNOWN, true, false, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN,
                    UNKNOWN, UNKNOWN);
        }
    }
}
