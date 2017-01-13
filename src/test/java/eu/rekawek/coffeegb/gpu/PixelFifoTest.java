package eu.rekawek.coffeegb.gpu;

import org.junit.Test;

import java.util.Arrays;

import static eu.rekawek.coffeegb.gpu.PixelFifo.zip;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PixelFifoTest {

    @Test
    public void testEnqueue() {
        PixelFifo fifo = new PixelFifo();
        fifo.enqueue8Pixels(0b11001001, 0b11110000);
        assertEquals(asList(3, 3, 1, 1, 2, 0, 0, 2), fifo.asList());
    }

    @Test
    public void testDequeue() {
        PixelFifo fifo = new PixelFifo();
        fifo.enqueue8Pixels(0b11001001, 0b11110000);
        fifo.enqueue8Pixels(0b10101011, 0b11100111);
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b01, fifo.dequeuePixel());
        assertEquals(0b01, fifo.dequeuePixel());
        assertEquals(0b10, fifo.dequeuePixel());
    }

    @Test
    public void testZip() {
        assertEquals(Arrays.asList(3, 3, 1, 1, 2, 0, 0, 2), zip(0b11001001, 0b11110000));
    }
}
