package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EmulationServiceTest {

    @Test
    public void startWireForwardsBenchmarkScenarioToken() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "cgb-action-v1");

        assertEquals("cgb-action-v1", EmulationService.benchmarkScenarioExtraValue(options));
    }
}
