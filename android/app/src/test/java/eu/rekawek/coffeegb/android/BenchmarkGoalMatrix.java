package eu.rekawek.coffeegb.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict host-side parser for the identity-free goal-matrix-v1 evidence stream.
 *
 * <p>This parser is intentionally separate from the legacy seven-row benchmark parser.  It
 * accepts exactly the eight workload/hardware cells in {@link BenchmarkWorkload.Cell}; it does
 * not infer a cell from a cartridge header or from a row-owned input scenario.  Every measured
 * run has one frame-600, identity-bound {@code core_result}, and its final record must link that
 * result by {@code core_result_id}.</p>
 */
public final class BenchmarkGoalMatrix {

    public static final String MATRIX_VERSION = BenchmarkWorkload.MATRIX_VERSION;
    public static final long REQUIRED_TICKS = 42_134_400L;
    private static final int REQUIRED_RUNS = BenchmarkWorkload.Cell.values().length * 2;
    private static final int FINAL_FRAME = 600;
    private static final int MAX_INPUT_LINES = 4096;
    private static final int MAX_LINE_LENGTH = 4096;
    private static final Pattern SAFE_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern NONCE = Pattern.compile("[a-z0-9][a-z0-9._-]{15,63}");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> EVENTS = Set.of(
            "matrix_run", "boot_result", "core_result", "final_result");
    private static final Set<String> DECIMAL_FIELDS = Set.of(
            "fps", "ready_interval_fps", "submission_interval_fps", "controller_util_pct");
    private static final Set<String> REQUESTED_HARDWARE = Set.of("dmg", "cgb", "sgb");
    private static final Set<String> SPEED_MODE_SAMPLES = Set.of("frame_600");
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "rom", "rom_path", "rom_title", "rom_name", "rom_header", "rom_checksum",
            "header", "checksum", "title", "name", "frame_hash", "pixel_hash", "pixels",
            "save", "save_path");

    private static final List<String> CORE_COUNTERS = List.of(
            "scheduler_master_ticks", "scheduler_scalar_ticks",
            "scheduler_phase_count", "scheduler_phase_ticks", "scheduler_phase_max_ticks",
            "scheduler_halt_count", "scheduler_halt_ticks", "scheduler_halt_max_ticks",
            "scheduler_epoch_count", "scheduler_epoch_ticks", "scheduler_epoch_max_ticks",
            "scheduler_length_bucket_0", "scheduler_length_bucket_1",
            "scheduler_length_bucket_2", "scheduler_length_bucket_3",
            "scheduler_length_bucket_4", "scheduler_speed1_ticks",
            "scheduler_speed2_ticks", "scheduler_speed_switch_ticks",
            "scheduler_ppu_direct_ticks", "scheduler_ppu_fallback_ticks",
            "scheduler_ppu_fast_ticks", "scheduler_cpu_safe_accesses",
            "scheduler_cpu_direct_rom_reads", "scheduler_cpu_terminal_reads",
            "scheduler_cpu_terminal_writes", "scheduler_audio_skipped_ticks",
            "scheduler_audio_zero_sample_slots", "scheduler_audio_materializations",
            "scheduler_sgb_frame_array_allocations", "scheduler_sgb_border_rebuilds",
            "scheduler_sgb_center_pixels");

    private static final Set<String> MATRIX_FIELDS = Set.of(
            "event", "matrix_version", "cell_id", "workload_nonce", "scenario_id",
            "scenario_count", "expected_profile", "effective_profile", "pair_id",
            "matrix_block", "run_side", "workload_slot", "core_result_id", "frame", "fps",
            "row_order", "recent_slot", "session_generation", "requested_hardware",
            "execution_mode");
    private static final Set<String> BOOT_FIELDS = union(MATRIX_FIELDS, Set.of(
            "session_generation", "requested_bootstrap", "bootstrap_outcome", "profile",
            "effective_gbc", "effective_dmg_compat", "effective_speed_mode", "accepted",
            "reason"));
    private static final Set<String> CORE_FIELDS = union(MATRIX_FIELDS, Set.of(
            "core_result_id", "frame", "scheduler_master_ticks", "scheduler_scalar_ticks",
            "scheduler_phase_count", "scheduler_phase_ticks", "scheduler_phase_max_ticks",
            "scheduler_halt_count", "scheduler_halt_ticks", "scheduler_halt_max_ticks",
            "scheduler_epoch_count", "scheduler_epoch_ticks", "scheduler_epoch_max_ticks",
            "scheduler_length_bucket_0", "scheduler_length_bucket_1",
            "scheduler_length_bucket_2", "scheduler_length_bucket_3",
            "scheduler_length_bucket_4", "scheduler_speed1_ticks",
            "scheduler_speed2_ticks", "scheduler_speed_switch_ticks",
            "scheduler_ppu_direct_ticks", "scheduler_ppu_fallback_ticks",
            "scheduler_ppu_fast_ticks", "scheduler_cpu_safe_accesses",
            "scheduler_cpu_direct_rom_reads", "scheduler_cpu_terminal_reads",
            "scheduler_cpu_terminal_writes", "scheduler_audio_skipped_ticks",
            "scheduler_audio_zero_sample_slots", "scheduler_audio_materializations",
            "scheduler_sgb_frame_array_allocations", "scheduler_sgb_border_rebuilds",
            "scheduler_sgb_center_pixels"));
    private static final Set<String> FINAL_FIELDS = union(MATRIX_FIELDS, Set.of(
            "build_profile", "artifact_id", "row_order", "session_generation",
            "benchmark_generation", "benchmark_token", "warmup", "input_contract",
            "scenario_session_generation", "scenario_completed", "scenario_completed_frames",
            "scenario_expected_frames", "scenario_source_closed", "scenario_audio_drained",
            "execution_mode", "ready_count", "submitted_count", "dropped_count",
            "duplicate_count", "late_count", "corrupt_count", "ready_first_id",
            "ready_last_id", "ready_first_ns", "ready_last_ns", "submission_first_id",
            "submission_last_id", "submission_first_ns", "submission_last_ns",
            "ready_interval_fps", "submission_interval_fps", "wall_ms", "requested_profile",
            "profile", "effective_gbc", "effective_dmg_compat", "effective_mode", "device_id",
            "speed_mode_initial", "speed_mode_final", "clock_ticks_num", "clock_ticks_den",
            "clock_frames_num", "clock_frames_den", "clock_ticks_frame", "thermal_end",
            "battery_temp_end", "display_refresh_end_millihz", "display_state_end",
            "interactive_end", "plugged_end", "power_save_end", "stay_awake_end",
            "stay_on_plugged_mask_end", "thread_priority_end", "app_importance_end",
            "system_load_end_milli", "cpu_count_end", "memory_available_end_bytes",
            "environment_sample_count", "thermal_worst", "system_load_worst_milli",
            "cpu_freq_min_khz", "display_refresh_min_millihz", "display_bad_count",
            "interactive_bad_count", "plugged_bad_count", "power_save_bad_count",
            "stay_awake_bad_count", "priority_bad_count", "importance_bad_count",
            "battery_temp_min", "battery_temp_max", "audio_active", "audio_sample_rate",
            "audio_overruns", "audio_underruns", "audio_track_underruns", "audio_restarts",
            "audio_paused", "audio_min_buffer_bytes", "audio_configured_buffer_bytes",
            "audio_actual_buffer_bytes", "audio_pcm_input_events", "audio_pcm_input_frames",
            "audio_pcm_enqueued_bytes", "audio_pcm_enqueued_frames", "audio_pcm_written_bytes",
            "audio_pcm_written_frames", "audio_write_failures", "audio_pcm_discarded_bytes",
            "audio_pcm_pending_bytes", "audio_pcm_queued_bytes", "audio_queue_frames",
            "audio_output_open", "audio_output_playing", "audio_muted", "audio_volume",
            "audio_route_failures", "audio_playback_position_frames", "audio_system_volume",
            "audio_system_volume_max", "audio_system_music_muted", "audio_queue_capacity_frames",
            "audio_max_frame_bytes", "audio_output_identity", "audio_queue_identity",
            "benchmark_audio_policy", "benchmark_audio_requested",
            "benchmark_audio_active_at_boundary", "benchmark_audio_disabled_after",
            "benchmark_audio_flags", "benchmark_audio_calendar", "benchmark_audio_skipped_ticks",
            "benchmark_audio_zero_sample_slots", "benchmark_audio_zero_sample_events",
            "benchmark_audio_max_debt", "benchmark_audio_apu_reads", "benchmark_audio_apu_writes",
            "benchmark_audio_frame_sequencer_commits", "benchmark_audio_dropped_channel_ticks",
            "system_audio_sample_count", "system_audio_bad_count", "surface_vote_hz",
            "display_target_hz", "surface_content_rate_millihz", "drain_success",
            "audio_focus_granted", "audio_focus_start_loss_count", "audio_focus_loss_count",
            "controller_cpu_ms", "controller_util_pct", "gc_count_delta", "gc_time_ms_delta",
            "alloc_bytes_delta", "live_input_mutations", "speed_mode_sample",
            "audio_start_ledger"));

    private BenchmarkGoalMatrix() {
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return Collections.unmodifiableSet(result);
    }

    /** Returns the immutable eight-cell contract in declaration order. */
    public static List<BenchmarkWorkload.Cell> cells() {
        return BenchmarkWorkload.Cell.all();
    }

    public static Report parse(List<String> lines) {
        return parse(lines, null, null);
    }

    /**
     * Parses goal evidence and, when supplied, binds the two run sides to the caller's expected
     * app artifacts.  The no-argument overload remains useful for offline inspection, while a
     * report runner that knows the installed parent/candidate artifacts should use this overload
     * so self-reported artifact fields cannot manufacture a valid comparison.
     */
    public static Report parse(List<String> lines, String parentArtifactId,
            String candidateArtifactId) {
        ArrayList<String> errors = new ArrayList<>();
        LinkedHashMap<String, Run> runs = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, String>> pendingBootResults = new LinkedHashMap<>();
        if (lines == null) {
            errors.add("result log is missing");
            return finish(runs, errors, parentArtifactId, candidateArtifactId);
        }
        if (lines.size() > MAX_INPUT_LINES) {
            errors.add("result log exceeds bounded line count");
        }
        int lineLimit = Math.min(lines.size(), MAX_INPUT_LINES);
        for (int index = 0; index < lineLimit; index++) {
            String line = lines.get(index);
            if (line != null && line.length() > MAX_LINE_LENGTH) {
                errors.add("line " + (index + 1) + " exceeds bounded length");
                continue;
            }
            Map<String, String> fields = parseFields(line, index + 1, errors);
            if (fields == null) {
                continue;
            }
            String event = fields.get("event");
            if ("matrix_run".equals(event)) {
                String key = runKey(fields, index + 1, errors);
                boolean bootPrecedesMatrix = key != null && pendingBootResults.containsKey(key);
                if (!bootPrecedesMatrix) {
                    errors.add("line " + (index + 1)
                            + ": matrix_run must follow boot_result");
                }
                addMatrixRun(fields, index + 1, runs, errors);
                attachPendingBootResult(fields, index + 1, runs, pendingBootResults, errors);
            } else if ("boot_result".equals(event)) {
                addBootResult(fields, index + 1, runs, pendingBootResults, errors);
            } else if ("core_result".equals(event)) {
                addCoreResult(fields, index + 1, runs, errors);
            } else if ("final_result".equals(event)) {
                addFinalResult(fields, index + 1, runs, errors);
            }
        }
        for (Map.Entry<String, Map<String, String>> entry : pendingBootResults.entrySet()) {
            if (!runs.containsKey(entry.getKey())) {
                errors.add("boot_result has no matrix_run");
            }
        }
        return finish(runs, errors, parentArtifactId, candidateArtifactId);
    }

    private static Map<String, String> parseFields(String line, int lineNumber,
            List<String> errors) {
        if (line == null) {
            errors.add("line " + lineNumber + ": missing record");
            return null;
        }
        if (line.isBlank()) {
            return null;
        }
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length == 0 || !tokens[0].startsWith("event=")) {
            errors.add("line " + lineNumber + ": malformed event record");
            return null;
        }
        String event = tokens[0].substring("event=".length()).toLowerCase(Locale.ROOT);
        if (!EVENTS.contains(event)) {
            errors.add("line " + lineNumber + ": unknown goal matrix event");
            rejectUnknownEventTokens(tokens, lineNumber, errors);
            return null;
        }
        Set<String> allowed = "matrix_run".equals(event) ? MATRIX_FIELDS
                : "boot_result".equals(event) ? BOOT_FIELDS
                : "core_result".equals(event) ? CORE_FIELDS : FINAL_FIELDS;
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
            if (isForbiddenKey(key)) {
                errors.add("line " + lineNumber + ": forbidden ROM/save/frame payload field");
            }
            if (!allowed.contains(key)) {
                errors.add("line " + lineNumber + ": unknown field for event");
            }
            validateValue(key, value, lineNumber, errors);
            if (fields.put(key, value) != null) {
                errors.add("line " + lineNumber + ": duplicate field " + key);
            }
        }
        return fields;
    }

    private static void rejectUnknownEventTokens(String[] tokens, int lineNumber,
            List<String> errors) {
        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            int equals = token.indexOf('=');
            if (equals <= 0 || equals == token.length() - 1) {
                errors.add("line " + lineNumber + ": malformed key/value token");
                continue;
            }
            String key = token.substring(0, equals).toLowerCase(Locale.ROOT);
            String value = token.substring(equals + 1);
            if (isForbiddenKey(key) || isPathLikeValue(value)) {
                errors.add("line " + lineNumber + ": forbidden ROM/save/frame payload field");
            }
        }
    }

    private static void validateValue(String key, String value, int lineNumber,
            List<String> errors) {
        if (isPathLikeValue(value)) {
            errors.add("line " + lineNumber + ": path-like value is forbidden");
            return;
        }
        if ("matrix_version".equals(key) && !MATRIX_VERSION.equals(value)) {
            errors.add("line " + lineNumber + ": matrix version is not goal-matrix-v1");
        } else if ("cell_id".equals(key) && BenchmarkWorkload.Cell.fromExternalValue(value) == null) {
            errors.add("line " + lineNumber + ": invalid goal matrix cell");
        } else if ("workload_nonce".equals(key) && !NONCE.matcher(value).matches()) {
            errors.add("line " + lineNumber + ": invalid workload nonce");
        } else if ("scenario_id".equals(key) && !SAFE_TOKEN.matcher(value).matches()) {
            errors.add("line " + lineNumber + ": invalid scenario id");
        } else if ("expected_profile".equals(key) || "effective_profile".equals(key)) {
            if (!Set.of("dmg", "cgb-compat", "cgb-native", "sgb").contains(value)) {
                errors.add("line " + lineNumber + ": invalid profile");
            }
        } else if ("requested_hardware".equals(key)) {
            // The goal wire has one canonical spelling per Cell.  In particular, do not let the
            // legacy forced-dmg alias or an AUTO value masquerade as a requested profile.
            if (!REQUESTED_HARDWARE.contains(value)) {
                errors.add("line " + lineNumber + ": invalid requested hardware");
            }
        } else if ("execution_mode".equals(key)) {
            if (!"performance".equals(value)) {
                errors.add("line " + lineNumber + ": goal matrix requires execution_mode=performance");
            }
        } else if ("requested_bootstrap".equals(key)) {
            if (!Set.of("skip", "fast-forward", "normal").contains(value)) {
                errors.add("line " + lineNumber + ": invalid requested bootstrap mode");
            }
        } else if ("bootstrap_outcome".equals(key)) {
            if (!Set.of("skipped", "authentic_handoff", "timed_out_fallback", "pending")
                    .contains(value)) {
                errors.add("line " + lineNumber + ": invalid bootstrap outcome");
            }
        } else if ("profile".equals(key)) {
            if (!Set.of("dmg", "mgb", "cgb", "cgb0", "sgb", "sgb2").contains(value)) {
                errors.add("line " + lineNumber + ": invalid bootstrap profile");
            }
        } else if ("effective_speed_mode".equals(key)) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed != 1L) {
                    errors.add("line " + lineNumber + ": invalid bootstrap speed mode");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid bootstrap speed mode");
            }
        } else if ("accepted".equals(key) || "effective_gbc".equals(key)
                || "effective_dmg_compat".equals(key)) {
            if (!"true".equals(value) && !"false".equals(value)) {
                errors.add("line " + lineNumber + ": invalid boolean " + key);
            }
        } else if ("row_order".equals(key)) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0L || parsed > 7L) {
                    errors.add("line " + lineNumber + ": goal row_order must be in 0..7");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid row_order");
            }
        } else if ("recent_slot".equals(key)) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0L || parsed >= BenchmarkWorkload.Slot.values().length) {
                    errors.add("line " + lineNumber + ": invalid recent_slot");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid recent_slot");
            }
        } else if ("session_generation".equals(key)
                || "scenario_session_generation".equals(key)) {
            try {
                if (Long.parseLong(value) <= 0L) {
                    errors.add("line " + lineNumber + ": invalid session generation");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid session generation");
            }
        } else if ("live_input_mutations".equals(key)) {
            try {
                if (Long.parseLong(value) < 0L) {
                    errors.add("line " + lineNumber + ": negative live input mutation count");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid live input mutation count");
            }
        } else if ("speed_mode_sample".equals(key)) {
            if (!SPEED_MODE_SAMPLES.contains(value)) {
                errors.add("line " + lineNumber + ": invalid speed mode sample");
            }
        } else if ("run_side".equals(key) && !Set.of("parent", "candidate").contains(value)) {
            errors.add("line " + lineNumber + ": invalid run side");
        } else if ("core_result_id".equals(key) && !SAFE_TOKEN.matcher(value).matches()) {
            errors.add("line " + lineNumber + ": invalid core result id");
        } else if ("pair_id".equals(key) || "matrix_block".equals(key)
                || "workload_slot".equals(key)) {
            if (!SAFE_TOKEN.matcher(value).matches()) {
                errors.add("line " + lineNumber + ": unsafe identity token");
            }
        } else if (DECIMAL_FIELDS.contains(key)) {
            try {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed) || parsed < 0.0d) {
                    errors.add("line " + lineNumber + ": invalid decimal value");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid decimal value");
            }
        } else if ("scenario_count".equals(key) || "frame".equals(key)
                || key.startsWith("scheduler_")) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0L) {
                    errors.add("line " + lineNumber + ": negative counter");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": invalid numeric counter");
            }
        }
    }

    private static boolean isForbiddenKey(String key) {
        return FORBIDDEN_KEYS.contains(key) || key.startsWith("rom_")
                || key.startsWith("save_") || key.endsWith("_hash")
                || key.contains("title") || key.contains("header")
                || key.contains("checksum") || key.contains("hash");
    }

    private static boolean isPathLikeValue(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || lower.contains("://")
                || lower.matches(".*\\.(gb|gbc|sgb|rom|sav|cgbstate|7z|rar)([^a-z0-9].*)?$");
    }

    private static void addMatrixRun(Map<String, String> fields, int lineNumber,
            Map<String, Run> runs, List<String> errors) {
        String key = runKey(fields, lineNumber, errors);
        if (key == null) {
            return;
        }
        if (runs.containsKey(key)) {
            errors.add("line " + lineNumber + ": duplicate matrix_run");
            return;
        }
        String cellValue = required(fields, "cell_id", lineNumber, errors);
        BenchmarkWorkload.Cell cell = BenchmarkWorkload.Cell.fromExternalValue(cellValue);
        String nonce = required(fields, "workload_nonce", lineNumber, errors);
        String scenarioId = required(fields, "scenario_id", lineNumber, errors);
        int scenarioCount = intValue(fields, "scenario_count", lineNumber, errors);
        String expectedProfile = required(fields, "expected_profile", lineNumber, errors);
        String effectiveProfile = required(fields, "effective_profile", lineNumber, errors);
        String version = required(fields, "matrix_version", lineNumber, errors);
        String workloadSlot = required(fields, "workload_slot", lineNumber, errors);
        String requestedHardware = required(fields, "requested_hardware", lineNumber, errors);
        String executionMode = required(fields, "execution_mode", lineNumber, errors);
        int rowOrder = intValue(fields, "row_order", lineNumber, errors);
        int recentSlot = intValue(fields, "recent_slot", lineNumber, errors);
        long sessionGeneration = longValue(fields, "session_generation", lineNumber, errors);
        if (cell == null || nonce == null || scenarioId == null || expectedProfile == null
                || effectiveProfile == null || version == null || workloadSlot == null
                || requestedHardware == null || executionMode == null || recentSlot < 0
                || sessionGeneration <= 0L) {
            return;
        }
        if (!version.equals(MATRIX_VERSION)) {
            errors.add("line " + lineNumber + ": matrix version is not goal-matrix-v1");
        }
        Run run = new Run(key, cell, fields.get("workload_nonce"), scenarioId, scenarioCount,
                expectedProfile, effectiveProfile, fields.get("pair_id"),
                fields.get("matrix_block"), fields.get("run_side"), version, workloadSlot,
                requestedHardware, executionMode, rowOrder, recentSlot, sessionGeneration);
        validateRunContract(run, lineNumber, errors);
        runs.put(key, run);
    }

    private static void addBootResult(Map<String, String> fields, int lineNumber,
            Map<String, Run> runs, Map<String, Map<String, String>> pendingBootResults,
            List<String> errors) {
        String key = runKey(fields, lineNumber, errors);
        if (key == null) {
            return;
        }
        Run run = runs.get(key);
        if (run == null) {
            if (pendingBootResults.putIfAbsent(key, new LinkedHashMap<>(fields)) != null) {
                errors.add("line " + lineNumber + ": duplicate boot_result");
            }
            return;
        }
        errors.add("line " + lineNumber + ": boot_result must precede matrix_run");
        attachBootResult(run, fields, lineNumber, errors);
    }

    private static void attachPendingBootResult(Map<String, String> fields, int lineNumber,
            Map<String, Run> runs, Map<String, Map<String, String>> pendingBootResults,
            List<String> errors) {
        String key = runKey(fields, lineNumber, errors);
        if (key == null) {
            return;
        }
        Map<String, String> pending = pendingBootResults.remove(key);
        if (pending != null) {
            Run run = runs.get(key);
            if (run != null) {
                attachBootResult(run, pending, lineNumber, errors);
            }
        }
    }

    private static void attachBootResult(Run run, Map<String, String> fields, int lineNumber,
            List<String> errors) {
        if (run.boot != null) {
            errors.add("line " + lineNumber + ": duplicate boot_result");
            return;
        }
        if (!sameIdentity(run, fields, lineNumber, errors)) {
            return;
        }
        long sessionGeneration = longValue(fields, "session_generation", lineNumber, errors);
        String requestedBootstrap = required(fields, "requested_bootstrap", lineNumber, errors);
        String outcome = required(fields, "bootstrap_outcome", lineNumber, errors);
        String profile = required(fields, "profile", lineNumber, errors);
        String effectiveGbc = required(fields, "effective_gbc", lineNumber, errors);
        String effectiveDmgCompat = required(fields, "effective_dmg_compat", lineNumber, errors);
        int effectiveSpeed = intValue(fields, "effective_speed_mode", lineNumber, errors);
        String accepted = required(fields, "accepted", lineNumber, errors);
        if (sessionGeneration <= 0L) {
            errors.add("line " + lineNumber + ": boot_result has invalid session_generation");
        }
        if (requestedBootstrap == null || outcome == null || profile == null
                || effectiveGbc == null || effectiveDmgCompat == null || accepted == null) {
            return;
        }
        if (!"true".equals(accepted)) {
            errors.add("line " + lineNumber + ": boot_result must be accepted");
        }
        String expectedOutcome = "skip".equals(requestedBootstrap)
                ? "skipped" : "authentic_handoff";
        if (!outcome.equals(expectedOutcome)) {
            errors.add("line " + lineNumber
                    + ": boot_result outcome is not valid for requested bootstrap");
        }
        String expectedCoreProfile = switch (run.expectedProfile) {
            case "dmg" -> "dmg";
            case "cgb-native", "cgb-compat" -> "cgb";
            case "sgb" -> "sgb";
            default -> "unknown";
        };
        if (!expectedCoreProfile.equals(profile)) {
            errors.add("line " + lineNumber + ": boot_result profile does not match cell");
        }
        boolean expectedGbc = "cgb-native".equals(run.expectedProfile)
                || "cgb-compat".equals(run.expectedProfile);
        boolean expectedCompat = "cgb-compat".equals(run.expectedProfile);
        if (!Boolean.toString(expectedGbc).equals(effectiveGbc)
                || !Boolean.toString(expectedCompat).equals(effectiveDmgCompat)) {
            errors.add("line " + lineNumber + ": boot_result effective flags do not match cell");
        }
        if (effectiveSpeed != 1) {
            errors.add("line " + lineNumber + ": boot_result has invalid speed mode");
        }
        if (fields.containsKey("reason")) {
            errors.add("line " + lineNumber + ": accepted boot_result cannot carry a reason");
        }
        if (sessionGeneration != run.sessionGeneration) {
            errors.add("line " + lineNumber + ": boot_result session_generation does not match matrix_run");
        }
        run.boot = new BootEvidence(lineNumber, sessionGeneration, requestedBootstrap, outcome);
    }

    private static void addCoreResult(Map<String, String> fields, int lineNumber,
            Map<String, Run> runs, List<String> errors) {
        String key = runKey(fields, lineNumber, errors);
        Run run = key == null ? null : runs.get(key);
        if (run == null) {
            errors.add("line " + lineNumber + ": core_result has no matrix_run");
            return;
        }
        if (run.boot == null) {
            errors.add("line " + lineNumber + ": core_result must follow boot_result");
        }
        if (run.core != null) {
            errors.add("line " + lineNumber + ": duplicate core_result");
            return;
        }
        if (!sameIdentity(run, fields, lineNumber, errors)) {
            return;
        }
        String coreResultId = required(fields, "core_result_id", lineNumber, errors);
        if (intValue(fields, "frame", lineNumber, errors) != FINAL_FRAME) {
            errors.add("line " + lineNumber + ": core_result must be bound to frame 600");
        }
        for (String counter : CORE_COUNTERS) {
            if (!fields.containsKey(counter)) {
                errors.add("line " + lineNumber + ": core_result is missing " + counter);
            } else if (longValue(fields, counter, lineNumber, errors) < 0L) {
                errors.add("line " + lineNumber + ": core counter is negative");
            }
        }
        long master = longValue(fields, "scheduler_master_ticks", lineNumber, errors);
        long scalar = longValue(fields, "scheduler_scalar_ticks", lineNumber, errors);
        long phase = longValue(fields, "scheduler_phase_ticks", lineNumber, errors);
        long halt = longValue(fields, "scheduler_halt_ticks", lineNumber, errors);
        long epoch = longValue(fields, "scheduler_epoch_ticks", lineNumber, errors);
        long speed1 = longValue(fields, "scheduler_speed1_ticks", lineNumber, errors);
        long speed2 = longValue(fields, "scheduler_speed2_ticks", lineNumber, errors);
        long speedSwitch = longValue(fields, "scheduler_speed_switch_ticks", lineNumber, errors);
        long phaseCount = longValue(fields, "scheduler_phase_count", lineNumber, errors);
        long haltCount = longValue(fields, "scheduler_halt_count", lineNumber, errors);
        long epochCount = longValue(fields, "scheduler_epoch_count", lineNumber, errors);
        long phaseMax = longValue(fields, "scheduler_phase_max_ticks", lineNumber, errors);
        long haltMax = longValue(fields, "scheduler_halt_max_ticks", lineNumber, errors);
        long epochMax = longValue(fields, "scheduler_epoch_max_ticks", lineNumber, errors);
        long bucket0 = longValue(fields, "scheduler_length_bucket_0", lineNumber, errors);
        long bucket1 = longValue(fields, "scheduler_length_bucket_1", lineNumber, errors);
        long bucket2 = longValue(fields, "scheduler_length_bucket_2", lineNumber, errors);
        long bucket3 = longValue(fields, "scheduler_length_bucket_3", lineNumber, errors);
        long bucket4 = longValue(fields, "scheduler_length_bucket_4", lineNumber, errors);
        long ppuDirect = longValue(fields, "scheduler_ppu_direct_ticks", lineNumber, errors);
        long ppuFallback = longValue(fields, "scheduler_ppu_fallback_ticks", lineNumber, errors);
        long ppuFast = longValue(fields, "scheduler_ppu_fast_ticks", lineNumber, errors);
        long cpuSafeAccesses = longValue(fields, "scheduler_cpu_safe_accesses", lineNumber, errors);
        long cpuDirectRomReads = longValue(fields, "scheduler_cpu_direct_rom_reads",
                lineNumber, errors);
        long cpuTerminalReads = longValue(fields, "scheduler_cpu_terminal_reads",
                lineNumber, errors);
        long cpuTerminalWrites = longValue(fields, "scheduler_cpu_terminal_writes",
                lineNumber, errors);
        long audioSkippedTicks = longValue(fields, "scheduler_audio_skipped_ticks",
                lineNumber, errors);
        long audioZeroSampleSlots = longValue(fields, "scheduler_audio_zero_sample_slots",
                lineNumber, errors);
        long audioMaterializations = longValue(fields, "scheduler_audio_materializations",
                lineNumber, errors);
        long sgbFrameArrayAllocations = longValue(fields,
                "scheduler_sgb_frame_array_allocations", lineNumber, errors);
        long sgbBorderRebuilds = longValue(fields, "scheduler_sgb_border_rebuilds",
                lineNumber, errors);
        long sgbCenterPixels = longValue(fields, "scheduler_sgb_center_pixels", lineNumber, errors);
        if (master != REQUIRED_TICKS || !sumEqualsRequired(scalar, phase, halt, epoch)) {
            errors.add("line " + lineNumber + ": scheduler tick sum must equal 42134400");
        }
        if (!sumEqualsRequired(speed1, speed2, speedSwitch)) {
            errors.add("line " + lineNumber + ": speed tick sum must equal 42134400");
        }
        if (!"cgb-native".equals(run.expectedProfile)
                && (speed1 != REQUIRED_TICKS || speed2 != 0L || speedSwitch != 0L)) {
            errors.add("line " + lineNumber
                    + ": non-native goal profiles cannot carry speed-switch ticks");
        }
        long packetCount = safeSum(phaseCount, haltCount, epochCount);
        long packetTicks = scalar >= 0L && scalar <= REQUIRED_TICKS
                ? REQUIRED_TICKS - scalar : -1L;
        if (packetCount < 0L || packetTicks < 0L || packetCount > packetTicks
                || !sumEqualsExpected(packetCount, bucket0, bucket1, bucket2, bucket3, bucket4)) {
            errors.add("line " + lineNumber
                    + ": scheduler packet count must equal length-bucket count");
        }
        if (!validPacketClass(phaseCount, phase, phaseMax)
                || !validPacketClass(haltCount, halt, haltMax)
                || !validPacketClass(epochCount, epoch, epochMax)) {
            errors.add("line " + lineNumber + ": scheduler packet count/ticks/max are inconsistent");
        }
        long weightedMinimum = weightedPacketTicks(bucket0, bucket1, bucket2, bucket3, bucket4,
                false);
        long weightedMaximum = weightedPacketTicks(bucket0, bucket1, bucket2, bucket3, bucket4,
                true);
        if (weightedMinimum < 0L || weightedMaximum < 0L
                || packetTicks < weightedMinimum || packetTicks > weightedMaximum) {
            errors.add("line " + lineNumber + ": scheduler length buckets do not bound packet ticks");
        }
        if (!sumEqualsExpected(epoch, ppuDirect, ppuFallback, ppuFast)) {
            errors.add("line " + lineNumber + ": PPU epoch partition is inconsistent");
        }
        if (cpuSafeAccesses < 0L || cpuDirectRomReads < 0L || cpuTerminalReads < 0L
                || cpuTerminalWrites < 0L) {
            errors.add("line " + lineNumber + ": CPU access evidence is negative");
        }
        long expectedAudioSlots = "sgb".equals(run.expectedProfile)
                ? 3_830_400L : 766_080L;
        if (audioSkippedTicks != REQUIRED_TICKS || audioZeroSampleSlots != expectedAudioSlots
                || audioMaterializations <= 0L) {
            errors.add("line " + lineNumber + ": audio calendar counters are not exact");
        }
        if ("sgb".equals(run.expectedProfile)) {
            long expectedAllocations = "parent".equals(run.side) ? FINAL_FRAME : 0L;
            if (sgbFrameArrayAllocations != expectedAllocations || sgbBorderRebuilds < 0L
                    || sgbBorderRebuilds > FINAL_FRAME || sgbCenterPixels != 13_824_000L) {
                errors.add("line " + lineNumber + ": SGB telemetry evidence is incomplete");
            }
        } else if (sgbFrameArrayAllocations != 0L || sgbBorderRebuilds != 0L
                || sgbCenterPixels != 0L) {
            errors.add("line " + lineNumber + ": non-SGB run carries SGB telemetry");
        }
        run.core = new CoreEvidence(coreResultId, lineNumber, audioSkippedTicks,
                audioZeroSampleSlots, audioMaterializations);
    }

    private static void addFinalResult(Map<String, String> fields, int lineNumber,
            Map<String, Run> runs, List<String> errors) {
        String key = runKey(fields, lineNumber, errors);
        Run run = key == null ? null : runs.get(key);
        if (run == null) {
            errors.add("line " + lineNumber + ": final_result has no matrix_run");
            return;
        }
        if (run.boot == null || run.core == null) {
            errors.add("line " + lineNumber + ": final_result must follow boot_result and core_result");
        }
        if (run.finalLine > 0) {
            errors.add("line " + lineNumber + ": duplicate final_result");
            return;
        }
        if (!sameIdentity(run, fields, lineNumber, errors)) {
            return;
        }
        if (intValue(fields, "frame", lineNumber, errors) != FINAL_FRAME) {
            errors.add("line " + lineNumber + ": final_result must be bound to frame 600");
        }
        String buildProfile = required(fields, "build_profile", lineNumber, errors);
        String artifactId = required(fields, "artifact_id", lineNumber, errors);
        String deviceId = required(fields, "device_id", lineNumber, errors);
        if (!"benchmark".equals(buildProfile)) {
            errors.add("line " + lineNumber + ": final_result requires build_profile=benchmark");
        }
        if (artifactId == null || !SHA256_HEX.matcher(artifactId).matches()) {
            errors.add("line " + lineNumber + ": final_result artifact_id is not SHA-256 hex");
        }
        if (deviceId == null || !SHA256_HEX.matcher(deviceId).matches()) {
            errors.add("line " + lineNumber + ": final_result device_id is not SHA-256 hex");
        }
        if (artifactId != null && SHA256_HEX.matcher(artifactId).matches()) {
            run.artifactId = artifactId;
        }
        if (deviceId != null && SHA256_HEX.matcher(deviceId).matches()) {
            run.deviceId = deviceId;
        }
        String finalCore = required(fields, "core_result_id", lineNumber, errors);
        String speedModeSample = required(fields, "speed_mode_sample", lineNumber, errors);
        String liveInputMutations = required(fields, "live_input_mutations", lineNumber, errors);
        double fps = requiredFinitePositiveDecimal(fields, "fps", lineNumber, errors);
        double readyIntervalFps = requiredFinitePositiveDecimal(
                fields, "ready_interval_fps", lineNumber, errors);
        double submissionIntervalFps = requiredFinitePositiveDecimal(
                fields, "submission_interval_fps", lineNumber, errors);
        if (run.core == null || finalCore == null || !finalCore.equals(run.core.id)) {
            errors.add("line " + lineNumber + ": final_result must link exactly one core_result");
        }
        if (speedModeSample == null || liveInputMutations == null
                || !Double.isFinite(fps) || !Double.isFinite(readyIntervalFps)
                || !Double.isFinite(submissionIntervalFps)) {
            errors.add("line " + lineNumber + ": final_result is missing finite terminal evidence");
        }
        validateGoalFinalEvidence(run, fields, lineNumber, errors);
        run.finalLine = lineNumber;
    }

    /**
     * A goal-matrix final record is a measured proof, not merely a frame-rate summary.  Keep this
     * gate deliberately local to the goal parser so a malformed final cannot be accepted after a
     * valid boot/matrix/core prefix.  The compact audio-start ledger carries the few baseline
     * values needed to prove queue conservation without making the bounded final record repeat
     * the complete Android sink baseline schema.
     */
    private static void validateGoalFinalEvidence(Run run, Map<String, String> fields,
            int lineNumber, List<String> errors) {
        long benchmarkGeneration = requiredLong(fields, "benchmark_generation",
                lineNumber, errors);
        if (benchmarkGeneration <= 0L) {
            errors.add("line " + lineNumber + ": benchmark generation must be positive");
        }
        long scenarioSession = requiredLong(fields, "scenario_session_generation",
                lineNumber, errors);
        int scenarioCompletedFrames = requiredInt(fields, "scenario_completed_frames",
                lineNumber, errors);
        int scenarioExpectedFrames = requiredInt(fields, "scenario_expected_frames",
                lineNumber, errors);
        boolean scenarioCompleted = requiredBoolean(fields, "scenario_completed",
                lineNumber, errors);
        boolean scenarioSourceClosed = requiredBoolean(fields, "scenario_source_closed",
                lineNumber, errors);
        boolean scenarioAudioDrained = requiredBoolean(fields, "scenario_audio_drained",
                lineNumber, errors);
        if (scenarioSession != run.sessionGeneration || !scenarioCompleted
                || scenarioCompletedFrames != run.scenarioCount
                || scenarioExpectedFrames != run.scenarioCount || !scenarioSourceClosed
                || !scenarioAudioDrained) {
            errors.add("line " + lineNumber + ": scenario completion evidence is not exact");
        }

        String requestedProfile = required(fields, "requested_profile", lineNumber, errors);
        String profile = required(fields, "profile", lineNumber, errors);
        String effectiveMode = required(fields, "effective_mode", lineNumber, errors);
        boolean effectiveGbc = requiredBoolean(fields, "effective_gbc", lineNumber, errors);
        boolean effectiveDmgCompat = requiredBoolean(fields, "effective_dmg_compat",
                lineNumber, errors);
        int speedInitial = requiredInt(fields, "speed_mode_initial", lineNumber, errors);
        int speedFinal = requiredInt(fields, "speed_mode_final", lineNumber, errors);
        String expectedProfile = run.expectedProfile;
        String expectedCoreProfile = switch (expectedProfile) {
            case "dmg" -> "dmg";
            case "cgb-native", "cgb-compat" -> "cgb";
            case "sgb" -> "sgb";
            default -> "unknown";
        };
        String expectedEffectiveMode = switch (expectedProfile) {
            case "dmg" -> "dmg";
            case "cgb-native" -> "cgb-native";
            case "cgb-compat" -> "cgb-dmg-compat";
            case "sgb" -> "sgb";
            default -> "unknown";
        };
        boolean expectedGbc = expectedProfile.startsWith("cgb-");
        boolean expectedCompat = "cgb-compat".equals(expectedProfile);
        boolean terminalSpeedValid = speedFinal == 1 || ("cgb-native".equals(expectedProfile)
                && speedFinal == 2);
        if (!run.requestedHardware.equals(requestedProfile) || !expectedCoreProfile.equals(profile)
                || !expectedEffectiveMode.equals(effectiveMode) || effectiveGbc != expectedGbc
                || effectiveDmgCompat != expectedCompat || speedInitial != 1
                || !terminalSpeedValid) {
            errors.add("line " + lineNumber + ": terminal hardware/speed evidence is invalid");
        }
        long clockTicksNumerator = requiredLong(fields, "clock_ticks_num", lineNumber, errors);
        long clockTicksDenominator = requiredLong(fields, "clock_ticks_den", lineNumber, errors);
        long clockFramesNumerator = requiredLong(fields, "clock_frames_num", lineNumber, errors);
        long clockFramesDenominator = requiredLong(fields, "clock_frames_den", lineNumber, errors);
        long clockTicksFrame = requiredLong(fields, "clock_ticks_frame", lineNumber, errors);
        boolean clockExact;
        if ("sgb".equals(expectedProfile)) {
            clockExact = clockTicksNumerator == 47_250_000L
                    && clockTicksDenominator == 11L
                    && clockFramesNumerator == 140_625L
                    && clockFramesDenominator == 2_299L
                    && clockTicksFrame == 70_224L;
        } else {
            clockExact = clockTicksNumerator == 4_194_304L
                    && clockTicksDenominator == 1L
                    && clockFramesNumerator == 60L
                    && clockFramesDenominator == 1L
                    && clockTicksFrame == 69_905L;
        }
        if (!clockExact) {
            errors.add("line " + lineNumber + ": final clock identity does not match profile");
        }

        int ready = requiredInt(fields, "ready_count", lineNumber, errors);
        int submitted = requiredInt(fields, "submitted_count", lineNumber, errors);
        int dropped = requiredInt(fields, "dropped_count", lineNumber, errors);
        int duplicate = requiredInt(fields, "duplicate_count", lineNumber, errors);
        int late = requiredInt(fields, "late_count", lineNumber, errors);
        int corrupt = requiredInt(fields, "corrupt_count", lineNumber, errors);
        if (ready != FINAL_FRAME || submitted != FINAL_FRAME || dropped != 0 || duplicate != 0
                || late != 0 || corrupt != 0) {
            errors.add("line " + lineNumber + ": final frame/drop evidence is not exact");
        }
        if (requiredLong(fields, "ready_first_id", lineNumber, errors) != 1L
                || requiredLong(fields, "ready_last_id", lineNumber, errors) != FINAL_FRAME
                || requiredLong(fields, "submission_first_id", lineNumber, errors) != 1L
                || requiredLong(fields, "submission_last_id", lineNumber, errors) != FINAL_FRAME) {
            errors.add("line " + lineNumber + ": final frame ids are not exact");
        }
        long readyFirstNs = requiredLong(fields, "ready_first_ns", lineNumber, errors);
        long readyLastNs = requiredLong(fields, "ready_last_ns", lineNumber, errors);
        long submissionFirstNs = requiredLong(fields, "submission_first_ns", lineNumber, errors);
        long submissionLastNs = requiredLong(fields, "submission_last_ns", lineNumber, errors);
        if (readyFirstNs <= 0L || readyLastNs < readyFirstNs || submissionFirstNs <= 0L
                || submissionLastNs < submissionFirstNs) {
            errors.add("line " + lineNumber + ": final frame timestamps are invalid");
        }

        requireBoolean(fields, "audio_active", true, lineNumber, errors);
        requireBoolean(fields, "audio_paused", false, lineNumber, errors);
        requireBoolean(fields, "audio_output_open", true, lineNumber, errors);
        requireBoolean(fields, "audio_output_playing", true, lineNumber, errors);
        requireBoolean(fields, "audio_muted", false, lineNumber, errors);
        requireBoolean(fields, "audio_system_music_muted", true, lineNumber, errors);
        requireBoolean(fields, "drain_success", true, lineNumber, errors);
        requireBoolean(fields, "audio_focus_granted", true, lineNumber, errors);
        int sampleRate = requiredInt(fields, "audio_sample_rate", lineNumber, errors);
        int volume = requiredInt(fields, "audio_volume", lineNumber, errors);
        int systemVolume = requiredInt(fields, "audio_system_volume", lineNumber, errors);
        int systemVolumeMax = requiredInt(fields, "audio_system_volume_max", lineNumber, errors);
        int queueCapacity = requiredInt(fields, "audio_queue_capacity_frames", lineNumber, errors);
        int maxFrameBytes = requiredInt(fields, "audio_max_frame_bytes", lineNumber, errors);
        int queueFrames = requiredInt(fields, "audio_queue_frames", lineNumber, errors);
        if (sampleRate <= 0 || volume != 100 || systemVolume != 0 || systemVolumeMax <= 0
                || queueCapacity != 6 || maxFrameBytes <= 0 || queueFrames < 0
                || queueFrames > queueCapacity) {
            errors.add("line " + lineNumber + ": final audio device state is invalid");
        }
        for (String zero : List.of("audio_overruns", "audio_underruns", "audio_track_underruns",
                "audio_restarts", "audio_write_failures", "audio_pcm_discarded_bytes",
                "audio_route_failures")) {
            if (requiredLong(fields, zero, lineNumber, errors) != 0L) {
                errors.add("line " + lineNumber + ": final audio error counter is non-zero");
            }
        }
        long currentInputEvents = requiredLong(fields, "audio_pcm_input_events", lineNumber, errors);
        long currentInputFrames = requiredLong(fields, "audio_pcm_input_frames", lineNumber, errors);
        long currentEnqueuedBytes = requiredLong(fields, "audio_pcm_enqueued_bytes", lineNumber, errors);
        long currentEnqueuedFrames = requiredLong(fields, "audio_pcm_enqueued_frames", lineNumber, errors);
        long currentWrittenBytes = requiredLong(fields, "audio_pcm_written_bytes", lineNumber, errors);
        long currentWrittenFrames = requiredLong(fields, "audio_pcm_written_frames", lineNumber, errors);
        long pendingBytes = requiredLong(fields, "audio_pcm_pending_bytes", lineNumber, errors);
        long queuedBytes = requiredLong(fields, "audio_pcm_queued_bytes", lineNumber, errors);
        long playbackFrames = requiredLong(fields, "audio_playback_position_frames", lineNumber, errors);
        long outputIdentity = requiredLong(fields, "audio_output_identity", lineNumber, errors);
        long queueIdentity = requiredLong(fields, "audio_queue_identity", lineNumber, errors);
        if (currentInputEvents <= 0L || currentInputFrames <= 0L || currentEnqueuedBytes <= 0L
                || currentEnqueuedFrames <= 0L || currentWrittenBytes <= 0L
                || currentWrittenFrames <= 0L || pendingBytes < 0L || queuedBytes < 0L
                || queuedBytes > pendingBytes || outputIdentity <= 0L || queueIdentity <= 0L
                || playbackFrames <= 0L) {
            errors.add("line " + lineNumber + ": final audio counters are invalid");
        }

        long[] startLedger = requiredLongVector(fields, "audio_start_ledger", 10,
                lineNumber, errors);
        if (startLedger != null) {
            long startInputEvents = startLedger[0];
            long startInputFrames = startLedger[1];
            long startEnqueuedBytes = startLedger[2];
            long startEnqueuedFrames = startLedger[3];
            long startWrittenBytes = startLedger[4];
            long startWrittenFrames = startLedger[5];
            long startPendingBytes = startLedger[6];
            long startQueuedBytes = startLedger[7];
            long startOutputIdentity = startLedger[8];
            long startQueueIdentity = startLedger[9];
            if (startInputEvents <= 0L || startInputFrames <= 0L || startEnqueuedBytes <= 0L
                    || startEnqueuedFrames <= 0L || startWrittenBytes <= 0L
                    || startWrittenFrames <= 0L || startPendingBytes != 0L
                    || startQueuedBytes != 0L || startOutputIdentity <= 0L
                    || startQueueIdentity <= 0L || outputIdentity != startOutputIdentity
                    || queueIdentity != startQueueIdentity || currentInputEvents < startInputEvents
                    || currentInputFrames < startInputFrames || currentEnqueuedBytes < startEnqueuedBytes
                    || currentEnqueuedFrames < startEnqueuedFrames
                    || currentWrittenBytes < startWrittenBytes
                    || currentWrittenFrames < startWrittenFrames || playbackFrames <= 0L) {
                errors.add("line " + lineNumber + ": audio output/start ledger is unstable");
            } else {
                long enqueuedDelta = currentEnqueuedFrames - startEnqueuedFrames;
                long writtenDelta = currentWrittenFrames - startWrittenFrames;
                long enqueuedByteDelta = currentEnqueuedBytes - startEnqueuedBytes;
                long writtenByteDelta = currentWrittenBytes - startWrittenBytes;
                boolean conservation = enqueuedDelta >= 0L && writtenDelta >= 0L
                        && enqueuedDelta <= Long.MAX_VALUE / 4L
                        && writtenDelta <= Long.MAX_VALUE / 4L
                        && enqueuedByteDelta == enqueuedDelta * 4L
                        && writtenByteDelta == writtenDelta * 4L
                        && startPendingBytes <= Long.MAX_VALUE - enqueuedByteDelta
                        && pendingBytes <= Long.MAX_VALUE - writtenByteDelta
                        && enqueuedByteDelta + startPendingBytes
                                == writtenByteDelta + pendingBytes;
                if (!conservation) {
                    errors.add("line " + lineNumber + ": audio queue conservation is invalid");
                }
            }
        }

        if (requiredLong(fields, "system_audio_sample_count", lineNumber, errors) != 12L
                || requiredLong(fields, "system_audio_bad_count", lineNumber, errors) != 0L) {
            errors.add("line " + lineNumber + ": system mute evidence is incomplete");
        }
        String policy = required(fields, "benchmark_audio_policy", lineNumber, errors);
        String flags = required(fields, "benchmark_audio_flags", lineNumber, errors);
        if (!"silent-pcm-v1".equals(policy) || !"111".equals(flags)) {
            errors.add("line " + lineNumber + ": exact silent PCM policy is missing");
        }
        long[] calendar = requiredLongVector(fields, "benchmark_audio_calendar", 8,
                lineNumber, errors);
        if (calendar != null) {
            long expectedSlots = "sgb".equals(run.expectedProfile) ? 3_830_400L : 766_080L;
            long expectedEvents = "sgb".equals(run.expectedProfile) ? 600L : -1L;
            boolean eventsValid = expectedEvents > 0L ? calendar[2] == expectedEvents
                    : calendar[2] == 602L || calendar[2] == 603L;
            if (calendar[0] != REQUIRED_TICKS || calendar[1] != expectedSlots || !eventsValid
                    || calendar[3] <= 0L || calendar[4] < 0L || calendar[5] < 0L
                    || calendar[6] <= 0L || calendar[7] != 0L) {
                errors.add("line " + lineNumber + ": silent PCM calendar is not exact");
            }
            if (startLedger != null
                    && (currentInputEvents < startLedger[0]
                    || currentInputFrames < startLedger[1]
                    || currentInputEvents - startLedger[0] != calendar[2]
                    || currentInputFrames - startLedger[1] != calendar[1])) {
                errors.add("line " + lineNumber
                        + ": silent PCM calendar does not match sink deltas");
            }
            if (run.core != null && (run.core.audioSkippedTicks != calendar[0]
                    || run.core.audioZeroSampleSlots != calendar[1])) {
                errors.add("line " + lineNumber
                        + ": core audio calendar does not match final calendar");
            }
        }
        if (requiredLong(fields, "live_input_mutations", lineNumber, errors) != 0L) {
            errors.add("line " + lineNumber + ": measured input mutated live");
        }
    }

    private static int requiredInt(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        long value = requiredLong(fields, key, lineNumber, errors);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            errors.add("line " + lineNumber + ": integer is out of range for " + key);
            return -1;
        }
        return (int) value;
    }

    private static long requiredLong(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = required(fields, key, lineNumber, errors);
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            errors.add("line " + lineNumber + ": invalid numeric evidence " + key);
            return -1L;
        }
    }

    private static void requireBoolean(Map<String, String> fields, String key, boolean expected,
            int lineNumber, List<String> errors) {
        String value = required(fields, key, lineNumber, errors);
        if (!Boolean.toString(expected).equals(value)) {
            errors.add("line " + lineNumber + ": " + key + " must be " + expected);
        }
    }

    private static boolean requiredBoolean(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = required(fields, key, lineNumber, errors);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        if (value != null) {
            errors.add("line " + lineNumber + ": invalid boolean evidence " + key);
        }
        return false;
    }

    private static long[] requiredLongVector(Map<String, String> fields, String key, int width,
            int lineNumber, List<String> errors) {
        String value = required(fields, key, lineNumber, errors);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",", -1);
        if (parts.length != width) {
            errors.add("line " + lineNumber + ": " + key + " has wrong width");
            return null;
        }
        long[] result = new long[width];
        for (int index = 0; index < width; index++) {
            try {
                result[index] = Long.parseLong(parts[index]);
                if (result[index] < 0L) {
                    errors.add("line " + lineNumber + ": " + key + " contains a negative value");
                }
            } catch (NumberFormatException malformed) {
                errors.add("line " + lineNumber + ": " + key + " contains invalid numeric evidence");
                return null;
            }
        }
        return result;
    }

    private static String runKey(Map<String, String> fields, int lineNumber,
            List<String> errors) {
        String pair = required(fields, "pair_id", lineNumber, errors);
        String block = required(fields, "matrix_block", lineNumber, errors);
        String side = required(fields, "run_side", lineNumber, errors);
        String cell = required(fields, "cell_id", lineNumber, errors);
        int rowOrder = intValue(fields, "row_order", lineNumber, errors);
        if (pair == null || block == null || side == null || cell == null) {
            return null;
        }
        return pair + "|" + block + "|" + side + "|" + cell + "|" + rowOrder;
    }

    private static boolean sameIdentity(Run run, Map<String, String> fields, int lineNumber,
            List<String> errors) {
        boolean same = equals(run.cell.externalValue(), fields.get("cell_id"))
                && equals(run.nonce, fields.get("workload_nonce"))
                && equals(run.scenarioId, fields.get("scenario_id"))
                && equals(Integer.toString(run.scenarioCount), fields.get("scenario_count"))
                && equals(run.expectedProfile, fields.get("expected_profile"))
                && equals(run.effectiveProfile, fields.get("effective_profile"))
                && equals(run.matrixVersion, fields.get("matrix_version"))
                && equals(run.workloadSlot, fields.get("workload_slot"))
                && equals(run.requestedHardware, fields.get("requested_hardware"))
                && equals(run.executionMode, fields.get("execution_mode"))
                && equals(Integer.toString(run.rowOrder), fields.get("row_order"))
                && equals(Integer.toString(run.recentSlot), fields.get("recent_slot"))
                && equals(Long.toString(run.sessionGeneration), fields.get("session_generation"))
                && equals(run.pairId, fields.get("pair_id"))
                && equals(run.matrixBlock, fields.get("matrix_block"))
                && equals(run.side, fields.get("run_side"));
        if (!same) {
            errors.add("line " + lineNumber + ": evidence identity does not match matrix_run");
        }
        return same;
    }

    private static boolean equals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static void validateRunContract(Run run, int lineNumber, List<String> errors) {
        BenchmarkWorkload.Timeline timeline = BenchmarkWorkload.timelineForCell(run.cell);
        if (timeline == null) {
            errors.add("line " + lineNumber + ": workload contract is missing");
            return;
        }
        if (!timeline.complete()) {
            errors.add("line " + lineNumber + ": workload contract " + timeline.id()
                    + " has no captured timeline");
        } else if (!timeline.id().equals(run.scenarioId)
                || timeline.endpointFrame() != run.scenarioCount) {
            errors.add("line " + lineNumber + ": scenario id/count does not match workload contract");
        }
        if (!run.cell.effectiveProfile().externalValue().equals(run.expectedProfile)) {
            errors.add("line " + lineNumber + ": expected profile does not match cell");
        }
        if (!run.cell.effectiveProfile().externalValue().equals(run.effectiveProfile)) {
            errors.add("line " + lineNumber + ": effective profile does not match cell");
        }
        if (!run.cell.requestedHardware().externalValue().equals(run.requestedHardware)) {
            errors.add("line " + lineNumber + ": requested hardware does not match cell");
        }
        if (!"performance".equals(run.executionMode)) {
            errors.add("line " + lineNumber + ": goal matrix requires execution_mode=performance");
        }
        if (run.rowOrder < 0 || run.rowOrder > 7) {
            errors.add("line " + lineNumber + ": goal row_order must be in 0..7");
        }
        if (!run.cell.workload().externalValue().equals(run.workloadSlot)) {
            errors.add("line " + lineNumber + ": workload slot does not match cell");
        }
        if (run.recentSlot != run.cell.workload().recentSlot()) {
            errors.add("line " + lineNumber + ": recent_slot does not match workload slot");
        }
        if (!NONCE.matcher(run.nonce).matches()) {
            errors.add("line " + lineNumber + ": missing or invalid workload nonce");
        }
    }

    private static double requiredFinitePositiveDecimal(Map<String, String> fields, String key,
            int lineNumber, List<String> errors) {
        String value = required(fields, key, lineNumber, errors);
        if (value == null) {
            return Double.NaN;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed <= 0.0d) {
                errors.add("line " + lineNumber + ": " + key + " must be finite and positive");
                return Double.NaN;
            }
            return parsed;
        } catch (NumberFormatException malformed) {
            errors.add("line " + lineNumber + ": " + key + " must be finite and positive");
            return Double.NaN;
        }
    }

    /** Compares non-negative counters without allowing a crafted long overflow to wrap a sum. */
    private static boolean sumEqualsRequired(long... values) {
        long remaining = REQUIRED_TICKS;
        for (long value : values) {
            if (value < 0L || value > remaining) {
                return false;
            }
            remaining -= value;
        }
        return remaining == 0L;
    }

    private static boolean sumEqualsExpected(long expected, long... values) {
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

    /** Returns a non-negative sum, or -1 when adding the supplied counters would overflow. */
    private static long safeSum(long... values) {
        long result = 0L;
        for (long value : values) {
            if (value < 0L || result > Long.MAX_VALUE - value) {
                return -1L;
            }
            result += value;
        }
        return result;
    }

    /** A packet category is either absent or has one-to-one count/ticks/max evidence. */
    private static boolean validPacketClass(long count, long ticks, long max) {
        if (count < 0L || ticks < 0L || max < 0L || max > 54L) {
            return false;
        }
        if (count == 0L) {
            return ticks == 0L && max == 0L;
        }
        return ticks >= count && max >= 1L && max <= ticks;
    }

    /** Computes conservative packet-tick bounds from the five inclusive length buckets. */
    private static long weightedPacketTicks(long bucket0, long bucket1, long bucket2,
            long bucket3, long bucket4, boolean maximum) {
        long[] weights = maximum ? new long[]{1L, 3L, 7L, 15L, 54L}
                : new long[]{1L, 2L, 4L, 8L, 16L};
        long result = 0L;
        long[] buckets = {bucket0, bucket1, bucket2, bucket3, bucket4};
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

    private static Report finish(Map<String, Run> runs, List<String> errors,
            String expectedParentArtifactId, String expectedCandidateArtifactId) {
        Map<String, Set<String>> nonceByWorkload = new HashMap<>();
        Map<String, String> coreIdOwners = new HashMap<>();
        Map<String, Set<String>> sidesByCell = new LinkedHashMap<>();
        String expectedBlock = null;
        Map<String, Set<String>> pairsByCell = new LinkedHashMap<>();
        Map<String, String> cellByPair = new LinkedHashMap<>();
        Map<String, Integer> rowByCell = new LinkedHashMap<>();
        Map<Integer, String> cellByRow = new LinkedHashMap<>();
        Map<String, String> artifactBySide = new LinkedHashMap<>();
        Set<String> artifactIds = new LinkedHashSet<>();
        Set<String> deviceIds = new LinkedHashSet<>();
        String campaignBootstrap = null;
        String campaignOutcome = null;
        for (Run run : runs.values()) {
            if (expectedBlock == null) {
                expectedBlock = run.matrixBlock;
            } else if (!expectedBlock.equals(run.matrixBlock)) {
                errors.add(run.key + " uses an unexpected matrix_block");
            }
            if (!isNonUnknownToken(run.matrixBlock)) {
                errors.add(run.key + " must use one non-unknown matrix_block");
            }
            if (!isNonUnknownToken(run.pairId)) {
                errors.add(run.key + " must use a non-unknown pair_id");
            }
            sidesByCell.computeIfAbsent(run.cell.externalValue(), ignored -> new LinkedHashSet<>())
                    .add(run.side);
            pairsByCell.computeIfAbsent(run.cell.externalValue(), ignored -> new LinkedHashSet<>())
                    .add(run.pairId);
            String pairCell = cellByPair.putIfAbsent(run.pairId, run.cell.externalValue());
            if (pairCell != null && !pairCell.equals(run.cell.externalValue())) {
                errors.add(run.key + " reuses a pair_id across cells");
            }
            Integer previousRow = rowByCell.putIfAbsent(run.cell.externalValue(), run.rowOrder);
            if (previousRow != null && previousRow != run.rowOrder) {
                errors.add(run.key + " changes row_order for a cell");
            }
            if (run.rowOrder >= 0 && run.rowOrder <= 7) {
                String previousCell = cellByRow.putIfAbsent(run.rowOrder, run.cell.externalValue());
                if (previousCell != null && !previousCell.equals(run.cell.externalValue())) {
                    errors.add(run.key + " reuses row_order for different cells");
                }
            }
            String workload = run.cell.workload().externalValue();
            nonceByWorkload.computeIfAbsent(workload, ignored -> new LinkedHashSet<>())
                    .add(run.nonce);
            if (run.core == null) {
                errors.add(run.key + " is missing core_result");
            } else if (run.core.id == null || run.core.id.isBlank()) {
                errors.add(run.key + " has an empty core_result_id");
            } else if (coreIdOwners.put(run.core.id, run.key) != null) {
                errors.add(run.key + " reuses a core_result_id");
            }
            if (run.finalLine <= 0) {
                errors.add(run.key + " is missing final_result");
            }
            if (run.artifactId == null || run.deviceId == null) {
                errors.add(run.key + " is missing final artifact/device identity");
            } else {
                artifactIds.add(run.artifactId);
                deviceIds.add(run.deviceId);
                String previousArtifact = artifactBySide.putIfAbsent(run.side, run.artifactId);
                if (previousArtifact != null && !previousArtifact.equals(run.artifactId)) {
                    errors.add(run.key + " changes artifact_id for its run side");
                }
            }
            if (run.boot == null) {
                errors.add(run.key + " is missing boot_result");
            } else {
                if (campaignBootstrap == null) {
                    campaignBootstrap = run.boot.requestedBootstrap;
                    campaignOutcome = run.boot.outcome;
                } else if (!campaignBootstrap.equals(run.boot.requestedBootstrap)
                        || !campaignOutcome.equals(run.boot.outcome)) {
                    errors.add(run.key + " changes requested bootstrap/outcome within campaign");
                }
                if (run.boot.sessionGeneration != run.sessionGeneration) {
                    errors.add(run.key + " boot_result session does not match matrix session");
                }
            }
        }
        if (runs.size() != REQUIRED_RUNS) {
            errors.add("goal matrix requires exactly " + REQUIRED_RUNS + " runs");
        }
        for (BenchmarkWorkload.Cell cell : BenchmarkWorkload.Cell.values()) {
            Set<String> sides = sidesByCell.get(cell.externalValue());
            if (sides == null) {
                errors.add(cell.externalValue() + " cell is missing");
            } else if (!sides.equals(Set.of("parent", "candidate"))) {
                errors.add(cell.externalValue() + " must have exactly one parent and candidate run");
            }
            Set<String> pairs = pairsByCell.get(cell.externalValue());
            if (pairs == null || pairs.size() != 1 || !pairs.stream().allMatch(
                    BenchmarkGoalMatrix::isNonUnknownToken)) {
                errors.add(cell.externalValue() + " must have one non-unknown pair_id");
            }
            Integer row = rowByCell.get(cell.externalValue());
            if (row == null || row < 0 || row > 7) {
                errors.add(cell.externalValue() + " must have a goal row_order in 0..7");
            }
        }
        if (expectedBlock == null || !isNonUnknownToken(expectedBlock)) {
            errors.add("goal matrix requires one non-unknown matrix_block");
        }
        if (cellByPair.size() != BenchmarkWorkload.Cell.values().length) {
            errors.add("goal matrix requires eight distinct per-cell pair_ids");
        }
        if (cellByRow.size() != BenchmarkWorkload.Cell.values().length
                || !cellByRow.keySet().containsAll(Set.of(0, 1, 2, 3, 4, 5, 6, 7))) {
            errors.add("goal matrix requires a row_order permutation of 0..7");
        }
        for (Map.Entry<String, Set<String>> entry : nonceByWorkload.entrySet()) {
            if (entry.getValue().size() != 1) {
                errors.add(entry.getKey() + " workload nonce changed across cells");
            }
        }
        Set<String> catalogNonces = new LinkedHashSet<>();
        for (BenchmarkWorkload.Slot slot : BenchmarkWorkload.Slot.values()) {
            Set<String> nonces = nonceByWorkload.get(slot.externalValue());
            if (nonces == null || nonces.size() != 1) {
                errors.add(slot.externalValue() + " workload nonce is not stable across cells");
            }
            if (nonces != null) {
                catalogNonces.addAll(nonces);
            }
        }
        if (catalogNonces.size() != BenchmarkWorkload.Slot.values().length) {
            errors.add("workload catalog nonces must be four distinct values");
        }
        if (artifactIds.size() != 2 || artifactBySide.size() != 2) {
            errors.add("goal matrix requires one stable artifact_id per side");
        }
        if (artifactBySide.size() == 2 && artifactBySide.get("parent") != null
                && artifactBySide.get("candidate") != null
                && artifactBySide.get("parent").equals(artifactBySide.get("candidate"))) {
            errors.add("parent and candidate artifact_id must differ");
        }
        if (deviceIds.size() != 1) {
            errors.add("goal matrix requires one stable device_id");
        }
        validateExpectedArtifact("parent", expectedParentArtifactId, artifactBySide, errors);
        validateExpectedArtifact("candidate", expectedCandidateArtifactId, artifactBySide, errors);
        boolean complete = errors.isEmpty();
        return new Report(complete, Collections.unmodifiableList(new ArrayList<>(errors)), runs);
    }

    private static void validateExpectedArtifact(String side, String expected,
            Map<String, String> actualBySide, List<String> errors) {
        if (expected == null) {
            return;
        }
        if (!SHA256_HEX.matcher(expected).matches()) {
            errors.add("expected " + side + " artifact_id is not SHA-256 hex");
            return;
        }
        if (!expected.equals(actualBySide.get(side))) {
            errors.add(side + " artifact_id does not match the selected installed artifact");
        }
    }

    private static boolean isNonUnknownToken(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches()
                && !"unknown".equals(value) && !"invalid".equals(value);
    }

    private static String required(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            errors.add("line " + lineNumber + ": missing " + key);
        }
        return value;
    }

    private static int intValue(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        long value = longValue(fields, key, lineNumber, errors);
        if (value > Integer.MAX_VALUE) {
            errors.add("line " + lineNumber + ": integer is too large for " + key);
            return -1;
        }
        return (int) value;
    }

    private static long longValue(Map<String, String> fields, String key, int lineNumber,
            List<String> errors) {
        String value = required(fields, key, lineNumber, errors);
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return -1L;
        }
    }

    public static final class Report {
        private final boolean accepted;
        private final List<String> errors;
        private final Map<String, Run> runs;

        private Report(boolean accepted, List<String> errors, Map<String, Run> runs) {
            this.accepted = accepted;
            this.errors = errors;
            this.runs = Collections.unmodifiableMap(new LinkedHashMap<>(runs));
        }

        public boolean accepted() {
            return accepted;
        }

        public List<String> errors() {
            return errors;
        }

        public int runCount() {
            return runs.size();
        }

        /** Returns the stable self-reported artifact for one side, or null when inconsistent. */
        public String artifactId(String side) {
            if (!"parent".equals(side) && !"candidate".equals(side)) {
                return null;
            }
            String artifact = null;
            for (Run run : runs.values()) {
                if (!side.equals(run.side)) {
                    continue;
                }
                if (run.artifactId == null) {
                    return null;
                }
                if (artifact == null) {
                    artifact = run.artifactId;
                } else if (!artifact.equals(run.artifactId)) {
                    return null;
                }
            }
            return artifact;
        }
    }

    private static final class Run {
        final String key;
        final BenchmarkWorkload.Cell cell;
        final String nonce;
        final String scenarioId;
        final int scenarioCount;
        final String expectedProfile;
        final String effectiveProfile;
        final String pairId;
        final String matrixBlock;
        final String side;
        final String matrixVersion;
        final String workloadSlot;
        final String requestedHardware;
        final String executionMode;
        final int rowOrder;
        final int recentSlot;
        final long sessionGeneration;
        String artifactId;
        String deviceId;
        CoreEvidence core;
        BootEvidence boot;
        int finalLine;

        Run(String key, BenchmarkWorkload.Cell cell, String nonce, String scenarioId,
                int scenarioCount, String expectedProfile, String effectiveProfile,
                String pairId, String matrixBlock, String side, String matrixVersion,
                String workloadSlot, String requestedHardware, String executionMode,
                int rowOrder, int recentSlot, long sessionGeneration) {
            this.key = key;
            this.cell = cell;
            this.nonce = nonce;
            this.scenarioId = scenarioId;
            this.scenarioCount = scenarioCount;
            this.expectedProfile = expectedProfile;
            this.effectiveProfile = effectiveProfile;
            this.pairId = pairId;
            this.matrixBlock = matrixBlock;
            this.side = side;
            this.matrixVersion = matrixVersion;
            this.workloadSlot = workloadSlot;
            this.requestedHardware = requestedHardware;
            this.executionMode = executionMode;
            this.rowOrder = rowOrder;
            this.recentSlot = recentSlot;
            this.sessionGeneration = sessionGeneration;
        }
    }

    private static final class CoreEvidence {
        final String id;
        final int line;
        final long audioSkippedTicks;
        final long audioZeroSampleSlots;
        final long audioMaterializations;

        CoreEvidence(String id, int line, long audioSkippedTicks, long audioZeroSampleSlots,
                long audioMaterializations) {
            this.id = id;
            this.line = line;
            this.audioSkippedTicks = audioSkippedTicks;
            this.audioZeroSampleSlots = audioZeroSampleSlots;
            this.audioMaterializations = audioMaterializations;
        }
    }

    private static final class BootEvidence {
        final int line;
        final long sessionGeneration;
        final String requestedBootstrap;
        final String outcome;

        BootEvidence(int line, long sessionGeneration, String requestedBootstrap,
                String outcome) {
            this.line = line;
            this.sessionGeneration = sessionGeneration;
            this.requestedBootstrap = requestedBootstrap;
            this.outcome = outcome;
        }
    }
}
