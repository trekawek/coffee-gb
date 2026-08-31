package eu.rekawek.coffeegb.android;

import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.state.StateEntryKey;
import eu.rekawek.coffeegb.controller.state.StateOperation;
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent;
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent;
import eu.rekawek.coffeegb.controller.state.StateRef;
import eu.rekawek.coffeegb.controller.state.StateResumeAvailableEvent;
import eu.rekawek.coffeegb.controller.state.StateResumeDecisionEvent;
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
            CountDownLatch stateSavedMessage = new CountDownLatch(1);
            CountDownLatch stateLoadedMessage = new CountDownLatch(1);
            AtomicReference<StateOperationFailedEvent> stateFailure = new AtomicReference<>();
            runtime.addObserver(new RuntimeObserver() {
                @Override
                public void onStateChanged(RuntimeState state) {
                }

                @Override
                public void onTransientMessage(String message) {
                    if (message.startsWith("State saved")) {
                        stateSavedMessage.countDown();
                    } else if (message.startsWith("State loaded")) {
                        stateLoadedMessage.countDown();
                    }
                }
            });
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
            assertAutosaveOfferAccepted(events);
            runtime.openRom(FixtureRomProvider.URI, 0);
            awaitFixtureStart(runtime, loadFailure);

            // GitHub-hosted emulators are not a performance target. A valid native frame before
            // input, after input, and after state restoration proves the rendering path without
            // coupling this smoke test to the runner's callback cadence.
            assertFrame(runtime, "before input", NativeFrameStore.Presentation.DMG);
            runtime.input().onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
            runtime.input().onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A));
            assertFrame(runtime, "after input", NativeFrameStore.Presentation.DMG);

            runtime.saveSnapshot(0);
            assertTrue("state save request", stateSaveRequested.await(
                    STATE_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            awaitStateOperation("state save", stateSaved, stateFailure);
            assertTrue("state saved flash message", stateSavedMessage.await(
                    STATE_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            awaitSavedState(runtime, 0);
            runtime.restoreSnapshot(0);
            awaitStateOperation("state restore", stateRestored, stateFailure);
            assertTrue("state loaded flash message", stateLoadedMessage.await(
                    STATE_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            assertFrame(runtime, "after state restore", NativeFrameStore.Presentation.DMG);

            // The in-screen menu owns the pause it creates. A successful game choice transfers
            // that ownership to the replacement request instead of leaving the new game paused
            // behind a hidden menu.
            runtime.pause();
            await("pause before ROM replacement",
                    () -> runtime.state().phase() == RuntimeState.Phase.PAUSED);
            long firstGeneration = runtime.state().sessionGeneration();
            runtime.openRom(FixtureRomProvider.SECOND_URI, 0, true);
            await("playing ROM replacement", () -> runtime.state().phase()
                    == RuntimeState.Phase.RUNNING
                    && runtime.state().sessionGeneration() > firstGeneration);
            assertEquals("CI SMOKE CGB", runtime.state().romTitle());
            assertFrame(runtime, "after ROM replacement", NativeFrameStore.Presentation.CGB);

            runtime.pause();
            await("pause before reset",
                    () -> runtime.state().phase() == RuntimeState.Phase.PAUSED);
            long replacementGeneration = runtime.state().sessionGeneration();
            runtime.reset(true);
            await("playing reset", () -> runtime.state().phase() == RuntimeState.Phase.RUNNING
                    && runtime.state().sessionGeneration() > replacementGeneration);
            assertFrame(runtime, "after reset", NativeFrameStore.Presentation.CGB);

            runtime.onHostNotVisible();
            await("background pause", () -> runtime.state().phase() == RuntimeState.Phase.PAUSED);
            runtime.onHostVisible();
            runtime.resume();
            await("foreground resume", () -> runtime.state().phase() == RuntimeState.Phase.RUNNING);
            runtime.stop();
            await("runtime stop", () -> runtime.state().phase() == RuntimeState.Phase.STOPPED);
            assertEquals(NativeFrameStore.Presentation.DMG,
                    runtime.frames().presentation());
        }
    }

    private static void assertAutosaveOfferAccepted(EventBus events) throws Exception {
        long requestId = 8_001L;
        long sessionId = 8_002L;
        CountDownLatch decisionReceived = new CountDownLatch(1);
        AtomicReference<StateResumeDecisionEvent> decision = new AtomicReference<>();
        events.register(event -> {
            if (event.getRequestId() == requestId && event.getExpectedSessionId() == sessionId) {
                decision.set(event);
                decisionReceived.countDown();
            }
        }, StateResumeDecisionEvent.class);

        // The portable controller defaults to ASK. Android has no corresponding prompt, so the
        // runtime must answer the offer or a reopened game remains paused on its first frame.
        StateResumeAvailableEvent offer = new StateResumeAvailableEvent(
                requestId,
                sessionId,
                new StateEntryKey(StateRef.Autosave.INSTANCE, 0),
                null,
                null);
        long deadline = SystemClock.elapsedRealtime() + STATE_REQUEST_TIMEOUT_MILLIS;
        do {
            // The runtime publishes its EventBus before createController has registered every
            // listener. Retry this synthetic probe until owner initialization reaches the Android
            // resume handler; production ROM requests are already serialized behind initialization.
            events.post(offer);
            if (decisionReceived.await(50L, TimeUnit.MILLISECONDS)) {
                break;
            }
        } while (SystemClock.elapsedRealtime() < deadline);

        assertEquals("Android autosave resume decision", 0L, decisionReceived.getCount());
        assertTrue("Android accepts managed autosave", decision.get().getAccept());
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

    private static void assertFrame(AndroidEmulationRuntime runtime, String checkpoint,
            NativeFrameStore.Presentation presentation) throws Exception {
        NativeFrameStore.Frame frame = awaitValue(runtime.frames()::takeLatest, checkpoint);
        try {
            assertNotNull(frame);
            assertEquals(160, frame.width());
            assertEquals(144, frame.height());
            assertEquals(presentation, frame.presentation());
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
