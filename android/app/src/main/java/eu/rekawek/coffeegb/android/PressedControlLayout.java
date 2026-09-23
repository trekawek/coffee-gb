package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;

/** Native artwork bounds and atlas slots shared by the renderer and sprite preparation tool. */
enum PressedControlLayout {
    A(Button.A, "a", 0, 0, new int[]{753, 1089, 870, 1208}, new int[]{1507, 381, 1622, 498}),
    B(Button.B, "b", 128, 0, new int[]{607, 1152, 725, 1273}, new int[]{1373, 459, 1489, 576}),
    SELECT(Button.SELECT, "utility", 256, 0, new int[]{317, 1377, 426, 1420}, new int[]{130, 802, 220, 835}),
    START(Button.START, "utility", 384, 0, new int[]{484, 1377, 593, 1420}, new int[]{1454, 802, 1545, 835}),
    UP(Button.UP, "dpad", 0, 128, new int[]{67, 1028, 325, 1288}, new int[]{54, 350, 300, 597}),
    DOWN(Button.DOWN, "dpad", 272, 128, new int[]{67, 1028, 325, 1288}, new int[]{54, 350, 300, 597}),
    LEFT(Button.LEFT, "dpad", 0, 400, new int[]{67, 1028, 325, 1288}, new int[]{54, 350, 300, 597}),
    RIGHT(Button.RIGHT, "dpad", 272, 400, new int[]{67, 1028, 325, 1288}, new int[]{54, 350, 300, 597}),
    BRIDGE(null, "bridge", 512, 0, null, null);

    static final int ATLAS_WIDTH = 576;
    static final int ATLAS_HEIGHT = 672;

    final Button button;
    final String sourceName;
    final int atlasX;
    final int atlasY;
    private final int[] portraitBounds;
    private final int[] landscapeBounds;

    PressedControlLayout(Button button, String sourceName, int atlasX, int atlasY,
            int[] portraitBounds, int[] landscapeBounds) {
        this.button = button;
        this.sourceName = sourceName;
        this.atlasX = atlasX;
        this.atlasY = atlasY;
        this.portraitBounds = portraitBounds;
        this.landscapeBounds = landscapeBounds;
    }

    boolean active(int mask) {
        return button == null
                ? TouchPressState.contains(mask, Button.A) && TouchPressState.contains(mask, Button.B)
                : TouchPressState.contains(mask, button);
    }

    SkinTransform.Bounds bounds(int width, int height) {
        if (this == BRIDGE) {
            return TouchControlsLayout.actionBridgeCueBounds(width, height);
        }
        boolean portrait = height >= width;
        int[] bounds = portrait ? portraitBounds : landscapeBounds;
        float scaleX = width / (portrait ? 941f : 1672f);
        float scaleY = height / (portrait ? 1672f : 941f);
        return new SkinTransform.Bounds(bounds[0] * scaleX, bounds[1] * scaleY,
                bounds[2] * scaleX, bounds[3] * scaleY);
    }

    int spriteWidth(boolean portrait) {
        SkinTransform.Bounds bounds = bounds(portrait ? 941 : 1672, portrait ? 1672 : 941);
        return (int) Math.ceil(bounds.right() - bounds.left());
    }

    int spriteHeight(boolean portrait) {
        SkinTransform.Bounds bounds = bounds(portrait ? 941 : 1672, portrait ? 1672 : 941);
        return (int) Math.ceil(bounds.bottom() - bounds.top());
    }
}
