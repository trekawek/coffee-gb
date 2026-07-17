package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OamSearchTest {

    @Test
    public void findsVisibleSpriteWithoutDma() {
        Fixture fixture = new Fixture();

        fixture.runSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void hidesSpriteWhenDmaOverlapsOamScan() {
        Fixture fixture = new Fixture();
        fixture.dma.setByte(0xff46, 0x12);

        fixture.runSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void hidesSpriteWhenDmaEndsBetweenItsYAndXReads() {
        Fixture fixture = new Fixture();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.search.start();

        fixture.search.tick();
        for (int i = 0; i < 648; i++) {
            fixture.dma.tick();
        }
        fixture.search.tick();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    private static class Fixture {

        private final Ram memory = new Ram(0, 0x10000);

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final Dma dma = new Dma(memory, oam, new SpeedMode(false));

        private final GpuRegisterValues registers = new GpuRegisterValues();

        private final OamSearch search = new OamSearch(oam, dma, new Lcdc(), registers);

        private Fixture() {
            registers.put(GpuRegister.LY, 0);
            oam.setByte(0xfe00, 16);
            oam.setByte(0xfe01, 8);
        }

        private void runSearch() {
            search.start();
            while (search.tick()) {
                // Complete the 80-dot OAM scan.
            }
        }
    }
}
