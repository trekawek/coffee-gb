package eu.rekawek.coffeegb.swing.io;

import com.sun.jna.NativeLibrary;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import io.github.libsdl4j.api.gamecontroller.SDL_GameController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.github.libsdl4j.api.Sdl.SDL_Init;
import static io.github.libsdl4j.api.SdlSubSystemConst.SDL_INIT_GAMECONTROLLER;
import static io.github.libsdl4j.api.error.SdlError.SDL_GetError;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerAxis.SDL_CONTROLLER_AXIS_LEFTX;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerAxis.SDL_CONTROLLER_AXIS_LEFTY;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerAxis.SDL_CONTROLLER_AXIS_RIGHTX;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerAxis.SDL_CONTROLLER_AXIS_RIGHTY;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerButton.*;
import static io.github.libsdl4j.api.gamecontroller.SdlGamecontroller.*;
import static io.github.libsdl4j.api.joystick.SdlJoystick.SDL_NumJoysticks;

/**
 * Game-controller input via SDL2 (issue #78): any pad SDL recognizes - PS3/PS4/PS5,
 * Xbox, Switch Pro, generic HID - works out of the box through SDL's built-in mapping
 * database. The first connected controller drives the joypad: d-pad or left stick for
 * the directions, A/B for the buttons, Start, and Back/Select for select. The right stick
 * controls MBC7 cartridge tilt for Kirby's Tilt 'n' Tumble. Hotplug is handled by
 * rescanning while no controller is open.
 *
 * <p>Rumble requests from cartridges and pass-through accessories are forwarded to the
 * controller's force feedback when it has any.
 *
 * <p>libsdl4j bundles the SDL2 native only for Linux and Windows. On macOS SDL2 must be
 * installed separately ({@code brew install sdl2}); this looks it up in Homebrew's lib
 * directories, which JNA does not search by default, and otherwise degrades to
 * keyboard-only input with a hint in the log.
 */
public class SwingGamepad implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(SwingGamepad.class);

    private static final int POLL_MS = 8;

    // half of the axis range: the left stick acts as a digital d-pad past this deflection
    private static final int AXIS_THRESHOLD = 16384;

    // Ignore the center eighth of the right stick, then rescale the remaining travel so
    // controllers with ordinary resting drift do not continuously tilt the cartridge.
    static final int TILT_DEAD_ZONE = 4096;

    private final EventBus eventBus;

    private volatile boolean doStop;

    // written by the event bus, applied on the SDL thread
    private volatile boolean rumbleRequested;

    private SDL_GameController controller;

    private final Set<Button> pressed = EnumSet.noneOf(Button.class);

    private boolean rumbleActive;

    private boolean tiltActive;

    public SwingGamepad(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.register(e -> rumbleRequested = e.on(), RumbleEvent.class);
    }

    public void stop() {
        doStop = true;
    }

    @Override
    public void run() {
        locateSystemSdl();
        try {
            if (SDL_Init(SDL_INIT_GAMECONTROLLER) != 0) {
                LOG.info("Game controllers unavailable: {}", SDL_GetError());
                return;
            }
        } catch (UnsatisfiedLinkError e) {
            // no SDL2 library on this machine - keyboard input still works. libsdl4j
            // bundles the native only for Linux and Windows; macOS must supply its own.
            if (isMac()) {
                LOG.warn("Game controllers need SDL2, which macOS builds don't bundle. "
                        + "Install it with 'brew install sdl2' and restart. Keyboard input still works.");
            } else {
                LOG.info("Game controllers unavailable (no SDL2 native): {}", e.getMessage());
            }
            return;
        } catch (Throwable e) {
            LOG.info("Game controllers unavailable: {}", e.toString());
            return;
        }
        while (!doStop) {
            SDL_GameControllerUpdate();
            if (controller == null) {
                openFirstController();
            } else if (!SDL_GameControllerGetAttached(controller)) {
                LOG.info("Game controller disconnected");
                SDL_GameControllerClose(controller);
                controller = null;
                releaseAll();
            }
            if (controller != null) {
                pollButtons();
                pollTilt();
                applyRumble();
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    // libsdl4j ships the native only for Linux and Windows; on macOS SDL2 comes from a
    // system install (Homebrew). JNA's default dlopen search misses Homebrew's dirs - the
    // Apple-Silicon prefix /opt/homebrew/lib especially - so add them explicitly for the
    // "SDL2" library JNA will look up.
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

    private void openFirstController() {
        int joysticks = SDL_NumJoysticks();
        for (int i = 0; i < joysticks; i++) {
            if (SDL_IsGameController(i)) {
                controller = SDL_GameControllerOpen(i);
                if (controller != null) {
                    LOG.info("Game controller connected: {}", SDL_GameControllerName(controller));
                    return;
                }
            }
        }
    }

    private void pollButtons() {
        int x = SDL_GameControllerGetAxis(controller, SDL_CONTROLLER_AXIS_LEFTX);
        int y = SDL_GameControllerGetAxis(controller, SDL_CONTROLLER_AXIS_LEFTY);
        update(Button.UP, isDown(SDL_CONTROLLER_BUTTON_DPAD_UP) || y < -AXIS_THRESHOLD);
        update(Button.DOWN, isDown(SDL_CONTROLLER_BUTTON_DPAD_DOWN) || y > AXIS_THRESHOLD);
        update(Button.LEFT, isDown(SDL_CONTROLLER_BUTTON_DPAD_LEFT) || x < -AXIS_THRESHOLD);
        update(Button.RIGHT, isDown(SDL_CONTROLLER_BUTTON_DPAD_RIGHT) || x > AXIS_THRESHOLD);
        update(Button.A, isDown(SDL_CONTROLLER_BUTTON_A));
        update(Button.B, isDown(SDL_CONTROLLER_BUTTON_B) || isDown(SDL_CONTROLLER_BUTTON_X));
        update(Button.START, isDown(SDL_CONTROLLER_BUTTON_START));
        update(Button.SELECT, isDown(SDL_CONTROLLER_BUTTON_BACK));
    }

    private void pollTilt() {
        updateTilt(
                SDL_GameControllerGetAxis(controller, SDL_CONTROLLER_AXIS_RIGHTX),
                SDL_GameControllerGetAxis(controller, SDL_CONTROLLER_AXIS_RIGHTY));
    }

    void updateTilt(int rawX, int rawY) {
        double x = normalizeTiltAxis(rawX);
        double y = normalizeTiltAxis(rawY);
        if (x != 0 || y != 0) {
            // Keep asserting a held stick so it remains authoritative over mouse movement.
            eventBus.post(new AccelerometerEvent(x, y));
            tiltActive = true;
        } else if (tiltActive) {
            // Post center once, then leave the idle controller quiet so mouse tilt remains
            // available as an alternative input.
            eventBus.post(new AccelerometerEvent(0, 0));
            tiltActive = false;
        }
    }

    static double normalizeTiltAxis(int raw) {
        int magnitude = Math.abs(raw);
        if (magnitude <= TILT_DEAD_ZONE) {
            return 0;
        }
        double normalized = (double) (magnitude - TILT_DEAD_ZONE) / (32767 - TILT_DEAD_ZONE);
        return Math.copySign(Math.min(1, normalized), raw);
    }

    private boolean isDown(int button) {
        return SDL_GameControllerGetButton(controller, button) != 0;
    }

    private void update(Button button, boolean down) {
        if (down && pressed.add(button)) {
            eventBus.post(new ButtonPressEvent(button));
        } else if (!down && pressed.remove(button)) {
            eventBus.post(new ButtonReleaseEvent(button));
        }
    }

    private void releaseAll() {
        for (Button button : pressed) {
            eventBus.post(new ButtonReleaseEvent(button));
        }
        pressed.clear();
        if (tiltActive) {
            eventBus.post(new AccelerometerEvent(0, 0));
            tiltActive = false;
        }
    }

    private void applyRumble() {
        boolean requested = rumbleRequested;
        if (requested == rumbleActive) {
            return;
        }
        rumbleActive = requested;
        if (SDL_GameControllerHasRumble(controller)) {
            // the cart holds the motor line for as long as it rumbles; refresh with a
            // generous duration and cut it on the off transition
            SDL_GameControllerRumble(controller,
                    (short) (requested ? 0xffff : 0), (short) (requested ? 0xffff : 0),
                    requested ? 60_000 : 0);
        }
    }
}
