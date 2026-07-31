package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.ControllerProperties;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import org.junit.Test;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SwingJoypadTest {

    @Test
    public void fourPlayerKeysAreIdempotentAndFocusLossReleasesInputAndRewind() {
        Properties properties = new Properties();
        properties.setProperty("input.p2.btn_a", "VK_W");
        ControllerProperties.PlayerMapping mapping =
                ControllerProperties.INSTANCE.getPlayerMapping(properties);
        EventBusImpl bus = new EventBusImpl(null, null, false);
        PlayerInputHub hub = new PlayerInputHub();
        DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        SwingJoypad joypad = new SwingJoypad(mapping, bus, input);
        List<Boolean> rewinds = new ArrayList<>();
        bus.register(event -> rewinds.add(event.getActive()), Controller.RewindEvent.class);

        joypad.keyPressed(key(KeyEvent.VK_Z, KeyEvent.KEY_PRESSED));
        joypad.keyPressed(key(KeyEvent.VK_Z, KeyEvent.KEY_PRESSED));
        joypad.keyPressed(key(KeyEvent.VK_W, KeyEvent.KEY_PRESSED));
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));
        assertEquals(Set.of(Button.A), hub.sample().buttons(1));
        joypad.keyReleased(key(KeyEvent.VK_Z, KeyEvent.KEY_RELEASED));
        assertTrue(hub.sample().buttons(0).isEmpty());
        assertEquals(Set.of(Button.A), hub.sample().buttons(1));

        joypad.keyPressed(key(KeyEvent.VK_BACK_SPACE, KeyEvent.KEY_PRESSED));
        joypad.keyPressed(key(KeyEvent.VK_BACK_SPACE, KeyEvent.KEY_PRESSED));
        joypad.windowLostFocus(null);
        assertTrue(hub.sample().players().stream().allMatch(Set::isEmpty));
        assertEquals(List.of(true, false), rewinds);
        joypad.stop();
        assertEquals(List.of(true, false), rewinds);

        joypad.windowGainedFocus(null);
        joypad.keyPressed(key(KeyEvent.VK_W, KeyEvent.KEY_PRESSED));
        joypad.keyPressed(key(KeyEvent.VK_BACK_SPACE, KeyEvent.KEY_PRESSED));
        joypad.releaseForLifecycleChange();
        assertTrue(hub.sample().players().stream().allMatch(Set::isEmpty));
        assertEquals(List.of(true, false, true, false), rewinds);
    }

    @Test
    public void replacingMappingReleasesOldBindingsBeforeNewBindingsBecomeLive() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        PlayerInputHub hub = new PlayerInputHub();
        DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        SwingJoypad joypad =
                new SwingJoypad(
                        ControllerProperties.INSTANCE.getPlayerMapping(new Properties()),
                        bus,
                        input);
        List<Boolean> rewinds = new ArrayList<>();
        bus.register(event -> rewinds.add(event.getActive()), Controller.RewindEvent.class);
        Object gamepadSource = new Object();

        joypad.keyPressed(key(KeyEvent.VK_Z, KeyEvent.KEY_PRESSED));
        joypad.keyPressed(key(KeyEvent.VK_BACK_SPACE, KeyEvent.KEY_PRESSED));
        input.update(gamepadSource, 1, Set.of(Button.B));
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));
        assertEquals(Set.of(Button.B), hub.sample().buttons(1));

        Properties replacement = new Properties();
        replacement.setProperty("input.p1.btn_a", "VK_C");
        joypad.updateMapping(ControllerProperties.INSTANCE.getPlayerMapping(replacement));
        assertTrue(hub.sample().players().stream().allMatch(Set::isEmpty));
        assertEquals(List.of(true, false), rewinds);

        joypad.keyPressed(key(KeyEvent.VK_Z, KeyEvent.KEY_PRESSED));
        assertTrue(hub.sample().players().stream().allMatch(Set::isEmpty));
        joypad.keyPressed(key(KeyEvent.VK_C, KeyEvent.KEY_PRESSED));
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));

        input.update(gamepadSource, 1, Set.of(Button.B));
        assertEquals(Set.of(Button.B), hub.sample().buttons(1));
    }

    @Test
    public void explicitBackspaceMappingWinsOverFallbackRewind() {
        Properties properties = new Properties();
        properties.setProperty("input.p1.btn_a", "VK_BACK_SPACE");
        EventBusImpl bus = new EventBusImpl(null, null, false);
        PlayerInputHub hub = new PlayerInputHub();
        DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        SwingJoypad joypad = new SwingJoypad(
                ControllerProperties.INSTANCE.getPlayerMapping(properties), bus, input);
        List<Boolean> rewinds = new ArrayList<>();
        bus.register(event -> rewinds.add(event.getActive()), Controller.RewindEvent.class);

        assertTrue(joypad.handlesKeyCode(KeyEvent.VK_BACK_SPACE));
        joypad.keyPressed(key(KeyEvent.VK_BACK_SPACE, KeyEvent.KEY_PRESSED));
        assertEquals(Set.of(Button.A), hub.sample().buttons(0));
        assertTrue(rewinds.isEmpty());
        joypad.keyReleased(key(KeyEvent.VK_BACK_SPACE, KeyEvent.KEY_RELEASED));
        assertTrue(hub.sample().buttons(0).isEmpty());
        assertTrue(rewinds.isEmpty());
        assertFalse(joypad.handlesKeyCode(KeyEvent.VK_F12));
    }

    private static KeyEvent key(int code, int id) {
        return new KeyEvent(new Canvas(), id, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
    }
}
