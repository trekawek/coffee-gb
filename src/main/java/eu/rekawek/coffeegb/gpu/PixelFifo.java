package eu.rekawek.coffeegb.gpu;

public class PixelFifo {

    private static final int MAX_SIZE = 16;

    private int value = 0;

    private int length = 0;

    public int getLength() {
        return length;
    }

    public int dequeuePixel() {
        if (length == 0) {
            throw new IllegalStateException("No more elements");
        }
        int pixel = (value >> ((MAX_SIZE * 2) - 2)) & 0b11;
        length--;
        value = (value << 2) & 0xffffffff;
        return pixel;
    }

    public void enqueue8Pixels(int data1, int data2) {
        if ((length + 8) > MAX_SIZE) {
            throw new IllegalStateException("Not enough space");
        }
        int pixelLine = zip(data1, data2);
        value = value | (pixelLine << (MAX_SIZE * 2 - length * 2 - 16));
        length += 8;
    }

    public static int zip(int data1, int data2) {
        int pixelLine = 0;
        for (int i = 7; i >= 0; i--) {
            int mask = (1 << i);
            int pixel = 2 * ((data1 & mask) == 0 ? 0 : 1) + ((data2 & mask) == 0 ? 0 : 1);
            pixelLine = (pixelLine << 2) | pixel;
        }
        return pixelLine;
    }

    int getValue() {
        return value;
    }
}
