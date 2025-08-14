package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

public class DmgPixelFifo implements PixelFifo, Serializable, Originator<DmgPixelFifo> {

    private final IntQueue pixels = new IntQueue(16);

    private final IntQueue palettes = new IntQueue(16);

    private final IntQueue pixelType = new IntQueue(16); // 0 - bg, 1 - sprite

    private final Display display;

    private final GpuRegisterValues registers;

    private final VRamTransfer vRamTransfer;

    public DmgPixelFifo(Display display, GpuRegisterValues registers, VRamTransfer vRamTransfer) {
        this.display = display;
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
        dequeuePixel();
    }

    int dequeuePixel() {
        pixelType.dequeue();

        var pixel = pixels.dequeue();
        if (vRamTransfer != null) {
            vRamTransfer.putPixel(pixel);
        }

        return getColor(palettes.dequeue(), pixel);
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        for (int p : pixelLine) {
            pixels.enqueue(p);
            palettes.enqueue(registers.get(GpuRegister.BGP));
            pixelType.enqueue(0);
        }
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        boolean priority = flags.isPriority();
        int overlayPalette = registers.get(flags.getDmgPalette());

        for (int j = offset; j < pixelLine.length; j++) {
            int p = pixelLine[j];
            int i = j - offset;
            if (pixelType.get(i) == 1) {
                continue;
            }
            if ((priority && pixels.get(i) == 0) || !priority && p != 0) {
                pixels.set(i, p);
                palettes.set(i, overlayPalette);
                pixelType.set(i, 1);
            }
        }
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
        palettes.clear();
        pixelType.clear();
    }

    @Override
    public Memento<DmgPixelFifo> saveToMemento() {
        return new DmgPixelFifoMemento(
                pixels.saveToMemento(), palettes.saveToMemento(), pixelType.saveToMemento());
    }

    @Override
    public void restoreFromMemento(Memento<DmgPixelFifo> memento) {
        if (!(memento instanceof DmgPixelFifoMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        pixels.restoreFromMemento(mem.pixels);
        palettes.restoreFromMemento(mem.palettes);
        pixelType.restoreFromMemento(mem.pixelType);
    }

    private record DmgPixelFifoMemento(
            Memento<IntQueue> pixels, Memento<IntQueue> palettes, Memento<IntQueue> pixelType)
            implements Memento<DmgPixelFifo> {
    }
}
