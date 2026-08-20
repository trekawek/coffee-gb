package eu.rekawek.coffeegb.android;

import android.view.InputDevice;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AndroidInputRouterTest {

    @Test
    public void touchPointersMergeChordsAndReleasingOnePointerKeepsTheOtherHeld() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            router.updateTouchPointer(4, List.of(Button.LEFT, Button.UP));
            router.updateTouchPointer(9, List.of(Button.A));

            assertEquals(java.util.Set.of(Button.LEFT, Button.UP, Button.A), hub.sample().buttons(0));

            router.releaseTouchPointer(4);

            assertEquals(java.util.Set.of(Button.A), hub.sample().buttons(0));
        } finally {
            router.close();
        }
    }

    @Test
    public void releaseAllClearsEveryTouchAndControllerSourceWithoutStuckButtons() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            router.updateTouchPointer(1, List.of(Button.B, Button.START));
            router.updateTouchPointer(2, List.of(Button.RIGHT));

            router.releaseAll();

            assertTrue(hub.sample().buttons(0).isEmpty());
        } finally {
            router.close();
        }
    }

    @Test
    public void benchmarkLockRejectsInputBeforeArmAndIsIdempotent() {
        PlayerInputHub hub = new PlayerInputHub();
        AtomicInteger mutations = new AtomicInteger();
        AndroidInputRouter router = new AndroidInputRouter(hub, null, mutations::incrementAndGet);
        try {
            router.updateTouchPointer(1, List.of(Button.A));
            assertEquals(java.util.Set.of(Button.A), hub.sample().buttons(0));

            router.lockBenchmarkWindow();
            router.lockBenchmarkWindow();
            router.updateTouchPointer(1, List.of(Button.B));

            assertTrue(hub.sample().buttons(0).isEmpty());
            assertEquals(1, mutations.get());
        } finally {
            router.close();
        }
    }

    @Test
    public void onlyPhysicalGamepadOrJoystickQualifiesForConfiguration() {
        assertTrue(AndroidInputRouter.isConfigurableControllerSources(
                InputDevice.SOURCE_GAMEPAD, false));
        assertTrue(AndroidInputRouter.isConfigurableControllerSources(
                InputDevice.SOURCE_JOYSTICK, false));
        org.junit.Assert.assertFalse(AndroidInputRouter.isConfigurableControllerSources(
                InputDevice.SOURCE_DPAD, false));
        org.junit.Assert.assertFalse(AndroidInputRouter.isConfigurableControllerSources(
                InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_DPAD, true));
    }
}
