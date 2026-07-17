package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class FetcherScrollSamplingTest {

    @Test
    public void cgbLatchesScxAtTileFetchStart() {
        Fixture f = new Fixture();

        f.fetcher.advance(144, false, -1, false); // GET_TILE_T1
        f.registers.put(GpuRegister.SCX, 64);
        f.finishFetch();

        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1, 1, 1}, f.fifo.pixels);
    }

    @Test
    public void cgbTileFetchCollidingWithScxWriteSeesOldValue() {
        Fixture f = new Fixture();

        f.registers.setByte(GpuRegister.SCX.getAddress(), 64);
        f.registers.tickConflicts();
        f.finishFetch();

        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1, 1, 1}, f.fifo.pixels);
    }

    @Test
    public void cgbSeesScxWrittenBeforeTileFetchStart() {
        Fixture f = new Fixture();

        f.registers.put(GpuRegister.SCX, 64);
        f.finishFetch();

        assertArrayEquals(new int[]{2, 2, 2, 2, 2, 2, 2, 2}, f.fifo.pixels);
    }

    private static class Fixture {

        private final RecordingFifo fifo = new RecordingFifo();
        private final GpuRegisterValues registers = new GpuRegisterValues();
        private final Fetcher fetcher;

        private Fixture() {
            Ram vram0 = new Ram(0x8000, 0x2000);
            Ram vram1 = new Ram(0x8000, 0x2000);
            registers.put(GpuRegister.LY, 0);
            registers.put(GpuRegister.SCY, 0);
            registers.put(GpuRegister.SCX, 0);
            registers.setGbc(true);

            // At position 144 the CGB fetches map column 18 with SCX=0 and column 26
            // with SCX=64. Give the two columns solid, distinguishable tile rows.
            vram0.setByte(0x9800 + 18, 1);
            vram0.setByte(0x9800 + 26, 2);
            vram0.setByte(0x8000 + 1 * 16, 0xff);
            vram0.setByte(0x8000 + 1 * 16 + 1, 0x00);
            vram0.setByte(0x8000 + 2 * 16, 0x00);
            vram0.setByte(0x8000 + 2 * 16 + 1, 0xff);

            fetcher = new Fetcher(
                    fifo, vram0, vram1, new Ram(0xfe00, 0xa0),
                    new Lcdc(), registers, true);
            fetcher.startLine();
        }

        private void finishFetch() {
            while (fifo.pixels == null) {
                fetcher.advance(144, false, -1, false);
            }
        }
    }

    private static class RecordingFifo implements PixelFifo {

        private int[] pixels;

        @Override
        public int getLength() {
            return 0;
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
