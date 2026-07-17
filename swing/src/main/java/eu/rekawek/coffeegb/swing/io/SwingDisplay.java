package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.controller.properties.DisplayProperties;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.TimeUnit;

import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_WIDTH;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;

public class SwingDisplay extends JPanel implements Runnable {

    private static final int NOTIFICATION_DURATION_MS = 1500;

    private final EventBus eventBus;

    private BufferedImage img;

    private int[] imgPixels;

    /** Guards replacement, upload, and painting of {@link #img}'s directly accessed raster. */
    private final Object rasterLock = new Object();

    private final int[] waitingFrame;

    private boolean isSgbBorder;

    private volatile boolean doStop;

    private volatile boolean isStopped;

    private boolean frameIsWaiting;

    private int scale;

    private boolean grayscale;

    private boolean blending;

    private boolean colorCorrection;

    private int rotation;

    private int[] previousFrame;

    private GameboyType gameboyType;

    // the rumble carts' motor (issue #93): while it runs, the picture is jiggled by a
    // pixel each frame - a dependency-free stand-in for a vibrating console
    private volatile boolean rumbling;

    private int rumblePhase;

    private volatile String notificationText;

    private volatile long notificationExpiresAt;

    public SwingDisplay(DisplayProperties properties, EventBus eventBus, String callerId) {
        super();
        this.eventBus = eventBus;
        createFrameImage(DISPLAY_WIDTH, DISPLAY_HEIGHT);
        waitingFrame = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
        eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class, callerId);
        eventBus.register(this::onGbcFrame, Display.GbcFrameReadyEvent.class, callerId);
        eventBus.register(this::onSgbFrame, SgbDisplay.SgbFrameReadyEvent.class, callerId);
        eventBus.register(this::onGameboyType, Controller.GameboyTypeEvent.class, callerId);
        eventBus.register(e -> setScale(e.scale), SetScaleEvent.class);
        eventBus.register(e -> this.grayscale = e.grayscale, SetGrayscaleEvent.class);
        eventBus.register(e -> setBlending(e.blending), SetBlendingEvent.class);
        eventBus.register(e -> setColorCorrection(e.colorCorrection), SetColorCorrectionEvent.class);
        eventBus.register(e -> setRotation(e.rotation), SetRotationEvent.class);
        eventBus.register(e -> this.rumbling = e.on(), eu.rekawek.coffeegb.core.memory.cart.type.Mbc5.RumbleEvent.class, callerId);
        eventBus.register(e -> showNotification("State saved (slot " + e.getSlot() + ")"),
                Controller.SnapshotSavedEvent.class, callerId);
        eventBus.register(e -> showNotification("State loaded (slot " + e.getSlot() + ")"),
                Controller.SnapshotRestoredEvent.class, callerId);
        this.grayscale = properties.getGrayscale();
        this.rotation = normalizeRotation(properties.getRotation());
        setBlending(properties.getBlending());
        setColorCorrection(properties.getColorCorrection());
        setScale(properties.getScale());
    }

    private synchronized void onGameboyType(Controller.GameboyTypeEvent e) {
        this.gameboyType = e.getGameboyType();
    }

    private synchronized void onGbcFrame(Display.GbcFrameReadyEvent e) {
        e.toRgb(waitingFrame, colorCorrection);
        setBorder(false);
        frameQueued();
    }

    private synchronized void onDmgFrame(Display.DmgFrameReadyEvent e) {
        if (gameboyType == GameboyType.SGB) {
            return;
        }
        e.toRgb(waitingFrame, grayscale);
        setBorder(false);
        frameQueued();
    }

    private synchronized void onSgbFrame(SgbDisplay.SgbFrameReadyEvent e) {
        e.toRgb(waitingFrame, grayscale);
        setBorder(e.includeBorder());
        frameQueued();
    }

    /**
     * Keep the newest panel state while the display thread has not uploaded the pending
     * frame yet. In particular, LCD-off can replace a just-finished partial scanout before
     * Swing paints it instead of leaving that stale transition visible for a host frame.
     */
    private void frameQueued() {
        if (!frameIsWaiting) {
            frameIsWaiting = true;
            notify();
        }
    }

    private void setBorder(boolean isSgbBorder) {
        if (isSgbBorder != this.isSgbBorder) {
            this.isSgbBorder = isSgbBorder;
            createFrameImage(getDisplayWidth(), getDisplayHeight());
            setScale(scale);
        }
    }

    /**
     * The image raster is written directly (setRGB would convert every pixel through the
     * color model); TYPE_INT_RGB matches the int-packed RGB produced by the emulator.
     */
    private void createFrameImage(int width, int height) {
        synchronized (rasterLock) {
            img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            imgPixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        }
    }

    private synchronized void setScale(int scale) {
        this.scale = scale;
        setPreferredSize(rotatedPreferredSize());
        eventBus.post(new DisplaySizeUpdatedEvent(getPreferredSize()));
    }

    private synchronized void setRotation(int degrees) {
        this.rotation = normalizeRotation(degrees);
        setPreferredSize(rotatedPreferredSize());
        eventBus.post(new DisplaySizeUpdatedEvent(getPreferredSize()));
        repaint();
    }

    private static int normalizeRotation(int degrees) {
        int r = ((degrees % 360) + 360) % 360;
        // snap to the four quarter turns the display supports
        return (r / 90) * 90;
    }

    private Dimension rotatedPreferredSize() {
        int w = getDisplayWidth() * scale;
        int h = getDisplayHeight() * scale;
        return (rotation == 90 || rotation == 270) ? new Dimension(h, w) : new Dimension(w, h);
    }

    // run() uploads directly into img's raster. The EDT uses the dedicated raster lock while
    // scanning it, or alternating frames can be shown with a horizontal boundary between the
    // old and new pictures (F-1 Race, issue #147). Do not use the component monitor here:
    // Swing paints children while holding the AWT tree lock, and border resizing takes that
    // lock while handling a synchronized frame callback.
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        int localScale = scale;
        BufferedImage localImg;
        synchronized (rasterLock) {
            localImg = img;
        }
        int w = localImg.getWidth() * localScale;
        int h = localImg.getHeight() * localScale;
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        if (rumbling) {
            rumblePhase++;
            g2d.translate((rumblePhase & 2) == 0 ? 1 : -1, (rumblePhase & 1) == 0 ? 1 : -1);
        }
        switch (rotation) {
            case 90 -> {
                g2d.translate(h, 0);
                g2d.rotate(Math.PI / 2);
            }
            case 180 -> {
                g2d.translate(w, h);
                g2d.rotate(Math.PI);
            }
            case 270 -> {
                g2d.translate(0, w);
                g2d.rotate(3 * Math.PI / 2);
            }
            default -> {
            }
        }
        synchronized (rasterLock) {
            g2d.drawImage(localImg, 0, 0, w, h, null);
        }
        paintNotification(g2d, w, h, localScale);
        g2d.dispose();
    }

    private void showNotification(String text) {
        notificationText = text;
        notificationExpiresAt = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(NOTIFICATION_DURATION_MS);
        SwingUtilities.invokeLater(() -> {
            repaint();
            Timer timer = new Timer(NOTIFICATION_DURATION_MS, e -> repaint());
            timer.setRepeats(false);
            timer.start();
        });
    }

    private void paintNotification(Graphics2D g, int width, int height, int localScale) {
        String text = notificationText;
        if (text == null || System.nanoTime() >= notificationExpiresAt) {
            return;
        }

        int fontSize = Math.max(12, 7 * localScale);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        int paddingX = Math.max(6, 4 * localScale);
        int paddingY = Math.max(4, 2 * localScale);
        int boxWidth = metrics.stringWidth(text) + 2 * paddingX;
        int boxHeight = metrics.getHeight() + 2 * paddingY;
        int x = (width - boxWidth) / 2;
        int y = height - boxHeight - Math.max(4, 4 * localScale);
        int arc = Math.max(6, 4 * localScale);

        g.setColor(new Color(0, 0, 0, 190));
        g.fillRoundRect(x, y, boxWidth, boxHeight, arc, arc);
        g.setColor(Color.WHITE);
        g.drawString(text, x + paddingX, y + paddingY + metrics.getAscent());
    }

    private int getDisplayWidth() {
        return isSgbBorder ? SGB_DISPLAY_WIDTH : DISPLAY_WIDTH;
    }

    private int getDisplayHeight() {
        return isSgbBorder ? SGB_DISPLAY_HEIGHT : DISPLAY_HEIGHT;
    }

    @Override
    public void run() {
        doStop = false;
        isStopped = false;
        frameIsWaiting = false;

        while (!doStop) {
            synchronized (this) {
                if (frameIsWaiting) {
                    if (blending) {
                        blendWithPreviousFrame();
                    }
                    synchronized (rasterLock) {
                        System.arraycopy(waitingFrame, 0, imgPixels, 0,
                                getDisplayWidth() * getDisplayHeight());
                    }
                    repaint();
                    frameIsWaiting = false;
                } else {
                    try {
                        wait(10);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        isStopped = true;
        synchronized (this) {
            notifyAll();
        }
    }

    public void stop() {
        doStop = true;
        synchronized (this) {
            while (!isStopped) {
                try {
                    wait(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private synchronized void setColorCorrection(boolean colorCorrection) {
        this.colorCorrection = colorCorrection;
    }

    private synchronized void setBlending(boolean blending) {
        this.blending = blending;
        previousFrame = null;
    }

    /**
     * Approximates the ghosting of the original LCD by averaging with the previous frame;
     * games flickering sprites at 30 Hz (like Chikyuu Kaihou Gun ZAS) rely on it.
     */
    private void blendWithPreviousFrame() {
        int size = getDisplayWidth() * getDisplayHeight();
        if (previousFrame == null || previousFrame.length != size) {
            previousFrame = new int[size];
            System.arraycopy(waitingFrame, 0, previousFrame, 0, size);
            return;
        }
        for (int i = 0; i < size; i++) {
            int a = waitingFrame[i];
            int b = previousFrame[i];
            previousFrame[i] = a;
            waitingFrame[i] = (((a ^ b) & 0xfefefe) >> 1) + (a & b);
        }
    }

    public record SetScaleEvent(int scale) implements Event {
    }

    public record SetGrayscaleEvent(boolean grayscale) implements Event {
    }

    public record SetColorCorrectionEvent(boolean colorCorrection) implements Event {
    }

    public record SetRotationEvent(int rotation) implements Event {
    }

    public record SetBlendingEvent(boolean blending) implements Event {
    }

    public record DisplaySizeUpdatedEvent(Dimension preferredSize) implements Event {
    }
}
