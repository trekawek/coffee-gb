package eu.rekawek.coffeegb.sound;

import org.junit.Assert;
import org.junit.Test;

public class LfsrTest {

    @Test
    public void testLfsr() {
        Lfsr lfsr = new Lfsr();
        int previousValue = 0;
        for (int i = 0; i < 100; i++) {
            lfsr.nextBit(false);
            Assert.assertNotEquals(previousValue, lfsr.getValue());
            previousValue = lfsr.getValue();
        }
    }

    @Test
    public void testLfsrWidth7() {
        Lfsr lfsr = new Lfsr();
        int previousValue = 0;
        for (int i = 0; i < 100; i++) {
            lfsr.nextBit(true);
            Assert.assertNotEquals(previousValue, lfsr.getValue());
            previousValue = lfsr.getValue();
        }
    }
}
