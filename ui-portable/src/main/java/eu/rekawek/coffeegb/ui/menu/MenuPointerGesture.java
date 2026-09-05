package eu.rekawek.coffeegb.ui.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** UI-thread gesture state: a pointer activates only the target on which it was pressed. */
public final class MenuPointerGesture {

    private final Map<Integer, MenuPointerTarget> pressed = new HashMap<>();

    public void press(int pointerId, MenuPointerTarget target) {
        pressed.remove(pointerId);
        if (target != null) {
            pressed.put(pointerId, target);
        }
    }

    public boolean captured(int pointerId) {
        return pressed.containsKey(pointerId);
    }

    public Optional<MenuPointerTarget> release(int pointerId, MenuPointerTarget target) {
        MenuPointerTarget start = pressed.remove(pointerId);
        return start != null && start.equals(target) ? Optional.of(start) : Optional.empty();
    }

    public void cancel() {
        pressed.clear();
    }
}
