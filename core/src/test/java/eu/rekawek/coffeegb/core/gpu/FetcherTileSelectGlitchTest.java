package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FetcherTileSelectGlitchTest {

    @Test
    public void fallingTileSelectUsesTileIndexForDeferredHighByte() {
        HoldingFifo fifo = new HoldingFifo();
        Ram vram0 = new Ram(0x8000, 0x2000);
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.put(GpuRegister.LY, 4);
        registers.put(GpuRegister.SCY, 0);
        registers.put(GpuRegister.SCX, 0);

        int tileId = 0x55;
        vram0.setByte(0x9800, tileId);
        vram0.setByte(0x8000 + tileId * 0x10 + 8, 0x7f);
        vram0.setByte(0x8000 + tileId * 0x10 + 9, 0x5d);

        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(true);
        lcdc.set(0x91);
        Fetcher fetcher = new Fetcher(
                fifo, vram0, new Ram(0x8000, 0x2000), new Ram(0xfe00, 0xa0),
                lcdc, registers, true);
        fetcher.startLine();

        for (int i = 0; i < 6; i++) {
            fetcher.advance(-16, false, -1, false);
        }

        lcdc.set(0x81);
        lcdc.triggerTileSelectGlitch();
        lcdc.tickConflicts();
        assertTrue(lcdc.isTileSelectGlitch());
        fetcher.advance(-16, false, -1, false);

        lcdc.tickConflicts();
        assertFalse(lcdc.isTileSelectGlitch());
        fifo.holding = false;
        fetcher.advance(-16, false, -1, false);

        assertArrayEquals(
                Fetcher.zip(0x7f, tileId, false, new int[8]),
                fifo.pixels);
    }

    private static class HoldingFifo implements PixelFifo {

        private boolean holding = true;

        private int[] pixels;

        @Override
        public int getLength() {
            return holding ? 1 : 0;
        }

        @Override
        public void enqueue8Pixels(int[] pixels, TileAttributes tileAttributes) {
            this.pixels = pixels.clone();
        }

        @Override
        public void putPixelToScreen() {
        }

        @Override
        public void dropPixel() {
        }

        @Override
        public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        }

        @Override
        public void clear() {
        }

        @Override
        public void clearBg() {
        }
    }
}
