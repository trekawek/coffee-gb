package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.swing.translation.TranslationRegion;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwingTranslationOverlayTest {

    @Test
    public void translatedBoxesFollowRotationScalingAndLetterboxingForDmgAndSgbFrames() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        TranslationRegion region = new TranslationRegion("Meet me at the harbor.", 20, 16, 60, 24);
        try {
            for (int[] dimensions : new int[][]{{160, 144}, {256, 224}}) {
                BufferedImage source = solidImage(dimensions[0], dimensions[1], Color.MAGENTA);
                for (int rotation : new int[]{0, 90, 180, 270}) {
                    eventBus.post(new SwingDisplay.SetRotationEvent(rotation));
                    onEdt(() -> {
                        display.setTranslationOverlay(source, List.of(), "English · Esc to return");
                        return null;
                    });
                    BufferedImage original = paint(display, 521, 409);
                    onEdt(() -> {
                        display.setTranslationOverlay(source, List.of(region), "English · Esc to return");
                        return null;
                    });
                    BufferedImage translated = paint(display, 521, 409);
                    DisplayViewport viewport = DisplayViewport.calculate(
                            521, 409 - 22, dimensions[0], dimensions[1], rotation, display.getScaleMode());
                    AffineTransform transform = viewport.sourceToComponentTransform();
                    Rectangle expected = transform.createTransformedShape(
                            new Rectangle(region.x(), region.y(), region.width(), region.height())).getBounds();
                    // Permit the single antialiased edge pixel at a fractional viewport boundary.
                    expected.grow(1, 1);
                    int changed = 0;
                    for (int y = 0; y < translated.getHeight(); y++) {
                        for (int x = 0; x < translated.getWidth(); x++) {
                            if (translated.getRGB(x, y) != original.getRGB(x, y)) {
                                changed++;
                                assertTrue("translation escaped its transformed box", expected.contains(x, y));
                            }
                        }
                    }
                    assertTrue("translation box was not painted", changed > 100);
                    assertEquals("letterbox was painted over", original.getRGB(0, 0), translated.getRGB(0, 0));
                }
            }
        } finally {
            eventBus.close();
        }
    }

    @Test
    public void longTranslationsAndOffscreenBoxesCannotPaintOutsideTheirClippedRegions() {
        BufferedImage source = solidImage(160, 144, Color.MAGENTA);
        SwingTranslationOverlay overlay = new SwingTranslationOverlay(source, List.of(
                new TranslationRegion("A tremendouslylongunbrokenword ".repeat(100), -4, 8, 39, 17),
                new TranslationRegion("Continue your journey", 151, 133, 50, 30),
                new TranslationRegion("Offscreen", Integer.MAX_VALUE, 1, 200, 20)), null);
        BufferedImage target = solidImage(180, 160, Color.CYAN);
        Graphics2D graphics = target.createGraphics();
        try {
            overlay.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Rectangle first = new Rectangle(0, 8, 35, 17);
        Rectangle second = new Rectangle(151, 133, 9, 11);
        int textPixels = 0;
        for (int y = 0; y < target.getHeight(); y++) {
            for (int x = 0; x < target.getWidth(); x++) {
                int color = target.getRGB(x, y);
                if (x >= 160 || y >= 144) {
                    assertEquals("overlay painted outside source dimensions", Color.CYAN.getRGB(), color);
                } else if (!first.contains(x, y) && !second.contains(x, y)) {
                    assertEquals("text escaped its source box", Color.MAGENTA.getRGB(), color);
                } else if ((color & 0xff) > 80 && color != Color.MAGENTA.getRGB()) {
                    textPixels++;
                }
            }
        }
        assertTrue("long translation became entirely invisible", textPixels > 0);
    }

    @Test
    public void overlayCopiesCallerOwnedScreenshotAndRegionList() {
        BufferedImage source = solidImage(160, 144, Color.MAGENTA);
        List<TranslationRegion> regions = new ArrayList<>();
        SwingTranslationOverlay overlay = new SwingTranslationOverlay(source, regions, null);
        source.setRGB(0, 0, Color.YELLOW.getRGB());
        regions.add(new TranslationRegion("New text", 0, 0, 160, 144));
        BufferedImage target = solidImage(160, 144, Color.CYAN);
        Graphics2D graphics = target.createGraphics();
        try {
            overlay.paint(graphics);
        } finally {
            graphics.dispose();
        }
        assertEquals(Color.MAGENTA.getRGB(), target.getRGB(0, 0));
        assertEquals(Color.MAGENTA.getRGB(), target.getRGB(80, 72));
    }

    @Test
    public void screenshotsExcludeOverlaysAndRemainNativeWhilePresentationIsFrozen() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        eventBus.post(new SwingDisplay.SetBlendingEvent(false));
        eventBus.post(new SwingDisplay.SetRotationEvent(90));
        assertNull(display.captureTranslationFrame());
        Thread worker = new Thread(display, "translation-display-test");
        worker.setDaemon(true);
        worker.start();
        try {
            publishDmgFrame(eventBus, display, 3);
            BufferedImage source = display.captureTranslationFrame();
            assertEquals(160, source.getWidth());
            assertEquals(144, source.getHeight());
            int original = source.getRGB(0, 0);
            onEdt(() -> {
                display.setTranslationOverlay(source,
                        List.of(new TranslationRegion("Welcome!", 10, 10, 100, 24)), "Translated");
                return null;
            });
            BufferedImage frozen = paint(display, 450, 390);
            publishDmgFrame(eventBus, display, 0);
            eventBus.post(new Controller.SnapshotSavedEvent(1));
            BufferedImage next = display.captureTranslationFrame();
            assertNotEquals("capture returned frozen presentation", original, next.getRGB(0, 0));
            assertEquals("capture included a host overlay", next.getRGB(0, 0), next.getRGB(10, 10));
            next.setRGB(0, 0, Color.RED.getRGB());
            assertNotEquals(Color.RED.getRGB(), display.captureTranslationFrame().getRGB(0, 0));
            BufferedImage stillFrozen = paint(display, 450, 390);
            assertEquals("new emulation frame replaced the frozen screenshot", frozen.getRGB(200, 60),
                    stillFrozen.getRGB(200, 60));
            assertEquals(original, source.getRGB(0, 0));

            display.releaseForLifecycleChange();
            assertFalse(display.hasTranslationOverlay());
            assertNull(display.captureTranslationFrame());
        } finally {
            display.stop();
            worker.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void overlayMutationsRequireTheEventDispatchThread() throws Exception {
        SwingDisplay display = newDisplay(EventBus.NULL_EVENT_BUS);
        BufferedImage frame = solidImage(160, 144, Color.MAGENTA);
        assertThrows(IllegalStateException.class,
                () -> display.setTranslationOverlay(frame, List.of(), "Translating…"));
        assertThrows(IllegalStateException.class, display::clearTranslationOverlay);
        onEdt(() -> {
            display.setTranslationOverlay(frame, List.of(), "Translating…");
            assertTrue(display.hasTranslationOverlay());
            display.clearTranslationOverlay();
            assertFalse(display.hasTranslationOverlay());
            return null;
        });
    }

    private static SwingDisplay newDisplay(EventBus bus) throws Exception {
        return onEdt(() -> new SwingDisplay(new EmulatorProperties().getDisplay(), bus, "test"));
    }

    private static void publishDmgFrame(EventBus bus, SwingDisplay display, int shade) {
        int[] pixels = new int[160 * 144];
        Arrays.fill(pixels, shade);
        int[] expected = new int[pixels.length];
        Display.DmgFrameReadyEvent event = new Display.DmgFrameReadyEvent(pixels);
        event.toRgb(expected, new EmulatorProperties().getDisplay().getGrayscale());
        bus.post(event);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            BufferedImage captured = display.captureTranslationFrame();
            if (captured != null && (captured.getRGB(0, 0) & 0xffffff) == expected[0]) {
                return;
            }
            Thread.yield();
        }
        fail("display did not publish the expected frame");
    }

    private static BufferedImage paint(SwingDisplay display, int width, int height) throws Exception {
        return onEdt(() -> {
            display.setSize(width, height);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                display.paintComponent(graphics);
            } finally {
                graphics.dispose();
            }
            return image;
        });
    }

    private static BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
