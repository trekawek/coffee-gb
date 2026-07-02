package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.events.EventBus;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.Map;

public class SwingJoypad implements KeyListener {

    private static final int REWIND_KEY = KeyEvent.VK_BACK_SPACE;

    private final Map<Integer, Button> mapping;

    private final EventBus eventBus;

    private boolean rewindActive;

    public SwingJoypad(Map<Integer, Button> mapping, EventBus eventBus) {
        this.mapping = mapping;
        this.eventBus = eventBus;
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == REWIND_KEY) {
            // holding the key auto-repeats; post only on the state change
            if (!rewindActive) {
                rewindActive = true;
                eventBus.post(new Controller.RewindEvent(true));
            }
            return;
        }
        Button b = getButton(e);
        if (b != null) {
            eventBus.post(new ButtonPressEvent(b));
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == REWIND_KEY) {
            rewindActive = false;
            eventBus.post(new Controller.RewindEvent(false));
            return;
        }
        Button b = getButton(e);
        if (b != null) {
            eventBus.post(new ButtonReleaseEvent(b));
        }
    }

    private Button getButton(KeyEvent e) {
        return mapping.get(e.getKeyCode());
    }
}
