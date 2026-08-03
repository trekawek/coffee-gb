package eu.rekawek.coffeegb.android;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
    private final PlayerInputHub.SourceHandle keyboard;
    private final EnumSet<Button> keyboardButtons = EnumSet.noneOf(Button.class);

    private InputDevice activeController;
    private Button captureTarget;
    private boolean closed;

    AndroidInputRouter(PlayerInputHub hub) {
        this(hub, null);
    }

    AndroidInputRouter(PlayerInputHub hub, AndroidControllerMappings mappings) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.mappings = mappings;
        keyboard = hub.openSource(0);
    }

    synchronized void updateTouchPointer(int pointerId, Collection<Button> buttons) {
        if (closed) {
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
        if (isGameController(event.getSource())) {
            InputDevice controller = event.getDevice();
            activeController = controller;
            if (event.getAction() == KeyEvent.ACTION_DOWN && captureTarget != null) {
                if (mappings != null && controller != null) {
                    mappings.setBinding(controller, event.getKeyCode(), captureTarget, DEFAULT_KEYS);
                }
                captureTarget = null;
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
        InputDevice controller = event.getDevice();
        activeController = controller;
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
        String key = deviceIds.remove(deviceId);
        if (key == null) {
            return;
        }
        DeviceSource source = devices.remove(key);
        if (source != null) {
            source.close();
        }
        if (activeController != null && activeController.getId() == deviceId) {
            activeController = null;
            captureTarget = null;
        }
    }

    synchronized boolean beginCapture(Button target) {
        if (closed || configurationDevice() == null) {
            return false;
        }
        captureTarget = Objects.requireNonNull(target, "target");
        return true;
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
        captureTarget = null;
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
        captureTarget = null;
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
        activeController = null;
        captureTarget = null;
        closed = true;
    }

    private DeviceSource device(InputDevice device) {
        String key = device == null ? "unknown" : deviceKey(device);
        if (device != null) {
            deviceIds.put(device.getId(), key);
        }
        return devices.computeIfAbsent(key, ignored -> new DeviceSource(hub.openSource(0)));
    }

    private InputDevice configurationDevice() {
        if (activeController != null) {
            return activeController;
        }
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && isGameController(device.getSources())) {
                activeController = device;
                return device;
            }
        }
        return null;
    }

    private static boolean isGameController(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static String deviceKey(InputDevice device) {
        String descriptor = device.getDescriptor();
        if (descriptor != null && !descriptor.isBlank()) {
            return descriptor;
        }
        return device.getVendorId() + ":" + device.getProductId() + ":" + device.getId();
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
}
