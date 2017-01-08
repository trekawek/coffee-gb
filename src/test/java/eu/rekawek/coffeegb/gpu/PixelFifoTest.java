package eu.rekawek.coffeegb.gpu;

import org.junit.Test;

import static org.junit.Assert.fail;

public class PixelFifoTest {

    @Test
    public void testEnqueue() {
        PixelFifo fifo = new PixelFifo();
        fifo.enqueue4Pixels(0b11001001);
        assertEquals(0b11001001000000000000000000000000, fifo.getValue());
        fifo.enqueue4Pixels(0b11110000);
        assertEquals(0b11001001111100000000000000000000, fifo.getValue());
        fifo.enqueue4Pixels(0b10101011);
        assertEquals(0b11001001111100001010101100000000, fifo.getValue());
        fifo.enqueue4Pixels(0b11100111);
        assertEquals(0b11001001111100001010101111100111, fifo.getValue());
    }

    @Test
    public void testDequeue() {
        PixelFifo fifo = new PixelFifo();
        fifo.enqueue4Pixels(0b11001001);
        fifo.enqueue4Pixels(0b11110000);
        fifo.enqueue4Pixels(0b10101011);
        fifo.enqueue4Pixels(0b11100111);
        assertEquals(0b11001001111100001010101111100111, fifo.getValue());
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b00100111110000101010111110011100, fifo.getValue());
        assertEquals(0b00, fifo.dequeuePixel());
        assertEquals(0b10011111000010101011111001110000, fifo.getValue());
        assertEquals(0b10, fifo.dequeuePixel());
        assertEquals(0b01111100001010101111100111000000, fifo.getValue());
        assertEquals(0b01, fifo.dequeuePixel());
        assertEquals(0b11110000101010111110011100000000, fifo.getValue());
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            fail(String.format("\nExpected :%32s\nActual   :%32s", Integer.toBinaryString(expected), Integer.toBinaryString(actual)));
        }
    }
}
