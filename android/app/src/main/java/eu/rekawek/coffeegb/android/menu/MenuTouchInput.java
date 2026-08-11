package eu.rekawek.coffeegb.android.menu;

import java.util.Collection;

/** Touch bridge used by the video surface while an in-screen menu is visible. */
public interface MenuTouchInput {

    boolean visible();

    void updatePointer(int pointerId, Collection<MenuKey> keys);

    void releasePointer(int pointerId);

    void releaseAllPointers();
}
