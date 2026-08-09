package eu.rekawek.coffeegb.swing.io;

import java.util.function.LongSupplier;

/** Low-frequency meter for frames actually accepted by SwingDisplay's presentation thread. */
final class PresentationFrameRateMeter {

    static final long SAMPLE_WINDOW_NANOS = 1_000_000_000L;

    private final LongSupplier nanoTime;

    private long sampleStartedAt;

    private long acceptedFrames;

    PresentationFrameRateMeter() {
        this(System::nanoTime);
    }

    PresentationFrameRateMeter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        sampleStartedAt = nanoTime.getAsLong();
    }

    /**
     * Records one frame after coalescing and publication. Returns NaN until the next sample is
     * due, avoiding a desktop/EDT update for every emulator frame.
     */
    synchronized double framePublished() {
        acceptedFrames++;
        long now = nanoTime.getAsLong();
        long elapsed = now - sampleStartedAt;
        if (elapsed < SAMPLE_WINDOW_NANOS) {
            return Double.NaN;
        }
        double framesPerSecond = acceptedFrames * 1_000_000_000.0 / elapsed;
        sampleStartedAt = now;
        acceptedFrames = 0;
        return framesPerSecond;
    }

    synchronized void reset() {
        sampleStartedAt = nanoTime.getAsLong();
        acceptedFrames = 0;
    }
}
