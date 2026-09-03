package eu.rekawek.coffeegb.ui.menu;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MenuReducerTest {

    @Test
    public void showAndMoveExposeAnImmutableFocusedPresentation() {
        MenuState hidden = MenuReducer.initial();
        assertFalse(hidden.visible());
        assertFalse(hidden.presentation().visible());

        MenuState state = MenuReducer.reduce(hidden, MenuCommand.show(MenuRoute.PAUSE_CONSOLE));
        assertTrue(state.visible());
        assertEquals(MenuRoute.PAUSE_CONSOLE, state.route());
        assertEquals("save-state", state.focusedItemId());

        state = MenuReducer.reduce(state, MenuCommand.move(MenuCommand.Direction.DOWN));
        assertEquals("load-state", state.focusedItemId());
        state = MenuReducer.reduce(state, MenuCommand.move(MenuCommand.Direction.UP));
        assertEquals("save-state", state.focusedItemId());
        state = MenuReducer.reduce(state, MenuCommand.move(MenuCommand.Direction.UP));
        assertEquals("settings", state.focusedItemId());

        MenuPresentation presentation = state.presentation();
        assertTrue(presentation.visible());
        assertEquals(MenuRoute.PAUSE_CONSOLE, presentation.route());
        assertEquals("settings", presentation.items().get(presentation.focusedIndex()).id());
        assertUnmodifiable(presentation.items());
        assertNotSame(presentation, state.presentation());
    }

    @Test
    public void rootCatalogsUseTheSharedPauseAndLibraryOrdering() {
        MenuPage pause = MenuPages.forRoute(MenuRoute.PAUSE_CONSOLE);
        assertEquals(List.of("save-state", "load-state", "open-rom", "reset", "recent-games",
                        "settings"),
                pause.items().stream().map(MenuItem::id).toList());
        assertEquals(List.of("D-PAD MOVE", "A CHOOSE", "B RESUME"), pause.footerHints());
        assertEquals("save-state", pause.items().get(pause.initialFocusIndex()).id());

        MenuPage library = MenuPages.forRoute(MenuRoute.LIBRARY);
        assertEquals(List.of("open-rom", "recent-games", "settings"),
                library.items().stream().map(MenuItem::id).toList());
        assertEquals("open-rom", library.items().get(library.initialFocusIndex()).id());
    }

    @Test
    public void everyPageUsesTheSingleVerticalOptionRail() {
        MenuPage page = new MenuPage(
                MenuRoute.SETTINGS,
                "COFFEE GB",
                "GRID TEST",
                "",
                "TEST",
                List.of("GRID"),
                List.of(
                        new MenuItem("one", "ONE"),
                        new MenuItem("two", "TWO"),
                        new MenuItem("three", "THREE"),
                        new MenuItem("four", "FOUR")),
                2,
                List.of("MOVE"));
        MenuState state = MenuState.visible(page, 0);

        assertEquals(1, page.columns());
        state = MenuReducer.move(state, MenuCommand.Direction.RIGHT);
        assertEquals("one", state.focusedItemId());
        state = MenuReducer.move(state, MenuCommand.Direction.DOWN);
        assertEquals("two", state.focusedItemId());
        state = MenuReducer.move(state, MenuCommand.Direction.DOWN);
        assertEquals("three", state.focusedItemId());
        state = MenuReducer.move(state, MenuCommand.Direction.LEFT);
        assertEquals("three", state.focusedItemId());
        state = MenuReducer.move(state, MenuCommand.Direction.UP);
        assertEquals("two", state.focusedItemId());
    }

    @Test
    public void pushAndBackPreserveParentFocusThenHideAtRoot() {
        MenuState state = MenuReducer.show(MenuReducer.initial(), MenuRoute.PAUSE_CONSOLE);
        assertEquals("save-state", state.focusedItemId());

        state = MenuReducer.push(state, MenuRoute.SETTINGS);
        assertEquals(2, state.depth());
        assertEquals(MenuRoute.SETTINGS, state.route());
        assertEquals("system", state.focusedItemId());

        state = MenuReducer.back(state);
        assertEquals(1, state.depth());
        assertEquals(MenuRoute.PAUSE_CONSOLE, state.route());
        assertEquals("save-state", state.focusedItemId());

        state = MenuReducer.back(state);
        assertFalse(state.visible());
        assertNull(state.route());
        assertEquals(-1, state.presentation().focusedIndex());
    }

    @Test
    public void leftAndRightAreNoOpsForTheProposalVerticalLists() {
        MenuState state = MenuReducer.show(MenuReducer.initial(), MenuRoute.SETTINGS);
        assertEquals("system", state.focusedItemId());
        assertEquals("system", MenuReducer.move(state, MenuCommand.Direction.LEFT).focusedItemId());
        assertEquals("system", MenuReducer.move(state, MenuCommand.Direction.RIGHT).focusedItemId());
    }

    private static void assertUnmodifiable(List<?> values) {
        try {
            values.clear();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Expected an immutable list");
    }
}
