package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;
import org.junit.Test;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DesktopTiltInputTest {

    @Test
    public void keyTiltFocusLossRecentersStateExactlyOnce() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        DesktopTiltInput tilt = new DesktopTiltInput(bus);
        SwingTiltKeys keys = new SwingTiltKeys(tilt);
        List<AccelerometerEvent> events = new ArrayList<>();
        bus.register(events::add, AccelerometerEvent.class);

        keys.keyPressed(key(KeyEvent.VK_I, KeyEvent.KEY_PRESSED));
        tilt.windowLostFocus(null);
        tilt.windowLostFocus(null);
        assertEquals(List.of(
                new AccelerometerEvent(0, -0.15),
                new AccelerometerEvent(0, 0)), events);
        assertEquals(0, tilt.currentX(), 0);
        assertEquals(0, tilt.currentY(), 0);

        tilt.windowGainedFocus(null);
        keys.keyPressed(key(KeyEvent.VK_I, KeyEvent.KEY_PRESSED));
        assertEquals(new AccelerometerEvent(0, -0.15), events.get(2));
    }

    @Test
    public void mouseLastWriterLifecycleAndStopRecenterAndCannotRelatch() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        DesktopTiltInput tilt = new DesktopTiltInput(bus);
        SwingTiltKeys keys = new SwingTiltKeys(tilt);
        SwingAccelerometer mouse = new SwingAccelerometer(bus, tilt, new Dimension(100, 100));
        List<AccelerometerEvent> events = new ArrayList<>();
        bus.register(events::add, AccelerometerEvent.class);

        keys.keyPressed(key(KeyEvent.VK_L, KeyEvent.KEY_PRESSED));
        mouse.mouseMoved(mouse(100, 0));
        tilt.releaseForLifecycleChange();
        tilt.releaseForLifecycleChange();
        assertEquals(List.of(
                new AccelerometerEvent(0.15, 0),
                new AccelerometerEvent(1, -1),
                new AccelerometerEvent(0, 0)), events);

        mouse.mouseMoved(mouse(0, 100));
        tilt.stop();
        tilt.stop();
        keys.keyPressed(key(KeyEvent.VK_L, KeyEvent.KEY_PRESSED));
        mouse.mouseMoved(mouse(100, 100));
        assertEquals(new AccelerometerEvent(0, 0), events.get(events.size() - 1));
        assertEquals(5, events.size());
    }

    private static KeyEvent key(int code, int id) {
        return new KeyEvent(new Canvas(), id, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static MouseEvent mouse(int x, int y) {
        return new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false);
    }
}
