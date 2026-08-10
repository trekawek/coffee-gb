package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.TimingTicker;
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Assume;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import static org.junit.Assert.assertEquals;

/**
 * External-ROM probe for the Swing audio path used by the desktop application.
 *
 * <p>This intentionally opens the real Java Sound backend. It is therefore skipped before any
 * desktop/audio setup unless {@code -DharryPotterRom} is supplied. The shell harness is the
 * supported way to run it against a locally owned Harry Potter ROM.</p>
 */
public class HarryPotterIntroUiAudioTimingTest {

    private static final int WARMUP_FRAMES = 1_200;
    private static final int MEASUREMENT_FRAMES = 600;
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
        UiAudioMetrics metrics = new UiAudioMetrics();
        MeasuringAudioBackend backend = new MeasuringAudioBackend(metrics);
        EmulatorProperties properties = new EmulatorProperties();
        EventBusImpl rootBus = new EventBusImpl(null, null, false);
        EventBusImpl mainBus = rootBus.fork("main");
        SwingDisplay display = null;
        Thread displayThread = null;
        AudioSystemSound audio = null;

        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setBatteryData(loadBatteryData())
                .setSupportBatterySave(false)
                .build()) {
            display = onEdt(() -> new SwingDisplay(properties.getDisplay(), mainBus, "main"));
            displayThread = new Thread(display, "coffee-gb-harry-potter-display");
            displayThread.setDaemon(true);
            displayThread.start();

            // Register this first so a producer timestamp precedes the synchronous PCM conversion
            // and a very fast device write cannot appear to predate its source event.
            mainBus.register(metrics::onProducerEvent, Sound.SoundSampleEvent.class, "main");
            AudioSystemSound output = new AudioSystemSound(
                    new AudioRuntimeConfiguration(
                            AudioDeviceSnapshot.SYSTEM_DEFAULT_ID,
                            100,
                            false,
                            latencyPreset),
                    mainBus,
                    "main",
                    backend,
                    ignored -> {
            });
            audio = output;
            BlockingQueue<byte[]> workerQueue = workerQueue(output);
            // EventBusImpl dispatches registrations in order. This observer is intentionally
            // registered after AudioSystemSound so queue.size() sees the synchronous play/enqueue
            // result rather than the depth before PCM conversion.
            mainBus.register(
                    event -> metrics.onFrontendQueueAfterSynchronousPlay(workerQueue.size()),
                    Sound.SoundSampleEvent.class,
                    "main");
            output.start();
            await("a real Java Sound line", () -> {
                AudioOutputStatus.State state = output.currentStatus().state();
                return state == AudioOutputStatus.State.ACTIVE
                        || state == AudioOutputStatus.State.UNAVAILABLE;
            });
            if (output.currentStatus().state() != AudioOutputStatus.State.ACTIVE) {
                throw new IllegalStateException("Java Sound output was unavailable: "
                        + output.currentStatus().detail());
            }

            metrics.startCumulativeDeliveryAccounting();
            gameboy.init(mainBus, SerialEndpoint.NULL_ENDPOINT, null);

            // Recording.start() can briefly retransform classes. Include that one-off host pause
            // in warmup so its line gap cannot be misattributed to the measured game workload.
            Recording recording = startRecording();
            try {
                TimingTicker ticker = new TimingTicker();
                ClockSpec clockSpec = gameboy.getClockSpec();
                ClockSpec.RateAccumulator frameNanos = clockSpec.newFrameNanosecondAccumulator();
                for (int frame = 0; frame < WARMUP_FRAMES; frame++) {
                    runControllerFrame(gameboy, ticker, clockSpec, frameNanos.advance(1), null);
                }

                // Keep the worker and its device buffer in their normal steady state. Startup
                // priming and optional JFR startup both happened during paced warmup; deliberately
                // draining here would create an artificial empty-line write at measurement start.
                metrics.resetForMeasurement();
                for (int frame = 0; frame < MEASUREMENT_FRAMES; frame++) {
                    long nominalNanos = frameNanos.advance(1);
                    runControllerFrame(gameboy, ticker, clockSpec, nominalNanos, metrics);
                }
                metrics.awaitProducedPcmAtWorker(workerQueue, OUTPUT_DELIVERY_TIMEOUT);
            } finally {
                stopAndDump(recording);
            }

            metrics.printReport(latencyPreset);
            if (forceFrameSkip()) {
                System.out.println("Forced frame suppression: enabled");
            }
        } finally {
            if (audio != null) {
                audio.stopThread();
            }
            if (display != null) {
                display.stop();
            }
            if (displayThread != null) {
                displayThread.join(TimeUnit.SECONDS.toMillis(2));
            }
            mainBus.close();
            rootBus.close();
            properties.close();
        }
    }

    private static void runControllerFrame(
            Gameboy gameboy,
            TimingTicker ticker,
            ClockSpec clockSpec,
            long nominalNanos,
            UiAudioMetrics metrics) {
        gameboy.requestFrameRenderSuppression(
                forceFrameSkip() || ticker.getHasPacingDebt$controller());

        long workStarted = System.nanoTime();
        int ticks = clockSpec.controllerTicksPerFrame();
        for (int tick = 0; tick < ticks; tick++) {
            gameboy.tick();
            // The final ticker call is the controller's sleep/yield boundary. Measure only work
            // that can starve the producer, exactly as BasicController orders this cadence.
            if (tick + 1 < ticks) {
                ticker.run(clockSpec);
            }
        }
        long workNanos = System.nanoTime() - workStarted;
        ticker.run(clockSpec);
        if (metrics != null) {
            metrics.onControllerFrame(
                    workNanos, nominalNanos, ticker.getHasPacingDebt$controller());
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
            System.out.println("JFR measurement recording: " + destination.toAbsolutePath());
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

    private static boolean forceFrameSkip() {
        return Boolean.getBoolean("harryPotterForceFrameSkip");
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
    public void estimatesAudibleGapFromPriorDeviceOccupancy() {
        long tenMillisOfPcm = BYTES_PER_SECOND / 100;
        assertEquals(10_000_000L,
                GapAccounting.audibleGapNanos(20_000_000L, (int) tenMillisOfPcm));
        assertEquals(0L,
                GapAccounting.audibleGapNanos(5_000_000L, (int) tenMillisOfPcm));
    }

    @Test
    public void accountsForExactPcmPhaseAndFrontendDropsAcrossTheMeasurementBoundary() {
        PcmExpectation expectation = new PcmExpectation();
        ClockSpec clock = ClockSpec.LEGACY;
        for (int frame = 0; frame < WARMUP_FRAMES; frame++) {
            expectation.accept(clock, clock.controllerTicksPerFrame());
        }
        expectation.resetMeasuredBytes();
        for (int frame = 0; frame < MEASUREMENT_FRAMES; frame++) {
            expectation.accept(clock, clock.controllerTicksPerFrame());
        }

        assertEquals(1_763_996L, expectation.measuredBytes());
        assertEquals(2_940L, expectation.largestChunkBytes());
        assertEquals(58_800L, DropAccounting.droppedBytes(
                expectation.measuredBytes(), 1_705_196L));
        assertEquals("20", DropAccounting.frameEquivalent(58_800L, expectation.largestChunkBytes()));
    }

    @Test
    public void cumulativeDeliveryAccountingDoesNotLetWarmupTailMaskMeasurementDrops() {
        ClockSpec clock = ClockSpec.LEGACY;
        CumulativePcmAccounting accounting = new CumulativePcmAccounting();
        for (int frame = 0; frame < 3; frame++) {
            accounting.onProducerEvent(clock, clock.controllerTicksPerFrame());
        }
        long warmupBytes = accounting.expectedBytes();

        // resetForMeasurement intentionally clears only the windowed timing metrics. These three
        // writes are warmup PCM that reaches the worker after the boundary.
        for (int frame = 0; frame < 3; frame++) {
            accounting.onProducerEvent(clock, clock.controllerTicksPerFrame());
        }
        long measurementBytes = accounting.expectedBytes() - warmupBytes;
        accounting.onWorkerWrite(warmupBytes + measurementBytes - 2L * 2_940);

        assertEquals(5_880L, accounting.droppedBytes());
        assertEquals("2", DropAccounting.frameEquivalent(
                accounting.droppedBytes(), accounting.largestChunkBytes()));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Delegates to Java Sound while retaining the actual device values around every write. */
    private static final class MeasuringAudioBackend implements AudioBackend {
        private final AudioBackend delegate = new JavaSoundAudioBackend();
        private final UiAudioMetrics metrics;

        private MeasuringAudioBackend(UiAudioMetrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public List<AudioDeviceSnapshot> devices() {
            return delegate.devices();
        }

        @Override
        public AudioLine open(String stableId, AudioFormat format, int bufferBytes)
                throws LineUnavailableException {
            return new MeasuringAudioLine(delegate.open(stableId, format, bufferBytes), metrics);
        }
    }

    private static final class MeasuringAudioLine implements AudioBackend.AudioLine {
        private final AudioBackend.AudioLine delegate;
        private final UiAudioMetrics metrics;

        private MeasuringAudioLine(AudioBackend.AudioLine delegate, UiAudioMetrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            int bufferBytes = delegate.bufferSize();
            int availableBefore = delegate.available();
            long started = System.nanoTime();
            metrics.onLineWriteStarted(started);
            try {
                int written = delegate.write(bytes, offset, length);
                long completed = System.nanoTime();
                int availableAfter = delegate.available();
                metrics.onLineWrite(
                        bufferBytes, availableBefore, availableAfter, written, started, completed);
                return written;
            } finally {
                metrics.onLineWriteFinished(System.nanoTime());
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

    /** Measurement-only collector; all methods are synchronized across the emulation and worker threads. */
    private static final class UiAudioMetrics {
        private final List<Long> producerIntervalsNanos = new ArrayList<>();
        private final List<Long> controllerWorkNanos = new ArrayList<>();
        private final List<Long> lineWriteIntervalsNanos = new ArrayList<>();
        private final List<Long> writeBlockingNanos = new ArrayList<>();
        private volatile boolean measuring;
        private long lastProducerNanos;
        private long lastPostWriteNanos;
        private long lastLineWriteCompletedNanos;
        private int lineWritesInProgress;
        private int previousPostWriteOccupancyBytes;
        private final CumulativePcmAccounting delivery = new CumulativePcmAccounting();
        private int lineBufferBytes;
        private int producerGapsOver25Ms;
        private int producerGapsOver50Ms;
        private int controllerWorkOverNominal;
        private int pacingDebtFrames;
        private int emptyLineWrites;
        private int frontendQueuePeakFrames;
        private double totalAudibleGapMs;
        private double maximumAudibleGapMs;
        private double minimumBufferedBeforeWriteMs = Double.POSITIVE_INFINITY;
        private boolean cumulativeDeliveryAccounting;

        synchronized void startCumulativeDeliveryAccounting() {
            cumulativeDeliveryAccounting = true;
        }

        synchronized void resetForMeasurement() {
            measuring = false;
            producerIntervalsNanos.clear();
            controllerWorkNanos.clear();
            lineWriteIntervalsNanos.clear();
            writeBlockingNanos.clear();
            lastProducerNanos = 0;
            lastPostWriteNanos = 0;
            previousPostWriteOccupancyBytes = 0;
            lineBufferBytes = 0;
            producerGapsOver25Ms = 0;
            producerGapsOver50Ms = 0;
            controllerWorkOverNominal = 0;
            pacingDebtFrames = 0;
            emptyLineWrites = 0;
            frontendQueuePeakFrames = 0;
            totalAudibleGapMs = 0;
            maximumAudibleGapMs = 0;
            minimumBufferedBeforeWriteMs = Double.POSITIVE_INFINITY;
            measuring = true;
        }

        synchronized void onProducerEvent(Sound.SoundSampleEvent event) {
            if (!cumulativeDeliveryAccounting) {
                return;
            }
            int ticks = event.buffer().length / 2;
            delivery.onProducerEvent(event.clockSpec(), ticks);
            if (!measuring) {
                return;
            }
            long now = System.nanoTime();
            if (lastProducerNanos != 0) {
                long interval = now - lastProducerNanos;
                producerIntervalsNanos.add(interval);
                if (interval > TimeUnit.MILLISECONDS.toNanos(25)) {
                    producerGapsOver25Ms++;
                }
                if (interval > TimeUnit.MILLISECONDS.toNanos(50)) {
                    producerGapsOver50Ms++;
                }
            }
            lastProducerNanos = now;
        }

        synchronized void onFrontendQueueAfterSynchronousPlay(int queuedFrames) {
            if (measuring) {
                frontendQueuePeakFrames = Math.max(frontendQueuePeakFrames, queuedFrames);
            }
        }

        synchronized void onControllerFrame(long workNanos, long nominalNanos, boolean pacingDebt) {
            if (!measuring) {
                return;
            }
            controllerWorkNanos.add(workNanos);
            if (workNanos > nominalNanos) {
                controllerWorkOverNominal++;
            }
            if (pacingDebt) {
                pacingDebtFrames++;
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
            int occupancyBefore = Math.max(0, bufferBytes - availableBefore);
            int occupancyAfter = Math.max(0, bufferBytes - availableAfter);
            minimumBufferedBeforeWriteMs = Math.min(
                    minimumBufferedBeforeWriteMs, millisecondsForBytes(occupancyBefore));
            if (lastPostWriteNanos != 0) {
                long interval = Math.max(0, started - lastPostWriteNanos);
                lineWriteIntervalsNanos.add(interval);
                long gapNanos = GapAccounting.audibleGapNanos(
                        interval, previousPostWriteOccupancyBytes);
                double gapMs = gapNanos / 1_000_000.0;
                totalAudibleGapMs += gapMs;
                maximumAudibleGapMs = Math.max(maximumAudibleGapMs, gapMs);
                // Java Sound providers can report a single stereo frame as still occupied while
                // the DAC has effectively drained. Treat that four-byte rounding residue as an
                // empty line, matching the historic desktop underrun interpretation.
                if (availableBefore >= bufferBytes - BYTES_PER_STEREO_FRAME) {
                    emptyLineWrites++;
                }
            }
            writeBlockingNanos.add(Math.max(0, completed - started));
            previousPostWriteOccupancyBytes = occupancyAfter;
            lastPostWriteNanos = completed;
            notifyAll();
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
                BlockingQueue<byte[]> workerQueue, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!deliveryReached(workerQueue) && System.nanoTime() < deadline) {
                long remaining = Math.min(
                        Math.max(1, deadline - System.nanoTime()),
                        nanosUntilDeliveryCanBeQuiescent(workerQueue));
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            if (!deliveryReached(workerQueue)) {
                throw new IllegalStateException("Audio worker delivery timed out: "
                        + delivery.workerBytes() + " worker PCM bytes (expected "
                        + delivery.expectedBytes()
                        + "), queue empty=" + workerQueue.isEmpty()
                        + ", final source/write timestamps=" + lastProducerNanos
                        + "/" + lastPostWriteNanos + ", line writes in progress="
                        + lineWritesInProgress + " after " + timeout.toMillis() + " ms");
            }
            measuring = false;
        }

        private boolean deliveryReached(BlockingQueue<byte[]> workerQueue) {
            // A short frontend queue can legitimately drop PCM. After the final producer event,
            // wait for a line write that began/completed after it, no in-flight write, and a short
            // quiet interval. This prevents an older write completing after the source timestamp
            // from racing an already-polled final buffer that has not reached AudioLine.write().
            return workerQueue.isEmpty()
                    && lastProducerNanos != 0
                    && lastPostWriteNanos >= lastProducerNanos
                    && lineWritesInProgress == 0
                    && System.nanoTime() - lastLineWriteCompletedNanos >= OUTPUT_QUIESCENCE.toNanos();
        }

        private long nanosUntilDeliveryCanBeQuiescent(BlockingQueue<byte[]> workerQueue) {
            if (!workerQueue.isEmpty()
                    || lastProducerNanos == 0
                    || lastPostWriteNanos < lastProducerNanos
                    || lineWritesInProgress != 0) {
                return OUTPUT_DELIVERY_TIMEOUT.toNanos();
            }
            return Math.max(1, lastLineWriteCompletedNanos + OUTPUT_QUIESCENCE.toNanos()
                    - System.nanoTime());
        }

        synchronized void printReport(AudioRuntimeConfiguration.LatencyPreset latencyPreset) {
            System.out.printf("UI audio timing preset: %s%n", latencyPreset);
            System.out.printf("Audio frontend expected/worker PCM bytes: %d / %d%n",
                    delivery.expectedBytes(), delivery.workerBytes());
            long droppedBytes = delivery.droppedBytes();
            System.out.printf("Audio frontend dropped PCM bytes/ms/frames: %d / %.3f / %s%n",
                    droppedBytes,
                    millisecondsForBytes(droppedBytes),
                    DropAccounting.frameEquivalent(droppedBytes, delivery.largestChunkBytes()));
            System.out.printf("Audio frontend queue peak/capacity frames: %d / %d%n",
                    frontendQueuePeakFrames, latencyPreset.runtimeQueueCapacity());
            System.out.printf("Audio line buffer bytes/ms: %d / %.3f%n",
                    lineBufferBytes, millisecondsForBytes(lineBufferBytes));
            System.out.printf("Producer event interval p50/p95/p99/max ms: %s%n",
                    percentileSummary(producerIntervalsNanos));
            System.out.printf("Audio gaps >25ms/>50ms: %d / %d%n",
                    producerGapsOver25Ms, producerGapsOver50Ms);
            System.out.printf("Controller frame work p50/p95/p99/max ms: %s%n",
                    percentileSummary(controllerWorkNanos));
            System.out.printf("Controller work > nominal / pacing-debt frames: %d / %d%n",
                    controllerWorkOverNominal, pacingDebtFrames);
            System.out.printf("Audio line write interval p50/p95/p99/max ms: %s%n",
                    percentileSummary(lineWriteIntervalsNanos));
            System.out.printf("Audio line max write blocking ms: %.3f%n",
                    maximumMillis(writeBlockingNanos));
            System.out.printf("Audio line minimum buffered before write ms: %.3f%n",
                    Double.isInfinite(minimumBufferedBeforeWriteMs) ? 0 : minimumBufferedBeforeWriteMs);
            System.out.printf("Audio underruns (empty-line writes after priming): %d%n",
                    emptyLineWrites);
            System.out.printf("Audio gap estimate total/max ms: %.3f / %.3f%n",
                    totalAudibleGapMs, maximumAudibleGapMs);
        }

        private static double millisecondsForBytes(long bytes) {
            return bytes * 1_000.0 / BYTES_PER_SECOND;
        }

        private static double maximumMillis(List<Long> nanos) {
            return nanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000.0;
        }

        private static String percentileSummary(List<Long> values) {
            if (values.isEmpty()) {
                return "n/a";
            }
            long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(sorted);
            return String.format("%.3f / %.3f / %.3f / %.3f",
                    percentileMillis(sorted, 0.50), percentileMillis(sorted, 0.95),
                    percentileMillis(sorted, 0.99), sorted[sorted.length - 1] / 1_000_000.0);
        }

        private static double percentileMillis(long[] sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1_000_000.0;
        }
    }

    /** Mirrors StereoPcmConverter's fractional sample accumulator without rendering samples. */
    private static final class PcmExpectation {
        private ClockSpec activeClock;
        private ClockSpec.RateAccumulator sampleAccumulator;
        private long measuredBytes;
        private long largestChunkBytes;

        long accept(ClockSpec clock, int ticks) {
            if (activeClock == null || !activeClock.equals(clock)) {
                activeClock = clock;
                sampleAccumulator = clock.newTickRateAccumulator(SAMPLE_RATE);
            }
            long bytes = Math.multiplyExact(
                    sampleAccumulator.advance(ticks), BYTES_PER_STEREO_FRAME);
            measuredBytes = Math.addExact(measuredBytes, bytes);
            largestChunkBytes = Math.max(largestChunkBytes, bytes);
            return bytes;
        }

        void resetMeasuredBytes() {
            measuredBytes = 0;
            largestChunkBytes = 0;
        }

        long measuredBytes() {
            return measuredBytes;
        }

        long largestChunkBytes() {
            return largestChunkBytes;
        }
    }

    /** PCM accounting deliberately spans the warmup/measurement boundary. */
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
