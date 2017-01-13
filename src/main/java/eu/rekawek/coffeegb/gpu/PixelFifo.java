package eu.rekawek.coffeegb.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class PixelFifo {

    private Deque<Integer> deque = new ArrayDeque<>();

    public int getLength() {
        return deque.size();
    }

    public int dequeuePixel() {
        return deque.poll();
    }

    public void enqueue8Pixels(int data1, int data2) {
        deque.addAll(zip(data1, data2));
    }

    public static List<Integer> zip(int data1, int data2) {
        List<Integer> pixelLine = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            int mask = (1 << i);
            pixelLine.add(2 * ((data1 & mask) == 0 ? 0 : 1) + ((data2 & mask) == 0 ? 0 : 1));
        }
        return pixelLine;
    }

    public List<Integer> asList() {
        return new ArrayList<>(deque);
    }
}
