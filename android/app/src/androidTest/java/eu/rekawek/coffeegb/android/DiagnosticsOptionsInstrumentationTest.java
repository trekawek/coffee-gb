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
