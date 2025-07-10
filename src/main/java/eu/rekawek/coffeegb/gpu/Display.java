package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;

import java.io.Serializable;

public class Display implements Serializable {

  public static final int DISPLAY_WIDTH = 160;

  public static final int DISPLAY_HEIGHT = 144;

  private volatile transient EventBus eventBus;

  private final int[] buffer = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];

  private final boolean gbc;

  private int i;

  private boolean enabled;

  public Display(boolean gbc) {
    this.gbc = gbc;
  }

  public void init(EventBus eventBus) {
    this.eventBus = eventBus;
  }

  void putDmgPixel(int color) {
    if (!enabled) {
      return;
    }
    if (i >= buffer.length) {
      return;
    }
    buffer[i++] = color;
  }

  void putColorPixel(int gbcRgb) {
    if (!enabled) {
      return;
    }
    if (i >= buffer.length) {
      return;
    }
    buffer[i++] = gbcRgb;
  }

  public void frameIsReady() {
    eventBus.post(gbc ? new GbcFrameReady(buffer) : new DmgFrameReady(buffer));
    i = 0;
  }

  public void enableLcd() {
    i = 0;
    enabled = true;
  }

  public void disableLcd() {
    enabled = false;
    for (i = 0; i < buffer.length; i++) {
      buffer[i] = 0;
    }
    frameIsReady();
  }

  public record GbcFrameReady(int[] pixels) implements Event {

    public static int translateGbcRgb(int gbcRgb) {
      int r = (gbcRgb >> 0) & 0x1f;
      int g = (gbcRgb >> 5) & 0x1f;
      int b = (gbcRgb >> 10) & 0x1f;
      int result = (r * 8) << 16;
      result |= (g * 8) << 8;
      result |= (b * 8) << 0;
      return result;
    }

    public void toRgb(int[] dest) {
      for (int i = 0; i < pixels.length; i++) {
        dest[i] = translateGbcRgb(pixels[i]);
      }
    }
  }

  public record DmgFrameReady(int[] pixels) implements Event {

    public static final int[] COLORS = new int[] {0xe6f8da, 0x99c886, 0x437969, 0x051f2a};

    public static final int[] COLORS_GRAYSCALE = new int[] {0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000};

    public void toRgb(int[] dest, int[] palette) {
      for (int i = 0; i < pixels.length; i++) {
        dest[i] = palette[pixels[i]];
      }
    }

    public void toRgb(int[] dest, boolean grayscale) {
      toRgb(dest, grayscale ? COLORS_GRAYSCALE : COLORS);
    }
  }
}
