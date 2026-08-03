package eu.rekawek.coffeegb.core.memory.cart.type;

import java.util.Arrays;

/**
 * An immutable, caller-owned RGB camera frame.
 *
 * <p>This deliberately has no toolkit image type. Hosts may capture with Android, desktop, or
 * test APIs, then hand the camera a bounded RGB snapshot without retaining a mutable raster.
 */
public final class CameraFrame {

    public static final int MAX_DIMENSION = 4096;

    public static final int MAX_PIXELS = 16 * 1024 * 1024;

    private final int width;

    private final int height;

    private final int[] rgb;

    public CameraFrame(int width, int height, int[] rgb) {
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Camera frame dimensions must be within 1.." + MAX_DIMENSION);
        }
        int pixels = Math.multiplyExact(width, height);
        if (pixels > MAX_PIXELS || rgb == null || rgb.length != pixels) {
            throw new IllegalArgumentException("Camera frame must contain one bounded RGB value per pixel");
        }
        this.width = width;
        this.height = height;
        this.rgb = Arrays.copyOf(rgb, rgb.length);
        for (int i = 0; i < this.rgb.length; i++) {
            this.rgb[i] &= 0x00ffffff;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Returns a fresh, caller-owned RGB copy in row-major order. */
    public int[] copyRgb() {
        return Arrays.copyOf(rgb, rgb.length);
    }
}
