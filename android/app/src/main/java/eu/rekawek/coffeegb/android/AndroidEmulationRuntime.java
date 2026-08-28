package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.PauseMenuSnapshot;
import eu.rekawek.coffeegb.ui.menu.PlayTimeTracker;
import eu.rekawek.coffeegb.controller.BasicController;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides;
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.controller.properties.RuntimeWarmupFlavor;
import eu.rekawek.coffeegb.controller.state.StateIdentity;
import eu.rekawek.coffeegb.controller.state.StateLoadRefRequestEvent;
import eu.rekawek.coffeegb.controller.state.StateRef;
import eu.rekawek.coffeegb.controller.state.StateRepository;
import eu.rekawek.coffeegb.controller.state.StateResumeAvailableEvent;
import eu.rekawek.coffeegb.controller.state.StateResumeDecisionEvent;
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent;
import eu.rekawek.coffeegb.controller.state.StateOperation;
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent;
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent;
import eu.rekawek.coffeegb.controller.state.StateImage;
import eu.rekawek.coffeegb.controller.state.StatePngCodec;
import eu.rekawek.coffeegb.controller.state.StateStorageLayout;
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int BENCHMARK_AUDIO_DRAIN_MAX_POLLS = 50;
    private static final long BENCHMARK_AUDIO_DRAIN_POLL_MILLIS = 20L;

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
    /** Registration epochs prevent a queued message from a prior Activity attachment resurfacing. */
    private final ConcurrentHashMap<RuntimeObserver, Long> observerRegistrations =
            new ConcurrentHashMap<>();
    private final AtomicLong nextObserverRegistration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Serializes a UI-thread pause capture with owner-thread presentation publication. */
    private final Object presentationLock = new Object();
    private final RuntimeLifecycleGate lifecycle = new RuntimeLifecycleGate();
    private final DiagnosticsOptions diagnosticsOptions;
    private final AndroidBenchmarkDiagnostics diagnostics;
    private final BenchmarkGameplayScenario benchmarkScenario;
    /** Adaptive hint session; live mutations stay on synchronous controller callbacks. */
    private final AndroidPerformanceBoost performanceBoost;
    private final NativeFrameStore frames;
    /** Session timing belongs to the service, not to a short-lived Activity attachment. */
    private final PlayTimeTracker playTime = new PlayTimeTracker();
    private final EmulatorProperties properties;
    private final AndroidInputRouter input;

    private volatile RuntimeState state = RuntimeState.stopped();
    // Updated directly from controller event dispatch; the owner queues requests until it exists.
    private volatile long activeStateSessionId;

    // Everything below is accessed only on the owner executor.
    private EventBus eventBus;
    private BasicController controller;
    private volatile AndroidAudioSink audio;
    /** Published before the controller-thread arm acknowledgement; never retained after ACK. */
    private volatile PendingBenchmarkArm pendingBenchmarkArm;
    /** Serializes hidden-session pause against scenario-drain inspection and audio resume. */
    private final BenchmarkAudioLifecycleGate benchmarkAudioLifecycle =
            new BenchmarkAudioLifecycleGate();
    /** Owner-thread fence invalidating every scheduled scenario/audio poll on replacement. */
    private long benchmarkScenarioCompletionEpoch;
    private volatile AndroidRumbleSink rumble;
    private volatile AndroidTiltSink tilt;
    private volatile AndroidCameraSource camera;
    private AndroidPrinterStore printer;
    private boolean printerEnabled;
    private boolean cameraEnabled;
    private volatile String cameraLens = "rear";
    private volatile boolean gpsEnabled;
    private AndroidRomPersistenceStore persistenceStore;
    private RomSourceSnapshot pendingSnapshot;
    private Uri pendingSource;
    /** Carries pause ownership through a multi-entry archive chooser. */
    private boolean pendingSnapshotReleasesMenuPause;
    private List<Uri> pendingRecents = List.of();
    private List<RecentSafDocuments.Entry> pendingRecentGames = List.of();
    private Uri currentSource;
    private long activeCandidateToken = RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN;
    private String activeArchiveEntryName = "";
    private int activeArchiveEntryOccurrence = -1;
    /** SHA-256 of the normalized cartridge bytes selected for the active session. */
    private String activeRomHash = "";
    private StateStorageLayout activeLayout;
    private StateRepository activeStates;
    private long nextOpenRequestId;
    private long nextStateRequestId;
    private final List<PendingStateRequest> pendingStateRequests = new ArrayList<>();
    private final AndroidStateSaveCompletionTracker stateSaveCompletions =
            new AndroidStateSaveCompletionTracker();
    private long activeOpenRequestId;
    private long tiltOpenRequestId;
    private boolean tiltRequiredForOpenRequest;
    private long cameraOpenRequestId;
    private boolean cameraRequiredForOpenRequest;
    private long nextFlushRequestId;
    private long pendingFlushRequestId;
    private ScheduledFuture<?> flushDeadline;
    /** Mapper-derived metadata for the active controller session; never inferred from files. */
    private String activeRomTitle = "";
    private boolean activeBatterySave;
    private volatile long activeSessionGeneration;
    private boolean playbackTimerSessionActive;
    private long playbackTimerSessionGeneration;
    private boolean restartPlaybackTimerOnNextPublish;
    /** Frozen root-pause presentation retained across Activity and picker recreation. */
    private volatile PauseMenuSnapshot pauseMenuSnapshot;
    private volatile long pauseSnapshotSessionGeneration;
    private volatile String pauseSnapshotTitle = "";

    public AndroidEmulationRuntime(Context context) {
        this(context, DiagnosticsOptions.disabled());
    }

    AndroidEmulationRuntime(Context context, DiagnosticsOptions options) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        performanceBoost = new AndroidPerformanceBoost(this.context);
        diagnosticsOptions = options == null ? DiagnosticsOptions.disabled() : options;
        benchmarkScenario = new BenchmarkGameplayScenario(
                diagnosticsOptions.benchmarkScenario,
                diagnosticsOptions.benchmarkNativeFrameKind());
        diagnostics = new AndroidBenchmarkDiagnostics(this.context, diagnosticsOptions,
                this::onSystemAudioViolation);
        // Release/non-diagnostic sessions use a null sink so the native frame hot path does not
        // enter synchronized benchmark methods on every frame.
        frames = new NativeFrameStore(diagnostics.enabled() ? diagnostics : null,
                this::postBenchmarkPhysicalBoundary);
        properties = new EmulatorProperties(androidSettingsOverrides(diagnosticsOptions));
        diagnostics.sessionLaunch();
        input = new AndroidInputRouter(properties.getPlayerInputSource(),
                new AndroidControllerMappings(this.context), diagnostics::inputMutation);
        submit(this::initialize);
    }

    /** Android has no rewind control yet; disable its snapshot work without changing saved settings. */
    static ApplicationSettingsOverrides androidSettingsOverrides() {
        return androidSettingsOverrides(DiagnosticsOptions.disabled());
    }

    static ApplicationSettingsOverrides androidSettingsOverrides(DiagnosticsOptions options) {
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        HardwareProfile profile = checked.enabled ? checked.hardware.profileOverride() : null;
        // Benchmark sessions use their transiently requested bootstrap mode: the profile event
        // is emitted only after the actual post-boot KEY0/GPU mode has been reached.
        // Release/non-diagnostic callers retain the ordinary settings path unchanged.
        BootstrapMode bootstrapMode = checked.enabled ? checked.bootstrapMode : null;
        return new ApplicationSettingsOverrides(profile, bootstrapMode,
                checked.enabled ? false : null, false, false, false,
                checked.enabled && checked.runtimeWarmup, runtimeWarmupFlavor(checked), checked.enabled,
                checked.enabled ? checked.executionMode : null);
    }

    private static RuntimeWarmupFlavor runtimeWarmupFlavor(DiagnosticsOptions options) {
        if (options.enabled
                && options.runtimeWarmup
                && options.hardware == DiagnosticsOptions.Hardware.CGB
                && options.executionMode == ExecutionMode.PERFORMANCE
                && options.benchmarkScenario == DiagnosticsOptions.BenchmarkScenario.CGB_ACTION_V1
                && options.audioPolicy == DiagnosticsOptions.AudioPolicy.SILENT_PCM_V1) {
            return RuntimeWarmupFlavor.SHADOW_MEASURED_EXACT_V1;
        }
        if (options.enabled
                && options.runtimeWarmup
                && options.executionMode == ExecutionMode.PERFORMANCE) {
            return RuntimeWarmupFlavor.PERFORMANCE;
        }
        return RuntimeWarmupFlavor.SCALAR;
    }

    public RuntimeState state() {
        return state;
    }

    /** Called synchronously by NativeFrameStore on the controller thread at physical ready #600. */
    private void postBenchmarkPhysicalBoundary(long generation) {
        if (!diagnostics.enabled() || eventBus == null || generation <= 0L) {
            return;
        }
        eventBus.post(new Controller.BenchmarkPhysicalFrameBoundaryEvent(600L, generation));
    }

    /** Revokes one silent-PCM generation on the first bad read-only system-audio sample. */
    private void onSystemAudioViolation(long generation, long sessionGeneration) {
        if (!diagnostics.enabled() || eventBus == null || generation <= 0L
                || sessionGeneration <= 0L || generation != diagnostics.benchmarkGeneration()
                || sessionGeneration != activeSessionGeneration
                || !diagnostics.systemAudioBadLatched()) {
            return;
        }
        // This callback can originate while diagnostics holds its monitor. Do not acquire the
        // lifecycle gate here: visibility/focus paths take that gate before sampling diagnostics,
        // so taking it in the reverse order would deadlock. BasicController's owner-thread
        // listener freezes synchronously before app PCM is paused. Keep all three safety actions
        // independent so a sink or event subscriber failure cannot skip the owner freeze or the
        // generation-bound terminal policy record.
        RuntimeException failure = null;
        try {
            eventBus.post(new Controller.BenchmarkSystemAudioViolationEvent(
                    generation, sessionGeneration, diagnosticsOptions.audioPolicy.externalValue()));
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            AndroidAudioSink activeAudio = audio;
            if (activeAudio != null) {
                // Stop and clear app-owned PCM immediately; this does not touch system volume/mute.
                activeAudio.pause();
            }
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            }
        }
        try {
            eventBus.post(new Controller.BenchmarkSilentPcmPolicyEvent(
                    false, generation, sessionGeneration, false,
                    diagnosticsOptions.audioPolicy.externalValue()));
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            }
        }
        try {
            eventBus.post(new Controller.PauseEmulationEvent());
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Advances the configured timeline on the controller's physical native-frame callback. */
    private void benchmarkScenarioFrameReady(BenchmarkGameplayScenario.NativeFrameKind frameKind) {
        if (!diagnostics.enabled() || !benchmarkScenario.enabled()) {
            return;
        }
        long sessionGeneration = benchmarkScenario.sessionGeneration();
        if (!benchmarkScenario.acceptsNativeFrame(frameKind, sessionGeneration)) {
            return;
        }
        int nextMask = benchmarkScenario.onFrameReady(frameKind, sessionGeneration);
        if (nextMask != BenchmarkGameplayScenario.UNCHANGED) {
            input.setBenchmarkScenarioMask(nextMask);
        }
        if (!benchmarkScenario.consumePauseRequest()) {
            return;
        }
        // The endpoint is a released frame. Explicitly clear the private source before asking the
        // controller for its safe pause; no scenario input survives into the measured epoch.
        input.clearBenchmarkScenario();
        eventBus.post(new Controller.BenchmarkGameplayScenarioEndpointEvent(
                sessionGeneration, benchmarkScenario.frameForTesting()));
    }

    private ExecutionMode executionModeForSession() {
        if (diagnostics.enabled()) {
            return diagnosticsOptions.executionMode;
        }
        try {
            return properties.getSystem().getExecutionMode();
        } catch (RuntimeException unavailable) {
            return ExecutionMode.ACCURACY;
        }
    }

    /** Service-owned native frames for a short-lived {@link CoffeeGbSurfaceView} attachment. */
    NativeFrameStore frames() {
        return frames;
    }

    /** Returns a detached, opaque copy of the latest native game frame for a frozen pause menu. */
    MenuPreview capturePauseMenuPreview() {
        NativeFrameStore.Snapshot snapshot = frames.snapshot();
        return snapshot == null ? MenuPreview.empty()
                : MenuPreview.ready(snapshot.width(), snapshot.height(), snapshot.pixels());
    }

    /**
     * Freezes the currently active game for the root pause menu before a caller asks the
     * controller to pause. The immutable capture is service-owned so an Activity recreation or
     * a native picker round trip restores the exact same frame and elapsed time.
     */
    PauseMenuSnapshot capturePauseMenuSnapshot() {
        synchronized (presentationLock) {
            RuntimeState current = state;
            if (!hasActiveSession(current)) {
                return null;
            }
            PauseMenuSnapshot existing = pauseMenuSnapshotFor(current);
            if (existing != null) {
                return existing;
            }
            String title = current.romTitle().isBlank() ? "NO GAME" : current.romTitle();
            PauseMenuSnapshot captured = new PauseMenuSnapshot(title, playTime.elapsedNanos(),
                    current.batterySaveActive(), capturePauseMenuPreview());
            pauseSnapshotSessionGeneration = current.sessionGeneration();
            pauseSnapshotTitle = current.romTitle();
            pauseMenuSnapshot = captured;
            return captured;
        }
    }

    /** Returns the retained root-pause capture only when it still belongs to the active session. */
    PauseMenuSnapshot pauseMenuSnapshot() {
        synchronized (presentationLock) {
            return pauseMenuSnapshotFor(state);
        }
    }

    /** Drops a root-pause capture after the user has actually left the menu or resumed play. */
    void clearPauseMenuSnapshot() {
        clearPauseMenuSnapshotInternal();
    }

    /** Service-owned source merger for transient Android touch and controller devices. */
    AndroidInputRouter input() {
        return input;
    }

    /** Applies host-only audio controls without changing emulation timing or save state. */
    void setAudioMuted(boolean muted) {
        if (diagnostics.enabled()) {
            if (rejectBenchmarkMutation() || muted) {
                return;
            }
        }
        AndroidAudioSink activeAudio = audio;
        if (activeAudio != null) {
            activeAudio.setMuted(muted);
        }
    }

    void setAudioVolume(int volume) {
        if (diagnostics.enabled()) {
            if (rejectBenchmarkMutation() || volume != 100) {
                return;
            }
        }
        AndroidAudioSink activeAudio = audio;
        if (activeAudio != null) {
            activeAudio.setVolume(volume);
        }
    }

    private boolean rejectBenchmarkMutation() {
        if (!diagnostics.enabled() || !diagnostics.benchmarkWindowLocked()) {
            return false;
        }
        diagnostics.inputMutation();
        return true;
    }

    /** Applies the user’s host-only rumble preference without changing the portable cartridge. */
    void setRumbleEnabled(boolean enabled) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        AndroidRumbleSink activeRumble = rumble;
        if (activeRumble != null) {
            activeRumble.setEnabled(enabled);
        }
    }

    /** Calibrates an active MBC7 cartridge to the device's current resting position. */
    void calibrateTilt() {
        if (rejectBenchmarkMutation()) {
            return;
        }
        AndroidTiltSink activeTilt = tilt;
        if (activeTilt != null) {
            activeTilt.calibrate();
        }
    }

    /** Enables live Pocket Camera capture after the user grants the optional camera permission. */
    void setCameraEnabled(boolean enabled) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        submit(() -> {
            cameraEnabled = enabled;
            AndroidCameraSource activeCamera = camera;
            if (activeCamera != null) {
                activeCamera.setEnabled(enabled);
            }
        });
    }

    /** Selects the physical CameraX lens used by a Pocket Camera cartridge. */
    void setCameraLens(String lens) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        String selected = "front".equalsIgnoreCase(lens) ? "front" : "rear";
        submit(() -> {
            cameraLens = selected;
            AndroidCameraSource activeCamera = camera;
            if (activeCamera != null) {
                activeCamera.setLens(selected);
            }
        });
    }

    /** Applies the persisted Android gamepad selector to both live input and future ROM opens. */
    void setGamepadSelection(String selection) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        String normalized = "none".equalsIgnoreCase(selection)
                || "off".equalsIgnoreCase(selection) ? "none"
                : (selection == null || selection.isBlank() || "auto".equalsIgnoreCase(selection)
                        ? "auto" : selection);
        input.setGamepadSelection(normalized);
    }

    /** Persists and applies the Android-only emulated GPS accessory switch. */
    void setGpsEnabled(boolean enabled) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        gpsEnabled = enabled;
        submit(() -> {
            if (eventBus != null && controller != null && activeLayout != null) {
                eventBus.post(new Controller.SetGpsReceiverEvent(enabled));
            }
        });
    }

    /** Persists the selected DMG palette in the controller settings and frame renderer. */
    void setDisplayGrayscale(boolean grayscale) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        frames.setGrayscale(grayscale);
        submit(() -> {
            String value = Boolean.toString(grayscale);
            if (!Objects.equals(properties.getProperty(EmulatorProperties.Key.DisplayGrayscale,
                    null), value)) {
                properties.setProperty(EmulatorProperties.Key.DisplayGrayscale, value);
            }
        });
    }

    /** Applies the SGB border choice immediately and carries it into future ROM profiles. */
    void setSgbBorder(boolean enabled) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        submit(() -> {
            String value = Boolean.toString(enabled);
            if (!Objects.equals(
                    properties.getProperty(EmulatorProperties.Key.ShowSgbBorder, null), value)) {
                properties.setProperty(EmulatorProperties.Key.ShowSgbBorder, value);
            }
            // The stored preference and the live machine can legitimately differ after a
            // profile-constrained session or host reattachment. Reassert the requested value
            // even when persistence already agrees, otherwise an ON checkbox can leave the
            // active SGB display producing borderless 160x144 frames indefinitely.
            if (eventBus != null) {
                eventBus.post(new SgbDisplay.SetSgbBorder(enabled));
            }
        });
    }

    /** Applies a system selector with the controller's documented reload semantics. */
    void setSystemSelection(String id, String value) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        submit(() -> {
            boolean changed;
            switch (id) {
                case "dmg-games" -> changed = setProfileProperty(
                        EmulatorProperties.Key.DmgGamesType, value);
                case "cgb-games" -> changed = setProfileProperty(
                        EmulatorProperties.Key.CgbGamesType, value);
                case "execution-mode" -> changed = setExecutionModeProperty(value);
                case "bootstrap" -> {
                    String mode = "full".equalsIgnoreCase(value) ? "NORMAL"
                            : ("fast-forward".equalsIgnoreCase(value)
                                    || "fast_forward".equalsIgnoreCase(value)
                                    ? "FAST_FORWARD" : value.toUpperCase(java.util.Locale.ROOT));
                    String before = properties.getProperty(
                            EmulatorProperties.Key.BootstrapMode, null);
                    if (Objects.equals(before, mode)) {
                        changed = false;
                    } else {
                        properties.setProperty(EmulatorProperties.Key.BootstrapMode, mode);
                        changed = !Objects.equals(before, properties.getProperty(
                                EmulatorProperties.Key.BootstrapMode, null));
                    }
                }
                default -> { return; }
            }
            if (changed && eventBus != null && controller != null && activeLayout != null) {
                eventBus.post(new Controller.UpdatedSystemMappingEvent());
            }
        });
    }

    private boolean setProfileProperty(EmulatorProperties.Key key, String value) {
        String before = properties.getProperty(key, null);
        String normalized = value == null || value.isBlank() || "auto".equalsIgnoreCase(value)
                ? null : value.toLowerCase(java.util.Locale.ROOT);
        if (Objects.equals(before, normalized)) {
            return false;
        }
        if (normalized == null) {
            properties.clearProperty(key);
        } else {
            properties.setProperty(key, normalized);
        }
        return !Objects.equals(before, properties.getProperty(key, null));
    }

    /** Applies the core strategy through the controller settings model before a safe reload. */
    private boolean setExecutionModeProperty(String value) {
        String normalized = DiagnosticsOptions.executionModeValue(
                DiagnosticsOptions.parseExecutionMode(value)).toUpperCase(java.util.Locale.ROOT);
        String before = properties.getProperty(EmulatorProperties.Key.ExecutionMode, null);
        if (Objects.equals(before, normalized)) {
            return false;
        }
        properties.setProperty(EmulatorProperties.Key.ExecutionMode, normalized);
        return !Objects.equals(before,
                properties.getProperty(EmulatorProperties.Key.ExecutionMode, null));
    }

    /** Connects or disconnects the portable Game Boy Printer at the controller-safe boundary. */
    void setPrinterEnabled(boolean enabled) {
        if (rejectBenchmarkMutation()) {
            return;
        }
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
        if (rejectBenchmarkMutation()) {
            return;
        }
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
        reset(false);
    }

    /** Resets after an in-screen menu action, releasing only the pause owned by that menu. */
    void reset(boolean releaseMenuPause) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        submit(() -> {
            if (controller != null && activeLayout != null) {
                if (releaseMenuPause) {
                    preparePlayingReplacement();
                }
                eventBus.post(new Controller.ResetEmulationEvent());
            }
        });
    }

    /** Requests a portable quick-state save at the controller's safe point. */
    public void saveSnapshot(int slot) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        saveSnapshot(slot, null);
    }

    /** Requests a quick-state save and invokes the detached completion callback on the main thread. */
    void saveSnapshot(int slot, Runnable onCompleted) {
        if (rejectBenchmarkMutation()) {
            return;
        }
        checkStateSlot(slot);
        submit(() -> requestStateSave(slot, onCompleted));
    }

    /** Requests a portable quick-state restore; controller errors remain typed/redacted. */
    public void restoreSnapshot(int slot) {
        if (rejectBenchmarkMutation()) {
            return;
        }
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
                // The on-screen save/load page intentionally exposes four stable slots.  Keep the
                // catalog read bounded to those slots so a refresh cannot decode unused entries.
                for (int slot = 0; slot <= 3; slot++) {
                    int index = slot;
                    var entry = entries.stream()
                            .filter(candidate -> candidate.getRef() instanceof StateRef.Slot
                                    && ((StateRef.Slot) candidate.getRef()).getIndex() == index)
                            .findFirst().orElse(null);
                    slots.add(AndroidStateSlot.from(index, entry, readStatePreview(entry,
                            new StateRef.Slot(index))));
                }
            }
            List<AndroidStateSlot> snapshot = List.copyOf(slots);
            mainHandler.post(() -> checked.accept(snapshot));
        });
    }

    /** Decodes the hash-bound persisted thumbnail on the runtime owner thread. */
    private MenuPreview readStatePreview(eu.rekawek.coffeegb.controller.state.StateCatalogEntry entry,
            StateRef.Slot ref) {
        if (entry == null || entry.getMetadata() == null || entry.getStateSha256() == null
                || entry.getMetadata().getThumbnailSha256() == null || activeStates == null) {
            return MenuPreview.empty();
        }
        try {
            var read = activeStates.readThumbnail(ref, entry.getStateSha256(),
                    entry.getMetadata().getThumbnailSha256());
            byte[] bytes = read.copyBytes();
            if (bytes == null) {
                return MenuPreview.empty();
            }
            StateImage image = StatePngCodec.INSTANCE.decode(bytes);
            int[] rgb = image.copyRgb();
            int[] argb = new int[rgb.length];
            for (int index = 0; index < rgb.length; index++) {
                argb[index] = 0xff000000 | rgb[index];
            }
            return MenuPreview.ready(image.getWidth(), image.getHeight(), argb);
        } catch (Exception ignored) {
            return MenuPreview.empty();
        }
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
        observerRegistrations.put(checked, nextObserverRegistration.incrementAndGet());
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
        observerRegistrations.remove(observer);
    }

    /** Opens the user-selected SAF document; all metadata, archive and ROM work stays off-main. */
    public void openRom(Uri uri, int resultFlags) {
        openRom(uri, resultFlags, false);
    }

    /** Opens a document selected from a menu that may own the current session's pause. */
    void openRom(Uri uri, int resultFlags, boolean releaseMenuPause) {
        Uri checked = Objects.requireNonNull(uri, "uri");
        submit(() -> openSelectedRom(checked, resultFlags, releaseMenuPause));
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
            boolean releaseMenuPause = pendingSnapshotReleasesMenuPause;
            pendingSnapshot = null;
            pendingSource = null;
            pendingSnapshotReleasesMenuPause = false;
            activateSnapshot(snapshot, token, source, releaseMenuPause);
        });
    }

    /** Reads persisted recent grants on the runtime owner and publishes redacted choices. */
    public void requestRecentDocuments() {
        submit(() -> {
            // The QA launcher has one deliberately read-only identity path below. Do not expose
            // the mutable Library catalog to it: readable() may migrate legacy metadata, prune
            // revoked grants, and clean preview files. A benchmark does not offer that UI list.
            if (diagnostics.enabled()) {
                pendingRecents = List.of();
                publish(RuntimeState.Phase.FAILED,
                        "Recent ROM catalog is unavailable in benchmark mode.",
                        List.of(), activeLayout != null, state.paused(), false);
                return;
            }
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

    /** Reads recent games for the in-screen page without changing the runtime phase. */
    void requestRecentGames(Consumer<List<RecentGame>> callback) {
        Consumer<List<RecentGame>> checked = Objects.requireNonNull(callback, "callback");
        submit(() -> {
            NativeFrameStore.Snapshot currentFrame = diagnostics.enabled()
                    ? null : frames.snapshot();
            if (!diagnostics.enabled()) {
                persistActiveRecentGame(currentFrame);
            }
            deliverRecentGames(checked);
            if (!diagnostics.enabled() && currentSource != null && currentFrame == null) {
                scheduleRecentGamesRefresh(checked, currentSource, activeOpenRequestId, 0);
            }
        });
    }

    /** Opens one opaque Recent Games token published by {@link #requestRecentGames(Consumer)}. */
    void selectRecentGame(long token, boolean releaseMenuPause) {
        submit(() -> {
            if (token < 0 || token >= pendingRecentGames.size()) {
                publish(RuntimeState.Phase.FAILED,
                        "That recent game is no longer available.", List.of(),
                        activeLayout != null, state.paused(), false);
                return;
            }
            RecentSafDocuments.Entry entry = pendingRecentGames.get((int) token);
            pendingRecentGames = List.of();
            openRecentGame(entry, releaseMenuPause);
        });
    }

    /** Starts the newest readable Recent Games entry without presenting its metadata to the UI. */
    void launchMostRecentBenchmarkGame() {
        if (!diagnostics.enabled() || !diagnosticsOptions.launchRecent) {
            return;
        }
        submit(() -> {
            RecentSafDocuments recentStore = new RecentSafDocuments(context);
            RecentSafDocuments.Entry recent = diagnosticsOptions.recentSlot >= 0
                    ? recentStore.benchmarkEntryAtSlot(diagnosticsOptions.recentSlot)
                    : recentStore.mostRecentReadableEntry();
            if (recent == null) {
                diagnostics.noRecentEntry();
                publish(RuntimeState.Phase.FAILED,
                        "No readable recent game is available for benchmark.", List.of(),
                        false, false, false);
                return;
            }
            diagnostics.openStart();
            diagnostics.setWorkloadNonce(new RecentSafDocuments(context)
                    .ensureBenchmarkNonce(recent));
            // Freeze gameplay input before materialization. A configured scenario keeps only its
            // private frame-driven source admitted until the preconditioning pause; the ordinary
            // benchmark path locks all sources immediately. The ACK handler repeats this
            // idempotently as the measured-epoch guard.
            if (!invalidateBenchmarkSession(true)) {
                publishBenchmarkScenarioInputFailure();
                return;
            }
            openRecentGame(recent, false);
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
            openSelectedRom(uri, 0, false);
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
        if (diagnostics.enabled()) {
            // Benchmark visibility is a scheduler precondition.  Do not flush/reopen audio or
            // enter the ordinary battery/autosave lifecycle barrier; a hidden run is discarded.
            benchmarkAudioLifecycle.run(() -> {
                // Diagnostics owns the atomic current-or-next generation decision. This closes
                // both the pre-OPENING queue race and the LOADING-to-STARTED handoff.
                diagnostics.benchmarkVisibilityLost();
                postBenchmarkAudioDisableAndPauseLocked();
                AndroidAudioSink activeAudio = audio;
                if (activeAudio != null) {
                    // The same gate surrounds the only pre-ARM resume path. If a poll wins first,
                    // this pause wins last; if visibility loss wins first, the poll fails closed.
                    activeAudio.pause();
                }
            });
            return;
        }
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
            persistCurrentRecentGame();
            if (controller == null) {
                return;
            }
            lifecycle.background(lifecycleCommands());
        });
    }

    /** Records visibility for a future load without automatically resuming an already paused game. */
    public void onHostVisible() {
        if (diagnostics.enabled()) {
            diagnostics.inputMutation();
            return;
        }
        submit(lifecycle::foregrounded);
    }

    /** Audio loss follows the same conservative policy: pause, flush, and require user resume. */
    public void onAudioFocusLost() {
        if (diagnostics.enabled()) {
            benchmarkAudioLifecycle.run(() -> {
                diagnostics.audioFocusLost();
                postBenchmarkAudioDisableAndPauseLocked();
                AndroidAudioSink activeAudio = audio;
                if (activeAudio != null) {
                    activeAudio.pause();
                }
            });
            return;
        }
        onHostNotVisible();
    }

    /** Records the service's intrinsic focus request result before a benchmark ARM. */
    public void onAudioFocusResult(boolean granted) {
        if (diagnostics.enabled()) {
            benchmarkAudioLifecycle.run(() -> diagnostics.audioFocusResult(granted));
        } else {
            diagnostics.audioFocusResult(granted);
        }
    }

    /** Runs under benchmarkAudioLifecycle; policy disable is FIFO before the pause command. */
    private void postBenchmarkAudioDisableAndPauseLocked() {
        if (!diagnostics.enabled() || eventBus == null) {
            return;
        }
        long generation = diagnostics.benchmarkGeneration();
        long sessionGeneration = activeSessionGeneration;
        if (generation > 0L && sessionGeneration > 0L) {
            eventBus.post(new Controller.BenchmarkSilentPcmPolicyEvent(
                    false, generation, sessionGeneration, false,
                    diagnosticsOptions.audioPolicy.externalValue()));
        }
        eventBus.post(new Controller.PauseEmulationEvent());
    }

    /** Audio focus gain intentionally does not resume emulation without an explicit user command. */
    public void resume() {
        if (rejectBenchmarkMutation()) {
            return;
        }
        clearPauseMenuSnapshot();
        submit(() -> {
            if (controller == null || activeLayout == null) {
                return;
            }
            resumeSession();
        });
    }

    /**
     * Releases the controller and Android-output pause immediately before a replacement request.
     * The controller queue orders this event before the following load/reset event, so the
     * replacement inherits the user's intent to play.
     */
    private void preparePlayingReplacement() {
        clearPauseMenuSnapshotInternal();
        resumeSession();
    }

    private void resumeSession() {
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
    }

    /**
     * Arms one benchmark generation after the host has captured the warm-up compositor baseline.
     * This path is benchmark-only; ordinary resume remains a separate user operation.
     */
    void armBenchmark(String token, long requestedSessionGeneration) {
        if (!diagnostics.enabled()) {
            return;
        }
        submit(() -> {
            long generation = System.nanoTime();
            long sessionGeneration = activeSessionGeneration;
            if (generation <= 0L || token == null
                    || !token.matches("[a-z0-9][a-z0-9._-]{15,63}")
                    || requestedSessionGeneration != sessionGeneration
                    || sessionGeneration <= 0L || state.phase() != RuntimeState.Phase.PAUSED
                    || state.sessionGeneration() != sessionGeneration
                    || !diagnostics.benchmarkAnchorReady(sessionGeneration)) {
                return;
            }
            AndroidAudioSink activeAudio = audio;
            AndroidAudioSink.AudioBaseline audioBaseline = activeAudio == null
                    ? AndroidAudioSink.AudioBaseline.unavailable() : activeAudio.benchmarkBaseline();
            pendingBenchmarkArm = new PendingBenchmarkArm(
                    sessionGeneration, generation, token, audioBaseline);
            // BasicController performs the ticker/deadline reset and synchronously emits the
            // acknowledgement on its owner thread. CPU/priority/environment baselines and the
            // matrix_run record are taken only from that acknowledgement callback.
            eventBus.post(new Controller.BenchmarkArmEvent(
                    generation, token, sessionGeneration,
                    diagnosticsOptions.audioPolicy.externalValue()));
        });
    }

    boolean benchmarkAnchorReady(long sessionGeneration) {
        return diagnostics.enabled() && sessionGeneration > 0L
                && sessionGeneration == activeSessionGeneration
                && state.phase() == RuntimeState.Phase.PAUSED
                && state.sessionGeneration() == sessionGeneration
                && diagnostics.benchmarkAnchorReady(sessionGeneration);
    }

    /** The host may request its compositor anchor only after optional input preconditioning. */
    boolean benchmarkPreconditionReady() {
        return !diagnostics.enabled()
                || diagnostics.benchmarkPreArmValid(activeSessionGeneration)
                && (!benchmarkScenario.enabled() || benchmarkScenario.preconditionReady());
    }

    /** Receives the renderer-thread result of the one-time pre-measurement Surface anchor post. */
    void benchmarkAnchorPosted(long sessionGeneration, boolean success, Runnable completed) {
        if (!diagnostics.enabled()) {
            return;
        }
        submit(() -> {
            diagnostics.benchmarkAnchorPosted(sessionGeneration, success);
            if (completed != null) {
                completed.run();
            }
        });
    }

    /**
     * Waits outside the controller thread for a two-phase audio barrier. First the paused sink
     * must report an empty queue and a stopped output, proving the scenario PCM was flushed. Then
     * the output is resumed while the guest remains paused, and readiness requires the clean,
     * playing baseline that the benchmark matrix records at ARM.
     */
    private void finishBenchmarkScenarioCompletion(
            Controller.BenchmarkGameplayScenarioCompletedEvent event, long completionEpoch,
            int attempt,
            boolean audioResumeRequested) {
        benchmarkAudioLifecycle.run(() -> finishBenchmarkScenarioCompletionLocked(
                event, completionEpoch, attempt, audioResumeRequested));
    }

    /** Runs under {@link #benchmarkAudioLifecycle}; never call this method directly. */
    private void finishBenchmarkScenarioCompletionLocked(
            Controller.BenchmarkGameplayScenarioCompletedEvent event, long completionEpoch,
            int attempt,
            boolean audioResumeRequested) {
        if (closed.get() || !benchmarkCompletionPollMayTouchAudio(
                diagnostics.benchmarkPreArmValid(event.getSessionGeneration()),
                completionEpoch, benchmarkScenarioCompletionEpoch,
                event.getSessionGeneration(), activeSessionGeneration,
                benchmarkScenario.sessionGeneration(), state.sessionGeneration())) {
            return;
        }
        AndroidAudioSink activeAudio = audio;
        AndroidAudioSink.AudioBaseline baseline = activeAudio == null
                ? AndroidAudioSink.AudioBaseline.unavailable() : activeAudio.benchmarkBaseline();
        boolean sourceClosed = input.benchmarkScenarioSourceClosed();
        boolean emptyAudio = !diagnosticsOptions.audioOutput
                || baseline.pendingBytes() == 0L && baseline.queuedBytes() == 0L;
        boolean audioReady;
        if (!diagnosticsOptions.audioOutput) {
            audioReady = true;
        } else if (!audioResumeRequested) {
            audioReady = false;
            boolean flushObserved = activeAudio != null && emptyAudio && !baseline.outputPlaying();
            if (flushObserved) {
                activeAudio.resume();
                deadlines.schedule(
                        () -> submit(() -> finishBenchmarkScenarioCompletion(
                                event, completionEpoch, 0, true)),
                        BENCHMARK_AUDIO_DRAIN_POLL_MILLIS,
                        TimeUnit.MILLISECONDS);
                return;
            }
        } else {
            audioReady = activeAudio != null && emptyAudio && baseline.outputPlaying();
        }
        if ((!audioReady || !sourceClosed) && attempt < BENCHMARK_AUDIO_DRAIN_MAX_POLLS) {
            deadlines.schedule(
                    () -> submit(() -> finishBenchmarkScenarioCompletion(
                            event, completionEpoch, attempt + 1, audioResumeRequested)),
                    BENCHMARK_AUDIO_DRAIN_POLL_MILLIS,
                    TimeUnit.MILLISECONDS);
            return;
        }
        boolean completed = event.getCompleted() && audioReady && sourceClosed
                && event.getCompletedFrames() == event.getExpectedFrames();
        diagnostics.benchmarkScenarioCompleted(
                event.getSessionGeneration(), event.getCompletedFrames(), event.getExpectedFrames(),
                completed, sourceClosed, audioReady);
        if (!completed) {
            return;
        }
        benchmarkScenario.markPreconditionReady();
        diagnostics.emulationStarted(event.getSessionGeneration());
        RuntimeState current = state;
        if (current.sessionGeneration() == event.getSessionGeneration()) {
            publish(RuntimeState.Phase.PAUSED, "Benchmark preconditioning complete.", List.of(),
                    true, true, current.flushPending());
        }
    }

    /** Stops one session and recreates its controller shell for a later load without leaks. */
    public void stop() {
        input.releaseAll();
        submit(() -> {
            invalidateBenchmarkSession(false);
            persistCurrentRecentGame();
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
            clearActiveCandidate();
            activeRomTitle = "";
            activeBatterySave = false;
            activeSessionGeneration = 0L;
            clearPauseMenuSnapshotInternal();
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
        observers.clear();
        observerRegistrations.clear();
        deadlines.shutdownNow();
        try {
            owner.execute(() -> {
                persistCurrentRecentGame();
                clearPendingSource();
                closeController();
                activeLayout = null;
                activeStates = null;
                currentSource = null;
                clearActiveCandidate();
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
                (StateOperationCompletedEvent event) -> {
                    if (event.getOperation() == StateOperation.SAVE
                            || event.getOperation() == StateOperation.LOAD) {
                        // Controller callbacks run on the emulation thread. Keep runtime-owned
                        // layout/session checks and save callback bookkeeping on the owner.
                        submit(() -> {
                            if (event.getOperation() == StateOperation.SAVE) {
                                completeStateSave(event.getRequestId());
                            }
                            publishStateCompletionMessage(event);
                        });
                    }
                },
                StateOperationCompletedEvent.class);
        eventBus.register(
                (StateOperationFailedEvent event) -> {
                    if (event.getOperation() == StateOperation.SAVE) {
                        // Terminal failure also releases the menu's loading state so it can show
                        // the last persisted catalog again; the callback itself is generation
                        // guarded by MainActivity.
                        submit(() -> completeStateSave(event.getRequestId()));
                    }
                },
                StateOperationFailedEvent.class);
        eventBus.register(
                (StateUxSessionEvent event) -> {
                    activeStateSessionId = event.getAvailable() ? event.getSessionId() : 0L;
                    submit(this::drainPendingStateRequests);
                },
                StateUxSessionEvent.class);
        AndroidBenchmarkDiagnostics activeDiagnostics = diagnostics.enabled() ? diagnostics : null;
        audio = diagnostics.enabled() && !diagnosticsOptions.audioOutput
                ? AndroidAudioSink.disabled(eventBus)
                : new AndroidAudioSink(context, eventBus, activeDiagnostics);
        diagnostics.setAudioSink(audio);
        if (diagnostics.enabled() && !diagnosticsOptions.audioOutput) {
            diagnostics.audioStats(new AndroidAudioSink.AudioStats(0, 0, 0, 0));
        }
        audio.start();
        rumble = new AndroidRumbleSink(context, eventBus, false);
        tilt = new AndroidTiltSink(context, eventBus);
        camera = new AndroidCameraSource(context);
        camera.setLens(cameraLens);
        camera.setEnabled(cameraEnabled);
        PocketCamera.setCameraSource(camera);
        printer = new AndroidPrinterStore();
        // The profile event is dispatched synchronously before the controller starts ticking.
        // NativeFrameStore must know whether a DMG event is transfer input for SGB before it
        // receives the first physical frame.
        eventBus.register(
                event -> {
                    frames.setHardwareProfile(event.getProfile());
                    performanceBoost.onHardwareProfile(event.getProfile().clockSpec());
                    AndroidAudioSink activeAudio = audio;
                    if (activeAudio != null) {
                        activeAudio.setClockSpec(event.getProfile().clockSpec());
                    }
                    diagnostics.hardwareProfile(event);
                },
                Controller.HardwareProfileEvent.class);
        eventBus.register(
                event -> diagnostics.benchmarkFrameBoundary(event),
                Controller.BenchmarkFrameBoundaryEvent.class);
        // A rejected ARM is reported by BasicController as a generation-bound false policy. Clear
        // only the matching pending host request; a stale false event must never cancel a newer
        // generation or touch the controller's already-authoritative policy state.
        eventBus.register(
                (Controller.BenchmarkSilentPcmPolicyEvent event) -> {
                    if (!diagnostics.enabled() || event.getRequested()) {
                        return;
                    }
                    PendingBenchmarkArm pending = pendingBenchmarkArm;
                    if (pending != null
                            && pending.sessionGeneration() == event.getSessionGeneration()
                            && pending.generation() == event.getGeneration()) {
                        // This listener may run while diagnostics owns its sample monitor.
                        // The field is volatile and clearing it needs no lifecycle lock.
                        pendingBenchmarkArm = null;
                    }
                },
                Controller.BenchmarkSilentPcmPolicyEvent.class);
        eventBus.register(
                event -> {
                    if (!diagnostics.enabled()) {
                        return;
                    }
                    benchmarkAudioLifecycle.run(() -> {
                        PendingBenchmarkArm pending = pendingBenchmarkArm;
                        if (pending == null || !pending.matches(event)
                                || !benchmarkSessionMatches(
                                        event.getSessionGeneration(), activeSessionGeneration,
                                        benchmarkScenario.enabled()
                                                ? benchmarkScenario.sessionGeneration()
                                                : event.getSessionGeneration(),
                                        state.sessionGeneration())
                                || state.phase() != RuntimeState.Phase.PAUSED) {
                            return;
                        }
                        pendingBenchmarkArm = null;
                        // The pre-ACK sample only gates the host's arm request. The controller
                        // acknowledgement is the authority boundary, so capture a fresh sink
                        // baseline here while the lifecycle gate excludes drain/focus races.
                        AndroidAudioSink activeAudio = audio;
                        AndroidAudioSink.AudioBaseline freshBaseline = activeAudio == null
                                ? AndroidAudioSink.AudioBaseline.unavailable()
                                : activeAudio.benchmarkBaseline();
                        if (!diagnostics.armBenchmark(
                                event.getSessionGeneration(), event.getGeneration(), event.getToken(),
                                freshBaseline)) {
                            // Never resume an arm whose fresh host proof failed. A matching false
                            // policy event also clears any controller-side stale calendar. A
                            // system-audio violation already emitted its one revocation callback
                            // synchronously while armBenchmark sampled the baseline.
                            if (!diagnostics.systemAudioBadLatched()) {
                                eventBus.post(new Controller.BenchmarkSilentPcmPolicyEvent(
                                        false, event.getGeneration(),
                                        event.getSessionGeneration(), false,
                                        diagnosticsOptions.audioPolicy.externalValue()));
                            }
                            return;
                        }
                        frames.beginBenchmarkEpoch(event.getGeneration());
                        input.lockBenchmarkWindow();
                        eventBus.post(new Controller.BenchmarkSilentPcmPolicyEvent(
                                diagnosticsOptions.audioPolicy.isSilent(),
                                event.getGeneration(), event.getSessionGeneration(), true,
                                diagnosticsOptions.audioPolicy.externalValue()));
                        eventBus.post(new Controller.ResumeEmulationEvent());
                    });
                },
                Controller.BenchmarkArmAcknowledgedEvent.class);
        // BasicController brackets each live PERFORMANCE controller batch. These synchronous
        // events report its work while excluding TimingTicker pacing.
        eventBus.register(
                event -> performanceBoost.onWorkStarted(),
                Controller.PerformanceWorkStartedEvent.class);
        eventBus.register(
                event -> performanceBoost.onWorkCompleted(),
                Controller.PerformanceWorkCompletedEvent.class);
        eventBus.register(
                event -> performanceBoost.onWorkAborted(),
                Controller.PerformanceWorkAbortedEvent.class);
        // Display events run synchronously on the controller thread. The bounded store must copy
        // their producer-owned arrays before this callback returns; it never touches Android UI.
        // A configured scenario observes only its matching native event, so an SGB transfer DMG
        // event cannot advance the timeline twice for one displayed frame.
        eventBus.register(
                (Display.DmgFrameReadyEvent event) -> {
                    frames.publish(event);
                    benchmarkScenarioFrameReady(BenchmarkGameplayScenario.NativeFrameKind.DMG);
                },
                Display.DmgFrameReadyEvent.class);
        eventBus.register(
                (Display.GbcFrameReadyEvent event) -> {
                    frames.publish(event);
                    benchmarkScenarioFrameReady(BenchmarkGameplayScenario.NativeFrameKind.GBC);
                },
                Display.GbcFrameReadyEvent.class);
        eventBus.register(frames::publish, SgbDisplay.SgbFrameReadyEvent.class);
        eventBus.register(printer::append, Controller.PrinterPrintEvent.class);
        eventBus.register(
                (Controller.BenchmarkGameplayScenarioCompletedEvent event) -> {
                    if (!diagnostics.enabled() || !benchmarkScenario.enabled()
                            || !benchmarkSessionMatches(
                                    event.getSessionGeneration(), activeSessionGeneration,
                                    benchmarkScenario.sessionGeneration(), state.sessionGeneration())) {
                        return;
                    }
                    // Stop accepting PCM and clear the bounded queue before any host anchor can
                    // be requested. The owner waits for an in-flight write to leave accounting.
                    AndroidAudioSink activeAudio = audio;
                    if (activeAudio != null) {
                        activeAudio.pause();
                    }
                    input.endBenchmarkScenario();
                    submit(() -> {
                        long completionEpoch = ++benchmarkScenarioCompletionEpoch;
                        finishBenchmarkScenarioCompletion(event, completionEpoch, 0, false);
                    });
                },
                Controller.BenchmarkGameplayScenarioCompletedEvent.class);
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
                (Controller.EmulationStartedEvent event) -> {
                    // This callback runs synchronously on the controller thread. Keep the CPU
                    // sample and adaptive session ownership on that thread instead of the runtime
                    // owner executor.
                    ExecutionMode executionMode = executionModeForSession();
                    performanceBoost.onSessionStarted(executionMode);
                    AndroidPerformanceBoost.apply(executionMode);
                    submit(() -> {
                    boolean requestedOpen = event.getOpenRequestId() != null
                            && event.getOpenRequestId() == activeOpenRequestId;
                    // Reset reloads the current cartridge without a host open-request id. Its
                    // session generation is authoritative and must replace the just-ended one;
                    // otherwise its following metadata event would be rejected as stale.
                    boolean resetReload = isResetReload(event.getOpenRequestId(), activeLayout != null,
                            event.getSessionGeneration(), activeSessionGeneration);
                    if (requestedOpen || resetReload) {
                        activeRomTitle = event.getRomName();
                        activeBatterySave = false;
                        activeSessionGeneration = event.getSessionGeneration() == null ? 0L
                                : event.getSessionGeneration();
                        diagnostics.beginSession(activeSessionGeneration);
                        restartPlaybackTimerOnNextPublish = true;
                        clearPauseMenuSnapshotInternal();
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
                        if (currentSource != null && !diagnostics.enabled()) {
                            NativeFrameStore.Snapshot recentFrame = frames.snapshot();
                            persistActiveRecentGame(recentFrame);
                            if (recentFrame == null) {
                                scheduleRecentPreview(currentSource, activeOpenRequestId, 0);
                            }
                        }
                        lifecycle.activated(lifecycleCommands());
                        if (printerEnabled) {
                            eventBus.post(new Controller.SetPrinterEvent(true));
                        }
                        if (gpsEnabled) {
                            eventBus.post(new Controller.SetGpsReceiverEvent(true));
                        }
                        boolean configuredScenario = diagnostics.enabled()
                                && benchmarkScenario.enabled();
                        if (configuredScenario) {
                            benchmarkScenario.beginSession(activeSessionGeneration);
                            if (!input.beginBenchmarkScenario()) {
                                benchmarkScenario.resetSession();
                                diagnostics.invalidateSession();
                                publishBenchmarkScenarioInputFailure();
                                return;
                            }
                            eventBus.post(new Controller.BenchmarkGameplayScenarioStartEvent(
                                    activeSessionGeneration,
                                    benchmarkScenario.endpointFrameForTesting()));
                            eventBus.post(new Controller.ResumeEmulationEvent());
                        } else {
                            diagnostics.emulationStarted(activeSessionGeneration);
                        }
                        boolean benchmarkPaused = diagnostics.enabled() && !configuredScenario;
                        publish(benchmarkPaused ? RuntimeState.Phase.PAUSED
                                        : RuntimeState.Phase.RUNNING,
                                "Loaded " + event.getRomName() + ". App-private saves are ready.",
                                List.of(), true, benchmarkPaused, false);
                    }
                    });
                },
                Controller.EmulationStartedEvent.class);
        eventBus.register(
                (Controller.SessionPresentationEvent event) -> submit(() -> {
                    if (activeLayout == null) {
                        return;
                    }
                    Long eventGeneration = event.getSessionGeneration();
                    if (eventGeneration != null && activeSessionGeneration != 0L
                            && eventGeneration.longValue() != activeSessionGeneration) {
                        return;
                    }
                    activeRomTitle = event.getRomTitle();
                    activeBatterySave = event.getBatterySaveActive();
                    if (eventGeneration != null) {
                        activeSessionGeneration = eventGeneration;
                    }
                    publish(state.phase(), state.message(), state.selections(),
                            state.transferReady(), state.paused(), state.flushPending());
                }),
                Controller.SessionPresentationEvent.class);
        eventBus.register(
                (Controller.EmulationStoppedEvent event) -> {
                    // The BasicController thread survives between sessions; do not let a prior
                    // PERFORMANCE session leave its scheduler hint on an idle/Accuracy session.
                    performanceBoost.onSessionStopped();
                    AndroidPerformanceBoost.apply(ExecutionMode.ACCURACY);
                    submit(() -> {
                        if (!closed.get()) {
                            invalidateBenchmarkSession(false);
                            if (!diagnostics.enabled()) {
                                persistActiveRecentGame(frames.snapshot());
                            }
                            publish(RuntimeState.Phase.STOPPED,
                                    "Game stopped. Choose a ROM or ZIP document.",
                                    List.of(), false, false, false);
                        }
                    });
                },
                Controller.EmulationStoppedEvent.class);
        eventBus.register(
                (Controller.LoadRomFailedEvent event) -> submit(() -> {
                    if (event.getOpenRequestId() != null
                            && event.getOpenRequestId() == activeOpenRequestId) {
                        invalidateBenchmarkSession(false);
                        if (!diagnostics.enabled()) {
                            new RecentSafDocuments(context).removeGame(currentSource,
                                    activeCandidateToken, activeArchiveEntryName,
                                    activeArchiveEntryOccurrence);
                        }
                        publish(RuntimeState.Phase.FAILED,
                                "Coffee GB could not load the selected ROM.",
                                List.of(), false, true, false);
                    }
                }),
                Controller.LoadRomFailedEvent.class);
        // The desktop ASK policy emits a prompt before restoring a managed close autosave.
        // Android does not expose that desktop prompt, so leaving the offer unanswered freezes
        // the newly loaded game on its first (usually black) frame. Mobile sessions continue
        // from their managed autosave automatically; Reset still opts out of resume scanning.
        eventBus.register(
                (StateResumeAvailableEvent event) -> {
                    eventBus.post(new StateResumeDecisionEvent(
                            event.getRequestId(), event.getSessionId(), true));
                },
                StateResumeAvailableEvent.class);
        eventBus.register(
                (Controller.SessionPlaybackStateEvent event) -> {
                    // This authoritative edge is synchronous on the controller thread. Resetting
                    // here prevents a paused interval from becoming the next reported duration.
                    performanceBoost.onPlaybackStateChanged(event.getPaused());
                    submit(() -> {
                    if (activeLayout == null || state.phase() == RuntimeState.Phase.LOADING) {
                        return;
                    }
                    if (event.getSessionGeneration() != activeSessionGeneration) {
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
                    });
                },
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

    private void openSelectedRom(Uri uri, int resultFlags, boolean releaseMenuPause) {
        persistCurrentRecentGame();
        clearPendingSource();
        frames.clear();
        publish(RuntimeState.Phase.OPENING, "Opening selected ROM…", List.of(), false, true, false);
        Uri durableSource = uri;
        boolean imported = false;
        try {
            if (!retainReadPermission(uri, resultFlags)) {
                durableSource = new AndroidRomImportStore(context).importDocument(uri);
                imported = true;
            }
            AndroidRomInput input = new AndroidRomInput(context.getContentResolver(), durableSource);
            RomSourceSnapshot snapshot = RomSourceSnapshot.open(input);
            if (!snapshot.isArchive() || snapshot.candidates().size() == 1) {
                activateSnapshot(snapshot,
                        snapshot.isArchive()
                                ? snapshot.candidates().get(0).token()
                                : RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN,
                        durableSource, releaseMenuPause);
                return;
            }
            pendingSnapshot = snapshot;
            pendingSource = durableSource;
            pendingSnapshotReleasesMenuPause = releaseMenuPause;
            List<RuntimeState.Selection> choices = snapshot.candidates().stream()
                    .map(candidate -> new RuntimeState.Selection(candidate.token(), candidate.displayName()))
                    .collect(Collectors.toList());
            publish(RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION,
                    "Choose ROM from archive.", choices, false, true, false);
        } catch (Exception failure) {
            if (!diagnostics.enabled()) {
                if (imported) {
                    try {
                        new AndroidRomImportStore(context).deleteIfOwned(durableSource);
                    } catch (IOException ignored) {
                        // The import directory being unavailable already explains this failure.
                    }
                } else {
                    forgetRevokedPermission(uri);
                }
            }
            publish(RuntimeState.Phase.FAILED,
                    "Coffee GB could not open this document. Check its permission and format.",
                    List.of(), activeLayout != null, state.paused(), false);
        }
    }

    /** Reopens the exact game-level archive entry retained by the recent catalog. */
    private void openRecentGame(RecentSafDocuments.Entry recent, boolean releaseMenuPause) {
        persistCurrentRecentGame();
        clearPendingSource();
        publish(RuntimeState.Phase.OPENING, "Opening recent game…", List.of(),
                false, true, false);
        RomSourceSnapshot snapshot = null;
        try {
            snapshot = RomSourceSnapshot.open(
                    new AndroidRomInput(context.getContentResolver(), recent.uri()));
            long token;
            if (recent.candidateToken() == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN) {
                if (snapshot.isArchive()) {
                    // The former document-level catalog could not retain which member of a ZIP
                    // had run. Keep that hashless migration usable without guessing: an
                    // unambiguous archive can open directly, while a multi-game archive asks the
                    // player to choose and will be upgraded to a game-level row on start.
                    if (!recent.romHash().isBlank()) {
                        recentGameUnavailable(recent,
                                "That recent game is no longer a direct ROM.");
                        closeQuietly(snapshot);
                        return;
                    }
                    if (snapshot.candidates().isEmpty()) {
                        recentGameUnavailable(recent,
                                "That archive no longer contains a readable game.");
                        closeQuietly(snapshot);
                        return;
                    } else if (snapshot.candidates().size() == 1) {
                        token = snapshot.candidates().get(0).token();
                    } else {
                        pendingSnapshot = snapshot;
                        pendingSource = recent.uri();
                        pendingSnapshotReleasesMenuPause = releaseMenuPause;
                        List<RuntimeState.Selection> choices = snapshot.candidates().stream()
                                .map(candidate -> new RuntimeState.Selection(candidate.token(),
                                        candidate.displayName()))
                                .collect(Collectors.toList());
                        publish(RuntimeState.Phase.AWAITING_ARCHIVE_SELECTION,
                                "Choose the game previously opened from this archive.", choices,
                                false, true, false);
                        return;
                    }
                } else {
                    token = RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN;
                }
            } else {
                Long resolved = resolveRecentCandidateToken(snapshot.candidates(),
                        recent.candidateToken(), recent.archiveEntryName(),
                        recent.archiveEntryOccurrence());
                if (!snapshot.isArchive() || resolved == null) {
                    recentGameUnavailable(recent,
                            "That game is no longer available in the archive.");
                    closeQuietly(snapshot);
                    return;
                }
                token = resolved;
            }
            activateSnapshot(snapshot, token, recent.uri(), recent, releaseMenuPause);
        } catch (Exception failure) {
            closeQuietly(snapshot);
            recentGameUnavailable(recent,
                    "That recent game could not be reopened.");
        }
    }

    private void activateSnapshot(RomSourceSnapshot snapshot, long token, Uri source,
            boolean releaseMenuPause) {
        activateSnapshot(snapshot, token, source, null, releaseMenuPause);
    }

    private void activateSnapshot(RomSourceSnapshot snapshot, long token, Uri source,
            RecentSafDocuments.Entry recent, boolean releaseMenuPause) {
        try {
            RomSourceSnapshot.ArchiveCandidate candidate = token
                    == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN ? null
                    : snapshot.candidates().stream()
                            .filter(item -> item.token() == token)
                            .findFirst().orElseThrow();
            RomImage image = token == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN
                    ? snapshot.loadSingle()
                    : snapshot.load(token);
            Rom rom = new Rom(image);
            String hash;
            if (diagnostics.enabled() && recent != null
                    && RecentSafDocuments.hasValidRomHash(recent.romHash())) {
                // The benchmark reuses the recent catalog identity and intentionally does not
                // hash cartridge bytes or write a new recent preview. Normal opens retain the
                // existing change-detection path below.
                hash = recent.romHash().toLowerCase(java.util.Locale.ROOT);
            } else if (diagnostics.enabled()) {
                diagnostics.noRecentEntry();
                recentGameUnavailable(recent, "Recent game identity is unavailable.");
                return;
            } else {
                hash = StateIdentity.INSTANCE.hash(rom).hex();
                if (recent != null && !recentHashMatches(recent.romHash(), hash)) {
                    recentGameUnavailable(recent,
                            "That recent game has changed since it was last played.");
                    return;
                }
            }
            StateStorageLayout layout = persistenceStore.layout(hash);
            // Preserve the currently paused session until the candidate has been positively
            // identified. This keeps a stale recent row from blanking the menu preview or
            // replacing any runtime identity before the controller accepts a load request.
            frames.clear();
            activeRomTitle = "";
            activeBatterySave = false;
            if (!invalidateBenchmarkSession(true)) {
                publishBenchmarkScenarioInputFailure();
                return;
            }
            activeSessionGeneration = 0L;
            clearPauseMenuSnapshotInternal();
            activeLayout = layout;
            activeStates = new StateRepository(layout, AtomicFileWriter.system());
            currentSource = source;
            activeCandidateToken = token;
            activeArchiveEntryName = candidate == null ? "" : candidate.entryName();
            activeArchiveEntryOccurrence = candidate == null ? -1 : candidate.entryOccurrence();
            activeRomHash = hash;
            activeOpenRequestId = ++nextOpenRequestId;
            tiltOpenRequestId = activeOpenRequestId;
            tiltRequiredForOpenRequest = !diagnostics.enabled() && rom.getType().isMbc7();
            cameraOpenRequestId = activeOpenRequestId;
            cameraRequiredForOpenRequest = !diagnostics.enabled() && (rom.getType().isPocketCamera()
                    || rom.getCartridgeProperties().getMapper() == CartridgeProperties.Mapper.POCKET_CAMERA);
            publish(RuntimeState.Phase.LOADING, "Loading selected ROM…", List.of(),
                    false, true, false);
            if (releaseMenuPause) {
                preparePlayingReplacement();
            }
            controllerEventBus().post(new Controller.LoadRomEvent(
                    image, null, persistenceStore, activeOpenRequestId, !diagnostics.enabled()));
        } catch (Exception failure) {
            if (recent == null) {
                if (!diagnostics.enabled()) {
                    forgetRevokedPermission(source);
                }
            } else if (!diagnostics.enabled()) {
                new RecentSafDocuments(context).removeGame(recent);
            }
            activeLayout = null;
            activeStates = null;
            currentSource = null;
            clearActiveCandidate();
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
        requestStateSave(slot, null);
    }

    private void requestStateSave(int slot, Runnable onCompleted) {
        requestState(new PendingStateRequest(slot, true, captureStateImage(), onCompleted));
    }

    private void requestStateLoad(int slot) {
        requestState(new PendingStateRequest(slot, false, null, null));
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
            if (request.onCompleted != null) {
                stateSaveCompletions.register(request.slot, requestId, request.onCompleted);
            } else {
                stateSaveCompletions.register(request.slot, requestId, null);
            }
            eventBus.post(new StateSaveRequestEvent(requestId, stateSessionId, ref, null,
                    request.thumbnail));
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

    private StateImage captureStateImage() {
        NativeFrameStore.Snapshot snapshot = frames.snapshot();
        return snapshot == null ? null
                : new StateImage(snapshot.width(), snapshot.height(), snapshot.pixels());
    }

    private void completeStateSave(long requestId) {
        Runnable callback = stateSaveCompletions.complete(requestId);
        if (callback != null) {
            mainHandler.post(callback);
        }
    }

    /** Forwards only successful SAVE/LOAD completions belonging to the active managed session. */
    private void publishStateCompletionMessage(StateOperationCompletedEvent event) {
        long sessionId = event.getSessionId();
        if (closed.get() || activeLayout == null || sessionId <= 0L
                || sessionId != activeStateSessionId) {
            return;
        }
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        List<ObserverRegistration> recipients = observers.stream()
                .map(observer -> new ObserverRegistration(observer,
                        observerRegistrations.get(observer)))
                .filter(recipient -> recipient.registrationId() != null)
                .collect(Collectors.toList());
        mainHandler.post(() -> {
            // Activity/service lifecycle changes can remove and later re-add the same observer;
            // re-check both ownership and the state-session edge before delivering this queued
            // callback so an old completion cannot flash in a replacement session.
            if (closed.get() || activeStateSessionId != sessionId) {
                return;
            }
            for (ObserverRegistration recipient : recipients) {
                Long currentRegistration = observerRegistrations.get(recipient.observer());
                if (currentRegistration != null
                        && currentRegistration.equals(recipient.registrationId())
                        && observers.contains(recipient.observer())) {
                    recipient.observer().onTransientMessage(message);
                }
            }
        });
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

    /** Drops every generation-bound benchmark latch before a session is replaced or removed. */
    private boolean invalidateBenchmarkSession(boolean prepareScenarioSource) {
        if (!diagnostics.enabled()) {
            return true;
        }
        pendingBenchmarkArm = null;
        benchmarkScenarioCompletionEpoch++;
        benchmarkScenario.resetSession();
        diagnostics.invalidateSession();
        input.resetBenchmarkSession();
        if (!prepareScenarioSource) {
            return true;
        }
        if (benchmarkScenario.enabled()) {
            return input.beginBenchmarkScenario();
        }
        input.lockBenchmarkWindow();
        return true;
    }

    /** Package-visible replacement seam used by the deterministic router/runtime unit test. */
    static boolean resetBenchmarkScenarioInput(
            AndroidInputRouter input, boolean prepareScenarioSource) {
        input.resetBenchmarkSession();
        return !prepareScenarioSource || input.beginBenchmarkScenario();
    }

    static boolean benchmarkCompletionPollMatches(
            long completionEpoch, long activeCompletionEpoch,
            long eventSessionGeneration, long activeSessionGeneration,
            long scenarioSessionGeneration, long presentedSessionGeneration) {
        return completionEpoch > 0L && completionEpoch == activeCompletionEpoch
                && benchmarkSessionMatches(eventSessionGeneration, activeSessionGeneration,
                        scenarioSessionGeneration, presentedSessionGeneration);
    }

    /** A stale or hidden pre-ARM session must not let a scheduled poll resume audio. */
    static boolean benchmarkCompletionPollMayTouchAudio(
            boolean preArmValid,
            long completionEpoch, long activeCompletionEpoch,
            long eventSessionGeneration, long activeSessionGeneration,
            long scenarioSessionGeneration, long presentedSessionGeneration) {
        return preArmValid && benchmarkCompletionPollMatches(
                completionEpoch, activeCompletionEpoch,
                eventSessionGeneration, activeSessionGeneration,
                scenarioSessionGeneration, presentedSessionGeneration);
    }

    private void publishBenchmarkScenarioInputFailure() {
        publish(RuntimeState.Phase.FAILED,
                "Benchmark scenario input could not be isolated for this session.",
                List.of(), activeLayout != null, true, false);
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
            performanceBoost.onSessionStopped();
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
            stateSaveCompletions.clear();
            return true;
        }
        try {
            // BasicController.close() joins its timing thread and intentionally suppresses the
            // ordinary EmulationStoppedEvent. Close the mutable platform session only after that
            // join, when no work callback can still race this runtime-owner fallback.
            active.close();
            performanceBoost.onSessionStopped();
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
            stateSaveCompletions.clear();
            return true;
        } catch (RuntimeException failure) {
            // The synchronized boost owner can be disarmed safely even if controller shutdown
            // timed out; subsequent synchronous work callbacks observe the cleared session.
            performanceBoost.onSessionStopped();
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

    private boolean retainReadPermission(Uri uri, int resultFlags) {
        try {
            if (new AndroidRomImportStore(context).ownsReadable(uri)) {
                return true;
            }
        } catch (IOException ignored) {
            // A transient document can still be opened or reported as unavailable below.
        }
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.getUri().equals(uri)) {
                return true;
            }
        }
        int read = resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int persistable = resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (read == 0 || persistable == 0) {
            return false;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri, read);
            return true;
        } catch (SecurityException ignored) {
            // A one-shot provider grant remains usable for this open but is not retained in Recents.
            return false;
        }
    }

    private void forgetRevokedPermission(Uri uri) {
        if (uri == null) {
            return;
        }
        new RecentSafDocuments(context).remove(uri);
        try {
            new AndroidRomImportStore(context).deleteIfOwned(uri);
        } catch (IOException ignored) {
            // There is no app-private import to remove.
        }
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
        pendingSnapshotReleasesMenuPause = false;
        pendingRecents = List.of();
        pendingRecentGames = List.of();
        closeQuietly(snapshot);
    }

    private void scheduleRecentPreview(Uri source, long openRequestId, int attempt) {
        if (diagnostics.enabled()) {
            return;
        }
        deadlines.schedule(() -> submit(() -> {
            if (currentSource == null || !currentSource.equals(source)
                    || activeOpenRequestId != openRequestId) {
                return;
            }
            NativeFrameStore.Snapshot snapshot = frames.snapshot();
            if (snapshot != null) {
                persistActiveRecentGame(snapshot);
            } else if (attempt + 1 < 4) {
                scheduleRecentPreview(source, openRequestId, attempt + 1);
            }
        }), recentPreviewDelay(attempt), TimeUnit.MILLISECONDS);
    }

    private void scheduleRecentGamesRefresh(Consumer<List<RecentGame>> callback, Uri source,
            long openRequestId, int attempt) {
        if (diagnostics.enabled()) {
            return;
        }
        deadlines.schedule(() -> submit(() -> {
            if (currentSource == null || !currentSource.equals(source)
                    || activeOpenRequestId != openRequestId) {
                return;
            }
            NativeFrameStore.Snapshot snapshot = frames.snapshot();
            if (snapshot != null) {
                persistActiveRecentGame(snapshot);
                deliverRecentGames(callback);
            } else if (attempt + 1 < 4) {
                scheduleRecentGamesRefresh(callback, source, openRequestId, attempt + 1);
            }
        }), recentPreviewDelay(attempt), TimeUnit.MILLISECONDS);
    }

    private static long recentPreviewDelay(int attempt) {
        return switch (Math.max(0, attempt)) {
            case 0 -> 100L;
            case 1 -> 250L;
            case 2 -> 500L;
            default -> 1_000L;
        };
    }

    private void deliverRecentGames(Consumer<List<RecentGame>> callback) {
        if (diagnostics.enabled()) {
            // The benchmark launch already selected its identity through the read-only v3
            // scanner. Avoid the normal menu catalog because it can write migration/pruning
            // metadata and delete stale preview files.
            pendingRecentGames = List.of();
            mainHandler.post(() -> callback.accept(List.of()));
            return;
        }
        List<RecentSafDocuments.Entry> entries =
                new RecentSafDocuments(context).readableEntries();
        pendingRecentGames = List.copyOf(entries);
        ArrayList<RecentGame> games = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            RecentSafDocuments.Entry entry = entries.get(index);
            games.add(new RecentGame(index, entry.romName(),
                    entry.lastPlayedMillis(), entry.preview()));
        }
        mainHandler.post(() -> callback.accept(List.copyOf(games)));
    }

    private void persistActiveRecentGame(NativeFrameStore.Snapshot snapshot) {
        if (diagnostics.enabled() || currentSource == null || activeRomTitle == null
                || activeRomTitle.isBlank()
                || activeLayout == null) {
            return;
        }
        new RecentSafDocuments(context).recordIfPersisted(currentSource, activeRomTitle,
                activeCandidateToken, activeArchiveEntryName, activeArchiveEntryOccurrence,
                activeRomHash, snapshot);
    }

    private void persistCurrentRecentGame() {
        if (!diagnostics.enabled()) {
            persistActiveRecentGame(frames.snapshot());
        }
    }

    private void recentGameUnavailable(RecentSafDocuments.Entry recent, String message) {
        if (!diagnostics.enabled()) {
            new RecentSafDocuments(context).removeGame(recent);
        }
        boolean active = activeLayout != null;
        boolean paused = active && state.paused();
        publish(active ? (paused ? RuntimeState.Phase.PAUSED : RuntimeState.Phase.RUNNING)
                        : RuntimeState.Phase.FAILED,
                message, List.of(), active, paused, false);
    }

    static Long resolveRecentCandidateToken(
            List<RomSourceSnapshot.ArchiveCandidate> candidates, long savedToken,
            String entryName, int entryOccurrence) {
        if (entryName == null || entryName.isEmpty() || entryOccurrence < 0) {
            return null;
        }
        for (RomSourceSnapshot.ArchiveCandidate candidate : candidates) {
            if (candidate.token() == savedToken && candidate.entryName().equals(entryName)
                    && candidate.entryOccurrence() == entryOccurrence) {
                return candidate.token();
            }
        }
        for (RomSourceSnapshot.ArchiveCandidate candidate : candidates) {
            if (candidate.entryName().equals(entryName)
                    && candidate.entryOccurrence() == entryOccurrence) {
                return candidate.token();
            }
        }
        return null;
    }

    static boolean recentHashMatches(String savedHash, String loadedHash) {
        // Entries written by earlier builds have no hash. Their exact archive coordinates (or
        // direct-document shape) remain the safest available legacy identity and are upgraded
        // with the computed hash as soon as emulation starts successfully.
        return savedHash == null || savedHash.isBlank()
                || loadedHash != null && savedHash.equalsIgnoreCase(loadedHash);
    }

    private void clearActiveCandidate() {
        activeCandidateToken = RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN;
        activeArchiveEntryName = "";
        activeArchiveEntryOccurrence = -1;
        activeRomHash = "";
    }

    record RecentGame(long token, String name, long lastPlayedMillis, MenuPreview preview) {
        RecentGame {
            if (name == null || name.isBlank()) {
                name = "RECENT GAME";
            }
            preview = preview == null ? MenuPreview.empty() : preview;
        }
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
        RuntimeState next;
        synchronized (presentationLock) {
            boolean activeSession = phase == RuntimeState.Phase.RUNNING
                    || phase == RuntimeState.Phase.PAUSED;
            String romTitle = activeSession ? activeRomTitle : "";
            long sessionGeneration = activeSession ? activeSessionGeneration : 0L;
            long playTimeNanos = updatePlayTime(phase, sessionGeneration);
            next = new RuntimeState(
                    phase, message, selections, transferReady, paused, flushPending,
                    romTitle,
                    activeSession && activeBatterySave,
                    sessionGeneration,
                    playTimeNanos,
                    Math.addExact(state.generation(), 1));
            state = next;
        }
        mainHandler.post(() -> {
            for (RuntimeObserver observer : observers) {
                observer.onStateChanged(next);
            }
        });
    }

    private long updatePlayTime(RuntimeState.Phase phase, long sessionGeneration) {
        if (phase != RuntimeState.Phase.RUNNING && phase != RuntimeState.Phase.PAUSED) {
            playTime.clear();
            playbackTimerSessionActive = false;
            playbackTimerSessionGeneration = 0L;
            restartPlaybackTimerOnNextPublish = false;
            clearPauseMenuSnapshotInternal();
            return 0L;
        }
        boolean sessionChanged = restartPlaybackTimerOnNextPublish || !playbackTimerSessionActive
                || (sessionGeneration != 0L && sessionGeneration != playbackTimerSessionGeneration);
        if (sessionChanged) {
            playTime.start();
            playbackTimerSessionActive = true;
            playbackTimerSessionGeneration = sessionGeneration;
            restartPlaybackTimerOnNextPublish = false;
            clearPauseMenuSnapshotInternal();
        } else if (playbackTimerSessionGeneration == 0L && sessionGeneration != 0L) {
            // Legacy producers may initially omit the generation. Promoting it must retain,
            // rather than restart, the interval that already began at EmulationStarted.
            playbackTimerSessionGeneration = sessionGeneration;
        }
        playTime.setRunning(phase == RuntimeState.Phase.RUNNING);
        return playTime.elapsedNanos();
    }

    /**
     * A reset replaces the controller session without a host open-request id.  Its preceding
     * {@code EmulationStoppedEvent} may already have reduced the Android presentation to
     * STOPPED, so acceptance must be based on the monotonic controller generation—not phase.
     */
    static boolean isResetReload(Long openRequestId, boolean hasActiveLayout,
            Long eventSessionGeneration, long activeSessionGeneration) {
        return openRequestId == null && hasActiveLayout && eventSessionGeneration != null
                && eventSessionGeneration > activeSessionGeneration;
    }

    /** Shared fail-closed generation gate for synchronous and owner-queued benchmark events. */
    static boolean benchmarkSessionMatches(long eventGeneration, long activeGeneration,
            long scenarioGeneration, long presentedGeneration) {
        return eventGeneration > 0L
                && eventGeneration == activeGeneration
                && eventGeneration == scenarioGeneration
                && eventGeneration == presentedGeneration;
    }

    private static boolean hasActiveSession(RuntimeState state) {
        return state.phase() == RuntimeState.Phase.RUNNING
                || state.phase() == RuntimeState.Phase.PAUSED;
    }

    private boolean matchesPauseSnapshotSession(RuntimeState current) {
        long capturedGeneration = pauseSnapshotSessionGeneration;
        if (capturedGeneration != 0L || current.sessionGeneration() != 0L) {
            return capturedGeneration == current.sessionGeneration();
        }
        // Older controller adapters can omit a session generation. An empty legacy state is not
        // enough evidence to invalidate a still-visible root pause menu, but a different title is.
        return current.romTitle().isBlank() || pauseSnapshotTitle.equals(current.romTitle());
    }

    private PauseMenuSnapshot pauseMenuSnapshotFor(RuntimeState current) {
        PauseMenuSnapshot captured = pauseMenuSnapshot;
        if (captured == null || !hasActiveSession(current)
                || !matchesPauseSnapshotSession(current)) {
            return null;
        }
        return captured;
    }

    private void clearPauseMenuSnapshotInternal() {
        synchronized (presentationLock) {
            pauseMenuSnapshot = null;
            pauseSnapshotSessionGeneration = 0L;
            pauseSnapshotTitle = "";
        }
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
        private final StateImage thumbnail;
        private final Runnable onCompleted;

        private PendingStateRequest(int slot, boolean save, StateImage thumbnail,
                Runnable onCompleted) {
            this.slot = slot;
            this.save = save;
            this.thumbnail = thumbnail;
            this.onCompleted = onCompleted;
        }
    }

    private record ObserverRegistration(RuntimeObserver observer, Long registrationId) {
    }

    private record PendingBenchmarkArm(
            long sessionGeneration,
            long generation,
            String token,
            AndroidAudioSink.AudioBaseline baseline) {

        private boolean matches(Controller.BenchmarkArmAcknowledgedEvent event) {
            return event != null
                    && sessionGeneration == event.getSessionGeneration()
                    && generation == event.getGeneration()
                    && token.equals(event.getToken());
        }
    }

    /** Tiny testable monitor shared by the main-thread hide path and owner-thread audio polls. */
    static final class BenchmarkAudioLifecycleGate {
        synchronized void run(Runnable action) {
            Objects.requireNonNull(action, "action").run();
        }
    }

    @FunctionalInterface
    private interface CheckedIoAction {
        void run() throws IOException;
    }
}
