package eu.rekawek.coffeegb.swing.io;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.Arrays;
import java.util.Objects;

/**
 * A private-raster frame that is copied once and never mutated after publication.
 */
final class DisplayFrameSnapshot {

    private static final int RED_MASK = 0x00ff0000;

    private static final int GREEN_MASK = 0x0000ff00;

    private static final int BLUE_MASK = 0x000000ff;

    private static final int[] RGB_MASKS = {RED_MASK, GREEN_MASK, BLUE_MASK};

    private static final DirectColorModel RGB_COLOR_MODEL =
            new DirectColorModel(24, RED_MASK, GREEN_MASK, BLUE_MASK);

    private final int width;

    private final int height;

    private final BufferedImage image;

    private DisplayFrameSnapshot(int width, int height, int[] ownedRgb) {
        this.width = width;
        this.height = height;
        DataBufferInt dataBuffer = new DataBufferInt(ownedRgb, ownedRgb.length);
        WritableRaster raster = Raster.createPackedRaster(
                dataBuffer, width, height, width, RGB_MASKS, null);
        this.image = new BufferedImage(RGB_COLOR_MODEL, raster, false, null);
    }

    static DisplayFrameSnapshot copyOf(int width, int height, int[] rgb) {
        int expectedLength = validate(width, height, rgb);
        return takeOwnership(width, height, Arrays.copyOf(rgb, expectedLength));
    }

    /**
     * Takes sole ownership of a validated exact-length raster. Callers must not mutate or reuse
     * the array after this method returns.
     */
    static DisplayFrameSnapshot takeOwnership(int width, int height, int[] ownedRgb) {
        validate(width, height, ownedRgb);
        return new DisplayFrameSnapshot(width, height, ownedRgb);
    }

    private static int validate(int width, int height, int[] rgb) {
        Objects.requireNonNull(rgb, "rgb");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive");
        }
        int expectedLength = Math.multiplyExact(width, height);
        if (rgb.length != expectedLength) {
            throw new IllegalArgumentException(
                    "Frame pixel count must be exactly " + expectedLength);
        }
        return expectedLength;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int rgbAt(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ")");
        }
        return image.getRGB(x, y) & 0xffffff;
    }

    int[] copyRgb() {
        int[] pixels = new int[Math.multiplyExact(width, height)];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] &= 0x00ffffff;
        }
        return pixels;
    }

    void paint(Graphics2D graphics) {
        graphics.drawImage(image, 0, 0, null);
    }
}
