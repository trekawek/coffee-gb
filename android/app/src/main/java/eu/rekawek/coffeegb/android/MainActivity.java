package eu.rekawek.coffeegb.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import eu.rekawek.coffeegb.controller.state.StateRef;
import eu.rekawek.coffeegb.controller.state.StateRepository;
import eu.rekawek.coffeegb.controller.state.StateStorageLayout;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.RomImage;
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Permission-free SAF ROM-opening probe. ROM decoding and app-private storage work run on the
 * dedicated worker; the UI thread only launches documents and renders immutable results.
 */
public final class MainActivity extends Activity {

    private static final int OPEN_ROM_REQUEST = 1;
    private static final int IMPORT_BATTERY_REQUEST = 2;
    private static final int EXPORT_BATTERY_REQUEST = 3;
    private static final int IMPORT_STATE_REQUEST = 4;
    private static final int EXPORT_STATE_REQUEST = 5;

    private final ExecutorService romWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coffee-gb-android-rom-open");
        thread.setDaemon(true);
        return thread;
    });

    private TextView status;
    private Button importBattery;
    private Button exportBattery;
    private Button importState;
    private Button exportState;

    private volatile StateStorageLayout activeLayout;
    private volatile StateRepository activeStates;

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
        status.setContentDescription("Coffee GB Android ROM loading status");
        status.setText("Coffee GB Android is ready. Choose a ROM or ZIP document.");
        content.addView(status);

        Button open = new Button(this);
        open.setText("Open ROM");
        open.setOnClickListener(this::openRomDocument);
        content.addView(open);

        Button recent = new Button(this);
        recent.setText("Open recent ROM");
        recent.setOnClickListener(this::openRecentRom);
        content.addView(recent);

        importBattery = transferButton("Import battery save", this::chooseBatteryImport);
        exportBattery = transferButton("Export battery save", this::chooseBatteryExport);
        importState = transferButton("Import state slot 0", this::chooseStateImport);
        exportState = transferButton("Export state slot 0", this::chooseStateExport);
        content.addView(importBattery);
        content.addView(exportBattery);
        content.addView(importState);
        content.addView(exportState);
        setContentView(content);
    }

    @Override
    protected void onDestroy() {
        romWorker.shutdownNow();
        super.onDestroy();
    }

    private void openRomDocument(View ignored) {
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == OPEN_ROM_REQUEST) {
            retainReadPermission(uri, data.getFlags());
            status.setText("Opening selected ROM…");
            romWorker.execute(() -> inspect(uri));
            return;
        }
        switch (requestCode) {
            case IMPORT_BATTERY_REQUEST -> importBattery(uri);
            case EXPORT_BATTERY_REQUEST -> confirmExport(
                    "Export battery save?",
                    "The chosen document will be replaced.",
                    () -> exportBattery(uri));
            case IMPORT_STATE_REQUEST -> importState(uri);
            case EXPORT_STATE_REQUEST -> confirmExport(
                    "Export state slot 0?",
                    "The chosen document will be replaced.",
                    () -> exportState(uri));
            default -> { }
        }
    }

    private void retainReadPermission(Uri uri, int resultFlags) {
        int read = resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int persistable = resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (read == 0 || persistable == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, read);
        } catch (SecurityException ignored) {
            // Some providers lawfully grant only the activity-lifetime permission. The document
            // can still be opened now but is not retained as a recent document.
        }
    }

    private void openRecentRom(View ignored) {
        status.setText("Checking recent ROM permissions…");
        romWorker.execute(() -> {
            List<Uri> recent = new RecentSafDocuments(getApplicationContext()).readable();
            if (recent.isEmpty()) {
                showFailure("No readable recent ROM document is available.");
                return;
            }
            String[] labels = new String[recent.size()];
            for (int index = 0; index < labels.length; index++) {
                labels[index] = "Recent ROM " + (index + 1);
            }
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Open recent ROM")
                    .setItems(labels, (dialog, index) -> {
                        status.setText("Opening recent ROM…");
                        romWorker.execute(() -> inspect(recent.get(index)));
                    })
                    .show());
        });
    }

    private Button transferButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setEnabled(false);
        button.setOnClickListener(listener);
        return button;
    }

    private void chooseBatteryImport(View ignored) {
        confirmImport(
                "Import battery save?",
                "Importing can replace this ROM's app-private battery save.",
                IMPORT_BATTERY_REQUEST,
                "application/octet-stream");
    }

    private void chooseBatteryExport(View ignored) {
        chooseExport(EXPORT_BATTERY_REQUEST, "battery.sav");
    }

    private void chooseStateImport(View ignored) {
        confirmImport(
                "Import state slot 0?",
                "Importing can replace this ROM's app-private state slot 0.",
                IMPORT_STATE_REQUEST,
                "application/octet-stream");
    }

    private void chooseStateExport(View ignored) {
        chooseExport(EXPORT_STATE_REQUEST, "slot-0.cgbstate");
    }

    private void confirmImport(String title, String message, int requestCode, String mimeType) {
        if (!ensureLoaded()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose document", (dialog, ignored) -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType(mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        requestCode))
                .show();
    }

    private void chooseExport(int requestCode, String suggestedName) {
        if (!ensureLoaded()) {
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

    private boolean ensureLoaded() {
        if (activeLayout != null && activeStates != null) {
            return true;
        }
        showFailure("Open a ROM before importing or exporting its save data.");
        return false;
    }

    private void importBattery(Uri source) {
        StateStorageLayout layout = activeLayout;
        if (layout == null) return;
        status.setText("Importing battery save…");
        romWorker.execute(() -> {
            try {
                SafPersistenceExchange.importBattery(
                        getContentResolver(),
                        source,
                        layout,
                        SafPersistenceExchange.CollisionDecision.REPLACE);
                runOnUiThread(() -> status.setText("Battery save imported."));
            } catch (Exception failure) {
                showFailure("Coffee GB could not import that battery save.");
            }
        });
    }

    private void exportBattery(Uri destination) {
        StateStorageLayout layout = activeLayout;
        if (layout == null) return;
        status.setText("Exporting battery save…");
        romWorker.execute(() -> {
            try {
                SafPersistenceExchange.exportBattery(getContentResolver(), destination, layout, true);
                runOnUiThread(() -> status.setText("Battery save exported."));
            } catch (Exception failure) {
                showFailure("Coffee GB could not export the battery save.");
            }
        });
    }

    private void importState(Uri source) {
        StateRepository states = activeStates;
        if (states == null) return;
        status.setText("Importing state slot 0…");
        romWorker.execute(() -> {
            try {
                SafPersistenceExchange.importState(
                        getContentResolver(),
                        source,
                        states,
                        new StateRef.Slot(0),
                        SafPersistenceExchange.CollisionDecision.REPLACE);
                runOnUiThread(() -> status.setText("State slot 0 imported."));
            } catch (Exception failure) {
                showFailure("Coffee GB could not import that state file.");
            }
        });
    }

    private void exportState(Uri destination) {
        StateRepository states = activeStates;
        if (states == null) return;
        status.setText("Exporting state slot 0…");
        romWorker.execute(() -> {
            try {
                SafPersistenceExchange.exportState(
                        getContentResolver(), destination, states, new StateRef.Slot(0), true);
                runOnUiThread(() -> status.setText("State slot 0 exported."));
            } catch (Exception failure) {
                showFailure("Coffee GB could not export state slot 0.");
            }
        });
    }

    private void confirmExport(String title, String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Export", (dialog, ignored) -> action.run())
                .show();
    }

    private void inspect(Uri uri) {
        try {
            AndroidRomInput input = new AndroidRomInput(getContentResolver(), uri);
            RomSourceSnapshot snapshot = RomSourceSnapshot.open(input);
            if (!snapshot.isArchive() || snapshot.candidates().size() == 1) {
                load(snapshot, snapshot.isArchive()
                        ? snapshot.candidates().get(0).token()
                        : RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN, uri);
                return;
            }
            List<RomSourceSnapshot.ArchiveCandidate> candidates = snapshot.candidates();
            String[] names = candidates.stream()
                    .map(RomSourceSnapshot.ArchiveCandidate::displayName)
                    .toArray(String[]::new);
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Choose ROM from archive")
                    .setItems(names, (dialog, index) -> romWorker.execute(
                            () -> load(snapshot, candidates.get(index).token(), uri)))
                    .setOnCancelListener(dialog -> closeQuietly(snapshot))
                    .show());
        } catch (Exception failure) {
            // Deliberately do not expose content URIs or provider details in the UI.
            forgetRevokedPermission(uri, failure);
            showFailure("Coffee GB could not open this document. Check its permission and format.");
        }
    }

    private void load(RomSourceSnapshot snapshot, long token, Uri sourceUri) {
        try {
            RomImage image = token == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN
                    ? snapshot.loadSingle()
                    : snapshot.load(token);
            Rom rom = new Rom(image);
            AndroidRomPersistenceStore store = new AndroidRomPersistenceStore(getApplicationContext());
            // Construct the hash-keyed layout now so a valid document never needs a path beside
            // the provider's source. Controller session activation consumes this same store.
            StateStorageLayout layout = store.layout(
                    eu.rekawek.coffeegb.controller.state.StateIdentity.INSTANCE.hash(rom).hex());
            StateRepository states = new StateRepository(
                    layout,
                    eu.rekawek.coffeegb.core.persistence.AtomicFileWriter.system());
            new RecentSafDocuments(getApplicationContext()).recordIfPersisted(sourceUri);
            runOnUiThread(() -> {
                activeLayout = layout;
                activeStates = states;
                importBattery.setEnabled(true);
                exportBattery.setEnabled(true);
                importState.setEnabled(true);
                exportState.setEnabled(true);
                status.setText("Loaded " + rom.getTitle() + ". App-private saves are ready.");
            });
        } catch (Exception failure) {
            forgetRevokedPermission(sourceUri, failure);
            showFailure("Coffee GB could not load the selected ROM.");
        } finally {
            closeQuietly(snapshot);
        }
    }

    private void forgetRevokedPermission(Uri uri, Exception failure) {
        if (failure instanceof SecurityException || failure instanceof IOException) {
            for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(uri) && permission.isReadPermission()) {
                    getContentResolver().releasePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
        }
    }

    private void showFailure(String message) {
        runOnUiThread(() -> status.setText(message));
    }

    private static void closeQuietly(RomSourceSnapshot snapshot) {
        try {
            snapshot.close();
        } catch (IOException ignored) {
            // Stream snapshots have no path to unlink; a close failure is not user actionable.
        }
    }
}
