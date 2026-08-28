package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded Android host-audio adapter. Emulator events only fill preallocated PCM slots; all
 * {@code AudioTrack} opening, writes, flushes, and release work on this adapter's consumer thread.
 */
final class AndroidAudioSink implements AutoCloseable {

    /** Four real controller packets cover roughly 65-67 ms for every supported clock profile. */
    static final int PRIMER_PACKETS = 4;

    interface OutputFactory {
        Output open();
    }

    /** Package-private deterministic seam for the queue-swap concurrency test. */
    interface QueueSwapHook {
        void beforeRateChangingSwap();
    }

    /** Package-private deterministic seam for boundary-publication concurrency tests. */
    interface BoundaryClearHook {
        void beforeQueuedPcmClear();
    }

    record AudioStats(int sampleRate, int minimumBufferBytes, int configuredBufferBytes,
                      int actualBufferBytes) {
    }

    interface Output {
        int sampleRate();

        default AudioStats audioStats() {
            return new AudioStats(sampleRate(), 0, 0, 0);
        }

        /** Maximum application-write reservoir in stereo PCM sample frames. */
        default int bufferCapacityFrames() {
            return Math.max(0, audioStats().actualBufferBytes() / 4);
        }

        /** Current application-write reservoir in stereo PCM sample frames. */
        default int effectiveBufferFrames() {
            return bufferCapacityFrames();
        }

        /** Refill level at which a streaming output is expected to restart after underrun. */
        default int startThresholdFrames() {
            return effectiveBufferFrames();
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
    /** Shorter than the six-slot source runway, so a dead output cannot force a queue overrun. */
    private static final long UNDERRUN_STALL_MILLIS = 60L;
    private static final long CLOSE_TIMEOUT_MILLIS = 750L;

    private final OutputFactory outputFactory;
    private final AudioManager audioManager;
    private final boolean enabled;
    private final AndroidBenchmarkDiagnostics diagnostics;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reopenRequested = new AtomicBoolean();
    /** Sticky flush command; unlike the paused level, every pause request is observed once. */
    private final AtomicLong pauseFlushGeneration = new AtomicLong();
    /** Monotonic fence for PCM rendered with an obsolete mute/volume policy. */
    private final AtomicLong pcmPolicyGeneration = new AtomicLong();
    private final AtomicLong underruns = new AtomicLong();
    private final AtomicLong restarts = new AtomicLong();
    /** Monotonic identity fence for observational benchmark snapshots. */
    private final AtomicLong routeGeneration = new AtomicLong();
    /** Benchmark-only ledger.  Release R8 removes its implementation and all guarded call sites. */
    private final PcmAccounting pcmAccounting = BuildConfig.DIAGNOSTICS_ENABLED
            ? new DiagnosticPcmAccounting() : NoOpPcmAccounting.INSTANCE;
    private final Object wakeLock = new Object();
    /** Linearizes play() with pause/policy/reopen boundaries. */
    private final Object playbackControlLock = new Object();
    /** Serializes producer offers with a sample-rate-changing queue replacement. */
    private final Object queueLock = new Object();
    private final QueueSwapHook queueSwapHook;
    private final BoundaryClearHook boundaryClearHook;
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (containsOutputSink(addedDevices)) {
                requestRouteReopen();
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (containsOutputSink(removedDevices)) {
                requestRouteReopen();
            }
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
    /** Diagnostics-only exception for the legacy paused pre-ARM host-output proof. */
    private final AtomicBoolean benchmarkEmptyPlayRequested = new AtomicBoolean();
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
                true, null, null, null);
    }

    AndroidAudioSink(Context context, EventBus eventBus,
            AndroidBenchmarkDiagnostics diagnostics) {
        this(eventBus, new AndroidAudioTrackOutput.Factory(),
                Objects.requireNonNull(context, "context").getSystemService(AudioManager.class),
                true, diagnostics, null, null);
    }

    AndroidAudioSink(EventBus eventBus, OutputFactory outputFactory) {
        this(eventBus, outputFactory, null, true, null, null, null);
    }

    /** Benchmark JVM seam; ordinary construction cannot authorize empty AudioTrack playback. */
    AndroidAudioSink(EventBus eventBus, OutputFactory outputFactory,
            AndroidBenchmarkDiagnostics diagnostics) {
        this(eventBus, outputFactory, null, true, diagnostics, null, null);
    }

    AndroidAudioSink(EventBus eventBus, OutputFactory outputFactory, QueueSwapHook queueSwapHook) {
        this(eventBus, outputFactory, null, true, null,
                Objects.requireNonNull(queueSwapHook, "queueSwapHook"), null);
    }

    AndroidAudioSink(EventBus eventBus, OutputFactory outputFactory,
            QueueSwapHook queueSwapHook, BoundaryClearHook boundaryClearHook) {
        this(eventBus, outputFactory, null, true, null, queueSwapHook,
                Objects.requireNonNull(boundaryClearHook, "boundaryClearHook"));
    }

    private AndroidAudioSink(EventBus eventBus, OutputFactory outputFactory, AudioManager audioManager,
            boolean enabled, AndroidBenchmarkDiagnostics diagnostics, QueueSwapHook queueSwapHook,
            BoundaryClearHook boundaryClearHook) {
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
        this.audioManager = audioManager;
        this.enabled = enabled;
        this.diagnostics = diagnostics;
        this.queueSwapHook = queueSwapHook;
        this.boundaryClearHook = boundaryClearHook;
        EventBus bus = Objects.requireNonNull(eventBus, "eventBus");
        if (enabled) {
            bus.register(this::onSoundSample, Sound.SoundSampleEvent.class);
            bus.register(event -> setMuted(!event.enabled()), Sound.SoundEnabledEvent.class);
        }
    }

    static AndroidAudioSink disabled(EventBus eventBus) {
        return new AndroidAudioSink(eventBus, () -> null, null, false, null, null, null);
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
        synchronized (playbackControlLock) {
            paused = true;
            benchmarkEmptyPlayRequested.set(false);
            pauseFlushGeneration.incrementAndGet();
            clearQueuedPcmAtBoundary();
        }
        wakeConsumer();
    }

    void resume() {
        synchronized (playbackControlLock) {
            if (benchmarkEmptyPlayRequested.getAndSet(false)) {
                pauseFlushGeneration.incrementAndGet();
            }
            paused = false;
        }
        wakeConsumer();
    }

    /**
     * Preserves the benchmark's existing stopped/empty-to-playing pre-ARM proof while the guest is
     * paused and cannot produce primer packets.  This is intentionally unavailable to production
     * playback; all ordinary resume paths wait for the full genuine-PCM primer.
     */
    void resumeEmptyForBenchmarkPreArm() {
        if (diagnostics == null || !diagnostics.enabled()) {
            throw new IllegalStateException("Empty audio playback is benchmark-only");
        }
        synchronized (playbackControlLock) {
            if (!paused) {
                throw new IllegalStateException("Benchmark empty playback requires a paused sink");
            }
            benchmarkEmptyPlayRequested.set(true);
            paused = false;
        }
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
        synchronized (playbackControlLock) {
            if (muted == nextMuted) {
                return;
            }
            muted = nextMuted;
            pcmPolicyGeneration.incrementAndGet();
            clearQueuedPcmAtBoundary();
        }
        wakeConsumer();
    }

    /** Updates source cadence without reopening AudioTrack or rebuilding fixed PCM storage. */
    void setClockSpec(ClockSpec nextClock) {
        ClockSpec checked = Objects.requireNonNull(nextClock, "nextClock");
        if (sourceClock.equals(checked)) {
            return;
        }
        sourceClock = checked;
    }

    void setVolume(int nextVolume) {
        if (nextVolume < 0 || nextVolume > 100) {
            throw new IllegalArgumentException("Audio volume must be between 0 and 100");
        }
        synchronized (playbackControlLock) {
            if (volume == nextVolume) {
                return;
            }
            volume = nextVolume;
            pcmPolicyGeneration.incrementAndGet();
            clearQueuedPcmAtBoundary();
        }
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
        synchronized (playbackControlLock) {
            benchmarkEmptyPlayRequested.set(false);
            reopenRequested.set(true);
        }
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
        synchronized (playbackControlLock) {
            if (!running.getAndSet(false)) {
                return;
            }
            benchmarkEmptyPlayRequested.set(false);
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
        long eventPolicyGeneration;
        long eventPauseFlushGeneration;
        int eventVolume;
        boolean eventMuted;
        synchronized (playbackControlLock) {
            if (!running.get() || paused) {
                return;
            }
            // The first real packet revokes the diagnostics-only empty-play exception.  Its
            // sticky boundary makes the worker stop/flush even if it already called play().
            if (benchmarkEmptyPlayRequested.getAndSet(false)) {
                pauseFlushGeneration.incrementAndGet();
            }
            eventPolicyGeneration = pcmPolicyGeneration.get();
            eventPauseFlushGeneration = pauseFlushGeneration.get();
            eventVolume = volume;
            eventMuted = muted;
        }
        synchronized (queueLock) {
            BoundedPcmQueue active = queue;
            if (!running.get() || active == null || paused) {
                return;
            }
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                synchronized (pcmAccounting) {
                    pcmAccounting.input(event.buffer().length / 2L);
                    int bytes = active.offer(event, eventVolume, eventMuted,
                            eventPolicyGeneration, eventPauseFlushGeneration);
                    accountQueueDiscards(active);
                    if (bytes > 0) {
                        pcmAccounting.enqueued(bytes, bytes / 4L);
                    }
                }
            } else {
                active.offer(event, eventVolume, eventMuted, eventPolicyGeneration,
                        eventPauseFlushGeneration);
            }
            hasProducedPcm = true;
        }
        wakeConsumer();
    }

    private void runConsumer() {
        preferAudioThreadPriority();
        Output output = null;
        BoundedPcmQueue activeQueue = null;
        List<PendingFrame> primerFrames = new ArrayList<>(PRIMER_PACKETS + 1);
        boolean outputStarted = false;
        boolean outputOpenedOnce = false;
        UnderrunRecovery underrunRecovery = new UnderrunRecovery();
        OutputBufferLimits outputBufferLimits = OutputBufferLimits.UNAVAILABLE;
        long appliedPauseFlushGeneration = pauseFlushGeneration.get();
        long appliedPcmPolicyGeneration = pcmPolicyGeneration.get();
        long observedOutputUnderruns = -1L;
        OutputProgress outputProgress = new OutputProgress();
        int primerReplayIndex = 0;
        int primerPackets = 0;
        int primerBytes = 0;
        try {
            while (running.get()) {
                long requestedPauseFlushGeneration = pauseFlushGeneration.get();
                long requestedPcmPolicyGeneration = pcmPolicyGeneration.get();
                if (requestedPauseFlushGeneration != appliedPauseFlushGeneration
                        || requestedPcmPolicyGeneration != appliedPcmPolicyGeneration) {
                    appliedPauseFlushGeneration = requestedPauseFlushGeneration;
                    appliedPcmPolicyGeneration = requestedPcmPolicyGeneration;
                    if (activeQueue != null) {
                        discardPrimerFrames(activeQueue, primerFrames);
                    } else {
                        primerFrames.clear();
                    }
                    primerReplayIndex = 0;
                    primerPackets = 0;
                    primerBytes = 0;
                    outputStarted = false;
                    underrunRecovery.reset();
                    if (resetOutputForPcmBoundary(output)) {
                        outputProgress.resetAfterFlush(output);
                        observedOutputUnderruns = sampleOutputUnderruns(output,
                                observedOutputUnderruns);
                    }
                    continue;
                }
                boolean reopen = reopenRequested.getAndSet(false);
                if (output == null || reopen) {
                    if (BuildConfig.DIAGNOSTICS_ENABLED && output != null) {
                        outputOpen = false;
                        outputPlaying = false;
                    }
                    resetPendingFramesForReplacement(primerFrames);
                    release(output);
                    output = openOutput();
                    activeOutput = output;
                    routeGeneration.incrementAndGet();
                    outputStarted = false;
                    underrunRecovery.reset();
                    outputBufferLimits = OutputBufferLimits.UNAVAILABLE;
                    primerReplayIndex = 0;
                    primerPackets = 0;
                    primerBytes = 0;
                    if (output == null) {
                        if (BuildConfig.DIAGNOSTICS_ENABLED) {
                            outputOpen = false;
                            outputPlaying = false;
                        }
                        awaitWake(POLL_MILLIS);
                        continue;
                    }
                    int openedSampleRate;
                    AudioStats outputStats;
                    try {
                        openedSampleRate = output.sampleRate();
                        outputStats = output.audioStats();
                        outputBufferLimits = readOutputBufferLimits(output);
                    } catch (RuntimeException unavailable) {
                        accountRouteFailure();
                        release(output);
                        output = null;
                        activeOutput = null;
                        routeGeneration.incrementAndGet();
                        awaitWake(POLL_MILLIS);
                        continue;
                    }
                    if (activeQueue == null || sampleRate != openedSampleRate) {
                        synchronized (queueLock) {
                            if (activeQueue != null) {
                                if (queueSwapHook != null) {
                                    queueSwapHook.beforeRateChangingSwap();
                                }
                                discardPrimerFrames(activeQueue, primerFrames);
                                clearQueue(activeQueue);
                            }
                            activeQueue = new BoundedPcmQueue(openedSampleRate, sourceClock);
                            queue = activeQueue;
                        }
                        routeGeneration.incrementAndGet();
                    }
                    sampleRate = openedSampleRate;
                    minimumBufferBytes = outputStats.minimumBufferBytes();
                    configuredBufferBytes = outputStats.configuredBufferBytes();
                    actualBufferBytes = outputStats.actualBufferBytes();
                    if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        outputOpen = true;
                        outputPlaying = false;
                    }
                    if (outputOpenedOnce) {
                        restarts.incrementAndGet();
                    } else {
                        outputOpenedOnce = true;
                    }
                    if (diagnostics != null) {
                        diagnostics.audioStats(outputStats);
                    }
                    outputProgress.open(output);
                    observedOutputUnderruns = sampleOutputUnderruns(output, -1L);
                    continue;
                }

                if (paused) {
                    awaitWake(POLL_MILLIS);
                    continue;
                }

                if (underrunRecovery.active()) {
                    if (reopenRequested.get()) {
                        continue;
                    }
                    long playedFrames = outputProgress.readPlaybackPosition(output);
                    if (playedFrames < 0L) {
                        // Without a trustworthy head, bounded refill progress cannot be proven.
                        accountRouteFailure();
                        requestRecoveryReopen();
                        underrunRecovery.reset();
                        continue;
                    }
                    long latestOutputUnderruns = sampleOutputUnderruns(output,
                            observedOutputUnderruns);
                    if (hasOutputUnderrunIncreased(observedOutputUnderruns,
                            latestOutputUnderruns)) {
                        observedOutputUnderruns = latestOutputUnderruns;
                        if (!rebaseUnderrunRecovery(output, outputProgress,
                                outputBufferLimits, underrunRecovery)) {
                            accountRouteFailure();
                            requestRecoveryReopen();
                            underrunRecovery.reset();
                        }
                        continue;
                    }
                    observedOutputUnderruns = latestOutputUnderruns;
                    long bufferedFrames = outputProgress.bufferedFrames(playedFrames);
                    if (bufferedFrames < 0L) {
                        accountRouteFailure();
                        requestRecoveryReopen();
                        underrunRecovery.reset();
                        continue;
                    }
                    underrunRecovery.observeRefillProgress(outputProgress.acceptedFrames(),
                            bufferedFrames, outputBufferLimits.startThresholdFrames());
                    if (underrunRecovery.hasRestarted(playedFrames)
                            && primerFrames.isEmpty()) {
                        // AudioTrack restarts itself after its data path reaches the threshold.
                        // Keep every accepted byte in place and resume the ordinary write stream.
                        underrunRecovery.reset();
                        continue;
                    }
                    int effectiveFrames = outputBufferLimits.effectiveBufferFrames();
                    if (bufferedFrames >= effectiveFrames) {
                        if (underrunRecovery.capacityHasStalled(System.nanoTime())) {
                            // A blocking write beyond known free capacity could deadlock forever.
                            // Replace only after a full reservoir still produces no head progress.
                            accountRouteFailure();
                            requestRecoveryReopen();
                            underrunRecovery.reset();
                        } else {
                            awaitWake(POLL_MILLIS);
                        }
                        continue;
                    }

                    underrunRecovery.clearCapacityStall();
                    PendingFrame pending;
                    if (!primerFrames.isEmpty()) {
                        pending = primerFrames.get(0);
                    } else {
                        BoundedPcmQueue.Frame frame = activeQueue.poll(
                                POLL_MILLIS, TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            continue;
                        }
                        reconcileFrameAccounting(activeQueue, frame);
                        long latestPauseFlushGeneration = pauseFlushGeneration.get();
                        long latestPcmPolicyGeneration = pcmPolicyGeneration.get();
                        if (latestPauseFlushGeneration != appliedPauseFlushGeneration
                                || latestPcmPolicyGeneration != appliedPcmPolicyGeneration) {
                            discardPrimerFrames(activeQueue, primerFrames);
                            if (frameMatchesBoundary(frame, latestPauseFlushGeneration,
                                    latestPcmPolicyGeneration)) {
                                primerFrames.add(new PendingFrame(frame));
                            } else {
                                discardFrame(activeQueue, frame);
                            }
                            appliedPauseFlushGeneration = latestPauseFlushGeneration;
                            appliedPcmPolicyGeneration = latestPcmPolicyGeneration;
                            primerReplayIndex = 0;
                            primerPackets = 0;
                            primerBytes = 0;
                            outputStarted = false;
                            underrunRecovery.reset();
                            if (resetOutputForPcmBoundary(output)) {
                                outputProgress.resetAfterFlush(output);
                                observedOutputUnderruns = sampleOutputUnderruns(output,
                                        observedOutputUnderruns);
                            }
                            continue;
                        }
                        if (!frameMatchesBoundary(frame, appliedPauseFlushGeneration,
                                appliedPcmPolicyGeneration)) {
                            discardFrame(activeQueue, frame);
                            continue;
                        }
                        pending = new PendingFrame(frame);
                        primerFrames.add(pending);
                    }
                    long freeFrames = effectiveFrames - bufferedFrames;
                    int writeBudget = (int) Math.min(Integer.MAX_VALUE & ~3L,
                            Math.multiplyExact(freeFrames, 4L));
                    WriteResult result = writeFrame(output, pending,
                            appliedPauseFlushGeneration, appliedPcmPolicyGeneration,
                            outputProgress, observedOutputUnderruns, false, writeBudget);
                    latestOutputUnderruns = sampleOutputUnderruns(output,
                            result.observedOutputUnderruns());
                    boolean newerOutputUnderrun = hasOutputUnderrunIncreased(
                            observedOutputUnderruns, latestOutputUnderruns);
                    observedOutputUnderruns = latestOutputUnderruns;
                    if (result.outcome() == WriteOutcome.COMPLETE) {
                        primerFrames.remove(0);
                        commitFrame(activeQueue, pending.frame());
                    } else if (result.outcome() == WriteOutcome.OUTPUT_FAILURE) {
                        accountWriteFailure();
                        requestRecoveryReopen();
                        underrunRecovery.reset();
                    }
                    if (newerOutputUnderrun
                            && (result.outcome() == WriteOutcome.COMPLETE
                                    || result.outcome() == WriteOutcome.CAPACITY_FILLED)
                            && !rebaseUnderrunRecovery(output, outputProgress,
                                    outputBufferLimits, underrunRecovery)) {
                        accountRouteFailure();
                        requestRecoveryReopen();
                        underrunRecovery.reset();
                    }
                    continue;
                }

                if (!outputStarted && benchmarkEmptyPlayRequested.get()) {
                    PlayOutcome playOutcome = playOutput(output, appliedPauseFlushGeneration,
                            appliedPcmPolicyGeneration, true);
                    if (playOutcome == PlayOutcome.STARTED) {
                        outputStarted = true;
                        if (BuildConfig.DIAGNOSTICS_ENABLED) {
                            outputPlaying = true;
                        }
                    } else if (playOutcome == PlayOutcome.OUTPUT_FAILURE) {
                        accountRouteFailure();
                        requestRecoveryReopen();
                    }
                    continue;
                }

                if (outputStarted && benchmarkEmptyPlayRequested.get()) {
                    // The diagnostics pre-ARM proof intentionally runs an empty track. Rebaseline
                    // its expected counter movement without entering production recovery.
                    outputProgress.readPlaybackPosition(output);
                    observedOutputUnderruns = sampleOutputUnderruns(output,
                            observedOutputUnderruns);
                } else if (outputStarted) {
                    // Keep the unsigned playback head extended continuously so a long-running
                    // output can cross 2^32 frames before an underrun needs refill tracking.
                    outputProgress.readPlaybackPosition(output);
                    long latestOutputUnderruns = sampleOutputUnderruns(output,
                            observedOutputUnderruns);
                    if (hasOutputUnderrunIncreased(observedOutputUnderruns,
                            latestOutputUnderruns)) {
                        observedOutputUnderruns = latestOutputUnderruns;
                        if (!rebaseUnderrunRecovery(output, outputProgress,
                                outputBufferLimits, underrunRecovery)) {
                            accountRouteFailure();
                            requestRecoveryReopen();
                        }
                        continue;
                    }
                    observedOutputUnderruns = latestOutputUnderruns;
                }

                if (!outputStarted) {
                    int requiredPrimerFrames = outputBufferLimits.startThresholdFrames();
                    PendingFrame pending;
                    if (primerReplayIndex < primerFrames.size()) {
                        pending = primerFrames.get(primerReplayIndex);
                    } else {
                        BoundedPcmQueue.Frame frame = activeQueue.poll(
                                POLL_MILLIS, TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            continue;
                        }
                        reconcileFrameAccounting(activeQueue, frame);
                        long latestPauseFlushGeneration = pauseFlushGeneration.get();
                        long latestPcmPolicyGeneration = pcmPolicyGeneration.get();
                        if (latestPauseFlushGeneration != appliedPauseFlushGeneration
                                || latestPcmPolicyGeneration != appliedPcmPolicyGeneration) {
                            discardPrimerFrames(activeQueue, primerFrames);
                            if (frameMatchesBoundary(frame, latestPauseFlushGeneration,
                                    latestPcmPolicyGeneration)) {
                                primerFrames.add(new PendingFrame(frame));
                            } else {
                                discardFrame(activeQueue, frame);
                            }
                            appliedPauseFlushGeneration = latestPauseFlushGeneration;
                            appliedPcmPolicyGeneration = latestPcmPolicyGeneration;
                            primerReplayIndex = 0;
                            primerPackets = 0;
                            primerBytes = 0;
                            outputStarted = false;
                            underrunRecovery.reset();
                            if (resetOutputForPcmBoundary(output)) {
                                outputProgress.resetAfterFlush(output);
                                observedOutputUnderruns = sampleOutputUnderruns(output,
                                        observedOutputUnderruns);
                            }
                            continue;
                        }
                        if (!frameMatchesBoundary(frame, appliedPauseFlushGeneration,
                                appliedPcmPolicyGeneration)) {
                            discardFrame(activeQueue, frame);
                            continue;
                        }
                        pending = new PendingFrame(frame);
                        primerFrames.add(pending);
                    }
                    BoundedPcmQueue.Frame frame = pending.frame();
                    int writeStartOffset = pending.writeOffset();
                    int effectiveBufferBytes = Math.multiplyExact(
                            outputBufferLimits.effectiveBufferFrames(), 4);
                    int availableBufferBytes = effectiveBufferBytes - primerBytes;
                    if (availableBufferBytes <= 0) {
                        // A stopped streaming track cannot consume more data. If its advertised
                        // start threshold is still unmet, the output contract is inconsistent.
                        accountRouteFailure();
                        requestRecoveryReopen();
                        continue;
                    }
                    WriteResult result = writeFrame(output, pending,
                            appliedPauseFlushGeneration, appliedPcmPolicyGeneration,
                            outputProgress, observedOutputUnderruns, false,
                            Math.min(frame.length() - pending.writeOffset(),
                                    availableBufferBytes));
                    observedOutputUnderruns = result.observedOutputUnderruns();
                    if (result.outcome() == WriteOutcome.OUTPUT_FAILURE) {
                        accountWriteFailure();
                        requestRecoveryReopen();
                        continue;
                    }
                    if (result.outcome() == WriteOutcome.INTERRUPTED) {
                        continue;
                    }
                    if (result.outcome() == WriteOutcome.OUTPUT_UNDERRUN) {
                        if (!rebaseUnderrunRecovery(output, outputProgress,
                                outputBufferLimits, underrunRecovery)) {
                            accountRouteFailure();
                            requestRecoveryReopen();
                        }
                        continue;
                    }
                    primerBytes += pending.writeOffset() - writeStartOffset;
                    if (result.outcome() == WriteOutcome.COMPLETE && writeStartOffset == 0) {
                        primerPackets++;
                    }
                    if (result.outcome() == WriteOutcome.COMPLETE) {
                        primerReplayIndex++;
                    }
                    if (primerPackets < PRIMER_PACKETS
                            || primerBytes / 4 < requiredPrimerFrames) {
                        continue;
                    }
                    PlayOutcome playOutcome = playOutput(output, appliedPauseFlushGeneration,
                            appliedPcmPolicyGeneration, false);
                    if (playOutcome == PlayOutcome.STALE_BOUNDARY) {
                        continue;
                    }
                    if (playOutcome == PlayOutcome.OUTPUT_FAILURE) {
                        accountRouteFailure();
                        requestRecoveryReopen();
                        continue;
                    }
                    if (playOutcome == PlayOutcome.STARTED) {
                        outputStarted = true;
                        if (BuildConfig.DIAGNOSTICS_ENABLED) {
                            outputPlaying = true;
                        }
                    }
                    commitCompletedPrimerFrames(activeQueue, primerFrames);
                    primerReplayIndex = 0;
                    primerPackets = 0;
                    primerBytes = 0;
                    continue;
                }

                if (!primerFrames.isEmpty()) {
                    // A pre-S device can require its full effective buffer before the first play,
                    // leaving an ordered suffix of the threshold-filling packet. Finish that
                    // suffix after playback has started before polling any later packet.
                    PendingFrame pending = primerFrames.get(0);
                    WriteResult result = writeFrame(output, pending,
                            appliedPauseFlushGeneration, appliedPcmPolicyGeneration,
                            outputProgress, observedOutputUnderruns, true,
                            pending.frame().length() - pending.writeOffset());
                    observedOutputUnderruns = result.observedOutputUnderruns();
                    if (result.outcome() == WriteOutcome.COMPLETE) {
                        primerFrames.remove(0);
                        commitFrame(activeQueue, pending.frame());
                    } else if (result.outcome() == WriteOutcome.OUTPUT_UNDERRUN) {
                        if (!rebaseUnderrunRecovery(output, outputProgress,
                                outputBufferLimits, underrunRecovery)) {
                            accountRouteFailure();
                            requestRecoveryReopen();
                        }
                    } else if (result.outcome() == WriteOutcome.OUTPUT_FAILURE) {
                        accountWriteFailure();
                        requestRecoveryReopen();
                    }
                    continue;
                }

                BoundedPcmQueue.Frame frame = activeQueue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    if (hasProducedPcm) {
                        underruns.incrementAndGet();
                    }
                    continue;
                }
                reconcileFrameAccounting(activeQueue, frame);
                long latestPauseFlushGeneration = pauseFlushGeneration.get();
                long latestPcmPolicyGeneration = pcmPolicyGeneration.get();
                if (latestPauseFlushGeneration != appliedPauseFlushGeneration
                        || latestPcmPolicyGeneration != appliedPcmPolicyGeneration) {
                    discardPrimerFrames(activeQueue, primerFrames);
                    if (frameMatchesBoundary(frame, latestPauseFlushGeneration,
                            latestPcmPolicyGeneration)) {
                        primerFrames.add(new PendingFrame(frame));
                    } else {
                        discardFrame(activeQueue, frame);
                    }
                    appliedPauseFlushGeneration = latestPauseFlushGeneration;
                    appliedPcmPolicyGeneration = latestPcmPolicyGeneration;
                    primerReplayIndex = 0;
                    primerPackets = 0;
                    primerBytes = 0;
                    outputStarted = false;
                    underrunRecovery.reset();
                    if (resetOutputForPcmBoundary(output)) {
                        outputProgress.resetAfterFlush(output);
                        observedOutputUnderruns = sampleOutputUnderruns(output,
                                observedOutputUnderruns);
                    }
                    continue;
                }
                if (!frameMatchesBoundary(frame, appliedPauseFlushGeneration,
                        appliedPcmPolicyGeneration)) {
                    discardFrame(activeQueue, frame);
                    continue;
                }
                PendingFrame pending = new PendingFrame(frame);
                try {
                    long latestOutputUnderruns = sampleOutputUnderruns(output,
                            observedOutputUnderruns);
                    if (hasOutputUnderrunIncreased(observedOutputUnderruns,
                            latestOutputUnderruns)) {
                        observedOutputUnderruns = latestOutputUnderruns;
                        primerFrames.add(pending);
                        frame = null;
                        if (!rebaseUnderrunRecovery(output, outputProgress,
                                outputBufferLimits, underrunRecovery)) {
                            accountRouteFailure();
                            requestRecoveryReopen();
                        }
                        continue;
                    }
                    observedOutputUnderruns = latestOutputUnderruns;
                    WriteResult result = writeFrame(output, pending,
                            appliedPauseFlushGeneration, appliedPcmPolicyGeneration,
                            outputProgress, observedOutputUnderruns, true,
                            frame.length() - pending.writeOffset());
                    observedOutputUnderruns = result.observedOutputUnderruns();
                    if (result.outcome() == WriteOutcome.COMPLETE) {
                        commitFrame(activeQueue, frame);
                        frame = null;
                    } else if (result.outcome() == WriteOutcome.OUTPUT_UNDERRUN) {
                        if (pending.writeOffset() < pending.frame().length()) {
                            primerFrames.add(pending);
                            frame = null;
                        } else {
                            commitFrame(activeQueue, frame);
                            frame = null;
                        }
                        if (!rebaseUnderrunRecovery(output, outputProgress,
                                outputBufferLimits, underrunRecovery)) {
                            accountRouteFailure();
                            requestRecoveryReopen();
                        }
                    } else {
                        // Retain the exact converted packet.  A replacement stopped track will
                        // replay it first, followed by three new ordered packets, before play().
                        primerFrames.add(pending);
                        frame = null;
                        if (result.outcome() == WriteOutcome.OUTPUT_FAILURE) {
                            accountWriteFailure();
                            requestRecoveryReopen();
                        }
                    }
                } finally {
                    if (frame != null) {
                        discardFrame(activeQueue, frame);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (activeQueue != null) {
                discardPrimerFrames(activeQueue, primerFrames);
                synchronized (queueLock) {
                    clearQueue(activeQueue);
                    if (queue == activeQueue) {
                        queue = null;
                    }
                }
            } else {
                synchronized (queueLock) {
                    queue = null;
                }
            }
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

    private static OutputBufferLimits readOutputBufferLimits(Output output) {
        int capacityFrames = output.bufferCapacityFrames();
        int effectiveFrames = output.effectiveBufferFrames();
        int startThresholdFrames = output.startThresholdFrames();
        if (capacityFrames <= 0 || effectiveFrames <= 0 || effectiveFrames > capacityFrames
                || startThresholdFrames <= 0 || startThresholdFrames > effectiveFrames) {
            throw new IllegalStateException("Invalid AudioTrack buffer limits");
        }
        return new OutputBufferLimits(capacityFrames, effectiveFrames, startThresholdFrames);
    }

    private WriteResult writeFrame(Output output, PendingFrame pending,
            long expectedPauseFlushGeneration, long expectedPcmPolicyGeneration,
            OutputProgress outputProgress, long observedOutputUnderruns,
            boolean monitorOutputUnderruns, int maximumBytes) {
        BoundedPcmQueue.Frame frame = pending.frame();
        long latestOutputUnderruns = observedOutputUnderruns;
        if ((frame.length() & 3) != 0 || (pending.writeOffset() & 3) != 0
                || maximumBytes < 0 || (maximumBytes & 3) != 0) {
            return new WriteResult(WriteOutcome.OUTPUT_FAILURE, latestOutputUnderruns);
        }
        int writeBudget = maximumBytes;
        while (pending.writeOffset() < frame.length()) {
            if (writeBudget == 0) {
                return new WriteResult(WriteOutcome.CAPACITY_FILLED, latestOutputUnderruns);
            }
            if (!isPlaybackBoundaryCurrent(expectedPauseFlushGeneration,
                    expectedPcmPolicyGeneration)) {
                return new WriteResult(WriteOutcome.INTERRUPTED, latestOutputUnderruns);
            }
            if (monitorOutputUnderruns) {
                long sampled = sampleOutputUnderruns(output, latestOutputUnderruns);
                if (hasOutputUnderrunIncreased(latestOutputUnderruns, sampled)) {
                    return new WriteResult(WriteOutcome.OUTPUT_UNDERRUN, sampled);
                }
                latestOutputUnderruns = sampled;
            }
            int remaining = Math.min(frame.length() - pending.writeOffset(), writeBudget);
            int written;
            try {
                written = output.write(frame.bytes(), pending.writeOffset(), remaining);
            } catch (RuntimeException unavailable) {
                return new WriteResult(WriteOutcome.OUTPUT_FAILURE, latestOutputUnderruns);
            }
            if (written <= 0 || written > remaining || (written & 3) != 0) {
                return new WriteResult(WriteOutcome.OUTPUT_FAILURE, latestOutputUnderruns);
            }
            pending.advance(written);
            writeBudget -= written;
            outputProgress.accept(written);
            if (monitorOutputUnderruns) {
                long sampled = sampleOutputUnderruns(output, latestOutputUnderruns);
                if (hasOutputUnderrunIncreased(latestOutputUnderruns, sampled)) {
                    return new WriteResult(WriteOutcome.OUTPUT_UNDERRUN, sampled);
                }
                latestOutputUnderruns = sampled;
            }
        }
        if (!isPlaybackBoundaryCurrent(expectedPauseFlushGeneration,
                expectedPcmPolicyGeneration)) {
            return new WriteResult(WriteOutcome.INTERRUPTED, latestOutputUnderruns);
        }
        return new WriteResult(WriteOutcome.COMPLETE, latestOutputUnderruns);
    }

    private boolean isPlaybackBoundaryCurrent(long expectedPauseFlushGeneration,
            long expectedPcmPolicyGeneration) {
        return running.get() && !paused && !reopenRequested.get()
                && pauseFlushGeneration.get() == expectedPauseFlushGeneration
                && pcmPolicyGeneration.get() == expectedPcmPolicyGeneration;
    }

    private PlayOutcome playOutput(Output output, long expectedPauseFlushGeneration,
            long expectedPcmPolicyGeneration, boolean benchmarkEmptyPlay) {
        synchronized (playbackControlLock) {
            if (!isPlaybackBoundaryCurrent(expectedPauseFlushGeneration,
                    expectedPcmPolicyGeneration)
                    || benchmarkEmptyPlayRequested.get() != benchmarkEmptyPlay) {
                return PlayOutcome.STALE_BOUNDARY;
            }
            try {
                output.play();
                return PlayOutcome.STARTED;
            } catch (RuntimeException unavailable) {
                return PlayOutcome.OUTPUT_FAILURE;
            }
        }
    }

    private long sampleOutputUnderruns(Output output, long previous) {
        if (output == null) {
            return previous;
        }
        try {
            long sampled = output.outputUnderrunCount();
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                outputUnderruns = sampled;
            }
            return sampled >= 0L ? sampled : previous;
        } catch (RuntimeException unavailable) {
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                outputUnderruns = -1L;
            }
            return previous;
        }
    }

    private static boolean hasOutputUnderrunIncreased(long previous, long latest) {
        return previous >= 0L && latest > previous;
    }

    /**
     * Starts a fresh proof for the latest cumulative underrun observation. The playback head is
     * deliberately sampled here, after the counter increase has been observed, so progress that
     * preceded the latest underrun cannot satisfy the new recovery.
     */
    private static boolean rebaseUnderrunRecovery(Output output,
            OutputProgress outputProgress, OutputBufferLimits outputBufferLimits,
            UnderrunRecovery underrunRecovery) {
        long playbackHeadFrames = outputProgress.readPlaybackPosition(output);
        long bufferedFrames = outputProgress.bufferedFrames(playbackHeadFrames);
        return underrunRecovery.rebase(playbackHeadFrames, outputProgress.acceptedFrames(),
                bufferedFrames, outputBufferLimits.startThresholdFrames());
    }

    private void requestRecoveryReopen() {
        synchronized (playbackControlLock) {
            benchmarkEmptyPlayRequested.set(false);
            reopenRequested.set(true);
        }
    }

    private static boolean frameMatchesBoundary(BoundedPcmQueue.Frame frame,
            long pauseGeneration, long policyGeneration) {
        return frame.pauseFlushGeneration() == pauseGeneration
                && frame.policyGeneration() == policyGeneration;
    }

    private void reconcileFrameAccounting(BoundedPcmQueue active,
            BoundedPcmQueue.Frame frame) {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        synchronized (pcmAccounting) {
            int delta = active.reconcileAccountingBytes(frame);
            pcmAccounting.adjustEnqueued(delta, delta / 4L);
            accountQueueDiscards(active);
        }
    }

    private void commitCompletedPrimerFrames(BoundedPcmQueue active,
            List<PendingFrame> frames) {
        while (!frames.isEmpty()) {
            PendingFrame pending = frames.get(0);
            if (pending.writeOffset() < pending.frame().length()) {
                return;
            }
            frames.remove(0);
            commitFrame(active, pending.frame());
        }
    }

    private void commitFrame(BoundedPcmQueue active, BoundedPcmQueue.Frame frame) {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            synchronized (pcmAccounting) {
                pcmAccounting.written(frame.length(), frame.length() / 4L);
            }
        }
        active.release(frame);
    }

    private void discardPrimerFrames(BoundedPcmQueue active, List<PendingFrame> frames) {
        for (PendingFrame pending : frames) {
            discardFrame(active, pending.frame());
        }
        frames.clear();
    }

    private static void resetPendingFramesForReplacement(List<PendingFrame> frames) {
        for (PendingFrame frame : frames) {
            frame.resetForReplacement();
        }
    }

    private void discardFrame(BoundedPcmQueue active, BoundedPcmQueue.Frame frame) {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            synchronized (pcmAccounting) {
                pcmAccounting.discarded(frame.accountingBytes());
            }
        }
        active.release(frame);
    }

    private void accountWriteFailure() {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            synchronized (pcmAccounting) {
                pcmAccounting.writeFailure();
            }
        }
    }

    private void accountRouteFailure() {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            synchronized (pcmAccounting) {
                pcmAccounting.routeFailure();
            }
        }
    }

    private boolean resetOutputForPcmBoundary(Output output) {
        boolean reset = true;
        if (output != null) {
            try {
                output.pause();
                output.flush();
            } catch (RuntimeException unavailable) {
                accountRouteFailure();
                requestRecoveryReopen();
                reset = false;
            }
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            outputPlaying = false;
        }
        return reset;
    }

    private static void preferAudioThreadPriority() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException unavailable) {
            // Some OEM policies can reject priority changes. Audio remains functional.
        }
    }

    private static boolean containsOutputSink(AudioDeviceInfo[] devices) {
        if (devices == null) {
            return false;
        }
        for (AudioDeviceInfo device : devices) {
            if (device == null) {
                continue;
            }
            try {
                if (device.isSink()) {
                    return true;
                }
            } catch (RuntimeException unavailable) {
                // A stale framework descriptor must not force an unrelated route restart.
            }
        }
        return false;
    }

    private void clearQueuedPcm() {
        synchronized (queueLock) {
            BoundedPcmQueue active = queue;
            if (active != null) {
                clearQueue(active);
            }
            hasProducedPcm = false;
        }
    }

    /** Called only while playbackControlLock publishes a new pause/policy boundary. */
    private void clearQueuedPcmAtBoundary() {
        if (boundaryClearHook != null) {
            boundaryClearHook.beforeQueuedPcmClear();
        }
        clearQueuedPcm();
    }

    private void clearQueue(BoundedPcmQueue active) {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            clearAndAccount(active);
        } else {
            active.clear();
        }
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

    /** One converted packet retained across a stopped-state replay or underrun refill. */
    private static final class PendingFrame {
        private final BoundedPcmQueue.Frame frame;
        /** Prefix accepted by the current output (including positive short writes). */
        private int writeOffset;

        private PendingFrame(BoundedPcmQueue.Frame frame) {
            this.frame = frame;
        }

        private BoundedPcmQueue.Frame frame() {
            return frame;
        }

        private int writeOffset() {
            return writeOffset;
        }

        private void advance(int bytes) {
            writeOffset = Math.addExact(writeOffset, bytes);
        }

        private void resetForReplacement() {
            writeOffset = 0;
        }
    }

    private record OutputBufferLimits(int capacityFrames, int effectiveBufferFrames,
            int startThresholdFrames) {
        private static final OutputBufferLimits UNAVAILABLE = new OutputBufferLimits(0, 0, 0);
    }

    /** Worker-local state for one or more coalesced in-place AudioTrack threshold refills. */
    static final class UnderrunRecovery {
        private boolean active;
        private long headAtDetectionFrames = -1L;
        private long acceptedFramesAtDetection = -1L;
        private long refillFramesRequired;
        private long capacityFullSinceNanos = -1L;
        private boolean refillThresholdReached;

        boolean rebase(long playbackHeadFrames, long acceptedFrames, long bufferedFrames,
                int startThresholdFrames) {
            if (playbackHeadFrames < 0L || acceptedFrames < 0L || bufferedFrames < 0L
                    || startThresholdFrames <= 0) {
                return false;
            }
            active = true;
            headAtDetectionFrames = playbackHeadFrames;
            acceptedFramesAtDetection = acceptedFrames;
            refillFramesRequired = Math.max(0L, startThresholdFrames - bufferedFrames);
            capacityFullSinceNanos = -1L;
            refillThresholdReached = false;
            return true;
        }

        boolean active() {
            return active;
        }

        boolean hasRestarted(long playbackHeadFrames) {
            return active && refillThresholdReached
                    && playbackHeadFrames > headAtDetectionFrames;
        }

        void observeRefillProgress(long acceptedFrames, long bufferedFrames,
                int startThresholdFrames) {
            if (active && acceptedFrames >= acceptedFramesAtDetection
                    && acceptedFrames - acceptedFramesAtDetection >= refillFramesRequired
                    && bufferedFrames >= startThresholdFrames) {
                refillThresholdReached = true;
            }
        }

        boolean capacityHasStalled(long nowNanos) {
            if (!refillThresholdReached) {
                return false;
            }
            if (capacityFullSinceNanos < 0L) {
                capacityFullSinceNanos = nowNanos;
                return false;
            }
            return nowNanos - capacityFullSinceNanos
                    >= TimeUnit.MILLISECONDS.toNanos(UNDERRUN_STALL_MILLIS);
        }

        void clearCapacityStall() {
            capacityFullSinceNanos = -1L;
        }

        void reset() {
            active = false;
            headAtDetectionFrames = -1L;
            acceptedFramesAtDetection = -1L;
            refillFramesRequired = 0L;
            capacityFullSinceNanos = -1L;
            refillThresholdReached = false;
        }
    }

    private record WriteResult(WriteOutcome outcome, long observedOutputUnderruns) {
    }

    private enum PlayOutcome {
        STARTED,
        STALE_BOUNDARY,
        OUTPUT_FAILURE
    }

    /**
     * Per-output accepted-byte ledger and unsigned 32-bit playback-head extension.
     * The epoch is reset after every flush/reopen, matching AudioTrack's head semantics.
     */
    static final class OutputProgress {
        private static final long UNSIGNED_INT_RANGE = 1L << 32;
        private static final long UNSIGNED_INT_MASK = UNSIGNED_INT_RANGE - 1L;
        private static final long WRAP_THRESHOLD = 1L << 31;

        private long previousExtendedPosition = -1L;
        private long playbackBaseFrames = -1L;
        private long acceptedBytes;

        void open(Output output) {
            previousExtendedPosition = -1L;
            playbackBaseFrames = -1L;
            acceptedBytes = 0L;
            long initialPosition = readPlaybackPosition(output);
            if (initialPosition >= 0L) {
                playbackBaseFrames = initialPosition;
            }
        }

        void resetAfterFlush(Output output) {
            open(output);
        }

        void accept(int bytes) {
            acceptedBytes = Math.addExact(acceptedBytes, bytes);
        }

        long acceptedFrames() {
            return (acceptedBytes & 3L) == 0L ? acceptedBytes / 4L : -1L;
        }

        long readPlaybackPosition(Output output) {
            if (output == null) {
                return -1L;
            }
            long raw;
            try {
                raw = output.playbackPositionFrames();
            } catch (RuntimeException unavailable) {
                return -1L;
            }
            long extended = extendUnsignedPlaybackPosition(previousExtendedPosition, raw);
            if (extended >= 0L) {
                previousExtendedPosition = extended;
                if (playbackBaseFrames < 0L) {
                    // A new/just-flushed streaming AudioTrack starts at frame zero. Some routes
                    // expose the head only after play(), so make that documented epoch explicit.
                    playbackBaseFrames = 0L;
                }
            }
            return extended;
        }

        long bufferedFrames(long playedFrames) {
            if (playbackBaseFrames < 0L || playedFrames < 0L) {
                return -1L;
            }
            if ((acceptedBytes & 3L) != 0L) {
                return -1L;
            }
            long playedSinceOpen = playedFrames - playbackBaseFrames;
            if (playedSinceOpen < 0L) {
                return -1L;
            }
            long acceptedFrames = acceptedBytes / 4L;
            return Math.max(0L, acceptedFrames - playedSinceOpen);
        }

        static long extendUnsignedPlaybackPosition(long previousExtended, long currentRaw) {
            if (currentRaw < 0L) {
                return -1L;
            }
            // Test outputs may already expose a wider monotonic head. Android's implementation
            // supplies the documented unsigned 32-bit value.
            if (currentRaw > UNSIGNED_INT_MASK) {
                return previousExtended < 0L || currentRaw >= previousExtended
                        ? currentRaw : -1L;
            }
            long normalized = currentRaw & UNSIGNED_INT_MASK;
            if (previousExtended < 0L) {
                return normalized;
            }
            long previousRaw = previousExtended & UNSIGNED_INT_MASK;
            long epoch = previousExtended - previousRaw;
            if (normalized < previousRaw) {
                if (previousRaw - normalized <= WRAP_THRESHOLD) {
                    // A small regression within one flush epoch is not a trustworthy drain head.
                    return -1L;
                }
                epoch = Math.addExact(epoch, UNSIGNED_INT_RANGE);
            }
            return Math.addExact(epoch, normalized);
        }
    }

    private enum WriteOutcome {
        COMPLETE,
        CAPACITY_FILLED,
        INTERRUPTED,
        OUTPUT_UNDERRUN,
        OUTPUT_FAILURE
    }
}
