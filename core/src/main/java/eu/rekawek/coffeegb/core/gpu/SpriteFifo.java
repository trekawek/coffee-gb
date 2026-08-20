package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

/**
 * The object pixel FIFO. It pops in lockstep with the background FIFO; an object fetch pads
 * it to 8 pixels and merges the object's row into it, so pixels of objects that start left
 * of the screen edge get discarded together with the corresponding background pixels.
 */
public class SpriteFifo implements StatefulComponent<SpriteFifo> {

    private static final int EMPTY_PRIORITY = 200;

    final int[] pixel = new int[8];

    final int[] palette = new int[8];

    final int[] priority = new int[8];

    final boolean[] bgPriority = new boolean[8];

    int head;

    int size;

    // pops taken while the FIFO was empty; a rewind cancels these first so it only
    // restores pixels that were really popped (window-activation rollback alignment)
    int underflow;

    /** Popped pixel color index, 0 = transparent. */
    public int poppedPixel;

    /** Popped pixel palette (DMG: OBP selector 0/1, CGB: palette index). */
    public int poppedPalette;

    public boolean poppedBgPriority;

    public void clear() {
        size = 0;
        head = 0;
        underflow = 0;
    }

    public void pop() {
        if (size == 0) {
            poppedPixel = 0;
            poppedPalette = 0;
            poppedBgPriority = false;
            underflow++;
            return;
        }
        poppedPixel = pixel[head];
        poppedPalette = palette[head];
        poppedBgPriority = bgPriority[head];
        head = (head + 1) & 7;
        size--;
    }

    /** Steps back one pop, restoring the last popped pixel (window-activation rollback). */
    public void rewind() {
        if (underflow > 0) {
            underflow--;
            return;
        }
        if (size < 8) {
            head = (head - 1) & 7;
            size++;
        }
    }

    public void overlay(int[] pixelLine, int offset, int paletteIndex, boolean objBgPriority, int objPriority) {
        // a fresh object fetch re-establishes the alignment; drop any pending underflow
        underflow = 0;
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

    /**
     * Re-resolves an earlier overlay with freshly read tile data (the object data reads
     * sample the registers a few dots after our machine's fetch dots; see PixelTransfer's
     * object refresh). Only pixels still in the FIFO change - popped ones keep the data
     * they were popped with, like on hardware. {@code from} is the index of the first
     * object-row pixel still in the FIFO.
     */
    public void refresh(int from, int[] oldLine, int[] newLine, int paletteIndex, boolean objBgPriority) {
        for (int k = from; k < 8; k++) {
            int idx = k - from;
            if (idx >= size) {
                break;
            }
            int slot = (head + idx) & 7;
            if (oldLine[k] != 0 && pixel[slot] == oldLine[k] && palette[slot] == paletteIndex) {
                // this slot's pixel came from the refreshed object; withdraw it
                pixel[slot] = 0;
                priority[slot] = EMPTY_PRIORITY;
            }
            if (newLine[k] != 0 && (pixel[slot] == 0 || priority[slot] > 0)) {
                pixel[slot] = newLine[k];
                palette[slot] = paletteIndex;
                priority[slot] = 0;
                bgPriority[slot] = objBgPriority;
            }
        }
    }

    @Override
    public ComponentState<SpriteFifo> captureState() {
        return new SpriteFifoState(
                pixel.clone(), palette.clone(), priority.clone(), bgPriority.clone(), head, size, underflow);
    }

    @Override
    public ComponentState<SpriteFifo> captureState(MachineStateCapture capture) {
        return new SpriteFifoState(
                capture.ints(pixel),
                capture.ints(palette),
                capture.ints(priority),
                capture.booleans(bgPriority),
                head,
                size,
                underflow);
    }

    @Override
    public void restoreState(ComponentState<SpriteFifo> state) {
        SpriteFifoState mem = validateState(state);
        System.arraycopy(mem.pixel, 0, pixel, 0, 8);
        System.arraycopy(mem.palette, 0, palette, 0, 8);
        System.arraycopy(mem.priority, 0, priority, 0, 8);
        System.arraycopy(mem.bgPriority, 0, bgPriority, 0, 8);
        this.head = mem.head;
        this.size = mem.size;
        this.underflow = mem.underflow;
    }

    /** Validates a sprite FIFO memento without mutating the live FIFO. */
    static SpriteFifoState validateState(ComponentState<?> state) {
        if (!(state instanceof SpriteFifoState mem)) {
            throw new IllegalArgumentException("Invalid SpriteFifo state type");
        }
        if (mem.pixel == null || mem.pixel.length != 8
                || mem.palette == null || mem.palette.length != 8
                || mem.priority == null || mem.priority.length != 8
                || mem.bgPriority == null || mem.bgPriority.length != 8) {
            throw new IllegalArgumentException("SpriteFifo state array capacity doesn't match");
        }
        if (mem.head < 0 || mem.head >= 8 || mem.size < 0 || mem.size > 8
                || mem.underflow < 0) {
            throw new IllegalArgumentException("Invalid SpriteFifo state cursor");
        }
        return mem;
    }

    record SpriteFifoState(
            int[] pixel, int[] palette, int[] priority, boolean[] bgPriority, int head, int size, int underflow)
            implements ComponentState<SpriteFifo> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SpriteFifoMemento(
            int[] pixel, int[] palette, int[] priority, boolean[] bgPriority, int head, int size, int underflow)
            implements Memento<SpriteFifo> {
    }
}
