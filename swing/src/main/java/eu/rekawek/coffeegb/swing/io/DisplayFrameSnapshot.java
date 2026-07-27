package eu.rekawek.coffeegb.swing.io;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Objects;

/**
 * A private-raster frame that is copied once and never mutated after publication.
 */
final class DisplayFrameSnapshot {

    private final int width;

    private final int height;

    private final BufferedImage image;

    private DisplayFrameSnapshot(int width, int height, int[] rgb) {
        this.width = width;
        this.height = height;
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(rgb, 0, imagePixels, 0, imagePixels.length);
    }

    static DisplayFrameSnapshot copyOf(int width, int height, int[] rgb) {
        Objects.requireNonNull(rgb, "rgb");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive");
        }
        int expectedLength = Math.multiplyExact(width, height);
        if (rgb.length != expectedLength) {
            throw new IllegalArgumentException(
                    "Frame pixel count must be exactly " + expectedLength);
        }
        return new DisplayFrameSnapshot(width, height, rgb);
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

    void paint(Graphics2D graphics) {
        graphics.drawImage(image, 0, 0, null);
    }
}
