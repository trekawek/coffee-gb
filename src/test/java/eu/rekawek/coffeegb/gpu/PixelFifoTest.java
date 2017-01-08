package eu.rekawek.coffeegb.gpu;

import org.junit.Test;

import static eu.rekawek.coffeegb.gpu.PixelFifo.zip;
import static org.junit.Assert.fail;

public class PixelFifoTest {

    @Test
    public void testEnqueue() {
        PixelFifo fifo = new PixelFifo();
        fifo.enqueue8Pixels(0b11001001, 0b11110000);
        assertEquals(0b11110101100000100000000000000000, fifo.getValue());
        fifo.enqueue8Pixels(0b10101010, 0b11100111);
        assertEquals(0b11110101100000101101110010011101, fifo.getValue());
    }

    @Test
    public void testDequeue() {
        PixelFifo fifo = new PixelFifo();
        fifo.enqueue8Pixels(0b11001001, 0b11110000);
        fifo.enqueue8Pixels(0b10101011, 0b11100111);
        assertEquals(0b11110101100000101101110010011111, fifo.getValue());
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b11010110000010110111001001111100, fifo.getValue());
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b01011000001011011100100111110000, fifo.getValue());
        assertEquals(0b01, fifo.dequeuePixel());
        assertEquals(0b01100000101101110010011111000000, fifo.getValue());
        assertEquals(0b01, fifo.dequeuePixel());
        assertEquals(0b10000010110111001001111100000000, fifo.getValue());
        assertEquals(0b10, fifo.dequeuePixel());
        assertEquals(0b00001011011100100111110000000000, fifo.getValue());
    }

    @Test
    public void testZip() {
        assertEquals(0b1111010110000010, zip(0b11001001, 0b11110000));
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            fail(String.format("\nExpected :%32s\nActual   :%32s", Integer.toBinaryString(expected), Integer.toBinaryString(actual)));
        }
    }
}
