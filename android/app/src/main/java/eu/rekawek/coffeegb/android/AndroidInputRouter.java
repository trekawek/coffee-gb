package eu.rekawek.coffeegb.android;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;

import java.util.Collection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Android source adapter over the portable {@link PlayerInputHub}.
 *
 * <p>Each touch pointer and connected controller has its own releasable source handle. The hub
 * unions these sources, so a cancellation or disconnect releases only that source and never
 * creates duplicate or stuck logical transitions when touch, keyboard, and controllers overlap.
 */
final class AndroidInputRouter implements AutoCloseable {

    private static final float DEAD_ZONE = 0.45f;

    private static final Map<Integer, Button> DEFAULT_KEYS = Map.ofEntries(
            Map.entry(KeyEvent.KEYCODE_DPAD_LEFT, Button.LEFT),
            Map.entry(KeyEvent.KEYCODE_DPAD_RIGHT, Button.RIGHT),
            Map.entry(KeyEvent.KEYCODE_DPAD_UP, Button.UP),
            Map.entry(KeyEvent.KEYCODE_DPAD_DOWN, Button.DOWN),
            Map.entry(KeyEvent.KEYCODE_BUTTON_A, Button.A),
            Map.entry(KeyEvent.KEYCODE_BUTTON_B, Button.B),
            Map.entry(KeyEvent.KEYCODE_BUTTON_X, Button.A),
            Map.entry(KeyEvent.KEYCODE_BUTTON_Y, Button.B),
            Map.entry(KeyEvent.KEYCODE_BUTTON_START, Button.START),
            Map.entry(KeyEvent.KEYCODE_BUTTON_SELECT, Button.SELECT),
            Map.entry(KeyEvent.KEYCODE_ENTER, Button.START),
            Map.entry(KeyEvent.KEYCODE_SPACE, Button.A));

    private final PlayerInputHub hub;
    private final AndroidControllerMappings mappings;
    private final Map<Integer, PlayerInputHub.SourceHandle> touchPointers = new HashMap<>();
    private final Map<String, DeviceSource> devices = new HashMap<>();
    private final Map<Integer, String> deviceIds = new HashMap<>();
    private final Map<String, InputDevice> connectedDevices = new HashMap<>();
    private final PlayerInputHub.SourceHandle keyboard;
    private final EnumSet<Button> keyboardButtons = EnumSet.noneOf(Button.class);
    private final ControllerKeyCapture capture;
    private final Runnable benchmarkMutationRecorder;

    private InputDevice activeController;
    /** Persisted selector: none, auto, or an sdl- stable device token. */
    private String gamepadSelection = "auto";
    private boolean closed;
    private boolean benchmarkLocked;

    AndroidInputRouter(PlayerInputHub hub) {
        this(hub, null);
    }

    AndroidInputRouter(PlayerInputHub hub, AndroidControllerMappings mappings) {
        this(hub, mappings, null);
    }

    AndroidInputRouter(PlayerInputHub hub, AndroidControllerMappings mappings,
            Runnable benchmarkMutationRecorder) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.mappings = mappings;
        this.benchmarkMutationRecorder = benchmarkMutationRecorder;
        capture = new ControllerKeyCapture((deviceId, keyCode, target) -> {
            InputDevice controller = InputDevice.getDevice(deviceId);
            if (this.mappings != null && isConfigurableController(controller)) {
                this.mappings.setBinding(controller, keyCode, target, DEFAULT_KEYS);
            }
        });
        keyboard = hub.openSource(0);
    }

    synchronized void updateTouchPointer(int pointerId, Collection<Button> buttons) {
        if (closed) {
            return;
        }
        if (benchmarkLocked) {
            recordBenchmarkMutation();
            return;
        }
        PlayerInputHub.SourceHandle source = touchPointers.computeIfAbsent(
                pointerId, ignored -> hub.openSource(0));
        source.update(buttons);
    }

    synchronized void releaseTouchPointer(int pointerId) {
        PlayerInputHub.SourceHandle source = touchPointers.remove(pointerId);
        if (source != null) {
            source.close();
        }
    }

    synchronized void releaseAllTouch() {
        touchPointers.values().forEach(PlayerInputHub.SourceHandle::close);
        touchPointers.clear();
    }

    synchronized boolean onKeyEvent(KeyEvent event) {
        if (closed || event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            return false;
        }
        if (benchmarkLocked) {
            recordBenchmarkMutation();
            return true;
        }
        if (isGameController(event.getSource())) {
            InputDevice controller = event.getDevice();
            if (!isAllowed(controller)) {
                return true;
            }
            if (isConfigurableController(controller)) {
                activeController = controller;
            }
            if (capture.active()) {
                CaptureResult result = captureKeyEvent(event);
                if (result != CaptureResult.NONE) {
                    return true;
                }
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                return true;
            }
            Button button = DEFAULT_KEYS.get(event.getKeyCode());
            if (mappings != null) {
                button = mappings.binding(controller, event.getKeyCode(), button);
            }
            if (button == null) {
                return false;
            }
            DeviceSource source = device(controller);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                source.keyButtons.add(button);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                source.keyButtons.remove(button);
            } else {
                return false;
            }
            source.publish();
            return true;
        }
        Button button = DEFAULT_KEYS.get(event.getKeyCode());
        if (button == null) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            keyboardButtons.add(button);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            keyboardButtons.remove(button);
        } else {
            return false;
        }
        keyboard.update(keyboardButtons);
        return true;
    }

    synchronized boolean onMotionEvent(MotionEvent event) {
        if (closed || !isGameController(event.getSource()) || event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        if (benchmarkLocked) {
            recordBenchmarkMutation();
            return true;
        }
        InputDevice controller = event.getDevice();
        if (!isAllowed(controller)) {
            return true;
        }
        if (isConfigurableController(controller)) {
            activeController = controller;
        }
        DeviceSource source = device(controller);
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float x = Math.abs(hatX) >= DEAD_ZONE ? hatX : event.getAxisValue(MotionEvent.AXIS_X);
        float y = Math.abs(hatY) >= DEAD_ZONE ? hatY : event.getAxisValue(MotionEvent.AXIS_Y);
        if (mappings != null && mappings.invertedX(controller)) {
            x = -x;
        }
        if (mappings != null && mappings.invertedY(controller)) {
            y = -y;
        }
        applyAxis(source.axisButtons, x,
                Button.LEFT, Button.RIGHT);
        applyAxis(source.axisButtons, y,
                Button.UP, Button.DOWN);
        source.publish();
        return true;
    }

    synchronized void disconnect(InputDevice device) {
        if (device == null) {
            return;
        }
        disconnect(device.getId());
    }

    synchronized void disconnect(int deviceId) {
        if (benchmarkLocked) {
            recordBenchmarkMutation();
            return;
        }
        String key = deviceIds.remove(deviceId);
        if (key != null) {
            connectedDevices.remove(key);
            DeviceSource source = devices.remove(key);
            if (source != null) {
                source.close();
            }
        }
        if (activeController != null && activeController.getId() == deviceId) {
            activeController = null;
            cancelCapture();
        } else {
            capture.disconnect(deviceId);
        }
    }

    synchronized boolean beginCapture(Button target) {
        if (closed || configurationDevice() == null) {
            return false;
        }
        if (benchmarkLocked) {
            recordBenchmarkMutation();
            return false;
        }
        capture.begin(Objects.requireNonNull(target, "target"));
        return true;
    }

    /** Selects which physical gamepad is allowed to feed player one. */
    synchronized void setGamepadSelection(String selection) {
        if (benchmarkLocked) {
            recordBenchmarkMutation();
            return;
        }
        String normalized = normalizeSelection(selection);
        if (gamepadSelection.equals(normalized)) {
            return;
        }
        gamepadSelection = normalized;
        if (!isAllowed(activeController)) {
            activeController = null;
            cancelCapture();
        }
        ArrayList<String> remove = new ArrayList<>();
        for (Map.Entry<String, DeviceSource> entry : devices.entrySet()) {
            InputDevice device = connectedDevices.get(entry.getKey());
            if (!isAllowed(device)) {
                entry.getValue().close();
                remove.add(entry.getKey());
            }
        }
        for (String key : remove) {
            devices.remove(key);
        }
    }

    synchronized String gamepadSelection() {
        return gamepadSelection;
    }

    synchronized boolean acceptsController(InputDevice device) {
        return isAllowed(device);
    }

    /** Menu navigation remains available from a physical controller even when gameplay is OFF. */
    synchronized boolean acceptsMenuController(InputDevice device) {
        return device != null && acceptsMenuControllerSources(device.getSources(), device.isVirtual());
    }

    static boolean acceptsMenuControllerSources(int sources, boolean virtual) {
        return isConfigurableControllerSources(sources, virtual);
    }

    /** Returns currently connected, non-virtual physical gamepads with stable selector tokens. */
    synchronized List<ControllerChoice> controllerChoices() {
        ArrayList<ControllerChoice> choices = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (isConfigurableController(device)) {
                choices.add(new ControllerChoice(stableToken(device),
                        device.getName() == null || device.getName().isBlank()
                                ? "CONTROLLER" : device.getName()));
            }
        }
        choices.sort(java.util.Comparator.comparing(ControllerChoice::label)
                .thenComparing(ControllerChoice::token));
        return List.copyOf(choices);
    }

    synchronized CaptureResult captureKeyEvent(KeyEvent event) {
        if (closed || benchmarkLocked || !capture.active() || event == null
                || !isConfigurableController(event.getDevice())
                || !isAllowed(event.getDevice())) {
            return CaptureResult.NONE;
        }
        InputDevice controller = event.getDevice();
        activeController = controller;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            capture.keyDown(event.getDeviceId(), event.getKeyCode());
            return CaptureResult.CONSUMED;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            ControllerKeyCapture.Result result = capture.keyUp(
                    event.getDeviceId(), event.getKeyCode());
            return result == ControllerKeyCapture.Result.COMPLETED
                    ? CaptureResult.COMPLETED : CaptureResult.CONSUMED;
        }
        return CaptureResult.CONSUMED;
    }

    synchronized void cancelCapture() {
        capture.cancel();
    }

    synchronized boolean captureActive() {
        return capture.active();
    }

    synchronized boolean captureWaitingForRelease() {
        return capture.waitingForRelease();
    }

    synchronized Button captureTarget() {
        return capture.target();
    }

    synchronized Map<Button, String> effectiveKeyLabels() {
        InputDevice controller = configurationDevice();
        EnumMap<Button, String> labels = new EnumMap<>(Button.class);
        if (controller == null) {
            return Map.of();
        }
        for (Button button : Button.values()) {
            Integer keyCode = mappings == null ? defaultKeyCode(button)
                    : mappings.keyCodeForButton(controller, button, DEFAULT_KEYS);
            if (keyCode != null) {
                labels.put(button, keyLabel(keyCode));
            }
        }
        return Map.copyOf(labels);
    }

    synchronized boolean toggleHorizontalInversion() {
        InputDevice controller = configurationDevice();
        if (controller == null || mappings == null) {
            return false;
        }
        mappings.toggleInvertedX(controller);
        return true;
    }

    synchronized boolean toggleVerticalInversion() {
        InputDevice controller = configurationDevice();
        if (controller == null || mappings == null) {
            return false;
        }
        mappings.toggleInvertedY(controller);
        return true;
    }

    synchronized boolean horizontalInverted() {
        InputDevice controller = configurationDevice();
        return controller != null && mappings != null && mappings.invertedX(controller);
    }

    synchronized boolean verticalInverted() {
        InputDevice controller = configurationDevice();
        return controller != null && mappings != null && mappings.invertedY(controller);
    }

    synchronized boolean resetActiveController() {
        InputDevice controller = configurationDevice();
        if (controller == null || mappings == null) {
            return false;
        }
        mappings.reset(controller);
        cancelCapture();
        return true;
    }

    synchronized String activeControllerName() {
        InputDevice controller = configurationDevice();
        return controller == null ? null : controller.getName();
    }

    synchronized void releaseAll() {
        releaseAllTouch();
        keyboardButtons.clear();
        keyboard.update(keyboardButtons);
        devices.values().forEach(DeviceSource::clear);
        cancelCapture();
    }

    /** Freezes all live controller/touch sources for the measured benchmark window. */
    synchronized void lockBenchmarkWindow() {
        if (closed || benchmarkLocked) {
            return;
        }
        releaseAll();
        benchmarkLocked = true;
    }

    private void recordBenchmarkMutation() {
        if (benchmarkMutationRecorder != null) {
            benchmarkMutationRecorder.run();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        releaseAllTouch();
        keyboard.close();
        devices.values().forEach(DeviceSource::close);
        devices.clear();
        deviceIds.clear();
        connectedDevices.clear();
        activeController = null;
        cancelCapture();
        closed = true;
    }

    private DeviceSource device(InputDevice device) {
        String key = device == null ? "unknown" : deviceKey(device);
        if (device != null) {
            deviceIds.put(device.getId(), key);
            connectedDevices.put(key, device);
        }
        return devices.computeIfAbsent(key, ignored -> new DeviceSource(hub.openSource(0)));
    }

    private InputDevice configurationDevice() {
        if ("none".equals(gamepadSelection)) {
            activeController = null;
            return null;
        }
        if (isConfigurableController(activeController)) {
            if (isAllowed(activeController)) {
                return activeController;
            }
            activeController = null;
        }
        InputDevice first = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (isConfigurableController(device) && isAllowed(device)) {
                if (first == null) {
                    first = device;
                }
                if (!"auto".equals(gamepadSelection)) {
                    break;
                }
            }
        }
        activeController = first;
        return first;
    }

    private boolean isAllowed(InputDevice device) {
        if (!isConfigurableController(device) || "none".equals(gamepadSelection)) {
            return false;
        }
        return "auto".equals(gamepadSelection)
                || stableToken(device).equals(gamepadSelection);
    }

    static boolean allowsSelection(String selection, String token) {
        String normalized = normalizeSelection(selection);
        return !"none".equals(normalized)
                && ("auto".equals(normalized) || normalized.equals(token));
    }

    private static String normalizeSelection(String selection) {
        if (selection == null || selection.isBlank() || "AUTO".equalsIgnoreCase(selection)) {
            return "auto";
        }
        if ("OFF".equalsIgnoreCase(selection) || "none".equalsIgnoreCase(selection)) {
            return "none";
        }
        return selection;
    }

    static String stableToken(InputDevice device) {
        if (device == null) {
            return "";
        }
        String descriptor = device.getDescriptor();
        String raw = (descriptor == null ? "" : descriptor) + "|"
                + device.getVendorId() + "|" + device.getProductId();
        return "sdl-" + sha256(raw);
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(Character.forDigit((value >>> 4) & 0xf, 16));
                encoded.append(Character.forDigit(value & 0xf, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String deviceKey(InputDevice device) {
        String descriptor = device.getDescriptor();
        if (descriptor != null && !descriptor.isBlank()) {
            return descriptor;
        }
        return device.getVendorId() + ":" + device.getProductId() + ":" + device.getId();
    }

    /** A picker-safe immutable physical-controller identity. */
    record ControllerChoice(String token, String label) {
    }

    private static boolean isGameController(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static boolean isConfigurableController(InputDevice device) {
        return device != null
                && isConfigurableControllerSources(device.getSources(), device.isVirtual());
    }

    static boolean isConfigurableControllerSources(int sources, boolean virtual) {
        return !virtual && ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    private static Integer defaultKeyCode(Button target) {
        int[] preferred = {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        };
        for (int keyCode : preferred) {
            if (DEFAULT_KEYS.get(keyCode) == target) {
                return keyCode;
            }
        }
        return null;
    }

    private static String keyLabel(int keyCode) {
        String label = KeyEvent.keyCodeToString(keyCode);
        if (label.startsWith("KEYCODE_")) {
            label = label.substring("KEYCODE_".length());
        }
        return label.replace("BUTTON_", "BUTTON ");
    }


    private static void applyAxis(EnumSet<Button> buttons, float value, Button negative, Button positive) {
        buttons.remove(negative);
        buttons.remove(positive);
        if (value <= -DEAD_ZONE) {
            buttons.add(negative);
        } else if (value >= DEAD_ZONE) {
            buttons.add(positive);
        }
    }

    private static final class DeviceSource {
        private final PlayerInputHub.SourceHandle handle;
        private final EnumSet<Button> keyButtons = EnumSet.noneOf(Button.class);
        private final EnumSet<Button> axisButtons = EnumSet.noneOf(Button.class);

        private DeviceSource(PlayerInputHub.SourceHandle handle) {
            this.handle = handle;
        }

        private void publish() {
            EnumSet<Button> pressed = EnumSet.copyOf(keyButtons);
            pressed.addAll(axisButtons);
            handle.update(pressed);
        }

        private void clear() {
            keyButtons.clear();
            axisButtons.clear();
            publish();
        }

        private void close() {
            handle.close();
        }
    }

    enum CaptureResult {
        NONE,
        CONSUMED,
        COMPLETED
    }
}
