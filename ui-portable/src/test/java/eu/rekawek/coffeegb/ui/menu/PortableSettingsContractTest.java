package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.MenuWidgetType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Contracts for the reusable option-row widget library. */
public class PortableSettingsContractTest {

    private static final int WIDTH = MenuArtworkCatalog.PACKAGED_WIDTH;

    @Test
    public void allFourWidgetKindsShareSevenEqual72PixelSlotsAndTheLicensedMediumMetrics()
            throws Exception {
        assertEquals(7, MenuScreenTemplate.OPTION_ROW_COUNT);
        assertEquals(72, MenuScreenTemplate.OPTION_ROW_HEIGHT);
        assertEquals(7, MenuScreenTemplate.OPTION_ROWS.size());

        MenuRect first = MenuScreenTemplate.optionRow(0);
        for (int index = 0; index < MenuScreenTemplate.OPTION_ROW_COUNT; index++) {
            MenuRect row = MenuScreenTemplate.optionRow(index);
            assertEquals(first.x(), row.x());
            assertEquals(first.width(), row.width());
            assertEquals(72, row.height());
            if (index > 0) {
                assertEquals(MenuScreenTemplate.OPTION_DIVIDER_HEIGHT,
                        row.y() - MenuScreenTemplate.optionRow(index - 1).bottom());
            }
        }

        MenuPresentation widgets = presentation(widgetItems(), "button");
        assertEquals(List.of(MenuWidgetType.BUTTON, MenuWidgetType.DROPDOWN,
                        MenuWidgetType.CHECKBOX, MenuWidgetType.SLIDER),
                widgets.items().stream().map(MenuPresentation.Item::widgetType).toList());
        assertEquals(Proposal3GlyphAtlas.Role.MEDIUM,
                Proposal3MenuCompositor.itemTextRole());
        assertEquals(Proposal3GlyphAtlas.Role.MEDIUM,
                Proposal3MenuCompositor.footerTextRole());
        Proposal3GlyphAtlas atlas = Proposal3GlyphAtlas.load();
        assertEquals("medium glyph advance must match the packaged atlas recipe", 19,
                atlas.advance(Proposal3GlyphAtlas.Role.MEDIUM, 'A'));
        assertEquals("medium space advance must match the packaged atlas recipe", 8,
                atlas.advance(Proposal3GlyphAtlas.Role.MEDIUM, ' '));
    }

    @Test
    public void replacingAWidgetChangesPixelsOnlyInsideItsExistingRow() {
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        MenuRect replacedRow = MenuScreenTemplate.optionRow(1);
        int[] button = compose(compositor, variant(MenuWidgetType.BUTTON));

        for (MenuWidgetType type : List.of(MenuWidgetType.DROPDOWN,
                MenuWidgetType.CHECKBOX, MenuWidgetType.SLIDER)) {
            int[] replacement = compose(compositor, variant(type));
            assertTrue(type + " was visually identical to a button",
                    differences(button, replacement, replacedRow) > 0);
            assertEqualOutside(button, replacement, replacedRow);
        }
    }

    @Test
    public void defaultSettingsScreensPublishTypedReusableWidgets() {
        assertEquals(List.of(MenuWidgetType.BUTTON, MenuWidgetType.BUTTON,
                        MenuWidgetType.BUTTON, MenuWidgetType.BUTTON),
                types(MenuRoute.SETTINGS));
        assertEquals(List.of(MenuWidgetType.SLIDER, MenuWidgetType.CHECKBOX),
                types(MenuRoute.AUDIO));
        assertEquals(List.of(MenuWidgetType.CHECKBOX, MenuWidgetType.DROPDOWN),
                types(MenuRoute.DISPLAY));
        assertEquals(List.of(MenuWidgetType.DROPDOWN, MenuWidgetType.DROPDOWN,
                        MenuWidgetType.CHECKBOX),
                types(MenuRoute.OPTIONAL_DEVICES));
        assertEquals(List.of(MenuWidgetType.DROPDOWN, MenuWidgetType.DROPDOWN,
                        MenuWidgetType.DROPDOWN, MenuWidgetType.DROPDOWN),
                types(MenuRoute.SYSTEM));
    }

    private static MenuPresentation variant(MenuWidgetType type) {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        items.add(MenuPageSpec.Item.button("focus", "FOCUS", "", true));
        items.add(switch (type) {
            case BUTTON -> MenuPageSpec.Item.button("variant", "WIDGET", "VALUE", true);
            case DROPDOWN -> MenuPageSpec.Item.dropdown("variant", "WIDGET", "VALUE", true);
            case CHECKBOX -> MenuPageSpec.Item.checkbox("variant", "WIDGET", "ON", true);
            case SLIDER -> MenuPageSpec.Item.slider("variant", "WIDGET", "50%", true, 50);
        });
        items.add(MenuPageSpec.Item.button("tail", "TAIL", "", true));
        return presentation(items, "focus");
    }

    private static List<MenuPageSpec.Item> widgetItems() {
        return List.of(
                MenuPageSpec.Item.button("button", "BUTTON", "", true),
                MenuPageSpec.Item.dropdown("dropdown", "DROPDOWN", "VALUE", true),
                MenuPageSpec.Item.checkbox("checkbox", "CHECKBOX", "ON", true),
                MenuPageSpec.Item.slider("slider", "SLIDER", "50%", true, 50));
    }

    private static MenuPresentation presentation(List<MenuPageSpec.Item> items, String focus) {
        MenuController controller = new MenuController(new NoopListener());
        controller.setPage(new MenuPageSpec(MenuRoute.SETTINGS, "WIDGETS", "", "", "",
                List.of(), items, 1, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), focus,
                MenuPreview.empty()));
        controller.show(MenuRoute.SETTINGS);
        return controller.presentation();
    }

    private static int[] compose(Proposal3MenuCompositor compositor,
            MenuPresentation presentation) {
        return compositor.compose(presentation).orElseThrow().copyPixels();
    }

    private static List<MenuWidgetType> types(MenuRoute route) {
        MenuController controller = new MenuController(new NoopListener());
        controller.show(route);
        return controller.presentation().items().stream()
                .map(MenuPresentation.Item::widgetType).toList();
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
                assertEquals("replacement escaped its row at " + x + "," + y,
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
