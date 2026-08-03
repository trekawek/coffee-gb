package eu.rekawek.coffeegb.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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

    private TextView status;
    private CoffeeGbSurfaceView video;
    private Button open;
    private Button recent;
    private Button pauseMenu;
    private Button resume;
    private Button stop;
    private Button states;
    private Button settings;
    private Button about;
    private Button importBattery;
    private Button exportBattery;
    private Button importState;
    private Button exportState;
    private Button exportScreenshot;
    private Button touchControls;
    private Button controllerMapping;

    private AndroidEmulationRuntime runtime;
    private boolean bound;
    private InputManager inputManager;
    private long shownSelectionGeneration = -1L;

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
            applyState(runtime.state());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (runtime != null) {
                video.detach();
                runtime.removeObserver(MainActivity.this);
            }
            runtime = null;
            bound = false;
            disableCommands();
            status.setText("Coffee GB runtime stopped. Reopen the app to choose a ROM.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setContentDescription("Coffee GB Android runtime status");
        status.setText("Starting Coffee GB Android runtime…");
        content.addView(status);

        video = new CoffeeGbSurfaceView(this);
        int videoHeight = (int) (240 * getResources().getDisplayMetrics().density);
        content.addView(video, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));

        open = button("Open ROM", this::openRomDocument);
        recent = button("Open recent ROM", ignored -> requireRuntime(AndroidEmulationRuntime::requestRecentDocuments));
        pauseMenu = button("Pause and menu", ignored -> showPauseMenu());
        resume = button("Resume", ignored -> requireRuntime(AndroidEmulationRuntime::resume));
        stop = button("Stop game", ignored -> requireRuntime(AndroidEmulationRuntime::stop));
        states = button("Save states", ignored -> showStateSlots());
        settings = button("Settings", ignored -> showSettings());
        about = button("About, licenses, and privacy", ignored -> showAbout());
        importBattery = button("Import battery save", this::chooseBatteryImport);
        exportBattery = button("Export battery save", this::chooseBatteryExport);
        importState = button("Import state slot 0", this::chooseStateImport);
        exportState = button("Export state slot 0", this::chooseStateExport);
        exportScreenshot = button("Export native screenshot", this::chooseScreenshotExport);
        touchControls = button("Touch controls", ignored -> configureTouchControls());
        controllerMapping = button("Controller mapping", ignored -> configureController());
        content.addView(open);
        content.addView(recent);
        content.addView(pauseMenu);
        content.addView(resume);
        content.addView(stop);
        content.addView(states);
        content.addView(settings);
        content.addView(importBattery);
        content.addView(exportBattery);
        content.addView(importState);
        content.addView(exportState);
        content.addView(exportScreenshot);
        content.addView(touchControls);
        content.addView(controllerMapping);
        content.addView(about);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        disableCommands();
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
        AndroidEmulationRuntime active = runtime;
        if (active != null && active.input().onKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
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
        }
    }

    @Override
    public void onStateChanged(RuntimeState state) {
        applyState(state);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null || runtime == null) {
            return;
        }
        switch (requestCode) {
            case OPEN_ROM_REQUEST -> runtime.openRom(data.getData(), data.getFlags());
            case IMPORT_BATTERY_REQUEST -> runtime.importBattery(data.getData());
            case EXPORT_BATTERY_REQUEST -> confirmExport(
                    "Export battery save?", "The chosen document will be replaced.",
                    () -> runtime.exportBattery(data.getData()));
            case IMPORT_STATE_REQUEST -> runtime.importState(data.getData());
            case EXPORT_STATE_REQUEST -> confirmExport(
                    "Export state slot 0?", "The chosen document will be replaced.",
                    () -> runtime.exportState(data.getData()));
            case EXPORT_SCREENSHOT_REQUEST -> confirmExport(
                    "Export native screenshot?", "The chosen document will be replaced.",
                    () -> runtime.exportScreenshot(data.getData()));
            default -> { }
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
        if (runtime == null || !exportScreenshot.isEnabled()) {
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

    private void configureTouchControls() {
        TouchControlsLayout initial = video.touchLayout();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding, padding, padding);

        SeekBar opacity = slider(form, "Opacity", 15, 100,
                Math.round(initial.opacity() * 100));
        SeekBar scale = slider(form, "Size", 60, 140,
                Math.round(initial.scale() * 100));
        SeekBar vertical = slider(form, "Raise controls", 0, 100,
                Math.round(initial.verticalPosition() * 100));
        Switch leftHanded = new Switch(this);
        leftHanded.setText("Left-handed layout");
        leftHanded.setChecked(initial.leftHanded());
        form.addView(leftHanded);
        Switch haptics = new Switch(this);
        haptics.setText("Haptic feedback");
        haptics.setChecked(initial.haptics());
        form.addView(haptics);

        new AlertDialog.Builder(this)
                .setTitle("Touch controls")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", (dialog, ignored) -> video.resetTouchLayout())
                .setPositiveButton("Save", (dialog, ignored) -> video.updateTouchLayout(
                        new TouchControlsLayout(opacity.getProgress() / 100f,
                                scale.getProgress() / 100f, vertical.getProgress() / 100f,
                                leftHanded.isChecked(), haptics.isChecked())))
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

    private void showPauseMenu() {
        AndroidEmulationRuntime active = runtime;
        if (active == null || !pauseMenu.isEnabled()) {
            return;
        }
        active.pause();
        String[] choices = {"Resume", "Reset game", "Save state (slot 0)", "Load state (slot 0)",
                "Save states", "Settings", "Stop game"};
        new AlertDialog.Builder(this)
                .setTitle("Game paused")
                .setItems(choices, (dialog, which) -> {
                    switch (which) {
                        case 0 -> active.resume();
                        case 1 -> new AlertDialog.Builder(this).setTitle("Reset game?")
                                .setMessage("Unsaved progress in the running game may be lost.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Reset", (ignored, button) -> active.reset()).show();
                        case 2 -> active.saveSnapshot(0);
                        case 3 -> active.restoreSnapshot(0);
                        case 4 -> showStateSlots();
                        case 5 -> showSettings();
                        case 6 -> new AlertDialog.Builder(this).setTitle("Stop game?")
                                .setMessage("The running session will end after its safe cleanup.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Stop", (ignored, button) -> active.stop()).show();
                        default -> { }
                    }
                }).show();
    }

    private void showStateSlots() {
        AndroidEmulationRuntime active = runtime;
        if (active == null || !states.isEnabled()) {
            return;
        }
        active.listStateSlots(slots -> {
            if (slots.isEmpty()) {
                Toast.makeText(this, "Open a ROM before managing save states.", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] labels = slots.stream().map(AndroidStateSlot::label).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("Save states")
                    .setItems(labels, (dialog, which) -> showStateSlotActions(slots.get(which))).show();
        });
    }

    private void showStateSlotActions(AndroidStateSlot slot) {
        AndroidEmulationRuntime active = runtime;
        if (active == null) {
            return;
        }
        List<String> choices = new java.util.ArrayList<>();
        choices.add("Save or overwrite");
        if (slot.loadable()) {
            choices.add("Load");
            choices.add("Delete");
        }
        new AlertDialog.Builder(this).setTitle("State slot " + slot.index())
                .setMessage(slot.detail()).setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    String choice = choices.get(which);
                    if (choice.equals("Save or overwrite")) {
                        active.saveSnapshot(slot.index());
                    } else if (choice.equals("Load")) {
                        active.restoreSnapshot(slot.index());
                    } else {
                        new AlertDialog.Builder(this).setTitle("Delete state slot?")
                                .setMessage("This cannot be undone.").setNegativeButton("Cancel", null)
                                .setPositiveButton("Delete", (ignored, button) -> active.deleteSnapshot(slot.index()))
                                .show();
                    }
                }).show();
    }

    private void showSettings() {
        String[] choices = {"Audio", "Touch controls", "Controller mapping", "Video", "System profile",
                "Rewind and save behavior"};
        new AlertDialog.Builder(this).setTitle("Settings").setItems(choices, (dialog, which) -> {
            switch (which) {
                case 0 -> configureAudio();
                case 1 -> configureTouchControls();
                case 2 -> configureController();
                case 3 -> showUnavailable("Video", "Video uses native nearest-neighbor rendering with aspect-preserving fit.");
                case 4 -> showUnavailable("System profile", "Profile selection is determined safely when the ROM opens; changing it during a session is unavailable.");
                case 5 -> showUnavailable("Rewind and save behavior", "Rewind and battery-save behavior use the portable session defaults. Live changes are unavailable during a session.");
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

    private void showUnavailable(String title, String reason) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(reason)
                .setPositiveButton("OK", null).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("About Coffee GB Android")
                .setMessage("Coffee GB is GPL-3.0-or-later software. This MVP opens GB, GBC, and ZIP documents you select, stores saves privately by ROM identity, and exports only when you choose a document. It requests no network, broad storage, microphone, camera, or vibration permission.\n\nSource and third-party notices: github.com/trekawek/coffee-gb")
                .setPositiveButton("OK", null).show();
    }

    private void confirmImport(String title, String message, int requestCode) {
        if (runtime == null || !importBattery.isEnabled()) {
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
        if (runtime == null || !exportBattery.isEnabled()) {
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

    private void applyState(RuntimeState state) {
        status.setText(state.message());
        boolean ready = runtime != null;
        open.setEnabled(ready);
        recent.setEnabled(ready);
        pauseMenu.setEnabled(ready && state.transferReady() && !state.paused());
        resume.setEnabled(ready && state.paused() && state.transferReady());
        stop.setEnabled(ready && state.transferReady());
        states.setEnabled(ready && state.transferReady() && !state.flushPending());
        settings.setEnabled(ready);
        about.setEnabled(true);
        importBattery.setEnabled(ready && state.transferReady() && !state.flushPending());
        exportBattery.setEnabled(ready && state.transferReady() && !state.flushPending());
        importState.setEnabled(ready && state.transferReady() && !state.flushPending());
        exportState.setEnabled(ready && state.transferReady() && !state.flushPending());
        exportScreenshot.setEnabled(ready && state.transferReady() && !state.flushPending());
        touchControls.setEnabled(ready);
        controllerMapping.setEnabled(ready);
        showSelectionIfNeeded(state);
    }

    private void showSelectionIfNeeded(RuntimeState state) {
        if (runtime == null || state.selections().isEmpty()
                || state.generation() == shownSelectionGeneration) {
            return;
        }
        boolean archive = state.phase() == RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION;
        boolean recentSelection = state.phase() == RuntimeState.Phase.AWAITING_RECENT_SELECTION;
        if (!archive && !recentSelection) {
            return;
        }
        shownSelectionGeneration = state.generation();
        List<RuntimeState.Selection> selections = state.selections();
        String[] labels = selections.stream().map(RuntimeState.Selection::label).toArray(String[]::new);
        new AlertDialog.Builder(this)
                .setTitle(archive ? "Choose ROM from archive" : "Open recent ROM")
                .setItems(labels, (dialog, index) -> {
                    RuntimeState.Selection selection = selections.get(index);
                    if (archive) {
                        runtime.selectArchiveCandidate(selection.token());
                    } else {
                        runtime.selectRecentDocument(selection.token());
                    }
                })
                .setOnCancelListener(dialog -> runtime.cancelPendingSelection())
                .show();
    }

    private void requireRuntime(RuntimeAction action) {
        AndroidEmulationRuntime active = runtime;
        if (active != null) {
            action.run(active);
        }
    }

    private void disableCommands() {
        if (open == null) {
            return;
        }
        open.setEnabled(false);
        recent.setEnabled(false);
        pauseMenu.setEnabled(false);
        resume.setEnabled(false);
        stop.setEnabled(false);
        states.setEnabled(false);
        settings.setEnabled(false);
        about.setEnabled(true);
        importBattery.setEnabled(false);
        exportBattery.setEnabled(false);
        importState.setEnabled(false);
        exportState.setEnabled(false);
        exportScreenshot.setEnabled(false);
        touchControls.setEnabled(false);
        controllerMapping.setEnabled(false);
    }

    @FunctionalInterface
    private interface RuntimeAction {
        void run(AndroidEmulationRuntime runtime);
    }
}
