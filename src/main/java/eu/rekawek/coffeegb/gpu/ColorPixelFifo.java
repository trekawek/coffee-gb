package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

public class ColorPixelFifo implements PixelFifo {

    private final Lcdc lcdc;

    private final Display display;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    public ColorPixelFifo(Lcdc lcdc, Display display, ColorPalette bgPalette, ColorPalette oamPalette) {
        this.lcdc = lcdc;
        this.display = display;
        this.bgPalette = bgPalette;
        this.oamPalette = oamPalette;
    }

    @Override
    public int getLength() {
        return 0;
    }

    @Override
    public void putPixelToScreen() {

    }

    @Override
    public void dropPixel() {

    }

    @Override
    public void enqueue8Pixels(int[] pixels, TileAttributes tileAttributes) {

    }

    @Override
    public void setOverlay(int[] pixelLine, TileAttributes flags, int oamIndex) {

    }

    @Override
    public void clear() {

    }
}
