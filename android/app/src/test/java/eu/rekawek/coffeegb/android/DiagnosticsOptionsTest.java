package eu.rekawek.coffeegb.android;

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
}
