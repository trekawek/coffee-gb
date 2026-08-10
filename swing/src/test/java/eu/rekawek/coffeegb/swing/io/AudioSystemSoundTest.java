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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertArrayEquals;
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
    public void balancedRuntimeQueueAcceptsContinuityHeadroomAndBoundsOldestFrame()
            throws Exception {
        EventBusImpl eventBus = synchronousBus();
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                new FakeBackend(),
                ignored -> {
                });
        int ticks = ClockSpec.LEGACY.controllerTicksPerFrame();
        int capacity = AudioRuntimeConfiguration.LatencyPreset.BALANCED.runtimeQueueCapacity();

        for (int frame = 1; frame <= capacity; frame++) {
            eventBus.post(new Sound.SoundSampleEvent(constantSamples(ticks, frame * 480)));
        }
        assertEquals(24, capacity);
        assertEquals(capacity, queue(sound).size());
        List<byte[]> beforeOverflow = new ArrayList<>(queue(sound));

        eventBus.post(new Sound.SoundSampleEvent(constantSamples(ticks, (capacity + 1) * 480)));
        assertEquals("runtime queue must remain bounded", capacity, queue(sound).size());
        assertArrayEquals("overflow must discard the oldest real PCM frame",
                beforeOverflow.get(1), queue(sound).peek());
    }

    @Test
    public void balancedQueueCoversA324MillisecondScriptedWorkerStallWithoutSleeping()
            throws Exception {
        EventBusImpl eventBus = synchronousBus();
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                new FakeBackend(),
                ignored -> {
                });
        ClockSpec clock = ClockSpec.LEGACY;
        int stalledFrames = 20;
        ClockSpec.RateAccumulator elapsed = clock.newFrameNanosecondAccumulator();

        for (int frame = 0; frame < stalledFrames; frame++) {
            eventBus.post(new Sound.SoundSampleEvent(
                    constantSamples(clock.controllerTicksPerFrame(), 480)));
        }

        long scriptedStallNanos = elapsed.advance(stalledFrames);
        assertTrue(scriptedStallNanos >= TimeUnit.MILLISECONDS.toNanos(324));
        assertTrue(scriptedStallNanos < TimeUnit.MILLISECONDS.toNanos(400));
        assertTrue(stalledFrames <= AudioRuntimeConfiguration.LatencyPreset.BALANCED
                .runtimeQueueCapacity());
        assertEquals(stalledFrames, queue(sound).size());
    }

    @Test
    public void windowsPriorityIsAppliedBeforeStartWhileNonWindowsLeavesItUntouched() {
        Thread windowsWorker = new Thread();
        AtomicReference<Thread.State> stateDuringConfiguration = new AtomicReference<>();
        AtomicInteger requestedPriority = new AtomicInteger();

        AudioSystemSound.configureOwnedWorkerPriority(
                windowsWorker,
                "wInDoWs 11",
                (worker, priority) -> {
                    stateDuringConfiguration.set(worker.getState());
                    requestedPriority.set(priority);
                    worker.setPriority(priority);
                });

        assertEquals(Thread.State.NEW, stateDuringConfiguration.get());
        assertEquals(Thread.NORM_PRIORITY + 1, requestedPriority.get());
        assertEquals(Thread.NORM_PRIORITY + 1, windowsWorker.getPriority());
        assertTrue(AudioSystemSound.isWindows("Windows NT"));
        assertFalse(AudioSystemSound.isWindows("Linux"));

        Thread nonWindowsWorker = new Thread();
        nonWindowsWorker.setPriority(Thread.NORM_PRIORITY - 2);
        AtomicBoolean nonWindowsSetterCalled = new AtomicBoolean();

        AudioSystemSound.configureOwnedWorkerPriority(
                nonWindowsWorker,
                "Mac OS X",
                (worker, priority) -> nonWindowsSetterCalled.set(true));

        assertFalse("non-Windows worker priority must remain inherited", nonWindowsSetterCalled.get());
        assertEquals(Thread.NORM_PRIORITY - 2, nonWindowsWorker.getPriority());
    }

    @Test
    public void windowsOwnedWorkerStartsAsNamedDaemonAtRaisedPriority() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.add("default", "System Default");
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                backend,
                ignored -> {
                },
                "Windows 11");

        Thread worker = sound.start();
        try {
            await("Windows-priority output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            assertEquals("coffee-gb-audio-output", worker.getName());
            assertTrue(worker.isDaemon());
            assertEquals(Thread.NORM_PRIORITY + 1, worker.getPriority());
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void priorityFailureDoesNotPreventWindowsAudioWorkerStartup() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.add("default", "System Default");
        AtomicBoolean priorityAttempted = new AtomicBoolean();
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                backend,
                ignored -> {
                },
                "Windows 11",
                (worker, priority) -> {
                    priorityAttempted.set(true);
                    throw new SecurityException("priority denied");
                });

        Thread worker = sound.start();
        try {
            await("audio worker after a failed priority request",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            assertTrue(priorityAttempted.get());
            assertTrue(worker.isAlive());
        } finally {
            sound.stopThread();
        }
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
    public void runningLineStartsOnlyAtOpenNotAtPrimingOrEachRealBuffer() throws Exception {
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
        int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());
        int watermark = AudioRuntimeConfiguration.LatencyPreset.BALANCED.queuedFrames();

        sound.start();
        try {
            await("default output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            FakeLine line = backend.lastLine("default");
            assertEquals("openAndStart starts the fresh provider once", 1, line.startCount);

            for (int frame = 0; frame < watermark; frame++) {
                eventBus.post(new Sound.SoundSampleEvent(samples));
            }
            await("primed PCM batch", () -> line.nonSilentWriteCount() == watermark);
            assertEquals("priming must not restart a running line", 1, line.startCount);

            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("steady-state PCM frame", () -> line.nonSilentWriteCount() == watermark + 1);
            assertEquals("steady PCM must not restart a running line", 1, line.startCount);
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void windowsPublicRunningStateMayNeedOnePrimingStartButNotSteadyStarts()
            throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.runningBecomesPublicAfterFirstWrite = true;
        backend.add("default", "System Default");
        AudioSystemSound sound = new AudioSystemSound(
                AudioRuntimeConfiguration.defaults(),
                eventBus,
                null,
                backend,
                ignored -> {
                });
        int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());
        int watermark = AudioRuntimeConfiguration.LatencyPreset.BALANCED.queuedFrames();

        sound.start();
        try {
            await("Windows-like output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            FakeLine line = backend.lastLine("default");
            assertEquals(1, line.startCount);
            assertFalse("some providers do not report running until the first PCM write",
                    line.isRunning());

            for (int frame = 0; frame < watermark; frame++) {
                eventBus.post(new Sound.SoundSampleEvent(samples));
            }
            await("primed Windows-like PCM", () -> line.nonSilentWriteCount() == watermark);
            assertEquals("the false public state permits one idempotent priming start", 2,
                    line.startCount);
            assertTrue(line.isRunning());

            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("steady Windows-like PCM", () -> line.nonSilentWriteCount() == watermark + 1);
            assertEquals("steady PCM must not repeat native starts", 2, line.startCount);
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void stoppedLineRestartsBeforeRealPcmAndStartFailureFallsBack() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        FakeBackend backend = new FakeBackend();
        backend.add(DEVICE_A, "Output A");
        backend.add("default", "System Default");
        AudioSystemSound sound = new AudioSystemSound(
                new AudioRuntimeConfiguration(
                        DEVICE_A, 100, false,
                        AudioRuntimeConfiguration.LatencyPreset.BALANCED),
                eventBus,
                null,
                backend,
                ignored -> {
                });
        int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());
        int watermark = AudioRuntimeConfiguration.LatencyPreset.BALANCED.queuedFrames();

        sound.start();
        try {
            await("configured output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            FakeLine line = backend.lastLine(DEVICE_A);
            for (int frame = 0; frame < watermark; frame++) {
                eventBus.post(new Sound.SoundSampleEvent(samples));
            }
            await("initial real PCM", () -> line.nonSilentWriteCount() == watermark);

            line.running = false;
            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("stopped provider restart", () -> line.nonSilentWriteCount() == watermark + 1);
            assertEquals("the stopped provider must restart before writing real PCM", 2,
                    line.startCount);

            line.running = false;
            line.failStarts = true;
            backend.remove(DEVICE_A);
            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("restart failure fallback", () -> sound.currentStatus().state()
                    == AudioOutputStatus.State.FALLBACK);
            assertTrue("failed restart line must be closed", line.closed);
            assertEquals("default", sound.currentStatus().activeDeviceId());
            assertEquals(1, backend.lastLine("default").startCount);
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void workerCompletesPartialProviderWritesInOrderWithoutAvailabilityPolling()
            throws Exception {
        EventBusImpl eventBus = synchronousBus();
        PartialWriteBackend backend = new PartialWriteBackend(12);
        AudioRuntimeConfiguration configuration = new AudioRuntimeConfiguration(
                "default", 100, false, AudioRuntimeConfiguration.LatencyPreset.LOW);
        AudioSystemSound sound = new AudioSystemSound(
                configuration,
                eventBus,
                null,
                backend,
                ignored -> {
                });
        int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());
        byte[] expected = render(configuration, samples);

        sound.start();
        try {
            await("partial-write output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("complete PCM frame through partial writes",
                    () -> backend.line.writtenBytes() == expected.length);

            assertTrue("the provider must report more than one partial write",
                    backend.line.writeLengths.size() > 1);
            assertEquals("the initial provider call receives the whole PCM frame", expected.length,
                    (int) backend.line.requestLengths.get(0));
            assertEquals("writeFully must not second-guess provider availability", 0,
                    backend.line.availableCalls);
            assertArrayEquals(expected, backend.line.concatenatedWrites());
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void wholePcmBufferWriteObservationSpansBlockingPartialProviderWrites() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        PausingPartialWriteBackend backend = new PausingPartialWriteBackend(16);
        AudioRuntimeConfiguration configuration = new AudioRuntimeConfiguration(
                "default", 100, false, AudioRuntimeConfiguration.LatencyPreset.LOW);
        AudioSystemSound sound = new AudioSystemSound(
                configuration,
                eventBus,
                null,
                backend,
                ignored -> {
                });
        int[] samples = audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame());
        int expectedBytes = render(configuration, samples).length;

        sound.start();
        try {
            await("partial-write output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            eventBus.post(new Sound.SoundSampleEvent(samples));
            await("first PCM chunk before the provider blocks", backend.line::firstChunkWritten);

            assertTrue("the dequeued PCM buffer must remain visible between chunks",
                    sound.isPcmBufferWriteInProgressForTesting());
            backend.line.allowRemainingWrites();
            await("complete PCM buffer after availability resumes",
                    () -> backend.line.writtenBytes() == expectedBytes);
            await("completed PCM buffer observation to clear",
                    () -> !sound.isPcmBufferWriteInProgressForTesting());
        } finally {
            sound.stopThread();
        }
    }

    @Test
    public void zeroProgressProviderWriteReopensTheOutput() throws Exception {
        EventBusImpl eventBus = synchronousBus();
        PartialWriteBackend backend = new PartialWriteBackend(12, 1);
        AudioSystemSound sound = new AudioSystemSound(
                new AudioRuntimeConfiguration(
                        "default", 100, false, AudioRuntimeConfiguration.LatencyPreset.LOW),
                eventBus,
                null,
                backend,
                ignored -> {
                });

        sound.start();
        try {
            await("zero-progress output to open",
                    () -> sound.currentStatus().state() == AudioOutputStatus.State.ACTIVE);
            PartialWriteLine first = backend.line;
            eventBus.post(new Sound.SoundSampleEvent(
                    audibleSamples(ClockSpec.LEGACY.controllerTicksPerFrame())));
            await("zero-progress output to reopen", () -> backend.openCount() >= 2);
            assertTrue("the no-progress provider must be closed", first.closed);
            assertEquals(AudioOutputStatus.State.ACTIVE, sound.currentStatus().state());
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
        assertEquals(16384, first.requestedBufferBytes);

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
        assertEquals(32768, safe.requestedBufferBytes);
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
        assertEquals(16384, defaults.latencyPreset().lineBufferBytes());
        assertEquals(6, defaults.latencyPreset().queuedFrames());
        assertEquals(4, AudioRuntimeConfiguration.LatencyPreset.LOW.runtimeQueueCapacity());
        assertEquals(24, defaults.latencyPreset().runtimeQueueCapacity());
        assertEquals(32768, AudioRuntimeConfiguration.LatencyPreset.SAFE.lineBufferBytes());
        assertEquals(12, AudioRuntimeConfiguration.LatencyPreset.SAFE.queuedFrames());
        assertEquals(32, AudioRuntimeConfiguration.LatencyPreset.SAFE.runtimeQueueCapacity());

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
        return render(configuration, constantSamples(2000, 480));
    }

    private static byte[] render(AudioRuntimeConfiguration configuration, int[] samples)
            throws Exception {
        EventBusImpl eventBus = synchronousBus();
        AudioSystemSound sound = new AudioSystemSound(
                configuration,
                eventBus,
                null,
                new FakeBackend(),
                ignored -> {
                });
        eventBus.post(new Sound.SoundSampleEvent(samples));
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
        private volatile boolean runningBecomesPublicAfterFirstWrite;

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
            FakeLine line = new FakeLine(
                    bufferBytes, operationThreads, runningBecomesPublicAfterFirstWrite);
            lines.put(stableId, line);
            openedIds.add(stableId);
            return line;
        }
    }

    private static final class FakeLine implements AudioBackend.AudioLine {
        private final int requestedBufferBytes;
        private final List<String> operationThreads;
        private final boolean runningBecomesPublicAfterFirstWrite;
        private volatile boolean open = true;
        private volatile boolean running;
        private volatile boolean startRequested;
        private volatile boolean closed;
        private volatile boolean failWrites;
        private volatile boolean failStarts;
        private volatile int flushCount;
        private volatile int startCount;
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();

        private FakeLine(
                int requestedBufferBytes,
                List<String> operationThreads,
                boolean runningBecomesPublicAfterFirstWrite) {
            this.requestedBufferBytes = requestedBufferBytes;
            this.operationThreads = operationThreads;
            this.runningBecomesPublicAfterFirstWrite = runningBecomesPublicAfterFirstWrite;
        }

        private void record() {
            operationThreads.add(Thread.currentThread().getName());
        }

        @Override
        public void start() {
            record();
            startCount++;
            if (failStarts) {
                throw new IllegalStateException("provider failed to start");
            }
            startRequested = true;
            if (!runningBecomesPublicAfterFirstWrite) {
                running = true;
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            record();
            if (failWrites) {
                throw new IllegalStateException("device disconnected");
            }
            if (runningBecomesPublicAfterFirstWrite && startRequested) {
                running = true;
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
            running = false;
        }

        @Override
        public void close() {
            record();
            open = false;
            running = false;
            closed = true;
        }
    }

    private static final class PartialWriteBackend implements AudioBackend {
        private final int maximumProgressBytes;
        private int zeroProgressOpensRemaining;
        private final List<PartialWriteLine> lines = new CopyOnWriteArrayList<>();
        private volatile PartialWriteLine line;

        private PartialWriteBackend(int maximumProgressBytes) {
            this(maximumProgressBytes, 0);
        }

        private PartialWriteBackend(int maximumProgressBytes, int zeroProgressOpens) {
            this.maximumProgressBytes = maximumProgressBytes;
            this.zeroProgressOpensRemaining = zeroProgressOpens;
        }

        @Override
        public List<AudioDeviceSnapshot> devices() {
            return List.of(AudioDeviceSnapshot.systemDefaultDevice());
        }

        @Override
        public AudioLine open(String stableId, AudioFormat format, int bufferBytes)
                throws LineUnavailableException {
            if (!AudioDeviceSnapshot.SYSTEM_DEFAULT_ID.equals(stableId)) {
                throw new LineUnavailableException("missing " + stableId);
            }
            PartialWriteLine opened = new PartialWriteLine(
                    maximumProgressBytes, zeroProgressOpensRemaining-- > 0);
            lines.add(opened);
            line = opened;
            return opened;
        }

        private int openCount() {
            return lines.size();
        }
    }

    private static final class PartialWriteLine implements AudioBackend.AudioLine {
        private final int maximumProgressBytes;
        private final boolean zeroProgress;
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();
        private final List<Integer> requestLengths = new CopyOnWriteArrayList<>();
        private final List<Integer> writeLengths = new CopyOnWriteArrayList<>();
        private volatile int availableCalls;
        private volatile boolean running;
        private volatile boolean closed;

        private PartialWriteLine(int maximumProgressBytes, boolean zeroProgress) {
            this.maximumProgressBytes = maximumProgressBytes;
            this.zeroProgress = zeroProgress;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            requestLengths.add(length);
            if (zeroProgress) {
                return 0;
            }
            int written = Math.min(length, maximumProgressBytes);
            writeLengths.add(written);
            writes.add(Arrays.copyOfRange(bytes, offset, offset + written));
            return written;
        }

        private int writtenBytes() {
            return writeLengths.stream().mapToInt(Integer::intValue).sum();
        }

        private byte[] concatenatedWrites() {
            byte[] result = new byte[writtenBytes()];
            int offset = 0;
            for (byte[] write : writes) {
                System.arraycopy(write, 0, result, offset, write.length);
                offset += write.length;
            }
            return result;
        }

        @Override
        public int available() {
            availableCalls++;
            return 0;
        }

        @Override
        public int bufferSize() {
            return maximumProgressBytes;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void flush() {
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void close() {
            closed = true;
            running = false;
        }
    }

    private static final class PausingPartialWriteBackend implements AudioBackend {
        private final PausingPartialWriteLine line;

        private PausingPartialWriteBackend(int chunkBytes) {
            this.line = new PausingPartialWriteLine(chunkBytes);
        }

        @Override
        public List<AudioDeviceSnapshot> devices() {
            return List.of(AudioDeviceSnapshot.systemDefaultDevice());
        }

        @Override
        public AudioLine open(String stableId, AudioFormat format, int bufferBytes)
                throws LineUnavailableException {
            if (!AudioDeviceSnapshot.SYSTEM_DEFAULT_ID.equals(stableId)) {
                throw new LineUnavailableException("missing " + stableId);
            }
            return line;
        }
    }

    private static final class PausingPartialWriteLine implements AudioBackend.AudioLine {
        private final int chunkBytes;
        private final CountDownLatch firstChunk = new CountDownLatch(1);
        private final CountDownLatch allowRemainingWrites = new CountDownLatch(1);
        private volatile boolean firstWrite = true;
        private volatile boolean running;
        private volatile int writtenBytes;

        private PausingPartialWriteLine(int chunkBytes) {
            this.chunkBytes = chunkBytes;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            if (firstWrite) {
                firstWrite = false;
                int written = Math.min(length, chunkBytes);
                writtenBytes += written;
                firstChunk.countDown();
                return written;
            }
            try {
                allowRemainingWrites.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
            int written = Math.min(length, chunkBytes);
            writtenBytes += written;
            return written;
        }

        private boolean firstChunkWritten() {
            return firstChunk.getCount() == 0;
        }

        private void allowRemainingWrites() {
            allowRemainingWrites.countDown();
        }

        private int writtenBytes() {
            return writtenBytes;
        }

        @Override
        public int available() {
            return chunkBytes;
        }

        @Override
        public int bufferSize() {
            return chunkBytes;
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
            running = false;
        }

        @Override
        public void close() {
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
                public boolean isRunning() {
                    return true;
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
