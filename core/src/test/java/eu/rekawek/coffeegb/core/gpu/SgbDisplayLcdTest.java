package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SgbDisplayLcdTest {

    private static final int PIXELS = Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT;

    @Test
    public void lcdReenableFrameIsBlankBeforeSgbColorization() throws IOException {
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        SgbDisplay sgbDisplay = new SgbDisplay(testRom(), sgbBus, true, false);
        sgbDisplay.init(eventBus);

        Display display = new Display(false);
        display.init(eventBus);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(
                event -> frame.set(event.buffer().clone()), SgbDisplay.SgbFrameReadyEvent.class);

        fill(display, 3);
        display.frameIsReady();
        int nonBlankColor = frame.get()[0];

        display.disableLcd();
        display.enableLcd();
        fill(display, 2);
        display.frameIsReady();

        int blankColor = frame.get()[0];
        assertNotEquals(nonBlankColor, blankColor);
        for (int pixel : frame.get()) {
            assertEquals(blankColor, pixel);
        }
    }

    private static Rom testRom() throws IOException {
        byte[] bytes = new byte[0x8000];
        bytes[0x147] = 0x00;
        return new Rom(bytes);
    }

    private static void fill(Display display, int color) {
        for (int i = 0; i < PIXELS; i++) {
            display.putDmgPixel(color);
        }
    }
}
