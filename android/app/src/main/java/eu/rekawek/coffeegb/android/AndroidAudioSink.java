package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.sound.Sound;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded Android host-audio adapter. Emulator events only fill preallocated PCM slots; all
 * {@code AudioTrack} opening, writes, flushes, and release work on this adapter's consumer thread.
 */
final class AndroidAudioSink implements AutoCloseable {

    interface OutputFactory {
        Output open();
    }

    record AudioStats(int sampleRate, int minimumBufferBytes, int configuredBufferBytes,
                      int actualBufferBytes) {
    }

    interface Output {
        int sampleRate();

        default AudioStats audioStats() {
            return new AudioStats(sampleRate(), 0, 0, 0);
        }

        void play();

        void pause();

        void flush();

        /** Returns the bytes written, or a non-positive error code. */
        int write(byte[] bytes, int offset, int length);

        void release();
    }

    record Stats(int sampleRate, long overruns, long underruns, long restarts, boolean paused,
                 boolean active, int minimumBufferBytes, int configuredBufferBytes,
                 int actualBufferBytes) {
    }

    private static final long POLL_MILLIS = 20L;
    private static final long CLOSE_TIMEOUT_MILLIS = 750L;

    private final OutputFactory outputFactory;
    private final AudioManager audioManager;
    private final boolean enabled;
    private final AndroidBenchmarkDiagnostics diagnostics;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reopenRequested = new AtomicBoolean();
    private final AtomicLong underruns = new AtomicLong();
    private final AtomicLong restarts = new AtomicLong();
    private final Object wakeLock = new Object();
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            requestRouteReopen();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            requestRouteReopen();
        }
    };

    private volatile BoundedPcmQueue queue;
    private volatile boolean paused;
    private volatile boolean muted;
    private volatile int volume = 100;
    private volatile boolean hasProducedPcm;
    private volatile int sampleRate;
    private volatile int minimumBufferBytes;
    private volatile int configuredBufferBytes;
    private volatile int actualBufferBytes;
    private volatile Thread worker;

    AndroidAudioSink(Context context, EventBus eventBus) {
        this(eventBus, new AndroidAudioTrackOutput.Factory(),
                Objects.requireNonNull(context, "context").getSystemService(AudioManager.class),
                true, null);
    }

    AndroidAudioSink(Context context, EventBus eventBus,
            AndroidBenchmarkDiagnostics diagnostics) {
        this(eventBus, new AndroidAudioTrackOutput.Factory(),
                Objects.requireNonNull(context, "context").getSystemService(AudioManager.class),
                true, diagnostics);
    }

    AndroidAudioSink(EventBus eventBus, OutputFactory outputFactory) {
        this(eventBus, outputFactory, null, true, null);
    }

    private AndroidAudioSink(EventBus eventBus, OutputFactory outputFactory, AudioManager audioManager,
            boolean enabled, AndroidBenchmarkDiagnostics diagnostics) {
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
        this.audioManager = audioManager;
        this.enabled = enabled;
        this.diagnostics = diagnostics;
        EventBus bus = Objects.requireNonNull(eventBus, "eventBus");
        if (enabled) {
            bus.register(this::onSoundSample, Sound.SoundSampleEvent.class);
            bus.register(event -> setMuted(!event.enabled()), Sound.SoundEnabledEvent.class);
        }
    }

    static AndroidAudioSink disabled(EventBus eventBus) {
        return new AndroidAudioSink(eventBus, () -> null, null, false, null);
    }

    void start() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Android audio sink can only be started once");
        }
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(deviceCallback, null);
        }
        Thread next = new Thread(this::runConsumer, "coffee-gb-android-audio");
        next.setDaemon(true);
        worker = next;
        next.start();
    }

    void pause() {
        paused = true;
        clearQueuedPcm();
        wakeConsumer();
    }

    void resume() {
        paused = false;
        wakeConsumer();
    }

    void setMuted(boolean nextMuted) {
        muted = nextMuted;
        clearQueuedPcm();
        wakeConsumer();
    }

    void setVolume(int nextVolume) {
        if (nextVolume < 0 || nextVolume > 100) {
            throw new IllegalArgumentException("Audio volume must be between 0 and 100");
        }
        volume = nextVolume;
        clearQueuedPcm();
        wakeConsumer();
    }

    Stats stats() {
        BoundedPcmQueue active = queue;
        return new Stats(sampleRate, active == null ? 0 : active.overruns(), underruns.get(),
                restarts.get(), paused, running.get(), minimumBufferBytes, configuredBufferBytes,
                actualBufferBytes);
    }

    AudioStats audioStats() {
        return new AudioStats(sampleRate, minimumBufferBytes, configuredBufferBytes,
                actualBufferBytes);
    }

    void requestRouteReopen() {
        reopenRequested.set(true);
        clearQueuedPcm();
        wakeConsumer();
    }

    Thread workerThreadForTesting() {
        return worker;
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        if (audioManager != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback);
        }
        clearQueuedPcm();
        wakeConsumer();
        Thread active = worker;
        if (active != null && active != Thread.currentThread()) {
            try {
                active.join(CLOSE_TIMEOUT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void onSoundSample(Sound.SoundSampleEvent event) {
        BoundedPcmQueue active = queue;
        if (!running.get() || active == null || paused) {
            return;
        }
        active.offer(event, volume, muted);
        hasProducedPcm = true;
        wakeConsumer();
    }

    private void runConsumer() {
        Output output = null;
        BoundedPcmQueue activeQueue = null;
        boolean outputPaused = false;
        boolean outputOpenedOnce = false;
        try {
            while (running.get()) {
                if (output == null || reopenRequested.getAndSet(false)) {
                    release(output);
                    output = openOutput();
                    outputPaused = false;
                    if (output == null) {
                        awaitWake(POLL_MILLIS);
                        continue;
                    }
                    if (outputOpenedOnce) {
                        restarts.incrementAndGet();
                    } else {
                        outputOpenedOnce = true;
                    }
                    if (activeQueue == null || sampleRate != output.sampleRate()) {
                        activeQueue = new BoundedPcmQueue(output.sampleRate());
                        queue = activeQueue;
                    } else {
                        activeQueue.clear();
                    }
                    sampleRate = output.sampleRate();
                    AudioStats outputStats = output.audioStats();
                    minimumBufferBytes = outputStats.minimumBufferBytes();
                    configuredBufferBytes = outputStats.configuredBufferBytes();
                    actualBufferBytes = outputStats.actualBufferBytes();
                    if (diagnostics != null) {
                        diagnostics.audioStats(outputStats);
                    }
                    hasProducedPcm = false;
                    if (!paused) {
                        output.play();
                    }
                    continue;
                }

                if (paused) {
                    if (!outputPaused) {
                        output.pause();
                        output.flush();
                        outputPaused = true;
                    }
                    awaitWake(POLL_MILLIS);
                    continue;
                }
                if (outputPaused) {
                    output.play();
                    outputPaused = false;
                }

                BoundedPcmQueue.Frame frame = activeQueue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    if (hasProducedPcm) {
                        underruns.incrementAndGet();
                    }
                    continue;
                }
                try {
                    if (!writeFully(output, frame)) {
                        reopenRequested.set(true);
                    }
                } finally {
                    activeQueue.release(frame);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            queue = null;
            sampleRate = 0;
            minimumBufferBytes = 0;
            configuredBufferBytes = 0;
            actualBufferBytes = 0;
            release(output);
        }
    }

    private Output openOutput() {
        try {
            return outputFactory.open();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private boolean writeFully(Output output, BoundedPcmQueue.Frame frame) {
        int offset = 0;
        while (offset < frame.length() && running.get() && !paused) {
            int written = output.write(frame.bytes(), offset, frame.length() - offset);
            if (written <= 0) {
                return false;
            }
            offset += written;
        }
        return offset == frame.length();
    }

    private void clearQueuedPcm() {
        BoundedPcmQueue active = queue;
        if (active != null) {
            active.clear();
        }
        hasProducedPcm = false;
    }

    private void awaitWake(long timeoutMillis) {
        synchronized (wakeLock) {
            try {
                wakeLock.wait(timeoutMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wakeConsumer() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    private static void release(Output output) {
        if (output == null) {
            return;
        }
        try {
            output.pause();
        } catch (RuntimeException ignored) {
            // A disconnected output may already be unusable.
        }
        try {
            output.flush();
        } catch (RuntimeException ignored) {
            // A disconnected output may already be unusable.
        }
        try {
            output.release();
        } catch (RuntimeException ignored) {
            // Release must not retain the service because a route vanished during teardown.
        }
    }
}
