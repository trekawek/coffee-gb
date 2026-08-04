package eu.rekawek.coffeegb.android;

import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.events.EventBus;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.lang.reflect.Field;
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

    private static final long TIMEOUT_MILLIS = 20_000L;

    @Test
    public void playsGeneratedContentFixtureThroughSaveAndLifecycleTransitions() throws Exception {
        try (AndroidEmulationRuntime runtime = new AndroidEmulationRuntime(
                InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            await("runtime initialization", () -> runtime.state().phase() == RuntimeState.Phase.STOPPED);
            assertFixtureReadable();
            AtomicReference<Controller.LoadRomFailedEvent> loadFailure = new AtomicReference<>();
            EventBus events = runtimeEvents(runtime);
            events.register(loadFailure::set, Controller.LoadRomFailedEvent.class);
            CountDownLatch snapshotSaved = new CountDownLatch(1);
            AtomicReference<Controller.SnapshotSaveFailedEvent> snapshotSaveFailure =
                    new AtomicReference<>();
            events.register(event -> {
                if (event.getSlot() == 0) {
                    snapshotSaved.countDown();
                }
            }, Controller.SnapshotSavedEvent.class);
            events.register(event -> {
                if (event.getSlot() == 0) {
                    snapshotSaveFailure.set(event);
                    snapshotSaved.countDown();
                }
            }, Controller.SnapshotSaveFailedEvent.class);
            CountDownLatch snapshotRestored = new CountDownLatch(1);
            AtomicReference<Controller.SnapshotLoadFailedEvent> snapshotLoadFailure =
                    new AtomicReference<>();
            events.register(event -> {
                if (event.getSlot() == 0) {
                    snapshotRestored.countDown();
                }
            }, Controller.SnapshotRestoredEvent.class);
            events.register(event -> {
                if (event.getSlot() == 0) {
                    snapshotLoadFailure.set(event);
                    snapshotRestored.countDown();
                }
            }, Controller.SnapshotLoadFailedEvent.class);
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
            awaitSnapshotSaved(snapshotSaved, snapshotSaveFailure);
            runtime.restoreSnapshot(0);
            awaitSnapshotRestored(snapshotRestored, snapshotLoadFailure);
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

    private static void awaitSnapshotSaved(
            CountDownLatch completed,
            AtomicReference<Controller.SnapshotSaveFailedEvent> failure) throws Exception {
        assertTrue("snapshot save event", completed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        Controller.SnapshotSaveFailedEvent saveFailure = failure.get();
        if (saveFailure != null) {
            fail("snapshot save failed: " + saveFailure.getMessage());
        }
    }

    private static void awaitSnapshotRestored(
            CountDownLatch completed,
            AtomicReference<Controller.SnapshotLoadFailedEvent> failure) throws Exception {
        assertTrue("snapshot restore event", completed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        Controller.SnapshotLoadFailedEvent loadFailure = failure.get();
        if (loadFailure != null) {
            fail("snapshot restore failed: " + loadFailure.getMessage());
        }
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
