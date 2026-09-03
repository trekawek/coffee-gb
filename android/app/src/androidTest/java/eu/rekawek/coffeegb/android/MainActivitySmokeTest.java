package eu.rekawek.coffeegb.android;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the bound runtime through Activity recreation and a visibility transition. */
@RunWith(AndroidJUnit4.class)
public class MainActivitySmokeTest {

    @Test
    public void mbc7OrientationLockSurvivesRecreationResetAndReplacement() throws Exception {
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        AtomicReference<Integer> originalOrientation = new AtomicReference<>();
        List<RuntimeState> transitions = new CopyOnWriteArrayList<>();
        RuntimeObserver transitionObserver = transitions::add;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await("runtime binding", () -> {
                scenario.onActivity(activity -> {
                    runtime.set(runtime(activity));
                    originalOrientation.compareAndSet(null, activity.getRequestedOrientation());
                });
                return runtime.get() != null;
            });
            runtime.get().addObserver(transitionObserver);

            runtime.get().openRom(FixtureRomProvider.TILT_URI, 0);
            await("tilt fixture running", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING
                    && runtime.get().state().tiltOrientationLocked());
            awaitActivityOrientation(scenario, true, ActivityInfo.SCREEN_ORIENTATION_LOCKED);

            scenario.recreate();
            awaitActivityOrientation(scenario, true, ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            // Rebinding after Activity recreation deliberately leaves a live game paused until
            // the user resumes it. Establish a playing session before exercising reset semantics.
            runtime.get().resume();
            await("tilt fixture resumed after recreation", () ->
                    runtime.get().state().phase() == RuntimeState.Phase.RUNNING
                            && runtime.get().state().tiltOrientationLocked());

            long beforeReset = runtime.get().state().sessionGeneration();
            transitions.clear();
            runtime.get().reset();
            await("tilt fixture reset", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING
                    && runtime.get().state().sessionGeneration() > beforeReset);
            awaitActivityOrientation(scenario, true, ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            assertFalse("reset transition must publish at least one presentation state",
                    transitions.isEmpty());
            assertFalse("MBC7 reset must not publish an unlocked presentation state",
                    transitions.stream().anyMatch(state -> !state.tiltOrientationLocked()));

            long beforeSystemReload = runtime.get().state().sessionGeneration();
            transitions.clear();
            runtime.get().setSystemSelection("execution-mode", "accuracy");
            await("tilt fixture reloaded after active system setting", () ->
                    runtime.get().state().phase() == RuntimeState.Phase.RUNNING
                            && runtime.get().state().sessionGeneration() > beforeSystemReload);
            awaitActivityOrientation(scenario, true, ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            assertFalse("system reload must publish at least one presentation state",
                    transitions.isEmpty());
            assertFalse("active MBC7 system reload must not publish an unlocked state",
                    transitions.stream().anyMatch(state -> !state.tiltOrientationLocked()));

            long beforeReplacement = runtime.get().state().sessionGeneration();
            transitions.clear();
            runtime.get().openRom(FixtureRomProvider.TILT_URI, 0);
            await("tilt fixture replaced by tilt fixture", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING
                    && runtime.get().state().sessionGeneration() > beforeReplacement);
            awaitActivityOrientation(scenario, true, ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            assertFalse("replacement must publish at least one presentation state",
                    transitions.isEmpty());
            assertFalse("MBC7 replacement must not publish an unlocked presentation state",
                    transitions.stream().anyMatch(state -> !state.tiltOrientationLocked()));

            long beforeNormal = runtime.get().state().sessionGeneration();
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("tilt fixture replaced by normal fixture", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING
                    && runtime.get().state().sessionGeneration() > beforeNormal
                    && !runtime.get().state().tiltOrientationLocked());
            awaitActivityOrientation(scenario, false, originalOrientation.get());

            long beforeModeRestore = runtime.get().state().sessionGeneration();
            runtime.get().setSystemSelection("execution-mode", "performance");
            await("execution mode restored after orientation test", () ->
                    runtime.get().state().phase() == RuntimeState.Phase.RUNNING
                            && runtime.get().state().sessionGeneration() > beforeModeRestore);
        } finally {
            if (runtime.get() != null) {
                runtime.get().removeObserver(transitionObserver);
                runtime.get().stop();
            }
        }
    }

    private static void awaitActivityOrientation(
            ActivityScenario<MainActivity> scenario, boolean locked, int orientation)
            throws Exception {
        await("Activity orientation presentation", () -> {
            AtomicBoolean matched = new AtomicBoolean();
            scenario.onActivity(activity -> matched.set(
                    observedState(activity).tiltOrientationLocked() == locked
                            && activity.getRequestedOrientation() == orientation));
            return matched.get();
        });
    }

    @Test
    public void romPickerUsesTrustedExtensionFilterWhenTheDeviceProvidesOne() {
        Intent intent = RomPickerIntents.create(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        if (RomPickerIntents.MIUI_FILTERED_PICKER.equals(intent.getComponent())) {
            assertEquals(Intent.ACTION_PICK, intent.getAction());
            assertEquals("all/*", intent.getType());
            assertArrayEquals(RomPickerIntents.SUPPORTED_EXTENSIONS,
                    intent.getStringArrayExtra(RomPickerIntents.MIUI_EXTENSION_FILTER));
        } else {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
            assertEquals("application/octet-stream", intent.getType());
            assertNotNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        }
    }

    @Test
    public void transientPickerRomBecomesAReadablePrivateImport() throws Exception {
        AndroidRomImportStore store = new AndroidRomImportStore(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        Uri imported = store.importDocument(FixtureRomProvider.URI);
        try {
            assertEquals("file", imported.getScheme());
            assertTrue(store.ownsReadable(imported));
            assertTrue(RomDocumentFilter.accepts(imported.getLastPathSegment()));
        } finally {
            store.deleteIfOwned(imported);
        }
    }

    @Test
    public void freshLaunchShowsLibraryAndRestoresItAfterRecreation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitRoute(scenario, MenuRoute.LIBRARY);
            scenario.onActivity(activity -> assertEquals("Close Coffee GB menu",
                    menuButton(activity).getContentDescription().toString()));
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitRoute(scenario, MenuRoute.LIBRARY);
            scenario.recreate();
            awaitRoute(scenario, MenuRoute.LIBRARY);
        }
    }

    @Test
    public void transparentMenuHitTargetHasNoFrameworkVisualsButRemainsAccessibleAndClickable()
            throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitRoute(scenario, MenuRoute.LIBRARY);
            scenario.onActivity(activity -> {
                View menu = menuButton(activity);
                assertTrue(menu.isClickable());
                assertTrue(menu.isFocusable());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES,
                        menu.getImportantForAccessibility());
                assertNotNull(menu.getContentDescription());
                assertNull(menu.getBackground());
                assertNull(menu.getForeground());
                assertNull(menu.getStateListAnimator());
                assertEquals(0.0f, menu.getElevation(), 0.0f);
                assertEquals(0.0f, menu.getTranslationZ(), 0.0f);
                assertFalse(menu.getDefaultFocusHighlightEnabled());

                assertTrue(menu.performClick());
                assertEquals("Close Coffee GB menu", menu.getContentDescription().toString());
                assertTrue(menuController(activity).dispatchBackEdge());
                assertEquals(MenuRoute.LIBRARY, menuController(activity).route());
                assertTrue(menuController(activity).visible());

                menuController(activity).push(MenuRoute.SETTINGS);
                assertTrue(menu.performClick());
                assertEquals(MenuRoute.SETTINGS, menuController(activity).route());
                assertTrue(menuController(activity).visible());
                assertTrue(menuController(activity).dispatchBackEdge());
                assertEquals(MenuRoute.LIBRARY, menuController(activity).route());
            });
        }
    }

    @Test
    public void startingAndRecreatingAnActiveGameShowsTheGameInsteadOfLibrary()
            throws Exception {
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitRoute(scenario, MenuRoute.LIBRARY);
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("fixture game presentation", () -> {
                AtomicBoolean gameVisible = new AtomicBoolean();
                scenario.onActivity(activity -> gameVisible.set(
                        observedState(activity).phase() == RuntimeState.Phase.RUNNING
                                && !menuController(activity).visible()));
                return gameVisible.get();
            });

            scenario.recreate();
            await("recreated fixture game presentation", () -> {
                AtomicBoolean gameVisible = new AtomicBoolean();
                scenario.onActivity(activity -> gameVisible.set(runtime(activity) != null
                        && observedState(activity).transferReady()
                        && !menuController(activity).visible()));
                return gameVisible.get();
            });
        } finally {
            if (runtime.get() != null) {
                runtime.get().stop();
                await("runtime cleanup", () -> runtime.get().state().phase()
                        == RuntimeState.Phase.STOPPED);
            }
        }
    }

    @Test
    public void unavailablePrinterPaperNeverConfirmsAndBackStillReturnsToParent()
            throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MenuController controller = menuController(activity);
                for (MenuPreview preview : List.of(MenuPreview.loading(), MenuPreview.empty())) {
                    setObjectField(activity, "printerPreview", preview);
                    invokeNoArgs(activity, "refreshMenuPages");
                    controller.show(MenuRoute.DATA_MEDIA);
                    controller.push(MenuRoute.PRINTER_PAPER);
                    assertEquals(MenuRoute.PRINTER_PAPER, controller.route());
                    assertEquals("paper-status", focusedItemId(activity));

                    invoke(activity, "handlePrinterPaperItem", new Class<?>[]{String.class},
                            "clear-paper");
                    invoke(activity, "handlePrinterPaperItem", new Class<?>[]{String.class},
                            "export-share-paper");
                    assertNull(objectField(activity, "confirmVariant"));
                    assertEquals(MenuRoute.PRINTER_PAPER, controller.route());

                    assertTrue(controller.dispatchBackEdge());
                    assertEquals(MenuRoute.DATA_MEDIA, controller.route());
                }
            });
        }
    }

    @Test
    public void printerPaperEntryWaitsForReadyPreview() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MenuController controller = menuController(activity);
                controller.show(MenuRoute.DATA_MEDIA);
                setObjectField(activity, "printerPreview", MenuPreview.loading());

                invokeNoArgs(activity, "openPrinterPaper");

                assertEquals(MenuRoute.DATA_MEDIA, controller.route());
                assertNull(objectField(activity, "confirmVariant"));
            });
        }
    }

    @Test
    public void toggleMenuAdvancesGenerationBeforeQueuedStateDelivery() throws Exception {
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        AtomicBoolean ownerDrained = new AtomicBoolean();
        AtomicBoolean cleanupDrained = new AtomicBoolean();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().listStateSlots(ignored -> ownerDrained.set(true));
            await("runtime owner queue", ownerDrained::get);

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
                    assertEquals(currentGeneration,
                            longField(activity, "observedGeneration"));
                    assertEquals(current, observedState(activity));
                    activity.onStateChanged(queued);
                    assertEquals(currentGeneration, longField(activity, "observedGeneration"));
                    assertEquals(current, observedState(activity));
                } finally {
                    setRuntimeState(active, original);
                }
            });
        } finally {
            AndroidEmulationRuntime active = runtime.get();
            if (active != null) {
                active.stop();
                active.listStateSlots(ignored -> cleanupDrained.set(true));
                await("runtime cleanup queue", cleanupDrained::get);
                assertEquals("runtime cleanup phase", RuntimeState.Phase.STOPPED,
                        active.state().phase());
            }
        }
    }

    @Test
    public void systemBackKeepsNoGameRootMenuVisible() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitRoute(scenario, MenuRoute.LIBRARY);
            scenario.onActivity(activity -> {
                View menu = menuButton(activity);
                assertEquals("Close Coffee GB menu", menu.getContentDescription().toString());
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());

            sendSystemBack(instrumentation);

            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertFalse(activity.isDestroyed());
                assertEquals("Close Coffee GB menu",
                        menuButton(activity).getContentDescription().toString());
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());

            sendSystemBack(instrumentation);
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertFalse(activity.isDestroyed());
                assertEquals("Close Coffee GB menu",
                        menuButton(activity).getContentDescription().toString());
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        }
    }

    @Test
    public void audioChangesPersistImmediatelyAndNestedSystemBackPreservesActivity()
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
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.PAUSE_CONSOLE);
            await("fixture paused by the menu", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.PAUSED);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.SETTINGS);
            moveFocusTo(scenario, instrumentation, "audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.AUDIO);

            moveFocusTo(scenario, instrumentation, "volume");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            scenario.onActivity(activity -> assertEquals(100,
                    activity.getPreferences(MainActivity.MODE_PRIVATE)
                            .getInt("audio.volume", 100)));
            press(instrumentation, KeyEvent.KEYCODE_DPAD_LEFT, 2);
            moveFocusTo(scenario, instrumentation, "mute-audio");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            scenario.onActivity(activity -> {
                assertEquals(90, activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getInt("audio.volume", 100));
                assertTrue(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("audio.muted", false));
            });

            sendSystemBack(instrumentation);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.SETTINGS);
            sendSystemBack(instrumentation);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.PAUSE_CONSOLE);
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            sendSystemBack(instrumentation);
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertEquals("Open Coffee GB menu",
                        menuButton(activity).getContentDescription().toString());
            });
            await("pause root B resumes the fixture", () ->
                    runtime.get().state().phase() == RuntimeState.Phase.RUNNING);
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
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.PAUSE_CONSOLE);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.SETTINGS);
            scenario.onActivity(activity -> menuController(activity).show(
                    eu.rekawek.coffeegb.ui.menu.MenuRoute.DATA_MEDIA));
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.DATA_MEDIA);
            moveFocusTo(scenario, instrumentation, "import-battery");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.CONFIRM_ACTION);
            press(instrumentation, KeyEvent.KEYCODE_DPAD_DOWN, 1);
            awaitFocused(scenario, "confirm");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);

            await("native picker input ready", () -> externalSurfaceActive(scenario)
                    && nativePickerInputReady(instrumentation));
            sendSystemBack(instrumentation);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.DATA_MEDIA);
            awaitFocused(scenario, "import-battery");
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));

            scenario.recreate();
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.DATA_MEDIA);
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
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.PAUSE_CONSOLE);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.SETTINGS);
            scenario.onActivity(activity -> menuController(activity).show(
                    eu.rekawek.coffeegb.ui.menu.MenuRoute.ABOUT));
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.ABOUT);
            moveFocusTo(scenario, instrumentation, "source-notices");

            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.recreate();
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.ABOUT);
            assertFocused(scenario, "source-notices");
        } finally {
            if (runtime.get() != null) {
                runtime.get().stop();
            }
        }
    }

    @Test
    public void rumbleControlPersistsAndUpdatesTheBoundRuntimeImmediately() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            scenario.onActivity(activity -> {
                activity.getPreferences(MainActivity.MODE_PRIVATE).edit()
                        .putBoolean("devices.rumble", false).commit();
                runtime(activity).setRumbleEnabled(false);
                menuController(activity).show(MenuRoute.OPTIONAL_DEVICES);
            });
            awaitRoute(scenario, MenuRoute.OPTIONAL_DEVICES);
            awaitFocused(scenario, "rumble");

            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            scenario.onActivity(activity -> {
                assertTrue(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("devices.rumble", false));
                assertTrue(runtime(activity).rumbleEnabledForTesting());
            });

            runtime.set(null);
            scenario.recreate();
            await("runtime rebinding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            awaitRoute(scenario, MenuRoute.OPTIONAL_DEVICES);
            awaitFocused(scenario, "rumble");
            scenario.onActivity(activity -> {
                assertTrue(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("devices.rumble", false));
                assertTrue(runtime(activity).rumbleEnabledForTesting());
            });

            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            scenario.onActivity(activity -> {
                assertFalse(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("devices.rumble", true));
                assertFalse(runtime(activity).rumbleEnabledForTesting());
            });
        }
    }

    @Test
    public void deniedCameraPermissionUsesOneOwnerAndStatusSurvivesRecreation()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        resetCameraPermissionForPrompt(instrumentation);
        AtomicReference<AndroidEmulationRuntime> runtime = new AtomicReference<>();
        StablePermissionReadiness permissionReadiness = new StablePermissionReadiness();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> assertEquals(
                    "camera permission must be denied before requesting it",
                    android.content.pm.PackageManager.PERMISSION_DENIED,
                    activity.checkSelfPermission(android.Manifest.permission.CAMERA)));
            await("runtime binding", () -> {
                scenario.onActivity(activity -> runtime.set(runtime(activity)));
                return runtime.get() != null;
            });
            runtime.get().openRom(FixtureRomProvider.URI, 0);
            await("fixture running", () -> runtime.get().state().phase()
                    == RuntimeState.Phase.RUNNING);
            await("activity observes fixture running", () -> {
                AtomicReference<Boolean> caughtUp = new AtomicReference<>(false);
                scenario.onActivity(activity -> {
                    RuntimeState current = runtime(activity).state();
                    RuntimeState observed = observedState(activity);
                    caughtUp.set(current.phase() == RuntimeState.Phase.RUNNING
                            && observed.phase() == RuntimeState.Phase.RUNNING
                            && observed.generation() >= current.generation());
                });
                return caughtUp.get();
            });

            scenario.onActivity(activity -> menuButton(activity).performClick());
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.PAUSE_CONSOLE);
            moveFocusTo(scenario, instrumentation, "settings");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.SETTINGS);
            scenario.onActivity(activity -> menuController(activity).show(
                    eu.rekawek.coffeegb.ui.menu.MenuRoute.OPTIONAL_DEVICES));
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.OPTIONAL_DEVICES);
            moveFocusTo(scenario, instrumentation, "camera");
            press(instrumentation, KeyEvent.KEYCODE_ENTER, 1);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.OPTION_PICKER);
            moveFocusTo(scenario, instrumentation, "choice:rear");
            scenario.onActivity(activity -> {
                eu.rekawek.coffeegb.ui.menu.MenuController controller =
                        menuController(activity);
                controller.onKeyDown(eu.rekawek.coffeegb.ui.menu.MenuKey.A, false);
                controller.onKeyUp(eu.rekawek.coffeegb.ui.menu.MenuKey.A);
                assertFalse(menuController(activity).visible());
                assertFalse(booleanField(activity, "menuPauseOwned"));
                eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState surface =
                        externalSurface(activity);
                assertTrue("camera permission surface must retain menu pause ownership; "
                                + pauseOwnershipDiagnostic(activity),
                        surface.active() && surface.pauseOwned());
            });
            awaitStableCameraPermissionSurface(instrumentation, permissionReadiness);
            cancelCameraPermissionSurface(instrumentation);
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.OPTIONAL_DEVICES);
            awaitFocused(scenario, "camera");
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            scenario.onActivity(activity -> {
                assertFalse(externalSurface(activity).active());
                assertEquals("CAMERA DENIED / DISABLED",
                        stringField(activity, "optionalDevicesStatus"));
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                        activity.checkSelfPermission(android.Manifest.permission.CAMERA));
                assertFalse(activity.getPreferences(MainActivity.MODE_PRIVATE)
                        .getBoolean("devices.camera", true));
            });

            scenario.recreate();
            awaitRoute(scenario, eu.rekawek.coffeegb.ui.menu.MenuRoute.OPTIONAL_DEVICES);
            scenario.onActivity(activity -> assertEquals("CAMERA DENIED / DISABLED",
                    stringField(activity, "optionalDevicesStatus")));
        } finally {
            try {
                dismissCameraPermissionIfReady(instrumentation);
            } finally {
                if (runtime.get() != null) {
                    runtime.get().stop();
                }
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
                            int.class, Uri.class, int.class, boolean.class);
                    constructor.setAccessible(true);
                    Object pending = constructor.newInstance(
                            eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState.Action
                                    .EXPORT_STATE_0,
                            5, FixtureRomProvider.URI, 0x43, true);
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
                    assertEquals(true, recordValue(type, restored, "releaseMenuPause"));
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
            if (currentResumedActivity(trimmed) && nativePickerActivity(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nativePickerInputReady(Instrumentation instrumentation)
            throws IOException {
        if (!nativePickerResumed(instrumentation)) {
            return false;
        }
        boolean legacy = Build.VERSION.SDK_INT <= Build.VERSION_CODES.O;
        String windows = shellOutput(instrumentation,
                legacy ? "dumpsys window -a" : "dumpsys window windows");
        if (legacy && !appTransitionSnapshot(windows).idle()) {
            return false;
        }
        FocusedWindow windowsCurrentFocus = currentFocusFromWindowDump(windows);
        FocusedWindow currentFocus = windowsCurrentFocus;
        if (!legacy) {
            DisplayFocus displayFocus = displayZeroFocus(shellOutput(instrumentation,
                    "dumpsys window displays"));
            if (displayFocus.currentFocus().present()) {
                if (windowsCurrentFocus.present()
                        && !displayFocus.currentFocus().sameIdentity(windowsCurrentFocus)) {
                    return false;
                }
                currentFocus = displayFocus.currentFocus();
            }
            if (displayFocus.focusedWindow().present()
                    && !displayFocus.focusedWindow().sameIdentityAndTitle(currentFocus)) {
                return false;
            }
        }
        if (!nativePickerActivity(currentFocus.title())
                || !windowBlockState(windows, currentFocus).ready()) {
            return false;
        }
        String input = shellOutput(instrumentation, "dumpsys input");
        InputFocusSignals inputFocusSignals = inputFocusSignals(input, legacy);
        if (!inputFocusSignals.agree()) {
            return false;
        }
        FocusedWindow inputFocus = inputFocusSignals.selected();
        boolean inputIdentityMatches = currentFocus.sameIdentity(inputFocus);
        return nativePickerActivity(inputFocus.title())
                && inputIdentityMatches
                && inputWindowState(input, currentFocus, legacy, inputIdentityMatches).ready();
    }

    private static boolean nativePickerActivity(String line) {
        return (line.contains("com.google.android.documentsui/")
                || line.contains("com.android.documentsui/"))
                && line.contains("PickActivity");
    }

    private static boolean cameraPermissionResumed(Instrumentation instrumentation)
            throws IOException {
        String activities = shellOutput(instrumentation, "dumpsys activity activities");
        for (String line : activities.split("\\R")) {
            String trimmed = line.trim();
            if (currentResumedActivity(trimmed) && cameraPermissionActivity(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static PermissionSurfaceObservation permissionSurfaceObservation(
            Instrumentation instrumentation) throws IOException {
        if (!cameraPermissionResumed(instrumentation)) {
            return PermissionSurfaceObservation.notReady(
                    "no current resumed GrantPermissionsActivity");
        }
        boolean legacyTransitionGate = Build.VERSION.SDK_INT <= Build.VERSION_CODES.O;
        String windows = shellOutput(instrumentation,
                legacyTransitionGate ? "dumpsys window -a" : "dumpsys window windows");
        AppTransitionSnapshot transition = legacyTransitionGate
                ? appTransitionSnapshot(windows) : AppTransitionSnapshot.notRequired();
        FocusedWindow windowsCurrentFocus = currentFocusFromWindowDump(windows);
        if (legacyTransitionGate && !transition.idle()) {
            return PermissionSurfaceObservation.notReady(
                    "legacy app transition=" + transition + ", wmCurrentFocus="
                            + windowsCurrentFocus);
        }
        WindowManagerFocus windowManagerFocus;
        if (legacyTransitionGate) {
            windowManagerFocus = WindowManagerFocus.legacy(windowsCurrentFocus);
        } else {
            DisplayFocus displayFocus = displayZeroFocus(shellOutput(instrumentation,
                    "dumpsys window displays"));
            if (displayFocus.currentFocus().present() && windowsCurrentFocus.present()
                    && !displayFocus.currentFocus().sameIdentity(windowsCurrentFocus)) {
                return PermissionSurfaceObservation.notReady(
                        "display/windows mCurrentFocus disagreement: display=" + displayFocus
                                + ", windows=" + windowsCurrentFocus);
            }
            FocusedWindow currentFocus = displayFocus.currentFocus().present()
                    ? displayFocus.currentFocus() : windowsCurrentFocus;
            if (displayFocus.focusedWindow().present()
                    && !displayFocus.focusedWindow().sameIdentityAndTitle(currentFocus)) {
                return PermissionSurfaceObservation.notReady(
                        "display mFocusedWindow disagreement: current=" + currentFocus
                                + ", focused=" + displayFocus.focusedWindow());
            }
            windowManagerFocus = new WindowManagerFocus(currentFocus, windowsCurrentFocus,
                    displayFocus.currentFocus(), displayFocus.focusedWindow());
        }
        FocusedWindow currentFocus = windowManagerFocus.currentFocus();
        if (!currentFocus.cameraPermission(legacyTransitionGate)) {
            return PermissionSurfaceObservation.notReady(
                    "wmFocus=" + windowManagerFocus + ", expected="
                            + expectedCameraPermissionWindow(legacyTransitionGate));
        }
        WindowBlockState windowState = windowBlockState(windows, currentFocus);
        if (!windowState.ready()) {
            return PermissionSurfaceObservation.notReady(
                    "wmCurrentFocus=" + currentFocus + ", wmWindow=" + windowState);
        }
        String input = shellOutput(instrumentation, "dumpsys input");
        InputFocusSignals inputFocusSignals = inputFocusSignals(input,
                legacyTransitionGate);
        if (!inputFocusSignals.agree()) {
            return PermissionSurfaceObservation.notReady(
                    "input focused-window disagreement=" + inputFocusSignals);
        }
        FocusedWindow inputFocus = inputFocusSignals.selected();
        boolean inputIdentityMatches = currentFocus.sameIdentity(inputFocus);
        InputWindowState inputState = inputWindowState(input, currentFocus,
                legacyTransitionGate, inputIdentityMatches);
        if (!inputFocus.cameraPermission(legacyTransitionGate)
                || !inputIdentityMatches
                || !inputState.ready()) {
            return PermissionSurfaceObservation.notReady(
                    "wmFocus=" + windowManagerFocus + ", inputFocus=" + inputFocusSignals
                            + ", inputWindow=" + inputState + ", expected="
                            + expectedCameraPermissionWindow(legacyTransitionGate));
        }
        PermissionSurfaceSnapshot snapshot = new PermissionSurfaceSnapshot(windowManagerFocus,
                inputFocusSignals, windowState, inputState, transition.nextAppTransition(),
                transition.appTransitionState());
        return PermissionSurfaceObservation.ready(snapshot);
    }

    private static String expectedCameraPermissionWindow(boolean legacy) {
        return legacy ? "com.android.packageinstaller/...GrantPermissionsActivity"
                : "PackageInstaller/PermissionController GrantPermissionsActivity";
    }

    private static boolean cameraPermissionActivity(String line) {
        return (line.contains("com.android.packageinstaller/")
                || line.contains("com.google.android.packageinstaller/")
                || line.contains("com.android.permissioncontroller/")
                || line.contains("com.google.android.permissioncontroller/"))
                && line.contains("GrantPermissionsActivity");
    }

    private static FocusedWindow parseFocusedWindow(String line) {
        int opening = line.indexOf("Window{");
        int closing = opening < 0 ? -1 : line.indexOf('}', opening);
        if (opening >= 0 && closing >= 0) {
            return parseFocusedWindowBody(
                    line.substring(opening + "Window{".length(), closing));
        }
        int nameStart = line.indexOf("name='");
        int nameEnd = nameStart < 0 ? -1 : line.indexOf('\'', nameStart + "name='".length());
        if (nameStart < 0 || nameEnd < 0) {
            return FocusedWindow.none();
        }
        return parseFocusedWindowBody(
                line.substring(nameStart + "name='".length(), nameEnd));
    }

    private static FocusedWindow parseFocusedWindowBody(String value) {
        String body = value.trim();
        int identityEnd = body.indexOf(' ');
        if (identityEnd < 0) {
            return FocusedWindow.none();
        }
        String identity = body.substring(0, identityEnd);
        if (!hexIdentity(identity)) {
            identity = "";
        }
        String title = body.substring(identityEnd + 1).trim();
        int userEnd = title.indexOf(' ');
        if (userEnd > 0 && userToken(title.substring(0, userEnd))) {
            title = title.substring(userEnd + 1).trim();
        }
        return new FocusedWindow(identity, title);
    }

    private static FocusedWindow currentFocusFromWindowDump(String windows) {
        FocusedWindow currentFocus = FocusedWindow.none();
        for (String line : windows.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("mCurrentFocus=")) {
                currentFocus = parseFocusedWindow(trimmed);
            }
        }
        return currentFocus;
    }

    private static DisplayFocus displayZeroFocus(String displays) {
        boolean displayZero = false;
        FocusedWindow currentFocus = FocusedWindow.none();
        FocusedWindow focusedWindow = FocusedWindow.none();
        for (String line : displays.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Display: mDisplayId=")) {
                displayZero = trimmed.equals("Display: mDisplayId=0")
                        || trimmed.startsWith("Display: mDisplayId=0 ");
                continue;
            }
            if (!displayZero) {
                continue;
            }
            if (trimmed.startsWith("mCurrentFocus=")) {
                currentFocus = parseFocusedWindow(trimmed);
            } else if (trimmed.startsWith("mFocusedWindow=")) {
                focusedWindow = parseFocusedWindow(trimmed);
            }
        }
        return new DisplayFocus(currentFocus, focusedWindow);
    }

    private static InputFocusSignals inputFocusSignals(String input, boolean legacy) {
        FocusedWindow singular = FocusedWindow.none();
        for (String line : input.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("FocusedWindow:")) {
                singular = parseFocusedWindow(trimmed);
                break;
            }
        }
        if (legacy) {
            return new InputFocusSignals(FocusedWindow.none(), singular);
        }

        FocusedWindow plural = FocusedWindow.none();
        boolean focusedWindows = false;
        int sectionIndent = -1;
        for (String line : input.split("\\R")) {
            String trimmed = line.trim();
            int indentation = leadingSpaces(line);
            if (!focusedWindows) {
                if (trimmed.equals("FocusedWindows:")) {
                    focusedWindows = true;
                    sectionIndent = indentation;
                }
                continue;
            }
            if (!trimmed.isEmpty() && indentation <= sectionIndent) {
                break;
            }
            if (trimmed.startsWith("displayId=0,") && trimmed.contains("name='")) {
                plural = parseFocusedWindow(trimmed);
            }
        }
        return new InputFocusSignals(plural, singular);
    }

    private static boolean hexIdentity(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            if (!Character.isDigit(character) && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean userToken(String value) {
        if (value.length() < 2 || value.charAt(0) != 'u') {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static AppTransitionSnapshot appTransitionSnapshot(String windows) {
        String nextAppTransition = "";
        String appTransitionState = "";
        boolean layoutToAnim = false;
        for (String line : windows.split("\\R")) {
            if (!layoutToAnim) {
                layoutToAnim = "  mLayoutToAnim:".equals(line);
                continue;
            }
            int indentation = leadingSpaces(line);
            if (indentation <= 2) {
                break;
            }
            if (indentation != 4) {
                continue;
            }
            String field = line.substring(4);
            if (field.startsWith("mNextAppTransition=")) {
                nextAppTransition = field.substring("mNextAppTransition=".length());
            } else if (field.startsWith("mAppTransitionState=")) {
                appTransitionState = field.substring("mAppTransitionState=".length());
            }
        }
        return new AppTransitionSnapshot(nextAppTransition, appTransitionState);
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static WindowBlockState windowBlockState(String windows, FocusedWindow target) {
        if (target.identity().isEmpty()) {
            return WindowBlockState.none();
        }
        boolean matching = false;
        boolean found = false;
        boolean hasSurface = false;
        boolean readyForDisplay = false;
        boolean surfaceShown = false;
        boolean hasDrawn = false;
        for (String line : windows.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Window #")) {
                FocusedWindow candidate = parseFocusedWindow(trimmed);
                matching = target.sameIdentity(candidate)
                        && target.title().equals(candidate.title());
                found |= matching;
                continue;
            }
            if (!matching) {
                continue;
            }
            hasSurface |= trimmed.contains("mHasSurface=true");
            readyForDisplay |= trimmed.contains("isReadyForDisplay()=true");
            surfaceShown |= trimmed.startsWith("Surface:")
                    && trimmed.contains("shown=true");
            hasDrawn |= trimmed.contains("mDrawState=HAS_DRAWN");
        }
        return new WindowBlockState(found, hasSurface, readyForDisplay, surfaceShown, hasDrawn);
    }

    private static InputWindowState inputWindowState(String input, FocusedWindow target,
            boolean legacy, boolean dispatcherFocused) {
        if (!legacy) {
            return modernInputWindowState(input, target, dispatcherFocused);
        }
        if (target.identity().isEmpty()) {
            return InputWindowState.none();
        }
        boolean found = false;
        boolean hasFocus = false;
        boolean canReceiveKeys = false;
        for (String line : input.split("\\R")) {
            String trimmed = line.trim();
            FocusedWindow candidate = parseFocusedWindow(trimmed);
            if (!target.sameIdentity(candidate)) {
                continue;
            }
            found = true;
            hasFocus |= trimmed.contains("hasFocus=true");
            canReceiveKeys |= trimmed.contains("canReceiveKeys=true");
        }
        return new InputWindowState(found, hasFocus, canReceiveKeys, canReceiveKeys,
                canReceiveKeys, true);
    }

    private static InputWindowState modernInputWindowState(String input,
            FocusedWindow target, boolean dispatcherFocused) {
        if (target.identity().isEmpty()) {
            return InputWindowState.none();
        }
        boolean displayZero = false;
        int displayIndent = -1;
        boolean windows = false;
        boolean displayZeroWindows = false;
        int sectionIndent = -1;
        boolean matching = false;
        int entryIndent = -1;
        boolean found = false;
        boolean inputConfigSeen = false;
        boolean visible = true;
        boolean focusable = true;
        boolean hasInputChannel = true;
        boolean dispatchReady = true;
        for (String line : input.split("\\R")) {
            String trimmed = line.trim();
            int indentation = leadingSpaces(line);
            if (trimmed.startsWith("Display: ")) {
                displayZero = trimmed.equals("Display: 0")
                        || trimmed.startsWith("Display: 0 ");
                displayIndent = indentation;
                windows = false;
                continue;
            }
            if (!windows) {
                if (trimmed.equals("Windows:")) {
                    windows = true;
                    sectionIndent = indentation;
                    displayZeroWindows = displayZero && displayIndent >= 0
                            && indentation > displayIndent;
                }
                continue;
            }
            if (!trimmed.isEmpty() && indentation <= sectionIndent) {
                windows = false;
                matching = false;
                continue;
            }
            if (trimmed.contains("name=")) {
                FocusedWindow candidate = parseInputWindowEntry(trimmed);
                if (candidate.present()) {
                    matching = (displayZeroWindows || displayZeroEntry(trimmed))
                            && target.sameIdentityAndTitle(candidate);
                    entryIndent = indentation;
                    found |= matching;
                    if (matching) {
                        inputConfigSeen |= trimmed.contains("inputConfig=");
                        visible &= !trimmed.contains("NOT_VISIBLE");
                        focusable &= !trimmed.contains("NOT_FOCUSABLE");
                        hasInputChannel &= !trimmed.contains("NO_INPUT_CHANNEL");
                        dispatchReady &= inputDispatchReady(trimmed);
                    }
                    continue;
                }
            }
            if (!matching) {
                continue;
            }
            if (!trimmed.isEmpty() && indentation <= entryIndent) {
                matching = false;
                continue;
            }
            inputConfigSeen |= trimmed.contains("inputConfig=");
            visible &= !trimmed.contains("NOT_VISIBLE");
            focusable &= !trimmed.contains("NOT_FOCUSABLE");
            hasInputChannel &= !trimmed.contains("NO_INPUT_CHANNEL");
            dispatchReady &= inputDispatchReady(trimmed);
        }
        return new InputWindowState(found, dispatcherFocused,
                inputConfigSeen && visible, inputConfigSeen && focusable,
                inputConfigSeen && hasInputChannel, inputConfigSeen && dispatchReady);
    }

    private static boolean inputDispatchReady(String line) {
        return !line.contains("PAUSE_DISPATCHING") && !line.contains("DROP_INPUT");
    }

    private static FocusedWindow parseInputWindowEntry(String line) {
        FocusedWindow quoted = parseFocusedWindow(line);
        if (quoted.present()) {
            return quoted;
        }
        int nameStart = line.indexOf("name=");
        if (nameStart < 0) {
            return FocusedWindow.none();
        }
        nameStart += "name=".length();
        int nameEnd = line.indexOf(',', nameStart);
        if (nameEnd < 0) {
            nameEnd = line.length();
        }
        return parseFocusedWindowBody(line.substring(nameStart, nameEnd));
    }

    private static boolean displayZeroEntry(String line) {
        return line.contains("displayId=0,") || line.endsWith("displayId=0");
    }

    private static void dismissCameraPermissionIfReady(Instrumentation instrumentation)
            throws IOException {
        StablePermissionReadiness readiness = new StablePermissionReadiness();
        readiness.ready(instrumentation);
        if (readiness.ready(instrumentation)) {
            cancelCameraPermissionSurface(instrumentation);
        }
    }

    private static void awaitStableCameraPermissionSurface(Instrumentation instrumentation,
            StablePermissionReadiness readiness) throws Exception {
        try {
            await("stable native camera permission surface", () ->
                    readiness.ready(instrumentation));
        } catch (AssertionError failure) {
            AssertionError diagnostic = new AssertionError(failure.getMessage()
                    + "; last permission observation: " + readiness.diagnostic());
            diagnostic.initCause(failure);
            throw diagnostic;
        }
    }

    private static void cancelCameraPermissionSurface(Instrumentation instrumentation)
            throws IOException {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
            runShell(instrumentation, "am force-stop com.android.packageinstaller");
        } else {
            sendSystemBack(instrumentation);
        }
    }

    private record FocusedWindow(String identity, String title) {
        private static FocusedWindow none() {
            return new FocusedWindow("", "");
        }

        private boolean cameraPermission(boolean legacy) {
            if (legacy) {
                return title.startsWith("com.android.packageinstaller/")
                        && title.endsWith("GrantPermissionsActivity");
            }
            return cameraPermissionActivity(title);
        }

        private boolean sameIdentity(FocusedWindow other) {
            return !identity.isEmpty() && !other.identity.isEmpty()
                    && identity.equals(other.identity);
        }

        private boolean sameIdentityAndTitle(FocusedWindow other) {
            return sameIdentity(other) && title.equals(other.title);
        }

        private boolean present() {
            return !identity.isEmpty() && !title.isEmpty();
        }
    }

    private record DisplayFocus(FocusedWindow currentFocus,
            FocusedWindow focusedWindow) {
    }

    private record WindowManagerFocus(FocusedWindow currentFocus,
            FocusedWindow windowsCurrentFocus, FocusedWindow displaysCurrentFocus,
            FocusedWindow displaysFocusedWindow) {

        private static WindowManagerFocus legacy(FocusedWindow currentFocus) {
            return new WindowManagerFocus(currentFocus, currentFocus,
                    FocusedWindow.none(), FocusedWindow.none());
        }
    }

    private record InputFocusSignals(FocusedWindow plural,
            FocusedWindow singular) {

        private FocusedWindow selected() {
            return plural.present() ? plural : singular;
        }

        private boolean agree() {
            return !plural.present() || !singular.present()
                    || plural.sameIdentityAndTitle(singular);
        }
    }

    private record WindowBlockState(boolean found, boolean hasSurface,
            boolean readyForDisplay, boolean surfaceShown, boolean hasDrawn) {

        private static WindowBlockState none() {
            return new WindowBlockState(false, false, false, false, false);
        }

        private boolean ready() {
            return found && hasSurface && readyForDisplay && surfaceShown && hasDrawn;
        }
    }

    private record InputWindowState(boolean found, boolean focused,
            boolean visible, boolean focusable, boolean hasInputChannel,
            boolean dispatchReady) {

        private static InputWindowState none() {
            return new InputWindowState(false, false, false, false, false, false);
        }

        private boolean ready() {
            return found && focused && visible && focusable && hasInputChannel
                    && dispatchReady;
        }
    }

    private record AppTransitionSnapshot(String nextAppTransition,
            String appTransitionState) {

        private static AppTransitionSnapshot notRequired() {
            return new AppTransitionSnapshot("NOT_REQUIRED", "NOT_REQUIRED");
        }

        private boolean idle() {
            return "TRANSIT_UNSET".equals(nextAppTransition)
                    && "APP_STATE_IDLE".equals(appTransitionState);
        }
    }

    private record PermissionSurfaceObservation(PermissionSurfaceSnapshot snapshot,
            String diagnostic) {

        private static PermissionSurfaceObservation ready(
                PermissionSurfaceSnapshot snapshot) {
            return new PermissionSurfaceObservation(snapshot, "ready " + snapshot);
        }

        private static PermissionSurfaceObservation notReady(String diagnostic) {
            return new PermissionSurfaceObservation(null, diagnostic);
        }
    }

    private record PermissionSurfaceSnapshot(WindowManagerFocus windowManagerFocus,
            InputFocusSignals inputFocusSignals, WindowBlockState windowState,
            InputWindowState inputWindowState, String nextAppTransition,
            String appTransitionState) {
    }

    private static final class StablePermissionReadiness {
        private PermissionSurfaceSnapshot previous;
        private String diagnostic = "not sampled";

        private boolean ready(Instrumentation instrumentation) throws IOException {
            PermissionSurfaceObservation observation =
                    permissionSurfaceObservation(instrumentation);
            diagnostic = observation.diagnostic();
            PermissionSurfaceSnapshot current = observation.snapshot();
            if (current == null) {
                previous = null;
                return false;
            }
            boolean stable = current.equals(previous);
            previous = current;
            return stable;
        }

        private String diagnostic() {
            return diagnostic;
        }
    }

    private static void resetCameraPermissionForPrompt(Instrumentation instrumentation)
            throws IOException {
        String packageName = instrumentation.getTargetContext().getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runShell(instrumentation, "pm clear-permission-flags " + packageName
                    + " android.permission.CAMERA user-set user-fixed");
        }
        runShell(instrumentation,
                "pm revoke " + packageName + " android.permission.CAMERA");
    }

    private static boolean currentResumedActivity(String line) {
        return line.startsWith("mResumedActivity:")
                || line.startsWith("mResumedActivity=")
                || line.startsWith("topResumedActivity=")
                || line.startsWith("ResumedActivity:");
    }

    private static View menuButton(MainActivity activity) {
        View menu = findMenuOverlay(activity.getWindow().getDecorView());
        assertNotNull("menu button", menu);
        return menu;
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

    private static eu.rekawek.coffeegb.ui.menu.MenuController menuController(
            MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("menuController");
            field.setAccessible(true);
            return (eu.rekawek.coffeegb.ui.menu.MenuController) field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String focusedItemId(MainActivity activity) {
        eu.rekawek.coffeegb.ui.menu.MenuStackSnapshot snapshot =
                menuController(activity).snapshot();
        return snapshot.frames().get(snapshot.frames().size() - 1).focusedItemId();
    }

    private static boolean externalSurfaceActive(ActivityScenario<MainActivity> scenario) {
        AtomicReference<Boolean> active = new AtomicReference<>(false);
        scenario.onActivity(activity -> active.set(externalSurface(activity).active()));
        return active.get();
    }

    private static eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState externalSurface(
            MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("externalSurface");
            field.setAccessible(true);
            return (eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState)
                    field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String pauseOwnershipDiagnostic(MainActivity activity) {
        AndroidEmulationRuntime active = runtime(activity);
        RuntimeState runtimeState = active == null ? null : active.state();
        RuntimeState observed = observedState(activity);
        eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState surface =
                externalSurface(activity);
        return "sdk=" + Build.VERSION.SDK_INT
                + ", permission=" + activity.checkSelfPermission(
                        android.Manifest.permission.CAMERA)
                + ", rationale=" + activity.shouldShowRequestPermissionRationale(
                        android.Manifest.permission.CAMERA)
                + ", runtimePhase=" + (runtimeState == null ? "null" : runtimeState.phase())
                + ", observedPhase=" + observed.phase()
                + ", observedGeneration=" + longField(activity, "observedGeneration")
                + ", menuPauseOwned=" + booleanField(activity, "menuPauseOwned")
                + ", externalActive=" + surface.active()
                + ", externalAction=" + surface.action()
                + ", externalPauseOwned=" + surface.pauseOwned()
                + ", externalRestoreRequested=" + surface.restoreRequested();
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

    private static Object objectField(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setObjectField(MainActivity activity, String name, Object value) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void invokeNoArgs(MainActivity activity, String name) {
        invoke(activity, name, new Class<?>[0]);
    }

    private static void invoke(MainActivity activity, String name, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name,
                    parameterTypes);
            method.setAccessible(true);
            method.invoke(activity, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertFocused(ActivityScenario<MainActivity> scenario, String expected) {
        scenario.onActivity(activity -> {
            eu.rekawek.coffeegb.ui.menu.MenuStackSnapshot snapshot =
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
                eu.rekawek.coffeegb.ui.menu.MenuStackSnapshot snapshot =
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
                eu.rekawek.coffeegb.ui.menu.MenuStackSnapshot snapshot =
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
            eu.rekawek.coffeegb.ui.menu.MenuRoute expected) throws Exception {
        await("route " + expected, () -> {
            AtomicReference<eu.rekawek.coffeegb.ui.menu.MenuRoute> route =
                    new AtomicReference<>();
            scenario.onActivity(activity -> route.set(menuController(activity).route()));
            return route.get() == expected;
        });
    }

    private static void await(String action, CheckedCondition condition) throws Exception {
        for (int poll = 0; poll < 2_400; poll++) {
            if (condition.get()) {
                return;
            }
            SystemClock.sleep(25L);
        }
        assertTrue("timed out waiting for " + action, condition.get());
    }

    private static View findMenuOverlay(View view) {
        CharSequence description = view.getContentDescription();
        if (description != null && (description.equals("Open Coffee GB menu")
                || description.equals("Close Coffee GB menu"))) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View menu = findMenuOverlay(group.getChildAt(index));
                if (menu != null) {
                    return menu;
                }
            }
        }
        return null;
    }

    private static void waitForState(ActivityScenario<?> scenario, Lifecycle.State expected,
            Instrumentation instrumentation) {
        for (int poll = 0; poll < 120; poll++) {
            if (scenario.getState() == expected) {
                return;
            }
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
