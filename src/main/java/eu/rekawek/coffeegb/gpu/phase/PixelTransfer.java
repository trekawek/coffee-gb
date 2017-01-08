package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.Fetcher;
import eu.rekawek.coffeegb.gpu.PixelFifo;

public class PixelTransfer implements GpuPhase {

    private final int line;

    private final PixelFifo fifo;

    private final Fetcher fetcher;

    private int pixels;

    public PixelTransfer(int line, AddressSpace videoRam, int lcdc, int scrollX, int scrollY) {
        this.line = line;
        this.fifo = new PixelFifo();
        this.fetcher = new Fetcher(fifo, line, videoRam, lcdc, scrollX, scrollY);
    }

    @Override
    public boolean tick() {
        fetcher.tick();
        if (fifo.getLength() > 8) {
            switch (fifo.dequeuePixel()) {
                case 0:
                    System.out.print("1");
                    break;
                case 1:
                    System.out.print("2");
                    break;
                case 2:
                    System.out.print("3");
                    break;
                case 3:
                    System.out.print("4");
                    break;
            }
            if (++pixels == 160) {
                System.out.println();
                return false;
            }
        }
        return true;
    }

}
