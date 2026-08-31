package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuKey;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Focused contract checks for the shared portable Settings surfaces. */
public class PortableSettingsContractTest {

    @Test
    public void settingsRowsAreTheFourTopAlignedEntries() {
        MenuController controller = new MenuController(new NoopListener());
        controller.show(MenuRoute.SETTINGS);
        MenuPresentation page = controller.presentation();
        assertEquals(List.of("system", "display", "audio", "peripherals"),
                page.items().stream().map(MenuPresentation.Item::id).toList());
        assertEquals(List.of("SYSTEM", "DISPLAY", "AUDIO", "PERIPHERALS"),
                page.items().stream().map(MenuPresentation.Item::label).toList());

        List<Proposal3OverlayCatalog.Slot> rows =
                Proposal3OverlayCatalog.compactSettingsRows(page.items().size());
        assertEquals(4, rows.size());
        int firstY = rows.get(0).bounds().y();
        for (int index = 0; index < rows.size(); index++) {
            assertTrue(rows.get(index).bounds().y() >= firstY);
            if (index > 0) {
                assertTrue(rows.get(index - 1).bounds().bottom() < rows.get(index).bounds().y());
            }
        }
        assertTrue(rows.get(3).bounds().bottom() < Proposal3OverlayCatalog.SETTINGS_PANEL.bottom());
    }

    @Test
    public void routeAndDefaultCoverageMatchesSettingsContract() {
        assertEquals("PERIPHERALS", MenuRoute.OPTIONAL_DEVICES.label());
        assertEquals("DISPLAY", MenuRoute.DISPLAY.label());
        assertEquals("OPTION PICKER", MenuRoute.OPTION_PICKER.label());
        assertEquals(List.of("dmg-games", "cgb-games", "bootstrap", "execution-mode"),
                show(MenuRoute.SYSTEM).items().stream().map(MenuPresentation.Item::id).toList());
        assertEquals(List.of("sgb-border", "dmg-colors"),
                show(MenuRoute.DISPLAY).items().stream().map(MenuPresentation.Item::id).toList());
        assertEquals(List.of("camera", "gamepad", "gps"),
                show(MenuRoute.OPTIONAL_DEVICES).items().stream()
                        .map(MenuPresentation.Item::id).toList());
        assertEquals("GREEN", show(MenuRoute.DISPLAY).items().get(1).detail());
        assertEquals("SKIP", show(MenuRoute.SYSTEM).items().get(2).detail());
        assertEquals("PERFORMANCE", show(MenuRoute.SYSTEM).items().get(3).detail());
    }

    @Test
    public void choiceAndCheckboxRowsComposeWithPreparedAssets() throws Exception {
        Proposal3WidgetSkins skins = Proposal3WidgetSkins.load();
        assertNotNull(skins.choiceField());
        assertNotNull(skins.settingsIllustration(MenuRoute.SYSTEM));
        assertNotNull(skins.settingsIllustration(MenuRoute.DISPLAY));
        assertNotNull(skins.settingsIllustration(MenuRoute.OPTIONAL_DEVICES));

        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : List.of(MenuRoute.SYSTEM, MenuRoute.DISPLAY,
                MenuRoute.OPTIONAL_DEVICES)) {
            MenuController controller = new MenuController(new NoopListener());
            controller.show(route);
            MenuArgbFrame frame = compositor.compose(controller.presentation()).orElseThrow();
            assertEquals(924, frame.width());
            assertEquals(736, frame.height());
        }
    }

    @Test
    public void systemExecutionModeUsesTheFourthVisibleChoiceRow() {
        MenuController controller = new MenuController(new NoopListener());
        controller.show(MenuRoute.SYSTEM);
        Proposal3OverlayCatalog.RouteLayout layout =
                Proposal3OverlayCatalog.layout(MenuRoute.SYSTEM);

        assertEquals(4, layout.rows().size());
        MenuRect fourthRow = layout.rows().get(3).bounds();
        int[] frame = new Proposal3MenuCompositor().compose(controller.presentation())
                .orElseThrow().copyPixels();
        assertTrue(lightPixels(frame, fourthRow.x(), fourthRow.y(),
                fourthRow.width(), fourthRow.height()) > 20);
    }

    @Test
    public void optionPickerUsesChoiceIdsAndScrollArrows() {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            items.add(new MenuPageSpec.Item("choice:" + index, "VALUE " + index,
                    index == 2 ? "SELECTED" : "", true));
        }
        MenuPageSpec spec = new MenuPageSpec(MenuRoute.OPTION_PICKER, "COFFEE GB",
                "OPTION PICKER", "", "", List.of(), items, 1,
                List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), "choice:0", MenuPreview.empty());
        MenuController controller = new MenuController(new NoopListener());
        controller.setPage(spec);
        controller.show(MenuRoute.OPTION_PICKER);
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] top = compositor.compose(controller.presentation()).orElseThrow().copyPixels();
        controller.onKeyDown(MenuKey.DOWN, false);
        controller.onKeyUp(MenuKey.DOWN);
        controller.onKeyDown(MenuKey.DOWN, false);
        controller.onKeyUp(MenuKey.DOWN);
        controller.onKeyDown(MenuKey.DOWN, false);
        controller.onKeyUp(MenuKey.DOWN);
        int[] middle = compositor.compose(controller.presentation()).orElseThrow().copyPixels();
        assertFalse(java.util.Arrays.equals(top, middle));
        assertTrue(lightPixels(top, 420, 118, 489, 67) > 0);
        assertTrue(lightPixels(middle, 420, 118, 489, 67) > 0);
    }

    @Test
    public void optionPickerRetainsTheOriginatingSettingsIllustration() {
        assertEquals(MenuRoute.SYSTEM,
                Proposal3MenuCompositor.optionPickerIllustrationRoute("DMG GAMES"));
        assertEquals(MenuRoute.SYSTEM,
                Proposal3MenuCompositor.optionPickerIllustrationRoute("BOOTSTRAP"));
        assertEquals(MenuRoute.SYSTEM,
                Proposal3MenuCompositor.optionPickerIllustrationRoute("EXECUTION MODE"));
        assertEquals(MenuRoute.DISPLAY,
                Proposal3MenuCompositor.optionPickerIllustrationRoute("DMG COLORS"));
        assertEquals(MenuRoute.OPTIONAL_DEVICES,
                Proposal3MenuCompositor.optionPickerIllustrationRoute("CAMERA"));
        assertEquals(MenuRoute.OPTIONAL_DEVICES,
                Proposal3MenuCompositor.optionPickerIllustrationRoute("GAMEPAD"));
        assertEquals(null,
                Proposal3MenuCompositor.optionPickerIllustrationRoute("SELECT OPTION"));
    }

    @Test
    public void dynamicMasksStayInsideTheCanonicalFrame() {
        for (MenuRoute route : MenuRoute.values()) {
            for (MenuRect mask : Proposal3MenuCompositor.dynamicMasks(route)) {
                assertTrue(route + " mask escaped left edge", mask.x() >= 0);
                assertTrue(route + " mask escaped top edge", mask.y() >= 0);
                assertTrue(route + " mask escaped right edge", mask.right() <= 924);
                assertTrue(route + " mask escaped bottom edge", mask.bottom() <= 736);
            }
        }
    }

    private static int lightPixels(int[] pixels, int x, int y, int width, int height) {
        int count = 0;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                int pixel = pixels[row * 924 + column];
                if (((pixel >>> 16) & 0xff) > 180 && ((pixel >>> 8) & 0xff) > 175) {
                    count++;
                }
            }
        }
        return count;
    }

    private static MenuPresentation show(MenuRoute route) {
        MenuController controller = new MenuController(new NoopListener());
        controller.show(route);
        return controller.presentation();
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
