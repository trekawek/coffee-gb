package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BenchmarkGameplayScenarioTest {

    @Test
    public void dmgTimelineUsesPhysicalFrameBoundariesAndEndsReleased() {
        BenchmarkGameplayScenario scenario = new BenchmarkGameplayScenario(
                DiagnosticsOptions.BenchmarkScenario.DMG_ACTION_V1);
        scenario.beginSession(1L);

        for (int frame = 1; frame < 120; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.START_MASK, scenario.onFrameReady());
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, scenario.onFrameReady());
        for (int frame = 124; frame < 183; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.RIGHT_MASK, scenario.onFrameReady());
        for (int frame = 184; frame < 303; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, scenario.onFrameReady());
        for (int frame = 304; frame < 313; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        assertTrue(scenario.pauseRequested());
        assertFalse(scenario.preconditionReady());
        assertTrue(scenario.consumePauseRequest());
        assertFalse(scenario.consumePauseRequest());
        scenario.markPreconditionReady();
        assertTrue(scenario.preconditionReady());
        assertEquals(313, scenario.frameForTesting());
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, scenario.maskForTesting());
    }

    @Test
    public void cgbTimelineHasLongReleasedPrefixAndNoPostEndpointInput() {
        BenchmarkGameplayScenario scenario = new BenchmarkGameplayScenario(
                DiagnosticsOptions.BenchmarkScenario.CGB_ACTION_V1);
        scenario.beginSession(1L);
        for (int frame = 1; frame < 670; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.B_MASK, scenario.onFrameReady());
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, scenario.onFrameReady());
        for (int frame = 674; frame < 793; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.RIGHT_MASK, scenario.onFrameReady());
        for (int frame = 794; frame < 913; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.NONE_MASK, scenario.onFrameReady());
        for (int frame = 914; frame < 923; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        }
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        assertTrue(scenario.pauseRequested());
        assertEquals(923, scenario.endpointFrameForTesting());
        assertTrue(scenario.consumePauseRequest());
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
        assertFalse(scenario.consumePauseRequest());
    }

    @Test
    public void dmgCompatUsesDmgActionsButOnlyGbcNativeFrameEvents() {
        BenchmarkGameplayScenario scenario = new BenchmarkGameplayScenario(
                DiagnosticsOptions.BenchmarkScenario.DMG_ACTION_V1,
                BenchmarkGameplayScenario.NativeFrameKind.GBC);
        scenario.beginSession(41L);

        for (int frame = 1; frame < 120; frame++) {
            assertEquals(BenchmarkGameplayScenario.UNCHANGED,
                    scenario.onFrameReady(BenchmarkGameplayScenario.NativeFrameKind.GBC, 41L));
        }
        assertEquals(119, scenario.frameForTesting());
        assertEquals(BenchmarkGameplayScenario.UNCHANGED,
                scenario.onFrameReady(BenchmarkGameplayScenario.NativeFrameKind.DMG, 41L));
        assertEquals(BenchmarkGameplayScenario.UNCHANGED,
                scenario.onFrameReady(BenchmarkGameplayScenario.NativeFrameKind.GBC, 40L));
        assertEquals(119, scenario.frameForTesting());
        assertEquals(BenchmarkGameplayScenario.START_MASK,
                scenario.onFrameReady(BenchmarkGameplayScenario.NativeFrameKind.GBC, 41L));
        assertEquals(120, scenario.frameForTesting());
        assertEquals(313, scenario.endpointFrameForTesting());
    }

    @Test
    public void replacementSessionRejectsStaleNativeFrames() {
        BenchmarkGameplayScenario scenario = new BenchmarkGameplayScenario(
                DiagnosticsOptions.BenchmarkScenario.DMG_ACTION_V1);
        scenario.beginSession(7L);
        scenario.onFrameReady(BenchmarkGameplayScenario.NativeFrameKind.DMG, 7L);
        scenario.beginSession(8L);

        assertEquals(BenchmarkGameplayScenario.UNCHANGED,
                scenario.onFrameReady(BenchmarkGameplayScenario.NativeFrameKind.DMG, 7L));
        assertEquals(0, scenario.frameForTesting());
        assertFalse(scenario.preconditionReady());
    }

    @Test
    public void noneIsReadyWithoutAControllerTimeline() {
        BenchmarkGameplayScenario scenario = new BenchmarkGameplayScenario(
                DiagnosticsOptions.BenchmarkScenario.NONE);
        assertTrue(scenario.preconditionReady());
        assertEquals(BenchmarkGameplayScenario.UNCHANGED, scenario.onFrameReady());
    }
}
