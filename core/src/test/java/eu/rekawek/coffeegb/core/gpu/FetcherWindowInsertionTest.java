package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FetcherWindowInsertionTest {

    @Test
    public void disabledWindowDoesNotInsertPixelWithoutWyTrigger() {
        RecordingFifo fifo = runFetch(false);

        assertEquals(0, fifo.singlePixelPushes);
        assertEquals(1, fifo.tilePushes);
    }

    @Test
    public void disabledWindowInsertsPixelAfterWyTrigger() {
        RecordingFifo fifo = runFetch(true);

        assertEquals(1, fifo.singlePixelPushes);
        assertEquals(0, fifo.tilePushes);
    }

    private static RecordingFifo runFetch(boolean windowYTriggered) {
        RecordingFifo fifo = new RecordingFifo();
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.put(GpuRegister.LY, 128);
        registers.put(GpuRegister.WY, 128);
        registers.put(GpuRegister.WX, 151);

        Lcdc lcdc = new Lcdc(); // $91: LCD and BG on, window off (Bo Jackson scoreboard)
        Fetcher fetcher = new Fetcher(
                fifo,
                new Ram(0x8000, 0x2000),
                null,
                new Ram(0xfe00, 0xa0),
                lcdc,
                registers,
                false);
        fetcher.startLine();
        fetcher.setWindowYTriggered(windowYTriggered);

        while (fifo.singlePixelPushes == 0 && fifo.tilePushes == 0) {
            fetcher.advance(144, false, -1, false);
        }
        return fifo;
    }

    private static class RecordingFifo implements PixelFifo {

        private int singlePixelPushes;
        private int tilePushes;

        @Override
        public int getLength() {
            return 0;
        }

        @Override
        public void enqueuePixel(int pixel) {
            singlePixelPushes++;
        }

        @Override
        public void enqueue8Pixels(int[] pixels, TileAttributes tileAttributes) {
            tilePushes++;
        }

        @Override
        public void putPixelToScreen() {
        }

        @Override
        public void dropPixel() {
        }

        @Override
        public void setOverlay(
                int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        }

        @Override
        public void clear() {
        }

        @Override
        public void clearBg() {
        }
    }
}
