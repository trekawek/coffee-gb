package eu.rekawek.coffeegb.swing.io;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.Objects;

/**
 * Immutable, device-pixel viewport geometry for one emulated frame.
 *
 * <p>The exact width and height may be fractional in aspect-fit mode. Rendering uses
 * {@link #scale()} for both axes, so rounding the conservative {@link #paintBounds()} never
 * introduces a non-uniform stretch. When centering leaves an odd device pixel, the extra pixel is
 * deliberately placed on the right or bottom edge.</p>
 */
public final class DisplayViewport {

    private static final double INTEGER_EPSILON = 1e-9;

    private final int componentWidth;

    private final int componentHeight;

    private final int sourceWidth;

    private final int sourceHeight;

    private final int rotation;

    private final int x;

    private final int y;

    private final double width;

    private final double height;

    private final double scale;

    private DisplayViewport(
            int componentWidth,
            int componentHeight,
            int sourceWidth,
            int sourceHeight,
            int rotation,
            int x,
            int y,
            double width,
            double height,
            double scale) {
        this.componentWidth = componentWidth;
        this.componentHeight = componentHeight;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.rotation = rotation;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scale = scale;
    }

    public static DisplayViewport calculate(
            int componentWidth,
            int componentHeight,
            int sourceWidth,
            int sourceHeight,
            int rotation,
            DisplayScaleMode mode) {
        if (componentWidth < 0 || componentHeight < 0) {
            throw new IllegalArgumentException("Component dimensions must not be negative");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("Source dimensions must be positive");
        }
        Objects.requireNonNull(mode, "mode");

        int normalizedRotation = normalizeRotation(rotation);
        int rotatedWidth = swapsAxes(normalizedRotation) ? sourceHeight : sourceWidth;
        int rotatedHeight = swapsAxes(normalizedRotation) ? sourceWidth : sourceHeight;

        double fitScale = Math.min(
                componentWidth / (double) rotatedWidth,
                componentHeight / (double) rotatedHeight);
        double scale;
        if (mode == DisplayScaleMode.INTEGER_FIT) {
            double integerScale = Math.floor(fitScale);
            // A transient layout can make the component smaller than one emulated frame. Keep
            // the complete image visible instead of choosing the only fitting integer (zero).
            scale = integerScale >= 1 ? integerScale : fitScale;
        } else if (mode == DisplayScaleMode.ASPECT_FIT) {
            scale = fitScale;
        } else {
            scale = mode.explicitScale();
        }

        double width = rotatedWidth * scale;
        double height = rotatedHeight * scale;
        int x = centeredOrigin(componentWidth, width);
        int y = centeredOrigin(componentHeight, height);
        return new DisplayViewport(
                componentWidth,
                componentHeight,
                sourceWidth,
                sourceHeight,
                normalizedRotation,
                x,
                y,
                width,
                height,
                scale);
    }

    public static Dimension preferredSize(
            int sourceWidth, int sourceHeight, int rotation, DisplayScaleMode mode) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("Source dimensions must be positive");
        }
        Objects.requireNonNull(mode, "mode");
        int normalizedRotation = normalizeRotation(rotation);
        int factor = mode.isExplicit() ? mode.explicitScale() : 1;
        int width = Math.multiplyExact(sourceWidth, factor);
        int height = Math.multiplyExact(sourceHeight, factor);
        return swapsAxes(normalizedRotation)
                ? new Dimension(height, width)
                : new Dimension(width, height);
    }

    private static int centeredOrigin(int available, double content) {
        return (int) Math.floor((available - content) / 2.0);
    }

    private static boolean swapsAxes(int rotation) {
        return rotation == 90 || rotation == 270;
    }

    private static int normalizeRotation(int rotation) {
        int normalized = Math.floorMod(rotation, 360);
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException("Rotation must be a multiple of 90 degrees");
        }
        return normalized;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public double scale() {
        return scale;
    }

    public int rotation() {
        return rotation;
    }

    public int rotatedSourceWidth() {
        return swapsAxes(rotation) ? sourceHeight : sourceWidth;
    }

    public int rotatedSourceHeight() {
        return swapsAxes(rotation) ? sourceWidth : sourceHeight;
    }

    /**
     * Returns the component-clipped integer bounds covering every device pixel the exact
     * transform can touch.
     */
    public Rectangle paintBounds() {
        int left = clamp(x, 0, componentWidth);
        int top = clamp(y, 0, componentHeight);
        int right = clamp(ceilStable(x + width), 0, componentWidth);
        int bottom = clamp(ceilStable(y + height), 0, componentHeight);
        return new Rectangle(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    /**
     * Returns the exact, uniformly scaled transform from unrotated source pixels to the
     * component.
     */
    public AffineTransform sourceToComponentTransform() {
        AffineTransform transform = AffineTransform.getTranslateInstance(x, y);
        transform.scale(scale, scale);
        switch (rotation) {
            case 90 -> {
                transform.translate(sourceHeight, 0);
                transform.quadrantRotate(1);
            }
            case 180 -> {
                transform.translate(sourceWidth, sourceHeight);
                transform.quadrantRotate(2);
            }
            case 270 -> {
                transform.translate(0, sourceWidth);
                transform.quadrantRotate(3);
            }
            default -> {
            }
        }
        return transform;
    }

    private static int ceilStable(double value) {
        double nearestInteger = Math.rint(value);
        if (Math.abs(value - nearestInteger) < INTEGER_EPSILON) {
            return (int) nearestInteger;
        }
        return (int) Math.ceil(value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
