package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Fetcher;
import eu.rekawek.coffeegb.gpu.PixelFifo;
import eu.rekawek.coffeegb.memory.MemoryRegisters;

import static eu.rekawek.coffeegb.gpu.GpuRegister.LY;

public class PixelTransfer implements GpuPhase {

    private final PixelFifo fifo;

    private final Fetcher fetcher;

    private final Display display;

    private final MemoryRegisters r;

    private int pixels;

    public PixelTransfer(AddressSpace videoRam, Display display, MemoryRegisters r) {
        this.r = r;
        this.fifo = new PixelFifo();
        this.fetcher = new Fetcher(fifo, videoRam, r);
        this.display = display;
    }

    @Override
    public boolean tick() {
        fetcher.tick();
        if (fifo.getLength() > 8) {
            display.setPixel(pixels++, r.get(LY), fifo.dequeuePixel());
            if (pixels == 160) {
                return false;
            }
        }
        return true;
    }

}
