package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.memory.cart.type.Mbc5;
import io.github.libsdl4j.api.gamecontroller.SDL_GameController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

import static io.github.libsdl4j.api.Sdl.SDL_Init;
import static io.github.libsdl4j.api.SdlSubSystemConst.SDL_INIT_GAMECONTROLLER;
import static io.github.libsdl4j.api.error.SdlError.SDL_GetError;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerAxis.SDL_CONTROLLER_AXIS_LEFTX;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerAxis.SDL_CONTROLLER_AXIS_LEFTY;
import static io.github.libsdl4j.api.gamecontroller.SDL_GameControllerButton.*;
import static io.github.libsdl4j.api.gamecontroller.SdlGamecontroller.*;
import static io.github.libsdl4j.api.joystick.SdlJoystick.SDL_NumJoysticks;

/**
 * Game-controller input via SDL2 (issue #78): any pad SDL recognizes - PS3/PS4/PS5,
 * Xbox, Switch Pro, generic HID - works out of the box through SDL's built-in mapping
 * database. The first connected controller drives the joypad: d-pad or left stick for
 * the directions, A/B for the buttons, Start, and Back/Select for select. Hotplug is
 * handled by rescanning while no controller is open.
 *
 * <p>The MBC5 rumble carts' motor (issue #93) is forwarded to the controller's force
 * feedback when it has any.
 */
public class SwingGamepad implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(SwingGamepad.class);

    private static final int POLL_MS = 8;

    // half of the axis range: the left stick acts as a digital d-pad past this deflection
    private static final int AXIS_THRESHOLD = 16384;

    private final EventBus eventBus;

    private volatile boolean doStop;

    // written by the event bus, applied on the SDL thread
    private volatile boolean rumbleRequested;

    private SDL_GameController controller;

    private final Set<Button> pressed = EnumSet.noneOf(Button.class);

    private boolean rumbleActive;

    public SwingGamepad(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.register(e -> rumbleRequested = e.on(), Mbc5.RumbleEvent.class);
    }

    public void stop() {
        doStop = true;
    }

    @Override
    public void run() {
        try {
            if (SDL_Init(SDL_INIT_GAMECONTROLLER) != 0) {
                LOG.info("Game controllers unavailable: {}", SDL_GetError());
                return;
            }
        } catch (Throwable e) {
            // no SDL native for this platform - keyboard input still works
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
