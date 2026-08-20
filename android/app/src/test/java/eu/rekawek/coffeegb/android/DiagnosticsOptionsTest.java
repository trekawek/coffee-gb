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
