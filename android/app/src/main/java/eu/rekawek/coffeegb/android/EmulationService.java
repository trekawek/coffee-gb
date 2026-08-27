package eu.rekawek.coffeegb.android;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;

import eu.rekawek.coffeegb.core.ExecutionMode;

/**
 * Same-process, non-foreground owner for the Android emulation runtime.
 *
 * <p>Activities bind only to observe/command the runtime. This service owns the controller and
 * its event tree across Activity recreation; it is {@link #START_NOT_STICKY}, does not enter the
 * foreground, and never claims that a process-recreated game is still running.
 */
public final class EmulationService extends Service implements AudioManager.OnAudioFocusChangeListener {

    private final RuntimeBinder binder = new RuntimeBinder();
    private static volatile DiagnosticsOptions nextStartOptions = DiagnosticsOptions.disabled();

    private AndroidEmulationRuntime runtime;
    private AudioManager audioManager;
    private DiagnosticsOptions pendingOptions = DiagnosticsOptions.disabled();

    public static void start(Context context) {
        start(context, DiagnosticsOptions.executionModeValue(
                ExecutionMode.ACCURACY));
    }

    /** Starts an ordinary session with the persisted frontend-selected core strategy. */
    static void start(Context context, String executionMode) {
        DiagnosticsOptions selected = DiagnosticsOptions.disabled(
                DiagnosticsOptions.parseExecutionMode(executionMode));
        nextStartOptions = selected;
        context.startService(new Intent(context, EmulationService.class)
                .putExtra(DiagnosticsOptions.EXTRA_EXECUTION_MODE,
                        DiagnosticsOptions.executionModeValue(selected.executionMode)));
    }

    static void start(Context context, DiagnosticsOptions options) {
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        context.startService(startIntent(context, checked));
    }

    /** Builds the service wire separately so its typed diagnostics extras can be unit-tested. */
    static Intent startIntent(Context context, DiagnosticsOptions options) {
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        return populateStartIntent(new Intent(context, EmulationService.class), checked);
    }

    static Intent startIntent(DiagnosticsOptions options) {
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        return populateStartIntent(new Intent(), checked);
    }

    static String benchmarkScenarioExtraValue(DiagnosticsOptions options) {
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        return checked.benchmarkScenario.externalValue();
    }

    static String audioPolicyExtraValue(DiagnosticsOptions options) {
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        return checked.audioPolicy.externalValue();
    }

    static String bootstrapModeExtraValue(DiagnosticsOptions options) {
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        return DiagnosticsOptions.bootstrapModeValue(checked.bootstrapMode);
    }

    private static Intent populateStartIntent(Intent intent, DiagnosticsOptions checked) {
        nextStartOptions = checked;
        return intent.putExtra(DiagnosticsOptions.EXTRA_BENCHMARK, checked.enabled)
                .putExtra(DiagnosticsOptions.EXTRA_HARDWARE,
                        checked.hardware.externalValue())
                .putExtra(DiagnosticsOptions.EXTRA_AUDIO, checked.audioOutput)
                .putExtra(DiagnosticsOptions.EXTRA_RENDER,
                        checked.render == DiagnosticsOptions.Render.FRAME_SINK ? "sink" : "presentation")
                .putExtra(DiagnosticsOptions.EXTRA_WARMUP, checked.runtimeWarmup)
                .putExtra(DiagnosticsOptions.EXTRA_RECENT, checked.launchRecent)
                .putExtra(DiagnosticsOptions.EXTRA_BUILD_ID, checked.buildId)
                .putExtra(DiagnosticsOptions.EXTRA_PAIR_ID, checked.pairId)
                .putExtra(DiagnosticsOptions.EXTRA_MATRIX_BLOCK, checked.matrixBlock)
                .putExtra(DiagnosticsOptions.EXTRA_ROW_ORDER, checked.rowOrder)
                .putExtra(DiagnosticsOptions.EXTRA_RUN_SIDE, checked.runSide.externalValue())
                .putExtra(DiagnosticsOptions.EXTRA_FIRST_SIDE, checked.firstSide.externalValue())
                .putExtra(DiagnosticsOptions.EXTRA_DEVICE_BUILD, checked.deviceBuild)
                .putExtra(DiagnosticsOptions.EXTRA_THERMAL_WINDOW, checked.thermalWindow)
                .putExtra(DiagnosticsOptions.EXTRA_THERMAL_VALID, checked.thermalValid)
                .putExtra(DiagnosticsOptions.EXTRA_WORKLOAD_NONCE, checked.workloadNonce)
                .putExtra(DiagnosticsOptions.EXTRA_SURFACE_RATE_HZ, checked.displayTargetHz)
                .putExtra(DiagnosticsOptions.EXTRA_RECENT_SLOT, checked.recentSlot)
                .putExtra(DiagnosticsOptions.EXTRA_EXECUTION_MODE,
                        DiagnosticsOptions.executionModeValue(checked.executionMode))
                .putExtra(DiagnosticsOptions.EXTRA_BOOTSTRAP,
                        bootstrapModeExtraValue(checked))
                .putExtra(DiagnosticsOptions.EXTRA_BENCHMARK_SCENARIO,
                        benchmarkScenarioExtraValue(checked))
                .putExtra(DiagnosticsOptions.EXTRA_AUDIO_POLICY,
                        audioPolicyExtraValue(checked));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = getSystemService(AudioManager.class);
        pendingOptions = nextStartOptions;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The system must not recreate a stopped process and imply that its in-memory game lived.
        pendingOptions = intent == null ? nextStartOptions : DiagnosticsOptions.fromIntent(intent);
        // Matrix scheduling must force-stop/relaunch the benchmark process between runs.  An
        // in-process runtime replacement would leave an already-bound Activity/Surface attached
        // to the closed generation and could silently turn a visible run into a headless run.
        // Keep one runtime per service generation; the host workflow owns isolation.
        ensureRuntime();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        ensureRuntime();
        runtime.onHostVisible();
        requestAudioFocus();
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        abandonAudioFocus();
        runtime.onHostNotVisible();
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        runtime.onHostVisible();
        requestAudioFocus();
        super.onRebind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // This pauses at a controller safe point and starts a bounded flush without background
        // playback. The non-sticky service may be reclaimed; a later process starts truthfully
        // stopped and offers only the persisted recent-document metadata.
        abandonAudioFocus();
        runtime.onHostNotVisible();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        abandonAudioFocus();
        if (runtime != null) {
            runtime.close();
        }
        super.onDestroy();
    }

    private void ensureRuntime() {
        if (runtime == null) {
            runtime = new AndroidEmulationRuntime(this, pendingOptions);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (runtime != null && (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            runtime.onAudioFocusLost();
        }
        // On gain, runtime deliberately remains paused until the user resumes. Phase 6 installs
        // AudioTrack here; this lifecycle policy already prevents an unexpected restart/route race.
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            if (runtime != null) {
                runtime.onAudioFocusResult(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
        } else if (runtime != null) {
            runtime.onAudioFocusResult(false);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null) {
            audioManager.abandonAudioFocus(this);
        }
    }

    public final class RuntimeBinder extends Binder {
        public AndroidEmulationRuntime runtime() {
            return runtime;
        }
    }
}
