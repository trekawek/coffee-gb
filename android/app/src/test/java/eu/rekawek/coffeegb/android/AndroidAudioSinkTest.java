package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AndroidAudioSinkTest {

    @Test
    public void writesOnlyOnDedicatedConsumerAndRecoversAfterUnderrunAndRouteChange() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == 44_100);
            Thread owner = sink.workerThreadForTesting();
            assertNotNull(owner);
            assertTrue(owner.getName().contains("android-audio"));

            events.post(new Sound.SoundSampleEvent(audibleSamples(), ClockSpec.LEGACY));
            await("first PCM write", () -> factory.current().writes.size() == 1);
            assertTrue(factory.current().writeThreads.stream().allMatch(name -> name.equals(owner.getName())));

            await("underrun", () -> sink.stats().underruns() > 0);
            events.post(new Sound.SoundSampleEvent(audibleSamples(), ClockSpec.LEGACY));
            await("underrun recovery", () -> factory.current().writes.size() == 2);

            FakeOutput first = factory.current();
            sink.requestRouteReopen();
            await("route reopen", () -> factory.opens.get() == 2 && first.released);
            assertEquals(1, sink.stats().restarts());
        } finally {
            sink.close();
        }
        assertTrue(factory.current().released);
        assertFalse(sink.stats().active());
        assertFalse(sink.workerThreadForTesting().isAlive());
    }

    @Test
    public void pauseClearsQueuedFramesUntilExplicitResume() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == 44_100);
            sink.pause();
            events.post(new Sound.SoundSampleEvent(audibleSamples(), ClockSpec.LEGACY));
            Thread.sleep(60);
            assertEquals(0, factory.current().writes.size());

            sink.resume();
            events.post(new Sound.SoundSampleEvent(audibleSamples(), ClockSpec.LEGACY));
            await("resumed PCM write", () -> factory.current().writes.size() == 1);
        } finally {
            sink.close();
        }
    }

    private static int[] audibleSamples() {
        int ticks = ClockSpec.LEGACY.controllerTicksPerFrame();
        int[] samples = new int[ticks * 2];
        for (int tick = 0; tick < ticks; tick++) {
            samples[tick * 2] = 480;
            samples[tick * 2 + 1] = -480;
        }
        return samples;
    }

    private static void await(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("Timed out waiting for " + description, condition.getAsBoolean());
    }

    private static final class FakeFactory implements AndroidAudioSink.OutputFactory {
        private final AtomicInteger opens = new AtomicInteger();
        private final List<FakeOutput> outputs = new CopyOnWriteArrayList<>();

        @Override
        public AndroidAudioSink.Output open() {
            FakeOutput output = new FakeOutput();
            outputs.add(output);
            opens.incrementAndGet();
            return output;
        }

        private FakeOutput current() {
            return outputs.get(outputs.size() - 1);
        }
    }

    private static final class FakeOutput implements AndroidAudioSink.Output {
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();
        private final List<String> writeThreads = new CopyOnWriteArrayList<>();
        private volatile boolean released;

        @Override
        public int sampleRate() {
            return 44_100;
        }

        @Override
        public void play() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void flush() {
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            writes.add(Arrays.copyOfRange(bytes, offset, offset + length));
            writeThreads.add(Thread.currentThread().getName());
            return length;
        }

        @Override
        public void release() {
            released = true;
        }
    }
}
