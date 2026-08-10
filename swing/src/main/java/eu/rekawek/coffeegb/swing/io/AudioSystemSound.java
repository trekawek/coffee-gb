package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.SoundProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.sound.StereoPcmConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Plays emulator audio through a bounded, reconfigurable host-output worker.
 *
 * <p>The per-tick sample buffer posted by the core is decimated directly in the event handler (the
 * event bus dispatches synchronously on the emulation thread, so its shared buffer can be read
 * without copying it). Device discovery, line open/reconfiguration, fallback, writes, and ordinary
 * close operations belong exclusively to the audio worker. Applying settings only publishes an
 * immutable configuration and never blocks the Swing EDT on a host device.
 *
 * <p>A fractional resampling step keeps the produced rate exactly at the line's sample rate. Host
 * device, gain, mute, and latency changes deliberately leave that phase and the DC blockers intact:
 * presentation changes must not alter emulation timing or same-profile pause/restore continuity.
 *
 * <p>A newly opened or intentionally flushed line stages the configured frame watermark before its
 * first write. Once playing, a late producer is allowed to drain naturally: poll timeouts never
 * manufacture frame-sized silence or delay the next real PCM frame.
 */
public class AudioSystemSound implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(AudioSystemSound.class);

    private static final int SAMPLE_RATE = 44100;
    private static final int BYTES_PER_STEREO_FRAME = 4;

    private static final AudioFormat FORMAT =
            new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SAMPLE_RATE,
                    16,
                    2,
                    BYTES_PER_STEREO_FRAME,
                    SAMPLE_RATE,
                    false);

    private static final long RETRY_UNAVAILABLE_MILLIS = 1000;
    private static final long STOP_TIMEOUT_MILLIS = 2000;
    private static final long FORCED_CLOSE_TIMEOUT_MILLIS = 250;
    private static final int MAX_RUNTIME_QUEUED_FRAMES =
            AudioRuntimeConfiguration.LatencyPreset.SAFE.runtimeQueueCapacity();

    private final AudioBackend backend;
    private final AtomicReference<AudioRuntimeConfiguration> desiredConfiguration;
    private final BlockingQueue<AudioRuntimeConfiguration> configurationQueue =
            new ArrayBlockingQueue<>(1);
    private final AtomicLong configurationGeneration = new AtomicLong();

    /*
     * Keep this field name and BlockingQueue type source-compatible with the deterministic clock
     * probes. The physical capacity is the largest runtime preset; enqueuePcm enforces the active
     * bounded runtime capacity while startup priming continues to use queuedFrames().
     */
    private final BlockingQueue<byte[]> queue =
            new ArrayBlockingQueue<>(MAX_RUNTIME_QUEUED_FRAMES);

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean emergencyCloseStarted = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile Consumer<AudioOutputStatus> statusObserver;
    private volatile AudioOutputStatus status;
    private volatile boolean doStop;
    private volatile Thread workerThread;
    private volatile AudioBackend.AudioLine activeLine;

    private final StereoPcmConverter pcmConverter = new StereoPcmConverter(SAMPLE_RATE);

    // Event delivery is synchronous on the emulation thread, so one grow-only scratch
    // buffer is safe to reuse between conversions. The queued frame remains an owned,
    // right-sized copy while the worker drains it.
    private byte[] pcmScratch = new byte[0];

    /** Existing desktop constructor; preserves the old enabled/default-device behavior. */
    public AudioSystemSound(SoundProperties properties, EventBus eventBus, String callerId) {
        this(
                AudioRuntimeConfiguration.defaults(properties.getSoundEnabled()),
                eventBus,
                callerId,
                new JavaSoundAudioBackend(),
                ignored -> {
                });
    }

    /**
     * Runtime-configurable desktop constructor. The observer runs on the audio thread; Swing callers
     * must marshal any component mutation to the EDT.
     */
    public AudioSystemSound(
            AudioRuntimeConfiguration initialConfiguration,
            EventBus eventBus,
            String callerId,
            Consumer<AudioOutputStatus> statusObserver) {
        this(
                initialConfiguration,
                eventBus,
                callerId,
                new JavaSoundAudioBackend(),
                statusObserver);
    }

    AudioSystemSound(
            AudioRuntimeConfiguration initialConfiguration,
            EventBus eventBus,
            String callerId,
            AudioBackend backend,
            Consumer<AudioOutputStatus> statusObserver) {
        Objects.requireNonNull(eventBus, "eventBus");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.desiredConfiguration =
                new AtomicReference<>(Objects.requireNonNull(
                        initialConfiguration, "initialConfiguration"));
        this.statusObserver = Objects.requireNonNull(statusObserver, "statusObserver");
        this.status = new AudioOutputStatus(
                AudioOutputStatus.State.STARTING,
                initialConfiguration.outputDeviceId(),
                null,
                "Audio output has not started");
        eventBus.register(this::play, Sound.SoundSampleEvent.class, callerId);
        eventBus.register(
                event -> applyConfiguration(
                        desiredConfiguration.get().withMuted(!event.enabled())),
                Sound.SoundEnabledEvent.class);
    }

    /**
     * Starts the owned audio worker as a named daemon. Existing callers may still wrap this Runnable
     * in their own thread temporarily, but new desktop integration should use this method.
     */
    public Thread start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Audio output can only be started once");
        }
        if (doStop) {
            throw new IllegalStateException("Audio output has already been stopped");
        }
        Thread thread = new Thread(this::runWorker, "coffee-gb-audio-output");
        thread.setDaemon(true);
        workerThread = thread;
        thread.start();
        return thread;
    }

    /**
     * Compatibility Runnable entry point. Prefer {@link #start()} so a stalled platform provider
     * cannot keep the JVM alive.
     */
    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Audio output can only be started once");
        }
        runWorker();
    }

    /**
     * Publishes settings without opening, enumerating, or closing a host line on the caller.
     * Superseded pending configurations are coalesced.
     */
    public void applyConfiguration(AudioRuntimeConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        AudioRuntimeConfiguration previous = desiredConfiguration.getAndSet(configuration);
        if (previous.equals(configuration)) {
            return;
        }
        configurationGeneration.incrementAndGet();
        queue.clear();
        configurationQueue.clear();
        configurationQueue.offer(configuration);
    }

    public AudioRuntimeConfiguration currentConfiguration() {
        return desiredConfiguration.get();
    }

    public AudioOutputStatus currentStatus() {
        return status;
    }

    /**
     * Replaces the status observer and immediately supplies the immutable current snapshot on the
     * caller's thread.
     */
    public void setStatusObserver(Consumer<AudioOutputStatus> observer) {
        statusObserver = Objects.requireNonNull(observer, "observer");
        observer.accept(status);
    }

    /**
     * Signals shutdown and waits only for a bounded interval. Ordinary line cleanup stays on the
     * worker. A provider that remains stuck in write receives one emergency close from a daemon
     * helper; even a provider whose close itself stalls cannot block this caller past the deadline.
     */
    public void stopThread() {
        doStop = true;
        Thread thread = workerThread;
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        thread.interrupt();

        boolean interrupted = false;
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(
                        STOP_TIMEOUT_MILLIS + FORCED_CLOSE_TIMEOUT_MILLIS);
        try {
            if (!stopped.await(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                requestEmergencyClose(activeLine);
                thread.interrupt();
                stopped.await(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            }
            TimeUnit.NANOSECONDS.timedJoin(thread, remainingNanos(deadline));
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runWorker() {
        workerThread = Thread.currentThread();
        AudioRuntimeConfiguration applied = null;
        AudioBackend.AudioLine line = null;
        boolean usingFallback = false;
        boolean priming = true;
        Deque<byte[]> primingFrames = new ArrayDeque<>(MAX_RUNTIME_QUEUED_FRAMES);
        long appliedGeneration = configurationGeneration.get();
        long retryAtNanos = 0;
        publishStatus(new AudioOutputStatus(
                AudioOutputStatus.State.STARTING,
                desiredConfiguration.get().outputDeviceId(),
                null,
                "Opening audio output"));

        try {
            workerLoop:
            while (!doStop) {
                AudioRuntimeConfiguration pending = latestPendingConfiguration();
                if (applied == null || pending != null) {
                    long nextGeneration = configurationGeneration.get();
                    AudioRuntimeConfiguration next =
                            pending == null ? desiredConfiguration.get() : pending;
                    boolean reopen =
                            applied == null
                                    || !applied.outputDeviceId().equals(next.outputDeviceId())
                                    || applied.latencyPreset() != next.latencyPreset();
                    queue.clear();
                    primingFrames.clear();
                    priming = true;
                    if (reopen) {
                        closeLine(line);
                        line = null;
                        activeLine = null;
                        if (!doStop) {
                            line = openWithFallback(next);
                            activeLine = line;
                            usingFallback =
                                    line != null
                                            && status.state() == AudioOutputStatus.State.FALLBACK;
                            retryAtNanos = retryDeadline();
                        }
                    } else if (line != null) {
                        /*
                         * Gain/mute changes are producer-side. Flush the old-gain device buffer so
                         * they become audible promptly even under the SAFE latency preset.
                         */
                        safelyFlush(line);
                    }
                    applied = next;
                    appliedGeneration = nextGeneration;
                }

                if (line == null) {
                    usingFallback = false;
                    primingFrames.clear();
                    priming = true;
                    discardOneFrameWhileUnavailable();
                    if (System.nanoTime() >= retryAtNanos && !doStop) {
                        line = openWithFallback(applied == null
                                ? desiredConfiguration.get()
                                : applied);
                        activeLine = line;
                        usingFallback =
                                line != null
                                        && status.state() == AudioOutputStatus.State.FALLBACK;
                        retryAtNanos = retryDeadline();
                    }
                    continue;
                }

                if (usingFallback && System.nanoTime() >= retryAtNanos && !doStop) {
                    AudioBackend.AudioLine previousLine = line;
                    FallbackRecovery recovery = tryRecoverPreferred(applied, line);
                    retryAtNanos = retryDeadline();
                    line = recovery.line();
                    activeLine = line;
                    usingFallback = recovery.usingFallback();
                    if (line != previousLine) {
                        primingFrames.clear();
                        priming = true;
                    }
                    if (line == null) continue;
                }

                if (!line.isOpen()) {
                    closeLine(line);
                    line = null;
                    activeLine = null;
                    usingFallback = false;
                    primingFrames.clear();
                    priming = true;
                    retryAtNanos = 0;
                    continue;
                }

                byte[] buffer = pollPcm();
                if (doStop) {
                    break;
                }
                if (appliedGeneration != configurationGeneration.get()) {
                    // applyConfiguration has already cleared the shared queue. Do not let a frame
                    // removed just before that clear survive in this worker-local jitter buffer.
                    primingFrames.clear();
                    priming = true;
                    continue;
                }
                try {
                    if (priming) {
                        if (buffer != null && buffer.length > 0) {
                            primingFrames.addLast(buffer);
                        }
                        int watermark = applied.latencyPreset().queuedFrames();
                        if (primingFrames.size() >= watermark) {
                            // A running line lets a staged batch larger than its physical buffer
                            // drain while writeFully completes. start() is an idempotent recovery
                            // nudge for providers that stop themselves after an underrun.
                            line.start();
                            while (!primingFrames.isEmpty()) {
                                if (appliedGeneration != configurationGeneration.get()) {
                                    // A SAFE batch can span several blocking device writes. Do not
                                    // let the worker-local tail cross a settings/device boundary.
                                    primingFrames.clear();
                                    priming = true;
                                    continue workerLoop;
                                }
                                writeFully(line, primingFrames.removeFirst());
                            }
                            priming = false;
                        }
                    } else if (buffer != null && buffer.length > 0) {
                        // SourceDataLine providers may stop after naturally draining. Resume the
                        // real frame immediately; withholding it to rebuild the startup watermark
                        // would turn a slow producer into a larger periodic burst/pause cycle.
                        line.start();
                        writeFully(line, buffer);
                    }
                } catch (RuntimeException failure) {
                    LOG.warn("Audio output failed; reopening with safe fallback", failure);
                    closeLine(line);
                    line = null;
                    activeLine = null;
                    usingFallback = false;
                    primingFrames.clear();
                    priming = true;
                    retryAtNanos = 0;
                }
            }
        } finally {
            closeLine(line);
            activeLine = null;
            publishStatus(new AudioOutputStatus(
                    AudioOutputStatus.State.STOPPED,
                    desiredConfiguration.get().outputDeviceId(),
                    null,
                    "Audio output stopped"));
            stopped.countDown();
        }
    }

    private AudioRuntimeConfiguration latestPendingConfiguration() {
        AudioRuntimeConfiguration latest = null;
        AudioRuntimeConfiguration next;
        while ((next = configurationQueue.poll()) != null) {
            latest = next;
        }
        return latest;
    }

    private AudioBackend.AudioLine openWithFallback(AudioRuntimeConfiguration configuration) {
        String requested = configuration.outputDeviceId();
        Throwable requestedFailure;
        try {
            AudioBackend.AudioLine line =
                    openAndStart(requested, configuration.latencyPreset().lineBufferBytes());
            publishStatus(new AudioOutputStatus(
                    AudioOutputStatus.State.ACTIVE,
                    requested,
                    requested,
                    AudioDeviceSnapshot.SYSTEM_DEFAULT_ID.equals(requested)
                            ? "Using system-default audio output"
                            : "Using configured audio output"));
            return line;
        } catch (LineUnavailableException | RuntimeException failure) {
            requestedFailure = failure;
        }

        if (!AudioDeviceSnapshot.SYSTEM_DEFAULT_ID.equals(requested)) {
            try {
                AudioBackend.AudioLine fallback =
                        openAndStart(
                                AudioDeviceSnapshot.SYSTEM_DEFAULT_ID,
                                configuration.latencyPreset().lineBufferBytes());
                publishStatus(new AudioOutputStatus(
                        AudioOutputStatus.State.FALLBACK,
                        requested,
                        AudioDeviceSnapshot.SYSTEM_DEFAULT_ID,
                        "Configured audio output is unavailable; using System Default ("
                                + failureDetail(requestedFailure) + ")"));
                return fallback;
            } catch (LineUnavailableException | RuntimeException fallbackFailure) {
                publishUnavailable(requested, requestedFailure, fallbackFailure);
                return null;
            }
        }

        publishUnavailable(requested, requestedFailure, null);
        return null;
    }

    private FallbackRecovery tryRecoverPreferred(
            AudioRuntimeConfiguration configuration,
            AudioBackend.AudioLine fallback) {
        String requested = configuration.outputDeviceId();
        if (AudioDeviceSnapshot.SYSTEM_DEFAULT_ID.equals(requested)) {
            return new FallbackRecovery(fallback, false);
        }

        boolean available;
        try {
            available = backend.devices().stream()
                    .anyMatch(device -> device.stableId().equals(requested));
        } catch (RuntimeException enumerationFailure) {
            LOG.debug("Unable to probe the configured audio output", enumerationFailure);
            return new FallbackRecovery(fallback, true);
        }
        if (!available) {
            return new FallbackRecovery(fallback, true);
        }

        /*
         * Some providers expose the preferred mixer again but reject an open while the fallback
         * line owns the same physical endpoint. Catalog presence gates this disruptive retry so an
         * actually absent device does not make healthy fallback audio reopen every second.
         */
        closeLine(fallback);
        if (doStop) {
            return new FallbackRecovery(null, false);
        }
        AudioBackend.AudioLine reopened = openWithFallback(configuration);
        boolean stillUsingFallback =
                reopened != null && status.state() == AudioOutputStatus.State.FALLBACK;
        return new FallbackRecovery(reopened, stillUsingFallback);
    }

    private AudioBackend.AudioLine openAndStart(String deviceId, int lineBufferBytes)
            throws LineUnavailableException {
        AudioBackend.AudioLine line = backend.open(deviceId, FORMAT, lineBufferBytes);
        try {
            line.start();
            return line;
        } catch (RuntimeException failure) {
            line.close();
            throw failure;
        }
    }

    private void publishUnavailable(
            String requested, Throwable requestedFailure, Throwable fallbackFailure) {
        String detail = "No audio output is currently available ("
                + failureDetail(requestedFailure);
        if (fallbackFailure != null) {
            detail += "; System Default: " + failureDetail(fallbackFailure);
        }
        detail += ")";
        publishStatus(new AudioOutputStatus(
                AudioOutputStatus.State.UNAVAILABLE,
                requested,
                null,
                detail));
    }

    private void publishStatus(AudioOutputStatus next) {
        AudioOutputStatus previous = status;
        status = next;
        if (next.equals(previous)) {
            return;
        }
        switch (next.state()) {
            case FALLBACK -> LOG.warn(next.detail());
            case UNAVAILABLE -> LOG.error(next.detail());
            default -> LOG.debug(next.detail());
        }
        try {
            statusObserver.accept(next);
        } catch (RuntimeException failure) {
            LOG.warn("Audio status observer failed", failure);
        }
    }

    private void closeLine(AudioBackend.AudioLine line) {
        if (line == null) {
            return;
        }
        try {
            line.stop();
        } catch (RuntimeException failure) {
            LOG.debug("Unable to stop audio line cleanly", failure);
        }
        safelyFlush(line);
        try {
            line.close();
        } catch (RuntimeException failure) {
            LOG.debug("Unable to close audio line cleanly", failure);
        }
    }

    private void safelyFlush(AudioBackend.AudioLine line) {
        try {
            line.flush();
        } catch (RuntimeException failure) {
            LOG.debug("Unable to flush audio line cleanly", failure);
        }
    }

    private void requestEmergencyClose(AudioBackend.AudioLine line) {
        if (line == null || !emergencyCloseStarted.compareAndSet(false, true)) {
            return;
        }
        Thread closer = new Thread(
                () -> {
                    try {
                        line.close();
                    } catch (RuntimeException failure) {
                        LOG.debug("Emergency audio-line close failed", failure);
                    }
                },
                "coffee-gb-audio-emergency-close");
        closer.setDaemon(true);
        closer.start();
    }

    private void discardOneFrameWhileUnavailable() {
        try {
            queue.poll(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            if (!doStop) {
                Thread.interrupted();
            }
        }
    }

    private byte[] pollPcm() {
        try {
            return queue.poll(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            if (!doStop) {
                Thread.interrupted();
            }
            return null;
        }
    }

    private void writeFully(AudioBackend.AudioLine line, byte[] bytes) {
        int offset = 0;
        while (offset < bytes.length && !doStop) {
            int written = line.write(bytes, offset, bytes.length - offset);
            if (written <= 0) {
                throw new IllegalStateException("Audio line made no write progress");
            }
            offset += written;
        }
    }

    private long retryDeadline() {
        return System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(RETRY_UNAVAILABLE_MILLIS);
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private record FallbackRecovery(
            AudioBackend.AudioLine line,
            boolean usingFallback) {
    }

    private static String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message;
    }

    private void play(Sound.SoundSampleEvent event) {
        long generation = configurationGeneration.get();
        AudioRuntimeConfiguration configuration = desiredConfiguration.get();
        int[] source = event.buffer();
        int ticks = source.length / 2;
        int maximumBytes = pcmConverter.maximumPcmBytes(ticks, event.clockSpec());
        if (pcmScratch.length < maximumBytes) {
            pcmScratch = Arrays.copyOf(pcmScratch, maximumBytes);
        }
        int written = pcmConverter.render(source, event.clockSpec(),
                configuration.masterVolume(), configuration.muted(), pcmScratch);
        byte[] trimmed = Arrays.copyOf(pcmScratch, written);
        if (generation == configurationGeneration.get()) {
            enqueuePcm(trimmed, configuration.latencyPreset().runtimeQueueCapacity());
        }
    }

    private void enqueuePcm(byte[] bytes, int maximumFrames) {
        synchronized (queue) {
            while (queue.size() >= maximumFrames) {
                queue.poll();
            }
            if (!queue.offer(bytes)) {
                queue.poll();
                queue.offer(bytes);
            }
        }
    }

    static AudioFormat outputFormat() {
        return FORMAT;
    }

    /** Package-private deterministic probe; exposes only the scalar conversion phase. */
    long samplePhaseForTesting() {
        return pcmConverter.samplePhase();
    }

    Thread workerThreadForTesting() {
        return workerThread;
    }
}
