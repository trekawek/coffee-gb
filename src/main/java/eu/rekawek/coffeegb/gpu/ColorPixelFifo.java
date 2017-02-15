package eu.rekawek.coffeegb.gpu;

import java.util.LinkedList;
import java.util.List;

public class ColorPixelFifo implements PixelFifo {

    private final List<Integer> pixels = new LinkedList<>();

    private final List<int[]> palettes = new LinkedList<>();

    private final List<Integer> priorities = new LinkedList<>();

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
        return pixels.size();
    }

    @Override
    public void putPixelToScreen() {
        display.putColorPixel(dequeuePixel());
    }

    private int dequeuePixel() {
        priorities.remove(0);
        return getColor(palettes.remove(0), pixels.remove(0));
    }

    @Override
    public void dropPixel() {
        dequeuePixel();
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        for (int p : pixelLine) {
            pixels.add(p);
            palettes.add(bgPalette.getPalette(tileAttributes.getColorPaletteIndex()));
            priorities.add(tileAttributes.isPriority() ? 100 : -1);
        }
    }

    /*
    lcdc.0

    when 0 => sprites are always displayed on top of the bg

    bg tile attribute.7

    when 0 => use oam priority bit
    when 1 => bg priority

    sprite attribute.7

    when 0 => sprite above bg
    when 1 => sprite above bg color 0
     */
    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes spriteAttr, int oamIndex) {
        for (int j = offset; j < pixelLine.length; j++) {
            int p = pixelLine[j];
            int i = j - offset;
            if (p == 0) {
                continue; // color 0 is always transparent
            }
            int oldPriority = priorities.get(i);

            boolean put = false;
            if ((oldPriority == -1 || oldPriority == 100) && !lcdc.isBgAndWindowDisplay()) { // this one takes precedence
                put = true;
            } else if (oldPriority == 100) { // bg with priority
                put = pixels.get(i) == 0;
            } else if (oldPriority == -1 && !spriteAttr.isPriority()) { // bg without priority
                put = true;
            } else if (oldPriority == -1 && spriteAttr.isPriority() && pixels.get(i) == 0) { // bg without priority
                put = true;
            } else if (oldPriority >= 0 && oldPriority < 10) { // other sprite
                put = oldPriority > oamIndex;
            }

            if (put) {
                pixels.set(i, p);
                palettes.set(i, oamPalette.getPalette(spriteAttr.getColorPaletteIndex()));
                priorities.set(i, oamIndex);
            }
        }
    }

    @Override
    public void clear() {
        pixels.clear();
        palettes.clear();
        priorities.clear();
    }

    private int getColor(int[] palette, int color) {
        return palette[color];
    }
}
