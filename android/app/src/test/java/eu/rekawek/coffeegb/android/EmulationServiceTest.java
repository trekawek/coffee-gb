package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class EmulationServiceTest {

    @Test
    public void ordinaryStartWireExplicitlyDisablesBenchmarkMode() {
        assertFalse(EmulationService.benchmarkModeExtraValue(
                DiagnosticsOptions.disabled(
                        eu.rekawek.coffeegb.core.ExecutionMode.PERFORMANCE)));
    }

    @Test
    public void startWireForwardsBenchmarkScenarioToken() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "cgb-action-v1");

        assertEquals("cgb-action-v1", EmulationService.benchmarkScenarioExtraValue(options));
    }

    @Test
    public void startWireForwardsSilentPcmPolicyToken() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "cgb-action-v1", "silent-pcm-v1");

        assertEquals("silent-pcm-v1", EmulationService.audioPolicyExtraValue(options));
    }

    @Test
    public void startWireForwardsRequestedBootstrapMode() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", null, null, "full");

        assertEquals("normal", EmulationService.bootstrapModeExtraValue(options));
    }
}
