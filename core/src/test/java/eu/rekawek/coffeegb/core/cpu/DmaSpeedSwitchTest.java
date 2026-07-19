package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DmaSpeedSwitchTest {

    @Test
    public void speedSwitchDoesNotRescaleElapsedOamDmaClocks() {
        Ram memory = new Ram(0, 0x10000);
        Ram oam = new Ram(0xfe00, 0xa0);
        SpeedMode speedMode = new SpeedMode(true);
        Dma dma = new Dma(memory, oam, speedMode);
        for (int i = 0; i < 0xa0; i++) {
            memory.setByte(0x8000 + i, i);
            oam.setByte(0xfe00 + i, 0xff);
        }

        dma.setByte(0xff46, 0x80);
        for (int i = 0; i < 40; i++) {
            dma.tick();
        }
        speedMode.setByte(0xff4d, 0x01);
        speedMode.onStop();
        for (int i = 0; i < 400 && dma.isTransferInProgress(); i++) {
            dma.tick();
        }

        assertFalse(dma.isTransferInProgress());
        for (int i = 0; i < 0xa0; i++) {
            assertEquals("OAM byte " + i, i, oam.getByte(0xfe00 + i));
        }
    }

    @Test
    public void cpuBusValueTracksTransferClocksAtDoubleSpeed() {
        Ram memory = new Ram(0, 0x10000);
        SpeedMode speedMode = new SpeedMode(true);
        Dma dma = new Dma(memory, new Ram(0xfe00, 0xa0), speedMode);
        for (int i = 0; i < 0xa0; i++) {
            memory.setByte(0x8000 + i, i);
        }

        speedMode.setByte(0xff4d, 0x01);
        speedMode.onStop();
        dma.setByte(0xff46, 0x80);
        for (int i = 0; i < 8; i++) {
            dma.tick();
        }

        assertEquals(2, dma.getCpuBusValue());
    }
}
