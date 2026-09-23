package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PressedControlsAndroidTest {
    @Test
    public void allSkinsKeepTheirApertureAndUnpressedPixels() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RasterSkin[] skins = {RasterSkin.portrait(context), RasterSkin.landscape(context),
                RasterSkin.cgbPortrait(context), RasterSkin.cgbLandscape(context),
                RasterSkin.sgbPortrait(context), RasterSkin.sgbLandscape(context)};
        PressedControlsArt art = new PressedControlsArt(context);
        for (int index = 0; index < skins.length; index++) {
            boolean portrait = index % 2 == 0;
            int width = portrait ? 941 : 1672, height = portrait ? 1672 : 941;
            SkinTransform transform = skins[index].transform(width, height);
            Bitmap neutral = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            skins[index].draw(new Canvas(neutral), new Paint(), transform);
            Bitmap pressed = neutral.copy(Bitmap.Config.ARGB_8888, true);
            art.draw(new Canvas(pressed), new Paint(Paint.FILTER_BITMAP_FLAG), transform, 255);
            int[] before = new int[width * height], after = new int[width * height];
            neutral.getPixels(before, 0, width, 0, 0, width, height);
            pressed.getPixels(after, 0, width, 0, 0, width, height);
            int changed = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = y * width + x;
                    assertEquals("aperture alpha", before[offset] >>> 24, after[offset] >>> 24);
                    if (before[offset] == after[offset]) continue;
                    changed++;
                    boolean inside = false;
                    for (PressedControlLayout control : PressedControlLayout.values()) {
                        SkinTransform.Bounds bounds = control.bounds(width, height);
                        inside |= x >= Math.floor(bounds.left()) && x <= Math.ceil(bounds.right())
                                && y >= Math.floor(bounds.top()) && y <= Math.ceil(bounds.bottom());
                    }
                    assertTrue("only button artwork changes", inside);
                }
            }
            assertTrue("pressed state is visible", changed > 10_000);
            neutral.recycle();
            pressed.recycle();
        }
    }

    @Test
    public void everyDpadDirectionAndDiagonalKeepsInactiveArrowsNeutral() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RasterSkin skin = RasterSkin.portrait(context);
        SkinTransform transform = skin.transform(941, 1672);
        Bitmap neutral = Bitmap.createBitmap(941, 1672, Bitmap.Config.ARGB_8888);
        skin.draw(new Canvas(neutral), new Paint(), transform);
        PressedControlsArt art = new PressedControlsArt(context);
        Button[] directions = {Button.UP, Button.RIGHT, Button.DOWN, Button.LEFT};
        int[][] points = {{196, 1080}, {277, 1158}, {196, 1241}, {111, 1158}};
        for (int index = 0; index < 8; index++) {
            int first = index / 2;
            int mask = TouchPressState.bit(directions[first]);
            if (index % 2 != 0) mask |= TouchPressState.bit(directions[(first + 1) % 4]);
            Bitmap pressed = neutral.copy(Bitmap.Config.ARGB_8888, true);
            art.draw(new Canvas(pressed), new Paint(), transform, mask);
            for (int direction = 0; direction < 4; direction++) {
                int x = points[direction][0], y = points[direction][1];
                if (TouchPressState.contains(mask, directions[direction])) {
                    assertNotEquals("active arrow", neutral.getPixel(x, y), pressed.getPixel(x, y));
                } else {
                    assertEquals("inactive arrow", neutral.getPixel(x, y), pressed.getPixel(x, y));
                }
            }
            pressed.recycle();
        }
        neutral.recycle();
    }

    @Test
    public void touchSlideCancelAndFocusLossClearVisiblePresses() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            CoffeeGbSurfaceView view = new CoffeeGbSurfaceView(context);
            AndroidInputRouter router = new AndroidInputRouter(new PlayerInputHub());
            view.layout(0, 0, 941, 1672);
            view.attach(new NativeFrameStore(), router);
            try {
                touch(view, MotionEvent.ACTION_DOWN, 738, 1180);
                assertEquals(TouchPressState.bit(Button.A) | TouchPressState.bit(Button.B), view.pressedButtons());
                touch(view, MotionEvent.ACTION_MOVE, 810, 1152);
                assertEquals(TouchPressState.bit(Button.A), view.pressedButtons());
                touch(view, MotionEvent.ACTION_MOVE, 470, 500);
                assertEquals(0, view.pressedButtons());
                touch(view, MotionEvent.ACTION_MOVE, 100, 1080);
                assertEquals(TouchPressState.bit(Button.UP) | TouchPressState.bit(Button.LEFT), view.pressedButtons());
                touch(view, MotionEvent.ACTION_CANCEL, 100, 1080);
                assertEquals(0, view.pressedButtons());
                touch(view, MotionEvent.ACTION_DOWN, 665, 1212);
                view.onWindowFocusChanged(false);
                assertEquals(0, view.pressedButtons());
                touch(view, MotionEvent.ACTION_DOWN, 665, 1212);
                view.detach();
                assertEquals(0, view.pressedButtons());
            } finally {
                view.detach();
                router.close();
            }
        });
    }

    private static void touch(CoffeeGbSurfaceView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        try {
            assertTrue(view.onTouchEvent(event));
        } finally {
            event.recycle();
        }
    }
}
