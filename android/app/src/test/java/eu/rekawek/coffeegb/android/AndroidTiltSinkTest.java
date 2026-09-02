package eu.rekawek.coffeegb.android;

import android.view.Surface;
import android.hardware.SensorManager;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AndroidTiltSinkTest {

    @Test
    public void calibratesNeutralAndMapsAxesForEachDisplayRotation() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        List<AccelerometerEvent> samples = new ArrayList<>();
        events.register(samples::add, AccelerometerEvent.class);
        FakeInput input = new FakeInput(true);
        FakeOrientation orientation = new FakeOrientation(Surface.ROTATION_0);
        AndroidTiltSink sink = new AndroidTiltSink(events, input, orientation);

        sink.setCartridgeActive(true);
        input.sample(5, 10);
        input.sample(7, 13);
        assertSample(samples.get(0), 0, 0);
        assertSample(samples.get(1),
                -2 / SensorManager.GRAVITY_EARTH,
                3 / SensorManager.GRAVITY_EARTH);

        orientation.rotation = Surface.ROTATION_90;
        input.sample(7, 13);
        input.sample(8, 14);
        assertSample(samples.get(2), 0, 0);
        assertSample(samples.get(3),
                1 / SensorManager.GRAVITY_EARTH,
                1 / SensorManager.GRAVITY_EARTH);

        orientation.rotation = Surface.ROTATION_180;
        input.sample(8, 14);
        input.sample(9, 16);
        assertSample(samples.get(4), 0, 0);
        assertSample(samples.get(5),
                1 / SensorManager.GRAVITY_EARTH,
                -2 / SensorManager.GRAVITY_EARTH);

        orientation.rotation = Surface.ROTATION_270;
        input.sample(9, 16);
        input.sample(11, 19);
        assertSample(samples.get(6), 0, 0);
        assertSample(samples.get(7),
                -3 / SensorManager.GRAVITY_EARTH,
                -2 / SensorManager.GRAVITY_EARTH);
    }

    @Test
    public void loweringDisplayRightEdgeProducesPositiveHorizontalTiltForEveryRotation() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        List<AccelerometerEvent> samples = new ArrayList<>();
        events.register(samples::add, AccelerometerEvent.class);
        FakeInput input = new FakeInput(true);
        FakeOrientation orientation = new FakeOrientation(Surface.ROTATION_0);
        AndroidTiltSink sink = new AndroidTiltSink(events, input, orientation);

        int[] rotations = {
                Surface.ROTATION_0,
                Surface.ROTATION_90,
                Surface.ROTATION_180,
                Surface.ROTATION_270
        };
        float gravity = SensorManager.GRAVITY_EARTH;
        float[][] rightEdgeDownDeltas = {
                {-gravity, 0},
                {0, gravity},
                {gravity, 0},
                {0, -gravity}
        };

        sink.setCartridgeActive(true);
        for (int i = 0; i < rotations.length; i++) {
            orientation.rotation = rotations[i];
            input.sample(0, 0);
            input.sample(rightEdgeDownDeltas[i][0], rightEdgeDownDeltas[i][1]);
            assertSample(samples.get(samples.size() - 1), 1, 0);
        }
    }

    @Test
    public void convertsAndroidMetersPerSecondSquaredToGravityUnits() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        List<AccelerometerEvent> samples = new ArrayList<>();
        events.register(samples::add, AccelerometerEvent.class);
        FakeInput input = new FakeInput(true);
        AndroidTiltSink sink = new AndroidTiltSink(events, input,
                () -> Surface.ROTATION_0);

        sink.setCartridgeActive(true);
        input.sample(0, 0);
        input.sample(SensorManager.GRAVITY_EARTH, -SensorManager.GRAVITY_EARTH);

        assertSample(samples.get(1), -1, -1);
    }

    @Test
    public void pauseAndMissingSensorSafelyFallBackToNeutralInput() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        List<AccelerometerEvent> samples = new ArrayList<>();
        events.register(samples::add, AccelerometerEvent.class);
        FakeInput input = new FakeInput(true);
        AndroidTiltSink sink = new AndroidTiltSink(events, input,
                () -> Surface.ROTATION_0);

        sink.setCartridgeActive(true);
        input.sample(4, 9);
        sink.pause();
        input.sample(9, 12);
        sink.resume();
        input.sample(9, 12);

        assertEquals(2, input.starts);
        assertEquals(1, input.stops);
        assertEquals(2, samples.size());
        assertSample(samples.get(0), 0, 0);
        assertSample(samples.get(1),
                -5 / SensorManager.GRAVITY_EARTH,
                3 / SensorManager.GRAVITY_EARTH);
        sink.close();
        assertEquals(2, input.stops);

        FakeInput unavailable = new FakeInput(false);
        AndroidTiltSink fallback = new AndroidTiltSink(events, unavailable,
                () -> Surface.ROTATION_0);
        fallback.setCartridgeActive(true);
        assertEquals(1, unavailable.starts);
        assertSample(samples.get(2), 0, 0);
    }

    @Test
    public void explicitCalibrationRebasesTheNextSensorSample() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        List<AccelerometerEvent> samples = new ArrayList<>();
        events.register(samples::add, AccelerometerEvent.class);
        FakeInput input = new FakeInput(true);
        AndroidTiltSink sink = new AndroidTiltSink(events, input,
                () -> Surface.ROTATION_0);

        sink.setCartridgeActive(true);
        input.sample(2, 4);
        input.sample(4, 7);
        sink.calibrate();
        input.sample(4, 7);
        input.sample(5, 9);

        assertSample(samples.get(1),
                -2 / SensorManager.GRAVITY_EARTH,
                3 / SensorManager.GRAVITY_EARTH);
        assertSample(samples.get(2), 0, 0);
        assertSample(samples.get(3),
                -1 / SensorManager.GRAVITY_EARTH,
                2 / SensorManager.GRAVITY_EARTH);
    }

    private static void assertSample(AccelerometerEvent sample, double x, double y) {
        assertEquals(x, sample.x(), 0.0001);
        assertEquals(y, sample.y(), 0.0001);
    }

    private static final class FakeInput implements AndroidTiltSink.Input {
        private final boolean available;
        private AndroidTiltSink.SampleListener listener;
        private int starts;
        private int stops;

        private FakeInput(boolean available) {
            this.available = available;
        }

        @Override
        public boolean start(AndroidTiltSink.SampleListener listener) {
            starts++;
            if (!available) {
                return false;
            }
            this.listener = listener;
            return true;
        }

        @Override
        public void stop(AndroidTiltSink.SampleListener listener) {
            stops++;
            if (this.listener == listener) {
                this.listener = null;
            }
        }

        private void sample(float x, float y) {
            if (listener != null) {
                listener.onAcceleration(x, y);
            }
        }
    }

    private static final class FakeOrientation implements AndroidTiltSink.Orientation {
        private int rotation;

        private FakeOrientation(int rotation) {
            this.rotation = rotation;
        }

        @Override
        public int rotation() {
            return rotation;
        }
    }
}
