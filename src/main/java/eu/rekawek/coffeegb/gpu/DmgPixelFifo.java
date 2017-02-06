package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.google.common.collect.Lists.reverse;

public class DmgPixelFifo implements PixelFifo {

    private final int bgp;

    private final List<Integer> pixels = new LinkedList<>();

    private final List<Integer> palettes = new LinkedList<>();

    private final Display display;

    private final Lcdc lcdc;

    private final boolean gbc;

    public DmgPixelFifo(int bgp, Lcdc lcdc, Display display, boolean gbc) {
        this.bgp = bgp;
        this.lcdc = lcdc;
        this.gbc = gbc;
        this.display = display;
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
        return getColor(palettes.remove(0), pixels.remove(0));
    }

    @Override
    public void enqueue8Pixels(int data1, int data2) {
        for (int p : zip(data1, data2)) {
            pixels.add(p);
            palettes.add(bgp);
        }
    }

    // FIXME in the GBC mode sprite priorites depends on the OAM table position
    // see "Sprite Priorities and Conflicts" in pandocs
    @Override
    public void setOverlay(int data1, int data2, int offset, TileAttributes flags, MemoryRegisters registers) {
        List<Integer> pixelLine = zip(data1, data2);
        if (flags.isXflip()) {
            pixelLine = reverse(pixelLine);
        }
        boolean priority = flags.isPriority();
        if (gbc && !lcdc.isBgAndWindowDisplay()) {
            priority = false;
        }
        int overlayPalette = registers.get(flags.getDmgPalette());

        int i = 0;
        for (int p : pixelLine.subList(offset, pixelLine.size())) {
            if ((priority && pixels.get(i) == 0) || !priority && p != 0) {
                pixels.set(i, p);
                palettes.set(i, overlayPalette);
            }
            i++;
        }
    }

    static List<Integer> zip(int data1, int data2) {
        List<Integer> pixelLine = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            int mask = (1 << i);
            pixelLine.add(2 * ((data2 & mask) == 0 ? 0 : 1) + ((data1 & mask) == 0 ? 0 : 1));
        }
        return pixelLine;
    }

    List<Integer> asList() {
        return new ArrayList<>(pixels);
    }

    private static int getColor(int palette, int colorIndex) {
        return 0b11 & (palette >> (colorIndex * 2));
    }

    @Override
    public void clear() {
        pixels.clear();
        palettes.clear();
    }
}
