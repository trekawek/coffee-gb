package eu.rekawek.coffeegb.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import eu.rekawek.coffeegb.android.menu.MenuController;
import eu.rekawek.coffeegb.android.menu.MenuKey;
import eu.rekawek.coffeegb.android.menu.MenuPageSpec;
import eu.rekawek.coffeegb.android.menu.MenuPresentation;
import eu.rekawek.coffeegb.android.menu.MenuRoute;
import eu.rekawek.coffeegb.android.menu.OpenRomPickerState;
import eu.rekawek.coffeegb.controller.state.StateRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin SAF/UI client for {@link EmulationService}.
 *
 * <p>The Activity owns only document-picker and dialog interactions. Its bound service owns every
 * emulator, controller, event-bus, persistence, and lifecycle resource, so rotation never creates
 * a second session and unbinding never directly stops the active game.
 */
public final class MainActivity extends Activity implements RuntimeObserver {

    private static final int OPEN_ROM_REQUEST = 1;
    private static final int IMPORT_BATTERY_REQUEST = 2;
    private static final int EXPORT_BATTERY_REQUEST = 3;
    private static final int IMPORT_STATE_REQUEST = 4;
    private static final int EXPORT_STATE_REQUEST = 5;
    private static final int EXPORT_SCREENSHOT_REQUEST = 6;
    private static final int EXPORT_PRINTER_REQUEST = 7;
    private static final int CAMERA_PERMISSION_REQUEST = 8;
    private static final String STATE_OPEN_ROM_PICKER_ROUTE = "openRomPicker.route";
    private static final String STATE_OPEN_ROM_PICKER_PAUSE_OWNED = "openRomPicker.pauseOwned";
    private static final String STATE_OPEN_ROM_PICKER_RESTORE = "openRomPicker.restore";

    private CoffeeGbSurfaceView video;
    private Button menuButton;

    private AndroidEmulationRuntime runtime;
    private boolean bound;
    private InputManager inputManager;
    private PendingDocumentResult pendingDocumentResult;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MenuController menuController;
    private RuntimeState observedState = RuntimeState.stopped();
    private List<AndroidStateSlot> stateSlots = List.of();
    private StateMenuMode stateMenuMode = StateMenuMode.SAVE;
    private ConfirmVariant confirmVariant;
    private int confirmSlot = -1;
    private boolean stateSlotsLoading;
    private boolean menuVisible;
    private boolean menuPauseOwned;
    private boolean selectionActionInFlight;
    private OpenRomPickerState openRomPickerState = OpenRomPickerState.none();

    private final InputManager.InputDeviceListener inputDevices = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            // The first event selects the controller; no implicit mapping is created on connect.
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            AndroidEmulationRuntime active = runtime;
            if (active != null) {
                active.input().disconnect(deviceId);
            }
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            // Android delivers the new axes on the next motion event.
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            runtime = ((EmulationService.RuntimeBinder) service).runtime();
            bound = true;
            runtime.addObserver(MainActivity.this);
            video.attach(runtime.frames(), runtime.input());
            runtime.setAudioMuted(getPreferences(MODE_PRIVATE).getBoolean("audio.muted", false));
            runtime.setAudioVolume(getPreferences(MODE_PRIVATE).getInt("audio.volume", 100));
            runtime.setRumbleEnabled(getPreferences(MODE_PRIVATE).getBoolean("devices.rumble", false));
            runtime.setPrinterEnabled(getPreferences(MODE_PRIVATE).getBoolean("devices.printer", false));
            if (getPreferences(MODE_PRIVATE).getBoolean("devices.camera", false)
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                runtime.setCameraEnabled(true);
            }
            applyState(runtime.state());
            dispatchPendingDocumentResult();
            restoreMenuAfterOpenRomCancel();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (runtime != null) {
                video.detach();
                runtime.removeObserver(MainActivity.this);
            }
            menuPauseOwned = false;
            if (menuController != null) {
                menuController.hide();
            }
            video.clearMenuPresentation();
            runtime = null;
            bound = false;
            observedState = RuntimeState.stopped();
            refreshMenuPages();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreOpenRomPickerState(savedInstanceState);

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
        });
        video.setMenuInput(menuController);
        root.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        menuButton = new Button(this);
        styleMenuButton();
        menuButton.setContentDescription("Open Coffee GB menu");
        menuButton.setOnClickListener(ignored -> toggleMenu());
        root.addView(menuButton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            positionMenuOverlay(right - left, bottom - top);
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            positionMenuOverlay(view.getWidth(), view.getHeight());
            return insets;
        });
        setContentView(root);
        refreshMenuPages();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (openRomPickerState.active()) {
            outState.putString(STATE_OPEN_ROM_PICKER_ROUTE, openRomPickerState.route().name());
            outState.putBoolean(
                    STATE_OPEN_ROM_PICKER_PAUSE_OWNED, openRomPickerState.pauseOwned());
            outState.putBoolean(
                    STATE_OPEN_ROM_PICKER_RESTORE, openRomPickerState.restoreRequested());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        inputManager = getSystemService(InputManager.class);
        inputManager.registerInputDeviceListener(inputDevices, null);
        EmulationService.start(this);
        if (!bound) {
            bindService(new Intent(this, EmulationService.class), connection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(inputDevices);
            inputManager = null;
        }
        if (menuController != null && menuController.visible()) {
            // Backgrounding must preserve the runtime's paused lifecycle state; it must not
            // resume a game merely because the transient Activity menu was dismissed.
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
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
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
        AndroidEmulationRuntime active = runtime;
        if (active != null && active.input().onKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (menuController != null && menuController.visible()
                && isGameController(event.getSource())
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float x = Math.abs(hatX) >= .45f ? hatX : event.getAxisValue(MotionEvent.AXIS_X);
            float y = Math.abs(hatY) >= .45f ? hatY : event.getAxisValue(MotionEvent.AXIS_Y);
            menuController.onAxis(x, y);
            return true;
        }
        AndroidEmulationRuntime active = runtime;
        if (active != null && active.input().onMotionEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && runtime != null) {
            runtime.input().releaseAll();
            if (menuController != null) {
                menuController.cancelInput();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (menuController != null && menuController.visible()) {
            menuController.onKeyDown(MenuKey.B, false);
            menuController.onKeyUp(MenuKey.B);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onStateChanged(RuntimeState state) {
        applyState(state);
    }

    private void toggleMenu() {
        if (menuController == null) {
            return;
        }
        if (menuController.visible()) {
            menuController.hide();
            return;
        }
        AndroidEmulationRuntime active = runtime;
        RuntimeState current = active == null ? observedState : active.state();
        observedState = current;
        if (active != null) {
            // No touch/key state that opened the menu may leak into the emulated game.
            active.input().releaseAll();
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
        } else if (current.phase() == RuntimeState.Phase.PAUSED
                || (current.transferReady() && current.paused())) {
            menuController.show(MenuRoute.PAUSE_CONSOLE);
        } else if (current.transferReady() && current.phase() == RuntimeState.Phase.RUNNING) {
            menuController.show(MenuRoute.PAUSE_CONSOLE);
        } else {
            menuController.show(MenuRoute.LIBRARY);
        }
    }

    private void styleMenuButton() {
        int density = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        // The raster skin supplies the visible menu glyph; this is its accessible tap target.
        menuButton.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        menuButton.setText(null);
        menuButton.setMinWidth(48 * density);
        menuButton.setMinHeight(48 * density);
        menuButton.setPadding(0, 0, 0, 0);
    }

    private void positionMenuOverlay(int width, int height) {
        if (width <= 0 || height <= 0 || menuButton == null) {
            return;
        }
        int density = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        int buttonTarget = 48 * density;
        FrameLayout.LayoutParams buttonLayout =
                (FrameLayout.LayoutParams) menuButton.getLayoutParams();
        boolean portrait = height >= width;
        int skinMenuCenterX = Math.round(width * (portrait ? .125f : .059f));
        int skinMenuCenterY = Math.round(height * (portrait ? .050f : .085f));
        buttonLayout.gravity = Gravity.TOP | Gravity.START;
        buttonLayout.leftMargin = Math.max(0, skinMenuCenterX - buttonTarget / 2);
        buttonLayout.rightMargin = 0;
        buttonLayout.topMargin = Math.max(0, skinMenuCenterY - buttonTarget / 2);
        menuButton.setLayoutParams(buttonLayout);
    }

    private void presentMenu(MenuPresentation presentation) {
        boolean wasVisible = menuVisible;
        menuVisible = presentation.visible();
        if (menuVisible) {
            video.setMenuPresentation(presentation);
            menuButton.setContentDescription("Close Coffee GB menu");
        } else {
            video.clearMenuPresentation();
            menuButton.setContentDescription("Open Coffee GB menu");
            if (wasVisible) {
                onMenuClosed();
            }
        }
    }

    private void onMenuClosed() {
        if (selectionActionInFlight) {
            selectionActionInFlight = false;
        } else if (runtime != null && (observedState.phase()
                == RuntimeState.Phase.AWAITING_RECENT_SELECTION
                || observedState.phase() == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION)) {
            runtime.cancelPendingSelection();
        }
        if (menuPauseOwned) {
            menuPauseOwned = false;
            AndroidEmulationRuntime active = runtime;
            if (active != null) {
                active.resume();
            }
        }
    }

    private void handleMenuHeader(MenuRoute route) {
        if (route == MenuRoute.PAUSE_CONSOLE || route == MenuRoute.LIBRARY) {
            openRomFromMenu();
        }
    }

    private void handleMenuItem(MenuRoute route, String id, boolean secondary) {
        AndroidEmulationRuntime active = runtime;
        if (active == null || id == null) {
            return;
        }
        if (route == MenuRoute.PAUSE_CONSOLE) {
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
            return;
        }
        if (route == MenuRoute.SAVE_STATES) {
            if (id.equals("back")) {
                menuController.back();
                return;
            }
            if (secondary || id.startsWith("delete-slot:")) {
                String value = id.startsWith("delete-slot:")
                        ? id.substring("delete-slot:".length()) : id;
                int slot = parseSlot(value);
                if (slot >= 0) {
                    showConfirmation(ConfirmVariant.DELETE, slot);
                }
                return;
            }
            if (id.startsWith("slot:")) {
                int slot = parseSlot(id.substring("slot:".length()));
                if (slot < 0) {
                    return;
                }
                AndroidStateSlot stateSlot = stateSlot(slot);
                if (stateMenuMode == StateMenuMode.LOAD) {
                    if (stateSlot != null && stateSlot.loadable()) {
                        active.restoreSnapshot(slot);
                        closeMenuWithoutResume();
                    }
                } else if (stateSlot != null && stateSlot.loadable()) {
                    showConfirmation(ConfirmVariant.OVERWRITE, slot);
                } else {
                    active.saveSnapshot(slot);
                    refreshStateSlotsAfterMutation();
                }
            }
            return;
        }
        if (route == MenuRoute.LIBRARY) {
            if (id.equals("open-rom")) {
                openRomFromMenu();
            } else if (id.equals("recent-rom")) {
                active.requestRecentDocuments();
            } else if (id.startsWith("recent:")) {
                long token = parseToken(id.substring("recent:".length()));
                if (token >= 0) {
                    selectRecentFromMenu(active, token);
                }
            } else if (id.equals("choose-rom")) {
                menuController.push(MenuRoute.CHOOSE_ROM);
            }
            return;
        }
        if (route == MenuRoute.CHOOSE_ROM) {
            if (id.equals("cancel")) {
                menuController.back();
                return;
            }
            if (id.startsWith("archive:")) {
                long token = parseToken(id.substring("archive:".length()));
                if (token >= 0) {
                    selectionActionInFlight = true;
                    menuPauseOwned = false;
                    menuController.hide();
                    active.selectArchiveCandidate(token);
                }
            }
            return;
        }
        if (route == MenuRoute.CONFIRM_ACTION) {
            if (id.equals("cancel")) {
                menuController.back();
            } else if (id.equals("confirm")) {
                executeConfirmation(active);
            }
        }
    }

    private void selectRecentFromMenu(AndroidEmulationRuntime active, long token) {
        selectionActionInFlight = true;
        menuPauseOwned = false;
        menuController.hide();
        active.selectRecentDocument(token);
    }

    private void showStateMenu(StateMenuMode mode) {
        stateMenuMode = mode;
        stateSlotsLoading = true;
        refreshMenuPages();
        menuController.push(MenuRoute.SAVE_STATES);
        AndroidEmulationRuntime active = runtime;
        if (active != null) {
            active.listStateSlots(slots -> {
                stateSlots = List.copyOf(slots);
                stateSlotsLoading = false;
                refreshMenuPages();
            });
        }
    }

    private void showConfirmation(ConfirmVariant variant, int slot) {
        confirmVariant = variant;
        confirmSlot = slot;
        refreshMenuPages();
        menuController.push(MenuRoute.CONFIRM_ACTION);
    }

    private void executeConfirmation(AndroidEmulationRuntime active) {
        ConfirmVariant variant = confirmVariant;
        int slot = confirmSlot;
        if (variant == null) {
            menuController.back();
            return;
        }
        switch (variant) {
            case RESET -> {
                closeMenuWithoutResume();
                active.reset();
            }
            case STOP -> {
                closeMenuWithoutResume();
                active.stop();
            }
            case OVERWRITE -> {
                active.saveSnapshot(slot);
                menuController.back();
                refreshStateSlotsAfterMutation();
            }
            case DELETE -> {
                active.deleteSnapshot(slot);
                menuController.back();
                refreshStateSlotsAfterMutation();
            }
        }
        confirmVariant = null;
        confirmSlot = -1;
    }

    private void resumeAndClose(AndroidEmulationRuntime active) {
        menuPauseOwned = false;
        menuController.hide();
        active.resume();
    }

    private void closeMenuWithoutResume() {
        menuPauseOwned = false;
        menuController.hide();
    }

    private void openRomFromMenu() {
        if (runtime == null || menuController == null || !menuController.visible()) {
            return;
        }
        // A native picker owns the screen. Keep a paused session paused while it is open and do not
        // attempt to represent provider/filesystem rows in the emulator renderer.
        openRomPickerState = OpenRomPickerState.launched(
                menuController.route(), menuPauseOwned);
        menuPauseOwned = false;
        menuController.hide();
        openRomDocument(null);
    }

    private void refreshStateSlotsAfterMutation() {
        AndroidEmulationRuntime active = runtime;
        if (active == null) {
            return;
        }
        stateSlotsLoading = true;
        refreshMenuPages();
        active.listStateSlots(slots -> {
            stateSlots = List.copyOf(slots);
            stateSlotsLoading = false;
            refreshMenuPages();
        });
        // Manual state workers finish asynchronously; these bounded re-reads make the visible row
        // reflect the catalog after the controller has committed the save/delete operation.
        mainHandler.postDelayed(() -> refreshStateSlotsIfMenuVisible(), 250L);
        mainHandler.postDelayed(() -> refreshStateSlotsIfMenuVisible(), 750L);
    }

    private void refreshStateSlotsIfMenuVisible() {
        if (!menuVisible || menuController.route() != MenuRoute.SAVE_STATES) {
            return;
        }
        AndroidEmulationRuntime active = runtime;
        if (active != null) {
            active.listStateSlots(slots -> {
                stateSlots = List.copyOf(slots);
                stateSlotsLoading = false;
                refreshMenuPages();
            });
        }
    }

    private AndroidStateSlot stateSlot(int index) {
        for (AndroidStateSlot slot : stateSlots) {
            if (slot.index() == index) {
                return slot;
            }
        }
        return null;
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

    private void refreshMenuPages() {
        if (menuController == null) {
            return;
        }
        menuController.setPages(List.of(
                pausePage(), statePage(), libraryPage(), chooseRomPage(), confirmationPage()));
    }

    private MenuPageSpec pausePage() {
        return page(MenuRoute.PAUSE_CONSOLE, "COFFEE GB", "PAUSED", "OPEN ROM", "CURRENT GAME",
                List.of(observedState.message(), "INPUT  MENU CAPTURED", "A RESUME / B BACK"),
                List.of(
                        item("resume", "RESUME", "RUN GAME", true),
                        item("save-state", "SAVE STATE", "SAVE MODE", true),
                        item("load-state", "LOAD STATE", "LOAD MODE", true),
                        item("reset", "RESET GAME", "CONFIRM", true),
                        item("settings", "SETTINGS", "OPEN", true),
                        item("stop", "STOP GAME", "CONFIRM", true),
                        item("open-rom", "OPEN ROM", "NATIVE PICKER", runtime != null)),
                List.of("D-PAD MOVE", "[A] OK", "[B] BACK", "[START] OPEN ROM"));
    }

    private MenuPageSpec statePage() {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (int index = StateRef.MIN_SLOT; index <= StateRef.MAX_SLOT; index++) {
            AndroidStateSlot slot = stateSlot(index);
            boolean loadable = slot != null && slot.loadable();
            boolean enabled = !stateSlotsLoading && (stateMenuMode == StateMenuMode.SAVE || loadable);
            String detail = stateSlotsLoading ? "LOADING" : slot == null ? "EMPTY"
                    : slot.detail();
            String secondary = loadable ? "delete-slot:" + index : null;
            items.add(item("slot:" + index, "SLOT " + index, detail, enabled, secondary));
        }
        items.add(item("back", "BACK", "RETURN", true));
        String mode = stateMenuMode == StateMenuMode.SAVE ? "SAVE" : "LOAD";
        return page(MenuRoute.SAVE_STATES, "COFFEE GB", "SAVE STATES / " + mode, "", "STATE BANK",
                List.of("SLOTS " + StateRef.MIN_SLOT + "-" + StateRef.MAX_SLOT,
                        stateSlotsLoading ? "READING CATALOG" : "A SELECTS",
                        "SELECT/Y DELETE"), items,
                List.of("D-PAD MOVE", "[A] SELECT", "[SELECT] DELETE", "[B] BACK"));
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
                List.of("DOCUMENT PICKER  NATIVE", "RECENT METADATA  PRIVATE", "ZIP  MULTI-ROM"),
                items, List.of("D-PAD MOVE", "[A] OPEN", "[B] BACK", "[START] OPEN ROM"));
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
                List.of("D-PAD MOVE", "[A] OPEN", "[B] CANCEL"));
    }

    private MenuPageSpec confirmationPage() {
        ConfirmVariant variant = confirmVariant == null ? ConfirmVariant.RESET : confirmVariant;
        return page(MenuRoute.CONFIRM_ACTION, "COFFEE GB", "CONFIRM ACTION", "", variant.label,
                List.of(variant.description, "A CONFIRM", "B CANCEL"),
                List.of(item("cancel", "CANCEL", "RETURN", true),
                        item("confirm", "CONFIRM", variant.label, true)),
                List.of("D-PAD MOVE", "[A] CONFIRM", "[B] CANCEL"));
    }

    private static MenuPageSpec page(MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines,
            List<MenuPageSpec.Item> items, List<String> hints) {
        return new MenuPageSpec(route, title, context, headerAction, sideHeading, sideLines,
                items, 1, hints);
    }

    private static MenuPageSpec.Item item(String id, String label, String detail, boolean enabled) {
        return new MenuPageSpec.Item(id, label, detail, enabled);
    }

    private static MenuPageSpec.Item item(String id, String label, String detail, boolean enabled,
            String secondaryId) {
        return new MenuPageSpec.Item(id, label, detail, enabled, secondaryId);
    }

    private static boolean isGameController(int source) {
        return (source & android.view.InputDevice.SOURCE_GAMEPAD)
                        == android.view.InputDevice.SOURCE_GAMEPAD
                || (source & android.view.InputDevice.SOURCE_JOYSTICK)
                        == android.view.InputDevice.SOURCE_JOYSTICK
                || (source & android.view.InputDevice.SOURCE_DPAD)
                        == android.view.InputDevice.SOURCE_DPAD;
    }

    private static MenuKey menuKey(KeyEvent event) {
        return switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> MenuKey.A;
            case KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_BACK -> MenuKey.B;
            case KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_FORWARD_DEL,
                    KeyEvent.KEYCODE_DEL -> MenuKey.SECONDARY;
            case KeyEvent.KEYCODE_BUTTON_START -> MenuKey.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> MenuKey.SELECT;
            default -> null;
        };
    }

    private void openRomDocument(View ignored) {
        if (runtime == null) {
            return;
        }
        Intent request = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream",
                        "application/x-gameboy-rom",
                        "application/zip",
                        "application/x-zip-compressed",
                })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(request, OPEN_ROM_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data == null ? null : data.getData();
        if (requestCode == OPEN_ROM_REQUEST) {
            if (resultCode != RESULT_OK || uri == null) {
                openRomPickerState = openRomPickerState.canceled();
                restoreMenuAfterOpenRomCancel();
                return;
            }
            // Opening another ROM supersedes the old paused/menu session. Do not restore or resume
            // it even when runtime binding finishes after this result callback.
            openRomPickerState = openRomPickerState.completed();
        }
        if (resultCode != RESULT_OK || uri == null) {
            return;
        }
        PendingDocumentResult result = new PendingDocumentResult(
                requestCode, uri, data.getFlags());
        if (runtime == null) {
            pendingDocumentResult = result;
            return;
        }
        dispatchDocumentResult(result);
    }

    private void restoreOpenRomPickerState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        String routeName = savedInstanceState.getString(STATE_OPEN_ROM_PICKER_ROUTE);
        if (routeName == null) {
            return;
        }
        try {
            openRomPickerState = OpenRomPickerState.restored(
                    MenuRoute.valueOf(routeName),
                    savedInstanceState.getBoolean(STATE_OPEN_ROM_PICKER_PAUSE_OWNED),
                    savedInstanceState.getBoolean(STATE_OPEN_ROM_PICKER_RESTORE));
        } catch (IllegalArgumentException ignored) {
            openRomPickerState = OpenRomPickerState.none();
        }
    }

    private void restoreMenuAfterOpenRomCancel() {
        OpenRomPickerState pickerState = openRomPickerState;
        AndroidEmulationRuntime active = runtime;
        if (!pickerState.restoreRequested() || active == null || menuController == null) {
            return;
        }
        openRomPickerState = pickerState.completed();
        active.input().releaseAll();
        menuPauseOwned = pickerState.pauseOwned();
        if (menuPauseOwned) {
            active.pause();
        }
        refreshMenuPages();
        menuController.show(pickerState.route());
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
        switch (result.requestCode()) {
            case OPEN_ROM_REQUEST -> active.openRom(result.uri(), result.flags());
            case IMPORT_BATTERY_REQUEST -> active.importBattery(result.uri());
            case EXPORT_BATTERY_REQUEST -> confirmExport(
                    "Export battery save?", "The chosen document will be replaced.",
                    () -> active.exportBattery(result.uri()));
            case IMPORT_STATE_REQUEST -> active.importState(result.uri());
            case EXPORT_STATE_REQUEST -> confirmExport(
                    "Export state slot 0?", "The chosen document will be replaced.",
                    () -> active.exportState(result.uri()));
            case EXPORT_SCREENSHOT_REQUEST -> confirmExport(
                    "Export native screenshot?", "The chosen document will be replaced.",
                    () -> active.exportScreenshot(result.uri()));
            case EXPORT_PRINTER_REQUEST -> confirmExport(
                    "Export printer paper?", "The chosen document will be replaced.",
                    () -> active.exportPrinter(result.uri(), () -> sharePrinter(result.uri())));
            default -> { }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST || runtime == null) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            runtime.setCameraEnabled(true);
        } else {
            runtime.setCameraEnabled(false);
            Toast.makeText(this, "Pocket Camera will use its test pattern until camera access is allowed.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chooseBatteryImport(View ignored) {
        confirmImport(
                "Import battery save?",
                "Importing can replace this ROM's app-private battery save.",
                IMPORT_BATTERY_REQUEST);
    }

    private void chooseBatteryExport(View ignored) {
        chooseExport(EXPORT_BATTERY_REQUEST, "battery.sav");
    }

    private void chooseStateImport(View ignored) {
        confirmImport(
                "Import state slot 0?",
                "Importing can replace this ROM's app-private state slot 0.",
                IMPORT_STATE_REQUEST);
    }

    private void chooseStateExport(View ignored) {
        chooseExport(EXPORT_STATE_REQUEST, "slot-0.cgbstate");
    }

    private void chooseScreenshotExport(View ignored) {
        if (!canTransfer()) {
            return;
        }
        startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/png")
                        .putExtra(Intent.EXTRA_TITLE, "coffee-gb.png")
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                EXPORT_SCREENSHOT_REQUEST);
    }

    private void choosePrinterExport() {
        if (runtime == null) {
            return;
        }
        startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/png")
                        .putExtra(Intent.EXTRA_TITLE, "coffee-gb-printer.png")
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                EXPORT_PRINTER_REQUEST);
    }

    private void configureTouchControls() {
        TouchControlsLayout initial = video.touchLayout();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding, padding, padding);

        TextView fixedSkin = new TextView(this);
        fixedSkin.setText("The Coffee GB skin fixes the visible control positions.");
        form.addView(fixedSkin);
        Switch haptics = new Switch(this);
        haptics.setText("Haptic feedback");
        haptics.setChecked(initial.haptics());
        form.addView(haptics);

        new AlertDialog.Builder(this)
                .setTitle("Touch controls")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", (dialog, ignored) -> video.updateTouchLayout(
                        new TouchControlsLayout(TouchControlsLayout.DEFAULT_OPACITY,
                                TouchControlsLayout.DEFAULT_SCALE,
                                TouchControlsLayout.DEFAULT_VERTICAL_POSITION, false, false)))
                .setPositiveButton("Save", (dialog, ignored) -> video.updateTouchLayout(
                        new TouchControlsLayout(TouchControlsLayout.DEFAULT_OPACITY,
                                TouchControlsLayout.DEFAULT_SCALE,
                                TouchControlsLayout.DEFAULT_VERTICAL_POSITION, false,
                                haptics.isChecked())))
                .show();
    }

    private SeekBar slider(LinearLayout form, String label, int min, int max, int value) {
        TextView text = new TextView(this);
        text.setText(label);
        form.addView(text);
        SeekBar slider = new SeekBar(this);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        form.addView(slider);
        return slider;
    }

    private void configureController() {
        AndroidEmulationRuntime active = runtime;
        if (active == null) {
            return;
        }
        AndroidInputRouter input = active.input();
        String name = input.activeControllerName();
        if (name == null) {
            Toast.makeText(this, "Connect or press a game controller first.", Toast.LENGTH_SHORT).show();
            return;
        }
        eu.rekawek.coffeegb.core.joypad.Button[] targets = {
                eu.rekawek.coffeegb.core.joypad.Button.A,
                eu.rekawek.coffeegb.core.joypad.Button.B,
                eu.rekawek.coffeegb.core.joypad.Button.START,
                eu.rekawek.coffeegb.core.joypad.Button.SELECT,
                eu.rekawek.coffeegb.core.joypad.Button.UP,
                eu.rekawek.coffeegb.core.joypad.Button.DOWN,
                eu.rekawek.coffeegb.core.joypad.Button.LEFT,
                eu.rekawek.coffeegb.core.joypad.Button.RIGHT
        };
        String[] items = {
                "Map A", "Map B", "Map Start", "Map Select", "Map Up", "Map Down",
                "Map Left", "Map Right", "Horizontal axis: "
                        + (input.horizontalInverted() ? "inverted" : "normal"),
                "Vertical axis: " + (input.verticalInverted() ? "inverted" : "normal"),
                "Reset this controller"
        };
        new AlertDialog.Builder(this)
                .setTitle("Controller: " + name)
                .setItems(items, (dialog, which) -> {
                    if (which < targets.length) {
                        if (input.beginCapture(targets[which])) {
                            Toast.makeText(this, "Press the button to map. A conflicting mapping is replaced.",
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (which == targets.length) {
                        if (input.toggleHorizontalInversion()) {
                            Toast.makeText(this, "Horizontal axis toggled.", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == targets.length + 1) {
                        if (input.toggleVerticalInversion()) {
                            Toast.makeText(this, "Vertical axis toggled.", Toast.LENGTH_SHORT).show();
                        }
                    } else if (input.resetActiveController()) {
                        Toast.makeText(this, "Controller mappings reset.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showSettings() {
        String[] choices = {"Audio", "Touch controls", "Controller mapping", "Optional devices", "Video", "System profile",
                "Rewind and save behavior"};
        new AlertDialog.Builder(this).setTitle("Settings").setItems(choices, (dialog, which) -> {
            switch (which) {
                case 0 -> configureAudio();
                case 1 -> configureTouchControls();
                case 2 -> configureController();
                case 3 -> configureOptionalDevices();
                case 4 -> showUnavailable("Video", "Video uses native nearest-neighbor rendering with aspect-preserving fit.");
                case 5 -> showUnavailable("System profile", "Profile selection is determined safely when the ROM opens; changing it during a session is unavailable.");
                case 6 -> showUnavailable("Rewind and save behavior", "Rewind and battery-save behavior use the portable session defaults. Live changes are unavailable during a session.");
                default -> { }
            }
        }).show();
    }

    private void configureAudio() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int volume = getPreferences(MODE_PRIVATE).getInt("audio.volume", 100);
        boolean muted = getPreferences(MODE_PRIVATE).getBoolean("audio.muted", false);
        SeekBar slider = slider(form, "Volume", 0, 100, volume);
        Switch mute = new Switch(this);
        mute.setText("Mute audio");
        mute.setChecked(muted);
        form.addView(mute);
        new AlertDialog.Builder(this).setTitle("Audio").setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    getPreferences(MODE_PRIVATE).edit().putInt("audio.volume", slider.getProgress())
                            .putBoolean("audio.muted", mute.isChecked()).apply();
                    requireRuntime(active -> {
                        active.setAudioVolume(slider.getProgress());
                        active.setAudioMuted(mute.isChecked());
                    });
                }).show();
    }

    private void configureOptionalDevices() {
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        Switch rumble = new Switch(this);
        rumble.setText("Rumble when supported by the game and device");
        rumble.setChecked(getPreferences(MODE_PRIVATE).getBoolean("devices.rumble", false));
        options.addView(rumble);
        Switch camera = new Switch(this);
        camera.setText("Use live camera for Pocket Camera cartridges");
        camera.setChecked(getPreferences(MODE_PRIVATE).getBoolean("devices.camera", false));
        options.addView(camera);
        Switch printer = new Switch(this);
        printer.setText("Emulate the Game Boy Printer");
        printer.setChecked(getPreferences(MODE_PRIVATE).getBoolean("devices.printer", false));
        options.addView(printer);
        Button calibrateTilt = new Button(this);
        calibrateTilt.setText("Set current position as tilt neutral");
        calibrateTilt.setOnClickListener(ignored -> {
            requireRuntime(AndroidEmulationRuntime::calibrateTilt);
            Toast.makeText(this, "Tilt will calibrate using the next sensor sample.",
                    Toast.LENGTH_SHORT).show();
        });
        options.addView(calibrateTilt);
        Button previewPrinter = new Button(this);
        previewPrinter.setText("Preview printer paper");
        previewPrinter.setOnClickListener(ignored -> requireRuntime(this::showPrinterPreview));
        options.addView(previewPrinter);
        Button exportPrinter = new Button(this);
        exportPrinter.setText("Export and share printer paper");
        exportPrinter.setOnClickListener(ignored -> choosePrinterExport());
        options.addView(exportPrinter);
        new AlertDialog.Builder(this).setTitle("Optional devices").setView(options)
                .setMessage("Tilt, camera, and printer integrations require a compatible cartridge and are configured only when available.")
                .setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, which) -> {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("devices.rumble", rumble.isChecked()).apply();
                    getPreferences(MODE_PRIVATE).edit().putBoolean("devices.camera", camera.isChecked()).apply();
                    getPreferences(MODE_PRIVATE).edit().putBoolean("devices.printer", printer.isChecked()).apply();
                    requireRuntime(active -> {
                        active.setRumbleEnabled(rumble.isChecked());
                        active.setPrinterEnabled(printer.isChecked());
                    });
                    if (camera.isChecked()) {
                        enableCameraIfPermitted();
                    } else {
                        requireRuntime(active -> active.setCameraEnabled(false));
                    }
                }).show();
    }

    private void enableCameraIfPermitted() {
        if (runtime == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runtime.setCameraEnabled(true);
            return;
        }
        runtime.setCameraEnabled(false);
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    private void showPrinterPreview(AndroidEmulationRuntime active) {
        active.previewPrinter(bitmap -> {
            if (bitmap == null) {
                Toast.makeText(this, "Nothing has been printed yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(image);
            new AlertDialog.Builder(this).setTitle("Game Boy Printer paper").setView(scroll)
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Clear", (dialog, which) -> active.clearPrinter())
                    .setPositiveButton("Export", (dialog, which) -> choosePrinterExport()).show();
        });
    }

    private void sharePrinter(android.net.Uri uri) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri);
        share.setClipData(ClipData.newRawUri("Game Boy Printer paper", uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share printer paper"));
    }

    private void showUnavailable(String title, String reason) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(reason)
                .setPositiveButton("OK", null).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("About Coffee GB Android")
                .setMessage("Coffee GB is GPL-3.0-or-later software. This MVP opens GB, GBC, and ZIP documents you select, stores saves privately by ROM identity, and exports only when you choose a document. It requests no network, broad storage, or microphone permission. Camera access is requested only after you enable live Pocket Camera capture; optional rumble uses Android's normal vibration permission only when enabled.\n\nSource and third-party notices: github.com/trekawek/coffee-gb")
                .setPositiveButton("OK", null).show();
    }

    private void confirmImport(String title, String message, int requestCode) {
        if (!canTransfer()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose document", (dialog, ignored) -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/octet-stream")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        requestCode))
                .show();
    }

    private void chooseExport(int requestCode, String suggestedName) {
        if (!canTransfer()) {
            return;
        }
        startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, suggestedName)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                requestCode);
    }

    private void confirmExport(String title, String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Export", (dialog, ignored) -> action.run())
                .show();
    }

    private boolean canTransfer() {
        return runtime != null && observedState.transferReady() && !observedState.flushPending();
    }

    private void applyState(RuntimeState state) {
        observedState = state;
        refreshMenuPages();
        if (runtime == null || menuController == null) {
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

    private void requireRuntime(RuntimeAction action) {
        AndroidEmulationRuntime active = runtime;
        if (active != null) {
            action.run(active);
        }
    }

    @FunctionalInterface
    private interface RuntimeAction {
        void run(AndroidEmulationRuntime runtime);
    }

    private record PendingDocumentResult(int requestCode, Uri uri, int flags) { }

    private enum StateMenuMode {
        SAVE,
        LOAD
    }

    private enum ConfirmVariant {
        RESET("RESET GAME", "UNSAVED PROGRESS MAY BE LOST"),
        STOP("STOP GAME", "THE CURRENT SESSION WILL END"),
        OVERWRITE("OVERWRITE STATE", "THE EXISTING SLOT WILL BE REPLACED"),
        DELETE("DELETE STATE", "THE SAVED SLOT CANNOT BE RECOVERED");

        private final String label;
        private final String description;

        ConfirmVariant(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }
}
