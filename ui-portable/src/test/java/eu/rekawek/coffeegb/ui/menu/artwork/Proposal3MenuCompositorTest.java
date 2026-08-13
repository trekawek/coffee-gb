package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Proposal3MenuCompositorTest {

    @Test
    public void everyRouteComposesToTheCanonicalFrame() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation presentation = defaultPresentation(route);
            MenuArgbFrame frame = compositor.compose(presentation).orElseThrow();
            assertEquals(route.name(), 924, frame.width());
            assertEquals(route.name(), 736, frame.height());
            assertEquals(route.name(), 1, compositor.cachedTemplateRouteCount());
            assertEquals(route.name(), 1, compositor.cachedComposedFrameCount());
        }
    }

    @Test
    public void hiddenPresentationProducesNoFrame() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        assertFalse(compositor.compose(new MenuController(listener()).presentation()).isPresent());
    }

    @Test
    public void canonicalFramesRenderRuntimeTextAboveTheTemplateLayer() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            int[] template = Proposal3TemplateFrameCatalog.decode(route).copyPixels();
            int[] composed = compositor.compose(defaultPresentation(route)).orElseThrow().copyPixels();
            assertFalse(route.name() + " did not render runtime content above the template",
                    Arrays.equals(template, composed));
            assertNoDifferenceOutside(template, composed,
                    Proposal3MenuCompositor.dynamicMasks(route));
        }
    }

    @Test
    public void templateAuthorityIsUntouchedOutsideDeclaredDynamicMasks() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation presentation = defaultPresentation(route);
            int[] template = Proposal3TemplateFrameCatalog.decode(route).copyPixels();
            int[] composed = compositor.compose(presentation).orElseThrow().copyPixels();
            List<MenuRect> masks = compositor.dynamicMasks(route);
            for (int index = 0; index < template.length; index++) {
                int x = index % 924;
                int y = index / 924;
                if (!inside(masks, x, y)) {
                    assertEquals(route.name() + " changed template pixel " + x + "," + y,
                            template[index], composed[index]);
                }
            }
        }
    }

    @Test
    public void outerArtworkFrameRemainsByteIdentical() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            int[] template = Proposal3TemplateFrameCatalog.decode(route).copyPixels();
            int[] composed = compositor.compose(defaultPresentation(route)).orElseThrow().copyPixels();
            for (int y : new int[]{2, 8, 727, 733}) {
                for (int x : new int[]{2, 8, 915, 921}) {
                    assertEquals(route + " outer frame " + x + "," + y,
                            template[y * 924 + x], composed[y * 924 + x]);
                }
            }
        }
    }

    @Test
    public void pauseOpenRomIsRenderedAsAnEqualHeightMenuRow() throws Exception {
        MenuPresentation presentation = presentation(new MenuPageSpec(MenuRoute.PAUSE_CONSOLE,
                "COFFEE GB", "", "", "TETRIS", List.of("PLAY TIME", "00:42",
                "BATTERY SAVE ACTIVE"),
                List.of(
                        item("resume", "RESUME", true),
                        item("save-state", "SAVE STATE", true),
                        item("load-state", "LOAD STATE", true),
                        item("open-rom", "OPEN ROM", true),
                        item("reset", "RESET GAME", true),
                        item("settings", "SETTINGS", true),
                        item("stop", "STOP GAME", true)), 1,
                List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), "open-rom",
                MenuPreview.empty()));
        int[] template = Proposal3TemplateFrameCatalog.decode(MenuRoute.PAUSE_CONSOLE).copyPixels();
        int[] composed = new Proposal3MenuCompositor().compose(presentation).orElseThrow().copyPixels();
        assertFalse(Arrays.equals(template, composed));
        assertTrue(differentInside(template, composed, Proposal3OverlayCatalog.PAUSE_OPEN_ROM));
        assertFalse(differentInside(template, composed, Proposal3OverlayCatalog.PAUSE_HEADER_ACTION));
        assertNoDifferenceOutside(template, composed,
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.PAUSE_CONSOLE));
    }

    @Test
    public void pauseRailUsesSevenExactEqualRowsWithUntouchedDividers() {
        Proposal3OverlayCatalog.RouteLayout layout = Proposal3OverlayCatalog.layout(
                MenuRoute.PAUSE_CONSOLE);
        assertEquals(7, layout.rows().size());
        for (int index = 0; index < layout.rows().size(); index++) {
            MenuRect row = layout.rows().get(index).bounds();
            assertEquals(424, row.x());
            assertEquals(484, row.width());
            assertEquals(72, row.height());
            assertEquals(121 + index * 74, row.y());
        }
        assertEquals(6, Proposal3OverlayCatalog.PAUSE_DIVIDERS.size());
        for (int index = 0; index < Proposal3OverlayCatalog.PAUSE_DIVIDERS.size(); index++) {
            MenuRect divider = Proposal3OverlayCatalog.PAUSE_DIVIDERS.get(index);
            assertEquals(193 + index * 74, divider.y());
            assertEquals(2, divider.height());
        }
    }

    @Test
    public void pausePreviewClearsTheEntireBezelInnerAperture() throws Exception {
        MenuRect aperture = Proposal3OverlayCatalog.PAUSE_PREVIEW;
        assertEquals(new MenuRect(30, 139, 351, 243), aperture);

        int[] template = Proposal3TemplateFrameCatalog.decode(MenuRoute.PAUSE_CONSOLE).copyPixels();
        for (int y = aperture.y(); y < aperture.bottom(); y++) {
            for (int x = aperture.x(); x < aperture.right(); x++) {
                assertEquals("placeholder pixel survived at " + x + "," + y,
                        0xff121b14, pixel(template, x, y));
            }
        }

        MenuPresentation populated = withPreview(defaultPresentation(MenuRoute.PAUSE_CONSOLE),
                MenuPreview.ready(160, 144, new int[160 * 144]));
        int[] composed = new Proposal3MenuCompositor().compose(populated).orElseThrow().copyPixels();
        // The 4:3 game frame is centered in this wider aperture, leaving matte at both corners.
        assertEquals(0xff121b14, pixel(composed, aperture.x(), aperture.y()));
        assertEquals(0xff121b14, pixel(composed, aperture.right() - 1, aperture.bottom() - 1));
    }

    @Test
    public void changingCanonicalNormalRowLabelChangesPixelsInsideItsMask() throws Exception {
        MenuPresentation first = defaultPresentation(MenuRoute.SETTINGS);
        MenuPresentation second = presentation(new MenuPageSpec(MenuRoute.SETTINGS,
                first.title(), first.context(), first.headerAction(), first.sideHeading(),
                first.sideLines(), List.of(
                        item("audio", "A DIFFERENT USER LABEL", true),
                        item("touch-controls", "TOUCH CONTROLS", true),
                        item("controller-mapping", "CONTROLLER MAPPING", true),
                        item("optional-devices", "OPTIONAL DEVICES", true),
                        item("video", "VIDEO", true),
                        item("system-profile", "SYSTEM PROFILE", true),
                        item("rewind-save", "REWIND & SAVE", true),
                        item("data-media", "DATA & MEDIA", true),
                        item("about", "ABOUT", true)), 1, first.footerHints()));
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] before = compositor.compose(first).orElseThrow().copyPixels();
        int[] after = compositor.compose(second).orElseThrow().copyPixels();
        assertFalse("canonical normal-row label was not rendered at compose time",
                Arrays.equals(before, after));
        List<MenuRect> masks = compositor.dynamicMasks(MenuRoute.SETTINGS);
        for (int index = 0; index < before.length; index++) {
            if (before[index] == after[index]) {
                continue;
            }
            int x = index % 924;
            int y = index / 924;
            assertTrue("changed pixel outside settings masks: " + x + "," + y,
                    inside(masks, x, y));
        }
    }

    @Test
    public void everyMultiRowRouteKeepsRowGlyphMaskAndBaselineAcrossFocusStates() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        Proposal3WidgetSkins skins = Proposal3WidgetSkins.load();
        for (MenuRoute route : MenuRoute.values()) {
            List<Proposal3OverlayCatalog.Slot> rows = Proposal3OverlayCatalog.layout(route).rows();
            if (rows.size() < 2) {
                continue;
            }

            String firstRowId = firstRowFocusId(route);
            MenuPresentation selected = withFocus(defaultPresentation(route), firstRowId);
            String alternateFocusId = focusTarget(route);
            assertFalse(route + " focus comparison must use two different items",
                    firstRowId.equals(alternateFocusId));
            MenuPresentation unselected = withFocus(selected, alternateFocusId);
            MenuPresentation.Item rowItem = itemById(selected, firstRowId);
            assertNotNull(route + " first visible row is missing from the presentation", rowItem);

            Proposal3OverlayCatalog.Slot slot = rows.get(0);
            MenuRect labelBounds = Proposal3MenuCompositor.rowLabelBoundsForAudit(route,
                    slot.bounds(), supportsRowDetails(route) && !rowItem.detail().isEmpty(), 0);
            int[] selectedPixels = compositor.compose(selected).orElseThrow().copyPixels();
            int[] unselectedPixels = compositor.compose(unselected).orElseThrow().copyPixels();
            boolean[] selectedMask = glyphMask(selectedPixels,
                    rowBackground(route, slot, true, skins), labelBounds);
            boolean[] unselectedMask = glyphMask(unselectedPixels,
                    rowBackground(route, slot, false, skins), labelBounds);

            assertTrue(route + " selected row has no detectable glyphs", hasGlyph(selectedMask));
            assertTrue(route + " unselected row has no detectable glyphs",
                    hasGlyph(unselectedMask));
            assertArrayEquals(route + " changed glyph pixels when focus moved away",
                    selectedMask, unselectedMask);
            assertEquals(route + " changed the actual glyph baseline when focus moved away",
                    glyphTop(selectedMask, labelBounds.width()),
                    glyphTop(unselectedMask, labelBounds.width()));
        }
    }

    @Test
    public void everyRouteRendersCommonAndPresentationChromeAtComposeTime() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation presentation = defaultPresentation(route);
            int[] template = Proposal3TemplateFrameCatalog.decode(route).copyPixels();
            int[] composed = compositor.compose(presentation).orElseThrow().copyPixels();
            for (Proposal3TextCatalog.TextRegion region : Proposal3TextCatalog.regions(route)) {
                String value = runtimeRegionValue(region, presentation);
                if (value.isEmpty()) {
                    continue;
                }
                assertTrue(route + " baked/runtime text region remained unchanged: "
                                + region.key() + "#" + region.index(),
                        differentInside(template, composed, region.bounds()));
            }
            assertTrue(route + " common footer was not audited",
                    Proposal3MenuCompositor.dynamicMasks(route).contains(
                            Proposal3TextCatalog.FOOTER));
        }
    }

    @Test
    public void canonicalRowsAndActionsFitWithoutEllipsizing() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation presentation = defaultPresentation(route);
            Proposal3OverlayCatalog.RouteLayout layout = Proposal3OverlayCatalog.layout(route);
            int rowCount = Math.min(layout.rows().size(), presentation.items().size());
            for (int index = 0; index < rowCount; index++) {
                MenuPresentation.Item item = presentation.items().get(index);
                boolean detail = route == MenuRoute.SAVE_STATES
                        || route == MenuRoute.AUDIO
                        || route == MenuRoute.TOUCH_CONTROLS
                        || route == MenuRoute.CONTROLLER_MAPPING
                        || route == MenuRoute.OPTIONAL_DEVICES
                        || route == MenuRoute.LIBRARY
                        || route == MenuRoute.SYSTEM;
                MenuRect label = Proposal3MenuCompositor.rowLabelBoundsForAudit(route,
                        layout.rows().get(index).bounds(), detail, index);
                assertTrue(route + " row label would ellipsize: " + item.label(),
                        atlas.measure(Proposal3MenuCompositor.rowTextRole(route, index), item.label())
                                <= label.width());
                if (detail && !item.detail().isEmpty()) {
                    MenuRect detailBounds = Proposal3MenuCompositor.rowDetailBoundsForAudit(route,
                            layout.rows().get(index).bounds());
                    Proposal3GlyphAtlas.Role detailRole = route == MenuRoute.SYSTEM
                            ? Proposal3GlyphAtlas.Role.SMALL : Proposal3GlyphAtlas.Role.MEDIUM;
                    assertTrue(route + " row detail would ellipsize: " + item.detail(),
                            atlas.measure(detailRole, item.detail()) <= detailBounds.width());
                }
            }
            if (route == MenuRoute.PRINTER_PAPER) {
                String[] labels = {"CLEAR PAPER", "EXPORT & SHARE"};
                for (int index = 0; index < labels.length; index++) {
                    Proposal3GlyphAtlas.Role role = index == 1
                            ? Proposal3GlyphAtlas.Role.SMALL
                            : Proposal3MenuCompositor.actionTextRole(route);
                    assertTrue(route + " action would ellipsize: " + labels[index],
                            atlas.measure(role, labels[index])
                                    <= layout.actions().get(index).bounds().width() - 20);
                }
            }
        }
    }

    @Test
    public void aboutNoticeRowsUseNormalWidthMetricsInBothFocusStates() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();
        Proposal3WidgetSkins skins = Proposal3WidgetSkins.load();
        MenuRoute route = MenuRoute.ABOUT;
        MenuPresentation canonical = defaultPresentation(route);
        List<Proposal3OverlayCatalog.Slot> rows = Proposal3OverlayCatalog.layout(route).rows();
        String[] ids = {"privacy-notices", "network", "storage", "live-camera", "source-notices"};

        assertEquals("About title must retain the Proposal 3 display weight",
                Proposal3GlyphAtlas.Role.SEMIBOLD,
                Proposal3MenuCompositor.rowTextRole(route, 0));

        for (int index = 1; index < ids.length; index++) {
            MenuPresentation.Item item = itemById(canonical, ids[index]);
            MenuRect label = Proposal3MenuCompositor.rowLabelBoundsForAudit(route,
                    rows.get(index).bounds(), false, index);
            assertNotNull("missing About row " + ids[index], item);
            assertEquals("About row role changed with its position or focus",
                    Proposal3GlyphAtlas.Role.NOTICE,
                    Proposal3MenuCompositor.rowTextRole(route, index));
            assertTrue("About row would ellipsize: " + item.label(),
                    atlas.measure(Proposal3GlyphAtlas.Role.NOTICE, item.label()) <= label.width());
            assertTrue("About row glyph pixels exceed its label bounds: " + item.label(),
                    glyphInkFits(atlas, Proposal3GlyphAtlas.Role.NOTICE, item.label(),
                            label.width()));

            MenuPresentation selected = withFocus(canonical, ids[index]);
            MenuPresentation normal = withFocus(canonical, ids[0]);
            int[] selectedPixels = new Proposal3MenuCompositor().compose(selected)
                    .orElseThrow().copyPixels();
            int[] normalPixels = new Proposal3MenuCompositor().compose(normal)
                    .orElseThrow().copyPixels();
            boolean[] selectedMask = glyphMask(selectedPixels,
                    rowBackground(route, rows.get(index), true, skins), label);
            boolean[] normalMask = glyphMask(normalPixels,
                    rowBackground(route, rows.get(index), false, skins), label);
            assertArrayEquals("About row changed glyph geometry when focused: " + ids[index],
                    normalMask, selectedMask);
            assertEquals("About row changed baseline when focused: " + ids[index],
                    glyphTop(normalMask, label.width()), glyphTop(selectedMask, label.width()));
        }
    }

    @Test
    public void canonicalChromeFitsWithoutEllipsizing() throws Exception {
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation presentation = defaultPresentation(route);
            for (Proposal3TextCatalog.TextRegion region : Proposal3TextCatalog.regions(route)) {
                if (region.key() == Proposal3TextCatalog.Key.FOOTER_BUTTON
                        || region.key() == Proposal3TextCatalog.Key.FOOTER_LABEL
                        || region.key() == Proposal3TextCatalog.Key.CONFIRM_TITLE
                        || region.key() == Proposal3TextCatalog.Key.CONFIRM_COPY_ONE
                        || region.key() == Proposal3TextCatalog.Key.CONFIRM_COPY_TWO
                        || region.key() == Proposal3TextCatalog.Key.CONFIRM_COPY_THREE) {
                    continue;
                }
                String value = runtimeRegionValue(region, presentation);
                for (String line : value.split("\\n", -1)) {
                    assertTrue(route + " chrome would ellipsize: " + line,
                            atlas.measure(Proposal3MenuCompositor.chromeTextRole(route, region), line)
                                    <= region.bounds().width());
                }
            }
        }
    }

    private static String runtimeRegionValue(Proposal3TextCatalog.TextRegion region,
            MenuPresentation presentation) {
        if (region.literal() != null) {
            return region.literal();
        }
        return switch (region.key()) {
            case HEADER_TITLE -> presentation.title();
            case HEADER_CONTEXT -> presentation.route() == MenuRoute.PAUSE_CONSOLE ? ""
                    : presentation.context().isEmpty() ? "/" : presentation.context();
            case HEADER_ACTION -> presentation.route() == MenuRoute.PAUSE_CONSOLE ? ""
                    : presentation.headerAction().isEmpty() ? "BACK" : presentation.headerAction();
            case SIDE_HEADING -> presentation.sideHeading();
            case SIDE_LINE -> region.index() < presentation.sideLines().size()
                    ? presentation.sideLines().get(region.index()) : "";
            case FOOTER_DPAD, FOOTER_BUTTON, FOOTER_LABEL, CONFIRM_TITLE,
                    CONFIRM_COPY_ONE, CONFIRM_COPY_TWO, CONFIRM_COPY_THREE -> "RUNTIME";
            case LITERAL -> "";
        };
    }

    @Test
    public void changingRouteChromePresentationChangesRuntimePixels() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation source = defaultPresentation(route);
            List<String> sideLines = List.of("RUNTIME SIDE ONE", "RUNTIME SIDE TWO",
                    "RUNTIME SIDE THREE");
            MenuPresentation changed = presentation(new MenuPageSpec(route,
                    "RUNTIME TITLE", "RUNTIME CONTEXT", "RUNTIME ACTION",
                    "RUNTIME HEADING", sideLines, copyItems(source.items()), source.columns(),
                    List.of("RUNTIME MOVE", "[X] ACCEPT", "[Y] RETURN"),
                    source.items().get(Math.max(0, source.focusedIndex())).id(), source.preview()));
            int[] before = compositor.compose(source).orElseThrow().copyPixels();
            int[] after = compositor.compose(changed).orElseThrow().copyPixels();
            assertFalse(route + " presentation chrome was not runtime-rendered",
                    Arrays.equals(before, after));
            assertNoDifferenceOutside(before, after,
                    Proposal3MenuCompositor.dynamicMasks(route));
        }
    }

    @Test
    public void dynamicLibraryLabelAndDetailUseDisjointWidgetColumns() throws Exception {
        MenuPresentation live = presentation(new MenuPageSpec(MenuRoute.LIBRARY,
                "COFFEE GB", "LIBRARY", "OPEN ROM", "RECENT ROMS", List.of(), List.of(
                        new MenuPageSpec.Item("recent-rom", "RECENT ROMS", "CHOOSE", true),
                        new MenuPageSpec.Item("choose-rom", "CHOOSE ROM", "ZIP RESULTS", true),
                        new MenuPageSpec.Item("open-rom", "OPEN ROM", "NATIVE PICKER", true)),
                1, List.of("D-PAD MOVE", "[A] OK", "[B] BACK"), "recent-rom",
                MenuPreview.empty()));
        int[] pixels = new Proposal3MenuCompositor().compose(live).orElseThrow().copyPixels();
        // The coordinated row compositor reserves x=682..693 between the ROM label and its
        // right-aligned status. No light glyph pixel may leak across that gutter.
        for (int y = 182; y < 229; y++) {
            for (int x = 682; x < 694; x++) {
                int value = pixels[y * 924 + x];
                int average = ((value >>> 16 & 0xff) + (value >>> 8 & 0xff)
                        + (value & 0xff)) / 3;
                assertTrue("library label/detail overlap at " + x + "," + y,
                        average < 125);
            }
        }
        assertNoDifferenceOutside(Proposal3TemplateFrameCatalog.decode(MenuRoute.LIBRARY).copyPixels(),
                pixels, Proposal3MenuCompositor.dynamicMasks(MenuRoute.LIBRARY));
    }

    @Test
    public void everyRouteFocusMutationIsVisibleAndConfinedToAuditedMasks() {
        Map<MenuRoute, String> focusTargets = Map.ofEntries(
                Map.entry(MenuRoute.PAUSE_CONSOLE, "save-state"),
                Map.entry(MenuRoute.SAVE_STATES, "slot-1-save"),
                Map.entry(MenuRoute.SETTINGS, "touch-controls"),
                Map.entry(MenuRoute.AUDIO, "emulated-audio"),
                Map.entry(MenuRoute.TOUCH_CONTROLS, "button-opacity"),
                Map.entry(MenuRoute.CONTROLLER_MAPPING, "map-b"),
                Map.entry(MenuRoute.OPTIONAL_DEVICES, "live-camera"),
                Map.entry(MenuRoute.DATA_MEDIA, "export-battery"),
                Map.entry(MenuRoute.LIBRARY, "open-rom"),
                Map.entry(MenuRoute.CHOOSE_ROM, "rom-2"),
                Map.entry(MenuRoute.SYSTEM, "profile-status"),
                Map.entry(MenuRoute.ABOUT, "network"),
                Map.entry(MenuRoute.CONFIRM_ACTION, "confirm"),
                Map.entry(MenuRoute.PRINTER_PAPER, "clear-paper"));
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (Map.Entry<MenuRoute, String> target : focusTargets.entrySet()) {
            MenuPresentation canonical = defaultPresentation(target.getKey());
            MenuPresentation focused = withFocus(canonical, target.getValue());
            int[] before = compositor.compose(canonical).orElseThrow().copyPixels();
            int[] after = compositor.compose(focused).orElseThrow().copyPixels();
            assertFalse(target.getKey() + " focus mutation was visually inert",
                    Arrays.equals(before, after));
            assertNoDifferenceOutside(before, after,
                    Proposal3MenuCompositor.dynamicMasks(target.getKey()));
        }
    }

    @Test
    public void paperActionFocusSwapsBothBackgroundAndGlyphPalette() {
        MenuPresentation canonical = defaultPresentation(MenuRoute.PRINTER_PAPER);
        MenuPresentation clearFocused = withFocus(canonical, "clear-paper");
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] before = compositor.compose(canonical).orElseThrow().copyPixels();
        int[] after = compositor.compose(clearFocused).orElseThrow().copyPixels();
        MenuRect clear = Proposal3OverlayCatalog.layout(MenuRoute.PRINTER_PAPER)
                .actions().get(0).bounds();
        MenuRect export = Proposal3OverlayCatalog.layout(MenuRoute.PRINTER_PAPER)
                .actions().get(1).bounds();
        assertTrue(selectedPixels(after, clear) > selectedPixels(before, clear) + 500);
        assertTrue(selectedPixels(after, export) + 500 < selectedPixels(before, export));
        assertTrue(inkPixels(after, clear) < inkPixels(before, clear));
        assertTrue(inkPixels(after, export) > inkPixels(before, export));
    }

    @Test
    public void audioSliderMovesTheExactKnobSpriteToBothEndpoints() throws Exception {
        int[] template = Proposal3TemplateFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] zero = compositor.compose(audioPresentation(0)).orElseThrow().copyPixels();
        int[] hundred = compositor.compose(audioPresentation(100)).orElseThrow().copyPixels();
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        assertRectCopy(raw, Proposal3OverlayCatalog.AUDIO_KNOB, zero, 427, 201);
        assertRectCopy(raw, Proposal3OverlayCatalog.AUDIO_KNOB, hundred, 827, 201);
        assertNoDifferenceOutside(template, zero,
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.AUDIO));
        assertNoDifferenceOutside(template, hundred,
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.AUDIO));
    }

    @Test
    public void canonicalAudioProgressKeepsSliderGeometryAndUsesRuntimeText() throws Exception {
        int[] template = Proposal3TemplateFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        int[] canonical = new Proposal3MenuCompositor().compose(audioPresentation(75))
                .orElseThrow().copyPixels();
        assertFalse(Arrays.equals(template, canonical));
        assertNoDifferenceOutside(template, canonical,
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.AUDIO));
    }

    @Test
    public void sliderAssetBlitsOnlyChangeTheDeclaredTravelMask() throws Exception {
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        Proposal3WidgetSkins skins = Proposal3WidgetSkins.load();
        int[] zero = raw.clone();
        new MenuRaster(zero).drawAudioSlider(skins.audioSliderEmpty(),
                skins.audioSliderFilled(), skins.audioKnob(),
                Proposal3OverlayCatalog.AUDIO_KNOB_TRAVEL,
                Proposal3OverlayCatalog.AUDIO_KNOB, 0);
        int[] hundred = raw.clone();
        new MenuRaster(hundred).drawAudioSlider(skins.audioSliderEmpty(),
                skins.audioSliderFilled(), skins.audioKnob(),
                Proposal3OverlayCatalog.AUDIO_KNOB_TRAVEL,
                Proposal3OverlayCatalog.AUDIO_KNOB, 100);
        assertNoDifferenceOutside(raw, zero,
                List.of(Proposal3OverlayCatalog.AUDIO_KNOB_TRAVEL));
        assertNoDifferenceOutside(raw, hundred,
                List.of(Proposal3OverlayCatalog.AUDIO_KNOB_TRAVEL));
        assertNoDifferenceOutside(zero, hundred,
                List.of(Proposal3OverlayCatalog.AUDIO_KNOB_TRAVEL));
        assertTrue("progress must move the packaged knob sprite", !Arrays.equals(zero, hundred));
    }

    @Test
    public void sliderSurfacesMeetOnlyUnderTheExactKnob() throws Exception {
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        Proposal3WidgetSkins skins = Proposal3WidgetSkins.load();
        for (int progress : new int[]{0, 25, 50, 75, 100}) {
            int localKnobX = progress * 4;
            int[] composed = raw.clone();
            new MenuRaster(composed).drawAudioSlider(skins.audioSliderEmpty(),
                    skins.audioSliderFilled(), skins.audioKnob(),
                    Proposal3OverlayCatalog.AUDIO_KNOB_TRAVEL,
                    Proposal3OverlayCatalog.AUDIO_KNOB, progress);
            for (int y = 0; y < 59; y++) {
                for (int x = 0; x < localKnobX; x++) {
                    assertEquals("filled surface " + progress + "% at " + x + "," + y,
                            skins.audioSliderFilled().pixel(x, y), pixel(composed, 427 + x, 201 + y));
                }
                for (int x = localKnobX + 31; x < 438; x++) {
                    assertEquals("empty surface " + progress + "% at " + x + "," + y,
                            skins.audioSliderEmpty().pixel(x, y), pixel(composed, 427 + x, 201 + y));
                }
            }
            assertRectCopy(raw, Proposal3OverlayCatalog.AUDIO_KNOB,
                    composed, 427 + localKnobX, 201);
        }
    }

    @Test
    public void sliderAssetsPreserveTheCanonicalVisibleSegments() throws Exception {
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        Proposal3WidgetSkins skins = Proposal3WidgetSkins.load();
        for (int y = 0; y < 59; y++) {
            for (int x = 331; x < 438; x++) {
                assertEquals(raw[(201 + y) * 924 + 427 + x],
                        skins.audioSliderEmpty().pixel(x, y));
            }
            for (int x = 0; x < 300; x++) {
                assertEquals(raw[(201 + y) * 924 + 427 + x],
                        skins.audioSliderFilled().pixel(x, y));
            }
        }
    }

    @Test
    public void readyPreviewUsesNearestNeighbourAspectFitWithoutStretching() {
        MenuPresentation canonical = defaultPresentation(MenuRoute.PRINTER_PAPER);
        MenuPreview preview = MenuPreview.ready(2, 1, new int[]{0xffff0000, 0xff0000ff});
        MenuPresentation presentation = withPreview(canonical, preview);
        int[] pixels = new Proposal3MenuCompositor().compose(presentation)
                .orElseThrow().copyPixels();
        MenuRect target = Proposal3OverlayCatalog.PRINTER_PREVIEW;
        assertEquals(MenuRaster.PAPER, pixel(pixels, target.x(), target.y()));
        assertEquals(MenuRaster.PAPER, pixel(pixels, target.x(), 264));
        assertEquals(0xffff0000, pixel(pixels, target.x(), 265));
        assertEquals(0xffff0000, pixel(pixels, target.x() + 159, 300));
        assertEquals(0xff0000ff, pixel(pixels, target.x() + 160, 300));
        assertEquals(0xff0000ff, pixel(pixels, target.right() - 1, 424));
        assertEquals(MenuRaster.PAPER, pixel(pixels, target.x(), 425));
    }

    @Test
    public void cacheIsBoundedAndConcurrentCompositionIsDeterministic() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        MenuPresentation presentation = defaultPresentation(MenuRoute.PAUSE_CONSOLE);
        MenuArgbFrame first = compositor.compose(presentation).orElseThrow();
        assertSame(first, compositor.compose(presentation).orElseThrow());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<int[]>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                tasks.add(() -> compositor.compose(presentation).orElseThrow().copyPixels());
            }
            for (Future<int[]> task : executor.invokeAll(tasks)) {
                assertArrayEquals(first.copyPixels(), task.get());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, compositor.cachedTemplateRouteCount());
        assertEquals(1, compositor.cachedComposedFrameCount());
    }

    @Test
    public void roleSpecificAtlasesArePinnedAndNoFontSourceShips() throws Exception {
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-medium-atlas.png", 576, 144,
                "1981fe7c3dc64099c171570cd44e0e64599d7b2afd79dc6707942b459d72c5e7");
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-semibold-atlas.png", 768, 192,
                "d6c6a831b6d5481fa42abe5c1c125d5efe5dca68d16caf819681f20ddca38949");
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-display-atlas.png", 576, 192,
                "7018dabbb1b26cd014e34b2e1f10b707beb3fd9d17c4bc6040f640c624e7e9c9");
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-small-atlas.png", 352, 144,
                "a7d109b7dead9ad3d155c49276e1fb4014b51a5ea759e11dc3bdd00a475b587d");
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-notice-atlas.png", 448, 144,
                "16fd8d8fe29652cc65ab60ef8fead0827d415c2bca1b9be7746f4e340d62f3a2");
        assertNotNull(Proposal3MenuCompositor.class.getResource(
                "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/PixelifySans-OFL.txt"));
        assertTrue(Proposal3MenuCompositor.class.getResource(
                "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/PixelifySans.ttf") == null);
    }

    @Test
    public void widgetOverlayPngsArePinnedAndDecodable() throws Exception {
        String root = "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/widgets/";
        assertAtlas(root + "dark-widget.png", 900, 160,
                "b4c845c44627138ecf49a3adf1cad62f7caeeb093eff209a87c5c89d00f7634a");
        assertAtlas(root + "paper-widget.png", 900, 160,
                "0026fd096f910ecf76860c42484e8325edd957922412deec536e9f17f3308af5");
        assertAtlas(root + "selected-widget.png", 900, 160,
                "33c95fdf9ec596b9e5c7041348117f2c244bc021cdc14ffa7f335a4f75521723");
        assertAtlas(root + "focus-arrow.png", 13, 20,
                "8123a8d82f1dd4e3f22d9cf8353a1b89f6777bd5a81f2304cdf00e0a65a79342");
        assertAtlas(root + "audio-slider-empty.png", 438, 59,
                "47b5f4ececa249baf992e5a2cd83c00161f5543a7a2900e859829b631a96e216");
        assertAtlas(root + "audio-slider-filled.png", 438, 59,
                "52aaeeb688ee65f5b9b39e5ff2d659430bdbfc7b4373eceba2a5d147fa458a09");
        assertAtlas(root + "audio-knob.png", 31, 59,
                "dc14fb200f7f65c8730d3e3be8c5060c59fcd547a55af470a3e04887efd3e781");
        assertAtlas(root + "data-arrow-left.png", 45, 45,
                "a6b67e6cd8bac2b4f29d7f48a8cfef3a1045a71604e6196388c22b60fcd7b562");
        assertAtlas(root + "data-arrow-right.png", 45, 45,
                "dfe2752eb83a470f67e3d8dfe650f32d9649cd54809611c4b5826909c8872fc6");
        assertAtlas(root + "data-camera.png", 50, 50,
                "a3f8e3c551a4be14edb547d4edd657500f5fa1d426172645210125c0fc24f6a6");
        assertAtlas(root + "data-printer.png", 50, 50,
                "85e2337a5f49a5bd0f841de9c582086406c0800ee4917b590b8068645cc949f4");
        assertAtlas(root + "about-network.png", 70, 58,
                "dee7372a54fbde9f7aacf6dbb6a818c2ea320be17bd6c1720e3d1b99b63f8331");
        assertAtlas(root + "about-storage.png", 70, 58,
                "233d11ffda3207650c03ba5cd3f150b1ac1ff55938b0f3e26ae929da218571d1");
        assertAtlas(root + "about-camera.png", 70, 58,
                "64fdb7e1ec7df02d383a148e34cb8ce01452945538dc8b091ff2927c5ccb9910");
        assertAtlas(root + "about-source.png", 70, 58,
                "6bf1ffe75351e8556ce3b071017faae4f89443c0343171567496924f077ca999");
        assertAtlas(root + "action-save.png", 39, 39,
                "5e8d892803639f28b56c268b40cf414ae4da60eaf95f7a64854111221a19dec2");
        assertAtlas(root + "action-load.png", 39, 39,
                "f340767214348008cf5b04ca86c4d4b473fe9f0705d8dfa2d913de39444c3ecd");
        assertAtlas(root + "action-delete.png", 42, 46,
                "088f24312b1d4f3714c6eb299074bcc3ef519f876ab656fa2109a2b29a19b8b5");
        assertAtlas(root + "action-optional-save.png", 39, 38,
                "8aad7ebe6a7b9af8c19a214f9bd7e72fa4aab14c518daffba6a3346670b2e7c8");
        assertAtlas(root + "action-optional-cancel.png", 44, 42,
                "edb8cb73d475eb30ec1624a77ab9975ca49537636e64bba8da945216ed543d6a");
        assertAtlas(root + "action-library.png", 45, 39,
                "819fa8b4fbe9e7148294747a5db150ec2007cb3cc1d13753860b61d3408d3113");
        assertAtlas(root + "action-github.png", 53, 53,
                "5629b630fcf132e8edfbe1f6c2690dddca2990d81cb1b65caf2b64cd2fdb2634");
    }

    @Test
    public void audioSliderRuntimeRequiresPackagedSpritesInsteadOfAnAuthorityRaster() {
        java.lang.reflect.Method method = null;
        for (java.lang.reflect.Method candidate : MenuRaster.class.getDeclaredMethods()) {
            if (candidate.getName().equals("drawAudioSlider")) {
                method = candidate;
                break;
            }
        }
        assertNotNull(method);
        assertFalse(Arrays.stream(method.getParameterTypes())
                .anyMatch(type -> type == int[].class));
        assertEquals(6, method.getParameterCount());
        assertEquals(Proposal3WidgetSkins.Sprite.class, method.getParameterTypes()[0]);
        assertEquals(Proposal3WidgetSkins.Sprite.class, method.getParameterTypes()[1]);
        assertEquals(Proposal3WidgetSkins.Sprite.class, method.getParameterTypes()[2]);
    }

    @Test
    public void publicApiIsPortable() {
        for (Class<?> type : new Class<?>[]{Proposal3MenuCompositor.class,
                MenuArgbFrame.class, MenuViewport.class, MenuArtwork.class,
                MenuArtworkCatalog.class}) {
            assertPortable(type.getName());
            for (java.lang.reflect.Method method : type.getMethods()) {
                assertPortable(method.toGenericString());
            }
        }
    }

    @Test
    public void writesRepresentativePreviewPngsForVisualReview() throws Exception {
        if (!Boolean.getBoolean("ui.portable.writePreviews")) {
            return;
        }
        Path workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path repositoryDirectory = workingDirectory.getFileName().toString().equals("ui-portable")
                ? workingDirectory.getParent() : workingDirectory;
        Path directory = repositoryDirectory.resolve("output/portable-compositor");
        Files.createDirectories(directory);
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation canonical = defaultPresentation(route);
            MenuArgbFrame frame = compositor.compose(canonical).orElseThrow();
            writeFrame(directory.resolve(route.name().toLowerCase() + ".png"), frame);
            MenuArgbFrame focused = compositor.compose(withFocus(canonical, focusTarget(route)))
                    .orElseThrow();
            writeFrame(directory.resolve(route.name().toLowerCase() + "-focus.png"), focused);
        }
        // A deliberately non-placeholder Game Boy-sized image makes the pause-menu review
        // artifact exercise the same aspect-fit and frozen-preview path as a live game frame.
        int[] gameFrame = new int[160 * 144];
        for (int y = 0; y < 144; y++) {
            for (int x = 0; x < 160; x++) {
                int color;
                if (y < 36) {
                    color = ((x / 8 + y / 6) & 1) == 0 ? 0xffb7dd79 : 0xff8fbe63;
                } else if (y < 105) {
                    color = ((x / 12 + y / 10) & 1) == 0 ? 0xff396e55 : 0xff28513f;
                } else {
                    color = ((x / 10 + y / 8) & 1) == 0 ? 0xff172c34 : 0xff0f2028;
                }
                if (x >= 61 && x < 99 && y >= 47 && y < 82) {
                    color = (x + y) % 7 < 4 ? 0xffffd66b : 0xffdb7d46;
                }
                gameFrame[y * 160 + x] = color;
            }
        }
        MenuPresentation populatedPause = presentation(new MenuPageSpec(MenuRoute.PAUSE_CONSOLE,
                "COFFEE GB", "", "", "THE LEGEND OF ZELDA: LINK'S AWAKENING DX",
                List.of("PLAY TIME", "1:23:45", "BATTERY SAVE ACTIVE"),
                List.of(
                        item("resume", "RESUME", true),
                        item("save-state", "SAVE STATE", true),
                        item("load-state", "LOAD STATE", true),
                        item("open-rom", "OPEN ROM", true),
                        item("reset", "RESET GAME", true),
                        item("settings", "SETTINGS", true),
                        item("stop", "STOP GAME", true)), 1,
                List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), "open-rom",
                MenuPreview.ready(160, 144, gameFrame)));
        writeFrame(directory.resolve("pause_console-populated.png"),
                compositor.compose(populatedPause).orElseThrow());
        MenuPresentation confirmation = defaultPresentation(MenuRoute.CONFIRM_ACTION);
        ArrayList<MenuPresentation.Item> stopItems = new ArrayList<>(confirmation.items());
        for (int index = 0; index < stopItems.size(); index++) {
            MenuPresentation.Item item = stopItems.get(index);
            if ("confirm".equals(item.id())) {
                stopItems.set(index, new MenuPresentation.Item(item.id(), item.label(),
                        "STOP GAME", item.enabled(), item.secondaryId(), item.adjustable(),
                        item.progress()));
            }
        }
        writeFrame(directory.resolve("confirm_action-stop.png"), compositor.compose(
                presentation(spec(confirmation, "confirm", confirmation.preview(), stopItems)))
                .orElseThrow());
        writeFrame(directory.resolve("audio-volume-0.png"),
                compositor.compose(audioPresentation(0)).orElseThrow());
        writeFrame(directory.resolve("audio-volume-100.png"),
                compositor.compose(audioPresentation(100)).orElseThrow());
    }

    private static void writeFrame(Path path, MenuArgbFrame frame) throws IOException {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(),
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, frame.width(), frame.height(), frame.copyPixels(), 0, frame.width());
        ImageIO.write(image, "png", path.toFile());
    }

    private static MenuPresentation defaultPresentation(MenuRoute route) {
        MenuController controller = new MenuController(listener());
        controller.show(route);
        return controller.presentation();
    }

    private static String focusTarget(MenuRoute route) {
        return switch (route) {
            case PAUSE_CONSOLE -> "save-state";
            case SAVE_STATES -> "slot-1-save";
            case SETTINGS -> "touch-controls";
            case AUDIO -> "emulated-audio";
            case TOUCH_CONTROLS -> "button-opacity";
            case CONTROLLER_MAPPING -> "map-b";
            case OPTIONAL_DEVICES -> "live-camera";
            case DATA_MEDIA -> "export-battery";
            case LIBRARY -> "open-rom";
            case CHOOSE_ROM -> "rom-2";
            case SYSTEM -> "profile-status";
            case ABOUT -> "network";
            case CONFIRM_ACTION -> "confirm";
            case PRINTER_PAPER -> "clear-paper";
        };
    }

    private static String firstRowFocusId(MenuRoute route) {
        return switch (route) {
            case PAUSE_CONSOLE -> "resume";
            case SAVE_STATES -> "slot-0-save";
            case SETTINGS -> "audio";
            case AUDIO -> "mute-audio";
            case TOUCH_CONTROLS -> "haptics";
            case CONTROLLER_MAPPING -> "map-a";
            case OPTIONAL_DEVICES -> "rumble";
            case DATA_MEDIA -> "import-battery";
            case LIBRARY -> "recent-rom";
            case CHOOSE_ROM -> "rom-1";
            case SYSTEM -> "video-status";
            case ABOUT -> "privacy-notices";
            case CONFIRM_ACTION, PRINTER_PAPER ->
                    throw new AssertionError("route does not have visible rows: " + route);
        };
    }

    private static MenuPresentation.Item itemById(MenuPresentation presentation, String id) {
        for (MenuPresentation.Item item : presentation.items()) {
            if (id.equals(item.id())) {
                return item;
            }
        }
        return null;
    }

    /** Mirrors the compositor's private row label reservation so icons and details stay excluded. */
    private static boolean supportsRowDetails(MenuRoute route) {
        return switch (route) {
            case SAVE_STATES, AUDIO, TOUCH_CONTROLS, CONTROLLER_MAPPING,
                    OPTIONAL_DEVICES, LIBRARY, SYSTEM -> true;
            default -> false;
        };
    }

    private static MenuPresentation presentation(MenuPageSpec spec) {
        final MenuPresentation[] result = new MenuPresentation[1];
        MenuController controller = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation presentation) {
                result[0] = presentation;
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
            }
        });
        controller.setPage(spec);
        controller.show(spec.route());
        result[0] = controller.presentation();
        return result[0];
    }

    private static MenuPresentation withFocus(MenuPresentation source, String preferredFocusId) {
        return presentation(spec(source, preferredFocusId, source.preview(), source.items()));
    }

    private static MenuPresentation withPreview(MenuPresentation source, MenuPreview preview) {
        String focus = source.items().get(source.focusedIndex()).id();
        return presentation(spec(source, focus, preview, source.items()));
    }

    private static MenuPresentation audioPresentation(int progress) {
        MenuPresentation source = defaultPresentation(MenuRoute.AUDIO);
        ArrayList<MenuPresentation.Item> items = new ArrayList<>(source.items());
        MenuPresentation.Item volume = items.get(0);
        items.set(0, new MenuPresentation.Item(volume.id(), volume.label(), progress + "%",
                volume.enabled(), volume.secondaryId(), true, progress));
        return presentation(spec(source, "mute-audio", source.preview(), items));
    }

    private static MenuPageSpec spec(MenuPresentation source, String preferredFocusId,
            MenuPreview preview, List<MenuPresentation.Item> sourceItems) {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>(sourceItems.size());
        for (MenuPresentation.Item item : sourceItems) {
            items.add(new MenuPageSpec.Item(item.id(), item.label(), item.detail(), item.enabled(),
                    item.secondaryId(), item.adjustable(), item.progress()));
        }
        return new MenuPageSpec(source.route(), source.title(), source.context(),
                source.headerAction(), source.sideHeading(), source.sideLines(), items,
                source.columns(), source.footerHints(), preferredFocusId, preview);
    }

    private static MenuPageSpec.Item item(String id, String label, boolean enabled) {
        return new MenuPageSpec.Item(id, label, "", enabled);
    }

    private static List<MenuPageSpec.Item> copyItems(List<MenuPresentation.Item> source) {
        ArrayList<MenuPageSpec.Item> result = new ArrayList<>(source.size());
        for (MenuPresentation.Item item : source) {
            result.add(new MenuPageSpec.Item(item.id(), item.label(), item.detail(), item.enabled(),
                    item.secondaryId(), item.adjustable(), item.progress()));
        }
        return result;
    }

    private static MenuController.Listener listener() {
        return new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation presentation) {
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
            }
        };
    }

    private static boolean inside(List<MenuRect> masks, int x, int y) {
        for (MenuRect mask : masks) {
            if (mask.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private static boolean differentInside(int[] before, int[] after, MenuRect region) {
        for (int y = region.y(); y < region.bottom(); y++) {
            for (int x = region.x(); x < region.right(); x++) {
                if (before[y * 924 + x] != after[y * 924 + x]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int pixel(int[] pixels, int x, int y) {
        return pixels[y * 924 + x];
    }

    private static int selectedPixels(int[] pixels, MenuRect bounds) {
        return countPixels(pixels, bounds, pixel -> {
            int red = pixel >>> 16 & 0xff;
            int green = pixel >>> 8 & 0xff;
            int blue = pixel & 0xff;
            return red >= green + 18 && red >= blue + 18;
        });
    }

    private static int lightPixels(int[] pixels, MenuRect bounds) {
        return countPixels(pixels, bounds, pixel -> {
            int red = pixel >>> 16 & 0xff;
            int green = pixel >>> 8 & 0xff;
            int blue = pixel & 0xff;
            return red >= 210 && green >= 195 && blue >= 145;
        });
    }

    private static int inkPixels(int[] pixels, MenuRect bounds) {
        return countPixels(pixels, bounds, pixel -> {
            int red = pixel >>> 16 & 0xff;
            int green = pixel >>> 8 & 0xff;
            int blue = pixel & 0xff;
            return (red + green + blue) / 3 < 45;
        });
    }

    private static int countPixels(int[] pixels, MenuRect bounds, PixelPredicate predicate) {
        int result = 0;
        for (int y = bounds.y(); y < bounds.bottom(); y++) {
            for (int x = bounds.x(); x < bounds.right(); x++) {
                if (predicate.test(pixel(pixels, x, y))) {
                    result++;
                }
            }
        }
        return result;
    }

    private static void assertRectCopy(int[] authority, MenuRect source, int[] actual,
            int targetX, int targetY) {
        for (int y = 0; y < source.height(); y++) {
            for (int x = 0; x < source.width(); x++) {
                assertEquals("sprite pixel " + x + "," + y,
                        pixel(authority, source.x() + x, source.y() + y),
                        pixel(actual, targetX + x, targetY + y));
            }
        }
    }

    private static void assertNoDifferenceOutside(int[] before, int[] after,
            List<MenuRect> masks) {
        for (int index = 0; index < before.length; index++) {
            if (before[index] == after[index]) {
                continue;
            }
            int x = index % 924;
            int y = index / 924;
            assertTrue("changed pixel outside stage-one masks: " + x + "," + y,
                    inside(masks, x, y));
        }
    }

    private static int[] rowBackground(MenuRoute route, Proposal3OverlayCatalog.Slot slot,
            boolean selected, Proposal3WidgetSkins skins) throws Exception {
        int[] background = Proposal3TemplateFrameCatalog.decode(route).copyPixels();
        Proposal3OverlayCatalog.Surface surface = selected
                ? Proposal3OverlayCatalog.Surface.DARK : slot.surface();
        Proposal3WidgetSkins.Surface skin = selected ? Proposal3WidgetSkins.Surface.SELECTED
                : surface == Proposal3OverlayCatalog.Surface.DARK
                ? Proposal3WidgetSkins.Surface.DARK : Proposal3WidgetSkins.Surface.PAPER;
        MenuRect target = route == MenuRoute.PAUSE_CONSOLE ? slot.bounds()
                : expand(slot.bounds(), 2);
        new MenuRaster(background).paintWidget(skins.surface(skin), target);
        if (route == MenuRoute.PAUSE_CONSOLE) {
            for (MenuRect divider : Proposal3OverlayCatalog.PAUSE_DIVIDERS) {
                new MenuRaster(background).fill(divider, MenuRaster.PAPER);
            }
        }
        return background;
    }

    private static MenuRect expand(MenuRect bounds, int amount) {
        return new MenuRect(Math.max(0, bounds.x() - amount),
                Math.max(0, bounds.y() - amount),
                Math.min(924, bounds.right() + amount) - Math.max(0, bounds.x() - amount),
                Math.min(736, bounds.bottom() + amount) - Math.max(0, bounds.y() - amount));
    }

    private static boolean[] glyphMask(int[] pixels, int[] background, MenuRect bounds) {
        boolean[] result = new boolean[bounds.width() * bounds.height()];
        for (int y = 0; y < bounds.height(); y++) {
            for (int x = 0; x < bounds.width(); x++) {
                int index = (bounds.y() + y) * 924 + bounds.x() + x;
                result[y * bounds.width() + x] = pixels[index] != background[index];
            }
        }
        return result;
    }

    private static boolean hasGlyph(boolean[] mask) {
        for (boolean pixel : mask) {
            if (pixel) {
                return true;
            }
        }
        return false;
    }

    private static boolean glyphInkFits(Proposal3GlyphAtlas atlas,
            Proposal3GlyphAtlas.Role role, String value, int width) {
        int cursor = 0;
        int right = 0;
        for (int index = 0; index < value.length(); index++) {
            int glyph = atlas.index(value.charAt(index));
            for (int y = 0; y < atlas.cellHeight(role); y++) {
                for (int x = 0; x < atlas.cellWidth(role); x++) {
                    if ((atlas.pixel(role, glyph, x, y) >>> 24) != 0) {
                        right = Math.max(right, cursor + x + 1);
                    }
                }
            }
            cursor += atlas.advance(role, value.charAt(index));
        }
        return right <= width;
    }

    /** Returns the first actual lit glyph row, i.e. the rendered baseline anchor, not a constant. */
    private static int glyphTop(boolean[] mask, int width) {
        for (int y = 0; y < mask.length / width; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static void assertAtlas(String path, int width, int height, String expectedHash)
            throws Exception {
        byte[] bytes;
        try (java.io.InputStream input = Proposal3MenuCompositorTest.class.getResourceAsStream(path)) {
            assertNotNull(path, input);
            bytes = input.readAllBytes();
        }
        assertEquals(expectedHash, hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        MenuArgbFrame decoded = PngArgbDecoder.decode(new java.io.ByteArrayInputStream(bytes));
        assertEquals(width, decoded.width());
        assertEquals(height, decoded.height());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void assertPortable(String signature) {
        assertFalse(signature, signature.contains("android."));
        assertFalse(signature, signature.contains("java.awt."));
        assertFalse(signature, signature.contains("javax.imageio."));
        assertFalse(signature, signature.contains("javafx."));
        assertFalse(signature, signature.contains("org."));
    }

    @FunctionalInterface
    private interface PixelPredicate {
        boolean test(int pixel);
    }
}
