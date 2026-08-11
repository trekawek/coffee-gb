package eu.rekawek.coffeegb.android;

import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the bound runtime through Activity recreation and a visibility transition. */
@RunWith(AndroidJUnit4.class)
public class MainActivitySmokeTest {

    @Test
    public void launchesRecreatesAndBackgroundsWithoutStartingACameraOrGame() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
        }
    }

    @Test
    public void toggleMenuAdvancesGenerationBeforeQueuedStateDelivery() throws Exception {
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("fixture running", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING);

            scenario.onActivity(activity -> {
                AndroidEmulationRuntime active = runtime(activity);
                RuntimeState original = active.state();
                long observedGeneration = longField(activity, "observedGeneration");
                long currentGeneration = Math.max(observedGeneration, original.generation()) + 2L;
                RuntimeState current = new RuntimeState(RuntimeState.Phase.PAUSED,
                        "newest runtime snapshot", List.of(), true, true, false,
                        currentGeneration);
                RuntimeState queued = new RuntimeState(RuntimeState.Phase.STOPPED,
                        "older queued snapshot", List.of(), false, false, false,
                        currentGeneration - 1L);
                try {
                    setRuntimeState(active, current);
                    menuButton(activity).performClick();
                    activity.onStateChanged(queued);
                    assertEquals(currentGeneration, longField(activity, "observedGeneration"));
                    assertEquals(RuntimeState.Phase.PAUSED, observedState(activity).phase());
                } finally {
                    setRuntimeState(active, original);
                }
            });
        } finally {
            if (runtime.get() != null) {
                runtime.get().stop();
            }
        }
    }

    @Test
    public void systemBackClosesRootMenuBeforeFinishingActivity() throws IOException {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Button menu = menuButton(activity);
                assertEquals("Open Coffee GB menu", menu.getContentDescription().toString());
                menu.performClick();
                assertEquals("Close Coffee GB menu", menu.getContentDescription().toString());
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());

            sendSystemBack(instrumentation);

            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertFalse(activity.isDestroyed());
                assertEquals("Open Coffee GB menu",
                        menuButton(activity).getContentDescription().toString());
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());

            sendSystemBack(instrumentation);
            waitForState(scenario, Lifecycle.State.DESTROYED, instrumentation);
        }
    }

    @Test
    public void audioCancelAndSaveAreDraftedAndNestedSystemBackPreservesActivity()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.getPreferences(MainActivity.MODE_PRIVATE)
                    .edit().clear().commit());
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("fixture running", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING);

            scenario.onActivity(activity -> menuButton(activity).performClick());
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.PAUSE_CONSOLE);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            moveFocusTo(scenario, instrumentation, "audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.AUDIO);

            press(instrumentation, KeyEvent.KEYCODE_DPAD_LEFT, 2);
            moveFocusTo(scenario, instrumentation, "mute-audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            moveFocusTo(scenario, instrumentation, "cancel-audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            scenario.onActivity(activity -> {
                assertEquals(100, activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getInt("audio.volume", 100));
                assertFalse(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("audio.muted", false));
            });

            moveFocusTo(scenario, instrumentation, "audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.AUDIO);
            press(instrumentation, KeyEvent.KEYCODE_DPAD_LEFT, 1);
            moveFocusTo(scenario, instrumentation, "mute-audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            moveFocusTo(scenario, instrumentation, "save-audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            scenario.onActivity(activity -> {
                assertEquals(95, activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getInt("audio.volume", 100));
                assertTrue(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("audio.muted", false));
            });

            sendSystemBack(instrumentation);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.PAUSE_CONSOLE);
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            sendSystemBack(instrumentation);
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertEquals("Open Coffee GB menu",
                        menuButton(activity).getContentDescription().toString());
            });
        } finally {
            if (runtime.get() != null) {
                runtime.get().stop();
            }
        }
    }

    @Test
    public void canceledNativePickerRestoresNestedDataFocusAcrossRecreation() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("fixture running", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING);

            scenario.onActivity(activity -> menuButton(activity).performClick());
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.PAUSE_CONSOLE);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            moveFocusTo(scenario, instrumentation, "data-media");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.DATA_MEDIA);
            moveFocusTo(scenario, instrumentation, "import-battery");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.CONFIRM_ACTION);
            moveFocusTo(scenario, instrumentation, "confirm");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);

            await("native picker resumed", () -> externalSurfaceActive(scenario)
                    && nativePickerResumed(instrumentation));
            sendSystemBack(instrumentation);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.DATA_MEDIA);
            awaitFocused(scenario, "import-battery");
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));

            scenario.recreate();
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.DATA_MEDIA);
            awaitFocused(scenario, "import-battery");
            scenario.onActivity(activity -> {
                long latestGeneration = longField(activity, "observedGeneration");
                assertTrue("fixture runtime has published state", latestGeneration > 0L);

                activity.onStateChanged(new RuntimeState(RuntimeState.Phase.STOPPED,
                        "stale observer delivery", List.of(), false, false, false,
                        latestGeneration - 1L));
                assertEquals("import-battery", focusedItemId(activity));

                activity.onStateChanged(new RuntimeState(RuntimeState.Phase.PAUSED,
                        "background flush", List.of(), true, true, true,
                        latestGeneration + 1L));
                activity.onStateChanged(new RuntimeState(RuntimeState.Phase.PAUSED,
                        "background flush complete", List.of(), true, true, false,
                        latestGeneration + 2L));
                assertEquals("import-battery", focusedItemId(activity));
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        } finally {
            if (runtime.get() != null) {
                runtime.get().stop();
            }
        }
    }

    @Test
    public void nestedAboutRouteAndFocusSurviveBackgroundRecreation() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("fixture running", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING);

            scenario.onActivity(activity -> menuButton(activity).performClick());
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.PAUSE_CONSOLE);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            moveFocusTo(scenario, instrumentation, "about");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.ABOUT);
            moveFocusTo(scenario, instrumentation, "source-notices");

            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.recreate();
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.ABOUT);
            assertFocused(scenario, "source-notices");
        } finally {
            if (runtime.get() != null) {
                runtime.get().stop();
            }
        }
    }

    @Test
    public void deniedCameraPermissionUsesOneOwnerAndStatusSurvivesRecreation()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        runShell(instrumentation,
                "pm revoke eu.rekawek.coffeegb.android android.permission.CAMERA");
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("fixture running", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING);

            scenario.onActivity(activity -> menuButton(activity).performClick());
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.PAUSE_CONSOLE);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            moveFocusTo(scenario, instrumentation, "optional-devices");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.OPTIONAL_DEVICES);
            moveFocusTo(scenario, instrumentation, "live-camera");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            moveFocusTo(scenario, instrumentation, "save-devices");
            scenario.onActivity(activity -> {
                eu.rekawek.coffeegb.android.menu.MenuController controller =
                        menuController(activity);
                controller.onKeyDown(eu.rekawek.coffeegb.android.menu.MenuKey.A, false);
                controller.onKeyUp(eu.rekawek.coffeegb.android.menu.MenuKey.A);
                assertFalse(menuController(activity).visible());
                assertFalse(booleanField(activity, "menuPauseOwned"));
                assertTrue(externalPauseOwned(activity));
            });
            await("native camera permission surface", () ->
                    shellOutput(instrumentation, "dumpsys activity activities")
                            .contains("GrantPermissionsActivity"));
            sendSystemBack(instrumentation);
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            scenario.onActivity(activity -> {
                assertEquals("CAMERA DENIED / DISABLED",
                        stringField(activity, "optionalDevicesStatus"));
                assertFalse(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("devices.camera", true));
            });

            scenario.recreate();
            awaitRoute(scenario, eu.rekawek.coffeegb.android.menu.MenuRoute.SETTINGS);
            scenario.onActivity(activity -> assertEquals("CAMERA DENIED / DISABLED",
                    stringField(activity, "optionalDevicesStatus")));
        } finally {
            if (runtime.get() != null) {
                runtime.get().stop();
            }
        }
    }

    @Test
    public void deliveredDocumentMetadataSurvivesSavedStateBeforeServiceRebind()
            throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Class<?> type = Class.forName(MainActivity.class.getName()
                            + "$PendingDocumentResult");
                    java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(
                            eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState.Action.class,
                            int.class, Uri.class, int.class);
                    constructor.setAccessible(true);
                    Object pending = constructor.newInstance(
                            eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState.Action
                                    .EXPORT_STATE_0,
                            5, FixtureRomProvider.URI, 0x43);
                    Field field = MainActivity.class.getDeclaredField("pendingDocumentResult");
                    field.setAccessible(true);
                    field.set(activity, pending);

                    Bundle saved = new Bundle();
                    activity.onSaveInstanceState(saved);
                    field.set(activity, null);
                    java.lang.reflect.Method restore = MainActivity.class
                            .getDeclaredMethod("restoreActivityState", Bundle.class);
                    restore.setAccessible(true);
                    restore.invoke(activity, saved);

                    Object restored = field.get(activity);
                    assertNotNull(restored);
                    assertEquals("EXPORT_STATE_0",
                            recordValue(type, restored, "action").toString());
                    assertEquals(5, recordValue(type, restored, "requestCode"));
                    assertEquals(FixtureRomProvider.URI,
                            recordValue(type, restored, "uri"));
                    assertEquals(0x43, recordValue(type, restored, "flags"));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            });
        }
    }

    @Test
    public void printerCompletionAfterRecreationIsConsumedByCurrentActivityOnly()
            throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        android.content.SharedPreferences continuation = context.getSharedPreferences(
                "printer-share-continuation", MainActivity.MODE_PRIVATE);
        continuation.edit().clear()
                .putLong("token", 41L)
                .putString("uri", FixtureRomProvider.URI.toString())
                .putString("phase", "PENDING").commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.recreate();
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());

            continuation.edit().putString("phase", "FAILED").apply();
            await("deferred printer failure", () -> {
                AtomicReference<String> status = new AtomicReference<>();
                scenario.onActivity(activity -> status.set(
                        stringField(activity, "printerStatus")));
                return "EXPORT FAILED".equals(status.get());
            });
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
        } finally {
            continuation.edit().clear().commit();
        }
    }

    private static void sendSystemBack(Instrumentation instrumentation) throws IOException {
        runShell(instrumentation, "input keyevent 4");
        instrumentation.waitForIdleSync();
    }

    private static void runShell(Instrumentation instrumentation, String command)
            throws IOException {
        shellOutput(instrumentation, command);
    }

    private static String shellOutput(Instrumentation instrumentation, String command)
            throws IOException {
        ParcelFileDescriptor result = instrumentation.getUiAutomation()
                .executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream stream =
                     new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static boolean nativePickerResumed(Instrumentation instrumentation) throws IOException {
        String activities = shellOutput(instrumentation, "dumpsys activity activities");
        for (String line : activities.split("\\R")) {
            String trimmed = line.trim();
            boolean resumed = trimmed.startsWith("mResumedActivity:")
                    || trimmed.startsWith("mResumedActivity=")
                    || trimmed.startsWith("topResumedActivity=")
                    || trimmed.startsWith("ResumedActivity:");
            if (resumed
                    && (trimmed.contains("com.google.android.documentsui/")
                    || trimmed.contains("com.android.documentsui/"))
                    && trimmed.contains("PickActivity")) {
                return true;
            }
        }
        return false;
    }

    private static Button menuButton(MainActivity activity) {
        Button button = findButton(activity.getWindow().getDecorView());
        assertNotNull("menu button", button);
        return button;
    }

    private static AndroidEmulationRuntime runtime(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("runtime");
            field.setAccessible(true);
            return (AndroidEmulationRuntime) field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static eu.rekawek.coffeegb.android.menu.MenuController menuController(
            MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("menuController");
            field.setAccessible(true);
            return (eu.rekawek.coffeegb.android.menu.MenuController) field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String focusedItemId(MainActivity activity) {
        eu.rekawek.coffeegb.android.menu.MenuStackSnapshot snapshot =
                menuController(activity).snapshot();
        return snapshot.frames().get(snapshot.frames().size() - 1).focusedItemId();
    }

    private static boolean externalSurfaceActive(ActivityScenario<MainActivity> scenario) {
        AtomicReference<Boolean> active = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            try {
                Field field = MainActivity.class.getDeclaredField("externalSurface");
                field.setAccessible(true);
                eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState state =
                        (eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState)
                                field.get(activity);
                active.set(state.active());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
        return active.get();
    }

    private static boolean externalPauseOwned(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("externalSurface");
            field.setAccessible(true);
            return ((eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState)
                    field.get(activity)).pauseOwned();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean booleanField(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static long longField(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getLong(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static RuntimeState observedState(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("observedState");
            field.setAccessible(true);
            return (RuntimeState) field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setRuntimeState(AndroidEmulationRuntime runtime, RuntimeState state) {
        try {
            Field field = AndroidEmulationRuntime.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(runtime, state);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String stringField(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertFocused(ActivityScenario<MainActivity> scenario, String expected) {
        scenario.onActivity(activity -> {
            eu.rekawek.coffeegb.android.menu.MenuStackSnapshot snapshot =
                    menuController(activity).snapshot();
            assertTrue(snapshot.visible());
            assertEquals(expected, snapshot.frames().get(snapshot.frames().size() - 1)
                    .focusedItemId());
        });
    }

    private static void awaitFocused(ActivityScenario<MainActivity> scenario, String expected)
            throws Exception {
        await("focus " + expected, () -> {
            AtomicReference<String> focused = new AtomicReference<>();
            scenario.onActivity(activity -> {
                eu.rekawek.coffeegb.android.menu.MenuStackSnapshot snapshot =
                        menuController(activity).snapshot();
                if (snapshot.visible()) {
                    focused.set(snapshot.frames().get(snapshot.frames().size() - 1)
                            .focusedItemId());
                }
            });
            return expected.equals(focused.get());
        });
    }

    private static void press(Instrumentation instrumentation, int keyCode, int count) {
        for (int index = 0; index < count; index++) {
            instrumentation.sendKeyDownUpSync(keyCode);
            instrumentation.waitForIdleSync();
        }
    }

    private static void moveFocusTo(ActivityScenario<MainActivity> scenario,
            Instrumentation instrumentation, String expectedId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            AtomicReference<String> focused = new AtomicReference<>();
            scenario.onActivity(activity -> {
                eu.rekawek.coffeegb.android.menu.MenuStackSnapshot snapshot =
                        menuController(activity).snapshot();
                if (snapshot.visible()) {
                    focused.set(snapshot.frames().get(snapshot.frames().size() - 1)
                            .focusedItemId());
                }
            });
            if (expectedId.equals(focused.get())) {
                return;
            }
            press(instrumentation, KeyEvent.KEYCODE_DPAD_DOWN, 1);
        }
        throw new AssertionError("could not focus " + expectedId);
    }

    private static void awaitRoute(ActivityScenario<MainActivity> scenario,
            eu.rekawek.coffeegb.android.menu.MenuRoute expected) throws Exception {
        await("route " + expected, () -> {
            AtomicReference<eu.rekawek.coffeegb.android.menu.MenuRoute> route =
                    new AtomicReference<>();
            scenario.onActivity(activity -> route.set(menuController(activity).route()));
            return route.get() == expected;
        });
    }

    private static void await(String action, CheckedCondition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 60_000L;
        while (!condition.get() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25L);
        }
        assertTrue("timed out waiting for " + action, condition.get());
    }

    private static Button findButton(View view) {
        if (view instanceof Button button) {
            return button;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                Button button = findButton(group.getChildAt(index));
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private static void waitForState(ActivityScenario<?> scenario, Lifecycle.State expected,
            Instrumentation instrumentation) {
        long deadline = SystemClock.uptimeMillis() + 3_000L;
        while (scenario.getState() != expected && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            SystemClock.sleep(25L);
        }
        assertEquals(expected, scenario.getState());
    }

    private static Object recordValue(Class<?> type, Object record, String accessor)
            throws ReflectiveOperationException {
        java.lang.reflect.Method method = type.getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean get() throws Exception;
    }
}
