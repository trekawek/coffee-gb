package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class DmgPixelFifo implements PixelFifo, Serializable, Originator<DmgPixelFifo> {

    private final IntQueue pixels = new IntQueue(16);

    private final SpriteFifo spriteFifo = new SpriteFifo();

    private final Display display;

    private final Lcdc lcdc;

    private final GpuRegisterValues registers;

    private final VRamTransfer vRamTransfer;

    public DmgPixelFifo(Display display, Lcdc lcdc, GpuRegisterValues registers, VRamTransfer vRamTransfer) {
        this.display = display;
        this.lcdc = lcdc;
        this.registers = registers;
        this.vRamTransfer = vRamTransfer;
    }

    @Override
    public int getLength() {
        return pixels.size();
    }

    @Override
    public void putPixelToScreen() {
        int pixel = dequeuePixel();
        display.putDmgPixel(pixel);
    }

    @Override
    public void dropPixel() {
        pixels.dequeue();
        spriteFifo.pop();
    }

    int dequeuePixel() {
        int bgRaw = pixels.dequeue();
        // the background/window enable bit is applied when the pixel leaves the FIFO
        if (!lcdc.isBgAndWindowDisplay()) {
            bgRaw = 0;
        }
        spriteFifo.pop();

        int raw;
        int palette;
        if (spriteFifo.poppedPixel != 0
                && lcdc.isObjDisplay()
                && !(spriteFifo.poppedBgPriority && bgRaw != 0)) {
            raw = spriteFifo.poppedPixel;
            palette = registers.get(spriteFifo.poppedPalette == 0 ? GpuRegister.OBP0 : GpuRegister.OBP1);
        } else {
            raw = bgRaw;
            palette = registers.get(GpuRegister.BGP);
        }
        if (vRamTransfer != null) {
            vRamTransfer.putPixel(raw);
        }
        return getColor(palette, raw);
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        for (int p : pixelLine) {
            pixels.enqueue(p);
        }
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        int paletteSelector = flags.getDmgPalette() == GpuRegister.OBP1 ? 1 : 0;
        // on the DMG the first fetched object wins, so overlaying fills transparent
        // pixels only (constant priority)
        spriteFifo.overlay(pixelLine, offset, paletteSelector, flags.isPriority(), 0);
    }

    IntQueue getPixels() {
        return pixels;
    }

    private static int getColor(int palette, int colorIndex) {
        return 0b11 & (palette >> (colorIndex * 2));
    }

    @Override
    public void clear() {
        pixels.clear();
        spriteFifo.clear();
    }

    @Override
    public Memento<DmgPixelFifo> saveToMemento() {
        return new DmgPixelFifoMemento(pixels.saveToMemento(), spriteFifo.saveToMemento());
    }

    @Override
    public void restoreFromMemento(Memento<DmgPixelFifo> memento) {
        if (!(memento instanceof DmgPixelFifoMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        pixels.restoreFromMemento(mem.pixels);
        spriteFifo.restoreFromMemento(mem.spriteFifo);
    }

    private record DmgPixelFifoMemento(Memento<IntQueue> pixels, Memento<SpriteFifo> spriteFifo)
            implements Memento<DmgPixelFifo> {
    }
}
