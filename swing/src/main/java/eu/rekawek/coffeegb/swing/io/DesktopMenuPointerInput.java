package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.ui.menu.MenuPointerTarget;

import java.util.Optional;

/** Semantic pointer bridge for the portable menu's canonical source coordinates. */
public interface DesktopMenuPointerInput {

    Optional<MenuPointerTarget> targetAt(int sourceX, int sourceY);

    boolean activateTarget(MenuPointerTarget target);
}
