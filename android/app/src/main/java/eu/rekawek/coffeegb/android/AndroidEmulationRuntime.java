package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import eu.rekawek.coffeegb.android.menu.MenuPreview;
import eu.rekawek.coffeegb.controller.BasicController;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.controller.state.StateIdentity;
import eu.rekawek.coffeegb.controller.state.StateLoadRefRequestEvent;
import eu.rekawek.coffeegb.controller.state.StateRef;
import eu.rekawek.coffeegb.controller.state.StateRepository;
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent;
import eu.rekawek.coffeegb.controller.state.StateStorageLayout;
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.RomImage;
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot;
import eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera;
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The one same-process owner of Coffee GB's Android controller, event bus, selected ROM bytes,
 * persistence handles, and lifecycle operations.
 *
 * <p>Every mutable operation is serialized on {@code coffee-gb-android-runtime}. Observers receive
 * immutable, redacted state snapshots on the main thread and never retain the service or an
 * Activity. The controller remains the sole owner of the emulation timing thread; this runtime
 * only posts its documented commands and observes its documented events.
 */
public final class AndroidEmulationRuntime implements AutoCloseable {

    private static final long BACKGROUND_FLUSH_TIMEOUT_MILLIS = 5_000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService owner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coffee-gb-android-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "coffee-gb-android-runtime-deadline");
                thread.setDaemon(true);
                return thread;
            });
    private final CopyOnWriteArraySet<RuntimeObserver> observers = new CopyOnWriteArraySet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RuntimeLifecycleGate lifecycle = new RuntimeLifecycleGate();
    private final NativeFrameStore frames = new NativeFrameStore();
    private final EmulatorProperties properties = new EmulatorProperties();
    private final AndroidInputRouter input;

    private volatile RuntimeState state = RuntimeState.stopped();
    // Updated directly from controller event dispatch; the owner queues requests until it exists.
    private volatile long activeStateSessionId;

    // Everything below is accessed only on the owner executor.
    private EventBus eventBus;
    private BasicController controller;
    private volatile AndroidAudioSink audio;
    private volatile AndroidRumbleSink rumble;
    private volatile AndroidTiltSink tilt;
    private volatile AndroidCameraSource camera;
    private AndroidPrinterStore printer;
    private boolean printerEnabled;
    private boolean cameraEnabled;
    private AndroidRomPersistenceStore persistenceStore;
    private RomSourceSnapshot pendingSnapshot;
    private Uri pendingSource;
    private List<Uri> pendingRecents = List.of();
    private Uri currentSource;
    private StateStorageLayout activeLayout;
    private StateRepository activeStates;
    private long nextOpenRequestId;
    private long nextStateRequestId;
    private final List<PendingStateRequest> pendingStateRequests = new ArrayList<>();
    private long activeOpenRequestId;
    private long tiltOpenRequestId;
    private boolean tiltRequiredForOpenRequest;
    private long cameraOpenRequestId;
    private boolean cameraRequiredForOpenRequest;
    private long nextFlushRequestId;
    private long pendingFlushRequestId;
    private ScheduledFuture<?> flushDeadline;

    public AndroidEmulationRuntime(Context context) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        input = new AndroidInputRouter(properties.getPlayerInputSource(),
                new AndroidControllerMappings(this.context));
        submit(this::initialize);
    }

    public RuntimeState state() {
        return state;
    }

    /** Service-owned native frames for a short-lived {@link CoffeeGbSurfaceView} attachment. */
    NativeFrameStore frames() {
        return frames;
    }

    /** Service-owned source merger for transient Android touch and controller devices. */
    AndroidInputRouter input() {
        return input;
    }

    /** Applies host-only audio controls without changing emulation timing or save state. */
    void setAudioMuted(boolean muted) {
        AndroidAudioSink activeAudio = audio;
        if (activeAudio != null) {
            activeAudio.setMuted(muted);
        }
    }

    void setAudioVolume(int volume) {
        AndroidAudioSink activeAudio = audio;
        if (activeAudio != null) {
            activeAudio.setVolume(volume);
        }
    }

    /** Applies the user’s host-only rumble preference without changing the portable cartridge. */
    void setRumbleEnabled(boolean enabled) {
        AndroidRumbleSink activeRumble = rumble;
        if (activeRumble != null) {
            activeRumble.setEnabled(enabled);
        }
    }

    /** Calibrates an active MBC7 cartridge to the device's current resting position. */
    void calibrateTilt() {
        AndroidTiltSink activeTilt = tilt;
        if (activeTilt != null) {
            activeTilt.calibrate();
        }
    }

    /** Enables live Pocket Camera capture after the user grants the optional camera permission. */
    void setCameraEnabled(boolean enabled) {
        submit(() -> {
            cameraEnabled = enabled;
            AndroidCameraSource activeCamera = camera;
            if (activeCamera != null) {
                activeCamera.setEnabled(enabled);
            }
        });
    }

    /** Connects or disconnects the portable Game Boy Printer at the controller-safe boundary. */
    void setPrinterEnabled(boolean enabled) {
        submit(() -> {
            printerEnabled = enabled;
            if (controller != null && activeLayout != null) {
                eventBus.post(new Controller.SetPrinterEvent(enabled));
            }
        });
    }

    /** Creates a bounded detached preview away from the main thread. */
    void previewPrinter(Consumer<MenuPreview> callback) {
        Consumer<MenuPreview> checked = Objects.requireNonNull(callback, "callback");
        submit(() -> {
            AndroidPrinterStore.Snapshot snapshot = printer == null ? null : printer.snapshot();
            MenuPreview preview = snapshot == null ? MenuPreview.empty()
                    : snapshot.preview(160, 192);
            mainHandler.post(() -> checked.accept(preview));
        });
    }

    void clearPrinter() {
        submit(() -> {
            if (printer != null) {
                printer.clear();
            }
        });
    }

    /** Pauses one active session at its controller-owned safe point without ending it. */
    public void pause() {
        input.releaseAll();
        AndroidAudioSink activeAudio = audio;
        if (activeAudio != null) {
            activeAudio.pause();
        }
        AndroidRumbleSink activeRumble = rumble;
        if (activeRumble != null) {
            activeRumble.pause();
        }
        AndroidTiltSink activeTilt = tilt;
        if (activeTilt != null) {
            activeTilt.pause();
        }
        AndroidCameraSource activeCamera = camera;
        if (activeCamera != null) {
            activeCamera.pause();
        }
        submit(() -> {
            if (controller != null && activeLayout != null) {
                eventBus.post(new Controller.PauseEmulationEvent());
            }
        });
    }

    /** Resets only the active emulator session through its portable controller event. */
    public void reset() {
        submit(() -> {
            if (controller != null && activeLayout != null) {
                eventBus.post(new Controller.ResetEmulationEvent());
            }
        });
    }

    /** Requests a portable quick-state save at the controller's safe point. */
    public void saveSnapshot(int slot) {
        checkStateSlot(slot);
        submit(() -> requestStateSave(slot));
    }

    /** Requests a portable quick-state restore; controller errors remain typed/redacted. */
    public void restoreSnapshot(int slot) {
        checkStateSlot(slot);
        submit(() -> requestStateLoad(slot));
    }

    /** Reads only redacted state metadata on the runtime owner, then delivers immutable rows on UI. */
    void listStateSlots(Consumer<List<AndroidStateSlot>> callback) {
        Consumer<List<AndroidStateSlot>> checked = Objects.requireNonNull(callback, "callback");
        submit(() -> {
            List<AndroidStateSlot> slots = new ArrayList<>();
            if (activeStates != null) {
                var entries = activeStates.catalog(null).getEntries();
                for (int slot = StateRef.MIN_SLOT; slot <= StateRef.MAX_SLOT; slot++) {
                    int index = slot;
                    var entry = entries.stream()
                            .filter(candidate -> candidate.getRef() instanceof StateRef.Slot
                                    && ((StateRef.Slot) candidate.getRef()).getIndex() == index)
                            .findFirst().orElse(null);
                    slots.add(AndroidStateSlot.from(index, entry));
                }
            }
            List<AndroidStateSlot> snapshot = List.copyOf(slots);
            mainHandler.post(() -> checked.accept(snapshot));
        });
    }

    void deleteSnapshot(int slot) {
        checkStateSlot(slot);
        submit(() -> {
            if (activeStates == null) {
                return;
            }
            try {
                activeStates.delete(new StateRef.Slot(slot));
                publish(state.phase(), "State slot " + slot + " deleted.", List.of(),
                        state.transferReady(), state.paused(), state.flushPending());
            } catch (Exception failure) {
                publish(state.phase(), "Coffee GB could not delete that state slot.", List.of(),
                        state.transferReady(), state.paused(), state.flushPending());
            }
        });
    }

    /** Registers one UI observer and immediately replays the latest immutable snapshot. */
    public void addObserver(RuntimeObserver observer) {
        RuntimeObserver checked = Objects.requireNonNull(observer, "observer");
        observers.add(checked);
        RuntimeState replay = state;
        mainHandler.post(() -> {
            if (observers.contains(checked)) {
                checked.onStateChanged(replay);
            }
        });
    }

    /** Must be paired with {@link #addObserver(RuntimeObserver)} from Activity/service teardown. */
    public void removeObserver(RuntimeObserver observer) {
        observers.remove(observer);
    }

    /** Opens the user-selected SAF document; all metadata, archive and ROM work stays off-main. */
    public void openRom(Uri uri, int resultFlags) {
        Uri checked = Objects.requireNonNull(uri, "uri");
        submit(() -> {
            retainReadPermission(checked, resultFlags);
            openRom(checked);
        });
    }

    /** Selects an opaque archive token published through {@link RuntimeState#selections()}. */
    public void selectArchiveCandidate(long token) {
        submit(() -> {
            RomSourceSnapshot snapshot = pendingSnapshot;
            Uri source = pendingSource;
            if (snapshot == null || source == null) {
                publish(RuntimeState.Phase.FAILED,
                        "That ROM selection is no longer available. Choose the document again.",
                        List.of(), false, false, false);
                return;
            }
            pendingSnapshot = null;
            pendingSource = null;
            activateSnapshot(snapshot, token, source);
        });
    }

    /** Reads persisted recent grants on the runtime owner and publishes redacted choices. */
    public void requestRecentDocuments() {
        submit(() -> {
            List<Uri> recent = new RecentSafDocuments(context).readable();
            if (recent.isEmpty()) {
                publish(RuntimeState.Phase.FAILED,
                        "No readable recent ROM document is available.",
                        List.of(), activeLayout != null, state.paused(), false);
                return;
            }
            pendingRecents = List.copyOf(recent);
            List<RuntimeState.Selection> choices = new ArrayList<>();
            for (int index = 0; index < recent.size(); index++) {
                choices.add(new RuntimeState.Selection(index, "Recent ROM " + (index + 1)));
            }
            publish(RuntimeState.Phase.AWAITING_RECENT_SELECTION,
                    "Choose a recent ROM document.", choices, false, true, false);
        });
    }

    /** Opens a redacted recent-document token published by {@link #requestRecentDocuments()}. */
    public void selectRecentDocument(long token) {
        submit(() -> {
            if (token < 0 || token >= pendingRecents.size()) {
                publish(RuntimeState.Phase.FAILED,
                        "That recent ROM selection is no longer available.",
                        List.of(), activeLayout != null, state.paused(), false);
                return;
            }
            Uri uri = pendingRecents.get((int) token);
            pendingRecents = List.of();
            openRom(uri);
        });
    }

    /** Releases an unselected archive snapshot or recent-choice list without changing a live game. */
    public void cancelPendingSelection() {
        submit(() -> {
            clearPendingSource();
            if (activeLayout == null) {
                publish(RuntimeState.Phase.STOPPED,
                        "Coffee GB Android is ready. Choose a ROM or ZIP document.",
                        List.of(), false, false, false);
            } else {
                publish(state.paused() ? RuntimeState.Phase.PAUSED : RuntimeState.Phase.RUNNING,
                        state.paused() ? "Game paused." : "Game running.",
                        List.of(), true, state.paused(), state.flushPending());
            }
        });
    }

    /** Pauses at the controller safe point and asks for a bounded asynchronous battery flush. */
    public void onHostNotVisible() {
        input.releaseAll();
        AndroidAudioSink activeAudio = audio;
        if (activeAudio != null) {
            activeAudio.pause();
        }
        AndroidRumbleSink activeRumble = rumble;
        if (activeRumble != null) {
            activeRumble.pause();
        }
        AndroidTiltSink activeTilt = tilt;
        if (activeTilt != null) {
            activeTilt.pause();
        }
        AndroidCameraSource activeCamera = camera;
        if (activeCamera != null) {
            activeCamera.pause();
        }
        submit(() -> {
            if (controller == null) {
                return;
            }
            lifecycle.background(lifecycleCommands());
        });
    }

    /** Records visibility for a future load without automatically resuming an already paused game. */
    public void onHostVisible() {
        submit(lifecycle::foregrounded);
    }

    /** Audio loss follows the same conservative policy: pause, flush, and require user resume. */
    public void onAudioFocusLost() {
        onHostNotVisible();
    }

    /** Audio focus gain intentionally does not resume emulation without an explicit user command. */
    public void resume() {
        submit(() -> {
            if (controller == null || activeLayout == null) {
                return;
            }
            AndroidAudioSink activeAudio = audio;
            if (activeAudio != null) {
                activeAudio.resume();
            }
            AndroidRumbleSink activeRumble = rumble;
            if (activeRumble != null) {
                activeRumble.resume();
            }
            AndroidTiltSink activeTilt = tilt;
            if (activeTilt != null) {
                activeTilt.resume();
            }
            AndroidCameraSource activeCamera = camera;
            if (activeCamera != null) {
                activeCamera.resume();
            }
            lifecycle.resumedByUser();
            eventBus.post(new Controller.ResumeEmulationEvent());
        });
    }

    /** Stops one session and recreates its controller shell for a later load without leaks. */
    public void stop() {
        input.releaseAll();
        submit(() -> {
            clearPendingSource();
            if (!closeController()) {
                publish(RuntimeState.Phase.FAILED,
                        "Coffee GB could not safely stop the current game. Retry stopping it.",
                        List.of(), activeLayout != null, true, false);
                return;
            }
            activeLayout = null;
            activeStates = null;
            currentSource = null;
            frames.clear();
            lifecycle.released();
            createController();
            publish(RuntimeState.Phase.STOPPED,
                    "Game stopped. Choose a ROM or ZIP document.",
                    List.of(), false, false, false);
        });
    }

    public void importBattery(Uri source) {
        transfer("Importing battery save…", "Battery save imported.", () ->
                SafPersistenceExchange.importBattery(
                        context.getContentResolver(), source, requireLayout(),
                        SafPersistenceExchange.CollisionDecision.REPLACE));
    }

    public void exportBattery(Uri destination) {
        transfer("Exporting battery save…", "Battery save exported.", () ->
                SafPersistenceExchange.exportBattery(
                        context.getContentResolver(), destination, requireLayout(), true));
    }

    public void importState(Uri source) {
        transfer("Importing state slot 0…", "State slot 0 imported.", () ->
                SafPersistenceExchange.importState(
                        context.getContentResolver(), source, requireStates(), new StateRef.Slot(0),
                        SafPersistenceExchange.CollisionDecision.REPLACE));
    }

    public void exportState(Uri destination) {
        transfer("Exporting state slot 0…", "State slot 0 exported.", () ->
                SafPersistenceExchange.exportState(
                        context.getContentResolver(), destination, requireStates(), new StateRef.Slot(0), true));
    }

    /** Exports the retained Game Boy Printer paper as PNG through a user-selected SAF document. */
    void exportPrinter(Uri destination, Runnable completed) {
        Uri checked = Objects.requireNonNull(destination, "destination");
        Runnable callback = Objects.requireNonNull(completed, "completed");
        submit(() -> {
            AndroidPrinterStore.Snapshot snapshot = printer == null ? null : printer.snapshot();
            if (snapshot == null) {
                publish(state.phase(), "Nothing has been printed yet.", List.of(),
                        state.transferReady(), state.paused(), state.flushPending());
                return;
            }
            RuntimeState before = state;
            publish(before.phase(), "Exporting printer paper…", List.of(), before.transferReady(),
                    before.paused(), before.flushPending());
            Bitmap bitmap = null;
            try {
                bitmap = snapshot.toBitmap();
                try (OutputStream output = context.getContentResolver().openOutputStream(checked, "wt")) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("Printer paper could not be encoded");
                    }
                    output.flush();
                }
                publish(before.phase(), "Printer paper exported.", List.of(), before.transferReady(),
                        before.paused(), before.flushPending());
                mainHandler.post(callback);
            } catch (IOException failure) {
                publish(before.phase(), "Coffee GB could not export printer paper.", List.of(),
                        before.transferReady(), before.paused(), before.flushPending());
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
        });
    }

    /** Exports full-resolution paper and reports success or failure on the main thread. */
    void exportPrinter(Uri destination, Consumer<Boolean> completed) {
        Uri checked = Objects.requireNonNull(destination, "destination");
        Consumer<Boolean> callback = Objects.requireNonNull(completed, "completed");
        submit(() -> {
            AndroidPrinterStore.Snapshot snapshot = printer == null ? null : printer.snapshot();
            if (snapshot == null) {
                publish(state.phase(), "Nothing has been printed yet.", List.of(),
                        state.transferReady(), state.paused(), state.flushPending());
                mainHandler.post(() -> callback.accept(false));
                return;
            }
            RuntimeState before = state;
            publish(before.phase(), "Exporting printer paper…", List.of(), before.transferReady(),
                    before.paused(), before.flushPending());
            Bitmap bitmap = null;
            boolean success = false;
            try {
                bitmap = snapshot.toBitmap();
                try (OutputStream output = context.getContentResolver()
                        .openOutputStream(checked, "wt")) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("Printer paper could not be encoded");
                    }
                    output.flush();
                }
                success = true;
                publish(before.phase(), "Printer paper exported.", List.of(),
                        before.transferReady(), before.paused(), before.flushPending());
            } catch (IOException failure) {
                publish(before.phase(), "Coffee GB could not export printer paper.", List.of(),
                        before.transferReady(), before.paused(), before.flushPending());
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
            boolean result = success;
            mainHandler.post(() -> callback.accept(result));
        });
    }

    /** Writes the latest native emulated frame as a PNG; it never captures Android UI overlays. */
    public void exportScreenshot(Uri destination) {
        Uri checked = Objects.requireNonNull(destination, "destination");
        submit(() -> {
            NativeFrameStore.Snapshot snapshot = frames.snapshot();
            if (snapshot == null) {
                publish(RuntimeState.Phase.FAILED,
                        "Render one game frame before exporting a screenshot.",
                        List.of(), activeLayout != null, state.paused(), state.flushPending());
                return;
            }
            RuntimeState before = state;
            publish(before.phase(), "Exporting native screenshot…", List.of(),
                    before.transferReady(), before.paused(), before.flushPending());
            try {
                writePng(checked, snapshot);
                publish(before.phase(), "Native screenshot exported.", List.of(),
                        before.transferReady(), before.paused(), before.flushPending());
            } catch (IOException failure) {
                publish(before.phase(), "Coffee GB could not export that screenshot.", List.of(),
                        before.transferReady(), before.paused(), before.flushPending());
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        deadlines.shutdownNow();
        try {
            owner.execute(() -> {
                clearPendingSource();
                closeController();
                activeLayout = null;
                activeStates = null;
                currentSource = null;
                input.close();
                frames.close();
            });
        } catch (RejectedExecutionException ignored) {
            // The owner is already tearing down; no new resource can have been admitted.
        } finally {
            owner.shutdown();
        }
    }

    private void initialize() {
        try {
            persistenceStore = new AndroidRomPersistenceStore(context);
            createController();
        } catch (Exception failure) {
            publish(RuntimeState.Phase.FAILED,
                    "Coffee GB could not initialize its private game storage.",
                    List.of(), false, false, false);
        }
    }

    private void createController() {
        eventBus = new EventBusImpl();
        eventBus.register(
                (StateUxSessionEvent event) -> {
                    activeStateSessionId = event.getAvailable() ? event.getSessionId() : 0L;
                    submit(this::drainPendingStateRequests);
                },
                StateUxSessionEvent.class);
        audio = new AndroidAudioSink(context, eventBus);
        audio.start();
        rumble = new AndroidRumbleSink(context, eventBus, false);
        tilt = new AndroidTiltSink(context, eventBus);
        camera = new AndroidCameraSource(context);
        camera.setEnabled(cameraEnabled);
        PocketCamera.setCameraSource(camera);
        printer = new AndroidPrinterStore();
        // Display events run synchronously on the controller thread. The bounded store must copy
        // their producer-owned arrays before this callback returns; it never touches Android UI.
        eventBus.register(frames::publish, Display.DmgFrameReadyEvent.class);
        eventBus.register(frames::publish, Display.GbcFrameReadyEvent.class);
        eventBus.register(frames::publish, SgbDisplay.SgbFrameReadyEvent.class);
        eventBus.register(printer::append, Controller.PrinterPrintEvent.class);
        eventBus.register(
                (Controller.RomLoadingEvent event) -> submit(() -> {
                    if (event.getOpenRequestId() != null
                            && event.getOpenRequestId() == activeOpenRequestId) {
                        publish(RuntimeState.Phase.LOADING, "Loading selected ROM…", List.of(),
                                false, true, state.flushPending());
                    }
                }),
                Controller.RomLoadingEvent.class);
        eventBus.register(
                (Controller.EmulationStartedEvent event) -> submit(() -> {
                    if (event.getOpenRequestId() != null
                            && event.getOpenRequestId() == activeOpenRequestId) {
                        AndroidTiltSink activeTilt = tilt;
                        if (activeTilt != null) {
                            activeTilt.setCartridgeActive(tiltOpenRequestId
                                    == activeOpenRequestId && tiltRequiredForOpenRequest);
                        }
                        AndroidCameraSource activeCamera = camera;
                        if (activeCamera != null) {
                            activeCamera.setCartridgeActive(cameraOpenRequestId
                                    == activeOpenRequestId && cameraRequiredForOpenRequest);
                        }
                        if (currentSource != null) {
                            new RecentSafDocuments(context).recordIfPersisted(currentSource);
                        }
                        lifecycle.activated(lifecycleCommands());
                        if (printerEnabled) {
                            eventBus.post(new Controller.SetPrinterEvent(true));
                        }
                        publish(RuntimeState.Phase.RUNNING,
                                "Loaded " + event.getRomName() + ". App-private saves are ready.",
                                List.of(), true, false, false);
                    }
                }),
                Controller.EmulationStartedEvent.class);
        eventBus.register(
                (Controller.EmulationStoppedEvent event) -> submit(() -> {
                    if (!closed.get()) {
                        publish(RuntimeState.Phase.STOPPED,
                                "Game stopped. Choose a ROM or ZIP document.",
                                List.of(), false, false, false);
                    }
                }),
                Controller.EmulationStoppedEvent.class);
        eventBus.register(
                (Controller.LoadRomFailedEvent event) -> submit(() -> {
                    if (event.getOpenRequestId() != null
                            && event.getOpenRequestId() == activeOpenRequestId) {
                        forgetRevokedPermission(currentSource);
                        publish(RuntimeState.Phase.FAILED,
                                "Coffee GB could not load the selected ROM.",
                                List.of(), false, true, false);
                    }
                }),
                Controller.LoadRomFailedEvent.class);
        eventBus.register(
                (Controller.SessionPlaybackStateEvent event) -> submit(() -> {
                    if (activeLayout == null || state.phase() == RuntimeState.Phase.LOADING) {
                        return;
                    }
                    if (event.getPaused()) {
                        publish(RuntimeState.Phase.PAUSED,
                                state.flushPending()
                                        ? "Game paused. Saving battery data…"
                                        : "Game paused.",
                                List.of(), true, true, state.flushPending());
                    } else {
                        publish(RuntimeState.Phase.RUNNING, "Game running.", List.of(),
                                true, false, state.flushPending());
                    }
                }),
                Controller.SessionPlaybackStateEvent.class);
        eventBus.register(
                (Controller.BatteryFlushCompletedEvent event) -> submit(() -> {
                    if (event.getRequestId() != pendingFlushRequestId) {
                        return;
                    }
                    cancelFlushDeadline();
                    pendingFlushRequestId = 0;
                    lifecycle.flushCompleted();
                    // A user may explicitly resume while the asynchronous battery write is still
                    // completing. Its completion is not another pause request, so preserve the
                    // controller's most recently published playback state instead of regressing
                    // a resumed session back to PAUSED.
                    boolean paused = state.paused();
                    if (event.getSucceeded()) {
                        publish(paused ? RuntimeState.Phase.PAUSED : RuntimeState.Phase.RUNNING,
                                paused ? "Game paused. Battery data saved."
                                        : "Game running. Battery data saved.",
                                List.of(), true, paused, false);
                    } else {
                        publish(paused ? RuntimeState.Phase.PAUSED : RuntimeState.Phase.RUNNING,
                                paused ? "Game paused. Battery save needs retrying."
                                        : "Game running. Battery save needs retrying.",
                                List.of(), true, paused, false);
                    }
                }),
                Controller.BatteryFlushCompletedEvent.class);
        controller = new BasicController(eventBus, properties, null);
        controller.startController();
    }

    private void openRom(Uri uri) {
        clearPendingSource();
        frames.clear();
        publish(RuntimeState.Phase.OPENING, "Opening selected ROM…", List.of(), false, true, false);
        try {
            AndroidRomInput input = new AndroidRomInput(context.getContentResolver(), uri);
            RomSourceSnapshot snapshot = RomSourceSnapshot.open(input);
            if (!snapshot.isArchive() || snapshot.candidates().size() == 1) {
                activateSnapshot(snapshot,
                        snapshot.isArchive()
                                ? snapshot.candidates().get(0).token()
                                : RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN,
                        uri);
                return;
            }
            pendingSnapshot = snapshot;
            pendingSource = uri;
            List<RuntimeState.Selection> choices = snapshot.candidates().stream()
                    .map(candidate -> new RuntimeState.Selection(candidate.token(), candidate.displayName()))
                    .collect(Collectors.toList());
            publish(RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION,
                    "Choose ROM from archive.", choices, false, true, false);
        } catch (Exception failure) {
            forgetRevokedPermission(uri);
            publish(RuntimeState.Phase.FAILED,
                    "Coffee GB could not open this document. Check its permission and format.",
                    List.of(), activeLayout != null, state.paused(), false);
        }
    }

    private void activateSnapshot(RomSourceSnapshot snapshot, long token, Uri source) {
        try {
            RomImage image = token == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN
                    ? snapshot.loadSingle()
                    : snapshot.load(token);
            Rom rom = new Rom(image);
            String hash = StateIdentity.INSTANCE.hash(rom).hex();
            StateStorageLayout layout = persistenceStore.layout(hash);
            activeLayout = layout;
            activeStates = new StateRepository(layout, AtomicFileWriter.system());
            currentSource = source;
            activeOpenRequestId = ++nextOpenRequestId;
            tiltOpenRequestId = activeOpenRequestId;
            tiltRequiredForOpenRequest = rom.getType().isMbc7();
            cameraOpenRequestId = activeOpenRequestId;
            cameraRequiredForOpenRequest = rom.getType().isPocketCamera()
                    || rom.getCartridgeProperties().getMapper() == CartridgeProperties.Mapper.POCKET_CAMERA;
            publish(RuntimeState.Phase.LOADING, "Loading selected ROM…", List.of(),
                    false, true, false);
            controllerEventBus().post(new Controller.LoadRomEvent(
                    image, null, persistenceStore, activeOpenRequestId, true));
        } catch (Exception failure) {
            forgetRevokedPermission(source);
            activeLayout = null;
            activeStates = null;
            currentSource = null;
            publish(RuntimeState.Phase.FAILED,
                    "Coffee GB could not load the selected ROM.", List.of(), false, true, false);
        } finally {
            closeQuietly(snapshot);
        }
    }

    private void requestBackgroundFlush() {
        if (pendingFlushRequestId != 0) {
            return;
        }
        long requestId = ++nextFlushRequestId;
        pendingFlushRequestId = requestId;
        publish(RuntimeState.Phase.PAUSED, "Game paused. Saving battery data…", List.of(),
                true, true, true);
        eventBus.post(new Controller.FlushBatteryEvent(requestId));
        flushDeadline = deadlines.schedule(
                () -> submit(() -> onFlushDeadline(requestId)),
                BACKGROUND_FLUSH_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    private void requestStateSave(int slot) {
        requestState(new PendingStateRequest(slot, true));
    }

    private void requestStateLoad(int slot) {
        requestState(new PendingStateRequest(slot, false));
    }

    private void requestState(PendingStateRequest request) {
        if (controller == null || activeLayout == null) {
            return;
        }
        if (activeStateSessionId == 0L) {
            // Emulation-start notification reaches the owner before the controller publishes the
            // authoritative State UX session identifier. Preserve an immediate button press.
            pendingStateRequests.add(request);
            return;
        }
        postStateRequest(request);
    }

    private void drainPendingStateRequests() {
        if (activeStateSessionId == 0L || controller == null || activeLayout == null) {
            pendingStateRequests.clear();
            return;
        }
        List<PendingStateRequest> pending = List.copyOf(pendingStateRequests);
        pendingStateRequests.clear();
        for (PendingStateRequest request : pending) {
            postStateRequest(request);
        }
    }

    private void postStateRequest(PendingStateRequest request) {
        long stateSessionId = activeStateSessionId;
        if (stateSessionId == 0L || controller == null || activeLayout == null) {
            pendingStateRequests.add(request);
            return;
        }
        long requestId = ++nextStateRequestId;
        StateRef.Slot ref = new StateRef.Slot(request.slot);
        if (request.save) {
            eventBus.post(new StateSaveRequestEvent(requestId, stateSessionId, ref, null, null));
        } else {
            eventBus.post(new StateLoadRefRequestEvent(requestId, stateSessionId, ref));
        }
    }

    private RuntimeLifecycleGate.SessionCommands lifecycleCommands() {
        return new RuntimeLifecycleGate.SessionCommands() {
            @Override
            public void pause() {
                eventBus.post(new Controller.PauseEmulationEvent());
            }

            @Override
            public void requestBatteryFlush() {
                requestBackgroundFlush();
            }
        };
    }

    private void onFlushDeadline(long requestId) {
        if (pendingFlushRequestId != requestId) {
            return;
        }
        // The controller keeps the single writer alive; this only bounds the lifecycle caller's
        // wait. A late completion still clears this truthful retryable warning.
        publish(RuntimeState.Phase.PAUSED,
                "Game paused. Battery save is still completing; retry if it fails.",
                List.of(), true, true, true);
    }

    private void transfer(String working, String success, CheckedIoAction action) {
        submit(() -> {
            if (activeLayout == null || activeStates == null) {
                publish(RuntimeState.Phase.FAILED,
                        "Open a ROM before importing or exporting its save data.",
                        List.of(), false, false, false);
                return;
            }
            RuntimeState before = state;
            publish(before.phase(), working, List.of(), true, before.paused(), before.flushPending());
            try {
                action.run();
                publish(before.phase(), success, List.of(), true, before.paused(), before.flushPending());
            } catch (Exception failure) {
                publish(before.phase(), "Coffee GB could not complete that save-data transfer.",
                        List.of(), true, before.paused(), before.flushPending());
            }
        });
    }

    private StateStorageLayout requireLayout() {
        if (activeLayout == null) {
            throw new IllegalStateException("No active ROM storage");
        }
        return activeLayout;
    }

    private StateRepository requireStates() {
        if (activeStates == null) {
            throw new IllegalStateException("No active ROM state storage");
        }
        return activeStates;
    }

    private static void checkStateSlot(int slot) {
        if (slot < StateRef.MIN_SLOT || slot > StateRef.MAX_SLOT) {
            throw new IllegalArgumentException("State slot must be between " + StateRef.MIN_SLOT
                    + " and " + StateRef.MAX_SLOT);
        }
    }

    private EventBus controllerEventBus() {
        if (eventBus == null || controller == null) {
            throw new IllegalStateException("Android emulator runtime is not available");
        }
        return eventBus;
    }

    private boolean closeController() {
        cancelFlushDeadline();
        pendingFlushRequestId = 0;
        BasicController active = controller;
        EventBus activeBus = eventBus;
        AndroidAudioSink activeAudio = audio;
        AndroidRumbleSink activeRumble = rumble;
        AndroidTiltSink activeTilt = tilt;
        AndroidCameraSource activeCamera = camera;
        AndroidPrinterStore activePrinter = printer;
        controller = null;
        eventBus = null;
        audio = null;
        rumble = null;
        tilt = null;
        camera = null;
        printer = null;
        if (active == null) {
            if (activeAudio != null) {
                activeAudio.close();
            }
            if (activeRumble != null) {
                activeRumble.close();
            }
            if (activeTilt != null) {
                activeTilt.close();
            }
            if (activeCamera != null) {
                activeCamera.close();
            }
            PocketCamera.setCameraSource(null);
            activeStateSessionId = 0L;
            pendingStateRequests.clear();
            return true;
        }
        try {
            active.close();
            if (activeAudio != null) {
                activeAudio.close();
            }
            if (activeRumble != null) {
                activeRumble.close();
            }
            if (activeTilt != null) {
                activeTilt.close();
            }
            if (activeCamera != null) {
                activeCamera.close();
            }
            PocketCamera.setCameraSource(null);
            lifecycle.released();
            activeStateSessionId = 0L;
            pendingStateRequests.clear();
            return true;
        } catch (RuntimeException failure) {
            // Retain the controller for an explicit retry; it may still own an unflushed battery.
            controller = active;
            eventBus = activeBus;
            audio = activeAudio;
            rumble = activeRumble;
            tilt = activeTilt;
            camera = activeCamera;
            printer = activePrinter;
            return false;
        }
    }

    private void retainReadPermission(Uri uri, int resultFlags) {
        int read = resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int persistable = resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (read == 0 || persistable == 0) {
            return;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri, read);
        } catch (SecurityException ignored) {
            // A one-shot provider grant remains usable for this open but is not retained in Recents.
        }
    }

    private void forgetRevokedPermission(Uri uri) {
        if (uri == null) {
            return;
        }
        new RecentSafDocuments(context).remove(uri);
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission()) {
                try {
                    context.getContentResolver().releasePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // The provider already revoked it. The recent-list cleanup above is enough.
                }
            }
        }
    }

    private void clearPendingSource() {
        RomSourceSnapshot snapshot = pendingSnapshot;
        pendingSnapshot = null;
        pendingSource = null;
        pendingRecents = List.of();
        closeQuietly(snapshot);
    }

    private void cancelFlushDeadline() {
        if (flushDeadline != null) {
            flushDeadline.cancel(false);
            flushDeadline = null;
        }
    }

    private void writePng(Uri destination, NativeFrameStore.Snapshot snapshot) throws IOException {
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) {
                throw new IOException("The selected screenshot document is unavailable");
            }
            Bitmap bitmap = Bitmap.createBitmap(
                    snapshot.pixels(), snapshot.width(), snapshot.height(), Bitmap.Config.ARGB_8888);
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Android could not encode the native screenshot");
                }
            } finally {
                bitmap.recycle();
            }
        }
    }

    private void publish(
            RuntimeState.Phase phase,
            String message,
            List<RuntimeState.Selection> selections,
            boolean transferReady,
            boolean paused,
            boolean flushPending) {
        RuntimeState next = new RuntimeState(
                phase, message, selections, transferReady, paused, flushPending,
                Math.addExact(state.generation(), 1));
        state = next;
        mainHandler.post(() -> {
            for (RuntimeObserver observer : observers) {
                observer.onStateChanged(next);
            }
        });
    }

    private void submit(Runnable action) {
        if (closed.get()) {
            return;
        }
        try {
            owner.execute(action);
        } catch (RejectedExecutionException ignored) {
            // Runtime teardown already owns the resource boundary.
        }
    }

    private static void closeQuietly(RomSourceSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            snapshot.close();
        } catch (IOException ignored) {
            // Stream snapshots own no visible path. The next process startup cannot reopen one.
        }
    }

    private static final class PendingStateRequest {
        private final int slot;
        private final boolean save;

        private PendingStateRequest(int slot, boolean save) {
            this.slot = slot;
            this.save = save;
        }
    }

    @FunctionalInterface
    private interface CheckedIoAction {
        void run() throws IOException;
    }
}
