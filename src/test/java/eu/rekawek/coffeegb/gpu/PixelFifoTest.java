package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PixelFifoTest {

    private DmgPixelFifo fifo;

    @Before
    public void createFifo() {
        MemoryRegisters r = new MemoryRegisters(GpuRegister.values());
        r.put(GpuRegister.BGP, 0b11100100);
        fifo = new DmgPixelFifo(Display.NULL_DISPLAY, new Lcdc(), r);
    }

    @Test
    public void testEnqueue() {
        fifo.enqueue8Pixels(zip(0b11001001, 0b11110000, false), TileAttributes.EMPTY);
        assertEquals(asList(3, 3, 2, 2, 1, 0, 0, 1), arrayQueueAsList(fifo.getPixels()));
    }

    @Test
    public void testDequeue() {
        fifo.enqueue8Pixels(zip(0b11001001, 0b11110000, false), TileAttributes.EMPTY);
        fifo.enqueue8Pixels(zip(0b10101011, 0b11100111, false), TileAttributes.EMPTY);
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b11, fifo.dequeuePixel());
        assertEquals(0b10, fifo.dequeuePixel());
        assertEquals(0b10, fifo.dequeuePixel());
        assertEquals(0b01, fifo.dequeuePixel());
    }

    @Test
    public void testZip() {
        assertArrayEquals(new int[] {3, 3, 2, 2, 1, 0, 0, 1}, zip(0b11001001, 0b11110000, false));
        assertArrayEquals(new int[] {1, 0, 0, 1, 2, 2, 3, 3}, zip(0b11001001, 0b11110000, true));
    }

    private int[] zip(int data1, int data2, boolean reverse) {
        return Fetcher.zip(data1, data2, reverse, new int[8]);
    }

    private static List<Integer> arrayQueueAsList(IntQueue queue) {
        List<Integer> l = new ArrayList<>(queue.size());
        for (int i = 0; i < queue.size(); i++) {
            l.add(queue.get(i));
        }
        return l;
    }
}
