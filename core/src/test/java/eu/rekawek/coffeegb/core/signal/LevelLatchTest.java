package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LevelLatchTest {

    @Test
    public void exhaustiveTruthTableHonorsGateAndControlDominance() {
        for (SrLatch.Dominance dominance : SrLatch.Dominance.values()) {
            for (boolean oldQ : booleans()) {
                for (boolean d : booleans()) {
                    for (boolean gate : booleans()) {
                        for (boolean set : booleans()) {
                            for (boolean clear : booleans()) {
                                LevelLatch latch = new LevelLatch(dominance, oldQ);
                                latch.resolve(d, gate, set, clear);

                                boolean expected = set && clear
                                        ? dominance == SrLatch.Dominance.SET
                                        : set || (!clear && (gate ? d : oldQ));
                                assertEquals(label(dominance, oldQ, d, gate, set, clear),
                                        expected, latch.nextQ());
                                assertEquals("committed Q changes only at commit", oldQ, latch.q());
                                latch.commit();
                                assertEquals(expected, latch.q());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void simultaneousStagesCaptureOnlyTheCommittedOldVector() {
        LevelLatch first = new LevelLatch(SrLatch.Dominance.CLEAR, false);
        LevelLatch second = new LevelLatch(SrLatch.Dominance.CLEAR, false);

        first.resolve(true, true, false, false);
        second.resolve(first.q(), true, false, false);
        first.commit();
        second.commit();

        assertEquals(true, first.q());
        assertFalse(second.q());
    }

    @Test
    public void restoreDiscardsAResolvedTransition() {
        LevelLatch latch = new LevelLatch(SrLatch.Dominance.SET, false);
        latch.resolve(true, true, false, false);

        latch.restore(false);
        latch.commit();

        assertFalse(latch.q());
        assertFalse(latch.nextQ());
    }

    private static String label(
            SrLatch.Dominance dominance, boolean oldQ, boolean d, boolean gate,
            boolean set, boolean clear) {
        return dominance + " q=" + oldQ + " d=" + d + " gate=" + gate
                + " set=" + set + " clear=" + clear;
    }

    private static boolean[] booleans() {
        return new boolean[] {false, true};
    }
}
