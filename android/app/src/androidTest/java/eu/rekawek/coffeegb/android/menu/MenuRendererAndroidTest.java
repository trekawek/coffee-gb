package eu.rekawek.coffeegb.android.menu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;

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
}
