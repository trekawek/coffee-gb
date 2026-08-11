package eu.rekawek.coffeegb.android.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuGeometryTest {

    @Test
    public void landscapeUsesTheStableLandscapeGridAndDoesNotClip() {
        MenuGeometry.Layout layout = MenuGeometry.forDisplay(920.0f, 718.0f);

        assertFalse(layout.portrait());
        assertEquals(MenuGeometry.LANDSCAPE_WIDTH, layout.logicalWidth());
        assertEquals(MenuGeometry.LANDSCAPE_HEIGHT, layout.logicalHeight());
        assertTrue(layout.scale() > 0.0f);
        assertTrue(layout.fits(920.0f, 718.0f));
        assertEquals(920.0f, layout.contentWidth(), 0.001f);
        assertEquals(690.0f, layout.contentHeight(), 0.001f);
        assertEquals(14.0f, layout.offsetY(), 0.001f);
    }

    @Test
    public void portraitReflowsToThePortraitGridAndDoesNotClip() {
        MenuGeometry.Layout layout = MenuGeometry.forDisplay(718.0f, 920.0f);

        assertTrue(layout.portrait());
        assertEquals(MenuGeometry.PORTRAIT_WIDTH, layout.logicalWidth());
        assertEquals(MenuGeometry.PORTRAIT_HEIGHT, layout.logicalHeight());
        assertTrue(layout.scale() > 0.0f);
        assertTrue(layout.fits(718.0f, 920.0f));
        assertEquals(690.0f, layout.contentWidth(), 0.001f);
        assertEquals(920.0f, layout.contentHeight(), 0.001f);
        assertEquals(14.0f, layout.offsetX(), 0.001f);
    }

    @Test
    public void verySmallAndInvalidWindowsRemainSafe() {
        MenuGeometry.Layout tiny = MenuGeometry.forDisplay(1.0f, 1.0f);
        assertTrue(tiny.scale() > 0.0f);
        assertTrue(tiny.fits(1.0f, 1.0f));

        MenuGeometry.Layout invalid = MenuGeometry.forDisplay(0.0f, 100.0f);
        assertEquals(0.0f, invalid.scale(), 0.0f);
        assertEquals(0, invalid.logicalWidth());
        assertEquals(0, invalid.logicalHeight());
    }

    @Test
    public void shortWideThumbnailUsesIndependentAxesAndKeepsDecorationInBounds() {
        MenuGeometry.ThumbnailGrid grid = MenuGeometry.thumbnailGrid(294.0f, 28.0f);

        assertTrue(grid.valid());
        assertEquals(294.0f / 36.0f, grid.unitX(), 0.001f);
        assertEquals(28.0f / 20.0f, grid.unitY(), 0.001f);
        assertTrue(grid.unitY() < grid.unitX());
        for (int index = 0; index < 13; index++) {
            float column = index * 7 % 31;
            float row = index * 11 % 19;
            assertTrue(grid.contains(column, row, column + 3, row + 2));
        }
        assertTrue(grid.contains(3, 13, 13, 17));
        assertTrue(grid.contains(23, 7, 31, 17));
        assertTrue(grid.contains(36.0f * 0.28f, 12, 36.0f * 0.28f + 4, 17));
        assertFalse(MenuGeometry.thumbnailGrid(294.0f, 0.0f).valid());
    }
}
