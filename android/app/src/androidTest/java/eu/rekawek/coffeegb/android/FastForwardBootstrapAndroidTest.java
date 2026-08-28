package eu.rekawek.coffeegb.android;

import android.net.Uri;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/** Device-level coverage for the release UI's fast-forward bootstrap path. */
@RunWith(AndroidJUnit4.class)
public class FastForwardBootstrapAndroidTest {

    private static final long TIMEOUT_MILLIS = 180_000L;

    @Test
    public void fastForwardReachesCartridgeForDmgCgbAndSgbGames() throws Exception {
        assertFastForwardStarts("dmg", null, FixtureRomProvider.URI,
                HardwareProfileRegistry.DMG);
        assertFastForwardStarts(null, "cgb", FixtureRomProvider.SECOND_URI,
                HardwareProfileRegistry.CGB);
        assertFastForwardStarts("sgb", null, FixtureRomProvider.SGB_URI,
                HardwareProfileRegistry.SGB);
    }

    private static void assertFastForwardStarts(
            String dmgProfile,
            String cgbProfile,
            Uri uri,
            HardwareProfile expectedProfile) throws Exception {
        try (AndroidEmulationRuntime runtime = new AndroidEmulationRuntime(
                InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            awaitStopped(runtime);
            if (dmgProfile != null) {
                runtime.setSystemSelection("dmg-games", dmgProfile);
            }
            if (cgbProfile != null) {
                runtime.setSystemSelection("cgb-games", cgbProfile);
            }
            runtime.setSystemSelection("bootstrap", "fast-forward");

            EventBus events = runtimeEvents(runtime);
            LinkedBlockingQueue<Controller.HardwareProfileEvent> profiles =
                    new LinkedBlockingQueue<>();
            AtomicReference<Boolean> bootstrapReadyAtProfile = new AtomicReference<>();
            AtomicReference<Controller.LoadRomFailedEvent> failure = new AtomicReference<>();
            events.register(event -> {
                bootstrapReadyAtProfile.set(runtimeGameboy(runtime).isBootstrapReady());
                profiles.add(event);
            }, Controller.HardwareProfileEvent.class);
            events.register(failure::set, Controller.LoadRomFailedEvent.class);

            runtime.openRom(uri, 0);
            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (runtime.state().phase() == RuntimeState.Phase.RUNNING) {
                    Controller.HardwareProfileEvent profile = profiles.poll(
                            5, TimeUnit.SECONDS);
                    assertNotNull("hardware profile event", profile);
                    assertEquals(expectedProfile, profile.getProfile());
                    assertEquals("bootstrap readiness at hardware profile event", Boolean.TRUE,
                            bootstrapReadyAtProfile.get());
                    assertEquals(BootstrapMode.FAST_FORWARD,
                            runtimeProperties(runtime).getSystem().getBootstrapMode());
                    return;
                }
                if (runtime.state().phase() == RuntimeState.Phase.FAILED) {
                    Controller.LoadRomFailedEvent loadFailure = failure.get();
                    fail(loadFailure == null ? runtime.state().message()
                            : loadFailure.getKind() + ": " + loadFailure.getTechnicalDetails());
                }
                Thread.sleep(50L);
            }
            fail("timed out waiting for fast-forward bootstrap: " + runtime.state().message());
        }
    }

    private static void awaitStopped(AndroidEmulationRuntime runtime) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (runtime.state().phase() != RuntimeState.Phase.STOPPED
                && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L);
        }
        assertEquals(RuntimeState.Phase.STOPPED, runtime.state().phase());
    }

    private static EventBus runtimeEvents(AndroidEmulationRuntime runtime) throws Exception {
        Field field = AndroidEmulationRuntime.class.getDeclaredField("eventBus");
        field.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            EventBus events = (EventBus) field.get(runtime);
            if (events != null) {
                return events;
            }
            Thread.sleep(50L);
        }
        fail("timed out waiting for the Android controller event bus");
        return null;
    }

    private static Gameboy runtimeGameboy(AndroidEmulationRuntime runtime) {
        try {
            Field controllerField = AndroidEmulationRuntime.class.getDeclaredField("controller");
            controllerField.setAccessible(true);
            Object controller = controllerField.get(runtime);
            assertNotNull("Android controller", controller);

            Field sessionField = controller.getClass().getDeclaredField("session");
            sessionField.setAccessible(true);
            Object session = sessionField.get(controller);
            assertNotNull("controller session", session);

            Field gameboyField = session.getClass().getDeclaredField("gameboy");
            gameboyField.setAccessible(true);
            return (Gameboy) gameboyField.get(session);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not inspect Android bootstrap readiness", failure);
        }
    }

    private static eu.rekawek.coffeegb.controller.properties.EmulatorProperties runtimeProperties(
            AndroidEmulationRuntime runtime) throws Exception {
        Field field = AndroidEmulationRuntime.class.getDeclaredField("properties");
        field.setAccessible(true);
        return (eu.rekawek.coffeegb.controller.properties.EmulatorProperties) field.get(runtime);
    }
}
