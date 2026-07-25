package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.ControllerProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static eu.rekawek.coffeegb.swing.io.GamepadBackend.Axis.*;
import static eu.rekawek.coffeegb.swing.io.GamepadBackend.PadButton.*;

/**
 * Up-to-four-player SDL gamepad polling with stable explicit assignments.
 *
 * <p>SDL enumeration is isolated behind {@link GamepadBackend}; the poller binds by a SHA-256
 * stable ID rather than array position, so enumeration churn cannot move held input between
 * players. Only P1 retains cartridge tilt and rumble compatibility.
 */
public class SwingGamepad implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(SwingGamepad.class);
    private static final int POLL_MS = 8;
    private static final int AXIS_THRESHOLD = 16384;
    static final int TILT_DEAD_ZONE = 4096;

    private final EventBus eventBus;
    private final DesktopPlayerInput input;
    private final DesktopTiltInput tiltInput;
    private final List<ControllerProperties.GamepadAssignment> assignments;
    private final GamepadBackend backend;
    private final Consumer<GamepadBackend.DeviceInfo> discoveryObserver;
    private final Map<Integer, ActiveDevice> active = new HashMap<>();
    private final Set<String> discovered = new HashSet<>();
    private final Object tiltSourceIdentity = new Object();

    private volatile boolean doStop;
    private volatile boolean rumbleRequested;
    private boolean rumbleActive;
    private boolean tiltActive;

    public SwingGamepad(ControllerProperties.PlayerMapping mapping, DesktopPlayerInput input,
                        DesktopTiltInput tiltInput, EventBus eventBus) {
        this(mapping, input, tiltInput, eventBus, new SdlGamepadBackend(), ignored -> {});
    }

    SwingGamepad(ControllerProperties.PlayerMapping mapping, DesktopPlayerInput input,
                 DesktopTiltInput tiltInput, EventBus eventBus, GamepadBackend backend) {
        this(mapping, input, tiltInput, eventBus, backend, ignored -> {});
    }

    SwingGamepad(ControllerProperties.PlayerMapping mapping, DesktopPlayerInput input,
                 DesktopTiltInput tiltInput, EventBus eventBus, GamepadBackend backend,
                 Consumer<GamepadBackend.DeviceInfo> discoveryObserver) {
        this.assignments = List.copyOf(mapping.getGamepads());
        this.input = input;
        this.tiltInput = tiltInput;
        this.eventBus = eventBus;
        this.backend = backend;
        this.discoveryObserver = discoveryObserver;
        tiltInput.registerResetter(this::releaseTiltAndRumble);
        eventBus.register(e -> rumbleRequested = e.on(), RumbleEvent.class);
    }

    public void stop() {
        doStop = true;
        rumbleRequested = false;
        input.setFocused(false);
    }

    @Override
    public void run() {
        try {
            backend.initialize();
            while (!doStop) {
                pollOnce();
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (UnsatisfiedLinkError unavailable) {
            if (isMac()) {
                LOG.warn("Game controllers need SDL2, which macOS builds don't bundle. "
                        + "Install it with 'brew install sdl2' and restart. Keyboard input still works.");
            } else {
                LOG.info("Game controllers unavailable (no SDL2 native): {}", unavailable.getMessage());
            }
        } catch (Throwable failure) {
            LOG.info("Game controllers unavailable: {}", failure.toString());
        } finally {
            closeAll();
            backend.close();
        }
    }

    synchronized void pollOnce() {
        backend.update();
        Map<String, GamepadBackend.DeviceInfo> devices = new HashMap<>();
        backend.devices().stream().sorted(Comparator.comparing(GamepadBackend.DeviceInfo::stableId))
                .forEach(device -> devices.putIfAbsent(device.stableId(), device));

        new ArrayList<>(active.entrySet()).forEach(entry -> {
            ActiveDevice device = entry.getValue();
            if (!device.device.attached() || !devices.containsKey(device.device.stableId())) {
                devices.remove(device.device.stableId());
                disconnect(entry.getKey(), "disconnected");
            }
        });

        discovered.retainAll(devices.keySet());
        devices.values().stream().sorted(Comparator.comparing(GamepadBackend.DeviceInfo::stableId))
                .filter(device -> discovered.add(device.stableId()))
                .forEach(device -> {
                    LOG.info("Game controller {} discovered as {}", device.name(), device.stableId());
                    discoveryObserver.accept(device);
                });

        Set<String> claimed = new HashSet<>();
        assignments.stream()
                .filter(assignment -> !assignment.getSelector().equals(
                        ControllerProperties.GamepadAssignment.AUTO))
                .forEach(assignment -> {
                    GamepadBackend.DeviceInfo desired = devices.get(assignment.getSelector());
                    assign(assignment.getPlayer(), desired);
                    if (desired != null) claimed.add(desired.stableId());
                });
        assignments.stream()
                .filter(assignment -> assignment.getSelector().equals(
                        ControllerProperties.GamepadAssignment.AUTO))
                .forEach(assignment -> {
                    ActiveDevice existing = active.get(assignment.getPlayer());
                    GamepadBackend.DeviceInfo desired = null;
                    if (existing != null && devices.containsKey(existing.device.stableId())
                            && !claimed.contains(existing.device.stableId())) {
                        desired = devices.get(existing.device.stableId());
                    }
                    if (desired == null) {
                        desired = devices.values().stream()
                                .filter(device -> !claimed.contains(device.stableId()))
                                .min(Comparator.comparing(GamepadBackend.DeviceInfo::stableId))
                                .orElse(null);
                    }
                    assign(assignment.getPlayer(), desired);
                    if (desired != null) claimed.add(desired.stableId());
                });

        active.forEach(this::poll);
        if (!active.containsKey(0)) {
            updateTilt(0, 0);
            rumbleActive = false;
        }
    }

    private void assign(int player, GamepadBackend.DeviceInfo desired) {
        ActiveDevice current = active.get(player);
        if (desired == null) {
            if (current != null) disconnect(player, "assignment unavailable");
            return;
        }
        if (current != null && current.device.stableId().equals(desired.stableId())) {
            return;
        }
        if (current != null) disconnect(player, "assignment changed");
        GamepadBackend.GamepadDevice opened = backend.open(desired);
        if (opened != null && opened.attached()) {
            ActiveDevice next = new ActiveDevice(opened);
            active.put(player, next);
            LOG.info("Game controller {} assigned to P{} as {}",
                    opened.name(), player + 1, opened.stableId());
        } else if (opened != null) {
            opened.close();
        }
    }

    private void poll(int player, ActiveDevice activeDevice) {
        GamepadBackend.GamepadDevice device = activeDevice.device;
        EnumSet<Button> buttons = EnumSet.noneOf(Button.class);
        if (input.isFocused()) {
            int x = device.axis(LEFT_X);
            int y = device.axis(LEFT_Y);
            add(buttons, Button.UP, device.button(UP) || y < -AXIS_THRESHOLD);
            add(buttons, Button.DOWN, device.button(DOWN) || y > AXIS_THRESHOLD);
            add(buttons, Button.LEFT, device.button(LEFT) || x < -AXIS_THRESHOLD);
            add(buttons, Button.RIGHT, device.button(RIGHT) || x > AXIS_THRESHOLD);
            add(buttons, Button.A, device.button(A));
            add(buttons, Button.B, device.button(B) || device.button(X));
            add(buttons, Button.START, device.button(START));
            add(buttons, Button.SELECT, device.button(BACK));
        }
        input.update(activeDevice.sourceIdentity, player, buttons);

        if (player == 0) {
            if (input.isFocused()) {
                updateTilt(device.axis(RIGHT_X), device.axis(RIGHT_Y));
            } else {
                updateTilt(0, 0);
            }
            boolean requested = input.isFocused() && rumbleRequested;
            if (requested != rumbleActive) {
                device.rumble(requested);
                rumbleActive = requested;
            }
        }
    }

    private static void add(Set<Button> buttons, Button button, boolean down) {
        if (down) buttons.add(button);
    }

    private void disconnect(int player, String reason) {
        ActiveDevice removed = active.remove(player);
        if (removed == null) return;
        input.disconnect(removed.sourceIdentity);
        if (player == 0) {
            if (rumbleActive) removed.device.rumble(false);
            rumbleActive = false;
            updateTilt(0, 0);
        }
        removed.device.close();
        LOG.info("Game controller {} released from P{} ({})",
                removed.device.name(), player + 1, reason);
    }

    private synchronized void closeAll() {
        new ArrayList<>(active.keySet()).forEach(player -> disconnect(player, "poller stopped"));
        tiltInput.clear(tiltSourceIdentity);
        discovered.clear();
    }

    void updateTilt(int rawX, int rawY) {
        double x = normalizeTiltAxis(rawX);
        double y = normalizeTiltAxis(rawY);
        if (x != 0 || y != 0) {
            tiltInput.update(tiltSourceIdentity, x, y);
            tiltActive = true;
        } else if (tiltActive) {
            tiltInput.clear(tiltSourceIdentity);
            tiltActive = false;
        }
    }

    private synchronized void releaseTiltAndRumble() {
        rumbleRequested = false;
        ActiveDevice primary = active.get(0);
        if (primary != null && rumbleActive) {
            primary.device.rumble(false);
        }
        rumbleActive = false;
        tiltActive = false;
    }

    static double normalizeTiltAxis(int raw) {
        int magnitude = Math.abs(raw);
        if (magnitude <= TILT_DEAD_ZONE) return 0;
        double normalized = (double) (magnitude - TILT_DEAD_ZONE) / (32767 - TILT_DEAD_ZONE);
        return Math.copySign(Math.min(1, normalized), raw);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static final class ActiveDevice {
        private final GamepadBackend.GamepadDevice device;
        private final Object sourceIdentity = new Object();

        private ActiveDevice(GamepadBackend.GamepadDevice device) {
            this.device = device;
        }
    }
}
