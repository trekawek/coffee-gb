package eu.rekawek.coffeegb.core.signal.experimental;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DeltaSettlingOracleTest {

    @Test
    public void transparentChainSettlesAcrossIslandsInEveryTraversalOrder() {
        ChainFixture fixture = transparentChain();
        DeltaSettlingOracle.SavedState allLow = fixture.network.save();

        // One resolve/commit pass samples only committed predecessors and strands two stages low.
        boolean[] old = {false, false, false};
        boolean[] onePass = {true, old[0], old[1]};
        assertArrayEquals(new boolean[]{true, false, false}, onePass);

        assertTransparentChain(fixture, allLow);

        fixture.network.restore(allLow);
        fixture.network.useReverseCellOrder();
        assertTransparentChain(fixture, allLow);

        for (int seed = 0; seed < 32; seed++) {
            fixture.network.restore(allLow);
            fixture.network.useShuffledCellOrder(0x5e771eL + seed);
            assertTransparentChain(fixture, allLow);
        }
    }

    @Test
    public void asynchronousClearFanoutPublishesInOneDeltaWithoutAnEdge() {
        DeltaSettlingOracle network = new DeltaSettlingOracle(8);
        DeltaSettlingOracle.Wire data = network.wire("data", false);
        DeltaSettlingOracle.Wire gate = network.wire("gate", false);
        DeltaSettlingOracle.Wire set = network.wire("set", false);
        DeltaSettlingOracle.Wire clear = network.wire("clear", false);
        DeltaSettlingOracle.Wire latch = network.wire("latch", true);
        DeltaSettlingOracle.Wire firstDff = network.wire("dff-1", true);
        DeltaSettlingOracle.Wire secondDff = network.wire("dff-2", true);

        network.driveTransparentLatch(latch, data, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, true);
        network.driveDff(firstDff, data, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, true);
        network.driveDff(secondDff, data, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, true);

        assertEquals(0, network.settle());
        network.setInput(clear, true);
        assertEquals(1, network.settle());
        assertFalse(network.level(latch));
        assertFalse(network.level(firstDff));
        assertFalse(network.level(secondDff));
    }

    @Test
    public void simultaneousSetAndClearUsesDeclaredDominanceForEveryStorageType() {
        DeltaSettlingOracle network = new DeltaSettlingOracle(8);
        DeltaSettlingOracle.Wire data = network.wire("data", false);
        DeltaSettlingOracle.Wire gate = network.wire("gate", false);
        DeltaSettlingOracle.Wire set = network.wire("set", true);
        DeltaSettlingOracle.Wire clear = network.wire("clear", true);
        DeltaSettlingOracle.Wire setLatch = network.wire("set-latch", false);
        DeltaSettlingOracle.Wire clearLatch = network.wire("clear-latch", true);
        DeltaSettlingOracle.Wire setDff = network.wire("set-dff", false);
        DeltaSettlingOracle.Wire clearDff = network.wire("clear-dff", true);

        network.driveTransparentLatch(setLatch, data, gate, set, clear,
                DeltaSettlingOracle.Dominance.SET, false);
        network.driveTransparentLatch(clearLatch, data, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, true);
        network.driveDff(setDff, data, set, clear,
                DeltaSettlingOracle.Dominance.SET, false);
        network.driveDff(clearDff, data, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, true);

        assertEquals(1, network.settle());
        assertTrue(network.level(setLatch));
        assertFalse(network.level(clearLatch));
        assertTrue(network.level(setDff));
        assertFalse(network.level(clearDff));
    }

    @Test
    public void stableCrossCoupledFeedbackRetainsItsSelectedBranch() {
        DeltaSettlingOracle network = new DeltaSettlingOracle(8);
        DeltaSettlingOracle.Wire set = network.wire("set", false);
        DeltaSettlingOracle.Wire reset = network.wire("reset", false);
        DeltaSettlingOracle.Wire q = network.wire("q", false);
        DeltaSettlingOracle.Wire qBar = network.wire("q-bar", true);

        network.driveNor(q, reset, qBar);
        network.driveNor(qBar, set, q);

        assertEquals(0, network.settle());
        network.setInput(set, true);
        assertEquals(2, network.settle());
        assertTrue(network.level(q));
        assertFalse(network.level(qBar));

        network.setInput(set, false);
        network.useReverseCellOrder();
        assertEquals(0, network.settle());
        assertTrue(network.level(q));
        assertFalse(network.level(qBar));
    }

    @Test
    public void edgeCapturesAllDffsBeforeAnyOfThemPublishes() {
        DeltaSettlingOracle network = new DeltaSettlingOracle(8);
        DeltaSettlingOracle.Wire data = network.wire("data", true);
        DeltaSettlingOracle.Wire set = network.wire("set", false);
        DeltaSettlingOracle.Wire clear = network.wire("clear", false);
        DeltaSettlingOracle.Wire first = network.wire("first", false);
        DeltaSettlingOracle.Wire second = network.wire("second", false);
        network.driveDff(first, data, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);
        network.driveDff(second, first, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);

        assertEquals(new DeltaSettlingOracle.EdgeResult(0, 1), network.edge());
        assertTrue(network.level(first));
        assertFalse(network.level(second));

        network.useShuffledCellOrder(0xc10cL);
        assertEquals(new DeltaSettlingOracle.EdgeResult(0, 1), network.edge());
        assertTrue(network.level(first));
        assertTrue(network.level(second));
    }

    @Test
    public void saveRestoreStoresInputsAndStorageButRecomputesTransientOutputs() {
        ReplayFixture fixture = replayFixture();
        assertEquals(1, fixture.network.settle());
        DeltaSettlingOracle.SavedState baseline = fixture.network.save();
        assertEquals(4, baseline.inputBitCount());
        assertEquals(2, baseline.storageBitCount());

        List<Integer> first = exerciseReplay(fixture);

        fixture.network.setInput(fixture.data, true);
        fixture.network.edge();
        assertTrue(fixture.network.level(fixture.q));
        assertEquals(1, fixture.network.restore(baseline));
        assertFalse(fixture.network.level(fixture.q));
        assertFalse(fixture.network.level(fixture.shadow));
        assertTrue(fixture.network.level(fixture.inverted));

        List<Integer> replayed = exerciseReplay(fixture);
        assertEquals(first, replayed);
    }

    @Test
    public void repeatedVectorAndDeltaBoundAreRejectedExplicitly() {
        DeltaSettlingOracle oscillator = new DeltaSettlingOracle(8);
        DeltaSettlingOracle.Wire ring = oscillator.wire("ring", false);
        oscillator.driveNot(ring, ring);

        DeltaSettlingOracle.NonConvergentException repeated = assertThrows(
                DeltaSettlingOracle.NonConvergentException.class, oscillator::settle);
        assertEquals(0, repeated.firstSeenDelta());
        assertEquals(2, repeated.stoppedAtDelta());
        assertTrue(repeated.getMessage().contains("repeated at delta 2"));

        DeltaSettlingOracle bounded = new DeltaSettlingOracle(1);
        DeltaSettlingOracle.Wire data = bounded.wire("data", true);
        DeltaSettlingOracle.Wire gate = bounded.wire("gate", true);
        DeltaSettlingOracle.Wire set = bounded.wire("set", false);
        DeltaSettlingOracle.Wire clear = bounded.wire("clear", false);
        DeltaSettlingOracle.Wire first = bounded.wire("first", false);
        DeltaSettlingOracle.Wire second = bounded.wire("second", false);
        bounded.driveTransparentLatch(first, data, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);
        bounded.driveTransparentLatch(second, first, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);

        DeltaSettlingOracle.NonConvergentException limit = assertThrows(
                DeltaSettlingOracle.NonConvergentException.class, bounded::settle);
        assertEquals(-1, limit.firstSeenDelta());
        assertEquals(1, limit.stoppedAtDelta());
        assertTrue(limit.getMessage().contains("exceeded 1"));
    }

    private static void assertTransparentChain(
            ChainFixture fixture, DeltaSettlingOracle.SavedState allLow) {
        fixture.network.setInput(fixture.data, true);
        assertEquals(3, fixture.network.settle());
        assertTrue(fixture.network.level(fixture.first));
        assertTrue(fixture.network.level(fixture.second));
        assertTrue(fixture.network.level(fixture.third));
        fixture.network.restore(allLow);
    }

    private static ChainFixture transparentChain() {
        DeltaSettlingOracle network = new DeltaSettlingOracle(8);
        DeltaSettlingOracle.Wire data = network.wire("data", false);
        DeltaSettlingOracle.Wire gate = network.wire("gate", true);
        DeltaSettlingOracle.Wire set = network.wire("set", false);
        DeltaSettlingOracle.Wire clear = network.wire("clear", false);
        DeltaSettlingOracle.Wire first = network.wire("first", false);
        DeltaSettlingOracle.Wire second = network.wire("second", false);
        DeltaSettlingOracle.Wire third = network.wire("third", false);
        network.driveTransparentLatch(first, data, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);
        network.driveTransparentLatch(second, first, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);
        network.driveTransparentLatch(third, second, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);
        return new ChainFixture(network, data, first, second, third);
    }

    private static ReplayFixture replayFixture() {
        DeltaSettlingOracle network = new DeltaSettlingOracle(12);
        DeltaSettlingOracle.Wire data = network.wire("data", false);
        DeltaSettlingOracle.Wire gate = network.wire("gate", true);
        DeltaSettlingOracle.Wire set = network.wire("set", false);
        DeltaSettlingOracle.Wire clear = network.wire("clear", false);
        DeltaSettlingOracle.Wire q = network.wire("q", false);
        DeltaSettlingOracle.Wire shadow = network.wire("shadow", false);
        DeltaSettlingOracle.Wire inverted = network.wire("inverted", false);
        network.driveDff(q, data, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);
        network.driveTransparentLatch(shadow, q, gate, set, clear,
                DeltaSettlingOracle.Dominance.CLEAR, false);
        network.driveNot(inverted, shadow);
        return new ReplayFixture(network, data, clear, q, shadow, inverted);
    }

    private static List<Integer> exerciseReplay(ReplayFixture fixture) {
        List<Integer> trace = new ArrayList<>();
        fixture.network.setInput(fixture.data, true);
        trace.add(fixture.network.edge().postEdgeDeltas());
        trace.add(bits(fixture));

        fixture.network.setInput(fixture.clear, true);
        trace.add(fixture.network.settle());
        trace.add(bits(fixture));

        fixture.network.setInput(fixture.clear, false);
        fixture.network.setInput(fixture.data, false);
        trace.add(fixture.network.edge().postEdgeDeltas());
        trace.add(bits(fixture));
        return trace;
    }

    private static int bits(ReplayFixture fixture) {
        return (fixture.network.level(fixture.q) ? 4 : 0)
                | (fixture.network.level(fixture.shadow) ? 2 : 0)
                | (fixture.network.level(fixture.inverted) ? 1 : 0);
    }

    private record ChainFixture(
            DeltaSettlingOracle network,
            DeltaSettlingOracle.Wire data,
            DeltaSettlingOracle.Wire first,
            DeltaSettlingOracle.Wire second,
            DeltaSettlingOracle.Wire third) {
    }

    private record ReplayFixture(
            DeltaSettlingOracle network,
            DeltaSettlingOracle.Wire data,
            DeltaSettlingOracle.Wire clear,
            DeltaSettlingOracle.Wire q,
            DeltaSettlingOracle.Wire shadow,
            DeltaSettlingOracle.Wire inverted) {
    }
}
