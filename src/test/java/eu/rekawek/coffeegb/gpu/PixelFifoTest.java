package eu.rekawek.coffeegb.gpu;

import org.junit.Test;

import java.util.Arrays;

import static eu.rekawek.coffeegb.gpu.DmgPixelFifo.zip;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class PixelFifoTest {

    @Test
    public void testEnqueue() {
        DmgPixelFifo fifo = new DmgPixelFifo(0b11100100, new Lcdc(0), null, false);
        fifo.enqueue8Pixels(0b11001001, 0b11110000);
        assertEquals(asList(3, 3, 2, 2, 1, 0, 0, 1), fifo.asList());
    }

    @Test
    public void testDequeue() {
        DmgPixelFifo fifo = new DmgPixelFifo(0b11100100, new Lcdc(0), null, false);
        fifo.enqueue8Pixels(0b11001001, 0b11110000);
        fifo.enqueue8Pixels(0b10101011, 0b11100111);
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b10, fifo.dequeuePixel());
        assertEquals(0b10, fifo.dequeuePixel());
        assertEquals(0b01, fifo.dequeuePixel());
    }

    @Test
    public void testZip() {
        assertEquals(Arrays.asList(3, 3, 2, 2, 1, 0, 0, 1), zip(0b11001001, 0b11110000));
    }
}
