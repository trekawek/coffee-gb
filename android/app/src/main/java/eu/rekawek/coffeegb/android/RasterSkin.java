package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

/** A raster control skin whose transparent rectangle is the live Game Boy display. */
final class RasterSkin {

    // Native-pixel centers of the baked speaker/menu glyph in each raster.
    private static final float PORTRAIT_MENU_X = 135.5f;
    private static final float PORTRAIT_MENU_Y = 86f;
    private static final float LANDSCAPE_MENU_X = 99.5f;
    private static final float LANDSCAPE_MENU_Y = 79.5f;

    private final Bitmap bitmap;
    private final Rect displayWindow;
    private final float menuControlX;
    private final float menuControlY;

    private RasterSkin(Bitmap bitmap, Rect displayWindow,
            float menuControlX, float menuControlY) {
        this.bitmap = bitmap;
        this.displayWindow = displayWindow;
        this.menuControlX = menuControlX;
        this.menuControlY = menuControlY;
    }

    static RasterSkin portrait(Context context) {
        return load(context, R.drawable.coffee_gb_skin_portrait,
                PORTRAIT_MENU_X, PORTRAIT_MENU_Y);
    }

    static RasterSkin landscape(Context context) {
        return load(context, R.drawable.coffee_gb_skin_landscape,
                LANDSCAPE_MENU_X, LANDSCAPE_MENU_Y);
    }

    private static RasterSkin load(Context context, int resource,
            float menuControlX, float menuControlY) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resource, options);
        if (bitmap == null) {
            throw new IllegalStateException("Unable to load Coffee GB raster skin");
        }
        return new RasterSkin(bitmap, transparentWindow(bitmap), menuControlX, menuControlY);
    }

    SkinTransform transform(int viewWidth, int viewHeight) {
        return SkinTransform.aspectFit(bitmap.getWidth(), bitmap.getHeight(),
                viewWidth, viewHeight);
    }

    RectF skinBounds(SkinTransform transform) {
        return rectF(transform.skinBounds());
    }

    RectF displayBounds(SkinTransform transform) {
        return rectF(transform.mapBounds(displayWindow.left, displayWindow.top,
                displayWindow.right, displayWindow.bottom));
    }

    PointF menuControlCenter(SkinTransform transform) {
        SkinTransform.Point center = transform.mapPoint(menuControlX, menuControlY);
        return new PointF(center.x(), center.y());
    }

    void draw(Canvas canvas, Paint paint, SkinTransform transform) {
        canvas.drawBitmap(bitmap, null, skinBounds(transform), paint);
    }

    private static RectF rectF(SkinTransform.Bounds bounds) {
        return new RectF(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
    }

    private static Rect transparentWindow(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = 0;
        int bottom = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if ((pixels[row + x] >>> 24) == 0) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x + 1);
                    bottom = Math.max(bottom, y + 1);
                }
            }
        }
        if (left >= right || top >= bottom) {
            throw new IllegalStateException("Coffee GB raster skin has no transparent display window");
        }
        return new Rect(left, top, right, bottom);
    }
}
