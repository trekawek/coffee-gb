package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EdgeDetectorTest {

    @Test
    public void exhaustivelyClassifiesEveryTransitionWithoutEarlyCommit() {
        for (int previous = 0; previous < 2; previous++) {
            for (int resolved = 0; resolved < 2; resolved++) {
                boolean oldLevel = previous != 0;
                boolean newLevel = resolved != 0;
                EdgeDetector detector = new EdgeDetector(oldLevel);

                detector.resolve(newLevel);

                assertEquals(oldLevel, detector.previousLevel());
                assertEquals(newLevel, detector.resolvedLevel());
                assertEquals(!oldLevel && newLevel, detector.rising());
                assertEquals(oldLevel && !newLevel, detector.falling());
                detector.commit();
                assertEquals(newLevel, detector.previousLevel());
                assertEquals(false, detector.rising());
                assertEquals(false, detector.falling());
            }
        }
    }

    @Test
    public void restoreDiscardsAnUncommittedEdge() {
        EdgeDetector detector = new EdgeDetector(false);
        detector.resolve(true);

        detector.restore(false);

        assertEquals(false, detector.rising());
        assertEquals(false, detector.falling());
    }
}
