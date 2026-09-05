package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ensures routes never add actions that were not supplied by the portable page model. */
public class Proposal3UnavailableRouteTest {

    private static final int WIDTH = MenuArtworkCatalog.PACKAGED_WIDTH;

    @Test
    public void identicalModelsRenderIdenticallyAcrossFormerlySpecialCaseRoutes() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] expected = null;

        for (MenuRoute route : List.of(MenuRoute.LIBRARY, MenuRoute.CHOOSE_ROM, MenuRoute.ABOUT,
                MenuRoute.CONFIRM_ACTION, MenuRoute.PRINTER_PAPER)) {
            MenuPresentation presentation = statusOnly(route);
            assertEquals(List.of("status"), presentation.items().stream()
                    .map(MenuPresentation.Item::id).toList());
            assertFalse(presentation.items().stream().anyMatch(
                    item -> List.of("open-rom", "github", "confirm", "export-share-paper")
                            .contains(item.id())));

            int[] pixels = compositor.compose(presentation).orElseThrow().copyPixels();
            if (expected == null) {
                expected = pixels;
            } else {
                assertArrayEquals(route + " injected route-specific pixels", expected, pixels);
            }
        }
    }

    @Test
    public void suppliedActionsAreOrdinaryRowsAndNeverSyntheticActionStrips() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        int[] status = compositor.compose(statusOnly(MenuRoute.ABOUT)).orElseThrow().copyPixels();
        MenuPresentation withAction = presentation(MenuRoute.ABOUT, List.of(
                MenuPageSpec.Item.button("status", "NOT AVAILABLE", "", true),
                MenuPageSpec.Item.button("explicit-action", "EXPLICIT ACTION", "", true)));
        int[] action = compositor.compose(withAction).orElseThrow().copyPixels();

        MenuRect secondRow = MenuScreenTemplate.optionRow(1);
        assertTrue("explicit action did not occupy the second reusable row",
                differences(status, action, secondRow) > 0);
        assertEqualOutside(status, action, new MenuRect(secondRow.x(), secondRow.y(),
                secondRow.width(), secondRow.height() + MenuScreenTemplate.OPTION_DIVIDER_HEIGHT));
    }

    private static MenuPresentation statusOnly(MenuRoute route) {
        return presentation(route, List.of(
                MenuPageSpec.Item.button("status", "NOT AVAILABLE", "", true)));
    }

    private static MenuPresentation presentation(MenuRoute route,
            List<MenuPageSpec.Item> items) {
        MenuController controller = new MenuController(new NoopListener());
        controller.setPage(new MenuPageSpec(route, "COMMON TITLE", "", "", "COMMON SUBTITLE",
                List.of("SAME MODEL"), items, 1,
                List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), items.get(0).id(), preview()));
        controller.show(route);
        return controller.presentation();
    }

    private static MenuPreview preview() {
        return MenuPreview.ready(2, 2,
                new int[]{0xff102818, 0xffd1cca8, 0xff69775b, 0xff203a2a});
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

    private static void assertEqualOutside(int[] expected, int[] actual, MenuRect bounds) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            int x = index % WIDTH;
            int y = index / WIDTH;
            if (!bounds.contains(x, y)) {
                assertEquals("action escaped its reusable row at " + x + "," + y,
                        expected[index], actual[index]);
            }
        }
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
