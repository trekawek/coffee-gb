package eu.rekawek.coffeegb.android.menu;

/**
 * Stable logical-coordinate fitting for the menu renderer.
 *
 * <p>Landscape uses a 320x240 design grid and portrait uses the same grid rotated to 240x320.
 * The fit is uniform and centered inside the RasterSkin display window, so neither orientation
 * can crop a panel or footer.
 */
final class MenuGeometry {

    static final int LANDSCAPE_WIDTH = 320;
    static final int LANDSCAPE_HEIGHT = 240;
    static final int PORTRAIT_WIDTH = 240;
    static final int PORTRAIT_HEIGHT = 320;

    private MenuGeometry() {
    }

    static Layout forDisplay(float displayWidth, float displayHeight) {
        if (!(displayWidth > 0.0f) || !(displayHeight > 0.0f)) {
            return new Layout(false, 0, 0, 0, 0, 0, 0, 0);
        }
        boolean portrait = displayHeight > displayWidth;
        float logicalWidth = portrait ? PORTRAIT_WIDTH : LANDSCAPE_WIDTH;
        float logicalHeight = portrait ? PORTRAIT_HEIGHT : LANDSCAPE_HEIGHT;
        float scale = Math.min(displayWidth / logicalWidth, displayHeight / logicalHeight);
        float contentWidth = logicalWidth * scale;
        float contentHeight = logicalHeight * scale;
        return new Layout(portrait, (int) logicalWidth, (int) logicalHeight, scale,
                (displayWidth - contentWidth) / 2.0f, (displayHeight - contentHeight) / 2.0f,
                contentWidth, contentHeight);
    }

    static final class Layout {

        private final boolean portrait;
        private final int logicalWidth;
        private final int logicalHeight;
        private final float scale;
        private final float offsetX;
        private final float offsetY;
        private final float contentWidth;
        private final float contentHeight;

        private Layout(boolean portrait, int logicalWidth, int logicalHeight, float scale,
                float offsetX, float offsetY, float contentWidth, float contentHeight) {
            this.portrait = portrait;
            this.logicalWidth = logicalWidth;
            this.logicalHeight = logicalHeight;
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
        }

        boolean portrait() {
            return portrait;
        }

        int logicalWidth() {
            return logicalWidth;
        }

        int logicalHeight() {
            return logicalHeight;
        }

        float scale() {
            return scale;
        }

        float offsetX() {
            return offsetX;
        }

        float offsetY() {
            return offsetY;
        }

        float contentWidth() {
            return contentWidth;
        }

        float contentHeight() {
            return contentHeight;
        }

        boolean fits(float displayWidth, float displayHeight) {
            return offsetX >= -0.001f && offsetY >= -0.001f
                    && contentWidth <= displayWidth + 0.001f
                    && contentHeight <= displayHeight + 0.001f;
        }
    }
}
