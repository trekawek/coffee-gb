package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Fetcher;
import eu.rekawek.coffeegb.gpu.PixelFifo;

public class PixelTransfer implements GpuPhase {

    private final int line;

    private final PixelFifo fifo;

    private final Fetcher fetcher;

    private final Display display;

    private int pixels;

    public PixelTransfer(int line, AddressSpace videoRam, Display display, int lcdc, int scrollX, int scrollY) {
        this.line = line;
        this.fifo = new PixelFifo();
        this.fetcher = new Fetcher(fifo, line, videoRam, lcdc, scrollX, scrollY);
        this.display = display;
    }

    @Override
    public boolean tick() {
        fetcher.tick();
        if (fifo.getLength() > 8) {
            display.setPixel(pixels++, line, fifo.dequeuePixel());
            if (pixels == 160) {
                display.refresh();
                return false;
            }
        }
        return true;
    }

}
