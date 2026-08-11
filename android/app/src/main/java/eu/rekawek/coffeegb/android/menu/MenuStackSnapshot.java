package eu.rekawek.coffeegb.android.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable route stack and per-route focus used across native Android surfaces. */
public final class MenuStackSnapshot {

    private static final MenuStackSnapshot HIDDEN = new MenuStackSnapshot(List.of());

    private final List<Frame> frames;

    public MenuStackSnapshot(List<Frame> frames) {
        Objects.requireNonNull(frames, "frames");
        ArrayList<Frame> copy = new ArrayList<>(frames.size());
        for (Frame frame : frames) {
            copy.add(Objects.requireNonNull(frame, "frames cannot contain null"));
        }
        this.frames = Collections.unmodifiableList(copy);
    }

    public static MenuStackSnapshot hidden() {
        return HIDDEN;
    }

    public List<Frame> frames() {
        return frames;
    }

    public boolean visible() {
        return !frames.isEmpty();
    }

    public MenuRoute route() {
        return visible() ? frames.get(frames.size() - 1).route() : null;
    }

    public record Frame(MenuRoute route, String focusedItemId) {
        public Frame {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(focusedItemId, "focusedItemId");
        }
    }
}
