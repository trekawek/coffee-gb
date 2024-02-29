package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.ButtonListener;
import eu.rekawek.coffeegb.controller.ButtonListener.Button;
import eu.rekawek.coffeegb.controller.Controller;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.Map;

public class SwingController implements Controller, KeyListener {

    private ButtonListener listener;

    private final Map<Integer, Button> mapping;

    public SwingController(Map<Integer, Button> mapping) {
        this.mapping = mapping;
    }

    @Override
    public void setButtonListener(ButtonListener listener) {
        this.listener = listener;
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (listener == null) {
            return;
        }
        Button b = getButton(e);
        if (b != null) {
            listener.onButtonPress(b);
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (listener == null) {
            return;
        }
        Button b = getButton(e);
        if (b != null) {
            listener.onButtonRelease(b);
        }
    }

    private Button getButton(KeyEvent e) {
        return mapping.get(e.getKeyCode());
    }
}
