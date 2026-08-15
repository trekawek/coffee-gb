package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmgWindowSourceLatchConeTest {

    @Test
    public void windowMatchTraversesTwoDffsBeforeSettingTheSourceLatch() {
        DmgWindowSourceLatchCone cone = new DmgWindowSourceLatchCone();

        var match = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withMatch(true).onMatchEdge());
        assertTrue(match.matchStage());
        assertFalse(match.startStage());
        assertFalse(match.inWindow());

        var start = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled().onStartEdge());
        assertTrue(start.startStage());
        assertTrue(start.inWindow());
        assertTrue(start.activated());
        assertFalse(start.deactivated());
    }

    @Test
    public void coincidentRocoAndMeheEdgesDoNotCollapseTheTwoStages() {
        DmgWindowSourceLatchCone cone = new DmgWindowSourceLatchCone();

        var collision = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withMatch(true).onMatchEdge().onStartEdge());

        assertTrue(collision.matchStage());
        assertFalse("nunu samples old pyco on the coincident edge", collision.startStage());
        assertFalse(collision.inWindow());

        var followingMehe = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .onStartEdge());
        assertTrue(followingMehe.startStage());
        assertTrue(followingMehe.inWindow());
    }

    @Test
    public void lcdcFiveDirectlyResetsAnAlreadyActiveWindowWithoutAClockEdge() {
        DmgWindowSourceLatchCone cone = activeCone();

        var cleared = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withLcdcWindowEnable(false));

        assertFalse(cleared.inWindow());
        assertTrue(cleared.deactivated());
        assertTrue("the source stage can still hold while its SR latch is reset",
                cleared.startStage());
    }

    @Test
    public void resetDominatesACoincidentStartPulseWithoutAFeatureBranch() {
        DmgWindowSourceLatchCone cone = new DmgWindowSourceLatchCone();
        cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withMatch(true).onMatchEdge());

        var collision = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .onStartEdge().withLcdcWindowEnable(false));

        assertTrue(collision.startStage());
        assertFalse(collision.inWindow());
        assertFalse(collision.activated());
    }

    @Test
    public void releasingLcdcResetReassertsTheSourceWhileNunuIsStillHigh() {
        DmgWindowSourceLatchCone cone = activeCone();

        var reset = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withLcdcWindowEnable(false));
        assertTrue(reset.startStage());
        assertFalse(reset.inWindow());

        var released = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled());
        assertTrue("NOR-latch set remains driven by the retained nunu stage",
                released.startStage());
        assertTrue(released.inWindow());
        assertTrue(released.activated());
    }

    @Test
    public void sourceRemainsLatchedAfterTheMatchPipelineReturnsLow() {
        DmgWindowSourceLatchCone cone = activeCone();

        cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled().onMatchEdge());
        var settled = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled().onStartEdge());

        assertFalse(settled.matchStage());
        assertFalse(settled.startStage());
        assertTrue(settled.inWindow());
    }

    @Test
    public void phaseAndPpuResetInputsShareTheSameClearDominantRoot() {
        DmgWindowSourceLatchCone phase = activeCone();
        assertFalse(phase.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withXahy(false)).inWindow());

        DmgWindowSourceLatchCone ppu = activeCone();
        var reset = ppu.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withPpuResetN(false));
        assertFalse(reset.matchStage());
        assertFalse(reset.startStage());
        assertFalse(reset.inWindow());
    }

    @Test
    public void arbitraryCommittedStateRestoresAndReplaysExactly() {
        DmgWindowSourceLatchCone reference = new DmgWindowSourceLatchCone();
        reference.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withMatch(true).onMatchEdge());
        DmgWindowSourceLatchCone.State snapshot = reference.capture();

        DmgWindowSourceLatchCone replay = new DmgWindowSourceLatchCone();
        replay.restore(snapshot);
        var input = DmgWindowSourceLatchCone.Inputs.idleEnabled().onStartEdge();

        assertEquals(reference.step(input), replay.step(input));
        assertEquals(reference.capture(), replay.capture());
    }

    private static DmgWindowSourceLatchCone activeCone() {
        DmgWindowSourceLatchCone cone = new DmgWindowSourceLatchCone();
        cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withMatch(true).onMatchEdge());
        assertTrue(cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .onStartEdge()).inWindow());
        return cone;
    }
}
