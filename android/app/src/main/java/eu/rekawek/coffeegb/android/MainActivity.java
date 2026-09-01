package eu.rekawek.coffeegb.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.graphics.PointF;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.android.menu.MenuExternalSurfaceState;
import eu.rekawek.coffeegb.ui.menu.MenuKey;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.PauseMenuSnapshot;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.MenuStackSnapshot;
import eu.rekawek.coffeegb.controller.state.StateRef;

import java.lang.ref.WeakReference;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Canvas-menu and native external-surface client for {@link EmulationService}. */
public final class MainActivity extends Activity implements RuntimeObserver {

    private static final int STATE_MENU_MIN_SLOT = StateRef.MIN_SLOT;
    private static final int STATE_MENU_MAX_SLOT = StateRef.MAX_SLOT;

    private static final int OPEN_ROM_REQUEST = 1;
    private static final int IMPORT_BATTERY_REQUEST = 2;
    private static final int EXPORT_BATTERY_REQUEST = 3;
    private static final int IMPORT_STATE_REQUEST = 4;
    private static final int EXPORT_STATE_REQUEST = 5;
    private static final int EXPORT_SCREENSHOT_REQUEST = 6;
    private static final int EXPORT_PRINTER_REQUEST = 7;
    private static final int CAMERA_PERMISSION_REQUEST = 8;
    private static final int GPS_PERMISSION_REQUEST = 9;

    private static final String STATE_EXTERNAL_ACTION = "external.action";
    private static final String STATE_EXTERNAL_REQUEST = "external.request";
    private static final String STATE_EXTERNAL_POLICY = "external.policy";
    private static final String STATE_EXTERNAL_PAUSE = "external.pause";
    private static final String STATE_EXTERNAL_RESTORE = "external.restore";
    private static final String STATE_MENU_PAUSE = "menu.pause";
    private static final String STATE_CONFIRM_VARIANT = "menu.confirm.variant";
    private static final String STATE_CONFIRM_SLOT = "menu.confirm.slot";
    private static final String STATE_SLOT_MODE = "menu.state.mode";
    private static final String STATE_AUDIO_ACTIVE = "draft.audio.active";
    private static final String STATE_AUDIO_VOLUME = "draft.audio.volume";
    private static final String STATE_AUDIO_MUTED = "draft.audio.muted";
    private static final String STATE_TOUCH_ACTIVE = "draft.touch.active";
    private static final String STATE_TOUCH_OPACITY = "draft.touch.opacity";
    private static final String STATE_TOUCH_SCALE = "draft.touch.scale";
    private static final String STATE_TOUCH_VERTICAL = "draft.touch.vertical";
    private static final String STATE_TOUCH_LEFT = "draft.touch.left";
    private static final String STATE_TOUCH_HAPTICS = "draft.touch.haptics";
    private static final String STATE_DEVICES_ACTIVE = "draft.devices.active";
    private static final String STATE_DEVICES_RUMBLE = "draft.devices.rumble";
    private static final String STATE_DEVICES_CAMERA = "draft.devices.camera";
    private static final String STATE_DEVICES_PRINTER = "draft.devices.printer";
    private static final String STATE_PENDING_ACTION = "document.pending.action";
    private static final String STATE_PENDING_REQUEST = "document.pending.request";
    private static final String STATE_PENDING_URI = "document.pending.uri";
    private static final String STATE_PENDING_FLAGS = "document.pending.flags";
    private static final String STATE_PENDING_RELEASE_MENU_PAUSE =
            "document.pending.release-menu-pause";
    private static final String STATE_OPTIONAL_STATUS = "status.optional-devices";
    private static final String STATE_PRINTER_STATUS = "status.printer";
    private static final String STATE_ABOUT_STATUS = "status.about";
    private static final String STATE_OPTION_ACTIVE = "choice.active";
    private static final String STATE_OPTION_ROUTE = "choice.route";
    private static final String STATE_OPTION_ID = "choice.id";
    private static final String STATE_OPTION_TITLE = "choice.title";
    private static final String STATE_OPTION_SELECTED = "choice.selected";
    private static final String STATE_OPTION_TOKENS = "choice.tokens";
    private static final String STATE_OPTION_LABELS = "choice.labels";
    private static final String STATE_OPTION_ENABLED = "choice.enabled";
    private static final String PRINTER_CONTINUATION_PREFS = "printer-share-continuation";
    private static final String PRINTER_CONTINUATION_TOKEN = "token";
    private static final String PRINTER_CONTINUATION_URI = "uri";
    private static final String PRINTER_CONTINUATION_PHASE = "phase";
    private static final String SOURCE_URL = "https://github.com/trekawek/coffee-gb";
    private static final DateTimeFormatter STATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private CoffeeGbSurfaceView video;
    private View menuButton;
    private AndroidEmulationRuntime runtime;
    private AndroidEmulationRuntime observedRuntime;
    private long observedGeneration = -1L;
    private boolean bound;
    private InputManager inputManager;
    private PendingDocumentResult pendingDocumentResult;
    private SharedPreferences printerContinuationPreferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener printerContinuationListener =
            (preferences, key) -> {
                if (PRINTER_CONTINUATION_PHASE.equals(key)) {
                    mainHandler.post(this::consumePrinterExportContinuation);
                }
            };
    private MenuController menuController;
    private RuntimeState observedState = RuntimeState.stopped();
    private List<AndroidStateSlot> stateSlots = List.of();
    private List<AndroidEmulationRuntime.RecentGame> recentGames = List.of();
    private StateMenuMode stateMenuMode = StateMenuMode.SAVE;
    private ConfirmVariant confirmVariant;
    private int confirmSlot = -1;
    private boolean stateSlotsLoading;
    private boolean recentGamesLoading;
    /** Monotonic guard for owner-thread catalog reads crossing Activity/ROM transitions. */
    private long stateCatalogGeneration;
    private long recentGamesCatalogGeneration;
    private boolean menuVisible;
    private boolean menuPauseOwned;
    private boolean selectionActionInFlight;
    private boolean preserveRouteOnHide;
    private MenuRoute presentedRoute;
    private MenuStackSnapshot suspendedMenu = MenuStackSnapshot.hidden();
    private boolean suspendedMenuPauseOwned;
    private MenuExternalSurfaceState externalSurface = MenuExternalSurfaceState.none();
    private Api33MenuBackCallback predictiveMenuBack;

    private AndroidMenuModel.AudioDraft audioDraft;
    private AndroidMenuModel.TouchDraft touchDraft;
    private AndroidMenuModel.DevicesDraft devicesDraft;
    private ChoiceSession optionSession;
    private String optionalDevicesStatus = "READY";
    private String aboutStatus = "OPEN IN BROWSER";
    private String printerStatus = "READY";
    private String systemPreferredFocus = "dmg-games";
    private MenuPreview printerPreview = MenuPreview.empty();
    private int printerPreviewGeneration;
    private boolean printerPaperEntryPending;
    private MenuRoute printerPaperEntryParent;
    private MenuStackSnapshot deferredMenuFocusRestore = MenuStackSnapshot.hidden();
    private boolean activityResumed;
    private DiagnosticsOptions diagnosticsOptions = DiagnosticsOptions.disabled();
    private boolean benchmarkRecentLaunchRequested;
    private boolean benchmarkAnchorRequested;
    private long benchmarkAnchorSessionGeneration;
    private final BenchmarkArmTokenLatch pendingBenchmarkArm = new BenchmarkArmTokenLatch();
    // Android 6-8 can return from a cancelled permission Activity without delivering its result.
    private MenuExternalSurfaceState legacyCameraPermissionFallbackSurface;
    private boolean legacyCameraPermissionFallbackPosted;
    private long lifecycleGeneration;

    private static final String PREF_SYSTEM_DMG = "system.dmgGames";
    private static final String PREF_SYSTEM_CGB = "system.cgbGames";
    private static final String PREF_SYSTEM_BOOTSTRAP = "system.bootstrap";
    private static final String PREF_EXECUTION_MODE = "execution.mode";
    private static final String DEFAULT_EXECUTION_MODE = "performance";
    private static final String PREF_DISPLAY_BORDER = "display.sgbBorder";
    private static final String PREF_DISPLAY_COLORS = "display.dmgColors";
    private static final String PREF_CAMERA_SELECTION = "devices.camera.selection";
    private static final String PREF_GAMEPAD_SELECTION = "devices.gamepad.selection";
    private static final String PREF_GPS_ENABLED = "devices.gps.enabled";

    private final InputManager.InputDeviceListener inputDevices =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    refreshMenuPages();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    AndroidEmulationRuntime active = runtime;
                    if (active != null) {
                        active.input().disconnect(deviceId);
                    }
                    cancelControllerCapture();
                    refreshMenuPages();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    refreshMenuPages();
                }
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AndroidEmulationRuntime connected =
                    ((EmulationService.RuntimeBinder) service).runtime();
            if (observedRuntime != connected) {
                observedRuntime = connected;
                observedGeneration = -1L;
                benchmarkAnchorRequested = false;
                benchmarkAnchorSessionGeneration = 0L;
                pendingBenchmarkArm.clear();
            }
            runtime = connected;
            bound = true;
            runtime.addObserver(MainActivity.this);
            video.attach(runtime.frames(), runtime.input());
            runtime.setAudioMuted(diagnosticsOptions.enabled ? false : getPreferences(MODE_PRIVATE)
                    .getBoolean("audio.muted", false));
            runtime.setAudioVolume(diagnosticsOptions.enabled ? 100 : getPreferences(MODE_PRIVATE)
                    .getInt("audio.volume", 100));
            SharedPreferences settings = getPreferences(MODE_PRIVATE);
            if (diagnosticsOptions.enabled) {
                // A benchmark session is deliberately isolated from persisted optional-device
                // selectors.  These are transient overrides: do not write them back to prefs.
                runtime.setRumbleEnabled(false);
                runtime.setPrinterEnabled(false);
                runtime.setCameraLens("off");
                runtime.setCameraEnabled(false);
                runtime.setGamepadSelection("none");
                runtime.setGpsEnabled(false);
            } else {
                runtime.setRumbleEnabled(settings.getBoolean("devices.rumble", false));
                runtime.setPrinterEnabled(settings.getBoolean("devices.printer", false));
                runtime.setCameraLens(cameraSelection(settings));
                runtime.setCameraEnabled(!"off".equals(cameraSelection(settings))
                        && checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED);
                runtime.setGamepadSelection(gamepadSelection(settings));
                boolean gpsRequested = settings.getBoolean(PREF_GPS_ENABLED, false);
                boolean gpsAllowed = locationPermissionGranted();
                if (gpsRequested && !gpsAllowed) {
                    settings.edit().putBoolean(PREF_GPS_ENABLED, false).apply();
                }
                runtime.setGpsEnabled(gpsRequested && gpsAllowed);
            }
            if (!diagnosticsOptions.enabled) {
                runtime.setDisplayGrayscale("grey".equals(displayColors(settings)));
                runtime.setSgbBorder(settings.getBoolean(PREF_DISPLAY_BORDER, false));
            }
            // Benchmark options are transient and must not rewrite the persisted system
            // selectors while the QA variant is comparing hardware profiles.
            if (!diagnosticsOptions.enabled) {
                applySystemSettings(runtime, settings);
            }
            applyState(runtime.state());
            dispatchPendingDocumentResult();
            restoreExternalSurfaceIfRequested();
            restoreSuspendedMenu();
            if (diagnosticsOptions.enabled && diagnosticsOptions.launchRecent
                    && !benchmarkRecentLaunchRequested) {
                benchmarkRecentLaunchRequested = true;
                runtime.launchMostRecentBenchmarkGame();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (runtime != null) {
                cancelControllerCapture();
                video.detach();
                runtime.removeObserver(MainActivity.this);
            }
            if (menuController != null && menuController.visible()) {
                suspendedMenu = snapshotForPersistence();
                suspendedMenuPauseOwned = menuPauseOwned;
                preserveRouteOnHide = true;
                menuPauseOwned = false;
                menuController.hide();
            }
            video.clearMenuPresentation();
            runtime = null;
            observedRuntime = null;
            observedGeneration = -1L;
            benchmarkAnchorRequested = false;
            benchmarkAnchorSessionGeneration = 0L;
            pendingBenchmarkArm.clear();
            bound = false;
            observedState = RuntimeState.stopped();
            stateCatalogGeneration++;
            stateSlotsLoading = false;
            stateSlots = List.of();
            recentGamesCatalogGeneration++;
            recentGamesLoading = false;
            recentGames = List.of();
            cancelPendingPrinterPaperEntry();
            printerPreview = MenuPreview.empty();
            refreshMenuPages();
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        String token = DiagnosticsOptions.benchmarkArmToken(intent);
        if (token == null) {
            return;
        }
        AndroidEmulationRuntime active = runtime;
        pendingBenchmarkArm.put(token, observedState.sessionGeneration());
        if (active != null && bound) {
            armPendingBenchmarkIfReady(active);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        diagnosticsOptions = BuildConfig.DIAGNOSTICS_ENABLED
                ? DiagnosticsOptions.fromIntent(getIntent()) : DiagnosticsOptions.disabled();
        if (diagnosticsOptions.enabled) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true);
                setShowWhenLocked(true);
            }
        }
        printerContinuationPreferences = getApplicationContext().getSharedPreferences(
                PRINTER_CONTINUATION_PREFS, MODE_PRIVATE);
        restoreActivityState(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        video = new CoffeeGbSurfaceView(this);
        if (diagnosticsOptions.enabled) {
            // The host separately pins the display mode (60/120 Hz).  Surface.setFrameRate must
            // describe the exact emulated producer cadence, e.g. 59.7275 rather than the SGB
            // display target 120 Hz.
            video.setBenchmarkContentRateMillihz(diagnosticsOptions.surfaceContentRateMillihz);
        }
        menuController = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation presentation) {
                presentMenu(presentation);
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
                handleMenuItem(route, id, secondary);
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
                handleMenuHeader(route);
            }

            @Override
            public void onItemAdjusted(MenuRoute route, String id, int direction) {
                handleMenuAdjustment(route, id, direction);
            }

            @Override
            public void onBackIntercepted(MenuRoute route) {
                if (route == MenuRoute.CONTROLLER_MAPPING) {
                    cancelControllerCapture();
                    refreshMenuPages();
                }
            }
        });
        menuController.setRootDismissAllowed(observedState.sessionGeneration() != 0L);
        video.setMenuInput(menuController);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            predictiveMenuBack = new Api33MenuBackCallback(this, this::dispatchMenuBack);
        }
        root.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The menu grille is already baked into the raster skin. Keep only an accessible,
        // invisible hit target over it; a Button would add platform state/ripple pixels.
        menuButton = new View(this);
        styleMenuButton();
        menuButton.setContentDescription("Open Coffee GB menu");
        menuButton.setOnClickListener(ignored -> toggleMenu());
        int menuTarget = menuButtonSize();
        root.addView(menuButton, new FrameLayout.LayoutParams(
                menuTarget, menuTarget));
        video.addOnLayoutChangeListener((view, left, top, right, bottom,
                                         oldLeft, oldTop, oldRight, oldBottom) ->
                positionMenuOverlay());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        refreshMenuPages();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (menuController != null && menuController.visible()) {
            writeSnapshot(outState, "menu", snapshotForPersistence());
            outState.putBoolean(STATE_MENU_PAUSE, menuPauseOwned);
        } else if (suspendedMenu.visible()) {
            writeSnapshot(outState, "menu", suspendedMenu);
            outState.putBoolean(STATE_MENU_PAUSE, suspendedMenuPauseOwned);
        }
        if (externalSurface.active()) {
            outState.putString(STATE_EXTERNAL_ACTION, externalSurface.action().name());
            outState.putInt(STATE_EXTERNAL_REQUEST, externalSurface.requestCode());
            outState.putString(STATE_EXTERNAL_POLICY, externalSurface.restorePolicy().name());
            outState.putBoolean(STATE_EXTERNAL_PAUSE, externalSurface.pauseOwned());
            outState.putBoolean(STATE_EXTERNAL_RESTORE, externalSurface.restoreRequested());
            writeSnapshot(outState, "external", externalSurface.menuStack());
        }
        saveDrafts(outState);
        saveOptionSession(outState);
        if (confirmVariant != null) {
            outState.putString(STATE_CONFIRM_VARIANT, confirmVariant.name());
            outState.putInt(STATE_CONFIRM_SLOT, confirmSlot);
        }
        outState.putString(STATE_SLOT_MODE, stateMenuMode.name());
        outState.putString(STATE_OPTIONAL_STATUS, optionalDevicesStatus);
        outState.putString(STATE_PRINTER_STATUS, printerStatus);
        outState.putString(STATE_ABOUT_STATUS, aboutStatus);
        if (pendingDocumentResult != null) {
            outState.putString(STATE_PENDING_ACTION, pendingDocumentResult.action().name());
            outState.putInt(STATE_PENDING_REQUEST, pendingDocumentResult.requestCode());
            outState.putString(STATE_PENDING_URI, pendingDocumentResult.uri().toString());
            outState.putInt(STATE_PENDING_FLAGS, pendingDocumentResult.flags());
            outState.putBoolean(STATE_PENDING_RELEASE_MENU_PAUSE,
                    pendingDocumentResult.releaseMenuPause());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleGeneration++;
        printerContinuationPreferences.registerOnSharedPreferenceChangeListener(
                printerContinuationListener);
        inputManager = getSystemService(InputManager.class);
        inputManager.registerInputDeviceListener(inputDevices, null);
        if (diagnosticsOptions.enabled) {
            EmulationService.start(this, diagnosticsOptions);
        } else {
            EmulationService.start(this, executionMode(getPreferences(MODE_PRIVATE)));
        }
        if (!bound) {
            bindService(new Intent(this, EmulationService.class), connection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        consumePrinterExportContinuation();
        postLegacyCameraPermissionFallback();
    }

    @Override
    protected void onPause() {
        armLegacyCameraPermissionFallback(externalSurface);
        activityResumed = false;
        if (video != null) {
            video.clearTransientMessage();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        lifecycleGeneration++;
        printerPreviewGeneration++;
        cancelPendingPrinterPaperEntry();
        printerContinuationPreferences.unregisterOnSharedPreferenceChangeListener(
                printerContinuationListener);
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(inputDevices);
            inputManager = null;
        }
        cancelControllerCapture();
        if (menuController != null && menuController.visible()) {
            if (externalSurface.active()) {
                suspendedMenu = MenuStackSnapshot.hidden();
            } else {
                suspendedMenu = snapshotForPersistence();
                suspendedMenuPauseOwned = menuPauseOwned;
            }
            preserveRouteOnHide = true;
            menuPauseOwned = false;
            menuController.hide();
        }
        if (menuController != null) {
            menuController.cancelInput();
        }
        if (bound) {
            runtime.input().releaseAll();
            video.detach();
            runtime.removeObserver(this);
            unbindService(connection);
            bound = false;
            runtime = null;
        }
        stateCatalogGeneration++;
        stateSlotsLoading = false;
        stateSlots = List.of();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lifecycleGeneration++;
        if (printerContinuationPreferences != null) {
            printerContinuationPreferences.unregisterOnSharedPreferenceChangeListener(
                    printerContinuationListener);
        }
        if (predictiveMenuBack != null) {
            predictiveMenuBack.close();
            predictiveMenuBack = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isSystemBack(event)) {
            return super.dispatchKeyEvent(event);
        }
        AndroidEmulationRuntime active = runtime;
        if (active != null && menuController != null && menuController.visible()
                && menuController.route() == MenuRoute.CONTROLLER_MAPPING
                && active.input().captureActive() && isGameController(event.getSource())) {
            AndroidInputRouter.CaptureResult result = active.input().captureKeyEvent(event);
            if (result != AndroidInputRouter.CaptureResult.NONE) {
                if (result == AndroidInputRouter.CaptureResult.COMPLETED) {
                    menuController.setBackIntercepted(false);
                }
                refreshMenuPages();
                return true;
            }
        }
        boolean selectedController = active == null || !isGameController(event.getSource())
                || (menuController != null && menuController.visible()
                        ? active.input().acceptsMenuController(event.getDevice())
                        : active.input().acceptsController(event.getDevice()));
        MenuKey menuKey = menuKey(event);
        if (menuKey != null && selectedController) {
            if (event.getAction() == KeyEvent.ACTION_MULTIPLE
                    && menuController != null && menuController.visible()) {
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && menuController != null && menuController.visible()
                    && menuController.onKeyDown(menuKey, event.getRepeatCount() > 0)) {
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP
                    && menuController != null && menuController.onKeyUp(menuKey)) {
                return true;
            }
        }
        if (active != null && active.input().onKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        AndroidEmulationRuntime active = runtime;
        if (menuController != null && menuController.visible()
                && isGameController(event.getSource())
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            if (active == null || !active.input().acceptsMenuController(event.getDevice())) {
                return true;
            }
            if (active != null && menuController.route() == MenuRoute.CONTROLLER_MAPPING
                    && active.input().captureActive()) {
                return true;
            }
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float x = Math.abs(hatX) >= .45f ? hatX
                    : event.getAxisValue(MotionEvent.AXIS_X);
            float y = Math.abs(hatY) >= .45f ? hatY
                    : event.getAxisValue(MotionEvent.AXIS_Y);
            menuController.onAxis(x, y);
            return true;
        }
        if (active != null && active.input().onMotionEvent(event)) {
            refreshMenuPages();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && runtime != null) {
            runtime.input().releaseAll();
            cancelControllerCapture();
            if (menuController != null) {
                menuController.cancelInput();
            }
            refreshMenuPages();
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && dispatchMenuBack()) {
            return;
        }
        super.onBackPressed();
    }

    private boolean dispatchMenuBack() {
        return menuController != null && menuController.dispatchBackEdge();
    }

    @Override
    public void onStateChanged(RuntimeState state) {
        applyState(state);
    }

    /** Shows current-session SAVE/LOAD completion feedback above gameplay or the menu. */
    @Override
    public void onTransientMessage(String message) {
        // Runtime callbacks are posted to the main thread, but an Activity can be between
        // onPause/onStop or service rebinding when a queued callback arrives. Do not let an old
        // lifecycle paint feedback into a newly attached surface.
        if (!activityResumed || !bound || runtime == null || video == null
                || message == null || message.isBlank()) {
            return;
        }
        video.showTransientMessage(message);
    }

    private void toggleMenu() {
        if (menuController == null || externalSurface.active()) {
            return;
        }
        boolean wasVisible = menuController.visible();
        AndroidEmulationRuntime active = runtime;
        RuntimeState current = active == null ? observedState : active.state();
        // Resolve a retained-service transition before interpreting a tap on the automatic
        // Library root. This turns a just-started session into its pause menu instead of treating
        // the stale idle menu as something the user merely wants to close.
        applyState(current);
        current = observedState;
        if (menuController.visible()) {
            if (wasVisible) {
                if (current.sessionGeneration() == 0L) {
                    return;
                }
                menuController.hide();
            }
            return;
        }
        if (active != null) {
            active.input().releaseAll();
        }
        boolean pauseTarget = isPauseMenuTarget(current);
        if (active != null) {
            if (pauseTarget) {
                // Capture before pause() asks the controller to stop producing new frames. The
                // runtime keeps this immutable root capture through Activity recreation.
                active.capturePauseMenuSnapshot();
            } else {
                active.clearPauseMenuSnapshot();
            }
        }
        menuPauseOwned = current.phase() == RuntimeState.Phase.RUNNING;
        if (menuPauseOwned && active != null) {
            active.pause();
        }
        refreshMenuPages();
        if (current.phase() == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION) {
            menuController.show(MenuRoute.CHOOSE_ROM);
        } else if (current.phase() == RuntimeState.Phase.AWAITING_RECENT_SELECTION) {
            menuController.show(MenuRoute.LIBRARY);
        } else if (pauseTarget) {
            menuController.show(MenuRoute.PAUSE_CONSOLE);
        } else {
            menuController.show(MenuRoute.LIBRARY);
        }
    }

    private static boolean isPauseMenuTarget(RuntimeState state) {
        return state.phase() == RuntimeState.Phase.PAUSED
                || (state.transferReady() && state.paused())
                || (state.transferReady() && state.phase() == RuntimeState.Phase.RUNNING);
    }

    private void styleMenuButton() {
        menuButton.setBackground(null);
        menuButton.setForeground(null);
        menuButton.setStateListAnimator(null);
        menuButton.setElevation(0.0f);
        menuButton.setTranslationZ(0.0f);
        menuButton.setDefaultFocusHighlightEnabled(false);
        menuButton.setWillNotDraw(true);
        menuButton.setClickable(true);
        menuButton.setFocusable(true);
        menuButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private int menuButtonSize() {
        return Math.max(1, Math.round(48f * getResources().getDisplayMetrics().density));
    }

    private void positionMenuOverlay() {
        if (video == null || menuButton == null || video.getParent() == null) {
            return;
        }
        int width = video.getWidth();
        int height = video.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int target = menuButtonSize();
        PointF center = video.menuControlCenter(width, height);
        FrameLayout root = (FrameLayout) video.getParent();
        int videoLeft = video.getLeft() - root.getPaddingLeft();
        int videoTop = video.getTop() - root.getPaddingTop();
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) menuButton.getLayoutParams();
        layout.gravity = Gravity.TOP | Gravity.START;
        layout.width = target;
        layout.height = target;
        layout.leftMargin = videoLeft + Math.round(center.x - target / 2f);
        layout.rightMargin = 0;
        layout.topMargin = videoTop + Math.round(center.y - target / 2f);
        menuButton.setLayoutParams(layout);
    }

    private void presentMenu(MenuPresentation presentation) {
        if (presentation.visible() && presentation.route() == MenuRoute.SAVE_STATES
                && refreshStatePreviewForFocus(presentation)) {
            return;
        }
        if (presentation.visible() && presentation.route() == MenuRoute.RECENT_GAMES
                && refreshRecentGamePreviewForFocus(presentation)) {
            return;
        }
        boolean wasVisible = menuVisible;
        MenuRoute previous = presentedRoute;
        menuVisible = presentation.visible();
        presentedRoute = menuVisible ? presentation.route() : null;
        if (predictiveMenuBack != null) {
            predictiveMenuBack.setEnabled(menuVisible);
        }
        if (menuVisible) {
            if (previous != null && previous != presentedRoute
                    && !stackContains(menuController.snapshot(), previous)) {
                onRouteExited(previous);
            }
            video.setMenuPresentation(presentation);
            menuButton.setContentDescription("Close Coffee GB menu");
        } else {
            video.clearMenuPresentation();
            menuButton.setContentDescription("Open Coffee GB menu");
            if (wasVisible) {
                boolean preserve = preserveRouteOnHide || externalSurface.active();
                preserveRouteOnHide = false;
                if (!preserve && previous != null) {
                    onRouteExited(previous);
                }
                if (!preserve) {
                    onMenuClosed();
                }
            }
        }
    }

    /** Rebinds only the detached preview when focus moves between stable state slots. */
    private boolean refreshStatePreviewForFocus(MenuPresentation presentation) {
        if (menuController == null || stateSlotsLoading) {
            return false;
        }
        MenuPresentation.Item focused = presentation.items().get(presentation.focusedIndex());
        int slot = parseSlot(focused.id().replace("slot:", ""));
        AndroidStateSlot selected = stateSlot(slot);
        MenuPreview preview = selected == null ? MenuPreview.empty() : selected.preview();
        List<String> sideLines = stateSavedAtLines(selected);
        if (presentation.preview() == preview && presentation.sideLines().equals(sideLines)) {
            return false;
        }
        String focusId = focused.id();
        menuController.setPage(statePage(preview, focusId));
        return true;
    }

    /** Rebinds the detached recent-game preview and timestamp as focus moves between rows. */
    private boolean refreshRecentGamePreviewForFocus(MenuPresentation presentation) {
        if (menuController == null || recentGamesLoading || recentGames.isEmpty()) {
            return false;
        }
        MenuPresentation.Item focused = presentation.items().get(presentation.focusedIndex());
        AndroidEmulationRuntime.RecentGame selected = null;
        for (AndroidEmulationRuntime.RecentGame game : recentGames) {
            if (("recent:" + game.token()).equals(focused.id())) {
                selected = game;
                break;
            }
        }
        if (selected == null) {
            return false;
        }
        MenuPreview preview = selected.preview();
        List<String> sideLines = formatRecentPlayed(selected.lastPlayedMillis()).isBlank()
                ? List.of() : List.of("LAST PLAYED: "
                + formatRecentPlayed(selected.lastPlayedMillis()));
        if (presentation.preview() == preview && presentation.sideLines().equals(sideLines)) {
            return false;
        }
        menuController.setPage(recentGamesPage());
        return true;
    }

    private void onRouteExited(MenuRoute route) {
        if (route == printerPaperEntryParent) {
            cancelPendingPrinterPaperEntry();
        }
        if (deferredMenuFocusRestore.visible()
                && stackContains(deferredMenuFocusRestore, route)) {
            deferredMenuFocusRestore = MenuStackSnapshot.hidden();
        }
        switch (route) {
            case AUDIO -> audioDraft = null;
            case RECENT_GAMES -> {
                recentGamesCatalogGeneration++;
                recentGamesLoading = false;
                recentGames = List.of();
            }
            case OPTION_PICKER -> optionSession = null;
            case TOUCH_CONTROLS -> touchDraft = null;
            case OPTIONAL_DEVICES -> devicesDraft = null;
            case CONTROLLER_MAPPING -> cancelControllerCapture();
            case PRINTER_PAPER -> {
                printerPreviewGeneration++;
                deferredMenuFocusRestore = MenuStackSnapshot.hidden();
            }
            case CHOOSE_ROM -> {
                if (!selectionActionInFlight && runtime != null
                        && observedState.phase()
                        == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION) {
                    runtime.cancelPendingSelection();
                }
            }
            default -> { }
        }
        if (route == MenuRoute.CONFIRM_ACTION) {
            confirmVariant = null;
            confirmSlot = -1;
        }
    }

    private static boolean stackContains(MenuStackSnapshot snapshot, MenuRoute route) {
        for (MenuStackSnapshot.Frame frame : snapshot.frames()) {
            if (frame.route() == route) {
                return true;
            }
        }
        return false;
    }

    private void onMenuClosed() {
        AndroidEmulationRuntime active = runtime;
        if (active != null) {
            active.clearPauseMenuSnapshot();
        }
        cancelPendingPrinterPaperEntry();
        if (selectionActionInFlight) {
            selectionActionInFlight = false;
        } else if (runtime != null && (observedState.phase()
                == RuntimeState.Phase.AWAITING_RECENT_SELECTION
                || observedState.phase() == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION)) {
            runtime.cancelPendingSelection();
        }
        if (menuPauseOwned) {
            menuPauseOwned = false;
            if (runtime != null) {
                runtime.resume();
            }
        }
    }

    private void handleMenuHeader(MenuRoute route) {
        // Library has no header action. Open ROM is an ordinary row, matching the pause menu.
    }

    private void handleMenuAdjustment(MenuRoute route, String id, int direction) {
        if (route == MenuRoute.AUDIO && "volume".equals(id) && audioDraft != null) {
            audioDraft = AndroidMenuModel.adjustVolume(audioDraft, direction);
            persistAudioDraft();
            refreshMenuPages();
        }
    }

    private void handleMenuItem(MenuRoute route, String id, boolean secondary) {
        if (id == null) {
            return;
        }
        AndroidEmulationRuntime active = runtime;
        switch (route) {
            case PAUSE_CONSOLE -> handlePauseItem(active, id);
            case SAVE_STATES -> handleStateItem(active, id, secondary);
            case RECENT_GAMES -> handleRecentGamesItem(active, id);
            case LIBRARY -> handleLibraryItem(id);
            case CHOOSE_ROM -> handleChooseRomItem(active, id);
            case SETTINGS -> handleSettingsItem(id);
            case AUDIO -> handleAudioItem(id);
            case DISPLAY -> handleDisplayItem(id);
            case TOUCH_CONTROLS -> handleTouchItem(id);
            case OPTIONAL_DEVICES -> handleOptionalDevicesItem(active, id);
            case OPTION_PICKER -> handleOptionPickerItem(id);
            case CONTROLLER_MAPPING -> handleControllerItem(active, id);
            case PRINTER_PAPER -> handlePrinterPaperItem(id);
            case SYSTEM -> handleSystemItem(id);
            case DATA_MEDIA -> handleDataMediaItem(id);
            case ABOUT -> handleAboutItem(id);
            case CONFIRM_ACTION -> handleConfirmationItem(id);
        }
    }

    private void handlePauseItem(AndroidEmulationRuntime active, String id) {
        if (active == null) {
            return;
        }
        switch (id) {
            case "resume" -> resumeAndClose(active);
            case "save-state" -> showStateMenu(StateMenuMode.SAVE);
            case "load-state" -> showStateMenu(StateMenuMode.LOAD);
            case "reset" -> showConfirmation(ConfirmVariant.RESET, -1);
            case "settings" -> menuController.push(MenuRoute.SETTINGS);
            case "recent-games" -> showRecentGames();
            case "open-rom" -> openRomFromMenu();
            default -> { }
        }
    }

    private void handleStateItem(AndroidEmulationRuntime active, String id, boolean secondary) {
        if (active == null) {
            return;
        }
        if (!id.startsWith("slot:")) {
            return;
        }
        int slot = parseSlot(id.substring("slot:".length()));
        AndroidStateSlot stateSlot = stateSlot(slot);
        if (slot < 0) {
            return;
        }
        if (stateMenuMode == StateMenuMode.LOAD) {
            // Empty/unloadable rows remain focusable but loading them is a safe no-op.
            if (!stateSlotsLoading && stateSlot != null && stateSlot.loadable()) {
                active.restoreSnapshot(slot);
                closeMenuWithoutResume();
            }
        } else {
            // Saving is deliberately a direct overwrite, including occupied slots.
            stateSlotsLoading = true;
            stateSlots = List.of();
            refreshMenuPages();
            long generation = lifecycleGeneration;
            active.saveSnapshot(slot, () -> {
                if (runtime != active || generation != lifecycleGeneration) {
                    return;
                }
                loadStateSlots();
            });
        }
    }

    private void handleRecentGamesItem(AndroidEmulationRuntime active, String id) {
        if (active == null || id == null || !id.startsWith("recent:")) {
            return;
        }
        long token = parseToken(id.substring("recent:".length()));
        if (token < 0 || recentGamesLoading) {
            return;
        }
        boolean releaseMenuPause = menuPauseOwned;
        selectionActionInFlight = true;
        menuPauseOwned = false;
        menuController.hide();
        active.selectRecentGame(token, releaseMenuPause);
    }

    private void handleLibraryItem(String id) {
        if ("recent-games".equals(id)) {
            showRecentGames();
        } else if ("open-rom".equals(id)) {
            openRomFromMenu();
        } else if ("settings".equals(id)) {
            menuController.push(MenuRoute.SETTINGS);
        }
    }

    private void handleChooseRomItem(AndroidEmulationRuntime active, String id) {
        if ("cancel".equals(id)) {
            menuController.back();
            return;
        }
        if (id.startsWith("archive:") && active != null) {
            long token = parseToken(id.substring("archive:".length()));
            if (token >= 0) {
                selectionActionInFlight = true;
                menuPauseOwned = false;
                menuController.hide();
                active.selectArchiveCandidate(token);
            }
        }
    }

    private void handleSettingsItem(String id) {
        switch (id) {
            case "system" -> {
                systemPreferredFocus = "dmg-games";
                refreshMenuPages();
                menuController.push(MenuRoute.SYSTEM);
            }
            case "display" -> {
                refreshMenuPages();
                menuController.push(MenuRoute.DISPLAY);
            }
            case "audio" -> {
                audioDraft = loadAudioDraft();
                refreshMenuPages();
                menuController.push(MenuRoute.AUDIO);
            }
            case "peripherals" -> {
                refreshMenuPages();
                menuController.push(MenuRoute.OPTIONAL_DEVICES);
            }
            case "touch-controls" -> {
                touchDraft = AndroidMenuModel.touchDraft(video.touchLayout());
                refreshMenuPages();
                menuController.push(MenuRoute.TOUCH_CONTROLS);
            }
            default -> { }
        }
    }

    private void handleAudioItem(String id) {
        if (audioDraft == null) {
            audioDraft = loadAudioDraft();
        }
        switch (id) {
            // Volume is adjusted only with left/right while focused; A is intentionally inert.
            case "volume" -> { }
            case "mute-audio" -> {
                audioDraft = audioDraft.toggleMuted();
                persistAudioDraft();
            }
            default -> { }
        }
        refreshMenuPages();
    }

    private void handleSystemItem(String id) {
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        switch (id) {
            case "dmg-games" -> openOptionPicker(MenuRoute.SYSTEM, id, "DMG GAMES",
                    List.of(new AndroidMenuModel.ChoiceValue("auto", "AUTO"),
                            new AndroidMenuModel.ChoiceValue("dmg", "DMG"),
                            new AndroidMenuModel.ChoiceValue("cgb", "CGB"),
                            new AndroidMenuModel.ChoiceValue("sgb", "SGB")),
                    systemDmg(preferences));
            case "cgb-games" -> openOptionPicker(MenuRoute.SYSTEM, id, "CGB GAMES",
                    List.of(new AndroidMenuModel.ChoiceValue("auto", "AUTO"),
                            new AndroidMenuModel.ChoiceValue("cgb", "CGB")),
                    systemCgb(preferences));
            case "bootstrap" -> openOptionPicker(MenuRoute.SYSTEM, id, "BOOTSTRAP",
                    List.of(new AndroidMenuModel.ChoiceValue("skip", "SKIP"),
                            new AndroidMenuModel.ChoiceValue("fast-forward", "FAST-FORWARD"),
                            new AndroidMenuModel.ChoiceValue("full", "FULL")),
                    systemBootstrap(preferences));
            case "execution-mode" -> openOptionPicker(MenuRoute.SYSTEM, id,
                    "MODE",
                    List.of(new AndroidMenuModel.ChoiceValue("accuracy", "ACCURACY"),
                            new AndroidMenuModel.ChoiceValue("performance", "PERFORMANCE")),
                    executionMode(preferences));
            default -> { }
        }
    }

    private void handleDisplayItem(String id) {
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        switch (id) {
            case "sgb-border" -> {
                boolean enabled = !preferences.getBoolean(PREF_DISPLAY_BORDER, false);
                preferences.edit().putBoolean(PREF_DISPLAY_BORDER, enabled).apply();
                if (runtime != null) {
                    runtime.setSgbBorder(enabled);
                }
            }
            case "dmg-colors" -> openOptionPicker(MenuRoute.DISPLAY, id, "DMG COLORS",
                    List.of(new AndroidMenuModel.ChoiceValue("green", "GREEN"),
                            new AndroidMenuModel.ChoiceValue("grey", "GREY")),
                    displayColors(preferences));
            default -> { }
        }
        refreshMenuPages();
    }

    private void openOptionPicker(MenuRoute origin, String originId, String title,
            List<AndroidMenuModel.ChoiceValue> choices, String selectedToken) {
        optionSession = new ChoiceSession(origin, originId, title, choices, selectedToken);
        refreshMenuPages();
        menuController.push(MenuRoute.OPTION_PICKER);
    }

    private void handleOptionPickerItem(String id) {
        ChoiceSession session = optionSession;
        if (session == null || id == null || !id.startsWith("choice:")) {
            return;
        }
        String token = id.substring("choice:".length());
        AndroidMenuModel.ChoiceValue choice = session.choices().stream()
                .filter(candidate -> candidate.token().equals(token))
                .findFirst().orElse(null);
        if (choice == null || !choice.enabled() || "unavailable".equals(token)) {
            return;
        }
        optionSession = null;
        menuController.back();
        applyChoice(session, token);
        refreshMenuPages();
    }

    private void applyChoice(ChoiceSession session, String token) {
        SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit();
        AndroidEmulationRuntime active = runtime;
        switch (session.originId()) {
            case "dmg-games" -> {
                edit.putString(PREF_SYSTEM_DMG, token).apply();
                if (active != null) active.setSystemSelection("dmg-games", token);
            }
            case "cgb-games" -> {
                edit.putString(PREF_SYSTEM_CGB, token).apply();
                if (active != null) active.setSystemSelection("cgb-games", token);
            }
            case "bootstrap" -> {
                edit.putString(PREF_SYSTEM_BOOTSTRAP, token).apply();
                if (active != null) active.setSystemSelection("bootstrap", token);
            }
            case "dmg-colors" -> {
                edit.putString(PREF_DISPLAY_COLORS, token).apply();
                if (active != null) active.setDisplayGrayscale("grey".equals(token));
            }
            case "execution-mode" -> {
                String selected = DiagnosticsOptions.executionModeValue(
                        DiagnosticsOptions.parseExecutionMode(token));
                edit.putString(PREF_EXECUTION_MODE, selected).apply();
                if (active != null) {
                    active.setSystemSelection("execution-mode", selected);
                }
            }

            case "camera" -> applyCameraChoice(token);
            case "gamepad" -> applyGamepadChoice(token);
            default -> { }
        }
    }

    private void applyGamepadChoice(String token) {
        getPreferences(MODE_PRIVATE).edit().putString(PREF_GAMEPAD_SELECTION, token).apply();
        if (runtime != null) {
            runtime.setGamepadSelection(token);
        }
    }

    private void applyCameraChoice(String token) {
        String selected = switch (token) {
            case "front", "rear" -> token;
            default -> "off";
        };
        getPreferences(MODE_PRIVATE).edit().putString(PREF_CAMERA_SELECTION, selected)
                .putBoolean("devices.camera", !"off".equals(selected)).apply();
        if (runtime != null) {
            runtime.setCameraLens(selected);
        }
        if ("off".equals(selected)) {
            if (runtime != null) runtime.setCameraEnabled(false);
            return;
        }
        boolean granted = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (runtime != null) runtime.setCameraEnabled(true);
            optionalDevicesStatus = "CAMERA ENABLED";
            return;
        }
        // The picker has already returned to its origin. Capture that exact stack before
        // replacing it with the native permission surface, so denial restores the same page.
        requestCameraPermissionForCurrentMenu();
    }

    private void requestCameraPermissionForCurrentMenu() {
        MenuStackSnapshot restoreStack = menuController.snapshot();
        externalSurface = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.CAMERA_PERMISSION, CAMERA_PERMISSION_REQUEST,
                restoreStack, menuPauseOwned,
                MenuExternalSurfaceState.RestorePolicy.ALWAYS);
        clearLegacyCameraPermissionFallback();
        menuPauseOwned = false;
        menuController.hide();
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    private void handleTouchItem(String id) {
        if (touchDraft == null) {
            touchDraft = AndroidMenuModel.touchDraft(video.touchLayout());
        }
        switch (id) {
            case "haptics" -> {
                // Haptics is a small, self-contained setting: apply it to the running
                // surface and persist it as soon as the row changes.
                touchDraft = touchDraft.toggleHaptics();
                video.updateTouchLayout(touchDraft.layout());
            }
            case "controller-mapping" -> {
                // A controller may disconnect between page rendering and activation.
                if (activeControllerAvailable()) {
                    menuController.push(MenuRoute.CONTROLLER_MAPPING);
                }
            }
            default -> { }
        }
        refreshMenuPages();
    }

    /** Persists the currently visible audio values so leaving the page never discards a change. */
    private void persistAudioDraft() {
        if (audioDraft == null) {
            return;
        }
        getPreferences(MODE_PRIVATE).edit()
                .putInt("audio.volume", audioDraft.volume())
                .putBoolean("audio.muted", audioDraft.muted()).apply();
        if (runtime != null) {
            runtime.setAudioVolume(audioDraft.volume());
            runtime.setAudioMuted(audioDraft.muted());
        }
    }

    private void handleOptionalDevicesItem(AndroidEmulationRuntime active, String id) {
        if ("camera".equals(id)) {
            String selected = cameraSelection(getPreferences(MODE_PRIVATE));
            ArrayList<AndroidMenuModel.ChoiceValue> choices = new ArrayList<>(cameraChoices());
            if (choices.stream().noneMatch(choice -> choice.token().equals(selected))) {
                choices.add(new AndroidMenuModel.ChoiceValue(selected, "UNAVAILABLE", false));
            }
            openOptionPicker(MenuRoute.OPTIONAL_DEVICES, id, "CAMERA", choices, selected);
            return;
        }
        if ("gamepad".equals(id)) {
            SharedPreferences preferences = getPreferences(MODE_PRIVATE);
            String selected = gamepadSelection(preferences);
            ArrayList<AndroidMenuModel.ChoiceValue> choices = new ArrayList<>();
            choices.add(new AndroidMenuModel.ChoiceValue("none", "OFF"));
            choices.add(new AndroidMenuModel.ChoiceValue("auto", "AUTO"));
            AndroidInputRouter input = runtime == null ? null : runtime.input();
            if (input != null) {
                for (AndroidInputRouter.ControllerChoice choice : input.controllerChoices()) {
                    choices.add(new AndroidMenuModel.ChoiceValue(choice.token(), choice.label()));
                }
            }
            boolean listed = choices.stream().anyMatch(choice -> choice.token().equals(selected));
            if (!listed && !"none".equals(selected) && !"auto".equals(selected)) {
                choices.add(new AndroidMenuModel.ChoiceValue(selected, "UNAVAILABLE", false));
            }
            openOptionPicker(MenuRoute.OPTIONAL_DEVICES, id, "GAMEPAD", choices, selected);
            return;
        }
        if ("gps".equals(id)) {
            SharedPreferences preferences = getPreferences(MODE_PRIVATE);
            boolean enabled = preferences.getBoolean(PREF_GPS_ENABLED, false);
            if (enabled) {
                preferences.edit().putBoolean(PREF_GPS_ENABLED, false).apply();
                if (runtime != null) {
                    runtime.setGpsEnabled(false);
                }
                optionalDevicesStatus = "GPS DISABLED";
                refreshMenuPages();
                return;
            }
            if (locationPermissionGranted()) {
                preferences.edit().putBoolean(PREF_GPS_ENABLED, true).apply();
                if (runtime != null) {
                    runtime.setGpsEnabled(true);
                }
                optionalDevicesStatus = "GPS ENABLED";
                refreshMenuPages();
                return;
            }
            requestGpsPermissionForCurrentMenu();
            return;
        }
        if (devicesDraft == null) {
            devicesDraft = loadDevicesDraft();
        }
        switch (id) {
            case "rumble" -> devicesDraft = devicesDraft.toggleRumble();
            case "live-camera" -> devicesDraft = devicesDraft.toggleCamera();
            case "game-boy-printer" -> devicesDraft = devicesDraft.togglePrinter();
            case "calibrate-tilt" -> {
                if (active != null) {
                    active.calibrateTilt();
                    optionalDevicesStatus = "TILT CALIBRATES ON NEXT SAMPLE";
                } else {
                    optionalDevicesStatus = "NO GAME";
                }
            }
            case "preview-printer-paper" -> openPrinterPaper();
            case "export-share-paper" -> {
                if (runtime != null && AndroidMenuModel.printerPreviewReady(printerPreview)) {
                    showConfirmation(ConfirmVariant.EXPORT_PRINTER, -1);
                }
            }
            case "save-devices" -> saveOptionalDevices();
            case "cancel-devices" -> {
                devicesDraft = null;
                menuController.back();
            }
            default -> { }
        }
        refreshMenuPages();
    }

    private void requestGpsPermissionForCurrentMenu() {
        MenuStackSnapshot restoreStack = menuController.snapshot();
        externalSurface = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.GPS_PERMISSION, GPS_PERMISSION_REQUEST,
                restoreStack, menuPauseOwned,
                MenuExternalSurfaceState.RestorePolicy.ALWAYS);
        menuPauseOwned = false;
        menuController.hide();
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, GPS_PERMISSION_REQUEST);
    }

    private boolean locationPermissionGranted() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void completeGpsPermission(MenuExternalSurfaceState pending, int requestCode) {
        if (externalSurface != pending || !pending.active()
                || pending.action() != MenuExternalSurfaceState.Action.GPS_PERMISSION
                || pending.requestCode() != requestCode || pending.restoreRequested()) {
            return;
        }
        boolean granted = locationPermissionGranted();
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_GPS_ENABLED, granted).apply();
        if (runtime != null) {
            runtime.setGpsEnabled(granted);
        }
        optionalDevicesStatus = granted ? "GPS ENABLED" : "GPS DENIED / DISABLED";
        externalSurface = pending.afterResult(granted);
        restoreExternalSurfaceIfRequested();
        refreshMenuPages();
    }

    private void saveOptionalDevices() {
        if (devicesDraft == null) {
            return;
        }
        AndroidMenuModel.DevicesDraft committed = devicesDraft;
        AndroidMenuModel.DevicesCommit plan = AndroidMenuModel.commitDevices(committed,
                checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED);
        getPreferences(MODE_PRIVATE).edit()
                .putBoolean("devices.rumble", plan.rumble())
                .putBoolean("devices.printer", plan.printer())
                .putBoolean("devices.camera", plan.persistedCamera()).apply();
        if (runtime != null) {
            runtime.setRumbleEnabled(plan.rumble());
            runtime.setPrinterEnabled(plan.printer());
            runtime.setCameraEnabled(plan.cameraEnabled());
        }
        devicesDraft = null;
        if (!plan.requestCameraPermission()) {
            menuController.back();
            return;
        }
        // A normal Devices stack returns to its parent after Save. A direct/restored Devices
        // route has no parent: backing out would resume the game before the permission surface
        // can take over its pause ownership. Keep that route as the restore target instead.
        MenuStackSnapshot restoreStack = menuController.snapshot();
        if (restoreStack.frames().size() > 1) {
            menuController.back();
            restoreStack = menuController.snapshot();
        }
        externalSurface = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.CAMERA_PERMISSION, CAMERA_PERMISSION_REQUEST,
                restoreStack, menuPauseOwned,
                MenuExternalSurfaceState.RestorePolicy.ALWAYS);
        clearLegacyCameraPermissionFallback();
        menuPauseOwned = false;
        menuController.hide();
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    private void handleControllerItem(AndroidEmulationRuntime active, String id) {
        if (active == null) {
            return;
        }
        AndroidInputRouter input = active.input();
        eu.rekawek.coffeegb.core.joypad.Button target = mappingTarget(id);
        if (target != null) {
            if (input.beginCapture(target)) {
                menuController.setBackIntercepted(true);
            }
        } else if ("reset-controller".equals(id)) {
            input.resetActiveController();
        }
        refreshMenuPages();
    }

    private void handlePrinterPaperItem(String id) {
        switch (id) {
            case "clear-paper" -> {
                if (runtime != null && AndroidMenuModel.printerPreviewReady(printerPreview)) {
                    showConfirmation(ConfirmVariant.CLEAR_PRINTER, -1);
                }
            }
            case "export-share-paper" -> {
                if (runtime != null && AndroidMenuModel.printerPreviewReady(printerPreview)) {
                    showConfirmation(ConfirmVariant.EXPORT_PRINTER, -1);
                }
            }
            case "back" -> menuController.back();
            default -> { }
        }
    }

    private void handleDataMediaItem(String id) {
        switch (id) {
            case "import-battery" -> showConfirmation(ConfirmVariant.IMPORT_BATTERY, -1);
            case "export-battery" -> showConfirmation(ConfirmVariant.EXPORT_BATTERY, -1);
            case "import-state-0" -> showConfirmation(ConfirmVariant.IMPORT_STATE, -1);
            case "export-state-0" -> showConfirmation(ConfirmVariant.EXPORT_STATE, -1);
            case "export-screenshot" -> showConfirmation(ConfirmVariant.EXPORT_SCREENSHOT, -1);
            case "preview-printer-paper" -> openPrinterPaper();
            case "back" -> menuController.back();
            default -> { }
        }
    }

    private void handleAboutItem(String id) {
        if ("privacy-notices".equals(id) || "source-notices".equals(id)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
                aboutStatus = "OPENED IN BROWSER";
            } catch (ActivityNotFoundException failure) {
                aboutStatus = "NO BROWSER AVAILABLE";
            }
            refreshMenuPages();
        } else if ("back".equals(id)) {
            menuController.back();
        }
    }

    private void handleConfirmationItem(String id) {
        if ("cancel".equals(id)) {
            menuController.back();
        } else if ("confirm".equals(id)) {
            executeConfirmation();
        }
    }

    private void executeConfirmation() {
        ConfirmVariant variant = confirmVariant;
        int slot = confirmSlot;
        confirmVariant = null;
        confirmSlot = -1;
        AndroidEmulationRuntime active = runtime;
        if (variant == null) {
            menuController.back();
            return;
        }
        switch (variant) {
            case RESET -> {
                if (active != null) {
                    boolean releaseMenuPause = menuPauseOwned;
                    closeMenuWithoutResume();
                    active.reset(releaseMenuPause);
                }
            }
            case STOP -> {
                if (active != null) {
                    closeMenuWithoutResume();
                    active.stop();
                }
            }
            case OVERWRITE -> {
                if (active != null) {
                    active.saveSnapshot(slot);
                    menuController.back();
                    refreshStateSlotsAfterMutation();
                }
            }
            case DELETE -> {
                if (active != null) {
                    active.deleteSnapshot(slot);
                    menuController.back();
                    refreshStateSlotsAfterMutation();
                }
            }
            case CLEAR_PRINTER -> {
                if (active == null || !AndroidMenuModel.printerPreviewReady(printerPreview)) {
                    leavePrinterPaperConfirmation();
                    printerStatus = "NOTHING PRINTED";
                    refreshMenuPages();
                    return;
                }
                leavePrinterPaperConfirmation();
                printerPreview = MenuPreview.loading();
                active.clearPrinter();
                printerStatus = "PAPER CLEARED";
                refreshMenuPages();
                mainHandler.postDelayed(this::loadPrinterPreview, 100L);
            }
            case IMPORT_BATTERY, EXPORT_BATTERY, IMPORT_STATE, EXPORT_STATE,
                    EXPORT_SCREENSHOT, EXPORT_PRINTER -> {
                if (variant == ConfirmVariant.EXPORT_PRINTER
                        && (active == null
                        || !AndroidMenuModel.printerPreviewReady(printerPreview))) {
                    leavePrinterPaperConfirmation();
                    printerStatus = "NOTHING PRINTED";
                    refreshMenuPages();
                    return;
                }
                menuController.back();
                launchDocumentAction(variant.externalAction);
            }
        }
    }

    private void showStateMenu(StateMenuMode mode) {
        stateMenuMode = mode;
        stateSlotsLoading = true;
        stateSlots = List.of();
        refreshMenuPages();
        menuController.push(MenuRoute.SAVE_STATES);
        loadStateSlots();
    }

    private void showRecentGames() {
        recentGamesLoading = true;
        recentGames = List.of();
        recentGamesCatalogGeneration++;
        refreshMenuPages();
        menuController.push(MenuRoute.RECENT_GAMES);
        AndroidEmulationRuntime active = runtime;
        if (active == null) {
            recentGamesLoading = false;
            refreshMenuPages();
            return;
        }
        long generation = recentGamesCatalogGeneration;
        long ownerGeneration = lifecycleGeneration;
        active.requestRecentGames(games -> {
            if (generation != recentGamesCatalogGeneration
                    || ownerGeneration != lifecycleGeneration
                    || runtime != active
                    || menuController == null || !menuController.visible()
                    || menuController.route() != MenuRoute.RECENT_GAMES) {
                return;
            }
            recentGames = List.copyOf(games);
            recentGamesLoading = false;
            refreshMenuPages();
        });
    }

    private void loadStateSlots() {
        long generation = ++stateCatalogGeneration;
        long ownerGeneration = lifecycleGeneration;
        AndroidEmulationRuntime active = runtime;
        stateSlotsLoading = true;
        stateSlots = List.of();
        refreshMenuPages();
        if (active != null) {
            long sessionGeneration = active.state().sessionGeneration();
            active.listStateSlots(slots -> {
                if (generation != stateCatalogGeneration
                        || ownerGeneration != lifecycleGeneration
                        || runtime != active
                        || active.state().sessionGeneration() != sessionGeneration
                        || menuController == null
                        || !menuController.visible()
                        || menuController.route() != MenuRoute.SAVE_STATES) {
                    return;
                }
                stateSlots = List.copyOf(slots);
                stateSlotsLoading = false;
                refreshMenuPages();
            });
        } else {
            stateSlotsLoading = false;
            refreshMenuPages();
        }
    }

    private void showConfirmation(ConfirmVariant variant, int slot) {
        confirmVariant = variant;
        confirmSlot = slot;
        refreshMenuPages();
        menuController.push(MenuRoute.CONFIRM_ACTION);
    }

    private void openSystem(String preferredFocus) {
        systemPreferredFocus = preferredFocus;
        refreshMenuPages();
        menuController.push(MenuRoute.SYSTEM);
    }

    private void openPrinterPaper() {
        if (menuController == null || !menuController.visible()) {
            return;
        }
        printerPaperEntryPending = true;
        printerPaperEntryParent = menuController.route();
        printerPreview = MenuPreview.loading();
        printerStatus = "READING BOUNDED PREVIEW";
        refreshMenuPages();
        loadPrinterPreview();
    }

    private void loadPrinterPreview() {
        int generation = ++printerPreviewGeneration;
        long ownerGeneration = lifecycleGeneration;
        AndroidEmulationRuntime active = runtime;
        if (active == null) {
            cancelPendingPrinterPaperEntry();
            printerPreview = MenuPreview.empty();
            printerStatus = "NO GAME";
            refreshMenuPages();
            return;
        }
        printerPreview = MenuPreview.loading();
        refreshMenuPages();
        active.previewPrinter(preview -> {
            if (generation != printerPreviewGeneration
                    || ownerGeneration != lifecycleGeneration || runtime != active) {
                return;
            }
            printerPreview = preview;
            printerStatus = preview.state() == MenuPreview.State.EMPTY
                    ? "NOTHING PRINTED" : "BOUNDED PREVIEW / FULL EXPORT";
            boolean openWhenReady = printerPaperEntryPending
                    && AndroidMenuModel.printerPreviewReady(preview)
                    && menuController != null && menuController.visible()
                    && menuController.route() == printerPaperEntryParent;
            cancelPendingPrinterPaperEntry();
            refreshMenuPages();
            restoreDeferredPaperFocusIfReady();
            if (openWhenReady) {
                menuController.push(MenuRoute.PRINTER_PAPER);
            }
        });
    }

    private void cancelPendingPrinterPaperEntry() {
        printerPaperEntryPending = false;
        printerPaperEntryParent = null;
    }

    private void leavePrinterPaperConfirmation() {
        menuController.back();
        if (menuController.route() == MenuRoute.PRINTER_PAPER) {
            menuController.back();
        }
    }

    private void resumeAndClose(AndroidEmulationRuntime active) {
        menuPauseOwned = false;
        menuController.hide();
        active.resume();
    }

    private void closeMenuWithoutResume() {
        menuPauseOwned = false;
        suspendedMenu = MenuStackSnapshot.hidden();
        menuController.hide();
    }

    private void openRomFromMenu() {
        launchDocumentAction(MenuExternalSurfaceState.Action.OPEN_ROM);
    }

    private void launchDocumentAction(MenuExternalSurfaceState.Action action) {
        if (runtime == null || menuController == null || !menuController.visible()
                || externalSurface.active()) {
            return;
        }
        if (action == MenuExternalSurfaceState.Action.EXPORT_PRINTER_SHARE
                && printerPreview.state() != MenuPreview.State.READY) {
            printerStatus = "NOTHING PRINTED";
            refreshMenuPages();
            return;
        }
        Intent intent;
        int requestCode;
        MenuExternalSurfaceState.RestorePolicy policy;
        switch (action) {
            case OPEN_ROM -> {
                intent = openRomIntent();
                requestCode = OPEN_ROM_REQUEST;
                policy = MenuExternalSurfaceState.RestorePolicy.ON_CANCEL;
            }
            case IMPORT_BATTERY -> {
                intent = importIntent();
                requestCode = IMPORT_BATTERY_REQUEST;
                policy = MenuExternalSurfaceState.RestorePolicy.ALWAYS;
            }
            case EXPORT_BATTERY -> {
                intent = exportIntent("application/octet-stream", "battery.sav");
                requestCode = EXPORT_BATTERY_REQUEST;
                policy = MenuExternalSurfaceState.RestorePolicy.ALWAYS;
            }
            case IMPORT_STATE_0 -> {
                intent = importIntent();
                requestCode = IMPORT_STATE_REQUEST;
                policy = MenuExternalSurfaceState.RestorePolicy.ALWAYS;
            }
            case EXPORT_STATE_0 -> {
                intent = exportIntent("application/octet-stream", "slot-0.cgbstate");
                requestCode = EXPORT_STATE_REQUEST;
                policy = MenuExternalSurfaceState.RestorePolicy.ALWAYS;
            }
            case EXPORT_SCREENSHOT -> {
                intent = exportIntent("image/png", "coffee-gb.png");
                requestCode = EXPORT_SCREENSHOT_REQUEST;
                policy = MenuExternalSurfaceState.RestorePolicy.ALWAYS;
            }
            case EXPORT_PRINTER_SHARE -> {
                intent = exportIntent("image/png", "coffee-gb-printer.png")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                requestCode = EXPORT_PRINTER_REQUEST;
                policy = MenuExternalSurfaceState.RestorePolicy.ALWAYS;
            }
            case CAMERA_PERMISSION, GPS_PERMISSION -> {
                return;
            }
            default -> throw new IllegalStateException("Unknown native action " + action);
        }
        MenuStackSnapshot stack = menuController.snapshot();
        externalSurface = MenuExternalSurfaceState.launched(
                action, requestCode, stack, menuPauseOwned, policy);
        menuPauseOwned = false;
        menuController.hide();
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException failure) {
            externalSurface = externalSurface.afterResult(false);
            restoreExternalSurfaceIfRequested();
        }
    }

    private Intent openRomIntent() {
        return RomPickerIntents.create(this);
    }

    private static Intent importIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private static Intent exportIntent(String type, String title) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type)
                .putExtra(Intent.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!externalSurface.active() || externalSurface.requestCode() != requestCode) {
            return;
        }
        Uri uri = data == null ? null : data.getData();
        boolean successful = resultCode == RESULT_OK && uri != null;
        MenuExternalSurfaceState.Action action = externalSurface.action();
        boolean releaseMenuPause = externalSurface.pauseOwned();
        externalSurface = externalSurface.afterResult(successful);
        if (!successful) {
            restoreExternalSurfaceIfRequested();
            return;
        }
        PendingDocumentResult result = new PendingDocumentResult(
                action, requestCode, uri, data.getFlags(), releaseMenuPause);
        if (runtime == null) {
            pendingDocumentResult = result;
            return;
        }
        dispatchDocumentResult(result);
        restoreExternalSurfaceIfRequested();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            MenuExternalSurfaceState pending = externalSurface;
            clearLegacyCameraPermissionFallback();
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            completeCameraPermission(pending, requestCode, granted);
        } else if (requestCode == GPS_PERMISSION_REQUEST) {
            completeGpsPermission(externalSurface, requestCode);
        }
    }

    private void postLegacyCameraPermissionFallback() {
        MenuExternalSurfaceState pending = legacyCameraPermissionFallbackSurface;
        if (pending == null || legacyCameraPermissionFallbackPosted) {
            return;
        }
        legacyCameraPermissionFallbackPosted = true;
        int requestCode = pending.requestCode();
        mainHandler.post(() -> completeReturnedLegacyCameraPermission(pending, requestCode));
    }

    private void completeReturnedLegacyCameraPermission(MenuExternalSurfaceState pending,
            int requestCode) {
        if (!activityResumed || isFinishing() || isDestroyed()
                || externalSurface != pending
                || requestCode != CAMERA_PERMISSION_REQUEST
                || pending.requestCode() != requestCode
                || pending.restoreRequested()) {
            return;
        }
        boolean granted = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        completeCameraPermission(pending, requestCode, granted);
    }

    private void completeCameraPermission(MenuExternalSurfaceState pending, int requestCode,
            boolean granted) {
        if (externalSurface != pending || !ownsCameraPermissionSurface(pending)
                || pending.requestCode() != requestCode || pending.restoreRequested()) {
            return;
        }
        clearLegacyCameraPermissionFallback();
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        String selected = cameraSelection(preferences);
        if (!granted) {
            selected = "off";
        }
        preferences.edit().putBoolean("devices.camera", granted)
                .putString(PREF_CAMERA_SELECTION, selected).apply();
        if (runtime != null) {
            runtime.setCameraLens(selected);
            runtime.setCameraEnabled(granted && !"off".equals(selected));
        }
        optionalDevicesStatus = granted ? "CAMERA ENABLED" : "CAMERA DENIED / DISABLED";
        externalSurface = pending.afterResult(granted);
        restoreExternalSurfaceIfRequested();
        refreshMenuPages();
    }

    private void armLegacyCameraPermissionFallback(MenuExternalSurfaceState pending) {
        if (!unresolvedLegacyCameraPermissionSurface(pending)) {
            return;
        }
        legacyCameraPermissionFallbackSurface = pending;
        legacyCameraPermissionFallbackPosted = false;
    }

    private void clearLegacyCameraPermissionFallback() {
        legacyCameraPermissionFallbackSurface = null;
        legacyCameraPermissionFallbackPosted = false;
    }

    private static boolean unresolvedLegacyCameraPermissionSurface(
            MenuExternalSurfaceState pending) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.O
                && ownsCameraPermissionSurface(pending)
                && pending.requestCode() == CAMERA_PERMISSION_REQUEST
                && !pending.restoreRequested();
    }

    private static boolean ownsCameraPermissionSurface(MenuExternalSurfaceState pending) {
        return pending.active()
                && pending.action() == MenuExternalSurfaceState.Action.CAMERA_PERMISSION;
    }

    private void dispatchPendingDocumentResult() {
        PendingDocumentResult result = pendingDocumentResult;
        pendingDocumentResult = null;
        if (result != null && runtime != null) {
            dispatchDocumentResult(result);
        }
    }

    private void dispatchDocumentResult(PendingDocumentResult result) {
        AndroidEmulationRuntime active = runtime;
        if (active == null) {
            pendingDocumentResult = result;
            return;
        }
        switch (result.action()) {
            case OPEN_ROM -> active.openRom(
                    result.uri(), result.flags(), result.releaseMenuPause());
            case IMPORT_BATTERY -> active.importBattery(result.uri());
            case EXPORT_BATTERY -> active.exportBattery(result.uri());
            case IMPORT_STATE_0 -> active.importState(result.uri());
            case EXPORT_STATE_0 -> active.exportState(result.uri());
            case EXPORT_SCREENSHOT -> active.exportScreenshot(result.uri());
            case EXPORT_PRINTER_SHARE -> {
                long token = beginPrinterExportContinuation(result.uri());
                active.exportPrinter(result.uri(), new PrinterExportCompletion(
                        printerContinuationPreferences, token, this, lifecycleGeneration));
            }
            case CAMERA_PERMISSION, GPS_PERMISSION -> { }
        }
    }

    private long beginPrinterExportContinuation(Uri uri) {
        synchronized (MainActivity.class) {
            long previous = printerContinuationPreferences.getLong(
                    PRINTER_CONTINUATION_TOKEN, 0L);
            long token = previous == Long.MAX_VALUE ? 1L : previous + 1L;
            writePrinterExportContinuation(printerContinuationPreferences,
                    PrinterExportContinuation.pending(token, uri.toString()), true);
            return token;
        }
    }

    private void consumePrinterExportContinuation() {
        consumePrinterExportContinuation(-1L);
    }

    private void consumePrinterExportContinuation(long expectedGeneration) {
        if (!activityResumed || isFinishing() || isDestroyed()
                || (expectedGeneration >= 0L && expectedGeneration != lifecycleGeneration)) {
            return;
        }
        PrinterExportContinuation continuation;
        synchronized (MainActivity.class) {
            continuation = readPrinterExportContinuation(printerContinuationPreferences);
            if (!continuation.actionable()) {
                return;
            }
            writePrinterExportContinuation(printerContinuationPreferences,
                    PrinterExportContinuation.none(), false);
        }
        if (continuation.phase() == PrinterExportContinuation.Phase.FAILED) {
            printerStatus = "EXPORT FAILED";
            refreshMenuPages();
            return;
        }
        printerStatus = "EXPORTED / OPENING SHARE";
        refreshMenuPages();
        sharePrinter(Uri.parse(continuation.uri()));
    }

    private static PrinterExportContinuation readPrinterExportContinuation(
            SharedPreferences preferences) {
        return PrinterExportContinuation.restored(
                preferences.getLong(PRINTER_CONTINUATION_TOKEN, 0L),
                preferences.getString(PRINTER_CONTINUATION_URI, ""),
                preferences.getString(PRINTER_CONTINUATION_PHASE,
                        PrinterExportContinuation.Phase.NONE.name()));
    }

    private static void writePrinterExportContinuation(SharedPreferences preferences,
            PrinterExportContinuation continuation, boolean updateToken) {
        SharedPreferences.Editor editor = preferences.edit();
        if (updateToken) {
            editor.putLong(PRINTER_CONTINUATION_TOKEN, continuation.token());
        }
        if (continuation.phase() == PrinterExportContinuation.Phase.NONE) {
            editor.remove(PRINTER_CONTINUATION_URI)
                    .remove(PRINTER_CONTINUATION_PHASE);
        } else {
            editor.putString(PRINTER_CONTINUATION_URI, continuation.uri())
                    .putString(PRINTER_CONTINUATION_PHASE, continuation.phase().name());
        }
        editor.apply();
    }

    private void sharePrinter(Uri uri) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri);
        share.setClipData(ClipData.newRawUri("Game Boy Printer paper", uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "Share printer paper"));
        } catch (ActivityNotFoundException failure) {
            printerStatus = "NO SHARE HANDLER";
            refreshMenuPages();
        }
    }

    private void restoreExternalSurfaceIfRequested() {
        if (!externalSurface.restoreRequested() || runtime == null || menuController == null) {
            return;
        }
        MenuExternalSurfaceState restoring = externalSurface;
        MenuStackSnapshot safeMenu = withoutPrinterPaper(restoring.menuStack());
        externalSurface = MenuExternalSurfaceState.none();
        runtime.input().releaseAll();
        menuPauseOwned = restoring.pauseOwned();
        if (menuPauseOwned) {
            runtime.pause();
        }
        ensurePauseMenuSnapshot(safeMenu);
        refreshMenuPages();
        menuController.restore(safeMenu);
        deferFocusRestoreIfNeeded(safeMenu);
        refreshRestoredDynamicRoute(safeMenu);
    }

    private void restoreSuspendedMenu() {
        if (externalSurface.active() || !suspendedMenu.visible() || runtime == null
                || menuController == null || menuController.visible()) {
            return;
        }
        MenuStackSnapshot restoring = withoutPrinterPaper(suspendedMenu);
        suspendedMenu = MenuStackSnapshot.hidden();
        runtime.input().releaseAll();
        menuPauseOwned = suspendedMenuPauseOwned;
        suspendedMenuPauseOwned = false;
        if (menuPauseOwned) {
            runtime.pause();
        }
        ensurePauseMenuSnapshot(restoring);
        refreshMenuPages();
        menuController.restore(restoring);
        deferFocusRestoreIfNeeded(restoring);
        refreshRestoredDynamicRoute(restoring);
    }

    /** Restores the service-owned root capture before rebuilding any pause-stack child page. */
    private void ensurePauseMenuSnapshot(MenuStackSnapshot snapshot) {
        if (runtime == null || !stackContains(snapshot, MenuRoute.PAUSE_CONSOLE)) {
            return;
        }
        RuntimeState current = runtime.state();
        if (isPauseMenuTarget(current) && runtime.pauseMenuSnapshot() == null) {
            runtime.capturePauseMenuSnapshot();
        }
    }

    private void refreshRestoredDynamicRoute(MenuStackSnapshot snapshot) {
        if (stackContains(snapshot, MenuRoute.SAVE_STATES)) {
            loadStateSlots();
        }
        if (stackContains(snapshot, MenuRoute.RECENT_GAMES)) {
            recentGamesLoading = true;
            recentGamesCatalogGeneration++;
            mainHandler.post(this::reloadRecentGames);
        }
        if (stackContains(snapshot, MenuRoute.PRINTER_PAPER)) {
            deferredMenuFocusRestore = snapshot;
            mainHandler.post(this::loadPrinterPreview);
        }
    }

    private void restoreDeferredPaperFocusIfReady() {
        MenuStackSnapshot deferred = deferredMenuFocusRestore;
        if (!deferred.visible() || !stackContains(deferred, MenuRoute.PRINTER_PAPER)) {
            return;
        }
        if (printerPreview.state() != MenuPreview.State.READY) {
            if (printerPreview.state() == MenuPreview.State.EMPTY) {
                deferredMenuFocusRestore = MenuStackSnapshot.hidden();
            }
            return;
        }
        attemptDeferredFocusRestore();
    }

    private void deferFocusRestoreIfNeeded(MenuStackSnapshot desired) {
        if (menuController == null || !desired.visible()) {
            return;
        }
        MenuStackSnapshot current = menuController.snapshot();
        if (sameRoutePath(current, desired) && !sameFocusedItems(current, desired)) {
            deferredMenuFocusRestore = desired;
        }
    }

    private void attemptDeferredFocusRestore() {
        MenuStackSnapshot desired = deferredMenuFocusRestore;
        if (menuController == null || !desired.visible()) {
            return;
        }
        MenuStackSnapshot current = menuController.snapshot();
        if (!sameRoutePath(current, desired)) {
            deferredMenuFocusRestore = MenuStackSnapshot.hidden();
            return;
        }
        MenuStackSnapshot.Frame desiredFrame = desired.frames().get(desired.frames().size() - 1);
        boolean enabled = false;
        for (MenuPresentation.Item item : menuController.presentation().items()) {
            if (desiredFrame.focusedItemId().equals(item.id()) && item.enabled()) {
                enabled = true;
                break;
            }
        }
        if (!enabled) {
            return;
        }
        deferredMenuFocusRestore = MenuStackSnapshot.hidden();
        menuController.restore(desired);
    }

    private MenuStackSnapshot snapshotForPersistence() {
        MenuStackSnapshot current = menuController == null
                ? MenuStackSnapshot.hidden() : menuController.snapshot();
        return deferredMenuFocusRestore.visible()
                && sameRoutePath(current, deferredMenuFocusRestore)
                ? deferredMenuFocusRestore : current;
    }

    private static boolean sameRoutePath(MenuStackSnapshot left, MenuStackSnapshot right) {
        if (left.frames().size() != right.frames().size()) {
            return false;
        }
        for (int index = 0; index < left.frames().size(); index++) {
            if (left.frames().get(index).route() != right.frames().get(index).route()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameFocusedItems(MenuStackSnapshot left, MenuStackSnapshot right) {
        if (!sameRoutePath(left, right)) {
            return false;
        }
        for (int index = 0; index < left.frames().size(); index++) {
            if (!left.frames().get(index).focusedItemId()
                    .equals(right.frames().get(index).focusedItemId())) {
                return false;
            }
        }
        return true;
    }

    private void refreshStateSlotsAfterMutation() {
        loadStateSlots();
    }

    private AndroidStateSlot stateSlot(int index) {
        for (AndroidStateSlot slot : stateSlots) {
            if (slot.index() == index) {
                return slot;
            }
        }
        return null;
    }

    private void refreshMenuPages() {
        if (menuController == null || video == null) {
            return;
        }
        AndroidMenuModel.AudioDraft audio = audioDraft == null ? loadAudioDraft() : audioDraft;
        AndroidMenuModel.TouchDraft touch = touchDraft == null
                ? AndroidMenuModel.touchDraft(video.touchLayout()) : touchDraft;
        boolean controllerAvailable = activeControllerAvailable();
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        String selectedCamera = cameraSelection(preferences);
        String camera = cameraChoices().stream().anyMatch(
                choice -> choice.token().equals(selectedCamera)) ? selectedCamera : "unavailable";
        String gamepad = gamepadSelection(preferences);
        ArrayList<AndroidMenuModel.ChoiceValue> gamepadChoices = new ArrayList<>();
        if (runtime != null) {
            for (AndroidInputRouter.ControllerChoice choice : runtime.input().controllerChoices()) {
                gamepadChoices.add(new AndroidMenuModel.ChoiceValue(choice.token(), choice.label()));
            }
        }
        menuController.setPages(List.of(
                pausePage(), statePage(), recentGamesPage(), libraryPage(), chooseRomPage(),
                AndroidMenuModel.settingsPage(),
                AndroidMenuModel.audioPage(audio),
                AndroidMenuModel.displayPage(
                        preferences.getBoolean(PREF_DISPLAY_BORDER, false),
                        "grey".equals(displayColors(preferences))),
                AndroidMenuModel.touchPage(touch, controllerAvailable), controllerPage(),
                AndroidMenuModel.optionalDevicesPage(camera, gamepad,
                        preferences.getBoolean(PREF_GPS_ENABLED, false), gamepadChoices),
                AndroidMenuModel.optionPickerPage(
                        optionSession == null ? "SELECT OPTION" : optionSession.title(),
                        optionSession == null ? List.of() : optionSession.choices(),
                        optionSession == null ? null : optionSession.selectedToken()),
                AndroidMenuModel.printerPaperPage(printerPreview, printerStatus),
                AndroidMenuModel.systemPage(systemDmg(preferences), systemCgb(preferences),
                        systemBootstrap(preferences), executionMode(preferences),
                        systemPreferredFocus),
                AndroidMenuModel.dataMediaPage(AndroidMenuModel.transferAvailability(
                        runtime != null, observedState)),
                AndroidMenuModel.aboutPage(BuildConfig.VERSION_NAME, aboutStatus),
                confirmationPage()));
        attemptDeferredFocusRestore();
    }

    private void reloadRecentGames() {
        AndroidEmulationRuntime active = runtime;
        if (active == null || menuController == null || !menuController.visible()
                || menuController.route() != MenuRoute.RECENT_GAMES) {
            return;
        }
        long generation = recentGamesCatalogGeneration;
        long ownerGeneration = lifecycleGeneration;
        active.requestRecentGames(games -> {
            if (generation != recentGamesCatalogGeneration
                    || ownerGeneration != lifecycleGeneration
                    || runtime != active
                    || menuController == null || !menuController.visible()
                    || menuController.route() != MenuRoute.RECENT_GAMES) {
                return;
            }
            recentGames = List.copyOf(games);
            recentGamesLoading = false;
            refreshMenuPages();
        });
    }

    private MenuPageSpec pausePage() {
        AndroidEmulationRuntime active = runtime;
        PauseMenuSnapshot snapshot = active == null ? null : active.pauseMenuSnapshot();
        String title = snapshot == null ? (observedState.romTitle().isBlank()
                ? "NO GAME" : observedState.romTitle()) : snapshot.romTitle();
        String elapsed = snapshot == null ? PauseMenuSnapshot.formatPlayTime(observedState.playTimeNanos())
                : snapshot.formattedPlayTime();
        boolean battery = snapshot != null ? snapshot.batterySaveActive()
                : observedState.batterySaveActive();
        MenuPreview preview = snapshot == null ? MenuPreview.empty() : snapshot.preview();
        return new MenuPageSpec(MenuRoute.PAUSE_CONSOLE, "COFFEE GB", "", "", title,
                List.of("PLAY TIME", elapsed,
                        battery ? "BATTERY SAVE ACTIVE" : "NO BATTERY SAVE"),
                List.of(
                        button("resume", "RESUME", "", true),
                        button("save-state", "SAVE STATE", "", runtime != null),
                        button("load-state", "LOAD STATE", "", runtime != null),
                        button("open-rom", "OPEN ROM", "", runtime != null),
                        button("reset", "RESET GAME", "CONFIRM", runtime != null),
                        button("settings", "SETTINGS", "OPEN", true),
                        button("recent-games", "RECENT GAMES", "OPEN", runtime != null)),
                1, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), null, preview);
    }

    private MenuPageSpec statePage() {
        String preferred = null;
        if (menuController != null && menuController.visible()
                && menuController.route() == MenuRoute.SAVE_STATES) {
            MenuPresentation current = menuController.presentation();
            if (current.focusedIndex() >= 0 && current.focusedIndex() < current.items().size()) {
                preferred = current.items().get(current.focusedIndex()).id();
            }
        }
        return statePage(statePreview(preferred), preferred);
    }

    private MenuPreview statePreview(String preferredFocus) {
        int slot = preferredFocus == null ? StateRef.MIN_SLOT
                : parseSlot(preferredFocus.replace("slot:", ""));
        AndroidStateSlot selected = stateSlot(slot);
        return selected == null ? MenuPreview.empty() : selected.preview();
    }

    private MenuPageSpec statePage(MenuPreview preview, String preferredFocus) {
        List<MenuPageSpec.Item> items = stateMenuItems(stateSlots);
        String mode = stateMenuMode == StateMenuMode.SAVE ? "SAVE" : "LOAD";
        return new MenuPageSpec(MenuRoute.SAVE_STATES, "COFFEE GB", mode + " STATES", "", "",
                stateSavedAtLines(stateSlot(preferredFocus == null
                        ? StateRef.MIN_SLOT : parseSlot(preferredFocus.replace("slot:", "")))),
                items, 1,
                List.of("D-PAD MOVE", "A " + mode, "B BACK"),
                preferredFocus == null ? "slot:" + StateRef.MIN_SLOT : preferredFocus, preview);
    }

    private MenuPageSpec recentGamesPage() {
        String preferred = null;
        if (menuController != null && menuController.visible()
                && menuController.route() == MenuRoute.RECENT_GAMES) {
            MenuPresentation current = menuController.presentation();
            if (current.focusedIndex() >= 0 && current.focusedIndex() < current.items().size()) {
                preferred = current.items().get(current.focusedIndex()).id();
            }
        }
        ArrayList<MenuPageSpec.RecentGame> items = new ArrayList<>(recentGames.size());
        for (AndroidEmulationRuntime.RecentGame game : recentGames) {
            items.add(new MenuPageSpec.RecentGame("recent:" + game.token(), game.name(),
                    formatRecentPlayed(game.lastPlayedMillis()), true, game.preview()));
        }
        // A transient empty result is intentionally represented as the same safe status row as
        // an empty catalog. The callback replaces it atomically once the private catalog read is
        // complete, so there is never a URI or provider detail painted on-screen.
        return AndroidMenuModel.recentGamesPage(items, preferred, recentGamesLoading);
    }

    private static String formatRecentPlayed(long millis) {
        if (millis <= 0L) {
            return "";
        }
        try {
            return STATE_TIME_FORMAT.format(Instant.ofEpochMilli(millis));
        } catch (DateTimeException | ArithmeticException ignored) {
            return "";
        }
    }

    /** Stable state rows shared by SAVE and LOAD; persisted rows carry a reusable status label. */
    static List<MenuPageSpec.Item> stateMenuItems(List<AndroidStateSlot> catalog) {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (int index = STATE_MENU_MIN_SLOT; index <= STATE_MENU_MAX_SLOT; index++) {
            // Every stable slot remains focusable in both modes. LOAD treats an empty or
            // unavailable slot as a no-op; SAVE overwrites directly without confirmation.
            boolean used = catalogContainsLoadableSlot(catalog, index);
            // The reusable button detail distinguishes a persisted state from an empty but still
            // focusable slot without requiring a save-state-specific asset.
            items.add(MenuPageSpec.Item.button("slot:" + index, "SLOT " + index,
                    used ? "SAVED" : "", true));
        }
        return List.copyOf(items);
    }

    private static boolean catalogContainsLoadableSlot(List<AndroidStateSlot> catalog, int index) {
        for (AndroidStateSlot slot : catalog) {
            if (slot.index() == index && slot.loadable()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> stateSavedAtLines(AndroidStateSlot slot) {
        String formatted = formatStateSavedAt(slot == null ? null : slot.savedAt());
        return formatted == null ? List.of() : List.of(formatted);
    }

    static String formatStateSavedAt(Instant savedAt) {
        if (savedAt == null) {
            return null;
        }
        try {
            return "SAVED " + STATE_TIME_FORMAT.format(savedAt);
        } catch (DateTimeException | ArithmeticException ignored) {
            // Metadata is optional; unsupported/corrupt values must not become a fake date.
            return null;
        }
    }

    private MenuPageSpec libraryPage() {
        return AndroidMenuModel.libraryPage(runtime != null);
    }

    private MenuPageSpec chooseRomPage() {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (RuntimeState.Selection selection : observedState.selections()) {
            items.add(button("archive:" + selection.token(), selection.label(), "A OPEN", true));
        }
        if (items.isEmpty()) {
            items.add(button("empty", "NO ROM CANDIDATES", "CANCEL", false));
        }
        items.add(button("cancel", "BACK TO LIBRARY", "CANCEL", true));
        return page(MenuRoute.CHOOSE_ROM, "COFFEE GB", "CHOOSE ROM", "", "ZIP CONTENTS",
                List.of("SELECT ONE TO OPEN", "A OPENS ROM", "B CANCELS PENDING ZIP"), items,
                List.of("D-PAD MOVE", "A CHOOSE", "B BACK"));
    }

    private MenuPageSpec controllerPage() {
        AndroidInputRouter input = runtime == null ? null : runtime.input();
        String name = input == null ? null : input.activeControllerName();
        Map<eu.rekawek.coffeegb.core.joypad.Button, String> labels = input == null
                ? Map.of() : input.effectiveKeyLabels();
        return AndroidMenuModel.controllerPage(name, labels,
                input == null ? null : input.captureTarget(),
                input != null && input.captureWaitingForRelease());
    }

    private boolean activeControllerAvailable() {
        AndroidInputRouter input = runtime == null ? null : runtime.input();
        return input != null && input.activeControllerName() != null;
    }

    private MenuPageSpec confirmationPage() {
        ConfirmVariant variant = confirmVariant == null ? ConfirmVariant.RESET : confirmVariant;
        return page(MenuRoute.CONFIRM_ACTION, "COFFEE GB", "CONFIRM ACTION", "", variant.label,
                List.of(variant.description),
                List.of(button("cancel", "CANCEL", "RETURN", true),
                        button("confirm", "CONFIRM", variant.label, true)),
                List.of("D-PAD MOVE", "A CHOOSE", "B BACK"));
    }

    private static MenuPageSpec page(MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines,
            List<MenuPageSpec.Item> items, List<String> hints) {
        return new MenuPageSpec(route, title, context, headerAction, sideHeading, sideLines,
                items, 1, hints);
    }

    private static MenuPageSpec.Item button(String id, String label, String detail,
            boolean enabled) {
        return MenuPageSpec.Item.button(id, label, detail, enabled);
    }

    private AndroidMenuModel.AudioDraft loadAudioDraft() {
        return AndroidMenuModel.audioDraft(
                getPreferences(MODE_PRIVATE).getInt("audio.volume", 100),
                getPreferences(MODE_PRIVATE).getBoolean("audio.muted", false));
    }

    private List<AndroidMenuModel.ChoiceValue> cameraChoices() {
        ArrayList<AndroidMenuModel.ChoiceValue> choices = new ArrayList<>();
        choices.add(new AndroidMenuModel.ChoiceValue("off", "OFF"));
        boolean rear = true;
        boolean front = true;
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager != null) {
                rear = false;
                front = false;
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    rear |= facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                    front |= facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                }
            }
        } catch (Exception ignored) {
            // CameraX remains the authority if the platform does not expose lens metadata.
            rear = true;
            front = true;
        }
        if (rear) choices.add(new AndroidMenuModel.ChoiceValue("rear", "REAR"));
        if (front) choices.add(new AndroidMenuModel.ChoiceValue("front", "FRONT"));
        return List.copyOf(choices);
    }

    private static String cameraSelection(SharedPreferences preferences) {
        String selected = preferences.getString(PREF_CAMERA_SELECTION, null);
        if (selected == null) {
            return preferences.getBoolean("devices.camera", false) ? "rear" : "off";
        }
        return "front".equalsIgnoreCase(selected) || "rear".equalsIgnoreCase(selected)
                ? selected.toLowerCase(java.util.Locale.US) : "off";
    }

    private static String gamepadSelection(SharedPreferences preferences) {
        String selected = preferences.getString(PREF_GAMEPAD_SELECTION, "auto");
        if (selected == null || selected.isBlank() || "AUTO".equalsIgnoreCase(selected)) {
            return "auto";
        }
        if ("OFF".equalsIgnoreCase(selected) || "none".equalsIgnoreCase(selected)) {
            return "none";
        }
        return selected;
    }

    private static String displayColors(SharedPreferences preferences) {
        return "grey".equalsIgnoreCase(preferences.getString(PREF_DISPLAY_COLORS, "green"))
                ? "grey" : "green";
    }

    private static String systemDmg(SharedPreferences preferences) {
        String value = preferences.getString(PREF_SYSTEM_DMG, "auto");
        if (value == null) {
            return "auto";
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "dmg", "cgb", "sgb" -> value.toLowerCase(java.util.Locale.ROOT);
            default -> "auto";
        };
    }

    private static String systemCgb(SharedPreferences preferences) {
        return "cgb".equalsIgnoreCase(preferences.getString(PREF_SYSTEM_CGB, "auto"))
                ? "cgb" : "auto";
    }

    private static String systemBootstrap(SharedPreferences preferences) {
        String value = preferences.getString(PREF_SYSTEM_BOOTSTRAP, "skip");
        if (value == null) {
            return "skip";
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "fast_forward", "fast-forward" -> "fast-forward";
            case "full" -> "full";
            default -> "skip";
        };
    }

    /** Reads the persisted core strategy; a new Android install starts in Performance mode. */
    private static String executionMode(SharedPreferences preferences) {
        return DiagnosticsOptions.executionModeValue(DiagnosticsOptions.parseExecutionMode(
                preferences.getString(PREF_EXECUTION_MODE, DEFAULT_EXECUTION_MODE)));
    }

    private static void applySystemSettings(AndroidEmulationRuntime runtime,
            SharedPreferences preferences) {
        runtime.setSystemSelection("dmg-games", systemDmg(preferences));
        runtime.setSystemSelection("cgb-games", systemCgb(preferences));
        runtime.setSystemSelection("bootstrap", systemBootstrap(preferences));
        runtime.setSystemSelection("execution-mode", executionMode(preferences));
    }

    private AndroidMenuModel.DevicesDraft loadDevicesDraft() {
        return new AndroidMenuModel.DevicesDraft(
                getPreferences(MODE_PRIVATE).getBoolean("devices.rumble", false),
                getPreferences(MODE_PRIVATE).getBoolean("devices.camera", false),
                getPreferences(MODE_PRIVATE).getBoolean("devices.printer", false));
    }

    private void cancelControllerCapture() {
        if (runtime != null) {
            runtime.input().cancelCapture();
        }
        if (menuController != null) {
            menuController.setBackIntercepted(false);
        }
    }

    private static eu.rekawek.coffeegb.core.joypad.Button mappingTarget(String id) {
        return switch (id) {
            case "map-a" -> eu.rekawek.coffeegb.core.joypad.Button.A;
            case "map-b" -> eu.rekawek.coffeegb.core.joypad.Button.B;
            case "map-start" -> eu.rekawek.coffeegb.core.joypad.Button.START;
            case "map-select" -> eu.rekawek.coffeegb.core.joypad.Button.SELECT;
            case "map-up" -> eu.rekawek.coffeegb.core.joypad.Button.UP;
            case "map-down" -> eu.rekawek.coffeegb.core.joypad.Button.DOWN;
            case "map-left" -> eu.rekawek.coffeegb.core.joypad.Button.LEFT;
            case "map-right" -> eu.rekawek.coffeegb.core.joypad.Button.RIGHT;
            default -> null;
        };
    }

    private static int parseSlot(String value) {
        try {
            int slot = Integer.parseInt(value);
            return slot >= StateRef.MIN_SLOT && slot <= StateRef.MAX_SLOT ? slot : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static long parseToken(String value) {
        try {
            long token = Long.parseLong(value);
            return token >= 0 ? token : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean isGameController(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static MenuKey menuKey(KeyEvent event) {
        return switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> MenuKey.A;
            case KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> MenuKey.B;
            case KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_FORWARD_DEL,
                    KeyEvent.KEYCODE_DEL -> MenuKey.SECONDARY;
            case KeyEvent.KEYCODE_BUTTON_START -> MenuKey.START;
            // Select is deliberately consumed by the portable menu but has no bound action.
            case KeyEvent.KEYCODE_BUTTON_SELECT -> MenuKey.SELECT;
            default -> null;
        };
    }

    @SuppressLint("GestureBackNavigation")
    private static boolean isSystemBack(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_BACK;
    }

    private void applyState(RuntimeState state) {
        if (state.generation() < observedGeneration) {
            return;
        }
        long previousSessionGeneration = observedState.sessionGeneration();
        if (diagnosticsOptions.enabled
                && pendingBenchmarkArm.onStateTransition(previousSessionGeneration, state)) {
            benchmarkAnchorRequested = false;
            benchmarkAnchorSessionGeneration = 0L;
        }
        observedGeneration = state.generation();
        if (state.flushPending() && !observedState.flushPending()
                && menuController != null && menuController.visible()
                && menuController.route() == MenuRoute.DATA_MEDIA) {
            deferredMenuFocusRestore = menuController.snapshot();
        }
        observedState = state;
        if (menuController != null) {
            menuController.setRootDismissAllowed(state.sessionGeneration() != 0L);
        }
        if (diagnosticsOptions.enabled && state.phase() == RuntimeState.Phase.PAUSED
                && !benchmarkAnchorRequested && runtime != null && video != null
                && state.sessionGeneration() > 0L
                && runtime.benchmarkPreconditionReady()) {
            benchmarkAnchorRequested = true;
            benchmarkAnchorSessionGeneration = state.sessionGeneration();
            AndroidEmulationRuntime active = runtime;
            long anchorSessionGeneration = benchmarkAnchorSessionGeneration;
            video.requestBenchmarkAnchor(success -> {
                active.benchmarkAnchorPosted(anchorSessionGeneration, success,
                        () -> runOnUiThread(() -> {
                            if (success && runtime == active
                                    && observedState.phase() == RuntimeState.Phase.PAUSED
                                    && observedState.sessionGeneration() == anchorSessionGeneration
                                    && benchmarkAnchorSessionGeneration
                                            == anchorSessionGeneration) {
                                armPendingBenchmarkIfReady(active, anchorSessionGeneration);
                            }
                        }));
            });
        }
        refreshMenuPages();
        if (runtime == null || menuController == null || externalSurface.active()) {
            return;
        }
        if (isGamePresentationState(state) && menuController.visible()
                && menuController.route() == MenuRoute.LIBRARY && !menuPauseOwned) {
            // A cold Activity starts on Library while the retained service is idle. If a game is
            // then opened without going through the document-picker callback (for example from an
            // intent or a recent-game launch), replace that automatic root with the game surface.
            menuController.hide();
        }
        if (!diagnosticsOptions.enabled && isIdlePresentationState(state)
                && !menuController.visible() && !suspendedMenu.visible()) {
            // A stopped service has no frame to present. Keep the first user-visible Activity
            // state useful instead of leaving the LCD aperture empty.
            menuPauseOwned = false;
            menuController.show(MenuRoute.LIBRARY);
        } else if (state.phase() == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION
                && !menuController.visible()) {
            menuPauseOwned = false;
            menuController.show(MenuRoute.CHOOSE_ROM);
        } else if (state.phase() == RuntimeState.Phase.AWAITING_RECENT_SELECTION
                && !menuController.visible()) {
            menuPauseOwned = false;
            menuController.show(MenuRoute.LIBRARY);
        }
    }

    private static boolean isIdlePresentationState(RuntimeState state) {
        return state.phase() == RuntimeState.Phase.STOPPED
                || state.phase() == RuntimeState.Phase.FAILED;
    }

    private static boolean isGamePresentationState(RuntimeState state) {
        return state.phase() == RuntimeState.Phase.OPENING
                || state.phase() == RuntimeState.Phase.LOADING
                || state.phase() == RuntimeState.Phase.RUNNING
                || state.phase() == RuntimeState.Phase.PAUSED;
    }

    /** Defers a singleTop arm token until the real anchor has completed on the renderer. */
    private void armPendingBenchmarkIfReady(AndroidEmulationRuntime active) {
        armPendingBenchmarkIfReady(active, observedState.sessionGeneration());
    }

    private void armPendingBenchmarkIfReady(
            AndroidEmulationRuntime active, long sessionGeneration) {
        if (!diagnosticsOptions.enabled || active == null
                || !pendingBenchmarkArm.pendingFor(sessionGeneration)
                || sessionGeneration <= 0L
                || observedState.phase() != RuntimeState.Phase.PAUSED
                || observedState.sessionGeneration() != sessionGeneration
                || !active.benchmarkAnchorReady(sessionGeneration)) {
            return;
        }
        String token = pendingBenchmarkArm.take(sessionGeneration);
        if (token != null) {
            active.armBenchmark(token, sessionGeneration);
        }
    }

    /** Generation-bound singleTop token latch; renderer completion may race a later intent. */
    static final class BenchmarkArmTokenLatch {
        private String token;
        private long sessionGeneration;

        synchronized void put(String token, long sessionGeneration) {
            this.token = token;
            this.sessionGeneration = sessionGeneration;
        }

        synchronized boolean pendingFor(long sessionGeneration) {
            return token != null && sessionGeneration > 0L
                    && this.sessionGeneration == sessionGeneration;
        }

        synchronized String take(long sessionGeneration) {
            if (!pendingFor(sessionGeneration)) {
                return null;
            }
            String result = token;
            clear();
            return result;
        }

        synchronized boolean onStateTransition(
                long previousSessionGeneration, RuntimeState state) {
            boolean invalidate = state.phase() == RuntimeState.Phase.LOADING
                    || state.sessionGeneration() != previousSessionGeneration;
            if (invalidate) {
                clear();
            }
            return invalidate;
        }

        synchronized void clear() {
            token = null;
            sessionGeneration = 0L;
        }
    }


    private void restoreActivityState(Bundle state) {
        if (state == null) {
            return;
        }
        suspendedMenu = withoutPrinterPaper(readSnapshot(state, "menu"));
        suspendedMenuPauseOwned = state.getBoolean(STATE_MENU_PAUSE);
        String actionName = state.getString(STATE_EXTERNAL_ACTION);
        String policyName = state.getString(STATE_EXTERNAL_POLICY);
        if (actionName != null && policyName != null) {
            try {
                externalSurface = MenuExternalSurfaceState.restored(
                        MenuExternalSurfaceState.Action.valueOf(actionName),
                        state.getInt(STATE_EXTERNAL_REQUEST, -1),
                        withoutPrinterPaper(readSnapshot(state, "external")),
                        state.getBoolean(STATE_EXTERNAL_PAUSE),
                        MenuExternalSurfaceState.RestorePolicy.valueOf(policyName),
                        state.getBoolean(STATE_EXTERNAL_RESTORE));
            } catch (IllegalArgumentException ignored) {
                externalSurface = MenuExternalSurfaceState.none();
            }
        }
        armLegacyCameraPermissionFallback(externalSurface);
        restoreDrafts(state);
        restoreOptionSession(state);
        String confirm = state.getString(STATE_CONFIRM_VARIANT);
        if (confirm != null) {
            try {
                confirmVariant = ConfirmVariant.valueOf(confirm);
                confirmSlot = state.getInt(STATE_CONFIRM_SLOT, -1);
            } catch (IllegalArgumentException ignored) {
                confirmVariant = null;
                confirmSlot = -1;
            }
        }
        String slotMode = state.getString(STATE_SLOT_MODE);
        if (slotMode != null) {
            try {
                stateMenuMode = StateMenuMode.valueOf(slotMode);
            } catch (IllegalArgumentException ignored) {
                stateMenuMode = StateMenuMode.SAVE;
            }
        }
        optionalDevicesStatus = state.getString(STATE_OPTIONAL_STATUS, optionalDevicesStatus);
        printerStatus = state.getString(STATE_PRINTER_STATUS, printerStatus);
        aboutStatus = state.getString(STATE_ABOUT_STATUS, aboutStatus);
        String pendingAction = state.getString(STATE_PENDING_ACTION);
        String pendingUri = state.getString(STATE_PENDING_URI);
        if (pendingAction != null && pendingUri != null) {
            try {
                pendingDocumentResult = new PendingDocumentResult(
                        MenuExternalSurfaceState.Action.valueOf(pendingAction),
                        state.getInt(STATE_PENDING_REQUEST, -1), Uri.parse(pendingUri),
                        state.getInt(STATE_PENDING_FLAGS),
                        state.getBoolean(STATE_PENDING_RELEASE_MENU_PAUSE));
            } catch (IllegalArgumentException ignored) {
                pendingDocumentResult = null;
            }
        }
    }

    private void saveDrafts(Bundle state) {
        if (audioDraft != null) {
            state.putBoolean(STATE_AUDIO_ACTIVE, true);
            state.putInt(STATE_AUDIO_VOLUME, audioDraft.volume());
            state.putBoolean(STATE_AUDIO_MUTED, audioDraft.muted());
        }
        if (touchDraft != null) {
            state.putBoolean(STATE_TOUCH_ACTIVE, true);
            state.putFloat(STATE_TOUCH_OPACITY, touchDraft.opacity());
            state.putFloat(STATE_TOUCH_SCALE, touchDraft.scale());
            state.putFloat(STATE_TOUCH_VERTICAL, touchDraft.verticalPosition());
            state.putBoolean(STATE_TOUCH_LEFT, touchDraft.leftHanded());
            state.putBoolean(STATE_TOUCH_HAPTICS, touchDraft.haptics());
        }
        if (devicesDraft != null) {
            state.putBoolean(STATE_DEVICES_ACTIVE, true);
            state.putBoolean(STATE_DEVICES_RUMBLE, devicesDraft.rumble());
            state.putBoolean(STATE_DEVICES_CAMERA, devicesDraft.camera());
            state.putBoolean(STATE_DEVICES_PRINTER, devicesDraft.printer());
        }
    }

    private void restoreDrafts(Bundle state) {
        if (state.getBoolean(STATE_AUDIO_ACTIVE)) {
            audioDraft = AndroidMenuModel.audioDraft(state.getInt(STATE_AUDIO_VOLUME, 100),
                    state.getBoolean(STATE_AUDIO_MUTED));
        }
        if (state.getBoolean(STATE_TOUCH_ACTIVE)) {
            touchDraft = new AndroidMenuModel.TouchDraft(
                    state.getFloat(STATE_TOUCH_OPACITY, TouchControlsLayout.DEFAULT_OPACITY),
                    state.getFloat(STATE_TOUCH_SCALE, TouchControlsLayout.DEFAULT_SCALE),
                    state.getFloat(STATE_TOUCH_VERTICAL,
                            TouchControlsLayout.DEFAULT_VERTICAL_POSITION),
                    state.getBoolean(STATE_TOUCH_LEFT),
                    state.getBoolean(STATE_TOUCH_HAPTICS, true));
        }
        if (state.getBoolean(STATE_DEVICES_ACTIVE)) {
            devicesDraft = new AndroidMenuModel.DevicesDraft(
                    state.getBoolean(STATE_DEVICES_RUMBLE),
                    state.getBoolean(STATE_DEVICES_CAMERA),
                    state.getBoolean(STATE_DEVICES_PRINTER));
        }
    }

    private void saveOptionSession(Bundle state) {
        if (optionSession == null) {
            return;
        }
        state.putBoolean(STATE_OPTION_ACTIVE, true);
        state.putString(STATE_OPTION_ROUTE, optionSession.origin().name());
        state.putString(STATE_OPTION_ID, optionSession.originId());
        state.putString(STATE_OPTION_TITLE, optionSession.title());
        state.putString(STATE_OPTION_SELECTED, optionSession.selectedToken());
        ArrayList<String> tokens = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Boolean> enabled = new ArrayList<>();
        for (AndroidMenuModel.ChoiceValue choice : optionSession.choices()) {
            tokens.add(choice.token());
            labels.add(choice.label());
            enabled.add(choice.enabled());
        }
        state.putStringArrayList(STATE_OPTION_TOKENS, tokens);
        state.putStringArrayList(STATE_OPTION_LABELS, labels);
        state.putSerializable(STATE_OPTION_ENABLED, enabled);
    }

    private void restoreOptionSession(Bundle state) {
        if (!state.getBoolean(STATE_OPTION_ACTIVE, false)) {
            return;
        }
        try {
            MenuRoute origin = MenuRoute.valueOf(state.getString(STATE_OPTION_ROUTE));
            ArrayList<String> tokens = state.getStringArrayList(STATE_OPTION_TOKENS);
            ArrayList<String> labels = state.getStringArrayList(STATE_OPTION_LABELS);
            @SuppressWarnings("unchecked")
            ArrayList<Boolean> enabled = (ArrayList<Boolean>) state.getSerializable(
                    STATE_OPTION_ENABLED);
            if (tokens == null || labels == null || tokens.size() != labels.size()
                    || tokens.isEmpty()) {
                return;
            }
            ArrayList<AndroidMenuModel.ChoiceValue> choices = new ArrayList<>();
            for (int index = 0; index < tokens.size(); index++) {
                choices.add(new AndroidMenuModel.ChoiceValue(tokens.get(index), labels.get(index),
                        enabled == null || index >= enabled.size() || enabled.get(index)));
            }
            optionSession = new ChoiceSession(origin, state.getString(STATE_OPTION_ID),
                    state.getString(STATE_OPTION_TITLE), choices,
                    state.getString(STATE_OPTION_SELECTED));
        } catch (RuntimeException ignored) {
            optionSession = null;
        }
    }

    private static void writeSnapshot(Bundle state, String prefix, MenuStackSnapshot snapshot) {
        state.putInt(prefix + ".count", snapshot.frames().size());
        for (int index = 0; index < snapshot.frames().size(); index++) {
            MenuStackSnapshot.Frame frame = snapshot.frames().get(index);
            state.putString(prefix + ".route." + index, frame.route().name());
            state.putString(prefix + ".focus." + index, frame.focusedItemId());
        }
    }

    private static MenuStackSnapshot readSnapshot(Bundle state, String prefix) {
        int count = state.getInt(prefix + ".count", 0);
        ArrayList<MenuStackSnapshot.Frame> frames = new ArrayList<>(Math.max(0, count));
        for (int index = 0; index < count; index++) {
            String route = state.getString(prefix + ".route." + index);
            String focus = state.getString(prefix + ".focus." + index);
            if (route == null || focus == null) {
                return MenuStackSnapshot.hidden();
            }
            try {
                frames.add(new MenuStackSnapshot.Frame(MenuRoute.valueOf(route), focus));
            } catch (IllegalArgumentException ignored) {
                return MenuStackSnapshot.hidden();
            }
        }
        return new MenuStackSnapshot(frames);
    }

    private static MenuStackSnapshot withoutPrinterPaper(MenuStackSnapshot snapshot) {
        if (!stackContains(snapshot, MenuRoute.PRINTER_PAPER)) {
            return snapshot;
        }
        ArrayList<MenuStackSnapshot.Frame> frames = new ArrayList<>(snapshot.frames().size());
        for (MenuStackSnapshot.Frame frame : snapshot.frames()) {
            if (frame.route() != MenuRoute.PRINTER_PAPER) {
                frames.add(frame);
            }
        }
        return frames.isEmpty() ? MenuStackSnapshot.hidden() : new MenuStackSnapshot(frames);
    }

    private record ChoiceSession(MenuRoute origin, String originId, String title,
                                 List<AndroidMenuModel.ChoiceValue> choices,
                                 String selectedToken) {
        private ChoiceSession {
            choices = List.copyOf(choices);
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33MenuBackCallback {

        private final OnBackInvokedDispatcher dispatcher;
        private final OnBackInvokedCallback callback;
        private boolean registered;

        private Api33MenuBackCallback(Activity activity, Runnable action) {
            dispatcher = activity.getOnBackInvokedDispatcher();
            callback = action::run;
        }

        private void setEnabled(boolean enabled) {
            if (enabled == registered) {
                return;
            }
            if (enabled) {
                dispatcher.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            } else {
                dispatcher.unregisterOnBackInvokedCallback(callback);
            }
            registered = enabled;
        }

        private void close() {
            setEnabled(false);
        }
    }

    /** Application-owned completion that never retains or launches from an obsolete Activity. */
    private static final class PrinterExportCompletion implements Consumer<Boolean> {

        private final SharedPreferences preferences;
        private final long token;
        private final WeakReference<MainActivity> activity;
        private final long ownerGeneration;

        private PrinterExportCompletion(SharedPreferences preferences, long token,
                MainActivity activity, long ownerGeneration) {
            this.preferences = preferences;
            this.token = token;
            this.activity = new WeakReference<>(activity);
            this.ownerGeneration = ownerGeneration;
        }

        @Override
        public void accept(Boolean successful) {
            synchronized (MainActivity.class) {
                PrinterExportContinuation current =
                        readPrinterExportContinuation(preferences);
                PrinterExportContinuation completed = current.complete(token,
                        Boolean.TRUE.equals(successful));
                if (completed != current) {
                    writePrinterExportContinuation(preferences, completed, true);
                }
            }
            MainActivity owner = activity.get();
            if (owner != null) {
                owner.mainHandler.post(() ->
                        owner.consumePrinterExportContinuation(ownerGeneration));
            }
        }
    }

    private record PendingDocumentResult(MenuExternalSurfaceState.Action action, int requestCode,
                                         Uri uri, int flags, boolean releaseMenuPause) {
    }

    private enum StateMenuMode {
        SAVE,
        LOAD
    }

    private enum ConfirmVariant {
        RESET("RESET GAME", "UNSAVED PROGRESS MAY BE LOST", null),
        STOP("STOP GAME", "THE CURRENT SESSION WILL END", null),
        OVERWRITE("OVERWRITE STATE", "THE EXISTING SLOT WILL BE REPLACED", null),
        DELETE("DELETE STATE", "THE SAVED SLOT CANNOT BE RECOVERED", null),
        CLEAR_PRINTER("CLEAR PAPER", "THE PRINTER ROLL CANNOT BE RECOVERED", null),
        IMPORT_BATTERY("IMPORT BATTERY SAVE", "APP-PRIVATE SAVE DATA MAY BE REPLACED",
                MenuExternalSurfaceState.Action.IMPORT_BATTERY),
        EXPORT_BATTERY("EXPORT BATTERY SAVE", "ANDROID WILL CREATE A DOCUMENT",
                MenuExternalSurfaceState.Action.EXPORT_BATTERY),
        IMPORT_STATE("IMPORT STATE SLOT 0", "APP-PRIVATE SLOT 0 MAY BE REPLACED",
                MenuExternalSurfaceState.Action.IMPORT_STATE_0),
        EXPORT_STATE("EXPORT STATE SLOT 0", "ANDROID WILL CREATE A DOCUMENT",
                MenuExternalSurfaceState.Action.EXPORT_STATE_0),
        EXPORT_SCREENSHOT("EXPORT NATIVE SCREENSHOT", "ANDROID WILL CREATE A PNG",
                MenuExternalSurfaceState.Action.EXPORT_SCREENSHOT),
        EXPORT_PRINTER("EXPORT & SHARE PAPER", "ANDROID WILL CREATE A FULL-RES PNG",
                MenuExternalSurfaceState.Action.EXPORT_PRINTER_SHARE);

        private final String label;
        private final String description;
        private final MenuExternalSurfaceState.Action externalAction;

        ConfirmVariant(String label, String description,
                MenuExternalSurfaceState.Action externalAction) {
            this.label = label;
            this.description = description;
            this.externalAction = externalAction;
        }
    }
}
