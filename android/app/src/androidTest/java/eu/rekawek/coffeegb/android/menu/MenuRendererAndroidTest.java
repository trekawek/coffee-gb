package eu.rekawek.coffeegb.android.menu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/** Android-side contract tests for the PNG-only Proposal 3 adapter. */
@RunWith(AndroidJUnit4.class)
public class MenuRendererAndroidTest {

    @Test
    public void canonicalArtworkIsAspectFitWithoutStretchingInBothApertures() {
        Rect portrait = MenuRenderer.fitDestination(new Rect(0, 0, 758, 685));
        assertRect(portrait, 0, 41, 758, 644);

        Rect landscape = MenuRenderer.fitDestination(new Rect(0, 0, 919, 717));
        assertRect(landscape, 9, 0, 909, 717);
    }

    @Test
    public void repeatedPresentationReusesBitmapForTheSameFrameIdentity() {
        MenuRenderer renderer = new MenuRenderer();
        MenuPresentation presentation = visiblePausePresentation();
        Bitmap canvas = Bitmap.createBitmap(1000, 900, Bitmap.Config.ARGB_8888);
        try {
            renderer.draw(new Canvas(canvas), presentation, new RectF(50, 60, 950, 760));
            Bitmap first = renderer.cachedBitmapForTest();
            MenuArgbFrame firstFrame = renderer.cachedFrameForTest();
            renderer.draw(new Canvas(canvas), presentation, new RectF(50, 60, 950, 760));

            assertNotNull(first);
            assertNotNull(firstFrame);
            assertSame(first, renderer.cachedBitmapForTest());
            assertSame(firstFrame, renderer.cachedFrameForTest());
        } finally {
            canvas.recycle();
        }
    }

    @Test
    public void matteFillsOnlyApertureLetterboxAndNeverTouchesTheShell() {
        MenuRenderer renderer = new MenuRenderer();
        MenuPresentation presentation = visiblePausePresentation();
        Bitmap canvas = Bitmap.createBitmap(1000, 900, Bitmap.Config.ARGB_8888);
        canvas.eraseColor(0xffff00ff);
        try {
            renderer.draw(new Canvas(canvas), presentation, new RectF(50, 60, 950, 760));

            assertEquals(0xffff00ff, canvas.getPixel(20, 20));
            assertEquals(MenuRenderer.MENU_MATTE, canvas.getPixel(55, 100));
        } finally {
            canvas.recycle();
        }
    }

    private static MenuPresentation visiblePausePresentation() {
        MenuController controller = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation presentation) {
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
            }
        });
        controller.show(MenuRoute.PAUSE_CONSOLE);
        return controller.presentation();
    }

    private static void assertRect(Rect actual, int left, int top, int right, int bottom) {
        assertEquals(left, actual.left);
        assertEquals(top, actual.top);
        assertEquals(right, actual.right);
        assertEquals(bottom, actual.bottom);
    }
}
