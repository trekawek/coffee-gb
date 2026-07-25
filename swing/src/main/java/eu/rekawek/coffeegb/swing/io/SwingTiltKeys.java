package eu.rekawek.coffeegb.swing.io;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

/**
 * Keyboard control for the MBC7 accelerometer (Kirby Tilt 'n' Tumble): holding I/J/K/L
 * tilts the cartridge up/left/down/right, ramping while held (the OS key auto-repeat
 * drives the ramp) and recentering on release. The mouse position over the display
 * remains an alternative input.
 */
public class SwingTiltKeys implements KeyListener {

    private static final double STEP = 0.15;

    private static final double MAX = 0.9;

    private final DesktopTiltInput tiltInput;
    private final Object sourceIdentity = new Object();

    private double x, y;

    public SwingTiltKeys(DesktopTiltInput tiltInput) {
        this.tiltInput = tiltInput;
        tiltInput.registerResetter(this::resetState);
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public synchronized void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_I -> y = Math.max(-MAX, y - STEP);
            case KeyEvent.VK_K -> y = Math.min(MAX, y + STEP);
            case KeyEvent.VK_J -> x = Math.max(-MAX, x - STEP);
            case KeyEvent.VK_L -> x = Math.min(MAX, x + STEP);
            default -> {
                return;
            }
        }
        tiltInput.update(sourceIdentity, x, y);
    }

    @Override
    public synchronized void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_I, KeyEvent.VK_K -> y = 0;
            case KeyEvent.VK_J, KeyEvent.VK_L -> x = 0;
            default -> {
                return;
            }
        }
        tiltInput.update(sourceIdentity, x, y);
    }

    private synchronized void resetState() {
        x = 0;
        y = 0;
    }
}
