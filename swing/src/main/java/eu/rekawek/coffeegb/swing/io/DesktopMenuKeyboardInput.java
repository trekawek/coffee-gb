package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.ui.menu.MenuKey;

/** Keyboard-side capture boundary for the portable in-screen menu. */
public interface DesktopMenuKeyboardInput {

    boolean visible();

    boolean onKeyDown(MenuKey key, boolean repeat);

    boolean onKeyUp(MenuKey key);
}
