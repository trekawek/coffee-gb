package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.ControllerProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.joypad.Button;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.EnumSet;
import java.util.Map;

/** Keyboard adapter for the four platform-neutral logical input slots. */
public class SwingJoypad implements KeyListener, WindowFocusListener {

    private static final int REWIND_KEY = KeyEvent.VK_BACK_SPACE;

    private final Map<Integer, ControllerProperties.PlayerButton> mapping;
    private final EventBus eventBus;
    private final DesktopPlayerInput input;
    private final Object[] sourceIdentities = new Object[4];
    private final EnumSet<Button>[] pressed;

    private boolean rewindActive;

    @SuppressWarnings("unchecked")
    public SwingJoypad(ControllerProperties.PlayerMapping mapping, EventBus eventBus,
                       DesktopPlayerInput input) {
        this.mapping = mapping.getKeyboard();
        this.eventBus = eventBus;
        this.input = input;
        this.pressed = new EnumSet[4];
        for (int player = 0; player < 4; player++) {
            sourceIdentities[player] = new Object();
            pressed[player] = EnumSet.noneOf(Button.class);
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public synchronized void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == REWIND_KEY) {
            if (!rewindActive) {
                rewindActive = true;
                eventBus.post(new Controller.RewindEvent(true));
            }
            return;
        }
        ControllerProperties.PlayerButton binding = mapping.get(e.getKeyCode());
        if (binding != null && pressed[binding.getPlayer()].add(binding.getButton())) {
            update(binding.getPlayer());
        }
    }

    @Override
    public synchronized void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == REWIND_KEY) {
            releaseRewind();
            return;
        }
        ControllerProperties.PlayerButton binding = mapping.get(e.getKeyCode());
        if (binding != null && pressed[binding.getPlayer()].remove(binding.getButton())) {
            update(binding.getPlayer());
        }
    }

    @Override
    public synchronized void windowGainedFocus(WindowEvent e) {
        input.setFocused(true);
    }

    @Override
    public synchronized void windowLostFocus(WindowEvent e) {
        releaseKeyboard();
        releaseRewind();
        input.setFocused(false);
    }

    public synchronized void stop() {
        releaseKeyboard();
        releaseRewind();
        input.setFocused(false);
    }

    /** Releases transient keyboard/rewind latches at ROM and controller replacement. */
    public synchronized void releaseForLifecycleChange() {
        releaseKeyboard();
        releaseRewind();
        input.releaseAll();
    }

    private void update(int player) {
        input.update(sourceIdentities[player], player, pressed[player]);
    }

    private void releaseKeyboard() {
        for (int player = 0; player < pressed.length; player++) {
            pressed[player].clear();
            input.disconnect(sourceIdentities[player]);
        }
    }

    private void releaseRewind() {
        if (rewindActive) {
            rewindActive = false;
            eventBus.post(new Controller.RewindEvent(false));
        }
    }
}
