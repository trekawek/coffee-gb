package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.ControllerProperties;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SwingGamepadTest {

    private static final String ID_A = "sdl-" + "a".repeat(64);
    private static final String ID_B = "sdl-" + "b".repeat(64);
    private static final String ID_C = "sdl-" + "c".repeat(64);

    @Test
    public void rightStickUsesDeadZoneAndFullAnalogRange() {
        assertEquals(0, SwingGamepad.normalizeTiltAxis(0), 0);
        assertEquals(0, SwingGamepad.normalizeTiltAxis(SwingGamepad.TILT_DEAD_ZONE), 0);
        assertEquals(0, SwingGamepad.normalizeTiltAxis(-SwingGamepad.TILT_DEAD_ZONE), 0);
        assertEquals(1, SwingGamepad.normalizeTiltAxis(32767), 0);
        assertEquals(-1, SwingGamepad.normalizeTiltAxis(-32768), 0);
        assertEquals(0.5,
                SwingGamepad.normalizeTiltAxis((32767 + SwingGamepad.TILT_DEAD_ZONE) / 2),
                0.0001);
    }

    @Test
    public void idleStickDoesNotOverrideMouseAndRecentersOnce() {
        Rig rig = new Rig(mapping());
        List<AccelerometerEvent> events = new ArrayList<>();
        rig.bus.register(events::add, AccelerometerEvent.class);

        rig.gamepad.updateTilt(0, 0);
        rig.gamepad.updateTilt(32767, -32768);
        rig.gamepad.updateTilt(32767, -32768);
        rig.gamepad.updateTilt(0, 0);
        rig.gamepad.updateTilt(0, 0);

        assertEquals(List.of(
                new AccelerometerEvent(1, -1),
                new AccelerometerEvent(1, -1),
                new AccelerometerEvent(0, 0)
        ), events);
    }

    @Test
    public void stableExplicitAssignmentsSurviveEnumerationChurnAndReleaseOnlyDisconnectedSource() {
        Rig rig = new Rig(mapping(
                new ControllerProperties.GamepadAssignment(0, ID_A),
                new ControllerProperties.GamepadAssignment(1, ID_B)));
        FakeDevice first = rig.backend.add(ID_A, "first");
        FakeDevice second = rig.backend.add(ID_B, "second");
        first.buttons.add(GamepadBackend.PadButton.A);
        second.buttons.add(GamepadBackend.PadButton.B);

        rig.gamepad.pollOnce();
        assertEquals(Set.of(Button.A), rig.hub.sample().buttons(0));
        assertEquals(Set.of(Button.B), rig.hub.sample().buttons(1));
        rig.backend.reverseEnumeration();
        rig.gamepad.pollOnce();
        assertEquals(Set.of(Button.A), rig.hub.sample().buttons(0));
        assertEquals(Set.of(Button.B), rig.hub.sample().buttons(1));
        assertEquals(1, first.openCount);
        assertEquals(1, second.openCount);

        first.attached = false;
        rig.gamepad.pollOnce();
        assertTrue(rig.hub.sample().buttons(0).isEmpty());
        assertEquals(Set.of(Button.B), rig.hub.sample().buttons(1));

        rig.backend.remove(ID_A);
        FakeDevice replacement = rig.backend.add(ID_C, "replacement-at-array-zero");
        replacement.buttons.add(GamepadBackend.PadButton.START);
        rig.gamepad.pollOnce();
        assertTrue(rig.hub.sample().buttons(0).isEmpty());
        assertEquals(0, replacement.openCount);

        rig.backend.remove(ID_C);
        FakeDevice reconnected = rig.backend.add(ID_A, "replacement-with-assigned-identity");
        reconnected.buttons.add(GamepadBackend.PadButton.START);
        rig.gamepad.pollOnce();
        assertEquals(Set.of(Button.START), rig.hub.sample().buttons(0));
        assertEquals(1, reconnected.openCount);
    }

    @Test
    public void automaticAssignmentStaysWithIdentityAcrossArrayOrderChanges() {
        Rig rig = new Rig(mapping(new ControllerProperties.GamepadAssignment(0, "auto")));
        FakeDevice first = rig.backend.add(ID_A, "first");
        FakeDevice second = rig.backend.add(ID_B, "second");
        first.buttons.add(GamepadBackend.PadButton.A);
        second.buttons.add(GamepadBackend.PadButton.B);
        rig.gamepad.pollOnce();
        assertEquals(Set.of(Button.A), rig.hub.sample().buttons(0));

        rig.backend.reverseEnumeration();
        rig.gamepad.pollOnce();
        assertEquals(Set.of(Button.A), rig.hub.sample().buttons(0));
        assertEquals(1, first.openCount);
        assertEquals(0, second.openCount);
    }

    @Test
    public void everyControllerIsDiscoveredOncePerConnectionEvenWhenUnassigned() {
        List<GamepadBackend.DeviceInfo> discovered = new ArrayList<>();
        Rig rig = new Rig(
                mapping(new ControllerProperties.GamepadAssignment(0, "auto")), discovered);
        rig.backend.add(ID_A, "assigned");
        rig.backend.add(ID_B, "unassigned-two");
        rig.backend.add(ID_C, "unassigned-three");

        rig.gamepad.pollOnce();
        assertEquals(List.of(ID_A, ID_B, ID_C),
                discovered.stream().map(GamepadBackend.DeviceInfo::stableId).toList());
        rig.backend.reverseEnumeration();
        rig.gamepad.pollOnce();
        assertEquals(3, discovered.size());

        rig.backend.remove(ID_B);
        rig.gamepad.pollOnce();
        rig.backend.add(ID_B, "reconnected-two");
        rig.gamepad.pollOnce();
        assertEquals(List.of(ID_A, ID_B, ID_C, ID_B),
                discovered.stream().map(GamepadBackend.DeviceInfo::stableId).toList());
        assertEquals("reconnected-two", discovered.get(3).name());
    }

    @Test
    public void focusAndStopReleaseAllButtonsWhileTiltAndRumbleStayP1Only() {
        Rig rig = new Rig(mapping(
                new ControllerProperties.GamepadAssignment(0, ID_A),
                new ControllerProperties.GamepadAssignment(2, ID_B)));
        FakeDevice primary = rig.backend.add(ID_A, "primary");
        FakeDevice third = rig.backend.add(ID_B, "third");
        primary.buttons.add(GamepadBackend.PadButton.A);
        primary.axes.put(GamepadBackend.Axis.RIGHT_X, 32767);
        third.buttons.add(GamepadBackend.PadButton.B);
        third.axes.put(GamepadBackend.Axis.RIGHT_Y, -32768);
        List<AccelerometerEvent> tilt = new ArrayList<>();
        rig.bus.register(tilt::add, AccelerometerEvent.class);
        rig.bus.post(new RumbleEvent(true));

        rig.gamepad.pollOnce();
        assertEquals(Set.of(Button.A), rig.hub.sample().buttons(0));
        assertEquals(Set.of(Button.B), rig.hub.sample().buttons(2));
        assertTrue(primary.rumbling);
        assertFalse(third.rumbling);
        assertEquals(new AccelerometerEvent(1, 0), tilt.get(0));

        rig.input.setFocused(false);
        rig.tiltInput.windowLostFocus(null);
        rig.gamepad.pollOnce();
        assertTrue(rig.hub.sample().players().stream().allMatch(Set::isEmpty));
        assertFalse(primary.rumbling);
        assertEquals(new AccelerometerEvent(0, 0), tilt.get(1));
        rig.gamepad.stop();
        assertTrue(rig.hub.sample().players().stream().allMatch(Set::isEmpty));
    }

    @Test
    public void sdlIdentityIsCanonicalAndSensitiveToStableDescriptorFields() {
        String one = SdlGamepadBackend.stableId("guid", "/dev/input/a", "Pad");
        assertEquals("sdl-eccc3d8673d9b710c9274225ee4b44baba12f760dcd506549d655e96356d583a",
                one);
        assertTrue(one.matches("sdl-[0-9a-f]{64}"));
        assertEquals(one, SdlGamepadBackend.stableId("guid", "/dev/input/a", "Pad"));
        assertFalse(one.equals(SdlGamepadBackend.stableId("guid", "/dev/input/b", "Pad")));
        assertFalse(SdlGamepadBackend.stableId("guid", "", "Pad", 4)
                .equals(SdlGamepadBackend.stableId("guid", "", "Pad", 5)));
        assertEquals(SdlGamepadBackend.stableId("guid", "/dev/input/a", "Pad", 4),
                SdlGamepadBackend.stableId("guid", "/dev/input/a", "Pad", 5));
    }

    @Test
    public void pollerThreadExitReleasesSourcesAndClosesBackend() throws Exception {
        Rig rig = new Rig(mapping(new ControllerProperties.GamepadAssignment(0, ID_A)));
        FakeDevice device = rig.backend.add(ID_A, "primary");
        device.buttons.add(GamepadBackend.PadButton.A);
        device.axes.put(GamepadBackend.Axis.RIGHT_X, 32767);
        List<AccelerometerEvent> tilt = new ArrayList<>();
        rig.bus.register(tilt::add, AccelerometerEvent.class);
        rig.bus.post(new RumbleEvent(true));
        rig.gamepad.pollOnce();
        assertEquals(Set.of(Button.A), rig.hub.sample().buttons(0));
        assertTrue(device.rumbling);
        assertEquals(new AccelerometerEvent(1, 0), tilt.get(0));

        Thread thread = new Thread(rig.gamepad, "fake-gamepad-test");
        thread.start();
        rig.gamepad.stop();
        thread.interrupt();
        thread.join(2_000);

        assertFalse(thread.isAlive());
        assertTrue(rig.hub.sample().players().stream().allMatch(Set::isEmpty));
        assertTrue(rig.backend.closed);
        assertTrue(device.closeCount > 0);
        assertFalse(device.rumbling);
        assertEquals(new AccelerometerEvent(0, 0), tilt.get(tilt.size() - 1));
    }

    private static ControllerProperties.PlayerMapping mapping(
            ControllerProperties.GamepadAssignment... assignments) {
        return new ControllerProperties.PlayerMapping(Map.of(), List.of(assignments));
    }

    private static final class Rig {
        final EventBusImpl bus = new EventBusImpl(null, null, false);
        final PlayerInputHub hub = new PlayerInputHub();
        final DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        final DesktopTiltInput tiltInput = new DesktopTiltInput(bus);
        final FakeBackend backend = new FakeBackend();
        final SwingGamepad gamepad;

        Rig(ControllerProperties.PlayerMapping mapping) {
            this(mapping, new ArrayList<>());
        }

        Rig(ControllerProperties.PlayerMapping mapping,
            List<GamepadBackend.DeviceInfo> discovered) {
            gamepad = new SwingGamepad(mapping, input, tiltInput, bus, backend, discovered::add);
        }
    }

    private static final class FakeBackend implements GamepadBackend {
        private final LinkedHashMap<String, FakeDevice> devices = new LinkedHashMap<>();
        private boolean reversed;
        private boolean closed;

        FakeDevice add(String id, String name) {
            FakeDevice device = new FakeDevice(id, name);
            devices.put(id, device);
            return device;
        }

        void remove(String id) {
            devices.remove(id);
        }

        void reverseEnumeration() {
            reversed = !reversed;
        }

        @Override public void initialize() {}
        @Override public void update() {}
        @Override public void close() { closed = true; }

        @Override
        public List<DeviceInfo> devices() {
            List<FakeDevice> values = new ArrayList<>(devices.values());
            if (reversed) java.util.Collections.reverse(values);
            List<DeviceInfo> result = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                FakeDevice device = values.get(index);
                result.add(new DeviceInfo(device.id, device.name, index));
            }
            return result;
        }

        @Override
        public GamepadDevice open(DeviceInfo info) {
            FakeDevice device = devices.get(info.stableId());
            if (device != null) device.openCount++;
            return device;
        }
    }

    private static final class FakeDevice implements GamepadBackend.GamepadDevice {
        final String id;
        final String name;
        final EnumSet<GamepadBackend.PadButton> buttons =
                EnumSet.noneOf(GamepadBackend.PadButton.class);
        final EnumMap<GamepadBackend.Axis, Integer> axes =
                new EnumMap<>(GamepadBackend.Axis.class);
        boolean attached = true;
        boolean rumbling;
        int openCount;
        int closeCount;

        FakeDevice(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String stableId() { return id; }
        @Override public String name() { return name; }
        @Override public boolean attached() { return attached; }
        @Override public int axis(GamepadBackend.Axis axis) { return axes.getOrDefault(axis, 0); }
        @Override public boolean button(GamepadBackend.PadButton button) { return buttons.contains(button); }
        @Override public void rumble(boolean enabled) { rumbling = enabled; }
        @Override public void close() { closeCount++; }
    }
}
