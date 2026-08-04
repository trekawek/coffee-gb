package eu.rekawek.coffeegb.android;

import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** End-to-end content-URI, frame, input, state, and lifecycle smoke for the Android runtime. */
@RunWith(AndroidJUnit4.class)
public class AndroidRuntimeEndToEndSmokeTest {

    private static final long TIMEOUT_MILLIS = 20_000L;

    @Test
    public void playsGeneratedContentFixtureThroughSaveAndLifecycleTransitions() throws Exception {
        try (AndroidEmulationRuntime runtime = new AndroidEmulationRuntime(
                InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            await("runtime initialization", () -> runtime.state().phase() == RuntimeState.Phase.STOPPED);
            runtime.openRom(FixtureRomProvider.URI, 0);
            await("fixture start", () -> runtime.state().phase() == RuntimeState.Phase.RUNNING);

            awaitFrames(runtime, 600);
            runtime.input().onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
            runtime.input().onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A));
            assertFrame(runtime);

            runtime.saveSnapshot(0);
            awaitSavedState(runtime, 0);
            runtime.restoreSnapshot(0);
            assertFrame(runtime);

            runtime.onHostNotVisible();
            await("background pause", () -> runtime.state().phase() == RuntimeState.Phase.PAUSED);
            runtime.onHostVisible();
            runtime.resume();
            await("foreground resume", () -> runtime.state().phase() == RuntimeState.Phase.RUNNING);
            runtime.stop();
            await("runtime stop", () -> runtime.state().phase() == RuntimeState.Phase.STOPPED);
        }
    }

    private static void awaitFrames(AndroidEmulationRuntime runtime, int expected) throws Exception {
        CountDownLatch frames = new CountDownLatch(expected);
        NativeFrameStore.Listener listener = frames::countDown;
        runtime.frames().addListener(listener);
        try {
            assertTrue("expected " + expected + " rendered frames",
                    frames.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } finally {
            runtime.frames().removeListener(listener);
        }
    }

    private static void assertFrame(AndroidEmulationRuntime runtime) throws Exception {
        NativeFrameStore.Frame frame = awaitValue(runtime.frames()::takeLatest);
        try {
            assertNotNull(frame);
            assertEquals(160, frame.width());
            assertEquals(144, frame.height());
        } finally {
            runtime.frames().finishDrawing(frame);
        }
    }

    private static AndroidStateSlot stateSlot(AndroidEmulationRuntime runtime, int index) throws Exception {
        CountDownLatch callback = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        List<AndroidStateSlot>[] result = new List[1];
        runtime.listStateSlots(slots -> {
            result[0] = slots;
            callback.countDown();
        });
        assertTrue("state catalog callback", callback.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        assertNotNull(result[0]);
        return result[0].stream().filter(slot -> slot.index() == index).findFirst()
                .orElseThrow(() -> new AssertionError("missing state slot " + index));
    }

    private static void awaitSavedState(AndroidEmulationRuntime runtime, int index) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stateSlot(runtime, index).loadable()) {
                return;
            }
            Thread.sleep(50L);
        }
        fail("timed out waiting for state slot " + index + " to become loadable");
    }

    private static NativeFrameStore.Frame awaitValue(
            java.util.function.Supplier<NativeFrameStore.Frame> supplier) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        NativeFrameStore.Frame value;
        while ((value = supplier.get()) == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L);
        }
        if (value == null) {
            fail("timed out waiting for a rendered frame");
        }
        return value;
    }

    private static void await(String action, BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue("timed out waiting for " + action, condition.getAsBoolean());
    }
}
