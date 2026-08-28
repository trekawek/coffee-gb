package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DesktopAutofireInputTest {

    @Test
    public void heldButtonsPulseForTwoFramesOnAndTwoFramesOff() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        PlayerInputHub hub = new PlayerInputHub();
        DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        DesktopAutofireInput autofire = new DesktopAutofireInput(input, bus);
        Object source = new Object();

        autofire.update(source, 0, Set.of(Button.A));
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));

        postFrame(bus);
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));
        postFrame(bus);
        assertTrue(hub.sample().buttons(0).isEmpty());

        Object secondSource = new Object();
        autofire.update(secondSource, 1, Set.of(Button.B));
        assertEquals(Set.of(Button.B), hub.sample().buttons(1));
        postFrame(bus);
        assertTrue(hub.sample().buttons(0).isEmpty());
        assertEquals(Set.of(Button.B), hub.sample().buttons(1));
        postFrame(bus);
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));
        assertTrue(hub.sample().buttons(1).isEmpty());

        autofire.disconnect(source);
        assertTrue(hub.sample().buttons(0).isEmpty());
        autofire.disconnect(secondSource);
        bus.close();
    }

    @Test
    public void normalAndAutofireSourcesComposeAndLifecycleReleaseIsFinal() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        PlayerInputHub hub = new PlayerInputHub();
        DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        DesktopAutofireInput autofire = new DesktopAutofireInput(input, bus);
        Object normal = new Object();

        input.update(normal, 0, Set.of(Button.A));
        autofire.update(new Object(), 0, Set.of(Button.A, Button.B));
        postFrame(bus);
        postFrame(bus);
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));

        autofire.releaseAll();
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));
        input.disconnect(normal);
        assertTrue(hub.sample().buttons(0).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> autofire.update(new Object(), 0, Set.of(Button.START)));
        bus.close();
    }

    private static void postFrame(EventBusImpl bus) {
        bus.post(new Display.DmgFrameReadyEvent(
                new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT]));
    }
}
