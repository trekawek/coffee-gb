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
        assertEquals("resume", state.focusedItemId());

        state = MenuReducer.reduce(state, MenuCommand.move(MenuCommand.Direction.DOWN));
        assertEquals("save-state", state.focusedItemId());
        state = MenuReducer.reduce(state, MenuCommand.move(MenuCommand.Direction.UP));
        assertEquals("resume", state.focusedItemId());
        state = MenuReducer.reduce(state, MenuCommand.move(MenuCommand.Direction.UP));
        assertEquals("recent-games", state.focusedItemId());

        MenuPresentation presentation = state.presentation();
        assertTrue(presentation.visible());
        assertEquals(MenuRoute.PAUSE_CONSOLE, presentation.route());
        assertEquals("recent-games", presentation.items().get(presentation.focusedIndex()).id());
        assertUnmodifiable(presentation.items());
        assertNotSame(presentation, state.presentation());
    }

    @Test
    public void horizontalAndVerticalMovementWorkOnMultiColumnPages() {
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

        state = MenuReducer.move(state, MenuCommand.Direction.RIGHT);
        assertEquals("two", state.focusedItemId());
        state = MenuReducer.move(state, MenuCommand.Direction.DOWN);
        assertEquals("four", state.focusedItemId());
        state = MenuReducer.move(state, MenuCommand.Direction.LEFT);
        assertEquals("three", state.focusedItemId());
        state = MenuReducer.move(state, MenuCommand.Direction.UP);
        assertEquals("one", state.focusedItemId());
    }

    @Test
    public void pushAndBackPreserveParentFocusThenHideAtRoot() {
        MenuState state = MenuReducer.show(MenuReducer.initial(), MenuRoute.PAUSE_CONSOLE);
        state = MenuReducer.move(state, MenuCommand.Direction.DOWN);
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
