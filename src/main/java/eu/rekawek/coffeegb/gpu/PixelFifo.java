package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.google.common.collect.Lists.reverse;

public class PixelFifo {

    private final int bgp;

    private final List<Integer> pixels = new LinkedList<>();

    private final List<Integer> palettes = new LinkedList<>();

    public PixelFifo(int bgp) {
        this.bgp = bgp;
    }

    public int getLength() {
        return pixels.size();
    }

    public int dequeuePixel() {
        return getColor(palettes.remove(0), pixels.remove(0));
    }

    public void enqueue8Pixels(int data1, int data2) {
        for (int p : zip(data1, data2)) {
            pixels.add(p);
            palettes.add(bgp);
        }
    }

    public void setOverlay(int data1, int data2, int offset, SpriteFlags flags, MemoryRegisters registers) {
        List<Integer> pixelLine = zip(data1, data2);
        if (flags.isXflip()) {
            pixelLine = reverse(pixelLine);
        }
        boolean priority = flags.isPriority();
        int overlayPalette = registers.get(flags.getPalette());

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
}
