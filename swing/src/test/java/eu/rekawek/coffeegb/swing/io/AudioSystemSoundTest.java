package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AudioSystemSoundTest {

    private static final String DEVICE_A = "java-sound-" + "a".repeat(64);

    @Test
    public void softwareGainAndMuteDoNotDependOnHostMixerControls() throws Exception {
        byte[] full = render(new AudioRuntimeConfiguration(
                "default", 100, false,
                AudioRuntimeConfiguration.LatencyPreset.BALANCED));
        byte[] half = render(new AudioRuntimeConfiguration(
                "default", 50, false,
                AudioRuntimeConfiguration.LatencyPreset.BALANCED));
        byte[] muted = render(new AudioRuntimeConfiguration(
                "default", 100, true,
                AudioRuntimeConfiguration.LatencyPreset.BALANCED));

        int fullSample = firstNonZeroLeftSample(full);
        int halfSample = firstNonZeroLeftSample(half);
        assertNotEquals(0, fullSample);
        assertEquals(Math.abs(fullSample) / 2, Math.abs(halfSample), 1);
        assertTrue(Arrays.stream(toUnsigned(muted)).allMatch(value -> value == 0));
    }

    @Test
    public void activeLatencyPresetBoundsProducerQueueWithoutBlockingEmulation() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        AudioSystemSound sound = new AudioSystemSound(
                new AudioRuntimeConfiguration(
                        "default", 100, false,
                        AudioRuntimeConfiguration.LatencyPreset.LOW),
                eventBus,
                null,
                new FakeBackend(),
                ignored -> {
                });

        for (int i = 0; i < 10; i++) {
            eventBus.post(new Sound.SoundSampleEvent(new int[400]));
        }

        assertEquals(
                AudioRuntimeConfiguration.LatencyPreset.LOW.runtimeQueueCapacity(),
                queue(sound).size());
    }

    @Test
    public void balancedRuntimeQueueAcceptsCatchUpHeadroomWithoutChangingStartupWatermark()
            throws Exception {
        EventBusImpl eventBus = synchronousBus();
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                new FakeBackend(),
                ignored -> {
                });
        int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());
        int capacity = AudioRuntimeConfiguration.LatencyPreset.BALANCED.runtimeQueueCapacity();

        for (int frame = 0; frame < capacity; frame++) {
            eventBus.post(new Sound.SoundSampleEvent(samples));
        }
        assertEquals(12, capacity);
        assertEquals(capacity, queue(sound).size());

        eventBus.post(new Sound.SoundSampleEvent(samples));
        assertEquals("runtime queue must remain bounded", capacity, queue(sound).size());
    }

    @Test
    public void producerDelayNeverQueuesSyntheticFrameSizedSilence() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.add("default", "System Default");
        AudioSystemSound sound = new AudioSystemSound(
                new AudioRuntimeConfiguration(
                        "default", 100, false,
                        AudioRuntimeConfiguration.LatencyPreset.LOW),
                eventBus,
                null,
                backend,
                ignored -> {
                });

        sound.start();
        try {
            await("default output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            FakeLine line = backend.lastLine("default");
            int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());

            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("first real PCM frame", () -> line.nonSilentWriteCount() >= 1);
            int firstRealWrite = line.firstNonSilentWriteAfter(-1);

            // The old worker converted every 20 ms timeout into a complete 16.667 ms zero frame.
            // Keep the producer quiet across several such polls, then let the real stream resume.
            Thread.sleep(80);
            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("second real PCM frame", () -> line.nonSilentWriteCount() >= 2);
            int secondRealWrite = line.firstNonSilentWriteAfter(firstRealWrite);

            for (int write = firstRealWrite + 1; write < secondRealWrite; write++) {
                assertFalse(
                        "audio worker manufactured silence at write " + write,
                        allZero(line.writes.get(write)));
            }
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void latencyPresetPrimesItsWatermarkBeforeTheFirstWrite() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.add("default", "System Default");
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                backend,
                ignored -> {
                });

        sound.start();
        try {
            await("default output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            FakeLine line = backend.lastLine("default");
            int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());
            int watermark = AudioRuntimeConfiguration.LatencyPreset.BALANCED.queuedFrames();

            for (int frame = 1; frame < watermark; frame++) {
                eventBus.post(new Sound.SoundSampleEvent(samples));
            }
            Thread.sleep(40);
            assertTrue("partial watermark must remain staged", line.writes.isEmpty());

            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("primed PCM batch", () -> line.nonSilentWriteCount() == watermark);
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void legacySoundEventUpdatesMuteWithoutDiscardingOtherRuntimeSettings() {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        AudioRuntimeConfiguration initial = new AudioRuntimeConfiguration(
                DEVICE_A, 37, false, AudioRuntimeConfiguration.LatencyPreset.SAFE);
        AudioSystemSound sound = new AudioSystemSound(
                initial,
                eventBus,
                null,
                backend,
                ignored -> {
                });

        eventBus.post(new Sound.SoundEnabledEvent(false));
        assertEquals(
                new AudioRuntimeConfiguration(
                        DEVICE_A, 37, true, AudioRuntimeConfiguration.LatencyPreset.SAFE),
                sound.currentConfiguration());

        eventBus.post(new Sound.SoundEnabledEvent(true));
        assertEquals(initial, sound.currentConfiguration());
    }

    @Test
    public void runtimeConfigurationNeverResetsExactClockOrFilterPhase() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.add(DEVICE_A, "Output A");
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                backend,
                ignored -> {
                });
        ClockSpec clock = ClockSpec.SGB2;

        eventBus.post(new Sound.SoundSampleEvent(new int[86], clock));
        queue(sound).remove();
        long phaseBeforeChange = sound.samplePhaseForTesting();
        sound.applyConfiguration(new AudioRuntimeConfiguration(
                DEVICE_A, 37, true, AudioRuntimeConfiguration.LatencyPreset.SAFE));
        eventBus.post(new Sound.SoundSampleEvent(new int[134], ClockSpec.SGB2));
        long samples = queue(sound).remove().length / 4L;

        BigInteger numerator =
                BigInteger.valueOf(110L).multiply(BigInteger.valueOf(44_100));
        BigInteger[] expected = numerator.divideAndRemainder(
                BigInteger.valueOf(clock.ticksPerSecondNumerator()));
        assertNotEquals(0L, phaseBeforeChange);
        assertEquals(expected[0].longValueExact(), samples);
        assertEquals(expected[1].longValueExact(), sound.samplePhaseForTesting());
    }

    @Test
    public void workerOwnsOpenReconfigureFallbackRecoveryAndOrdinaryClose() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.add("default", "System Default");
        backend.add(DEVICE_A, "Output A");
        List<AudioOutputStatus> statuses = new CopyOnWriteArrayList<>();
        AudioSystemSound sound = new AudioSystemSound(
                new AudioRuntimeConfiguration(
                        DEVICE_A, 100, false,
                        AudioRuntimeConfiguration.LatencyPreset.BALANCED),
                eventBus,
                null,
                backend,
                statuses::add);

        Thread worker = sound.start();
        assertTrue(worker.isDaemon());
        await("configured device to open",
                () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
        assertEquals(DEVICE_A, sound.currentStatus().activeDeviceId());
        FakeLine first = backend.lastLine(DEVICE_A);
        assertEquals(8192, first.requestedBufferBytes);

        int opensBeforeGain = backend.openedIds.size();
        sound.applyConfiguration(new AudioRuntimeConfiguration(
                DEVICE_A, 25, false,
                AudioRuntimeConfiguration.LatencyPreset.BALANCED));
        await("gain change to flush old PCM", () -> first.flushCount > 0);
        assertEquals(opensBeforeGain, backend.openedIds.size());

        sound.applyConfiguration(new AudioRuntimeConfiguration(
                DEVICE_A, 25, false,
                AudioRuntimeConfiguration.LatencyPreset.SAFE));
        await("latency change to reopen line",
                () -> backend.openedIds.size() > opensBeforeGain);
        FakeLine safe = backend.lastLine(DEVICE_A);
        assertEquals(16384, safe.requestedBufferBytes);
        assertTrue(first.closed);

        backend.remove(DEVICE_A);
        safe.failWrites = true;
        for (int frame = 0;
                frame < AudioRuntimeConfiguration.LatencyPreset.SAFE.queuedFrames();
                frame++) {
            eventBus.post(new Sound.SoundSampleEvent(constantSamples(400, 480)));
        }
        await("missing configured device to fall back",
                () -> sound.currentStatus().state() == AudioOutputStatus.State.FALLBACK);
        assertEquals("default", sound.currentStatus().activeDeviceId());
        assertEquals(DEVICE_A, sound.currentStatus().requestedDeviceId());
        assertTrue(sound.currentStatus().detail().contains("System Default"));

        FakeLine fallback = backend.lastLine("default");
        backend.exclusivePreferredDevice = DEVICE_A;
        backend.add(DEVICE_A, "Output A reconnected");
        await("configured device to recover from fallback",
                () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE
                        && DEVICE_A.equals(sound.currentStatus().activeDeviceId()));
        assertTrue("exclusive fallback line must close before preferred reopen", fallback.closed);
        assertEquals(0, backend.exclusiveOpenConflicts);

        long started = System.nanoTime();
        sound.stopThread();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue("shutdown exceeded its bounded normal path: " + elapsedMillis,
                elapsedMillis < 2500);
        assertFalse(worker.isAlive());
        assertEquals(AudioOutputStatus.State.STOPPED, sound.currentStatus().state());

        assertFalse(backend.operationThreads.isEmpty());
        assertTrue(backend.operationThreads.stream()
                .allMatch(name -> name.equals(worker.getName())));
        assertTrue(statuses.stream()
                .anyMatch(status -> status.state() == AudioOutputStatus.State.FALLBACK));
    }

    @Test
    public void permanentlyBlockingProviderCloseCannotBreakStopDeadline() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        BlockingCloseBackend backend = new BlockingCloseBackend();
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                backend,
                ignored -> {
                });

        Thread worker = sound.start();
        await("blocking-close line to open",
                () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);

        long started = System.nanoTime();
        sound.stopThread();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue("shutdown exceeded its strict deadline: " + elapsedMillis,
                elapsedMillis < 2_750);
        assertTrue("the deliberately stuck worker should remain isolated as a daemon",
                worker.isAlive() && worker.isDaemon());
        assertTrue("worker and emergency helper did not both reach blocking close",
                backend.closeAttempts.await(1, TimeUnit.SECONDS));
        assertTrue(backend.closeThreads.stream().allMatch(Thread::isDaemon));
        assertTrue(backend.closeThreads.stream()
                .anyMatch(thread -> thread.getName().equals(
                        "coffee-gb-audio-emergency-close")));
    }

    @Test
    public void unavailableDefaultStaysSilentAndStopsCleanly() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                backend,
                ignored -> {
                });

        Thread worker = sound.start();
        await("unavailable status",
                () -> sound.currentStatus().state() == AudioOutputStatus.State.UNAVAILABLE);
        assertNull(sound.currentStatus().activeDeviceId());
        eventBus.post(new Sound.SoundSampleEvent(constantSamples(400, 480)));

        sound.stopThread();
        assertFalse(worker.isAlive());
    }

    @Test
    public void runtimeConfigurationHasExplicitBoundsAndSafeDefaults() {
        AudioRuntimeConfiguration defaults = AudioRuntimeConfiguration.defaults();
        assertEquals("default", defaults.outputDeviceId());
        assertEquals(100, defaults.masterVolume());
        assertFalse(defaults.muted());
        assertEquals(
                AudioRuntimeConfiguration.LatencyPreset.BALANCED,
                defaults.latencyPreset());
        assertEquals(8192, defaults.latencyPreset().lineBufferBytes());
        assertEquals(3, defaults.latencyPreset().queuedFrames());
        assertEquals(4, AudioRuntimeConfiguration.LatencyPreset.LOW.runtimeQueueCapacity());
        assertEquals(12, defaults.latencyPreset().runtimeQueueCapacity());
        assertEquals(15, AudioRuntimeConfiguration.LatencyPreset.SAFE.runtimeQueueCapacity());

        assertInvalid(() -> new AudioRuntimeConfiguration(
                "array-index-0", 100, false,
                AudioRuntimeConfiguration.LatencyPreset.BALANCED));
        assertInvalid(() -> new AudioRuntimeConfiguration(
                "default", -1, false,
                AudioRuntimeConfiguration.LatencyPreset.BALANCED));
        assertInvalid(() -> new AudioRuntimeConfiguration(
                "default", 101, false,
                AudioRuntimeConfiguration.LatencyPreset.BALANCED));
    }

    private static byte[] render(AudioRuntimeConfiguration configuration) throws Exception {
        EventBusImpl eventBus = synchronousBus();
        AudioSystemSound sound = new AudioSystemSound(
                configuration,
                eventBus,
                null,
                new FakeBackend(),
                ignored -> {
                });
        eventBus.post(new Sound.SoundSampleEvent(constantSamples(2000, 480)));
        return queue(sound).remove();
    }

    private static int[] constantSamples(int ticks, int value) {
        int[] samples = new int[ticks * 2];
        Arrays.fill(samples, value);
        return samples;
    }

    private static int[] audibleSamples(int ticks) {
        int[] samples = new int[ticks * 2];
        for (int tick = 0; tick < ticks; tick++) {
            int value = ((tick / 32) & 1) == 0 ? 480 : -480;
            samples[tick * 2] = value;
            samples[tick * 2 + 1] = value;
        }
        return samples;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static int firstNonZeroLeftSample(byte[] bytes) {
        for (int offset = 0; offset + 3 < bytes.length; offset += 4) {
            int sample = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
            if (sample != 0) {
                return sample;
            }
        }
        return 0;
    }

    private static int[] toUnsigned(byte[] bytes) {
        int[] values = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            values[i] = bytes[i] & 0xff;
        }
        return values;
    }

    private static EventBusImpl synchronousBus() {
        return new EventBusImpl(null, null, false);
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<byte[]> queue(AudioSystemSound sound) throws Exception {
        Field field = AudioSystemSound.class.getDeclaredField("queue");
        field.setAccessible(true);
        return (BlockingQueue<byte[]>) field.get(sound);
    }

    private static void await(String description, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("Timed out waiting for " + description, condition.getAsBoolean());
    }

    private static void assertInvalid(Runnable construction) {
        try {
            construction.run();
            fail("Expected invalid audio configuration");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static final class FakeBackend implements AudioBackend {
        private final Map<String, AudioDeviceSnapshot> available = new ConcurrentHashMap<>();
        private final Map<String, FakeLine> lines = new ConcurrentHashMap<>();
        private final List<String> openedIds = new CopyOnWriteArrayList<>();
        private final List<String> operationThreads = new CopyOnWriteArrayList<>();
        private volatile String exclusivePreferredDevice;
        private volatile int exclusiveOpenConflicts;

        void add(String id, String name) {
            available.put(
                    id,
                    "default".equals(id)
                            ? AudioDeviceSnapshot.systemDefaultDevice()
                            : new AudioDeviceSnapshot(id, name, false));
        }

        void remove(String id) {
            available.remove(id);
        }

        FakeLine lastLine(String id) {
            return lines.get(id);
        }

        @Override
        public List<AudioDeviceSnapshot> devices() {
            return List.copyOf(available.values());
        }

        @Override
        public AudioLine open(String stableId, AudioFormat format, int bufferBytes)
                throws LineUnavailableException {
            operationThreads.add(Thread.currentThread().getName());
            if (!available.containsKey(stableId)) {
                throw new LineUnavailableException("missing " + stableId);
            }
            FakeLine fallback = lines.get(AudioDeviceSnapshot.SYSTEM_DEFAULT_ID);
            if (stableId.equals(exclusivePreferredDevice)
                    && fallback != null
                    && fallback.isOpen()) {
                exclusiveOpenConflicts++;
                throw new LineUnavailableException(
                        "preferred output is exclusive while fallback remains open");
            }
            FakeLine line = new FakeLine(bufferBytes, operationThreads);
            lines.put(stableId, line);
            openedIds.add(stableId);
            return line;
        }
    }

    private static final class FakeLine implements AudioBackend.AudioLine {
        private final int requestedBufferBytes;
        private final List<String> operationThreads;
        private volatile boolean open = true;
        private volatile boolean closed;
        private volatile boolean failWrites;
        private volatile int flushCount;
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();

        private FakeLine(int requestedBufferBytes, List<String> operationThreads) {
            this.requestedBufferBytes = requestedBufferBytes;
            this.operationThreads = operationThreads;
        }

        private void record() {
            operationThreads.add(Thread.currentThread().getName());
        }

        @Override
        public void start() {
            record();
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            record();
            if (failWrites) {
                throw new IllegalStateException("device disconnected");
            }
            writes.add(Arrays.copyOfRange(bytes, offset, offset + length));
            return length;
        }

        private int nonSilentWriteCount() {
            int count = 0;
            for (byte[] write : writes) {
                if (!allZero(write)) {
                    count++;
                }
            }
            return count;
        }

        private int firstNonSilentWriteAfter(int previous) {
            for (int index = previous + 1; index < writes.size(); index++) {
                if (!allZero(writes.get(index))) {
                    return index;
                }
            }
            return -1;
        }

        @Override
        public int available() {
            return requestedBufferBytes;
        }

        @Override
        public int bufferSize() {
            return requestedBufferBytes;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void flush() {
            record();
            flushCount++;
        }

        @Override
        public void stop() {
            record();
        }

        @Override
        public void close() {
            record();
            open = false;
            closed = true;
        }
    }

    private static final class BlockingCloseBackend implements AudioBackend {
        private final CountDownLatch closeAttempts = new CountDownLatch(2);
        private final CountDownLatch neverRelease = new CountDownLatch(1);
        private final List<Thread> closeThreads = new CopyOnWriteArrayList<>();

        @Override
        public List<AudioDeviceSnapshot> devices() {
            return List.of(AudioDeviceSnapshot.systemDefaultDevice());
        }

        @Override
        public AudioLine open(String stableId, AudioFormat format, int bufferBytes) {
            return new AudioLine() {
                @Override
                public void start() {
                }

                @Override
                public int write(byte[] bytes, int offset, int length) {
                    return length;
                }

                @Override
                public int available() {
                    return bufferBytes;
                }

                @Override
                public int bufferSize() {
                    return bufferBytes;
                }

                @Override
                public boolean isOpen() {
                    return true;
                }

                @Override
                public void flush() {
                }

                @Override
                public void stop() {
                }

                @Override
                public void close() {
                    closeThreads.add(Thread.currentThread());
                    closeAttempts.countDown();
                    while (true) {
                        try {
                            neverRelease.await();
                        } catch (InterruptedException ignored) {
                            // Simulate a permanently stuck provider that ignores cancellation.
                        }
                    }
                }
            };
        }
    }
}
