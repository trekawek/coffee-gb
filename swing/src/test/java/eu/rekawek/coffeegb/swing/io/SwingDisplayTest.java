package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import org.junit.Test;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SwingDisplayTest {

    @Test
    public void paintingDoesNotAcquireTheComponentMonitor() throws Exception {
        int modifiers = SwingDisplay.class
                .getDeclaredMethod("paintComponent", Graphics.class)
                .getModifiers();
        assertFalse(Modifier.isSynchronized(modifiers));

        SwingDisplay display = newDisplay();
        BufferedImage target = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        Graphics graphics = target.getGraphics();
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
        Thread painter = daemonThread(() -> paint(display, graphics, painted, failure));
        holder.start();
        assertTrue(monitorHeld.await(2, TimeUnit.SECONDS));
        try {
            painter.start();
            assertTrue("painting blocked on the component monitor",
                    painted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseMonitor.countDown();
            holder.join(2_000);
            painter.join(2_000);
            graphics.dispose();
        }
        assertNull(failure.get());
    }

    @Test
    public void paintingUsesTheDedicatedRasterLock() throws Exception {
        Field lockField = SwingDisplay.class.getDeclaredField("rasterLock");
        assertTrue(Modifier.isPrivate(lockField.getModifiers()));
        assertTrue(Modifier.isFinal(lockField.getModifiers()));
        lockField.setAccessible(true);

        SwingDisplay display = newDisplay();
        Object rasterLock = lockField.get(display);
        BufferedImage target = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        Graphics graphics = target.getGraphics();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch painted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread painter = daemonThread(() -> {
            started.countDown();
            paint(display, graphics, painted, failure);
        });

        boolean blocked = false;
        synchronized (rasterLock) {
            painter.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (painted.getCount() != 0 && System.nanoTime() < deadline) {
                if (painter.getState() == Thread.State.BLOCKED) {
                    blocked = true;
                    break;
                }
                Thread.yield();
            }
            assertTrue("painting did not wait for the raster lock", blocked);
        }

        try {
            assertTrue(painted.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        } finally {
            painter.join(2_000);
            graphics.dispose();
        }
    }

    private static SwingDisplay newDisplay() {
        return new SwingDisplay(new EmulatorProperties().getDisplay(), EventBus.NULL_EVENT_BUS, "test");
    }

    private static Thread daemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    }

    private static void paint(SwingDisplay display, Graphics graphics, CountDownLatch painted,
                              AtomicReference<Throwable> failure) {
        try {
            display.paintComponent(graphics);
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            painted.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
