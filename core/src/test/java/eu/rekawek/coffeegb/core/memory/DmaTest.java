package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DmaTest {

    @Test
    public void dmaRegisterPowersUpAsZeroOnCgb() {
        assertEquals(0x00, createDma(true).getByte(0xff46));
    }

    @Test
    public void dmaRegisterPowersUpAsFfOnDmg() {
        assertEquals(0xff, createDma(false).getByte(0xff46));
    }

    private static Dma createDma(boolean gbc) {
        return new Dma(new Ram(0, 0x10000), new Ram(0xfe00, 0xa0), new SpeedMode(gbc));
    }
}
