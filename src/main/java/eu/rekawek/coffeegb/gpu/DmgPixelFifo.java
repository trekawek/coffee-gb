package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

public class DmgPixelFifo implements PixelFifo {

    private final IntQueue pixels = new IntQueue(16);

    private final IntQueue palettes = new IntQueue(16);

    private final IntQueue pixelType = new IntQueue(16); // 0 - bg, 1 - sprite

    private final Display display;

    private final Lcdc lcdc;

    private final MemoryRegisters registers;

    public DmgPixelFifo(Display display, Lcdc lcdc, MemoryRegisters registers) {
        this.lcdc = lcdc;
        this.display = display;
        this.registers = registers;
    }

    @Override
    public int getLength() {
        return pixels.size();
    }

    @Override
    public void putPixelToScreen() {
        display.putDmgPixel(dequeuePixel());
    }

    @Override
    public void dropPixel() {
        dequeuePixel();
    }

    int dequeuePixel() {
        pixelType.dequeue();
        return getColor(palettes.dequeue(), pixels.dequeue());
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
}
