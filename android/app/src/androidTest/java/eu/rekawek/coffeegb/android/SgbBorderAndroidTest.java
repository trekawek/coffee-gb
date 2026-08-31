package eu.rekawek.coffeegb.android;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Device-level coverage for live SGB border presentation on Android. */
@RunWith(AndroidJUnit4.class)
public class SgbBorderAndroidTest {

    private static final long TIMEOUT_MILLIS = 10_000L;

    @Test
    public void borderSettingChangesThePublishedSgbFrameGeometry() throws Exception {
        try (AndroidEmulationRuntime runtime = new AndroidEmulationRuntime(
                InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            runtime.setSystemSelection("dmg-games", "sgb");
            runtime.setSystemSelection("bootstrap", "skip");
            runtime.setSgbBorder(true);
            runtime.openRom(FixtureRomProvider.SGB_URI, 0);

            awaitPhase(runtime, RuntimeState.Phase.RUNNING);
            awaitFrameSize(runtime, SuperGameboy.SGB_DISPLAY_WIDTH,
                    SuperGameboy.SGB_DISPLAY_HEIGHT);

            // Recreate the state that used to strand Android with an ON preference and an
            // effectively disabled live display. Reapplying the same UI value must still reach
            // the session rather than being mistaken for a no-op.
            runtimeEvents(runtime).post(new SgbDisplay.SetSgbBorder(false));
            awaitFrameSize(runtime, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT);
            runtime.setSgbBorder(true);
            awaitFrameSize(runtime, SuperGameboy.SGB_DISPLAY_WIDTH,
                    SuperGameboy.SGB_DISPLAY_HEIGHT);

            runtime.setSgbBorder(false);
            awaitFrameSize(runtime, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT);

            runtime.setSgbBorder(true);
            awaitFrameSize(runtime, SuperGameboy.SGB_DISPLAY_WIDTH,
                    SuperGameboy.SGB_DISPLAY_HEIGHT);
        }
    }

    private static EventBus runtimeEvents(AndroidEmulationRuntime runtime) throws Exception {
        Field field = AndroidEmulationRuntime.class.getDeclaredField("eventBus");
        field.setAccessible(true);
        EventBus events = (EventBus) field.get(runtime);
        if (events == null) {
            fail("Android controller event bus is unavailable");
        }
        return events;
    }

    private static void awaitPhase(AndroidEmulationRuntime runtime, RuntimeState.Phase phase)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runtime.state().phase() == phase) {
                return;
            }
            if (runtime.state().phase() == RuntimeState.Phase.FAILED) {
                fail(runtime.state().message());
            }
            Thread.sleep(25L);
        }
        fail("timed out waiting for Android runtime phase " + phase);
    }

    private static void awaitFrameSize(AndroidEmulationRuntime runtime, int width, int height)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            NativeFrameStore.Snapshot frame = runtime.frames().snapshot();
            if (frame != null && frame.width() == width && frame.height() == height) {
                assertEquals(width * height, frame.pixels().length);
                assertEquals(width == SuperGameboy.SGB_DISPLAY_WIDTH
                                ? NativeFrameStore.Presentation.SGB_BORDER
                                : NativeFrameStore.Presentation.DMG,
                        runtime.frames().presentation());
                return;
            }
            Thread.sleep(25L);
        }
        NativeFrameStore.Snapshot frame = runtime.frames().snapshot();
        fail("timed out waiting for " + width + "x" + height + " SGB frame; latest="
                + (frame == null ? "none" : frame.width() + "x" + frame.height()));
    }
}
