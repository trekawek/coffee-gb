package eu.rekawek.coffeegb.ui.menu;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MenuControllerTest {

    @Test
    public void recentGamesPagePublishesFocusedPreviewAndLastPlayedMetadata() {
        int[] pixels = {0xff0f0f0f, 0xffb7dd79, 0xff396e55, 0xff172c34};
        MenuPreview preview = MenuPreview.ready(2, 2, pixels);
        MenuPageSpec spec = MenuPageSpec.recentGames(List.of(
                new MenuPageSpec.RecentGame("game-a", "ADVENTURE BOY.GB", "TODAY", true,
                        MenuPreview.empty()),
                new MenuPageSpec.RecentGame("game-b", "POCKET CAMERA.GBC", "YESTERDAY", true,
                        preview)), "game-b");
        assertEquals(MenuRoute.RECENT_GAMES, spec.route());
        assertEquals("game-b", spec.preferredFocusId());
        assertEquals("POCKET CAMERA.GBC", spec.items().get(1).label());
        assertEquals(List.of("LAST PLAYED: YESTERDAY"), spec.sideLines());
        assertEquals(preview, spec.preview());
        assertEquals(List.of("D-PAD MOVE", "A OPEN", "B BACK"), spec.footerHints());

        MenuPageSpec empty = MenuPageSpec.recentGames(List.of(), null);
        assertEquals("recent-games-status", empty.items().get(0).id());
        assertEquals("NO RECENT GAMES", empty.items().get(0).label());
        assertEquals(List.of("", "", "B BACK"), empty.footerHints());

        MenuPageSpec unavailable = MenuPageSpec.recentGames(List.of(
                new MenuPageSpec.RecentGame("missing", "MISSING.GB", "LAST WEEK", false,
                        MenuPreview.empty())), null);
        assertEquals("recent-games-status", unavailable.preferredFocusId());
        assertEquals("NO READABLE RECENT GAMES", unavailable.items().get(1).label());
    }

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
    public void verticalAxisMovementTriggersOnlyOnEdgesAndHorizontalAxisIsInert() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(page(MenuRoute.CONFIRM_ACTION, 2, List.of(
                item("one", "ONE", true, null), item("two", "TWO", true, null))));
        controller.show(MenuRoute.CONFIRM_ACTION);

        controller.onAxis(0.0f, 1.0f);
        controller.onAxis(0.0f, 1.0f);
        assertEquals(1, controller.presentation().focusedIndex());
        controller.onAxis(0.0f, 0.0f);
        controller.onAxis(0.0f, 1.0f);
        assertEquals(0, controller.presentation().focusedIndex());
        controller.onAxis(0.0f, 0.0f);
        controller.onAxis(1.0f, 0.0f);
        assertEquals(0, controller.presentation().focusedIndex());
    }

    @Test
    public void selectIsInertAndSecondaryPointerActionUsesImmutablePageMetadata() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(page(MenuRoute.SAVE_STATES, 1,
                List.of(item("slot:0", "SLOT 0", true, "delete:0"))));
        controller.show(MenuRoute.SAVE_STATES);

        controller.updatePointer(7, List.of(MenuKey.SELECT));
        controller.updatePointer(7, List.of(MenuKey.SELECT));
        controller.releasePointer(7);

        assertEquals(List.of(), events.items);
        controller.updatePointer(7, List.of(MenuKey.SECONDARY));
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
    public void fullWidthPagesRequestBoundedPageChangesOncePerInputEdge() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(fullWidthPage(1, 3,
                List.of(item("rom", "A VERY LONG ROM NAME.GB", true, null))));
        controller.show(MenuRoute.FILE_BROWSER);

        controller.onKeyDown(MenuKey.LEFT, false);
        controller.onKeyDown(MenuKey.LEFT, true);
        controller.onKeyUp(MenuKey.LEFT);
        controller.onKeyDown(MenuKey.RIGHT, false);
        controller.onKeyDown(MenuKey.RIGHT, false);
        controller.onKeyUp(MenuKey.RIGHT);

        assertEquals(List.of("FILE_BROWSER:0", "FILE_BROWSER:2"), events.pageRequests);
        assertEquals("rom", controller.snapshot().frames().get(0).focusedItemId());

        controller.setPage(fullWidthPage(0, 3,
                List.of(item("first", "FIRST.GB", true, null))));
        controller.onKeyDown(MenuKey.LEFT, false);
        controller.onKeyUp(MenuKey.LEFT);
        controller.setPage(fullWidthPage(2, 3,
                List.of(item("last", "LAST.GB", true, null))));
        controller.onKeyDown(MenuKey.RIGHT, false);
        controller.onKeyUp(MenuKey.RIGHT);

        assertEquals("out-of-range directions must not request a page",
                List.of("FILE_BROWSER:0", "FILE_BROWSER:2"), events.pageRequests);
    }

    @Test
    public void fullWidthRowsDelegateVerticalMovementWithoutReducerWrapping() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(fullWidthPage(0, 1, List.of(
                item("one", "ONE", true, null),
                item("two", "TWO", true, null))));
        controller.show(MenuRoute.FILE_BROWSER);

        controller.onKeyDown(MenuKey.UP, false);
        controller.onKeyDown(MenuKey.UP, true);
        controller.onKeyUp(MenuKey.UP);
        controller.onKeyDown(MenuKey.DOWN, false);
        controller.onKeyUp(MenuKey.DOWN);

        assertEquals(List.of("FILE_BROWSER:-1", "FILE_BROWSER:1"), events.rowRequests);
        assertEquals("one", controller.snapshot().frames().get(0).focusedItemId());
    }

    @Test
    public void fullWidthLayoutAloneKeepsOrdinaryReducerNavigation() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(new MenuPageSpec(MenuRoute.ABOUT, "COFFEE GB", "ABOUT", "", "",
                List.of(), List.of(
                        item("one", "ONE", true, null),
                        item("two", "TWO", true, null)),
                1, List.of("D-PAD MOVE", "A OPEN", "B BACK"), null, MenuPreview.empty(),
                MenuPageLayout.FULL_WIDTH_LIST, MenuPagination.singlePage()));
        controller.show(MenuRoute.ABOUT);

        controller.onKeyDown(MenuKey.DOWN, false);
        controller.onKeyUp(MenuKey.DOWN);
        assertEquals("two", controller.snapshot().frames().get(0).focusedItemId());
        controller.onKeyDown(MenuKey.DOWN, false);
        controller.onKeyUp(MenuKey.DOWN);

        assertEquals("one", controller.snapshot().frames().get(0).focusedItemId());
        assertEquals(List.of(), events.rowRequests);
    }

    @Test
    public void pageReplacementCanForceFocusWhenTheOldRowRemainsVisible() {
        MenuController controller = new MenuController(new Events());
        controller.setPage(fullWidthPage(0, 2, List.of(
                item("old", "OLD", true, null),
                item("edge", "EDGE", true, null))));
        controller.show(MenuRoute.FILE_BROWSER);
        controller.setPageAndFocus(fullWidthPage(1, 2, List.of(
                item("edge", "EDGE", true, null),
                item("next", "NEXT", true, null))), "next");

        assertEquals("next", controller.snapshot().frames().get(0).focusedItemId());
        assertThrows(IllegalArgumentException.class, () -> controller.setPageAndFocus(
                fullWidthPage(1, 2, List.of(item("edge", "EDGE", true, null))), "missing"));
        controller.hide();
        controller.show(MenuRoute.FILE_BROWSER);
        assertEquals(List.of("edge", "next"), controller.presentation().items().stream()
                .map(MenuPresentation.Item::id).toList());
    }

    @Test
    public void fullWidthSliderAdjustmentTakesPrecedenceOverPagination() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        MenuPageSpec.Item slider = new MenuPageSpec.Item("volume", "VOLUME", "50%", true,
                null, MenuWidgetType.SLIDER, 50);
        controller.setPage(fullWidthPage(1, 3, List.of(slider)));
        controller.show(MenuRoute.FILE_BROWSER);

        controller.onKeyDown(MenuKey.RIGHT, false);
        controller.onKeyUp(MenuKey.RIGHT);

        assertEquals(List.of("volume:1"), events.adjustments);
        assertEquals(List.of(), events.pageRequests);
    }

    @Test
    public void layoutAndPaginationPropagateToPresentationAndDefaultToSplit() {
        MenuController controller = new MenuController(new Events());
        MenuPageSpec legacy = page(MenuRoute.ABOUT, 1,
                List.of(item("about", "ABOUT", true, null)));
        assertEquals(MenuPageLayout.SPLIT, legacy.layout());
        assertEquals(MenuPagination.singlePage(), legacy.pagination());

        MenuPageSpec fullWidth = fullWidthPage(2, 4,
                List.of(item("rom", "ROM.GB", true, null)));
        controller.setPage(fullWidth);
        controller.show(MenuRoute.FILE_BROWSER);

        assertEquals(MenuPageLayout.FULL_WIDTH_LIST, controller.presentation().layout());
        assertEquals(new MenuPagination(2, 4), controller.presentation().pagination());

        MenuPage defaultFileBrowser = MenuPages.forRoute(MenuRoute.FILE_BROWSER);
        assertEquals(MenuPageLayout.FULL_WIDTH_LIST, defaultFileBrowser.layout());
        assertEquals(MenuPagination.singlePage(), defaultFileBrowser.pagination());
    }

    @Test
    public void paginationAndFullWidthRowCapacityAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new MenuPagination(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MenuPagination(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MenuPagination(2, 2));
        assertFalse(new MenuPagination(0, 3).hasPreviousPage());
        assertTrue(new MenuPagination(0, 3).hasNextPage());
        assertTrue(new MenuPagination(2, 3).hasPreviousPage());
        assertFalse(new MenuPagination(2, 3).hasNextPage());

        ArrayList<MenuPageSpec.Item> sevenItems = new ArrayList<>();
        for (int index = 0; index < MenuPageSpec.FULL_WIDTH_ITEM_LIMIT; index++) {
            sevenItems.add(item("rom-" + index, "ROM " + index, true, null));
        }
        fullWidthPage(0, 1, sevenItems);
        sevenItems.add(item("rom-7", "ROM 7", true, null));
        assertThrows(IllegalArgumentException.class,
                () -> fullWidthPage(0, 1, sevenItems));
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
    public void rootBackInterceptionLeavesChildNavigationUntouched() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setRootBackIntercepted(true);
        controller.show(MenuRoute.PAUSE_CONSOLE);

        assertTrue(controller.dispatchBackEdge());
        assertTrue(controller.visible());
        assertEquals(List.of(MenuRoute.PAUSE_CONSOLE), events.backRoutes);

        controller.push(MenuRoute.SETTINGS);
        assertTrue(controller.dispatchBackEdge());
        assertEquals(MenuRoute.PAUSE_CONSOLE, controller.route());
        assertEquals(List.of(MenuRoute.PAUSE_CONSOLE), events.backRoutes);

        controller.setRootBackIntercepted(false);
        assertTrue(controller.dispatchBackEdge());
        assertFalse(controller.visible());
    }

    @Test
    public void removedResumeFocusFallsBackToTheFirstPauseAction() {
        MenuController controller = new MenuController(new Events());

        controller.restore(new MenuStackSnapshot(List.of(
                new MenuStackSnapshot.Frame(MenuRoute.PAUSE_CONSOLE, "resume"))));

        assertEquals("save-state", controller.snapshot().frames().get(0).focusedItemId());
    }

    @Test
    public void lockedRootConsumesBackWhileChildRoutesStillPop() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setRootDismissAllowed(false);
        controller.show(MenuRoute.LIBRARY);

        assertTrue(controller.dispatchBackEdge());
        assertTrue(controller.visible());
        assertEquals(MenuRoute.LIBRARY, controller.route());

        controller.push(MenuRoute.SETTINGS);
        assertTrue(controller.dispatchBackEdge());
        assertTrue(controller.visible());
        assertEquals(MenuRoute.LIBRARY, controller.route());

        controller.setRootDismissAllowed(true);
        assertTrue(controller.dispatchBackEdge());
        assertFalse(controller.visible());
    }

    @Test
    public void fullStackRestorePreservesEveryFocusAndUsesPreferredEnabledFallback() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.show(MenuRoute.PAUSE_CONSOLE);
        for (int index = 0; index < 5; index++) {
            controller.onKeyDown(MenuKey.DOWN, false);
            controller.onKeyUp(MenuKey.DOWN);
        }
        controller.push(MenuRoute.SETTINGS);
        for (int index = 0; index < 1; index++) {
            controller.onKeyDown(MenuKey.DOWN, false);
            controller.onKeyUp(MenuKey.DOWN);
        }
        MenuStackSnapshot snapshot = controller.snapshot();
        controller.hide();
        controller.restore(snapshot);

        assertEquals(MenuRoute.SETTINGS, controller.route());
        assertEquals("display",
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
                        item("loading", "LOADING", true, null)), 1, List.of("BACK")));
        controller.restore(desired);
        assertEquals("loading", controller.snapshot().frames().get(1).focusedItemId());

        controller.setPage(new MenuPageSpec(MenuRoute.PRINTER_PAPER, "COFFEE GB", "PAPER", "",
                "PAPER", List.of("READY"), List.of(
                        item("clear-paper", "CLEAR", true, null),
                        item("export-share-paper", "EXPORT", true, null),
                        item("loading", "LOADING", false, null)), 1, List.of("BACK")));
        controller.restore(desired);
        assertEquals("export-share-paper",
                controller.snapshot().frames().get(1).focusedItemId());
    }

    @Test
    public void startActivatesExactlyLikeAAndSelectNeverActivates() {
        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.setPage(page(MenuRoute.CONFIRM_ACTION, 1,
                List.of(item("confirm", "CONFIRM", true, null))));
        controller.show(MenuRoute.CONFIRM_ACTION);

        controller.onKeyDown(MenuKey.SELECT, false);
        controller.onKeyUp(MenuKey.SELECT);
        controller.onKeyDown(MenuKey.START, false);
        controller.onKeyDown(MenuKey.START, true);
        controller.onKeyUp(MenuKey.START);
        controller.onKeyDown(MenuKey.A, false);
        controller.onKeyUp(MenuKey.A);

        assertEquals(List.of("confirm:false", "confirm:false"), events.items);
    }

    @Test
    public void statePageVariantsUseExactCopyAndOnlyFourPrimarySlotRows() {
        MenuPage save = MenuPages.statePage(false);
        MenuPage load = MenuPages.statePage(true);

        assertEquals("COFFEE GB", save.title());
        assertEquals("SAVE STATES", save.context());
        assertEquals("", save.headerAction());
        assertEquals("", save.sideHeading());
        assertEquals(List.of(), save.sideLines());
        assertEquals(List.of("D-PAD MOVE", "A SAVE", "B BACK"), save.footerHints());

        assertEquals("COFFEE GB", load.title());
        assertEquals("LOAD STATES", load.context());
        assertEquals("", load.headerAction());
        assertEquals(List.of("D-PAD MOVE", "A LOAD", "B BACK"), load.footerHints());

        List<String> expectedSlots = List.of("slot-0", "slot-1", "slot-2", "slot-3",
                "slot-4", "slot-5", "slot-6", "slot-7", "slot-8", "slot-9");
        assertEquals(expectedSlots, save.items().stream().map(MenuItem::id).toList());
        assertEquals(expectedSlots, load.items().stream().map(MenuItem::id).toList());
        assertTrue(save.items().stream().allMatch(item -> item.enabled()
                && item.detail().isEmpty() && item.secondaryId() == null));
        assertTrue(load.items().stream().allMatch(item -> item.enabled()
                && item.detail().isEmpty() && item.secondaryId() == null));
    }

    @Test
    public void confirmationDefaultsUseTheSingleVerticalRailAndBReturnsToParent() {
        MenuPage confirmation = MenuPages.forRoute(MenuRoute.CONFIRM_ACTION);
        assertEquals(1, confirmation.columns());
        assertEquals("CONFIRM ACTION", confirmation.context());
        assertEquals(List.of("UNSAVED PROGRESS MAY BE LOST"), confirmation.sideLines());
        assertEquals("", confirmation.headerAction());
        assertEquals(List.of("confirm", "cancel"),
                confirmation.items().stream().map(MenuItem::id).toList());
        assertTrue(confirmation.items().stream().allMatch(item -> item.detail().isEmpty()));
        assertEquals("cancel", confirmation.items().get(confirmation.initialFocusIndex()).id());

        Events events = new Events();
        MenuController controller = new MenuController(events);
        controller.show(MenuRoute.PAUSE_CONSOLE);
        controller.push(MenuRoute.CONFIRM_ACTION);
        assertEquals("cancel", controller.snapshot().frames().get(1).focusedItemId());

        controller.onKeyDown(MenuKey.UP, false);
        controller.onKeyUp(MenuKey.UP);
        assertEquals("confirm",
                controller.snapshot().frames().get(1).focusedItemId());
        controller.onKeyDown(MenuKey.RIGHT, false);
        controller.onKeyUp(MenuKey.RIGHT);
        assertEquals("horizontal input must not move the vertical rail", "confirm",
                controller.snapshot().frames().get(1).focusedItemId());
        controller.onKeyDown(MenuKey.DOWN, false);
        controller.onKeyUp(MenuKey.DOWN);
        assertEquals("cancel", controller.snapshot().frames().get(1).focusedItemId());
        controller.onKeyDown(MenuKey.A, false);
        controller.onKeyUp(MenuKey.A);
        assertEquals(List.of("cancel:false"), events.items);
        controller.onKeyDown(MenuKey.UP, false);
        controller.onKeyUp(MenuKey.UP);
        controller.onKeyDown(MenuKey.START, false);
        controller.onKeyUp(MenuKey.START);
        assertEquals(List.of("cancel:false", "confirm:false"), events.items);

        controller.onKeyDown(MenuKey.B, false);
        controller.onKeyUp(MenuKey.B);
        assertEquals(MenuRoute.PAUSE_CONSOLE, controller.route());
    }

    private static MenuPageSpec page(MenuRoute route, int columns, List<MenuPageSpec.Item> items) {
        return new MenuPageSpec(route, "COFFEE GB", "TEST", "", "TEST", List.of("TEST"),
                items, columns, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"));
    }

    private static MenuPageSpec fullWidthPage(int pageIndex, int pageCount,
            List<MenuPageSpec.Item> items) {
        return new MenuPageSpec(MenuRoute.FILE_BROWSER, "COFFEE GB", "OPEN ROM", "", "",
                List.of(), items, 1, List.of("D-PAD MOVE", "A OPEN", "B BACK"), null,
                MenuPreview.empty(), MenuPageLayout.FULL_WIDTH_LIST,
                new MenuPagination(pageIndex, pageCount));
    }

    private static MenuPageSpec.Item item(String id, String label, boolean enabled,
            String secondaryId) {
        return new MenuPageSpec.Item(id, label, "", enabled, secondaryId);
    }

    private static final class Events implements MenuController.Listener {
        private final List<String> items = new ArrayList<>();
        private final List<String> adjustments = new ArrayList<>();
        private final List<MenuRoute> backRoutes = new ArrayList<>();
        private final List<String> pageRequests = new ArrayList<>();
        private final List<String> rowRequests = new ArrayList<>();

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

        @Override
        public void onPageRequested(MenuRoute route, int targetIndex) {
            pageRequests.add(route + ":" + targetIndex);
        }

        @Override
        public void onListRowRequested(MenuRoute route, int direction) {
            rowRequests.add(route + ":" + direction);
        }
    }
}
