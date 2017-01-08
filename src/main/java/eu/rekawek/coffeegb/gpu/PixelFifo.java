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

    public void enqueue4Pixels(int byteValue) {
        if ((length + 4) > MAX_SIZE) {
            throw new IllegalStateException("Not enough space");
        }
        value = value | (byteValue << (MAX_SIZE * 2 - length * 2 - 8));
        length += 4;
    }

    int getValue() {
        return value;
    }
}
