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
    private static final String STATE_OPTIONAL_STATUS = "status.optional-devices";
    private static final String STATE_PRINTER_STATUS = "status.printer";
    private static final String STATE_ABOUT_STATUS = "status.about";
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
    private StateMenuMode stateMenuMode = StateMenuMode.SAVE;
    private ConfirmVariant confirmVariant;
    private int confirmSlot = -1;
    private boolean stateSlotsLoading;
    /** Monotonic guard for owner-thread catalog reads crossing Activity/ROM transitions. */
    private long stateCatalogGeneration;
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
    private String optionalDevicesStatus = "READY";
    private String aboutStatus = "OPEN IN BROWSER";
    private String printerStatus = "READY";
    private String systemPreferredFocus = "video-status";
    private MenuPreview printerPreview = MenuPreview.empty();
    private int printerPreviewGeneration;
    private boolean printerPaperEntryPending;
    private MenuRoute printerPaperEntryParent;
    private MenuStackSnapshot deferredMenuFocusRestore = MenuStackSnapshot.hidden();
    private boolean activityResumed;
    // Android 6-8 can return from a cancelled permission Activity without delivering its result.
    private MenuExternalSurfaceState legacyCameraPermissionFallbackSurface;
    private boolean legacyCameraPermissionFallbackPosted;
    private long lifecycleGeneration;

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
            }
            runtime = connected;
            bound = true;
            runtime.addObserver(MainActivity.this);
            video.attach(runtime.frames(), runtime.input());
            runtime.setAudioMuted(getPreferences(MODE_PRIVATE)
                    .getBoolean("audio.muted", false));
            runtime.setAudioVolume(getPreferences(MODE_PRIVATE)
                    .getInt("audio.volume", 100));
            runtime.setRumbleEnabled(getPreferences(MODE_PRIVATE)
                    .getBoolean("devices.rumble", false));
            runtime.setPrinterEnabled(getPreferences(MODE_PRIVATE)
                    .getBoolean("devices.printer", false));
            if (getPreferences(MODE_PRIVATE).getBoolean("devices.camera", false)
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                runtime.setCameraEnabled(true);
            }
            applyState(runtime.state());
            dispatchPendingDocumentResult();
            restoreExternalSurfaceIfRequested();
            restoreSuspendedMenu();
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
            bound = false;
            observedState = RuntimeState.stopped();
            stateCatalogGeneration++;
            stateSlotsLoading = false;
            stateSlots = List.of();
            cancelPendingPrinterPaperEntry();
            printerPreview = MenuPreview.empty();
            refreshMenuPages();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        printerContinuationPreferences = getApplicationContext().getSharedPreferences(
                PRINTER_CONTINUATION_PREFS, MODE_PRIVATE);
        restoreActivityState(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        video = new CoffeeGbSurfaceView(this);
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
        EmulationService.start(this);
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
        MenuKey menuKey = menuKey(event);
        if (menuKey != null) {
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
        if (menuController.visible()) {
            menuController.hide();
            return;
        }
        AndroidEmulationRuntime active = runtime;
        RuntimeState current = active == null ? observedState : active.state();
        applyState(current);
        current = observedState;
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
        if (route == MenuRoute.LIBRARY) {
            openRomFromMenu();
        }
    }

    private void handleMenuAdjustment(MenuRoute route, String id, int direction) {
        if (route == MenuRoute.AUDIO && "volume".equals(id) && audioDraft != null) {
            audioDraft = AndroidMenuModel.adjustVolume(audioDraft, direction);
            refreshMenuPages();
        } else if (route == MenuRoute.AUDIO && "mute-audio".equals(id)
                && audioDraft != null) {
            audioDraft = audioDraft.toggleMuted();
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
            case LIBRARY -> handleLibraryItem(active, id);
            case CHOOSE_ROM -> handleChooseRomItem(active, id);
            case SETTINGS -> handleSettingsItem(id);
            case AUDIO -> handleAudioItem(id);
            case TOUCH_CONTROLS -> handleTouchItem(id);
            case OPTIONAL_DEVICES -> handleOptionalDevicesItem(active, id);
            case CONTROLLER_MAPPING -> handleControllerItem(active, id);
            case PRINTER_PAPER -> handlePrinterPaperItem(id);
            case SYSTEM -> menuController.back();
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
            case "stop" -> showConfirmation(ConfirmVariant.STOP, -1);
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

    private void handleLibraryItem(AndroidEmulationRuntime active, String id) {
        if ("open-rom".equals(id)) {
            openRomFromMenu();
        } else if ("recent-rom".equals(id) && active != null) {
            active.requestRecentDocuments();
        } else if (id.startsWith("recent:") && active != null) {
            long token = parseToken(id.substring("recent:".length()));
            if (token >= 0) {
                selectionActionInFlight = true;
                menuPauseOwned = false;
                menuController.hide();
                active.selectRecentDocument(token);
            }
        } else if ("choose-rom".equals(id)) {
            menuController.push(MenuRoute.CHOOSE_ROM);
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
            case "audio" -> {
                audioDraft = loadAudioDraft();
                refreshMenuPages();
                menuController.push(MenuRoute.AUDIO);
            }
            case "touch-controls" -> {
                touchDraft = AndroidMenuModel.touchDraft(video.touchLayout());
                refreshMenuPages();
                menuController.push(MenuRoute.TOUCH_CONTROLS);
            }
            case "controller-mapping" -> menuController.push(MenuRoute.CONTROLLER_MAPPING);
            case "optional-devices" -> {
                devicesDraft = loadDevicesDraft();
                loadPrinterPreview();
                refreshMenuPages();
                menuController.push(MenuRoute.OPTIONAL_DEVICES);
            }
            case "video" -> openSystem("video-status");
            case "system-profile" -> openSystem("profile-status");
            case "rewind-save" -> openSystem("rewind-save-status");
            case "data-media" -> menuController.push(MenuRoute.DATA_MEDIA);
            case "about" -> menuController.push(MenuRoute.ABOUT);
            default -> { }
        }
    }

    private void handleAudioItem(String id) {
        if (audioDraft == null) {
            audioDraft = loadAudioDraft();
        }
        switch (id) {
            case "volume" -> audioDraft = AndroidMenuModel.adjustVolume(audioDraft, 1);
            case "mute-audio" -> audioDraft = audioDraft.toggleMuted();
            case "save-audio" -> {
                getPreferences(MODE_PRIVATE).edit()
                        .putInt("audio.volume", audioDraft.volume())
                        .putBoolean("audio.muted", audioDraft.muted()).apply();
                if (runtime != null) {
                    runtime.setAudioVolume(audioDraft.volume());
                    runtime.setAudioMuted(audioDraft.muted());
                }
                audioDraft = null;
                menuController.back();
            }
            case "cancel-audio" -> {
                audioDraft = null;
                menuController.back();
            }
            default -> { }
        }
        refreshMenuPages();
    }

    private void handleTouchItem(String id) {
        if (touchDraft == null) {
            touchDraft = AndroidMenuModel.touchDraft(video.touchLayout());
        }
        switch (id) {
            case "haptics" -> touchDraft = touchDraft.toggleHaptics();
            case "reset-touch" -> touchDraft = AndroidMenuModel.resetTouchDraft();
            case "save-touch" -> {
                video.updateTouchLayout(touchDraft.layout());
                touchDraft = null;
                menuController.back();
            }
            case "cancel-touch" -> {
                touchDraft = null;
                menuController.back();
            }
            default -> { }
        }
        refreshMenuPages();
    }

    private void handleOptionalDevicesItem(AndroidEmulationRuntime active, String id) {
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
        menuController.back();
        if (!plan.requestCameraPermission()) {
            return;
        }
        externalSurface = MenuExternalSurfaceState.launched(
                MenuExternalSurfaceState.Action.CAMERA_PERMISSION, CAMERA_PERMISSION_REQUEST,
                menuController.snapshot(), menuPauseOwned,
                MenuExternalSurfaceState.RestorePolicy.ALWAYS);
        clearLegacyCameraPermissionFallback();
        menuPauseOwned = false;
        menuController.hide();
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    private void handleControllerItem(AndroidEmulationRuntime active, String id) {
        if ("back".equals(id)) {
            menuController.back();
            return;
        }
        if (active == null) {
            return;
        }
        AndroidInputRouter input = active.input();
        eu.rekawek.coffeegb.core.joypad.Button target = mappingTarget(id);
        if (target != null) {
            if (input.beginCapture(target)) {
                menuController.setBackIntercepted(true);
            }
        } else if ("invert-x".equals(id)) {
            input.toggleHorizontalInversion();
        } else if ("invert-y".equals(id)) {
            input.toggleVerticalInversion();
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
                    closeMenuWithoutResume();
                    active.reset();
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
            case CAMERA_PERMISSION -> {
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

    private static Intent openRomIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream", "application/x-gameboy-rom",
                        "application/zip", "application/x-zip-compressed"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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
        externalSurface = externalSurface.afterResult(successful);
        if (!successful) {
            restoreExternalSurfaceIfRequested();
            return;
        }
        PendingDocumentResult result = new PendingDocumentResult(
                action, requestCode, uri, data.getFlags());
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
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }
        MenuExternalSurfaceState pending = externalSurface;
        clearLegacyCameraPermissionFallback();
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        completeCameraPermission(pending, requestCode, granted);
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
        getPreferences(MODE_PRIVATE).edit().putBoolean("devices.camera", granted).apply();
        if (runtime != null) {
            runtime.setCameraEnabled(granted);
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
            case OPEN_ROM -> active.openRom(result.uri(), result.flags());
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
            case CAMERA_PERMISSION -> { }
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
        if (stackContains(snapshot, MenuRoute.PRINTER_PAPER)
                || stackContains(snapshot, MenuRoute.OPTIONAL_DEVICES)) {
            deferredMenuFocusRestore = snapshot;
            mainHandler.post(this::loadPrinterPreview);
        }
    }

    private void restoreDeferredPaperFocusIfReady() {
        MenuStackSnapshot deferred = deferredMenuFocusRestore;
        if (!deferred.visible()
                || (!stackContains(deferred, MenuRoute.PRINTER_PAPER)
                && !stackContains(deferred, MenuRoute.OPTIONAL_DEVICES))) {
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
        AndroidMenuModel.DevicesDraft devices = devicesDraft == null
                ? loadDevicesDraft() : devicesDraft;
        menuController.setPages(List.of(
                pausePage(), statePage(), libraryPage(), chooseRomPage(),
                AndroidMenuModel.settingsPage(), AndroidMenuModel.audioPage(audio),
                AndroidMenuModel.touchPage(touch), controllerPage(),
                AndroidMenuModel.optionalDevicesPage(devices, optionalDevicesStatus,
                        printerPreview),
                AndroidMenuModel.printerPaperPage(printerPreview, printerStatus),
                AndroidMenuModel.systemPage(systemPreferredFocus),
                AndroidMenuModel.dataMediaPage(AndroidMenuModel.transferAvailability(
                        runtime != null, observedState)),
                AndroidMenuModel.aboutPage(BuildConfig.VERSION_NAME, aboutStatus),
                confirmationPage()));
        attemptDeferredFocusRestore();
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
                        item("resume", "RESUME", "", true),
                        item("save-state", "SAVE STATE", "", runtime != null),
                        item("load-state", "LOAD STATE", "", runtime != null),
                        item("open-rom", "OPEN ROM", "", runtime != null),
                        item("reset", "RESET GAME", "CONFIRM", runtime != null),
                        item("settings", "SETTINGS", "OPEN", true),
                        item("stop", "STOP GAME", "CONFIRM", runtime != null)),
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

    /** Stable state rows shared by SAVE and LOAD; persisted rows carry a visual-only seal hint. */
    static List<MenuPageSpec.Item> stateMenuItems(List<AndroidStateSlot> catalog) {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (int index = STATE_MENU_MIN_SLOT; index <= STATE_MENU_MAX_SLOT; index++) {
            // Every stable slot remains focusable in both modes. LOAD treats an empty or
            // unavailable slot as a no-op; SAVE overwrites directly without confirmation.
            boolean used = catalogContainsLoadableSlot(catalog, index);
            // The compositor uses this semantic marker to distinguish a persisted state from an
            // empty but still focusable slot. It is intentionally not exposed as row copy.
            items.add(new MenuPageSpec.Item("slot:" + index, "SLOT " + index,
                    used ? "USED" : "", true));
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
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        if (observedState.phase() == RuntimeState.Phase.AWAITING_RECENT_SELECTION) {
            for (RuntimeState.Selection selection : observedState.selections()) {
                items.add(item("recent:" + selection.token(), selection.label(), "A OPEN", true));
            }
        } else {
            items.add(item("recent-rom", "RECENT ROMS", "CHOOSE", runtime != null));
            items.add(item("choose-rom", "CHOOSE ROM", "ZIP RESULTS",
                    observedState.phase() == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION));
        }
        items.add(item("open-rom", "OPEN ROM", "NATIVE PICKER", runtime != null));
        if (runtime == null) {
            items.add(item("wait", "RUNTIME STARTING", "WAIT", true));
        }
        return page(MenuRoute.LIBRARY, "COFFEE GB", "LIBRARY", "OPEN ROM", "RECENT ROMS",
                List.of("DOCUMENT PICKER NATIVE", "RECENT METADATA PRIVATE", "ZIP MULTI-ROM"),
                items, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"));
    }

    private MenuPageSpec chooseRomPage() {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (RuntimeState.Selection selection : observedState.selections()) {
            items.add(item("archive:" + selection.token(), selection.label(), "A OPEN", true));
        }
        if (items.isEmpty()) {
            items.add(item("empty", "NO ROM CANDIDATES", "CANCEL", false));
        }
        items.add(item("cancel", "BACK TO LIBRARY", "CANCEL", true));
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
                input != null && input.captureWaitingForRelease(),
                input != null && input.horizontalInverted(),
                input != null && input.verticalInverted());
    }

    private MenuPageSpec confirmationPage() {
        ConfirmVariant variant = confirmVariant == null ? ConfirmVariant.RESET : confirmVariant;
        return page(MenuRoute.CONFIRM_ACTION, "COFFEE GB", "CONFIRM ACTION", "", variant.label,
                List.of(variant.description),
                List.of(item("cancel", "CANCEL", "RETURN", true),
                        item("confirm", "CONFIRM", variant.label, true)),
                2, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"));
    }

    private static MenuPageSpec page(MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines,
            List<MenuPageSpec.Item> items, List<String> hints) {
        return page(route, title, context, headerAction, sideHeading, sideLines, items, 1, hints);
    }

    private static MenuPageSpec page(MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines,
            List<MenuPageSpec.Item> items, int columns, List<String> hints) {
        return new MenuPageSpec(route, title, context, headerAction, sideHeading, sideLines,
                items, columns, hints);
    }

    private static MenuPageSpec.Item item(String id, String label, String detail,
            boolean enabled) {
        return new MenuPageSpec.Item(id, label, detail, enabled);
    }

    private AndroidMenuModel.AudioDraft loadAudioDraft() {
        return AndroidMenuModel.audioDraft(
                getPreferences(MODE_PRIVATE).getInt("audio.volume", 100),
                getPreferences(MODE_PRIVATE).getBoolean("audio.muted", false));
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
        observedGeneration = state.generation();
        if (state.flushPending() && !observedState.flushPending()
                && menuController != null && menuController.visible()
                && menuController.route() == MenuRoute.DATA_MEDIA) {
            deferredMenuFocusRestore = menuController.snapshot();
        }
        observedState = state;
        refreshMenuPages();
        if (runtime == null || menuController == null || externalSurface.active()) {
            return;
        }
        if (state.phase() == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION
                && !menuController.visible()) {
            menuPauseOwned = false;
            menuController.show(MenuRoute.CHOOSE_ROM);
        } else if (state.phase() == RuntimeState.Phase.AWAITING_RECENT_SELECTION
                && !menuController.visible()) {
            menuPauseOwned = false;
            menuController.show(MenuRoute.LIBRARY);
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
                        state.getInt(STATE_PENDING_FLAGS));
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
                                         Uri uri, int flags) {
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
