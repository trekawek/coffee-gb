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
import java.util.Objects;
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
    static final int TILT_DEAD_ZONE =
            GamepadConfiguration.Tuning.DEFAULT_TILT_DEAD_ZONE;

    private final EventBus eventBus;
    private final DesktopPlayerInput input;
    private final DesktopTiltInput tiltInput;
    private final GamepadBackend backend;
    private final Consumer<GamepadBackend.DeviceInfo> discoveryObserver;
    private final GamepadCatalog catalog = new GamepadCatalog();
    private final Map<Integer, ActiveDevice> active = new HashMap<>();
    private final Set<String> discovered = new HashSet<>();
    private final Set<Integer> playersRequiringNeutral = new HashSet<>();
    private final Object tiltSourceIdentity = new Object();

    private GamepadConfiguration requestedConfiguration;
    private GamepadConfiguration appliedConfiguration;
    private boolean rearmRequested;
    private boolean hasPolled;
    private volatile boolean doStop;
    private volatile boolean rumbleRequested;
    private boolean rumbleActive;
    private boolean tiltActive;

    public SwingGamepad(ControllerProperties.PlayerMapping mapping, DesktopPlayerInput input,
                        DesktopTiltInput tiltInput, EventBus eventBus) {
        this(GamepadConfiguration.from(mapping), input, tiltInput, eventBus,
                new SdlGamepadBackend(), ignored -> {});
    }

    public SwingGamepad(GamepadConfiguration configuration, DesktopPlayerInput input,
                        DesktopTiltInput tiltInput, EventBus eventBus) {
        this(configuration, input, tiltInput, eventBus,
                new SdlGamepadBackend(), ignored -> {});
    }

    SwingGamepad(ControllerProperties.PlayerMapping mapping, DesktopPlayerInput input,
                 DesktopTiltInput tiltInput, EventBus eventBus, GamepadBackend backend) {
        this(GamepadConfiguration.from(mapping), input, tiltInput, eventBus,
                backend, ignored -> {});
    }

    SwingGamepad(ControllerProperties.PlayerMapping mapping, DesktopPlayerInput input,
                 DesktopTiltInput tiltInput, EventBus eventBus, GamepadBackend backend,
                 Consumer<GamepadBackend.DeviceInfo> discoveryObserver) {
        this(GamepadConfiguration.from(mapping), input, tiltInput, eventBus,
                backend, discoveryObserver);
    }

    SwingGamepad(GamepadConfiguration configuration, DesktopPlayerInput input,
                 DesktopTiltInput tiltInput, EventBus eventBus, GamepadBackend backend) {
        this(configuration, input, tiltInput, eventBus, backend, ignored -> {});
    }

    SwingGamepad(GamepadConfiguration configuration, DesktopPlayerInput input,
                 DesktopTiltInput tiltInput, EventBus eventBus, GamepadBackend backend,
                 Consumer<GamepadBackend.DeviceInfo> discoveryObserver) {
        this.requestedConfiguration = Objects.requireNonNull(configuration, "configuration");
        this.appliedConfiguration = configuration;
        this.input = Objects.requireNonNull(input, "input");
        this.tiltInput = Objects.requireNonNull(tiltInput, "tiltInput");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.discoveryObserver =
                Objects.requireNonNull(discoveryObserver, "discoveryObserver");
        tiltInput.registerResetter(this::releaseTiltAndRumble);
        eventBus.register(e -> rumbleRequested = e.on(), RumbleEvent.class);
    }

    /** A lock-free immutable device view that is safe to read from Swing's EDT. */
    public GamepadCatalog catalog() {
        return catalog;
    }

    /**
     * Requests a complete runtime replacement without calling SDL on the caller's thread.
     *
     * <p>The shared input hub is released before this method returns. The polling thread performs
     * device close/open and waits for a neutral physical sample before the new mapping can latch.
     */
    public synchronized void updateConfiguration(GamepadConfiguration configuration) {
        GamepadConfiguration next = Objects.requireNonNull(configuration, "configuration");
        if (next.equals(requestedConfiguration)) {
            return;
        }
        requestedConfiguration = next;
        rearmRequested = true;
        rumbleRequested = false;
        input.releaseAll();
        tiltInput.clear(tiltSourceIdentity);
    }

    public void updateMapping(ControllerProperties.PlayerMapping mapping) {
        updateConfiguration(GamepadConfiguration.from(mapping));
    }

    /** Releases transient input at ROM/controller replacement and rearms only from neutral. */
    public synchronized void releaseForLifecycleChange() {
        rearmRequested = true;
        rumbleRequested = false;
        input.releaseAll();
        tiltInput.clear(tiltSourceIdentity);
    }

    public void stop() {
        doStop = true;
        rumbleRequested = false;
        input.setFocused(false);
    }

    @Override
    public void run() {
        boolean backendUnavailable = false;
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
            backendUnavailable = true;
            if (isMac()) {
                LOG.warn("Game controllers need SDL2, which macOS builds don't bundle. "
                        + "Install it with 'brew install sdl2' and restart. Keyboard input still works.");
            } else {
                LOG.info("Game controllers unavailable (no SDL2 native): {}", unavailable.getMessage());
            }
        } catch (Throwable failure) {
            backendUnavailable = true;
            LOG.info("Game controllers unavailable: {}", failure.toString());
        } finally {
            closeAll();
            backend.close();
            if (backendUnavailable) {
                catalog.publishUnavailable();
            } else {
                catalog.publishStopped();
            }
        }
    }

    synchronized void pollOnce() {
        applyRequestedConfiguration();
        backend.update();
        Map<String, GamepadBackend.DeviceInfo> devices = new HashMap<>();
        backend.devices().stream().sorted(Comparator.comparing(GamepadBackend.DeviceInfo::stableId))
                .forEach(device -> devices.putIfAbsent(device.stableId(), device));

        new ArrayList<>(active.entrySet()).forEach(entry -> {
            ActiveDevice device = entry.getValue();
            if (!device.device.attached() || !devices.containsKey(device.device.stableId())) {
                devices.remove(device.device.stableId());
                playersRequiringNeutral.add(entry.getKey());
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
        appliedConfiguration.assignments().stream()
                .filter(assignment -> !assignment.getSelector().equals(
                        ControllerProperties.GamepadAssignment.AUTO))
                .forEach(assignment -> {
                    GamepadBackend.DeviceInfo desired = devices.get(assignment.getSelector());
                    assign(assignment.getPlayer(), desired);
                    if (desired != null) claimed.add(desired.stableId());
                });
        appliedConfiguration.assignments().stream()
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
        publishCatalog(devices);
        hasPolled = true;
    }

    private void applyRequestedConfiguration() {
        if (!appliedConfiguration.equals(requestedConfiguration)) {
            playersRequiringNeutral.addAll(List.of(0, 1, 2, 3));
            new ArrayList<>(active.keySet())
                    .forEach(player -> disconnect(player, "configuration changed"));
            appliedConfiguration = requestedConfiguration;
        }
        if (rearmRequested) {
            playersRequiringNeutral.addAll(List.of(0, 1, 2, 3));
            active.values().forEach(device -> device.waitingForNeutral = true);
            rearmRequested = false;
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
            boolean waitForNeutral =
                    playersRequiringNeutral.remove(player) || hasPolled;
            ActiveDevice next = new ActiveDevice(opened, waitForNeutral);
            active.put(player, next);
            LOG.info("Game controller {} assigned to P{} as {}",
                    opened.name(), player + 1, opened.stableId());
        } else if (opened != null) {
            opened.close();
        }
    }

    private void poll(int player, ActiveDevice activeDevice) {
        GamepadBackend.GamepadDevice device = activeDevice.device;
        GamepadConfiguration.Tuning tuning =
                appliedConfiguration.tuningFor(device.stableId());
        PhysicalState state = PhysicalState.read(device);
        EnumSet<Button> buttons = EnumSet.noneOf(Button.class);
        if (activeDevice.waitingForNeutral) {
            if (state.isNeutral(tuning)) {
                activeDevice.waitingForNeutral = false;
            }
        } else if (input.isFocused()) {
            int x = invertAxis(state.leftX, tuning.invertMovementX());
            int y = invertAxis(state.leftY, tuning.invertMovementY());
            int threshold = tuning.movementDeadZone();
            add(buttons, Button.UP, state.buttons.contains(UP) || y < -threshold);
            add(buttons, Button.DOWN, state.buttons.contains(DOWN) || y > threshold);
            add(buttons, Button.LEFT, state.buttons.contains(LEFT) || x < -threshold);
            add(buttons, Button.RIGHT, state.buttons.contains(RIGHT) || x > threshold);
            add(buttons, Button.A, state.buttons.contains(A));
            add(buttons, Button.B, state.buttons.contains(B) || state.buttons.contains(X));
            add(buttons, Button.START, state.buttons.contains(START));
            add(buttons, Button.SELECT, state.buttons.contains(BACK));
        }
        input.update(activeDevice.sourceIdentity, player, buttons);

        if (player == 0) {
            if (input.isFocused() && !activeDevice.waitingForNeutral) {
                updateTilt(
                        invertAxis(state.rightX, tuning.invertTiltX()),
                        invertAxis(state.rightY, tuning.invertTiltY()),
                        tuning.tiltDeadZone());
            } else {
                updateTilt(0, 0);
            }
            boolean requested =
                    input.isFocused() && !activeDevice.waitingForNeutral && rumbleRequested;
            if (requested != rumbleActive) {
                device.rumble(requested);
                rumbleActive = requested;
            }
        }
    }

    static int invertAxis(int value, boolean invert) {
        if (!invert) {
            return value;
        }
        return value == Short.MIN_VALUE ? Short.MAX_VALUE : -value;
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
        updateTilt(rawX, rawY, TILT_DEAD_ZONE);
    }

    private void updateTilt(int rawX, int rawY, int deadZone) {
        double x = normalizeTiltAxis(rawX, deadZone);
        double y = normalizeTiltAxis(rawY, deadZone);
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
        tiltActive = false;
    }

    static double normalizeTiltAxis(int raw) {
        return normalizeTiltAxis(raw, TILT_DEAD_ZONE);
    }

    static double normalizeTiltAxis(int raw, int deadZone) {
        int magnitude = Math.abs(raw);
        if (magnitude <= deadZone) return 0;
        double normalized = (double) (magnitude - deadZone) / (32767 - deadZone);
        return Math.copySign(Math.min(1, normalized), raw);
    }

    private void publishCatalog(Map<String, GamepadBackend.DeviceInfo> devices) {
        Map<String, Integer> playersByStableId = new HashMap<>();
        active.forEach((player, activeDevice) ->
                playersByStableId.put(activeDevice.device.stableId(), player));
        catalog.publishAvailable(devices.values().stream()
                .map(device -> new GamepadCatalog.Device(
                        device.stableId(),
                        device.name(),
                        playersByStableId.get(device.stableId())))
                .toList());
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private record PhysicalState(
            int leftX,
            int leftY,
            int rightX,
            int rightY,
            EnumSet<GamepadBackend.PadButton> buttons) {

        private static PhysicalState read(GamepadBackend.GamepadDevice device) {
            EnumSet<GamepadBackend.PadButton> buttons =
                    EnumSet.noneOf(GamepadBackend.PadButton.class);
            for (GamepadBackend.PadButton button : GamepadBackend.PadButton.values()) {
                if (device.button(button)) {
                    buttons.add(button);
                }
            }
            return new PhysicalState(
                    device.axis(LEFT_X),
                    device.axis(LEFT_Y),
                    device.axis(RIGHT_X),
                    device.axis(RIGHT_Y),
                    buttons);
        }

        private boolean isNeutral(GamepadConfiguration.Tuning tuning) {
            return buttons.isEmpty()
                    && Math.abs(leftX) <= tuning.movementDeadZone()
                    && Math.abs(leftY) <= tuning.movementDeadZone()
                    && Math.abs(rightX) <= tuning.tiltDeadZone()
                    && Math.abs(rightY) <= tuning.tiltDeadZone();
        }
    }

    private static final class ActiveDevice {
        private final GamepadBackend.GamepadDevice device;
        private final Object sourceIdentity = new Object();
        private boolean waitingForNeutral;

        private ActiveDevice(GamepadBackend.GamepadDevice device, boolean waitingForNeutral) {
            this.device = device;
            this.waitingForNeutral = waitingForNeutral;
        }
    }
}
