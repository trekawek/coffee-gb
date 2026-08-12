package eu.rekawek.coffeegb.ui.menu;

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

    @Test
    public void adjustableRowsEmitOneSemanticEdgeWithoutMovingGridFocus() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(new MenuPageSpec(MenuRoute.AUDIO, "COFFEE GB", "AUDIO", "", "AUDIO",
                List.of("TEST"), List.of(
                        new MenuPageSpec.Item("volume", "VOLUME", "50%", true, null, true, 50),
                        item("save", "SAVE", true, null)), 2, List.of("MOVE")));
        controller.show(MenuRoute.AUDIO);

        controller.onKeyDown(MenuKey.RIGHT, false);
        controller.onKeyDown(MenuKey.RIGHT, true);
        controller.onKeyUp(MenuKey.RIGHT);
        controller.onAxis(-1.0f, 0.0f);
        controller.onAxis(-1.0f, 0.0f);

        assertEquals(List.of("volume:1", "volume:-1"), events.adjustments);
        assertEquals("volume", controller.snapshot().frames().get(0).focusedItemId());
    }

    @Test
    public void adjustableMuteEmitsOneToggleEdgeForLeftRightAndConfirmRemainsSelection() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(new MenuPageSpec(MenuRoute.AUDIO, "COFFEE GB", "AUDIO", "", "AUDIO",
                List.of("TEST"), List.of(
                        new MenuPageSpec.Item("mute-audio", "MUTE", "OFF", true,
                                null, true, -1)), 1, List.of("MOVE")));
        controller.show(MenuRoute.AUDIO);

        controller.onKeyDown(MenuKey.LEFT, false);
        controller.onKeyDown(MenuKey.LEFT, true);
        controller.onKeyUp(MenuKey.LEFT);
        controller.onKeyDown(MenuKey.RIGHT, false);
        controller.onKeyUp(MenuKey.RIGHT);
        controller.onKeyDown(MenuKey.A, false);
        controller.onKeyUp(MenuKey.A);

        assertEquals(List.of("mute-audio:-1", "mute-audio:1"), events.adjustments);
        assertEquals(List.of("mute-audio:false"), events.items);
    }

    @Test
    public void captureBackInterceptionKeepsRouteVisibleUntilReleased() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.show(MenuRoute.CONTROLLER_MAPPING);
        controller.setBackIntercepted(true);

        assertTrue(controller.dispatchBackEdge());
        assertTrue(controller.visible());
        assertEquals(List.of(MenuRoute.CONTROLLER_MAPPING), events.backRoutes);

        controller.setBackIntercepted(false);
        assertTrue(controller.dispatchBackEdge());
        assertFalse(controller.visible());
    }

    @Test
    public void fullStackRestorePreservesEveryFocusAndUsesPreferredEnabledFallback() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.show(MenuRoute.PAUSE_CONSOLE);
        for (int index = 0; index < 4; index++) {
            controller.onKeyDown(MenuKey.DOWN, false);
            controller.onKeyUp(MenuKey.DOWN);
        }
        controller.push(MenuRoute.SETTINGS);
        for (int index = 0; index < 3; index++) {
            controller.onKeyDown(MenuKey.DOWN, false);
            controller.onKeyUp(MenuKey.DOWN);
        }
        MenuStackSnapshot snapshot = controller.snapshot();
        controller.hide();
        controller.restore(snapshot);

        assertEquals(MenuRoute.SETTINGS, controller.route());
        assertEquals("optional-devices",
                controller.snapshot().frames().get(1).focusedItemId());
        controller.back();
        assertEquals("settings", controller.snapshot().frames().get(0).focusedItemId());

        controller.setPage(new MenuPageSpec(MenuRoute.ABOUT, "COFFEE GB", "ABOUT", "", "ABOUT",
                List.of("TEST"), List.of(
                        item("disabled", "DISABLED", false, null),
                        item("enabled", "ENABLED", true, null)), 1, List.of("BACK"),
                "disabled", MenuPreview.empty()));
        controller.show(MenuRoute.ABOUT);
        assertEquals("enabled", controller.snapshot().frames().get(0).focusedItemId());
    }

    @Test
    public void printerFocusCanBeDeferredUntilReloadedRowBecomesEnabled() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        MenuStackSnapshot desired = new MenuStackSnapshot(List.of(
                new MenuStackSnapshot.Frame(MenuRoute.SETTINGS, "optional-devices"),
                new MenuStackSnapshot.Frame(MenuRoute.PRINTER_PAPER, "export-share-paper")));
        controller.setPage(new MenuPageSpec(MenuRoute.PRINTER_PAPER, "COFFEE GB", "PAPER", "",
                "PAPER", List.of("LOADING"), List.of(
                        item("clear-paper", "CLEAR", false, null),
                        item("export-share-paper", "EXPORT", false, null),
                        item("back", "BACK", true, null)), 1, List.of("BACK")));
        controller.restore(desired);
        assertEquals("back", controller.snapshot().frames().get(1).focusedItemId());

        controller.setPage(new MenuPageSpec(MenuRoute.PRINTER_PAPER, "COFFEE GB", "PAPER", "",
                "PAPER", List.of("READY"), List.of(
                        item("clear-paper", "CLEAR", true, null),
                        item("export-share-paper", "EXPORT", true, null),
                        item("back", "BACK", true, null)), 1, List.of("BACK")));
        controller.restore(desired);
        assertEquals("export-share-paper",
                controller.snapshot().frames().get(1).focusedItemId());
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
        private final List<String> adjustments = new ArrayList<>();
        private final List<MenuRoute> backRoutes = new ArrayList<>();

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

        @Override
        public void onItemAdjusted(MenuRoute route, String id, int direction) {
            adjustments.add(id + ":" + direction);
        }

        @Override
        public void onBackIntercepted(MenuRoute route) {
            backRoutes.add(route);
        }
    }
}
