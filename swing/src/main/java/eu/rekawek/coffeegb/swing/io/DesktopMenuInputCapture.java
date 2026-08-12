package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.Collection;

/**
 * Optional host capture for the portable menu's player-one controller input.
 *
 * <p>The desktop input hub calls this with the union of every physical player-one source. A true
 * result means that the current sample belongs to the menu and must not reach gameplay.</p>
 */
public interface DesktopMenuInputCapture {

    boolean updatePlayerButtons(Collection<Button> buttons);

    boolean visible();
}
