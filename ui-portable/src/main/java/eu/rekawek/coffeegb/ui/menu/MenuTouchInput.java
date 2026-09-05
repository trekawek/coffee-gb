package eu.rekawek.coffeegb.ui.menu;

import java.util.Collection;

/** Touch bridge used by the video surface while an in-screen menu is visible. */
public interface MenuTouchInput {

    boolean visible();

    /** Activates a row or navigation control after a pointer is released over its press target. */
    default boolean activateTarget(MenuPointerTarget target) {
        return false;
    }

    void updatePointer(int pointerId, Collection<MenuKey> keys);

    void releasePointer(int pointerId);

    void releaseAllPointers();
}
