package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DffTest {

    @Test
    public void exhaustivelyResolvesClockSetClearAndBothDominanceRules() {
        for (SrLatch.Dominance dominance : SrLatch.Dominance.values()) {
            for (int oldQ = 0; oldQ < 2; oldQ++) {
                for (int d = 0; d < 2; d++) {
                    for (int clock = 0; clock < 2; clock++) {
                        for (int set = 0; set < 2; set++) {
                            for (int clear = 0; clear < 2; clear++) {
                                Dff flipFlop = new Dff(dominance, oldQ != 0);

                                flipFlop.resolve(
                                        d != 0, clock != 0, set != 0, clear != 0);

                                boolean expected = expected(
                                        dominance, oldQ != 0, d != 0, clock != 0,
                                        set != 0, clear != 0);
                                assertEquals(oldQ != 0, flipFlop.q());
                                assertEquals(expected, flipFlop.nextQ());
                                flipFlop.commit();
                                assertEquals(expected, flipFlop.q());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aChainSamplesOneOldVectorBeforeSimultaneousCommit() {
        Dff first = new Dff(SrLatch.Dominance.CLEAR, false);
        Dff second = new Dff(SrLatch.Dominance.CLEAR, false);

        first.resolve(true, true, false, false);
        second.resolve(first.q(), true, false, false);
        first.commit();
        second.commit();

        assertEquals(true, first.q());
        assertEquals(false, second.q());
    }

    @Test
    public void restoreDiscardsAnUncommittedSample() {
        Dff flipFlop = new Dff(SrLatch.Dominance.SET, false);
        flipFlop.resolve(true, true, false, false);

        flipFlop.restore(false);
        flipFlop.commit();

        assertEquals(false, flipFlop.q());
    }

    private static boolean expected(
            SrLatch.Dominance dominance, boolean q, boolean d, boolean clock,
            boolean set, boolean clear) {
        if (set && clear) {
            return dominance == SrLatch.Dominance.SET;
        }
        if (set) {
            return true;
        }
        if (clear) {
            return false;
        }
        return clock ? d : q;
    }
}
