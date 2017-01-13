package eu.rekawek.coffeegb.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.google.common.collect.Lists.reverse;

public class PixelFifo {

    private Deque<Integer> deque = new ArrayDeque<>();

    private Deque<Integer> overlayDeque = new ArrayDeque<>();

    private boolean overlayPriority;

    public int getLength() {
        return deque.size();
    }

    public int dequeuePixel() {
        if (overlayDeque.isEmpty()) {
            return deque.poll();
        } else {
            int overlayPixel = overlayDeque.poll();
            int pixel = deque.poll();
            if (overlayPriority && pixel != 0) {
                return pixel;
            } else {
                return overlayPixel;
            }
        }
    }

    public void enqueue8Pixels(int data1, int data2) {
        deque.addAll(zip(data1, data2));
    }

    public void setOverlay(int data1, int data2, int offset, SpriteFlags flags) {
        List<Integer> pixelLine = zip(data1, data2);
        if (flags.isXflip()) {
            reverse(pixelLine);
        }
        overlayPriority = flags.isPriority();
        overlayDeque.clear();
        overlayDeque.addAll(pixelLine.subList(offset, 8));
    }

    static List<Integer> zip(int data1, int data2) {
        List<Integer> pixelLine = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            int mask = (1 << i);
            pixelLine.add(2 * ((data1 & mask) == 0 ? 0 : 1) + ((data2 & mask) == 0 ? 0 : 1));
        }
        return pixelLine;
    }

    List<Integer> asList() {
        return new ArrayList<>(deque);
    }
}
