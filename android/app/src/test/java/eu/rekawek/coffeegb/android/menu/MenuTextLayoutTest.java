package eu.rekawek.coffeegb.android.menu;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertTrue;

public class MenuTextLayoutTest {

    private static final EnumSet<MenuRoute> PR3_ROUTES = EnumSet.of(
            MenuRoute.SETTINGS, MenuRoute.AUDIO, MenuRoute.TOUCH_CONTROLS,
            MenuRoute.CONTROLLER_MAPPING, MenuRoute.OPTIONAL_DEVICES,
            MenuRoute.PRINTER_PAPER, MenuRoute.SYSTEM, MenuRoute.DATA_MEDIA,
            MenuRoute.ABOUT);

    @Test
    public void allNineRoutesReserveNonOverlappingMeasuredColumnsInBothOrientations() {
        for (boolean portrait : new boolean[]{false, true}) {
            float headerRight = portrait ? 235.0f : 315.0f;
            float listLeft = portrait ? 8.0f : 123.0f;
            float listRight = portrait ? 232.0f : 312.0f;
            for (MenuRoute route : PR3_ROUTES) {
                MenuController controller = new MenuController(new MenuController.Listener() {
                    @Override
                    public void onPresentation(MenuPresentation presentation) {
                    }

                    @Override
                    public void onItemSelected(MenuRoute route, String id, boolean secondary) {
                    }

                    @Override
                    public void onHeaderSelected(MenuRoute route) {
                    }
                });
                controller.show(route);
                MenuPresentation page = controller.presentation();
                float actionWidth = page.headerAction().isEmpty() ? 0.0f : 68.0f;
                MenuTextLayout.HeaderColumns header = MenuTextLayout.header(5.0f,
                        headerRight, width(page.title(), 12.0f), !page.context().isEmpty(),
                        actionWidth);
                assertSeparated(route + " header", header.title(), header.context());
                assertSeparated(route + " context/action", header.context(), header.action());
                assertFits(page.title(), header.title(), 12.0f);
                assertFits(page.context(), header.context(), 8.0f);

                for (MenuPresentation.Item item : page.items()) {
                    MenuTextLayout.RowColumns row = MenuTextLayout.row(listLeft, listRight,
                            !item.detail().isEmpty());
                    assertSeparated(route + " row " + item.id(), row.label(), row.detail());
                    assertFits(item.label(), row.label(), 10.0f);
                    assertFits(item.detail(), row.detail(), 9.0f);
                }
            }
        }
    }

    private static void assertFits(String value, MenuTextLayout.Span span, float size) {
        if (value.isEmpty()) {
            return;
        }
        String fitted = MenuTextLayout.ellipsize(value, span.width(),
                text -> width(text, size));
        assertTrue(value + " did not fit " + span,
                width(fitted, size) <= span.width() + 0.001f);
    }

    private static void assertSeparated(String message, MenuTextLayout.Span left,
            MenuTextLayout.Span right) {
        assertTrue(message, left.right() <= right.left() + 0.001f);
    }

    private static float width(String value, float size) {
        return value.length() * size * 0.62f;
    }
}
