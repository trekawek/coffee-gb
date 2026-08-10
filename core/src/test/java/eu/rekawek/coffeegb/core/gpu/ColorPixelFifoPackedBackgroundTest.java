package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

public class ColorPixelFifoPackedBackgroundTest {

    @Test
    public void packedBackgroundKeepsPalettePriorityAndSpriteResolution() {
        Fixture fixture = new Fixture();
        fixture.fifo.enqueue8Pixels(
                new int[] {1, 0, 1, 0, 1, 0, 1, 0}, TileAttributes.valueOf(0x82));
        fixture.fifo.setOverlay(
                new int[] {3, 3, 0, 0, 0, 0, 0, 0}, 0, TileAttributes.valueOf(0x05), 0);

        fixture.putLivePixel();
        fixture.putLivePixel();

        assertEquals(fixture.bgPalette.getPalette(2)[1], fixture.displayPixel(0));
        assertEquals(fixture.oamPalette.getPalette(5)[3], fixture.displayPixel(1));
    }

    @Test
    public void clearedPackedBackgroundRetainsDropAndRewindSemantics() {
        Fixture fixture = new Fixture();
        fixture.fifo.enqueue8Pixels(
                new int[] {1, 2, 3, 1, 2, 3, 1, 2}, TileAttributes.valueOf(0x04));
        fixture.fifo.clearBg();

        fixture.fifo.dropClearedBgPixel();
        fixture.fifo.putClearedBgToScreen();
        fixture.fifo.rewindOnePixel();
        fixture.fifo.outputTick();

        assertEquals(6, fixture.fifo.getClearedBgLength());
        assertEquals(0, fixture.displaySize());

        fixture.fifo.putClearedBgToScreen();
        fixture.fifo.outputTick();
        assertEquals(fixture.bgPalette.getPalette(4)[3], fixture.displayPixel(0));
    }

    @Test
    public void wrappedLiveAndClearedBackgroundsSurviveStateRoundTrip() {
        Fixture source = new Fixture();
        source.fifo.enqueue8Pixels(
                new int[] {1, 2, 3, 1, 2, 3, 1, 2}, TileAttributes.valueOf(0x01));
        source.fifo.clearBg();

        source.fifo.enqueue8Pixels(
                new int[] {1, 1, 1, 1, 1, 1, 1, 1}, TileAttributes.valueOf(0x02));
        dropLive(source.fifo, 5);
        source.fifo.enqueue8Pixels(
                new int[] {2, 2, 2, 2, 2, 2, 2, 2}, TileAttributes.valueOf(0x03));
        dropLive(source.fifo, 5);
        source.fifo.enqueue8Pixels(
                new int[] {3, 3, 3, 3, 3, 3, 3, 3}, TileAttributes.valueOf(0x04));
        ComponentState<ColorPixelFifo> state = source.fifo.captureState();

        Fixture restored = new Fixture();
        restored.fifo.restoreState(state);
        assertEquals(14, restored.fifo.getLength());
        assertEquals(8, restored.fifo.getClearedBgLength());

        source.fifo.putClearedBgToScreen();
        restored.fifo.putClearedBgToScreen();
        source.fifo.outputTick();
        restored.fifo.outputTick();
        assertEquals(source.displayPixel(0), restored.displayPixel(0));
        assertEquals(source.bgPalette.getPalette(1)[1], restored.displayPixel(0));

        source.fifo.putPixelToScreen();
        restored.fifo.putPixelToScreen();
        source.fifo.outputTick();
        restored.fifo.outputTick();
        assertEquals(source.displayPixel(1), restored.displayPixel(1));
        assertEquals(source.bgPalette.getPalette(3)[2], restored.displayPixel(1));
    }

    @Test
    public void tokenAwareCaptureRestoresPackedBackgroundQueues() {
        Fixture source = new Fixture();
        source.fifo.enqueue8Pixels(
                new int[] {1, 2, 3, 1, 2, 3, 1, 2}, TileAttributes.valueOf(0x06));
        dropLive(source.fifo, 3);
        source.fifo.clearBg();
        source.fifo.enqueue8Pixels(
                new int[] {2, 3, 1, 2, 3, 1, 2, 3}, TileAttributes.valueOf(0x07));

        Fixture restored = new Fixture();
        MachineStateCapture.withVerifiedView(
                capture -> {},
                source.fifo::captureState,
                (state, capture) -> {
                    restored.fifo.restoreState(state);
                    return null;
                });

        assertEquals(8, restored.fifo.getLength());
        assertEquals(5, restored.fifo.getClearedBgLength());
        restored.fifo.putClearedBgToScreen();
        restored.fifo.outputTick();
        assertEquals(restored.bgPalette.getPalette(6)[1], restored.displayPixel(0));
        restored.fifo.putPixelToScreen();
        restored.fifo.outputTick();
        assertEquals(restored.bgPalette.getPalette(7)[2], restored.displayPixel(1));
    }

    private static void dropLive(ColorPixelFifo fifo, int count) {
        for (int i = 0; i < count; i++) {
            fifo.dropPixel();
        }
    }

    private static final class Fixture {

        private final Display display = new Display(true);

        private final ColorPalette bgPalette = new ColorPalette(0xff68);

        private final ColorPalette oamPalette = new ColorPalette(0xff6a);

        private final ColorPixelFifo fifo;

        private Fixture() {
            GpuRegisterValues registers = new GpuRegisterValues();
            registers.setGbc(true);
            Lcdc lcdc = new Lcdc();
            lcdc.setGbc(true);
            lcdc.set(0x93);
            for (int palette = 0; palette < 8; palette++) {
                for (int pixel = 0; pixel < 4; pixel++) {
                    bgPalette.getPalette(palette)[pixel] = 0x100 * palette + pixel;
                    oamPalette.getPalette(palette)[pixel] = 0x4000 + 0x100 * palette + pixel;
                }
            }
            fifo = new ColorPixelFifo(
                    display, lcdc, bgPalette, oamPalette, registers, new SpeedMode(true));
        }

        private void putLivePixel() {
            fifo.putPixelToScreen();
            fifo.outputTick();
        }

        private int displayPixel(int index) {
            return displayBuffer()[index];
        }

        private int displaySize() {
            return (int) displayField("i");
        }

        private int[] displayBuffer() {
            return (int[]) displayField("buffer");
        }

        private Object displayField(String name) {
            try {
                Field field = Display.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(display);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
