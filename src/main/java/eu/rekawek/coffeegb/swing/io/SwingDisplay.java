package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.GameboyType;
import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.sgb.Background;
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator;
import eu.rekawek.coffeegb.swing.gui.properties.DisplayProperties;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

import static eu.rekawek.coffeegb.gpu.Display.DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.gpu.Display.DISPLAY_WIDTH;
import static eu.rekawek.coffeegb.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;

public class SwingDisplay extends JPanel implements Runnable {

    private final EventBus eventBus;

    private final BufferedImage img;

    private final BufferedImage sgbImg;

    private final int[] waitingSgbFrame;

    private final int[] waitingFrame;

    private volatile boolean doStop;

    private volatile boolean isStopped;

    private boolean frameIsWaiting;

    private int scale;

    private boolean grayscale;

    private GameboyType gameboyType;

    public SwingDisplay(DisplayProperties properties, EventBus eventBus) {
        this(properties, eventBus, null);
    }

    public SwingDisplay(DisplayProperties properties, EventBus eventBus, String callerId) {
        super();
        this.eventBus = eventBus;
        GraphicsConfiguration gfxConfig =
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration();
        img = gfxConfig.createCompatibleImage(DISPLAY_WIDTH, DISPLAY_HEIGHT);
        sgbImg = gfxConfig.createCompatibleImage(SGB_DISPLAY_WIDTH, SGB_DISPLAY_HEIGHT);
        waitingSgbFrame = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
        waitingFrame = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];
        eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class, callerId);
        eventBus.register(this::onGbcFrame, Display.GbcFrameReadyEvent.class, callerId);
        eventBus.register(this::onSgbBackground, Background.SgbBackgroundReadyEvent.class, callerId);
        eventBus.register(this::onGameboyType, SwingEmulator.GameboyTypeEvent.class, callerId);
        eventBus.register(e -> setScale(e.scale), SetScaleEvent.class);
        eventBus.register(e -> this.grayscale = e.grayscale, SetGrayscaleEvent.class);
        this.grayscale = properties.getGrayscale();
        setScale(properties.getScale());
    }

    private synchronized void onGameboyType(SwingEmulator.GameboyTypeEvent e) {
        if (this.gameboyType != e.getGameboyType()) {
            this.gameboyType = e.getGameboyType();
            setScale(scale);
        }
    }

    private synchronized void onGbcFrame(Display.GbcFrameReadyEvent e) {
        if (frameIsWaiting) {
            return;
        }
        frameIsWaiting = true;
        e.toRgb(waitingFrame);
        notify();
    }

    private synchronized void onDmgFrame(Display.DmgFrameReadyEvent e) {
        if (frameIsWaiting) {
            return;
        }
        frameIsWaiting = true;
        e.toRgb(waitingFrame, grayscale);
        notify();
    }

    private synchronized void onSgbBackground(Background.SgbBackgroundReadyEvent sgbBackgroundReadyEvent) {
        System.arraycopy(sgbBackgroundReadyEvent.buffer(), 0, waitingSgbFrame, 0, waitingSgbFrame.length);
    }

    private void setScale(int scale) {
        this.scale = scale;
        setPreferredSize(new Dimension(getDisplayWidth() * scale, getDisplayHeight() * scale));
        eventBus.post(new DisplaySizeUpdatedEvent(getPreferredSize()));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        int localScale = scale;
        Graphics2D g2d = (Graphics2D) g.create();
        if (gameboyType == GameboyType.SGB) {
            g2d.drawImage(sgbImg, 0, 0, getDisplayWidth() * localScale, getDisplayHeight() * localScale, null);
        } else {
            g2d.drawImage(img, 0, 0, getDisplayWidth() * localScale, getDisplayHeight() * localScale, null);
        }
        g2d.dispose();
    }

    private int getDisplayWidth() {
        return gameboyType == GameboyType.SGB ? SGB_DISPLAY_WIDTH : DISPLAY_WIDTH;
    }

    private int getDisplayHeight() {
        return gameboyType == GameboyType.SGB ? SGB_DISPLAY_HEIGHT : DISPLAY_HEIGHT;
    }

    @Override
    public void run() {
        doStop = false;
        isStopped = false;
        frameIsWaiting = false;

        while (!doStop) {
            synchronized (this) {
                if (frameIsWaiting) {
                    if (gameboyType == GameboyType.SGB) {
                        sgbImg.setRGB(0, 0, SGB_DISPLAY_WIDTH, SGB_DISPLAY_HEIGHT, waitingSgbFrame, 0, SGB_DISPLAY_WIDTH);
                        sgbImg.setRGB(48, 40, DISPLAY_WIDTH, DISPLAY_HEIGHT, waitingFrame, 0, DISPLAY_WIDTH);
                    } else {
                        img.setRGB(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, waitingFrame, 0, DISPLAY_WIDTH);
                    }
                    validate();
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

    public record SetScaleEvent(int scale) implements Event {
    }

    public record SetGrayscaleEvent(boolean grayscale) implements Event {
    }

    public record DisplaySizeUpdatedEvent(Dimension preferredSize) implements Event {
    }
}
