package eu.rekawek.coffeegb.android;

/** Pure nearest-neighbour viewport math, deliberately independent of Android UI classes. */
final class VideoGeometry {

    private VideoGeometry() {
    }

    static Viewport nearestFit(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new Viewport(0, 0, 0, 0);
        }
        int integerScale = Math.min(targetWidth / sourceWidth, targetHeight / sourceHeight);
        int width;
        int height;
        if (integerScale >= 1) {
            width = sourceWidth * integerScale;
            height = sourceHeight * integerScale;
        } else {
            return aspectFit(sourceWidth, sourceHeight, targetWidth, targetHeight);
        }
        return new Viewport((targetWidth - width) / 2, (targetHeight - height) / 2, width, height);
    }

    /** Fractional fallback for a target smaller than one native source frame. */
    private static Viewport aspectFit(
            int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new Viewport(0, 0, 0, 0);
        }
        double fitScale = Math.min(
                (double) targetWidth / sourceWidth,
                (double) targetHeight / sourceHeight);
        int width = Math.max(1, (int) Math.floor(sourceWidth * fitScale));
        int height = Math.max(1, (int) Math.floor(sourceHeight * fitScale));
        return new Viewport((targetWidth - width) / 2, (targetHeight - height) / 2, width, height);
    }

    /** Fits the frame at the top of a portrait play surface, leaving the lower half for controls. */
    static Viewport nearestFitTop(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        Viewport viewport = nearestFit(sourceWidth, sourceHeight, targetWidth, targetHeight);
        return new Viewport(viewport.left(), 0, viewport.width(), viewport.height());
    }

    record Viewport(int left, int top, int width, int height) {
    }
}
