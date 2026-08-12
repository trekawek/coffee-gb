package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.ControllerProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.swing.DesktopKeyboardKeyAdapter;
import eu.rekawek.coffeegb.ui.menu.MenuKey;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.EnumSet;
import java.util.Map;

/** Keyboard adapter for the four platform-neutral logical input slots. */
public class SwingJoypad implements KeyListener, WindowFocusListener {

    private static final int REWIND_KEY = KeyEvent.VK_BACK_SPACE;

    private Map<Integer, ControllerProperties.PlayerButton> mapping;
    private final EventBus eventBus;
    private final DesktopPlayerInput input;
    private final Object[] sourceIdentities = new Object[4];
    private final EnumSet<Button>[] pressed;

    private boolean rewindActive;

    @SuppressWarnings("unchecked")
    public SwingJoypad(ControllerProperties.PlayerMapping mapping, EventBus eventBus,
                       DesktopPlayerInput input) {
        this.mapping = Map.copyOf(DesktopKeyboardKeyAdapter.resolveMapping(mapping.getKeyboard()));
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
        ControllerProperties.PlayerButton binding = mapping.get(e.getKeyCode());
        if (binding != null) {
            if (pressed[binding.getPlayer()].add(binding.getButton())) {
                update(binding.getPlayer());
            }
            return;
        }
        if (e.getKeyCode() == REWIND_KEY) {
            if (!rewindActive) {
                rewindActive = true;
                eventBus.post(new Controller.RewindEvent(true));
            }
            return;
        }
    }

    @Override
    public synchronized void keyReleased(KeyEvent e) {
        ControllerProperties.PlayerButton binding = mapping.get(e.getKeyCode());
        if (binding != null && pressed[binding.getPlayer()].remove(binding.getButton())) {
            update(binding.getPlayer());
            return;
        }
        if (e.getKeyCode() == REWIND_KEY) {
            releaseRewind();
        }
    }

    /** True when an unmodified key belongs to a configured button or the fallback rewind key. */
    public synchronized boolean handlesKeyCode(int keyCode) {
        return mapping.containsKey(keyCode) || keyCode == REWIND_KEY;
    }

    /** Returns the configured player-one logical key for portable-menu capture, if any. */
    public synchronized MenuKey menuKeyForKeyCode(int keyCode) {
        ControllerProperties.PlayerButton binding = mapping.get(keyCode);
        if (binding == null || binding.getPlayer() != 0) {
            return null;
        }
        return switch (binding.getButton()) {
            case RIGHT -> MenuKey.RIGHT;
            case LEFT -> MenuKey.LEFT;
            case UP -> MenuKey.UP;
            case DOWN -> MenuKey.DOWN;
            case A -> MenuKey.A;
            case B -> MenuKey.B;
            case SELECT -> MenuKey.SELECT;
            case START -> MenuKey.START;
        };
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

    /**
     * Replaces keyboard bindings without allowing held keys from the old map to become stuck.
     *
     * <p>The Preferences dialog calls this only after its complete draft has validated and been
     * persisted. Releasing before swapping also means a physically held key must be released and
     * pressed again before the new binding becomes active.
     */
    public synchronized void updateMapping(ControllerProperties.PlayerMapping mapping) {
        releaseKeyboard();
        releaseRewind();
        input.releaseAll();
        this.mapping = Map.copyOf(DesktopKeyboardKeyAdapter.resolveMapping(mapping.getKeyboard()));
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
