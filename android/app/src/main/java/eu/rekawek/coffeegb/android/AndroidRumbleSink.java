package eu.rekawek.coffeegb.android;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;

import java.util.Objects;

/**
 * Android host adapter for the portable rumble event. It never changes emulation state and can be
 * disabled without suppressing the event stream needed by other frontend adapters.
 */
final class AndroidRumbleSink implements AutoCloseable {

    interface Output {
        boolean supported();

        void start();

        void cancel();
    }

    private static final class VibratorOutput implements Output {
        private final Vibrator vibrator;

        private VibratorOutput(Context context) {
            Context applicationContext = Objects.requireNonNull(context, "context")
                    .getApplicationContext();
            vibrator = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? Api31Vibrator.defaultVibrator(applicationContext)
                    : applicationContext.getSystemService(Vibrator.class);
        }

        @Override
        public boolean supported() {
            return vibrator != null && vibrator.hasVibrator();
        }

        @Override
        public void start() {
            if (supported()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0L, 50L}, 0));
            }
        }

        @Override
        public void cancel() {
            if (vibrator != null) {
                vibrator.cancel();
            }
        }
    }

    /** Keeps the Android 12 API reference out of verification on older devices. */
    private static final class Api31Vibrator {
        @TargetApi(Build.VERSION_CODES.S)
        private static Vibrator defaultVibrator(Context context) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            return manager == null ? null : manager.getDefaultVibrator();
        }
    }

    private final Output output;
    private boolean enabled;
    private boolean active;
    private boolean hostActive = true;
    private boolean outputActive;
    private boolean closed;

    AndroidRumbleSink(Context context, EventBus eventBus, boolean enabled) {
        this(new VibratorOutput(context), eventBus, enabled);
    }

    AndroidRumbleSink(Output output, EventBus eventBus, boolean enabled) {
        this.output = Objects.requireNonNull(output, "output");
        this.enabled = enabled;
        Objects.requireNonNull(eventBus, "eventBus").register(this::onRumble, RumbleEvent.class);
    }

    synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        apply();
    }

    synchronized void pause() {
        hostActive = false;
        apply();
    }

    synchronized void resume() {
        hostActive = true;
        apply();
    }

    synchronized boolean supported() {
        return !closed && output.supported();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        outputActive = false;
        output.cancel();
    }

    private synchronized void onRumble(RumbleEvent event) {
        if (closed) {
            return;
        }
        active = event.on();
        apply();
    }

    private void apply() {
        boolean shouldRun = enabled && active && hostActive && output.supported();
        if (shouldRun == outputActive) {
            return;
        }
        outputActive = shouldRun;
        if (shouldRun) {
            output.start();
        } else {
            output.cancel();
        }
    }
}
