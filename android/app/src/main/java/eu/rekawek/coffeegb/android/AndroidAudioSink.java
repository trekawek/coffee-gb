package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
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

        /** Playback head in stereo PCM sample frames, or -1 when unavailable. */
        default long playbackPositionFrames() {
            return -1L;
        }

        /** Cumulative AudioTrack underruns, or -1 when the output does not expose the metric. */
        default long outputUnderrunCount() {
            return -1L;
        }

        default boolean isPlaying() {
            return true;
        }

        void play();

        void pause();

        void flush();

        /** Returns the bytes written, or a non-positive error code. */
        int write(byte[] bytes, int offset, int length);

        void release();
    }

    record Stats(int sampleRate, long overruns, long underruns, long outputUnderruns,
                 long restarts, boolean paused,
                 boolean active, int minimumBufferBytes, int configuredBufferBytes,
                 int actualBufferBytes, long pcmInputEvents, long pcmInputFrames,
                 long pcmEnqueuedBytes, long pcmEnqueuedFrames, long pcmWrittenBytes,
                 long pcmWrittenFrames, long writeFailures, long pcmDiscardedBytes,
                 long pcmPendingBytes, long pcmQueuedBytes, int queuedFrames,
                 boolean outputOpen, boolean outputPlaying, boolean muted, int volume,
                 long routeFailures, long playbackPositionFrames, int systemVolume,
                 int systemVolumeMax, boolean systemMusicMuted, int queueCapacityFrames,
                 int maximumFrameBytes) {
    }

    /** Absolute audio counters captured at the host ARM boundary; no queue mutation occurs. */
    record AudioBaseline(long inputEvents, long inputFrames, long enqueuedBytes, long enqueuedFrames,
                         long writtenBytes, long writtenFrames, long writeFailures,
                         long discardedBytes, long pendingBytes, long queuedBytes,
                         long playbackPositionFrames, long overruns, long underruns,
                         long outputUnderruns, long restarts,
                         long routeFailures, boolean outputOpen, boolean outputPlaying,
                         int sampleRate, int queueCapacityFrames, int maximumFrameBytes,
                         boolean active, boolean paused, boolean muted, int volume,
                         int systemVolume, int systemVolumeMax, boolean systemMusicMuted,
                         int queuedFrames, boolean reopenPending, long outputIdentity,
                         long queueIdentity) {
        /** Source-compatible constructor retained for existing benchmark fixtures. */
        AudioBaseline(long inputEvents, long inputFrames, long enqueuedBytes, long enqueuedFrames,
                long writtenBytes, long writtenFrames, long writeFailures,
                long discardedBytes, long pendingBytes, long queuedBytes,
                long playbackPositionFrames, long overruns, long underruns,
                long outputUnderruns, long restarts, long routeFailures,
                boolean outputOpen, boolean outputPlaying, int sampleRate,
                int queueCapacityFrames, int maximumFrameBytes) {
            this(inputEvents, inputFrames, enqueuedBytes, enqueuedFrames, writtenBytes,
                    writtenFrames, writeFailures, discardedBytes, pendingBytes, queuedBytes,
                    playbackPositionFrames, overruns, underruns, outputUnderruns, restarts,
                    routeFailures, outputOpen, outputPlaying, sampleRate, queueCapacityFrames,
                    maximumFrameBytes, outputOpen, false, false, 100, 1, 1, false,
                    queuedBytes > 0L ? 1 : 0, false, outputOpen ? 1L : 0L,
                    queueCapacityFrames > 0 ? 1L : 0L);
        }

        static AudioBaseline unavailable() {
            return new AudioBaseline(-1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L,
                    -1L, -1L, -1L, -1L, -1L, -1L, -1L, false, false, -1, -1, -1,
                    false, true, true, 0, -1, -1, true, -1, true, 0L, 0L);
        }
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
    /** Monotonic identity fence for observational benchmark snapshots. */
    private final AtomicLong routeGeneration = new AtomicLong();
    /** Benchmark-only ledger.  Release R8 removes its implementation and all guarded call sites. */
    private final PcmAccounting pcmAccounting = BuildConfig.DIAGNOSTICS_ENABLED
            ? new DiagnosticPcmAccounting() : NoOpPcmAccounting.INSTANCE;
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
    /** Source cadence is latched by the controller's HardwareProfile event. */
    private volatile ClockSpec sourceClock = ClockSpec.LEGACY;
    private volatile boolean hasProducedPcm;
    private volatile int sampleRate;
    private volatile int minimumBufferBytes;
    private volatile int configuredBufferBytes;
    private volatile int actualBufferBytes;
    private volatile boolean outputOpen;
    private volatile boolean outputPlaying;
    private volatile Output activeOutput;
    private volatile long playbackPositionFrames = -1L;
    private volatile long outputUnderruns = -1L;
    private volatile int systemVolume = -1;
    private volatile int systemVolumeMax = -1;
    private volatile boolean systemMusicMuted = true;
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

    /** Captures absolute benchmark audio counters at ARM without flushing or reopening output. */
    AudioBaseline benchmarkBaseline() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return AudioBaseline.unavailable();
        }
        try {
            synchronized (pcmAccounting) {
                long generation = routeGeneration.get();
                Output output = activeOutput;
                BoundedPcmQueue active = queue;
                updatePlaybackEvidence(output);
                PcmSnapshot snapshot = pcmAccounting.snapshot();
                boolean baselineOutputOpen = outputOpen;
                boolean baselineOutputPlaying = outputPlaying;
                boolean baselineReopenPending = reopenRequested.get();
                long outputIdentity = output == null ? 0L
                        : Integer.toUnsignedLong(System.identityHashCode(output));
                long queueIdentity = active == null ? 0L
                        : Integer.toUnsignedLong(System.identityHashCode(active));
                AudioBaseline baseline = new AudioBaseline(
                        snapshot.inputEvents(), snapshot.inputFrames(),
                        snapshot.enqueuedBytes(), snapshot.enqueuedFrames(), snapshot.writtenBytes(),
                        snapshot.writtenFrames(), snapshot.writeFailures(), snapshot.discardedBytes(),
                        snapshot.pendingBytes(), active == null ? 0L : active.queuedBytes(),
                        playbackPositionFrames, active == null ? 0L : active.overruns(),
                        underruns.get(), outputUnderruns, restarts.get(), snapshot.routeFailures(),
                        baselineOutputOpen, baselineOutputPlaying, sampleRate,
                        active == null ? 0 : active.capacityFrames(),
                        active == null ? 0 : active.maximumFrameBytes(), running.get(), paused, muted,
                        volume, systemVolume, systemVolumeMax, systemMusicMuted,
                        active == null ? 0 : active.queuedFrames(), baselineReopenPending,
                        outputIdentity, queueIdentity);
                if (routeGeneration.get() != generation
                        || activeOutput != output || queue != active
                        || outputOpen != baselineOutputOpen
                        || outputPlaying != baselineOutputPlaying
                        || reopenRequested.get() != baselineReopenPending) {
                    return AudioBaseline.unavailable();
                }
                return baseline;
            }
        } catch (RuntimeException unavailable) {
            // The consumer may release/rebuild an AudioTrack or queue concurrently with the
            // observational ARM read. Fail closed instead of publishing a mixed-session proof.
            return AudioBaseline.unavailable();
        }
    }

    void setMuted(boolean nextMuted) {
        muted = nextMuted;
        clearQueuedPcm();
        wakeConsumer();
    }

    /**
     * Selects the fixed source-buffer size used by the next audio queue. Profile events arrive
     * before the first sound event; if the worker already opened its default queue, rebuild it
     * without ever allocating from the controller callback.
     */
    void setClockSpec(ClockSpec nextClock) {
        ClockSpec checked = Objects.requireNonNull(nextClock, "nextClock");
        if (sourceClock.equals(checked)) {
            return;
        }
        sourceClock = checked;
        if (running.get()) {
            reopenRequested.set(true);
            clearQueuedPcm();
            wakeConsumer();
        }
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
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            synchronized (pcmAccounting) {
                return diagnosticStats(active);
            }
        }
        return new Stats(sampleRate, active == null ? 0 : active.overruns(), underruns.get(),
                -1L, restarts.get(), paused, running.get(), minimumBufferBytes, configuredBufferBytes,
                actualBufferBytes, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                active == null ? 0 : active.queuedFrames(), false, false, muted, volume, 0L,
                -1L, -1, -1, true, active == null ? 0 : active.capacityFrames(),
                active == null ? 0 : active.maximumFrameBytes());
    }

    private Stats diagnosticStats(BoundedPcmQueue active) {
        updatePlaybackEvidence(activeOutput);
        PcmSnapshot snapshot = pcmAccounting.snapshot();
        return new Stats(sampleRate, active == null ? 0 : active.overruns(), underruns.get(),
                outputUnderruns, restarts.get(), paused, running.get(), minimumBufferBytes, configuredBufferBytes,
                actualBufferBytes, snapshot.inputEvents(), snapshot.inputFrames(),
                snapshot.enqueuedBytes(), snapshot.enqueuedFrames(), snapshot.writtenBytes(),
                snapshot.writtenFrames(), snapshot.writeFailures(), snapshot.discardedBytes(),
                snapshot.pendingBytes(), active == null ? 0L : active.queuedBytes(),
                active == null ? 0 : active.queuedFrames(), outputOpen, outputPlaying, muted,
                volume, snapshot.routeFailures(), playbackPositionFrames, systemVolume,
                systemVolumeMax, systemMusicMuted, active == null ? 0 : active.capacityFrames(),
                active == null ? 0 : active.maximumFrameBytes());
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

    int sourceSamplesForTesting() {
        BoundedPcmQueue active = queue;
        return active == null ? 0 : active.maximumSourceSamples();
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
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            synchronized (pcmAccounting) {
                pcmAccounting.input(event.buffer().length / 2L);
                int bytes = active.offer(event, volume, muted);
                accountQueueDiscards(active);
                if (bytes > 0) {
                    pcmAccounting.enqueued(bytes, bytes / 4L);
                }
            }
        } else {
            active.offer(event, volume, muted);
        }
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
                    if (BuildConfig.DIAGNOSTICS_ENABLED && output != null) {
                        outputOpen = false;
                        outputPlaying = false;
                    }
                    release(output);
                    output = openOutput();
                    activeOutput = output;
                    routeGeneration.incrementAndGet();
                    outputPaused = false;
                    if (output == null) {
                        if (BuildConfig.DIAGNOSTICS_ENABLED) {
                            outputOpen = false;
                            outputPlaying = false;
                        }
                        awaitWake(POLL_MILLIS);
                        continue;
                    }
                    if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        outputOpen = true;
                        outputPlaying = false;
                    }
                    if (outputOpenedOnce) {
                        restarts.incrementAndGet();
                    } else {
                        outputOpenedOnce = true;
                    }
                    ClockSpec queueClock = sourceClock;
                    int expectedSourceSamples = Math.multiplyExact(
                            queueClock.controllerTicksPerFrame(), 2);
                    if (activeQueue == null || sampleRate != output.sampleRate()
                            || activeQueue.maximumSourceSamples() != expectedSourceSamples) {
                        if (BuildConfig.DIAGNOSTICS_ENABLED && activeQueue != null) {
                            clearAndAccount(activeQueue);
                        } else if (activeQueue != null) {
                            activeQueue.clear();
                        }
                        activeQueue = new BoundedPcmQueue(output.sampleRate(), queueClock);
                        queue = activeQueue;
                        routeGeneration.incrementAndGet();
                    } else {
                        if (BuildConfig.DIAGNOSTICS_ENABLED) {
                            clearAndAccount(activeQueue);
                        } else {
                            activeQueue.clear();
                        }
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
                        if (BuildConfig.DIAGNOSTICS_ENABLED) {
                            outputPlaying = true;
                        }
                    }
                    continue;
                }

                if (paused) {
                    if (!outputPaused) {
                        output.pause();
                        output.flush();
                        outputPaused = true;
                        if (BuildConfig.DIAGNOSTICS_ENABLED) {
                            outputPlaying = false;
                        }
                    }
                    awaitWake(POLL_MILLIS);
                    continue;
                }
                if (outputPaused) {
                    output.play();
                    outputPaused = false;
                    if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        outputPlaying = true;
                    }
                }

                BoundedPcmQueue.Frame frame = activeQueue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    if (hasProducedPcm) {
                        underruns.incrementAndGet();
                    }
                    continue;
                }
                try {
                    if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        synchronized (pcmAccounting) {
                            pcmAccounting.adjustEnqueued(
                                    frame.length() - frame.accountingBytes(),
                                    frame.length() / 4L - frame.accountingBytes() / 4L);
                            accountQueueDiscards(activeQueue);
                        }
                    }
                    if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        WriteResult result = writeFullyWithAccounting(output, frame);
                        synchronized (pcmAccounting) {
                            if (result.bytesWritten() > 0) {
                                pcmAccounting.written(result.bytesWritten(),
                                        result.complete() ? frame.length() / 4L : 0L);
                            }
                            if (!result.complete()) {
                                int discarded = frame.length() - result.bytesWritten();
                                if (discarded > 0) {
                                    pcmAccounting.discarded(discarded);
                                }
                                pcmAccounting.writeFailure();
                                reopenRequested.set(true);
                            }
                        }
                    } else {
                        if (!writeFully(output, frame)) {
                            reopenRequested.set(true);
                        }
                    }
                } finally {
                    activeQueue.release(frame);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (BuildConfig.DIAGNOSTICS_ENABLED && activeQueue != null) {
                clearAndAccount(activeQueue);
            } else if (activeQueue != null) {
                activeQueue.clear();
            }
            queue = null;
            activeOutput = null;
            routeGeneration.incrementAndGet();
            sampleRate = 0;
            minimumBufferBytes = 0;
            configuredBufferBytes = 0;
            actualBufferBytes = 0;
            outputUnderruns = -1L;
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                outputOpen = false;
                outputPlaying = false;
            }
            release(output);
        }
    }

    private void updatePlaybackEvidence(Output output) {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        if (output == null) {
            playbackPositionFrames = -1L;
            outputUnderruns = -1L;
            outputPlaying = false;
        } else {
            playbackPositionFrames = output.playbackPositionFrames();
            try {
                outputUnderruns = output.outputUnderrunCount();
            } catch (RuntimeException unavailable) {
                outputUnderruns = -1L;
            }
            try {
                outputPlaying = output.isPlaying();
            } catch (RuntimeException unavailable) {
                outputPlaying = false;
            }
        }
        if (audioManager == null) {
            systemVolume = -1;
            systemVolumeMax = -1;
            systemMusicMuted = true;
            return;
        }
        try {
            systemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            systemVolumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            systemMusicMuted = android.os.Build.VERSION.SDK_INT >= 23
                    && audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException unavailable) {
            systemVolume = -1;
            systemVolumeMax = -1;
            systemMusicMuted = true;
        }
    }

    private Output openOutput() {
        try {
            return outputFactory.open();
        } catch (RuntimeException unavailable) {
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                synchronized (pcmAccounting) {
                    pcmAccounting.routeFailure();
                }
            }
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

    private WriteResult writeFullyWithAccounting(Output output, BoundedPcmQueue.Frame frame) {
        int offset = 0;
        while (offset < frame.length() && running.get() && !paused) {
            int written = output.write(frame.bytes(), offset, frame.length() - offset);
            if (written <= 0) {
                return new WriteResult(false, offset);
            }
            offset += written;
        }
        return new WriteResult(offset == frame.length(), offset);
    }

    private void clearQueuedPcm() {
        BoundedPcmQueue active = queue;
        if (active != null) {
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                clearAndAccount(active);
            } else {
                active.clear();
            }
        }
        hasProducedPcm = false;
    }

    private void clearAndAccount(BoundedPcmQueue active) {
        synchronized (pcmAccounting) {
            active.clear();
            accountQueueDiscards(active);
        }
    }

    private void accountQueueDiscards(BoundedPcmQueue active) {
        long discarded = active.drainDiscardedBytes();
        if (discarded > 0L) {
            pcmAccounting.discarded(discarded);
        }
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

    private interface PcmAccounting {
        void input(long frames);

        void enqueued(long bytes, long frames);

        void adjustEnqueued(long bytes, long frames);

        void written(long bytes, long frames);

        void discarded(long bytes);

        void writeFailure();

        void routeFailure();

        PcmSnapshot snapshot();
    }

    /** No-op implementation keeps the ordinary audio path free of benchmark state. */
    private static final class NoOpPcmAccounting implements PcmAccounting {
        private static final NoOpPcmAccounting INSTANCE = new NoOpPcmAccounting();

        @Override
        public void input(long frames) {
        }

        @Override
        public void enqueued(long bytes, long frames) {
        }

        @Override
        public void adjustEnqueued(long bytes, long frames) {
        }

        @Override
        public void written(long bytes, long frames) {
        }

        @Override
        public void discarded(long bytes) {
        }

        @Override
        public void writeFailure() {
        }

        @Override
        public void routeFailure() {
        }

        @Override
        public PcmSnapshot snapshot() {
            return PcmSnapshot.EMPTY;
        }
    }

    /** Plain counters are safe because callers serialize the benchmark ledger on this object. */
    private static final class DiagnosticPcmAccounting implements PcmAccounting {
        private long inputEvents;
        private long inputFrames;
        private long enqueuedBytes;
        private long enqueuedFrames;
        private long writtenBytes;
        private long writtenFrames;
        private long writeFailures;
        private long discardedBytes;
        private long pendingBytes;
        private long routeFailures;

        @Override
        public void input(long frames) {
            inputEvents++;
            inputFrames += frames;
        }

        @Override
        public void enqueued(long bytes, long frames) {
            enqueuedBytes += bytes;
            enqueuedFrames += frames;
            pendingBytes += bytes;
        }

        @Override
        public void adjustEnqueued(long bytes, long frames) {
            enqueuedBytes += bytes;
            enqueuedFrames += frames;
            pendingBytes += bytes;
        }

        @Override
        public void written(long bytes, long frames) {
            writtenBytes += bytes;
            writtenFrames += frames;
            pendingBytes -= bytes;
        }

        @Override
        public void discarded(long bytes) {
            discardedBytes += bytes;
            pendingBytes -= bytes;
        }

        @Override
        public void writeFailure() {
            writeFailures++;
        }

        @Override
        public void routeFailure() {
            routeFailures++;
        }

        @Override
        public PcmSnapshot snapshot() {
            return new PcmSnapshot(inputEvents, inputFrames, enqueuedBytes, enqueuedFrames,
                    writtenBytes, writtenFrames, writeFailures, discardedBytes, pendingBytes,
                    routeFailures);
        }
    }

    private record PcmSnapshot(long inputEvents, long inputFrames, long enqueuedBytes,
            long enqueuedFrames, long writtenBytes, long writtenFrames, long writeFailures,
            long discardedBytes, long pendingBytes, long routeFailures) {
        private static final PcmSnapshot EMPTY = new PcmSnapshot(0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L);
    }

    private record WriteResult(boolean complete, int bytesWritten) {
    }
}
