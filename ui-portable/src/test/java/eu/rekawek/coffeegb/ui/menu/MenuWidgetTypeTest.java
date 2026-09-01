package eu.rekawek.coffeegb.ui.menu;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MenuWidgetTypeTest {

    @Test
    public void explicitWidgetTypesRoundTripFromPageSpecToPresentation() {
        List<MenuPageSpec.Item> items = List.of(
                MenuPageSpec.Item.button("button", "BUTTON", "", true),
                MenuPageSpec.Item.dropdown("dropdown", "DROPDOWN", "VALUE", true),
                MenuPageSpec.Item.checkbox("checkbox", "CHECKBOX", "ON", true),
                MenuPageSpec.Item.slider("slider", "SLIDER", "42%", true, 42));
        MenuController controller = new MenuController(new NoopListener());
        controller.setPage(new MenuPageSpec(MenuRoute.AUDIO, "COFFEE GB", "WIDGETS", "", "",
                List.of(), items, 1, List.of("B BACK")));
        controller.show(MenuRoute.AUDIO);

        assertEquals(List.of(MenuWidgetType.BUTTON, MenuWidgetType.DROPDOWN,
                        MenuWidgetType.CHECKBOX, MenuWidgetType.SLIDER),
                controller.presentation().items().stream()
                        .map(MenuPresentation.Item::widgetType).toList());
        assertFalse(controller.presentation().items().get(2).adjustable());
        assertTrue(controller.presentation().items().get(3).adjustable());
        assertEquals(42, controller.presentation().items().get(3).progress());
    }

    @Test
    public void legacyConstructorsRetainAdjustableBehavior() {
        MenuPageSpec.Item button = new MenuPageSpec.Item(
                "button", "BUTTON", "", true, null, false, -1);
        MenuPageSpec.Item slider = new MenuPageSpec.Item(
                "slider", "SLIDER", "50%", true, null, true, 50);

        assertEquals(MenuWidgetType.BUTTON, button.widgetType());
        assertFalse(button.adjustable());
        assertEquals(MenuWidgetType.SLIDER, slider.widgetType());
        assertTrue(slider.adjustable());
    }

    @Test
    public void progressIsValidatedAtPublicModelBoundaries() {
        assertThrows(IllegalArgumentException.class,
                () -> MenuPageSpec.Item.slider("slider", "SLIDER", "101%", true, 101));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuPresentation.Item("slider", "SLIDER", "-2%", true, null,
                        MenuWidgetType.SLIDER, -2));
    }

    @Test
    public void defaultCatalogUsesTheReusableWidgetKinds() {
        assertEquals(List.of(MenuWidgetType.SLIDER, MenuWidgetType.CHECKBOX),
                show(MenuRoute.AUDIO));
        assertEquals(List.of(MenuWidgetType.CHECKBOX, MenuWidgetType.DROPDOWN),
                show(MenuRoute.DISPLAY));
        assertEquals(List.of(MenuWidgetType.DROPDOWN, MenuWidgetType.DROPDOWN,
                        MenuWidgetType.CHECKBOX),
                show(MenuRoute.OPTIONAL_DEVICES));
        assertEquals(List.of(MenuWidgetType.DROPDOWN, MenuWidgetType.DROPDOWN,
                        MenuWidgetType.DROPDOWN, MenuWidgetType.DROPDOWN),
                show(MenuRoute.SYSTEM));
    }

    private static List<MenuWidgetType> show(MenuRoute route) {
        MenuController controller = new MenuController(new NoopListener());
        controller.show(route);
        return controller.presentation().items().stream()
                .map(MenuPresentation.Item::widgetType).toList();
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
