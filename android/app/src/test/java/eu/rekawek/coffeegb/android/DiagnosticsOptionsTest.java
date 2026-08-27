package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticsOptionsTest {

    @Test
    public void releaseGateIgnoresAllLaunchOptions() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                false, "dmg", false, "sink", true, true, true);

        assertFalse(options.enabled);
        assertEquals(DiagnosticsOptions.Hardware.AUTO, options.hardware);
        assertTrue(options.audioOutput);
        assertEquals(ExecutionMode.ACCURACY, options.executionMode);
    }

    @Test
    public void releaseGateCannotEnableRelaxedAudioPolicy() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                false, "cgb", true, "presentation", true, true, false,
                "build-0001", "pair-0001", "block-0001", 0,
                "parent", "parent", "device-0001", "thermal-0001", true,
                "workload-0001", 60, -1, "performance", null,
                "silent-pcm-relaxed-apu-v1");

        assertFalse(options.enabled);
        assertEquals(DiagnosticsOptions.AudioPolicy.CANONICAL, options.audioPolicy);
        assertEquals(ExecutionMode.PERFORMANCE, options.executionMode);
    }

    @Test
    public void releaseGateCannotEnableSgbSilentAudioPolicy() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                false, "sgb2", true, "presentation", true, true, false,
                "build-0001", "pair-0001", "block-0001", 0,
                "parent", "parent", "device-0001", "thermal-0001", true,
                "workload-0001", 120, -1, "performance", "dmg-action-v1",
                "silent-pcm-v1");

        assertFalse(options.enabled);
        assertEquals(DiagnosticsOptions.Hardware.AUTO, options.hardware);
        assertEquals(DiagnosticsOptions.BenchmarkScenario.NONE, options.benchmarkScenario);
        assertEquals(DiagnosticsOptions.AudioPolicy.CANONICAL, options.audioPolicy);
        assertEquals(ExecutionMode.PERFORMANCE, options.executionMode);
    }

    @Test
    public void benchmarkOptionsAreTypedAndTransient() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "dmg", false, "sink", true, false, false);

        assertTrue(options.enabled);
        assertEquals(DiagnosticsOptions.Hardware.DMG, options.hardware);
        assertFalse(options.audioOutput);
        assertEquals(DiagnosticsOptions.Render.FRAME_SINK, options.render);
        assertTrue(options.runtimeWarmup);
        assertFalse(options.launchRecent);
        assertEquals(ExecutionMode.ACCURACY, options.executionMode);
        assertEquals(BootstrapMode.SKIP, options.bootstrapMode);
        assertEquals(DiagnosticsOptions.BenchmarkScenario.NONE, options.benchmarkScenario);
    }

    @Test
    public void bootstrapModeAcceptsFastForwardAndFullWithSkipFallback() {
        DiagnosticsOptions fastForward = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, null, "fast-forward");
        DiagnosticsOptions normal = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, null, "full");
        DiagnosticsOptions malformed = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, null, "turbo");

        assertEquals(BootstrapMode.FAST_FORWARD, fastForward.bootstrapMode);
        assertEquals(BootstrapMode.NORMAL, normal.bootstrapMode);
        assertEquals(BootstrapMode.SKIP, malformed.bootstrapMode);
        assertEquals("fast-forward", DiagnosticsOptions.bootstrapModeValue(fastForward.bootstrapMode));
        assertEquals("normal", DiagnosticsOptions.bootstrapModeValue(normal.bootstrapMode));
        assertEquals("skip", DiagnosticsOptions.bootstrapModeValue(malformed.bootstrapMode));
    }

    @Test
    public void benchmarkScenarioUsesOnlySafeExternalContracts() {
        DiagnosticsOptions dmg = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "DMG-ACTION-V1");
        DiagnosticsOptions cgb = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "cgb-action-v1");
        DiagnosticsOptions malformed = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "../private");

        assertEquals(DiagnosticsOptions.BenchmarkScenario.DMG_ACTION_V1, dmg.benchmarkScenario);
        assertEquals("dmg-action-v1", dmg.benchmarkScenario.externalValue());
        assertEquals(DiagnosticsOptions.BenchmarkScenario.CGB_ACTION_V1, cgb.benchmarkScenario);
        assertEquals(DiagnosticsOptions.BenchmarkScenario.NONE, malformed.benchmarkScenario);
        assertEquals(BenchmarkGameplayScenario.NativeFrameKind.DMG,
                dmg.benchmarkNativeFrameKind());
        assertEquals(BenchmarkGameplayScenario.NativeFrameKind.GBC,
                cgb.benchmarkNativeFrameKind());
    }

    @Test
    public void cgbDmgCompatKeepsDmgTimelineOnGbcNativeFrames() {
        DiagnosticsOptions compat = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "dmg-action-v1");
        DiagnosticsOptions cgb0 = DiagnosticsOptions.parseValues(
                true, "cgb0", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "cgb-action-v1");
        DiagnosticsOptions mgb = DiagnosticsOptions.parseValues(
                true, "mgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "dmg-action-v1");

        assertEquals(DiagnosticsOptions.BenchmarkScenario.DMG_ACTION_V1,
                compat.benchmarkScenario);
        assertEquals(BenchmarkGameplayScenario.NativeFrameKind.GBC,
                compat.benchmarkNativeFrameKind());
        assertEquals(BenchmarkGameplayScenario.NativeFrameKind.GBC,
                cgb0.benchmarkNativeFrameKind());
        assertEquals(BenchmarkGameplayScenario.NativeFrameKind.DMG,
                mgb.benchmarkNativeFrameKind());
    }

    @Test
    public void executionModeIsAClosedLaunchAllowListWithAccuracyFallback() {
        DiagnosticsOptions performance = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance");
        DiagnosticsOptions malformed = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "turbo");

        assertEquals(ExecutionMode.PERFORMANCE, performance.executionMode);
        assertEquals("performance", DiagnosticsOptions.executionModeValue(performance.executionMode));
        assertEquals(ExecutionMode.ACCURACY, malformed.executionMode);
        assertEquals("accuracy", DiagnosticsOptions.executionModeValue(malformed.executionMode));
    }

    @Test
    public void silentPcmPolicyIsPerformanceOnlyAndDefaultsToCanonical() {
        DiagnosticsOptions silent = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, "silent-pcm-v1");
        DiagnosticsOptions accuracy = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "accuracy", null, "silent-pcm-v1");
        DiagnosticsOptions noAudio = DiagnosticsOptions.parseValues(
                true, "cgb", false, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, "silent-pcm-v1");
        DiagnosticsOptions sgb = DiagnosticsOptions.parseValues(
                true, "sgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, "silent-pcm-v1");
        DiagnosticsOptions sgb2 = DiagnosticsOptions.parseValues(
                true, "sgb2", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, "silent-pcm-v1");
        DiagnosticsOptions relaxedSgb = DiagnosticsOptions.parseValues(
                true, "sgb", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, "silent-pcm-relaxed-apu-v1");
        DiagnosticsOptions auto = DiagnosticsOptions.parseValues(
                true, "auto", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, "silent-pcm-v1");

        assertEquals(DiagnosticsOptions.AudioPolicy.SILENT_PCM_V1, silent.audioPolicy);
        assertEquals(DiagnosticsOptions.AudioPolicy.CANONICAL, accuracy.audioPolicy);
        assertEquals(DiagnosticsOptions.AudioPolicy.CANONICAL, noAudio.audioPolicy);
        assertEquals(DiagnosticsOptions.AudioPolicy.SILENT_PCM_V1, sgb.audioPolicy);
        assertEquals(DiagnosticsOptions.AudioPolicy.SILENT_PCM_V1, sgb2.audioPolicy);
        assertEquals(DiagnosticsOptions.AudioPolicy.CANONICAL, relaxedSgb.audioPolicy);
        assertEquals(DiagnosticsOptions.AudioPolicy.CANONICAL, auto.audioPolicy);
    }

    @Test
    public void relaxedSilentPcmPolicyUsesItsOwnCoreCalendarMode() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", false, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, "silent-pcm-relaxed-apu-v1");

        assertEquals(DiagnosticsOptions.AudioPolicy.SILENT_PCM_RELAXED_APU_V1,
                options.audioPolicy);
        assertTrue(options.audioPolicy.isSilent());
        assertTrue(options.audioPolicy.isRelaxedApu());
        assertEquals(eu.rekawek.coffeegb.core.sound.Sound.PerformanceSystemMutedAudioMode.RELAXED_APU,
                options.audioPolicy.performanceSystemMutedAudioMode());
    }

    @Test
    public void malformedValuesUseSafeDefaults() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "not-a-profile", "unknown", "unknown", false, true, false);

        assertEquals(DiagnosticsOptions.Hardware.AUTO, options.hardware);
        assertTrue(options.audioOutput);
        assertEquals(DiagnosticsOptions.Render.PRESENTATION, options.render);
        assertTrue(options.launchRecent);
    }

    @Test
    public void everyHardwareValueMapsFromItsServiceWireValueExactly() {
        for (DiagnosticsOptions.Hardware hardware : DiagnosticsOptions.Hardware.values()) {
            DiagnosticsOptions parsed = DiagnosticsOptions.parseValues(
                    true, hardware.externalValue(), true, null, false, true, false);

            assertEquals(hardware, parsed.hardware);
            assertEquals(expectedProfile(hardware), hardware.profileOverride());
        }
    }

    @Test
    public void forcedDmgAliasRemainsAStableCompatibilityAlias() {
        DiagnosticsOptions parsed = DiagnosticsOptions.parseValues(
                true, "forced-dmg", true, null, false, true, false);

        assertEquals(DiagnosticsOptions.Hardware.DMG, parsed.hardware);
        assertEquals(HardwareProfileRegistry.DMG, parsed.hardware.profileOverride());
    }

    @Test
    public void effectiveModeRequiresActualMachineFlags() {
        assertEquals(DiagnosticsOptions.EffectiveMode.DMG,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.DMG, false, false));
        assertEquals(DiagnosticsOptions.EffectiveMode.MGB,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.MGB, false, false));
        assertEquals(DiagnosticsOptions.EffectiveMode.CGB_NATIVE,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.CGB, true, false));
        assertEquals(DiagnosticsOptions.EffectiveMode.CGB0_NATIVE,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.CGB0, true, false));
        assertEquals(DiagnosticsOptions.EffectiveMode.CGB_DMG_COMPAT,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.CGB, true, true));
        assertEquals(DiagnosticsOptions.EffectiveMode.CGB0_DMG_COMPAT,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.CGB0, true, true));
        assertEquals(DiagnosticsOptions.EffectiveMode.SGB,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.SGB, false, false));
        assertEquals(DiagnosticsOptions.EffectiveMode.SGB2,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.SGB2, false, false));
        assertEquals(DiagnosticsOptions.EffectiveMode.UNKNOWN,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.CGB, true, null));
        assertEquals(DiagnosticsOptions.EffectiveMode.UNKNOWN,
                DiagnosticsOptions.EffectiveMode.classify(
                        HardwareProfileRegistry.DMG, true, false));
    }

    @Test
    public void matrixMetadataIsBoundedAndRoundTripsTypedValues() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                "Candidate-Build_1", "p-0001", "block-02", 5, "candidate", "parent",
                "redmi-build", "thermal-window-a", true, "workload-0001");

        assertEquals("candidate-build_1", options.buildId);
        assertEquals("p-0001", options.pairId);
        assertEquals("block-02", options.matrixBlock);
        assertEquals(5, options.rowOrder);
        assertEquals(DiagnosticsOptions.RunSide.CANDIDATE, options.runSide);
        assertEquals(DiagnosticsOptions.RunSide.PARENT, options.firstSide);
        assertEquals("redmi-build", options.deviceBuild);
        assertEquals("thermal-window-a", options.thermalWindow);
        assertTrue(options.thermalValid);
        assertEquals("workload-0001", options.workloadNonce);

        DiagnosticsOptions malformed = DiagnosticsOptions.parseValues(
                true, "dmg", true, null, false, true, false,
                "/rom/path", "save/payload", "block with spaces", 9,
                "other", "other", "/device", "window with spaces", false);
        assertEquals("invalid", malformed.buildId);
        assertEquals("invalid", malformed.pairId);
        assertEquals("invalid", malformed.matrixBlock);
        assertEquals(-1, malformed.rowOrder);
        assertEquals(DiagnosticsOptions.RunSide.UNKNOWN, malformed.runSide);
        assertEquals(DiagnosticsOptions.RunSide.UNKNOWN, malformed.firstSide);
        assertEquals("invalid", malformed.deviceBuild);
        assertEquals("invalid", malformed.thermalWindow);
    }

    @Test
    public void contentCadenceIsExactAndIndependentFromDisplayTarget() {
        DiagnosticsOptions legacy = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, 60);
        DiagnosticsOptions sgb = DiagnosticsOptions.parseValues(
                true, "sgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, 120);

        assertEquals(60, legacy.displayTargetHz);
        assertEquals(59_728, legacy.surfaceContentRateMillihz);
        assertEquals(120, sgb.displayTargetHz);
        assertEquals(61_168, sgb.surfaceContentRateMillihz);
    }

    private static Object expectedProfile(DiagnosticsOptions.Hardware hardware) {
        switch (hardware) {
            case AUTO:
                return null;
            case DMG:
                return HardwareProfileRegistry.DMG;
            case MGB:
                return HardwareProfileRegistry.MGB;
            case CGB:
                return HardwareProfileRegistry.CGB;
            case CGB0:
                return HardwareProfileRegistry.CGB0;
            case SGB:
                return HardwareProfileRegistry.SGB;
            case SGB2:
                return HardwareProfileRegistry.SGB2;
            default:
                throw new AssertionError(hardware);
        }
    }
}
