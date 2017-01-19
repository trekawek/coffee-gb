package eu.rekawek.coffeegb.sound;

import org.junit.Test;

public class LfsrTest {

    @Test
    public void testLfsr() {
        Lfsr lfsr = new Lfsr();
        for (int i = 0; i < 100; i++) {
            lfsr.nextBit(false);
            System.out.println(String.format("%15s", Integer.toBinaryString(lfsr.getValue())));
        }
    }

}
