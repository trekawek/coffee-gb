package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuKey;
import eu.rekawek.coffeegb.ui.menu.MenuPageLayout;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPagination;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
    public void pointersResolvePaintedItemsAndScrollAnchorsButNeverEmptyRowsOrDividers() {
        MenuPresentation shortList = presentation(MenuRoute.SETTINGS, "SETTINGS", "", List.of(),
                List.of(button("first", "FIRST"),
                        MenuPageSpec.Item.button("disabled", "DISABLED", "", false)),
                "first", MenuPreview.empty());
        assertEquals("first", Proposal3MenuCompositor.hitTest(shortList, 500, 150)
                .orElseThrow().itemId());
        for (int[] point : List.of(new int[]{500, 193}, new int[]{500, 220},
                new int[]{500, 300}, new int[]{420, 150}, new int[]{920, 150})) {
            assertTrue(Proposal3MenuCompositor.hitTest(shortList, point[0], point[1]).isEmpty());
        }

        MenuPresentation start = presentation(MenuRoute.SAVE_STATES, "SAVE STATES", "", List.of(),
                buttons(13, 0), "item-0", MenuPreview.empty());
        var down = Proposal3MenuCompositor.hitTest(start, 660, 600).orElseThrow();
        assertEquals("item-5", down.itemId());
        assertEquals(MenuKey.DOWN, down.key());
        MenuPresentation end = presentation(MenuRoute.SAVE_STATES, "SAVE STATES", "", List.of(),
                buttons(13, 0), "item-12", MenuPreview.empty());
        var up = Proposal3MenuCompositor.hitTest(end, 660, 150).orElseThrow();
        assertEquals("item-7", up.itemId());
        assertEquals(MenuKey.UP, up.key());

        List<MenuPageSpec.Item> unavailableEdge = new ArrayList<>(buttons(13, 0));
        unavailableEdge.set(5, MenuPageSpec.Item.button("item-5", "UNAVAILABLE", "", false));
        MenuPresentation disabled = presentation(MenuRoute.RECENT_GAMES, "RECENT", "", List.of(),
                unavailableEdge, "item-0", MenuPreview.empty());
        assertEquals("scrolling must not anchor on an unavailable row", "item-4",
                Proposal3MenuCompositor.hitTest(disabled, 660, 600).orElseThrow().itemId());
    }

    @Test
    public void pointersUseFullWidthRowsAndOnlyAdvertisedFooterActions() {
        MenuPresentation wide = fullWidthPresentation(List.of(button("rom", "GAME.GBC")),
                "rom", MenuPagination.singlePage());
        assertEquals("rom", Proposal3MenuCompositor.hitTest(wide, 40, 150)
                .orElseThrow().itemId());
        MenuPresentation firstPage = fullWidthPresentation(List.of(button("rom", "GAME.GBC")),
                "rom", new MenuPagination(0, 3));
        assertTrue(Proposal3MenuCompositor.hitTest(firstPage, 70, 690).isEmpty());
        assertEquals(MenuKey.RIGHT, Proposal3MenuCompositor.hitTest(firstPage, 290, 690)
                .orElseThrow().key());
        MenuPresentation lastPage = fullWidthPresentation(List.of(button("rom", "GAME.GBC")),
                "rom", new MenuPagination(2, 3));
        assertEquals(MenuKey.LEFT, Proposal3MenuCompositor.hitTest(lastPage, 70, 690)
                .orElseThrow().key());
        assertTrue(Proposal3MenuCompositor.hitTest(lastPage, 290, 690).isEmpty());
        MenuController controller = new MenuController(new NoopListener());
        controller.show(MenuRoute.AUDIO);
        assertTrue("A is inert for a slider", Proposal3MenuCompositor.hitTest(
                controller.presentation(), 480, 690).isEmpty());
        assertEquals(MenuKey.LEFT, Proposal3MenuCompositor.hitTest(
                controller.presentation(), 630, 150).orElseThrow().key());
        assertEquals(MenuKey.RIGHT, Proposal3MenuCompositor.hitTest(
                controller.presentation(), 800, 150).orElseThrow().key());
        controller.setRootDismissAllowed(false);
        controller.show(MenuRoute.LIBRARY);
        assertTrue("root Back is unavailable", Proposal3MenuCompositor.hitTest(
                controller.presentation(), 760, 690).isEmpty());
        controller.hide();
        assertTrue(Proposal3MenuCompositor.hitTest(controller.presentation(), 500, 150).isEmpty());
    }

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
            if (candidate == MenuRoute.FILE_BROWSER) {
                continue;
            }
            assertEquals("dynamic regions must not vary by route", masks,
                    Proposal3MenuCompositor.dynamicMasks(candidate));
        }
        assertEquals(Proposal3MenuCompositor.dynamicMasks(MenuPageLayout.FULL_WIDTH_LIST),
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.FILE_BROWSER));
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
    public void footerResumeLabelFitsWithoutEllipsis() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();

        assertTrue(atlas.renderedWidth(Proposal3MenuCompositor.footerTextRole(), "RESUME")
                <= Proposal3MenuCompositor.footerBackBounds().width());
        assertTrue(Proposal3MenuCompositor.footerBackBounds().right()
                <= MenuScreenTemplate.FOOTER.right());
    }

    @Test
    public void titleWordsUseAFullGlyphWidthOfSeparation() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();

        assertTrue(atlas.advance(Proposal3GlyphAtlas.Role.SEMIBOLD, ' ')
                + Proposal3MenuCompositor.titleExtraWordSpacing()
                >= atlas.advance(Proposal3GlyphAtlas.Role.SEMIBOLD, 'A'));
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
    public void legacyCheckboxAliasesOnlyDriveTheBoxAndNeverPaintStatusText() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] unchecked = pixels(compositor, presentation(MenuRoute.DISPLAY, "CHECKBOX", "",
                List.of(), List.of(MenuPageSpec.Item.checkbox(
                        "checkbox", "SGB BORDER", false, true)), "checkbox",
                MenuPreview.empty()));

        for (String alias : List.of("NO", "FALSE", "DISABLED", "UNCHECKED")) {
            int[] candidate = pixels(compositor, presentation(MenuRoute.DISPLAY, "CHECKBOX", "",
                    List.of(), List.of(MenuPageSpec.Item.checkbox(
                            "checkbox", "SGB BORDER", alias, true)), "checkbox",
                    MenuPreview.empty()));
            assertArrayEquals(alias + " leaked checkbox status text into the row", unchecked,
                    candidate);
        }

        int[] checked = pixels(compositor, presentation(MenuRoute.DISPLAY, "CHECKBOX", "",
                List.of(), List.of(MenuPageSpec.Item.checkbox(
                        "checkbox", "SGB BORDER", true, true)), "checkbox",
                MenuPreview.empty()));
        for (String alias : List.of("ON", "YES", "TRUE", "ENABLED", "CHECKED")) {
            int[] candidate = pixels(compositor, presentation(MenuRoute.DISPLAY, "CHECKBOX", "",
                    List.of(), List.of(MenuPageSpec.Item.checkbox(
                            "checkbox", "SGB BORDER", alias, true)), "checkbox",
                    MenuPreview.empty()));
            assertArrayEquals(alias + " leaked checkbox status text into the row", checked,
                    candidate);
        }
    }

    @Test
    public void focusedRowsUseAReadableLightForeground() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] focused = pixels(compositor, presentation(MenuRoute.SYSTEM, "SELECTION", "",
                List.of(), List.of(button("focus", "FOCUSED ITEM")), "focus",
                MenuPreview.empty()));
        MenuRect row = MenuScreenTemplate.optionRow(0);

        assertEquals("focus arrow must use the light selected foreground",
                MenuRaster.SELECTED_TEXT,
                focused[(row.y() + row.height() / 2) * WIDTH + row.x() + 5]);

        Proposal3WidgetSkins.Sprite selected = Proposal3WidgetSkins.load()
                .surface(Proposal3WidgetSkins.Surface.SELECTED);
        double minimumContrast = Double.POSITIVE_INFINITY;
        for (int y = 0; y < selected.height(); y++) {
            for (int x = 0; x < selected.width(); x++) {
                minimumContrast = Math.min(minimumContrast,
                        contrastRatio(MenuRaster.SELECTED_TEXT, selected.pixel(x, y)));
            }
        }
        assertTrue("selected foreground contrast was only " + minimumContrast,
                minimumContrast >= 4.5);
    }

    @Test
    public void buttonStatusIsRenderedInTheReusableTrailingDetailRegion() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] empty = pixels(compositor, presentation(MenuRoute.SAVE_STATES, "SAVE STATES", "",
                List.of(), List.of(MenuPageSpec.Item.button(
                        "slot-9", "SLOT 9", "", true)), "slot-9", MenuPreview.empty()));
        int[] saved = pixels(compositor, presentation(MenuRoute.SAVE_STATES, "SAVE STATES", "",
                List.of(), List.of(MenuPageSpec.Item.button(
                        "slot-9", "SLOT 9", "SAVED", true)), "slot-9", MenuPreview.empty()));

        MenuRect row = MenuScreenTemplate.optionRow(0);
        MenuRect detail = new MenuRect(row.x() + 316, row.y(), row.width() - 336,
                row.height());
        assertTrue("button status was not painted", differences(empty, saved, detail) > 0);
        assertPixelsEqualOutside(empty, saved, List.of(detail));
    }

    @Test
    public void rightAlignedTextKeepsTheCompleteTrailingGlyph() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();

        for (String value : List.of("SAVED", "100%", "PERFORMANCE", "FAST-FORWARD")) {
            int renderedWidth = atlas.renderedWidth(Proposal3GlyphAtlas.Role.MEDIUM, value);
            MenuRaster left = new MenuRaster(new int[WIDTH * HEIGHT]);
            MenuRaster right = new MenuRaster(new int[WIDTH * HEIGHT]);
            left.drawText(atlas, Proposal3GlyphAtlas.Role.MEDIUM, value,
                    new MenuRect(100, 100, 300, 72), MenuRaster.PAPER_TEXT,
                    MenuRaster.HorizontalAlignment.LEFT);
            right.drawText(atlas, Proposal3GlyphAtlas.Role.MEDIUM, value,
                    new MenuRect(500, 100, renderedWidth, 72), MenuRaster.PAPER_TEXT,
                    MenuRaster.HorizontalAlignment.RIGHT);

            assertEquals(value + " lost ink when right-aligned",
                    paintedPixels(left.pixels()), paintedPixels(right.pixels()));
        }
    }

    @Test
    public void trailingWidgetsUseTheSafeInsetAndOneHundredPercentIsNotEllipsized() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] empty = pixels(compositor, presentation(MenuRoute.AUDIO, "TRAILING", "",
                List.of(), List.of(MenuPageSpec.Item.button(
                        "empty", "", "", true)), "empty", MenuPreview.empty()));
        int[] saved = pixels(compositor, presentation(MenuRoute.SAVE_STATES, "TRAILING", "",
                List.of(), List.of(MenuPageSpec.Item.button(
                        "saved", "", "SAVED", true)), "saved", MenuPreview.empty()));
        int[] dropdown = pixels(compositor, presentation(MenuRoute.SYSTEM, "TRAILING", "",
                List.of(), List.of(MenuPageSpec.Item.dropdown(
                        "dropdown", "BOOTSTRAP", "FAST-FORWARD", true)), "dropdown",
                MenuPreview.empty()));
        int[] dropdownEllipsis = pixels(compositor, presentation(MenuRoute.SYSTEM, "TRAILING", "",
                List.of(), List.of(MenuPageSpec.Item.dropdown(
                        "dropdown", "BOOTSTRAP", "FAST-FORW...", true)), "dropdown",
                MenuPreview.empty()));
        int[] checkbox = pixels(compositor, presentation(MenuRoute.DISPLAY, "TRAILING", "",
                List.of(), List.of(MenuPageSpec.Item.checkbox(
                        "checkbox", "", true, true)), "checkbox", MenuPreview.empty()));
        int[] oneHundred = pixels(compositor, presentation(MenuRoute.AUDIO, "TRAILING", "",
                List.of(), List.of(MenuPageSpec.Item.slider(
                        "volume", "", "100%", true, 100)), "volume", MenuPreview.empty()));
        int[] ellipsis = pixels(compositor, presentation(MenuRoute.AUDIO, "TRAILING", "",
                List.of(), List.of(MenuPageSpec.Item.slider(
                        "volume", "", "...", true, 100)), "volume", MenuPreview.empty()));

        MenuRect row = MenuScreenTemplate.optionRow(0);
        MenuRect rightInset = new MenuRect(row.right() - 20, row.y(), 20, row.height());
        for (int[] candidate : List.of(saved, dropdown, checkbox, oneHundred)) {
            assertEquals("trailing widget entered the shared right inset", 0,
                    differences(empty, candidate, rightInset));
        }
        MenuRect value = new MenuRect(row.x() + 376, row.y(), 88, row.height());
        assertTrue("100% was rendered as an ellipsis",
                differences(oneHundred, ellipsis, value) > 0);
        MenuRect field = new MenuRect(row.x() + 38, row.y() + 36,
                row.width() - 58, 36);
        assertTrue("FAST-FORWARD was rendered as an ellipsis",
                differences(dropdown, dropdownEllipsis, field) > 0);

        int fieldRight = row.right() - 20;
        int fieldCenterY = row.y() + 54;
        assertEquals("dropdown chevron touched the field border", MenuRaster.PAPER,
                dropdown[(fieldCenterY - 2) * WIDTH + fieldRight - 4]);
        assertEquals(MenuRaster.INK,
                dropdown[(fieldCenterY - 2) * WIDTH + fieldRight - 3]);
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

    @Test
    public void fullWidthListUsesItsGeneratedSinglePanelAndNeverPaintsOutsideItsMasks()
            throws Exception {
        MenuPresentation presentation = fullWidthPresentation(
                List.of(button("parent", ".."), button("rom", "A VERY LONG ROM NAME.GBC")),
                "parent", MenuPagination.singlePage());
        int[] base = Proposal3TemplateFrameCatalog.decode(MenuPageLayout.FULL_WIDTH_LIST)
                .copyPixels();
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] composed = compositor.compose(presentation).orElseThrow().copyPixels();

        assertPixelsEqualOutside(base, composed,
                Proposal3MenuCompositor.dynamicMasks(MenuPageLayout.FULL_WIDTH_LIST));
        int[] split = Proposal3TemplateFrameCatalog.decode(MenuRoute.LIBRARY).copyPixels();
        MenuRect formerSeam = new MenuRect(405, 150, 20, 450);
        assertTrue("generated full-width template retained the split-panel seam",
                differences(split, base, formerSeam) > 0);
        assertEquals(1, compositor.cachedTemplateRouteCount());
    }

    @Test
    public void focusedLongFilenameWaitsOneSecondThenScrollsSlowlyAndStopsAtItsTail() {
        assertEquals(1_000_000_000L, Proposal3MenuCompositor.marqueeDelayNanos());
        assertEquals(24, Proposal3MenuCompositor.marqueePixelsPerSecond());
        AtomicLong clock = new AtomicLong();
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor(clock::get);
        String longName = "A-VERY-LONG-GAME-BOY-COLOR-ROM-FILENAME-WITH-REGION-AND-REVISION-"
                + "DETAILS-THAT-CANNOT-FIT-IN-ONE-FULL-WIDTH-ROW.GBC";
        MenuPresentation presentation = fullWidthPresentation(
                List.of(button("rom", longName)), "rom", MenuPagination.singlePage());

        MenuArgbFrame initial = compositor.compose(presentation).orElseThrow();
        clock.set(Proposal3MenuCompositor.marqueeDelayNanos() - 1);
        assertSame("filename moved before its one-second dwell elapsed", initial,
                compositor.compose(presentation).orElseThrow());

        clock.set(Proposal3MenuCompositor.marqueeDelayNanos() + 500_000_000L);
        MenuArgbFrame moving = compositor.compose(presentation).orElseThrow();
        assertNotSame(initial, moving);
        MenuRect label = Proposal3MenuCompositor.fullWidthLabelBounds(0);
        assertTrue("overflowing filename did not move", differences(initial.copyPixels(),
                moving.copyPixels(), label) > 0);
        assertPixelsEqualOutside(initial.copyPixels(), moving.copyPixels(), List.of(label));

        clock.set(120_000_000_000L);
        MenuArgbFrame atTail = compositor.compose(presentation).orElseThrow();
        clock.set(121_000_000_000L);
        assertSame("clamped filename kept animating after its tail was revealed", atTail,
                compositor.compose(presentation).orElseThrow());
    }

    @Test
    public void shortFilenameNeverAnimatesAndChangingFocusRestartsTheDwell() {
        AtomicLong clock = new AtomicLong();
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor(clock::get);
        MenuPresentation shortName = fullWidthPresentation(
                List.of(button("short", "TETRIS.GB")), "short", MenuPagination.singlePage());
        MenuArgbFrame shortInitial = compositor.compose(shortName).orElseThrow();
        clock.set(60_000_000_000L);
        assertSame(shortInitial, compositor.compose(shortName).orElseThrow());

        String first = "FIRST-" + "LONG-FILENAME-".repeat(8) + ".GB";
        String second = "SECOND-" + "LONG-FILENAME-".repeat(8) + ".GBC";
        MenuPresentation firstFocused = fullWidthPresentation(
                List.of(button("first", first), button("second", second)), "first",
                MenuPagination.singlePage());
        compositor.compose(firstFocused).orElseThrow();
        clock.addAndGet(Proposal3MenuCompositor.marqueeDelayNanos() + 1_000_000_000L);
        compositor.compose(firstFocused).orElseThrow();

        MenuPresentation secondFocused = fullWidthPresentation(
                List.of(button("first", first), button("second", second)), "second",
                MenuPagination.singlePage());
        int[] reset = compositor.compose(secondFocused).orElseThrow().copyPixels();
        Proposal3MenuCompositor baseline = new Proposal3MenuCompositor(clock::get);
        assertArrayEquals("newly focused filename skipped its one-second dwell", reset,
                baseline.compose(secondFocused).orElseThrow().copyPixels());
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

    private static MenuPresentation fullWidthPresentation(List<MenuPageSpec.Item> items,
            String focus, MenuPagination pagination) {
        MenuPageSpec spec = new MenuPageSpec(MenuRoute.FILE_BROWSER, "COFFEE GB", "/ROMS", "",
                "", List.of(), items, 1, FOOTER, focus, MenuPreview.empty(),
                MenuPageLayout.FULL_WIDTH_LIST, pagination);
        MenuController controller = controller(spec);
        controller.show(MenuRoute.FILE_BROWSER);
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

    private static int paintedPixels(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) {
            if (pixel != 0) {
                count++;
            }
        }
        return count;
    }

    private static double contrastRatio(int first, int second) {
        double lighter = Math.max(relativeLuminance(first), relativeLuminance(second));
        double darker = Math.min(relativeLuminance(first), relativeLuminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(int color) {
        double red = linearChannel((color >> 16) & 0xff);
        double green = linearChannel((color >> 8) & 0xff);
        double blue = linearChannel(color & 0xff);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double linearChannel(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
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
