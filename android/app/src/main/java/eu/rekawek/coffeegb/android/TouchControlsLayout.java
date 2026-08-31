package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.EnumSet;
import java.util.List;

/**
 * Persisted touch settings and hit geometry for the Coffee GB raster skins.
 *
 * <p>The visual controls are part of the portrait and landscape images, so the default hit zones
 * deliberately use the same normalized coordinates as those assets.
 */
final class TouchControlsLayout {

    static final float DEFAULT_OPACITY = 0.85f;
    static final float DEFAULT_SCALE = 1f;
    static final float DEFAULT_VERTICAL_POSITION = 0f;

    private final float opacity;
    private final float scale;
    private final float verticalPosition;
    private final boolean leftHanded;
    private final boolean haptics;

    TouchControlsLayout(float opacity, float scale, float verticalPosition,
                        boolean leftHanded, boolean haptics) {
        this.opacity = clamp(opacity, 0.15f, 1f);
        this.scale = clamp(scale, 0.60f, 1.40f);
        this.verticalPosition = clamp(verticalPosition, 0f, 1f);
        this.leftHanded = leftHanded;
        this.haptics = haptics;
    }

    float opacity() {
        return opacity;
    }

    float scale() {
        return scale;
    }

    float verticalPosition() {
        return verticalPosition;
    }

    boolean leftHanded() {
        return leftHanded;
    }

    boolean haptics() {
        return haptics;
    }

    List<Button> buttonsAtViewPoint(float x, float y, SkinTransform transform) {
        if (!transform.containsViewPoint(x, y)) {
            return List.of();
        }
        SkinTransform.Point nativePoint = transform.inversePoint(x, y);
        return buttonsAt(nativePoint.x(), nativePoint.y(),
                transform.skinWidth(), transform.skinHeight());
    }

    List<Button> buttonsAt(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return List.of();
        }
        Geometry geometry = geometry(width, height);
        if (inCircle(x, y, geometry.dpadX, geometry.dpadY, geometry.dpadRadius)) {
            EnumSet<Button> pressed = EnumSet.noneOf(Button.class);
            if (x < geometry.dpadX - geometry.dpadRadius * 0.30f) {
                pressed.add(Button.LEFT);
            } else if (x > geometry.dpadX + geometry.dpadRadius * 0.30f) {
                pressed.add(Button.RIGHT);
            }
            if (y < geometry.dpadY - geometry.dpadRadius * 0.30f) {
                pressed.add(Button.UP);
            } else if (y > geometry.dpadY + geometry.dpadRadius * 0.30f) {
                pressed.add(Button.DOWN);
            }
            return List.copyOf(pressed);
        }
        float actionMiddleX = (geometry.aX + geometry.bX) / 2f;
        float actionMiddleY = (geometry.aY + geometry.bY) / 2f;
        if (inCircle(x, y, actionMiddleX, actionMiddleY, geometry.actionRadius)) {
            return List.of(Button.A, Button.B);
        }
        if (inCircle(x, y, geometry.bX, geometry.bY, geometry.actionRadius)) {
            return List.of(Button.B);
        }
        if (inCircle(x, y, geometry.aX, geometry.aY, geometry.actionRadius)) {
            return List.of(Button.A);
        }
        if (inCapsule(x, y, geometry.bX, geometry.bY, geometry.aX, geometry.aY,
                geometry.actionRadius)) {
            return List.of(Button.A, Button.B);
        }
        if (inRect(x, y, geometry.selectX, geometry.utilityY,
                geometry.utilityWidth, geometry.utilityHeight)) {
            return List.of(Button.SELECT);
        }
        if (inRect(x, y, geometry.startX, geometry.utilityY,
                geometry.utilityWidth, geometry.utilityHeight)) {
            return List.of(Button.START);
        }
        return List.of();
    }

    float dpadCenterX(int width, int height) {
        return geometry(width, height).dpadX;
    }

    float dpadCenterY(int width, int height) {
        return geometry(width, height).dpadY;
    }

    float controlRadius(int width, int height) {
        return geometry(width, height).dpadRadius;
    }

    float actionCenterX(int width, int height, boolean a) {
        Geometry geometry = geometry(width, height);
        return a ? geometry.aX : geometry.bX;
    }

    float actionCenterY(int width, int height, boolean a) {
        Geometry geometry = geometry(width, height);
        return a ? geometry.aY : geometry.bY;
    }

    float utilityCenterX(int width, int height, boolean start) {
        Geometry geometry = geometry(width, height);
        return start ? geometry.startX : geometry.selectX;
    }

    float utilityCenterY(int width, int height) {
        return geometry(width, height).utilityY;
    }

    private static Geometry geometry(int width, int height) {
        if (height >= width) {
            return new Geometry(
                    width * .207f, height * .704f, width * .151f,
                    width * .707f, height * .727f,
                    width * .861f, height * .689f, width * .065f,
                    width * .394f, width * .574f, height * .838f,
                    width * .140f, height * .050f);
        }
        return new Geometry(
                width * .106f, height * .505f, height * .146f,
                width * .858f, height * .548f,
                width * .938f, height * .468f, height * .060f,
                width * .105f, width * .907f, height * .871f,
                width * .090f, height * .080f);
    }

    private static boolean inCircle(float x, float y, float centerX, float centerY, float radius) {
        float dx = x - centerX;
        float dy = y - centerY;
        return dx * dx + dy * dy <= radius * radius;
    }

    /**
     * Covers the rest of the bridge between the two round action-button hit targets. The central
     * A+B circle is checked first so its larger target can overlap the inner edges of both buttons.
     */
    private static boolean inCapsule(float x, float y, float startX, float startY,
                                     float endX, float endY, float radius) {
        float segmentX = endX - startX;
        float segmentY = endY - startY;
        float segmentLengthSquared = segmentX * segmentX + segmentY * segmentY;
        if (segmentLengthSquared == 0f) {
            return inCircle(x, y, startX, startY, radius);
        }
        float projection = ((x - startX) * segmentX + (y - startY) * segmentY)
                / segmentLengthSquared;
        projection = clamp(projection, 0f, 1f);
        return inCircle(x, y, startX + projection * segmentX,
                startY + projection * segmentY, radius);
    }

    private static boolean inRect(float x, float y, float centerX, float centerY,
                                  float width, float height) {
        return x >= centerX - width / 2f && x <= centerX + width / 2f
                && y >= centerY - height / 2f && y <= centerY + height / 2f;
    }

    private static float clamp(float value, float lower, float upper) {
        if (!Float.isFinite(value)) {
            return lower;
        }
        return Math.max(lower, Math.min(upper, value));
    }

    private record Geometry(float dpadX, float dpadY, float dpadRadius,
                            float bX, float bY, float aX, float aY, float actionRadius,
                            float selectX, float startX, float utilityY,
                            float utilityWidth, float utilityHeight) {
    }
}
