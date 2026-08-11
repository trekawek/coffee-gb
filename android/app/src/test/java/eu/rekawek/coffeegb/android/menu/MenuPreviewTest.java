package eu.rekawek.coffeegb.android.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MenuPreviewTest {

    @Test
    public void readyPixelsAreDetachedAndBounded() {
        int[] pixels = {1, 2, 3, 4};
        MenuPreview preview = MenuPreview.ready(2, 2, pixels);
        pixels[0] = 9;
        assertEquals(1, preview.copyPixels()[0]);
        int[] copy = preview.copyPixels();
        copy[1] = 9;
        assertEquals(2, preview.copyPixels()[1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnboundedRenderPayload() {
        MenuPreview.ready(MenuPreview.MAX_PIXELS + 1, 1,
                new int[MenuPreview.MAX_PIXELS + 1]);
    }
}
