package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class DmgPixelFifo implements PixelFifo {

    private final List<Integer> pixels = new LinkedList<>();

    private final List<Integer> palettes = new LinkedList<>();

    private final Display display;

    private final Lcdc lcdc;

    private final MemoryRegisters registers;

    public DmgPixelFifo(Display display, MemoryRegisters registers) {
        this.lcdc = new Lcdc(registers);
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
        return getColor(palettes.remove(0), pixels.remove(0));
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        for (int p : pixelLine) {
            pixels.add(p);
            palettes.add(registers.get(GpuRegister.BGP));
        }
    }

    @Override
    public void setOverlay(int[] pixelLine, TileAttributes flags, int oamIndex) {
        boolean priority = flags.isPriority();
        int overlayPalette = registers.get(flags.getDmgPalette());

        for (int i = 0; i < pixelLine.length; i++) {
            int p = pixelLine[i];
            if ((priority && pixels.get(i) == 0) || !priority && p != 0) {
                pixels.set(i, p);
                palettes.set(i, overlayPalette);
            }
        }
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
