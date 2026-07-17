package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SwingGamepadTest {

    @Test
    public void rightStickUsesDeadZoneAndFullAnalogRange() {
        assertEquals(0, SwingGamepad.normalizeTiltAxis(0), 0);
        assertEquals(0, SwingGamepad.normalizeTiltAxis(SwingGamepad.TILT_DEAD_ZONE), 0);
        assertEquals(0, SwingGamepad.normalizeTiltAxis(-SwingGamepad.TILT_DEAD_ZONE), 0);
        assertEquals(1, SwingGamepad.normalizeTiltAxis(32767), 0);
        assertEquals(-1, SwingGamepad.normalizeTiltAxis(-32768), 0);
        assertEquals(0.5, SwingGamepad.normalizeTiltAxis((32767 + SwingGamepad.TILT_DEAD_ZONE) / 2), 0.0001);
    }

    @Test
    public void idleStickDoesNotOverrideMouseAndRecentersOnce() {
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<AccelerometerEvent> events = new ArrayList<>();
        eventBus.register(events::add, AccelerometerEvent.class);
        SwingGamepad gamepad = new SwingGamepad(eventBus);

        gamepad.updateTilt(0, 0);
        gamepad.updateTilt(32767, -32768);
        gamepad.updateTilt(32767, -32768);
        gamepad.updateTilt(0, 0);
        gamepad.updateTilt(0, 0);

        assertEquals(List.of(
                new AccelerometerEvent(1, -1),
                new AccelerometerEvent(1, -1),
                new AccelerometerEvent(0, 0)
        ), events);
    }
}
