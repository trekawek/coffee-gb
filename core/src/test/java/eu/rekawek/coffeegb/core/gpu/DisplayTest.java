package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class DisplayTest {

    private static final int PIXELS = Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT;

    @Test
    public void dmgBlanksFirstFrameAfterLcdEnable() {
        Display display = new Display(false);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.pixels().clone()), Display.DmgFrameReadyEvent.class);
        display.init(eventBus);

        fillDmg(display, 3);
        display.frameIsReady();

        display.disableLcd();
        display.enableLcd();
        fillDmg(display, 2);
        display.frameIsReady();

        assertArrayEquals(new int[PIXELS], frame.get());

        int[] expected = new int[PIXELS];
        java.util.Arrays.fill(expected, 1);
        fillDmg(display, 1);
        display.frameIsReady();
        assertArrayEquals(expected, frame.get());
    }

    @Test
    public void cgbRepeatsPreviousFrameAfterLcdEnable() {
        Display display = new Display(true);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.pixels().clone()), Display.GbcFrameReadyEvent.class);
        display.init(eventBus);

        int[] expected = new int[PIXELS];
        java.util.Arrays.fill(expected, 0x1234);
        fillCgb(display, 0x1234);
        display.frameIsReady();

        display.disableLcd();
        display.enableLcd();
        fillCgb(display, 0x4321);
        display.frameIsReady();

        assertArrayEquals(expected, frame.get());
    }

    @Test
    public void cgbUsesWhiteWhileLcdIsOff() {
        Display display = new Display(true);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.pixels().clone()), Display.GbcFrameReadyEvent.class);
        display.init(eventBus);

        display.disableLcd();
        display.blankFrame();

        int[] expected = new int[PIXELS];
        java.util.Arrays.fill(expected, 0x7fff);
        assertArrayEquals(expected, frame.get());
    }

    @Test
    public void cgbCorrectionClipsHardwareHighlights() {
        int[] rgb = new int[3];
        // NBA Jam '99's two transition colors: RGB555 (28, 27, 31), sum 86,
        // and (28, 31, 31), sum 90. Both are white on the physical LCD.
        new Display.GbcFrameReadyEvent(new int[]{0x7f7c, 0x7ffc, 0x6f3c})
                .toRgb(rgb, true);

        assertEquals(rgb[0], rgb[1]);
        // A highlight below the clipping threshold retains its chroma.
        assertNotEquals(rgb[1], rgb[2]);
    }

    @Test
    public void mementoRestoresPendingLcdEnableFrameAndCachedPicture() {
        Display display = new Display(true);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.pixels().clone()), Display.GbcFrameReadyEvent.class);
        display.init(eventBus);

        int[] expected = new int[PIXELS];
        java.util.Arrays.fill(expected, 0x1234);
        fillCgb(display, 0x1234);
        display.frameIsReady();
        display.disableLcd();
        display.enableLcd();
        var memento = display.saveToMemento();

        // Destroy both pieces of display state before restoring: blankFrame clears the
        // cached picture and cancels the pending first-frame repeat.
        display.blankFrame();
        display.restoreFromMemento(memento);
        fillCgb(display, 0x4321);
        display.frameIsReady();

        assertArrayEquals(expected, frame.get());
    }

    private static void fillDmg(Display display, int color) {
        for (int i = 0; i < PIXELS; i++) {
            display.putDmgPixel(color);
        }
    }

    private static void fillCgb(Display display, int color) {
        for (int i = 0; i < PIXELS; i++) {
            display.putColorPixel(color);
        }
    }
}
