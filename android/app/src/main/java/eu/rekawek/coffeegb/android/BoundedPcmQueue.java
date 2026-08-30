package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.sound.StereoPcmConverter;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-storage hand-off from the emulation event thread to one host-audio consumer.
 *
 * <p>Every slot and PCM byte array is allocated at construction. A producer that falls behind
 * discards the oldest queued host frame, never waits for the consumer and never grows latency.
 */
final class BoundedPcmQueue {

    static final int DEFAULT_CAPACITY = 6;
    private static final ClockSpec[] SUPPORTED_CLOCKS = {
            ClockSpec.LEGACY, ClockSpec.SGB, ClockSpec.SGB2
    };

    private final StereoPcmConverter converter;
    private final ArrayBlockingQueue<Frame> available;
    private final ArrayBlockingQueue<Frame> queued;
    private final int capacity;
    private final int frameBytes;
    private final int sourceSamples;
    private final NanoClock nanoClock;
    private final TimingProbe timingProbe;
    private final AtomicInteger workerPhase;
    private final AtomicLong overruns = new AtomicLong();
    /** Benchmark-only discard ledger; release keeps the pre-existing overrun counter only. */
    private final DiscardAccounting discardAccounting = BuildConfig.DIAGNOSTICS_ENABLED
            ? new DiagnosticDiscardAccounting() : NoOpDiscardAccounting.INSTANCE;

    BoundedPcmQueue(int sampleRate) {
        this(sampleRate, DEFAULT_CAPACITY, maximumFrameBytes(sampleRate), ClockSpec.LEGACY);
    }

    BoundedPcmQueue(int sampleRate, int capacity, int frameBytes) {
        this(sampleRate, capacity, frameBytes, ClockSpec.LEGACY);
    }

    BoundedPcmQueue(int sampleRate, ClockSpec sourceClock) {
        this(sampleRate, DEFAULT_CAPACITY, maximumFrameBytes(sampleRate), sourceClock);
    }

    BoundedPcmQueue(int sampleRate, int capacity, int frameBytes, ClockSpec sourceClock) {
        this(sampleRate, capacity, frameBytes, sourceClock, NanoClock.SYSTEM,
                NoOpTimingProbe.INSTANCE, null);
    }

    BoundedPcmQueue(int sampleRate, int capacity, int frameBytes, ClockSpec sourceClock,
            NanoClock nanoClock, TimingProbe timingProbe, AtomicInteger workerPhase) {
        if (capacity < 2) {
            throw new IllegalArgumentException("PCM queue needs at least two frames");
        }
        if (frameBytes <= 0) {
            throw new IllegalArgumentException("PCM frames must have positive capacity");
        }
        if (sourceClock == null) {
            throw new NullPointerException("sourceClock");
        }
        converter = new StereoPcmConverter(sampleRate);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.timingProbe = Objects.requireNonNull(timingProbe, "timingProbe");
        this.workerPhase = workerPhase;
        this.capacity = capacity;
        this.frameBytes = frameBytes;
        // HardwareProfile can arrive immediately before its first PCM event.  Every reserved
        // source slot therefore covers the largest supported controller packet; changing the
        // source cadence never has to rebuild the queue or race that first SGB-family packet.
        sourceSamples = maximumSourceSamplesAcrossProfiles();
        available = new ArrayBlockingQueue<>(capacity);
        queued = new ArrayBlockingQueue<>(capacity);
        for (int index = 0; index < capacity; index++) {
            available.add(new Frame(new byte[frameBytes], new int[sourceSamples]));
        }
    }

    /**
     * Called synchronously by the emulation event bus; it never waits for host audio or runs the
     * resampler. The source copy is fixed-storage and lets the consumer own converter state and
     * ordering without retaining the core's reusable event buffer.
     */
    int offer(Sound.SoundSampleEvent event, int volume, boolean muted) {
        return offer(event, volume, muted, 0L, 0L);
    }

    int offer(Sound.SoundSampleEvent event, int volume, boolean muted, long policyGeneration) {
        return offer(event, volume, muted, policyGeneration, 0L);
    }

    int offer(Sound.SoundSampleEvent event, int volume, boolean muted, long policyGeneration,
            long pauseFlushGeneration) {
        return offer(event, volume, muted, policyGeneration, pauseFlushGeneration,
                -1L, 0L);
    }

    int offer(Sound.SoundSampleEvent event, int volume, boolean muted, long policyGeneration,
            long pauseFlushGeneration, long eventNanos, long timingGeneration) {
        Frame frame = available.poll();
        if (frame == null) {
            frame = queued.poll();
            if (frame == null) {
                // One consumer can own at most one frame, so this is a defensive last resort.
                overruns.incrementAndGet();
                return 0;
            }
            overruns.incrementAndGet();
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                discardAccounting.add(frame.accountingBytes);
            }
            frame.accountingBytes = 0;
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            frame.resetTiming();
        }
        try {
            if (event.buffer().length > frame.source.length) {
                // Fixed storage covers every supported production profile. Keep a fail-closed
                // guard for malformed/custom events rather than allocate on the controller.
                frame.clearSource();
                frame.accountingBytes = 0;
                frame.policyGeneration = 0L;
                frame.pauseFlushGeneration = 0L;
                if (BuildConfig.DIAGNOSTICS_ENABLED) {
                    frame.resetTiming();
                }
                available.offer(frame);
                overruns.incrementAndGet();
                return 0;
            }
            System.arraycopy(event.buffer(), 0, frame.source, 0, event.buffer().length);
            frame.sourceLength = event.buffer().length;
            frame.clockSpec = event.clockSpec();
            frame.volume = volume;
            frame.muted = muted;
            frame.policyGeneration = policyGeneration;
            frame.pauseFlushGeneration = pauseFlushGeneration;
            frame.accountingBytes = converter.maximumPcmBytes(
                    event.buffer().length / 2, event.clockSpec());
            frame.length = 0;
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                frame.timingGeneration = timingGeneration;
                frame.eventNanos = eventNanos;
                frame.publishNanos = nanoClock.nanoTime();
                frame.publicationWorkerPhase = workerPhase == null
                        ? AndroidAudioSink.WORKER_PHASE_CONTROL_OR_RECOVERY : workerPhase.get();
                // Account before making the pooled slot visible to the consumer; otherwise a
                // very fast dequeue/release could reset its metadata before this producer reads it.
                timingProbe.published(frame, event.clockSpec(), event.buffer().length / 2);
            }
            if (!queued.offer(frame)) {
                throw new IllegalStateException("PCM queue lost its reserved frame slot");
            }
            // Rendering happens in poll() on the dedicated audio worker. Return the maximum
            // host-frame size for diagnostics; poll() reconciles it with the exact output.
            return frame.accountingBytes;
        } catch (RuntimeException failure) {
            frame.clearSource();
            frame.accountingBytes = 0;
            frame.policyGeneration = 0L;
            frame.pauseFlushGeneration = 0L;
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                frame.resetTiming();
            }
            available.offer(frame);
            throw failure;
        }
    }

    Frame poll(long timeout, TimeUnit unit) throws InterruptedException {
        Frame frame;
        if (BuildConfig.DIAGNOSTICS_ENABLED && workerPhase != null) {
            workerPhase.set(AndroidAudioSink.WORKER_PHASE_POLL);
            try {
                frame = queued.poll(timeout, unit);
            } finally {
                workerPhase.set(AndroidAudioSink.WORKER_PHASE_CONTROL_OR_RECOVERY);
            }
        } else {
            frame = queued.poll(timeout, unit);
        }
        if (frame == null) {
            return null;
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            frame.dequeueNanos = nanoClock.nanoTime();
        }
        try {
            frame.length = converter.render(frame.source, frame.sourceLength, frame.clockSpec,
                    frame.volume, frame.muted, frame.bytes);
            frame.clearSource();
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                frame.pcmReadyNanos = nanoClock.nanoTime();
                timingProbe.ready(frame);
            }
            return frame;
        } catch (RuntimeException failure) {
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                discardAccounting.add(frame.accountingBytes);
            }
            frame.clearSource();
            frame.length = 0;
            frame.accountingBytes = 0;
            frame.policyGeneration = 0L;
            frame.pauseFlushGeneration = 0L;
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                frame.resetTiming();
            }
            available.offer(frame);
            throw failure;
        }
    }

    void release(Frame frame) {
        if (frame == null) {
            return;
        }
        frame.length = 0;
        frame.clearSource();
        frame.accountingBytes = 0;
        frame.policyGeneration = 0L;
        frame.pauseFlushGeneration = 0L;
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            frame.resetTiming();
        }
        if (!available.offer(frame)) {
            throw new IllegalStateException("PCM queue released a frame twice");
        }
    }

    void clear() {
        Frame frame;
        while ((frame = queued.poll()) != null) {
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                discardAccounting.add(frame.accountingBytes);
            }
            release(frame);
        }
    }

    long overruns() {
        return overruns.get();
    }

    int queuedFrames() {
        return queued.size();
    }

    int capacityFrames() {
        return capacity;
    }

    int maximumFrameBytes() {
        return frameBytes;
    }

    int maximumSourceSamples() {
        return sourceSamples;
    }

    long queuedBytes() {
        long bytes = 0L;
        for (Frame frame : queued) {
            bytes += frame.accountingBytes;
        }
        return bytes;
    }

    long drainDiscardedBytes() {
        return discardAccounting.drain();
    }

    long samplePhase() {
        return converter.samplePhase();
    }

    static int maximumFrameBytes(int sampleRate) {
        long maximum = 0;
        for (ClockSpec clock : SUPPORTED_CLOCKS) {
            maximum = Math.max(maximum, clock.maximumOutputUnits(
                    clock.controllerTicksPerFrame(), sampleRate));
        }
        return Math.toIntExact(Math.multiplyExact(maximum, 4));
    }

    /** Lowest PCM-frame total for this many packets, including a profile reset per packet. */
    static int minimumOutputFramesForPackets(int sampleRate, int packets) {
        if (sampleRate <= 0 || packets <= 0) {
            throw new IllegalArgumentException("Sample rate and packet count must be positive");
        }
        long minimumPacket = Long.MAX_VALUE;
        for (ClockSpec clock : SUPPORTED_CLOCKS) {
            long frames = clock.newTickRateAccumulator(sampleRate)
                    .advance(clock.controllerTicksPerFrame());
            minimumPacket = Math.min(minimumPacket, frames);
        }
        return Math.toIntExact(Math.multiplyExact(minimumPacket, packets));
    }

    /** Highest PCM-frame total for this many packets, including arbitrary profile/phase changes. */
    static int maximumOutputFramesForPackets(int sampleRate, int packets) {
        if (sampleRate <= 0 || packets <= 0) {
            throw new IllegalArgumentException("Sample rate and packet count must be positive");
        }
        long maximumPacket = 0L;
        for (ClockSpec clock : SUPPORTED_CLOCKS) {
            maximumPacket = Math.max(maximumPacket, clock.maximumOutputUnits(
                    clock.controllerTicksPerFrame(), sampleRate));
        }
        return Math.toIntExact(Math.multiplyExact(maximumPacket, packets));
    }

    private static int maximumSourceSamplesAcrossProfiles() {
        int maximum = 0;
        for (ClockSpec clock : SUPPORTED_CLOCKS) {
            maximum = Math.max(maximum,
                    Math.multiplyExact(clock.controllerTicksPerFrame(), 2));
        }
        return maximum;
    }

    /** Reconciles the producer's maximum estimate with the converter's exact packet length. */
    int reconcileAccountingBytes(Frame frame) {
        int previous = frame.accountingBytes;
        frame.accountingBytes = frame.length;
        return frame.length - previous;
    }

    static final class Frame {
        private final byte[] bytes;
        private final int[] source;
        private int length;
        private int sourceLength;
        private ClockSpec clockSpec;
        private int volume;
        private boolean muted;
        private long policyGeneration;
        private long pauseFlushGeneration;
        private int accountingBytes;
        private long timingGeneration;
        private long eventNanos = -1L;
        private long publishNanos = -1L;
        private long dequeueNanos = -1L;
        private long pcmReadyNanos = -1L;
        private long firstWriteNanos = -1L;
        private long writeCompleteNanos = -1L;
        private int publicationWorkerPhase = AndroidAudioSink.WORKER_PHASE_CONTROL_OR_RECOVERY;

        private Frame(byte[] bytes, int[] source) {
            this.bytes = bytes;
            this.source = source;
        }

        byte[] bytes() {
            return bytes;
        }

        int length() {
            return length;
        }

        int accountingBytes() {
            return accountingBytes;
        }

        long policyGeneration() {
            return policyGeneration;
        }

        long pauseFlushGeneration() {
            return pauseFlushGeneration;
        }

        long timingGeneration() {
            return timingGeneration;
        }

        long eventNanos() {
            return eventNanos;
        }

        long publishNanos() {
            return publishNanos;
        }

        long dequeueNanos() {
            return dequeueNanos;
        }

        long pcmReadyNanos() {
            return pcmReadyNanos;
        }

        long firstWriteNanos() {
            return firstWriteNanos;
        }

        void setFirstWriteNanos(long firstWriteNanos) {
            this.firstWriteNanos = firstWriteNanos;
        }

        long writeCompleteNanos() {
            return writeCompleteNanos;
        }

        void setWriteCompleteNanos(long writeCompleteNanos) {
            this.writeCompleteNanos = writeCompleteNanos;
        }

        int publicationWorkerPhase() {
            return publicationWorkerPhase;
        }

        private void clearSource() {
            sourceLength = 0;
            clockSpec = null;
            volume = 0;
            muted = false;
        }

        private void resetTiming() {
            timingGeneration = 0L;
            eventNanos = -1L;
            publishNanos = -1L;
            dequeueNanos = -1L;
            pcmReadyNanos = -1L;
            firstWriteNanos = -1L;
            writeCompleteNanos = -1L;
            publicationWorkerPhase = AndroidAudioSink.WORKER_PHASE_CONTROL_OR_RECOVERY;
        }
    }

    interface TimingProbe {
        void published(Frame frame, ClockSpec sourceClock, int sourceTicks);

        void ready(Frame frame);
    }

    private enum NoOpTimingProbe implements TimingProbe {
        INSTANCE;

        @Override
        public void published(Frame frame, ClockSpec sourceClock, int sourceTicks) {
        }

        @Override
        public void ready(Frame frame) {
        }
    }

    private interface DiscardAccounting {
        void add(long bytes);

        long drain();
    }

    private static final class NoOpDiscardAccounting implements DiscardAccounting {
        private static final NoOpDiscardAccounting INSTANCE = new NoOpDiscardAccounting();

        @Override
        public void add(long bytes) {
        }

        @Override
        public long drain() {
            return 0L;
        }
    }

    private static final class DiagnosticDiscardAccounting implements DiscardAccounting {
        private long bytes;

        @Override
        public void add(long value) {
            bytes += value;
        }

        @Override
        public long drain() {
            long value = bytes;
            bytes = 0L;
            return value;
        }
    }
}
