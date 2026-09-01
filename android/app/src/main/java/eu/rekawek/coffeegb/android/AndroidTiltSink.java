package eu.rekawek.coffeegb.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;

import java.util.Objects;

/**
 * Android host adapter for MBC7 tilt input. Neutral calibration and display rotation stay host
 * state: the portable mapper continues to receive only normalized accelerometer events.
 */
final class AndroidTiltSink implements AutoCloseable {

    interface SampleListener {
        void onAcceleration(float x, float y);
    }

    interface Input {
        boolean start(SampleListener listener);

        void stop(SampleListener listener);
    }

    interface Orientation {
        int rotation();
    }

    private static final class AndroidSensorInput implements Input, SensorEventListener {
        private final SensorManager sensorManager;
        private final Sensor accelerometer;
        private SampleListener listener;

        private AndroidSensorInput(Context context) {
            sensorManager = Objects.requireNonNull(context, "context")
                    .getApplicationContext().getSystemService(SensorManager.class);
            accelerometer = sensorManager == null
                    ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        @Override
        public synchronized boolean start(SampleListener listener) {
            if (accelerometer == null || this.listener != null) {
                return false;
            }
            this.listener = listener;
            if (!sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)) {
                this.listener = null;
                return false;
            }
            return true;
        }

        @Override
        public synchronized void stop(SampleListener listener) {
            if (this.listener != listener) {
                return;
            }
            sensorManager.unregisterListener(this);
            this.listener = null;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER || event.values.length < 2) {
                return;
            }
            SampleListener target;
            synchronized (this) {
                target = listener;
            }
            if (target != null) {
                target.onAcceleration(event.values[0], event.values[1]);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // The current sample is used directly; no accuracy threshold can safely invent input.
        }
    }

    private static final class DisplayOrientation implements Orientation {
        private final WindowManager windowManager;

        private DisplayOrientation(Context context) {
            windowManager = Objects.requireNonNull(context, "context")
                    .getApplicationContext().getSystemService(WindowManager.class);
        }

        @Override
        @SuppressLint("deprecation")
        public int rotation() {
            return windowManager == null ? Surface.ROTATION_0 : windowManager.getDefaultDisplay()
                    .getRotation();
        }
    }

    private final EventBus eventBus;
    private final Input input;
    private final Orientation orientation;
    private final SampleListener samples = this::onAcceleration;

    private boolean cartridgeActive;
    private boolean hostActive = true;
    private boolean sensorRegistered;
    private boolean calibrated;
    private boolean closed;
    private int calibrationRotation = -1;
    private float neutralX;
    private float neutralY;

    AndroidTiltSink(Context context, EventBus eventBus) {
        this(eventBus, new AndroidSensorInput(context), new DisplayOrientation(context));
    }

    AndroidTiltSink(EventBus eventBus, Input input, Orientation orientation) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.input = Objects.requireNonNull(input, "input");
        this.orientation = Objects.requireNonNull(orientation, "orientation");
    }

    synchronized void setCartridgeActive(boolean active) {
        if (closed || cartridgeActive == active) {
            return;
        }
        cartridgeActive = active;
        resetCalibration();
        if (active) {
            startIfNeeded();
        } else {
            stopIfNeeded();
        }
    }

    synchronized void pause() {
        hostActive = false;
        stopIfNeeded();
    }

    synchronized void resume() {
        hostActive = true;
        startIfNeeded();
    }

    /** Uses the next rotation-corrected sample as the neutral MBC7 position. */
    synchronized void calibrate() {
        resetCalibration();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        cartridgeActive = false;
        stopIfNeeded();
    }

    private synchronized void onAcceleration(float rawX, float rawY) {
        if (closed || !cartridgeActive || !hostActive || !sensorRegistered) {
            return;
        }
        int rotation = orientation.rotation();
        float x;
        float y;
        switch (rotation) {
            case Surface.ROTATION_90 -> {
                x = -rawY;
                y = rawX;
            }
            case Surface.ROTATION_180 -> {
                x = -rawX;
                y = -rawY;
            }
            case Surface.ROTATION_270 -> {
                x = rawY;
                y = -rawX;
            }
            default -> {
                x = rawX;
                y = rawY;
            }
        }
        if (!calibrated || calibrationRotation != rotation) {
            neutralX = x;
            neutralY = y;
            calibrationRotation = rotation;
            calibrated = true;
        }
        // Android reports acceleration in m/s² while the portable MBC7 contract uses g units.
        // Feeding the raw delta made the emulated sensor roughly 9.8 times too sensitive.
        eventBus.post(new AccelerometerEvent(
                (x - neutralX) / SensorManager.GRAVITY_EARTH,
                (y - neutralY) / SensorManager.GRAVITY_EARTH));
    }

    private void startIfNeeded() {
        if (closed || !cartridgeActive || !hostActive || sensorRegistered) {
            return;
        }
        sensorRegistered = input.start(samples);
        if (!sensorRegistered) {
            eventBus.post(new AccelerometerEvent(0, 0));
        }
    }

    private void stopIfNeeded() {
        if (!sensorRegistered) {
            return;
        }
        input.stop(samples);
        sensorRegistered = false;
    }

    private void resetCalibration() {
        calibrated = false;
        calibrationRotation = -1;
        neutralX = 0;
        neutralY = 0;
    }
}
