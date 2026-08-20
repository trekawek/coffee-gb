package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationSink;
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings;
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.controller.state.RomPersistenceStore;
import eu.rekawek.coffeegb.controller.state.SessionPersistence;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.swing.SwingAudioOutputFactory;
import eu.rekawek.coffeegb.swing.SwingEmulator;
import org.junit.Assume;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * External-ROM probe for the Swing audio path used by the desktop application.
 *
 * <p>This intentionally opens the real Java Sound backend. It is therefore skipped before any
 * desktop/audio setup unless {@code -DharryPotterRom} is supplied. The shell harness is the
 * supported way to run it against a locally owned Harry Potter ROM.</p>
 */
public class HarryPotterIntroUiAudioTimingTest {

    private static final int INTRO_FRAMES = 1_800;
    private static final long SAMPLE_RATE = 44_100;
    private static final int BYTES_PER_STEREO_FRAME = 4;
    private static final long BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_STEREO_FRAME;
    private static final Duration OUTPUT_DELIVERY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration OUTPUT_QUIESCENCE = Duration.ofMillis(50);
    private static final String EMBEDDED_BATTERY_SAVE =
            "H4sIAAAAAAAEAO3BMQEAIAgAMKhAAksR0ceSmsGPY9vpXZkrAAAAgH8XAAAAYIgH3AhargAgAAA=";

    @Test
    public void measuresHarryPotterIntroThroughTheDesktopAudioPath() throws Exception {
        File romFile = requireRom();
        AudioRuntimeConfiguration.LatencyPreset latencyPreset = latencyPreset();
        boolean sampleLineOccupancy = sampleLineOccupancy();
        UiAudioMetrics metrics = new UiAudioMetrics(sampleLineOccupancy);
        MeasuringAudioBackend backend = new MeasuringAudioBackend(metrics, sampleLineOccupancy);
        EmulatorProperties properties = new EmulatorProperties();
        boolean rewindEnabled = rewindEnabled();
        Gameboy.BootstrapMode bootstrapMode = bootstrapMode();
        applyRewindEnabled(properties, rewindEnabled);
        applyBootstrapMode(properties, bootstrapMode);
        EventBusImpl rootBus = new EventBusImpl();
        List<EventBus> idleBuses = new ArrayList<>();
        AtomicReference<AudioSystemSound> audioReference = new AtomicReference<>();
        AtomicReference<SwingEmulator> emulatorReference = new AtomicReference<>();
        AtomicReference<JFrame> frameReference = new AtomicReference<>();
        CountDownLatch introFramesProduced = new CountDownLatch(1);
        AtomicBoolean pauseRequested = new AtomicBoolean();

        try {
            metrics.limitToSourceFrames(INTRO_FRAMES);
            SwingAudioOutputFactory audioFactory = (configuration, eventBus, callerId) -> {
                AudioSystemSound output = new AudioSystemSound(
                        new AudioRuntimeConfiguration(
                                configuration.outputDeviceId(),
                                configuration.masterVolume(),
                                configuration.muted(),
                                latencyPreset),
                        eventBus,
                        callerId,
                        backend,
                        ignored -> {
                        });
                audioReference.set(output);
                return output;
            };

            // Register this before SwingEmulator installs AudioSystemSound. Its timestamp therefore
            // precedes synchronous conversion and it can request a controller-safe pause exactly
            // after the 1,800th source frame.
            rootBus.register(event -> {
                if (metrics.onProducerEvent(event)
                        && pauseRequested.compareAndSet(false, true)) {
                    introFramesProduced.countDown();
                    rootBus.post(new Controller.PauseEmulationEvent());
                }
            }, Sound.SoundSampleEvent.class, "main");

            onEdt(() -> {
                JFrame frame = new JFrame("Coffee GB Harry Potter UI audio timing");
                SwingEmulator emulator = new SwingEmulator(
                        rootBus,
                        null,
                        properties,
                        () -> Controller.MobileAdapterConfiguration.syntheticOffline(),
                        MobileAdapterGuestConfigurationSink.NO_OP,
                        audioFactory);
                emulator.bind(frame, () -> true);
                frame.pack();
                frame.setVisible(true);
                emulatorReference.set(emulator);
                frameReference.set(frame);
                return null;
            });

            // SwingEmulator/BasicController owns the production "session" fork. These are the
            // remaining desktop pollers created by SwingGui before it activates a ROM; the active
            // "main" fork remains controller-owned and is created only during activation.
            idleBuses.add(rootBus.fork("desktop-state-ux"));
            idleBuses.add(rootBus.fork("desktop-debugger"));
            idleBuses.add(rootBus.fork("desktop-netplay"));
            idleBuses.add(rootBus.fork("desktop-mobile-adapter-configuration"));

            AudioSystemSound audio = requireAudio(audioReference);
            BlockingQueue<byte[]> workerQueue = workerQueue(audio);
            // Registrations are invoked in registration order. This observer is deliberately after
            // the output constructor, so it observes the real post-enqueue queue depth.
            rootBus.register(
                    event -> metrics.onFrontendQueueAfterSynchronousPlay(workerQueue.size()),
                    Sound.SoundSampleEvent.class,
                    "main");

            metrics.startCumulativeDeliveryAccounting();
            // The sole metrics reset is immediately before activation/frame zero. JFR starts
            // before the same boundary so the initial desktop startup overlap is observable.
            metrics.resetForMeasurement();
            Recording recording = startRecording();
            try {
                byte[] batteryData = loadBatteryData();
                RomPersistenceStore persistenceStore = (configuration, hashes) -> {
                    configuration.setBatteryData(batteryData).setSupportBatterySave(false);
                    return new SessionPersistence(null, null, null);
                };
                rootBus.post(new Controller.LoadRomEvent(
                        romFile, null, null, persistenceStore, null, false));

                // Production does not hold ROM activation for Java Sound. The status wait happens
                // only after submission, while the controller is already running frame zero.
                await("a real Java Sound line after ROM activation", () -> {
                    AudioOutputStatus.State state = audio.currentStatus().state();
                    return state == AudioOutputStatus.State.ACTIVE
                            || state == AudioOutputStatus.State.UNAVAILABLE;
                });
                if (audio.currentStatus().state() != AudioOutputStatus.State.ACTIVE) {
                    throw new IllegalStateException("Java Sound output was unavailable: "
                            + audio.currentStatus().detail());
                }

                if (!introFramesProduced.await(45, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for " + INTRO_FRAMES
                            + " full-intro audio source frames; observed "
                            + metrics.sourceFrameCount());
                }
                metrics.awaitProducedPcmAtWorker(audio, workerQueue, OUTPUT_DELIVERY_TIMEOUT);
            } finally {
                stopAndDump(recording);
            }

            metrics.printReport(latencyPreset);
            Thread audioWorker = audio.workerThreadForTesting();
            System.out.printf("Full intro audio worker priority: %s%n",
                    audioWorker == null ? "n/a" : Integer.toString(audioWorker.getPriority()));
            System.out.printf("Full intro rewind enabled: %s%n", rewindEnabled);
            System.out.printf("Full intro bootstrap mode: %s%n", bootstrapMode);
            if (metrics.postLimitSourceEvents() != 0) {
                throw new IllegalStateException("Controller pause leaked "
                        + metrics.postLimitSourceEvents()
                        + " source events beyond the full-intro window");
            }
        } finally {
            SwingEmulator emulator = emulatorReference.get();
            if (emulator != null) {
                emulator.stop();
            }
            JFrame frame = frameReference.get();
            if (frame != null) {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
            for (EventBus idleBus : idleBuses) {
                idleBus.close();
            }
            rootBus.close();
            properties.close();
        }
    }

    private static void measureFullIntro(UiAudioMetrics metrics, IntroFrameRunner runner) {
        // The sole window reset happens before frame zero. The cumulative PCM ledger is
        // deliberately not reset, so it represents every produced intro frame.
        metrics.resetForMeasurement();
        for (int frame = 0; frame < INTRO_FRAMES; frame++) {
            runner.run(frame);
        }
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<byte[]> workerQueue(AudioSystemSound output) {
        try {
            Field field = AudioSystemSound.class.getDeclaredField("queue");
            field.setAccessible(true);
            return (BlockingQueue<byte[]>) field.get(output);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to observe the audio worker queue", failure);
        }
    }

    private static AudioSystemSound requireAudio(AtomicReference<AudioSystemSound> audioReference) {
        AudioSystemSound audio = audioReference.get();
        if (audio == null) {
            throw new IllegalStateException("SwingEmulator did not create its audio output");
        }
        return audio;
    }

    private static Recording startRecording() throws Exception {
        String configuredPath = System.getProperty("harryPotterJfr");
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName("Coffee GB Harry Potter UI audio timing");
        recording.start();
        return recording;
    }

    private static void stopAndDump(Recording recording) throws IOException {
        if (recording == null) {
            return;
        }
        try (recording) {
            recording.stop();
            Path destination = Paths.get(System.getProperty("harryPotterJfr"));
            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            recording.dump(destination);
            System.out.println("Full intro JFR recording: " + destination.toAbsolutePath());
        }
    }

    private static AudioRuntimeConfiguration.LatencyPreset latencyPreset() {
        String configured = System.getProperty("harryPotterAudioLatency", "BALANCED");
        try {
            return AudioRuntimeConfiguration.LatencyPreset.valueOf(configured.trim().toUpperCase());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "harryPotterAudioLatency must be LOW, BALANCED, or SAFE: " + configured,
                    failure);
        }
    }

    private static boolean rewindEnabled() {
        return parseRewindEnabled(System.getProperty("harryPotterRewindEnabled", "true"));
    }

    private static boolean parseRewindEnabled(String configured) {
        if ("true".equalsIgnoreCase(configured)) {
            return true;
        }
        if ("false".equalsIgnoreCase(configured)) {
            return false;
        }
        throw new IllegalArgumentException(
                "harryPotterRewindEnabled must be true or false: " + configured);
    }

    private static Gameboy.BootstrapMode bootstrapMode() {
        return bootstrapMode(System.getProperty("harryPotterBootstrapMode"));
    }

    private static Gameboy.BootstrapMode bootstrapMode(String configured) {
        return configured == null ? Gameboy.BootstrapMode.SKIP : parseBootstrapMode(configured);
    }

    private static Gameboy.BootstrapMode parseBootstrapMode(String configured) {
        try {
            return Gameboy.BootstrapMode.valueOf(configured.trim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "harryPotterBootstrapMode must be one of "
                            + Arrays.toString(Gameboy.BootstrapMode.values())
                            + ": " + configured,
                    failure);
        }
    }

    private static boolean sampleLineOccupancy() {
        return sampleLineOccupancy(System.getProperty("harryPotterSampleLineOccupancy"));
    }

    private static boolean sampleLineOccupancy(String configured) {
        if (configured == null) {
            return false;
        }
        if ("true".equals(configured)) {
            return true;
        }
        if ("false".equals(configured)) {
            return false;
        }
        throw new IllegalArgumentException(
                "harryPotterSampleLineOccupancy must be true or false: " + configured);
    }

    private static void applyRewindEnabled(EmulatorProperties properties, boolean rewindEnabled) {
        properties.updateApplicationSettings(settings -> withRewindEnabled(settings, rewindEnabled));
    }

    private static void applyBootstrapMode(
            EmulatorProperties properties, Gameboy.BootstrapMode bootstrapMode) {
        properties.updateApplicationSettings(settings -> withBootstrapMode(settings, bootstrapMode));
    }

    private static ApplicationSettings withRewindEnabled(
            ApplicationSettings settings, boolean rewindEnabled) {
        ApplicationSettings.Saves saves = settings.getSaves();
        ApplicationSettings.Saves updatedSaves = saves.copy(
                saves.getDirectory(),
                saves.getPreviousDirectories(),
                saves.getBatterySavesEnabled(),
                rewindEnabled,
                saves.getRewindSeconds(),
                saves.getAutosavePolicy(),
                saves.getResumePolicy(),
                saves.getRewindMemoryMiB());
        return settings.copy(
                settings.getSchemaVersion(),
                settings.getGeneral(),
                settings.getDisplay(),
                settings.getAudio(),
                settings.getInput(),
                settings.getPeripherals(),
                updatedSaves,
                settings.getAdvanced(),
                settings.getDesktop());
    }

    private static ApplicationSettings withBootstrapMode(
            ApplicationSettings settings, Gameboy.BootstrapMode bootstrapMode) {
        ApplicationSettings.Advanced advanced = settings.getAdvanced();
        ApplicationSettings.Advanced updatedAdvanced = advanced.copy(
                advanced.getDmgGamesProfile(),
                advanced.getCgbGamesProfile(),
                bootstrapMode,
                advanced.getDatelSlotRom(),
                advanced.getFullChangerCharacter(),
                advanced.getExecutionMode());
        return settings.copy(
                settings.getSchemaVersion(),
                settings.getGeneral(),
                settings.getDisplay(),
                settings.getAudio(),
                settings.getInput(),
                settings.getPeripherals(),
                settings.getSaves(),
                updatedAdvanced,
                settings.getDesktop());
    }

    private static File requireRom() {
        String configured = System.getProperty("harryPotterRom");
        Assume.assumeTrue(
                "Set -DharryPotterRom=<absolute path> to run the UI audio timing probe",
                configured != null && !configured.isBlank());
        File rom = new File(configured);
        if (!rom.isFile()) {
            throw new IllegalArgumentException("ROM not found: " + rom);
        }
        return rom;
    }

    private static byte[] loadBatteryData() throws IOException {
        String configured = System.getProperty("harryPotterBatterySave");
        if (configured != null && !configured.isBlank()) {
            File save = new File(configured);
            if (!save.isFile()) {
                throw new IllegalArgumentException("Battery save not found: " + save);
            }
            byte[] data = Files.readAllBytes(save.toPath());
            System.out.printf("Harry Potter battery save: %s (%d bytes)%n",
                    save.getAbsolutePath(), data.length);
            return data;
        }
        byte[] compressed = Base64.getDecoder().decode(EMBEDDED_BATTERY_SAVE);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] data = input.readAllBytes();
            System.out.printf("Harry Potter battery save: embedded (%d bytes)%n", data.length);
            return data;
        }
    }

    private static void await(String description, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        if (!condition.getAsBoolean()) {
            throw new IllegalStateException("Timed out waiting for " + description);
        }
    }

    private static <T> T onEdt(ThrowingSupplier<T> supplier) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return supplier.get();
        }
        FutureTask<T> task = new FutureTask<>(supplier::get);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    @Test
    public void estimatesAudibleGapFromIdleTimeAfterPriorProviderWrite() {
        // The interval begins when the prior provider call returned. Its blocking wall time is
        // recorded separately and must not be interpreted as an inter-write/output gap.
        long tenMillisOfPcm = BYTES_PER_SECOND / 100;
        assertEquals(10_000_000L,
                GapAccounting.audibleGapNanos(20_000_000L, (int) tenMillisOfPcm));
        assertEquals(0L,
                GapAccounting.audibleGapNanos(5_000_000L, (int) tenMillisOfPcm));
    }

    @Test
    public void lineStartMetricsCountOnlyTheResetWindowAndRetainTheMaximumDuration() {
        UiAudioMetrics metrics = new UiAudioMetrics(() -> 0, null);

        metrics.onLineStart(10, 30);
        metrics.resetForMeasurement();
        metrics.onLineStart(100, 1_100);
        metrics.onLineStart(2_000, 2_500);

        assertEquals(2, metrics.lineStartCalls);
        assertEquals(1_000L, metrics.maximumLineStartBlockingNanos);

        metrics.resetForMeasurement();
        assertEquals(0, metrics.lineStartCalls);
        assertEquals(0L, metrics.maximumLineStartBlockingNanos);
    }

    @Test
    public void lineOccupancySamplingIsStrictAndDisabledByDefault() {
        assertFalse(sampleLineOccupancy(null));
        assertTrue(sampleLineOccupancy("true"));
        assertFalse(sampleLineOccupancy("false"));
        assertThrows(IllegalArgumentException.class, () -> sampleLineOccupancy("TRUE"));
        assertThrows(IllegalArgumentException.class, () -> sampleLineOccupancy(" false"));
    }

    @Test
    public void lineOccupancySamplingIsOptInWithoutChangingProviderWriteAccounting() {
        byte[] pcm = new byte[BYTES_PER_STEREO_FRAME];

        UiAudioMetrics unsampled = new UiAudioMetrics(false, () -> 0, null);
        unsampled.startCumulativeDeliveryAccounting();
        unsampled.resetForMeasurement();
        CountingAudioLine unsampledDelegate = new CountingAudioLine(100, 96, 0, 0, 0, 0);
        MeasuringAudioLine unsampledLine = new MeasuringAudioLine(
                unsampledDelegate, unsampled, false);
        unsampledLine.write(pcm, 0, pcm.length);
        unsampledLine.write(pcm, 0, pcm.length);

        assertEquals(0, unsampledDelegate.availableCalls);
        assertEquals(2, unsampledDelegate.writeCalls);
        assertEquals(2L * BYTES_PER_STEREO_FRAME, unsampled.delivery.workerBytes());
        assertEquals(2, unsampled.writeBlockingNanos.size());
        assertEquals("n/a", unsampled.minimumBufferedBeforeWriteSummary());
        assertEquals("n/a", unsampled.emptyLineWritesSummary());
        assertEquals("n/a", unsampled.audibleGapSummary());

        UiAudioMetrics sampled = new UiAudioMetrics(true, () -> 0, null);
        sampled.startCumulativeDeliveryAccounting();
        sampled.resetForMeasurement();
        CountingAudioLine sampledDelegate = new CountingAudioLine(100, 100, 96, 96, 0);
        MeasuringAudioLine sampledLine = new MeasuringAudioLine(sampledDelegate, sampled, true);
        sampledLine.write(pcm, 0, pcm.length);
        sampledLine.write(pcm, 0, pcm.length);

        assertEquals(4, sampledDelegate.availableCalls);
        assertEquals(2L * BYTES_PER_STEREO_FRAME, sampled.delivery.workerBytes());
        assertEquals(2, sampled.writeBlockingNanos.size());
        assertEquals(1, sampled.emptyLineWrites);
        assertEquals("0.000", sampled.minimumBufferedBeforeWriteSummary());
        assertEquals("1", sampled.emptyLineWritesSummary());

        UiAudioMetrics gapSample = new UiAudioMetrics(true, () -> 0, null);
        gapSample.startCumulativeDeliveryAccounting();
        gapSample.resetForMeasurement();
        gapSample.onLineWrite(100, 100, 96, pcm.length, 1, 1);
        gapSample.onLineWrite(100, 96, 96, pcm.length,
                TimeUnit.MILLISECONDS.toNanos(20) + 1, TimeUnit.MILLISECONDS.toNanos(20) + 1);
        assertEquals(1, gapSample.emptyLineWrites);
        assertEquals("19.977 / 19.977", gapSample.audibleGapSummary());
    }

    @Test
    public void parsesAndAppliesTheHarnessRewindSettingWithoutChangingOtherSettings() {
        assertTrue(parseRewindEnabled("TrUe"));
        assertFalse(parseRewindEnabled("false"));
        assertThrows(IllegalArgumentException.class, () -> parseRewindEnabled("enabled"));

        ApplicationSettings before = new ApplicationSettings();
        ApplicationSettings after = withRewindEnabled(before, false);

        assertFalse(after.getSaves().getRewindEnabled());
        assertEquals(before.getSaves().getDirectory(), after.getSaves().getDirectory());
        assertEquals(before.getSaves().getPreviousDirectories(), after.getSaves().getPreviousDirectories());
        assertEquals(before.getSaves().getBatterySavesEnabled(), after.getSaves().getBatterySavesEnabled());
        assertEquals(before.getSaves().getRewindSeconds(), after.getSaves().getRewindSeconds());
        assertEquals(before.getSaves().getAutosavePolicy(), after.getSaves().getAutosavePolicy());
        assertEquals(before.getSaves().getResumePolicy(), after.getSaves().getResumePolicy());
        assertEquals(before.getSaves().getRewindMemoryMiB(), after.getSaves().getRewindMemoryMiB());
        assertSame(before.getGeneral(), after.getGeneral());
        assertSame(before.getDisplay(), after.getDisplay());
        assertSame(before.getAudio(), after.getAudio());
        assertSame(before.getInput(), after.getInput());
        assertSame(before.getPeripherals(), after.getPeripherals());
        assertSame(before.getAdvanced(), after.getAdvanced());
        assertSame(before.getDesktop(), after.getDesktop());
    }

    @Test
    public void parsesAndAppliesTheHarnessBootstrapModeWithoutChangingOtherSettings() {
        assertEquals(Gameboy.BootstrapMode.SKIP, bootstrapMode(null));
        assertEquals(Gameboy.BootstrapMode.SKIP, parseBootstrapMode("SKIP"));
        assertEquals(Gameboy.BootstrapMode.FAST_FORWARD, parseBootstrapMode("FAST_FORWARD"));
        assertEquals(Gameboy.BootstrapMode.NORMAL, parseBootstrapMode("NORMAL"));
        assertThrows(IllegalArgumentException.class, () -> parseBootstrapMode("fast-forward"));

        ApplicationSettings before = new ApplicationSettings();
        ApplicationSettings after = withBootstrapMode(before, Gameboy.BootstrapMode.FAST_FORWARD);

        assertEquals(Gameboy.BootstrapMode.FAST_FORWARD, after.getAdvanced().getBootstrapMode());
        assertEquals(before.getAdvanced().getDmgGamesProfile(), after.getAdvanced().getDmgGamesProfile());
        assertEquals(before.getAdvanced().getCgbGamesProfile(), after.getAdvanced().getCgbGamesProfile());
        assertEquals(before.getAdvanced().getDatelSlotRom(), after.getAdvanced().getDatelSlotRom());
        assertEquals(before.getAdvanced().getFullChangerCharacter(),
                after.getAdvanced().getFullChangerCharacter());
        assertSame(before.getGeneral(), after.getGeneral());
        assertSame(before.getDisplay(), after.getDisplay());
        assertSame(before.getAudio(), after.getAudio());
        assertSame(before.getInput(), after.getInput());
        assertSame(before.getPeripherals(), after.getPeripherals());
        assertSame(before.getSaves(), after.getSaves());
        assertSame(before.getDesktop(), after.getDesktop());
    }

    @Test
    public void fullIntroWindowIncludesInitialFramesAndAnEarlyProducerGap() {
        ClockSpec clock = ClockSpec.LEGACY;
        UiAudioMetrics metrics = new UiAudioMetrics();
        metrics.limitToSourceFrames(INTRO_FRAMES);
        metrics.startCumulativeDeliveryAccounting();
        Sound.SoundSampleEvent event = new Sound.SoundSampleEvent(
                new int[clock.controllerTicksPerFrame() * 2], clock);
        long[] now = {1};
        measureFullIntro(metrics, frame -> {
            now[0] += TimeUnit.MILLISECONDS.toNanos(frame == 7 ? 76 : 16);
            metrics.onProducerEventAtNanos(event, now[0]);
        });

        assertEquals(INTRO_FRAMES - 1, metrics.producerIntervals.size());
        assertEquals(1, metrics.producerGapsOver50Ms);
        assertEquals(INTRO_FRAMES, metrics.sourceFrameCount());
        assertEquals(5_291_992L, metrics.delivery.expectedBytes());
        assertEquals(2_940L, metrics.delivery.largestChunkBytes());

        metrics.onProducerEventAtNanos(event, now[0] + TimeUnit.MILLISECONDS.toNanos(16));
        assertEquals(INTRO_FRAMES - 1, metrics.producerIntervals.size());
        assertEquals(1, metrics.postLimitSourceEvents());
        assertEquals(5_291_992L, metrics.delivery.expectedBytes());
    }

    @Test
    public void pairsProducerWallAndControllerCpuIntervalsByEndingFrame() {
        ClockSpec clock = ClockSpec.LEGACY;
        long millis = TimeUnit.MILLISECONDS.toNanos(1);
        long[] wallNanos = {10 * millis, 28 * millis, 98 * millis, 138 * millis, 168 * millis, 183 * millis};
        long[] cpuNanos = {100 * millis, 108 * millis, 168 * millis, 173 * millis, 203 * millis, 213 * millis};
        AtomicInteger wallReads = new AtomicInteger();
        AtomicInteger cpuReads = new AtomicInteger();
        UiAudioMetrics metrics = new UiAudioMetrics(
                () -> wallNanos[wallReads.getAndIncrement()],
                () -> cpuNanos[cpuReads.getAndIncrement()]);
        Sound.SoundSampleEvent event = new Sound.SoundSampleEvent(
                new int[clock.controllerTicksPerFrame() * 2], clock);

        metrics.startCumulativeDeliveryAccounting();
        metrics.resetForMeasurement();
        for (int frame = 0; frame < wallNanos.length; frame++) {
            metrics.onProducerEvent(event);
        }

        assertEquals(wallNanos.length, wallReads.get());
        assertEquals(cpuNanos.length, cpuReads.get());
        assertEquals(wallNanos.length - 1, metrics.producerIntervals.size());
        assertProducerInterval(metrics.producerIntervals.get(0), 1, 18 * millis, 8 * millis);
        assertProducerInterval(metrics.producerIntervals.get(1), 2, 70 * millis, 60 * millis);

        List<ProducerInterval> topIntervals = metrics.topProducerIntervals(5);
        assertEquals(5, topIntervals.size());
        assertProducerInterval(topIntervals.get(0), 2, 70 * millis, 60 * millis);
        assertProducerInterval(topIntervals.get(1), 3, 40 * millis, 5 * millis);
        assertProducerInterval(topIntervals.get(2), 4, 30 * millis, 30 * millis);
        assertProducerInterval(topIntervals.get(3), 1, 18 * millis, 8 * millis);
        assertProducerInterval(topIntervals.get(4), 5, 15 * millis, 10 * millis);
        assertEquals("2: 70.000/60.000 ms", metrics.maximumProducerIntervalSummary());
        assertEquals("2: 70.000/60.000 ms, 3: 40.000/5.000 ms, "
                        + "4: 30.000/30.000 ms, 1: 18.000/8.000 ms, 5: 15.000/10.000 ms",
                metrics.topProducerIntervalsSummary());

        UiAudioMetrics unavailableCpu = new UiAudioMetrics(() -> 1, null);
        unavailableCpu.startCumulativeDeliveryAccounting();
        unavailableCpu.resetForMeasurement();
        unavailableCpu.onProducerEventAtNanos(event, 1, -1);
        unavailableCpu.onProducerEventAtNanos(event, TimeUnit.MILLISECONDS.toNanos(20) + 1, -1);
        assertEquals("1: 20.000/n/a ms", unavailableCpu.maximumProducerIntervalSummary());
    }

    private static void assertProducerInterval(
            ProducerInterval interval, int endingSourceFrameIndex, long wallNanos, long cpuNanos) {
        assertEquals(endingSourceFrameIndex, interval.endingSourceFrameIndex());
        assertEquals(wallNanos, interval.wallNanos());
        assertEquals(cpuNanos, interval.controllerCpuNanos());
    }

    @Test
    public void cumulativeDeliveryAccountingDoesNotLetEarlierTailMaskLaterDrops() {
        ClockSpec clock = ClockSpec.LEGACY;
        CumulativePcmAccounting accounting = new CumulativePcmAccounting();
        for (int frame = 0; frame < 3; frame++) {
            accounting.onProducerEvent(clock, clock.controllerTicksPerFrame());
        }
        long earlierBytes = accounting.expectedBytes();

        // A prior queue tail reaches the worker after later producer frames. Cumulative accounting
        // must retain those matching earlier source bytes instead of masking the later loss.
        for (int frame = 0; frame < 3; frame++) {
            accounting.onProducerEvent(clock, clock.controllerTicksPerFrame());
        }
        long laterBytes = accounting.expectedBytes() - earlierBytes;
        accounting.onWorkerWrite(earlierBytes + laterBytes - 2L * 2_940);

        assertEquals(5_880L, accounting.droppedBytes());
        assertEquals("2", DropAccounting.frameEquivalent(
                accounting.droppedBytes(), accounting.largestChunkBytes()));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface IntroFrameRunner {
        void run(int frame);
    }

    /** Delegates to Java Sound and optionally samples the device's occupancy around each write. */
    private static final class MeasuringAudioBackend implements AudioBackend {
        private final AudioBackend delegate = new JavaSoundAudioBackend();
        private final UiAudioMetrics metrics;
        private final boolean sampleLineOccupancy;

        private MeasuringAudioBackend(UiAudioMetrics metrics, boolean sampleLineOccupancy) {
            this.metrics = metrics;
            this.sampleLineOccupancy = sampleLineOccupancy;
        }

        @Override
        public List<AudioDeviceSnapshot> devices() {
            return delegate.devices();
        }

        @Override
        public AudioLine open(String stableId, AudioFormat format, int bufferBytes)
                throws LineUnavailableException {
            return new MeasuringAudioLine(
                    delegate.open(stableId, format, bufferBytes), metrics, sampleLineOccupancy);
        }
    }

    private static final class MeasuringAudioLine implements AudioBackend.AudioLine {
        private final AudioBackend.AudioLine delegate;
        private final UiAudioMetrics metrics;
        private final boolean sampleLineOccupancy;
        private final int bufferBytes;

        private MeasuringAudioLine(
                AudioBackend.AudioLine delegate,
                UiAudioMetrics metrics,
                boolean sampleLineOccupancy) {
            this.delegate = delegate;
            this.metrics = metrics;
            this.sampleLineOccupancy = sampleLineOccupancy;
            this.bufferBytes = delegate.bufferSize();
        }

        @Override
        public void start() {
            long started = System.nanoTime();
            try {
                delegate.start();
            } finally {
                metrics.onLineStart(started, System.nanoTime());
            }
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            int availableBefore = sampleLineOccupancy ? delegate.available() : 0;
            long started = System.nanoTime();
            metrics.onLineWriteStarted(started);
            long completed = 0;
            try {
                int written = delegate.write(bytes, offset, length);
                // Preserve provider-call timing even when optional diagnostic sampling itself is
                // slow or perturbs a platform mixer.
                completed = System.nanoTime();
                int availableAfter = sampleLineOccupancy ? delegate.available() : 0;
                metrics.onLineWrite(
                        bufferBytes,
                        availableBefore,
                        availableAfter,
                        written,
                        started,
                        completed);
                return written;
            } finally {
                metrics.onLineWriteFinished(completed == 0 ? System.nanoTime() : completed);
            }
        }

        @Override
        public int available() {
            return delegate.available();
        }

        @Override
        public int bufferSize() {
            return delegate.bufferSize();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** Deterministic line used to prove diagnostic sampling does not touch the normal write path. */
    private static final class CountingAudioLine implements AudioBackend.AudioLine {
        private final int bufferBytes;
        private final int[] availability;
        private int availabilityIndex;
        private int availableCalls;
        private int writeCalls;

        private CountingAudioLine(int bufferBytes, int... availability) {
            this.bufferBytes = bufferBytes;
            this.availability = availability;
        }

        @Override
        public void start() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            writeCalls++;
            return length;
        }

        @Override
        public int available() {
            availableCalls++;
            if (availability.length == 0) {
                return bufferBytes;
            }
            int index = Math.min(availabilityIndex++, availability.length - 1);
            return availability[index];
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
        }
    }

    /** Measurement-only collector; all methods are synchronized across the emulation and worker threads. */
    private static final class UiAudioMetrics {
        private static final long CPU_TIME_UNAVAILABLE = -1;

        private final boolean sampleLineOccupancy;
        private final LongSupplier wallClock;
        private final LongSupplier controllerCpuClock;
        private final List<ProducerInterval> producerIntervals = new ArrayList<>();
        private final List<Long> lineWriteIntervalsNanos = new ArrayList<>();
        private final List<Long> writeBlockingNanos = new ArrayList<>();
        private volatile boolean measuring;
        private int sourceFrameLimit = Integer.MAX_VALUE;
        private int sourceFrameCount;
        private int postLimitSourceEvents;
        private long lastProducerNanos;
        private long lastProducerCpuNanos = CPU_TIME_UNAVAILABLE;
        private long lastPostWriteNanos;
        private long lastLineWriteCompletedNanos;
        private int lineWritesInProgress;
        private int lineStartCalls;
        private long maximumLineStartBlockingNanos;
        private int previousPostWriteOccupancyBytes;
        private final CumulativePcmAccounting delivery = new CumulativePcmAccounting();
        private int lineBufferBytes;
        private int producerGapsOver25Ms;
        private int producerGapsOver50Ms;
        private int emptyLineWrites;
        private int frontendQueuePeakFrames;
        private double totalAudibleGapMs;
        private double maximumAudibleGapMs;
        private double minimumBufferedBeforeWriteMs = Double.POSITIVE_INFINITY;
        private boolean cumulativeDeliveryAccounting;

        private UiAudioMetrics() {
            this(false, System::nanoTime, controllerThreadCpuClock());
        }

        private UiAudioMetrics(boolean sampleLineOccupancy) {
            this(sampleLineOccupancy, System::nanoTime, controllerThreadCpuClock());
        }

        private UiAudioMetrics(LongSupplier wallClock, LongSupplier controllerCpuClock) {
            this(false, wallClock, controllerCpuClock);
        }

        private UiAudioMetrics(
                boolean sampleLineOccupancy,
                LongSupplier wallClock,
                LongSupplier controllerCpuClock) {
            this.sampleLineOccupancy = sampleLineOccupancy;
            this.wallClock = wallClock;
            this.controllerCpuClock = controllerCpuClock;
        }

        synchronized void startCumulativeDeliveryAccounting() {
            cumulativeDeliveryAccounting = true;
        }

        synchronized void limitToSourceFrames(int sourceFrameLimit) {
            if (sourceFrameLimit <= 0) {
                throw new IllegalArgumentException("Source frame limit must be positive");
            }
            this.sourceFrameLimit = sourceFrameLimit;
        }

        synchronized void resetForMeasurement() {
            measuring = false;
            producerIntervals.clear();
            lineWriteIntervalsNanos.clear();
            writeBlockingNanos.clear();
            sourceFrameCount = 0;
            postLimitSourceEvents = 0;
            lastProducerNanos = 0;
            lastProducerCpuNanos = CPU_TIME_UNAVAILABLE;
            lastPostWriteNanos = 0;
            previousPostWriteOccupancyBytes = 0;
            lineStartCalls = 0;
            maximumLineStartBlockingNanos = 0;
            lineBufferBytes = 0;
            producerGapsOver25Ms = 0;
            producerGapsOver50Ms = 0;
            emptyLineWrites = 0;
            frontendQueuePeakFrames = 0;
            totalAudibleGapMs = 0;
            maximumAudibleGapMs = 0;
            minimumBufferedBeforeWriteMs = Double.POSITIVE_INFINITY;
            measuring = true;
        }

        synchronized boolean onProducerEvent(Sound.SoundSampleEvent event) {
            long wallNanos = wallClock.getAsLong();
            long controllerCpuNanos = controllerCpuClock == null
                    ? CPU_TIME_UNAVAILABLE
                    : controllerCpuClock.getAsLong();
            return onProducerEventAtNanos(event, wallNanos, controllerCpuNanos);
        }

        synchronized boolean onProducerEventAtNanos(Sound.SoundSampleEvent event, long now) {
            return onProducerEventAtNanos(event, now, CPU_TIME_UNAVAILABLE);
        }

        synchronized boolean onProducerEventAtNanos(
                Sound.SoundSampleEvent event, long now, long controllerCpuNanos) {
            if (!cumulativeDeliveryAccounting) {
                return false;
            }
            if (sourceFrameCount >= sourceFrameLimit) {
                postLimitSourceEvents++;
                return false;
            }
            sourceFrameCount++;
            int ticks = event.buffer().length / 2;
            delivery.onProducerEvent(event.clockSpec(), ticks);
            if (!measuring) {
                return sourceFrameCount == sourceFrameLimit;
            }
            if (lastProducerNanos != 0) {
                long interval = now - lastProducerNanos;
                long controllerCpuInterval = lastProducerCpuNanos == CPU_TIME_UNAVAILABLE
                        || controllerCpuNanos == CPU_TIME_UNAVAILABLE
                        ? CPU_TIME_UNAVAILABLE
                        : Math.max(0, controllerCpuNanos - lastProducerCpuNanos);
                producerIntervals.add(new ProducerInterval(
                        sourceFrameCount - 1, interval, controllerCpuInterval));
                if (interval > TimeUnit.MILLISECONDS.toNanos(25)) {
                    producerGapsOver25Ms++;
                }
                if (interval > TimeUnit.MILLISECONDS.toNanos(50)) {
                    producerGapsOver50Ms++;
                }
            }
            lastProducerNanos = now;
            lastProducerCpuNanos = controllerCpuNanos;
            return sourceFrameCount == sourceFrameLimit;
        }

        synchronized void onFrontendQueueAfterSynchronousPlay(int queuedFrames) {
            if (measuring) {
                frontendQueuePeakFrames = Math.max(frontendQueuePeakFrames, queuedFrames);
            }
        }

        synchronized void onLineWrite(
                int bufferBytes,
                int availableBefore,
                int availableAfter,
                int written,
                long started,
                long completed) {
            if (!cumulativeDeliveryAccounting || written <= 0) {
                return;
            }
            delivery.onWorkerWrite(written);
            if (!measuring) {
                return;
            }
            lineBufferBytes = Math.max(lineBufferBytes, bufferBytes);
            if (lastPostWriteNanos != 0) {
                long interval = Math.max(0, started - lastPostWriteNanos);
                lineWriteIntervalsNanos.add(interval);
            }
            writeBlockingNanos.add(Math.max(0, completed - started));
            if (sampleLineOccupancy) {
                int occupancyBefore = Math.max(0, bufferBytes - availableBefore);
                int occupancyAfter = Math.max(0, bufferBytes - availableAfter);
                minimumBufferedBeforeWriteMs = Math.min(
                        minimumBufferedBeforeWriteMs, millisecondsForBytes(occupancyBefore));
                if (lastPostWriteNanos != 0) {
                    long interval = Math.max(0, started - lastPostWriteNanos);
                    long gapNanos = GapAccounting.audibleGapNanos(
                            interval, previousPostWriteOccupancyBytes);
                    double gapMs = gapNanos / 1_000_000.0;
                    totalAudibleGapMs += gapMs;
                    maximumAudibleGapMs = Math.max(maximumAudibleGapMs, gapMs);
                    // Java Sound providers can report a single stereo frame as still occupied
                    // while the DAC has effectively drained. Treat that four-byte rounding
                    // residue as an empty line, matching the historic desktop interpretation.
                    if (availableBefore >= bufferBytes - BYTES_PER_STEREO_FRAME) {
                        emptyLineWrites++;
                    }
                }
                previousPostWriteOccupancyBytes = occupancyAfter;
            }
            lastPostWriteNanos = completed;
            notifyAll();
        }

        /** Counts only starts issued after the frame-zero measurement reset. */
        synchronized void onLineStart(long started, long completed) {
            if (!measuring) {
                return;
            }
            lineStartCalls++;
            maximumLineStartBlockingNanos = Math.max(
                    maximumLineStartBlockingNanos, Math.max(0, completed - started));
        }

        synchronized void onLineWriteStarted(long started) {
            if (!cumulativeDeliveryAccounting) {
                return;
            }
            lineWritesInProgress++;
            notifyAll();
        }

        synchronized void onLineWriteFinished(long completed) {
            if (!cumulativeDeliveryAccounting) {
                return;
            }
            if (lineWritesInProgress <= 0) {
                throw new IllegalStateException("Unbalanced audio line-write accounting");
            }
            lineWritesInProgress--;
            lastLineWriteCompletedNanos = completed;
            notifyAll();
        }

        synchronized void awaitProducedPcmAtWorker(
                AudioSystemSound output,
                BlockingQueue<byte[]> workerQueue,
                Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!deliveryReached(output, workerQueue) && System.nanoTime() < deadline) {
                long remaining = Math.min(
                        Math.max(1, deadline - System.nanoTime()),
                        nanosUntilDeliveryCanBeQuiescent(output, workerQueue));
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            if (!deliveryReached(output, workerQueue)) {
                throw new IllegalStateException("Audio worker delivery timed out: "
                        + delivery.workerBytes() + " worker PCM bytes (expected "
                        + delivery.expectedBytes()
                        + "), queue empty=" + workerQueue.isEmpty()
                        + ", final source/write timestamps=" + lastProducerNanos
                        + "/" + lastPostWriteNanos + ", line writes in progress="
                        + lineWritesInProgress + ", whole PCM buffer write in progress="
                        + output.isPcmBufferWriteInProgressForTesting()
                        + " after " + timeout.toMillis() + " ms");
            }
            measuring = false;
        }

        private boolean deliveryReached(AudioSystemSound output, BlockingQueue<byte[]> workerQueue) {
            // A short frontend queue can legitimately drop PCM. After the final producer event,
            // wait for a line write that began/completed after it, no in-flight provider call or
            // worker-local dequeued buffer, and a short quiet interval. This prevents an older
            // write completing after the source timestamp from racing an already-polled final
            // buffer that has not entered its blocking provider call yet.
            return workerQueue.isEmpty()
                    && lastProducerNanos != 0
                    && lastPostWriteNanos >= lastProducerNanos
                    && lineWritesInProgress == 0
                    && !output.isPcmBufferWriteInProgressForTesting()
                    && System.nanoTime() - lastLineWriteCompletedNanos >= OUTPUT_QUIESCENCE.toNanos();
        }

        private long nanosUntilDeliveryCanBeQuiescent(
                AudioSystemSound output, BlockingQueue<byte[]> workerQueue) {
            if (lineWritesInProgress != 0) {
                // MeasuringAudioLine notifies on provider-call completion. Do not add an
                // independent 1 ms wakeup loop while that native call is intentionally blocking.
                return OUTPUT_DELIVERY_TIMEOUT.toNanos();
            }
            if (!workerQueue.isEmpty()
                    || lastProducerNanos == 0
                    || lastPostWriteNanos < lastProducerNanos
                    || output.isPcmBufferWriteInProgressForTesting()) {
                // This is only the short queue-to-worker handoff before the provider call has
                // started (or after it returned and before the ownership flag clears).
                return TimeUnit.MILLISECONDS.toNanos(10);
            }
            return Math.max(1, lastLineWriteCompletedNanos + OUTPUT_QUIESCENCE.toNanos()
                    - System.nanoTime());
        }

        synchronized void printReport(AudioRuntimeConfiguration.LatencyPreset latencyPreset) {
            System.out.printf("Full intro UI audio timing preset: %s%n", latencyPreset);
            System.out.printf("Full intro source frames observed/target/post-limit: %d / %d / %d%n",
                    sourceFrameCount, sourceFrameLimit, postLimitSourceEvents);
            System.out.printf("Full intro audio frontend expected/worker PCM bytes: %d / %d%n",
                    delivery.expectedBytes(), delivery.workerBytes());
            long droppedBytes = delivery.droppedBytes();
            System.out.printf("Full intro audio frontend dropped PCM bytes/ms/frames: %d / %.3f / %s%n",
                    droppedBytes,
                    millisecondsForBytes(droppedBytes),
                    DropAccounting.frameEquivalent(droppedBytes, delivery.largestChunkBytes()));
            System.out.printf("Full intro audio frontend queue peak/capacity frames: %d / %d%n",
                    frontendQueuePeakFrames, latencyPreset.runtimeQueueCapacity());
            // The audio worker can open before frame zero. This deliberately reports only starts
            // after reset/activation, which exposes redundant priming and steady-state starts.
            System.out.printf("Full intro audio line start calls (after frame zero): %d%n",
                    lineStartCalls);
            System.out.printf("Full intro audio line max start blocking ms (after frame zero): %.3f%n",
                    maximumLineStartBlockingNanos / 1_000_000.0);
            System.out.printf("Full intro audio line buffer bytes/ms: %d / %.3f%n",
                    lineBufferBytes, millisecondsForBytes(lineBufferBytes));
            System.out.printf("Full intro audio line occupancy sampling: %s%n", sampleLineOccupancy);
            System.out.printf("Full intro producer event interval p50/p95/p99/max ms: %s%n",
                    producerPercentileSummary(producerIntervals));
            System.out.printf("Full intro producer max wall/controller CPU interval ms: %s%n",
                    maximumProducerIntervalSummary());
            System.out.printf("Full intro producer top 5 wall intervals frame: wall/controller CPU ms: %s%n",
                    topProducerIntervalsSummary());
            System.out.printf("Full intro audio gaps >25ms/>50ms: %d / %d%n",
                    producerGapsOver25Ms, producerGapsOver50Ms);
            System.out.printf("Full intro audio line provider write return-to-next-call interval p50/p95/p99/max ms: %s%n",
                    percentileSummary(lineWriteIntervalsNanos));
            System.out.printf("Full intro audio line max provider write blocking ms: %.3f%n",
                    maximumMillis(writeBlockingNanos));
            System.out.printf("Full intro audio line minimum buffered before provider write ms: %s%n",
                    minimumBufferedBeforeWriteSummary());
            System.out.printf("Full intro audio underruns (empty-line writes after priming): %s%n",
                    emptyLineWritesSummary());
            System.out.printf("Full intro audio gap estimate total/max ms: %s%n",
                    audibleGapSummary());
        }

        private String minimumBufferedBeforeWriteSummary() {
            return sampleLineOccupancy
                    ? String.format("%.3f", Double.isInfinite(minimumBufferedBeforeWriteMs)
                            ? 0 : minimumBufferedBeforeWriteMs)
                    : "n/a";
        }

        private String emptyLineWritesSummary() {
            return sampleLineOccupancy ? Integer.toString(emptyLineWrites) : "n/a";
        }

        private String audibleGapSummary() {
            return sampleLineOccupancy
                    ? String.format("%.3f / %.3f", totalAudibleGapMs, maximumAudibleGapMs)
                    : "n/a";
        }

        synchronized int sourceFrameCount() {
            return sourceFrameCount;
        }

        synchronized int postLimitSourceEvents() {
            return postLimitSourceEvents;
        }

        private static double millisecondsForBytes(long bytes) {
            return bytes * 1_000.0 / BYTES_PER_SECOND;
        }

        private static double maximumMillis(List<Long> nanos) {
            return nanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000.0;
        }

        private List<ProducerInterval> topProducerIntervals(int maximumEntries) {
            List<ProducerInterval> top = new ArrayList<>(producerIntervals);
            top.sort(Comparator.comparingLong(ProducerInterval::wallNanos)
                    .reversed()
                    .thenComparingInt(ProducerInterval::endingSourceFrameIndex));
            return top.subList(0, Math.min(maximumEntries, top.size()));
        }

        private String maximumProducerIntervalSummary() {
            List<ProducerInterval> top = topProducerIntervals(1);
            return top.isEmpty() ? "n/a" : formatProducerInterval(top.get(0));
        }

        private String topProducerIntervalsSummary() {
            List<ProducerInterval> top = topProducerIntervals(5);
            if (top.isEmpty()) {
                return "n/a";
            }
            StringBuilder summary = new StringBuilder();
            for (ProducerInterval interval : top) {
                if (summary.length() != 0) {
                    summary.append(", ");
                }
                summary.append(formatProducerInterval(interval));
            }
            return summary.toString();
        }

        private static String formatProducerInterval(ProducerInterval interval) {
            return String.format("%d: %.3f/%s ms",
                    interval.endingSourceFrameIndex(),
                    interval.wallNanos() / 1_000_000.0,
                    interval.controllerCpuNanos() == CPU_TIME_UNAVAILABLE
                            ? "n/a"
                            : String.format("%.3f", interval.controllerCpuNanos() / 1_000_000.0));
        }

        private static String producerPercentileSummary(List<ProducerInterval> intervals) {
            long[] wallIntervals = new long[intervals.size()];
            for (int i = 0; i < intervals.size(); i++) {
                wallIntervals[i] = intervals.get(i).wallNanos();
            }
            return percentileSummary(wallIntervals);
        }

        private static String percentileSummary(List<Long> values) {
            if (values.isEmpty()) {
                return "n/a";
            }
            long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
            return percentileSummary(sorted);
        }

        private static String percentileSummary(long[] sorted) {
            if (sorted.length == 0) {
                return "n/a";
            }
            Arrays.sort(sorted);
            return String.format("%.3f / %.3f / %.3f / %.3f",
                    percentileMillis(sorted, 0.50), percentileMillis(sorted, 0.95),
                    percentileMillis(sorted, 0.99), sorted[sorted.length - 1] / 1_000_000.0);
        }

        private static double percentileMillis(long[] sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1_000_000.0;
        }

        private static LongSupplier controllerThreadCpuClock() {
            ThreadMXBean threadMxBean;
            try {
                threadMxBean = ManagementFactory.getThreadMXBean();
                if (!threadMxBean.isCurrentThreadCpuTimeSupported()) {
                    return null;
                }
                if (!threadMxBean.isThreadCpuTimeEnabled()) {
                    threadMxBean.setThreadCpuTimeEnabled(true);
                }
            } catch (SecurityException | UnsupportedOperationException ignored) {
                return null;
            }
            return () -> {
                try {
                    return threadMxBean.getCurrentThreadCpuTime();
                } catch (UnsupportedOperationException ignored) {
                    return CPU_TIME_UNAVAILABLE;
                }
            };
        }
    }

    private record ProducerInterval(int endingSourceFrameIndex, long wallNanos, long controllerCpuNanos) {
    }

    /** Mirrors StereoPcmConverter's fractional sample accumulator without rendering samples. */
    private static final class PcmExpectation {
        private ClockSpec activeClock;
        private ClockSpec.RateAccumulator sampleAccumulator;

        long accept(ClockSpec clock, int ticks) {
            if (activeClock == null || !activeClock.equals(clock)) {
                activeClock = clock;
                sampleAccumulator = clock.newTickRateAccumulator(SAMPLE_RATE);
            }
            return Math.multiplyExact(sampleAccumulator.advance(ticks), BYTES_PER_STEREO_FRAME);
        }
    }

    /** PCM accounting deliberately spans the entire intro window. */
    private static final class CumulativePcmAccounting {
        private final PcmExpectation expectation = new PcmExpectation();
        private long expectedBytes;
        private long workerBytes;
        private long largestChunkBytes;

        void onProducerEvent(ClockSpec clock, int ticks) {
            long bytes = expectation.accept(clock, ticks);
            expectedBytes = Math.addExact(expectedBytes, bytes);
            largestChunkBytes = Math.max(largestChunkBytes, bytes);
        }

        void onWorkerWrite(long bytes) {
            workerBytes = Math.addExact(workerBytes, bytes);
        }

        long expectedBytes() {
            return expectedBytes;
        }

        long workerBytes() {
            return workerBytes;
        }

        long largestChunkBytes() {
            return largestChunkBytes;
        }

        long droppedBytes() {
            return DropAccounting.droppedBytes(expectedBytes, workerBytes);
        }
    }

    private static final class DropAccounting {
        private DropAccounting() {
        }

        static long droppedBytes(long expectedBytes, long workerBytes) {
            return Math.max(0, expectedBytes - workerBytes);
        }

        static String frameEquivalent(long droppedBytes, long chunkBytes) {
            if (chunkBytes <= 0) {
                return "n/a";
            }
            if (droppedBytes % chunkBytes == 0) {
                return Long.toString(droppedBytes / chunkBytes);
            }
            return String.format("%.3f", droppedBytes * 1.0 / chunkBytes);
        }
    }

    /** Pure byte-rate calculation kept separate so its interpretation has a deterministic test. */
    private static final class GapAccounting {
        private GapAccounting() {
        }

        static long audibleGapNanos(long elapsedNanos, int priorBufferedBytes) {
            long bufferedNanos = priorBufferedBytes * 1_000_000_000L / BYTES_PER_SECOND;
            return Math.max(0, elapsedNanos - bufferedNanos);
        }
    }
}
