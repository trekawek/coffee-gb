package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

/**
 * The object pixel FIFO. It pops in lockstep with the background FIFO; an object fetch pads
 * it to 8 pixels and merges the object's row into it, so pixels of objects that start left
 * of the screen edge get discarded together with the corresponding background pixels.
 */
public class SpriteFifo implements Serializable, Originator<SpriteFifo> {

    private static final int EMPTY_PRIORITY = 200;

    private final int[] pixel = new int[8];

    private final int[] palette = new int[8];

    private final int[] priority = new int[8];

    private final boolean[] bgPriority = new boolean[8];

    private int head;

    private int size;

    /** Popped pixel color index, 0 = transparent. */
    public int poppedPixel;

    /** Popped pixel palette (DMG: OBP selector 0/1, CGB: palette index). */
    public int poppedPalette;

    public boolean poppedBgPriority;

    public void clear() {
        size = 0;
        head = 0;
    }

    public void pop() {
        if (size == 0) {
            poppedPixel = 0;
            poppedPalette = 0;
            poppedBgPriority = false;
            return;
        }
        poppedPixel = pixel[head];
        poppedPalette = palette[head];
        poppedBgPriority = bgPriority[head];
        head = (head + 1) & 7;
        size--;
    }

    public void overlay(int[] pixelLine, int offset, int paletteIndex, boolean objBgPriority, int objPriority) {
        while (size < 8) {
            int slot = (head + size) & 7;
            pixel[slot] = 0;
            palette[slot] = 0;
            priority[slot] = EMPTY_PRIORITY;
            bgPriority[slot] = false;
            size++;
        }
        for (int j = offset; j < 8; j++) {
            int p = pixelLine[j];
            int slot = (head + j - offset) & 7;
            if (p != 0 && (pixel[slot] == 0 || priority[slot] > objPriority)) {
                pixel[slot] = p;
                palette[slot] = paletteIndex;
                priority[slot] = objPriority;
                bgPriority[slot] = objBgPriority;
            }
        }
    }

    @Override
    public Memento<SpriteFifo> saveToMemento() {
        return new SpriteFifoMemento(
                pixel.clone(), palette.clone(), priority.clone(), bgPriority.clone(), head, size);
    }

    @Override
    public void restoreFromMemento(Memento<SpriteFifo> memento) {
        if (!(memento instanceof SpriteFifoMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.pixel, 0, pixel, 0, 8);
        System.arraycopy(mem.palette, 0, palette, 0, 8);
        System.arraycopy(mem.priority, 0, priority, 0, 8);
        System.arraycopy(mem.bgPriority, 0, bgPriority, 0, 8);
        this.head = mem.head;
        this.size = mem.size;
    }

    private record SpriteFifoMemento(
            int[] pixel, int[] palette, int[] priority, boolean[] bgPriority, int head, int size)
            implements Memento<SpriteFifo> {
    }
}
