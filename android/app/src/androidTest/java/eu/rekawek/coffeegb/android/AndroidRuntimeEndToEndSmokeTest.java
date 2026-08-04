package eu.rekawek.coffeegb.android;

import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.state.StateOperation;
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent;
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent;
import eu.rekawek.coffeegb.controller.state.StateRef;
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent;
import eu.rekawek.coffeegb.core.events.EventBus;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** End-to-end content-URI, frame, input, state, and lifecycle smoke for the Android runtime. */
@RunWith(AndroidJUnit4.class)
public class AndroidRuntimeEndToEndSmokeTest {

    // GitHub's API-26 emulator runs without KVM. State serialization may take longer than the
    // ordinary UI/lifecycle checks, so this remains a functional—not timing—test.
    private static final long TIMEOUT_MILLIS = 180_000L;
    private static final long STATE_REQUEST_TIMEOUT_MILLIS = 60_000L;

    @Test
    public void playsGeneratedContentFixtureThroughSaveAndLifecycleTransitions() throws Exception {
        try (AndroidEmulationRuntime runtime = new AndroidEmulationRuntime(
                InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            await("runtime initialization", () -> runtime.state().phase() == RuntimeState.Phase.STOPPED);
            assertFixtureReadable();
            AtomicReference<Controller.LoadRomFailedEvent> loadFailure = new AtomicReference<>();
            EventBus events = runtimeEvents(runtime);
            events.register(loadFailure::set, Controller.LoadRomFailedEvent.class);
            CountDownLatch stateSaved = new CountDownLatch(1);
            CountDownLatch stateRestored = new CountDownLatch(1);
            CountDownLatch stateSaveRequested = new CountDownLatch(1);
            AtomicReference<StateOperationFailedEvent> stateFailure = new AtomicReference<>();
            events.register(event -> {
                if (event.getRef() instanceof StateRef.Slot
                        && ((StateRef.Slot) event.getRef()).getIndex() == 0) {
                    stateSaveRequested.countDown();
                }
            }, StateSaveRequestEvent.class);
            events.register(event -> {
                if (!(event.getRef() instanceof StateRef.Slot)
                        || ((StateRef.Slot) event.getRef()).getIndex() != 0) {
                    return;
                }
                if (event.getOperation() == StateOperation.SAVE) {
                    stateSaved.countDown();
                } else if (event.getOperation() == StateOperation.LOAD) {
                    stateRestored.countDown();
                }
            }, StateOperationCompletedEvent.class);
            events.register(event -> {
                if (event.getOperation() == StateOperation.SAVE
                        || event.getOperation() == StateOperation.LOAD) {
                    stateFailure.compareAndSet(null, event);
                    stateSaved.countDown();
                    stateRestored.countDown();
                }
            }, StateOperationFailedEvent.class);
            runtime.openRom(FixtureRomProvider.URI, 0);
            awaitFixtureStart(runtime, loadFailure);

            // GitHub-hosted emulators are not a performance target. A valid native frame before
            // input, after input, and after state restoration proves the rendering path without
            // coupling this smoke test to the runner's callback cadence.
            assertFrame(runtime, "before input");
            runtime.input().onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
            runtime.input().onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A));
            assertFrame(runtime, "after input");

            runtime.saveSnapshot(0);
            assertTrue("state save request", stateSaveRequested.await(
                    STATE_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            awaitStateOperation("state save", stateSaved, stateFailure);
            awaitSavedState(runtime, 0);
            runtime.restoreSnapshot(0);
            awaitStateOperation("state restore", stateRestored, stateFailure);
            assertFrame(runtime, "after state restore");

            runtime.onHostNotVisible();
            await("background pause", () -> runtime.state().phase() == RuntimeState.Phase.PAUSED);
            runtime.onHostVisible();
            runtime.resume();
            await("foreground resume", () -> runtime.state().phase() == RuntimeState.Phase.RUNNING);
            runtime.stop();
            await("runtime stop", () -> runtime.state().phase() == RuntimeState.Phase.STOPPED);
        }
    }

    private static void assertFixtureReadable() throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getContentResolver().openInputStream(FixtureRomProvider.URI)) {
            assertNotNull("fixture content URI stream", input);
            input.skip(0x100L);
            assertEquals("fixture entry instruction", 0xc3, input.read());
        }
    }

    private static EventBus runtimeEvents(AndroidEmulationRuntime runtime) throws Exception {
        Field field = AndroidEmulationRuntime.class.getDeclaredField("eventBus");
        field.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        EventBus events;
        while ((events = (EventBus) field.get(runtime)) == null
                && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L);
        }
        assertNotNull("runtime event bus", events);
        return events;
    }

    private static void awaitFixtureStart(
            AndroidEmulationRuntime runtime,
            AtomicReference<Controller.LoadRomFailedEvent> loadFailure) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            RuntimeState state = runtime.state();
            if (state.phase() == RuntimeState.Phase.RUNNING) {
                return;
            }
            if (state.phase() == RuntimeState.Phase.FAILED) {
                Controller.LoadRomFailedEvent failure = loadFailure.get();
                String detail = failure == null
                        ? state.message()
                        : failure.getKind() + ": " + failure.getTechnicalDetails();
                fail("fixture start failed: " + detail);
            }
            Thread.sleep(50L);
        }
        fail("timed out waiting for fixture start: " + runtime.state().message());
    }

    private static void assertFrame(AndroidEmulationRuntime runtime, String checkpoint) throws Exception {
        NativeFrameStore.Frame frame = awaitValue(runtime.frames()::takeLatest, checkpoint);
        try {
            assertNotNull(frame);
            assertEquals(160, frame.width());
            assertEquals(144, frame.height());
        } finally {
            runtime.frames().finishDrawing(frame);
        }
    }

    private static void awaitStateOperation(
            String operation,
            CountDownLatch completed,
            AtomicReference<StateOperationFailedEvent> failure) throws Exception {
        assertTrue(operation + " event", completed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        StateOperationFailedEvent stateFailure = failure.get();
        if (stateFailure != null) {
            fail(operation + " failed: " + stateFailure.getError().getDetail());
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
            java.util.function.Supplier<NativeFrameStore.Frame> supplier, String checkpoint) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        NativeFrameStore.Frame value;
        while ((value = supplier.get()) == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L);
        }
        if (value == null) {
            fail("timed out waiting for a rendered frame " + checkpoint);
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
