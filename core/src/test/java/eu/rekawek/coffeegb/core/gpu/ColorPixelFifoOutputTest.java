package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ColorPixelFifoOutputTest {

    @Test
    public void suppressedTickDrainsPendingOutputWithoutPublishingIt() {
        Fixture fixture = new Fixture();
        fixture.fifo.outputTick();
        fixture.enqueuePixels();
        fixture.putPixels(1);

        fixture.fifo.setRenderOutput(false);
        fixture.fifo.outputTick();

        assertEquals(2, longField(fixture.fifo, "outputTicks"));
        assertEquals(1, intField(fixture.fifo, "delayHead"));
        assertEquals(0, intField(fixture.fifo, "delaySize"));
        assertEquals(0, displayPixelCount(fixture.display));
    }

    @Test
    public void suppressedDrainAdvancesTheDelayRingHeadAcrossWrap() {
        Fixture fixture = new Fixture();
        fixture.fifo.outputTick();
        fixture.enqueuePixels();
        for (int i = 0; i < 6; i++) {
            fixture.putPixels(1);
            fixture.fifo.outputTick();
        }
        assertEquals(6, intField(fixture.fifo, "delayHead"));

        fixture.enqueuePixels();
        fixture.putPixels(3);
        fixture.fifo.setRenderOutput(false);
        fixture.fifo.outputTick();

        assertEquals(1, intField(fixture.fifo, "delayHead"));
        assertEquals(0, intField(fixture.fifo, "delaySize"));
    }

    @Test
    public void sameDotEnqueueThenRewindLeavesNoSuppressedOutputBehind() {
        Fixture fixture = new Fixture();
        fixture.fifo.setRenderOutput(false);
        fixture.fifo.outputTick();
        fixture.enqueuePixels();
        fixture.putPixels(1);

        fixture.fifo.rewindOnePixel();
        fixture.fifo.outputTick();

        assertEquals(0, intField(fixture.fifo, "linePixels"));
        assertEquals(0, intField(fixture.fifo, "delaySize"));
        assertEquals(0, displayPixelCount(fixture.display));

        fixture.fifo.setRenderOutput(true);
        fixture.putPixels(1);
        fixture.fifo.outputTick();
        assertEquals(1, displayPixelCount(fixture.display));
    }

    @Test
    public void restoredPendingOutputStillResolvesOnItsDueTick() {
        Fixture source = new Fixture();
        source.fifo.outputTick();
        source.enqueuePixels();
        source.putPixels(1);
        ComponentState<ColorPixelFifo> pending = source.fifo.captureState();

        Fixture restored = new Fixture();
        restored.fifo.restoreState(pending);
        restored.fifo.outputTick();

        assertEquals(1, displayPixelCount(restored.display));
        assertEquals(restored.bgPalette.getPalette(0)[1], displayPixel(restored.display, 0));
    }

    @Test
    public void togglingSuppressionDiscardsOnlyThePendingOutput() {
        Fixture fixture = new Fixture();
        fixture.fifo.outputTick();
        fixture.enqueuePixels();
        fixture.putPixels(1);

        fixture.fifo.setRenderOutput(false);
        fixture.fifo.outputTick();
        fixture.fifo.setRenderOutput(true);
        fixture.fifo.outputTick();
        assertEquals(0, displayPixelCount(fixture.display));

        fixture.putPixels(1);
        fixture.fifo.outputTick();
        assertEquals(1, displayPixelCount(fixture.display));
        assertEquals(fixture.bgPalette.getPalette(0)[2], displayPixel(fixture.display, 0));
    }

    @Test
    public void suppressedDrainMatchesTheGeneralTimestampLoopForLivePendingState() {
        Fixture source = new Fixture();
        source.fifo.outputTick();
        source.enqueuePixels();
        source.putPixels(1);
        ComponentState<ColorPixelFifo> pending = source.fifo.captureState();

        Fixture fast = new Fixture();
        Fixture general = new Fixture();
        fast.fifo.restoreState(pending);
        general.fifo.restoreState(pending);

        fast.fifo.setRenderOutput(false);
        fast.fifo.outputTick();
        drainWithGeneralTimestampLoop(general.fifo);

        assertEquals(longField(general.fifo, "outputTicks"), longField(fast.fifo, "outputTicks"));
        assertEquals(intField(general.fifo, "delayHead"), intField(fast.fifo, "delayHead"));
        assertEquals(intField(general.fifo, "delaySize"), intField(fast.fifo, "delaySize"));
        assertArrayEquals(arrayField(general.fifo, "delayEntry"), arrayField(fast.fifo, "delayEntry"));
        assertArrayEquals(longArrayField(general.fifo, "delayStamp"), longArrayField(fast.fifo, "delayStamp"));
    }

    /** The timestamp loop used by the pre-fast-path suppressed implementation. */
    private static void drainWithGeneralTimestampLoop(ColorPixelFifo fifo) {
        long outputTicks = longField(fifo, "outputTicks") + 1;
        setLongField(fifo, "outputTicks", outputTicks);
        int delayHead = intField(fifo, "delayHead");
        int delaySize = intField(fifo, "delaySize");
        long[] delayStamp = longArrayField(fifo, "delayStamp");
        while (delaySize > 0 && delayStamp[delayHead] + ColorPixelFifo.OUTPUT_DELAY <= outputTicks) {
            delayHead = (delayHead + 1) & 7;
            delaySize--;
        }
        setIntField(fifo, "delayHead", delayHead);
        setIntField(fifo, "delaySize", delaySize);
    }

    private static int displayPixelCount(Display display) {
        return intField(display, "i");
    }

    private static int displayPixel(Display display, int index) {
        return arrayField(display, "buffer")[index];
    }

    private static int intField(Object object, String name) {
        return (int) getField(object, name);
    }

    private static long longField(Object object, String name) {
        return (long) getField(object, name);
    }

    private static int[] arrayField(Object object, String name) {
        return (int[]) getField(object, name);
    }

    private static long[] longArrayField(Object object, String name) {
        return (long[]) getField(object, name);
    }

    private static void setIntField(Object object, String name, int value) {
        setField(object, name, value);
    }

    private static void setLongField(Object object, String name, long value) {
        setField(object, name, value);
    }

    private static Object getField(Object object, String name) {
        try {
            return field(object, name).get(object);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object object, String name, Object value) {
        try {
            field(object, name).set(object, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static class Fixture {

        private final Display display = new Display(true);

        private final ColorPalette bgPalette = new ColorPalette(0xff68);

        private final ColorPixelFifo fifo;

        private Fixture() {
            GpuRegisterValues registers = new GpuRegisterValues();
            registers.setGbc(true);
            Lcdc lcdc = new Lcdc();
            lcdc.setGbc(true);
            lcdc.set(0x93);
            bgPalette.getPalette(0)[1] = 0x001f;
            bgPalette.getPalette(0)[2] = 0x03e0;
            bgPalette.getPalette(0)[3] = 0x7c00;
            fifo = new ColorPixelFifo(
                    display,
                    lcdc,
                    bgPalette,
                    new ColorPalette(0xff6a),
                    registers,
                    new SpeedMode(true));
        }

        private void enqueuePixels() {
            fifo.enqueue8Pixels(new int[]{1, 2, 3, 1, 2, 3, 1, 2}, TileAttributes.EMPTY);
        }

        private void putPixels(int count) {
            for (int i = 0; i < count; i++) {
                fifo.putPixelToScreen();
            }
        }
    }
}
