package eu.rekawek.coffeegb.android;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;

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
        nextStartOptions = DiagnosticsOptions.disabled();
        context.startService(new Intent(context, EmulationService.class));
    }

    static void start(Context context, DiagnosticsOptions options) {
        Intent intent = new Intent(context, EmulationService.class);
        DiagnosticsOptions checked = options == null ? DiagnosticsOptions.disabled() : options;
        nextStartOptions = checked;
        intent.putExtra(DiagnosticsOptions.EXTRA_BENCHMARK, checked.enabled)
                .putExtra(DiagnosticsOptions.EXTRA_HARDWARE,
                        checked.hardware == DiagnosticsOptions.Hardware.DMG ? "dmg" : "auto")
                .putExtra(DiagnosticsOptions.EXTRA_AUDIO, checked.audioOutput)
                .putExtra(DiagnosticsOptions.EXTRA_RENDER,
                        checked.render == DiagnosticsOptions.Render.FRAME_SINK ? "sink" : "presentation")
                .putExtra(DiagnosticsOptions.EXTRA_WARMUP, checked.runtimeWarmup)
                .putExtra(DiagnosticsOptions.EXTRA_RECENT, checked.launchRecent);
        context.startService(intent);
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
        pendingOptions = BuildConfig.DIAGNOSTICS_ENABLED
                ? DiagnosticsOptions.fromIntent(intent) : DiagnosticsOptions.disabled();
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
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            runtime.onAudioFocusLost();
        }
        // On gain, runtime deliberately remains paused until the user resumes. Phase 6 installs
        // AudioTrack here; this lifecycle policy already prevents an unexpected restart/route race.
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
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
