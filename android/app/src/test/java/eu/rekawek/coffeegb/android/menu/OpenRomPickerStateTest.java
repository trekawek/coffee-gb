package eu.rekawek.coffeegb.android.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenRomPickerStateTest {

    @Test
    public void cancelRequestsTheExactMenuRouteWithPauseOwnership() {
        OpenRomPickerState state = OpenRomPickerState
                .launched(MenuRoute.PAUSE_CONSOLE, true)
                .canceled();

        assertTrue(state.active());
        assertTrue(state.restoreRequested());
        assertTrue(state.pauseOwned());
        assertEquals(MenuRoute.PAUSE_CONSOLE, state.route());
    }

    @Test
    public void successfulSelectionDiscardsTheOldMenuSession() {
        OpenRomPickerState state = OpenRomPickerState
                .launched(MenuRoute.LIBRARY, false)
                .completed();

        assertFalse(state.active());
        assertFalse(state.restoreRequested());
        assertFalse(state.pauseOwned());
    }

    @Test
    public void recreatedCanceledPickerStillRequestsRestore() {
        OpenRomPickerState state = OpenRomPickerState.restored(
                MenuRoute.PAUSE_CONSOLE, true, true);

        assertTrue(state.restoreRequested());
        assertEquals(MenuRoute.PAUSE_CONSOLE, state.route());
    }
}
