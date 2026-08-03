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
            double fitScale = Math.min(
                    (double) targetWidth / sourceWidth,
                    (double) targetHeight / sourceHeight);
            width = Math.max(1, (int) Math.floor(sourceWidth * fitScale));
            height = Math.max(1, (int) Math.floor(sourceHeight * fitScale));
        }
        return new Viewport((targetWidth - width) / 2, (targetHeight - height) / 2, width, height);
    }

    record Viewport(int left, int top, int width, int height) {
    }
}
