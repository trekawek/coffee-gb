package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.Objects;

/** Pure one-button down/up capture state used before normal menu and emulator routing. */
final class ControllerKeyCapture {

    @FunctionalInterface
    interface BindingWriter {
        void write(int deviceId, int keyCode, Button target);
    }

    enum Result {
        NONE,
        CAPTURED_DOWN,
        CONSUMED,
        COMPLETED
    }

    private Button target;
    private int deviceId = -1;
    private int keyCode = -1;
    private final BindingWriter bindingWriter;

    ControllerKeyCapture() {
        this((deviceId, keyCode, target) -> { });
    }

    ControllerKeyCapture(BindingWriter bindingWriter) {
        this.bindingWriter = Objects.requireNonNull(bindingWriter, "bindingWriter");
    }

    void begin(Button nextTarget) {
        target = Objects.requireNonNull(nextTarget, "nextTarget");
        deviceId = -1;
        keyCode = -1;
    }

    Result keyDown(int nextDeviceId, int nextKeyCode) {
        if (target == null) {
            return Result.NONE;
        }
        if (keyCode < 0) {
            deviceId = nextDeviceId;
            keyCode = nextKeyCode;
            return Result.CAPTURED_DOWN;
        }
        return Result.CONSUMED;
    }

    Result keyUp(int nextDeviceId, int nextKeyCode) {
        if (target == null) {
            return Result.NONE;
        }
        if (deviceId == nextDeviceId && keyCode == nextKeyCode) {
            Button completedTarget = target;
            int completedDevice = deviceId;
            int completedKey = keyCode;
            cancel();
            bindingWriter.write(completedDevice, completedKey, completedTarget);
            return Result.COMPLETED;
        }
        return Result.CONSUMED;
    }

    void disconnect(int disconnectedDeviceId) {
        if (deviceId == disconnectedDeviceId) {
            cancel();
        }
    }

    void cancel() {
        target = null;
        deviceId = -1;
        keyCode = -1;
    }

    boolean active() {
        return target != null;
    }

    boolean waitingForRelease() {
        return active() && keyCode >= 0;
    }

    Button target() {
        return target;
    }
}
