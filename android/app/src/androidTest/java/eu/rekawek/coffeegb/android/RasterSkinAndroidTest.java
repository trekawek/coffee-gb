package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RasterSkinAndroidTest {

    private static final float DELTA = 0.01f;

    @Test
    public void rasterResourcesMapDisplayAndBakedMenuCenterInBothOrientations() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertResourceGeometry(RasterSkin.portrait(context), 920, 1884,
                941, 1672, 91f, 203f, 849f, 888f, 135.5f, 86f);
        assertResourceGeometry(RasterSkin.landscape(context), 1884, 920,
                1672, 941, 377f, 104f, 1296f, 821f, 99.5f, 79.5f);
    }

    @Test
    public void transparentMenuOverlayIsFortyEightDpAndCenteredOnBakedGrille() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                CoffeeGbSurfaceView video = findView(activity.getWindow().getDecorView(),
                        CoffeeGbSurfaceView.class);
                View menu = findMenuOverlay(activity.getWindow().getDecorView());
                assertNotNull("video", video);
                assertNotNull("menu button", menu);
                assertEquals("plain transparent View", View.class, menu.getClass());
                assertTrue("video laid out", video.getWidth() > 0 && video.getHeight() > 0);

                PointF expected = video.menuControlCenter(video.getWidth(), video.getHeight());
                float actualX = menu.getX() + menu.getWidth() / 2f - video.getX();
                float actualY = menu.getY() + menu.getHeight() / 2f - video.getY();
                int target = Math.round(48f * activity.getResources()
                        .getDisplayMetrics().density);

                assertEquals(target, menu.getWidth());
                assertEquals(target, menu.getHeight());
                assertEquals(expected.x, actualX, 1f);
                assertEquals(expected.y, actualY, 1f);
                assertNull("transparent menu background", menu.getBackground());
                assertNull("transparent menu foreground", menu.getForeground());
                assertNull("transparent menu state animator", menu.getStateListAnimator());
                assertEquals(0.0f, menu.getElevation(), 0.0f);
                assertTrue("menu remains clickable", menu.isClickable());
                assertTrue("menu remains focusable", menu.isFocusable());
                assertTrue("menu remains accessibility-visible",
                        menu.isImportantForAccessibility());
            });
        }
    }

    private static View findMenuOverlay(View view) {
        CharSequence description = view.getContentDescription();
        if (description != null && (description.equals("Open Coffee GB menu")
                || description.equals("Close Coffee GB menu"))) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findMenuOverlay(group.getChildAt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void assertResourceGeometry(RasterSkin skin, int viewWidth, int viewHeight,
            int skinWidth, int skinHeight, float displayLeft, float displayTop,
            float displayRight, float displayBottom, float menuX, float menuY) {
        SkinTransform transform = skin.transform(viewWidth, viewHeight);
        RectF skinBounds = skin.skinBounds(transform);
        RectF displayBounds = skin.displayBounds(transform);
        PointF menuCenter = skin.menuControlCenter(transform);
        SkinTransform.Point nativeDisplayTopLeft = transform.inversePoint(
                displayBounds.left, displayBounds.top);
        SkinTransform.Point nativeDisplayBottomRight = transform.inversePoint(
                displayBounds.right, displayBounds.bottom);
        SkinTransform.Point nativeMenu = transform.inversePoint(menuCenter.x, menuCenter.y);

        assertEquals(skinWidth, transform.skinWidth());
        assertEquals(skinHeight, transform.skinHeight());
        assertTrue(skinBounds.contains(displayBounds));
        assertEquals(displayLeft, nativeDisplayTopLeft.x(), DELTA);
        assertEquals(displayTop, nativeDisplayTopLeft.y(), DELTA);
        assertEquals(displayRight, nativeDisplayBottomRight.x(), DELTA);
        assertEquals(displayBottom, nativeDisplayBottomRight.y(), DELTA);
        assertEquals(menuX, nativeMenu.x(), DELTA);
        assertEquals(menuY, nativeMenu.y(), DELTA);
        assertTrue(skinBounds.contains(menuCenter.x, menuCenter.y));
    }

    private static <T extends View> T findView(View view, Class<T> type) {
        if (type.isInstance(view)) {
            return type.cast(view);
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = findView(group.getChildAt(index), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
