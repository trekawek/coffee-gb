package eu.rekawek.coffeegb.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Test/host-side parser and acceptance calculator for {@code CoffeeGbBench} records.
 *
 * <p>Visible-run counts describe successful Surface BufferQueue submissions, not compositor latch
 * or display completion; the companion host SurfaceFlinger timestats gate supplies that evidence.
 * This class deliberately lives in the JVM test source set. The benchmark APK emits only the
 * bounded records consumed here; no parser, bootstrap samples, ROM/save path, or frame/audio
 * payload is shipped in either Android release variant.</p>
 */
public final class BenchmarkMatrix {

    static final long DEFAULT_SEED = 0x434f464645454742L;
    static final int MIN_PAIRS = 12;
    static final int BOOTSTRAP_RESAMPLES = 10_000;
    static final int REQUIRED_FRAME_COUNT = 600;
    private static final int MAX_REPORTED_ERRORS = 256;
    private static final int RESERVED_STRUCTURAL_ERRORS = 64;
    private static final int MAX_INPUT_LINES = 250_000;
    private static final int MAX_LINE_LENGTH = 4_096;
    private static final int MAX_RUNS = 512;
    private static final int MAX_FRAME_SAMPLES = REQUIRED_FRAME_COUNT + 1;
    private static final int MAX_UNIQUE_ERRORS = 512;
    /**
     * The visible gate is relative to the exact hardware cadence, not a rounded 60.0 FPS.
     * The legacy Game Boy LCD cadence is 4,194,304 / 70,224 = 59.7275 FPS; requiring an
     * absolute 60.0 would reject a correctly paced emulator.  SGB has its own 61.17 FPS clock.
     */
    static final double MIN_REAL_TIME_RATIO = 0.99;
    static final double MAX_REAL_TIME_RATIO = 1.01;
    static final double RUN_FPS_ALARM = 58.0;
    static final double REGRESSION_PERCENT = -3.0;
    private static final double LEGACY_NOMINAL_FPS = 4_194_304.0 / 70_224.0;
    private static final double SGB_NOMINAL_FPS = 47_250_000.0 / 772_464.0;
    private static final long AUDIO_DURATION_TOLERANCE_NANOS = 250_000_000L;
    // Eligibility policy is deliberately explicit and device-independent: NONE thermal state,
    // at least a 60 Hz display, <=2.5 load units/CPU (the target Redmi commonly reports ~2.0),
    // and no more than one load unit/CPU drift over a run. Host labels cannot waive these checks.
    private static final int MAX_THERMAL_STATUS = 0;
    private static final int MIN_DISPLAY_REFRESH_MILLIHZ = 60_000;
    private static final int SGB_MIN_DISPLAY_REFRESH_MILLIHZ = 90_000;
    private static final long MAX_LOAD_PER_CPU_MILLI = 2_500L;
    private static final long MAX_LOAD_DRIFT_MILLI = 1_000L;
    private static final int MAX_BATTERY_TEMP_DRIFT_DECI_C = 20;

    private static final Pattern SAFE_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Set<String> INTERESTING_EVENTS = Set.of(
            "matrix_run", "frame_ready", "frame_submitted", "final_result",
            "compositor_result");
    private static final Set<String> BENIGN_EVENTS = Set.of(
            "session_launch", "rom_open_start", "recent_missing", "hardware_profile",
            "emulation_started", "warmup_complete", "benchmark_arm",
            "benchmark_arm_rejected", "benchmark_anchor", "speed_sample", "first_frame", "frames",
            "audio_output");
    private static final Set<String> MATRIX_FIELDS = Set.of(
            "event", "artifact_id", "pair_id", "matrix_block", "row_order", "run_side",
            "first_side", "thermal_window", "audio", "render", "availability",
            "benchmark_generation",
            "requested_hardware", "requested_profile", "profile", "effective_gbc",
            "effective_dmg_compat", "effective_mode", "device_id", "speed_mode_initial",
            "build_profile",
            "execution_mode",
            "clock_ticks_num", "clock_ticks_den", "clock_frames_num",
            "clock_frames_den", "clock_ticks_frame", "thermal_start",
            "battery_temp_start", "display_refresh_start_millihz", "display_state_start",
            "interactive_start", "plugged_start", "power_save_start", "stay_awake_start",
            "stay_on_plugged_mask_start", "thread_priority_start", "app_importance_start",
            "system_load_start_milli", "cpu_count_start", "memory_available_start_bytes",
            "workload_nonce", "warmup", "input_contract", "surface_vote_hz",
            "display_target_hz", "surface_content_rate_millihz",
            "audio_start_input_events", "audio_start_enqueued_bytes",
            "audio_start_input_frames",
            "audio_start_enqueued_frames", "audio_start_written_bytes",
            "audio_start_written_frames", "audio_start_write_failures",
            "audio_start_discarded_bytes", "audio_start_pending_bytes",
            "audio_start_queued_bytes", "audio_start_playback_position_frames",
            "audio_start_overruns", "audio_start_underruns", "audio_start_restarts",
            "audio_start_route_failures",
            "audio_start_output_open", "audio_start_output_playing", "audio_start_sample_rate",
            "audio_start_queue_capacity_frames", "audio_start_max_frame_bytes");
    private static final Set<String> FRAME_READY_FIELDS = Set.of(
            "event", "artifact_id", "pair_id", "matrix_block", "row_order", "run_side",
            "benchmark_generation",
            "ready_id", "ready_ns");
    private static final Set<String> FRAME_SUBMITTED_FIELDS = Set.of(
            "event", "artifact_id", "pair_id", "matrix_block", "row_order", "run_side",
            "submission_id", "submission_ns");
    private static final Set<String> FINAL_FIELDS = Set.of(
            "event", "artifact_id", "pair_id", "matrix_block", "row_order", "run_side",
            "benchmark_generation",
            "build_profile",
            "frame", "ready_count", "submitted_count", "dropped_count", "duplicate_count",
            "late_count", "corrupt_count", "ready_first_id", "ready_last_id",
            "ready_first_ns", "ready_last_ns", "submission_first_id", "submission_last_id",
            "submission_first_ns", "submission_last_ns", "ready_interval_fps",
            "submission_interval_fps", "wall_ms", "fps", "requested_profile", "profile",
            "effective_gbc", "effective_dmg_compat", "effective_mode", "device_id",
            "speed_mode_initial", "speed_mode_final", "clock_ticks_num", "clock_ticks_den",
            "execution_mode",
            "clock_frames_num", "clock_frames_den", "clock_ticks_frame",
            "workload_nonce", "warmup", "input_contract",
            "drain_success",
            "speed_mode_sample",
            "thermal_start", "thermal_end", "battery_temp_start", "battery_temp_end",
            "display_refresh_start_millihz", "display_refresh_end_millihz",
            "display_state_start", "display_state_end", "interactive_start", "interactive_end",
            "plugged_start", "plugged_end", "power_save_start", "power_save_end",
            "stay_awake_start", "stay_awake_end", "audio_active", "audio_sample_rate",
            "audio_overruns", "audio_underruns", "audio_track_underruns", "audio_restarts", "audio_paused",
            "audio_min_buffer_bytes", "audio_configured_buffer_bytes", "audio_actual_buffer_bytes",
            "audio_pcm_input_events", "audio_pcm_input_frames", "audio_pcm_enqueued_bytes",
            "audio_pcm_enqueued_frames", "audio_pcm_written_bytes", "audio_pcm_written_frames",
            "audio_write_failures", "audio_pcm_discarded_bytes", "audio_pcm_pending_bytes",
            "audio_pcm_queued_bytes", "audio_queue_frames",
            "audio_output_open", "audio_output_playing", "audio_muted", "audio_volume",
            "audio_route_failures", "audio_playback_position_frames", "audio_system_volume",
            "audio_system_volume_max", "audio_system_music_muted", "audio_queue_capacity_frames",
            "audio_max_frame_bytes",
            "audio_start_input_frames",
            "audio_start_input_events", "audio_start_enqueued_bytes",
            "audio_start_enqueued_frames", "audio_start_written_bytes",
            "audio_start_written_frames", "audio_start_write_failures",
            "audio_start_discarded_bytes", "audio_start_pending_bytes",
            "audio_start_queued_bytes", "audio_start_playback_position_frames",
            "audio_start_overruns", "audio_start_underruns", "audio_start_track_underruns",
            "audio_start_restarts",
            "audio_start_route_failures",
            "audio_start_output_open", "audio_start_output_playing", "audio_start_sample_rate",
            "audio_start_queue_capacity_frames", "audio_start_max_frame_bytes",
            "audio_focus_granted", "audio_focus_start_loss_count", "audio_focus_loss_count",
            "stay_on_plugged_mask_start", "stay_on_plugged_mask_end", "thread_priority_start",
            "thread_priority_end", "app_importance_start", "app_importance_end",
            "system_load_start_milli", "system_load_end_milli", "cpu_count_start", "cpu_count_end",
            "memory_available_start_bytes",
            "memory_available_end_bytes",
            "environment_sample_count", "thermal_worst", "system_load_worst_milli",
            "cpu_freq_min_khz", "display_refresh_min_millihz", "display_bad_count",
            "interactive_bad_count", "plugged_bad_count", "power_save_bad_count",
            "stay_awake_bad_count", "priority_bad_count", "importance_bad_count",
            "battery_temp_min", "battery_temp_max", "live_input_mutations",
            "surface_vote_hz", "display_target_hz", "surface_content_rate_millihz",
            "controller_cpu_ms", "controller_util_pct", "gc_count_delta",
            "gc_time_ms_delta", "alloc_bytes_delta");
    private static final Set<String> COMPOSITOR_FIELDS = Set.of(
            "event", "artifact_id", "device_id", "pair_id", "matrix_block", "row_order",
            "benchmark_generation",
            "run_side", "layer_id", "layer_uid", "total_frames", "histogram_frames",
            "raw_total_frames", "raw_histogram_frames", "boundary_frames",
            "boundary_intervals", "present_interval_count", "cadence_good_frames",
            "cadence_bad_frames", "cadence_vsync_total", "cadence_boundary_200_frames",
            "cadence_boundary_1000_frames", "cadence_max_gap_ms", "cadence_min_gap_ms",
            "compositor_histogram_fps",
            "dropped_frames", "late_acquire_frames", "bad_desired_present_frames",
            "display_refresh_hz", "measurement");
    private static final List<Row> REQUIRED_ROWS = List.of(
            Row.DMG, Row.MGB, Row.CGB_NATIVE, Row.CGB0_NATIVE,
            Row.CGB_DMG_COMPAT, Row.SGB, Row.SGB2);

    private BenchmarkMatrix() {
    }

    public enum Row {
        DMG("dmg", LEGACY_NOMINAL_FPS),
        MGB("mgb", LEGACY_NOMINAL_FPS),
        CGB_NATIVE("cgb-native", LEGACY_NOMINAL_FPS),
        CGB0_NATIVE("cgb0-native", LEGACY_NOMINAL_FPS),
        CGB_DMG_COMPAT("cgb-dmg-compat", LEGACY_NOMINAL_FPS),
        CGB0_DMG_COMPAT("cgb0-dmg-compat", LEGACY_NOMINAL_FPS),
        SGB("sgb", SGB_NOMINAL_FPS),
        SGB2("sgb2", LEGACY_NOMINAL_FPS);

        private final String externalValue;
        private final double nominalFps;

        Row(String externalValue, double nominalFps) {
            this.externalValue = externalValue;
            this.nominalFps = nominalFps;
        }

        public String externalValue() {
            return externalValue;
        }

        public double nominalFps() {
            return nominalFps;
        }

        static Row fromExternalValue(String value) {
            for (Row row : values()) {
                if (row.externalValue.equals(value)) {
                    return row;
                }
            }
            return null;
        }
    }

    enum Side {
        PARENT("parent"),
        CANDIDATE("candidate");

        private final String externalValue;

        Side(String externalValue) {
            this.externalValue = externalValue;
        }

        static Side fromExternalValue(String value) {
            for (Side side : values()) {
                if (side.externalValue.equals(value)) {
                    return side;
                }
            }
            return null;
        }
    }

    public static Report parse(List<String> lines) {
        return parse(lines, DEFAULT_SEED, BOOTSTRAP_RESAMPLES);
    }

    public static Report parse(List<String> lines, long seed, int resamples) {
        return parse(lines, seed, resamples, null, null);
    }

    /** Parses a matrix while pinning each side to the expected installed APK artifact hash. */
    public static Report parse(List<String> lines, long seed, int resamples,
            String expectedParentArtifact, String expectedCandidateArtifact) {
        List<String> errors = new BoundedErrors();
        LinkedHashMap<String, RunBuilder> runs = new LinkedHashMap<>();
        if (resamples < BOOTSTRAP_RESAMPLES) {
            errors.add("bootstrap resamples must be at least " + BOOTSTRAP_RESAMPLES);
        }
        if (lines == null) {
            errors.add("result log is missing");
        } else {
            if (lines.size() > MAX_INPUT_LINES) {
                errors.add("result log exceeds bounded line count");
            }
            int lineLimit = Math.min(lines.size(), MAX_INPUT_LINES);
            for (int lineNumber = 0; lineNumber < lineLimit; lineNumber++) {
                String line = lines.get(lineNumber);
                if (line != null && line.length() > MAX_LINE_LENGTH) {
                    errors.add("line " + (lineNumber + 1) + " exceeds bounded length");
                    continue;
                }
                Map<String, String> fields = fields(line, lineNumber + 1, errors);
                if (fields == null) {
                    continue;
                }
                String event = fields.get("event");
                if ("matrix_run".equals(event)) {
                    addMatrixRun(fields, lineNumber + 1, runs, errors);
                } else if ("frame_ready".equals(event)) {
                    addFrame(fields, lineNumber + 1, runs, true, errors);
                } else if ("frame_submitted".equals(event)) {
                    addFrame(fields, lineNumber + 1, runs, false, errors);
                } else if ("final_result".equals(event)) {
                    addFinal(fields, lineNumber + 1, runs, errors);
                } else if ("compositor_result".equals(event)) {
                    addCompositor(fields, lineNumber + 1, runs, errors);
                }
            }
        }
        if (errors instanceof BoundedErrors bounded) {
            bounded.beginStructuralPhase();
        }
        return finish(runs, errors, seed, resamples, expectedParentArtifact,
                expectedCandidateArtifact);
    }

    /**
     * Stable host entry point. Pass {@code -} (or no argument) to read redacted log records from
     * stdin, or pass a result-log file path. The parser never receives ROM/save payloads.
     */
    public static void main(String[] args) throws IOException {
        String expectedParent = null;
        String expectedCandidate = null;
        String inputName = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--parent-apk".equals(argument) || "--candidate-apk".equals(argument)) {
                if (index + 1 >= args.length) {
                    System.err.println("usage: BenchmarkMatrix --parent-apk <apk-or-sha256>"
                            + " --candidate-apk <apk-or-sha256> [result-log|-]");
                    System.exit(2);
                    return;
                }
                String identity = artifactArgument(args[++index]);
                if ("--parent-apk".equals(argument)) {
                    expectedParent = identity;
                } else {
                    expectedCandidate = identity;
                }
            } else if (inputName == null) {
                inputName = argument;
            } else {
                System.err.println("usage: BenchmarkMatrix --parent-apk <apk-or-sha256>"
                        + " --candidate-apk <apk-or-sha256> [result-log|-]");
                System.exit(2);
                return;
            }
        }
        if (expectedParent == null || expectedCandidate == null
                || expectedParent.equals(expectedCandidate)) {
            System.err.println("parent and candidate APK identities must be distinct SHA-256 values");
            System.exit(2);
            return;
        }
        if (inputName == null) {
            inputName = "-";
        }
        List<String> lines;
        if ("-".equals(inputName)) {
            lines = readLines(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        } else {
            Path input = Path.of(inputName);
            if (forbiddenInputPath(input)) {
                System.err.println("refusing non-log input path; pass a redacted .log/.txt file");
                System.exit(2);
                return;
            }
            try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
                lines = readLines(reader);
            }
        }
        Report report = parse(lines, DEFAULT_SEED, BOOTSTRAP_RESAMPLES, expectedParent,
                expectedCandidate);
        System.out.println("accepted=" + report.accepted);
        System.out.println("parent_artifact_id=" + expectedParent
                + " candidate_artifact_id=" + expectedCandidate);
        for (Row row : REQUIRED_ROWS) {
            RowSummary summary = report.rows.get(row);
            if (summary == null) {
                continue;
            }
            System.out.println("row=" + row.externalValue + " pair_count=" + summary.pairCount
                    + " target_nominal_fps=" + format(summary.targetNominalFps)
                    + " minimum_candidate_fps=" + format(summary.minimumCandidateFps)
                    + " minimum_run_fps=" + format(summary.minimumRunFps)
                    + " candidate_median_fps=" + format(summary.candidateFps.median)
                    + " real_time_ratio=" + format(summary.realTimeRatio)
                    + " candidate_ci_low=" + format(summary.candidateFps.lower)
                    + " candidate_ci_high=" + format(summary.candidateFps.upper)
                    + " effect_median_pct=" + format(summary.effectPercent.median)
                    + " effect_ci_low=" + format(summary.effectPercent.lower)
                    + " effect_ci_high=" + format(summary.effectPercent.upper)
                    + " alarm=" + summary.alarm + " regression=" + summary.regression
                    + " lower_bound_pass=" + summary.lowerBoundPass);
            for (RunEvidence evidence : summary.evidence) {
                System.out.println("row=" + row.externalValue + " pair_id=" + evidence.pairId
                        + " run_side=" + evidence.side + " artifact_id=" + evidence.artifactId
                        + " benchmark_generation=" + evidence.benchmarkGeneration
                        + " device_id=" + evidence.deviceId
                        + " requested_profile=" + evidence.requestedProfile
                        + " profile=" + evidence.profile + " effective_mode="
                        + evidence.effectiveMode + " speed_mode_initial="
                        + evidence.speedModeInitial + " speed_mode_final="
                        + evidence.speedModeFinal + " clock_ticks_num="
                        + evidence.clockTicksNumerator + " clock_ticks_den="
                        + evidence.clockTicksDenominator + " clock_frames_num="
                        + evidence.clockFramesNumerator + " clock_frames_den="
                        + evidence.clockFramesDenominator + " clock_ticks_frame="
                        + evidence.clockTicksFrame + " display_target_hz="
                        + evidence.displayTargetHz + " surface_content_rate_millihz="
                        + evidence.surfaceContentRateMillihz + " ready_count=" + evidence.readyCount
                        + " submitted_count=" + evidence.submittedCount + " dropped_count="
                        + evidence.droppedCount + " duplicate_count=" + evidence.duplicateCount
                        + " late_count=" + evidence.lateCount + " corrupt_count="
                        + evidence.corruptCount + " ready_interval_fps="
                        + format(evidence.readyIntervalFps) + " submission_interval_fps="
                        + format(evidence.presentationIntervalFps) + " audio_eligible="
                        + evidence.audioEligible + " environment_eligible="
                        + evidence.environmentEligible + " compositor_eligible="
                        + evidence.compositorEligible + " compositor_layer_id="
                        + evidence.compositorLayerId + " compositor_layer_uid="
                        + evidence.compositorLayerUid + " compositor_display_refresh_hz="
                        + evidence.compositorDisplayRefreshHz + " compositor_total_frames="
                        + evidence.compositorTotalFrames + " compositor_histogram_frames="
                        + evidence.compositorHistogramFrames + " compositor_raw_total_frames="
                        + evidence.compositorRawTotalFrames + " compositor_raw_histogram_frames="
                        + evidence.compositorRawHistogramFrames + " compositor_boundary_frames="
                        + evidence.compositorBoundaryFrames + " compositor_boundary_intervals="
                        + evidence.compositorBoundaryIntervals + " compositor_present_interval_count="
                        + evidence.compositorPresentIntervalCount + " compositor_cadence_good_frames="
                        + evidence.compositorCadenceGoodFrames + " compositor_cadence_bad_frames="
                        + evidence.compositorCadenceBadFrames + " compositor_cadence_vsync_total="
                        + evidence.compositorCadenceVsyncTotal + " compositor_boundary_200_frames="
                        + evidence.compositorBoundary200Frames + " compositor_boundary_1000_frames="
                        + evidence.compositorBoundary1000Frames + " compositor_cadence_max_gap_ms="
                        + evidence.compositorCadenceMaxGapMs + " compositor_histogram_fps="
                        + format(evidence.compositorHistogramFps) + " compositor_dropped_frames="
                        + evidence.compositorDroppedFrames + " compositor_late_frames="
                        + evidence.compositorLateFrames + " compositor_bad_desired_present_frames="
                        + evidence.compositorBadDesiredPresentFrames + " audio_sample_rate="
                        + evidence.audioSampleRate + " audio_enqueued_frames="
                        + evidence.audioEnqueuedFrames + " audio_written_frames="
                        + evidence.audioWrittenFrames + " audio_enqueued_bytes="
                        + evidence.audioEnqueuedBytes + " audio_written_bytes="
                        + evidence.audioWrittenBytes + " audio_discarded_bytes="
                        + evidence.audioDiscardedBytes + " audio_pending_bytes="
                        + evidence.audioPendingBytes + " audio_queued_bytes="
                        + evidence.audioQueuedBytes + " audio_queue_frames="
                        + evidence.audioQueueFrames + " audio_write_failures="
                        + evidence.audioWriteFailures + " thermal_start=" + evidence.thermalStart
                        + " thermal_end=" + evidence.thermalEnd + " battery_temp_start="
                        + evidence.batteryTempStart + " battery_temp_end="
                        + evidence.batteryTempEnd + " display_refresh_start_millihz="
                        + evidence.displayRefreshStartMillihz + " display_refresh_end_millihz="
                        + evidence.displayRefreshEndMillihz + " system_load_start_milli="
                        + evidence.systemLoadStartMilli + " system_load_end_milli="
                        + evidence.systemLoadEndMilli + " cpu_count_start="
                        + evidence.cpuCountStart + " cpu_count_end=" + evidence.cpuCountEnd
                        + " stay_on_plugged_mask_start=" + evidence.stayOnPluggedMaskStart
                        + " stay_on_plugged_mask_end=" + evidence.stayOnPluggedMaskEnd
                        + " thread_priority_start=" + evidence.threadPriorityStart
                        + " thread_priority_end=" + evidence.threadPriorityEnd
                        + " app_importance_start=" + evidence.appImportanceStart
                        + " app_importance_end=" + evidence.appImportanceEnd
                        + " workload_nonce=" + evidence.workloadNonce
                        + " warmup=" + evidence.warmup
                        + " input_contract=" + evidence.inputContract
                        + " wall_ms=" + evidence.wallMs
                        + " fps=" + format(evidence.runFps)
                        + " controller_cpu_ms=" + evidence.controllerCpuMs
                        + " controller_util_pct=" + format(evidence.controllerUtilPct)
                        + " gc_count_delta=" + evidence.gcCountDelta
                        + " gc_time_ms_delta=" + evidence.gcTimeMsDelta
                        + " alloc_bytes_delta=" + evidence.allocBytesDelta
                        + " audio_output_open=" + evidence.audioOutputOpen
                        + " audio_output_playing=" + evidence.audioOutputPlaying
                        + " audio_muted=" + evidence.audioMuted
                        + " audio_volume=" + evidence.audioVolume
                        + " audio_route_failures=" + evidence.audioRouteFailures
                        + " environment_sample_count=" + evidence.environmentSampleCount
                        + " thermal_worst=" + evidence.thermalWorst
                        + " system_load_worst_milli=" + evidence.systemLoadWorstMilli
                        + " cpu_freq_min_khz=" + evidence.cpuFreqMinKHz);
            }
        }
        for (String error : report.errors) {
            System.out.println("error=" + error.replace(' ', '_'));
        }
        if (!report.accepted) {
            System.exit(1);
        }
    }

    private static List<String> readLines(Reader reader) throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            while ((line = buffered.readLine()) != null) {
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH + 1);
                }
                lines.add(line);
                if (lines.size() == MAX_INPUT_LINES) {
                    if (buffered.readLine() != null) {
                        lines.add(null);
                    }
                    break;
                }
            }
        }
        return lines;
    }

    private static boolean forbiddenInputPath(Path path) {
        String name = path.getFileName() == null
                ? path.toString().toLowerCase(Locale.ROOT)
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.endsWith(".log") && !name.endsWith(".txt")
                || name.endsWith(".gb") || name.endsWith(".gbc") || name.endsWith(".sgb")
                || name.endsWith(".rom") || name.endsWith(".sav") || name.endsWith(".cgbstate")
                || name.endsWith(".7z") || name.endsWith(".rar");
    }

    private static boolean forbiddenArtifactPath(Path path) {
        String name = path.getFileName() == null
                ? path.toString().toLowerCase(Locale.ROOT)
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gb") || name.endsWith(".gbc") || name.endsWith(".sgb")
                || name.endsWith(".rom") || name.endsWith(".sav") || name.endsWith(".cgbstate")
                || name.endsWith(".7z") || name.endsWith(".rar") || !name.endsWith(".apk");
    }

    private static String artifactArgument(String argument) throws IOException {
        if (argument != null && argument.matches("(?i)[0-9a-f]{64}")) {
            return argument.toLowerCase(Locale.ROOT);
        }
        Path apk = Path.of(argument == null ? "" : argument);
        if (forbiddenArtifactPath(apk)) {
            throw new IOException("APK identity input must be a .apk path or 64-hex SHA-256");
        }
        try (InputStream input = Files.newInputStream(apk)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length > 0) {
                    digest.update(buffer, 0, length);
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    static String artifactIdentityForTesting(Path apk) throws IOException {
        return artifactArgument(apk.toString());
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", value));
        }
        return text.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static Map<String, String> fields(String line, int lineNumber,
            List<String> errors) {
        if (line == null) {
            return null;
        }
        int eventStart = line.indexOf("event=");
        if (eventStart < 0) {
            if (line.contains("CoffeeGbBench")) {
                errors.add("line " + lineNumber + ": benchmark record has no event");
                rejectCandidateTokens(line, lineNumber, errors);
            }
            return null;
        }
        rejectCandidateTokens(line.substring(0, eventStart), lineNumber, errors);
        String[] tokens = line.substring(eventStart).trim().split("\\s+");
        if (tokens.length == 0 || !tokens[0].startsWith("event=")) {
            return null;
        }
        String event = tokens[0].substring("event=".length()).trim().toLowerCase(Locale.ROOT);
        if (!INTERESTING_EVENTS.contains(event) && !BENIGN_EVENTS.contains(event)) {
            errors.add("line " + lineNumber + ": unknown benchmark event");
            rejectCandidateTokens(line.substring(eventStart), lineNumber, errors);
            return null;
        }
        Set<String> allowed = allowedFields(event);
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("event", event);
        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            int equals = token.indexOf('=');
            if (equals <= 0 || equals == token.length() - 1) {
                errors.add("line " + lineNumber + ": malformed key/value token");
                continue;
            }
            String key = token.substring(0, equals).toLowerCase(Locale.ROOT);
            String value = token.substring(equals + 1);
            validateFieldValue(event, key, value, lineNumber, errors);
            if (containsForbiddenKey(key)) {
                errors.add("line " + lineNumber + ": forbidden payload field");
            }
            if (!allowed.contains(key)) {
                errors.add("line " + lineNumber + ": unknown field for event");
            }
            if (fields.put(key, value) != null) {
                errors.add("line " + lineNumber + ": duplicate field " + key);
            }
        }
        return fields;
    }

    private static void rejectCandidateTokens(String text, int lineNumber,
            List<String> errors) {
        for (String token : text.trim().split("\\s+")) {
            int equals = token.indexOf('=');
            if (equals <= 0 || equals == token.length() - 1) {
                continue;
            }
            String key = token.substring(0, equals).toLowerCase(Locale.ROOT);
            String value = token.substring(equals + 1);
            String lowerValue = value.toLowerCase(Locale.ROOT);
            if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                    || lowerValue.contains("://")
                    || lowerValue.matches(".*\\.(gb|gbc|sgb|rom|sav|cgbstate|7z|rar)([^a-z0-9].*)?$")) {
                errors.add("line " + lineNumber + ": path-like or ROM/save value is forbidden");
            }
            if (containsForbiddenKey(key)) {
                errors.add("line " + lineNumber + ": forbidden payload field");
            } else {
                errors.add("line " + lineNumber + ": unknown field outside benchmark event");
            }
        }
    }

    private static void validateFieldValue(String event, String key, String value,
            int lineNumber, List<String> errors) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || lower.contains("://")
                || lower.matches(".*\\.(gb|gbc|sgb|rom|sav|cgbstate|7z|rar)([^a-z0-9].*)?$")) {
            errors.add("line " + lineNumber + ": path-like or ROM/save value is forbidden");
            return;
        }
        if (isBooleanField(key)) {
            if (!("true".equals(value) || "false".equals(value)
                    || "on".equals(value) || "off".equals(value))) {
                errors.add("line " + lineNumber + ": invalid boolean " + key);
            }
            return;
        }
        if ("profile".equals(key) || "requested_profile".equals(key)) {
            if (!("auto".equals(value) || Set.of("dmg", "mgb", "cgb", "cgb0", "sgb", "sgb2")
                    .contains(value))) {
                errors.add("line " + lineNumber + ": invalid profile");
            }
            return;
        }
        if ("hardware".equals(key) || "requested_hardware".equals(key)) {
            if (!Set.of("auto", "dmg", "mgb", "cgb", "cgb0", "sgb", "sgb2")
                    .contains(value)) {
                errors.add("line " + lineNumber + ": invalid hardware");
            }
            return;
        }
        if ("family".equals(key)) {
            if (!Set.of("dmg", "mgb", "cgb", "sgb", "sgb2").contains(value)) {
                errors.add("line " + lineNumber + ": invalid hardware family");
            }
            return;
        }
        if ("execution_mode".equals(key)
                && !Set.of("accuracy", "performance").contains(value)) {
            errors.add("line " + lineNumber + ": invalid execution mode");
            return;
        }
        if ("run_side".equals(key) || "first_side".equals(key)) {
            if (!Set.of("parent", "candidate").contains(value)) {
                errors.add("line " + lineNumber + ": invalid side");
            }
            return;
        }
        if ("render".equals(key)) {
            if (!Set.of("presentation", "sink").contains(value)) {
                errors.add("line " + lineNumber + ": invalid render mode");
            }
            return;
        }
        if ("availability".equals(key)
                && !Set.of("available", "unavailable").contains(value)) {
            errors.add("line " + lineNumber + ": invalid availability");
            return;
        }
        if ("artifact_id".equals(key) || "device_id".equals(key)) {
            if (!value.matches("[0-9a-f]{64}")) {
                errors.add("line " + lineNumber + ": " + key + " must be SHA-256");
            }
            return;
        }
        if ("layer_id".equals(key)) {
            if (!value.matches("[0-9a-f]{64}")) {
                errors.add("line " + lineNumber + ": layer_id must be SHA-256");
            }
            return;
        }
        if ("pair_id".equals(key) || "matrix_block".equals(key)
                || "thermal_window".equals(key)) {
            if (!SAFE_TOKEN.matcher(value).matches()) {
                errors.add("line " + lineNumber + ": unsafe value for " + key);
            }
            return;
        }
        if ("workload_nonce".equals(key)) {
            if (!value.matches("[a-z0-9][a-z0-9._-]{15,63}")
                    || "unknown".equals(value) || "invalid".equals(value)) {
                errors.add("line " + lineNumber + ": workload nonce is not an app-owned opaque token");
            }
            return;
        }
        if ("token".equals(key)) {
            if (!value.matches("[a-z0-9][a-z0-9._-]{15,63}")) {
                errors.add("line " + lineNumber + ": invalid benchmark arm token");
            }
            return;
        }
        if ("generation".equals(key)) {
            integerValue(value, lineNumber, errors, key);
            return;
        }
        if ("phase".equals(key)) {
            if (!Set.of("warming", "anchor_ready", "armed", "core_frozen",
                    "submissions_complete", "done", "idle").contains(value)) {
                errors.add("line " + lineNumber + ": invalid benchmark phase");
            }
            return;
        }
        if ("reason".equals(key) && !Set.of("not_anchor_ready", "not_warming",
                "post_failed").contains(value)) {
            errors.add("line " + lineNumber + ": invalid benchmark rejection reason");
            return;
        }
        if ("effective_mode".equals(key)) {
            if (Row.fromExternalValue(value) == null) {
                errors.add("line " + lineNumber + ": invalid effective_mode");
            }
            return;
        }
        if ("speed_mode_sample".equals(key)) {
            if (!("boot_resolved".equals(value) || "frame_600".equals(value))) {
                errors.add("line " + lineNumber + ": invalid speed_mode_sample");
            }
            return;
        }
        if ("measurement".equals(key)) {
            if (!"surfaceflinger_timestats".equals(value)) {
                errors.add("line " + lineNumber + ": invalid compositor measurement");
            }
            return;
        }
        if (("speed_mode_initial".equals(key) || "speed_mode_final".equals(key))
                && !"unknown".equals(value)) {
            integerValue(value, lineNumber, errors, key);
            if (!"1".equals(value) && !"2".equals(value)) {
                errors.add("line " + lineNumber + ": speed mode must be 1 or 2");
            }
            return;
        }
        if (isFloatingField(key)) {
            try {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed) || parsed < 0.0 || parsed > 100_000.0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid bounded number " + key);
            }
            return;
        }
        if (isNumericField(key)) {
            integerValue(value, lineNumber, errors, key);
            return;
        }
        if (!SAFE_TOKEN.matcher(value).matches()) {
            errors.add("line " + lineNumber + ": unsafe value for " + key);
        }
    }

    private static boolean isBooleanField(String key) {
        return key.equals("audio") || key.equals("warmup") || key.equals("effective_gbc")
                || key.equals("effective_dmg_compat") || key.equals("interactive_start")
                || key.equals("interactive_end") || key.equals("power_save_start")
                || key.equals("power_save_end") || key.equals("stay_awake_start")
                || key.equals("stay_awake_end")
                || key.equals("audio_active") || key.equals("audio_paused")
                || key.equals("audio_output_open") || key.equals("audio_output_playing")
                || key.equals("audio_start_output_open")
                || key.equals("audio_start_output_playing")
                || key.equals("audio_muted") || key.equals("audio_system_music_muted")
                || key.equals("audio_focus_granted");
    }

    private static boolean isFloatingField(String key) {
        return key.equals("fps") || key.equals("interval_fps")
                || key.equals("ready_interval_fps") || key.equals("submission_interval_fps")
                || key.equals("controller_util_pct") || key.equals("compositor_histogram_fps");
    }

    private static boolean isNumericField(String key) {
        return key.equals("frame") || key.equals("row_order") || key.equals("benchmark_generation")
                || key.endsWith("_ns")
                || key.endsWith("_ms") || key.endsWith("_bytes") || key.endsWith("_frames")
                || key.endsWith("_count") || key.endsWith("_events")
                || key.endsWith("_millihz")
                || key.endsWith("_milli") || key.endsWith("_rate") || key.endsWith("_status")
                || key.endsWith("_hz")
                || key.contains("_ms") || key.contains("_bytes") || key.contains("_frames")
                || key.contains("_count") || key.endsWith("_delta")
                || key.equals("ready_id") || key.equals("submission_id")
                || key.equals("boundary_intervals") || key.equals("cadence_vsync_total")
                || key.startsWith("clock_") || key.equals("plugged_start")
                || key.equals("plugged_end") || key.equals("thermal_start")
                || key.equals("thermal_end") || key.equals("display_state_start")
                || key.equals("display_state_end") || key.equals("thread_priority_start")
                || key.equals("thread_priority_end") || key.equals("app_importance_start")
                || key.equals("app_importance_end") || key.equals("audio_volume")
                || key.equals("audio_system_volume") || key.equals("audio_system_volume_max")
                || key.equals("audio_route_failures") || key.equals("layer_uid");
    }

    private static void integerValue(String value, int lineNumber, List<String> errors,
            String key) {
        try {
            Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            errors.add("line " + lineNumber + ": invalid integer " + key);
        }
    }

    private static Set<String> allowedFields(String event) {
        return switch (event) {
            case "matrix_run" -> MATRIX_FIELDS;
            case "frame_ready" -> FRAME_READY_FIELDS;
            case "frame_submitted" -> FRAME_SUBMITTED_FIELDS;
            case "final_result" -> FINAL_FIELDS;
            case "compositor_result" -> COMPOSITOR_FIELDS;
            case "session_launch" -> Set.of("event", "launch_ns", "hardware",
                    "requested_hardware", "audio", "render", "warmup");
            case "rom_open_start" -> Set.of("event", "wall_ns", "since_launch_ms");
            case "recent_missing" -> Set.of("event");
            case "hardware_profile" -> Set.of("event", "requested_hardware", "requested_profile",
                    "profile", "family", "effective_gbc", "effective_dmg_compat",
                    "effective_mode", "speed_mode_initial", "speed_mode_sample",
                    "clock_ticks_num", "clock_ticks_den", "clock_frames_num",
                    "clock_frames_den", "clock_ticks_frame", "device_id");
            case "emulation_started" -> Set.of("event", "wall_ns", "prep_ms",
                    "requested_hardware", "profile", "effective_gbc", "effective_dmg_compat",
                    "effective_mode", "speed_mode_initial", "speed_mode_sample",
                    "clock_ticks_num", "clock_ticks_den", "clock_frames_num",
                    "clock_frames_den", "clock_ticks_frame");
            case "warmup_complete" -> Set.of("event", "completed", "phase");
            case "benchmark_arm" -> Set.of("event", "generation", "token", "phase");
            case "benchmark_arm_rejected" -> Set.of("event", "phase", "reason");
            case "benchmark_anchor" -> Set.of("event", "success", "phase", "reason");
            case "speed_sample" -> Set.of("event", "frame", "effective_gbc",
                    "effective_dmg_compat", "speed_mode_final", "speed_mode_sample");
            case "first_frame" -> Set.of("event", "frame", "wall_ns", "since_launch_ms",
                    "prep_to_frame_ms");
            case "frames" -> Set.of("event", "frame", "ready_count", "submitted_count",
                    "wall_ms", "wall_delta_ms", "fps", "interval_fps", "effective_mode",
                    "speed_mode_sample", "controller_cpu_ms", "controller_util_pct",
                    "gc_count_delta", "gc_time_ms_delta", "alloc_bytes_delta");
            case "audio_output" -> Set.of("event", "sample_rate", "min_buffer_bytes",
                    "configured_buffer_bytes", "actual_buffer_bytes");
            default -> Set.of("event");
        };
    }

    private static boolean containsForbiddenKey(String key) {
        if ("rom".equals(key) || "save".equals(key) || key.contains("payload")) {
            return true;
        }
        // Do not classify legitimate telemetry such as power_save_start as save payload.
        return key.startsWith("rom_") || key.startsWith("save_") || key.endsWith("_path")
                || key.endsWith("_payload");
    }

    private static void addMatrixRun(Map<String, String> fields, int lineNumber,
            Map<String, RunBuilder> runs, List<String> errors) {
        String artifactId = artifactToken(fields, "artifact_id", lineNumber, errors);
        if (!"benchmark".equals(fields.get("build_profile"))) {
            errors.add("line " + lineNumber + ": benchmark build profile is missing or invalid");
        }
        String pairId = requiredToken(fields, "pair_id", lineNumber, errors);
        String block = requiredToken(fields, "matrix_block", lineNumber, errors);
        long benchmarkGeneration = longValue(fields, "benchmark_generation", lineNumber, errors);
        String device = deviceToken(fields, "device_id", lineNumber, errors);
        String thermalWindow = requiredToken(fields, "thermal_window", lineNumber, errors);
        Side side = side(fields.get("run_side"), "run_side", lineNumber, errors);
        Side firstSide = side(fields.get("first_side"), "first_side", lineNumber, errors);
        int rowOrder = integer(fields, "row_order", lineNumber, errors);
        String requestedProfile = requiredToken(fields, "requested_profile", lineNumber, errors);
        String profile = requiredToken(fields, "profile", lineNumber, errors);
        String executionMode = fields.getOrDefault("execution_mode", "accuracy");
        String workloadNonce = requiredToken(fields, "workload_nonce", lineNumber, errors);
        boolean warmup = booleanValue(fields, "warmup", lineNumber, errors);
        String inputContract = requiredToken(fields, "input_contract", lineNumber, errors);
        Boolean effectiveGbc = strictBoolean(fields, "effective_gbc", lineNumber, errors);
        Boolean effectiveDmgCompat = strictBoolean(fields, "effective_dmg_compat", lineNumber, errors);
        String effectiveMode = requiredToken(fields, "effective_mode", lineNumber, errors);
        int speedModeInitial = integer(fields, "speed_mode_initial", lineNumber, errors);
        int surfaceVoteHz = integer(fields, "surface_vote_hz", lineNumber, errors);
        int displayTargetHz = integer(fields, "display_target_hz", lineNumber, errors);
        int surfaceContentRateMillihz = integer(fields, "surface_content_rate_millihz",
                lineNumber, errors);
        ClockFields clock = parseClock(fields, lineNumber, errors);
        Row row = recomputeRow(profile, effectiveGbc, effectiveDmgCompat);
        Row claimedRow = Row.fromExternalValue(effectiveMode);
        if (row == null) {
            errors.add("line " + lineNumber + ": hardware evidence is unknown or invalid");
        } else if (claimedRow != row) {
            errors.add("line " + lineNumber + ": effective_mode does not match hardware evidence");
        } else if (clock != null && !clock.equals(expectedClock(row))) {
            errors.add("line " + lineNumber + ": exact clock identity does not match hardware row");
        }
        if (row != null && (speedModeInitial != 1 && speedModeInitial != 2
                || (row != Row.CGB_NATIVE && row != Row.CGB0_NATIVE
                && speedModeInitial != 1))) {
            errors.add("line " + lineNumber + ": invalid initial speed for hardware row");
        }
        if (row != null && !validSurfaceVote(row, surfaceVoteHz)) {
            errors.add("line " + lineNumber + ": surface vote is not valid for hardware row");
        }
        if (row != null && !validSurfaceContentRate(row, surfaceContentRateMillihz)) {
            errors.add("line " + lineNumber + ": Surface content rate is not exact for hardware row");
        }
        if (displayTargetHz != surfaceVoteHz) {
            errors.add("line " + lineNumber + ": display target does not match surface vote");
        }
        if (!requestedProfileMatches(requestedProfile, profile)) {
            errors.add("line " + lineNumber + ": requested profile does not match actual profile");
        }
        String availability = requiredToken(fields, "availability", lineNumber, errors);
        boolean unavailable;
        if ("available".equals(availability)) {
            unavailable = false;
        } else if ("unavailable".equals(availability)) {
            unavailable = true;
        } else {
            unavailable = false;
            errors.add("line " + lineNumber + ": invalid availability");
        }
        boolean audio = booleanValue(fields, "audio", lineNumber, errors);
        String render = fields.get("render");
        if (render == null) {
            errors.add("line " + lineNumber + ": missing render");
            render = "unknown";
        }
        if (rowOrder < 0 || rowOrder >= REQUIRED_ROWS.size()) {
            errors.add("line " + lineNumber + ": row_order must be 0..6");
        }
        EnvironmentFields environment = parseStartEnvironment(fields, lineNumber, errors);
        AudioStartFields audioStart = parseMatrixAudioStart(fields, lineNumber, errors);
        if (side == null || firstSide == null || artifactId == null || pairId == null
                || block == null || device == null || thermalWindow == null
                || requestedProfile == null || profile == null || effectiveMode == null
                || effectiveGbc == null || effectiveDmgCompat == null || environment == null
                || speedModeInitial < 1 || clock == null || workloadNonce == null
                || benchmarkGeneration <= 0L
                || inputContract == null || surfaceVoteHz < 60 || displayTargetHz < 60
                || surfaceContentRateMillihz <= 0) {
            return;
        }
        if (!warmup || !"none".equals(inputContract)) {
            errors.add("line " + lineNumber + ": run lacks required warmup/no-input contract");
        }
        String key = runKey(pairId, side, block, rowOrder);
        if (runs.containsKey(key)) {
            errors.add("line " + lineNumber + ": duplicate matrix run " + key);
            return;
        }
        if (runs.size() >= MAX_RUNS) {
            errors.add("result log exceeds bounded matrix-run declarations");
            return;
        }
        runs.put(key, new RunBuilder(key, lineNumber, artifactId, pairId, block, rowOrder, row,
                side, firstSide, device, thermalWindow, requestedProfile, profile,
                effectiveGbc, effectiveDmgCompat, effectiveMode, executionMode,
                speedModeInitial, clock, audio,
                render, !unavailable, environment, workloadNonce, warmup, inputContract,
                surfaceVoteHz, displayTargetHz, surfaceContentRateMillihz, benchmarkGeneration,
                audioStart));
    }

    private static void addFrame(Map<String, String> fields, int lineNumber,
            Map<String, RunBuilder> runs, boolean ready, List<String> errors) {
        RunBuilder run = findRun(fields, lineNumber, runs, errors);
        if (run == null) {
            return;
        }
        String idKey = ready ? "ready_id" : "submission_id";
        String timestampKey = ready ? "ready_ns" : "submission_ns";
        long id = longValue(fields, idKey, lineNumber, errors);
        long timestamp = longValue(fields, timestampKey, lineNumber, errors);
        if (id <= 0L || timestamp <= 0L) {
            return;
        }
        if (ready) {
            if (run.readyIds.size() >= MAX_FRAME_SAMPLES) {
                errors.add(run.key + " exceeds bounded ready-frame samples");
                return;
            }
            run.readyIds.add(id);
            run.readyNanos.add(timestamp);
        } else {
            if (run.submissionIds.size() >= MAX_FRAME_SAMPLES) {
                errors.add(run.key + " exceeds bounded surface-submission samples");
                return;
            }
            run.submissionIds.add(id);
            run.submissionNanos.add(timestamp);
        }
        run.frameOrdinals.add(lineNumber);
    }

    private static void addFinal(Map<String, String> fields, int lineNumber,
            Map<String, RunBuilder> runs, List<String> errors) {
        RunBuilder run = findRun(fields, lineNumber, runs, errors);
        if (run == null) {
            return;
        }
        if (run.finalFields != null) {
            errors.add("line " + lineNumber + ": duplicate final_result");
        } else {
            run.finalOrdinal = lineNumber;
            String artifactId = artifactToken(fields, "artifact_id", lineNumber, errors);
            if (!"benchmark".equals(fields.get("build_profile"))) {
                errors.add("line " + lineNumber + ": benchmark build profile is missing or invalid");
            }
            long benchmarkGeneration = longValue(fields, "benchmark_generation", lineNumber, errors);
            String requestedProfile = requiredToken(fields, "requested_profile", lineNumber, errors);
            String profile = requiredToken(fields, "profile", lineNumber, errors);
            Boolean effectiveGbc = strictBoolean(fields, "effective_gbc", lineNumber, errors);
            Boolean effectiveDmgCompat = strictBoolean(fields, "effective_dmg_compat",
                    lineNumber, errors);
            String effectiveMode = requiredToken(fields, "effective_mode", lineNumber, errors);
            String executionMode = fields.getOrDefault("execution_mode", "accuracy");
            int speedModeInitial = integer(fields, "speed_mode_initial", lineNumber, errors);
            int speedModeFinal = integer(fields, "speed_mode_final", lineNumber, errors);
            int surfaceVoteHz = integer(fields, "surface_vote_hz", lineNumber, errors);
            int displayTargetHz = integer(fields, "display_target_hz", lineNumber, errors);
            int surfaceContentRateMillihz = integer(fields, "surface_content_rate_millihz",
                    lineNumber, errors);
            ClockFields clock = parseClock(fields, lineNumber, errors);
            String deviceId = deviceToken(fields, "device_id", lineNumber, errors);
            String workloadNonce = requiredToken(fields, "workload_nonce", lineNumber, errors);
            boolean warmup = booleanValue(fields, "warmup", lineNumber, errors);
            String inputContract = requiredToken(fields, "input_contract", lineNumber, errors);
            Boolean drainSuccess = strictBoolean(fields, "drain_success", lineNumber, errors);
            EnvironmentFields start = parseStartEnvironment(fields, lineNumber, errors);
            EnvironmentFields end = parseEndEnvironment(fields, lineNumber, errors);
            AudioFields audio = parseAudio(fields, lineNumber, errors);
            if (!requestedProfileMatches(requestedProfile, profile)) {
                errors.add("line " + lineNumber
                        + ": final requested profile does not match actual profile");
            }
            run.finalFields = new FinalFields(
                    artifactId, benchmarkGeneration, requestedProfile, profile, effectiveGbc, effectiveDmgCompat,
                    effectiveMode, executionMode, speedModeInitial, speedModeFinal, clock, deviceId, start, end,
                    audio,
                    integer(fields, "frame", lineNumber, errors),
                    integer(fields, "ready_count", lineNumber, errors),
                    integer(fields, "submitted_count", lineNumber, errors),
                    integer(fields, "dropped_count", lineNumber, errors),
                    integer(fields, "duplicate_count", lineNumber, errors),
                    integer(fields, "late_count", lineNumber, errors),
                    integer(fields, "corrupt_count", lineNumber, errors),
                    doubleValue(fields, "ready_interval_fps", lineNumber, errors),
                    doubleValue(fields, "submission_interval_fps", lineNumber, errors),
                    optionalLong(fields, "ready_first_id"), optionalLong(fields, "ready_last_id"),
                    optionalLong(fields, "ready_first_ns"), optionalLong(fields, "ready_last_ns"),
                    optionalLong(fields, "submission_first_id"),
                    optionalLong(fields, "submission_last_id"),
                    optionalLong(fields, "submission_first_ns"),
                    optionalLong(fields, "submission_last_ns"),
                    doubleValue(fields, "fps", lineNumber, errors),
                    longValue(fields, "wall_ms", lineNumber, errors),
                    doubleValue(fields, "controller_util_pct", lineNumber, errors),
                    longValue(fields, "controller_cpu_ms", lineNumber, errors),
                    longValue(fields, "gc_count_delta", lineNumber, errors),
                    longValue(fields, "gc_time_ms_delta", lineNumber, errors),
                    longValue(fields, "alloc_bytes_delta", lineNumber, errors),
                    workloadNonce, warmup, inputContract, Boolean.TRUE.equals(drainSuccess),
                    integer(fields, "environment_sample_count", lineNumber, errors),
                    integer(fields, "thermal_worst", lineNumber, errors),
                    integer(fields, "system_load_worst_milli", lineNumber, errors),
                    integer(fields, "cpu_freq_min_khz", lineNumber, errors),
                    integer(fields, "display_refresh_min_millihz", lineNumber, errors),
                    integer(fields, "display_bad_count", lineNumber, errors),
                    integer(fields, "interactive_bad_count", lineNumber, errors),
                    integer(fields, "plugged_bad_count", lineNumber, errors),
                    integer(fields, "power_save_bad_count", lineNumber, errors),
                    integer(fields, "stay_awake_bad_count", lineNumber, errors),
                    integer(fields, "priority_bad_count", lineNumber, errors),
                    integer(fields, "importance_bad_count", lineNumber, errors),
                    integer(fields, "battery_temp_min", lineNumber, errors),
                    integer(fields, "battery_temp_max", lineNumber, errors),
                    integer(fields, "live_input_mutations", lineNumber, errors), surfaceVoteHz,
                    displayTargetHz, surfaceContentRateMillihz);
        }
    }

    private static void addCompositor(Map<String, String> fields, int lineNumber,
            Map<String, RunBuilder> runs, List<String> errors) {
        RunBuilder run = findRun(fields, lineNumber, runs, errors);
        if (run == null) {
            return;
        }
        if (run.compositor != null) {
            errors.add("line " + lineNumber + ": duplicate compositor_result");
            return;
        }
        String artifactId = artifactToken(fields, "artifact_id", lineNumber, errors);
        String deviceId = deviceToken(fields, "device_id", lineNumber, errors);
        long generation = longValue(fields, "benchmark_generation", lineNumber, errors);
        String layerId = fields.get("layer_id");
        if (layerId == null || !layerId.matches("[0-9a-f]{64}")) {
            errors.add("line " + lineNumber + ": compositor layer identity is invalid");
        }
        long layerUid = longValue(fields, "layer_uid", lineNumber, errors);
        long rawTotalFrames = longValue(fields, "raw_total_frames", lineNumber, errors);
        long rawHistogramFrames = longValue(fields, "raw_histogram_frames", lineNumber, errors);
        long boundaryFrames = longValue(fields, "boundary_frames", lineNumber, errors);
        long boundaryIntervals = longValue(fields, "boundary_intervals", lineNumber, errors);
        long totalFrames = longValue(fields, "total_frames", lineNumber, errors);
        long histogramFrames = longValue(fields, "histogram_frames", lineNumber, errors);
        long presentIntervalCount = longValue(fields, "present_interval_count", lineNumber, errors);
        long cadenceGoodFrames = longValue(fields, "cadence_good_frames", lineNumber, errors);
        long cadenceBadFrames = longValue(fields, "cadence_bad_frames", lineNumber, errors);
        long cadenceVsyncTotal = longValue(fields, "cadence_vsync_total", lineNumber, errors);
        long cadenceBoundary200 = longValue(fields, "cadence_boundary_200_frames", lineNumber, errors);
        long cadenceBoundary1000 = longValue(fields, "cadence_boundary_1000_frames", lineNumber, errors);
        long cadenceMaxGapMs = longValue(fields, "cadence_max_gap_ms", lineNumber, errors);
        long cadenceMinGapMs = longValue(fields, "cadence_min_gap_ms", lineNumber, errors);
        double histogramFps = doubleValue(fields, "compositor_histogram_fps", lineNumber, errors);
        long droppedFrames = longValue(fields, "dropped_frames", lineNumber, errors);
        long lateFrames = longValue(fields, "late_acquire_frames", lineNumber, errors);
        long badFrames = longValue(fields, "bad_desired_present_frames", lineNumber, errors);
        int displayRefreshHz = integer(fields, "display_refresh_hz", lineNumber, errors);
        if (layerUid <= 0L || rawTotalFrames < 0L || rawHistogramFrames < 0L
                || boundaryFrames < 0L || boundaryIntervals < 0L || totalFrames < 0L
                || histogramFrames < 0L || presentIntervalCount < 0L
                || cadenceGoodFrames < 0L || cadenceBadFrames < 0L
                || cadenceVsyncTotal < 0L || cadenceBoundary200 < 0L || cadenceBoundary1000 < 0L
                || cadenceMaxGapMs < 0L || cadenceMinGapMs < 0L
                || !Double.isFinite(histogramFps)
                || droppedFrames < 0L || lateFrames < 0L || badFrames < 0L) {
            errors.add("line " + lineNumber + ": compositor counters are invalid");
        }
        run.compositor = new CompositorFields(artifactId, deviceId, generation, layerId, layerUid,
                rawTotalFrames, rawHistogramFrames, boundaryFrames, boundaryIntervals,
                totalFrames, histogramFrames, presentIntervalCount, cadenceGoodFrames,
                cadenceBadFrames, cadenceVsyncTotal, cadenceBoundary200, cadenceBoundary1000,
                cadenceMaxGapMs, cadenceMinGapMs, histogramFps,
                droppedFrames, lateFrames, badFrames,
                displayRefreshHz, lineNumber);
    }

    private static RunBuilder findRun(Map<String, String> fields, int lineNumber,
            Map<String, RunBuilder> runs, List<String> errors) {
        String artifactId = artifactToken(fields, "artifact_id", lineNumber, errors);
        String pairId = requiredToken(fields, "pair_id", lineNumber, errors);
        String block = requiredToken(fields, "matrix_block", lineNumber, errors);
        Side side = side(fields.get("run_side"), "run_side", lineNumber, errors);
        int rowOrder = integer(fields, "row_order", lineNumber, errors);
        if (pairId == null || block == null || side == null || artifactId == null) {
            return null;
        }
        RunBuilder run = runs.get(runKey(pairId, side, block, rowOrder));
        if (run == null) {
            errors.add("line " + lineNumber + ": frame has no matching matrix_run");
            return null;
        }
        if (!run.artifactId.equals(artifactId)) {
            errors.add("line " + lineNumber + ": artifact identity changed within run");
        }
        return run;
    }

    private static Report finish(Map<String, RunBuilder> runs, List<String> errors,
            long seed, int resamples, String expectedParentArtifact,
            String expectedCandidateArtifact) {
        LinkedHashMap<Row, List<RunBuilder>> byRow = new LinkedHashMap<>();
        for (Row row : REQUIRED_ROWS) {
            byRow.put(row, new ArrayList<>());
        }
        validateEventIntervals(runs.values(), errors);
        List<BlockSummary> completeBlocks = validateBlocks(runs.values(), errors);
        Set<String> completeBlockNames = new LinkedHashSet<>();
        for (BlockSummary block : completeBlocks) {
            completeBlockNames.add(block.name);
        }

        Set<String> artifactIds = new LinkedHashSet<>();
        Map<Side, Set<String>> artifactIdsBySide = new EnumMapCompat<>();
        Set<String> deviceIds = new LinkedHashSet<>();
        Set<String> thermalWindows = new LinkedHashSet<>();
        Set<String> renderModes = new LinkedHashSet<>();
        Set<Boolean> audioRequests = new LinkedHashSet<>();

        for (RunBuilder run : runs.values()) {
            artifactIds.add(run.artifactId);
            artifactIdsBySide.computeIfAbsent(run.side, ignored -> new LinkedHashSet<>())
                    .add(run.artifactId);
            deviceIds.add(run.deviceId);
            thermalWindows.add(run.thermalWindow);
            renderModes.add(run.render);
            audioRequests.add(run.audio);
            if (run.row == Row.CGB0_DMG_COMPAT && run.available) {
                errors.add(run.key + " is diagnostic-only cgb0-dmg-compat and cannot be measured");
            }
            if (run.row != null && REQUIRED_ROWS.contains(run.row) && run.available
                    && completeBlockNames.contains(run.matrixBlock)) {
                byRow.get(run.row).add(run);
            }
            validateRun(run, errors);
        }

        if (artifactIds.size() != 2) {
            errors.add("dataset must contain exactly two artifact identities");
        }
        for (Side side : Side.values()) {
            Set<String> ids = artifactIdsBySide.get(side);
            if (ids == null || ids.size() != 1) {
                errors.add(side.externalValue + " must have exactly one artifact identity");
            }
        }
        if (artifactIdsBySide.get(Side.PARENT) != null
                && artifactIdsBySide.get(Side.CANDIDATE) != null
                && artifactIdsBySide.get(Side.PARENT).equals(artifactIdsBySide.get(Side.CANDIDATE))) {
            errors.add("parent and candidate artifact identities must differ");
        }
        if (expectedParentArtifact != null && expectedCandidateArtifact != null) {
            if (!expectedParentArtifact.matches("[0-9a-f]{64}")
                    || !expectedCandidateArtifact.matches("[0-9a-f]{64}")) {
                errors.add("pinned artifact identities must be SHA-256");
            }
            if (expectedParentArtifact.equals(expectedCandidateArtifact)) {
                errors.add("pinned parent and candidate artifact identities must differ");
            }
            Set<String> parentIds = artifactIdsBySide.get(Side.PARENT);
            Set<String> candidateIds = artifactIdsBySide.get(Side.CANDIDATE);
            if (parentIds == null || !parentIds.contains(expectedParentArtifact)) {
                errors.add("parent artifact does not match pinned identity");
            }
            if (candidateIds == null || !candidateIds.contains(expectedCandidateArtifact)) {
                errors.add("candidate artifact does not match pinned identity");
            }
        }
        if (deviceIds.size() != 1) {
            errors.add("device identity is missing or mixed");
        }
        if (thermalWindows.size() > 1) {
            errors.add("thermal window mismatch");
        }
        if (renderModes.size() > 1) {
            errors.add("mixed render modes");
        }
        if (audioRequests.size() > 1) {
            errors.add("mixed audio requests");
        }

        validateRandomizedOrder(completeBlocks, errors);
        validateWorkloadNonces(runs.values(), completeBlocks, errors);

        LinkedHashMap<Row, RowSummary> summaries = new LinkedHashMap<>();
        for (Row row : REQUIRED_ROWS) {
            List<RunBuilder> rowRuns = byRow.get(row);
            List<Pair> pairs = pairRuns(row, rowRuns, errors);
            if (pairs.size() < MIN_PAIRS) {
                errors.add(row.externalValue + " has fewer than " + MIN_PAIRS + " paired runs");
            }
            if (!pairs.isEmpty() && resamples >= BOOTSTRAP_RESAMPLES) {
                summaries.put(row, summarize(row, pairs, seed, resamples));
            }
        }

        for (Row row : REQUIRED_ROWS) {
            if (byRow.get(row).isEmpty()) {
                boolean explicitUnavailable = hasExplicitUnavailable(runs.values(), row);
                errors.add(explicitUnavailable
                        ? row.externalValue + " is explicitly unavailable"
                        : row.externalValue + " row is missing");
            }
        }

        boolean visiblePresentation = renderModes.size() == 1
                && renderModes.contains("presentation");
        boolean pinnedArtifacts = expectedParentArtifact != null
                && expectedCandidateArtifact != null;
        boolean targetAudioEligible = true;
        for (RunBuilder run : runs.values()) {
            if (run.available && run.row != null && REQUIRED_ROWS.contains(run.row)) {
                targetAudioEligible &= run.audioTargetEligible;
            }
        }
        boolean accepted = errors.isEmpty() && pinnedArtifacts && visiblePresentation
                && summaries.size() == REQUIRED_ROWS.size() && targetAudioEligible;
        boolean valid = errors.isEmpty() && summaries.size() == REQUIRED_ROWS.size();
        for (RowSummary summary : summaries.values()) {
            accepted &= summary.lowerBoundPass && !summary.alarm && !summary.regression;
        }
        return new Report(valid, accepted, Collections.unmodifiableList(new ArrayList<>(errors)),
                Collections.unmodifiableMap(summaries), seed, resamples, expectedParentArtifact,
                expectedCandidateArtifact);
    }

    private static void validateRandomizedOrder(List<BlockSummary> blocks,
            List<String> errors) {
        if (blocks.size() < MIN_PAIRS) {
            errors.add("fewer than " + MIN_PAIRS + " complete matrix blocks");
        }
        Set<String> signatures = new LinkedHashSet<>();
        Side previous = null;
        for (BlockSummary block : blocks) {
            if (previous != null && previous == block.firstSide) {
                errors.add("parent/candidate first side did not alternate");
            }
            previous = block.firstSide;
            signatures.add(block.rowSignature);
        }
        if (blocks.size() > 1 && signatures.size() < 2) {
            errors.add("seven-row order was not randomized between blocks");
        }
    }

    /**
     * The nonce is assigned by the app's private recent catalog after selection.  It is not a
     * host claim about ROM contents, so the parser only accepts its opaque shape and verifies that
     * every repeated launch of one selected workload used the same app-owned value.  Color-capable
     * rows share one selection, as do non-color rows; cgb0-dmg-compat remains diagnostic-only.
     */
    private static void validateWorkloadNonces(Iterable<RunBuilder> runs,
            List<BlockSummary> completeBlocks, List<String> errors) {
        Set<String> completeNames = new LinkedHashSet<>();
        for (BlockSummary block : completeBlocks) {
            completeNames.add(block.name);
        }
        Map<Row, String> rowNonces = new LinkedHashMap<>();
        Map<String, String> groupNonces = new LinkedHashMap<>();
        for (RunBuilder run : runs) {
            if (!run.available || run.row == null || !completeNames.contains(run.matrixBlock)) {
                continue;
            }
            if (!run.workloadNonce.matches("[a-z0-9][a-z0-9._-]{15,63}")
                    || "unknown".equals(run.workloadNonce)
                    || "invalid".equals(run.workloadNonce)) {
                errors.add(run.key + " does not carry a valid app-owned workload nonce");
                continue;
            }
            String previous = rowNonces.putIfAbsent(run.row, run.workloadNonce);
            if (previous != null && !previous.equals(run.workloadNonce)) {
                errors.add(run.row.externalValue + " workload nonce changed across matrix blocks");
            }
            String group = switch (run.row) {
                case CGB_NATIVE, CGB0_NATIVE -> "color";
                case DMG, MGB, CGB_DMG_COMPAT, SGB, SGB2 -> "noncolor";
                default -> null;
            };
            if (group != null) {
                String priorGroup = groupNonces.putIfAbsent(group, run.workloadNonce);
                if (priorGroup != null && !priorGroup.equals(run.workloadNonce)) {
                    errors.add(group + " workload selection changed across rows");
                }
            }
        }
    }

    private static List<BlockSummary> validateBlocks(Iterable<RunBuilder> runs,
            List<String> errors) {
        Map<String, BlockState> states = new LinkedHashMap<>();
        ArrayList<RunBuilder> orderedRuns = new ArrayList<>();
        for (RunBuilder run : runs) {
            BlockState state = states.computeIfAbsent(run.matrixBlock, BlockState::new);
            state.runs.add(run);
            orderedRuns.add(run);
            if (run.row != null) {
                state.byRow.computeIfAbsent(run.row, ignored -> new ArrayList<>()).add(run);
            }
        }
        orderedRuns.sort(Comparator.comparingInt(run -> run.ingestionOrdinal));
        Set<String> closedBlocks = new LinkedHashSet<>();
        String previousBlock = null;
        for (RunBuilder run : orderedRuns) {
            if (previousBlock != null && !previousBlock.equals(run.matrixBlock)) {
                closedBlocks.add(previousBlock);
                if (closedBlocks.contains(run.matrixBlock)) {
                    errors.add("matrix blocks are interleaved at " + run.matrixBlock);
                }
            }
            previousBlock = run.matrixBlock;
        }
        ArrayList<BlockSummary> complete = new ArrayList<>();
        for (BlockState state : states.values()) {
            state.runs.sort(Comparator.comparingInt(run -> run.ingestionOrdinal));
            boolean ok = true;
            if (state.byRow.size() != REQUIRED_ROWS.size()
                    || !state.byRow.keySet().containsAll(REQUIRED_ROWS)
                    || state.runs.size() != REQUIRED_ROWS.size() * 2) {
                errors.add("matrix block " + state.name
                        + " does not contain exactly one parent/candidate pair for seven rows");
                ok = false;
            }
            ok &= validateObservedSequence(state, errors);
            Side firstSide = null;
            int firstOrdinal = Integer.MAX_VALUE;
            ArrayList<RunBuilder> firstRows = new ArrayList<>();
            for (Row row : REQUIRED_ROWS) {
                List<RunBuilder> rowRuns = state.byRow.get(row);
                if (rowRuns == null || rowRuns.size() != 2) {
                    ok = false;
                    continue;
                }
                RunBuilder parent = null;
                RunBuilder candidate = null;
                for (RunBuilder run : rowRuns) {
                    if (!run.available) {
                        ok = false;
                    } else if (run.side == Side.PARENT && parent == null) {
                        parent = run;
                    } else if (run.side == Side.CANDIDATE && candidate == null) {
                        candidate = run;
                    } else {
                        errors.add("matrix block " + state.name + " has duplicate "
                                + row.externalValue + " side");
                        ok = false;
                    }
                }
                if (parent == null || candidate == null
                        || !parent.pairId.equals(candidate.pairId)) {
                    errors.add("matrix block " + state.name + " has an incomplete "
                            + row.externalValue + " pair");
                    ok = false;
                } else {
                    RunBuilder earlier = parent.ingestionOrdinal < candidate.ingestionOrdinal
                            ? parent : candidate;
                    if (earlier.firstSide != earlier.side) {
                        errors.add(row.externalValue + " pair " + earlier.pairId
                                + " declares first_side=" + earlier.firstSide.externalValue
                                + " but the earlier matrix_run is " + earlier.side.externalValue);
                        ok = false;
                    }
                    firstRows.add(earlier);
                    firstSide = firstSide == null ? earlier.side : firstSide;
                    if (firstSide != earlier.side) {
                        errors.add("matrix block " + state.name + " alternates first side");
                        ok = false;
                    }
                    firstOrdinal = Math.min(firstOrdinal,
                            Math.min(parent.ingestionOrdinal, candidate.ingestionOrdinal));
                }
            }
            firstRows.sort(Comparator.comparingInt(run -> run.ingestionOrdinal));
            for (int expected = 0; expected < firstRows.size(); expected++) {
                if (firstRows.get(expected).rowOrder != expected) {
                    errors.add("matrix block " + state.name + " observed row "
                            + firstRows.get(expected).row.externalValue + " at order " + expected
                            + " but declared row_order=" + firstRows.get(expected).rowOrder);
                    ok = false;
                }
            }
            if (ok) {
                StringBuilder observedSignature = new StringBuilder();
                for (RunBuilder firstRow : firstRows) {
                    observedSignature.append(firstRow.row.externalValue()).append('/');
                }
                complete.add(new BlockSummary(state.name, firstOrdinal, firstSide,
                        observedSignature.toString()));
            }
        }
        complete.sort(Comparator.comparingInt(block -> block.firstOrdinal));
        return complete;
    }

    private static boolean validateObservedSequence(BlockState state, List<String> errors) {
        if (state.runs.size() != REQUIRED_ROWS.size() * 2) {
            return false;
        }
        boolean ok = true;
        for (int rowOrder = 0; rowOrder < REQUIRED_ROWS.size(); rowOrder++) {
            RunBuilder first = state.runs.get(rowOrder * 2);
            RunBuilder second = state.runs.get(rowOrder * 2 + 1);
            if (first.rowOrder != rowOrder || second.rowOrder != rowOrder
                    || first.row != second.row || !first.pairId.equals(second.pairId)
                    || first.side == second.side || first.firstSide != first.side
                    || second.firstSide != first.firstSide || second.side == first.firstSide
                    || first.finalOrdinal < 0 || second.finalOrdinal < 0
                    || first.finalOrdinal >= second.ingestionOrdinal) {
                errors.add("matrix block " + state.name
                        + " does not contain adjacent ordered two-side pairs with completed runs");
                ok = false;
            }
        }
        return ok;
    }

    /**
     * Matrix declarations are starts, not measurements. A run owns a closed interval from its
     * matrix_run through its final_result; frame records must be inside that interval and the next
     * run cannot start until the previous interval has closed. This catches logs that merely list
     * every declaration first and every completion later, or that overlap two active runs.
     */
    private static void validateEventIntervals(Iterable<RunBuilder> runs,
            List<String> errors) {
        ArrayList<RunBuilder> ordered = new ArrayList<>();
        for (RunBuilder run : runs) {
            if (run.available) {
                ordered.add(run);
            }
        }
        ordered.sort(Comparator.comparingInt(run -> run.ingestionOrdinal));
        RunBuilder previous = null;
        for (RunBuilder run : ordered) {
            if (run.finalOrdinal < 0) {
                // validateRun emits the user-facing missing-final error; keep this check focused on
                // interval ordering and do not manufacture a synthetic end point.
                previous = run;
                continue;
            }
            if (run.finalOrdinal <= run.ingestionOrdinal) {
                errors.add(run.key + " final_result does not follow matrix_run");
            }
            for (int ordinal : run.frameOrdinals) {
                if (ordinal <= run.ingestionOrdinal || ordinal >= run.finalOrdinal) {
                    errors.add(run.key + " frame event is outside its run interval");
                }
            }
            if ("presentation".equals(run.render)) {
                if (run.compositor == null) {
                    errors.add(run.key + " is missing terminal compositor_result");
                } else if (run.compositor.ordinal <= run.finalOrdinal) {
                    errors.add(run.key + " compositor_result must follow final_result");
                }
            }
            if (previous != null
                    && (previous.finalOrdinal < 0 || previous.finalOrdinal >= run.ingestionOrdinal)) {
                errors.add(run.key + " overlaps the preceding matrix run; final_result must precede the next matrix_run");
            }
            if (previous != null && "presentation".equals(previous.render)
                    && previous.compositor != null
                    && previous.compositor.ordinal >= run.ingestionOrdinal) {
                errors.add(run.key + " compositor_result must precede the next matrix_run");
            }
            previous = run;
        }
    }

    private static void validateRowOrderPermutations(Iterable<RunBuilder> runs,
            List<String> errors) {
        Map<String, Map<Integer, Row>> rowsByBlock = new LinkedHashMap<>();
        for (RunBuilder run : runs) {
            if (!run.available || run.row == null) {
                continue;
            }
            Map<Integer, Row> rowSlots = rowsByBlock.computeIfAbsent(
                    run.matrixBlock, ignored -> new LinkedHashMap<>());
            Row previous = rowSlots.putIfAbsent(run.rowOrder, run.row);
            if (previous != null && previous != run.row) {
                errors.add("matrix block " + run.matrixBlock
                        + " reuses a row order for different rows");
            }
        }
        for (Map.Entry<String, Map<Integer, Row>> entry : rowsByBlock.entrySet()) {
            if (entry.getValue().size() != REQUIRED_ROWS.size()
                    || !entry.getValue().keySet().containsAll(Set.of(0, 1, 2, 3, 4, 5, 6))) {
                errors.add("matrix block " + entry.getKey()
                        + " does not contain a seven-row order permutation");
            }
        }
        Set<String> signatures = new LinkedHashSet<>();
        for (Map<Integer, Row> rowSlots : rowsByBlock.values()) {
            StringBuilder signature = new StringBuilder();
            for (int rowOrder = 0; rowOrder < REQUIRED_ROWS.size(); rowOrder++) {
                signature.append(rowSlots.get(rowOrder)).append('/');
            }
            signatures.add(signature.toString());
        }
        if (rowsByBlock.size() > 1 && signatures.size() < 2) {
            errors.add("seven-row order was not randomized between blocks");
        }
    }

    private static void validateObservedRowOrder(Iterable<RunBuilder> runs,
            List<String> errors) {
        Map<String, Map<Row, RunBuilder>> firstByRowByBlock = new LinkedHashMap<>();
        for (RunBuilder run : runs) {
            if (!run.available || run.row == null) {
                continue;
            }
            Map<Row, RunBuilder> firstByRow = firstByRowByBlock.computeIfAbsent(
                    run.matrixBlock, ignored -> new LinkedHashMap<>());
            RunBuilder previous = firstByRow.get(run.row);
            if (previous == null || run.ingestionOrdinal < previous.ingestionOrdinal) {
                firstByRow.put(run.row, run);
            }
        }
        for (Map.Entry<String, Map<Row, RunBuilder>> entry : firstByRowByBlock.entrySet()) {
            List<RunBuilder> firstRows = new ArrayList<>(entry.getValue().values());
            firstRows.sort(Comparator.comparingInt(run -> run.ingestionOrdinal));
            if (firstRows.size() != REQUIRED_ROWS.size()) {
                continue;
            }
            for (int expectedOrder = 0; expectedOrder < REQUIRED_ROWS.size(); expectedOrder++) {
                RunBuilder observed = firstRows.get(expectedOrder);
                if (observed.rowOrder != expectedOrder) {
                    errors.add("matrix block " + entry.getKey()
                            + " observed row " + observed.row.externalValue
                            + " at order " + expectedOrder + " but declared row_order="
                            + observed.rowOrder);
                }
            }
        }
    }

    private static boolean hasExplicitUnavailable(Iterable<RunBuilder> runs, Row row) {
        for (RunBuilder run : runs) {
            if (run.row == row && !run.available) {
                return true;
            }
        }
        return false;
    }

    private static List<Pair> pairRuns(Row row, List<RunBuilder> rowRuns,
            List<String> errors) {
        Map<String, List<RunBuilder>> byPair = new LinkedHashMap<>();
        for (RunBuilder run : rowRuns) {
            if (!run.available) {
                errors.add(row.externalValue + " has an unavailable run mixed with measurements");
                continue;
            }
            byPair.computeIfAbsent(run.pairId, ignored -> new ArrayList<>()).add(run);
        }
        ArrayList<Pair> pairs = new ArrayList<>();
        for (Map.Entry<String, List<RunBuilder>> entry : byPair.entrySet()) {
            RunBuilder parent = null;
            RunBuilder candidate = null;
            for (RunBuilder run : entry.getValue()) {
                if (run.side == Side.PARENT) {
                    if (parent != null) {
                        errors.add(row.externalValue + " pair " + entry.getKey()
                                + " has duplicate parent run");
                    }
                    parent = run;
                } else {
                    if (candidate != null) {
                        errors.add(row.externalValue + " pair " + entry.getKey()
                                + " has duplicate candidate run");
                    }
                    candidate = run;
                }
            }
            if (parent == null || candidate == null) {
                errors.add(row.externalValue + " pair " + entry.getKey()
                        + " does not contain parent and candidate");
                continue;
            }
            Side observedFirstSide = parent.ingestionOrdinal < candidate.ingestionOrdinal
                    ? Side.PARENT : Side.CANDIDATE;
            if (parent.firstSide != observedFirstSide
                    || candidate.firstSide != observedFirstSide) {
                errors.add(row.externalValue + " pair " + entry.getKey()
                        + " declares first_side=" + parent.firstSide.externalValue
                        + " but the earlier matrix_run is "
                        + observedFirstSide.externalValue);
            }
            if (parent.rowOrder != candidate.rowOrder
                    || !parent.matrixBlock.equals(candidate.matrixBlock)
                    || parent.firstSide != candidate.firstSide
                    || parent.audio != candidate.audio
                    || !parent.render.equals(candidate.render)
                    || !parent.deviceId.equals(candidate.deviceId)
                    || !parent.thermalWindow.equals(candidate.thermalWindow)
                    || !parent.requestedProfile.equals(candidate.requestedProfile)
                    || !parent.profile.equals(candidate.profile)
                    || parent.effectiveGbc != candidate.effectiveGbc
                    || parent.effectiveDmgCompat != candidate.effectiveDmgCompat
                    || !parent.effectiveMode.equals(candidate.effectiveMode)
                    || !parent.workloadNonce.equals(candidate.workloadNonce)
                    || parent.warmup != candidate.warmup
                    || !parent.inputContract.equals(candidate.inputContract)
                    || parent.surfaceVoteHz != candidate.surfaceVoteHz
                    || parent.speedModeInitial != candidate.speedModeInitial
                    || parent.finalFields == null || candidate.finalFields == null
                    || parent.finalFields.speedModeInitial != candidate.finalFields.speedModeInitial
                    || parent.finalFields.speedModeFinal != candidate.finalFields.speedModeFinal
                    || parent.clock == null || candidate.clock == null
                    || !parent.clock.equals(candidate.clock)
                    || !compatibleSustainedEnvironment(parent.finalFields,
                            candidate.finalFields)
                    || !compatibleAudio(parent.finalFields.audio, candidate.finalFields.audio)
                    || !compatibleEnvironment(parent.environment, candidate.environment)) {
                errors.add(row.externalValue + " pair " + entry.getKey()
                        + " has mixed run configuration");
            }
            pairs.add(new Pair(entry.getKey(), parent, candidate));
        }
        pairs.sort(Comparator.comparing(pair -> pair.pairId));
        return pairs;
    }

    /**
     * Device telemetry is sampled independently for the two alternating launches. Stable display,
     * power, foreground, and priority evidence must match exactly; thermal/load measurements may
     * drift within bounded limits but never substitute for the per-run eligibility checks.
     */
    private static boolean compatibleEnvironment(EnvironmentFields parent,
            EnvironmentFields candidate) {
        if (parent == null || candidate == null
                || parent.displayRefreshMillihz != candidate.displayRefreshMillihz
                || parent.displayState != candidate.displayState
                || !parent.interactive.equals(candidate.interactive)
                || parent.plugged != candidate.plugged
                || !parent.powerSave.equals(candidate.powerSave)
                || !parent.stayAwake.equals(candidate.stayAwake)
                || parent.stayOnPluggedMask != candidate.stayOnPluggedMask
                || parent.threadPriority != candidate.threadPriority
                || parent.appImportance != candidate.appImportance
                || parent.cpuCount != candidate.cpuCount) {
            return false;
        }
        return Math.abs(parent.thermalStatus - candidate.thermalStatus) <= 0
                && Math.abs(parent.batteryTemperatureDeciC - candidate.batteryTemperatureDeciC) <= 20
                && Math.abs(parent.systemLoadMilli - candidate.systemLoadMilli) <= 1000
                && memoryWithinTolerance(parent.memoryAvailableBytes,
                        candidate.memoryAvailableBytes);
    }

    private static boolean compatibleSustainedEnvironment(FinalFields parent,
            FinalFields candidate) {
        if (parent == null || candidate == null
                || parent.environmentSampleCount != candidate.environmentSampleCount
                || parent.thermalWorst != candidate.thermalWorst
                || Math.abs(parent.systemLoadWorstMilli - candidate.systemLoadWorstMilli) > 1000) {
            return false;
        }
        if (parent.cpuFreqMinKHz <= 0 || candidate.cpuFreqMinKHz <= 0) {
            return false;
        }
        long difference = Math.abs((long) parent.cpuFreqMinKHz - candidate.cpuFreqMinKHz);
        return difference <= Math.max(parent.cpuFreqMinKHz, candidate.cpuFreqMinKHz) / 2L
                && parent.displayRefreshMinMillihz == candidate.displayRefreshMinMillihz
                && parent.displayBadCount == candidate.displayBadCount
                && parent.interactiveBadCount == candidate.interactiveBadCount
                && parent.pluggedBadCount == candidate.pluggedBadCount
                && parent.powerSaveBadCount == candidate.powerSaveBadCount
                && parent.stayAwakeBadCount == candidate.stayAwakeBadCount
                && parent.priorityBadCount == candidate.priorityBadCount
                && parent.importanceBadCount == candidate.importanceBadCount
                && Math.abs(parent.batteryTempMin - candidate.batteryTempMin) <= 20
                && Math.abs(parent.batteryTempMax - candidate.batteryTempMax) <= 20
                && parent.liveInputMutations == candidate.liveInputMutations;
    }

    private static boolean compatibleAudio(AudioFields parent, AudioFields candidate) {
        return parent != null && candidate != null
                && parent.sampleRate == candidate.sampleRate
                && parent.queueCapacityFrames == candidate.queueCapacityFrames
                && parent.maximumFrameBytes == candidate.maximumFrameBytes
                && parent.systemVolume == candidate.systemVolume
                && parent.systemVolumeMax == candidate.systemVolumeMax
                && parent.systemMusicMuted.equals(candidate.systemMusicMuted);
    }

    private static boolean memoryWithinTolerance(long first, long second) {
        if (first <= 0L || second <= 0L) {
            return false;
        }
        long difference = Math.abs(first - second);
        return difference <= Math.max(first, second) / 2L;
    }

    private static void validateRun(RunBuilder run, List<String> errors) {
        if (!run.audio) {
            errors.add(run.key + " has audio disabled");
        }
        if (!"presentation".equals(run.render) && !"sink".equals(run.render)) {
            errors.add(run.key + " does not use visible presentation output");
        }
        if (!run.available) {
            return;
        }
        if (run.finalFields == null) {
            errors.add(run.key + " is missing final_result");
            return;
        }
        FinalFields result = run.finalFields;
        if (!run.artifactId.equals(result.artifactId)
                || run.benchmarkGeneration != result.benchmarkGeneration
                || !run.requestedProfile.equals(result.requestedProfile)
                || !run.profile.equals(result.profile)
                || run.effectiveGbc != result.effectiveGbc
                || run.effectiveDmgCompat != result.effectiveDmgCompat
                || !run.effectiveMode.equals(result.effectiveMode)
                || !run.executionMode.equals(result.executionMode)
                || run.speedModeInitial != result.speedModeInitial
                || !run.clock.equals(result.clock)
                || !run.deviceId.equals(result.deviceId)
                || run.surfaceVoteHz != result.surfaceVoteHz
                || !run.workloadNonce.equals(result.workloadNonce)
                || !run.warmup || !result.warmup
                || !run.inputContract.equals(result.inputContract)
                || run.displayTargetHz != result.displayTargetHz
                || run.surfaceContentRateMillihz != result.surfaceContentRateMillihz
                || !run.environment.equals(result.environmentStart)
                || (run.audioStart != null && !run.audioStart.equals(audioStart(result.audio)))) {
            errors.add(run.key + " final evidence does not match matrix_run");
        }
        Row finalRow = recomputeRow(result.profile, result.effectiveGbc,
                result.effectiveDmgCompat);
        Row claimedRow = Row.fromExternalValue(result.effectiveMode);
        if (finalRow == null || finalRow != claimedRow || finalRow != run.row) {
            errors.add(run.key + " final effective_mode is not recomputed hardware evidence");
        } else if (result.clock == null || !result.clock.equals(expectedClock(finalRow))) {
            errors.add(run.key + " final exact clock identity does not match hardware row");
        }
        if (finalRow != null && !validSpeedTrajectory(finalRow, result.speedModeInitial,
                result.speedModeFinal)) {
            errors.add(run.key + " speed mode trajectory is invalid for hardware row");
        }
        if (finalRow != null && !validSurfaceVote(finalRow, result.surfaceVoteHz)) {
            errors.add(run.key + " surface vote is not valid for hardware row");
        }
        if (finalRow != null && !validSurfaceContentRate(finalRow,
                result.surfaceContentRateMillihz)) {
            errors.add(run.key + " Surface content rate is not exact for hardware row");
        }
        if (result.displayTargetHz != result.surfaceVoteHz) {
            errors.add(run.key + " display target does not match surface vote");
        }
        double maximumCadence = run.row.nominalFps * MAX_REAL_TIME_RATIO;
        if (result.readyIntervalFps > maximumCadence
                || (!"sink".equals(run.render)
                && result.presentationIntervalFps > maximumCadence)) {
            errors.add(run.key + " cadence exceeds the nominal hardware rate");
        }
        if (!validEnvironment(run.environment, result.environmentEnd, run.executionMode)) {
            errors.add(run.key + " intrinsic thermal/display/power evidence is missing or ineligible");
        }
        if (!validSustainedEnvironment(result, run.row, run.environment)) {
            errors.add(run.key + " periodic thermal/load/clock evidence is missing or ineligible");
        }
        boolean sink = "sink".equals(run.render);
        if (!sink && (run.environment == null || result.environmentEnd == null
                || run.environment.displayRefreshMillihz
                        < requiredDisplayRefreshMillihz(run.row)
                || result.environmentEnd.displayRefreshMillihz
                        < requiredDisplayRefreshMillihz(run.row)
                || run.environment.displayRefreshMillihz
                        != result.environmentEnd.displayRefreshMillihz)) {
            errors.add(run.key + " display refresh cannot sustain the row nominal cadence");
        }
        if (result.frame != REQUIRED_FRAME_COUNT
                || result.readyCount != REQUIRED_FRAME_COUNT
                || (!sink && result.submittedCount != REQUIRED_FRAME_COUNT)) {
            errors.add(run.key + " does not contain exactly 600 ready/surface-submission frames");
        }
        if (!result.drainSuccess) {
            errors.add(run.key + " did not complete the compositor drain post");
        }
        if (!sink && !validCompositor(run, result)) {
            errors.add(run.key + " is missing linked SurfaceFlinger compositor evidence");
        }
        if (result.droppedCount != 0 || result.duplicateCount != 0
                || result.lateCount != 0 || result.corruptCount != 0) {
            errors.add(run.key + " has dropped, duplicate, late, or corrupt surface submission");
        }
        if (!sink) {
            run.audioTargetEligible = validAudio(result.audio, run.row, result.clock,
                    result.readyCount, result.readyFirstNanos, result.readyLastNanos,
                    result.readyIntervalFps, true);
        }
        if (!sink && !validAudio(result.audio, run.row, result.clock, result.readyCount,
                result.readyFirstNanos, result.readyLastNanos, result.readyIntervalFps, false)) {
            errors.add(run.key + " intrinsic audio output evidence is missing or ineligible");
        }
        if (sink) {
            if (result.submittedCount != 0 || !run.submissionNanos.isEmpty()
                    || result.presentationFirstId != 0L || result.presentationLastId != 0L
                    || result.presentationFirstNanos != 0L || result.presentationLastNanos != 0L) {
                errors.add(run.key + " frame-sink run must have no surface submissions");
            }
            if (!run.readyNanos.isEmpty()) {
                if (run.readyNanos.size() != REQUIRED_FRAME_COUNT
                        || !contiguous(run.readyIds)
                        || !strictlyIncreasing(run.readyNanos)) {
                    errors.add(run.key + " has non-monotonic or incomplete frame-sink samples");
                }
                if (!close(result.readyIntervalFps, intervalFps(run.readyNanos))) {
                    errors.add(run.key + " reports an inexact frame-sink interval FPS");
                }
            } else if (!validReadySummary(result)) {
                errors.add(run.key + " is missing bounded frame-sink timestamps");
            }
            return;
        }
        boolean hasEventSamples = !run.readyNanos.isEmpty() || !run.submissionNanos.isEmpty();
        if (hasEventSamples) {
            if (run.readyNanos.size() != REQUIRED_FRAME_COUNT
                    || run.submissionNanos.size() != REQUIRED_FRAME_COUNT
                    || !contiguous(run.readyIds)
                    || !contiguous(run.submissionIds)
                    || !strictlyIncreasing(run.readyNanos)
                    || !strictlyIncreasing(run.submissionNanos)) {
                errors.add(run.key + " has non-monotonic or incomplete frame samples");
            }
        } else if (!validSummary(result)) {
            errors.add(run.key + " is missing bounded ready/surface-submission timestamp summaries");
        }
        if (hasEventSamples && run.readyNanos.size() >= 2 && run.submissionNanos.size() >= 2) {
            double readyFps = intervalFps(run.readyNanos);
            double submissionFps = intervalFps(run.submissionNanos);
            if (!close(result.readyIntervalFps, readyFps)
                    || !close(result.presentationIntervalFps, submissionFps)) {
                errors.add(run.key + " reports an inexact interval FPS");
            }
        }
    }

    private static boolean validSummary(FinalFields result) {
        return validReadySummary(result)
                && result.readyLastId - result.readyFirstId + 1L == result.readyCount
                && result.presentationFirstId > 0L
                && result.presentationLastId >= result.presentationFirstId
                && result.presentationLastId - result.presentationFirstId + 1L
                        == result.submittedCount
                && result.readyFirstNanos > 0L && result.readyLastNanos > result.readyFirstNanos
                && result.presentationFirstNanos > 0L
                && result.presentationLastNanos > result.presentationFirstNanos
                && close(result.readyIntervalFps,
                        intervalFps(result.readyCount, result.readyFirstNanos,
                                result.readyLastNanos))
                && close(result.presentationIntervalFps,
                        intervalFps(result.submittedCount, result.presentationFirstNanos,
                                result.presentationLastNanos));
    }

    private static boolean validCompositor(RunBuilder run, FinalFields result) {
        CompositorFields compositor = run.compositor;
        return compositor != null
                && compositor.ordinal > run.finalOrdinal
                && run.finalOrdinal >= 0
                && run.artifactId.equals(compositor.artifactId)
                && run.deviceId.equals(compositor.deviceId)
                && run.benchmarkGeneration == compositor.benchmarkGeneration
                && compositor.layerUid > 0L
                && compositor.boundaryFrames == 1L
                && compositor.boundaryIntervals == 2L
                && compositor.rawTotalFrames == compositor.totalFrames + compositor.boundaryFrames
                && compositor.rawHistogramFrames
                        == compositor.histogramFrames + compositor.boundaryIntervals
                && compositor.totalFrames == REQUIRED_FRAME_COUNT
                && compositor.histogramFrames == REQUIRED_FRAME_COUNT - 1L
                && compositor.rawTotalFrames == REQUIRED_FRAME_COUNT + 1L
                && compositor.rawHistogramFrames == REQUIRED_FRAME_COUNT + 1L
                && compositor.presentIntervalCount == REQUIRED_FRAME_COUNT - 1L
                && compositor.cadenceGoodFrames + compositor.cadenceBadFrames
                        == compositor.presentIntervalCount
                && compositor.cadenceGoodFrames == REQUIRED_FRAME_COUNT - 1L
                && compositor.cadenceBadFrames == 0L
                && Double.isFinite(result.readyIntervalFps)
                && result.readyIntervalFps > 0.0
                && compositor.displayRefreshHz > 0
                // Accuracy/reference runs may be slower than real time.  Their compositor
                // intervals therefore span more display-vsyncs; derive the admissible lane
                // count from the observed ready cadence instead of conflating validity with the
                // performance target.  The histogram still has to be coherent with that cadence
                // below, and the separate summary gate decides whether it reaches real time.
                && compositor.cadenceVsyncTotal >= Math.max(1L,
                        Math.round((REQUIRED_FRAME_COUNT - 1L)
                                * compositor.displayRefreshHz / result.readyIntervalFps) - 2L)
                && compositor.cadenceVsyncTotal <= Math.round((REQUIRED_FRAME_COUNT - 1L)
                        * compositor.displayRefreshHz / result.readyIntervalFps) + 2L
                && compositor.cadenceBoundary200 == 1L
                && compositor.cadenceBoundary1000 == 1L
                && Double.isFinite(compositor.histogramFps)
                && close(compositor.histogramFps,
                        compositor.presentIntervalCount * compositor.displayRefreshHz
                                / (double) compositor.cadenceVsyncTotal)
                && compositor.histogramFps >= result.readyIntervalFps * MIN_REAL_TIME_RATIO
                && compositor.histogramFps <= result.readyIntervalFps * MAX_REAL_TIME_RATIO
                && compositor.histogramFps <= run.row.nominalFps * MAX_REAL_TIME_RATIO
                && compositor.cadenceMaxGapMs >= compositor.cadenceMinGapMs
                && compositor.droppedFrames == 0L
                && compositor.lateFrames == 0L
                && compositor.badDesiredPresentFrames == 0L
                && compositor.displayRefreshHz >= requiredDisplayRefreshMillihz(run.row) / 1000
                && compositor.displayRefreshHz >= run.surfaceVoteHz;
    }

    private static boolean validReadySummary(FinalFields result) {
        return result.readyFirstId > 0L && result.readyLastId >= result.readyFirstId
                && result.readyLastId - result.readyFirstId + 1L == result.readyCount
                && result.readyFirstNanos > 0L && result.readyLastNanos > result.readyFirstNanos
                && close(result.readyIntervalFps,
                        intervalFps(result.readyCount, result.readyFirstNanos,
                                result.readyLastNanos));
    }

    private static double intervalFps(int count, long firstNanos, long lastNanos) {
        if (count < 2 || firstNanos <= 0L || lastNanos <= firstNanos) {
            return 0.0;
        }
        return (count - 1L) * 1_000_000_000.0 / (lastNanos - firstNanos);
    }

    private static boolean strictlyIncreasing(List<Long> values) {
        long previous = Long.MIN_VALUE;
        for (long value : values) {
            if (value <= previous) {
                return false;
            }
            previous = value;
        }
        return true;
    }

    private static boolean contiguous(List<Long> values) {
        if (values.isEmpty()) {
            return false;
        }
        long previous = values.get(0);
        if (previous <= 0L) {
            return false;
        }
        for (int index = 1; index < values.size(); index++) {
            long current = values.get(index);
            if (current != previous + 1L) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    static double intervalFps(List<Long> timestampsNanos) {
        if (timestampsNanos == null || timestampsNanos.size() < 2) {
            return 0.0;
        }
        long first = timestampsNanos.get(0);
        long last = timestampsNanos.get(timestampsNanos.size() - 1);
        if (first <= 0L || last <= first) {
            return 0.0;
        }
        return (timestampsNanos.size() - 1L) * 1_000_000_000.0 / (last - first);
    }

    private static boolean close(double actual, double expected) {
        return Double.isFinite(actual) && Math.abs(actual - expected) <= 0.01;
    }

    private static RowSummary summarize(Row row, List<Pair> pairs, long seed, int resamples) {
        double[] candidates = new double[pairs.size()];
        double[] effects = new double[pairs.size()];
        double minimumCandidate = Double.POSITIVE_INFINITY;
        double minimumRun = Double.POSITIVE_INFINITY;
        for (int index = 0; index < pairs.size(); index++) {
            Pair pair = pairs.get(index);
            candidates[index] = submissionFps(pair.candidate());
            double parent = submissionFps(pair.parent());
            effects[index] = 100.0 * (candidates[index] / parent - 1.0);
            minimumCandidate = Math.min(minimumCandidate, candidates[index]);
            minimumRun = Math.min(minimumRun, Math.min(candidates[index], parent));
        }
        long rowSeed = seed ^ (0x9e3779b97f4a7c15L * (row.ordinal() + 1L));
        BootstrapInterval candidate = bootstrap(candidates, rowSeed, resamples);
        BootstrapInterval effect = bootstrap(effects, ~rowSeed, resamples);
        boolean alarm = minimumRun < RUN_FPS_ALARM;
        boolean regression = effect.upper < REGRESSION_PERCENT;
        double nominalFps = row.nominalFps;
        ArrayList<RunEvidence> evidence = new ArrayList<>(pairs.size() * 2);
        for (Pair pair : pairs) {
            evidence.add(runEvidence(pair.parent));
            evidence.add(runEvidence(pair.candidate));
        }
        return new RowSummary(row, pairs.size(), minimumCandidate, minimumRun, nominalFps, candidate,
                candidate.median / nominalFps, effect, alarm,
                regression, candidate.lower >= nominalFps * MIN_REAL_TIME_RATIO,
                Collections.unmodifiableList(evidence));
    }

    private static RunEvidence runEvidence(RunBuilder run) {
        FinalFields result = run.finalFields;
        boolean environmentEligible = result != null
                && validEnvironment(run.environment, result.environmentEnd, run.executionMode);
        boolean audioEligible = result != null && validAudio(result.audio, run.row, result.clock,
                result.readyCount, result.readyFirstNanos, result.readyLastNanos,
                result.readyIntervalFps, true);
        return new RunEvidence(run.pairId, run.side.externalValue, run.artifactId,
                run.benchmarkGeneration, run.deviceId,
                run.requestedProfile, run.profile, run.effectiveMode, run.speedModeInitial,
                result == null ? -1 : result.speedModeFinal,
                run.clock == null ? -1L : run.clock.ticksNumerator,
                run.clock == null ? -1L : run.clock.ticksDenominator,
                run.clock == null ? -1L : run.clock.framesNumerator,
                run.clock == null ? -1L : run.clock.framesDenominator,
                run.clock == null ? -1L : run.clock.ticksPerControllerFrame,
                run.displayTargetHz, run.surfaceContentRateMillihz,
                result == null ? -1 : result.readyCount,
                result == null ? -1 : result.submittedCount,
                result == null ? -1 : result.droppedCount,
                result == null ? -1 : result.duplicateCount,
                result == null ? -1 : result.lateCount,
                result == null ? -1 : result.corruptCount,
                audioEligible, environmentEligible,
                validCompositor(run, result),
                run.compositor == null ? "unknown" : run.compositor.layerId,
                run.compositor == null ? -1L : run.compositor.layerUid,
                run.compositor == null ? -1L : run.compositor.totalFrames,
                run.compositor == null ? -1L : run.compositor.histogramFrames,
                run.compositor == null ? -1L : run.compositor.rawTotalFrames,
                run.compositor == null ? -1L : run.compositor.rawHistogramFrames,
                run.compositor == null ? -1L : run.compositor.boundaryFrames,
                run.compositor == null ? -1L : run.compositor.boundaryIntervals,
                run.compositor == null ? -1L : run.compositor.presentIntervalCount,
                run.compositor == null ? -1L : run.compositor.cadenceGoodFrames,
                run.compositor == null ? -1L : run.compositor.cadenceBadFrames,
                run.compositor == null ? -1L : run.compositor.cadenceVsyncTotal,
                run.compositor == null ? -1L : run.compositor.cadenceBoundary200,
                run.compositor == null ? -1L : run.compositor.cadenceBoundary1000,
                run.compositor == null ? -1L : run.compositor.cadenceMaxGapMs,
                run.compositor == null ? Double.NaN : run.compositor.histogramFps,
                run.compositor == null ? -1L : run.compositor.droppedFrames,
                run.compositor == null ? -1L : run.compositor.lateFrames,
                run.compositor == null ? -1L : run.compositor.badDesiredPresentFrames,
                run.compositor == null ? -1 : run.compositor.displayRefreshHz,
                result == null || result.audio == null ? -1 : result.audio.sampleRate,
                result == null || result.audio == null ? -1 : result.audio.enqueuedFrames,
                result == null || result.audio == null ? -1 : result.audio.writtenFrames,
                result == null || result.audio == null ? -1 : result.audio.enqueuedBytes,
                result == null || result.audio == null ? -1 : result.audio.writtenBytes,
                result == null || result.audio == null ? -1 : result.audio.discardedBytes,
                result == null || result.audio == null ? -1 : result.audio.pendingBytes,
                result == null || result.audio == null ? -1 : result.audio.queuedBytes,
                result == null || result.audio == null ? -1 : result.audio.queueFrames,
                result == null || result.audio == null ? -1 : result.audio.writeFailures,
                result == null ? Double.NaN : result.readyIntervalFps,
                result == null ? Double.NaN : result.presentationIntervalFps,
                run.environment == null ? -1 : run.environment.thermalStatus,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.thermalStatus,
                run.environment == null ? -1 : run.environment.batteryTemperatureDeciC,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.batteryTemperatureDeciC,
                run.environment == null ? -1 : run.environment.displayRefreshMillihz,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.displayRefreshMillihz,
                run.environment == null ? -1 : run.environment.systemLoadMilli,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.systemLoadMilli,
                run.environment == null ? -1 : run.environment.cpuCount,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.cpuCount,
                run.environment == null ? -1 : run.environment.stayOnPluggedMask,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.stayOnPluggedMask,
                run.environment == null ? -1 : run.environment.threadPriority,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.threadPriority,
                run.environment == null ? -1 : run.environment.appImportance,
                result == null || result.environmentEnd == null
                        ? -1 : result.environmentEnd.appImportance,
                run.workloadNonce, run.warmup, run.inputContract,
                result == null ? -1L : result.wallMs,
                result == null ? Double.NaN : result.fps,
                result == null ? -1L : result.controllerCpuMs,
                result == null ? Double.NaN : result.controllerUtilPct,
                result == null ? -1L : result.gcCountDelta,
                result == null ? -1L : result.gcTimeMsDelta,
                result == null ? -1L : result.allocBytesDelta,
                result != null && result.audio != null && Boolean.TRUE.equals(result.audio.outputOpen),
                result != null && result.audio != null && Boolean.TRUE.equals(result.audio.outputPlaying),
                result != null && result.audio != null && Boolean.TRUE.equals(result.audio.muted),
                result == null || result.audio == null ? -1 : result.audio.volume,
                result == null || result.audio == null ? -1L : result.audio.routeFailures,
                result == null ? -1 : result.environmentSampleCount,
                result == null ? -1 : result.thermalWorst,
                result == null ? -1 : result.systemLoadWorstMilli,
                result == null ? -1 : result.cpuFreqMinKHz);
    }

    private static double submissionFps(RunBuilder run) {
        if (run.render.equals("presentation") && run.compositor != null
                && Double.isFinite(run.compositor.histogramFps)) {
            // SurfaceFlinger present2present histogram estimate is the visible/compositor metric;
            // producer unlockCanvasAndPost timestamps remain a separate diagnostic field.
            return run.compositor.histogramFps;
        }
        if (run.submissionNanos.size() >= 2) {
            return intervalFps(run.submissionNanos);
        }
        if ("sink".equals(run.render)) {
            return run.finalFields == null ? 0.0 : intervalFps(run.finalFields.readyCount,
                    run.finalFields.readyFirstNanos, run.finalFields.readyLastNanos);
        }
        return run.finalFields == null ? 0.0 : intervalFps(run.finalFields.submittedCount,
                run.finalFields.presentationFirstNanos, run.finalFields.presentationLastNanos);
    }

    private static BootstrapInterval bootstrap(double[] values, long seed, int resamples) {
        double observed = median(values);
        double[] distribution = new double[resamples];
        Random random = new Random(seed);
        double[] sample = new double[values.length];
        for (int iteration = 0; iteration < resamples; iteration++) {
            for (int index = 0; index < sample.length; index++) {
                sample[index] = values[random.nextInt(values.length)];
            }
            distribution[iteration] = median(sample);
        }
        Arrays.sort(distribution);
        int lowerIndex = (int) Math.floor(0.025 * (distribution.length - 1));
        int upperIndex = (int) Math.ceil(0.975 * (distribution.length - 1));
        return new BootstrapInterval(observed, distribution[lowerIndex], distribution[upperIndex]);
    }

    private static double median(double[] values) {
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        return medianSorted(sorted);
    }

    private static double medianSorted(double[] sorted) {
        int middle = sorted.length / 2;
        return (sorted.length & 1) == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    private static String runKey(String pairId, Side side, String block, int rowOrder) {
        return pairId + "|" + side.externalValue + "|" + block + "|" + rowOrder;
    }

    private static Side side(String value, String field, int lineNumber, List<String> errors) {
        Side side = value == null ? null : Side.fromExternalValue(value.toLowerCase(Locale.ROOT));
        if (side == null) {
            errors.add("line " + lineNumber + ": invalid " + field);
        }
        return side;
    }

    private static String artifactToken(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = requiredToken(fields, key, lineNumber, errors);
        if (value != null && !value.matches("[0-9a-f]{64}")) {
            errors.add("line " + lineNumber + ": artifact identity must be SHA-256");
            return null;
        }
        return value;
    }

    private static String deviceToken(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = requiredToken(fields, key, lineNumber, errors);
        if (value != null && !value.matches("[0-9a-f]{64}")) {
            errors.add("line " + lineNumber + ": device identity must be SHA-256");
            return null;
        }
        return value;
    }

    private static Boolean strictBoolean(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = fields.get(key);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        errors.add("line " + lineNumber + ": invalid " + key);
        return null;
    }

    private static EnvironmentFields parseStartEnvironment(Map<String, String> fields,
            int lineNumber, List<String> errors) {
        return parseEnvironment(fields, "start", lineNumber, errors);
    }

    private static ClockFields parseClock(Map<String, String> fields, int lineNumber,
            List<String> errors) {
        long ticksNumerator = longValue(fields, "clock_ticks_num", lineNumber, errors);
        long ticksDenominator = longValue(fields, "clock_ticks_den", lineNumber, errors);
        long framesNumerator = longValue(fields, "clock_frames_num", lineNumber, errors);
        long framesDenominator = longValue(fields, "clock_frames_den", lineNumber, errors);
        long ticksFrame = longValue(fields, "clock_ticks_frame", lineNumber, errors);
        if (ticksNumerator <= 0L || ticksDenominator <= 0L || framesNumerator <= 0L
                || framesDenominator <= 0L || ticksFrame <= 0L) {
            errors.add("line " + lineNumber + ": missing or invalid exact clock identity");
            return null;
        }
        return new ClockFields(ticksNumerator, ticksDenominator, framesNumerator,
                framesDenominator, ticksFrame);
    }

    private static EnvironmentFields parseEndEnvironment(Map<String, String> fields,
            int lineNumber, List<String> errors) {
        return parseEnvironment(fields, "end", lineNumber, errors);
    }

    private static EnvironmentFields parseEnvironment(Map<String, String> fields, String suffix,
            int lineNumber, List<String> errors) {
        return new EnvironmentFields(
                integer(fields, "thermal_" + suffix, lineNumber, errors),
                integer(fields, "battery_temp_" + suffix, lineNumber, errors),
                integer(fields, "display_refresh_" + suffix + "_millihz", lineNumber, errors),
                integer(fields, "display_state_" + suffix, lineNumber, errors),
                strictBoolean(fields, "interactive_" + suffix, lineNumber, errors),
                integer(fields, "plugged_" + suffix, lineNumber, errors),
                strictBoolean(fields, "power_save_" + suffix, lineNumber, errors),
                strictBoolean(fields, "stay_awake_" + suffix, lineNumber, errors),
                integer(fields, "stay_on_plugged_mask_" + suffix, lineNumber, errors),
                integer(fields, "thread_priority_" + suffix, lineNumber, errors),
                integer(fields, "app_importance_" + suffix, lineNumber, errors),
                integer(fields, "system_load_" + suffix + "_milli", lineNumber, errors),
                longValue(fields, "memory_available_" + suffix + "_bytes", lineNumber, errors),
                integer(fields, "cpu_count_" + suffix, lineNumber, errors));
    }

    private static AudioFields parseAudio(Map<String, String> fields, int lineNumber,
            List<String> errors) {
        return new AudioFields(
                strictBoolean(fields, "audio_active", lineNumber, errors),
                integer(fields, "audio_sample_rate", lineNumber, errors),
                longValue(fields, "audio_overruns", lineNumber, errors),
                longValue(fields, "audio_underruns", lineNumber, errors),
                longValue(fields, "audio_track_underruns", lineNumber, errors),
                longValue(fields, "audio_restarts", lineNumber, errors),
                strictBoolean(fields, "audio_paused", lineNumber, errors),
                integer(fields, "audio_min_buffer_bytes", lineNumber, errors),
                integer(fields, "audio_configured_buffer_bytes", lineNumber, errors),
                integer(fields, "audio_actual_buffer_bytes", lineNumber, errors),
                longValue(fields, "audio_pcm_input_events", lineNumber, errors),
                longValue(fields, "audio_pcm_input_frames", lineNumber, errors),
                longValue(fields, "audio_pcm_enqueued_bytes", lineNumber, errors),
                longValue(fields, "audio_pcm_enqueued_frames", lineNumber, errors),
                longValue(fields, "audio_pcm_written_bytes", lineNumber, errors),
                longValue(fields, "audio_pcm_written_frames", lineNumber, errors),
                longValue(fields, "audio_write_failures", lineNumber, errors),
                longValue(fields, "audio_pcm_discarded_bytes", lineNumber, errors),
                longValue(fields, "audio_pcm_pending_bytes", lineNumber, errors),
                longValue(fields, "audio_pcm_queued_bytes", lineNumber, errors),
                integer(fields, "audio_queue_frames", lineNumber, errors),
                strictBoolean(fields, "audio_output_open", lineNumber, errors),
                strictBoolean(fields, "audio_output_playing", lineNumber, errors),
                strictBoolean(fields, "audio_muted", lineNumber, errors),
                integer(fields, "audio_volume", lineNumber, errors),
                longValue(fields, "audio_route_failures", lineNumber, errors),
                longValue(fields, "audio_playback_position_frames", lineNumber, errors),
                integer(fields, "audio_system_volume", lineNumber, errors),
                integer(fields, "audio_system_volume_max", lineNumber, errors),
                strictBoolean(fields, "audio_system_music_muted", lineNumber, errors),
                integer(fields, "audio_queue_capacity_frames", lineNumber, errors),
                integer(fields, "audio_max_frame_bytes", lineNumber, errors),
                longValue(fields, "audio_start_input_events", lineNumber, errors),
                longValue(fields, "audio_start_input_frames", lineNumber, errors),
                longValue(fields, "audio_start_enqueued_bytes", lineNumber, errors),
                longValue(fields, "audio_start_enqueued_frames", lineNumber, errors),
                longValue(fields, "audio_start_written_bytes", lineNumber, errors),
                longValue(fields, "audio_start_written_frames", lineNumber, errors),
                longValue(fields, "audio_start_write_failures", lineNumber, errors),
                longValue(fields, "audio_start_discarded_bytes", lineNumber, errors),
                longValue(fields, "audio_start_pending_bytes", lineNumber, errors),
                longValue(fields, "audio_start_queued_bytes", lineNumber, errors),
                longValue(fields, "audio_start_playback_position_frames", lineNumber, errors),
                longValue(fields, "audio_start_overruns", lineNumber, errors),
                longValue(fields, "audio_start_underruns", lineNumber, errors),
                longValue(fields, "audio_start_track_underruns", lineNumber, errors),
                longValue(fields, "audio_start_restarts", lineNumber, errors),
                longValue(fields, "audio_start_route_failures", lineNumber, errors),
                strictBoolean(fields, "audio_start_output_open", lineNumber, errors),
                strictBoolean(fields, "audio_start_output_playing", lineNumber, errors),
                integer(fields, "audio_start_sample_rate", lineNumber, errors),
                integer(fields, "audio_start_queue_capacity_frames", lineNumber, errors),
                integer(fields, "audio_start_max_frame_bytes", lineNumber, errors),
                strictBoolean(fields, "audio_focus_granted", lineNumber, errors),
                longValue(fields, "audio_focus_start_loss_count", lineNumber, errors),
                longValue(fields, "audio_focus_loss_count", lineNumber, errors));
    }

    /**
     * The matrix declaration repeats the audio counter baseline so a final record cannot silently
     * replace it after ARM.  Older diagnostic-only/sink fixtures may omit the optional block; if
     * any start key is present, however, every value is parsed and bound exactly.
     */
    private static AudioStartFields parseMatrixAudioStart(Map<String, String> fields,
            int lineNumber, List<String> errors) {
        if (!fields.keySet().stream().anyMatch(key -> key.startsWith("audio_start_"))) {
            return null;
        }
        return new AudioStartFields(
                longValue(fields, "audio_start_input_events", lineNumber, errors),
                longValue(fields, "audio_start_input_frames", lineNumber, errors),
                longValue(fields, "audio_start_enqueued_bytes", lineNumber, errors),
                longValue(fields, "audio_start_enqueued_frames", lineNumber, errors),
                longValue(fields, "audio_start_written_bytes", lineNumber, errors),
                longValue(fields, "audio_start_written_frames", lineNumber, errors),
                longValue(fields, "audio_start_write_failures", lineNumber, errors),
                longValue(fields, "audio_start_discarded_bytes", lineNumber, errors),
                longValue(fields, "audio_start_pending_bytes", lineNumber, errors),
                longValue(fields, "audio_start_queued_bytes", lineNumber, errors),
                longValue(fields, "audio_start_playback_position_frames", lineNumber, errors),
                longValue(fields, "audio_start_overruns", lineNumber, errors),
                longValue(fields, "audio_start_underruns", lineNumber, errors),
                longValue(fields, "audio_start_track_underruns", lineNumber, errors),
                longValue(fields, "audio_start_restarts", lineNumber, errors),
                longValue(fields, "audio_start_route_failures", lineNumber, errors),
                strictBoolean(fields, "audio_start_output_open", lineNumber, errors),
                strictBoolean(fields, "audio_start_output_playing", lineNumber, errors),
                integer(fields, "audio_start_sample_rate", lineNumber, errors),
                integer(fields, "audio_start_queue_capacity_frames", lineNumber, errors),
                integer(fields, "audio_start_max_frame_bytes", lineNumber, errors));
    }

    private static AudioStartFields audioStart(AudioFields audio) {
        return audio == null ? null : new AudioStartFields(
                audio.startInputEvents, audio.startInputFrames, audio.startEnqueuedBytes,
                audio.startEnqueuedFrames, audio.startWrittenBytes, audio.startWrittenFrames,
                audio.startWriteFailures, audio.startDiscardedBytes, audio.startPendingBytes,
                audio.startQueuedBytes, audio.startPlaybackPositionFrames, audio.startOverruns,
                audio.startUnderruns, audio.startOutputUnderruns, audio.startRestarts,
                audio.startRouteFailures, audio.startOutputOpen, audio.startOutputPlaying,
                audio.startSampleRate, audio.startQueueCapacityFrames,
                audio.startMaximumFrameBytes);
    }

    private static Row recomputeRow(String profile, Boolean effectiveGbc,
            Boolean effectiveDmgCompat) {
        if (profile == null || effectiveGbc == null || effectiveDmgCompat == null) {
            return null;
        }
        return switch (profile) {
            case "dmg" -> !effectiveGbc && !effectiveDmgCompat ? Row.DMG : null;
            case "mgb" -> !effectiveGbc && !effectiveDmgCompat ? Row.MGB : null;
            case "cgb" -> effectiveGbc
                    ? (effectiveDmgCompat ? Row.CGB_DMG_COMPAT : Row.CGB_NATIVE) : null;
            case "cgb0" -> effectiveGbc
                    ? (effectiveDmgCompat ? Row.CGB0_DMG_COMPAT : Row.CGB0_NATIVE) : null;
            case "sgb" -> !effectiveGbc && !effectiveDmgCompat ? Row.SGB : null;
            case "sgb2" -> !effectiveGbc && !effectiveDmgCompat ? Row.SGB2 : null;
            default -> null;
        };
    }

    private static ClockFields expectedClock(Row row) {
        return switch (row) {
            case SGB -> new ClockFields(47_250_000L, 11L, 47_250_000L, 772_464L, 70_224L);
            case SGB2 -> new ClockFields(4_194_304L, 1L, 4_194_304L, 70_224L, 70_224L);
            default -> new ClockFields(4_194_304L, 1L, 60L, 1L, 69_905L);
        };
    }

    private static boolean requestedProfileMatches(String requestedProfile, String profile) {
        if (requestedProfile == null || profile == null) {
            return false;
        }
        return Set.of("dmg", "mgb", "cgb", "cgb0", "sgb", "sgb2")
                .contains(requestedProfile) && requestedProfile.equals(profile);
    }

    private static boolean validSpeedTrajectory(Row row, int initial, int finalMode) {
        if (initial != 1 && initial != 2 || finalMode != 1 && finalMode != 2) {
            return false;
        }
        // Only native CGB silicon can change speed during a measured run.  All other rows use
        // the single-speed clock identity above; accepting a relabeled speed would invalidate
        // the exact cadence and audio-duration checks.
        if (row == Row.CGB_NATIVE || row == Row.CGB0_NATIVE) {
            return true;
        }
        return initial == 1 && finalMode == 1;
    }

    private static boolean validEnvironment(EnvironmentFields start, EnvironmentFields end,
            String executionMode) {
        int expectedPriority = "performance".equals(executionMode)
                ? AndroidPerformanceBoost.PERFORMANCE_THREAD_PRIORITY : 0;
        if (start == null || end == null
                || start.thermalStatus != MAX_THERMAL_STATUS
                || end.thermalStatus != MAX_THERMAL_STATUS
                || start.displayRefreshMillihz <= 0
                || start.displayRefreshMillihz < MIN_DISPLAY_REFRESH_MILLIHZ
                || start.displayRefreshMillihz != end.displayRefreshMillihz
                || end.displayRefreshMillihz <= 0
                || end.displayRefreshMillihz < MIN_DISPLAY_REFRESH_MILLIHZ
                || start.displayState != 2 || end.displayState != 2
                || !Boolean.TRUE.equals(start.interactive)
                || !Boolean.TRUE.equals(end.interactive)
                || start.plugged <= 0 || end.plugged <= 0
                || start.stayOnPluggedMask < 0 || end.stayOnPluggedMask < 0
                || (start.stayOnPluggedMask & start.plugged) == 0
                || (end.stayOnPluggedMask & end.plugged) == 0
                || !Boolean.FALSE.equals(start.powerSave)
                || !Boolean.FALSE.equals(end.powerSave)
                || !Boolean.TRUE.equals(start.stayAwake)
                || !Boolean.TRUE.equals(end.stayAwake)
                || start.threadPriority != expectedPriority || end.threadPriority != expectedPriority
                || start.appImportance != 100 || end.appImportance != 100
                || start.systemLoadMilli < 0 || end.systemLoadMilli < 0
                || start.cpuCount <= 0 || end.cpuCount <= 0
                || start.systemLoadMilli > start.cpuCount * MAX_LOAD_PER_CPU_MILLI
                || end.systemLoadMilli > end.cpuCount * MAX_LOAD_PER_CPU_MILLI
                || start.memoryAvailableBytes <= 0 || end.memoryAvailableBytes <= 0) {
            return false;
        }
        return validBatteryTemperature(start.batteryTemperatureDeciC)
                && validBatteryTemperature(end.batteryTemperatureDeciC)
                && Math.abs(end.batteryTemperatureDeciC - start.batteryTemperatureDeciC)
                        <= MAX_BATTERY_TEMP_DRIFT_DECI_C
                && Math.abs(end.systemLoadMilli - start.systemLoadMilli)
                        <= MAX_LOAD_DRIFT_MILLI;
    }

    private static boolean validBatteryTemperature(int deciCelsius) {
        return deciCelsius >= 0 && deciCelsius <= 400;
    }

    private static boolean validSustainedEnvironment(FinalFields result, Row row,
            EnvironmentFields start) {
        return result.environmentSampleCount >= 10
                && result.thermalWorst == MAX_THERMAL_STATUS
                && result.systemLoadWorstMilli >= 0
                && start != null
                && result.systemLoadWorstMilli <= start.cpuCount * MAX_LOAD_PER_CPU_MILLI
                && result.cpuFreqMinKHz > 0
                && result.displayRefreshMinMillihz >= requiredDisplayRefreshMillihz(row)
                && result.displayBadCount == 0
                && result.interactiveBadCount == 0
                && result.pluggedBadCount == 0
                && result.powerSaveBadCount == 0
                && result.stayAwakeBadCount == 0
                && result.priorityBadCount == 0
                && result.importanceBadCount == 0
                && validBatteryTemperature(result.batteryTempMin)
                && validBatteryTemperature(result.batteryTempMax)
                && result.batteryTempMax >= result.batteryTempMin
                && result.liveInputMutations == 0;
    }

    private static int requiredDisplayRefreshMillihz(Row row) {
        return row == Row.SGB ? SGB_MIN_DISPLAY_REFRESH_MILLIHZ
                : (int) Math.ceil(row.nominalFps() * 1_000.0);
    }

    private static boolean validSurfaceVote(Row row, int rateHz) {
        return row == Row.SGB ? rateHz == 120 : rateHz >= 60;
    }

    private static boolean validSurfaceContentRate(Row row, int rateMillihz) {
        return rateMillihz == (int) Math.round(row.nominalFps() * 1_000.0);
    }

    private static boolean validAudio(AudioFields audio, Row row, ClockFields clock, int readyCount,
            long readyFirstNanos, long readyLastNanos, double readyIntervalFps,
            boolean requireRealtime) {
        if (audio == null || row == null || !Boolean.TRUE.equals(audio.active)
                || readyCount != REQUIRED_FRAME_COUNT || readyFirstNanos <= 0L
                || readyLastNanos <= readyFirstNanos || !Double.isFinite(readyIntervalFps)
                || readyIntervalFps <= 0.0 || clock == null) {
            return false;
        }
        long[] starts = {audio.startInputEvents, audio.startInputFrames,
                audio.startEnqueuedBytes, audio.startEnqueuedFrames, audio.startWrittenBytes,
                audio.startWrittenFrames, audio.startWriteFailures, audio.startDiscardedBytes,
                audio.startPendingBytes, audio.startQueuedBytes,
                audio.startPlaybackPositionFrames, audio.startOverruns, audio.startUnderruns,
                audio.startOutputUnderruns,
                audio.startRestarts,
                audio.startRouteFailures};
        for (long start : starts) {
            if (start < 0L) {
                return false;
            }
        }
        long[] ends = {audio.inputEvents, audio.inputFrames, audio.enqueuedBytes,
                audio.enqueuedFrames, audio.writtenBytes, audio.writtenFrames,
                audio.writeFailures, audio.discardedBytes, audio.pendingBytes,
                audio.queuedBytes, audio.playbackPositionFrames, audio.underruns,
                audio.outputUnderruns,
                audio.overruns, audio.restarts, audio.routeFailures};
        for (long end : ends) {
            if (end < 0L) {
                return false;
            }
        }
        long inputEvents = audio.inputEvents - audio.startInputEvents;
        long inputFrames = audio.inputFrames - audio.startInputFrames;
        long enqueuedBytes = audio.enqueuedBytes - audio.startEnqueuedBytes;
        long enqueuedFrames = audio.enqueuedFrames - audio.startEnqueuedFrames;
        long writtenBytes = audio.writtenBytes - audio.startWrittenBytes;
        long writtenFrames = audio.writtenFrames - audio.startWrittenFrames;
        long writeFailures = audio.writeFailures - audio.startWriteFailures;
        long discardedBytes = audio.discardedBytes - audio.startDiscardedBytes;
        long pendingBytes = audio.pendingBytes;
        long queuedBytes = audio.queuedBytes;
        long playbackAdvance = audio.playbackPositionFrames
                - audio.startPlaybackPositionFrames;
        long overruns = audio.overruns - audio.startOverruns;
        long underruns = audio.underruns - audio.startUnderruns;
        long outputUnderruns = audio.outputUnderruns - audio.startOutputUnderruns;
        long focusLosses = audio.focusLossCount - audio.focusStartLossCount;
        long restarts = audio.restarts - audio.startRestarts;
        long routeFailures = audio.routeFailures - audio.startRouteFailures;
        if (inputEvents < 0L || inputFrames < 0L || enqueuedBytes < 0L
                || enqueuedFrames < 0L || writtenBytes < 0L || writtenFrames < 0L
                || writeFailures < 0L || discardedBytes < 0L || pendingBytes < 0L
                || queuedBytes < 0L || playbackAdvance <= 0L || overruns < 0L
                || underruns < 0L || outputUnderruns < 0L || audio.focusStartLossCount < 0L
                || audio.focusLossCount < 0L || focusLosses < 0L
                || restarts < 0L || routeFailures < 0L) {
            return false;
        }
        // Sound events follow the emulated tick lattice, not wall time.  A slow Accuracy run can
        // take 26 seconds to publish 600 frames while still producing only about 603 legacy
        // events; using wall duration here incorrectly turns that comparable run into malformed
        // input.  Derive the expected event count from the exact row clock and the 70,224-tick
        // physical LCD frame cadence, with a small rational-rounding allowance.
        double ticksPerPhysicalFrame = (clock.ticksNumerator
                / (double) clock.ticksDenominator) / row.nominalFps;
        double eventsPerPhysicalFrame = ticksPerPhysicalFrame / clock.ticksPerControllerFrame;
        long expectedInputEvents = Math.round(readyCount * eventsPerPhysicalFrame);
        if (Math.abs(inputEvents - expectedInputEvents) > 3L) {
            return false;
        }
        if (requireRealtime) {
            long framePeriodNanos = Math.max(1L,
                    Math.round(1_000_000_000.0 / readyIntervalFps));
            long measuredDurationNanos;
            try {
                measuredDurationNanos = Math.addExact(readyLastNanos - readyFirstNanos,
                        framePeriodNanos);
            } catch (ArithmeticException overflow) {
                return false;
            }
            long expectedPcmFrames = Math.round(audio.sampleRate
                    * (measuredDurationNanos / 1_000_000_000.0));
            long durationTolerance = Math.max(1L, Math.round(
                    audio.sampleRate * (AUDIO_DURATION_TOLERANCE_NANOS / 1_000_000_000.0)));
            if (Math.abs(enqueuedFrames - expectedPcmFrames) > durationTolerance) {
                return false;
            }
        }
        long startPendingBytes = audio.startPendingBytes;
        long startQueuedBytes = audio.startQueuedBytes;
        long conservationLeft;
        long conservationRight;
        try {
            conservationLeft = Math.addExact(enqueuedBytes, startPendingBytes);
            conservationRight = Math.addExact(writtenBytes,
                    Math.addExact(pendingBytes, discardedBytes));
        } catch (ArithmeticException overflow) {
            return false;
        }
        return audio.sampleRate > 0 && audio.startSampleRate == audio.sampleRate
                && (!requireRealtime || (overruns == 0L && underruns == 0L
                && outputUnderruns == 0L && restarts == 0L))
                && writeFailures == 0L && discardedBytes == 0L && routeFailures == 0L
                && Boolean.FALSE.equals(audio.paused)
                && Boolean.TRUE.equals(audio.startOutputOpen)
                && Boolean.TRUE.equals(audio.startOutputPlaying)
                && Boolean.TRUE.equals(audio.outputOpen)
                && Boolean.TRUE.equals(audio.outputPlaying)
                && Boolean.FALSE.equals(audio.muted) && audio.volume > 0 && audio.volume <= 100
                && audio.systemVolume > 0 && audio.systemVolumeMax >= audio.systemVolume
                && Boolean.FALSE.equals(audio.systemMusicMuted)
                && Boolean.TRUE.equals(audio.focusGranted) && focusLosses == 0L
                && audio.playbackPositionFrames > audio.startPlaybackPositionFrames
                && audio.minimumBufferBytes > 0 && audio.configuredBufferBytes > 0
                && audio.actualBufferBytes > 0 && audio.routeFailures == 0L
                && inputFrames > 0L && enqueuedBytes > 0L && enqueuedFrames > 0L
                && writtenBytes > 0L && writtenFrames > 0L && inputFrames >= enqueuedFrames
                && enqueuedBytes == enqueuedFrames * 4L
                && writtenBytes == writtenFrames * 4L
                && conservationLeft == conservationRight
                && audio.queueFrames >= 0
                && audio.queueFrames <= 6 && audio.discardedBytes == 0L
                && audio.pendingBytes >= 0L && audio.queuedBytes >= 0L
                && audio.queueCapacityFrames == 6 && audio.startQueueCapacityFrames == 6
                && audio.maximumFrameBytes > 0
                && audio.startMaximumFrameBytes == audio.maximumFrameBytes
                && startQueuedBytes >= 0L && startQueuedBytes <= startPendingBytes
                && queuedBytes <= pendingBytes
                && queuedBytes <= (long) audio.queueCapacityFrames * audio.maximumFrameBytes
                && pendingBytes - queuedBytes <= audio.maximumFrameBytes
                && pendingBytes <= (long) (audio.queueCapacityFrames + 1)
                        * audio.maximumFrameBytes;
    }

    private static String requiredToken(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = fields.get(key);
        if (value == null || !SAFE_TOKEN.matcher(value).matches() || "unknown".equals(value)
                || "invalid".equals(value)) {
            errors.add("line " + lineNumber + ": invalid or missing " + key);
            return null;
        }
        return value;
    }

    private static boolean booleanValue(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = fields.get(key);
        if ("on".equals(value) || "true".equals(value)) {
            return true;
        }
        if ("off".equals(value) || "false".equals(value)) {
            return false;
        }
        errors.add("line " + lineNumber + ": invalid " + key);
        return false;
    }

    private static int integer(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = fields.get(key);
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException malformed) {
            errors.add("line " + lineNumber + ": invalid integer " + key);
            return -1;
        }
    }

    private static long longValue(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = fields.get(key);
        try {
            return Long.parseLong(value);
        } catch (RuntimeException malformed) {
            errors.add("line " + lineNumber + ": invalid long " + key);
            return -1L;
        }
    }

    private static long optionalLong(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (RuntimeException malformed) {
            return -1L;
        }
    }

    private static double doubleValue(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = fields.get(key);
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed)) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the bounded parser error below.
        }
        errors.add("line " + lineNumber + ": invalid number " + key);
        return Double.NaN;
    }

    public static final class Report {
        final boolean valid;
        final boolean accepted;
        final List<String> errors;
        final Map<Row, RowSummary> rows;
        final long seed;
        final int resamples;
        final String expectedParentArtifact;
        final String expectedCandidateArtifact;

        private Report(boolean valid, boolean accepted, List<String> errors, Map<Row, RowSummary> rows,
                long seed, int resamples, String expectedParentArtifact,
                String expectedCandidateArtifact) {
            this.valid = valid;
            this.accepted = accepted;
            this.errors = errors;
            this.rows = rows;
            this.seed = seed;
            this.resamples = resamples;
            this.expectedParentArtifact = expectedParentArtifact;
            this.expectedCandidateArtifact = expectedCandidateArtifact;
        }

        public boolean accepted() {
            return accepted;
        }

        public boolean valid() {
            return valid;
        }

        public List<String> errors() {
            return errors;
        }

        public RowSummary row(Row row) {
            return rows.get(row);
        }

        public String expectedParentArtifact() {
            return expectedParentArtifact;
        }

        public String expectedCandidateArtifact() {
            return expectedCandidateArtifact;
        }
    }

    public static final class BootstrapInterval {
        final double median;
        final double lower;
        final double upper;

        private BootstrapInterval(double median, double lower, double upper) {
            this.median = median;
            this.lower = lower;
            this.upper = upper;
        }
    }

    public static final class RowSummary {
        final Row row;
        final int pairCount;
        final double minimumCandidateFps;
        final double minimumRunFps;
        final double targetNominalFps;
        final BootstrapInterval candidateFps;
        final double realTimeRatio;
        final BootstrapInterval effectPercent;
        final boolean alarm;
        final boolean regression;
        final boolean lowerBoundPass;
        final List<RunEvidence> evidence;

        private RowSummary(Row row, int pairCount, double minimumCandidateFps,
                double minimumRunFps, double targetNominalFps, BootstrapInterval candidateFps,
                double realTimeRatio,
                BootstrapInterval effectPercent, boolean alarm, boolean regression,
                boolean lowerBoundPass, List<RunEvidence> evidence) {
            this.row = row;
            this.pairCount = pairCount;
            this.minimumCandidateFps = minimumCandidateFps;
            this.minimumRunFps = minimumRunFps;
            this.targetNominalFps = targetNominalFps;
            this.candidateFps = candidateFps;
            this.realTimeRatio = realTimeRatio;
            this.effectPercent = effectPercent;
            this.alarm = alarm;
            this.regression = regression;
            this.lowerBoundPass = lowerBoundPass;
            this.evidence = evidence;
        }

        public List<RunEvidence> evidence() {
            return evidence;
        }
    }

    /** Bounded per-run evidence retained in a report; it contains no ROM/save/payload fields. */
    public record RunEvidence(String pairId, String side, String artifactId,
            long benchmarkGeneration, String deviceId,
            String requestedProfile, String profile, String effectiveMode, int speedModeInitial,
            int speedModeFinal, long clockTicksNumerator, long clockTicksDenominator,
            long clockFramesNumerator, long clockFramesDenominator, long clockTicksFrame,
            int displayTargetHz, int surfaceContentRateMillihz,
            int readyCount,
            int submittedCount, int droppedCount, int duplicateCount, int lateCount,
            int corruptCount, boolean audioEligible, boolean environmentEligible,
            boolean compositorEligible, String compositorLayerId, long compositorLayerUid,
            long compositorTotalFrames, long compositorHistogramFrames,
            long compositorRawTotalFrames, long compositorRawHistogramFrames,
            long compositorBoundaryFrames, long compositorBoundaryIntervals,
            long compositorPresentIntervalCount, long compositorCadenceGoodFrames,
            long compositorCadenceBadFrames, long compositorCadenceVsyncTotal,
            long compositorBoundary200Frames, long compositorBoundary1000Frames,
            long compositorCadenceMaxGapMs, double compositorHistogramFps,
            long compositorDroppedFrames, long compositorLateFrames,
            long compositorBadDesiredPresentFrames, int compositorDisplayRefreshHz,
            int audioSampleRate, long audioEnqueuedFrames, long audioWrittenFrames,
            long audioEnqueuedBytes, long audioWrittenBytes, long audioDiscardedBytes,
            long audioPendingBytes, long audioQueuedBytes, int audioQueueFrames,
            long audioWriteFailures, double readyIntervalFps, double presentationIntervalFps,
            int thermalStart, int thermalEnd, int batteryTempStart, int batteryTempEnd,
            int displayRefreshStartMillihz, int displayRefreshEndMillihz,
            int systemLoadStartMilli, int systemLoadEndMilli, int cpuCountStart, int cpuCountEnd,
            int stayOnPluggedMaskStart, int stayOnPluggedMaskEnd, int threadPriorityStart,
            int threadPriorityEnd, int appImportanceStart, int appImportanceEnd,
            String workloadNonce, boolean warmup, String inputContract,
            long wallMs, double runFps, long controllerCpuMs, double controllerUtilPct,
            long gcCountDelta, long gcTimeMsDelta, long allocBytesDelta,
            boolean audioOutputOpen, boolean audioOutputPlaying, boolean audioMuted,
            int audioVolume, long audioRouteFailures, int environmentSampleCount,
            int thermalWorst, int systemLoadWorstMilli, int cpuFreqMinKHz) {
    }

    private static final class RunBuilder {
        final String key;
        final int ingestionOrdinal;
        final String artifactId;
        final String pairId;
        final String matrixBlock;
        final int rowOrder;
        final Row row;
        final Side side;
        final Side firstSide;
        final String deviceId;
        final String thermalWindow;
        final String requestedProfile;
        final String profile;
        final Boolean effectiveGbc;
        final Boolean effectiveDmgCompat;
        final String effectiveMode;
        final String executionMode;
        final int speedModeInitial;
        final ClockFields clock;
        final boolean audio;
        final String render;
        final boolean available;
        final EnvironmentFields environment;
        final AudioStartFields audioStart;
        final String workloadNonce;
        final boolean warmup;
        final String inputContract;
        final int surfaceVoteHz;
        final int displayTargetHz;
        final int surfaceContentRateMillihz;
        final long benchmarkGeneration;
        final List<Long> readyIds = new ArrayList<>();
        final List<Long> readyNanos = new ArrayList<>();
        final List<Long> submissionIds = new ArrayList<>();
        final List<Long> submissionNanos = new ArrayList<>();
        final List<Integer> frameOrdinals = new ArrayList<>();
        int finalOrdinal = -1;
        FinalFields finalFields;
        CompositorFields compositor;
        boolean audioTargetEligible;

        private RunBuilder(String key, int ingestionOrdinal, String artifactId, String pairId,
                String matrixBlock, int rowOrder, Row row, Side side, Side firstSide,
                String deviceId, String thermalWindow, String requestedProfile, String profile,
                Boolean effectiveGbc, Boolean effectiveDmgCompat, String effectiveMode,
                String executionMode, int speedModeInitial, ClockFields clock, boolean audio,
                String render, boolean available, EnvironmentFields environment,
                String workloadNonce, boolean warmup, String inputContract, int surfaceVoteHz,
                int displayTargetHz, int surfaceContentRateMillihz, long benchmarkGeneration,
                AudioStartFields audioStart) {
            this.key = key;
            this.ingestionOrdinal = ingestionOrdinal;
            this.artifactId = artifactId;
            this.pairId = pairId;
            this.matrixBlock = matrixBlock;
            this.rowOrder = rowOrder;
            this.row = row;
            this.side = side;
            this.firstSide = firstSide;
            this.deviceId = deviceId;
            this.thermalWindow = thermalWindow;
            this.requestedProfile = requestedProfile;
            this.profile = profile;
            this.effectiveGbc = effectiveGbc;
            this.effectiveDmgCompat = effectiveDmgCompat;
            this.effectiveMode = effectiveMode;
            this.executionMode = executionMode;
            this.speedModeInitial = speedModeInitial;
            this.clock = clock;
            this.audio = audio;
            this.render = render;
            this.available = available;
            this.environment = environment;
            this.audioStart = audioStart;
            this.workloadNonce = workloadNonce;
            this.warmup = warmup;
            this.inputContract = inputContract;
            this.surfaceVoteHz = surfaceVoteHz;
            this.displayTargetHz = displayTargetHz;
            this.surfaceContentRateMillihz = surfaceContentRateMillihz;
            this.benchmarkGeneration = benchmarkGeneration;
        }
    }

    private record FinalFields(String artifactId, long benchmarkGeneration,
            String requestedProfile, String profile,
            Boolean effectiveGbc, Boolean effectiveDmgCompat, String effectiveMode,
            String executionMode,
            int speedModeInitial, int speedModeFinal, ClockFields clock, String deviceId,
            EnvironmentFields environmentStart, EnvironmentFields environmentEnd,
            AudioFields audio, int frame, int readyCount, int submittedCount, int droppedCount,
            int duplicateCount, int lateCount, int corruptCount, double readyIntervalFps,
            double presentationIntervalFps, long readyFirstId, long readyLastId,
            long readyFirstNanos, long readyLastNanos, long presentationFirstId,
            long presentationLastId, long presentationFirstNanos, long presentationLastNanos,
            double fps, long wallMs, double controllerUtilPct, long controllerCpuMs,
            long gcCountDelta, long gcTimeMsDelta, long allocBytesDelta,
            String workloadNonce, boolean warmup, String inputContract,
            boolean drainSuccess,
            int environmentSampleCount, int thermalWorst, int systemLoadWorstMilli,
            int cpuFreqMinKHz, int displayRefreshMinMillihz, int displayBadCount,
            int interactiveBadCount, int pluggedBadCount, int powerSaveBadCount,
            int stayAwakeBadCount, int priorityBadCount, int importanceBadCount,
            int batteryTempMin, int batteryTempMax, int liveInputMutations, int surfaceVoteHz,
            int displayTargetHz, int surfaceContentRateMillihz) {
    }

    private record EnvironmentFields(int thermalStatus, int batteryTemperatureDeciC,
            int displayRefreshMillihz, int displayState, Boolean interactive, int plugged,
            Boolean powerSave, Boolean stayAwake, int stayOnPluggedMask, int threadPriority,
            int appImportance, int systemLoadMilli, long memoryAvailableBytes, int cpuCount) {
    }

    private record ClockFields(long ticksNumerator, long ticksDenominator,
            long framesNumerator, long framesDenominator, long ticksPerControllerFrame) {
    }

    private record AudioFields(Boolean active, int sampleRate, long overruns, long underruns,
            long outputUnderruns,
            long restarts, Boolean paused, int minimumBufferBytes, int configuredBufferBytes,
            int actualBufferBytes, long inputEvents, long inputFrames, long enqueuedBytes,
            long enqueuedFrames, long writtenBytes, long writtenFrames, long writeFailures,
            long discardedBytes, long pendingBytes, long queuedBytes, int queueFrames,
            Boolean outputOpen, Boolean outputPlaying, Boolean muted, int volume,
                long routeFailures, long playbackPositionFrames, int systemVolume,
                int systemVolumeMax, Boolean systemMusicMuted, int queueCapacityFrames,
                int maximumFrameBytes, long startInputEvents, long startInputFrames,
                long startEnqueuedBytes,
                long startEnqueuedFrames, long startWrittenBytes, long startWrittenFrames,
                long startWriteFailures, long startDiscardedBytes, long startPendingBytes,
                long startQueuedBytes, long startPlaybackPositionFrames, long startOverruns,
                long startUnderruns, long startOutputUnderruns,
                long startRestarts, long startRouteFailures, Boolean startOutputOpen,
                Boolean startOutputPlaying, int startSampleRate, int startQueueCapacityFrames,
                int startMaximumFrameBytes, Boolean focusGranted, long focusStartLossCount,
                long focusLossCount) {
    }

    private record AudioStartFields(long inputEvents, long inputFrames, long enqueuedBytes,
            long enqueuedFrames, long writtenBytes, long writtenFrames, long writeFailures,
            long discardedBytes, long pendingBytes, long queuedBytes, long playbackPositionFrames,
            long overruns, long underruns, long outputUnderruns, long restarts,
            long routeFailures, Boolean outputOpen, Boolean outputPlaying, int sampleRate,
            int queueCapacityFrames, int maximumFrameBytes) {
    }

    private record CompositorFields(String artifactId, String deviceId, long benchmarkGeneration,
            String layerId,
            long layerUid, long rawTotalFrames, long rawHistogramFrames, long boundaryFrames,
            long boundaryIntervals, long totalFrames, long histogramFrames,
            long presentIntervalCount, long cadenceGoodFrames, long cadenceBadFrames,
            long cadenceVsyncTotal, long cadenceBoundary200, long cadenceBoundary1000,
            long cadenceMaxGapMs, long cadenceMinGapMs, double histogramFps,
            long droppedFrames, long lateFrames, long badDesiredPresentFrames,
            int displayRefreshHz, int ordinal) {
    }

    private static final class BlockState {
        final String name;
        final List<RunBuilder> runs = new ArrayList<>();
        final Map<Row, List<RunBuilder>> byRow = new LinkedHashMap<>();

        private BlockState(String name) {
            this.name = name;
        }
    }

    private record BlockSummary(String name, int firstOrdinal, Side firstSide,
            String rowSignature) {
    }

    private record Pair(String pairId, RunBuilder parent, RunBuilder candidate) {
    }

    /** Small typed map avoiding a java.util.EnumMap import in the Android test desugaring path. */
    private static final class EnumMapCompat<K extends Enum<K>, V> extends HashMap<K, V> {
    }

    /** Prevent malformed/per-frame input from turning diagnostics parsing into an allocation sink. */
    private static final class BoundedErrors extends ArrayList<String> {
        private final Set<String> unique = new LinkedHashSet<>();
        private boolean structuralPhase;

        void beginStructuralPhase() {
            structuralPhase = true;
        }

        @Override
        public boolean add(String error) {
            String canonical = error.replaceFirst("^line [0-9]+: ", "");
            if (unique.size() >= MAX_UNIQUE_ERRORS && !unique.contains(canonical)) {
                return false;
            }
            if (!unique.add(canonical)) {
                return false;
            }
            int limit = structuralPhase
                    ? MAX_REPORTED_ERRORS : MAX_REPORTED_ERRORS - RESERVED_STRUCTURAL_ERRORS;
            if (size() < limit) {
                return super.add(error);
            }
            if (size() == limit) {
                super.add("parser errors truncated");
            }
            return false;
        }

        @Override
        public boolean addAll(java.util.Collection<? extends String> errors) {
            boolean changed = false;
            for (String error : errors) {
                changed |= add(error);
            }
            return changed;
        }
    }
}
