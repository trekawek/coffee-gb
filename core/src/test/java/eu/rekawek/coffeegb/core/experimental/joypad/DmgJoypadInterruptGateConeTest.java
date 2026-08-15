package eu.rekawek.coffeegb.core.experimental.joypad;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmgJoypadInterruptGateConeTest {

    @Test
    public void heldInputRaisesAsokOnTheFourthOneMegahertzEdge() {
        DmgJoypadInterruptGateCone cone = new DmgJoypadInterruptGateCone();

        var first = edge(cone, 0x1);
        assertBits(first, true, false, false, false);
        assertFalse(first.asok());
        assertFalse(first.asokRising());

        var second = edge(cone, 0x1);
        assertBits(second, true, true, false, false);
        assertFalse(second.asok());

        var third = edge(cone, 0x1);
        assertBits(third, true, true, true, false);
        assertFalse(third.asok());

        var fourth = edge(cone, 0x1);
        assertBits(fourth, true, true, true, true);
        assertTrue(fourth.asok());
        assertTrue(fourth.asokRising());
    }

    @Test
    public void aSecondLowPadCannotRetriggerWhileTheAggregateRemainsHigh() {
        DmgJoypadInterruptGateCone cone = new DmgJoypadInterruptGateCone();
        for (int edge = 0; edge < 4; edge++) {
            edge(cone, 0x1);
        }

        for (int edge = 0; edge < 8; edge++) {
            var observation = edge(cone, edge < 4 ? 0x3 : 0x2);
            assertTrue(observation.asok());
            assertFalse(observation.asokRising());
        }
    }

    @Test
    public void asokChecksTheCurrentAndThreeEdgeOldSamplesRatherThanConsecutiveness() {
        DmgJoypadInterruptGateCone rejected = new DmgJoypadInterruptGateCone();
        edge(rejected, 0x1);
        edge(rejected, 0x0);
        edge(rejected, 0x0);
        var narrowPulse = edge(rejected, 0x0);
        assertFalse(narrowPulse.asok());
        assertFalse(narrowPulse.asokRising());

        DmgJoypadInterruptGateCone endpointMatch = new DmgJoypadInterruptGateCone();
        edge(endpointMatch, 0x1);
        edge(endpointMatch, 0x0);
        edge(endpointMatch, 0x0);
        var nonConsecutive = edge(endpointMatch, 0x8);
        assertTrue(nonConsecutive.asok());
        assertTrue(nonConsecutive.asokRising());
    }

    @Test
    public void allEightSampleSequencesMatchTheBatuAndApugEndpointEquation() {
        for (int sequence = 0; sequence < 1 << 8; sequence++) {
            DmgJoypadInterruptGateCone cone = new DmgJoypadInterruptGateCone();
            boolean expectedPreviousAsok = false;
            int history = 0;

            for (int sample = 0; sample < 8; sample++) {
                boolean anyLow = (sequence & 1 << sample) != 0;
                // Pad identity deliberately varies: KERY must erase it before BATU.
                int lowLines = anyLow ? 1 << (sample & 3) : 0;
                var observation = edge(cone, lowLines);

                history = (history << 1 | (anyLow ? 1 : 0)) & 0x0f;
                boolean expectedAsok = (history & 0x09) == 0x09;
                assertEquals(expectedAsok, observation.asok());
                assertEquals(!expectedPreviousAsok && expectedAsok,
                        observation.asokRising());
                expectedPreviousAsok = expectedAsok;
            }
        }
    }

    @Test
    public void awobIsAnEarlierTransparentWakePathIndependentOfAsok() {
        DmgJoypadInterruptGateCone cone = new DmgJoypadInterruptGateCone();

        var opened = cone.step(0x4, true, false);
        assertTrue(opened.cpuWakeup());
        assertFalse(opened.asok());

        var followsRelease = cone.step(0, true, false);
        assertFalse(followsRelease.cpuWakeup());

        var retainedWhileLow = cone.step(0x8, false, false);
        assertFalse(retainedWhileLow.cpuWakeup());

        var nextHigh = cone.step(0x8, true, true);
        assertTrue(nextHigh.cpuWakeup());
        assertFalse(nextHigh.asok());
    }

    @Test
    public void provenancePinsTheAuditedSchematicSnapshot() {
        assertEquals("02399f96e0893783c130cf6f03fad7a1148ae60a",
                DmgJoypadInterruptGateCone.SCHEMATIC_REVISION);
    }

    private static DmgJoypadInterruptGateCone.Observation edge(
            DmgJoypadInterruptGateCone cone, int lowInputLines) {
        // The low half is relevant to AWOB retention; only the high transition clocks the DFFs.
        cone.step(lowInputLines, false, false);
        return cone.step(lowInputLines, true, true);
    }

    private static void assertBits(
            DmgJoypadInterruptGateCone.Observation observation,
            boolean batu, boolean acef, boolean agem, boolean apug) {
        assertEquals(batu, observation.batu());
        assertEquals(acef, observation.acef());
        assertEquals(agem, observation.agem());
        assertEquals(apug, observation.apug());
    }
}
