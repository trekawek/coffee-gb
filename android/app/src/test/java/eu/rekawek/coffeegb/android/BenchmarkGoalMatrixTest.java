package eu.rekawek.coffeegb.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BenchmarkGoalMatrixTest {

    private static final String NONCE_D = "nonce-d-000000000001";
    private static final String NONCE_U = "nonce-u-000000000001";
    private static final String NONCE_C1 = "nonce-c1-00000000001";
    private static final String NONCE_C2 = "nonce-c2-00000000001";
    private static final String ARTIFACT_PARENT =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String ARTIFACT_CANDIDATE =
            "2222222222222222222222222222222222222222222222222222222222222222";
    private static final String DEVICE_ID =
            "3333333333333333333333333333333333333333333333333333333333333333";

    @Test
    public void matrixContainsExactlyEightCellsAndNoSgb2() {
        assertEquals(8, BenchmarkGoalMatrix.cells().size());
        assertEquals(List.of(
                "d-dmg", "d-cgb-compat", "d-sgb", "u-dmg", "u-cgb-native", "u-sgb",
                "c1-cgb-native", "c2-cgb-native"),
                BenchmarkGoalMatrix.cells().stream().map(BenchmarkWorkload.Cell::externalValue)
                        .toList());
        assertFalse(BenchmarkGoalMatrix.cells().stream()
                .anyMatch(cell -> cell.externalValue().contains("sgb2")));
    }

    @Test
    public void uTimelineIsOneImmutable1297FrameContractForAllThreeCells() {
        BenchmarkWorkload.Timeline timeline = BenchmarkWorkload.timeline(BenchmarkWorkload.Slot.U);
        assertTrue(timeline.complete());
        assertEquals("u-v1", timeline.id());
        assertEquals(1296, timeline.advanceCount());
        assertEquals(1297, timeline.endpointFrame());
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, timeline.maskForFrame(1));
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, timeline.maskForFrame(420));
        assertEquals(BenchmarkGameplayScenario.START_MASK, timeline.maskForFrame(421));
        assertEquals(BenchmarkGameplayScenario.A_MASK, timeline.maskForFrame(484));
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, timeline.maskForFrame(1297));
        assertEquals(timeline, BenchmarkWorkload.timelineForCell(BenchmarkWorkload.Cell.U_DMG));
        assertEquals(timeline, BenchmarkWorkload.timelineForCell(BenchmarkWorkload.Cell.U_CGB_NATIVE));
        assertEquals(timeline, BenchmarkWorkload.timelineForCell(BenchmarkWorkload.Cell.U_SGB));
    }

    @Test
    public void nonUTimelinesRemainDistinctAndFrameExact() {
        BenchmarkWorkload.Timeline d = BenchmarkWorkload.timeline(BenchmarkWorkload.Slot.D);
        BenchmarkWorkload.Timeline c1 = BenchmarkWorkload.timeline(BenchmarkWorkload.Slot.C1);
        BenchmarkWorkload.Timeline c2 = BenchmarkWorkload.timeline(BenchmarkWorkload.Slot.C2);
        assertEquals(313, d.endpointFrame());
        assertEquals(1582, c1.endpointFrame());
        assertEquals(1084, c2.endpointFrame());
        assertEquals(BenchmarkGameplayScenario.START_MASK, d.maskForFrame(120));
        assertEquals(BenchmarkGameplayScenario.A_MASK, c1.maskForFrame(670));
        assertEquals(BenchmarkGameplayScenario.B_MASK, c2.maskForFrame(961));
        assertTrue(d != c1 && d != c2 && c1 != c2);
    }

    @Test
    public void parserEnumeratesAllEightCellsWithCompleteContracts() {
        List<String> records = validRecords();
        BenchmarkGoalMatrix.Report report = BenchmarkGoalMatrix.parse(records);
        assertTrue(report.errors().toString(), report.accepted());
        assertTrue(report.errors().isEmpty());
        assertEquals(16, report.runCount());
        assertEquals(ARTIFACT_PARENT, report.artifactId("parent"));
        assertEquals(ARTIFACT_CANDIDATE, report.artifactId("candidate"));
        assertTrue(BenchmarkGoalMatrix.parse(records, ARTIFACT_PARENT,
                ARTIFACT_CANDIDATE).accepted());
        assertFalse(BenchmarkGoalMatrix.parse(records, ARTIFACT_CANDIDATE,
                ARTIFACT_PARENT).accepted());
    }

    @Test
    public void parserRejectsMissingDuplicateAndSubstitutedCells() {
        List<String> missing = validRecords();
        missing.remove(0);
        assertFalse(BenchmarkGoalMatrix.parse(missing).accepted());

        List<String> duplicate = validRecords();
        duplicate.add(duplicate.get(0));
        assertFalse(BenchmarkGoalMatrix.parse(duplicate).accepted());

        List<String> substituted = validRecords();
        substituted.set(0, substituted.get(0).replace("cell_id=d-dmg", "cell_id=u-dmg"));
        assertFalse(BenchmarkGoalMatrix.parse(substituted).accepted());
    }

    @Test
    public void parserRejectsNonceScenarioAndEffectiveProfileDrift() {
        List<String> nonce = validRecords();
        nonce.set(3, nonce.get(3).replace(NONCE_D, "nonce-d-000000000002"));
        assertFalse(BenchmarkGoalMatrix.parse(nonce).accepted());

        List<String> scenario = validRecords();
        scenario.set(0, scenario.get(0).replace("scenario_count=313", "scenario_count=314"));
        assertFalse(BenchmarkGoalMatrix.parse(scenario).accepted());

        List<String> profile = validRecords();
        profile.set(0, profile.get(0).replace("effective_profile=dmg", "effective_profile=sgb"));
        assertFalse(BenchmarkGoalMatrix.parse(profile).accepted());
    }

    @Test
    public void parserRequiresBothSchedulerAndSpeedSums() {
        List<String> scheduler = validRecords();
        scheduler.set(2, scheduler.get(2).replace(
                "scheduler_scalar_ticks=42134400", "scheduler_scalar_ticks=42134401"));
        assertFalse(BenchmarkGoalMatrix.parse(scheduler).accepted());

        List<String> speed = validRecords();
        speed.set(2, speed.get(2).replace(
                "scheduler_speed1_ticks=42134400", "scheduler_speed1_ticks=42134401"));
        assertFalse(BenchmarkGoalMatrix.parse(speed).accepted());
    }

    @Test
    public void parserRequiresProfileExactSpeedPartitionAndClockSpec() {
        List<String> nonNativeSpeed = validRecords();
        for (int index = 0; index < nonNativeSpeed.size(); index++) {
            if (nonNativeSpeed.get(index).contains("event=core_result")
                    && nonNativeSpeed.get(index).contains("cell_id=d-dmg")) {
                nonNativeSpeed.set(index, nonNativeSpeed.get(index)
                        .replace("scheduler_speed1_ticks=42134400", "scheduler_speed1_ticks=42134399")
                        .replace("scheduler_speed2_ticks=0", "scheduler_speed2_ticks=1"));
                break;
            }
        }
        assertFalse(BenchmarkGoalMatrix.parse(nonNativeSpeed).accepted());

        List<String> wrongLegacyClock = validRecords();
        for (int index = 0; index < wrongLegacyClock.size(); index++) {
            if (wrongLegacyClock.get(index).contains("event=final_result")
                    && wrongLegacyClock.get(index).contains("cell_id=d-dmg")) {
                wrongLegacyClock.set(index, wrongLegacyClock.get(index)
                        .replace("clock_ticks_frame=69905", "clock_ticks_frame=70224"));
                break;
            }
        }
        assertFalse(BenchmarkGoalMatrix.parse(wrongLegacyClock).accepted());

        List<String> wrongSgbClock = validRecords();
        for (int index = 0; index < wrongSgbClock.size(); index++) {
            if (wrongSgbClock.get(index).contains("event=final_result")
                    && wrongSgbClock.get(index).contains("cell_id=d-sgb")) {
                wrongSgbClock.set(index, wrongSgbClock.get(index)
                        .replace("clock_frames_den=2299", "clock_frames_den=2300"));
                break;
            }
        }
        assertFalse(BenchmarkGoalMatrix.parse(wrongSgbClock).accepted());
    }

    @Test
    public void parserBindsSgbAllocationControlToRunSide() {
        List<String> parentTooFew = validRecords();
        replaceSgbCoreField(parentTooFew, "parent",
                "scheduler_sgb_frame_array_allocations=600",
                "scheduler_sgb_frame_array_allocations=599");
        assertFalse(BenchmarkGoalMatrix.parse(parentTooFew).accepted());

        List<String> parentTooMany = validRecords();
        replaceSgbCoreField(parentTooMany, "parent",
                "scheduler_sgb_frame_array_allocations=600",
                "scheduler_sgb_frame_array_allocations=601");
        assertFalse(BenchmarkGoalMatrix.parse(parentTooMany).accepted());

        List<String> candidateAllocated = validRecords();
        replaceSgbCoreField(candidateAllocated, "candidate",
                "scheduler_sgb_frame_array_allocations=0",
                "scheduler_sgb_frame_array_allocations=1");
        assertFalse(BenchmarkGoalMatrix.parse(candidateAllocated).accepted());
    }

    @Test
    public void parserAcceptsDecimalTerminalFps() {
        List<String> records = validRecords();
        records.set(3, records.get(3).replace("fps=60", "fps=59.875"));
        assertTrue(BenchmarkGoalMatrix.parse(records).accepted());
    }

    @Test
    public void parserRequiresFiniteTerminalFpsAndLiveInputSpeedEvidence() {
        for (String field : List.of("fps=60", "ready_interval_fps=60", "submission_interval_fps=60")) {
            List<String> records = validRecords();
            records.set(3, records.get(3).replace(field, field.substring(0, field.indexOf('='))
                    + "=NaN"));
            assertFalse(field, BenchmarkGoalMatrix.parse(records).accepted());
        }
        List<String> missingLive = validRecords();
        missingLive.set(3, missingLive.get(3).replace(" live_input_mutations=0", ""));
        assertFalse(BenchmarkGoalMatrix.parse(missingLive).accepted());
        List<String> missingSpeed = validRecords();
        missingSpeed.set(3, missingSpeed.get(3).replace(" speed_mode_sample=frame_600", ""));
        assertFalse(BenchmarkGoalMatrix.parse(missingSpeed).accepted());

        List<String> missingGeneration = validRecords();
        missingGeneration.set(3, missingGeneration.get(3).replace(" benchmark_generation=1", ""));
        assertFalse(BenchmarkGoalMatrix.parse(missingGeneration).accepted());

        List<String> zeroGeneration = validRecords();
        zeroGeneration.set(3, zeroGeneration.get(3).replace(
                "benchmark_generation=1", "benchmark_generation=0"));
        assertFalse(BenchmarkGoalMatrix.parse(zeroGeneration).accepted());
    }

    @Test
    public void parserRequiresExactScenarioCompletionAndTerminalHardwareEvidence() {
        for (String mutation : List.of(
                "scenario_session_generation=1", "scenario_completed=true",
                "scenario_completed_frames=313", "scenario_expected_frames=313",
                "scenario_source_closed=true", "scenario_audio_drained=true")) {
            List<String> records = validRecords();
            String replacement = switch (mutation) {
                case "scenario_session_generation=1" -> "scenario_session_generation=2";
                case "scenario_completed=true" -> "scenario_completed=false";
                case "scenario_completed_frames=313" -> "scenario_completed_frames=312";
                case "scenario_expected_frames=313" -> "scenario_expected_frames=312";
                case "scenario_source_closed=true" -> "scenario_source_closed=false";
                default -> "scenario_audio_drained=false";
            };
            records.set(3, records.get(3).replace(mutation, replacement));
            assertFalse(mutation, BenchmarkGoalMatrix.parse(records).accepted());
        }
        List<String> hardware = validRecords();
        hardware.set(3, hardware.get(3).replace("effective_mode=dmg", "effective_mode=sgb"));
        assertFalse(BenchmarkGoalMatrix.parse(hardware).accepted());
        List<String> speed = validRecords();
        speed.set(3, speed.get(3).replace("speed_mode_initial=1", "speed_mode_initial=2"));
        assertFalse(BenchmarkGoalMatrix.parse(speed).accepted());
    }

    @Test
    public void parserBindsSilentCalendarToAudioDeltasAndRejectsMalformedFinal() {
        List<String> inputEvents = validRecords();
        inputEvents.set(3, inputEvents.get(3).replace("audio_pcm_input_events=613",
                "audio_pcm_input_events=614"));
        assertFalse(BenchmarkGoalMatrix.parse(inputEvents).accepted());

        List<String> inputSlots = validRecords();
        inputSlots.set(3, inputSlots.get(3).replace("audio_pcm_input_frames=766180",
                "audio_pcm_input_frames=766181"));
        assertFalse(BenchmarkGoalMatrix.parse(inputSlots).accepted());

        List<String> calendar = validRecords();
        calendar.set(3, calendar.get(3).replace("benchmark_audio_calendar=42134400,766080,603",
                "benchmark_audio_calendar=42134400,766081,603"));
        assertFalse(BenchmarkGoalMatrix.parse(calendar).accepted());

        List<String> dropped = validRecords();
        dropped.set(3, dropped.get(3).replace("dropped_count=0", "dropped_count=1"));
        assertFalse(BenchmarkGoalMatrix.parse(dropped).accepted());
    }

    @Test
    public void parserRequiresBootBeforeMatrixAndCoreBeforeFinal() {
        List<String> reordered = validRecords();
        String boot = reordered.remove(0);
        reordered.add(1, boot); // matrix_run is now first for d-dmg.
        assertFalse(BenchmarkGoalMatrix.parse(reordered).accepted());

        List<String> coreBeforeMatrix = validRecords();
        String core = coreBeforeMatrix.remove(2);
        coreBeforeMatrix.add(1, core);
        assertFalse(BenchmarkGoalMatrix.parse(coreBeforeMatrix).accepted());
    }

    @Test
    public void parserRequiresCanonicalPerformanceIdentityOnEveryRecord() {
        for (int record : List.of(0, 1, 2)) {
            List<String> requested = validRecords();
            requested.set(record, requested.get(record).replace("requested_hardware=dmg",
                    "requested_hardware=forced-dmg"));
            assertFalse(BenchmarkGoalMatrix.parse(requested).accepted());
        }
        for (int record : List.of(0, 1, 2)) {
            List<String> accuracy = validRecords();
            accuracy.set(record, accuracy.get(record).replace("execution_mode=performance",
                    "execution_mode=accuracy"));
            assertFalse(BenchmarkGoalMatrix.parse(accuracy).accepted());
        }
    }

    @Test
    public void parserBindsRequestedHardwareToTheDeclaredCellOnEveryRecord() {
        for (int record : List.of(0, 1, 2)) {
            List<String> drift = validRecords();
            drift.set(record, drift.get(record).replace("requested_hardware=dmg",
                    "requested_hardware=cgb"));
            assertFalse(record + ": " + BenchmarkGoalMatrix.parse(drift).errors(),
                    BenchmarkGoalMatrix.parse(drift).accepted());
        }
        for (int record : List.of(0, 1, 2)) {
            List<String> missing = validRecords();
            missing.set(record, missing.get(record).replace(" requested_hardware=dmg", ""));
            assertFalse(record + ": " + BenchmarkGoalMatrix.parse(missing).errors(),
                    BenchmarkGoalMatrix.parse(missing).accepted());
        }
    }

    @Test
    public void parserRequiresEightDistinctRowsAndRejectsUnknownRecords() {
        List<String> outOfRange = validRecords();
        outOfRange.set(0, outOfRange.get(0).replace("row_order=0", "row_order=8"));
        assertFalse(BenchmarkGoalMatrix.parse(outOfRange).accepted());

        List<String> duplicateRow = validRecords();
        for (int index = 3; index < duplicateRow.size(); index++) {
            if (duplicateRow.get(index).contains("cell_id=u-dmg")) {
                duplicateRow.set(index, duplicateRow.get(index).replace("row_order=3", "row_order=0"));
            }
        }
        assertFalse(BenchmarkGoalMatrix.parse(duplicateRow).accepted());

        List<String> unknownEvent = validRecords();
        unknownEvent.add("event=unrelated rom_path=private.gb");
        assertFalse(BenchmarkGoalMatrix.parse(unknownEvent).accepted());
        List<String> malformed = validRecords();
        malformed.add("not-an-event record");
        assertFalse(BenchmarkGoalMatrix.parse(malformed).accepted());
    }

    @Test
    public void parserUsesEightPerCellPairsAndOneGlobalBlock() {
        List<String> valid = validRecords();
        BenchmarkGoalMatrix.Report validReport = BenchmarkGoalMatrix.parse(valid);
        assertTrue(validReport.errors().toString(), validReport.accepted());

        List<String> reused = validRecords();
        for (int index = 3; index < reused.size(); index++) {
            if (reused.get(index).contains("cell_id=u-dmg")) {
                reused.set(index, reused.get(index).replace("pair-u-dmg", "pair-d-dmg"));
            }
        }
        assertFalse(BenchmarkGoalMatrix.parse(reused).accepted());

        List<String> unknown = validRecords();
        unknown.set(0, unknown.get(0).replace("matrix_block=block-01", "matrix_block=unknown"));
        assertFalse(BenchmarkGoalMatrix.parse(unknown).accepted());
    }

    @Test
    public void catalogNonceCannotBeReplacedByHostNonceAndCatalogHasFourStableValues() {
        assertEquals(4, List.of(NONCE_D, NONCE_U, NONCE_C1, NONCE_C2).stream().distinct().count());
        DiagnosticsOptions options = DiagnosticsOptions.parseGoalValues(true, "dmg",
                BenchmarkWorkload.MATRIX_VERSION, "d-dmg", "d", "host-owned-000000000001",
                "performance", "silent-pcm-v1", 0);
        assertEquals("unknown", options.workloadNonce);
        assertEquals(0, options.rowOrder);
    }

    @Test
    public void parserRejectsExtraRunAndMissingOrReplacedSide() {
        List<String> extra = validRecords();
        extra.add(extra.get(0).replace("pair_id=pair-d-dmg", "pair_id=pair-extra"));
        assertFalse(BenchmarkGoalMatrix.parse(extra).accepted());

        List<String> missingSide = validRecords();
        missingSide.remove(3 * 1 + 2); // d-dmg candidate final_result
        missingSide.remove(3 * 1 + 1); // d-dmg candidate core_result
        missingSide.remove(3 * 1); // d-dmg candidate matrix_run
        assertFalse(BenchmarkGoalMatrix.parse(missingSide).accepted());

        List<String> replacedSide = validRecords();
        // The first side occupies records 0..3; mutate the candidate final record (7), not
        // the already-parent final record, so this exercises a real side substitution.
        replacedSide.set(7, replacedSide.get(7).replace("run_side=candidate", "run_side=parent"));
        assertFalse(BenchmarkGoalMatrix.parse(replacedSide).accepted());
    }

    @Test
    public void parserRejectsUnexpectedPairOrBlock() {
        List<String> pair = validRecords();
        for (int index = 0; index < 3; index++) {
            pair.set(index, pair.get(index).replace("pair_id=pair-d-dmg", "pair_id=pair-extra"));
        }
        assertFalse(BenchmarkGoalMatrix.parse(pair).accepted());

        List<String> block = validRecords();
        for (int index = 0; index < 3; index++) {
            block.set(index, block.get(index).replace("matrix_block=block-01", "matrix_block=block-02"));
        }
        assertFalse(BenchmarkGoalMatrix.parse(block).accepted());
    }

    @Test
    public void parserRejectsForbiddenRomOrFrameIdentityFields() {
        List<String> records = validRecords();
        records.set(0, records.get(0) + " rom_title=private-game");
        assertFalse(BenchmarkGoalMatrix.parse(records).accepted());

        List<String> hash = validRecords();
        hash.set(3, hash.get(3) + " frame_hash=deadbeef");
        assertFalse(BenchmarkGoalMatrix.parse(hash).accepted());
    }

    @Test
    public void parserAcceptsFullTerminalEvidenceFields() {
        List<String> records = validRecords();
        records.set(3, records.get(3) + " wall_ms=10001"
                + " thermal_end=0"
                + " battery_temp_end=20 display_refresh_end_millihz=60000 display_state_end=2"
                + " interactive_end=true plugged_end=1 power_save_end=false stay_awake_end=true"
                + " stay_on_plugged_mask_end=1 thread_priority_end=-4 app_importance_end=100"
                + " system_load_end_milli=10 cpu_count_end=8 memory_available_end_bytes=1024"
                + " environment_sample_count=10 thermal_worst=0 system_load_worst_milli=10"
                + " cpu_freq_min_khz=1000000 display_refresh_min_millihz=60000"
                + " display_bad_count=0 interactive_bad_count=0 plugged_bad_count=0"
                + " power_save_bad_count=0 stay_awake_bad_count=0 priority_bad_count=0"
                + " importance_bad_count=0 battery_temp_min=20 battery_temp_max=20"
                + " surface_vote_hz=60 display_target_hz=60 surface_content_rate_millihz=60000"
                + " controller_cpu_ms=1 controller_util_pct=1.5 gc_count_delta=0"
                + " gc_time_ms_delta=0 alloc_bytes_delta=0");
        BenchmarkGoalMatrix.Report report = BenchmarkGoalMatrix.parse(records);
        assertTrue(report.errors().toString(), report.accepted());
    }

    private static List<String> validRecords() {
        ArrayList<String> records = new ArrayList<>();
        for (BenchmarkWorkload.Cell cell : BenchmarkWorkload.Cell.values()) {
            String nonce = switch (cell.workload()) {
                case D -> NONCE_D;
                case U -> NONCE_U;
                case C1 -> NONCE_C1;
                case C2 -> NONCE_C2;
            };
            String scenarioId = BenchmarkWorkload.timelineForCell(cell).id();
            int scenarioCount = BenchmarkWorkload.timelineForCell(cell).endpointFrame();
            String expected = cell.effectiveProfile().externalValue();
            String pairId = "pair-" + cell.externalValue();
            for (String side : List.of("parent", "candidate")) {
                String prefix = "matrix_version=goal-matrix-v1 cell_id=" + cell.externalValue()
                        + " workload_slot=" + cell.workload().externalValue()
                        + " workload_nonce=" + nonce + " scenario_id=" + scenarioId
                        + " scenario_count=" + scenarioCount + " expected_profile=" + expected
                        + " effective_profile=" + expected + " requested_hardware="
                        + cell.requestedHardware().externalValue()
                        + " execution_mode=performance pair_id=" + pairId
                        + " matrix_block=block-01 row_order=" + cell.ordinal()
                        + " recent_slot=" + cell.workload().recentSlot()
                        + " session_generation=1 run_side=" + side;
                String coreId = "core-" + cell.externalValue().replace('-', '_') + "-" + side;
                String coreProfile = switch (expected) {
                    case "dmg" -> "dmg";
                    case "cgb-native", "cgb-compat" -> "cgb";
                    case "sgb" -> "sgb";
                    default -> "unknown";
                };
                boolean gbc = "cgb-native".equals(expected)
                        || "cgb-compat".equals(expected);
                boolean compat = "cgb-compat".equals(expected);
                String effectiveMode = switch (expected) {
                    case "dmg" -> "dmg";
                    case "cgb-native" -> "cgb-native";
                    case "cgb-compat" -> "cgb-dmg-compat";
                    case "sgb" -> "sgb";
                    default -> "unknown";
                };
                String clock = "sgb".equals(expected)
                        ? "clock_ticks_num=47250000 clock_ticks_den=11 clock_frames_num=140625 "
                        + "clock_frames_den=2299 clock_ticks_frame=70224"
                        : "clock_ticks_num=4194304 clock_ticks_den=1 clock_frames_num=60 "
                        + "clock_frames_den=1 clock_ticks_frame=69905";
                long silentSlots = "sgb".equals(expected) ? 3_830_400L : 766_080L;
                long silentEvents = "sgb".equals(expected) ? 600L : 603L;
                records.add("event=boot_result " + prefix
                        + " requested_bootstrap=fast-forward"
                        + " bootstrap_outcome=authentic_handoff profile=" + coreProfile
                        + " effective_gbc=" + gbc + " effective_dmg_compat=" + compat
                        + " effective_speed_mode=1 accepted=true");
                records.add("event=matrix_run " + prefix);
                records.add("event=core_result " + prefix + " core_result_id=" + coreId
                        + " frame=600 "
                        + counters(silentSlots, expected, side));
                String artifact = "parent".equals(side) ? ARTIFACT_PARENT : ARTIFACT_CANDIDATE;
                records.add("event=final_result " + prefix + " core_result_id=" + coreId
                        + " frame=600 benchmark_generation=1 fps=60"
                        + " ready_interval_fps=60 submission_interval_fps=60"
                        + " build_profile=benchmark artifact_id=" + artifact
                        + " device_id=" + DEVICE_ID
                        + " requested_profile=" + cell.requestedHardware().externalValue()
                        + " profile=" + coreProfile
                        + " effective_gbc=" + gbc
                        + " effective_dmg_compat=" + compat
                        + " effective_mode=" + effectiveMode
                        + " speed_mode_initial=1 speed_mode_final=1"
                        + " " + clock
                        + " scenario_session_generation=1 scenario_completed=true"
                        + " scenario_completed_frames=" + scenarioCount
                        + " scenario_expected_frames=" + scenarioCount
                        + " scenario_source_closed=true scenario_audio_drained=true"
                        + " ready_count=600 submitted_count=600 dropped_count=0"
                        + " duplicate_count=0 late_count=0 corrupt_count=0"
                        + " ready_first_id=1 ready_last_id=600 ready_first_ns=1 ready_last_ns=600"
                        + " submission_first_id=1 submission_last_id=600"
                        + " submission_first_ns=1 submission_last_ns=600"
                        + " audio_active=true audio_sample_rate=48000 audio_overruns=0"
                        + " audio_underruns=0 audio_track_underruns=0 audio_restarts=0 audio_paused=false"
                        + " audio_min_buffer_bytes=1024 audio_configured_buffer_bytes=2048"
                        + " audio_actual_buffer_bytes=2048 audio_pcm_input_events="
                        + (10L + silentEvents)
                        + " audio_pcm_input_frames=" + (100L + silentSlots)
                        + " audio_pcm_enqueued_bytes=40000"
                        + " audio_pcm_enqueued_frames=10000 audio_pcm_written_bytes=40000"
                        + " audio_pcm_written_frames=10000 audio_write_failures=0"
                        + " audio_pcm_discarded_bytes=0 audio_pcm_pending_bytes=0"
                        + " audio_pcm_queued_bytes=0 audio_queue_frames=0 audio_output_open=true"
                        + " audio_output_playing=true audio_muted=false audio_volume=100"
                        + " audio_route_failures=0 audio_playback_position_frames=2000"
                        + " audio_system_volume=0 audio_system_volume_max=25"
                        + " audio_system_music_muted=true audio_queue_capacity_frames=6"
                        + " audio_max_frame_bytes=4096 audio_output_identity=1 audio_queue_identity=2"
                        + " audio_start_ledger=10,100,400,100,400,100,0,0,1,2"
                        + " benchmark_audio_policy=silent-pcm-v1 benchmark_audio_flags=111"
                        + " benchmark_audio_calendar=42134400," + silentSlots + "," + silentEvents
                        + ",1,0,0,1,0 system_audio_sample_count=12 system_audio_bad_count=0"
                        + " audio_focus_granted=true audio_focus_start_loss_count=0"
                        + " audio_focus_loss_count=0 drain_success=true live_input_mutations=0"
                        + " speed_mode_sample=frame_600");
            }
        }
        return records;
    }

    private static String counters(long silentSlots, String expectedProfile, String side) {
        StringBuilder result = new StringBuilder();
        for (String name : List.of(
                "scheduler_master_ticks", "scheduler_scalar_ticks", "scheduler_phase_count",
                "scheduler_phase_ticks", "scheduler_phase_max_ticks", "scheduler_halt_count",
                "scheduler_halt_ticks", "scheduler_halt_max_ticks", "scheduler_epoch_count",
                "scheduler_epoch_ticks", "scheduler_epoch_max_ticks", "scheduler_length_bucket_0",
                "scheduler_length_bucket_1", "scheduler_length_bucket_2", "scheduler_length_bucket_3",
                "scheduler_length_bucket_4", "scheduler_speed1_ticks", "scheduler_speed2_ticks",
                "scheduler_speed_switch_ticks", "scheduler_ppu_direct_ticks",
                "scheduler_ppu_fallback_ticks", "scheduler_ppu_fast_ticks", "scheduler_cpu_safe_accesses",
                "scheduler_cpu_direct_rom_reads", "scheduler_cpu_terminal_reads",
                "scheduler_cpu_terminal_writes", "scheduler_audio_skipped_ticks",
                "scheduler_audio_zero_sample_slots", "scheduler_audio_materializations",
                "scheduler_sgb_frame_array_allocations", "scheduler_sgb_border_rebuilds",
                "scheduler_sgb_center_pixels")) {
            long value = switch (name) {
                case "scheduler_master_ticks" -> 42_134_400L;
                case "scheduler_scalar_ticks" -> 42_134_400L;
                case "scheduler_phase_ticks" -> 0L;
                case "scheduler_halt_ticks" -> 0L;
                case "scheduler_epoch_ticks" -> 0L;
                case "scheduler_speed1_ticks" -> "cgb-native".equals(expectedProfile)
                        ? 10_000_000L : 42_134_400L;
                case "scheduler_speed2_ticks" -> "cgb-native".equals(expectedProfile)
                        ? 32_134_400L : 0L;
                case "scheduler_speed_switch_ticks" -> 0L;
                case "scheduler_audio_skipped_ticks" -> 42_134_400L;
                case "scheduler_audio_zero_sample_slots" -> silentSlots;
                case "scheduler_audio_materializations" -> 1L;
                case "scheduler_sgb_frame_array_allocations" ->
                        "sgb".equals(expectedProfile) && "parent".equals(side) ? 600L : 0L;
                case "scheduler_sgb_border_rebuilds" -> 0L;
                case "scheduler_sgb_center_pixels" -> silentSlots == 3_830_400L
                        ? 13_824_000L : 0L;
                default -> 0L;
            };
            result.append(name).append('=').append(value).append(' ');
        }
        return result.toString().trim();
    }

    private static void replaceSgbCoreField(List<String> records, String side,
            String before, String after) {
        for (int index = 0; index < records.size(); index++) {
            String record = records.get(index);
            if (record.contains("event=core_result")
                    && record.contains("expected_profile=sgb")
                    && record.contains("run_side=" + side)) {
                records.set(index, record.replace(before, after));
                return;
            }
        }
        fail("No SGB " + side + " core_result fixture");
    }
}
