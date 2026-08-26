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
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapOutcome;
import eu.rekawek.coffeegb.core.Gameboy.PerformanceTelemetrySnapshot;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileIdentity;

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
    /** Authoritative terminal bootstrap metadata for the active benchmark generation. */
    private boolean bootstrapReady;
    private long bootstrapReadySessionGeneration;
    private BootstrapMode requestedBootstrapMode = BootstrapMode.FAST_FORWARD;
    private BootstrapOutcome bootstrapOutcome = BootstrapOutcome.PENDING;
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
    /** True only after a validated core_result has been emitted for this measurement window. */
    private boolean coreResultEmitted;
    private String coreResultId = "unknown";

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
    synchronized boolean setWorkloadNonce(String nonce) {
        // Goal-matrix nonces are app-owned values read from the selected recent catalog entry.
        // Host intent metadata may not seed or rewrite them.  Binding is one-shot for a session;
        // a replacement must start a new diagnostics session before another nonce is accepted.
        if (!enabled || nonce == null) {
            return false;
        }
        String normalized = nonce.trim().toLowerCase(java.util.Locale.ROOT);
        if (goalMatrixConfigured()) {
            if (!normalized.matches("[a-z0-9][a-z0-9._-]{15,63}")
                    || "unknown".equals(normalized) || "invalid".equals(normalized)
                    || !"unknown".equals(workloadNonce)) {
                return false;
            }
            workloadNonce = normalized;
            return true;
        }
        if (normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            workloadNonce = normalized;
            return true;
        }
        return false;
    }

    /** True for both complete and deliberately incomplete goal-matrix workload contracts. */
    private boolean benchmarkScenarioConfigured() {
        return options.workloadSlot != null
                || options.benchmarkScenario != DiagnosticsOptions.BenchmarkScenario.NONE;
    }

    private boolean goalMatrixConfigured() {
        return options.goalMatrixContract();
    }

    private String scenarioId() {
        if (options.workloadTimeline != null) {
            return options.workloadTimeline.id();
        }
        return options.benchmarkScenario.externalValue();
    }

    private int scenarioCount() {
        if (options.workloadTimeline != null) {
            return options.workloadTimeline.endpointFrame();
        }
        switch (options.benchmarkScenario) {
            case DMG_ACTION_V1:
                return 313;
            case CGB_ACTION_V1:
                return 923;
            default:
                return 0;
        }
    }

    private String expectedProfile() {
        if (options.cellId != null) {
            BenchmarkWorkload.Cell cell = BenchmarkWorkload.Cell.fromExternalValue(options.cellId);
            if (cell != null) {
                return cell.effectiveProfile().externalValue();
            }
        }
        return "unknown";
    }

    /**
     * Maps the core's revision-specific effective-mode labels onto the four goal-matrix profile
     * tokens.  In particular, both CGB revision compatibility labels are one canonical
     * {@code cgb-compat} profile in the matrix; leaving the internal {@code cgb-dmg-compat}
     * spelling on the wire would make a valid D compatibility cell unparsable.
     */
    private String effectiveProfile() {
        return effectiveProfileFor(effectiveMode);
    }

    private String effectiveProfileFor(DiagnosticsOptions.EffectiveMode mode) {
        if (!goalMatrixConfigured()) {
            return mode.externalValue();
        }
        switch (mode) {
            case DMG:
            case MGB:
                return BenchmarkWorkload.EffectiveProfile.DMG.externalValue();
            case CGB_NATIVE:
            case CGB0_NATIVE:
                return BenchmarkWorkload.EffectiveProfile.CGB_NATIVE.externalValue();
            case CGB_DMG_COMPAT:
            case CGB0_DMG_COMPAT:
                return BenchmarkWorkload.EffectiveProfile.CGB_COMPAT.externalValue();
            case SGB:
            case SGB2:
                return BenchmarkWorkload.EffectiveProfile.SGB.externalValue();
            default:
                return effectiveMode.externalValue();
        }
    }

    private String workloadMatrixFields() {
        return "matrix_version=" + options.matrixVersion
                + " cell_id=" + options.cellId
                + " workload_slot=" + (options.workloadSlot == null
                        ? "unknown" : options.workloadSlot.externalValue())
                + " recent_slot=" + options.recentSlot
                + " scenario_id=" + scenarioId()
                + " scenario_count=" + scenarioCount()
                + " expected_profile=" + expectedProfile()
                + " effective_profile=" + effectiveProfile();
    }

    /** Minimal identity shared by strict goal-matrix records; no artifact or cartridge metadata. */
    private String goalMatrixIdentityFields() {
        return "matrix_version=" + options.matrixVersion
                + " cell_id=" + options.cellId
                + " workload_slot=" + options.workloadSlot.externalValue()
                + " workload_nonce=" + workloadNonce
                + " scenario_id=" + scenarioId()
                + " scenario_count=" + scenarioCount()
                + " expected_profile=" + expectedProfile()
                + " effective_profile=" + effectiveProfile()
                + " requested_hardware=" + options.hardware.externalValue()
                + " execution_mode=" + DiagnosticsOptions.executionModeValue(options.executionMode)
                + " pair_id=" + options.pairId
                + " matrix_block=" + options.matrixBlock
                + " row_order=" + options.rowOrder
                + " recent_slot=" + options.recentSlot
                + " run_side=" + options.runSide.externalValue()
                + " session_generation=" + activeSessionGeneration;
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
        if (goalMatrixConfigured()) {
            PerformanceTelemetrySnapshot snapshot = event.getCoreResult();
            if (!validCoreResult(snapshot)) {
                // A goal run cannot be accepted on host-side speed/epoch evidence alone.  The
                // controller's immutable frame-600 snapshot is the measured core_result source;
                // missing or inconsistent partitions suppress both core_result and final_result.
                measurementArmed = false;
                phase = Phase.CORE_FROZEN;
                return;
            }
            coreResultId = goalCoreResultId();
            recordCoreResult(snapshot, coreResultId);
            coreResultEmitted = true;
        }
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

    private boolean validCoreResult(PerformanceTelemetrySnapshot snapshot) {
        if (snapshot == null
                || snapshot.getSchedulerMasterTicks() != 42_134_400L
                || !sumsTo(42_134_400L, snapshot.getSchedulerScalarTicks(),
                snapshot.getSchedulerPhaseTicks(), snapshot.getSchedulerHaltTicks(),
                snapshot.getSchedulerEpochTicks())
                || !sumsTo(42_134_400L, snapshot.getSchedulerSpeed1Ticks(),
                snapshot.getSchedulerSpeed2Ticks(), snapshot.getSchedulerSpeedSwitchTicks())) {
            return false;
        }
        // Only native CGB cells may enter double speed.  A legacy, compatibility, or SGB
        // result that merely balances its speed counters can otherwise masquerade as the
        // requested hardware profile while still summing to the measured tick budget.
        if (!BenchmarkWorkload.EffectiveProfile.CGB_NATIVE.externalValue().equals(expectedProfile())
                && (snapshot.getSchedulerSpeed1Ticks() != 42_134_400L
                || snapshot.getSchedulerSpeed2Ticks() != 0L
                || snapshot.getSchedulerSpeedSwitchTicks() != 0L)) {
            return false;
        }
        long packetCount = safeAdd(snapshot.getSchedulerPhaseCount(),
                snapshot.getSchedulerHaltCount());
        if (packetCount < 0L) {
            return false;
        }
        packetCount = safeAdd(packetCount, snapshot.getSchedulerEpochCount());
        long packetTicks = snapshot.getSchedulerScalarTicks() >= 0L
                && snapshot.getSchedulerScalarTicks() <= 42_134_400L
                ? 42_134_400L - snapshot.getSchedulerScalarTicks() : -1L;
        if (packetCount < 0L || packetTicks < 0L || packetCount > packetTicks) {
            return false;
        }
        if (!sumsTo(packetCount, snapshot.getSchedulerLengthBucket0(),
                snapshot.getSchedulerLengthBucket1(), snapshot.getSchedulerLengthBucket2(),
                snapshot.getSchedulerLengthBucket3(), snapshot.getSchedulerLengthBucket4())) {
            return false;
        }
        if (!validPacketClass(snapshot.getSchedulerPhaseCount(),
                snapshot.getSchedulerPhaseTicks(), snapshot.getSchedulerPhaseMaxTicks())
                || !validPacketClass(snapshot.getSchedulerHaltCount(),
                snapshot.getSchedulerHaltTicks(), snapshot.getSchedulerHaltMaxTicks())
                || !validPacketClass(snapshot.getSchedulerEpochCount(),
                snapshot.getSchedulerEpochTicks(), snapshot.getSchedulerEpochMaxTicks())) {
            return false;
        }
        long weightedMinimum = weightedPacketTicks(snapshot.getSchedulerLengthBucket0(),
                snapshot.getSchedulerLengthBucket1(), snapshot.getSchedulerLengthBucket2(),
                snapshot.getSchedulerLengthBucket3(), snapshot.getSchedulerLengthBucket4(), false);
        long weightedMaximum = weightedPacketTicks(snapshot.getSchedulerLengthBucket0(),
                snapshot.getSchedulerLengthBucket1(), snapshot.getSchedulerLengthBucket2(),
                snapshot.getSchedulerLengthBucket3(), snapshot.getSchedulerLengthBucket4(), true);
        if (weightedMinimum < 0L || weightedMaximum < 0L
                || packetTicks < weightedMinimum || packetTicks > weightedMaximum
                || !sumsTo(snapshot.getSchedulerEpochTicks(), snapshot.getSchedulerPpuDirectTicks(),
                snapshot.getSchedulerPpuFallbackTicks(), snapshot.getSchedulerPpuFastTicks())) {
            return false;
        }
        long expectedAudioSlots = "sgb".equals(expectedProfile()) ? 3_830_400L : 766_080L;
        if (snapshot.getSchedulerAudioBlockTicks() != 42_134_400L
                || snapshot.getSchedulerAudioSampleTicks() != expectedAudioSlots
                || snapshot.getSchedulerAudioMaterializations() <= 0L) {
            return false;
        }
        if ("sgb".equals(expectedProfile())) {
            // The parent is an explicit one-array-per-frame control artifact; the candidate is
            // the leased-buffer implementation.  Binding the expected count to the authenticated
            // side prevents an identical optimized APK pair from posing as the allocation A/B.
            long expectedAllocations = options.runSide == DiagnosticsOptions.RunSide.PARENT
                    ? FINAL_FRAME : 0L;
            return snapshot.getSchedulerSgbFrameArrayAllocations() == expectedAllocations
                    && snapshot.getSchedulerSgbBorderRebuilds() >= 0L
                    && snapshot.getSchedulerSgbBorderRebuilds() <= FINAL_FRAME
                    && snapshot.getSchedulerSgbCenterPixels() == 13_824_000L;
        }
        return snapshot.getSchedulerSgbFrameArrayAllocations() == 0L
                && snapshot.getSchedulerSgbBorderRebuilds() == 0L
                && snapshot.getSchedulerSgbCenterPixels() == 0L;
    }

    private static boolean sumsTo(long expected, long... values) {
        if (expected < 0L) {
            return false;
        }
        long remaining = expected;
        for (long value : values) {
            if (value < 0L || value > remaining) {
                return false;
            }
            remaining -= value;
        }
        return remaining == 0L;
    }

    private static long safeAdd(long first, long second) {
        return first < 0L || second < 0L || first > Long.MAX_VALUE - second
                ? -1L : first + second;
    }

    private static boolean validPacketClass(long count, long ticks, long max) {
        if (count < 0L || ticks < 0L || max < 0L || max > 54L) {
            return false;
        }
        if (count == 0L) {
            return ticks == 0L && max == 0L;
        }
        return ticks >= count && max >= 1L && max <= ticks;
    }

    private static long weightedPacketTicks(long bucket0, long bucket1, long bucket2,
            long bucket3, long bucket4, boolean maximum) {
        long[] weights = maximum ? new long[]{1L, 3L, 7L, 15L, 54L}
                : new long[]{1L, 2L, 4L, 8L, 16L};
        long[] buckets = {bucket0, bucket1, bucket2, bucket3, bucket4};
        long result = 0L;
        for (int index = 0; index < buckets.length; index++) {
            long bucket = buckets[index];
            if (bucket < 0L || bucket > Long.MAX_VALUE / weights[index]) {
                return -1L;
            }
            long contribution = bucket * weights[index];
            if (result > Long.MAX_VALUE - contribution) {
                return -1L;
            }
            result += contribution;
        }
        return result;
    }

    private void recordCoreResult(PerformanceTelemetrySnapshot snapshot, String resultId) {
        StringBuilder result = new StringBuilder("event=core_result ");
        result.append(goalMatrixIdentityFields())
                .append(" core_result_id=").append(resultId)
                .append(" frame=600")
                .append(" scheduler_master_ticks=").append(snapshot.getSchedulerMasterTicks())
                .append(" scheduler_scalar_ticks=").append(snapshot.getSchedulerScalarTicks())
                .append(" scheduler_phase_count=").append(snapshot.getSchedulerPhaseCount())
                .append(" scheduler_phase_ticks=").append(snapshot.getSchedulerPhaseTicks())
                .append(" scheduler_phase_max_ticks=").append(snapshot.getSchedulerPhaseMaxTicks())
                .append(" scheduler_halt_count=").append(snapshot.getSchedulerHaltCount())
                .append(" scheduler_halt_ticks=").append(snapshot.getSchedulerHaltTicks())
                .append(" scheduler_halt_max_ticks=").append(snapshot.getSchedulerHaltMaxTicks())
                .append(" scheduler_epoch_count=").append(snapshot.getSchedulerEpochCount())
                .append(" scheduler_epoch_ticks=").append(snapshot.getSchedulerEpochTicks())
                .append(" scheduler_epoch_max_ticks=").append(snapshot.getSchedulerEpochMaxTicks())
                .append(" scheduler_length_bucket_0=").append(snapshot.getSchedulerLengthBucket0())
                .append(" scheduler_length_bucket_1=").append(snapshot.getSchedulerLengthBucket1())
                .append(" scheduler_length_bucket_2=").append(snapshot.getSchedulerLengthBucket2())
                .append(" scheduler_length_bucket_3=").append(snapshot.getSchedulerLengthBucket3())
                .append(" scheduler_length_bucket_4=").append(snapshot.getSchedulerLengthBucket4())
                .append(" scheduler_speed1_ticks=").append(snapshot.getSchedulerSpeed1Ticks())
                .append(" scheduler_speed2_ticks=").append(snapshot.getSchedulerSpeed2Ticks())
                .append(" scheduler_speed_switch_ticks=")
                .append(snapshot.getSchedulerSpeedSwitchTicks())
                .append(" scheduler_ppu_direct_ticks=")
                .append(snapshot.getSchedulerPpuDirectTicks())
                .append(" scheduler_ppu_fallback_ticks=")
                .append(snapshot.getSchedulerPpuFallbackTicks())
                .append(" scheduler_ppu_fast_ticks=")
                .append(snapshot.getSchedulerPpuFastTicks())
                .append(" scheduler_cpu_safe_accesses=").append(snapshot.getSchedulerCpuSafeTicks())
                .append(" scheduler_cpu_direct_rom_reads=")
                .append(snapshot.getSchedulerCpuDirectRomTicks())
                .append(" scheduler_cpu_terminal_reads=")
                .append(snapshot.getSchedulerCpuTerminalReadTicks())
                .append(" scheduler_cpu_terminal_writes=")
                .append(snapshot.getSchedulerCpuTerminalWriteTicks())
                .append(" scheduler_audio_skipped_ticks=")
                .append(snapshot.getSchedulerAudioBlockTicks())
                .append(" scheduler_audio_zero_sample_slots=")
                .append(snapshot.getSchedulerAudioSampleTicks())
                .append(" scheduler_audio_materializations=")
                .append(snapshot.getSchedulerAudioMaterializations())
                .append(" scheduler_sgb_frame_array_allocations=")
                .append(snapshot.getSchedulerSgbFrameArrayAllocations())
                .append(" scheduler_sgb_border_rebuilds=")
                .append(snapshot.getSchedulerSgbBorderRebuilds())
                .append(" scheduler_sgb_center_pixels=")
                .append(snapshot.getSchedulerSgbCenterPixels());
        record(result.toString());
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
        coreResultEmitted = false;
        coreResultId = "unknown";
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
        // Preserve an explicitly host-assigned nonce; a recent-catalog launch may replace it
        // with the persisted slot nonce immediately before materialization.
        workloadNonce = options.workloadNonce;
        liveInputMutations = 0L;
        profile = null;
        effectiveGbc = null;
        effectiveDmgCompat = null;
        effectiveSpeedMode = null;
        effectiveMode = DiagnosticsOptions.EffectiveMode.UNKNOWN;
        effectiveClock = null;
        emulationStarted = false;
        bootstrapReady = false;
        bootstrapReadySessionGeneration = 0L;
        requestedBootstrapMode = options.bootstrapMode;
        bootstrapOutcome = BootstrapOutcome.PENDING;
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
                + " " + workloadMatrixFields()
                + " speed_mode_initial=" + valueOrUnknown(effectiveSpeedMode)
                + " speed_mode_sample=boot_resolved"
                + " " + clockFields());
    }

    /**
     * Accepts the generation-bound terminal bootstrap transaction.  The early hardware-profile
     * event is useful for frame routing, but it is not allowed to authorize a benchmark.  This
     * method is the only diagnostics seam that promotes a benchmark session to an authoritative
     * post-bootstrap hardware/effective-mode identity.
     */
    synchronized boolean acceptBootstrapReady(Controller.BootstrapReadyEvent next) {
        if (!enabled || next == null) {
            return false;
        }
        String reason = bootstrapRejectionReason(next);
        if (reason != null) {
            if (goalMatrixConfigured()) {
                recordBootstrapResult(next, false, reason);
            }
            return false;
        }
        profile = next.getProfile();
        effectiveGbc = next.getEffectiveGbc();
        effectiveDmgCompat = next.getEffectiveDmgCompat();
        effectiveSpeedMode = next.getEffectiveSpeedMode();
        effectiveMode = DiagnosticsOptions.EffectiveMode.classify(
                profile, effectiveGbc, effectiveDmgCompat);
        effectiveClock = next.getIdentity().clockSpec();
        requestedBootstrapMode = next.getRequestedBootstrapMode();
        bootstrapOutcome = next.getBootstrapOutcome();
        bootstrapReady = true;
        bootstrapReadySessionGeneration = next.getSessionGeneration();
        recordBootstrapResult(next, true, null);
        return true;
    }

    private String bootstrapRejectionReason(Controller.BootstrapReadyEvent next) {
        if (next.getSessionGeneration() <= 0L
                || next.getSessionGeneration() != activeSessionGeneration) {
            return "stale_session";
        }
        if (bootstrapReady) {
            return "duplicate";
        }
        if (next.getRequestedBootstrapMode() != options.bootstrapMode) {
            return "requested_bootstrap_mismatch";
        }
        BootstrapOutcome outcome = next.getBootstrapOutcome();
        boolean expectedOutcome = options.bootstrapMode == BootstrapMode.SKIP
                ? outcome == BootstrapOutcome.SKIPPED
                : outcome == BootstrapOutcome.AUTHENTIC_HANDOFF;
        if (!expectedOutcome) {
            return "outcome_not_authentic";
        }
        HardwareProfile nextProfile = next.getProfile();
        HardwareProfileIdentity identity = next.getIdentity();
        if (nextProfile == null || identity == null
                || !nextProfile.id().equals(identity.profileId())
                || !nextProfile.clockSpec().equals(identity.clockSpec())) {
            return "profile_identity_mismatch";
        }
        HardwareProfile requestedProfile = options.hardware.profileOverride();
        if (requestedProfile != null && !requestedProfile.id().equals(nextProfile.id())) {
            return "requested_hardware_mismatch";
        }
        if (next.getEffectiveSpeedMode() != 1) {
            return "effective_speed_mismatch";
        }
        DiagnosticsOptions.EffectiveMode nextMode = DiagnosticsOptions.EffectiveMode.classify(
                nextProfile, next.getEffectiveGbc(), next.getEffectiveDmgCompat());
        if (nextMode == DiagnosticsOptions.EffectiveMode.UNKNOWN) {
            return "effective_mode_mismatch";
        }
        if (goalMatrixConfigured()
                && !expectedProfile().equals(effectiveProfileFor(nextMode))) {
            return "effective_profile_mismatch";
        }
        return null;
    }

    private void recordBootstrapResult(Controller.BootstrapReadyEvent next, boolean accepted,
            String reason) {
        StringBuilder result = new StringBuilder("event=boot_result ");
        if (goalMatrixConfigured()) {
            result.append(goalMatrixIdentityFields());
        } else {
            result.append("pair_id=").append(options.pairId)
                    .append(" matrix_block=").append(options.matrixBlock)
                    .append(" row_order=").append(options.rowOrder)
                    .append(" run_side=").append(options.runSide.externalValue())
                    .append(" requested_hardware=").append(options.hardware.externalValue())
                    .append(" execution_mode=")
                    .append(DiagnosticsOptions.executionModeValue(options.executionMode));
        }
        if (!goalMatrixConfigured()) {
            result.append(" session_generation=").append(next.getSessionGeneration());
        }
        result.append(" requested_bootstrap=")
                .append(DiagnosticsOptions.bootstrapModeValue(next.getRequestedBootstrapMode()))
                .append(" bootstrap_outcome=")
                .append(next.getBootstrapOutcome().name().toLowerCase(java.util.Locale.ROOT))
                .append(" profile=")
                .append(next.getProfile() == null ? "unknown" : next.getProfile().id())
                .append(" effective_gbc=").append(next.getEffectiveGbc())
                .append(" effective_dmg_compat=").append(next.getEffectiveDmgCompat())
                .append(" effective_speed_mode=").append(next.getEffectiveSpeedMode())
                .append(" accepted=").append(accepted);
        if (reason != null) {
            result.append(" reason=").append(reason);
        }
        record(result.toString());
    }

    synchronized boolean bootstrapReadyForTesting() {
        return bootstrapReady;
    }

    synchronized BootstrapOutcome bootstrapOutcomeForTesting() {
        return bootstrapOutcome;
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
        bootstrapReady = false;
        bootstrapReadySessionGeneration = 0L;
        requestedBootstrapMode = options.bootstrapMode;
        bootstrapOutcome = BootstrapOutcome.PENDING;
        audioSessionBaseline = sessionBaseline == null
                ? AndroidAudioSink.AudioBaseline.unavailable() : sessionBaseline;
        benchmarkGeneration = 0L;
        measurementArmed = false;
        scenarioExpectedFrames = !benchmarkScenarioConfigured()
                ? 0 : -1;
        scenarioSessionGeneration = 0L;
        scenarioCompletedFrames = 0;
        scenarioCompleted = !benchmarkScenarioConfigured();
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
        bootstrapReady = false;
        bootstrapReadySessionGeneration = 0L;
        bootstrapOutcome = BootstrapOutcome.PENDING;
        benchmarkGeneration = 0L;
        measurementArmed = false;
        scenarioCompleted = false;
        scenarioSessionGeneration = 0L;
        scenarioSourceClosed = false;
        scenarioAudioDrained = false;
        preArmVisibilityLost = false;
        coreResultEmitted = false;
        coreResultId = "unknown";
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
                || !benchmarkScenarioConfigured()) {
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
                + " " + workloadMatrixFields()
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
        coreResultEmitted = false;
        coreResultId = "unknown";
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
                + " " + workloadMatrixFields()
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
        coreResultEmitted = false;
        coreResultId = "unknown";
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
        if (goalMatrixConfigured()) {
            record("event=matrix_run " + goalMatrixIdentityFields());
            return;
        }
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
                + " " + workloadMatrixFields()
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
                || (goalMatrixConfigured() && !coreResultEmitted)
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
        boolean goal = goalMatrixConfigured();
        String finalIdentity = goal ? goalFinalIdentityFields() : matrixIdentityFields();
        if (goal) {
            finalIdentity += " core_result_id=" + coreResultId;
        }
        StringBuilder result = new StringBuilder("event=final_result ")
                .append(finalIdentity)
                .append(" frame=600")
                .append(" ready_count=").append(readyFrames)
                .append(" submitted_count=").append(submittedFrames)
                .append(" dropped_count=").append(droppedFrames)
                .append(" duplicate_count=").append(duplicateFrames)
                .append(" late_count=").append(lateFrames)
                .append(" corrupt_count=").append(corruptFrames)
                .append(" ready_first_id=").append(readyFrames == 0L ? 0L : 1L)
                .append(" ready_last_id=").append(readyFrames)
                .append(" ready_first_ns=").append(readyFirstNanos)
                .append(" ready_last_ns=").append(readyLastNanos)
                .append(" submission_first_id=").append(firstSubmittedId)
                .append(" submission_last_id=").append(lastSubmittedId)
                .append(" submission_first_ns=").append(submittedFirstNanos)
                .append(" submission_last_ns=").append(submittedLastNanos)
                .append(" ready_interval_fps=").append(formatExact(readyFps))
                .append(" submission_interval_fps=").append(formatExact(submissionFps))
                .append(" wall_ms=").append(elapsedMillis(current, firstFrameNanos))
                .append(" fps=").append(format(submissionFps)).append(" ")
                .append(goal ? goalFinalHardwareEvidenceFields() : finalHardwareEvidenceFields());
        if (goal) {
            // Goal records are consumed under Android's per-record log bound.  Keep every field
            // required by the strict parser, but omit the legacy environment and verbose sink
            // diagnostics that duplicate the frame-600 core/audio proof.
            result.append(" ").append(goalFinalAudioEvidenceFields())
                    .append(" system_audio_sample_count=").append(systemAudioSampleCount)
                    .append(" system_audio_bad_count=").append(systemAudioBadCount)
                    .append(" live_input_mutations=").append(liveInputMutations)
                    .append(" audio_focus_granted=").append(audioFocusGranted)
                    .append(" speed_mode_sample=frame_600")
                    .append(" drain_success=").append(benchmarkDrainSuccess)
                    .append(" ").append(audioStartLedgerFields());
        } else {
            result.append(" ").append(environmentEndFields(environmentEnd))
                    .append(" environment_sample_count=").append(environmentSampleCount)
                    .append(" thermal_worst=").append(thermalWorst)
                    .append(" system_load_worst_milli=").append(systemLoadWorstMilli)
                    .append(" cpu_freq_min_khz=").append(cpuFreqMinKHz)
                    .append(" ").append(audioEvidenceFields())
                    .append(" system_audio_sample_count=").append(systemAudioSampleCount)
                    .append(" system_audio_bad_count=").append(systemAudioBadCount)
                    .append(" display_refresh_min_millihz=").append(displayRefreshMinMillihz)
                    .append(" display_bad_count=").append(displayBadCount)
                    .append(" interactive_bad_count=").append(interactiveBadCount)
                    .append(" plugged_bad_count=").append(pluggedBadCount)
                    .append(" power_save_bad_count=").append(powerSaveBadCount)
                    .append(" stay_awake_bad_count=").append(stayAwakeBadCount)
                    .append(" priority_bad_count=").append(priorityBadCount)
                    .append(" importance_bad_count=").append(importanceBadCount)
                    .append(" battery_temp_min=").append(batteryTempMin)
                    .append(" battery_temp_max=").append(batteryTempMax)
                    .append(" live_input_mutations=").append(liveInputMutations)
                    .append(" audio_focus_granted=").append(audioFocusGranted)
                    .append(" audio_focus_loss_count=").append(audioFocusLossCount)
                    .append(" surface_vote_hz=").append(options.displayTargetHz)
                    .append(" display_target_hz=").append(options.displayTargetHz)
                    .append(" surface_content_rate_millihz=")
                    .append(options.surfaceContentRateMillihz)
                    .append(" speed_mode_sample=frame_600")
                    .append(" drain_success=").append(benchmarkDrainSuccess)
                    .append(" controller_cpu_ms=").append(cpuElapsed / NANOS_PER_MILLI)
                    .append(" controller_util_pct=").append(format(utilization))
                    .append(" gc_count_delta=").append(delta(globalGcCount(), gcCountStart))
                    .append(" gc_time_ms_delta=").append(delta(globalGcTime(), gcTimeStart))
                    .append(" alloc_bytes_delta=").append(delta(globalAllocBytes(), allocBytesStart));
        }
        record(result.toString());
        phase = Phase.DONE;
        measurementArmed = false;
        emulationStarted = false;
    }

    private String goalCoreResultId() {
        return "core-" + options.cellId + "-" + options.runSide.externalValue()
                + "-" + benchmarkGeneration;
    }

    private String matrixIdentityFields() {
        String identity = "build_profile=" + BuildConfig.BUILD_TYPE
                + " artifact_id=" + artifactId + " pair_id=" + options.pairId
                + " matrix_block=" + options.matrixBlock + " row_order=" + options.rowOrder
                + " run_side=" + options.runSide.externalValue()
                + " recent_slot=" + options.recentSlot
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
                + " scenario_audio_drained=" + scenarioAudioDrained;
        if (goalMatrixConfigured()) {
            // Goal evidence binds every record to the catalog cell.  Legacy records intentionally
            // omit these optional identity echoes to keep the terminal Android log payload below
            // the platform's bounded-record limit; matrix_run remains the source of truth there.
            identity += " " + workloadMatrixFields()
                    + " requested_hardware=" + options.hardware.externalValue();
        }
        return identity + " execution_mode="
                + DiagnosticsOptions.executionModeValue(options.executionMode);
    }

    /**
     * Compact identity for a goal final_result.  The matrix/core records already carry the
     * verbose benchmark-generation and warmup diagnostics; final only needs the immutable
     * catalog identity plus scenario completion proof.
     */
    private String goalFinalIdentityFields() {
        return "build_profile=" + BuildConfig.BUILD_TYPE
                + " artifact_id=" + artifactId
                + " pair_id=" + options.pairId
                + " matrix_block=" + options.matrixBlock
                + " row_order=" + options.rowOrder
                + " run_side=" + options.runSide.externalValue()
                + " recent_slot=" + options.recentSlot
                + " session_generation=" + activeSessionGeneration
                + " benchmark_generation=" + benchmarkGeneration
                + " matrix_version=" + options.matrixVersion
                + " cell_id=" + options.cellId
                + " workload_slot=" + (options.workloadSlot == null
                        ? "unknown" : options.workloadSlot.externalValue())
                + " workload_nonce=" + workloadNonce
                + " scenario_id=" + scenarioId()
                + " scenario_count=" + scenarioCount()
                + " expected_profile=" + expectedProfile()
                + " effective_profile=" + effectiveProfile()
                + " requested_hardware=" + options.hardware.externalValue()
                + " execution_mode="
                + DiagnosticsOptions.executionModeValue(options.executionMode)
                + " scenario_session_generation=" + scenarioSessionGeneration
                + " scenario_completed=" + scenarioCompleted
                + " scenario_completed_frames=" + scenarioCompletedFrames
                + " scenario_expected_frames=" + scenarioExpectedFrames
                + " scenario_source_closed=" + scenarioSourceClosed
                + " scenario_audio_drained=" + scenarioAudioDrained;
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

    private String goalFinalHardwareEvidenceFields() {
        // Bind the exact clock domain at the terminal boundary.  Profile labels alone are not
        // enough: a substituted revision or clock configuration could otherwise satisfy the
        // scheduler partition while reporting the requested cell name.
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
     * Required audio subset for the strict goal final proof.  The legacy final keeps the full
     * sink schema; goal runs retain only counters used by the parser's device, error, and queue
     * conservation gates so worst-case opaque identities remain below Android's record limit.
     */
    private String goalFinalAudioEvidenceFields() {
        AndroidAudioSink.Stats stats = audioTerminalStats != null
                ? audioTerminalStats : (audioSink == null ? null : audioSink.stats());
        if (stats == null) {
            return "audio_active=false audio_sample_rate=0 audio_overruns=-1"
                    + " audio_underruns=-1 audio_track_underruns=-1 audio_restarts=-1"
                    + " audio_paused=true audio_pcm_input_events=0 audio_pcm_input_frames=0"
                    + " audio_pcm_enqueued_bytes=0 audio_pcm_enqueued_frames=0"
                    + " audio_pcm_written_bytes=0 audio_pcm_written_frames=0"
                    + " audio_write_failures=-1 audio_pcm_discarded_bytes=0"
                    + " audio_pcm_pending_bytes=0 audio_pcm_queued_bytes=0 audio_queue_frames=0"
                    + " audio_output_open=false audio_output_playing=false audio_muted=true"
                    + " audio_volume=0 audio_route_failures=-1 audio_playback_position_frames=-1"
                    + " audio_system_volume=-1 audio_system_volume_max=-1"
                    + " audio_system_music_muted=true audio_queue_capacity_frames=0"
                    + " audio_max_frame_bytes=0 audio_output_identity="
                    + audioTerminalOutputIdentity + " audio_queue_identity="
                    + audioTerminalQueueIdentity + " benchmark_audio_policy="
                    + benchmarkAudioPolicy.externalValue() + " benchmark_audio_flags="
                    + compactAudioFlags() + " benchmark_audio_calendar=" + compactAudioCalendar();
        }
        return "audio_active=" + stats.active()
                + " audio_sample_rate=" + stats.sampleRate()
                + " audio_overruns=" + stats.overruns()
                + " audio_underruns=" + stats.underruns()
                + " audio_track_underruns=" + stats.outputUnderruns()
                + " audio_restarts=" + stats.restarts()
                + " audio_paused=" + stats.paused()
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

    /** Compact final-only baseline needed by the goal parser's conservation/stability gate. */
    private String audioStartLedgerFields() {
        AndroidAudioSink.AudioBaseline baseline = audioBaseline;
        return "audio_start_ledger=" + baseline.inputEvents() + ","
                + baseline.inputFrames() + "," + baseline.enqueuedBytes() + ","
                + baseline.enqueuedFrames() + "," + baseline.writtenBytes() + ","
                + baseline.writtenFrames() + "," + baseline.pendingBytes() + ","
                + baseline.queuedBytes() + "," + baseline.outputIdentity() + ","
                + baseline.queueIdentity();
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
