package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.gpu.*;
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
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.put(GpuRegister.BGP, 0b11100100);
        registers.put(GpuRegister.OBP0, 0);
        registers.put(GpuRegister.OBP1, 0xff);
        fifo = new DmgPixelFifo(new Display(false), new Lcdc(), registers, null);
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
    public void emptyOutputTickAdvancesTimestampWithoutChangingRuntimeState() {
        DmgPixelFifo.RuntimeState initial = fifo.captureRuntimeState();

        fifo.outputTick();
        fifo.outputTick();

        assertEquals(initial, fifo.captureRuntimeState());
        assertEquals(2, longField(fifo, "outputTicks"));
        assertEquals(0, intField(fifo, "delayHead"));
        assertEquals(0, intField(fifo, "delaySize"));
    }

    @Test
    public void firstPixelLatchClearsBeforeAnotherOutputIsRequired() {
        DmgPixelFifo.RuntimeState initial = fifo.captureRuntimeState();
        assertEquals(0, initial.linePixels());
        assertEquals(0, initial.outCount());
        assertEquals(-1, initial.firstEntry());

        fifo.enqueue8Pixels(zip(0b11001001, 0b11110000, false), TileAttributes.EMPTY);
        fifo.setOverlay(
                new int[]{3, 0, 0, 0, 0, 0, 0, 0},
                0,
                TileAttributes.valueOf(0x90),
                0);
        fifo.putPixelToScreen();
        fifo.outputTick();
        fifo.outputTick();
        fifo.outputTick();

        DmgPixelFifo.RuntimeState pending = fifo.captureRuntimeState();
        assertEquals(1, pending.linePixels());
        assertEquals(1, pending.outCount());
        assertEquals(0x3f, pending.firstEntry());
        assertEquals(0b11100100, pending.firstBgp());
        assertEquals(0, pending.firstObp0());
        assertEquals(0xff, pending.firstObp1());

        fifo.outputTick();
        DmgPixelFifo.RuntimeState emitted = fifo.captureRuntimeState();
        assertEquals(1, emitted.linePixels());
        assertEquals(1, emitted.outCount());
        assertEquals(-1, emitted.firstEntry());

        fifo.restoreRuntimeState(pending);
        assertEquals(pending, fifo.captureRuntimeState());
        fifo.restoreRuntimeState(emitted);
        assertEquals(emitted, fifo.captureRuntimeState());
    }

    @Test
    public void suppressedOutputStillClearsPendingFirstPixelLatch() {
        fifo.setRenderOutput(false);
        fifo.enqueue8Pixels(zip(0b11001001, 0b11110000, false), TileAttributes.EMPTY);
        fifo.setOverlay(
                new int[]{3, 0, 0, 0, 0, 0, 0, 0},
                0,
                TileAttributes.valueOf(0x90),
                0);
        fifo.putPixelToScreen();

        fifo.outputTick();
        fifo.outputTick();
        fifo.outputTick();
        assertEquals(0x3f, fifo.captureRuntimeState().firstEntry());

        fifo.outputTick();

        assertEquals(-1, fifo.captureRuntimeState().firstEntry());
    }

    @Test
    public void testZip() {
        assertArrayEquals(new int[]{3, 3, 2, 2, 1, 0, 0, 1}, zip(0b11001001, 0b11110000, false));
        assertArrayEquals(new int[]{1, 0, 0, 1, 2, 2, 3, 3}, zip(0b11001001, 0b11110000, true));
    }

    private int[] zip(int data1, int data2, boolean reverse) {
        return Fetcher.zip(data1, data2, reverse, new int[8]);
    }

    private static int intField(Object object, String name) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (int) field.get(object);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long longField(Object object, String name) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (long) field.get(object);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<Integer> arrayQueueAsList(IntQueue queue) {
        List<Integer> l = new ArrayList<>(queue.size());
        for (int i = 0; i < queue.size(); i++) {
            l.add(queue.get(i));
        }
        return l;
    }
}
