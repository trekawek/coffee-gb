package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.google.common.collect.Lists.reverse;

public class PixelFifo {

    private final int bgp;

    private final Deque<Integer> deque = new ArrayDeque<>();

    private final Deque<Integer> overlayDeque = new ArrayDeque<>();

    private boolean overlayPriority;

    private int overlayPalette;

    public PixelFifo(int bgp) {
        this.bgp = bgp;
    }

    public int getLength() {
        return deque.size();
    }

    public int dequeuePixel() {
        if (overlayDeque.isEmpty()) {
            return getBgColor(deque.poll());
        } else {
            int overlayPixel = overlayDeque.poll();
            int pixel = deque.poll();
            if (overlayPriority) {
                return pixel == 0 ? getObjectColor(overlayPixel) : getBgColor(pixel);
            } else {
                return getObjectColor(overlayPixel);
            }
        }
    }

    public void enqueue8Pixels(int data1, int data2) {
        deque.addAll(zip(data1, data2));
    }

    public void setOverlay(int data1, int data2, int offset, SpriteFlags flags, MemoryRegisters registers) {
        List<Integer> pixelLine = zip(data1, data2);
        if (flags.isXflip()) {
            reverse(pixelLine);
        }
        overlayPriority = flags.isPriority();
        overlayPalette = registers.get(flags.getPalette());
        overlayDeque.clear();
        overlayDeque.addAll(pixelLine.subList(offset, 8));
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
        return new ArrayList<>(deque);
    }

    private int getBgColor(int colorIndex) {
        return getColor(bgp, colorIndex);
    }

    private int getObjectColor(int colorIndex) {
        return getColor(overlayPalette, colorIndex);
    }

    private static int getColor(int pallete, int colorIndex) {
        return 0b11 & (pallete >> (colorIndex * 2));
    }
}
