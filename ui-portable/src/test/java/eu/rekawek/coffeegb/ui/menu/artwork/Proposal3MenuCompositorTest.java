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
            assertEquals(route.name(), 1, compositor.cachedRawRouteCount());
            assertEquals(route.name(), 1, compositor.cachedComposedFrameCount());
        }
    }

    @Test
    public void hiddenPresentationProducesNoFrame() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        assertFalse(compositor.compose(new MenuController(listener()).presentation()).isPresent());
    }

    @Test
    public void canonicalFramesKeepTheRawArtworkAsLayerZero() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            int[] raw = Proposal3RawFrameCatalog.decode(route).copyPixels();
            int[] composed = compositor.compose(defaultPresentation(route)).orElseThrow().copyPixels();
            assertArrayEquals(route.name() + " canonical layer zero changed", raw, composed);
        }
    }

    @Test
    public void rawAuthorityIsUntouchedOutsideDeclaredPaintInteriors() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            MenuPresentation presentation = defaultPresentation(route);
            int[] raw = Proposal3RawFrameCatalog.decode(route).copyPixels();
            int[] composed = compositor.compose(presentation).orElseThrow().copyPixels();
            List<MenuRect> masks = compositor.dynamicMasks(route);
            for (int index = 0; index < raw.length; index++) {
                int x = index % 924;
                int y = index / 924;
                if (!inside(masks, x, y)) {
                    assertEquals(route.name() + " changed raw pixel " + x + "," + y,
                            raw[index], composed[index]);
                }
            }
        }
    }

    @Test
    public void auditedBordersRemainByteIdentical() throws Exception {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        MenuRoute[] routes = {MenuRoute.PAUSE_CONSOLE, MenuRoute.SAVE_STATES,
                MenuRoute.SETTINGS, MenuRoute.ABOUT, MenuRoute.PRINTER_PAPER};
        for (MenuRoute route : routes) {
            int[] raw = Proposal3RawFrameCatalog.decode(route).copyPixels();
            int[] composed = compositor.compose(defaultPresentation(route)).orElseThrow().copyPixels();
            assertEquals(route + " top-left border", raw[421 + 117 * 924],
                    composed[421 + 117 * 924]);
            assertEquals(route + " right border", raw[911 + 130 * 924],
                    composed[911 + 130 * 924]);
        }
        int[] rawPrinter = Proposal3RawFrameCatalog.decode(MenuRoute.PRINTER_PAPER).copyPixels();
        int[] printer = compositor.compose(defaultPresentation(MenuRoute.PRINTER_PAPER))
                .orElseThrow().copyPixels();
        for (int y : new int[]{126, 555}) {
            for (int x : new int[]{435, 846}) {
                assertEquals("printer paper frame " + x + "," + y,
                        rawPrinter[y * 924 + x], printer[y * 924 + x]);
            }
        }
    }

    @Test
    public void pauseOpenRomIsRenderedAsHeaderAction() throws Exception {
        MenuPresentation presentation = presentation(new MenuPageSpec(MenuRoute.PAUSE_CONSOLE,
                "COFFEE GB", "PAUSED", "OPEN ROM", "CURRENT GAME", List.of("PLAYING"),
                List.of(
                        item("resume", "RESUME", true),
                        item("open-rom", "OPEN ROM", true)), 1,
                List.of("D-PAD MOVE", "[A] OK", "[B] BACK"), "open-rom",
                MenuPreview.empty()));
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.PAUSE_CONSOLE).copyPixels();
        int[] composed = new Proposal3MenuCompositor().compose(presentation).orElseThrow().copyPixels();
        assertFalse(Arrays.equals(raw, composed));
        assertTrue(differentInside(raw, composed, Proposal3OverlayCatalog.OPEN_ROM_HEADER));
        assertNoDifferenceOutside(raw, composed,
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.PAUSE_CONSOLE));
    }

    @Test
    public void changingOneRowOnlyChangesItsDeclaredMask() throws Exception {
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
        assertNoDifferenceOutside(Proposal3RawFrameCatalog.decode(MenuRoute.LIBRARY).copyPixels(),
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
    public void stageOneLeavesPrinterLayerZeroUntouched() throws Exception {
        MenuPresentation presentation = defaultPresentation(MenuRoute.PRINTER_PAPER);
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.PRINTER_PAPER).copyPixels();
        int[] composed = new Proposal3MenuCompositor().compose(presentation).orElseThrow().copyPixels();
        assertArrayEquals(raw, composed);
    }

    @Test
    public void audioSliderMovesTheExactKnobSpriteToBothEndpoints() throws Exception {
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] zero = compositor.compose(audioPresentation(0)).orElseThrow().copyPixels();
        int[] hundred = compositor.compose(audioPresentation(100)).orElseThrow().copyPixels();
        assertRectCopy(raw, Proposal3OverlayCatalog.AUDIO_KNOB, zero, 427, 201);
        assertRectCopy(raw, Proposal3OverlayCatalog.AUDIO_KNOB, hundred, 827, 201);
        assertNoDifferenceOutside(raw, zero,
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.AUDIO));
        assertNoDifferenceOutside(raw, hundred,
                Proposal3MenuCompositor.dynamicMasks(MenuRoute.AUDIO));
    }

    @Test
    public void canonicalAudioProgressKeepsTheRawLayerZero() throws Exception {
        int[] raw = Proposal3RawFrameCatalog.decode(MenuRoute.AUDIO).copyPixels();
        int[] canonical = new Proposal3MenuCompositor().compose(audioPresentation(75))
                .orElseThrow().copyPixels();
        assertArrayEquals(raw, canonical);
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
        assertEquals(1, compositor.cachedRawRouteCount());
        assertEquals(1, compositor.cachedComposedFrameCount());
    }

    @Test
    public void roleSpecificAtlasesArePinnedAndNoFontSourceShips() throws Exception {
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-medium-atlas.png", 384, 144,
                "c0003e3e71ab58fd1ce79f18cac1b21edb2a812fbeff2c5b3badef0129c42a0d");
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-semibold-atlas.png", 512, 192,
                "435074e7fffede8ca887333518a49ab944de9677aff135342f5a1d6dc47f6361");
        assertAtlas("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/"
                + "pixelify-sans-display-atlas.png", 384, 192,
                "1d95c6029f34c5ab957b4987cb33263e571a4fbb9f770ef8494807b151e5c689");
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
