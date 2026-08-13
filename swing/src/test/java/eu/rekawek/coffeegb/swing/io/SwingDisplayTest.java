package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwingDisplayTest {

    @Test
    public void constructionRequiresTheEventDispatchThread() {
        assertFalse(SwingUtilities.isEventDispatchThread());
        assertThrows(
                IllegalStateException.class,
                () -> new SwingDisplay(
                        new EmulatorProperties().getDisplay(),
                        EventBus.NULL_EVENT_BUS,
                        "test"));
    }

    @Test
    public void romLoadingShowsPersistentNotificationUntilLoadingFinishes() throws Exception {
        EventBusImpl root = new EventBusImpl(null, null, false);
        EventBus session = root.fork("test");
        SwingDisplay display = newDisplay(root);
        Field textField = SwingDisplay.class.getDeclaredField("persistentNotificationText");
        textField.setAccessible(true);

        session.post(new Controller.RomLoadingEvent(new File("next.gb")));
        assertEquals("Loading…", textField.get(display));

        session.post(new Controller.EmulationStartedEvent("NEXT"));
        assertNull(textField.get(display));

        session.post(new Controller.RomLoadingEvent(new File("broken.gb")));
        session.post(new Controller.LoadRomFailedEvent(new File("broken.gb"), "broken"));
        assertNull(textField.get(display));

        session.post(new Controller.RomLoadingEvent(new File("cancelled.gb")));
        session.post(new Controller.RomLoadingCancelledEvent(new File("cancelled.gb")));
        assertNull(textField.get(display));
        root.close();
    }

    @Test
    public void linkedControllerOwnerStartClearsPersistentLoadingNotification() throws Exception {
        EventBusImpl root = new EventBusImpl(null, null, false);
        EventBus linkedController = root.fork("session");
        SwingDisplay display = newDisplay(root, "main");
        Field textField = SwingDisplay.class.getDeclaredField("persistentNotificationText");
        textField.setAccessible(true);

        linkedController.post(new Controller.RomLoadingEvent(new File("linked.gb")));
        assertEquals("Loading…", textField.get(display));

        linkedController.post(new Controller.EmulationStartedEvent("LINKED"));
        assertNull(textField.get(display));
        root.close();
    }

    @Test
    public void snapshotCompletionEventsShowAnOnScreenNotification() throws Exception {
        EventBusImpl root = new EventBusImpl(null, null, false);
        EventBus session = root.fork("test");
        SwingDisplay display = newDisplay(root);
        Field textField = SwingDisplay.class.getDeclaredField("notificationText");
        textField.setAccessible(true);

        session.post(new Controller.SnapshotSavedEvent(3));
        assertEquals("State saved (slot 3)", textField.get(display));

        BufferedImage target = paintAtPreferredSize(display);
        boolean hasVisibleText = false;
        for (int y = 0; y < target.getHeight() && !hasVisibleText; y++) {
            for (int x = 0; x < target.getWidth(); x++) {
                if ((target.getRGB(x, y) & 0xffffff) != 0) {
                    hasVisibleText = true;
                    break;
                }
            }
        }
        assertTrue("notification was not painted over the game image", hasVisibleText);

        session.post(new Controller.SnapshotRestoredEvent(7));
        assertEquals("State loaded (slot 7)", textField.get(display));
        root.close();
    }

    @Test
    public void ownerAndSessionLifecycleResetHostRumbleWithoutCoreTeardownEvent() throws Exception {
        EventBusImpl root = new EventBusImpl(null, null, false);
        EventBus session = root.fork("test");
        SwingDisplay display = newDisplay(root);
        Field rumbling = SwingDisplay.class.getDeclaredField("rumbling");
        rumbling.setAccessible(true);

        session.post(new RumbleEvent(true));
        assertTrue(rumbling.getBoolean(display));
        display.releaseForLifecycleChange();
        assertFalse(rumbling.getBoolean(display));

        session.post(new RumbleEvent(true));
        session.post(new Controller.RomLoadingEvent(new File("next.gb")));
        assertFalse(rumbling.getBoolean(display));

        session.post(new RumbleEvent(true));
        session.post(new Controller.EmulationStoppedEvent());
        assertFalse(rumbling.getBoolean(display));
        root.close();
    }

    @Test
    public void newestFrameReplacesPendingTransitionFrame() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        int[] transition = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        Arrays.fill(transition, 3);
        int[] blank = new int[transition.length];
        int[] expectedBlank = dmgRgb(blank);

        eventBus.post(new Display.DmgFrameReadyEvent(transition));
        eventBus.post(new Display.DmgFrameReadyEvent(blank));
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            awaitDisplayedFrame(display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, expectedBlank);
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void queuedFrameDoesNotRetainTheEmulatorOwnedBuffer() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        int[] emulatorOwned = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        Arrays.fill(emulatorOwned, 3);
        int[] expected = dmgRgb(emulatorOwned);

        eventBus.post(new Display.DmgFrameReadyEvent(emulatorOwned));
        Arrays.fill(emulatorOwned, 0);
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            awaitDisplayedFrame(display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, expected);
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void publishedFrameRemainsImmutableAcrossLaterFramesAndSourceMutation() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        eventBus.post(new SwingDisplay.SetBlendingEvent(false));
        int size = Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT;
        int[] firstSource = new int[size];
        Arrays.fill(firstSource, 3);
        int[] expectedFirst = dmgRgb(firstSource);
        int[] secondSource = new int[size];
        Arrays.fill(secondSource, 2);
        int[] expectedSecond = dmgRgb(secondSource);
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            eventBus.post(new Display.DmgFrameReadyEvent(firstSource));
            DisplayFrameSnapshot first = awaitDisplayedFrame(
                    display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, expectedFirst);
            Arrays.fill(firstSource, 0);

            eventBus.post(new Display.DmgFrameReadyEvent(secondSource));
            DisplayFrameSnapshot second = awaitDisplayedFrame(
                    display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, expectedSecond);
            Arrays.fill(secondSource, 0);

            assertArrayEquals(expectedFirst, first.copyRgb());
            assertArrayEquals(expectedSecond, second.copyRgb());
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void cgbFrameIsTranslatedAndCopiedBeforePublication() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        int[] emulatorOwned = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        Arrays.fill(emulatorOwned, 0x4210);
        int[] expected = gbcRgb(emulatorOwned);

        eventBus.post(new Display.GbcFrameReadyEvent(emulatorOwned));
        Arrays.fill(emulatorOwned, 0);
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            awaitDisplayedFrame(display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, expected);
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void lcdBlankDoesNotBlendWithTransitionFrame() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        eventBus.post(new SwingDisplay.SetBlendingEvent(true));
        Thread displayThread = daemonThread(display);
        displayThread.start();

        int size = Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT;
        int[] scene = new int[size];
        Arrays.fill(scene, 3);
        int[] transition = new int[size];
        transition[0] = 3;
        int[] blank = new int[size];
        int[] expectedBlank = dmgRgb(blank);

        Field previousFrameField = SwingDisplay.class.getDeclaredField("previousFrame");
        previousFrameField.setAccessible(true);
        try {
            eventBus.post(new Display.DmgFrameReadyEvent(scene));
            awaitArray(previousFrameField, display, dmgRgb(scene));

            eventBus.post(new Display.DmgFrameReadyEvent(transition));
            awaitArray(previousFrameField, display, dmgRgb(transition));

            eventBus.post(new Display.DmgFrameReadyEvent(blank, true));
            awaitArray(previousFrameField, display, expectedBlank);
            awaitDisplayedFrame(
                    display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, expectedBlank);
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void sgbFrameDimensionsArePublishedIndependentlyFromTheComponentSize() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        int[] border = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
        Arrays.fill(border, 0x123456);
        int[] expected = border.clone();

        onEdt(() -> display.setSize(777, 555));
        eventBus.post(new SgbDisplay.SgbFrameReadyEvent(border, true));
        Arrays.fill(border, 0);
        flushEdt();
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            DisplayFrameSnapshot snapshot =
                    awaitDisplayedFrame(display, SGB_DISPLAY_WIDTH, SGB_DISPLAY_HEIGHT, expected);
            assertEquals(0x123456, snapshot.rgbAt(SGB_DISPLAY_WIDTH - 1, SGB_DISPLAY_HEIGHT - 1));
            assertEquals(new Dimension(512, 448), onEdt(display::getPreferredSize));
            assertEquals(new Dimension(777, 555), onEdt(() -> {
                return display.getSize();
            }));
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void pauseCaptureUsesThePublishedFrameWithThePlayerRotation() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        int[] frame = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        for (int index = 0; index < frame.length; index++) {
            frame[index] = index & 3;
        }
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            eventBus.post(new Display.DmgFrameReadyEvent(frame));
            DisplayFrameSnapshot published = awaitDisplayedFrame(display, Display.DISPLAY_WIDTH,
                    Display.DISPLAY_HEIGHT, dmgRgb(frame));

            eventBus.post(new SwingDisplay.SetRotationEvent(90));
            MenuPreview preview = display.captureMenuPreview();

            assertEquals(MenuPreview.State.READY, preview.state());
            assertEquals(Display.DISPLAY_HEIGHT, preview.width());
            assertEquals(Display.DISPLAY_WIDTH, preview.height());
            int[] pixels = preview.copyPixels();
            assertEquals(0xff000000 | published.rgbAt(0, Display.DISPLAY_HEIGHT - 1), pixels[0]);
            assertEquals(0xff000000 | published.rgbAt(Display.DISPLAY_WIDTH - 1, 0),
                    pixels[(Display.DISPLAY_WIDTH - 1) * Display.DISPLAY_HEIGHT
                            + Display.DISPLAY_HEIGHT - 1]);
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void pauseCaptureAcceptsTheFullBoundedSgbFrame() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        int[] border = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
        Arrays.fill(border, 0x123456);
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            eventBus.post(new SgbDisplay.SgbFrameReadyEvent(border, true));
            awaitDisplayedFrame(display, SGB_DISPLAY_WIDTH, SGB_DISPLAY_HEIGHT, border);

            MenuPreview preview = display.captureMenuPreview();

            assertEquals(MenuPreview.State.READY, preview.state());
            assertEquals(SGB_DISPLAY_WIDTH, preview.width());
            assertEquals(SGB_DISPLAY_HEIGHT, preview.height());
            assertEquals(0xff123456,
                    preview.copyPixels()[preview.width() * preview.height() - 1]);
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void lifecycleInvalidationDropsAQueuedOldSessionFrameBeforeItCanBecomeAPausePreview()
            throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        int[] oldFrame = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        Arrays.fill(oldFrame, 3);
        int[] newFrame = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        Arrays.fill(newFrame, 2);

        eventBus.post(new Display.DmgFrameReadyEvent(oldFrame));
        display.releaseForLifecycleChange();
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            assertEquals(MenuPreview.State.EMPTY, display.captureMenuPreview().state());

            eventBus.post(new Display.DmgFrameReadyEvent(newFrame));
            awaitDisplayedFrame(display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT,
                    dmgRgb(newFrame));
            assertEquals(MenuPreview.State.READY, display.captureMenuPreview().state());
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void displaySizeUpdatesAreAppliedAndPublishedOnTheEdt() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        AtomicBoolean allEventsOnEdt = new AtomicBoolean(true);
        AtomicInteger eventCount = new AtomicInteger();
        AtomicReference<Dimension> latestSize = new AtomicReference<>();
        eventBus.register(event -> {
            allEventsOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread());
            eventCount.incrementAndGet();
            latestSize.set(event.preferredSize());
        }, SwingDisplay.DisplaySizeUpdatedEvent.class);

        eventBus.post(new SwingDisplay.SetScaleModeEvent(DisplayScaleMode.EXPLICIT_3X));
        flushEdt();
        assertEquals(new Dimension(480, 432), latestSize.get());
        assertEquals(new Dimension(480, 432), onEdt(display::getPreferredSize));

        eventBus.post(new SwingDisplay.SetRotationEvent(90));
        flushEdt();
        assertEquals(new Dimension(432, 480), latestSize.get());
        assertEquals(new Dimension(432, 480), onEdt(display::getPreferredSize));

        // Reapplying settings for a fullscreen-only change must not repack and overwrite the
        // restored window bounds.
        eventBus.post(new SwingDisplay.SetScaleModeEvent(DisplayScaleMode.EXPLICIT_3X));
        eventBus.post(new SwingDisplay.SetRotationEvent(90));
        flushEdt();
        assertEquals(2, eventCount.get());

        // A user-invoked window-size command must publish even when its scale is already active.
        eventBus.post(new SwingDisplay.SetScaleModeEvent(DisplayScaleMode.EXPLICIT_3X, true));
        flushEdt();
        assertEquals(3, eventCount.get());
        assertEquals(new Dimension(432, 480), latestSize.get());
        assertTrue(allEventsOnEdt.get());
        eventBus.close();
    }

    @Test
    public void displaySizeEventDefensivelyCopiesItsDimension() {
        Dimension original = new Dimension(320, 288);
        SwingDisplay.DisplaySizeUpdatedEvent event =
                new SwingDisplay.DisplaySizeUpdatedEvent(original);
        original.setSize(1, 1);
        Dimension received = event.preferredSize();
        received.setSize(2, 2);

        assertEquals(new Dimension(320, 288), event.preferredSize());
    }

    @Test
    public void paintingUsesViewportLetterboxingAndTheConfiguredNeutralColor() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "test", false);
        SwingDisplay display = newDisplay(eventBus);
        Color letterbox = new Color(12, 34, 56);
        int[] frame = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        int[] expected = dmgRgb(frame);
        AtomicBoolean backgroundChangedOnEdt = new AtomicBoolean();
        onEdt(() -> display.addPropertyChangeListener(
                "background",
                event -> backgroundChangedOnEdt.set(SwingUtilities.isEventDispatchThread())));
        Thread displayThread = daemonThread(display);
        displayThread.start();
        try {
            eventBus.post(new SwingDisplay.SetLetterboxColorEvent(letterbox));
            eventBus.post(new SwingDisplay.SetScaleModeEvent(DisplayScaleMode.ASPECT_FIT));
            eventBus.post(new Display.DmgFrameReadyEvent(frame));
            awaitDisplayedFrame(display, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, expected);
            flushEdt();

            BufferedImage target = onEdt(() -> {
                display.setSize(500, 300);
                BufferedImage image =
                        new BufferedImage(500, 300, BufferedImage.TYPE_INT_RGB);
                Graphics graphics = image.getGraphics();
                try {
                    display.paintComponent(graphics);
                } finally {
                    graphics.dispose();
                }
                return image;
            });

            assertEquals(letterbox.getRGB() & 0xffffff, target.getRGB(0, 150) & 0xffffff);
            assertEquals(expected[0], target.getRGB(250, 150) & 0xffffff);
            assertTrue(backgroundChangedOnEdt.get());
        } finally {
            display.stop();
            displayThread.join(2_000);
            eventBus.close();
        }
    }

    @Test
    public void paintingRequiresTheEventDispatchThread() throws Exception {
        SwingDisplay display = newDisplay();
        BufferedImage target = new BufferedImage(320, 288, BufferedImage.TYPE_INT_RGB);
        Graphics graphics = target.getGraphics();
        try {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> display.paintComponent(graphics));
            assertTrue(error.getMessage().contains("Event Dispatch Thread"));
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void paintingDoesNotAcquireTheComponentMonitor() throws Exception {
        int modifiers = SwingDisplay.class
                .getDeclaredMethod("paintComponent", Graphics.class)
                .getModifiers();
        assertFalse(Modifier.isSynchronized(modifiers));

        SwingDisplay display = newDisplay();
        onEdt(() -> display.setSize(display.getPreferredSize()));
        BufferedImage target = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        CountDownLatch painted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread holder = daemonThread(() -> {
            synchronized (display) {
                monitorHeld.countDown();
                await(releaseMonitor);
            }
        });
        Thread scheduler = daemonThread(() -> {
            try {
                onEdt(() -> {
                    Graphics graphics = target.getGraphics();
                    try {
                        display.paintComponent(graphics);
                    } finally {
                        graphics.dispose();
                    }
                });
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                painted.countDown();
            }
        });
        holder.start();
        assertTrue(monitorHeld.await(2, TimeUnit.SECONDS));
        try {
            scheduler.start();
            assertTrue("painting blocked on the component monitor",
                    painted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseMonitor.countDown();
            holder.join(2_000);
            scheduler.join(2_000);
        }
        assertNull(failure.get());
    }

    private static SwingDisplay newDisplay() throws Exception {
        return newDisplay(EventBus.NULL_EVENT_BUS);
    }

    private static SwingDisplay newDisplay(EventBus eventBus) throws Exception {
        return newDisplay(eventBus, "test");
    }

    private static SwingDisplay newDisplay(EventBus eventBus, String callerId) throws Exception {
        return onEdt(() -> new SwingDisplay(
                new EmulatorProperties().getDisplay(), eventBus, callerId));
    }

    private static BufferedImage paintAtPreferredSize(SwingDisplay display) throws Exception {
        return onEdt(() -> {
            display.setSize(display.getPreferredSize());
            BufferedImage target = new BufferedImage(
                    display.getWidth(), display.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics graphics = target.getGraphics();
            try {
                display.paintComponent(graphics);
            } finally {
                graphics.dispose();
            }
            return target;
        });
    }

    private static Thread daemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    }

    private static int[] dmgRgb(int[] pixels) {
        int[] rgb = new int[pixels.length];
        new Display.DmgFrameReadyEvent(pixels).toRgb(
                rgb, new EmulatorProperties().getDisplay().getGrayscale());
        return rgb;
    }

    private static int[] gbcRgb(int[] pixels) {
        int[] rgb = new int[pixels.length];
        new Display.GbcFrameReadyEvent(pixels).toRgb(
                rgb, new EmulatorProperties().getDisplay().getColorCorrection());
        return rgb;
    }

    private static DisplayFrameSnapshot awaitDisplayedFrame(
            SwingDisplay display, int width, int height, int[] expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            DisplayFrameSnapshot actual = display.displayedFrame();
            if (actual.width() == width
                    && actual.height() == height
                    && frameEquals(actual, expected)) {
                return actual;
            }
            Thread.yield();
        }
        fail("display thread did not publish the expected frame");
        throw new AssertionError();
    }

    private static boolean frameEquals(DisplayFrameSnapshot actual, int[] expected) {
        if (expected.length != actual.width() * actual.height()) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            int x = i % actual.width();
            int y = i / actual.width();
            if (actual.rgbAt(x, y) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static void awaitArray(Field field, Object target, int[] expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            synchronized (target) {
                int[] actual = (int[]) field.get(target);
                if (actual != null && Arrays.equals(expected, actual)) {
                    return;
                }
            }
            Thread.yield();
        }
        fail("display thread did not retain the expected blend history");
    }

    private static void flushEdt() throws Exception {
        onEdt(() -> {
        });
    }

    private static void onEdt(ThrowingRunnable runnable) throws Exception {
        onEdt(() -> {
            runnable.run();
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> callable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return callable.call();
        }
        FutureTask<T> task = new FutureTask<>(callable);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
