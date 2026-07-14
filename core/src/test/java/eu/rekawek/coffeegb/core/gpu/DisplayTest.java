package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;

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
