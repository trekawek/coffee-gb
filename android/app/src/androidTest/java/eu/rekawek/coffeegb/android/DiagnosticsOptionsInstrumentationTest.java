package eu.rekawek.coffeegb.android;

import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class DiagnosticsOptionsInstrumentationTest {

    @Test
    public void everyHardwareValueRoundTripsThroughServiceIntent() {
        for (DiagnosticsOptions.Hardware hardware : DiagnosticsOptions.Hardware.values()) {
            CapturingContext context = new CapturingContext(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
            DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                    true, hardware.externalValue(), true, null, false, true, false);
            EmulationService.start(context, options);

            assertNotNull(context.startedIntent);
            DiagnosticsOptions parsed = DiagnosticsOptions.parse(true, context.startedIntent);

            assertEquals(hardware, parsed.hardware);
        }
    }

    @Test
    public void forcedDmgIntentAliasMapsToRegisteredDmgProfile() {
        Intent intent = new Intent()
                .putExtra(DiagnosticsOptions.EXTRA_BENCHMARK, true)
                .putExtra(DiagnosticsOptions.EXTRA_HARDWARE, "forced-dmg");

        DiagnosticsOptions parsed = DiagnosticsOptions.parse(true, intent);

        assertEquals(DiagnosticsOptions.Hardware.DMG, parsed.hardware);
        assertEquals("dmg", parsed.hardware.profileOverride().id());
    }

    @Test
    public void matrixMetadataRoundTripsThroughTheServiceIntent() {
        CapturingContext context = new CapturingContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", false, true, false,
                "candidate-build", "pair-0001", "block-0001", 4,
                "candidate", "parent", "redmi-build", "thermal-a", true, "workload-0001");

        EmulationService.start(context, options);

        DiagnosticsOptions parsed = DiagnosticsOptions.parse(true, context.startedIntent);
        assertEquals(options.buildId, parsed.buildId);
        assertEquals(options.pairId, parsed.pairId);
        assertEquals(options.matrixBlock, parsed.matrixBlock);
        assertEquals(options.rowOrder, parsed.rowOrder);
        assertEquals(options.runSide, parsed.runSide);
        assertEquals(options.firstSide, parsed.firstSide);
        assertEquals(options.deviceBuild, parsed.deviceBuild);
        assertEquals(options.thermalWindow, parsed.thermalWindow);
        assertEquals(options.thermalValid, parsed.thermalValid);
        assertEquals(options.workloadNonce, parsed.workloadNonce);
    }

    private static final class CapturingContext extends ContextWrapper {
        private Intent startedIntent;

        CapturingContext(Context base) {
            super(base);
        }

        @Override
        public ComponentName startService(Intent service) {
            startedIntent = service;
            return null;
        }
    }
}
