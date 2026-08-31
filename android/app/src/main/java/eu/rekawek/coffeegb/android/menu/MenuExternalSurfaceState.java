package eu.rekawek.coffeegb.android.menu;

import eu.rekawek.coffeegb.ui.menu.MenuStackSnapshot;

import java.util.Objects;

/**
 * Immutable handoff state while Android owns a picker, document creator, or permission surface.
 * It deliberately stores only menu routes/focus and opaque action metadata, never document URIs.
 */
public final class MenuExternalSurfaceState {

    public enum Action {
        OPEN_ROM,
        IMPORT_BATTERY,
        EXPORT_BATTERY,
        IMPORT_STATE_0,
        EXPORT_STATE_0,
        EXPORT_SCREENSHOT,
        EXPORT_PRINTER_SHARE,
        CAMERA_PERMISSION,
        GPS_PERMISSION
    }

    public enum RestorePolicy {
        NEVER,
        ON_CANCEL,
        ALWAYS
    }

    public enum RecreationState {
        LIVE,
        RESTORED
    }

    private static final MenuExternalSurfaceState NONE = new MenuExternalSurfaceState(
            null, -1, MenuStackSnapshot.hidden(), false, RestorePolicy.NEVER,
            RecreationState.LIVE, false);

    private final Action action;
    private final int requestCode;
    private final MenuStackSnapshot menuStack;
    private final boolean pauseOwned;
    private final RestorePolicy restorePolicy;
    private final RecreationState recreationState;
    private final boolean restoreRequested;

    private MenuExternalSurfaceState(Action action, int requestCode, MenuStackSnapshot menuStack,
            boolean pauseOwned, RestorePolicy restorePolicy, RecreationState recreationState,
            boolean restoreRequested) {
        this.action = action;
        this.requestCode = requestCode;
        this.menuStack = Objects.requireNonNull(menuStack, "menuStack");
        this.pauseOwned = pauseOwned;
        this.restorePolicy = Objects.requireNonNull(restorePolicy, "restorePolicy");
        this.recreationState = Objects.requireNonNull(recreationState, "recreationState");
        this.restoreRequested = restoreRequested;
    }

    public static MenuExternalSurfaceState none() {
        return NONE;
    }

    public static MenuExternalSurfaceState launched(Action action, int requestCode,
            MenuStackSnapshot menuStack, boolean pauseOwned, RestorePolicy restorePolicy) {
        if (requestCode < 0) {
            throw new IllegalArgumentException("requestCode must not be negative");
        }
        return new MenuExternalSurfaceState(Objects.requireNonNull(action, "action"), requestCode,
                Objects.requireNonNull(menuStack, "menuStack"), pauseOwned,
                Objects.requireNonNull(restorePolicy, "restorePolicy"), RecreationState.LIVE,
                false);
    }

    public static MenuExternalSurfaceState restored(Action action, int requestCode,
            MenuStackSnapshot menuStack, boolean pauseOwned, RestorePolicy restorePolicy,
            boolean restoreRequested) {
        if (requestCode < 0) {
            throw new IllegalArgumentException("requestCode must not be negative");
        }
        return new MenuExternalSurfaceState(Objects.requireNonNull(action, "action"), requestCode,
                Objects.requireNonNull(menuStack, "menuStack"), pauseOwned,
                Objects.requireNonNull(restorePolicy, "restorePolicy"), RecreationState.RESTORED,
                restoreRequested);
    }

    public MenuExternalSurfaceState afterResult(boolean successful) {
        if (!active()) {
            return this;
        }
        boolean restore = restorePolicy == RestorePolicy.ALWAYS
                || (restorePolicy == RestorePolicy.ON_CANCEL && !successful);
        if (!restore) {
            return NONE;
        }
        return new MenuExternalSurfaceState(action, requestCode, menuStack, pauseOwned,
                restorePolicy, recreationState, true);
    }

    public boolean active() {
        return action != null;
    }

    public Action action() {
        return action;
    }

    public int requestCode() {
        return requestCode;
    }

    public MenuStackSnapshot menuStack() {
        return menuStack;
    }

    public boolean pauseOwned() {
        return pauseOwned;
    }

    public RestorePolicy restorePolicy() {
        return restorePolicy;
    }

    public RecreationState recreationState() {
        return recreationState;
    }

    public boolean restoreRequested() {
        return restoreRequested;
    }
}
