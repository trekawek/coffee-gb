package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SrLatchTest {

    @Test
    public void exhaustivelyImplementsBothDominanceTruthTables() {
        for (SrLatch.Dominance dominance : SrLatch.Dominance.values()) {
            for (int initial = 0; initial < 2; initial++) {
                for (int set = 0; set < 2; set++) {
                    for (int clear = 0; clear < 2; clear++) {
                        boolean initialQ = initial != 0;
                        SrLatch latch = new SrLatch(dominance, initialQ);

                        latch.resolve(set != 0, clear != 0);

                        boolean expected = expected(dominance, initialQ, set != 0, clear != 0);
                        assertEquals(initialQ, latch.q());
                        assertEquals(expected, latch.nextQ());
                        latch.commit();
                        assertEquals(expected, latch.q());
                    }
                }
            }
        }
    }

    @Test
    public void restoreAlsoResetsAnUncommittedNextState() {
        SrLatch latch = new SrLatch(SrLatch.Dominance.SET, false);
        latch.resolve(true, false);

        latch.restore(false);

        latch.commit();
        assertEquals(false, latch.q());
    }

    private static boolean expected(
            SrLatch.Dominance dominance, boolean q, boolean set, boolean clear) {
        if (set && clear) {
            return dominance == SrLatch.Dominance.SET;
        }
        if (set) {
            return true;
        }
        if (clear) {
            return false;
        }
        return q;
    }
}
