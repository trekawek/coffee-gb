package eu.rekawek.coffeegb.android.menu;

import java.util.Objects;

/** Immutable origin state for a native ROM picker launched from the in-screen menu. */
public final class OpenRomPickerState {

    private static final OpenRomPickerState NONE =
            new OpenRomPickerState(null, false, false);

    private final MenuRoute route;
    private final boolean pauseOwned;
    private final boolean restoreRequested;

    private OpenRomPickerState(MenuRoute route, boolean pauseOwned, boolean restoreRequested) {
        this.route = route;
        this.pauseOwned = pauseOwned;
        this.restoreRequested = restoreRequested;
    }

    public static OpenRomPickerState none() {
        return NONE;
    }

    public static OpenRomPickerState launched(MenuRoute route, boolean pauseOwned) {
        return new OpenRomPickerState(Objects.requireNonNull(route, "route"), pauseOwned, false);
    }

    public static OpenRomPickerState restored(MenuRoute route, boolean pauseOwned,
            boolean restoreRequested) {
        return new OpenRomPickerState(
                Objects.requireNonNull(route, "route"), pauseOwned, restoreRequested);
    }

    public OpenRomPickerState canceled() {
        return active() ? new OpenRomPickerState(route, pauseOwned, true) : this;
    }

    public OpenRomPickerState completed() {
        return NONE;
    }

    public boolean active() {
        return route != null;
    }

    public MenuRoute route() {
        return route;
    }

    public boolean pauseOwned() {
        return pauseOwned;
    }

    public boolean restoreRequested() {
        return restoreRequested;
    }
}
