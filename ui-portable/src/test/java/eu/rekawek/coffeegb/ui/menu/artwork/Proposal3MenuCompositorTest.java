package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Architecture-level contracts for the common menu template compositor. */
public class Proposal3MenuCompositorTest {

    private static final int WIDTH = MenuArtworkCatalog.PACKAGED_WIDTH;
    private static final int HEIGHT = MenuArtworkCatalog.PACKAGED_HEIGHT;
    private static final List<String> FOOTER =
            List.of("D-PAD MOVE", "A CHOOSE", "B BACK");

    @Test
    public void everyRouteProducesOneCanonical924By736Frame() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        MenuPreview preview = preview();

        for (MenuRoute route : MenuRoute.values()) {
            MenuArgbFrame frame = compositor.compose(presentation(route, "COMMON TITLE", "",
                    List.of(), buttons(1, 0), "item-0", preview)).orElseThrow();
            assertEquals(route + " width", 924, frame.width());
            assertEquals(route + " height", 736, frame.height());
        }

        assertEquals("all routes must share one decoded template", 1,
                compositor.cachedTemplateRouteCount());
        assertEquals("the compositor retains only its current frame", 1,
                compositor.cachedComposedFrameCount());
    }

    @Test
    public void composedContentCannotChangeTheSharedBaseOutsideDynamicRegions() throws Exception {
        MenuRoute route = MenuRoute.SYSTEM;
        int[] base = Proposal3TemplateFrameCatalog.decode(route).copyPixels();
        int[] composed = new Proposal3MenuCompositor().compose(presentation(route,
                "CUSTOM TITLE", "CUSTOM SUBTITLE", List.of("SECOND LINE"),
                mixedWidgets(), "slider", preview())).orElseThrow().copyPixels();

        List<MenuRect> masks = Proposal3MenuCompositor.dynamicMasks(route);
        for (MenuRoute candidate : MenuRoute.values()) {
            assertEquals("dynamic regions must not vary by route", masks,
                    Proposal3MenuCompositor.dynamicMasks(candidate));
        }
        assertPixelsEqualOutside(base, composed, masks);
    }

    @Test
    public void titlePreviewAndOptionalSubtitleAreIndependentCustomizations() {
        MenuPresentation plain = presentation(MenuRoute.PAUSE_CONSOLE, "TITLE ONE", "",
                List.of(), buttons(2, 0), "item-0", MenuPreview.empty());
        MenuPresentation customized = presentation(MenuRoute.PAUSE_CONSOLE, "TITLE TWO",
                "SCREENSHOT CAPTION", List.of("DETACHED PREVIEW"), buttons(2, 0), "item-0",
                preview());
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] before = compositor.compose(plain).orElseThrow().copyPixels();
        int[] after = compositor.compose(customized).orElseThrow().copyPixels();

        assertTrue("title did not change", differences(before, after,
                MenuScreenTemplate.TITLE) > 0);
        assertTrue("preview did not change", differences(before, after,
                MenuScreenTemplate.PICTURE) > 0);
        assertTrue("subtitle did not change", differences(before, after,
                MenuScreenTemplate.SUBTITLE) > 0);
        assertPixelsEqualOutside(before, after, List.of(MenuScreenTemplate.TITLE,
                MenuScreenTemplate.PICTURE, MenuScreenTemplate.SUBTITLE));
    }

    @Test
    public void moreThanSevenItemsUseGenericStartMiddleAndEndArrowSlots() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        List<MenuPageSpec.Item> thirteen = buttons(13, 0);

        int[] start = pixels(compositor, presentation(MenuRoute.RECENT_GAMES, "OVERFLOW", "",
                List.of(), thirteen, "item-0", preview()));
        int[] startBaseline = pixels(compositor, presentation(MenuRoute.RECENT_GAMES, "OVERFLOW",
                "", List.of(), buttons(7, 0), "item-0", preview()));
        assertEquals(0, differences(startBaseline, start, arrowRegion(0)));
        assertTrue(differences(startBaseline, start, arrowRegion(6)) > 0);
        assertArrowPoints(start, 6, false);

        List<MenuPageSpec.Item> endBaselineItems = new ArrayList<>();
        endBaselineItems.add(button("placeholder", "PLACEHOLDER"));
        for (int index = 7; index < 13; index++) {
            endBaselineItems.add(button("item-" + index, "ITEM " + index));
        }
        int[] end = pixels(compositor, presentation(MenuRoute.RECENT_GAMES, "OVERFLOW", "",
                List.of(), thirteen, "item-12", preview()));
        int[] endBaseline = pixels(compositor, presentation(MenuRoute.RECENT_GAMES, "OVERFLOW",
                "", List.of(), endBaselineItems, "item-12", preview()));
        assertTrue(differences(endBaseline, end, arrowRegion(0)) > 0);
        assertEquals(0, differences(endBaseline, end, arrowRegion(6)));
        assertArrowPoints(end, 0, true);

        List<MenuPageSpec.Item> middleBaselineItems = new ArrayList<>();
        middleBaselineItems.add(button("top-placeholder", "PLACEHOLDER"));
        for (int index = 4; index <= 8; index++) {
            middleBaselineItems.add(button("item-" + index, "ITEM " + index));
        }
        middleBaselineItems.add(button("bottom-placeholder", "PLACEHOLDER"));
        int[] middle = pixels(compositor, presentation(MenuRoute.RECENT_GAMES, "OVERFLOW", "",
                List.of(), thirteen, "item-6", preview()));
        int[] middleBaseline = pixels(compositor, presentation(MenuRoute.RECENT_GAMES, "OVERFLOW",
                "", List.of(), middleBaselineItems, "item-6", preview()));
        assertTrue(differences(middleBaseline, middle, arrowRegion(0)) > 0);
        assertTrue(differences(middleBaseline, middle, arrowRegion(6)) > 0);
        for (int row = 1; row < 6; row++) {
            assertEquals("overflow altered content slot " + row, 0,
                    differences(middleBaseline, middle, arrowRegion(row)));
        }
    }

    @Test
    public void hiddenPresentationsClearOnlyTheComposedFrameCache() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        MenuController controller = controller(new MenuPageSpec(MenuRoute.LIBRARY, "CACHE", "",
                "", "", List.of(), buttons(1, 0), 1, FOOTER, "item-0",
                MenuPreview.empty()));
        controller.show(MenuRoute.LIBRARY);

        MenuPresentation visible = controller.presentation();
        MenuArgbFrame first = compositor.compose(visible).orElseThrow();
        assertSame("same immutable presentation should reuse its frame", first,
                compositor.compose(visible).orElseThrow());
        assertEquals(1, compositor.cachedTemplateRouteCount());
        assertEquals(1, compositor.cachedComposedFrameCount());

        controller.hide();
        assertFalse(compositor.compose(controller.presentation()).isPresent());
        assertEquals(1, compositor.cachedTemplateRouteCount());
        assertEquals(0, compositor.cachedComposedFrameCount());

        controller.show(MenuRoute.LIBRARY);
        MenuArgbFrame recomposed = compositor.compose(controller.presentation()).orElseThrow();
        assertNotSame(first, recomposed);
        assertArrayEquals(first.copyPixels(), recomposed.copyPixels());
    }

    private static List<MenuPageSpec.Item> mixedWidgets() {
        return List.of(
                MenuPageSpec.Item.button("button", "BUTTON", "", true),
                MenuPageSpec.Item.dropdown("dropdown", "DROPDOWN", "VALUE", true),
                MenuPageSpec.Item.checkbox("checkbox", "CHECKBOX", "ON", true),
                MenuPageSpec.Item.slider("slider", "SLIDER", "50%", true, 50));
    }

    private static MenuPageSpec.Item button(String id, String label) {
        return MenuPageSpec.Item.button(id, label, "", true);
    }

    private static List<MenuPageSpec.Item> buttons(int count, int firstIndex) {
        ArrayList<MenuPageSpec.Item> result = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int index = firstIndex + offset;
            result.add(button("item-" + index, "ITEM " + index));
        }
        return List.copyOf(result);
    }

    private static MenuPresentation presentation(MenuRoute route, String title, String subtitle,
            List<String> sideLines, List<MenuPageSpec.Item> items, String focus,
            MenuPreview preview) {
        MenuPageSpec spec = new MenuPageSpec(route, title, "", "", subtitle, sideLines, items, 1,
                FOOTER, focus, preview);
        MenuController controller = controller(spec);
        controller.show(route);
        return controller.presentation();
    }

    private static MenuController controller(MenuPageSpec spec) {
        MenuController controller = new MenuController(new NoopListener());
        controller.setPage(spec);
        return controller;
    }

    private static MenuPreview preview() {
        return MenuPreview.ready(2, 2,
                new int[]{0xff18251a, 0xffd2ceaa, 0xff6b775d, 0xff24382a});
    }

    private static int[] pixels(Proposal3MenuCompositor compositor,
            MenuPresentation presentation) {
        return compositor.compose(presentation).orElseThrow().copyPixels();
    }

    private static MenuRect arrowRegion(int rowIndex) {
        MenuRect row = MenuScreenTemplate.optionRow(rowIndex);
        return new MenuRect(row.x() + row.width() / 2 - 32, row.y() + 14, 64,
                row.height() - 28);
    }

    private static int differences(int[] left, int[] right, MenuRect bounds) {
        int count = 0;
        for (int y = bounds.y(); y < bounds.bottom(); y++) {
            for (int x = bounds.x(); x < bounds.right(); x++) {
                if (left[y * WIDTH + x] != right[y * WIDTH + x]) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertArrowPoints(int[] pixels, int rowIndex, boolean up) {
        MenuRect row = MenuScreenTemplate.optionRow(rowIndex);
        int centerX = row.x() + row.width() / 2;
        int centerY = row.y() + row.height() / 2;
        int tipY = centerY + (up ? -8 : 8);
        int baseY = centerY + (up ? 8 : -8);
        assertEquals(MenuRaster.PAPER_TEXT, pixels[tipY * WIDTH + centerX]);
        assertEquals(MenuRaster.PAPER_TEXT, pixels[baseY * WIDTH + centerX - 8]);
        assertNotEquals(MenuRaster.PAPER_TEXT, pixels[tipY * WIDTH + centerX - 8]);
    }

    private static void assertPixelsEqualOutside(int[] expected, int[] actual,
            List<MenuRect> masks) {
        assertEquals(WIDTH * HEIGHT, expected.length);
        assertEquals(WIDTH * HEIGHT, actual.length);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (!insideAny(masks, x, y)) {
                    assertEquals("shared base changed at " + x + "," + y,
                            expected[y * WIDTH + x], actual[y * WIDTH + x]);
                }
            }
        }
    }

    private static boolean insideAny(List<MenuRect> masks, int x, int y) {
        for (MenuRect mask : masks) {
            if (mask.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private static final class NoopListener implements MenuController.Listener {
        @Override
        public void onPresentation(MenuPresentation presentation) {
        }

        @Override
        public void onItemSelected(MenuRoute route, String id, boolean secondary) {
        }

        @Override
        public void onHeaderSelected(MenuRoute route) {
        }
    }
}
