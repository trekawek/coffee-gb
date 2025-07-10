package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.swing.gui.properties.DisplayProperties;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

import static eu.rekawek.coffeegb.gpu.Display.DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.gpu.Display.DISPLAY_WIDTH;

public class SwingDisplay extends JPanel implements Runnable {

  private final BufferedImage img;

  private final int[] waitingFrame;

  private volatile boolean doStop;

  private volatile boolean isStopped;

  private boolean frameIsWaiting;

  private int scale;

  private boolean grayscale;

  public SwingDisplay(DisplayProperties properties, EventBus eventBus) {
    super();
    GraphicsConfiguration gfxConfig =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice()
            .getDefaultConfiguration();
    img = gfxConfig.createCompatibleImage(DISPLAY_WIDTH, DISPLAY_HEIGHT);
    waitingFrame = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];
    eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class);
    eventBus.register(this::onGbcFrame, Display.GbcFrameReadyEvent.class);
    eventBus.register(e -> setScale(e.scale), SetScaleEvent.class);
    eventBus.register(e -> this.grayscale = e.grayscale, SetGrayscaleEvent.class);
    this.grayscale = properties.getGrayscale();
    setScale(properties.getScale());
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

  private void setScale(int scale) {
    this.scale = scale;
    setPreferredSize(new Dimension(DISPLAY_WIDTH * scale, DISPLAY_HEIGHT * scale));
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);

    int localScale = scale;
    Graphics2D g2d = (Graphics2D) g.create();
    g2d.drawImage(img, 0, 0, DISPLAY_WIDTH * localScale, DISPLAY_HEIGHT * localScale, null);
    g2d.dispose();
  }

  @Override
  public void run() {
    doStop = false;
    isStopped = false;
    frameIsWaiting = false;

    while (!doStop) {
      synchronized (this) {
        if (frameIsWaiting) {
          img.setRGB(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, waitingFrame, 0, DISPLAY_WIDTH);
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

  public record SetScaleEvent(int scale) implements Event {}

  public record SetGrayscaleEvent(boolean grayscale) implements Event {}
}
