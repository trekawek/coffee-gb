package eu.rekawek.coffeegb.android;

import android.content.Intent;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.sound.Sound;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Process-local launch options for the minified benchmark APK.
 *
 * <p>The parser deliberately accepts only a small, typed allow-list.  The release APK compiles
 * this entire path with {@link BuildConfig#DIAGNOSTICS_ENABLED} set to {@code false}, so ordinary
 * launches cannot enable the benchmark behavior.  Benchmark options remain process-local and
 * are never copied into user preferences; the ordinary UI applies its persisted execution mode
 * through the controller settings model.</p>
 */
final class DiagnosticsOptions {

    static final String EXTRA_BENCHMARK = "coffee_gb_benchmark";
    static final String EXTRA_HARDWARE = "coffee_gb_hardware";
    static final String EXTRA_AUDIO = "coffee_gb_audio";
    static final String EXTRA_RENDER = "coffee_gb_render";
    static final String EXTRA_WARMUP = "coffee_gb_warmup";
    static final String EXTRA_RECENT = "coffee_gb_recent";
    static final String EXTRA_FRAME_SINK = "coffee_gb_frame_sink";
    /** Host-assigned build identity; accepted only as a bounded parser-safe token. */
    static final String EXTRA_BUILD_ID = "coffee_gb_build_id";
    static final String EXTRA_PAIR_ID = "coffee_gb_pair_id";
    static final String EXTRA_MATRIX_BLOCK = "coffee_gb_matrix_block";
    static final String EXTRA_ROW_ORDER = "coffee_gb_row_order";
    static final String EXTRA_RUN_SIDE = "coffee_gb_run_side";
    static final String EXTRA_FIRST_SIDE = "coffee_gb_first_side";
    static final String EXTRA_DEVICE_BUILD = "coffee_gb_device_build";
    static final String EXTRA_THERMAL_WINDOW = "coffee_gb_thermal_window";
    static final String EXTRA_THERMAL_VALID = "coffee_gb_thermal_valid";
    /** Opaque scheduler nonce; never derived from a ROM, save, URI, or title. */
    static final String EXTRA_WORKLOAD_NONCE = "coffee_gb_workload_nonce";
    /** Requested Surface frame-rate vote; only the benchmark variant consumes this value. */
    static final String EXTRA_SURFACE_RATE_HZ = "coffee_gb_surface_rate_hz";
    /** Opaque app-owned recent-catalog slot; no URI/title is accepted on the benchmark wire. */
    static final String EXTRA_RECENT_SLOT = "coffee_gb_recent_slot";
    /** One-shot host arm token delivered through a singleTop Activity intent. */
    static final String EXTRA_BENCHMARK_ARM_TOKEN = "coffee_gb_benchmark_arm_token";
    /** Session execution strategy; Accuracy is the compatibility-safe default. */
    static final String EXTRA_EXECUTION_MODE = "coffee_gb_execution_mode";
    /** Benchmark-only BIOS bootstrap strategy; skip is the compatibility-safe default. */
    static final String EXTRA_BOOTSTRAP = "coffee_gb_bootstrap";
    /** Benchmark-only deterministic gameplay preconditioning contract. */
    static final String EXTRA_BENCHMARK_SCENARIO = "coffee_gb_benchmark_scenario";
    /** Host-audio evidence policy; canonical is the compatibility-safe default. */
    static final String EXTRA_AUDIO_POLICY = "coffee_gb_audio_policy";

    private static final Pattern SAFE_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final String UNKNOWN_TOKEN = "unknown";

    enum Hardware {
        AUTO("auto", null),
        DMG("dmg", HardwareProfileRegistry.DMG),
        MGB("mgb", HardwareProfileRegistry.MGB),
        CGB("cgb", HardwareProfileRegistry.CGB),
        CGB0("cgb0", HardwareProfileRegistry.CGB0),
        SGB("sgb", HardwareProfileRegistry.SGB),
        SGB2("sgb2", HardwareProfileRegistry.SGB2);

        private final String externalValue;
        private final HardwareProfile profileOverride;

        Hardware(String externalValue, HardwareProfile profileOverride) {
            this.externalValue = externalValue;
            this.profileOverride = profileOverride;
        }

        String externalValue() {
            return externalValue;
        }

        HardwareProfile profileOverride() {
            return profileOverride;
        }

        static Hardware fromExternalValue(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("forced-dmg".equals(normalized)) {
                return DMG;
            }
            for (Hardware hardware : values()) {
                if (hardware.externalValue.equals(normalized)) {
                    return hardware;
                }
            }
            return AUTO;
        }
    }

    /** Redacted effective hardware labels used only in benchmark diagnostics. */
    enum EffectiveMode {
        UNKNOWN("unknown"),
        DMG("dmg"),
        MGB("mgb"),
        CGB_NATIVE("cgb-native"),
        CGB0_NATIVE("cgb0-native"),
        CGB_DMG_COMPAT("cgb-dmg-compat"),
        CGB0_DMG_COMPAT("cgb0-dmg-compat"),
        SGB("sgb"),
        SGB2("sgb2");

        private final String externalValue;

        EffectiveMode(String externalValue) {
            this.externalValue = externalValue;
        }

        String externalValue() {
            return externalValue;
        }

        static EffectiveMode classify(HardwareProfile profile, Boolean effectiveGbc,
                Boolean effectiveDmgCompat) {
            if (profile == null || effectiveGbc == null || effectiveDmgCompat == null
                    || effectiveGbc != profile.capabilities().cgbMode()) {
                return UNKNOWN;
            }
            switch (profile.id()) {
                case "dmg":
                    return !effectiveGbc && !effectiveDmgCompat ? DMG : UNKNOWN;
                case "mgb":
                    return !effectiveGbc && !effectiveDmgCompat ? MGB : UNKNOWN;
                case "cgb":
                    return effectiveDmgCompat ? CGB_DMG_COMPAT : CGB_NATIVE;
                case "cgb0":
                    return effectiveDmgCompat ? CGB0_DMG_COMPAT : CGB0_NATIVE;
                case "sgb":
                    return !effectiveGbc && !effectiveDmgCompat ? SGB : UNKNOWN;
                case "sgb2":
                    return !effectiveGbc && !effectiveDmgCompat ? SGB2 : UNKNOWN;
                default:
                    return UNKNOWN;
            }
        }
    }

    enum Render {
        PRESENTATION,
        FRAME_SINK
    }

    enum BenchmarkScenario {
        NONE("none"),
        DMG_ACTION_V1("dmg-action-v1"),
        CGB_ACTION_V1("cgb-action-v1");

        private final String externalValue;

        BenchmarkScenario(String externalValue) {
            this.externalValue = externalValue;
        }

        String externalValue() {
            return externalValue;
        }

        static BenchmarkScenario fromExternalValue(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (BenchmarkScenario scenario : values()) {
                if (scenario.externalValue.equals(normalized)) {
                    return scenario;
                }
            }
            return NONE;
        }
    }

    enum AudioPolicy {
        CANONICAL("canonical"),
        SILENT_PCM_V1("silent-pcm-v1"),
        SILENT_PCM_RELAXED_APU_V1("silent-pcm-relaxed-apu-v1");

        private final String externalValue;

        AudioPolicy(String externalValue) {
            this.externalValue = externalValue;
        }

        String externalValue() {
            return externalValue;
        }

        boolean isSilent() {
            return this != CANONICAL;
        }

        boolean isRelaxedApu() {
            return this == SILENT_PCM_RELAXED_APU_V1;
        }

        /** Maps the diagnostics-only wire token to the transient core calendar mode. */
        Sound.PerformanceSystemMutedAudioMode performanceSystemMutedAudioMode() {
            switch (this) {
                case SILENT_PCM_V1:
                    return Sound.PerformanceSystemMutedAudioMode.EXACT;
                case SILENT_PCM_RELAXED_APU_V1:
                    return Sound.PerformanceSystemMutedAudioMode.RELAXED_APU;
                default:
                    return Sound.PerformanceSystemMutedAudioMode.OFF;
            }
        }

        static AudioPolicy fromExternalValue(String value) {
            if (value == null || value.isBlank()) {
                return CANONICAL;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (AudioPolicy policy : values()) {
                if (policy.externalValue.equals(normalized)) {
                    return policy;
                }
            }
            return CANONICAL;
        }
    }

    BenchmarkGameplayScenario.NativeFrameKind benchmarkNativeFrameKind() {
        return hardware == Hardware.CGB || hardware == Hardware.CGB0
                ? BenchmarkGameplayScenario.NativeFrameKind.GBC
                : BenchmarkGameplayScenario.NativeFrameKind.DMG;
    }

    enum RunSide {
        UNKNOWN("unknown"),
        PARENT("parent"),
        CANDIDATE("candidate");

        private final String externalValue;

        RunSide(String externalValue) {
            this.externalValue = externalValue;
        }

        String externalValue() {
            return externalValue;
        }

        static RunSide fromExternalValue(String value) {
            if (value == null || value.isBlank()) {
                return UNKNOWN;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (RunSide side : values()) {
                if (side.externalValue.equals(normalized)) {
                    return side;
                }
            }
            return UNKNOWN;
        }
    }

    private static final DiagnosticsOptions DISABLED = new DiagnosticsOptions(
            false, Hardware.AUTO, true, Render.PRESENTATION, false, false,
            "disabled", UNKNOWN_TOKEN, UNKNOWN_TOKEN, -1, RunSide.UNKNOWN, RunSide.UNKNOWN,
            UNKNOWN_TOKEN, UNKNOWN_TOKEN, false, UNKNOWN_TOKEN, -1, -1,
            ExecutionMode.ACCURACY, BootstrapMode.SKIP, BenchmarkScenario.NONE,
            AudioPolicy.CANONICAL);

    final boolean enabled;
    final Hardware hardware;
    final boolean audioOutput;
    final Render render;
    final boolean runtimeWarmup;
    final boolean launchRecent;
    /** Build/matrix fields are bounded metadata only; they never identify a ROM or save. */
    final String buildId;
    final String pairId;
    final String matrixBlock;
    final int rowOrder;
    final RunSide runSide;
    final RunSide firstSide;
    final String deviceBuild;
    final String thermalWindow;
    final boolean thermalValid;
    final String workloadNonce;
    /** Host display-mode target; this is not the emulated producer/content cadence. */
    final int displayTargetHz;
    /** Exact profile producer cadence advertised to Surface.setFrameRate, in millihertz. */
    final int surfaceContentRateMillihz;
    final int recentSlot;
    /** Core-owned session strategy. This is not persisted in a save state. */
    final ExecutionMode executionMode;
    /** Benchmark-only BIOS bootstrap strategy; ordinary launches do not use this field. */
    final BootstrapMode bootstrapMode;
    /** Deterministic benchmark-only input contract; never persisted or exposed to release UI. */
    final BenchmarkScenario benchmarkScenario;
    /** Explicit host-audio evidence policy; never persisted or exposed to release UI. */
    final AudioPolicy audioPolicy;

    private DiagnosticsOptions(boolean enabled, Hardware hardware, boolean audioOutput,
            Render render, boolean runtimeWarmup, boolean launchRecent, String buildId,
            String pairId, String matrixBlock, int rowOrder, RunSide runSide,
            RunSide firstSide, String deviceBuild, String thermalWindow, boolean thermalValid,
            String workloadNonce, int displayTargetHz, int recentSlot, ExecutionMode executionMode,
            BootstrapMode bootstrapMode, BenchmarkScenario benchmarkScenario,
            AudioPolicy audioPolicy) {
        this.enabled = enabled;
        this.hardware = hardware;
        this.audioOutput = audioOutput;
        this.render = render;
        this.runtimeWarmup = runtimeWarmup;
        this.launchRecent = launchRecent;
        this.buildId = buildId;
        this.pairId = pairId;
        this.matrixBlock = matrixBlock;
        this.rowOrder = rowOrder;
        this.runSide = runSide;
        this.firstSide = firstSide;
        this.deviceBuild = deviceBuild;
        this.thermalWindow = thermalWindow;
        this.thermalValid = thermalValid;
        this.workloadNonce = workloadNonce;
        this.displayTargetHz = displayTargetHz;
        this.surfaceContentRateMillihz = contentRateMillihz(hardware);
        this.recentSlot = recentSlot >= 0 && recentSlot < 10 ? recentSlot : -1;
        this.executionMode = executionMode == null ? ExecutionMode.ACCURACY : executionMode;
        this.bootstrapMode = bootstrapMode == null ? BootstrapMode.SKIP : bootstrapMode;
        this.benchmarkScenario = enabled && benchmarkScenario != null
                ? benchmarkScenario : BenchmarkScenario.NONE;
        AudioPolicy requestedPolicy = audioPolicy == null ? AudioPolicy.CANONICAL : audioPolicy;
        // The silent calendar is intentionally unavailable outside the measured PERFORMANCE
        // topology, when host audio is disabled, or when the hardware profile is unresolved.
        // Exact silent PCM has an SGB/SGB2 calendar proof; the relaxed APU policy remains bounded
        // to the five DMG/CGB rows until it has equivalent SGB evidence. AUTO still fails closed
        // because its clock cannot be proven from the launch request.
        this.audioPolicy = enabled && this.executionMode == ExecutionMode.PERFORMANCE
                && audioOutput && requestedPolicy.isSilent()
                && supportsSilentPcmProfile(hardware, requestedPolicy)
                ? requestedPolicy : AudioPolicy.CANONICAL;
    }

    private static boolean supportsSilentPcmProfile(Hardware hardware, AudioPolicy policy) {
        if (policy == AudioPolicy.SILENT_PCM_V1) {
            return hardware == Hardware.DMG || hardware == Hardware.MGB
                    || hardware == Hardware.CGB || hardware == Hardware.CGB0
                    || hardware == Hardware.SGB || hardware == Hardware.SGB2;
        }
        return hardware == Hardware.DMG || hardware == Hardware.MGB
                || hardware == Hardware.CGB || hardware == Hardware.CGB0;
    }

    static DiagnosticsOptions disabled() {
        return DISABLED;
    }

    static DiagnosticsOptions disabled(ExecutionMode executionMode) {
        ExecutionMode selected = executionMode == null ? ExecutionMode.ACCURACY : executionMode;
        if (selected == ExecutionMode.ACCURACY) {
            return DISABLED;
        }
        return new DiagnosticsOptions(false, Hardware.AUTO, true, Render.PRESENTATION, false,
                false, "disabled", UNKNOWN_TOKEN, UNKNOWN_TOKEN, -1, RunSide.UNKNOWN,
                RunSide.UNKNOWN, UNKNOWN_TOKEN, UNKNOWN_TOKEN, false, UNKNOWN_TOKEN, -1, -1,
                selected, BootstrapMode.SKIP, BenchmarkScenario.NONE, AudioPolicy.CANONICAL);
    }

    static ExecutionMode parseExecutionMode(String value) {
        return "performance".equalsIgnoreCase(value == null ? "" : value.trim())
                ? ExecutionMode.PERFORMANCE : ExecutionMode.ACCURACY;
    }

    static String executionModeValue(ExecutionMode mode) {
        return mode == ExecutionMode.PERFORMANCE ? "performance" : "accuracy";
    }

    static BootstrapMode parseBootstrapMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("fast-forward".equals(normalized) || "fast_forward".equals(normalized)
                || "fastforward".equals(normalized) || "ff".equals(normalized)) {
            return BootstrapMode.FAST_FORWARD;
        }
        if ("full".equals(normalized) || "normal".equals(normalized)
                || "authentic".equals(normalized)) {
            return BootstrapMode.NORMAL;
        }
        return BootstrapMode.SKIP;
    }

    static String bootstrapModeValue(BootstrapMode mode) {
        if (mode == BootstrapMode.FAST_FORWARD) {
            return "fast-forward";
        }
        if (mode == BootstrapMode.NORMAL) {
            return "normal";
        }
        return "skip";
    }

    private static int contentRateMillihz(Hardware hardware) {
        if (hardware == Hardware.SGB) {
            return (int) Math.round((47_250_000.0 / 772_464.0) * 1_000.0);
        }
        if (hardware == Hardware.DMG || hardware == Hardware.MGB
                || hardware == Hardware.CGB || hardware == Hardware.CGB0
                || hardware == Hardware.SGB2) {
            return (int) Math.round((4_194_304.0 / 70_224.0) * 1_000.0);
        }
        return -1;
    }

    static String benchmarkArmToken(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_BENCHMARK_ARM_TOKEN)) {
            return null;
        }
        Object value = extraValue(intent, EXTRA_BENCHMARK_ARM_TOKEN);
        if (value == null) {
            return null;
        }
        String token = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return token.matches("[a-z0-9][a-z0-9._-]{15,63}") ? token : null;
    }

    static DiagnosticsOptions fromIntent(Intent intent) {
        return parse(BuildConfig.DIAGNOSTICS_ENABLED, intent);
    }

    static DiagnosticsOptions parse(boolean diagnosticsEnabled, Intent intent) {
        if (!diagnosticsEnabled) {
            return disabled(parseExecutionMode(stringExtra(intent, EXTRA_EXECUTION_MODE)));
        }

        // The benchmark variant is diagnostic by default so a bare `am start` is repeatable.
        // --ez coffee_gb_benchmark false remains a useful escape hatch for UI smoke checks.
        boolean enabled = booleanExtra(intent, EXTRA_BENCHMARK, true);
        if (!enabled) {
            return disabled(parseExecutionMode(stringExtra(intent, EXTRA_EXECUTION_MODE)));
        }
        return parseValues(true, stringExtra(intent, EXTRA_HARDWARE),
                extraValue(intent, EXTRA_AUDIO), stringExtra(intent, EXTRA_RENDER),
                booleanExtra(intent, EXTRA_WARMUP, false),
                booleanExtra(intent, EXTRA_RECENT, true),
                booleanExtra(intent, EXTRA_FRAME_SINK, false),
                stringExtra(intent, EXTRA_BUILD_ID), stringExtra(intent, EXTRA_PAIR_ID),
                stringExtra(intent, EXTRA_MATRIX_BLOCK), intExtra(intent, EXTRA_ROW_ORDER, -1),
                stringExtra(intent, EXTRA_RUN_SIDE), stringExtra(intent, EXTRA_FIRST_SIDE),
                stringExtra(intent, EXTRA_DEVICE_BUILD), stringExtra(intent, EXTRA_THERMAL_WINDOW),
                booleanExtra(intent, EXTRA_THERMAL_VALID, false),
                stringExtra(intent, EXTRA_WORKLOAD_NONCE),
                intExtra(intent, EXTRA_SURFACE_RATE_HZ, -1),
                intExtra(intent, EXTRA_RECENT_SLOT, -1),
                stringExtra(intent, EXTRA_EXECUTION_MODE),
                stringExtra(intent, EXTRA_BENCHMARK_SCENARIO),
                stringExtra(intent, EXTRA_AUDIO_POLICY),
                stringExtra(intent, EXTRA_BOOTSTRAP));
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, null, null, null, -1, null, null, null, null, false, null,
                -1, -1);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, buildId, pairId, matrixBlock, rowOrder, runSide, firstSide,
                deviceBuild, thermalWindow, thermalValid, null, -1);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid, String workloadNonce) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, buildId, pairId, matrixBlock, rowOrder, runSide, firstSide,
                deviceBuild, thermalWindow, thermalValid, workloadNonce, -1, -1);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid, String workloadNonce, int displayTargetHz) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, buildId, pairId, matrixBlock, rowOrder, runSide, firstSide,
                deviceBuild, thermalWindow, thermalValid, workloadNonce, displayTargetHz, -1);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid, String workloadNonce, int displayTargetHz, int recentSlot) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, buildId, pairId, matrixBlock, rowOrder, runSide, firstSide,
                deviceBuild, thermalWindow, thermalValid, workloadNonce, displayTargetHz,
                recentSlot, null);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid, String workloadNonce, int displayTargetHz, int recentSlot,
            String executionModeValue) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, buildId, pairId, matrixBlock, rowOrder, runSide, firstSide,
                deviceBuild, thermalWindow, thermalValid, workloadNonce, displayTargetHz,
                recentSlot, executionModeValue, null);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid, String workloadNonce, int displayTargetHz, int recentSlot,
            String executionModeValue, String benchmarkScenarioValue) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, buildId, pairId, matrixBlock, rowOrder, runSide, firstSide,
                deviceBuild, thermalWindow, thermalValid, workloadNonce, displayTargetHz,
                recentSlot, executionModeValue, benchmarkScenarioValue, null);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid, String workloadNonce, int displayTargetHz, int recentSlot,
            String executionModeValue, String benchmarkScenarioValue, String audioPolicyValue) {
        return parseValues(diagnosticsEnabled, hardwareValue, audioValue, renderValue, warmup,
                recent, frameSink, buildId, pairId, matrixBlock, rowOrder, runSide, firstSide,
                deviceBuild, thermalWindow, thermalValid, workloadNonce, displayTargetHz,
                recentSlot, executionModeValue, benchmarkScenarioValue, audioPolicyValue, null);
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink, String buildId, String pairId, String matrixBlock, int rowOrder,
            String runSide, String firstSide, String deviceBuild, String thermalWindow,
            boolean thermalValid, String workloadNonce, int displayTargetHz, int recentSlot,
            String executionModeValue, String benchmarkScenarioValue, String audioPolicyValue,
            String bootstrapModeValue) {
        if (!diagnosticsEnabled) {
            return disabled(parseExecutionMode(executionModeValue));
        }
        Hardware hardware = parseHardware(hardwareValue);
        boolean audio = booleanValue(audioValue, true);
        Render render = parseRender(renderValue);
        if (frameSink) {
            render = Render.FRAME_SINK;
        }
        int rate = displayTargetHz == 60 || displayTargetHz == 90 || displayTargetHz == 120
                ? displayTargetHz : -1;
        return new DiagnosticsOptions(true, hardware, audio, render, warmup, recent,
                safeToken(buildId, defaultBuildId()), safeToken(pairId, UNKNOWN_TOKEN),
                safeToken(matrixBlock, UNKNOWN_TOKEN), boundedRowOrder(rowOrder),
                RunSide.fromExternalValue(runSide), RunSide.fromExternalValue(firstSide),
                safeToken(deviceBuild, UNKNOWN_TOKEN), safeToken(thermalWindow, UNKNOWN_TOKEN),
                thermalValid, safeToken(workloadNonce, UNKNOWN_TOKEN), rate, recentSlot,
                parseExecutionMode(executionModeValue),
                parseBootstrapMode(bootstrapModeValue),
                BenchmarkScenario.fromExternalValue(benchmarkScenarioValue),
                AudioPolicy.fromExternalValue(audioPolicyValue));
    }

    private static Hardware parseHardware(String value) {
        return Hardware.fromExternalValue(value);
    }

    private static Render parseRender(String value) {
        if (value == null || value.isBlank()) {
            return Render.PRESENTATION;
        }
        return "sink".equalsIgnoreCase(value) || "diagnostic".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value) ? Render.FRAME_SINK
                : Render.PRESENTATION;
    }

    private static String stringExtra(Intent intent, String key) {
        if (intent == null || !intent.hasExtra(key)) {
            return null;
        }
        Object value = extraValue(intent, key);
        return value == null ? null : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private static Object extraValue(Intent intent, String key) {
        return intent == null || intent.getExtras() == null ? null : intent.getExtras().get(key);
    }

    private static int intExtra(Intent intent, String key, int defaultValue) {
        if (intent == null || !intent.hasExtra(key)) {
            return defaultValue;
        }
        Object value = extraValue(intent, key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException malformed) {
            return defaultValue;
        }
    }

    private static boolean booleanExtra(Intent intent, String key, boolean defaultValue) {
        if (intent == null || !intent.hasExtra(key)) {
            return defaultValue;
        }
        Object value = extraValue(intent, key);
        return booleanValue(value, defaultValue);
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)
                    || "yes".equalsIgnoreCase(text) || "1".equals(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)
                    || "no".equalsIgnoreCase(text) || "0".equals(text)) {
                return false;
            }
        }
        return defaultValue;
    }

    private static int boundedRowOrder(int value) {
        return value >= 0 && value < 7 ? value : -1;
    }

    private static String safeToken(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return SAFE_TOKEN.matcher(normalized).matches() ? normalized : "invalid";
    }

    private static String defaultBuildId() {
        String raw = (BuildConfig.APPLICATION_ID + "-" + BuildConfig.BUILD_TYPE + "-"
                + BuildConfig.VERSION_NAME).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(Math.min(64, raw.length()));
        for (int index = 0; index < raw.length() && normalized.length() < 64; index++) {
            char character = raw.charAt(index);
            normalized.append((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9') || character == '.'
                    || character == '_' || character == '-' ? character : '_');
        }
        String value = normalized.toString();
        return SAFE_TOKEN.matcher(value).matches() ? value : "coffee-gb-build";
    }
}
