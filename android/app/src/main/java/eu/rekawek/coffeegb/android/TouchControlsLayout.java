package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.EnumSet;
import java.util.List;

/**
 * Persistable geometry for the translucent touch controls.
 *
 * <p>The layout deliberately turns every pointer into a snapshot of logical buttons instead of
 * synthesising key events. That makes an Android touch chord equivalent to a physical controller
 * chord and keeps the control geometry independently testable.
 */
final class TouchControlsLayout {

    static final float DEFAULT_OPACITY = 0.38f;
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

    List<Button> buttonsAt(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return List.of();
        }
        float radius = controlRadius(width, height);
        float dpadX = dpadCenterX(width);
        float actionsX = actionsCenterX(width);
        float controlsY = controlsCenterY(height, radius);

        if (inCircle(x, y, dpadX, controlsY, radius * 1.35f)) {
            EnumSet<Button> pressed = EnumSet.noneOf(Button.class);
            if (x < dpadX - radius * 0.30f) {
                pressed.add(Button.LEFT);
            } else if (x > dpadX + radius * 0.30f) {
                pressed.add(Button.RIGHT);
            }
            if (y < controlsY - radius * 0.30f) {
                pressed.add(Button.UP);
            } else if (y > controlsY + radius * 0.30f) {
                pressed.add(Button.DOWN);
            }
            return List.copyOf(pressed);
        }

        float bX = actionsX - radius * 0.70f;
        float aX = actionsX + radius * 0.70f;
        if (inCircle(x, y, bX, controlsY, radius * 0.72f)) {
            return List.of(Button.B);
        }
        if (inCircle(x, y, aX, controlsY, radius * 0.72f)) {
            return List.of(Button.A);
        }

        float utilityY = controlsY - radius * 1.75f;
        if (inRect(x, y, width * 0.38f, utilityY - radius * 0.35f,
                width * 0.12f, radius * 0.70f)) {
            return List.of(Button.SELECT);
        }
        if (inRect(x, y, width * 0.50f, utilityY - radius * 0.35f,
                width * 0.12f, radius * 0.70f)) {
            return List.of(Button.START);
        }
        return List.of();
    }

    float controlRadius(int width, int height) {
        return Math.max(22f, Math.min(width, height) * 0.13f * scale);
    }

    float dpadCenterX(int width) {
        return width * (leftHanded ? 0.76f : 0.24f);
    }

    float actionsCenterX(int width) {
        return width * (leftHanded ? 0.24f : 0.76f);
    }

    float controlsCenterY(int height, float radius) {
        return Math.max(radius * 2.2f,
                height - radius * 2.0f - verticalPosition * height * 0.38f);
    }

    private static boolean inCircle(float x, float y, float centerX, float centerY, float radius) {
        float dx = x - centerX;
        float dy = y - centerY;
        return dx * dx + dy * dy <= radius * radius;
    }

    private static boolean inRect(float x, float y, float left, float top, float width, float height) {
        return x >= left && x <= left + width && y >= top && y <= top + height;
    }

    private static float clamp(float value, float lower, float upper) {
        if (!Float.isFinite(value)) {
            return lower;
        }
        return Math.max(lower, Math.min(upper, value));
    }
}
