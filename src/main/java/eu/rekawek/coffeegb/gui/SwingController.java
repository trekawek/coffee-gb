package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.controller.ButtonListener;
import eu.rekawek.coffeegb.controller.ButtonListener.Button;
import eu.rekawek.coffeegb.controller.Controller;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class SwingController implements Controller, KeyListener {

    private ButtonListener listener;

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
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT:
                return Button.LEFT;

            case KeyEvent.VK_RIGHT:
                return Button.RIGHT;

            case KeyEvent.VK_UP:
                return Button.UP;

            case KeyEvent.VK_DOWN:
                return Button.DOWN;

            case KeyEvent.VK_Z:
                return Button.A;

            case KeyEvent.VK_X:
                return Button.B;

            case KeyEvent.VK_ENTER:
                return Button.START;

            case KeyEvent.VK_BACK_SPACE:
                return Button.SELECT;
        }
        return null;
    }
}
