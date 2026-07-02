package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class ColorPixelFifo implements PixelFifo, Serializable, Originator<ColorPixelFifo> {

    private final IntQueue pixels = new IntQueue(16);

    private final IntQueue palettes = new IntQueue(16);

    private final IntQueue priorities = new IntQueue(16); // bg attribute priority flag

    private final SpriteFifo spriteFifo = new SpriteFifo();

    private final Lcdc lcdc;

    private final Display display;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    public ColorPixelFifo(
            Display display, Lcdc lcdc, ColorPalette bgPalette, ColorPalette oamPalette) {
        this.display = display;
        this.lcdc = lcdc;
        this.bgPalette = bgPalette;
        this.oamPalette = oamPalette;
    }

    @Override
    public int getLength() {
        return pixels.size();
    }

    @Override
    public void putPixelToScreen() {
        display.putColorPixel(dequeuePixel());
    }

    private int dequeuePixel() {
        int bgPixel = pixels.dequeue();
        int bgPaletteIndex = palettes.dequeue();
        boolean bgAttrPriority = priorities.dequeue() != 0;
        spriteFifo.pop();

        boolean drawSprite = false;
        if (spriteFifo.poppedPixel != 0 && lcdc.isObjDisplay()) {
            if (!lcdc.isBgAndWindowDisplay()) {
                // "master priority": sprites always on top
                drawSprite = true;
            } else if (bgAttrPriority) {
                drawSprite = bgPixel == 0;
            } else if (spriteFifo.poppedBgPriority) {
                drawSprite = bgPixel == 0;
            } else {
                drawSprite = true;
            }
        }
        if (drawSprite) {
            return oamPalette.getPalette(spriteFifo.poppedPalette)[spriteFifo.poppedPixel];
        } else {
            return bgPalette.getPalette(bgPaletteIndex)[bgPixel];
        }
    }

    @Override
    public void dropPixel() {
        pixels.dequeue();
        palettes.dequeue();
        priorities.dequeue();
        spriteFifo.pop();
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        for (int p : pixelLine) {
            pixels.enqueue(p);
            palettes.enqueue(tileAttributes.getColorPaletteIndex());
            priorities.enqueue(tileAttributes.isPriority() ? 1 : 0);
        }
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes spriteAttr, int oamIndex) {
        spriteFifo.overlay(
                pixelLine,
                offset,
                spriteAttr.getColorPaletteIndex(),
                spriteAttr.isPriority(),
                oamIndex);
    }

    @Override
    public void clear() {
        pixels.clear();
        palettes.clear();
        priorities.clear();
        spriteFifo.clear();
    }

    @Override
    public Memento<ColorPixelFifo> saveToMemento() {
        return new ColorPixelFifoMemento(
                pixels.saveToMemento(),
                palettes.saveToMemento(),
                priorities.saveToMemento(),
                spriteFifo.saveToMemento());
    }

    @Override
    public void restoreFromMemento(Memento<ColorPixelFifo> memento) {
        if (!(memento instanceof ColorPixelFifoMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        pixels.restoreFromMemento(mem.pixels);
        palettes.restoreFromMemento(mem.palettes);
        priorities.restoreFromMemento(mem.priorities);
        spriteFifo.restoreFromMemento(mem.spriteFifo);
    }

    private record ColorPixelFifoMemento(
            Memento<IntQueue> pixels,
            Memento<IntQueue> palettes,
            Memento<IntQueue> priorities,
            Memento<SpriteFifo> spriteFifo)
            implements Memento<ColorPixelFifo> {
    }
}
