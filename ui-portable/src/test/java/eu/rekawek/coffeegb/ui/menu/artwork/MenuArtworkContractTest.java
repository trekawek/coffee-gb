package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MenuArtworkContractTest {

    private static final Map<MenuRoute, String> EXPECTED_FILENAMES = Map.ofEntries(
            Map.entry(MenuRoute.PAUSE_CONSOLE, "00-pause-console.png"),
            Map.entry(MenuRoute.SAVE_STATES, "01-save-states.png"),
            Map.entry(MenuRoute.RECENT_GAMES, "16-recent-games.png"),
            Map.entry(MenuRoute.SETTINGS, "02-settings.png"),
            Map.entry(MenuRoute.AUDIO, "03-audio.png"),
            Map.entry(MenuRoute.DISPLAY, "14-display.png"),
            Map.entry(MenuRoute.TOUCH_CONTROLS, "04-touch-controls.png"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING, "05-controller-mapping.png"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES, "06-optional-devices.png"),
            Map.entry(MenuRoute.OPTION_PICKER, "15-option-picker.png"),
            Map.entry(MenuRoute.DATA_MEDIA, "07-data-media.png"),
            Map.entry(MenuRoute.LIBRARY, "08-library.png"),
            Map.entry(MenuRoute.CHOOSE_ROM, "09-choose-rom.png"),
            Map.entry(MenuRoute.SYSTEM, "10-system.png"),
            Map.entry(MenuRoute.ABOUT, "11-about.png"),
            Map.entry(MenuRoute.CONFIRM_ACTION, "12-confirm-action.png"),
            Map.entry(MenuRoute.PRINTER_PAPER, "13-printer-paper.png"));

    @Test
    public void metadataCoversEveryRouteWithoutExposingRawResources() {
        Map<MenuRoute, MenuArtwork> catalog = MenuArtworkCatalog.all();
        assertEquals(EnumSet.allOf(MenuRoute.class), catalog.keySet());
        assertEquals(MenuRoute.values().length, catalog.size());

        Set<String> sourceFilenames = new HashSet<>();
        Set<String> templateFilenames = new HashSet<>();
        for (MenuRoute route : MenuRoute.values()) {
            MenuArtwork artwork = catalog.get(route);
            assertNotNull(artwork);
            assertSame(artwork, MenuArtworkCatalog.artwork(route));
            assertEquals(route, artwork.route());
            assertTrue(sourceFilenames.add(artwork.sourceFilename()));
            assertEquals(EXPECTED_FILENAMES.get(route), artwork.sourceFilename());
            templateFilenames.add(artwork.templateFilename());
            assertEquals(MenuArtworkCatalog.COMMON_TEMPLATE_FILENAME,
                    artwork.templateFilename());
            assertEquals(MenuArtworkCatalog.SOURCE_VISIBLE_CROP, artwork.sourceVisibleCrop());
            assertEquals(MenuArtworkCatalog.PACKAGED_WIDTH, artwork.packagedWidth());
            assertEquals(MenuArtworkCatalog.PACKAGED_HEIGHT, artwork.packagedHeight());
        }
        assertEquals(MenuRoute.values().length, sourceFilenames.size());
        assertEquals(Set.of(MenuArtworkCatalog.COMMON_TEMPLATE_FILENAME), templateFilenames);
    }

    @Test
    public void sourceAndPackagedGeometryArePinned() {
        assertEquals(1672, MenuArtworkCatalog.SOURCE_WIDTH);
        assertEquals(941, MenuArtworkCatalog.SOURCE_HEIGHT);
        assertEquals(new MenuRect(374, 102, 924, 736), MenuArtworkCatalog.SOURCE_VISIBLE_CROP);
        assertEquals(new MenuRect(0, 0, 924, 736), MenuArtworkCatalog.PACKAGED_BOUNDS);
        assertEquals(1298, MenuArtworkCatalog.SOURCE_VISIBLE_CROP.right());
        assertEquals(838, MenuArtworkCatalog.SOURCE_VISIBLE_CROP.bottom());
        assertTrue(MenuArtworkCatalog.SOURCE_VISIBLE_CROP.right() <= MenuArtworkCatalog.SOURCE_WIDTH);
        assertTrue(MenuArtworkCatalog.SOURCE_VISIBLE_CROP.bottom() <= MenuArtworkCatalog.SOURCE_HEIGHT);
    }

    @Test
    public void sharedScreenGeometryPinsSevenInterchangeableRows() {
        assertEquals(new MenuRect(8, 8, 908, 95), MenuScreenTemplate.TITLE_BAR);
        assertEquals(new MenuRect(45, 25, 834, 61), MenuScreenTemplate.TITLE);
        assertEquals(new MenuRect(8, 110, 400, 534), MenuScreenTemplate.LEFT_PANEL);
        assertEquals(new MenuRect(30, 140, 352, 340), MenuScreenTemplate.PICTURE);
        assertEquals(new MenuRect(30, 490, 352, 139), MenuScreenTemplate.SUBTITLE);
        assertEquals(new MenuRect(418, 110, 498, 534), MenuScreenTemplate.RIGHT_PANEL);
        assertEquals(new MenuRect(424, 121, 484, 516), MenuScreenTemplate.OPTION_LIST);
        assertEquals(new MenuRect(8, 652, 908, 76), MenuScreenTemplate.FOOTER_PANEL);
        assertEquals(new MenuRect(18, 659, 888, 61), MenuScreenTemplate.FOOTER);

        assertEquals(7, MenuScreenTemplate.OPTION_ROW_COUNT);
        assertEquals(MenuScreenTemplate.OPTION_ROW_COUNT,
                MenuScreenTemplate.OPTION_ROWS.size());
        assertEquals(MenuScreenTemplate.OPTION_ROW_COUNT - 1,
                MenuScreenTemplate.OPTION_DIVIDERS.size());
        for (int index = 0; index < MenuScreenTemplate.OPTION_ROW_COUNT; index++) {
            MenuRect row = MenuScreenTemplate.OPTION_ROWS.get(index);
            assertSame(row, MenuScreenTemplate.optionRow(index));
            assertEquals(MenuScreenTemplate.OPTION_ROW_HEIGHT, row.height());
            assertTrue(contains(MenuScreenTemplate.OPTION_LIST, row));
            if (index > 0) {
                MenuRect previous = MenuScreenTemplate.OPTION_ROWS.get(index - 1);
                MenuRect divider = MenuScreenTemplate.OPTION_DIVIDERS.get(index - 1);
                assertEquals(previous.bottom(), divider.y());
                assertEquals(MenuScreenTemplate.OPTION_DIVIDER_HEIGHT, divider.height());
                assertEquals(divider.bottom(), row.y());
            }
        }
        assertTrue(contains(MenuArtworkCatalog.PACKAGED_BOUNDS, MenuScreenTemplate.TITLE_BAR));
        assertTrue(contains(MenuArtworkCatalog.PACKAGED_BOUNDS, MenuScreenTemplate.LEFT_PANEL));
        assertTrue(contains(MenuArtworkCatalog.PACKAGED_BOUNDS, MenuScreenTemplate.RIGHT_PANEL));
        assertTrue(contains(MenuArtworkCatalog.PACKAGED_BOUNDS, MenuScreenTemplate.FOOTER_PANEL));
        assertTrue(contains(MenuScreenTemplate.LEFT_PANEL, MenuScreenTemplate.PICTURE));
        assertTrue(contains(MenuScreenTemplate.LEFT_PANEL, MenuScreenTemplate.SUBTITLE));
        assertTrue(contains(MenuScreenTemplate.RIGHT_PANEL, MenuScreenTemplate.OPTION_LIST));
    }

    @Test
    public void integerViewportMatchesApprovedVectorsAndOddRemainders() {
        assertBounds(new MenuViewport(924, 736), 0, 0, 924, 736);
        assertBounds(new MenuViewport(1848, 1472), 0, 0, 1848, 1472);
        assertBounds(new MenuViewport(919, 717), 9, 0, 900, 717);
        assertBounds(new MenuViewport(758, 685), 0, 41, 758, 603);
        assertBounds(new MenuViewport(2400, 1080), 522, 0, 1355, 1080);
        assertBounds(new MenuViewport(1080, 2400), 0, 770, 1080, 860);

        MenuRect oddWidth = new MenuViewport(919, 717).contentBounds();
        assertEquals(9, oddWidth.x());
        assertEquals(10, 919 - oddWidth.right());
        MenuRect oddHeight = new MenuViewport(758, 685).contentBounds();
        assertEquals(41, oddHeight.y());
        assertEquals(41, 685 - oddHeight.bottom());
        MenuRect desktop = new MenuViewport(2400, 1080).contentBounds();
        assertEquals(522, desktop.x());
        assertEquals(523, 2400 - desktop.right());
    }

    @Test
    public void viewportInverseMappingIsHalfOpenAndRejectsBars() {
        MenuViewport viewport = new MenuViewport(758, 685);
        MenuRect bounds = viewport.contentBounds();
        assertTrue(viewport.containsView(bounds.x(), bounds.y()));
        assertTrue(viewport.containsView(bounds.right() - 1, bounds.bottom() - 1));
        assertFalse(viewport.containsView(bounds.right(), bounds.y()));
        assertFalse(viewport.containsView(bounds.x(), bounds.bottom()));
        assertFalse(viewport.containsView(0, bounds.y() - 1));

        assertEquals(new MenuPoint(0.0, 0.0), viewport.viewToSource(bounds.x(), bounds.y()).get());
        assertFalse(viewport.viewToSource(bounds.right(), bounds.y()).isPresent());
        assertFalse(viewport.viewToSource(bounds.x(), bounds.bottom()).isPresent());
        assertFalse(viewport.viewToSource(0, bounds.y() - 1).isPresent());
        assertFalse(viewport.viewToSource(new MenuPoint(bounds.right(), bounds.y())).isPresent());
        assertFalse(viewport.viewToSource(new MenuPoint(bounds.x(), bounds.bottom())).isPresent());
        assertTrue(viewport.viewToSource(new MenuPoint(bounds.right() - 0.5, bounds.bottom() - 0.5))
                .isPresent());

        OptionalInt sourceX = viewport.sourceX(bounds.right() - 1);
        OptionalInt sourceY = viewport.sourceY(bounds.bottom() - 1);
        assertTrue(sourceX.isPresent());
        assertTrue(sourceY.isPresent());
        assertTrue(sourceX.getAsInt() < MenuViewport.SOURCE_WIDTH);
        assertTrue(sourceY.getAsInt() < MenuViewport.SOURCE_HEIGHT);
        assertFalse(viewport.sourceX(bounds.right()).isPresent());
        assertFalse(viewport.sourceY(bounds.bottom()).isPresent());
    }

    @Test
    public void integerForwardInverseMappingRoundTrips() {
        MenuViewport viewport = new MenuViewport(1848, 1472);
        for (int sourceX : new int[]{0, 1, 100, 923}) {
            for (int sourceY : new int[]{0, 1, 200, 735}) {
                MenuPoint viewPoint = viewport.sourceToView(sourceX, sourceY);
                Optional<MenuPoint> sourcePoint = viewport.viewToSource(
                        (int) viewPoint.x(), (int) viewPoint.y());
                assertTrue(sourcePoint.isPresent());
                assertEquals(sourceX, (int) sourcePoint.get().x());
                assertEquals(sourceY, (int) sourcePoint.get().y());
            }
        }
        assertEquals(new MenuPoint(1848.0, 1472.0), viewport.sourceToView(924, 736));
        assertEquals(new MenuPoint(0.0, 0.0), viewport.sourceToView(0, 0));
        expectIllegalArgument(() -> viewport.sourceToViewX(-1));
        expectIllegalArgument(() -> viewport.sourceToViewY(MenuViewport.SOURCE_HEIGHT + 1));
    }

    @Test
    public void viewportUsesLongCrossProductsForNearMaximumDimensions() {
        int maximum = Integer.MAX_VALUE;
        MenuViewport viewport = new MenuViewport(maximum, maximum);
        MenuRect actual = viewport.contentBounds();
        assertEquals(expectedBounds(maximum, maximum), actual);
        assertTrue((long) actual.right() <= maximum);
        assertTrue((long) actual.bottom() <= maximum);
    }

    @Test
    public void productionClassesExposeOnlyTheInternalRuntimeArtworkPath() throws Exception {
        Path classes = Paths.get(MenuArtwork.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        if (Files.isDirectory(classes)) {
            assertTrue(Files.isRegularFile(classes.resolve(
                    "eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates/"
                            + MenuArtworkCatalog.COMMON_TEMPLATE_FILENAME)));
            assertFalse(Files.exists(classes.resolve(
                    "eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/templates")));
            assertFalse(Files.exists(classes.resolve(
                    "eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw")));
            assertFalse(Files.exists(classes.resolve("eu/rekawek/coffeegb/ui/menu/artwork/proposal3/source")));
        }
    }

    @Test
    public void publicApiIsPlatformNeutralAndDoesNotExposeRawAccess() {
        assertEquals(0, MenuArtwork.class.getConstructors().length);
        assertEquals(0, MenuScreenTemplate.class.getConstructors().length);
        for (Class<?> type : new Class<?>[]{
                MenuRect.class,
                MenuPoint.class,
                MenuArtwork.class,
                MenuArtworkCatalog.class,
                MenuScreenTemplate.class,
                MenuArgbFrame.class,
                MenuViewport.class
        }) {
            assertNeutralSignature(type.getName());
            for (Constructor<?> constructor : type.getConstructors()) {
                assertNeutralSignature(constructor.toGenericString());
            }
            for (Method method : type.getMethods()) {
                assertNeutralSignature(method.toGenericString());
                assertFalse("raw access leaked from MenuArtwork",
                        method.getName().equals("resourcePath") || method.getName().equals("openStream"));
            }
            for (Field field : type.getFields()) {
                assertNeutralSignature(field.toGenericString());
                assertFalse("fixture path leaked as public field", field.getName().contains("ROOT"));
            }
        }
    }

    private static void assertBounds(MenuViewport viewport, int x, int y, int width, int height) {
        MenuRect expected = new MenuRect(x, y, width, height);
        assertEquals(expected, viewport.contentBounds());
        assertSame(viewport.contentBounds(), viewport.destinationRect());
    }

    private static MenuRect expectedBounds(int viewWidth, int viewHeight) {
        long widthCrossProduct = (long) viewWidth * MenuViewport.SOURCE_HEIGHT;
        long heightCrossProduct = (long) viewHeight * MenuViewport.SOURCE_WIDTH;
        int width;
        int height;
        if (widthCrossProduct <= heightCrossProduct) {
            width = viewWidth;
            height = (int) (widthCrossProduct / MenuViewport.SOURCE_WIDTH);
        } else {
            height = viewHeight;
            width = (int) (heightCrossProduct / MenuViewport.SOURCE_HEIGHT);
        }
        return new MenuRect((viewWidth - width) / 2, (viewHeight - height) / 2, width, height);
    }

    private static boolean contains(MenuRect outer, MenuRect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void assertNeutralSignature(String signature) {
        assertFalse(signature, signature.contains("android."));
        assertFalse(signature, signature.contains("java.awt."));
        assertFalse(signature, signature.contains("javax.imageio."));
        assertFalse(signature, signature.contains("javafx."));
        assertFalse(signature, signature.contains("org."));
    }
}
