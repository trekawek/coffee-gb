package eu.rekawek.coffeegb.android;

import android.view.InputDevice;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void scenarioSourceIsIsolatedAndClearedByMeasurementLock() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            assertTrue(router.beginBenchmarkScenario());
            router.updateTouchPointer(1, List.of(Button.A));
            assertTrue(hub.sample().buttons(0).isEmpty());

            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.RIGHT_MASK);
            assertEquals(java.util.Set.of(Button.RIGHT), hub.sample().buttons(0));
            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.RIGHT_MASK);
            assertTrue(router.benchmarkScenarioActiveForTesting());

            router.lockBenchmarkWindow();
            assertTrue(hub.sample().buttons(0).isEmpty());
            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.START_MASK);
            assertTrue(hub.sample().buttons(0).isEmpty());
            router.endBenchmarkScenario();
            assertTrue(hub.sample().buttons(0).isEmpty());
        } finally {
            router.close();
        }
    }

    @Test
    public void focusLossReleasePreservesActiveScenarioHold() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            assertTrue(router.beginBenchmarkScenario());
            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.RIGHT_MASK);

            // MainActivity.onWindowFocusChanged(false) delegates to this cleanup.
            router.releaseAll();

            assertEquals(java.util.Set.of(Button.RIGHT), hub.sample().buttons(0));
            assertTrue(router.benchmarkScenarioActiveForTesting());
        } finally {
            router.close();
        }
    }

    @Test
    public void lifecycleReleasePreservesHoldUntilScenarioTransition() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            assertTrue(router.beginBenchmarkScenario());
            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.B_MASK);

            // MainActivity.onStop() may repeat focus cleanup before the next native-frame edge.
            router.releaseAll();
            router.releaseAll();
            assertEquals(java.util.Set.of(Button.B), hub.sample().buttons(0));

            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.NONE_MASK);
            assertTrue(hub.sample().buttons(0).isEmpty());
        } finally {
            router.close();
        }
    }

    @Test
    public void replacementResetsMeasuredLockAndAdmitsANewScenarioSource() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            assertTrue(router.beginBenchmarkScenario());
            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.B_MASK);
            router.endBenchmarkScenario();
            assertTrue(router.benchmarkScenarioSourceClosed());
            assertTrue(router.benchmarkLockedForTesting());
            assertFalse(router.beginBenchmarkScenario());

            router.resetBenchmarkSession();
            assertFalse(router.benchmarkScenarioSourceClosed());
            assertFalse(router.benchmarkLockedForTesting());
            assertTrue(router.beginBenchmarkScenario());
            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.START_MASK);

            assertEquals(java.util.Set.of(Button.START), hub.sample().buttons(0));
            assertFalse(router.benchmarkScenarioSourceClosed());
        } finally {
            router.close();
        }
    }

    @Test
    public void sourceClosureAtomicallyLocksPhysicalInputBeforeArm() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            assertFalse(router.benchmarkScenarioSourceClosed());
            assertTrue(router.beginBenchmarkScenario());
            router.setBenchmarkScenarioMask(BenchmarkGameplayScenario.RIGHT_MASK);

            router.endBenchmarkScenario();
            assertTrue(router.benchmarkScenarioSourceClosed());
            assertTrue(router.benchmarkLockedForTesting());
            assertTrue(hub.sample().buttons(0).isEmpty());

            // Audio drain and compositor anchoring happen after source closure but before ARM.
            router.updateTouchPointer(9, List.of(Button.A));
            assertTrue(hub.sample().buttons(0).isEmpty());
        } finally {
            router.close();
        }
    }

    @Test
    public void failedScenarioAdmissionCannotManufactureSourceClosedProof() {
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        router.close();

        assertFalse(router.beginBenchmarkScenario());
        assertFalse(router.benchmarkScenarioSourceClosed());
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
