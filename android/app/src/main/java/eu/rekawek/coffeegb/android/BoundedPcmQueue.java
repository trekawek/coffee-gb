package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.sound.StereoPcmConverter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-storage hand-off from the emulation event thread to one host-audio consumer.
 *
 * <p>Every slot and PCM byte array is allocated at construction. A producer that falls behind
 * discards the oldest queued host frame, never waits for the consumer and never grows latency.
 */
final class BoundedPcmQueue {

    static final int DEFAULT_CAPACITY = 6;

    private final StereoPcmConverter converter;
    private final ArrayBlockingQueue<Frame> available;
    private final ArrayBlockingQueue<Frame> queued;
    private final int capacity;
    private final int frameBytes;
    private final AtomicLong overruns = new AtomicLong();
    /** Benchmark-only discard ledger; release keeps the pre-existing overrun counter only. */
    private final DiscardAccounting discardAccounting = BuildConfig.DIAGNOSTICS_ENABLED
            ? new DiagnosticDiscardAccounting() : NoOpDiscardAccounting.INSTANCE;

    BoundedPcmQueue(int sampleRate) {
        this(sampleRate, DEFAULT_CAPACITY, maximumFrameBytes(sampleRate));
    }

    BoundedPcmQueue(int sampleRate, int capacity, int frameBytes) {
        if (capacity < 2) {
            throw new IllegalArgumentException("PCM queue needs at least two frames");
        }
        if (frameBytes <= 0) {
            throw new IllegalArgumentException("PCM frames must have positive capacity");
        }
        converter = new StereoPcmConverter(sampleRate);
        this.capacity = capacity;
        this.frameBytes = frameBytes;
        available = new ArrayBlockingQueue<>(capacity);
        queued = new ArrayBlockingQueue<>(capacity);
        for (int index = 0; index < capacity; index++) {
            available.add(new Frame(new byte[frameBytes]));
        }
    }

    /** Called synchronously by the emulation event bus; it never waits for host audio. */
    int offer(Sound.SoundSampleEvent event, int volume, boolean muted) {
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
                discardAccounting.add(frame.length);
            }
        }
        try {
            frame.length = converter.render(event.buffer(), event.clockSpec(), volume, muted,
                    frame.bytes);
            if (!queued.offer(frame)) {
                throw new IllegalStateException("PCM queue lost its reserved frame slot");
            }
            return frame.length;
        } catch (RuntimeException failure) {
            frame.length = 0;
            available.offer(frame);
            throw failure;
        }
    }

    Frame poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queued.poll(timeout, unit);
    }

    void release(Frame frame) {
        if (frame == null) {
            return;
        }
        frame.length = 0;
        if (!available.offer(frame)) {
            throw new IllegalStateException("PCM queue released a frame twice");
        }
    }

    void clear() {
        Frame frame;
        while ((frame = queued.poll()) != null) {
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                discardAccounting.add(frame.length);
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

    long queuedBytes() {
        long bytes = 0L;
        for (Frame frame : queued) {
            bytes += frame.length;
        }
        return bytes;
    }

    long drainDiscardedBytes() {
        return discardAccounting.drain();
    }

    long samplePhase() {
        return converter.samplePhase();
    }

    private static int maximumFrameBytes(int sampleRate) {
        long maximum = 0;
        for (ClockSpec clock : new ClockSpec[]{ClockSpec.LEGACY, ClockSpec.SGB, ClockSpec.SGB2}) {
            maximum = Math.max(maximum, clock.maximumOutputUnits(
                    clock.controllerTicksPerFrame(), sampleRate));
        }
        return Math.toIntExact(Math.multiplyExact(maximum, 4));
    }

    static final class Frame {
        private final byte[] bytes;
        private int length;

        private Frame(byte[] bytes) {
            this.bytes = bytes;
        }

        byte[] bytes() {
            return bytes;
        }

        int length() {
            return length;
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
