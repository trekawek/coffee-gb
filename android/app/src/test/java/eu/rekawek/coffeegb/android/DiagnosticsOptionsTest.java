package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;

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
