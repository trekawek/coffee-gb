package eu.rekawek.coffeegb.ui.menu.artwork;

import java.util.Objects;

/**
 * Immutable row-major straight-alpha ARGB pixels.
 *
 * <p>Each pixel is encoded as {@code 0xAARRGGBB}. Pixel storage is never exposed directly; all
 * public reads are defensive copies or validated copies into caller-owned storage.
 */
public final class MenuArgbFrame {

    private final int width;
    private final int height;
    private final int[] pixels;

    /** Package-private constructor for tests and trusted portable producers. */
    MenuArgbFrame(int width, int height, int[] pixels) {
        this(width, height, pixels, false);
    }

    /** Package-private ownership-transfer factory for the decoder. */
    static MenuArgbFrame trusted(int width, int height, int[] pixels) {
        return new MenuArgbFrame(width, height, pixels, true);
    }

    private MenuArgbFrame(int width, int height, int[] pixels, boolean takeOwnership) {
        int pixelCount = checkedPixelCount(width, height);
        Objects.requireNonNull(pixels, "pixels");
        if (pixels.length != pixelCount) {
            throw new IllegalArgumentException("pixels length must equal width * height");
        }
        this.width = width;
        this.height = height;
        this.pixels = takeOwnership ? pixels : pixels.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Returns a defensive copy of the row-major ARGB pixels. */
    public int[] copyPixels() {
        return pixels.clone();
    }

    /**
     * Copies this frame into a caller-owned strided destination.
     *
     * <p>Rows occupy {@code width} elements beginning at {@code offset + row * stride}. The
     * destination may contain padding after each row, but its final required element must fit.
     */
    public void copyTo(int[] destination, int offset, int stride) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (stride < width) {
            throw new IllegalArgumentException("stride must be at least the frame width");
        }
        if (offset > destination.length) {
            throw new IllegalArgumentException("offset exceeds destination length");
        }
        long requiredExclusive = (long) offset + (long) (height - 1) * stride + width;
        if (requiredExclusive > destination.length) {
            throw new IllegalArgumentException("destination is too small for the frame and stride");
        }
        for (int row = 0; row < height; row++) {
            int sourceOffset = row * width;
            int destinationOffset = (int) ((long) offset + (long) row * stride);
            System.arraycopy(pixels, sourceOffset, destination, destinationOffset, width);
        }
    }

    private static int checkedPixelCount(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame dimensions must be positive");
        }
        long pixelCount = (long) width * height;
        if (pixelCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("frame dimensions exceed Java array capacity");
        }
        return (int) pixelCount;
    }
}
