package eu.rekawek.coffeegb.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BenchmarkMatrixTest {

    private static final String PARENT_ARTIFACT = "a".repeat(64);
    private static final String CANDIDATE_ARTIFACT = "b".repeat(64);
    private static final String DEVICE_ID = "c".repeat(64);
    private static final String COMPOSITOR_LAYER_ID = "d".repeat(64);
    private static final String COLOR_WORKLOAD_NONCE = "color-selection-opaque-0001";
    private static final String NONCOLOR_WORKLOAD_NONCE = "noncolor-selection-opaque-01";
    private static final String[] BLOCK_NAMES = {
            "zeta", "alpha", "omega", "beta", "theta", "gamma",
            "eta", "delta", "iota", "epsilon", "kappa", "lambda"};

    private static BenchmarkMatrix.Report parse(List<String> lines) {
        return parse(lines, BenchmarkMatrix.DEFAULT_SEED, BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
    }

    private static BenchmarkMatrix.Report parse(List<String> lines, long seed, int resamples) {
        return BenchmarkMatrix.parse(lines, seed, resamples, PARENT_ARTIFACT,
                CANDIDATE_ARTIFACT);
    }

    @Test
    public void acceptsSevenRowsAndUsesExactIntervalFpsAndGates() {
        List<String> log = syntheticLog(61.0, 62.0);

        BenchmarkMatrix.Report report = parse(log);

        assertTrue(report.errors().toString(), report.accepted());
        assertEquals(7, report.rows.size());
        BenchmarkMatrix.RowSummary cgb = report.row(BenchmarkMatrix.Row.CGB_NATIVE);
        assertNotNull(cgb);
        assertEquals(12, cgb.pairCount);
        assertTrue(cgb.candidateFps.lower >= 60.0);
        assertTrue(cgb.effectPercent.median > 0.0);
        assertFalse(cgb.alarm);
        assertFalse(cgb.regression);
        assertEquals(4_194_304.0 / 70_224.0, cgb.targetNominalFps, 0.000001);
        assertEquals(cgb.candidateFps.median / cgb.targetNominalFps,
                cgb.realTimeRatio, 0.000001);
    }

    @Test
    public void bootstrapIsDeterministicForTheSameSeedAndPairOrder() {
        List<String> log = syntheticLog(61.0, 62.0);

        BenchmarkMatrix.Report first = parse(log, 1234L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        BenchmarkMatrix.Report second = parse(log, 1234L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);

        assertEquals(first.errors(), second.errors());
        for (BenchmarkMatrix.Row row : BenchmarkMatrix.Row.values()) {
            BenchmarkMatrix.RowSummary left = first.row(row);
            BenchmarkMatrix.RowSummary right = second.row(row);
            if (left == null || right == null) {
                assertEquals(left, right);
                continue;
            }
            assertEquals(left.candidateFps.median, right.candidateFps.median, 0.0);
            assertEquals(left.candidateFps.lower, right.candidateFps.lower, 0.0);
            assertEquals(left.candidateFps.upper, right.candidateFps.upper, 0.0);
            assertEquals(left.effectPercent.lower, right.effectPercent.lower, 0.0);
            assertEquals(left.effectPercent.upper, right.effectPercent.upper, 0.0);
        }
    }

    @Test
    public void acceptsBoundedSummaryRecordsWithoutPerFrameLogSpam() {
        List<String> log = syntheticSummaryLog(61.0, 62.0);

        BenchmarkMatrix.Report report = parse(log);

        assertTrue(report.errors().toString(), report.accepted());
        assertEquals(7 * BenchmarkMatrix.MIN_PAIRS * 2 * 3
                + 5 * BenchmarkMatrix.MIN_PAIRS * 2, log.size());
    }

    @Test
    public void finalResultMayInheritStartBaselinesFromMatrixRun() {
        List<String> log = omitFinalEnvironmentStartFields(
                omitFinalAudioStartFields(syntheticSummaryLog(61.0, 62.0)));

        BenchmarkMatrix.Report report = parse(log);

        assertTrue(report.errors().toString(), report.accepted());
    }

    @Test
    public void contradictoryLegacyFinalEnvironmentStartIsRejected() {
        List<String> log = replaceFirstLine(
                syntheticSummaryLog(61.0, 62.0), "event=final_result", "",
                "thermal_start=0", "thermal_start=1");

        BenchmarkMatrix.Report report = parse(log);

        assertFalse(report.accepted());
        assertContains(report.errors(), "final evidence does not match matrix_run");
    }

    @Test
    public void requiresExactGenerationBoundScenarioCompletionEvidence() {
        List<String> base = syntheticSummaryLog(61.0, 62.0);
        ArrayList<String> missing = new ArrayList<>(base);
        missing.removeIf(line -> line.startsWith("event=scenario_complete"));
        BenchmarkMatrix.Report missingReport = parse(missing);
        assertFalse(missingReport.accepted());
        assertContains(missingReport.errors(), "missing scenario_complete evidence");

        List<String> wrongFrames = replaceFirstLine(base, "event=scenario_complete", "",
                "completed_frames=313", "completed_frames=312");
        BenchmarkMatrix.Report framesReport = parse(wrongFrames);
        assertFalse(framesReport.accepted());
        assertContains(framesReport.errors(), "scenario_complete is not exact");

        List<String> wrongGeneration = replaceFirstLineKey(base, "event=scenario_complete", "",
                "session_generation", "999999999");
        BenchmarkMatrix.Report generationReport = parse(wrongGeneration);
        assertFalse(generationReport.accepted());
        assertContains(generationReport.errors(), "session generation does not match");

        List<String> wrongIdentity = replaceFirstLineKey(base, "event=scenario_complete", "",
                "pair_id", "stale-pair");
        BenchmarkMatrix.Report identityReport = parse(wrongIdentity);
        assertFalse(identityReport.accepted());
        assertContains(identityReport.errors(), "run identity does not match");

        List<String> missingIdentity = replaceFirstLine(base, "event=scenario_complete", "",
                "artifact_id=" + PARENT_ARTIFACT + " ", "");
        BenchmarkMatrix.Report malformedIdentityReport = parse(missingIdentity);
        assertFalse(malformedIdentityReport.accepted());
        assertContains(malformedIdentityReport.errors(), "scenario_complete is not exact");
    }

    @Test
    public void acceptsEmittedRejectionSchemaButStillRequiresCompletedRuns() {
        List<String> rejections = List.of(
                "event=benchmark_anchor success=false phase=scenario_running reason=stale_session",
                "event=benchmark_arm_rejected phase=anchor_ready reason=audio_pending",
                "event=benchmark_arm_rejected phase=anchor_ready reason=audio_not_playing",
                "event=benchmark_arm_rejected phase=anchor_ready reason=audio_focus",
                "event=benchmark_invalidated artifact_id=" + "f".repeat(64)
                        + " pair_id=unrelated-pair matrix_block=unrelated-block row_order=0"
                        + " run_side=parent session_generation=42 phase=scenario_running"
                        + " reason=visibility_lost");
        ArrayList<String> complete = new ArrayList<>(rejections);
        complete.addAll(syntheticSummaryLog(61.0, 62.0));

        BenchmarkMatrix.Report completeReport = parse(complete);
        assertTrue(completeReport.errors().toString(), completeReport.accepted());

        BenchmarkMatrix.Report rejectionOnlyReport = parse(rejections);
        assertFalse(rejectionOnlyReport.accepted());
        assertFalse(rejectionOnlyReport.errors().isEmpty());
    }

    @Test
    public void rejectsSameRunVisibilityInvalidationEvenAfterFinalResult() {
        List<String> invalidated = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        invalidated.add("event=benchmark_invalidated artifact_id=" + PARENT_ARTIFACT
                + " pair_id=p00-sgb2 matrix_block=zeta row_order=0 run_side=parent"
                + " session_generation=1 phase=done reason=visibility_lost");

        BenchmarkMatrix.Report report = parse(invalidated);

        assertFalse(report.accepted());
        assertContains(report.errors(), "was invalidated by host visibility loss");
    }

    @Test
    public void requiresRowMappedCompletionAndClosedDrainedScenario() {
        List<String> base = syntheticSummaryLog(61.0, 62.0);
        List<String> wrongContract = replaceFirstLine(base, "event=matrix_run",
                "effective_mode=cgb-dmg-compat", "input_contract=dmg-action-v1",
                "input_contract=cgb-action-v1");
        BenchmarkMatrix.Report contractReport = parse(wrongContract);
        assertFalse(contractReport.accepted());
        assertContains(contractReport.errors(), "does not match its hardware row");

        List<String> openSource = replaceFirstLine(base, "event=matrix_run", "",
                "scenario_source_closed=true", "scenario_source_closed=false");
        BenchmarkMatrix.Report sourceReport = parse(openSource);
        assertFalse(sourceReport.accepted());
        assertContains(sourceReport.errors(), "scenario completion does not match");

        List<String> undrained = replaceFirstLine(base, "event=matrix_run", "",
                "scenario_audio_drained=true", "scenario_audio_drained=false");
        BenchmarkMatrix.Report audioReport = parse(undrained);
        assertFalse(audioReport.accepted());
        assertContains(audioReport.errors(), "scenario completion does not match");
    }

    @Test
    public void requiresEmptyPlayingAudioArmBaselineForVisibleRuns() {
        List<String> base = syntheticSummaryLog(61.0, 62.0);
        List<String> pending = replaceFirstLine(base, "event=matrix_run", "",
                "audio_start_pending_bytes=0", "audio_start_pending_bytes=4");
        BenchmarkMatrix.Report pendingReport = parse(pending);
        assertFalse(pendingReport.accepted());
        assertContains(pendingReport.errors(), "audio arm baseline is not drained and playing");

        List<String> queued = replaceFirstLine(base, "event=matrix_run", "",
                "audio_start_queued_bytes=0", "audio_start_queued_bytes=4");
        BenchmarkMatrix.Report queuedReport = parse(queued);
        assertFalse(queuedReport.accepted());
        assertContains(queuedReport.errors(), "audio arm baseline is not drained and playing");

        List<String> stopped = replaceFirstLine(base, "event=matrix_run", "",
                "audio_start_output_playing=true", "audio_start_output_playing=false");
        BenchmarkMatrix.Report stoppedReport = parse(stopped);
        assertFalse(stoppedReport.accepted());
        assertContains(stoppedReport.errors(), "audio arm baseline is not drained and playing");
    }

    @Test
    public void silentPcmPolicyRequiresPositiveCalendarAndFrameSequencerEvidence() {
        List<String> valid = withSilentPolicyForSilentRows(
                omitFinalAudioStartFields(syntheticSilentSummaryLog(61.0, 62.0)));
        assertTrue(parse(valid).errors().toString(), parse(valid).accepted());

        List<String> compact = valid.stream().map(line -> line.startsWith("event=final_result")
                ? compactFinalAudioProof(line, "111", "1000,10,2,100,0,0,1,0") : line)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(parse(compact).errors().toString(), parse(compact).accepted());

        List<String> negativeCompact = syntheticSummaryLog(61.0, 62.0).stream()
                .map(line -> line.startsWith("event=final_result")
                        ? compactFinalAudioProof(line, "111", "-1,-1,-1,-1,-1,-1,-1,-1")
                        : line)
                .collect(java.util.stream.Collectors.toList());
        BenchmarkMatrix.Report negativeReport = parse(negativeCompact);
        assertFalse(negativeReport.accepted());
        assertContains(negativeReport.errors(), "invalid compact benchmark audio calendar");

        ArrayList<String> negativeExpanded = new ArrayList<>(valid);
        for (int index = 0; index < negativeExpanded.size(); index++) {
            String line = negativeExpanded.get(index);
            if (line.startsWith("event=final_result")) {
                for (String key : List.of(
                        "benchmark_audio_skipped_ticks",
                        "benchmark_audio_zero_sample_slots",
                        "benchmark_audio_zero_sample_events",
                        "benchmark_audio_max_debt", "benchmark_audio_apu_reads",
                        "benchmark_audio_apu_writes",
                        "benchmark_audio_frame_sequencer_commits",
                        "benchmark_audio_dropped_channel_ticks")) {
                    line = line.replaceAll(" " + key + "=[^ ]*", " " + key + "=-1");
                }
                negativeExpanded.set(index, line);
                break;
            }
        }
        BenchmarkMatrix.Report negativeExpandedReport = parse(negativeExpanded);
        assertFalse(negativeExpandedReport.accepted());
        assertContains(negativeExpandedReport.errors(),
                "expanded benchmark audio calendar must be nonnegative");

        ArrayList<String> mixedSchemas = new ArrayList<>(valid);
        for (int index = 0; index < mixedSchemas.size(); index++) {
            String line = mixedSchemas.get(index);
            if (line.startsWith("event=final_result")) {
                mixedSchemas.set(index, line + " benchmark_audio_flags=111"
                        + " benchmark_audio_calendar=1000,10,2,100,0,0,1,0");
                break;
            }
        }
        BenchmarkMatrix.Report mixedSchemaReport = parse(mixedSchemas);
        assertFalse(mixedSchemaReport.accepted());
        assertContains(mixedSchemaReport.errors(),
                "compact and expanded benchmark audio proofs cannot be mixed");

        for (String field : List.of(
                "benchmark_audio_zero_sample_slots",
                "benchmark_audio_zero_sample_events",
                "benchmark_audio_max_debt",
                "benchmark_audio_frame_sequencer_commits")) {
            List<String> missing = replaceFirstLineKey(
                    valid, "event=final_result", "pair_id=p00-dmg ", field, "0");
            BenchmarkMatrix.Report report = parse(missing);
            assertFalse(field, report.accepted());
            assertContains(report.errors(), "intrinsic audio output evidence");
        }
    }

    @Test
    public void relaxedSilentPcmPolicyRequiresDroppedTicksEqualToSkippedTicks() {
        List<String> exact = withSilentPolicyForRows(
                omitFinalAudioStartFields(syntheticRelaxedSilentSummaryLog(61.0, 62.0)),
                List.of("dmg", "mgb", "cgb-native", "cgb0-native", "cgb-dmg-compat"));
        List<String> relaxed = exact.stream().map(line -> {
            if (silentRow(line, List.of("dmg", "mgb", "cgb-native", "cgb0-native",
                    "cgb-dmg-compat")) == null) {
                return line;
            }
            return line.replace("silent-pcm-v1", "silent-pcm-relaxed-apu-v1")
                    .replace("benchmark_audio_dropped_channel_ticks=0",
                            "benchmark_audio_dropped_channel_ticks=1000");
        }).collect(java.util.stream.Collectors.toList());
        assertTrue(parse(relaxed).errors().toString(), parse(relaxed).accepted());

        List<String> mismatch = relaxed.stream()
                .map(line -> line.replace("benchmark_audio_dropped_channel_ticks=1000",
                        "benchmark_audio_dropped_channel_ticks=999"))
                .collect(java.util.stream.Collectors.toList());
        BenchmarkMatrix.Report report = parse(mismatch);
        assertFalse(report.accepted());
        assertContains(report.errors(), "intrinsic audio output evidence");
    }

    @Test
    public void rejectsMixedCanonicalAndSilentPolicies() {
        List<String> mixed = withSilentPolicyForRows(syntheticSummaryLog(61.0, 62.0),
                List.of("dmg", "mgb", "cgb-native", "cgb0-native", "cgb-dmg-compat"));
        BenchmarkMatrix.Report report = parse(mixed);
        assertFalse(report.accepted());
        assertContains(report.errors(), "mixed benchmark audio policies");
    }

    @Test
    public void silentPolicyAcceptsSgbRows() {
        List<String> allRows = withSilentPolicyForRows(
                omitFinalAudioStartFields(syntheticSummaryLog(61.0, 62.0)),
                List.of("dmg", "mgb", "cgb-native", "cgb0-native", "cgb-dmg-compat",
                        "sgb", "sgb2"));
        BenchmarkMatrix.Report report = parse(allRows);
        assertTrue(report.errors().toString(), report.accepted());
        assertEquals(7, report.rows.size());
    }

    @Test
    public void exactSilentPolicyRejectsCanonicalSgbScenarioContract() {
        List<String> exact = withSilentPolicyForSilentRows(
                omitFinalAudioStartFields(syntheticSummaryLog(61.0, 62.0)));
        ArrayList<String> canonicalSgb = new ArrayList<>();
        for (String original : exact) {
            String row = silentRow(original, List.of("sgb", "sgb2"));
            if (row == null) {
                canonicalSgb.add(original);
                continue;
            }
            if (original.startsWith("event=scenario_complete")) {
                continue;
            }
            canonicalSgb.add(original
                    .replace("input_contract=dmg-action-v1", "input_contract=none")
                    .replaceAll(" scenario_session_generation=[^ ]*",
                            " scenario_session_generation=0")
                    .replaceAll(" scenario_completed_frames=[^ ]*",
                            " scenario_completed_frames=0")
                    .replaceAll(" scenario_expected_frames=[^ ]*",
                            " scenario_expected_frames=0"));
        }
        BenchmarkMatrix.Report report = parse(canonicalSgb);
        assertFalse(report.accepted());
        assertContains(report.errors(), "matrix_run scenario completion does not match");

        BenchmarkMatrix.Report canonicalReport = parse(syntheticSummaryLog(61.0, 62.0));
        assertTrue(canonicalReport.errors().toString(), canonicalReport.accepted());
    }

    @Test
    public void relaxedSilentPolicyKeepsSgbRowsOutOfItsFiveRowContract() {
        List<String> allRows = withSilentPolicyForRows(syntheticSummaryLog(61.0, 62.0),
                List.of("dmg", "mgb", "cgb-native", "cgb0-native", "cgb-dmg-compat",
                        "sgb", "sgb2")).stream()
                .map(line -> line.replace("silent-pcm-v1", "silent-pcm-relaxed-apu-v1"))
                .collect(java.util.stream.Collectors.toList());
        BenchmarkMatrix.Report report = parse(allRows);
        assertFalse(report.accepted());
        assertContains(report.errors(), "row_order is outside selected matrix");
    }

    @Test
    public void silentPolicyRequiresAndBindsBenchmarkToken() {
        List<String> valid = withSilentPolicyForSilentRows(
                omitFinalAudioStartFields(syntheticSilentSummaryLog(61.0, 62.0)));
        List<String> missing = replaceFirstLine(valid, "event=matrix_run", "",
                "benchmark_token=silent-token-001 ", "");
        BenchmarkMatrix.Report missingReport = parse(missing);
        assertFalse(missingReport.accepted());
        assertContains(missingReport.errors(), "silent benchmark token is missing");

        List<String> drift = replaceFirstLine(valid, "event=final_result", "",
                "benchmark_token=silent-token-001",
                "benchmark_token=drift-token-0001");
        BenchmarkMatrix.Report driftReport = parse(drift);
        assertFalse(driftReport.accepted());
        assertContains(driftReport.errors(), "final evidence does not match matrix_run");
    }

    @Test
    public void validatesPerformanceSchedulerTelemetryAsIntegers() {
        List<String> valid = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        valid.add(
                "event=speed_sample frame=600 effective_gbc=false"
                        + " effective_dmg_compat=false speed_mode_final=1"
                        + " speed_mode_sample=frame_600 performance_bulk_spans=12"
                        + " performance_bulk_ticks=345 performance_epoch_count=67"
                        + " performance_epoch_ticks=8901 performance_epoch_max_ticks=64"
                        + " performance_epoch_raster_fast_ticks=7654"
                        + " performance_epoch_mode2_replay_ticks=4321"
                        + " performance_epoch_mode2_bulk_ticks=4000"
                        + " performance_epoch_lcd_off_ticks=4444");
        BenchmarkMatrix.Report accepted = parse(valid);
        assertTrue(accepted.errors().toString(), accepted.valid());

        List<String> malformed = new ArrayList<>(valid);
        int speedIndex = malformed.size() - 1;
        malformed.set(speedIndex, malformed.get(speedIndex).replace("performance_bulk_ticks=345",
                        "performance_bulk_ticks=not-a-number"));
        BenchmarkMatrix.Report rejected = parse(malformed);
        assertFalse(rejected.valid());
        assertContains(rejected.errors(), "invalid integer performance_bulk_ticks");

        for (String key : List.of(
                "performance_epoch_count",
                "performance_epoch_ticks",
                "performance_epoch_max_ticks",
                "performance_epoch_raster_fast_ticks",
                "performance_epoch_mode2_replay_ticks",
                "performance_epoch_mode2_bulk_ticks",
                "performance_epoch_lcd_off_ticks")) {
            List<String> malformedEpoch = replaceFirstLineKey(
                    valid, "event=speed_sample", "", key, "not-a-number");
            BenchmarkMatrix.Report rejectedEpoch = parse(malformedEpoch);
            assertFalse(rejectedEpoch.valid());
            assertContains(rejectedEpoch.errors(), "invalid integer " + key);
        }
    }

    @Test
    public void validatesTerminalAudioProofAsAnAtomicArmToBoundarySnapshot() {
        List<String> valid = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        valid.add("event=speed_sample frame=600 effective_gbc=false"
                + " effective_dmg_compat=false speed_mode_final=1"
                + " speed_mode_sample=frame_600"
                + terminalAudioProof());
        BenchmarkMatrix.Report accepted = parse(valid);
        assertTrue(accepted.errors().toString(), accepted.valid());

        List<String> incomplete = new ArrayList<>(valid);
        int speedIndex = incomplete.size() - 1;
        incomplete.set(speedIndex,
                incomplete.get(speedIndex).replace(" audio_arm_queue_identity=12", ""));
        BenchmarkMatrix.Report incompleteReport = parse(incomplete);
        assertFalse(incompleteReport.valid());
        assertContains(incompleteReport.errors(), "terminal audio proof must be all-or-none");

        List<String> negative = new ArrayList<>(valid);
        negative.set(speedIndex,
                negative.get(speedIndex).replace("audio_terminal_underruns=0",
                        "audio_terminal_underruns=-1"));
        BenchmarkMatrix.Report negativeReport = parse(negative);
        assertFalse(negativeReport.valid());
        assertContains(negativeReport.errors(),
                "terminal audio value must be nonnegative audio_terminal_underruns");
    }

    @Test
    public void acceptsReadyOnlyFrameSinkContractButDoesNotUseItForVisibleGate() {
        List<String> sink = toSinkSummaryLog(syntheticSummaryLog(61.0, 62.0));

        BenchmarkMatrix.Report report = parse(sink);

        assertTrue(report.errors().toString(), report.valid());
        assertFalse(report.accepted());
    }

    @Test
    public void rejectsSgbVisibleRowsWhenDisplayCannotSustainTheirNominalCadence() {
        List<String> sixtyHertz = syntheticSummaryLog(61.0, 62.0).stream()
                .map(line -> line.replace("display_refresh_start_millihz=120000",
                                "display_refresh_start_millihz=60000")
                        .replace("display_refresh_end_millihz=120000",
                                "display_refresh_end_millihz=60000"))
                .toList();
        BenchmarkMatrix.Report report = parse(sixtyHertz, 22L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);

        assertFalse(report.accepted());
        assertContains(report.errors(), "sgb");
        assertContains(report.errors(), "display refresh cannot sustain");
    }

    @Test
    public void rejectsPerRunCadenceAboveNominalHardwareRate() {
        List<String> overclocked = syntheticSummaryLog(61.0, 62.0).stream()
                .map(line -> {
                    if (!line.startsWith("event=final_result")
                            || !line.contains("effective_mode=dmg ")) {
                        return line;
                    }
                    return line.replaceFirst("ready_interval_fps=[0-9.]+",
                                    "ready_interval_fps=61.000")
                            .replaceFirst("submission_interval_fps=[0-9.]+",
                                    "submission_interval_fps=61.000");
                })
                .toList();
        BenchmarkMatrix.Report report = parse(overclocked, 23L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);

        assertFalse(report.accepted());
        assertContains(report.errors(), "cadence exceeds the nominal hardware rate");
    }

    @Test
    public void rejectsMissingRowsAndFewerThanTwelvePairs() {
        List<String> missing = new ArrayList<>();
        for (String line : syntheticLog(61.0, 62.0)) {
            if (!line.contains("effective_mode=cgb0-native")) {
                missing.add(line);
            }
        }
        BenchmarkMatrix.Report missingReport = parse(missing, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(missingReport.accepted());
        assertContains(missingReport.errors(), "cgb0-native row is missing");

        List<String> fewerPairs = syntheticLog(61.0, 62.0).stream()
                .filter(line -> !line.contains("pair_id=p11-"))
                .toList();
        BenchmarkMatrix.Report fewerReport = parse(fewerPairs, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(fewerReport.accepted());
        assertContains(fewerReport.errors(), "fewer than 12 paired runs");
    }

    @Test
    public void rejectsMixedBuildIdentityDeviceThermalAndPayloadFields() {
        List<String> base = syntheticLog(61.0, 62.0);
        List<String> mixedBuild = replaceFirst(base,
                "artifact_id=" + PARENT_ARTIFACT, "artifact_id=" + "d".repeat(64));
        BenchmarkMatrix.Report buildReport = parse(mixedBuild, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(buildReport.accepted());
        assertContains(buildReport.errors(), "exactly two artifact identities");

        List<String> mixedDevice = replaceFirst(base, "device_id=" + DEVICE_ID,
                "device_id=" + "d".repeat(64));
        BenchmarkMatrix.Report deviceReport = parse(mixedDevice, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(deviceReport.accepted());
        assertContains(deviceReport.errors(), "device identity is missing or mixed");

        List<String> mixedThermal = replaceFirst(base, "thermal_window=window-a",
                "thermal_window=window-b");
        BenchmarkMatrix.Report thermalReport = parse(mixedThermal, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(thermalReport.accepted());
        assertContains(thermalReport.errors(), "thermal window mismatch");

        List<String> mixedAudio = replaceFirst(base, "audio=on", "audio=off");
        BenchmarkMatrix.Report audioReport = parse(mixedAudio, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(audioReport.accepted());
        assertContains(audioReport.errors(), "mixed audio requests");

        List<String> mixedRender = replaceFirst(base, "render=presentation", "render=sink");
        BenchmarkMatrix.Report renderReport = parse(mixedRender, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(renderReport.accepted());
        assertContains(renderReport.errors(), "mixed render modes");

        List<String> payload = new ArrayList<>(base);
        payload.add("event=matrix_run artifact_id=" + PARENT_ARTIFACT
                + " pair_id=payload-pair matrix_block=payload-block row_order=0 run_side=parent "
                + "first_side=parent device_id=" + DEVICE_ID
                + " thermal_window=window-a audio=on render=presentation availability=available "
                + "requested_profile=dmg profile=dmg effective_gbc=false "
                + "effective_dmg_compat=false effective_mode=dmg "
                + "thermal_start=0 battery_temp_start=-1 display_refresh_start_millihz=60000 "
                + "display_state_start=2 interactive_start=true plugged_start=0 "
                + "power_save_start=false stay_awake_start=true "
                + "rom_path=/redacted");
        BenchmarkMatrix.Report payloadReport = parse(payload, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(payloadReport.accepted());
        assertContains(payloadReport.errors(), "forbidden payload field");
    }

    @Test
    public void rejectsSwappedParentCandidateArtifactLabelsWhenPinsAreProvided() {
        List<String> swapped = new ArrayList<>();
        String temporary = "f".repeat(64);
        for (String line : syntheticSummaryLog(61.0, 62.0)) {
            swapped.add(line.replace("artifact_id=" + PARENT_ARTIFACT,
                            "artifact_id=" + temporary)
                    .replace("artifact_id=" + CANDIDATE_ARTIFACT,
                            "artifact_id=" + PARENT_ARTIFACT)
                    .replace("artifact_id=" + temporary,
                            "artifact_id=" + CANDIDATE_ARTIFACT));
        }
        BenchmarkMatrix.Report report = BenchmarkMatrix.parse(swapped, 18L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES, PARENT_ARTIFACT, CANDIDATE_ARTIFACT);
        assertFalse(report.accepted());
        assertContains(report.errors(), "parent artifact does not match pinned identity");
        assertContains(report.errors(), "candidate artifact does not match pinned identity");
    }

    @Test
    public void unpinnedReportsCannotBeAccepted() {
        BenchmarkMatrix.Report report = BenchmarkMatrix.parse(syntheticSummaryLog(61.0, 62.0));
        assertTrue(report.valid());
        assertFalse(report.accepted());
    }

    @Test
    public void autoRequestedProfileIsDiagnosticOnlyAndCannotAcceptAResolvedRow() {
        List<String> auto = syntheticSummaryLog(61.0, 62.0).stream()
                .map(line -> line.replace("requested_profile=dmg", "requested_profile=auto"))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        BenchmarkMatrix.Report report = BenchmarkMatrix.parse(auto, 23L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES, PARENT_ARTIFACT, CANDIDATE_ARTIFACT);
        assertFalse(report.accepted());
        assertContains(report.errors(), "requested profile does not match actual profile");
    }

    @Test
    public void nonColorRowsRejectAClaimedDoubleSpeedTrajectory() {
        List<String> switched = syntheticSummaryLog(61.0, 62.0).stream()
                .map(line -> line.replace("speed_mode_final=1", "speed_mode_final=2"))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        BenchmarkMatrix.Report report = BenchmarkMatrix.parse(switched, 24L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES, PARENT_ARTIFACT, CANDIDATE_ARTIFACT);
        assertFalse(report.accepted());
        assertContains(report.errors(), "speed mode trajectory is invalid for hardware row");
    }

    @Test
    public void hashesOnlyExplicitApkArtifactInputsForPins() throws Exception {
        java.nio.file.Path apk = java.nio.file.Files.createTempFile("coffee-gb-parent-", ".apk");
        try {
            java.nio.file.Files.write(apk, new byte[]{1, 2, 3, 4});
            assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
                    BenchmarkMatrix.artifactIdentityForTesting(apk));
        } finally {
            java.nio.file.Files.deleteIfExists(apk);
        }
    }

    @Test
    public void rejectsUnknownEventsAndPrefixPayloadFieldsInsteadOfSilentlyIgnoringThem() {
        ArrayList<String> full = new ArrayList<>();
        full.add("I/CoffeeGbBench: event=session_launch launch_ns=1 hardware=auto"
                + " requested_hardware=auto audio=on render=presentation warmup=off");
        full.add("I/CoffeeGbBench: event=rom_open_start wall_ns=2 since_launch_ms=1");
        full.add("I/CoffeeGbBench: event=hardware_profile requested_hardware=auto"
                + " requested_profile=auto profile=dmg family=dmg effective_gbc=false"
                + " effective_dmg_compat=false effective_mode=dmg speed_mode_initial=1"
                + " speed_mode_sample=boot_resolved clock_ticks_num=4194304 clock_ticks_den=1"
                + " clock_frames_num=60 clock_frames_den=1 clock_ticks_frame=69905");
        full.add("I/CoffeeGbBench: event=emulation_started wall_ns=3 prep_ms=1"
                + " requested_hardware=auto profile=dmg effective_gbc=false"
                + " effective_dmg_compat=false effective_mode=dmg speed_mode_initial=1"
                + " speed_mode_sample=boot_resolved clock_ticks_num=4194304 clock_ticks_den=1"
                + " clock_frames_num=60 clock_frames_den=1 clock_ticks_frame=69905");
        full.add("I/CoffeeGbBench: event=first_frame frame=1 wall_ns=4 since_launch_ms=3"
                + " prep_to_frame_ms=1");
        full.add("I/CoffeeGbBench: event=frames frame=60 ready_count=60 submitted_count=60"
                + " wall_ms=1000 wall_delta_ms=1003 fps=60 interval_fps=60 effective_mode=dmg"
                + " speed_mode_sample=boot_resolved controller_cpu_ms=10 controller_util_pct=1"
                + " gc_count_delta=0 gc_time_ms_delta=0 alloc_bytes_delta=0");
        full.add("I/CoffeeGbBench: event=audio_output sample_rate=48000 min_buffer_bytes=1"
                + " configured_buffer_bytes=2 actual_buffer_bytes=2");
        full.add("I/CoffeeGbBench: event=recent_missing");
        full.addAll(syntheticSummaryLog(61.0, 62.0));
        BenchmarkMatrix.Report fullReport = BenchmarkMatrix.parse(full, 19L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES, PARENT_ARTIFACT, CANDIDATE_ARTIFACT);
        assertTrue(fullReport.errors().toString(), fullReport.accepted());

        List<String> injected = new ArrayList<>(full);
        injected.add("CoffeeGbBench: event=debug rom_path=/secret");
        injected.add("CoffeeGbBench: leaked_field=secret event=frames");
        BenchmarkMatrix.Report report = parse(injected, 19L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(report.accepted());
        assertContains(report.errors(), "unknown benchmark event");
        assertContains(report.errors(), "forbidden payload field");
        assertContains(report.errors(), "unknown field outside benchmark event");
    }

    @Test
    public void rejectsNonAdjacentPairsAndInterleavedBlocksFromObservedMatrixOrder() {
        List<String> nonAdjacent = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        for (String block : BLOCK_NAMES) {
            List<Integer> positions = new ArrayList<>();
            List<String> parents = new ArrayList<>();
            List<String> candidates = new ArrayList<>();
            for (int index = 0; index < nonAdjacent.size(); index++) {
                String line = nonAdjacent.get(index);
                if (!line.startsWith("event=matrix_run")
                        || !line.contains("matrix_block=" + block + " ")) {
                    continue;
                }
                positions.add(index);
                if (line.contains("run_side=parent")) {
                    parents.add(line);
                } else {
                    candidates.add(line);
                }
            }
            ArrayList<String> reordered = new ArrayList<>(parents);
            reordered.addAll(candidates);
            for (int index = 0; index < positions.size(); index++) {
                nonAdjacent.set(positions.get(index), reordered.get(index));
            }
        }
        BenchmarkMatrix.Report pairReport = parse(nonAdjacent, 20L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(pairReport.accepted());
        assertContains(pairReport.errors(), "adjacent ordered two-side pairs");

        List<String> interleaved = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        int first = -1;
        int second = -1;
        for (int index = 0; index < interleaved.size(); index++) {
            if (!interleaved.get(index).startsWith("event=matrix_run")) {
                continue;
            }
            if (interleaved.get(index).contains("matrix_block=" + blockName(0) + " ")
                    && first < 0) {
                first = index;
            }
            if (interleaved.get(index).contains("matrix_block=" + blockName(1) + " ")
                    && second < 0) {
                second = index;
            }
        }
        String swap = interleaved.get(first);
        interleaved.set(first, interleaved.get(second));
        interleaved.set(second, swap);
        BenchmarkMatrix.Report blockReport = parse(interleaved, 21L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(blockReport.accepted());
        assertContains(blockReport.errors(), "matrix blocks are interleaved");
    }

    @Test
    public void rejectsDeclarationsBeforeCompletionsAndOverlappingRunIntervals() {
        List<String> declarationsFirst = declarationsBeforeCompletions();
        BenchmarkMatrix.Report declarationsReport = parse(declarationsFirst, 22L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(declarationsReport.accepted());
        assertContains(declarationsReport.errors(), "overlaps the preceding matrix run");

        List<String> overlap = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        int firstFinal = -1;
        int secondMatrix = -1;
        int matricesSeen = 0;
        for (int index = 0; index < overlap.size(); index++) {
            String line = overlap.get(index);
            if (firstFinal < 0 && line.startsWith("event=final_result")
                    && line.contains("matrix_block=" + blockName(0))) {
                firstFinal = index;
            } else if (line.startsWith("event=matrix_run")
                    && line.contains("matrix_block=" + blockName(0))
                    && matricesSeen++ == 1) {
                secondMatrix = index;
            }
        }
        assertTrue(firstFinal < secondMatrix);
        String finalLine = overlap.remove(firstFinal);
        overlap.add(secondMatrix + 1, finalLine);
        BenchmarkMatrix.Report overlapReport = parse(overlap, 23L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(overlapReport.accepted());
        assertContains(overlapReport.errors(), "overlaps the preceding matrix run");
    }

    @Test
    public void rejectsCompositorEvidenceAppendedAfterTheNextRunStarts() {
        ArrayList<String> reordered = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        int firstCompositor = -1;
        int nextMatrix = -1;
        for (int index = 0; index < reordered.size(); index++) {
            String line = reordered.get(index);
            if (firstCompositor < 0 && line.startsWith("event=compositor_result")
                    && line.contains("matrix_block=" + blockName(0) + " ")) {
                firstCompositor = index;
            } else if (firstCompositor >= 0 && nextMatrix < 0
                    && line.startsWith("event=matrix_run")
                    && line.contains("matrix_block=" + blockName(0) + " ")) {
                nextMatrix = index;
            }
        }
        assertTrue(firstCompositor >= 0);
        assertTrue(nextMatrix > firstCompositor);
        String compositor = reordered.remove(firstCompositor);
        // Inserting after the next run's declaration makes the preceding run's compositor
        // evidence overlap its successor while preserving all records for both runs.
        reordered.add(nextMatrix, compositor);

        BenchmarkMatrix.Report report = parse(reordered, 26L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(report.accepted());
        assertContains(report.errors(), "compositor_result must precede the next matrix_run");
    }

    @Test
    public void rejectsPathLikeAndUnboundedAllowedTelemetryValues() {
        List<String> pathValue = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        pathValue.add("event=frames frame=600 ready_count=600 submitted_count=600 fps=/secret/pokemon.gb");
        BenchmarkMatrix.Report pathReport = parse(pathValue, 24L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(pathReport.accepted());
        assertContains(pathReport.errors(), "path-like or ROM/save value is forbidden");

        List<String> unbounded = new ArrayList<>(syntheticSummaryLog(61.0, 62.0));
        unbounded.add("event=frames frame=600 ready_count=600 submitted_count=600"
                + " fps=999999999 interval_fps=60 effective_mode=dmg"
                + " speed_mode_sample=boot_resolved controller_cpu_ms=10"
                + " controller_util_pct=1 gc_count_delta=0 gc_time_ms_delta=0 alloc_bytes_delta=0");
        BenchmarkMatrix.Report telemetryReport = parse(unbounded, 25L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(telemetryReport.accepted());
        assertContains(telemetryReport.errors(), "invalid bounded number fps");
    }

    @Test
    public void acceptsSmallTelemetryDriftButRejectsExcessiveOrMissingEligibilityEvidence() {
        List<String> smallDrift = replaceAllForArtifact(syntheticSummaryLog(61.0, 62.0),
                PARENT_ARTIFACT, "battery_temp_start=250", "battery_temp_start=260");
        smallDrift = replaceAllForArtifact(smallDrift, PARENT_ARTIFACT,
                "system_load_worst_milli=100", "system_load_worst_milli=200");
        BenchmarkMatrix.Report accepted = parse(smallDrift, 12L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertTrue(accepted.errors().toString(), accepted.accepted());

        List<String> excessiveDrift = replaceAllForArtifact(syntheticSummaryLog(61.0, 62.0),
                PARENT_ARTIFACT, "battery_temp_start=250", "battery_temp_start=300");
        excessiveDrift = replaceAllForArtifact(excessiveDrift, PARENT_ARTIFACT,
                "system_load_worst_milli=100", "system_load_worst_milli=2200");
        BenchmarkMatrix.Report driftReport = parse(excessiveDrift, 13L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(driftReport.accepted());
        assertContains(driftReport.errors(), "has mixed run configuration");

        List<String> missingTemperature = replaceAllForArtifact(syntheticSummaryLog(61.0, 62.0),
                PARENT_ARTIFACT, "battery_temp_start=250", "battery_temp_start=-1");
        BenchmarkMatrix.Report missingReport = parse(missingTemperature, 14L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(missingReport.accepted());
        assertContains(missingReport.errors(), "intrinsic thermal/display/power evidence");
    }

    @Test
    public void rejectsInsufficientRefreshAndForegroundEvidence() {
        List<String> lowRefresh = replaceAllForArtifact(syntheticSummaryLog(61.0, 62.0),
                PARENT_ARTIFACT, "display_refresh_start_millihz=120000",
                "display_refresh_start_millihz=59940");
        lowRefresh = replaceAllForArtifact(lowRefresh, PARENT_ARTIFACT,
                "display_refresh_end_millihz=120000", "display_refresh_end_millihz=59940");
        BenchmarkMatrix.Report refreshReport = parse(lowRefresh, 15L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(refreshReport.accepted());
        assertContains(refreshReport.errors(), "intrinsic thermal/display/power evidence");

        List<String> lowPriority = replaceAllForArtifact(syntheticSummaryLog(61.0, 62.0),
                PARENT_ARTIFACT, "thread_priority_start=0", "thread_priority_start=10");
        lowPriority = replaceAllForArtifact(lowPriority, PARENT_ARTIFACT,
                "thread_priority_end=0", "thread_priority_end=10");
        BenchmarkMatrix.Report priorityReport = parse(lowPriority, 16L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(priorityReport.accepted());
        assertContains(priorityReport.errors(), "intrinsic thermal/display/power evidence");

        List<String> noStayOnPolicy = replaceAllForArtifact(syntheticSummaryLog(61.0, 62.0),
                PARENT_ARTIFACT, "stay_on_plugged_mask_start=1", "stay_on_plugged_mask_start=0");
        noStayOnPolicy = replaceAllForArtifact(noStayOnPolicy, PARENT_ARTIFACT,
                "stay_on_plugged_mask_end=1", "stay_on_plugged_mask_end=0");
        BenchmarkMatrix.Report stayReport = parse(noStayOnPolicy, 17L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(stayReport.accepted());
        assertContains(stayReport.errors(), "intrinsic thermal/display/power evidence");
    }

    @Test
    public void rejectsDroppedOutpacedAndCorruptResultsEvenWhenFinalRecordExists() {
        List<String> dropped = replaceFirst(syntheticSummaryLog(61.0, 62.0),
                "dropped_count=0", "dropped_count=1");
        BenchmarkMatrix.Report droppedReport = parse(dropped, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(droppedReport.accepted());
        assertContains(droppedReport.errors(), "dropped, duplicate, late, or corrupt");

        List<String> outpaced = replaceFirst(syntheticSummaryLog(61.0, 62.0),
                "submitted_count=600", "submitted_count=599");
        BenchmarkMatrix.Report outpacedReport = parse(outpaced, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(outpacedReport.accepted());
        assertContains(outpacedReport.errors(), "exactly 600 ready/surface-submission");

        List<String> corrupt = replaceFirst(syntheticSummaryLog(61.0, 62.0),
                "corrupt_count=0", "corrupt_count=1");
        BenchmarkMatrix.Report corruptReport = parse(corrupt, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(corruptReport.accepted());
        assertContains(corruptReport.errors(), "dropped, duplicate, late, or corrupt");

        List<String> gap = replaceFirst(syntheticSummaryLog(61.0, 62.0),
                "submission_last_id=600", "submission_last_id=601");
        BenchmarkMatrix.Report gapReport = parse(gap, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(gapReport.accepted());
        assertContains(gapReport.errors(), "missing bounded ready/surface-submission timestamp summaries");

        List<String> oneExtraReady = replaceFirst(syntheticSummaryLog(61.0, 62.0),
                "ready_count=600", "ready_count=601");
        oneExtraReady = replaceFirst(oneExtraReady, "ready_last_id=600", "ready_last_id=601");
        BenchmarkMatrix.Report extraReadyReport = parse(oneExtraReady, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(extraReadyReport.accepted());
        assertContains(extraReadyReport.errors(), "missing bounded ready/surface-submission timestamp summaries");

        List<String> zeroAudio = replaceFirstKey(syntheticSummaryLog(61.0, 62.0),
                "audio_pcm_written_frames", "0");
        BenchmarkMatrix.Report zeroAudioReport = parse(zeroAudio, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(zeroAudioReport.accepted());
        assertContains(zeroAudioReport.errors(), "intrinsic audio output evidence");

        List<String> oneEventThenSilent = replaceFirstKey(syntheticSummaryLog(61.0, 62.0),
                "audio_pcm_input_events", "1");
        BenchmarkMatrix.Report silentAudioReport = parse(oneEventThenSilent, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(silentAudioReport.accepted());
        assertContains(silentAudioReport.errors(), "intrinsic audio output evidence");

        List<String> outputUnderrun = replaceFirstKey(syntheticSummaryLog(61.0, 62.0),
                "audio_track_underruns", "1");
        BenchmarkMatrix.Report outputUnderrunReport = parse(outputUnderrun, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(outputUnderrunReport.accepted());
        // A recovered output underrun keeps a slow Accuracy baseline structurally comparable,
        // but it is never eligible for the real-time acceptance target.
        assertTrue(outputUnderrunReport.valid());
        assertTrue(outputUnderrunReport.errors().isEmpty());

        List<String> unavailableOutputUnderrun = replaceFirstKey(
                syntheticSummaryLog(61.0, 62.0), "audio_track_underruns", "-1");
        BenchmarkMatrix.Report unavailableOutputUnderrunReport = parse(unavailableOutputUnderrun,
                1L, BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(unavailableOutputUnderrunReport.accepted());
        assertContains(unavailableOutputUnderrunReport.errors(), "intrinsic audio output evidence");

        List<String> lostFocus = replaceFirstKey(syntheticSummaryLog(61.0, 62.0),
                "audio_focus_loss_count", "1");
        BenchmarkMatrix.Report lostFocusReport = parse(lostFocus, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(lostFocusReport.accepted());
        assertContains(lostFocusReport.errors(), "intrinsic audio output evidence");

        List<String> deniedFocus = replaceFirstKey(syntheticSummaryLog(61.0, 62.0),
                "audio_focus_granted", "false");
        BenchmarkMatrix.Report deniedFocusReport = parse(deniedFocus, 1L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(deniedFocusReport.accepted());
        assertContains(deniedFocusReport.errors(), "intrinsic audio output evidence");
    }

    @Test
    public void rejectsFirstSideLiesAndObservedRowOrderLies() {
        List<String> firstSideLie = new ArrayList<>();
        for (String line : syntheticSummaryLog(61.0, 62.0)) {
            if (line.contains("matrix_block=" + blockName(0))
                    && line.contains("first_side=parent")) {
                line = line.replace("first_side=parent", "first_side=candidate");
            }
            firstSideLie.add(line);
        }
        BenchmarkMatrix.Report firstSideReport = parse(firstSideLie, 7L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(firstSideReport.accepted());
        assertContains(firstSideReport.errors(),
                "declares first_side=candidate but the earlier matrix_run is parent");

        List<String> rowOrderLie = new ArrayList<>();
        for (String line : syntheticSummaryLog(61.0, 62.0)) {
            if (line.contains("matrix_block=" + blockName(0))
                    && line.contains("effective_mode=sgb2")) {
                line = line.replace("row_order=0", "row_order=1");
            } else if (line.contains("matrix_block=" + blockName(0))
                    && line.contains("effective_mode=cgb-dmg-compat")) {
                line = line.replace("row_order=1", "row_order=0");
            }
            rowOrderLie.add(line);
        }
        BenchmarkMatrix.Report rowOrderReport = parse(rowOrderLie, 8L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(rowOrderReport.accepted());
        assertContains(rowOrderReport.errors(), "observed row sgb2 at order 0");
    }

    @Test
    public void reportsConfirmedRegressionAndIndividualBelow58Alarm() {
        BenchmarkMatrix.Report regression = parse(syntheticLog(61.0, 55.0),
                3L, BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(regression.accepted());
        BenchmarkMatrix.RowSummary row = regression.row(BenchmarkMatrix.Row.DMG);
        assertTrue(row.regression);
        assertTrue(row.alarm);
        assertTrue(row.candidateFps.lower < 60.0);

        BenchmarkMatrix.Report alarm = parse(syntheticLog(61.0, 57.5),
                4L, BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(alarm.accepted());
        assertTrue(alarm.row(BenchmarkMatrix.Row.SGB2).alarm);
    }

    @Test
    public void explicitUnavailableNativeRowsAreNotSubstitutedByCompatibility() {
        List<String> log = syntheticSummaryLog(61.0, 62.0).stream()
                .filter(line -> !line.contains("effective_mode=cgb-native")
                        && !line.contains("effective_mode=cgb0-native"))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        for (String row : List.of("cgb-native", "cgb0-native")) {
            log.add(unavailableMatrixRun(row));
        }
        BenchmarkMatrix.Report report = parse(log, 5L,
                BenchmarkMatrix.BOOTSTRAP_RESAMPLES);
        assertFalse(report.accepted());
        assertContains(report.errors(), "cgb-native is explicitly unavailable");
        assertContains(report.errors(), "cgb0-native is explicitly unavailable");
        assertTrue(report.row(BenchmarkMatrix.Row.CGB_NATIVE) == null);
        assertTrue(report.row(BenchmarkMatrix.Row.CGB0_NATIVE) == null);
    }

    private static String unavailableMatrixRun(String row) {
        String source = syntheticSummaryLog(61.0, 62.0).stream()
                .filter(line -> line.startsWith("event=matrix_run")
                        && line.contains("effective_mode=" + row + " "))
                .findFirst()
                .orElseThrow();
        return source.replace("pair_id=p00-" + row, "pair_id=unavailable-" + row)
                .replace("matrix_block=zeta", "matrix_block=unavailable-" + row)
                .replace("availability=available", "availability=unavailable");
    }

    private static List<String> syntheticLog(double parentFps, double candidateFps) {
        ArrayList<String> lines = new ArrayList<>();
        BenchmarkMatrix.Row[] order = {
                BenchmarkMatrix.Row.SGB2, BenchmarkMatrix.Row.CGB_DMG_COMPAT,
                BenchmarkMatrix.Row.DMG, BenchmarkMatrix.Row.CGB0_NATIVE,
                BenchmarkMatrix.Row.MGB, BenchmarkMatrix.Row.CGB_NATIVE,
                BenchmarkMatrix.Row.SGB};
        for (int pair = 0; pair < BenchmarkMatrix.MIN_PAIRS; pair++) {
            String block = blockName(pair);
            String first = (pair & 1) == 0 ? "parent" : "candidate";
            for (int rowOrder = 0; rowOrder < order.length; rowOrder++) {
                BenchmarkMatrix.Row row = order[(rowOrder + pair) % order.length];
                String pairId = String.format(Locale.ROOT, "p%02d-%s", pair,
                        row.externalValue());
                if ("parent".equals(first)) {
                    appendRun(lines, "parent-build", "parent", first, pairId, block, rowOrder,
                            row.externalValue(), parentFps, pair * 2_000_000_000_000L);
                    appendRun(lines, "candidate-build", "candidate", first, pairId, block,
                            rowOrder, row.externalValue(), candidateFps,
                            pair * 2_000_000_000_000L + 1_000_000_000_000L);
                } else {
                    appendRun(lines, "candidate-build", "candidate", first, pairId, block,
                            rowOrder, row.externalValue(), candidateFps,
                            pair * 2_000_000_000_000L);
                    appendRun(lines, "parent-build", "parent", first, pairId, block, rowOrder,
                            row.externalValue(), parentFps,
                            pair * 2_000_000_000_000L + 1_000_000_000_000L);
                }
            }
        }
        return lines;
    }

    private static List<String> syntheticSummaryLog(double parentFps, double candidateFps) {
        ArrayList<String> lines = new ArrayList<>();
        BenchmarkMatrix.Row[] order = {
                BenchmarkMatrix.Row.SGB2, BenchmarkMatrix.Row.CGB_DMG_COMPAT,
                BenchmarkMatrix.Row.DMG, BenchmarkMatrix.Row.CGB0_NATIVE,
                BenchmarkMatrix.Row.MGB, BenchmarkMatrix.Row.CGB_NATIVE,
                BenchmarkMatrix.Row.SGB};
        for (int pair = 0; pair < BenchmarkMatrix.MIN_PAIRS; pair++) {
            String block = blockName(pair);
            String first = (pair & 1) == 0 ? "parent" : "candidate";
            for (int rowOrder = 0; rowOrder < order.length; rowOrder++) {
                BenchmarkMatrix.Row row = order[(rowOrder + pair) % order.length];
                String pairId = String.format(Locale.ROOT, "p%02d-%s", pair,
                        row.externalValue());
                if ("parent".equals(first)) {
                    appendSummaryRun(lines, "parent-build", "parent", first, pairId, block,
                            rowOrder, row.externalValue(), parentFps,
                            pair * 2_000_000_000_000L);
                    appendSummaryRun(lines, "candidate-build", "candidate", first, pairId, block,
                            rowOrder, row.externalValue(), candidateFps,
                            pair * 2_000_000_000_000L + 1_000_000_000_000L);
                } else {
                    appendSummaryRun(lines, "candidate-build", "candidate", first, pairId, block,
                            rowOrder, row.externalValue(), candidateFps,
                            pair * 2_000_000_000_000L);
                    appendSummaryRun(lines, "parent-build", "parent", first, pairId, block,
                            rowOrder, row.externalValue(), parentFps,
                            pair * 2_000_000_000_000L + 1_000_000_000_000L);
                }
            }
        }
        return lines;
    }

    private static List<String> syntheticSilentSummaryLog(double parentFps, double candidateFps) {
        return syntheticSilentSummaryLog(parentFps, candidateFps,
                List.of("dmg", "mgb", "cgb-native", "cgb0-native", "cgb-dmg-compat",
                        "sgb", "sgb2"));
    }

    private static List<String> syntheticRelaxedSilentSummaryLog(
            double parentFps, double candidateFps) {
        return syntheticSilentSummaryLog(parentFps, candidateFps,
                List.of("dmg", "mgb", "cgb-native", "cgb0-native", "cgb-dmg-compat"));
    }

    private static List<String> syntheticSilentSummaryLog(double parentFps, double candidateFps,
            List<String> silentRows) {
        List<String> source = syntheticSummaryLog(parentFps, candidateFps);
        Map<String, Map<String, Integer>> orders = new java.util.LinkedHashMap<>();
        List<String> selected = new ArrayList<>();
        for (String line : source) {
            String row = silentRow(line, silentRows);
            if (row == null) {
                continue;
            }
            String block = field(line, "matrix_block");
            if (line.startsWith("event=matrix_run")) {
                Map<String, Integer> blockOrders = orders.computeIfAbsent(block,
                        ignored -> new java.util.LinkedHashMap<>());
                blockOrders.computeIfAbsent(row, ignored -> blockOrders.size());
            }
            selected.add(line);
        }
        List<String> remapped = new ArrayList<>(selected.size());
        for (String line : selected) {
            String row = silentRow(line, silentRows);
            String block = field(line, "matrix_block");
            Integer order = orders.get(block).get(row);
            remapped.add(line.replaceFirst("row_order=[0-9]+", "row_order=" + order));
        }
        return remapped;
    }

    private static String silentRow(String line, List<String> rows) {
        for (String row : rows) {
            if (line.contains("-" + row + " ")) {
                return row;
            }
        }
        return null;
    }

    private static String field(String line, String key) {
        String prefix = key + "=";
        int start = line.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("missing field " + key);
        }
        int valueStart = start + prefix.length();
        int end = line.indexOf(' ', valueStart);
        return line.substring(valueStart, end < 0 ? line.length() : end);
    }

    private static List<String> declarationsBeforeCompletions() {
        ArrayList<String> result = new ArrayList<>();
        for (String block : BLOCK_NAMES) {
            ArrayList<String> declarations = new ArrayList<>();
            ArrayList<String> completions = new ArrayList<>();
            for (String line : syntheticSummaryLog(61.0, 62.0)) {
                if (!line.contains("matrix_block=" + block + " ")) {
                    continue;
                }
                if (line.startsWith("event=matrix_run")) {
                    declarations.add(line);
                } else {
                    completions.add(line);
                }
            }
            result.addAll(declarations);
            result.addAll(completions);
        }
        return result;
    }

    private static List<String> toSinkSummaryLog(List<String> source) {
        ArrayList<String> sink = new ArrayList<>(source.size());
        for (String line : source) {
            if (line.startsWith("event=compositor_result")) {
                continue;
            }
            String converted = line.replace("render=presentation", "render=sink")
                    .replace("submitted_count=600", "submitted_count=0")
                    .replace("submission_first_id=1", "submission_first_id=0")
                    .replace("submission_last_id=600", "submission_last_id=0")
                    .replaceFirst("submission_first_ns=[0-9]+", "submission_first_ns=0")
                    .replaceFirst("submission_last_ns=[0-9]+", "submission_last_ns=0")
                    .replaceFirst("submission_interval_fps=[0-9.]+",
                            "submission_interval_fps=0.000");
            sink.add(converted);
        }
        return sink;
    }

    private static String compositorEvidence(String artifact, String pairId, String block,
            int rowOrder, String side, long generation, String row, double readyFps) {
        boolean sgb = "sgb".equals(row);
        int refresh = sgb ? 120 : 60;
        // The synthetic compositor record describes the same ready cadence as the run.  Keep
        // its quantized-vsync total coherent with the host evidence so tests can exercise
        // accuracy runs well below the nominal hardware rate as well as near-real-time runs.
        long vsyncTotal = Math.max(1L, Math.round(599.0 * refresh / readyFps));
        double histogramFps = 599.0 * refresh / vsyncTotal;
        int minimumGap = Math.max(1, (int) Math.round(1000.0 * vsyncTotal / (599.0 * refresh)));
        return "event=compositor_result artifact_id=" + artifact + " device_id=" + DEVICE_ID
                + " pair_id=" + pairId + " matrix_block=" + block + " row_order=" + rowOrder
                + " run_side=" + side + " benchmark_generation=" + generation
                + " layer_id=" + COMPOSITOR_LAYER_ID + " layer_uid=10234"
                + " raw_total_frames=601 raw_histogram_frames=601 boundary_frames=1 boundary_intervals=2"
                + " total_frames=600 histogram_frames=599 present_interval_count=599"
                + " cadence_good_frames=599 cadence_bad_frames=0 cadence_vsync_total=" + vsyncTotal
                + " cadence_boundary_200_frames=1 cadence_boundary_1000_frames=1"
                + " cadence_max_gap_ms=1000 cadence_min_gap_ms=" + minimumGap
                + " compositor_histogram_fps=" + histogramFps
                + " dropped_frames=0"
                + " late_acquire_frames=0 bad_desired_present_frames=0"
                + " display_refresh_hz=" + refresh
                + " measurement=surfaceflinger_timestats";
    }

    private static void appendSummaryRun(List<String> lines, String buildId, String side,
            String firstSide, String pairId, String block, int rowOrder, String row,
            double fps, long baseNanos) {
        fps = syntheticMeasuredFps(row, side, fps);
        long interval = Math.max(1L, Math.round(1_000_000_000.0 / fps));
        long firstNanos = baseNanos + interval;
        long lastNanos = firstNanos + (BenchmarkMatrix.REQUIRED_FRAME_COUNT - 1L) * interval;
        double intervalFps = (BenchmarkMatrix.REQUIRED_FRAME_COUNT - 1L)
                * 1_000_000_000.0 / (lastNanos - firstNanos);
        Evidence evidence = evidence(row);
        String artifact = "parent".equals(side) ? PARENT_ARTIFACT : CANDIDATE_ARTIFACT;
        String inputContract = inputContractFor(row);
        if (!"none".equals(inputContract)) {
            lines.add(scenarioCompleteEvidence(artifact, pairId, block, rowOrder, side, row,
                    generationFor(baseNanos, side)));
        }
        lines.add("event=matrix_run build_profile=benchmark artifact_id=" + artifact + " pair_id=" + pairId
                + " matrix_block=" + block + " row_order=" + rowOrder
                + " run_side=" + side + " first_side=" + firstSide
                + " session_generation=" + generationFor(baseNanos, side)
                + " benchmark_generation=" + generationFor(baseNanos, side)
                + " workload_nonce=" + nonceFor(row) + " warmup=on input_contract=" + inputContract
                + " " + scenarioCompletionFields(row, generationFor(baseNanos, side))
                + " device_id=" + DEVICE_ID + " thermal_window=window-a"
                        + " audio=on render=presentation availability=available"
                        + " requested_profile=" + evidence.requestedProfile + " profile=" + evidence.profile
                        + " effective_gbc=" + evidence.gbc + " effective_dmg_compat=" + evidence.dmgCompat
                        + " effective_mode=" + row + " surface_vote_hz="
                        + ("sgb".equals(row) ? 120 : 60) + " display_target_hz="
                        + ("sgb".equals(row) ? 120 : 60) + " surface_content_rate_millihz="
                        + contentRateMillihz(row) + " " + clockEvidence(row) + " "
                + environmentStart() + " " + audioStartEvidence());
        lines.add(String.format(Locale.ROOT,
                "event=final_result build_profile=benchmark artifact_id=%s pair_id=%s matrix_block=%s row_order=%d "
                        + "run_side=%s session_generation=%d benchmark_generation=%d workload_nonce=%s warmup=on input_contract=%s %s drain_success=true "
                        + "frame=600 ready_count=600 submitted_count=600 "
                        + "dropped_count=0 duplicate_count=0 late_count=0 corrupt_count=0 "
                        + "ready_first_id=1 ready_last_id=600 ready_first_ns=%d ready_last_ns=%d "
                        + "submission_first_id=1 submission_last_id=600 "
                        + "submission_first_ns=%d submission_last_ns=%d "
                        + "ready_interval_fps=%.3f submission_interval_fps=%.3f "
                        + "wall_ms=%d fps=%.3f controller_cpu_ms=0 controller_util_pct=0 "
                        + "gc_count_delta=0 gc_time_ms_delta=0 alloc_bytes_delta=0 "
                        + "environment_sample_count=10 thermal_worst=0 "
                        + "system_load_worst_milli=100 cpu_freq_min_khz=1800000 "
                        + "display_refresh_min_millihz=120000 display_bad_count=0 "
                        + "interactive_bad_count=0 plugged_bad_count=0 power_save_bad_count=0 "
                        + "stay_awake_bad_count=0 priority_bad_count=0 importance_bad_count=0 "
                        + "battery_temp_min=250 battery_temp_max=250 live_input_mutations=0 "
                        + "surface_vote_hz=%d display_target_hz=%d surface_content_rate_millihz=%d "
                        + "speed_mode_sample=frame_600 "
                        + "requested_profile=%s profile=%s effective_gbc=%s "
                        + "effective_dmg_compat=%s effective_mode=%s device_id=%s %s "
                        + "%s %s %s",
                artifact, pairId, block, rowOrder, side, generationFor(baseNanos, side),
                generationFor(baseNanos, side), nonceFor(row), inputContract,
                scenarioCompletionFields(row, generationFor(baseNanos, side)),
                firstNanos, lastNanos,
                firstNanos, lastNanos, intervalFps, intervalFps,
                Math.max(1L, Math.round(1_000.0 * 599.0 / fps)), fps,
                "sgb".equals(row) ? 120 : 60, "sgb".equals(row) ? 120 : 60,
                contentRateMillihz(row), evidence.requestedProfile,
                evidence.profile, evidence.gbc, evidence.dmgCompat, row, DEVICE_ID,
                finalClockEvidence(row), environmentStart(), environmentEnd(),
                audioEvidence(row, fps)));
        lines.add(compositorEvidence(artifact, pairId, block, rowOrder, side,
                generationFor(baseNanos, side), row, fps));
    }

    private static void appendRun(List<String> lines, String buildId, String side,
            String firstSide, String pairId, String block, int rowOrder, String row,
            double fps, long baseNanos) {
        fps = syntheticMeasuredFps(row, side, fps);
        long interval = Math.max(1L, Math.round(1_000_000_000.0 / fps));
        ArrayList<Long> ready = new ArrayList<>(BenchmarkMatrix.REQUIRED_FRAME_COUNT);
        ArrayList<Long> presented = new ArrayList<>(BenchmarkMatrix.REQUIRED_FRAME_COUNT);
        Evidence evidence = evidence(row);
        String artifact = "parent".equals(side) ? PARENT_ARTIFACT : CANDIDATE_ARTIFACT;
        String inputContract = inputContractFor(row);
        if (!"none".equals(inputContract)) {
            lines.add(scenarioCompleteEvidence(artifact, pairId, block, rowOrder, side, row,
                    generationFor(baseNanos, side)));
        }
        lines.add("event=matrix_run build_profile=benchmark artifact_id=" + artifact + " pair_id=" + pairId
                + " matrix_block=" + block + " row_order=" + rowOrder
                + " run_side=" + side + " first_side=" + firstSide
                + " session_generation=" + generationFor(baseNanos, side)
                + " benchmark_generation=" + generationFor(baseNanos, side)
                + " workload_nonce=" + nonceFor(row) + " warmup=on input_contract=" + inputContract
                + " " + scenarioCompletionFields(row, generationFor(baseNanos, side))
                + " device_id=" + DEVICE_ID + " thermal_window=window-a"
                        + " audio=on render=presentation availability=available"
                        + " requested_profile=" + evidence.requestedProfile + " profile=" + evidence.profile
                        + " effective_gbc=" + evidence.gbc + " effective_dmg_compat=" + evidence.dmgCompat
                        + " effective_mode=" + row + " surface_vote_hz="
                        + ("sgb".equals(row) ? 120 : 60) + " display_target_hz="
                        + ("sgb".equals(row) ? 120 : 60) + " surface_content_rate_millihz="
                        + contentRateMillihz(row) + " " + clockEvidence(row) + " "
                + environmentStart() + " " + audioStartEvidence());
        for (int frame = 1; frame <= BenchmarkMatrix.REQUIRED_FRAME_COUNT; frame++) {
            long timestamp = baseNanos + frame * interval;
            ready.add(timestamp);
            presented.add(timestamp);
            lines.add("event=frame_ready artifact_id=" + artifact + " pair_id=" + pairId
                    + " matrix_block=" + block + " row_order=" + rowOrder
                    + " run_side=" + side + " ready_id=" + frame + " ready_ns=" + timestamp);
            lines.add("event=frame_submitted artifact_id=" + artifact + " pair_id=" + pairId
                    + " matrix_block=" + block + " row_order=" + rowOrder
                    + " run_side=" + side + " submission_id=" + frame
                    + " submission_ns=" + timestamp);
        }
        double intervalFps = BenchmarkMatrix.intervalFps(ready);
        lines.add(String.format(Locale.ROOT,
                "event=final_result build_profile=benchmark artifact_id=%s pair_id=%s matrix_block=%s row_order=%d "
                        + "run_side=%s session_generation=%d benchmark_generation=%d workload_nonce=%s warmup=on input_contract=%s %s drain_success=true "
                        + "frame=600 ready_count=600 submitted_count=600 "
                        + "dropped_count=0 duplicate_count=0 late_count=0 corrupt_count=0 "
                        + "ready_first_id=1 ready_last_id=600 ready_first_ns=%d ready_last_ns=%d "
                        + "submission_first_id=1 submission_last_id=600 "
                        + "submission_first_ns=%d submission_last_ns=%d "
                        + "ready_interval_fps=%.3f submission_interval_fps=%.3f "
                        + "wall_ms=%d fps=%.3f controller_cpu_ms=0 controller_util_pct=0 "
                        + "gc_count_delta=0 gc_time_ms_delta=0 alloc_bytes_delta=0 "
                        + "environment_sample_count=10 thermal_worst=0 "
                        + "system_load_worst_milli=100 cpu_freq_min_khz=1800000 "
                        + "display_refresh_min_millihz=120000 display_bad_count=0 "
                        + "interactive_bad_count=0 plugged_bad_count=0 power_save_bad_count=0 "
                        + "stay_awake_bad_count=0 priority_bad_count=0 importance_bad_count=0 "
                        + "battery_temp_min=250 battery_temp_max=250 live_input_mutations=0 "
                        + "surface_vote_hz=%d display_target_hz=%d surface_content_rate_millihz=%d "
                        + "speed_mode_sample=frame_600 "
                        + "requested_profile=%s profile=%s effective_gbc=%s "
                        + "effective_dmg_compat=%s effective_mode=%s device_id=%s %s "
                        + "%s %s %s",
                artifact, pairId, block, rowOrder, side, generationFor(baseNanos, side),
                generationFor(baseNanos, side), nonceFor(row), inputContract,
                scenarioCompletionFields(row, generationFor(baseNanos, side)),
                ready.get(0), ready.get(ready.size() - 1),
                presented.get(0), presented.get(presented.size() - 1), intervalFps, intervalFps,
                Math.max(1L, Math.round(1_000.0 * 599.0 / fps)), fps,
                "sgb".equals(row) ? 120 : 60, "sgb".equals(row) ? 120 : 60,
                contentRateMillihz(row), evidence.requestedProfile, evidence.profile,
                evidence.gbc, evidence.dmgCompat,
                row, DEVICE_ID, finalClockEvidence(row), environmentStart(), environmentEnd(),
                audioEvidence(row, fps)));
        lines.add(compositorEvidence(artifact, pairId, block, rowOrder, side,
                generationFor(baseNanos, side), row, fps));
    }

    private static String blockName(int pair) {
        return BLOCK_NAMES[pair];
    }

    private static long generationFor(long baseNanos, String side) {
        return baseNanos + ("parent".equals(side) ? 1L : 2L);
    }

    private static double syntheticMeasuredFps(String row, String side, double requestedFps) {
        BenchmarkMatrix.Row resolved = BenchmarkMatrix.Row.fromExternalValue(row);
        if (resolved == null || requestedFps <= 0.0 || requestedFps <= resolved.nominalFps()) {
            return requestedFps;
        }
        // Keep ordinary positive fixtures inside the physical cadence ceiling while preserving a
        // small candidate-vs-parent effect for bootstrap assertions. Explicit overclock tests
        // mutate the emitted evidence after generation.
        return resolved.nominalFps() * ("candidate".equals(side) ? 1.005 : 1.002);
    }

    private static Evidence evidence(String row) {
        return switch (row) {
            case "dmg" -> new Evidence("dmg", "dmg", false, false);
            case "mgb" -> new Evidence("mgb", "mgb", false, false);
            case "cgb-native" -> new Evidence("cgb", "cgb", true, false);
            case "cgb0-native" -> new Evidence("cgb0", "cgb0", true, false);
            case "cgb-dmg-compat" -> new Evidence("cgb", "cgb", true, true);
            case "cgb0-dmg-compat" -> new Evidence("cgb0", "cgb0", true, true);
            case "sgb" -> new Evidence("sgb", "sgb", false, false);
            case "sgb2" -> new Evidence("sgb2", "sgb2", false, false);
            default -> throw new IllegalArgumentException("unknown row " + row);
        };
    }

    private static String inputContractFor(String row) {
        return switch (row) {
            case "dmg", "mgb", "cgb-dmg-compat", "cgb0-dmg-compat" -> "dmg-action-v1";
            case "cgb-native", "cgb0-native" -> "cgb-action-v1";
            case "sgb", "sgb2" -> "none";
            default -> throw new IllegalArgumentException("unknown row " + row);
        };
    }

    private static int scenarioFramesFor(String row) {
        return switch (inputContractFor(row)) {
            case "dmg-action-v1" -> 313;
            case "cgb-action-v1" -> 923;
            default -> 0;
        };
    }

    private static String scenarioCompleteEvidence(String artifact, String pairId, String block,
            int rowOrder, String side, String row, long sessionGeneration) {
        int frames = scenarioFramesFor(row);
        return "event=scenario_complete artifact_id=" + artifact + " pair_id=" + pairId
                + " matrix_block=" + block + " row_order=" + rowOrder + " run_side=" + side
                + " session_generation=" + sessionGeneration
                + " input_contract=" + inputContractFor(row)
                + " completed=true completed_frames=" + frames
                + " expected_frames=" + frames
                + " source_closed=true audio_drained=true";
    }

    private static String scenarioCompletionFields(String row, long sessionGeneration) {
        int frames = scenarioFramesFor(row);
        return "scenario_session_generation="
                + (frames == 0 ? 0L : sessionGeneration)
                + " scenario_completed=true scenario_completed_frames=" + frames
                + " scenario_expected_frames=" + frames
                + " scenario_source_closed=true scenario_audio_drained=true";
    }

    private static String nonceFor(String row) {
        return switch (row) {
            case "cgb-native", "cgb0-native" -> COLOR_WORKLOAD_NONCE;
            default -> NONCOLOR_WORKLOAD_NONCE;
        };
    }

    private static String clockEvidence(String row) {
        return switch (row) {
            case "sgb" -> "speed_mode_initial=1"
                    + " clock_ticks_num=47250000 clock_ticks_den=11"
                    + " clock_frames_num=47250000 clock_frames_den=772464"
                    + " clock_ticks_frame=70224";
            case "sgb2" -> "speed_mode_initial=1"
                    + " clock_ticks_num=4194304 clock_ticks_den=1"
                    + " clock_frames_num=4194304 clock_frames_den=70224"
                    + " clock_ticks_frame=70224";
            default -> "speed_mode_initial=1"
                    + " clock_ticks_num=4194304 clock_ticks_den=1"
                    + " clock_frames_num=60 clock_frames_den=1"
                    + " clock_ticks_frame=69905";
        };
    }

    private static String finalClockEvidence(String row) {
        return clockEvidence(row) + " speed_mode_final=1";
    }

    private static int contentRateMillihz(String row) {
        return "sgb".equals(row) ? 61168 : 59728;
    }

    private static String environmentStart() {
        return "thermal_start=0 battery_temp_start=250 display_refresh_start_millihz=120000"
                + " display_state_start=2 interactive_start=true plugged_start=1"
                + " power_save_start=false stay_awake_start=true stay_on_plugged_mask_start=1"
                + " thread_priority_start=0 app_importance_start=100 system_load_start_milli=100"
                + " cpu_count_start=8 memory_available_start_bytes=1000000000";
    }

    private static String environmentEnd() {
        return "thermal_end=0 battery_temp_end=250 display_refresh_end_millihz=120000"
                + " display_state_end=2 interactive_end=true plugged_end=1"
                + " power_save_end=false stay_awake_end=true stay_on_plugged_mask_end=1"
                + " thread_priority_end=0 app_importance_end=100 system_load_end_milli=100"
                + " cpu_count_end=8 memory_available_end_bytes=1000000000";
    }

    private static String terminalAudioProof() {
        return " audio_terminal_active=true audio_terminal_output_playing=true"
                + " audio_terminal_overruns=0 audio_terminal_underruns=0"
                + " audio_terminal_track_underruns=0 audio_terminal_restarts=0"
                + " audio_terminal_write_failures=0 audio_terminal_route_failures=0"
                + " audio_terminal_output_identity=11 audio_terminal_queue_identity=12"
                + " audio_arm_overruns=0 audio_arm_underruns=0"
                + " audio_arm_track_underruns=0 audio_arm_restarts=0"
                + " audio_arm_write_failures=0 audio_arm_route_failures=0"
                + " audio_arm_output_identity=11 audio_arm_queue_identity=12";
    }

    private static String audioStartEvidence() {
        return "audio_start_input_events=0 audio_start_input_frames=0"
                + " audio_start_enqueued_bytes=0 audio_start_enqueued_frames=0"
                + " audio_start_written_bytes=0 audio_start_written_frames=0"
                + " audio_start_write_failures=0 audio_start_discarded_bytes=0"
                + " audio_start_pending_bytes=0 audio_start_queued_bytes=0"
                + " audio_start_playback_position_frames=1 audio_start_overruns=0"
                + " audio_start_underruns=0 audio_start_track_underruns=0"
                + " audio_start_restarts=0 audio_start_route_failures=0"
                + " audio_start_output_open=true audio_start_output_playing=true"
                + " audio_start_sample_rate=48000 audio_start_queue_capacity_frames=6"
                + " audio_start_max_frame_bytes=1000000";
    }

    private static String audioEvidence(String row, double fps) {
        double controllerFps = "sgb".equals(row)
                ? (47_250_000.0 / 11.0) / 70_224.0
                : "sgb2".equals(row) ? 4_194_304.0 / 70_224.0
                : 4_194_304.0 / 69_905.0;
        // Audio blocks follow emulated physical frames, not elapsed wall time.  Use the exact
        // 70,224-tick LCD cadence so slow Accuracy fixtures remain structurally comparable.
        long inputEvents = ("sgb".equals(row) || "sgb2".equals(row))
                ? BenchmarkMatrix.REQUIRED_FRAME_COUNT
                : Math.round(BenchmarkMatrix.REQUIRED_FRAME_COUNT * 70_224.0 / 69_905.0);
        long enqueuedFrames = Math.round(48_000.0 * BenchmarkMatrix.REQUIRED_FRAME_COUNT / fps);
        long writtenFrames = enqueuedFrames - 1L;
        long enqueuedBytes = enqueuedFrames * 4L;
        long writtenBytes = writtenFrames * 4L;
        return "audio_active=true audio_sample_rate=48000 audio_overruns=0 audio_underruns=0"
                + " audio_track_underruns=0"
                + " audio_restarts=0 audio_paused=false audio_min_buffer_bytes=1024"
                + " audio_configured_buffer_bytes=2048 audio_actual_buffer_bytes=2048"
                + " audio_pcm_input_events=" + inputEvents
                + " audio_pcm_input_frames=41943000"
                + " audio_pcm_enqueued_bytes=" + enqueuedBytes
                + " audio_pcm_enqueued_frames=" + enqueuedFrames
                + " audio_pcm_written_bytes=" + writtenBytes
                + " audio_pcm_written_frames=" + writtenFrames
                + " audio_write_failures=0 audio_pcm_discarded_bytes=0"
                + " audio_pcm_pending_bytes=4 audio_pcm_queued_bytes=4 audio_queue_frames=1"
                + " audio_output_open=true audio_output_playing=true audio_muted=false"
                + " audio_volume=100 audio_route_failures=0"
                + " audio_playback_position_frames=100 audio_system_volume=10"
                + " audio_system_volume_max=15 audio_system_music_muted=false"
                + " audio_queue_capacity_frames=6 audio_max_frame_bytes=1000000"
                + " " + audioStartEvidence()
                + " audio_focus_granted=true audio_focus_start_loss_count=0"
                + " audio_focus_loss_count=0";
    }

    private static List<String> withSilentPolicyForSilentRows(List<String> source) {
        return withSilentPolicyForRows(source,
                List.of("dmg", "mgb", "cgb-native", "cgb0-native", "cgb-dmg-compat",
                        "sgb", "sgb2"));
    }

    private static List<String> withSilentPolicyForRows(List<String> source,
            List<String> selectedRows) {
        String token = "silent-token-001";
        ArrayList<String> result = new ArrayList<>(source.size());
        for (String original : source) {
            String row = silentRow(original, selectedRows);
            if (row == null) {
                result.add(original);
                continue;
            }
            String line = original;
            boolean sgbExactScenario = ("sgb".equals(row) || "sgb2".equals(row));
            if (sgbExactScenario && line.startsWith("event=matrix_run")) {
                result.add("event=scenario_complete artifact_id=" + field(line, "artifact_id")
                        + " pair_id=" + field(line, "pair_id")
                        + " matrix_block=" + field(line, "matrix_block")
                        + " row_order=" + field(line, "row_order")
                        + " run_side=" + field(line, "run_side")
                        + " session_generation=" + field(line, "session_generation")
                        + " input_contract=dmg-action-v1 completed=true completed_frames=313"
                        + " expected_frames=313 source_closed=true audio_drained=true");
                line = line.replace("input_contract=none", "input_contract=dmg-action-v1")
                        .replace("scenario_session_generation=0",
                                "scenario_session_generation=" + field(line, "session_generation"))
                        .replace("scenario_completed_frames=0", "scenario_completed_frames=313")
                        .replace("scenario_expected_frames=0", "scenario_expected_frames=313");
            } else if (sgbExactScenario && line.startsWith("event=final_result")) {
                line = line.replace("input_contract=none", "input_contract=dmg-action-v1")
                        .replace("scenario_session_generation=0",
                                "scenario_session_generation=" + field(line, "session_generation"))
                        .replace("scenario_completed_frames=0", "scenario_completed_frames=313")
                        .replace("scenario_expected_frames=0", "scenario_expected_frames=313");
            }
            if (line.startsWith("event=matrix_run")) {
                line += " benchmark_token=" + token + " benchmark_audio_policy=silent-pcm-v1"
                        + " audio_start_active=true audio_start_paused=false"
                        + " audio_start_muted=false audio_start_volume=100"
                        + " audio_start_system_volume=0 audio_start_system_volume_max=15"
                        + " audio_start_system_music_muted=true audio_start_queued_frames=0"
                        + " audio_start_reopen_pending=false"
                        + " audio_start_output_identity=11 audio_start_queue_identity=12";
                line = line.replace("audio_start_input_events=0", "audio_start_input_events=1")
                        .replace("audio_start_input_frames=0", "audio_start_input_frames=1")
                        .replace("audio_start_written_bytes=0", "audio_start_written_bytes=4")
                        .replace("audio_start_written_frames=0", "audio_start_written_frames=1");
            } else if (line.startsWith("event=final_result")) {
                line = line.replace("audio_system_volume=10", "audio_system_volume=0")
                        .replace("audio_system_music_muted=false",
                                "audio_system_music_muted=true");
                line = incrementLongField(line, "audio_pcm_input_events", 1L);
                line = incrementLongField(line, "audio_pcm_input_frames", 1L);
                line = incrementLongField(line, "audio_pcm_written_bytes", 4L);
                line = incrementLongField(line, "audio_pcm_written_frames", 1L);
                line += " benchmark_token=" + token + " benchmark_audio_policy=silent-pcm-v1"
                        + " benchmark_audio_requested=true"
                        + " benchmark_audio_active_at_boundary=true"
                        + " benchmark_audio_disabled_after=true"
                        + " benchmark_audio_skipped_ticks=1000"
                        + " benchmark_audio_zero_sample_slots=10"
                        + " benchmark_audio_zero_sample_events=2"
                        + " benchmark_audio_max_debt=100"
                        + " benchmark_audio_apu_reads=0 benchmark_audio_apu_writes=0"
                        + " benchmark_audio_frame_sequencer_commits=1"
                        + " benchmark_audio_dropped_channel_ticks=0"
                        + " system_audio_sample_count=12 system_audio_bad_count=0"
                        + " audio_output_identity=11 audio_queue_identity=12";
            }
            result.add(line);
        }
        return result;
    }

    private static String incrementLongField(String line, String key, long delta) {
        String prefix = key + "=";
        int start = line.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("missing field " + key);
        }
        int valueStart = start + prefix.length();
        int end = line.indexOf(' ', valueStart);
        if (end < 0) {
            end = line.length();
        }
        long value = Long.parseLong(line.substring(valueStart, end));
        return line.substring(0, valueStart) + (value + delta) + line.substring(end);
    }

    private static String compactFinalAudioProof(String line, String flags, String calendar) {
        String compact = line;
        for (String key : List.of(
                "benchmark_audio_requested", "benchmark_audio_active_at_boundary",
                "benchmark_audio_disabled_after", "benchmark_audio_skipped_ticks",
                "benchmark_audio_zero_sample_slots", "benchmark_audio_zero_sample_events",
                "benchmark_audio_max_debt", "benchmark_audio_apu_reads",
                "benchmark_audio_apu_writes", "benchmark_audio_frame_sequencer_commits",
                "benchmark_audio_dropped_channel_ticks")) {
            compact = compact.replaceAll(" " + key + "=[^ ]*", "");
        }
        return compact + " benchmark_audio_flags=" + flags
                + " benchmark_audio_calendar=" + calendar;
    }

    private record Evidence(String requestedProfile, String profile, boolean gbc,
            boolean dmgCompat) {
    }

    private static List<String> replaceFirst(List<String> source, String from, String to) {
        ArrayList<String> result = new ArrayList<>(source);
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).contains(from)) {
                result.set(index, result.get(index).replace(from, to));
                break;
            }
        }
        return result;
    }

    private static List<String> omitFinalAudioStartFields(List<String> source) {
        ArrayList<String> result = new ArrayList<>(source.size());
        for (String line : source) {
            if (!line.startsWith("event=final_result")) {
                result.add(line);
                continue;
            }
            StringBuilder bounded = new StringBuilder(line.length());
            for (String field : line.split(" ")) {
                if (!field.startsWith("audio_start_")) {
                    if (!bounded.isEmpty()) {
                        bounded.append(' ');
                    }
                    bounded.append(field);
                }
            }
            result.add(bounded.toString());
        }
        return result;
    }

    private static List<String> omitFinalEnvironmentStartFields(List<String> source) {
        ArrayList<String> result = new ArrayList<>(source.size());
        String block = " " + environmentStart();
        for (String line : source) {
            result.add(line.startsWith("event=final_result") ? line.replace(block, "") : line);
        }
        return result;
    }

    private static List<String> replaceFirstLine(List<String> source, String prefix,
            String contains, String from, String to) {
        ArrayList<String> result = new ArrayList<>(source);
        for (int index = 0; index < result.size(); index++) {
            String line = result.get(index);
            if (line.startsWith(prefix) && line.contains(contains) && line.contains(from)) {
                result.set(index, line.replace(from, to));
                break;
            }
        }
        return result;
    }

    private static List<String> replaceFirstLineKey(List<String> source, String prefix,
            String contains, String key, String replacement) {
        ArrayList<String> result = new ArrayList<>(source);
        String field = key + "=";
        for (int index = 0; index < result.size(); index++) {
            String line = result.get(index);
            int start = line.indexOf(field);
            if (!line.startsWith(prefix) || !line.contains(contains) || start < 0) {
                continue;
            }
            int end = line.indexOf(' ', start);
            if (end < 0) {
                end = line.length();
            }
            result.set(index, line.substring(0, start) + field + replacement
                    + line.substring(end));
            break;
        }
        return result;
    }

    private static List<String> replaceFirstKey(List<String> source, String key,
            String replacement) {
        ArrayList<String> result = new ArrayList<>(source);
        String prefix = key + "=";
        for (int index = 0; index < result.size(); index++) {
            String line = result.get(index);
            int start = line.indexOf(prefix);
            if (start < 0) {
                continue;
            }
            int end = line.indexOf(' ', start);
            if (end < 0) {
                end = line.length();
            }
            result.set(index, line.substring(0, start) + prefix + replacement
                    + line.substring(end));
            break;
        }
        return result;
    }

    private static List<String> replaceAllForArtifact(List<String> source, String artifact,
            String from, String to) {
        ArrayList<String> result = new ArrayList<>(source.size());
        for (String line : source) {
            result.add(line.contains("artifact_id=" + artifact) ? line.replace(from, to) : line);
        }
        return result;
    }

    private static void assertContains(List<String> errors, String expected) {
        for (String error : errors) {
            if (error.contains(expected)) {
                return;
            }
        }
        throw new AssertionError("missing error '" + expected + "' in " + errors);
    }
}
