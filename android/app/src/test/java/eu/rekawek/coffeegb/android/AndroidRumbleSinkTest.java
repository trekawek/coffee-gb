package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class AndroidRumbleSinkTest {

    @Test
    public void toggleAndCloseCancelWithoutChangingPortableRumbleEvents() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeOutput output = new FakeOutput(true);
        AndroidRumbleSink sink = new AndroidRumbleSink(output, events, true);

        events.post(new RumbleEvent(true));
        assertEquals(1, output.starts.get());
        sink.setEnabled(false);
        assertEquals(1, output.cancels.get());
        sink.setEnabled(true);
        assertEquals(2, output.starts.get());
        events.post(new RumbleEvent(false));
        assertEquals(2, output.cancels.get());
        sink.close();
        assertEquals(3, output.cancels.get());
    }

    @Test
    public void pauseStopsRumbleAndUserResumeRestoresOnlyAnActiveMotor() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeOutput output = new FakeOutput(true);
        AndroidRumbleSink sink = new AndroidRumbleSink(output, events, true);

        events.post(new RumbleEvent(true));
        sink.pause();
        sink.resume();
        events.post(new RumbleEvent(false));
        sink.pause();
        sink.resume();

        assertEquals(2, output.starts.get());
        assertEquals(2, output.cancels.get());
    }

    @Test
    public void unsupportedOutputNeverStarts() {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeOutput output = new FakeOutput(false);
        AndroidRumbleSink sink = new AndroidRumbleSink(output, events, true);

        events.post(new RumbleEvent(true));
        sink.pause();
        sink.resume();

        assertEquals(0, output.starts.get());
        assertEquals(0, output.cancels.get());
    }

    private static final class FakeOutput implements AndroidRumbleSink.Output {
        private final boolean supported;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();

        private FakeOutput(boolean supported) {
            this.supported = supported;
        }

        @Override
        public boolean supported() {
            return supported;
        }

        @Override
        public void start() {
            starts.incrementAndGet();
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }
    }
}
