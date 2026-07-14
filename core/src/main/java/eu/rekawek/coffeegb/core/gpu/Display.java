package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;
import java.util.Arrays;

public class Display implements Serializable, Originator<Display> {

    public static final int DISPLAY_WIDTH = 160;

    public static final int DISPLAY_HEIGHT = 144;

    private transient volatile EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private final int[] buffer = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];

    // the last complete frame that was shown, re-emitted for the one-frame repeat after a
    // CGB LCD-on (see repeatFrame); a display-only cache, not part of the machine state
    private final transient int[] lastFrame = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];

    private final boolean gbc;

    private int i;

    private boolean enabled = true;

    // The first frame after the LCD is switched back on is not shown while the PPU re-locks
    // to the line grid. A CGB repeats the previous frame; a DMG outputs a blank frame.
    // SameBoy models these as the two GB_FRAMESKIP_LCD_TURNED_ON paths. Exposing the
    // incomplete DMG frame leaves fragments of the old screen behind during transitions
    // (Hitori de Dekirumon, issue #126).
    private transient boolean firstFrameAfterLcdEnable;

    public Display(boolean gbc) {
        this.gbc = gbc;
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    void putDmgPixel(int color) {
        if (!enabled || firstFrameAfterLcdEnable) {
            return;
        }
        if (i >= buffer.length) {
            return;
        }
        buffer[i++] = color;
    }

    void putColorPixel(int gbcRgb) {
        if (!enabled || firstFrameAfterLcdEnable) {
            return;
        }
        if (i >= buffer.length) {
            return;
        }
        buffer[i++] = gbcRgb;
    }

    public void frameIsReady() {
        i = 0;
        if (firstFrameAfterLcdEnable) {
            firstFrameAfterLcdEnable = false;
            if (gbc) {
                // The CGB keeps the previous complete frame on its panel.
                eventBus.post(new GbcFrameReadyEvent(lastFrame));
            } else {
                // The DMG drives a blank frame instead of exposing the incomplete render.
                Arrays.fill(buffer, 0);
                System.arraycopy(buffer, 0, lastFrame, 0, buffer.length);
                eventBus.post(new DmgFrameReadyEvent(buffer));
            }
            return;
        }
        System.arraycopy(buffer, 0, lastFrame, 0, buffer.length);
        eventBus.post(gbc ? new GbcFrameReadyEvent(buffer) : new DmgFrameReadyEvent(buffer));
    }

    /** Steps the write pointer back one pixel (DMG window-activation LCD desync). */
    void rewindPixel() {
        if (i > 0) {
            i--;
        }
    }

    public void enableLcd() {
        i = 0;
        enabled = true;
        firstFrameAfterLcdEnable = true;
    }

    public void disableLcd() {
        // Only stop writing pixels - do NOT clear the framebuffer or reset the write index.
        // A game that blanks the LCD for a fraction of a frame (e.g. A Bug's Life swaps its
        // BG map every 7 frames behind a ~1100-tick LCD-off) must keep showing the last
        // complete frame. If we cleared here, the swap frame's re-render would not repaint
        // every pixel before VBlank and the gaps would show through as black, flickering
        // once per swap. enableLcd() resets the write index so the next frame repaints from
        // the top; a sustained LCD-off is blanked deliberately by blankFrame().
        enabled = false;
    }

    /** Emits a blank frame while the LCD stays off (a sustained off blanks the screen). */
    public void blankFrame() {
        // a deliberate blank overrides any pending enable-frame skip
        firstFrameAfterLcdEnable = false;
        Arrays.fill(buffer, 0);
        i = 0;
        frameIsReady();
    }

    @Override
    public Memento<Display> saveToMemento() {
        return new DisplayMemento(buffer.clone(), i, enabled);
    }

    @Override
    public void restoreFromMemento(Memento<Display> memento) {
        if (!(memento instanceof DisplayMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.buffer.length != mem.buffer.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        System.arraycopy(mem.buffer, 0, this.buffer, 0, this.buffer.length);
        this.i = mem.i;
        this.enabled = mem.enabled;
    }

    public record GbcFrameReadyEvent(int[] pixels) implements Event {

        // CGB LCD response (SameBoy's modern-balanced correction): per-channel
        // brightness curve plus the green-blue gamma mix of the panel
        private static final int[] CURVE = {0, 6, 12, 20, 28, 36, 45, 56, 66, 76, 88, 100, 113, 125,
                137, 149, 161, 172, 182, 192, 202, 210, 218, 225, 232, 238, 243, 247, 250, 252, 254, 255};

        private static volatile int[] correctedLut;

        private static int[] correctedLut() {
            int[] lut = correctedLut;
            if (lut == null) {
                lut = new int[0x8000];
                for (int color = 0; color < 0x8000; color++) {
                    int r = CURVE[color & 0x1f];
                    int g = CURVE[(color >> 5) & 0x1f];
                    int b = CURVE[(color >> 10) & 0x1f];
                    if (g != b) {
                        g = (int) Math.round(Math.pow(
                                (Math.pow(g / 255.0, 2.2) * 3 + Math.pow(b / 255.0, 2.2)) / 4, 1 / 2.2) * 255);
                    }
                    lut[color] = (r << 16) | (g << 8) | b;
                }
                correctedLut = lut;
            }
            return lut;
        }

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
            toRgb(dest, false);
        }

        public void toRgb(int[] dest, boolean colorCorrection) {
            if (colorCorrection) {
                int[] lut = correctedLut();
                for (int i = 0; i < pixels.length; i++) {
                    dest[i] = lut[pixels[i] & 0x7fff];
                }
            } else {
                for (int i = 0; i < pixels.length; i++) {
                    dest[i] = translateGbcRgb(pixels[i]);
                }
            }
        }
    }

    public record DmgFrameReadyEvent(int[] pixels) implements Event {

        public static final int[] COLORS = new int[]{0xe6f8da, 0x99c886, 0x437969, 0x051f2a};

        public static final int[] COLORS_GRAYSCALE = new int[]{0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000};

        public void toRgb(int[] dest, int[] palette) {
            for (int i = 0; i < pixels.length; i++) {
                dest[i] = palette[pixels[i]];
            }
        }

        public void toRgb(int[] dest, boolean grayscale) {
            toRgb(dest, grayscale ? COLORS_GRAYSCALE : COLORS);
        }
    }

    private record DisplayMemento(int[] buffer, int i, boolean enabled) implements Memento<Display> {
    }
}
