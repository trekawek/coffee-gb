package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** A raster control skin whose transparent rectangle is the live Game Boy display. */
final class RasterSkin {

    private final Bitmap bitmap;
    private final Rect displayWindow;

    private RasterSkin(Bitmap bitmap, Rect displayWindow) {
        this.bitmap = bitmap;
        this.displayWindow = displayWindow;
    }

    static RasterSkin load(Context context, int resource) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resource, options);
        if (bitmap == null) {
            throw new IllegalStateException("Unable to load Coffee GB raster skin");
        }
        return new RasterSkin(bitmap, transparentWindow(bitmap));
    }

    RectF displayBounds(int width, int height) {
        return new RectF(
                displayWindow.left * width / (float) bitmap.getWidth(),
                displayWindow.top * height / (float) bitmap.getHeight(),
                displayWindow.right * width / (float) bitmap.getWidth(),
                displayWindow.bottom * height / (float) bitmap.getHeight());
    }

    void draw(Canvas canvas, Paint paint) {
        canvas.drawBitmap(bitmap, null,
                new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), paint);
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
