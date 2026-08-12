package eu.rekawek.coffeegb.android.menu;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.MenuStackSnapshot;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuExternalSurfaceStateTest {

    private static final MenuStackSnapshot NESTED = new MenuStackSnapshot(List.of(
            new MenuStackSnapshot.Frame(MenuRoute.PAUSE_CONSOLE, "settings"),
            new MenuStackSnapshot.Frame(MenuRoute.SETTINGS, "data-media"),
            new MenuStackSnapshot.Frame(MenuRoute.DATA_MEDIA, "export-state-0")));

    @Test
    public void canceledOpenRomRestoresFullNestedStackAndPauseOwnership() {
        MenuExternalSurfaceState state = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.OPEN_ROM, 1, NESTED, true,
                MenuExternalSurfaceState.RestorePolicy.ON_CANCEL).afterResult(false);

        assertTrue(state.active());
        assertTrue(state.restoreRequested());
        assertTrue(state.pauseOwned());
        assertEquals(NESTED.frames(), state.menuStack().frames());
    }

    @Test
    public void successfulOpenRomDropsOldSessionWhileDataResultsAlwaysRestore() {
        MenuExternalSurfaceState open = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.OPEN_ROM, 1, NESTED, true,
                MenuExternalSurfaceState.RestorePolicy.ON_CANCEL).afterResult(true);
        assertFalse(open.active());

        MenuExternalSurfaceState data = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.EXPORT_STATE_0, 5, NESTED, true,
                MenuExternalSurfaceState.RestorePolicy.ALWAYS).afterResult(true);
        assertTrue(data.restoreRequested());
        assertEquals(MenuRoute.DATA_MEDIA, data.menuStack().route());
    }

    @Test
    public void recreationRetainsActionPolicyFocusAndRestoreRequest() {
        MenuExternalSurfaceState state = MenuExternalSurfaceState.restored(
                MenuExternalSurfaceState.Action.IMPORT_BATTERY, 2, NESTED, false,
                MenuExternalSurfaceState.RestorePolicy.ALWAYS, true);

        assertEquals(MenuExternalSurfaceState.RecreationState.RESTORED,
                state.recreationState());
        assertEquals("export-state-0", state.menuStack().frames().get(2).focusedItemId());
        assertTrue(state.restoreRequested());
    }

    @Test
    public void cameraDenialKeepsExactlyOneExternalOwnerUntilParentStackRestores() {
        MenuExternalSurfaceState camera = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.CAMERA_PERMISSION, 8, NESTED, true,
                MenuExternalSurfaceState.RestorePolicy.ALWAYS);

        assertTrue(camera.active());
        assertFalse(camera.restoreRequested());
        MenuExternalSurfaceState denied = camera.afterResult(false);
        assertTrue(denied.active());
        assertTrue(denied.restoreRequested());
        assertTrue(denied.pauseOwned());
        assertEquals(NESTED.frames(), denied.menuStack().frames());
    }
}
