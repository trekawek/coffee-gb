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

import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_WIDTH;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;

public class SwingDisplay extends JPanel implements Runnable {

    private final EventBus eventBus;

    private BufferedImage img;

    private int[] imgPixels;

    private final int[] waitingFrame;

    private boolean isSgbBorder;

    private volatile boolean doStop;

    private volatile boolean isStopped;

    private boolean frameIsWaiting;

    private int scale;

    private boolean grayscale;

    private boolean blending;

    private boolean colorCorrection;

    private int[] previousFrame;

    private GameboyType gameboyType;

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
        this.grayscale = properties.getGrayscale();
        setBlending(properties.getBlending());
        setColorCorrection(properties.getColorCorrection());
        setScale(properties.getScale());
    }

    private synchronized void onGameboyType(Controller.GameboyTypeEvent e) {
        this.gameboyType = e.getGameboyType();
    }

    private synchronized void onGbcFrame(Display.GbcFrameReadyEvent e) {
        if (frameIsWaiting) {
            return;
        }
        frameIsWaiting = true;
        e.toRgb(waitingFrame, colorCorrection);
        setBorder(false);
        notify();
    }

    private synchronized void onDmgFrame(Display.DmgFrameReadyEvent e) {
        if (frameIsWaiting || gameboyType == GameboyType.SGB) {
            return;
        }
        frameIsWaiting = true;
        e.toRgb(waitingFrame, grayscale);
        setBorder(false);
        notify();
    }

    private synchronized void onSgbFrame(SgbDisplay.SgbFrameReadyEvent e) {
        if (frameIsWaiting) {
            return;
        }
        frameIsWaiting = true;
        e.toRgb(waitingFrame, grayscale);
        setBorder(e.includeBorder());
        notify();
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
        img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        imgPixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private synchronized void setScale(int scale) {
        this.scale = scale;
        setPreferredSize(new Dimension(getDisplayWidth() * scale, getDisplayHeight() * scale));
        eventBus.post(new DisplaySizeUpdatedEvent(getPreferredSize()));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        int localScale = scale;
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.drawImage(img, 0, 0, getDisplayWidth() * localScale, getDisplayHeight() * localScale, null);
        g2d.dispose();
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
                    System.arraycopy(waitingFrame, 0, imgPixels, 0, getDisplayWidth() * getDisplayHeight());
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

    public record SetBlendingEvent(boolean blending) implements Event {
    }

    public record DisplaySizeUpdatedEvent(Dimension preferredSize) implements Event {
    }
}
