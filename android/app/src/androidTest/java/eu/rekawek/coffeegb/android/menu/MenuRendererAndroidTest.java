package eu.rekawek.coffeegb.android.menu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import eu.rekawek.coffeegb.ui.menu.MenuPreview;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

@RunWith(AndroidJUnit4.class)
public class MenuRendererAndroidTest {

    @Test
    public void shortWideThumbnailIsClippedToItsBounds() {
        int untouched = Color.MAGENTA;
        Bitmap bitmap = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(untouched);
        RectF bounds = new RectF(20, 30, 340, 58);

        new MenuRenderer().drawThumbnail(new Canvas(bitmap), bounds);

        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                boolean inside = x >= (int) bounds.left && x < (int) bounds.right
                        && y >= (int) bounds.top && y < (int) bounds.bottom;
                if (!inside) {
                    assertEquals("pixel outside thumbnail at " + x + "," + y,
                            untouched, bitmap.getPixel(x, y));
                }
            }
        }
        assertNotEquals(untouched, bitmap.getPixel(21, 31));
    }

    @Test
    public void boundedPrinterPreviewIsAspectFitAndClippedToPanel() {
        int untouched = Color.MAGENTA;
        Bitmap bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(untouched);
        int[] pixels = new int[20 * 100];
        java.util.Arrays.fill(pixels, Color.BLACK);
        RectF bounds = new RectF(40, 20, 200, 160);

        new MenuRenderer().drawPreview(
                new Canvas(bitmap), MenuPreview.ready(20, 100, pixels), bounds);

        assertEquals(untouched, bitmap.getPixel(10, 10));
        assertEquals(untouched, bitmap.getPixel(220, 170));
        assertNotEquals(untouched, bitmap.getPixel(120, 90));
        assertEquals(Color.rgb(239, 240, 211), bitmap.getPixel(45, 25));
    }
}
