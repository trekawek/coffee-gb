package eu.rekawek.coffeegb.swing.io;

import com.sun.jna.NativeLibrary;
import io.github.libsdl4j.api.gamecontroller.SDL_GameController;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static io.github.libsdl4j.api.Sdl.SDL_Init;
import static io.github.libsdl4j.api.Sdl.SDL_QuitSubSystem;
import static io.github.libsdl4j.api.SdlSubSystemConst.SDL_INIT_GAMECONTROLLER;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerAxis.*;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerButton.*;
import static io.github.libsdl4j.api.gamecontroller.SdlGamecontroller.*;
import static io.github.libsdl4j.api.joystick.SdlJoystick.*;

/** Real SDL2 implementation kept behind the no-native test seam. */
final class SdlGamepadBackend implements GamepadBackend {

    private boolean initialized;

    @Override
    public void initialize() {
        locateSystemSdl();
        if (SDL_Init(SDL_INIT_GAMECONTROLLER) != 0) {
            throw new IllegalStateException("SDL game-controller initialization failed");
        }
        initialized = true;
    }

    @Override
    public void update() {
        SDL_GameControllerUpdate();
    }

    @Override
    public List<DeviceInfo> devices() {
        List<DeviceInfo> devices = new ArrayList<>();
        for (int index = 0; index < SDL_NumJoysticks(); index++) {
            if (!SDL_IsGameController(index)) {
                continue;
            }
            String name = nullToEmpty(SDL_GameControllerNameForIndex(index));
            String path = nullToEmpty(SDL_GameControllerPathForIndex(index));
            String guid = SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(index));
            long instance = SDL_JoystickGetDeviceInstanceID(index).longValue();
            devices.add(new DeviceInfo(stableId(guid, path, name, instance), name, index));
        }
        return devices;
    }

    @Override
    public GamepadDevice open(DeviceInfo info) {
        SDL_GameController controller = SDL_GameControllerOpen(info.backendIndex());
        return controller == null ? null : new SdlDevice(info, controller);
    }

    @Override
    public void close() {
        if (initialized) {
            SDL_QuitSubSystem(SDL_INIT_GAMECONTROLLER);
            initialized = false;
        }
    }

    static String stableId(String guid, String path, String name) {
        return stableId(guid, path, name, 0);
    }

    static String stableId(String guid, String path, String name, long instance) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String location = nullToEmpty(path).isEmpty()
                    ? "instance:" + instance : "path:" + path;
            byte[] bytes = (nullToEmpty(guid) + '\0' + location + '\0'
                    + nullToEmpty(name)).getBytes(StandardCharsets.UTF_8);
            return "sdl-" + lowercaseHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String lowercaseHex(byte[] bytes) {
        char[] encoded = new char[Math.multiplyExact(bytes.length, 2)];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            encoded[i * 2] = Character.forDigit(value >>> 4, 16);
            encoded[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(encoded);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static void locateSystemSdl() {
        if (!isMac()) {
            return;
        }
        for (String dir : List.of("/opt/homebrew/lib", "/usr/local/lib")) {
            if (new File(dir, "libSDL2.dylib").exists()) {
                NativeLibrary.addSearchPath("SDL2", dir);
            }
        }
    }

    private record SdlDevice(DeviceInfo info, SDL_GameController controller)
            implements GamepadDevice {
        @Override public String stableId() { return info.stableId(); }
        @Override public String name() { return info.name(); }
        @Override public boolean attached() { return SDL_GameControllerGetAttached(controller); }

        @Override
        public int axis(Axis axis) {
            return SDL_GameControllerGetAxis(controller, switch (axis) {
                case LEFT_X -> SDL_CONTROLLER_AXIS_LEFTX;
                case LEFT_Y -> SDL_CONTROLLER_AXIS_LEFTY;
                case RIGHT_X -> SDL_CONTROLLER_AXIS_RIGHTX;
                case RIGHT_Y -> SDL_CONTROLLER_AXIS_RIGHTY;
            });
        }

        @Override
        public boolean button(PadButton button) {
            int value = switch (button) {
                case UP -> SDL_CONTROLLER_BUTTON_DPAD_UP;
                case DOWN -> SDL_CONTROLLER_BUTTON_DPAD_DOWN;
                case LEFT -> SDL_CONTROLLER_BUTTON_DPAD_LEFT;
                case RIGHT -> SDL_CONTROLLER_BUTTON_DPAD_RIGHT;
                case A -> SDL_CONTROLLER_BUTTON_A;
                case B -> SDL_CONTROLLER_BUTTON_B;
                case X -> SDL_CONTROLLER_BUTTON_X;
                case START -> SDL_CONTROLLER_BUTTON_START;
                case BACK -> SDL_CONTROLLER_BUTTON_BACK;
            };
            return SDL_GameControllerGetButton(controller, value) != 0;
        }

        @Override
        public void rumble(boolean enabled) {
            if (SDL_GameControllerHasRumble(controller)) {
                SDL_GameControllerRumble(controller,
                        (short) (enabled ? 0xffff : 0), (short) (enabled ? 0xffff : 0),
                        enabled ? 60_000 : 0);
            }
        }

        @Override public void close() { SDL_GameControllerClose(controller); }
    }
}
