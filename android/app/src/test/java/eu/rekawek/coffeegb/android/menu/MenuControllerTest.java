package eu.rekawek.coffeegb.android.menu;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuControllerTest {

    @Test
    public void repeatedConfirmAndKeyUpAreConsumedOnce() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(page(MenuRoute.CONFIRM_ACTION, 1,
                List.of(item("confirm", "CONFIRM", true, null))));
        controller.show(MenuRoute.CONFIRM_ACTION);

        assertTrue(controller.onKeyDown(MenuKey.A, false));
        assertTrue(controller.onKeyDown(MenuKey.A, true));
        assertTrue(controller.onKeyUp(MenuKey.A));

        assertEquals(List.of("confirm:false"), events.items);
        controller.onKeyDown(MenuKey.B, false);
        assertFalse(controller.visible());
        assertTrue(controller.onKeyUp(MenuKey.B));
    }

    @Test
    public void axisMovementTriggersOnlyOnEdgesUntilNeutral() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(page(MenuRoute.CONFIRM_ACTION, 2, List.of(
                item("one", "ONE", true, null), item("two", "TWO", true, null))));
        controller.show(MenuRoute.CONFIRM_ACTION);

        controller.onAxis(1.0f, 0.0f);
        controller.onAxis(1.0f, 0.0f);
        assertEquals(1, controller.presentation().focusedIndex());
        controller.onAxis(0.0f, 0.0f);
        controller.onAxis(1.0f, 0.0f);
        assertEquals(0, controller.presentation().focusedIndex());
    }

    @Test
    public void secondaryPointerActionUsesImmutablePageMetadata() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(page(MenuRoute.SAVE_STATES, 1,
                List.of(item("slot:0", "SLOT 0", true, "delete:0"))));
        controller.show(MenuRoute.SAVE_STATES);

        controller.updatePointer(7, List.of(MenuKey.SELECT));
        controller.updatePointer(7, List.of(MenuKey.SELECT));
        controller.releasePointer(7);

        assertEquals(List.of("delete:0:true"), events.items);
        assertEquals("slot:0", controller.presentation().items().get(0).id());
        assertEquals("delete:0", controller.presentation().items().get(0).secondaryId());
    }

    @Test
    public void systemBackDispatchesExactlyOneBEdgeAndFallsThroughWhenHidden() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.show(MenuRoute.PAUSE_CONSOLE);
        controller.push(MenuRoute.SETTINGS);

        assertTrue(controller.dispatchBackEdge());
        assertTrue(controller.visible());
        assertEquals(MenuRoute.PAUSE_CONSOLE, controller.route());

        assertTrue(controller.dispatchBackEdge());
        assertFalse(controller.visible());
        assertFalse(controller.dispatchBackEdge());
    }

    private static MenuPageSpec page(MenuRoute route, int columns, List<MenuPageSpec.Item> items) {
        return new MenuPageSpec(route, "COFFEE GB", "TEST", "", "TEST", List.of("TEST"),
                items, columns, List.of("[A] OK", "[B] BACK"));
    }

    private static MenuPageSpec.Item item(String id, String label, boolean enabled,
            String secondaryId) {
        return new MenuPageSpec.Item(id, label, "", enabled, secondaryId);
    }

    private static final class Events implements MenuController.Listener {
        private final List<String> items = new ArrayList<>();

        @Override
        public void onPresentation(MenuPresentation presentation) {
        }

        @Override
        public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            items.add(id + ":" + secondary);
        }

        @Override
        public void onHeaderSelected(MenuRoute route) {
        }
    }
}
