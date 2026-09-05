package eu.rekawek.coffeegb.ui.menu.artwork;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Proposal3GlyphAtlasTest {

    private static final int WIDTH = MenuArtworkCatalog.PACKAGED_WIDTH;
    private static final int HEIGHT = MenuArtworkCatalog.PACKAGED_HEIGHT;

    @Test
    public void wordBoundariesRemainVisibleBeyondWideGlyphOverhangs() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();

        for (Proposal3GlyphAtlas.Role role : Proposal3GlyphAtlas.Role.values()) {
            int glyph = atlas.index('M');
            int left = atlas.cellWidth(role);
            int right = 0;
            for (int x = 0; x < atlas.cellWidth(role); x++) {
                for (int y = 0; y < atlas.cellHeight(role); y++) {
                    if ((atlas.pixel(role, glyph, x, y) >>> 24) != 0) {
                        left = Math.min(left, x);
                        right = Math.max(right, x + 1);
                    }
                }
            }

            int visibleWordGap = atlas.measure(role, "M ") + left - right;
            assertTrue(role + " joins words across wide glyphs",
                    visibleWordGap >= (atlas.advance(role, 'M') + 1) / 2);
        }
    }

    @Test
    public void measuredMultiwordLabelsKeepAllInkWhenFittedAndRightAligned() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();

        for (Proposal3GlyphAtlas.Role role : Proposal3GlyphAtlas.Role.values()) {
            for (String label : List.of("D-PAD MOVE", "SAVE STATES", "LCD GHOSTING")) {
                MenuRaster reference = new MenuRaster(new int[WIDTH * HEIGHT]);
                MenuRaster fitted = new MenuRaster(new int[WIDTH * HEIGHT]);
                reference.drawText(atlas, role, label, new MenuRect(10, 10, 450, 72),
                        MenuRaster.PAPER_TEXT, MenuRaster.HorizontalAlignment.LEFT);
                fitted.drawText(atlas, role, label,
                        new MenuRect(500, 10, atlas.renderedWidth(role, label), 72),
                        MenuRaster.PAPER_TEXT, MenuRaster.HorizontalAlignment.RIGHT);

                assertEquals(role + " clipped or shortened " + label,
                        paintedPixels(reference), paintedPixels(fitted));
            }
        }
    }

    private static int paintedPixels(MenuRaster raster) {
        int count = 0;
        for (int pixel : raster.pixels()) {
            if (pixel != 0) {
                count++;
            }
        }
        return count;
    }
}
