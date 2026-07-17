package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmaTest {

    @Test
    public void copiesOamProgressively() {
        Fixture fixture = new Fixture();
        fixture.start();

        fixture.tick(7);
        assertEquals(0xee, fixture.oam.getByte(0xfe00));

        fixture.tick(1);
        assertEquals(0x40, fixture.oam.getByte(0xfe00));
        assertEquals(0xee, fixture.oam.getByte(0xfe01));

        fixture.tick(4);
        assertEquals(0x41, fixture.oam.getByte(0xfe01));
        assertEquals(0xee, fixture.oam.getByte(0xfe02));

        fixture.tick(632);
        assertEquals(0xdf, fixture.oam.getByte(0xfe9f));
        assertTrue(fixture.dma.isTransferInProgress());

        fixture.tick(4);
        assertFalse(fixture.dma.isTransferInProgress());
    }

    @Test
    public void ppuReadsTheOamWordCurrentlyDrivenByDma() {
        Fixture fixture = new Fixture();
        fixture.start();

        fixture.tick(8);
        assertEquals(0x40, fixture.dma.getOamByteForPpu(0xfe04));
        assertEquals(0xee, fixture.dma.getOamByteForPpu(0xfe05));

        fixture.tick(4);
        assertEquals(0xee, fixture.dma.getOamByteForPpu(0xfe00));
        assertEquals(0xee, fixture.dma.getOamByteForPpu(0xfe01));
    }

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

    private static class Fixture {

        private final Ram memory = new Ram(0, 0x10000);

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final Dma dma = new Dma(memory, oam, new SpeedMode(false));

        private Fixture() {
            for (int i = 0; i < 0xa0; i++) {
                memory.setByte(0x1200 + i, 0x40 + i);
                oam.setByte(0xfe00 + i, 0xee);
            }
        }

        private void start() {
            dma.setByte(0xff46, 0x12);
        }

        private void tick(int count) {
            for (int i = 0; i < count; i++) {
                dma.tick();
            }
        }
    }
}
