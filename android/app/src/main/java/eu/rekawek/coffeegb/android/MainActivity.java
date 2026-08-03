package eu.rekawek.coffeegb.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private Button resume;
    private Button stop;
    private Button importBattery;
    private Button exportBattery;
    private Button importState;
    private Button exportState;
    private Button exportScreenshot;

    private AndroidEmulationRuntime runtime;
    private boolean bound;
    private long shownSelectionGeneration = -1L;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            runtime = ((EmulationService.RuntimeBinder) service).runtime();
            bound = true;
            runtime.addObserver(MainActivity.this);
            video.attach(runtime.frames());
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
        content.addView(video, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        open = button("Open ROM", this::openRomDocument);
        recent = button("Open recent ROM", ignored -> requireRuntime(AndroidEmulationRuntime::requestRecentDocuments));
        resume = button("Resume", ignored -> requireRuntime(AndroidEmulationRuntime::resume));
        stop = button("Stop game", ignored -> requireRuntime(AndroidEmulationRuntime::stop));
        importBattery = button("Import battery save", this::chooseBatteryImport);
        exportBattery = button("Export battery save", this::chooseBatteryExport);
        importState = button("Import state slot 0", this::chooseStateImport);
        exportState = button("Export state slot 0", this::chooseStateExport);
        exportScreenshot = button("Export native screenshot", this::chooseScreenshotExport);
        content.addView(open);
        content.addView(recent);
        content.addView(resume);
        content.addView(stop);
        content.addView(importBattery);
        content.addView(exportBattery);
        content.addView(importState);
        content.addView(exportState);
        content.addView(exportScreenshot);
        setContentView(content);
        disableCommands();
    }

    @Override
    protected void onStart() {
        super.onStart();
        EmulationService.start(this);
        if (!bound) {
            bindService(new Intent(this, EmulationService.class), connection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        if (bound) {
            video.detach();
            runtime.removeObserver(this);
            unbindService(connection);
            bound = false;
            runtime = null;
        }
        super.onStop();
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
        resume.setEnabled(ready && state.paused() && state.transferReady());
        stop.setEnabled(ready && state.transferReady());
        importBattery.setEnabled(ready && state.transferReady() && !state.flushPending());
        exportBattery.setEnabled(ready && state.transferReady() && !state.flushPending());
        importState.setEnabled(ready && state.transferReady() && !state.flushPending());
        exportState.setEnabled(ready && state.transferReady() && !state.flushPending());
        exportScreenshot.setEnabled(ready && state.transferReady() && !state.flushPending());
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
        resume.setEnabled(false);
        stop.setEnabled(false);
        importBattery.setEnabled(false);
        exportBattery.setEnabled(false);
        importState.setEnabled(false);
        exportState.setEnabled(false);
        exportScreenshot.setEnabled(false);
    }

    @FunctionalInterface
    private interface RuntimeAction {
        void run(AndroidEmulationRuntime runtime);
    }
}
