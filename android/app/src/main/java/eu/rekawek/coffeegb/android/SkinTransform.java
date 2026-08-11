package eu.rekawek.coffeegb.android;

/** Pure centered aspect-fit geometry from one native raster skin into a view. */
final class SkinTransform {

    private final int skinWidth;
    private final int skinHeight;
    private final float scale;
    private final float left;
    private final float top;

    private SkinTransform(int skinWidth, int skinHeight, float scale, float left, float top) {
        this.skinWidth = skinWidth;
        this.skinHeight = skinHeight;
        this.scale = scale;
        this.left = left;
        this.top = top;
    }

    static SkinTransform aspectFit(int skinWidth, int skinHeight, int viewWidth, int viewHeight) {
        if (skinWidth <= 0 || skinHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException("Skin and view dimensions must be positive");
        }
        float scale = Math.min(viewWidth / (float) skinWidth, viewHeight / (float) skinHeight);
        float left = (viewWidth - skinWidth * scale) / 2f;
        float top = (viewHeight - skinHeight * scale) / 2f;
        return new SkinTransform(skinWidth, skinHeight, scale, left, top);
    }

    int skinWidth() {
        return skinWidth;
    }

    int skinHeight() {
        return skinHeight;
    }

    float scale() {
        return scale;
    }

    Bounds skinBounds() {
        return mapBounds(0f, 0f, skinWidth, skinHeight);
    }

    Bounds mapBounds(float nativeLeft, float nativeTop,
            float nativeRight, float nativeBottom) {
        return new Bounds(mapX(nativeLeft), mapY(nativeTop),
                mapX(nativeRight), mapY(nativeBottom));
    }

    Point mapPoint(float nativeX, float nativeY) {
        return new Point(mapX(nativeX), mapY(nativeY));
    }

    Point inversePoint(float viewX, float viewY) {
        return new Point((viewX - left) / scale, (viewY - top) / scale);
    }

    boolean containsViewPoint(float viewX, float viewY) {
        Bounds bounds = skinBounds();
        return viewX >= bounds.left() && viewX < bounds.right()
                && viewY >= bounds.top() && viewY < bounds.bottom();
    }

    private float mapX(float nativeX) {
        return left + nativeX * scale;
    }

    private float mapY(float nativeY) {
        return top + nativeY * scale;
    }

    record Bounds(float left, float top, float right, float bottom) {
    }

    record Point(float x, float y) {
    }
}
